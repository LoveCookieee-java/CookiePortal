package dev.khoa.plugin.cookieportal.platform;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Unified task scheduler interface providing seamless execution across both standard Bukkit
 * and multi-threaded Folia server platforms.
 */
public interface PortalScheduler {

    static PortalScheduler create(JavaPlugin plugin) {
        return FoliaPortalScheduler.isAvailable() 
            ? new FoliaPortalScheduler(plugin) 
            : new BukkitPortalScheduler(plugin);
    }

    Task runGlobal(Runnable runnable);

    Task runGlobalLater(Runnable runnable, long delayTicks);

    Task runGlobalTimer(Runnable runnable, long initialDelayTicks, long periodTicks);

    Task runForPlayer(Player player, Runnable runnable);

    Task runForPlayerLater(Player player, Runnable runnable, long delayTicks);

    Task runForEntity(Entity entity, Runnable runnable);

    Task runAt(Location location, Runnable runnable);

    Task runAtLater(Location location, Runnable runnable, long delayTicks);

    void cancelAll();

    boolean isFolia();

    interface Task {
        void cancel();
        boolean cancelled();
    }
}
