package dev.khoa.plugin.cookieportal.render.entity;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class UnsupportedEntityPacketBridge implements EntityPacketBridge {
   private final String reason;

   public UnsupportedEntityPacketBridge(String reason) {
      this.reason = reason;
   }

   public boolean supported() {
      return false;
   }

   public String status() {
      return this.reason;
   }

   public boolean spawn(Player viewer, LivingEntity source, ProjectedEntity projected) {
      return false;
   }

   public boolean update(Player viewer, LivingEntity source, ProjectedEntity projected, double x, double y, double z, float yaw, float pitch) {
      return false;
   }

   public void synchronize(Player viewer, LivingEntity source, ProjectedEntity projected) {
   }

   public void destroy(Player viewer, int fakeEntityId) {
   }
}
