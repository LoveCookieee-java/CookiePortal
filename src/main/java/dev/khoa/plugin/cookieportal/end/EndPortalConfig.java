package dev.khoa.plugin.cookieportal.end;

import dev.khoa.plugin.cookieportal.config.PortalConfig;
import org.bukkit.configuration.file.FileConfiguration;

public record EndPortalConfig(boolean enabled, boolean previewEnabled, double activationDistance, int updateIntervalTicks, int previewDepth, double particleMinimumDistance, boolean skyArrivalEnabled, int heightAboveGround, int hoverTicks, double descentSpeed, int fallProtectionTicks) {
   public static EndPortalConfig read(FileConfiguration config) {
      return new EndPortalConfig(
         PortalConfig.readToggle(config, "the_end", "end-portal.enabled", true),
         config.getBoolean("end-portal.preview.enabled", true),
         clamp(config.getDouble("end-portal.preview.activation-distance", (double)12.0F), (double)4.0F, (double)24.0F),
         clamp(config.getInt("end-portal.preview.update-interval-ticks", 2), 1, 10),
         clamp(config.getInt("end-portal.preview.depth", 9), 6, 12),
         clamp(config.getDouble("end-portal.preview.particle-minimum-distance", (double)7.0F), (double)0.0F, (double)24.0F),
         config.getBoolean("end-portal.sky-arrival.enabled", true),
         clamp(config.getInt("end-portal.sky-arrival.height-above-ground", 52), 20, 96),
         clamp(config.getInt("end-portal.sky-arrival.hover-ticks", 40), 0, 120),
         clamp(config.getDouble("end-portal.sky-arrival.descent-speed", 0.42), 0.15, 0.8),
         clamp(config.getInt("end-portal.sky-arrival.fall-protection-ticks", 80), 20, 200)
      );
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   private static double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }
}
