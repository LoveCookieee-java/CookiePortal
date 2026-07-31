package dev.khoa.plugin.cookieportal.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Cross-version platform compatibility utility for Paper/Spigot APIs including multi-block changes,
 * asynchronous chunk loading, and async teleportation.
 */
public final class PlatformCompatibility {

    private static final boolean HAS_MULTI_BLOCK_CHANGE = hasMethod(Player.class, "sendMultiBlockChange", Map.class);
    private static final boolean HAS_ASYNC_CHUNK_LOAD = hasMethod(World.class, "getChunkAtAsync", Integer.TYPE, Integer.TYPE, Boolean.TYPE);
    private static final boolean HAS_ASYNC_TELEPORT = hasMethod(Player.class, "teleportAsync", Location.class);

    private PlatformCompatibility() {}

    public static void sendBlockChanges(Player player, Map<Location, BlockData> changes) {
        if (changes == null || changes.isEmpty() || player == null || !player.isOnline()) {
            return;
        }

        if (HAS_MULTI_BLOCK_CHANGE) {
            try {
                player.sendMultiBlockChange(changes);
                return;
            } catch (NoSuchMethodError ignored) {}
        }

        List<BlockState> states = new ArrayList<>(changes.size());
        for (Map.Entry<Location, BlockData> entry : changes.entrySet()) {
            BlockState state = entry.getValue().createBlockState().copy(entry.getKey());
            state.setBlockData(entry.getValue());
            states.add(state);
        }

        player.sendBlockChanges(states);
    }

    public static CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ) {
        if (HAS_ASYNC_CHUNK_LOAD) {
            try {
                return world.getChunkAtAsync(chunkX, chunkZ, true);
            } catch (NoSuchMethodError ignored) {}
        }

        try {
            return CompletableFuture.completedFuture(world.getChunkAt(chunkX, chunkZ));
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    public static CompletableFuture<Boolean> teleport(Player player, Location target) {
        if (HAS_ASYNC_TELEPORT) {
            try {
                return player.teleportAsync(target);
            } catch (NoSuchMethodError ignored) {}
        }

        try {
            return CompletableFuture.completedFuture(player.teleport(target));
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
