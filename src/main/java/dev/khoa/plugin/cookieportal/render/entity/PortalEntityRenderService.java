package dev.khoa.plugin.cookieportal.render.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import dev.khoa.plugin.cookieportal.portal.PortalPlane;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class PortalEntityRenderService {
   private static final double PROJECTION_VERTICAL_OFFSET = (double)0.0F;
   private static final long MISSING_ENTITY_GRACE_MILLIS = 2500L;
   private static final Vector UP = new Vector(0, 1, 0);
   private static final double OPENING_TOLERANCE = 0.08;
   private static final double ROOM_DEPTH = (double)60.0F;
   private static final double FAR_ROOM_DEPTH = (double)3.0F;
   private static final double ROOM_BOTTOM_DROP = (double)3.0F;
   private static final double NEAR_DISTANCE = 1.1;
   private static final double FAR_DISTANCE = (double)8.0F;
   private static final double MIN_SIDE_PADDING = (double)3.0F;
   private static final double MAX_SIDE_PADDING = (double)4.0F;
   private static final double MIN_CEILING_RISE = (double)4.0F;
   private static final double MAX_CEILING_RISE = (double)5.0F;
   private static final int FULL_SYNC_EVERY_UPDATES = 5;
   private static final int FIRST_FAKE_ENTITY_ID = 1900000000;
   private static final int LAST_FAKE_ENTITY_ID = 1500000000;
   private final CookiePortalPlugin plugin;
   private final EntityPacketBridge bridge;
   private final Map<UUID, ViewerSession> sessions = new ConcurrentHashMap();
   private final AtomicInteger nextFakeEntityId = new AtomicInteger(1900000000);

   public PortalEntityRenderService(CookiePortalPlugin plugin) {
      this.plugin = plugin;
      this.bridge = new PacketEventsEntityPacketBridge(plugin);
   }

   public boolean supported() {
      return this.bridge.supported();
   }

   public String status() {
      return this.bridge.status();
   }

   public int projectedCount() {
      int total = 0;

      for(ViewerSession session : this.sessions.values()) {
         total += session.entities.size();
      }

      return total;
   }

   public void render(Player viewer, PortalPlane sourcePortal, PortalPlane destinationPortal) {
      if (this.plugin.settings().entities() && this.plugin.settings().maxEntities() > 0 && this.bridge.supported()) {
         if (viewer != null && viewer.isOnline() && sourcePortal != null && destinationPortal != null && sourcePortal.world() == viewer.getWorld() && destinationPortal.world() != null) {
            this.renderInternal(viewer, viewer.getEyeLocation(), sourcePortal, destinationPortal, false);
         } else {
            this.clear(viewer);
         }
      } else {
         this.clear(viewer);
      }
   }

   public void renderFolia(Player viewer, Location capturedEye, PortalPlane sourcePortal, PortalPlane destinationPortal) {
      if (this.plugin.settings().entities() && this.plugin.settings().maxEntities() > 0 && this.bridge.supported() && viewer != null && capturedEye != null && sourcePortal != null && destinationPortal != null && destinationPortal.world() != null) {
         this.renderInternal(viewer, capturedEye, sourcePortal, destinationPortal, true);
      }
   }

   private void renderInternal(Player viewer, Location eye, PortalPlane sourcePortal, PortalPlane destinationPortal, boolean folia) {
      int viewerSide = this.sideOf(eye, sourcePortal);
      Vector sourceForward = sourcePortal.normal(-viewerSide).normalize();
      Vector sourceRight = this.portalRight(sourceForward);
      Vector destinationForward = destinationPortal.normal(-viewerSide).normalize();
      Vector destinationRight = this.portalRight(destinationForward);
      String var10000 = sourcePortal.key();
      String projectionKey = var10000 + "->" + destinationPortal.key() + ":" + viewerSide;
      ViewerSession session = (ViewerSession)this.sessions.computeIfAbsent(viewer.getUniqueId(), (ignored) -> new ViewerSession());
      if (!projectionKey.equals(session.projectionKey)) {
         this.destroySession(viewer, session);
         session.projectionKey = projectionKey;
      }

      ++session.frame;
      RoomDimensions room = this.createRoomDimensions(eye, sourcePortal, destinationPortal, sourceForward);
      List<LivingEntity> visible = this.findVisibleEntities(viewer, eye, sourcePortal, destinationPortal, sourceRight, sourceForward, destinationRight, destinationForward, room, folia);
      int maximum = Math.min(this.plugin.settings().maxEntities(), visible.size());

      for(int index = 0; index < maximum; ++index) {
         LivingEntity source = (LivingEntity)visible.get(index);
         ProjectedPose pose = this.projectPose(source, sourcePortal, destinationPortal, sourceRight, sourceForward, destinationRight, destinationForward);
         ProjectedEntity projected = (ProjectedEntity)session.entities.get(source.getUniqueId());
         if (projected != null && projected.sourceType() != source.getType()) {
            this.bridge.destroy(viewer, projected.fakeEntityId());
            session.entities.remove(source.getUniqueId());
            session.missingSince.remove(source.getUniqueId());
            projected = null;
         }

         if (projected == null) {
            projected = new ProjectedEntity(source.getUniqueId(), this.allocateFakeEntityId(), source.getType(), pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            if (!this.bridge.spawn(viewer, source, projected)) {
               continue;
            }

            session.entities.put(source.getUniqueId(), projected);
         } else {
            boolean updated = this.bridge.update(viewer, source, projected, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            if (!updated) {
               session.missingSince.putIfAbsent(source.getUniqueId(), System.currentTimeMillis());
               if (this.missingTooLong(session, source.getUniqueId())) {
                  this.bridge.destroy(viewer, projected.fakeEntityId());
                  session.entities.remove(source.getUniqueId());
                  session.missingSince.remove(source.getUniqueId());
               }
               continue;
            }

            if (projected.shouldFullSync(5)) {
               this.bridge.synchronize(viewer, source, projected);
            }
         }

         projected.markSeen(session.frame);
         session.missingSince.remove(source.getUniqueId());
      }

      for(ProjectedEntity projected : new ArrayList<>(session.entities.values())) {
         if (projected.lastSeenFrame() != session.frame) {
            session.missingSince.putIfAbsent(projected.sourceUuid(), System.currentTimeMillis());
            if (this.missingTooLong(session, projected.sourceUuid())) {
               this.bridge.destroy(viewer, projected.fakeEntityId());
               session.entities.remove(projected.sourceUuid());
               session.missingSince.remove(projected.sourceUuid());
            }
         }
      }

   }

   private boolean missingTooLong(ViewerSession session, UUID sourceUuid) {
      Long missingSince = (Long)session.missingSince.get(sourceUuid);
      return missingSince != null && System.currentTimeMillis() - missingSince >= 2500L;
   }

   public void clear(Player viewer) {
      if (viewer != null) {
         ViewerSession session = (ViewerSession)this.sessions.remove(viewer.getUniqueId());
         if (session != null) {
            this.destroySession(viewer, session);
         }

      }
   }

   public void stop() {
      for(Player player : this.plugin.getServer().getOnlinePlayers()) {
         this.clear(player);
      }

      this.sessions.clear();
   }

   public void discard() {
      this.sessions.clear();
   }

   private List<LivingEntity> findVisibleEntities(Player viewer, Location eye, PortalPlane sourcePortal, PortalPlane destinationPortal, Vector sourceRight, Vector sourceForward, Vector destinationRight, Vector destinationForward, RoomDimensions room, boolean folia) {
      World destinationWorld = destinationPortal.world();
      if (destinationWorld == null) {
         return List.of();
      } else {
         BoundingBox searchBox = this.createSearchBox(destinationPortal, destinationRight, destinationForward, room);
         List<EntityCandidate> candidates = new ArrayList();

         for(Entity entity : destinationWorld.getNearbyEntities(searchBox)) {
            if (entity instanceof LivingEntity) {
               LivingEntity living = (LivingEntity)entity;
               if (living.isValid() && !living.isDead()) {
                  if (!folia && living instanceof Player) {
                     Player remotePlayer = (Player)living;
                     if (!viewer.canSee(remotePlayer)) {
                        continue;
                     }
                  }

                  RoomCoordinates feetCoordinates = this.roomCoordinates(living.getLocation().toVector(), destinationPortal, destinationRight, destinationForward);
                  if (this.insideRoom(feetCoordinates, room)) {
                     BoundingBox entityBox = living.getBoundingBox();
                     Vector remoteCenter = new Vector(entityBox.getCenterX(), entityBox.getCenterY(), entityBox.getCenterZ());
                     Vector fakeCenter = this.mapPoint(remoteCenter, sourcePortal, destinationPortal, sourceRight, sourceForward, destinationRight, destinationForward);
                     Location target = new Location(sourcePortal.world(), fakeCenter.getX(), fakeCenter.getY(), fakeCenter.getZ());
                     if (this.rayPassesOpening(eye, target, sourcePortal)) {
                        double score = feetCoordinates.depth() * feetCoordinates.depth() + feetCoordinates.horizontal() * feetCoordinates.horizontal();
                        candidates.add(new EntityCandidate(living, score));
                     }
                  }
               }
            }
         }

         candidates.sort(Comparator.comparingDouble(EntityCandidate::score));
         List<LivingEntity> result = new ArrayList(candidates.size());

         for(EntityCandidate candidate : candidates) {
            result.add(candidate.entity());
         }

         return result;
      }
   }

   private ProjectedPose projectPose(LivingEntity source, PortalPlane sourcePortal, PortalPlane destinationPortal, Vector sourceRight, Vector sourceForward, Vector destinationRight, Vector destinationForward) {
      Location sourceLocation = source.getLocation();
      Vector mappedPosition = this.mapPoint(sourceLocation.toVector(), sourcePortal, destinationPortal, sourceRight, sourceForward, destinationRight, destinationForward);
      Vector remoteDirection = sourceLocation.getDirection();
      double horizontal = remoteDirection.dot(destinationRight);
      double vertical = remoteDirection.getY();
      double forward = remoteDirection.dot(destinationForward);
      Vector mappedDirection = sourceRight.clone().multiply(horizontal).add(UP.clone().multiply(vertical)).add(sourceForward.clone().multiply(forward));
      Location rotation = new Location(sourcePortal.world(), (double)0.0F, (double)0.0F, (double)0.0F);
      if (mappedDirection.lengthSquared() > 1.0E-6) {
         rotation.setDirection(mappedDirection.normalize());
      } else {
         rotation.setYaw(sourceLocation.getYaw());
         rotation.setPitch(sourceLocation.getPitch());
      }

      return new ProjectedPose(mappedPosition.getX(), mappedPosition.getY(), mappedPosition.getZ(), rotation.getYaw(), rotation.getPitch());
   }

   private Vector mapPoint(Vector remotePoint, PortalPlane sourcePortal, PortalPlane destinationPortal, Vector sourceRight, Vector sourceForward, Vector destinationRight, Vector destinationForward) {
      Vector relative = remotePoint.clone().subtract(destinationPortal.center().toVector());
      double horizontal = relative.dot(destinationRight);
      double vertical = remotePoint.getY() - (double)destinationPortal.minY();
      double depth = relative.dot(destinationForward);
      return sourcePortal.center().toVector().add(sourceRight.clone().multiply(horizontal)).setY((double)sourcePortal.minY() + vertical + (double)0.0F).add(sourceForward.clone().multiply(depth));
   }

   private RoomCoordinates roomCoordinates(Vector remotePoint, PortalPlane destinationPortal, Vector destinationRight, Vector destinationForward) {
      Vector relative = remotePoint.clone().subtract(destinationPortal.center().toVector());
      return new RoomCoordinates(relative.dot(destinationRight), remotePoint.getY(), relative.dot(destinationForward));
   }

   private RoomDimensions createRoomDimensions(Location eye, PortalPlane sourcePortal, PortalPlane destinationPortal, Vector sourceForward) {
      double distanceToPortal = Math.max(0.65, this.distanceToPlane(eye, sourcePortal.center(), sourceForward));
      double proximity = this.proximityFactor(distanceToPortal);
      double halfWidth = (double)this.portalWidth(destinationPortal) * (double)0.5F + this.lerp((double)3.0F, (double)4.0F, proximity);
      double bottomY = (double)destinationPortal.minY() - (double)3.0F;
      double ceilingY = (double)destinationPortal.maxY() + this.lerp((double)4.0F, (double)5.0F, proximity);
      int maximumHalfWidth = Math.max(7, Math.min(30, this.plugin.settings().width() / 2));
      int maximumHalfHeight = Math.max(7, Math.min(30, this.plugin.settings().height() / 2));
      int worstCaseLayer = (maximumHalfWidth * 2 + 3) * (maximumHalfHeight * 2 + 3);
      int available = Math.max(worstCaseLayer * 3, this.plugin.settings().maxChanges() - 256);
      int budgetDepth = Math.max(3, available / Math.max(1, worstCaseLayer));
      double configuredDepth = Math.min((double)60.0F, (double)this.plugin.settings().depth());
      double requestedDepth = this.lerp(Math.min((double)8.0F, configuredDepth), configuredDepth, proximity);
      return new RoomDimensions(halfWidth, bottomY, ceilingY, Math.max((double)3.0F, Math.min(configuredDepth, Math.min(requestedDepth, (double)budgetDepth))));
   }

   private BoundingBox createSearchBox(PortalPlane portal, Vector right, Vector forward, RoomDimensions room) {
      Vector center = portal.center().toVector();
      double minX = Double.POSITIVE_INFINITY;
      double minZ = Double.POSITIVE_INFINITY;
      double maxX = Double.NEGATIVE_INFINITY;
      double maxZ = Double.NEGATIVE_INFINITY;
      double[] horizontalValues = new double[]{-room.halfWidth() - (double)2.0F, room.halfWidth() + (double)2.0F};
      double[] depthValues = new double[]{(double)-1.0F, room.depth() + (double)2.0F};

      for(double horizontal : horizontalValues) {
         for(double depth : depthValues) {
            Vector corner = center.clone().add(right.clone().multiply(horizontal)).add(forward.clone().multiply(depth));
            minX = Math.min(minX, corner.getX());
            minZ = Math.min(minZ, corner.getZ());
            maxX = Math.max(maxX, corner.getX());
            maxZ = Math.max(maxZ, corner.getZ());
         }
      }

      return new BoundingBox(minX, room.bottomY() - (double)2.0F, minZ, maxX, room.ceilingY() + (double)2.0F, maxZ);
   }

   private boolean insideRoom(RoomCoordinates coordinates, RoomDimensions room) {
      return Math.abs(coordinates.horizontal()) <= room.halfWidth() + (double)1.0F && coordinates.y() >= room.bottomY() - (double)1.5F && coordinates.y() <= room.ceilingY() + (double)1.5F && coordinates.depth() >= 0.2 && coordinates.depth() <= room.depth();
   }

   private boolean rayPassesOpening(Location eye, Location target, PortalPlane portal) {
      Vector origin = eye.toVector();
      Vector ray = target.toVector().subtract(origin);
      if (ray.lengthSquared() < 1.0E-7) {
         return false;
      } else {
         double plane = portal.axis() == PortalPlane.Axis.X ? (double)portal.minZ() + (double)0.5F : (double)portal.minX() + (double)0.5F;
         double component = portal.axis() == PortalPlane.Axis.X ? ray.getZ() : ray.getX();
         if (Math.abs(component) < 1.0E-7) {
            return false;
         } else {
            double originComponent = portal.axis() == PortalPlane.Axis.X ? origin.getZ() : origin.getX();
            double progress = (plane - originComponent) / component;
            if (!(progress <= (double)0.0F) && !(progress >= (double)1.0F)) {
               Vector hit = origin.clone().add(ray.multiply(progress));
               BoundingBox opening = portal.openingBox();
               return hit.getX() >= opening.getMinX() - 0.08 && hit.getX() <= opening.getMaxX() + 0.08 && hit.getY() >= opening.getMinY() - 0.08 && hit.getY() <= opening.getMaxY() + 0.08 && hit.getZ() >= opening.getMinZ() - 0.08 && hit.getZ() <= opening.getMaxZ() + 0.08;
            } else {
               return false;
            }
         }
      }
   }

   private void destroySession(Player viewer, ViewerSession session) {
      for(ProjectedEntity projected : session.entities.values()) {
         this.bridge.destroy(viewer, projected.fakeEntityId());
      }

      session.entities.clear();
      session.missingSince.clear();
      session.projectionKey = null;
   }

   private int allocateFakeEntityId() {
      return this.nextFakeEntityId.getAndUpdate((current) -> current <= 1500000000 ? 1900000000 : current - 1);
   }

   private int sideOf(Location location, PortalPlane portal) {
      double difference;
      if (portal.axis() == PortalPlane.Axis.X) {
         difference = location.getZ() - portal.center().getZ();
      } else {
         difference = location.getX() - portal.center().getX();
      }

      return difference >= (double)0.0F ? 1 : -1;
   }

   private Vector portalRight(Vector forward) {
      Vector right = forward.clone().crossProduct(UP);
      return right.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : right.normalize();
   }

   private int portalWidth(PortalPlane portal) {
      return portal.axis() == PortalPlane.Axis.X ? portal.maxX() - portal.minX() + 1 : portal.maxZ() - portal.minZ() + 1;
   }

   private double distanceToPlane(Location location, Location planeCenter, Vector planeNormal) {
      return Math.abs(location.toVector().subtract(planeCenter.toVector()).dot(planeNormal));
   }

   private double proximityFactor(double distanceToPortal) {
      double normalized = ((double)8.0F - distanceToPortal) / 6.9;
      return this.clamp(normalized, (double)0.0F, (double)1.0F);
   }

   private double lerp(double start, double end, double progress) {
      return start + (end - start) * progress;
   }

   private double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   private static final class ViewerSession {
      private String projectionKey;
      private long frame;
      private final Map<UUID, ProjectedEntity> entities = new HashMap();
      private final Map<UUID, Long> missingSince = new HashMap();
   }

   private static record RoomDimensions(double halfWidth, double bottomY, double ceilingY, double depth) {
   }

   private static record RoomCoordinates(double horizontal, double y, double depth) {
   }

   private static record EntityCandidate(LivingEntity entity, double score) {
   }

   private static record ProjectedPose(double x, double y, double z, float yaw, float pitch) {
   }
}
