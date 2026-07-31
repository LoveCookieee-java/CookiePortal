package dev.khoa.plugin.cookieportal.render;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import dev.khoa.plugin.cookieportal.config.PortalConfig;
import dev.khoa.plugin.cookieportal.platform.PlatformCompatibility;
import dev.khoa.plugin.cookieportal.platform.PortalScheduler;
import dev.khoa.plugin.cookieportal.portal.PortalPlane;
import dev.khoa.plugin.cookieportal.portal.PortalRegistry;
import dev.khoa.plugin.cookieportal.render.entity.PortalEntityRenderService;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class PortalRenderService {
   private static final Vector UP = new Vector(0, 1, 0);
   private static final double OPENING_TOLERANCE = 0.045;
   private static final int RASTER_PADDING = 12;
   private static final double BLOCK_SAMPLE_OFFSET = 0.48;
   private static final double[][] BLOCK_SAMPLE_OFFSETS = new double[][]{{(double)0.0F, (double)0.0F}, {0.48, (double)0.0F}, {-0.48, (double)0.0F}, {(double)0.0F, 0.48}, {(double)0.0F, -0.48}, {0.48, 0.48}, {-0.48, 0.48}, {0.48, -0.48}, {-0.48, -0.48}};
   private static final double ROOM_SHELL_THICKNESS = 1.05;
   private static final double ROOM_DEPTH = (double)60.0F;
   private static final double MIN_ROOM_DEPTH = (double)3.0F;
   private static final double ROOM_BOTTOM_DROP = (double)3.0F;
   private static final double PROJECTION_VERTICAL_OFFSET = (double)0.0F;
   private static final double NEAR_DISTANCE = 1.1;
   private static final double FAR_DISTANCE = (double)8.0F;
   private static final double MAX_SIDE_PADDING = (double)4.0F;
   private static final double MIN_CEILING_RISE = (double)4.0F;
   private static final double MAX_CEILING_RISE = (double)5.0F;
   private static final int CLOSE_RENDER_BUDGET = 14000;
   private static final int ABSOLUTE_RENDER_LIMIT = 24000;
   private static final double SIDE_VIEW_DOT_STILL = 0.42;
   private static final double SIDE_VIEW_DOT_MOVING = 0.55;
   private static final double MOVING_SPEED_THRESHOLD = 0.1;
   private static final double EYE_PREDICTION_TICKS = (double)2.0F;
   private static final long OVERLAY_REASSERT_MILLIS = 2500L;
   private static final long REMOTE_CHUNK_TICKET_MILLIS = 5000L;
   private static final BlockData AIR_BLOCK;
   private final CookiePortalPlugin plugin;
   private final PortalRegistry registry;
   private final PortalEntityRenderService entityRenderer;
   private final Map<UUID, ViewSession> sessions = new ConcurrentHashMap();
   private final Set<String> requestedChunks = ConcurrentHashMap.newKeySet();
   private final Map<String, RemoteScene> remoteScenes = new ConcurrentHashMap();
   private final Set<String> pendingRemoteScenes = ConcurrentHashMap.newKeySet();
   private final Set<UUID> pendingEntityRenders = ConcurrentHashMap.newKeySet();
   private final Set<UUID> entityViewsEnabled = ConcurrentHashMap.newKeySet();
   private final Map<String, HeldRemoteChunk> heldRemoteChunks = new ConcurrentHashMap();
   private PortalScheduler.Task task;

   public PortalRenderService(CookiePortalPlugin plugin, PortalRegistry registry) {
      this.plugin = plugin;
      this.registry = registry;
      this.entityRenderer = new PortalEntityRenderService(plugin);
   }

   public void start() {
      if (this.plugin.settings().enabled()) {
         int interval = Math.max(1, this.plugin.settings().interval());
         this.task = this.plugin.scheduler().runGlobalTimer(this::tick, 1L, (long)interval);
      }
   }

   public void restart() {
      this.stop();
      this.start();
      this.refreshAllLater(1L);
      this.refreshAllLater(3L);
      this.refreshAllLater(8L);
   }

   public void stop() {
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      if (this.plugin.scheduler().isFolia() && !this.plugin.isEnabled()) {
         this.entityRenderer.discard();
         this.sessions.clear();
         this.requestedChunks.clear();
         this.remoteScenes.clear();
         this.pendingRemoteScenes.clear();
         this.pendingEntityRenders.clear();
         this.entityViewsEnabled.clear();
         this.heldRemoteChunks.clear();
      } else {
         for(Player player : Bukkit.getOnlinePlayers()) {
            this.clear(player);
         }

         this.entityRenderer.stop();
         this.sessions.clear();
         this.requestedChunks.clear();
         this.remoteScenes.clear();
         this.pendingRemoteScenes.clear();
         this.pendingEntityRenders.clear();
         this.entityViewsEnabled.clear();
         this.releaseAllRemoteChunkTickets();
      }
   }

   public int activeViews() {
      return this.sessions.size();
   }

   public int projectedEntities() {
      return this.entityRenderer.projectedCount();
   }

   public String entityBridgeStatus() {
      return this.entityRenderer.status();
   }

   public BlockData projectedBlockData(Player player, Block block) {
      if (player != null && block != null) {
         ViewSession session = (ViewSession)this.sessions.get(player.getUniqueId());
         if (session == null) {
            return null;
         } else {
            return this.registry.contains(session.portalKey) && this.registry.contains(session.destinationKey) ? (BlockData)session.sent.get(block.getLocation()) : null;
         }
      } else {
         return null;
      }
   }

   public void refreshImmediately(Player player) {
      if (player != null && player.isOnline()) {
         PortalPlane portal = this.findVisible(player);
         if (portal == null) {
            this.clear(player);
         } else {
            ViewSession session = (ViewSession)this.sessions.computeIfAbsent(player.getUniqueId(), (ignored) -> new ViewSession());
            session.warmupFrames = Math.max(session.warmupFrames, 3);
            PortalPlane destination = this.registry.destinationFor(portal);
            if (destination != null && destination.world() != null) {
               this.forcePortalSurfaceAir(player, portal);
            } else {
               this.restorePortalSurface(player, portal);
            }

            this.render(player, portal);
         }
      }
   }

   public void refreshAllLater(long delayTicks) {
      this.plugin.scheduler().runGlobalLater(() -> {
         for(Player player : Bukkit.getOnlinePlayers()) {
            this.plugin.scheduler().runForPlayer(player, () -> this.refreshImmediately(player));
         }

      }, Math.max(1L, delayTicks));
   }

   public void refreshNear(PortalPlane portal) {
      if (portal != null && portal.world() != null) {
         double distance = this.plugin.settings().activationDistance();
         double distanceSquared = distance * distance;

         for(Player player : Bukkit.getOnlinePlayers()) {
            this.plugin.scheduler().runForPlayer(player, () -> {
               if (player.isOnline() && player.getWorld() == portal.world() && !(player.getEyeLocation().distanceSquared(portal.center()) > distanceSquared)) {
                  PortalPlane visible = this.findVisible(player);
                  if (visible != null && visible.key().equals(portal.key())) {
                     PortalPlane destination = this.registry.destinationFor(portal);
                     if (destination != null && destination.world() != null) {
                        this.forcePortalSurfaceAir(player, portal);
                     } else {
                        this.restorePortalSurface(player, portal);
                     }

                     this.render(player, visible);
                  }
               }
            });
         }

      }
   }

   private void tick() {
      this.releaseExpiredRemoteChunkTickets();
      this.remoteScenes.keySet().removeIf(key -> !this.registry.contains(key));
      this.pendingRemoteScenes.removeIf(key -> !this.registry.contains(key));
      if (this.plugin.scheduler().isFolia()) {
         for(Player player : Bukkit.getOnlinePlayers()) {
            this.plugin.scheduler().runForPlayer(player, () -> this.tickPlayer(player));
         }

         this.sessions.keySet().removeIf((uuid) -> Bukkit.getPlayer(uuid) == null);
      } else {
         this.registry.removeInvalid();

         for(Player player : Bukkit.getOnlinePlayers()) {
            this.tickPlayer(player);
         }

         this.sessions.keySet().removeIf((uuid) -> Bukkit.getPlayer(uuid) == null);
      }
   }

   private void tickPlayer(Player player) {
      if (player != null && player.isOnline()) {
         PortalPlane visiblePortal = this.findVisible(player);
         if (visiblePortal == null) {
            this.clear(player);
         } else {
            this.render(player, visiblePortal);
         }
      }
   }

   private PortalPlane findVisible(Player player) {
      PortalConfig config = this.plugin.settings();
      Location eye = player.getEyeLocation();
      PortalPlane closest = null;
      double closestDistance = Double.MAX_VALUE;
      double activationDistanceSquared = config.activationDistance() * config.activationDistance();

      for(PortalPlane portal : this.registry.all()) {
         if (portal.world() == player.getWorld()) {
            double distanceSquared = eye.distanceSquared(portal.center());
            if (!(distanceSquared > activationDistanceSquared) && !(distanceSquared >= closestDistance)) {
               Vector towardPortal = portal.center().toVector().subtract(eye.toVector());
               if (!(towardPortal.lengthSquared() < 1.0E-6)) {
                  towardPortal.normalize();
                  int viewerSide = this.sideOf(eye, portal);
                  Vector sourceForward = portal.normal(-viewerSide).normalize();
                  double forwardDot = eye.getDirection().normalize().dot(sourceForward);
                  double minimumForwardDot = this.horizontalSpeed(player) > 0.1 ? 0.55 : 0.42;
                  if (!(forwardDot < minimumForwardDot) && !(eye.getDirection().dot(towardPortal) < config.lookThreshold())) {
                     Location pointBehindPortal = portal.center().clone().add(sourceForward.clone().multiply(0.05));
                     if (PortalProjection.rayPassesOpening(eye, pointBehindPortal, portal)) {
                        closest = portal;
                        closestDistance = distanceSquared;
                     }
                  }
               }
            }
         }
      }

      return closest;
   }

   private boolean isPortalActivelyVisible(Player player, PortalPlane portal) {
      if (player != null && portal != null && portal.world() == player.getWorld()) {
         Location eye = player.getEyeLocation();
         Vector direction = eye.getDirection().normalize();
         Vector towardPortal = portal.center().toVector().subtract(eye.toVector());
         if (towardPortal.lengthSquared() < 1.0E-6) {
            return false;
         } else {
            towardPortal.normalize();
            int viewerSide = this.sideOf(eye, portal);
            Vector sourceForward = portal.normal(-viewerSide).normalize();
            double minimumForwardDot = this.horizontalSpeed(player) > 0.1 ? 0.55 : 0.42;
            if (!(direction.dot(sourceForward) < minimumForwardDot) && !(direction.dot(towardPortal) < this.plugin.settings().lookThreshold())) {
               Location pointBehindPortal = portal.center().clone().add(sourceForward.clone().multiply(0.05));
               return PortalProjection.rayPassesOpening(eye, pointBehindPortal, portal);
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private void render(Player player, PortalPlane sourcePortal) {
      if (!player.hasPermission("portal.see") && !player.hasPermission("cookieportal.see")) {
         ViewSession session = (ViewSession)this.sessions.get(player.getUniqueId());
         if (session != null) {
            this.clearSent(player, session);
         }
         this.entityRenderer.clear(player);
         this.restorePortalSurface(player, sourcePortal);
         return;
      }

      ViewSession session = (ViewSession)this.sessions.computeIfAbsent(player.getUniqueId(), (ignored) -> new ViewSession());
      if (!sourcePortal.key().equals(session.portalKey)) {
         this.clearSent(player, session);
         this.entityRenderer.clear(player);
         session.portalKey = sourcePortal.key();
         session.warmupFrames = 3;
      }

      PortalPlane destinationPortal = this.registry.destinationFor(sourcePortal);
      if (destinationPortal != null && destinationPortal.world() != null) {
         Location liveEye = player.getEyeLocation();
         int viewerSide = this.sideOf(liveEye, sourcePortal);
         if (!destinationPortal.key().equals(session.destinationKey) || viewerSide != session.viewerSide) {
            this.clearSent(player, session);
            this.entityRenderer.clear(player);
            session.portalKey = sourcePortal.key();
            session.destinationKey = destinationPortal.key();
            session.viewerSide = viewerSide;
            session.warmupFrames = 3;
         }

         boolean movingFast = this.horizontalSpeed(player) > 0.1;
         Location eye = this.predictedEye(liveEye, player, movingFast);
         Vector sourceForward = sourcePortal.normal(-viewerSide).normalize();
         Vector sourceRight = this.portalRight(sourceForward);
         Vector destinationForward = destinationPortal.normal(-viewerSide).normalize();
         Vector destinationRight = this.portalRight(destinationForward);
         PortalConfig config = this.plugin.settings();
         double distanceToPortal = this.distanceToPlane(eye, sourcePortal.center(), sourceForward);
         distanceToPortal = Math.max(0.65, distanceToPortal);
         double proximity = this.proximityFactor(distanceToPortal);
         RoomDimensions room = this.createRoomDimensions(destinationPortal);
         this.keepRemoteRoomLoaded(destinationPortal, room);
         int renderDepth = Math.max(4, (int)Math.floor(room.depth()));
         int maximumHalfWidth = Math.max(7, Math.min(30, config.width() / 2));
         int maximumHalfHeight = Math.max(7, Math.min(30, config.height() / 2));
         int renderBudget = this.adaptiveRenderBudget(config.maxChanges(), proximity);
         int minimumCompleteBudget = (maximumHalfWidth * 2 + 3) * (maximumHalfHeight * 2 + 3) * 3 + 256;
         renderBudget = Math.min(24000, Math.max(renderBudget, minimumCompleteBudget));
         RemoteScene remoteScene = (RemoteScene)this.remoteScenes.get(destinationPortal.key());
         if (remoteScene == null || remoteScene.expired(this.plugin.settings().sourceRefreshTicks())) {
            this.requestRemoteScene(destinationPortal);
            if (remoteScene == null) {
               this.renderPortalSurfaceOnly(player, sourcePortal, session);
               this.entityRenderer.clear(player);
               return;
            }
         }

         Map<Location, BlockData> shellCandidates = new LinkedHashMap();
         Map<Location, BlockData> sceneCandidates = new LinkedHashMap();
         Map<Location, BlockData> occlusionCandidates = new LinkedHashMap();

         for(int depth = 1; depth <= renderDepth; ++depth) {
            double perspectiveExpansion = 1.0 + (depth / Math.max(0.4, distanceToPortal)) * 1.35;
            int dynamicHalfWidth = Math.min(maximumHalfWidth, (int)Math.ceil((double)this.portalWidth(sourcePortal) * (double)0.5F * perspectiveExpansion) + 12);
            int dynamicHalfHeight = Math.min(maximumHalfHeight, (int)Math.ceil((double)this.portalHeight(sourcePortal) * (double)0.5F * perspectiveExpansion) + 12);

            for(int y = -dynamicHalfHeight - 1; y <= dynamicHalfHeight + 1; ++y) {
               for(int x = -dynamicHalfWidth - 1; x <= dynamicHalfWidth + 1; ++x) {
                  Location fakeBlock = this.createProjectedBlock(sourcePortal.center(), sourceRight, sourceForward, x, y, depth, (double)0.0F);
                  if (this.isVisibleThroughPortal(eye, fakeBlock, sourcePortal, sourceRight, sourceForward)) {
                     Location remoteSample = this.mapStableRemoteSample(sourcePortal, destinationPortal, destinationRight, destinationForward, x, y, depth);
                     if (remoteSample != null) {
                        RoomCoordinates coordinates = this.calculateRoomCoordinates(remoteSample, destinationPortal, destinationRight, destinationForward);
                        if (!(coordinates.depth() > room.depth() + 1.05) && !(coordinates.depth() < (double)0.0F)) {
                           BlockData shellData = this.createRoomShellBlock(coordinates, destinationPortal, room, remoteSample, remoteScene);
                           if (shellData != null) {
                              this.putIfVisuallyDifferent(shellCandidates, fakeBlock, this.transformBlockData(shellData, destinationRight, destinationForward, sourceRight, sourceForward));
                           } else if (this.isInsideRoomVolume(coordinates, room)) {
                              BlockData remoteData = this.remoteBlockData(remoteSample, remoteScene);
                              if (remoteData != null) {
                                 BlockData transformed = this.transformBlockData(remoteData, destinationRight, destinationForward, sourceRight, sourceForward);
                                 Map<Location, BlockData> priority = sceneCandidates;
                                 if (!this.plugin.scheduler().isFolia() && !fakeBlock.getBlock().getType().isAir()) {
                                    priority = occlusionCandidates;
                                 }

                                 this.putIfVisuallyDifferent(priority, fakeBlock, transformed);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         Map<Location, BlockData> next = new LinkedHashMap();
         this.addPortalSurfaceAir(player, sourcePortal, next);
         int criticalBudget = Math.min(24000, Math.max(renderBudget, next.size() + occlusionCandidates.size() + shellCandidates.size()));
         this.appendUntilBudget(next, occlusionCandidates, criticalBudget);
         this.appendUntilBudget(next, shellCandidates, criticalBudget);
         this.appendUntilBudget(next, sceneCandidates, Math.max(renderBudget, criticalBudget));
         this.applyChanges(player, session, next, movingFast);
         if (this.isPortalActivelyVisible(player, sourcePortal)) {
            this.updateLocalEntityOcclusion(player, sourcePortal, session, liveEye);
            this.renderProjectedEntities(player, sourcePortal, destinationPortal, liveEye);
            this.renderAmbientParticles(player, sourcePortal, destinationPortal, sourceForward, room);
         } else {
            this.restoreHiddenLocalEntities(player, session);
            this.disableProjectedEntities(player);
         }

      } else {
         this.clearSent(player, session);
         this.entityRenderer.clear(player);
         this.entityViewsEnabled.remove(player.getUniqueId());
         this.restorePortalSurface(player, sourcePortal);
      }
   }

   private double horizontalSpeed(Player player) {
      Vector velocity = player.getVelocity();
      return Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
   }

   private Location predictedEye(Location liveEye, Player player, boolean movingFast) {
      if (!movingFast) {
         return liveEye;
      } else {
         Vector velocity = player.getVelocity();
         return velocity.lengthSquared() < 1.0E-6 ? liveEye : liveEye.clone().add(velocity.getX() * (double)2.0F, (double)0.0F, velocity.getZ() * (double)2.0F);
      }
   }

   private void renderProjectedEntities(Player player, PortalPlane sourcePortal, PortalPlane destinationPortal, Location liveEye) {
      UUID viewerId = player.getUniqueId();
      this.entityViewsEnabled.add(viewerId);
      if (this.plugin.scheduler().isFolia()) {
         if (this.pendingEntityRenders.add(viewerId)) {
            Location capturedEye = liveEye.clone();
            this.plugin.scheduler().runAt(destinationPortal.center(), () -> {
               try {
                  if (this.entityViewsEnabled.contains(viewerId) && player.isOnline()) {
                     this.entityRenderer.renderFolia(player, capturedEye, sourcePortal, destinationPortal);
                  }
               } finally {
                  this.pendingEntityRenders.remove(viewerId);
               }

            });
         }
      } else {
         this.entityRenderer.render(player, sourcePortal, destinationPortal);
      }

   }

   private void disableProjectedEntities(Player player) {
      this.entityViewsEnabled.remove(player.getUniqueId());
      this.entityRenderer.clear(player);
   }

   private void renderAmbientParticles(Player player, PortalPlane sourcePortal, PortalPlane destinationPortal, Vector sourceForward, RoomDimensions room) {
      World destinationWorld = destinationPortal.world();
      if (destinationWorld != null && player.getTicksLived() % 4 == 0) {
         Location fogCenter = sourcePortal.center().clone().add(sourceForward.clone().multiply(Math.max(3.0, room.depth() - 1.5)));
         double horizontalSpread = Math.max(0.7, Math.min(2.8, (double)this.portalWidth(sourcePortal) * 0.75));
         double verticalSpread = Math.max(0.8, Math.min(3.0, (double)this.portalHeight(sourcePortal) * 0.85));

         switch (destinationWorld.getEnvironment()) {
            case NETHER -> {
               org.bukkit.block.Biome biome = destinationPortal.center().getBlock().getBiome();
               String biomeName = biome.getKey().getKey().toUpperCase();
               if (biomeName.contains("WARPED")) {
                  player.spawnParticle(Particle.WARPED_SPORE, fogCenter, 6, horizontalSpread, verticalSpread, 0.8, 0.02);
                  player.spawnParticle(Particle.SOUL_FIRE_FLAME, fogCenter, 1, horizontalSpread * 0.5, verticalSpread * 0.5, 0.4, 0.01);
               } else if (biomeName.contains("CRIMSON")) {
                  player.spawnParticle(Particle.CRIMSON_SPORE, fogCenter, 6, horizontalSpread, verticalSpread, 0.8, 0.02);
                  player.spawnParticle(Particle.FLAME, fogCenter, 1, horizontalSpread * 0.5, verticalSpread * 0.5, 0.4, 0.01);
               } else if (biomeName.contains("SOUL")) {
                  player.spawnParticle(Particle.SOUL, fogCenter, 3, horizontalSpread, verticalSpread, 0.7, 0.01);
                  player.spawnParticle(Particle.SOUL_FIRE_FLAME, fogCenter, 2, horizontalSpread * 0.6, verticalSpread * 0.6, 0.5, 0.01);
               } else if (biomeName.contains("BASALT")) {
                  player.spawnParticle(Particle.WHITE_ASH, fogCenter, 5, horizontalSpread, verticalSpread, 0.8, 0.02);
                  player.spawnParticle(Particle.ASH, fogCenter, 3, horizontalSpread, verticalSpread, 0.7, 0.01);
               } else {
                  player.spawnParticle(Particle.ASH, fogCenter, 4, horizontalSpread, verticalSpread, 0.8, 0.01);
                  player.spawnParticle(Particle.SMOKE, fogCenter, 2, horizontalSpread * 0.7, verticalSpread * 0.7, 0.5, 0.005);
               }
            }
            case THE_END -> {
               player.spawnParticle(Particle.END_ROD, fogCenter, 3, horizontalSpread, verticalSpread, 0.7, 0.01);
               player.spawnParticle(Particle.PORTAL, fogCenter, 4, horizontalSpread * 0.8, verticalSpread * 0.8, 0.6, 0.02);
            }
            default -> {
               player.spawnParticle(Particle.CLOUD, fogCenter, 2, horizontalSpread, verticalSpread, 0.7, 0.005);
               player.spawnParticle(Particle.WHITE_ASH, fogCenter, 2, horizontalSpread, verticalSpread, 0.6, 0.005);
            }
         }
      }
   }

   private void requestRemoteScene(PortalPlane portal) {
      String key = portal.key();
      World world = portal.world();
      if (world != null && this.pendingRemoteScenes.add(key)) {
         int radius = Math.max(8, Math.min(36, Math.max(this.plugin.settings().width() / 2 + 4, this.plugin.settings().depth() + 4)));
         Location center = portal.center();
         int minX = center.getBlockX() - radius;
         int maxX = center.getBlockX() + radius;
         int minZ = center.getBlockZ() - radius;
         int maxZ = center.getBlockZ() + radius;
         int minY = Math.max(world.getMinHeight(), portal.minY() - 6);
         int maxY = Math.min(world.getMaxHeight() - 1, portal.maxY() + Math.max(10, this.plugin.settings().height() / 2 + 4));
         int minChunkX = minX >> 4;
         int maxChunkX = maxX >> 4;
         int minChunkZ = minZ >> 4;
         int maxChunkZ = maxZ >> 4;
         int expectedChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
         AtomicInteger remaining = new AtomicInteger(expectedChunks);
         Map<Long, BlockData> blocks = new ConcurrentHashMap();
         Set<Long> capturedChunks = ConcurrentHashMap.newKeySet();

         for(int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
               final int fChunkX = chunkX;
               final int fChunkZ = chunkZ;
               Location chunkCenter = new Location(world, (double)((fChunkX << 4) + 8), center.getY(), (double)((fChunkZ << 4) + 8));
               Runnable finishCapture = () -> {
                  if (remaining.decrementAndGet() == 0) {
                     if (capturedChunks.size() == expectedChunks) {
                        this.remoteScenes.put(key, new RemoteScene(Map.copyOf(blocks), Set.copyOf(capturedChunks), minX, maxX, minY, maxY, minZ, maxZ, System.currentTimeMillis()));
                     }

                     this.pendingRemoteScenes.remove(key);
                  }
               };
               Runnable capture = () -> {
                  try {
                     if (world.isChunkLoaded(fChunkX, fChunkZ)) {
                        ChunkSnapshot snapshot = world.getChunkAt(fChunkX, fChunkZ).getChunkSnapshot(false, false, false);
                        int fromX = Math.max(minX, fChunkX << 4);
                        int toX = Math.min(maxX, (fChunkX << 4) + 15);
                        int fromZ = Math.max(minZ, fChunkZ << 4);
                        int toZ = Math.min(maxZ, (fChunkZ << 4) + 15);

                        for(int x = fromX; x <= toX; ++x) {
                           for(int z = fromZ; z <= toZ; ++z) {
                              for(int y = minY; y <= maxY; ++y) {
                                 BlockData data = snapshot.getBlockData(x & 15, y, z & 15);
                                 Material material = data.getMaterial();
                                 if (!material.isAir() && material != Material.NETHER_PORTAL) {
                                    blocks.put(blockKey(x, y, z), data);
                                 }
                              }
                           }
                        }

                        capturedChunks.add(chunkKey(fChunkX, fChunkZ));
                     }
                  } catch (RuntimeException exception) {
                     if (this.plugin.settings().debug()) {
                        this.plugin.getLogger().log(Level.WARNING, "Failed to capture scene chunk for portal: " + key, exception);
                     }
                  } finally {
                     finishCapture.run();
                  }

               };
               this.plugin.scheduler().runAt(chunkCenter, () -> {
                  if (world.isChunkLoaded(fChunkX, fChunkZ)) {
                     capture.run();
                  } else {
                     try {
                        PlatformCompatibility.loadChunk(world, fChunkX, fChunkZ).whenComplete((chunk, error) -> {
                           if (error == null && chunk != null) {
                              this.plugin.scheduler().runAt(chunkCenter, capture);
                           } else {
                              finishCapture.run();
                           }
                        });
                     } catch (RuntimeException ignored) {
                        finishCapture.run();
                     }

                  }
               });
            }
         }

      }
   }

   private void updateLocalEntityOcclusion(Player viewer, PortalPlane portal, ViewSession session, Location eye) {
      if (this.plugin.scheduler().isFolia()) {
         this.updateLocalEntityOcclusionFolia(viewer, portal, session, eye);
      } else {
         long generation = ++session.localOcclusionGeneration;
         Set<UUID> shouldHide = new HashSet();
         double radius = this.plugin.settings().activationDistance();
         int viewerSide = this.sideOf(eye, portal);
         session.localOcclusionKey = portal.key() + ":" + viewerSide;

         for(Entity entity : viewer.getWorld().getNearbyEntities(portal.center(), radius, radius, radius)) {
            if (entity instanceof LivingEntity && entity != viewer) {
               Location center = entity.getBoundingBox().getCenter().toLocation(viewer.getWorld());
               if (this.sideOf(center, portal) != viewerSide && this.entityIntersectsPortalView(eye, entity.getBoundingBox(), portal)) {
                  UUID uuid = entity.getUniqueId();
                  if (session.hiddenLocalEntities.put(uuid, generation) == null) {
                     viewer.hideEntity(this.plugin, entity);
                  }

                  shouldHide.add(uuid);
               }
            }
         }

         for(Map.Entry<UUID, Long> entry : (new HashMap<>(session.hiddenLocalEntities)).entrySet()) {
            UUID uuid = entry.getKey();
            if (!shouldHide.contains(uuid) && session.hiddenLocalEntities.remove(uuid, entry.getValue())) {
               Entity entity = Bukkit.getEntity(uuid);
               if (entity != null) {
                  viewer.showEntity(this.plugin, entity);
               }
            }
         }

      }
   }

   private void updateLocalEntityOcclusionFolia(Player viewer, PortalPlane portal, ViewSession session, Location eye) {
      long generation = ++session.localOcclusionGeneration;
      UUID viewerId = viewer.getUniqueId();
      int viewerSide = this.sideOf(eye, portal);
      String occlusionKey = portal.key() + ":" + viewerSide;
      session.localOcclusionKey = occlusionKey;
      double radius = this.plugin.settings().activationDistance();
      Set<UUID> candidates = new HashSet();

      for(Entity entity : viewer.getWorld().getNearbyEntities(portal.center(), radius, radius, radius)) {
         if (entity instanceof LivingEntity && entity != viewer) {
            UUID entityId = entity.getUniqueId();
            candidates.add(entityId);
            this.plugin.scheduler().runForEntity(entity, () -> {
               if (occlusionKey.equals(session.localOcclusionKey) && this.sessions.get(viewerId) == session) {
                  BoundingBox box = entity.getBoundingBox();
                  Location center = box.getCenter().toLocation(portal.world());
                  boolean shouldHide = entity.isValid() && entity.getWorld() == portal.world() && this.sideOf(center, portal) != viewerSide && this.entityIntersectsPortalView(eye, box, portal);
                  if (shouldHide) {
                     if (session.hiddenLocalEntities.put(entityId, generation) == null) {
                        viewer.hideEntity(this.plugin, entity);
                     }
                  } else {
                     this.restoreHiddenLocalEntity(viewer, session, entityId, entity);
                  }

               }
            });
         }
      }

      for(Map.Entry<UUID, Long> entry : (new HashMap<>(session.hiddenLocalEntities)).entrySet()) {
         if (!candidates.contains(entry.getKey())) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            this.restoreHiddenLocalEntity(viewer, session, entry.getKey(), entity);
         }
      }

   }

   private boolean entityIntersectsPortalView(Location eye, BoundingBox box, PortalPlane portal) {
      double[] xs = new double[]{box.getMinX(), box.getCenterX(), box.getMaxX()};
      double[] ys = new double[]{box.getMinY(), box.getCenterY(), box.getMaxY()};
      double[] zs = new double[]{box.getMinZ(), box.getCenterZ(), box.getMaxZ()};

      for(double x : xs) {
         for(double y : ys) {
            for(double z : zs) {
               Location sample = new Location(eye.getWorld(), x, y, z);
               if (PortalProjection.rayPassesOpening(eye, sample, portal)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private long blockKey(Location location) {
      return blockKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());
   }

   private static long blockKey(int x, int y, int z) {
      return (long)(x & 67108863) << 38 | (long)(z & 67108863) << 12 | (long)y & 4095L;
   }

   private static long chunkKey(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private void renderPortalSurfaceOnly(Player player, PortalPlane sourcePortal, ViewSession session) {
      Map<Location, BlockData> next = new LinkedHashMap();
      this.addPortalSurfaceAir(player, sourcePortal, next);
      this.applyChanges(player, session, next, true);
   }

   private void applyChanges(Player player, ViewSession session, Map<Location, BlockData> next, boolean movingFast) {
      boolean completeScene = next.values().stream().anyMatch((data) -> data.getMaterial() != Material.AIR);
      if (session.warmupFrames > 0 && completeScene) {
         Map<Location, BlockData> updates = new LinkedHashMap();

         for(Location old : new ArrayList<>(session.sent.keySet())) {
            if (!next.containsKey(old)) {
               updates.put(old, old.getBlock().getBlockData());
            }
         }

         updates.putAll(next);
         this.sendChanges(player, updates);
         session.sent.clear();
         session.sent.putAll(next);
         session.pending.clear();
         session.confirmations.clear();
         session.missingFrames.clear();
         --session.warmupFrames;
         session.lastReassertAt = System.currentTimeMillis();
      } else if (session.sent.isEmpty()) {
         this.sendChanges(player, next);
         session.sent.putAll(next);
         session.pending.clear();
         session.confirmations.clear();
         session.missingFrames.clear();
         session.lastReassertAt = System.currentTimeMillis();
      } else {
         Map<Location, BlockData> stable = new LinkedHashMap(session.sent);

         for(Map.Entry<Location, BlockData> entry : next.entrySet()) {
            Location location = entry.getKey();
            BlockData desired = entry.getValue();
            session.missingFrames.remove(location);
            if (desired.equals(session.sent.get(location))) {
               session.pending.remove(location);
               session.confirmations.remove(location);
            } else if (!session.sent.containsKey(location)) {
               stable.put(location, desired);
               session.pending.remove(location);
               session.confirmations.remove(location);
            } else if (desired.equals(session.pending.get(location))) {
               int requiredConfirmations = movingFast ? 1 : 2;
               if ((Integer)session.confirmations.merge(location, 1, Integer::sum) >= requiredConfirmations) {
                  stable.put(location, desired);
                  session.pending.remove(location);
                  session.confirmations.remove(location);
               }
            } else {
               session.pending.put(location, desired);
               session.confirmations.put(location, 1);
            }
         }

         for(Location old : new ArrayList<>(session.sent.keySet())) {
            if (!next.containsKey(old)) {
               int removeAfterFrames = movingFast ? 1 : 2;
               if ((Integer)session.missingFrames.merge(old, 1, Integer::sum) >= removeAfterFrames) {
                  stable.remove(old);
                  session.missingFrames.remove(old);
                  session.pending.remove(old);
                  session.confirmations.remove(old);
               }
            }
         }

         Map<Location, BlockData> updates = new LinkedHashMap();

         for(Location old : session.sent.keySet()) {
            if (!stable.containsKey(old)) {
               updates.put(old, old.getBlock().getBlockData());
            }
         }

         for(Map.Entry<Location, BlockData> entry : stable.entrySet()) {
            if (!((BlockData)entry.getValue()).equals(session.sent.get(entry.getKey()))) {
               updates.put((Location)entry.getKey(), (BlockData)entry.getValue());
            }
         }

         long now = System.currentTimeMillis();
         if (now - session.lastReassertAt >= 2500L) {
            updates.putAll(stable);
            session.lastReassertAt = now;
         }

         this.sendChanges(player, updates);
         session.sent.clear();
         session.sent.putAll(stable);
      }
   }

   private void sendChanges(Player player, Map<Location, BlockData> changes) {
      PlatformCompatibility.sendBlockChanges(player, changes);
   }

   private RoomDimensions createRoomDimensions(PortalPlane destinationPortal) {
      PortalConfig config = this.plugin.settings();
      double halfWidth = Math.max(4.0, (double)config.width() * 0.5);
      double bottomY = (double)destinationPortal.minY() - 3.0;
      double ceilingY = (double)destinationPortal.maxY() + Math.max(4.0, (double)config.height() * 0.5);
      double depth = Math.max(4.0, Math.min(60.0, (double)config.depth()));
      return new RoomDimensions(halfWidth, bottomY, ceilingY, depth);
   }

   private RoomCoordinates calculateRoomCoordinates(Location remoteSample, PortalPlane destinationPortal, Vector destinationRight, Vector destinationForward) {
      Vector sampleCenter = new Vector((double)remoteSample.getBlockX() + (double)0.5F, (double)remoteSample.getBlockY() + (double)0.5F, (double)remoteSample.getBlockZ() + (double)0.5F);
      Vector relative = sampleCenter.clone().subtract(destinationPortal.center().toVector());
      double horizontal = relative.dot(destinationRight);
      double depth = relative.dot(destinationForward);
      return new RoomCoordinates(horizontal, sampleCenter.getY(), depth);
   }

   private BlockData createRoomShellBlock(RoomCoordinates coordinates, PortalPlane destinationPortal, RoomDimensions room, Location remoteSample, RemoteScene remoteScene) {
      World destinationWorld = destinationPortal.world();
      if (destinationWorld == null) {
         return null;
      } else {
         double horizontal = coordinates.horizontal();
         double y = coordinates.y();
         double depth = coordinates.depth();
         boolean insideVertical = y >= room.bottomY() - 1.05 && y <= room.ceilingY() + 1.05;
         boolean insideHorizontal = Math.abs(horizontal) <= room.halfWidth() + 1.05;
         boolean sideWall = insideVertical && depth >= 0.75 && depth <= room.depth() + 1.05 && Math.abs(Math.abs(horizontal) - room.halfWidth()) <= 1.05;
         boolean ceiling = insideHorizontal && depth >= 0.75 && depth <= room.depth() + 1.05 && Math.abs(y - room.ceilingY()) <= 1.05;
         boolean floor = insideHorizontal && depth >= 1.75 && depth <= room.depth() + 1.05 && Math.abs(y - room.bottomY()) <= 1.05;
         boolean backWall = insideHorizontal && insideVertical && Math.abs(depth - room.depth()) <= 1.05;
         if (!sideWall && !ceiling && !floor && !backWall) {
            return null;
         }
         BlockData sampled = this.remoteBlockData(remoteSample, remoteScene);
         if (sampled != null && !sampled.getMaterial().isAir() && sampled.getMaterial().isSolid()) {
            return sampled;
         }
         if (destinationWorld.getEnvironment() == Environment.NETHER) {
            return backWall ? Material.BLACKSTONE.createBlockData() : (floor ? Material.BASALT.createBlockData() : Material.NETHERRACK.createBlockData());
         } else if (destinationWorld.getEnvironment() == Environment.THE_END) {
            return Material.END_STONE.createBlockData();
         } else {
            return backWall ? Material.DEEPSLATE.createBlockData() : (floor ? Material.DIRT.createBlockData() : Material.STONE.createBlockData());
         }
      }
   }

   private BlockData remoteBlockData(Location remoteSample, RemoteScene remoteScene) {
      return remoteScene == null ? null : remoteScene.blockDataAt(remoteSample.getBlockX(), remoteSample.getBlockY(), remoteSample.getBlockZ());
   }

   private void putIfVisuallyDifferent(Map<Location, BlockData> candidates, Location location, BlockData desired) {
      if (this.plugin.scheduler().isFolia() || !desired.equals(location.getBlock().getBlockData())) {
         candidates.put(location, desired);
      }
   }

   private boolean isInsideRoomVolume(RoomCoordinates coordinates, RoomDimensions room) {
      return Math.abs(coordinates.horizontal()) < room.halfWidth() && coordinates.y() > room.bottomY() && coordinates.y() < room.ceilingY() && coordinates.depth() >= 0.2 && coordinates.depth() < room.depth();
   }

   private int adaptiveRenderBudget(int configuredBudget, double proximity) {
      int baseBudget = Math.max(100, configuredBudget);
      int closeBudget = Math.max(baseBudget, 14000);
      int result = (int)Math.round(this.lerp((double)baseBudget, (double)closeBudget, proximity));
      return Math.min(24000, result);
   }

   private double proximityFactor(double distanceToPortal) {
      double normalized = ((double)8.0F - distanceToPortal) / 6.9;
      return this.clamp(normalized, (double)0.0F, (double)1.0F);
   }

   private void appendUntilBudget(Map<Location, BlockData> destination, Map<Location, BlockData> source, int maximumSize) {
      for(Map.Entry<Location, BlockData> entry : source.entrySet()) {
         if (destination.size() >= maximumSize) {
            return;
         }

         destination.put((Location)entry.getKey(), (BlockData)entry.getValue());
      }

   }

   private boolean isVisibleThroughPortal(Location eye, Location fakeBlock, PortalPlane portal, Vector portalRight, Vector portalForward) {
      Location center = this.centerOfBlock(fakeBlock);

      for(double[] offset : BLOCK_SAMPLE_OFFSETS) {
         Location target = center.clone().add(portalRight.clone().multiply(offset[0])).add(UP.clone().multiply(offset[1]));
         if (this.intersectsPortal(eye, target, portal, portalRight, portalForward)) {
            return true;
         }
      }

      return false;
   }

   private boolean intersectsPortal(Location eye, Location target, PortalPlane portal, Vector portalRight, Vector portalForward) {
      Vector origin = eye.toVector();
      Vector ray = target.toVector().subtract(origin);
      if (ray.lengthSquared() < 1.0E-7) {
         return false;
      } else {
         Vector planeCenter = portal.center().toVector();
         double denominator = ray.dot(portalForward);
         if (Math.abs(denominator) < 1.0E-7) {
            return false;
         } else {
            double distanceAlongRay = planeCenter.clone().subtract(origin).dot(portalForward) / denominator;
            if (!(distanceAlongRay <= (double)0.0F) && !(distanceAlongRay >= (double)1.0F)) {
               Vector hit = origin.clone().add(ray.clone().multiply(distanceAlongRay));
               Vector relative = hit.clone().subtract(planeCenter);
               double horizontal = relative.dot(portalRight);
               double vertical = relative.getY();
               double halfWidth = (double)this.portalWidth(portal) * (double)0.5F + 0.045;
               double halfHeight = (double)this.portalHeight(portal) * (double)0.5F + 0.045;
               return !(Math.abs(horizontal) > halfWidth) && !(Math.abs(vertical) > halfHeight);
            } else {
               return false;
            }
         }
      }
   }

   private Location mapStableRemoteSample(PortalPlane sourcePortal, PortalPlane destinationPortal, Vector destinationRight, Vector destinationForward, int horizontal, int vertical, int depth) {
      World destinationWorld = destinationPortal.world();
      if (destinationWorld == null) {
         return null;
      } else {
         int sourceCenterY = sourcePortal.center().getBlockY();
         int sampleY = destinationPortal.minY() + sourceCenterY - sourcePortal.minY() + vertical;
         Vector sample = destinationPortal.center().toVector().add(destinationRight.clone().multiply(horizontal)).setY(sampleY).add(destinationForward.clone().multiply(depth));
         return this.blockLocation(new Location(destinationWorld, sample.getX(), sample.getY(), sample.getZ()));
      }
   }

   private void addPortalSurfaceAir(Player player, PortalPlane portal, Map<Location, BlockData> next) {
      BlockData air = Material.AIR.createBlockData();

      for(int x = portal.minX(); x <= portal.maxX(); ++x) {
         for(int y = portal.minY(); y <= portal.maxY(); ++y) {
            for(int z = portal.minZ(); z <= portal.maxZ(); ++z) {
               next.put(new Location(player.getWorld(), (double)x, (double)y, (double)z), air);
            }
         }
      }

   }

   private void restorePortalSurface(Player player, PortalPlane portal) {
      if (player != null && portal != null && portal.world() == player.getWorld()) {
         Map<Location, BlockData> restored = new LinkedHashMap();

         for(int x = portal.minX(); x <= portal.maxX(); ++x) {
            for(int y = portal.minY(); y <= portal.maxY(); ++y) {
               for(int z = portal.minZ(); z <= portal.maxZ(); ++z) {
                  Location location = new Location(player.getWorld(), (double)x, (double)y, (double)z);
                  restored.put(location, location.getBlock().getBlockData());
               }
            }
         }

         this.sendChanges(player, restored);
      }
   }

   private void forcePortalSurfaceAir(Player player, PortalPlane portal) {
      Map<Location, BlockData> forced = new LinkedHashMap();
      this.addPortalSurfaceAir(player, portal, forced);
      this.sendChanges(player, forced);
   }

   private boolean ensureRemoteChunkLoaded(Location location) {
      World world = location.getWorld();
      if (world == null) {
         return false;
      } else {
         int chunkX = location.getBlockX() >> 4;
         int chunkZ = location.getBlockZ() >> 4;
         if (world.isChunkLoaded(chunkX, chunkZ)) {
            this.holdRemoteChunk(world, chunkX, chunkZ);
            return true;
         } else {
            String requestKey = String.valueOf(world.getUID()) + ":" + chunkX + ":" + chunkZ;
            if (this.requestedChunks.add(requestKey)) {
               this.requestChunkCompatible(world, chunkX, chunkZ, requestKey);
            }

            return false;
         }
      }
   }

   private void requestChunkCompatible(World world, int chunkX, int chunkZ, String requestKey) {
      try {
         Method method = world.getClass().getMethod("getChunkAtAsync", Integer.TYPE, Integer.TYPE, Boolean.TYPE);
         Object result = method.invoke(world, chunkX, chunkZ, true);
         if (result instanceof CompletableFuture<?> future) {
            future.whenComplete((chunk, throwable) -> {
               this.requestedChunks.remove(requestKey);
               if (throwable == null) {
                  Location center = new Location(world, (double)((chunkX << 4) + 8), (double)world.getMinHeight(), (double)((chunkZ << 4) + 8));
                  this.plugin.scheduler().runAt(center, () -> this.holdRemoteChunk(world, chunkX, chunkZ));
               }

            });
            return;
         }
      } catch (ReflectiveOperationException ignored) {
      }

      try {
         world.getChunkAt(chunkX, chunkZ);
         this.holdRemoteChunk(world, chunkX, chunkZ);
      } finally {
         this.requestedChunks.remove(requestKey);
      }

   }

   private void keepRemoteRoomLoaded(PortalPlane destinationPortal, RoomDimensions room) {
      World world = destinationPortal.world();
      if (world != null && !this.plugin.scheduler().isFolia()) {
         int radius = (int)Math.ceil(Math.max(room.halfWidth(), room.depth()) + (double)2.0F);
         int minChunkX = destinationPortal.center().getBlockX() - radius >> 4;
         int maxChunkX = destinationPortal.center().getBlockX() + radius >> 4;
         int minChunkZ = destinationPortal.center().getBlockZ() - radius >> 4;
         int maxChunkZ = destinationPortal.center().getBlockZ() + radius >> 4;

         for(int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
               if (world.isChunkLoaded(chunkX, chunkZ)) {
                  this.holdRemoteChunk(world, chunkX, chunkZ);
               } else {
                  String requestKey = String.valueOf(world.getUID()) + ":" + chunkX + ":" + chunkZ;
                  if (this.requestedChunks.add(requestKey)) {
                     this.requestChunkCompatible(world, chunkX, chunkZ, requestKey);
                  }
               }
            }
         }

      }
   }

   private void holdRemoteChunk(World world, int chunkX, int chunkZ) {
      if (world != null && !this.plugin.scheduler().isFolia()) {
         String key = String.valueOf(world.getUID()) + ":" + chunkX + ":" + chunkZ;
         long expiresAt = System.currentTimeMillis() + 5000L;
         HeldRemoteChunk current = (HeldRemoteChunk)this.heldRemoteChunks.get(key);
         if (current != null) {
            this.heldRemoteChunks.put(key, new HeldRemoteChunk(world, chunkX, chunkZ, expiresAt));
         } else {
            if (world.addPluginChunkTicket(chunkX, chunkZ, this.plugin)) {
               this.heldRemoteChunks.put(key, new HeldRemoteChunk(world, chunkX, chunkZ, expiresAt));
            }

         }
      }
   }

   private void releaseExpiredRemoteChunkTickets() {
      if (!this.plugin.scheduler().isFolia()) {
         long now = System.currentTimeMillis();

         for(Map.Entry<String, HeldRemoteChunk> entry : new ArrayList<>(this.heldRemoteChunks.entrySet())) {
            HeldRemoteChunk held = entry.getValue();
            if (held.expiresAt() <= now && this.heldRemoteChunks.remove(entry.getKey(), held)) {
               held.world().removePluginChunkTicket(held.chunkX(), held.chunkZ(), this.plugin);
            }
         }

      }
   }

   private void releaseAllRemoteChunkTickets() {
      if (!this.plugin.scheduler().isFolia()) {
         for(HeldRemoteChunk held : this.heldRemoteChunks.values()) {
            held.world().removePluginChunkTicket(held.chunkX(), held.chunkZ(), this.plugin);
         }
      }

      this.heldRemoteChunks.clear();
   }

   private Location createProjectedBlock(Location portalCenter, Vector right, Vector forward, int horizontal, int vertical, int depth, double verticalOffset) {
      Location projected = portalCenter.clone().add(right.clone().multiply(horizontal)).add((double)0.0F, (double)vertical + verticalOffset, (double)0.0F).add(forward.clone().multiply(depth));
      return this.blockLocation(projected);
   }

   private Vector portalRight(Vector forward) {
      Vector right = forward.clone().crossProduct(UP);
      return right.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : right.normalize();
   }

   private double distanceToPlane(Location location, Location planeCenter, Vector planeNormal) {
      return Math.abs(location.toVector().subtract(planeCenter.toVector()).dot(planeNormal));
   }

   private int portalWidth(PortalPlane portal) {
      return portal.axis() == PortalPlane.Axis.X ? portal.maxX() - portal.minX() + 1 : portal.maxZ() - portal.minZ() + 1;
   }

   private int portalHeight(PortalPlane portal) {
      return portal.maxY() - portal.minY() + 1;
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

   private Location centerOfBlock(Location blockLocation) {
      return blockLocation.clone().add((double)0.5F, (double)0.5F, (double)0.5F);
   }

   private Location blockLocation(Location location) {
      return new Location(location.getWorld(), (double)location.getBlockX(), (double)location.getBlockY(), (double)location.getBlockZ());
   }

   private double lerp(double start, double end, double progress) {
      return start + (end - start) * progress;
   }

   private double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   private BlockData transformBlockData(BlockData original, Vector destinationRight, Vector destinationForward, Vector sourceRight, Vector sourceForward) {
      if (!(original instanceof MultipleFacing originalFacing)) {
         return original;
      } else {
         MultipleFacing transformed = (MultipleFacing)original.clone();

         for(BlockFace face : transformed.getAllowedFaces()) {
            transformed.setFace(face, false);
         }

         for(BlockFace face : originalFacing.getFaces()) {
            BlockFace mapped = this.mapFace(face, destinationRight, destinationForward, sourceRight, sourceForward);
            if (transformed.getAllowedFaces().contains(mapped)) {
               transformed.setFace(mapped, true);
            }
         }

         return transformed;
      }
   }

   private BlockFace mapFace(BlockFace face, Vector destinationRight, Vector destinationForward, Vector sourceRight, Vector sourceForward) {
      if (face != BlockFace.UP && face != BlockFace.DOWN) {
         Vector direction = face.getDirection();
         Vector mapped = sourceRight.clone().multiply(direction.dot(destinationRight)).add(sourceForward.clone().multiply(direction.dot(destinationForward)));
         if (Math.abs(mapped.getX()) >= Math.abs(mapped.getZ())) {
            return mapped.getX() >= (double)0.0F ? BlockFace.EAST : BlockFace.WEST;
         } else {
            return mapped.getZ() >= (double)0.0F ? BlockFace.SOUTH : BlockFace.NORTH;
         }
      } else {
         return face;
      }
   }

   public void clear(Player player) {
      this.disableProjectedEntities(player);
      ViewSession session = (ViewSession)this.sessions.remove(player.getUniqueId());
      if (session != null) {
         this.clearSent(player, session);
      }

   }

   private void clearSent(Player player, ViewSession session) {
      Map<Location, BlockData> restored = new LinkedHashMap();

      for(Location location : new ArrayList<>(session.sent.keySet())) {
         if (location.getWorld() == player.getWorld()) {
            restored.put(location, location.getBlock().getBlockData());
         }
      }

      this.sendChanges(player, restored);
      session.sent.clear();
      session.pending.clear();
      session.confirmations.clear();
      session.missingFrames.clear();
      this.restoreHiddenLocalEntities(player, session);
      session.portalKey = null;
      session.destinationKey = null;
      session.viewerSide = 0;
   }

   private void restoreHiddenLocalEntities(Player player, ViewSession session) {
      ++session.localOcclusionGeneration;
      session.localOcclusionKey = null;
      if (!this.plugin.scheduler().isFolia()) {
         for(UUID uuid : session.hiddenLocalEntities.keySet()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
               player.showEntity(this.plugin, entity);
            }
         }

         session.hiddenLocalEntities.clear();
      } else {
         Map<UUID, Long> hidden = new HashMap(session.hiddenLocalEntities);
         session.hiddenLocalEntities.clear();

         for(UUID uuid : hidden.keySet()) {
            Entity entity = Bukkit.getEntity(uuid);
            this.scheduleShowLocalEntity(player, session, uuid, entity);
         }

      }
   }

   private void restoreHiddenLocalEntity(Player player, ViewSession session, UUID uuid, Entity entity) {
      Long hiddenGeneration = (Long)session.hiddenLocalEntities.remove(uuid);
      if (hiddenGeneration != null && entity != null) {
         this.scheduleShowLocalEntity(player, session, uuid, entity);
      }
   }

   private void scheduleShowLocalEntity(Player player, ViewSession session, UUID uuid, Entity entity) {
      if (entity != null) {
         this.plugin.scheduler().runForEntity(entity, () -> {
            if (!session.hiddenLocalEntities.containsKey(uuid) && player.isOnline()) {
               player.showEntity(this.plugin, entity);
            }

         });
      }
   }

   static {
      AIR_BLOCK = Material.AIR.createBlockData();
   }

   private static record RoomDimensions(double halfWidth, double bottomY, double ceilingY, double depth) {
   }

   private static record RoomCoordinates(double horizontal, double y, double depth) {
   }

   private static record RemoteScene(Map<Long, BlockData> blocks, Set<Long> capturedChunks, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, long capturedAt) {
      BlockData blockDataAt(int x, int y, int z) {
         return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ && this.capturedChunks.contains(PortalRenderService.chunkKey(x >> 4, z >> 4)) ? (BlockData)this.blocks.getOrDefault(PortalRenderService.blockKey(x, y, z), PortalRenderService.AIR_BLOCK) : null;
      }

      boolean expired(int refreshTicks) {
         return System.currentTimeMillis() - this.capturedAt > (long)refreshTicks * 50L;
      }
   }

   private static record HeldRemoteChunk(World world, int chunkX, int chunkZ, long expiresAt) {
   }
}
