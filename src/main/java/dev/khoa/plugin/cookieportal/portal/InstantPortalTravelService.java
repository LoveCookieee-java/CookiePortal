package dev.khoa.plugin.cookieportal.portal;

import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

public final class InstantPortalTravelService implements Listener {
   private final CookiePortalPlugin plugin;

   public InstantPortalTravelService(CookiePortalPlugin plugin) {
      this.plugin = plugin;
   }

   public void start() {
      if (!this.plugin.settings().enabled()) {
         return;
      }
      this.plugin.scheduler().runGlobal(() -> {
         for(World world : this.plugin.getServer().getWorlds()) {
            this.enableInstantTravel(world);
         }

      });
   }

   @EventHandler
   public void onWorldLoad(WorldLoadEvent event) {
      if (!this.plugin.settings().enabled()) {
         return;
      }
      World world = event.getWorld();
      this.plugin.scheduler().runGlobal(() -> this.enableInstantTravel(world));
   }

   private void enableInstantTravel(World world) {
      Integer current = (Integer)world.getGameRuleValue(GameRule.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY);
      if (current == null || current != 0) {
         world.setGameRule(GameRule.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY, 0);
      }

   }
}
