package dev.khoa.plugin.cookieportal.end;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import dev.khoa.plugin.cookieportal.platform.PlatformCompatibility;
import dev.khoa.plugin.cookieportal.platform.PortalScheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public final class EndPortalService implements Listener {
   private static final double OPENING_TOLERANCE = 0.06;
   private static final int SCENE_HALF_SPAN = 8;
   private static final double BLOCK_SAMPLE_INSET = 0.08;
   private static final int PORTAL_SCAN_INTERVAL_TICKS = 4;
   private static final long FULL_REASSERT_MILLIS = 2500L;
   private static final int DESCENT_TIMEOUT_TICKS = 600;
   private final CookiePortalPlugin plugin;
   private final Map<UUID, EndViewSession> sessions = new ConcurrentHashMap();
   private final Set<UUID> pendingArrivals = ConcurrentHashMap.newKeySet();
   private final Map<UUID, DescentState> descents = new ConcurrentHashMap();
   private final Map<UUID, Long> fallProtection = new ConcurrentHashMap();
   private final NamespacedKey recoveryPendingKey;
   private final NamespacedKey recoveryWorldKey;
   private final NamespacedKey recoveryXKey;
   private final NamespacedKey recoveryYKey;
   private final NamespacedKey recoveryZKey;
   private final NamespacedKey recoveryAllowFlightKey;
   private final NamespacedKey recoveryFlyingKey;
   private final BlockData air;
   private final BlockData endStone;
   private final BlockData obsidian;
   private final BlockData bedrock;
   private final BlockData ironBars;
   private final BlockData endRod;
   private final BlockData backdrop;
   private volatile EndPortalConfig config;
   private PortalScheduler.Task renderTask;
   private int cachedExitPortalY = 68;
   private long lastExitPortalScan = 0L;

   public EndPortalService(CookiePortalPlugin plugin) {
      this.air = Material.AIR.createBlockData();
      this.endStone = Material.END_STONE.createBlockData();
      this.obsidian = Material.OBSIDIAN.createBlockData();
      this.bedrock = Material.BEDROCK.createBlockData();
      this.ironBars = Material.IRON_BARS.createBlockData();
      this.endRod = Material.END_ROD.createBlockData();
      this.backdrop = Material.BLACK_CONCRETE.createBlockData();
      this.plugin = plugin;
      this.recoveryPendingKey = new NamespacedKey(plugin, "end_descent_pending");
      this.recoveryWorldKey = new NamespacedKey(plugin, "end_descent_world");
      this.recoveryXKey = new NamespacedKey(plugin, "end_descent_x");
      this.recoveryYKey = new NamespacedKey(plugin, "end_descent_y");
      this.recoveryZKey = new NamespacedKey(plugin, "end_descent_z");
      this.recoveryAllowFlightKey = new NamespacedKey(plugin, "end_descent_allow_flight");
      this.recoveryFlyingKey = new NamespacedKey(plugin, "end_descent_flying");
      this.config = EndPortalConfig.read(plugin.getConfig());
   }

   public void start() {
      for(Player player : Bukkit.getOnlinePlayers()) {
         if (this.hasDescentRecovery(player)) {
            this.plugin.scheduler().runForPlayerLater(player, () -> this.recoverInterruptedDescent(player), 1L);
         }
      }

      if (this.config.enabled() && this.config.previewEnabled() && this.renderTask == null) {
         this.renderTask = this.plugin.scheduler().runGlobalTimer(this::tickViews, 1L, (long)this.config.updateIntervalTicks());
      }
   }

   public void reload() {
      this.stopPreview();
      this.config = EndPortalConfig.read(this.plugin.getConfig());
      this.start();
   }

   public void stop() {
      this.stopPreview();
      this.pendingArrivals.clear();
      this.descents.clear();
      this.fallProtection.clear();
   }

   public BlockData projectedBlockData(Player player, Block block) {
      if (player != null && block != null) {
         EndViewSession session = (EndViewSession)this.sessions.get(player.getUniqueId());
         return session == null ? null : (BlockData)session.sent.get(block.getLocation());
      } else {
         return null;
      }
   }

   private void stopPreview() {
      if (this.renderTask != null) {
         this.renderTask.cancel();
         this.renderTask = null;
      }

      if (!this.plugin.isEnabled()) {
         this.sessions.clear();
      } else {
         for(Player player : Bukkit.getOnlinePlayers()) {
            if (this.plugin.scheduler().isFolia()) {
               this.plugin.scheduler().runForPlayer(player, () -> this.clearView(player));
            } else {
               this.clearView(player);
            }
         }

      }
   }

   private void tickViews() {
      for(Player player : Bukkit.getOnlinePlayers()) {
         if (this.plugin.scheduler().isFolia()) {
            this.plugin.scheduler().runForPlayer(player, () -> this.tickView(player));
         } else {
            this.tickView(player);
         }
      }

   }

   private void tickView(Player player) {
      if (player.isOnline() && player.getWorld().getEnvironment() == Environment.NORMAL) {
         EndViewSession session = (EndViewSession)this.sessions.computeIfAbsent(player.getUniqueId(), (ignored) -> new EndViewSession());
         EndPortalPlane portal = this.findNearbyPortal(player, session);
         if (portal != null && this.activelyLookingThrough(player, portal)) {
            this.renderView(player, portal, session);
         } else {
            this.clearSent(player, session);
         }
      } else {
         this.clearView(player);
      }
   }

   private EndPortalPlane findNearbyPortal(Player player, EndViewSession session) {
      Location eye = player.getEyeLocation();
      double maximumDistanceSquared = this.config.activationDistance() * this.config.activationDistance();
      if (session.portal != null && session.portal.world() == player.getWorld() && eye.distanceSquared(session.portal.center()) <= maximumDistanceSquared && session.portal.stillExists()) {
         return session.portal;
      } else {
         long tick = (long)player.getTicksLived();
         if (tick < session.nextScanTick) {
            return null;
         } else {
            session.nextScanTick = tick + 4L;
            session.portal = null;
            Vector direction = eye.getDirection().normalize();
            if (direction.getY() >= -0.12) {
               return null;
            } else {
               int previousX = Integer.MIN_VALUE;
               int previousY = Integer.MIN_VALUE;
               int previousZ = Integer.MIN_VALUE;

               for(double distance = (double)0.0F; distance <= this.config.activationDistance(); distance += 0.2) {
                  int x = (int)Math.floor(eye.getX() + direction.getX() * distance);
                  int y = (int)Math.floor(eye.getY() + direction.getY() * distance);
                  int z = (int)Math.floor(eye.getZ() + direction.getZ() * distance);
                  if (x != previousX || y != previousY || z != previousZ) {
                     previousX = x;
                     previousY = y;
                     previousZ = z;
                     if (y >= player.getWorld().getMinHeight() && y < player.getWorld().getMaxHeight()) {
                        Block block = player.getWorld().getBlockAt(x, y, z);
                        if (block.getType() == Material.END_PORTAL) {
                           EndPortalPlane discovered = this.discoverPortal(block);
                           if (discovered != null && eye.distanceSquared(discovered.center()) <= maximumDistanceSquared) {
                              session.portal = discovered;
                              return discovered;
                           }
                        }
                     }
                  }
               }

               return null;
            }
         }
      }
   }

   private EndPortalPlane discoverPortal(Block seed) {
      int y = seed.getY();
      int minimumX = Integer.MAX_VALUE;
      int maximumX = Integer.MIN_VALUE;
      int minimumZ = Integer.MAX_VALUE;
      int maximumZ = Integer.MIN_VALUE;
      int count = 0;

      for(int x = seed.getX() - 2; x <= seed.getX() + 2; ++x) {
         for(int z = seed.getZ() - 2; z <= seed.getZ() + 2; ++z) {
            if (seed.getWorld().getBlockAt(x, y, z).getType() == Material.END_PORTAL) {
               minimumX = Math.min(minimumX, x);
               maximumX = Math.max(maximumX, x);
               minimumZ = Math.min(minimumZ, z);
               maximumZ = Math.max(maximumZ, z);
               ++count;
            }
         }
      }

      if (count == 9 && maximumX - minimumX == 2 && maximumZ - minimumZ == 2) {
         return new EndPortalPlane(seed.getWorld(), minimumX, maximumX, y, minimumZ, maximumZ);
      } else {
         return null;
      }
   }

   private boolean activelyLookingThrough(Player player, EndPortalPlane portal) {
      Location eye = player.getEyeLocation();
      if (eye.distanceSquared(portal.center()) > this.config.activationDistance() * this.config.activationDistance()) {
         return false;
      } else {
         Vector direction = eye.getDirection().normalize();
         if (direction.getY() >= -0.12) {
            return false;
         } else {
            double progress = (portal.surfaceY() - eye.getY()) / direction.getY();
            if (progress <= (double)0.0F) {
               return false;
            } else {
               double hitX = eye.getX() + direction.getX() * progress;
               double hitZ = eye.getZ() + direction.getZ() * progress;
               return portal.contains(hitX, hitZ, 0.06);
            }
         }
      }
   }

   private void renderView(Player player, EndPortalPlane portal, EndViewSession session) {
      if (session.portalKey == null || !session.portalKey.equals(portal.key())) {
         this.clearSent(player, session);
         session.portal = portal;
         session.portalKey = portal.key();
         session.warmupFrames = 3;
      }

      Map<Location, BlockData> next = new LinkedHashMap();
      this.addPortalSurfaceAir(player, portal, next);
      Location eye = player.getEyeLocation();
      int centerBlockX = portal.minimumX() + 1;
      int centerBlockZ = portal.minimumZ() + 1;
      int depthLimit = this.config.previewDepth();

      World endWorld = this.resolveEndWorld();
      if (endWorld != null && !endWorld.isChunkLoaded(0, 0)) {
         PlatformCompatibility.loadChunk(endWorld, 0, 0);
      }

      for(int depth = 1; depth <= depthLimit; ++depth) {
         int y = portal.y() - depth;

         for(int offsetX = -8; offsetX <= 8; ++offsetX) {
            for(int offsetZ = -8; offsetZ <= 8; ++offsetZ) {
               Location fakeBlock = new Location(player.getWorld(), (double)(centerBlockX + offsetX), (double)y, (double)(centerBlockZ + offsetZ));
               if (this.rayPassesOpening(eye, fakeBlock, portal)) {
                  BlockData blockData = this.sceneBlock(endWorld, offsetX, offsetZ, depth, depthLimit);
                  boolean isInnerShaft = Math.abs(offsetX) <= 1 && Math.abs(offsetZ) <= 1;
                  boolean isShaftWall = (Math.abs(offsetX) == 2 && Math.abs(offsetZ) <= 2) || (Math.abs(offsetZ) == 2 && Math.abs(offsetX) <= 2);

                  if (!isInnerShaft && blockData.getMaterial().isAir()) {
                     if (isShaftWall && depth < depthLimit) {
                        next.put(fakeBlock, depth == 1 ? this.bedrock : this.endStone);
                     }
                  } else {
                     next.put(fakeBlock, blockData);
                  }
               }
            }
         }
      }

      this.applyChanges(player, session, next);
      double particleDistance = this.config.particleMinimumDistance();
      if (particleDistance > (double)0.0F && player.getTicksLived() % 8 == 0 && eye.distanceSquared(portal.center()) >= particleDistance * particleDistance) {
         player.spawnParticle(Particle.REVERSE_PORTAL, portal.center(), 2, (double)0.75F, 0.08, (double)0.75F, 0.01);
      }

   }

   private World resolveEndWorld() {
      for (World world : Bukkit.getWorlds()) {
         if (world.getEnvironment() == Environment.THE_END) {
            return world;
         }
      }
      return null;
   }

   private boolean rayPassesOpening(Location eye, Location fakeBlock, EndPortalPlane portal) {
      double targetY = fakeBlock.getY() + (double)0.5F;
      double[][] samples = new double[][]{{(double)0.5F, (double)0.5F}, {0.08, 0.08}, {0.92, 0.08}, {0.08, 0.92}, {0.92, 0.92}};

      for(double[] sample : samples) {
         if (this.raySamplePassesOpening(eye, fakeBlock.getX() + sample[0], targetY, fakeBlock.getZ() + sample[1], portal)) {
            return true;
         }
      }

      return false;
   }

   private boolean raySamplePassesOpening(Location eye, double targetX, double targetY, double targetZ, EndPortalPlane portal) {
      double denominator = targetY - eye.getY();
      if (denominator >= -1.0E-6) {
         return false;
      } else {
         double progress = (portal.surfaceY() - eye.getY()) / denominator;
         if (!(progress <= (double)0.0F) && !(progress >= (double)1.0F)) {
            double hitX = eye.getX() + (targetX - eye.getX()) * progress;
            double hitZ = eye.getZ() + (targetZ - eye.getZ()) * progress;
            return portal.contains(hitX, hitZ, 0.06);
         } else {
            return false;
         }
      }
   }

   private int findExitPortalY(World endWorld) {
      if (endWorld == null) return 68;
      long now = System.currentTimeMillis();
      if (now - this.lastExitPortalScan < 5000L) {
         return this.cachedExitPortalY;
      }
      this.lastExitPortalScan = now;
      if (!endWorld.isChunkLoaded(0, 0)) {
         return this.cachedExitPortalY;
      }
      for (int y = 80; y >= 50; y--) {
         Material type = endWorld.getBlockAt(0, y, 0).getType();
         if (type == Material.DRAGON_EGG) {
            this.cachedExitPortalY = y;
            return y;
         }
      }
      for (int y = 80; y >= 50; y--) {
         Material type = endWorld.getBlockAt(0, y, 0).getType();
         if (type == Material.END_PORTAL || type == Material.BEDROCK) {
            this.cachedExitPortalY = y + 3;
            return this.cachedExitPortalY;
         }
      }
      return this.cachedExitPortalY;
   }

   private BlockData sceneBlock(World endWorld, int x, int z, int depth, int depthLimit) {
      if (depth == depthLimit || Math.abs(x) == 8 || Math.abs(z) == 8) {
         return this.backdrop;
      }

      int topY = this.findExitPortalY(endWorld);
      int sampleY = topY - (depth - 1);

      if (endWorld != null && sampleY >= endWorld.getMinHeight() && sampleY < endWorld.getMaxHeight()) {
         if (endWorld.isChunkLoaded(x >> 4, z >> 4)) {
            BlockData realData = endWorld.getBlockAt(x, sampleY, z).getBlockData();
            if (!realData.getMaterial().isAir()) {
               return realData;
            }
         }
      }

      return this.syntheticExitPortalBlock(x, z, depth);
   }

   private BlockData syntheticExitPortalBlock(int x, int z, int depth) {
      int absX = Math.abs(x);
      int absZ = Math.abs(z);

      if (depth == 1 && absX == 0 && absZ == 0) {
         return Material.DRAGON_EGG.createBlockData();
      }
      if (depth == 2 && absX == 0 && absZ == 0) {
         return this.bedrock;
      }
      if (depth == 3 && absX <= 1 && absZ <= 1) {
         return Material.END_PORTAL.createBlockData();
      }
      if ((depth == 3 || depth == 4) && absX <= 2 && absZ <= 2) {
         return this.bedrock;
      }
      if (depth >= 5) {
         int surface = this.islandSurfaceDepth(x, z);
         if (surface >= 0 && depth >= surface) {
            return this.endStone;
         }
      }
      return this.air;
   }

   private int islandSurfaceDepth(int x, int z) {
      long noise = this.mix((long)x * 73428767L ^ (long)z * 912931L);
      double edge = (double)4.25F + (double)(Math.floorMod(noise, 5L) - 2L) * 0.18;
      double distance = Math.sqrt((double)(x * x) * 0.92 + (double)(z * z) * 1.08);
      if (distance > edge) {
         return -1;
      } else {
         int relief = Math.floorMod(noise >>> 8, 7L) < 2L ? 1 : 0;
         if (Math.abs(x) + Math.abs(z) <= 2) {
            relief = 0;
         }

         return 5 + relief;
      }
   }

   private void addPortalSurfaceAir(Player player, EndPortalPlane portal, Map<Location, BlockData> changes) {
      for(int x = portal.minimumX(); x <= portal.maximumX(); ++x) {
         for(int z = portal.minimumZ(); z <= portal.maximumZ(); ++z) {
            changes.put(new Location(player.getWorld(), (double)x, (double)portal.y(), (double)z), this.air);
         }
      }

   }

   private void applyChanges(Player player, EndViewSession session, Map<Location, BlockData> next) {
      Map<Location, BlockData> updates = new LinkedHashMap();
      if (session.warmupFrames > 0) {
         for(Location old : new ArrayList<>(session.sent.keySet())) {
            if (!next.containsKey(old)) {
               updates.put(old, old.getBlock().getBlockData());
            }
         }

         updates.putAll(next);
         --session.warmupFrames;
      } else {
         for(Location old : session.sent.keySet()) {
            if (!next.containsKey(old)) {
               updates.put(old, old.getBlock().getBlockData());
            }
         }

         for(Map.Entry<Location, BlockData> entry : next.entrySet()) {
            if (!((BlockData)entry.getValue()).equals(session.sent.get(entry.getKey()))) {
               updates.put(entry.getKey(), entry.getValue());
            }
         }
      }

      this.sendChanges(player, updates);
      session.sent.clear();
      session.sent.putAll(next);
   }

   private void sendChanges(Player player, Map<Location, BlockData> changes) {
      if (changes.isEmpty()) return;
      PlatformCompatibility.sendBlockChanges(player, changes);
   }

   private void clearView(Player player) {
      EndViewSession session = (EndViewSession)this.sessions.remove(player.getUniqueId());
      if (session != null) {
         this.clearSent(player, session);
      }

   }

   private void clearSent(Player player, EndViewSession session) {
      for(Location location : new ArrayList<>(session.sent.keySet())) {
         if (location.getWorld() == player.getWorld()) {
            player.sendBlockChange(location, location.getBlock().getBlockData());
         }
      }

      session.sent.clear();
      session.portalKey = null;
      session.warmupFrames = 0;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onEndPortal(PlayerTeleportEvent event) {
      if (this.config.enabled() && this.config.skyArrivalEnabled() && event.getCause() == TeleportCause.END_PORTAL && event.getFrom().getWorld() != null && event.getFrom().getWorld().getEnvironment() == Environment.NORMAL && event.getTo() != null && event.getTo().getWorld() != null && event.getTo().getWorld().getEnvironment() == Environment.THE_END) {
         this.pendingArrivals.add(event.getPlayer().getUniqueId());
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onChangedWorld(PlayerChangedWorldEvent event) {
      Player player = event.getPlayer();
      if (this.pendingArrivals.remove(player.getUniqueId()) && player.getWorld().getEnvironment() == Environment.THE_END) {
         this.plugin.scheduler().runForPlayerLater(player, () -> this.prepareSkyArrival(player), 1L);
      }
   }

   private void prepareSkyArrival(Player player) {
      if (player.isOnline() && player.getWorld().getEnvironment() == Environment.THE_END) {
         World world = player.getWorld();
         long hash = this.mix(player.getUniqueId().getMostSignificantBits() ^ player.getUniqueId().getLeastSignificantBits());
         double angle = (double)Math.floorMod(hash, 360L) * Math.PI / (double)180.0F;
         double radius = (double)22.0F + (double)Math.floorMod(hash >>> 10, 5L);
         int x = (int)Math.round(Math.cos(angle) * radius);
         int z = (int)Math.round(Math.sin(angle) * radius);
         float arrivalYaw = player.getLocation().getYaw();
         int chunkX = x >> 4;
         int chunkZ = z >> 4;
         PlatformCompatibility.loadChunk(world, chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null) {
               this.plugin.getLogger().warning("No se pudo preparar la llegada aerea al End: " + error.getMessage());
            } else {
               Location regionAnchor = new Location(world, (double)x, (double)world.getMinHeight(), (double)z);
               this.plugin.scheduler().runAt(regionAnchor, () -> {
                  int groundY = this.findLandingGround(world, x, z);
                  int arrivalY = Math.min(world.getMaxHeight() - 4, groundY + this.config.heightAboveGround());
                  Location target = new Location(world, (double)x + (double)0.5F, (double)arrivalY, (double)z + (double)0.5F, arrivalYaw, 55.0F);
                  this.plugin.scheduler().runForPlayer(player, () -> this.teleportToSky(player, target, (double)groundY + (double)1.0F));
               });
            }
         });
      }
   }

   private int findLandingGround(World world, int x, int z) {
      for(int y = world.getMaxHeight() - 3; y >= world.getMinHeight() + 1; --y) {
         Block floor = world.getBlockAt(x, y, z);
         if (floor.getType().isSolid() && world.getBlockAt(x, y + 1, z).isPassable() && world.getBlockAt(x, y + 2, z).isPassable()) {
            return y;
         }
      }

      return 64;
   }

   private void teleportToSky(Player player, Location target, double landingY) {
      if (player.isOnline() && player.getWorld() == target.getWorld()) {
         this.markDescentRecovery(player, target, landingY);
         player.setFallDistance(0.0F);
         PlatformCompatibility.teleport(player, target).whenComplete((success, error) -> {
            if (error == null && Boolean.TRUE.equals(success)) {
               this.plugin.scheduler().runForPlayerLater(player, () -> {
                  DescentState state = new DescentState(this.config.hoverTicks(), 600, landingY);
                  this.descents.put(player.getUniqueId(), state);
                  this.tickDescent(player);
               }, 1L);
            } else {
               String errMsg = error == null ? "" : " " + error.getMessage();
               this.plugin.getLogger().warning("Failed to elevate player in the End: " + errMsg);
               if (player.isOnline()) {
                  this.plugin.scheduler().runForPlayer(player, () -> {
                     this.restoreOriginalFlightState(player);
                     this.clearDescentRecovery(player);
                  });
               }

            }
         });
      }
   }

   private void tickDescent(Player player) {
      DescentState state = (DescentState)this.descents.get(player.getUniqueId());
      if (state != null && player.isOnline() && player.getWorld().getEnvironment() == Environment.THE_END) {
         player.setFallDistance(0.0F);
         if (state.hoverTicks > 0) {
            Vector velocity = player.getVelocity();
            velocity.setY((double)0.0F);
            player.setVelocity(velocity);
            --state.hoverTicks;
         } else {
            Vector velocity = player.getVelocity();
            velocity.setY(-this.config.descentSpeed());
            player.setVelocity(velocity);
         }

         --state.remainingTicks;
         boolean landed = state.hoverTicks <= 0 && (player.isOnGround() || player.getLocation().getY() <= state.landingY + 0.15);
         if (!landed && state.remainingTicks > 0) {
            this.plugin.scheduler().runForPlayerLater(player, () -> this.tickDescent(player), 1L);
         } else {
            this.finishDescent(player);
         }
      } else {
         this.descents.remove(player.getUniqueId());
      }
   }

   private void finishDescent(Player player) {
      this.descents.remove(player.getUniqueId());
      player.setFallDistance(0.0F);
      this.restoreOriginalFlightState(player);
      this.clearDescentRecovery(player);
      this.fallProtection.put(player.getUniqueId(), System.currentTimeMillis() + (long)this.config.fallProtectionTicks() * 50L);
   }

   private void markDescentRecovery(Player player, Location skyTarget, double landingY) {
      PersistentDataContainer data = player.getPersistentDataContainer();
      if (!data.has(this.recoveryPendingKey, PersistentDataType.BYTE)) {
         data.set(this.recoveryAllowFlightKey, PersistentDataType.BYTE, player.getAllowFlight() ? (byte)1 : (byte)0);
         data.set(this.recoveryFlyingKey, PersistentDataType.BYTE, player.isFlying() ? (byte)1 : (byte)0);
      }

      data.set(this.recoveryPendingKey, PersistentDataType.BYTE, (byte)1);
      data.set(this.recoveryWorldKey, PersistentDataType.STRING, skyTarget.getWorld().getUID().toString());
      data.set(this.recoveryXKey, PersistentDataType.DOUBLE, skyTarget.getX());
      data.set(this.recoveryYKey, PersistentDataType.DOUBLE, landingY);
      data.set(this.recoveryZKey, PersistentDataType.DOUBLE, skyTarget.getZ());
   }

   private boolean hasDescentRecovery(Player player) {
      return player.getPersistentDataContainer().has(this.recoveryPendingKey, PersistentDataType.BYTE);
   }

   private void recoverInterruptedDescent(Player player) {
      if (player.isOnline() && this.hasDescentRecovery(player)) {
         PersistentDataContainer data = player.getPersistentDataContainer();
         String worldId = (String)data.get(this.recoveryWorldKey, PersistentDataType.STRING);
         Double x = (Double)data.get(this.recoveryXKey, PersistentDataType.DOUBLE);
         Double y = (Double)data.get(this.recoveryYKey, PersistentDataType.DOUBLE);
         Double z = (Double)data.get(this.recoveryZKey, PersistentDataType.DOUBLE);
         World world = this.recoveryWorld(worldId);
         this.restoreOriginalFlightState(player);
         player.setVelocity(new Vector((double)0.0F, (double)0.0F, (double)0.0F));
         player.setFallDistance(0.0F);
         if (world != null && world.getEnvironment() == Environment.THE_END && x != null && y != null && z != null) {
            Location landing = new Location(world, x, y, z, player.getLocation().getYaw(), 0.0F);
            PlatformCompatibility.teleport(player, landing).whenComplete((success, error) -> {
               if (error == null && Boolean.TRUE.equals(success)) {
                  this.plugin.scheduler().runForPlayer(player, () -> {
                     this.restoreOriginalFlightState(player);
                     player.setVelocity(new Vector((double)0.0F, (double)0.0F, (double)0.0F));
                     player.setFallDistance(0.0F);
                     this.descents.remove(player.getUniqueId());
                     this.fallProtection.put(player.getUniqueId(), System.currentTimeMillis() + (long)this.config.fallProtectionTicks() * 50L);
                     this.clearDescentRecovery(player);
                  });
               } else {
                  this.plugin.getLogger().warning("Failed to recover landing for " + player.getName() + " in the End: " + (error == null ? "" : " " + error.getMessage()));
               }
            });
         } else {
            this.plugin.getLogger().warning("Discarded incomplete End descent recovery for " + player.getName() + ".");
            this.clearDescentRecovery(player);
         }
      }
   }

   private World recoveryWorld(String worldId) {
      if (worldId == null) {
         return null;
      } else {
         try {
            return Bukkit.getWorld(UUID.fromString(worldId));
         } catch (IllegalArgumentException ignored) {
            return null;
         }
      }
   }

   private void restoreOriginalFlightState(Player player) {
      PersistentDataContainer data = player.getPersistentDataContainer();
      boolean allowedBefore = Byte.valueOf((byte)1).equals(data.get(this.recoveryAllowFlightKey, PersistentDataType.BYTE));
      boolean flyingBefore = allowedBefore && Byte.valueOf((byte)1).equals(data.get(this.recoveryFlyingKey, PersistentDataType.BYTE));
      if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
         player.setFlying(false);
         player.setAllowFlight(allowedBefore);
         if (flyingBefore) {
            player.setFlying(true);
         }

      } else {
         player.setAllowFlight(true);
         player.setFlying(flyingBefore);
      }
   }

   private void clearDescentRecovery(Player player) {
      PersistentDataContainer data = player.getPersistentDataContainer();
      data.remove(this.recoveryPendingKey);
      data.remove(this.recoveryWorldKey);
      data.remove(this.recoveryXKey);
      data.remove(this.recoveryYKey);
      data.remove(this.recoveryZKey);
      data.remove(this.recoveryAllowFlightKey);
      data.remove(this.recoveryFlyingKey);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onFallDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player) {
         if (event.getCause() == DamageCause.FALL) {
            UUID uuid = player.getUniqueId();
            long protectedUntil = (Long)this.fallProtection.getOrDefault(uuid, 0L);
            if (!this.descents.containsKey(uuid) && protectedUntil <= System.currentTimeMillis()) {
               this.fallProtection.remove(uuid);
               return;
            }

            event.setCancelled(true);
            player.setFallDistance(0.0F);
            return;
         }
      }

   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (this.hasDescentRecovery(player)) {
         this.plugin.scheduler().runForPlayerLater(player, () -> this.recoverInterruptedDescent(player), 1L);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      Player player = event.getPlayer();
      UUID uuid = player.getUniqueId();
      boolean interruptedDescent = this.descents.remove(uuid) != null || this.hasDescentRecovery(player);
      if (interruptedDescent) {
         this.restoreOriginalFlightState(player);
         player.setVelocity(new Vector((double)0.0F, (double)0.0F, (double)0.0F));
         player.setFallDistance(0.0F);
      }

      this.sessions.remove(uuid);
      this.pendingArrivals.remove(uuid);
      this.fallProtection.remove(uuid);
   }

   private long mix(long value) {
      value ^= value >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      return value ^ value >>> 33;
   }

   private static final class EndViewSession {
      private EndPortalPlane portal;
      private String portalKey;
      private long nextScanTick;
      private int warmupFrames;
      private long lastReassertAt;
      private final Map<Location, BlockData> sent = new HashMap();
   }

   private static final class DescentState {
      private int hoverTicks;
      private int remainingTicks;
      private final double landingY;

      private DescentState(int hoverTicks, int remainingTicks, double landingY) {
         this.hoverTicks = hoverTicks;
         this.remainingTicks = remainingTicks;
         this.landingY = landingY;
      }
   }

   private static record EndPortalPlane(World world, int minimumX, int maximumX, int y, int minimumZ, int maximumZ) {
      private double surfaceY() {
         return (double)this.y + (double)0.75F;
      }

      private Location center() {
         return new Location(this.world, (double)(this.minimumX + this.maximumX + 1) * (double)0.5F, this.surfaceY(), (double)(this.minimumZ + this.maximumZ + 1) * (double)0.5F);
      }

      private boolean contains(double x, double z, double tolerance) {
         return x >= (double)this.minimumX - tolerance && x <= (double)this.maximumX + (double)1.0F + tolerance && z >= (double)this.minimumZ - tolerance && z <= (double)this.maximumZ + (double)1.0F + tolerance;
      }

      private boolean stillExists() {
         for(int x = this.minimumX; x <= this.maximumX; ++x) {
            for(int z = this.minimumZ; z <= this.maximumZ; ++z) {
               if (this.world.getBlockAt(x, this.y, z).getType() != Material.END_PORTAL) {
                  return false;
               }
            }
         }

         return true;
      }

      private String key() {
         return this.world.getUID() + ":" + this.minimumX + ":" + this.y + ":" + this.minimumZ;
      }
   }
}
