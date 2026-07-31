package dev.khoa.plugin.cookieportal.dimension;

import org.bukkit.configuration.file.FileConfiguration;

public record DimensionStackConfig(boolean enabled, boolean generateHoles, double holeChance, int holeRadius, long transitionCooldownMillis, int netherArrivalY, int overworldArrivalY, int endDropHeight, String overworldName, String netherName) {
   public static DimensionStackConfig read(FileConfiguration config) {
      return new DimensionStackConfig(config.getBoolean("dimension-stacking.enabled", true), config.getBoolean("dimension-stacking.holes.enabled", true), clamp(config.getDouble("dimension-stacking.holes.chance-per-chunk", 0.18), (double)0.0F, (double)1.0F), clamp(config.getInt("dimension-stacking.holes.radius", 1), 0, 3), clamp(config.getLong("dimension-stacking.transition-cooldown-millis", 3000L), 500L, 30000L), config.getInt("dimension-stacking.nether-arrival-y", 64), config.getInt("dimension-stacking.overworld-arrival-y", 24), clamp(config.getInt("dimension-stacking.end-drop-height", 356), 8, 1024), config.getString("dimension-stacking.worlds.overworld", ""), config.getString("dimension-stacking.worlds.nether", ""));
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   private static long clamp(long value, long minimum, long maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   private static double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }
}
