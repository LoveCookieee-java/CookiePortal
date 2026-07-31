package dev.khoa.plugin.cookieportal.render;

import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Listens for block break, damage, place, bucket, and interaction events targeting fake projected blocks.
 * Reasserts fake block data to prevent client-side block desynchronization when players attempt to interact
 * with portal projections.
 */
public final class ProjectedBlockProtectionListener implements Listener {

    private final CookiePortalPlugin plugin;

    public ProjectedBlockProtectionListener(CookiePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        this.protect(event.getPlayer(), event.getBlock(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        this.protect(event.getPlayer(), event.getBlock(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked != null) {
                BlockData projected = this.projectedData(event.getPlayer(), clicked);
                if (projected != null) {
                    event.setCancelled(true);
                    this.reassert(event.getPlayer(), clicked.getLocation(), projected);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        BlockData placedProjection = this.projectedData(event.getPlayer(), event.getBlockPlaced());
        BlockData againstProjection = this.projectedData(event.getPlayer(), event.getBlockAgainst());
        if (placedProjection != null || againstProjection != null) {
            event.setCancelled(true);
            Block protectedBlock = placedProjection != null ? event.getBlockPlaced() : event.getBlockAgainst();
            BlockData targetData = placedProjection != null ? placedProjection : againstProjection;
            this.reassert(event.getPlayer(), protectedBlock.getLocation(), targetData);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        this.protect(event.getPlayer(), event.getBlockClicked(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        this.protect(event.getPlayer(), event.getBlockClicked(), event::setCancelled);
    }

    private void protect(Player player, Block block, Consumer<Boolean> cancellation) {
        if (player == null || block == null) {
            return;
        }
        BlockData projected = this.projectedData(player, block);
        if (projected != null) {
            cancellation.accept(true);
            this.reassert(player, block.getLocation(), projected);
        }
    }

    private BlockData projectedData(Player player, Block block) {
        BlockData projected = this.plugin.renderer().projectedBlockData(player, block);
        return projected != null ? projected : this.plugin.endPortal().projectedBlockData(player, block);
    }

    private void reassert(Player player, Location location, BlockData data) {
        player.sendBlockChange(location, data);
        if (this.plugin.isEnabled()) {
            this.plugin.scheduler().runForPlayerLater(player, () -> {
                if (player.isOnline()) {
                    Block block = location.getBlock();
                    BlockData current = this.projectedData(player, block);
                    if (current != null) {
                        player.sendBlockChange(location, current);
                    }
                }
            }, 1L);
        }
    }
}
