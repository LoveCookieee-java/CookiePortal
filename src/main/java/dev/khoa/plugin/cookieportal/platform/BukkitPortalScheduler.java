package dev.khoa.plugin.cookieportal.platform;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class BukkitPortalScheduler implements PortalScheduler {
   private final JavaPlugin plugin;
   private final Set<BukkitTaskHandle> tasks = ConcurrentHashMap.newKeySet();

   public BukkitPortalScheduler(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   public PortalScheduler.Task runGlobal(Runnable action) {
      return this.track(Bukkit.getScheduler().runTask(this.plugin, action));
   }

   public PortalScheduler.Task runGlobalLater(Runnable action, long delayTicks) {
      return this.track(Bukkit.getScheduler().runTaskLater(this.plugin, action, Math.max(0L, delayTicks)));
   }

   public PortalScheduler.Task runGlobalTimer(Runnable action, long delayTicks, long periodTicks) {
      return this.track(Bukkit.getScheduler().runTaskTimer(this.plugin, action, Math.max(0L, delayTicks), Math.max(1L, periodTicks)));
   }

   public PortalScheduler.Task runForPlayer(Player player, Runnable action) {
      return this.runGlobal(action);
   }

   public PortalScheduler.Task runForPlayerLater(Player player, Runnable action, long delayTicks) {
      return this.runGlobalLater(action, delayTicks);
   }

   public PortalScheduler.Task runForEntity(Entity entity, Runnable action) {
      return this.runGlobal(action);
   }

   public PortalScheduler.Task runAt(Location location, Runnable action) {
      return this.runGlobal(action);
   }

   public PortalScheduler.Task runAtLater(Location location, Runnable action, long delayTicks) {
      return this.runGlobalLater(action, delayTicks);
   }

   public void cancelAll() {
      for(BukkitTaskHandle task : this.tasks) {
         task.cancel();
      }

      this.tasks.clear();
   }

   public boolean isFolia() {
      return false;
   }

   private PortalScheduler.Task track(BukkitTask task) {
      BukkitTaskHandle handle = new BukkitTaskHandle(task);
      this.tasks.add(handle);
      return handle;
   }

   private static final class BukkitTaskHandle implements PortalScheduler.Task {
      private final BukkitTask task;

      private BukkitTaskHandle(BukkitTask task) {
         this.task = task;
      }

      public void cancel() {
         if (!this.task.isCancelled()) {
            this.task.cancel();
         }

      }

      public boolean cancelled() {
         return this.task.isCancelled();
      }
   }
}
