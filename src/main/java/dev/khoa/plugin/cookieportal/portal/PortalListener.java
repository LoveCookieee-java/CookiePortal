package dev.khoa.plugin.cookieportal.portal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;

public final class PortalListener implements Listener {
   private final CookiePortalPlugin plugin;
   private final PortalRegistry registry;
   private final PortalSizeMatcher sizeMatcher;
   private final Map<UUID, PortalPlane> pendingSizes = new ConcurrentHashMap();
   private static final long[] DESTINATION_RETRY_DELAYS = new long[]{1L, 4L, 10L, 20L, 40L};

   public PortalListener(CookiePortalPlugin plugin, PortalRegistry registry) {
      this.plugin = plugin;
      this.registry = registry;
      this.sizeMatcher = new PortalSizeMatcher(plugin);
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onCreate(PortalCreateEvent event) {
      Block seed = event.getBlocks().stream().filter((state) -> state.getType() == Material.NETHER_PORTAL).map((state) -> state.getBlock()).findFirst().orElse(null);
      if (seed != null) {
         Location location = seed.getLocation();
         this.plugin.scheduler().runAtLater(location, () -> {
            PortalPlane portal = PortalPlane.discover(seed);
            if (portal != null) {
               this.registry.register(seed);
               this.animate(portal);
               this.plugin.renderer().refreshNear(portal);
               this.plugin.scheduler().runAtLater(location, () -> this.plugin.renderer().refreshNear(portal), 2L);
               this.plugin.scheduler().runAtLater(location, () -> this.plugin.renderer().refreshNear(portal), 7L);
            }
         }, 1L);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onTravel(PlayerPortalEvent event) {
      Block source = this.findPortal(event.getFrom(), 5);
      if (source != null) {
         this.registry.register(source);
      }

      PortalPlane sourcePlane = source == null ? null : PortalPlane.discover(source);
      if (sourcePlane != null) {
         this.pendingSizes.put(event.getPlayer().getUniqueId(), sourcePlane);
      }

      Location vanillaDestination = event.getTo();
      if (vanillaDestination != null) {
         if (sourcePlane != null) {
            this.scheduleDestinationRetries(event.getPlayer().getUniqueId(), sourcePlane, vanillaDestination.clone(), 6);
         } else {
            this.plugin.scheduler().runAtLater(vanillaDestination, () -> {
               Block destination = this.findPortal(vanillaDestination, 6);
               if (destination != null) {
                  this.registry.register(destination);
                  PortalPlane plane = PortalPlane.discover(destination);
                  if (plane != null) {
                     this.plugin.renderer().refreshNear(plane);
                  }

               }
            }, 3L);
         }

      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onChangedWorld(PlayerChangedWorldEvent event) {
      for(long delay : new long[]{1L, 4L, 10L, 20L, 40L}) {
         this.plugin.scheduler().runForPlayerLater(event.getPlayer(), () -> this.plugin.renderer().refreshImmediately(event.getPlayer()), delay);
      }

      UUID playerId = event.getPlayer().getUniqueId();
      PortalPlane source = (PortalPlane)this.pendingSizes.get(playerId);
      if (source != null) {
         Location arrival = event.getPlayer().getLocation().clone();
         this.scheduleDestinationRetries(playerId, source, arrival, 8);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.pendingSizes.remove(event.getPlayer().getUniqueId());
      this.plugin.renderer().clear(event.getPlayer());
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent event) {
      Material type = event.getBlock().getType();
      if (type == Material.OBSIDIAN || type == Material.NETHER_PORTAL) {
         Location broken = event.getBlock().getLocation();
         if (this.plugin.scheduler().isFolia()) {
            if (this.registry.removeNear(broken)) {
               this.plugin.renderer().refreshAllLater(1L);
            }
         } else {
            this.plugin.scheduler().runAtLater(broken, () -> {
               if (this.registry.removeInvalid()) {
                  this.plugin.renderer().refreshAllLater(1L);
               }

            }, 1L);
         }

      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onEntityExplode(EntityExplodeEvent event) {
      this.invalidateExplodedPortals(event.blockList());
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBlockExplode(BlockExplodeEvent event) {
      this.invalidateExplodedPortals(event.blockList());
   }

   @EventHandler
   public void onChunk(ChunkLoadEvent event) {
      this.registry.scan(event.getChunk());
   }

   private void invalidateExplodedPortals(List<Block> affectedBlocks) {
      boolean removed = false;

      for(Block block : affectedBlocks) {
         Material type = block.getType();
         if (type == Material.OBSIDIAN || type == Material.NETHER_PORTAL) {
            removed |= this.registry.removeNear(block.getLocation());
         }
      }

      if (removed) {
         this.plugin.renderer().refreshAllLater(1L);
      }

   }

   private Block findPortal(Location center, int radius) {
      if (center != null && center.getWorld() != null) {
         int baseX = center.getBlockX();
         int baseY = center.getBlockY();
         int baseZ = center.getBlockZ();

         for(int y = -radius; y <= radius; ++y) {
            for(int x = -radius; x <= radius; ++x) {
               for(int z = -radius; z <= radius; ++z) {
                  Block block = center.getWorld().getBlockAt(baseX + x, baseY + y, baseZ + z);
                  if (block.getType() == Material.NETHER_PORTAL) {
                     return block;
                  }
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private void scheduleDestinationRetries(UUID playerId, PortalPlane source, Location destinationHint, int radius) {
      if (source != null && destinationHint != null && destinationHint.getWorld() != null) {
         for(long delay : DESTINATION_RETRY_DELAYS) {
            Location regionalAnchor = destinationHint.clone();
            this.plugin.scheduler().runAtLater(regionalAnchor, () -> {
               if (source.equals(this.pendingSizes.get(playerId))) {
                  Block destination = this.findPortal(regionalAnchor, radius);
                  if (destination != null) {
                     this.registry.register(destination);
                     PortalPlane destinationPlane = PortalPlane.discover(destination);
                     if (destinationPlane != null && this.sizeMatcher.matchAndLink(source, destinationPlane)) {
                        this.pendingSizes.remove(playerId, source);
                     }

                  }
               }
            }, delay);
         }

      }
   }

   private void animate(PortalPlane plane) {
      if (this.plugin.settings().animation() && plane.world() != null) {
         plane.world().playSound(plane.center(), Sound.BLOCK_PORTAL_TRIGGER, 0.65F, 0.8F);
         int duration = this.plugin.settings().animationTicks();

         for(int tick = 0; tick < duration; tick += 2) {
            final int fTick = tick;
            this.plugin.scheduler().runAtLater(plane.center(), () -> {
               if (plane.stillExists() && plane.world() != null) {
                  double progress = ((double)fTick + (double)1.0F) / (double)duration;
                  double y = (double)plane.minY() + progress * (double)(plane.maxY() - plane.minY() + 1);
                  plane.world().spawnParticle(Particle.PORTAL, new Location(plane.world(), (double)(plane.minX() + plane.maxX() + 1) / (double)2.0F, y, (double)(plane.minZ() + plane.maxZ() + 1) / (double)2.0F), 10, 0.7, 0.12, 0.7, 0.12);
               }
            }, (long)tick + 1L);
         }

      }
   }
}
