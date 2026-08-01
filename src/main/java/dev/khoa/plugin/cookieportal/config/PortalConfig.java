package dev.khoa.plugin.cookieportal.config;

import org.bukkit.configuration.file.FileConfiguration;

public record PortalConfig(boolean enabled, double activationDistance, int interval, int width, int height, int depth, int maxChanges, double lookThreshold, int sourceRefreshTicks, boolean animation, int animationTicks, boolean entities, int maxEntities, boolean debug, boolean matchDestinationSize) {
   public static PortalConfig read(FileConfiguration c) {
      return new PortalConfig(
         readToggle(c, "nether", "render.enabled", true),
         clamp(c.getDouble("render.activation-distance", (double)20.0F), (double)4.0F, (double)48.0F),
         clamp(c.getInt("render.update-interval-ticks", 3), 1, 20),
         clamp(c.getInt("render.room-width", 60), 8, 60),
         clamp(c.getInt("render.room-height", 60), 8, 60),
         clamp(c.getInt("render.room-depth", 60), 8, 60),
         clamp(c.getInt("render.max-block-changes-per-update", 3500), 100, 12000),
         clamp(c.getDouble("render.look-dot-threshold", 0.2), (double)-0.5F, 0.95),
         clamp(c.getInt("render.source-refresh-ticks", 40), 40, 1200),
         c.getBoolean("animation.enabled", true),
         clamp(c.getInt("animation.duration-ticks", 24), 5, 100),
         c.getBoolean("entities.enabled", true),
         clamp(c.getInt("entities.maximum-per-portal", 24), 0, 64),
         c.getBoolean("debug", false),
         c.getBoolean("portal.match-destination-size", true)
      );
   }

   public static boolean readToggle(FileConfiguration c, String primaryPath, String fallbackPath, boolean defaultValue) {
      if (c.isBoolean(primaryPath)) {
         return c.getBoolean(primaryPath);
      }
      if (c.isString(primaryPath)) {
         String val = c.getString(primaryPath, "").trim().toLowerCase();
         if (val.equals("enable") || val.equals("enabled") || val.equals("true") || val.equals("1")) return true;
         if (val.equals("disable") || val.equals("disabled") || val.equals("false") || val.equals("0")) return false;
      }
      String subPath = primaryPath + ".enabled";
      if (c.isBoolean(subPath)) {
         return c.getBoolean(subPath);
      }
      if (c.isString(subPath)) {
         String val = c.getString(subPath, "").trim().toLowerCase();
         if (val.equals("enable") || val.equals("enabled") || val.equals("true") || val.equals("1")) return true;
         if (val.equals("disable") || val.equals("disabled") || val.equals("false") || val.equals("0")) return false;
      }
      if (fallbackPath != null && c.contains(fallbackPath)) {
         return c.getBoolean(fallbackPath, defaultValue);
      }
      return defaultValue;
   }

   private static int clamp(int v, int min, int max) {
      return Math.max(min, Math.min(max, v));
   }

   private static double clamp(double v, double min, double max) {
      return Math.max(min, Math.min(max, v));
   }
}
