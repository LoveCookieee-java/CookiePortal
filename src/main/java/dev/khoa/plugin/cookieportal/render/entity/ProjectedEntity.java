package dev.khoa.plugin.cookieportal.render.entity;

import java.util.UUID;
import org.bukkit.entity.EntityType;

public final class ProjectedEntity {
   private final UUID sourceUuid;
   private final int fakeEntityId;
   private final EntityType sourceType;
   private double x;
   private double y;
   private double z;
   private float yaw;
   private float pitch;
   private long lastSeenFrame;
   private int updatesSinceFullSync;

   public ProjectedEntity(UUID sourceUuid, int fakeEntityId, EntityType sourceType, double x, double y, double z, float yaw, float pitch) {
      this.sourceUuid = sourceUuid;
      this.fakeEntityId = fakeEntityId;
      this.sourceType = sourceType;
      this.x = x;
      this.y = y;
      this.z = z;
      this.yaw = yaw;
      this.pitch = pitch;
   }

   public UUID sourceUuid() {
      return this.sourceUuid;
   }

   public int fakeEntityId() {
      return this.fakeEntityId;
   }

   public EntityType sourceType() {
      return this.sourceType;
   }

   public double x() {
      return this.x;
   }

   public double y() {
      return this.y;
   }

   public double z() {
      return this.z;
   }

   public float yaw() {
      return this.yaw;
   }

   public float pitch() {
      return this.pitch;
   }

   public long lastSeenFrame() {
      return this.lastSeenFrame;
   }

   public void markSeen(long frame) {
      this.lastSeenFrame = frame;
   }

   public void updatePose(double x, double y, double z, float yaw, float pitch) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.yaw = yaw;
      this.pitch = pitch;
      ++this.updatesSinceFullSync;
   }

   public boolean shouldFullSync(int intervalUpdates) {
      if (this.updatesSinceFullSync < Math.max(1, intervalUpdates)) {
         return false;
      } else {
         this.updatesSinceFullSync = 0;
         return true;
      }
   }

   public void resetFullSyncCounter() {
      this.updatesSinceFullSync = 0;
   }
}
