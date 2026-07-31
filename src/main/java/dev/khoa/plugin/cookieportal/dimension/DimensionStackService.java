package dev.khoa.plugin.cookieportal.dimension;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import dev.khoa.plugin.cookieportal.platform.PlatformCompatibility;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Vector;

public final class DimensionStackService implements Listener {
   private static final long HASH_X = 341873128712L;
   private static final long HASH_Z = 132897987541L;
   private static final double LEGACY_SQUARE_HOLE_CHANCE = 0.45;
   private final CookiePortalPlugin plugin;
   private final Set<UUID> travelling = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Long> cooldowns = new ConcurrentHashMap();
   private final Map<UUID, Long> fallProtection = new ConcurrentHashMap();
   private volatile DimensionStackConfig config;

   public DimensionStackService(CookiePortalPlugin plugin) {
      this.plugin = plugin;
      this.reload();
   }

   public void reload() {
      this.config = DimensionStackConfig.read(this.plugin.getConfig());
   }

   public boolean enabled() {
      return this.config.enabled();
   }

   public void scanLoadedChunks() {
      if (this.config.enabled() && this.config.generateHoles()) {
         for(World world : this.plugin.getServer().getWorlds()) {
            if (this.supportsHoles(world)) {
               for(Chunk chunk : world.getLoadedChunks()) {
                  this.plugin.scheduler().runAt(chunk.getBlock(8, world.getMinHeight(), 8).getLocation(), () -> this.carveHole(chunk));
               }
            }
         }

      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onChunkLoad(ChunkLoadEvent event) {
      if (this.config.enabled() && this.config.generateHoles()) {
         this.carveHole(event.getChunk());
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent event) {
      Location to = event.getTo();
      if (to != null && this.config.enabled()) {
         Player player = event.getPlayer();
         UUID playerId = player.getUniqueId();
         long now = System.currentTimeMillis();
         if (this.travelling.contains(playerId)) {
            World world = to.getWorld();
            if (world != null && to.getY() < (double)world.getMinHeight() - (double)16.0F) {
               Vector velocity = player.getVelocity();
               if (velocity.getY() < (double)0.0F) {
                  player.setVelocity(velocity.setY((double)0.0F));
                  player.setFallDistance(0.0F);
               }
            }

         } else if ((Long)this.cooldowns.getOrDefault(playerId, 0L) <= now) {
            Transition transition = this.transitionAt(to);
            if (transition != null) {
               this.travelling.add(playerId);
               this.beginTransition(player, transition, to);
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      UUID playerId = event.getPlayer().getUniqueId();
      this.travelling.remove(playerId);
      this.cooldowns.remove(playerId);
      this.fallProtection.remove(playerId);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onFallDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player) {
         UUID playerId = player.getUniqueId();
         if (event.getCause() == DamageCause.VOID && this.travelling.contains(playerId)) {
            event.setCancelled(true);
            player.setFallDistance(0.0F);
            Vector velocity = player.getVelocity();
            if (velocity.getY() < 0.0) {
               player.setVelocity(velocity.setY(0.0));
            }
         } else if (event.getCause() == DamageCause.FALL) {
            long protectedUntil = (Long)this.fallProtection.getOrDefault(playerId, 0L);
            if (protectedUntil <= System.currentTimeMillis()) {
               this.fallProtection.remove(playerId);
            } else {
               event.setCancelled(true);
               player.setFallDistance(0.0F);
            }
         }
      }
   }

   private Transition transitionAt(Location location) {
      World world = location.getWorld();
      if (world == null) {
         return null;
      }
      return switch (world.getEnvironment()) {
         case NORMAL -> location.getY() < (double)world.getMinHeight() - 2.0 ? Transition.OVERWORLD_TO_NETHER : null;
         case NETHER -> location.getY() >= (double)this.netherRoofY(world) + 1.0 ? Transition.NETHER_TO_OVERWORLD : null;
         case THE_END -> location.getY() < (double)world.getMinHeight() - 2.0 ? Transition.END_TO_OVERWORLD : null;
         default -> null;
      };
   }

   private void beginTransition(Player player, Transition transition, Location source) {
      World destinationWorld = switch (transition) {
         case OVERWORLD_TO_NETHER -> this.resolveWorld(this.config.netherName(), Environment.NETHER);
         case NETHER_TO_OVERWORLD, END_TO_OVERWORLD -> this.resolveWorld(this.config.overworldName(), Environment.NORMAL);
      };

      if (destinationWorld == null) {
         this.fail(player, "Could not find destination world for " + transition + ".", null);
      } else {
         double x = switch (transition) {
            case OVERWORLD_TO_NETHER -> source.getX() / 8.0;
            case NETHER_TO_OVERWORLD -> source.getX() * 8.0;
            case END_TO_OVERWORLD -> source.getX();
         };

         double z = switch (transition) {
            case OVERWORLD_TO_NETHER -> source.getZ() / 8.0;
            case NETHER_TO_OVERWORLD -> source.getZ() * 8.0;
            case END_TO_OVERWORLD -> source.getZ();
         };

         int chunkX = this.floor(x) >> 4;
         int chunkZ = this.floor(z) >> 4;
         float yaw = source.getYaw();
         float pitch = source.getPitch();
         Vector velocity = player.getVelocity().clone();
         PlatformCompatibility.loadChunk(destinationWorld, chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null) {
               this.fail(player, "Failed to load target chunk for dimension transition.", error);
            } else {
               Location anchor = new Location(destinationWorld, x, (double)this.config.netherArrivalY(), z);
               this.plugin.scheduler().runAt(anchor, () -> {
                  try {
                     Location target = this.createTarget(transition, destinationWorld, x, z, yaw, pitch);
                     this.teleport(player, target, transition, velocity);
                  } catch (Throwable throwable) {
                     this.fail(player, "Failed to prepare safe landing target.", throwable);
                  }
               });
            }
         });
      }
   }

   private Location createTarget(Transition transition, World world, double x, double z, float yaw, float pitch) {
      Location target = switch (transition) {
         case OVERWORLD_TO_NETHER -> this.safeNetherTarget(world, x, z);
         case NETHER_TO_OVERWORLD -> this.safeOverworldMineTarget(world, x, z);
         case END_TO_OVERWORLD -> new Location(world, x, (double)(world.getMaxHeight() + this.config.endDropHeight()), z);
      };

      target.setYaw(yaw);
      target.setPitch(pitch);
      return target;
   }

   private Location safeNetherTarget(World world, double x, double z) {
      int blockX = this.floor(x);
      int blockZ = this.floor(z);
      int minimum = Math.max(world.getMinHeight() + 8, 8);
      int maximum = Math.min(this.netherRoofY(world) - 5, world.getMaxHeight() - 4);
      int preferred = Math.max(minimum, Math.min(maximum, this.config.netherArrivalY()));

      for(int offset = 0; offset <= maximum - minimum; ++offset) {
         int above = preferred + offset;
         if (above <= maximum && this.safeStandingColumn(world, blockX, above, blockZ)) {
            return this.centered(world, blockX, above, blockZ, x, z);
         }

         int below = preferred - offset;
         if (offset > 0 && below >= minimum && this.safeStandingColumn(world, blockX, below, blockZ)) {
            return this.centered(world, blockX, below, blockZ, x, z);
         }
      }

      int y = preferred;
      world.getBlockAt(blockX, preferred - 1, blockZ).setType(Material.NETHERRACK, false);

      for(int clearY = preferred; clearY <= y + 2; ++clearY) {
         world.getBlockAt(blockX, clearY, blockZ).setType(Material.AIR, false);
      }

      return this.centered(world, blockX, y, blockZ, x, z);
   }

   private Location safeOverworldMineTarget(World world, double x, double z) {
      int originalX = this.floor(x);
      int originalZ = this.floor(z);
      int blockX = this.keepRoomInsideChunk(originalX);
      int blockZ = this.keepRoomInsideChunk(originalZ);
      int minimum = world.getMinHeight() + 8;
      int maximum = Math.min(world.getSeaLevel() - 8, world.getMaxHeight() - 8);
      int preferred = Math.max(minimum, Math.min(maximum, this.config.overworldArrivalY()));

      for(int offset = 0; offset <= maximum - minimum; ++offset) {
         int below = preferred - offset;
         if (below >= minimum && this.safeCaveRoom(world, blockX, below, blockZ)) {
            return this.centered(world, blockX, below, blockZ, (double)blockX + (double)0.5F, (double)blockZ + (double)0.5F);
         }

         int above = preferred + offset;
         if (offset > 0 && above <= maximum && this.safeCaveRoom(world, blockX, above, blockZ)) {
            return this.centered(world, blockX, above, blockZ, (double)blockX + (double)0.5F, (double)blockZ + (double)0.5F);
         }
      }

      this.createSafeMineRoom(world, blockX, preferred, blockZ);
      return this.centered(world, blockX, preferred, blockZ, (double)blockX + (double)0.5F, (double)blockZ + (double)0.5F);
   }

   private boolean safeCaveRoom(World world, int centerX, int y, int centerZ) {
      for(int x = centerX - 1; x <= centerX + 1; ++x) {
         for(int z = centerZ - 1; z <= centerZ + 1; ++z) {
            Material floor = world.getBlockAt(x, y - 1, z).getType();
            if (!floor.isSolid() || this.dangerous(floor)) {
               return false;
            }

            for(int airY = y; airY <= y + 2; ++airY) {
               Block block = world.getBlockAt(x, airY, z);
               if (!block.isPassable() || this.dangerous(block.getType()) || !block.getType().isAir()) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   private void createSafeMineRoom(World world, int centerX, int y, int centerZ) {
      Material shell = y < 0 ? Material.DEEPSLATE : Material.STONE;

      for(int x = centerX - 3; x <= centerX + 3; ++x) {
         for(int z = centerZ - 3; z <= centerZ + 3; ++z) {
            for(int roomY = y - 1; roomY <= y + 4; ++roomY) {
               boolean wall = Math.abs(x - centerX) == 3 || Math.abs(z - centerZ) == 3 || roomY == y - 1 || roomY == y + 4;
               world.getBlockAt(x, roomY, z).setType(wall ? shell : Material.AIR, false);
            }
         }
      }

      world.getBlockAt(centerX - 2, y, centerZ - 2).setType(Material.TORCH, false);
      world.getBlockAt(centerX + 2, y, centerZ - 2).setType(Material.TORCH, false);
      world.getBlockAt(centerX - 2, y, centerZ + 2).setType(Material.TORCH, false);
      world.getBlockAt(centerX + 2, y, centerZ + 2).setType(Material.TORCH, false);
   }

   private int keepRoomInsideChunk(int coordinate) {
      int chunkStart = Math.floorDiv(coordinate, 16) * 16;
      return Math.max(chunkStart + 3, Math.min(chunkStart + 12, coordinate));
   }

   private Location centered(World world, int blockX, int y, int blockZ, double originalX, double originalZ) {
      double localX = originalX - Math.floor(originalX);
      double localZ = originalZ - Math.floor(originalZ);
      localX = Math.max(0.31, Math.min(0.69, localX));
      localZ = Math.max(0.31, Math.min(0.69, localZ));
      return new Location(world, (double)blockX + localX, (double)y, (double)blockZ + localZ);
   }

   private boolean safeStandingColumn(World world, int x, int y, int z) {
      Block floor = world.getBlockAt(x, y - 1, z);
      Block feet = world.getBlockAt(x, y, z);
      Block head = world.getBlockAt(x, y + 1, z);
      return floor.getType().isSolid() && !this.dangerous(floor.getType()) && feet.isPassable() && !this.dangerous(feet.getType()) && head.isPassable() && !this.dangerous(head.getType());
   }

   private boolean dangerous(Material material) {
      return switch (material) {
         case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW, SWEET_BERRY_BUSH -> true;
         default -> false;
      };
   }

   private void teleport(Player player, Location target, Transition transition, Vector oldVelocity) {
      this.plugin.scheduler().runForPlayer(player, () -> {
         if (!player.isOnline()) {
            this.travelling.remove(player.getUniqueId());
         } else {
            player.setFallDistance(0.0F);
            PlatformCompatibility.teleport(player, target).whenComplete((success, error) -> {
               if (error == null && Boolean.TRUE.equals(success)) {
                  UUID playerId = player.getUniqueId();
                  this.cooldowns.put(playerId, System.currentTimeMillis() + this.config.transitionCooldownMillis());
                  if (transition != DimensionStackService.Transition.END_TO_OVERWORLD) {
                     this.fallProtection.put(playerId, System.currentTimeMillis() + 3000L);
                  }

                  this.plugin.scheduler().runForPlayerLater(player, () -> {
                     Vector velocity = oldVelocity.clone();
                     if (transition == DimensionStackService.Transition.END_TO_OVERWORLD) {
                        velocity.setY(Math.min(-0.8, velocity.getY()));
                     } else {
                        velocity.setY(0.0);
                     }

                     player.setFallDistance(0.0F);
                     player.setVelocity(velocity);
                     this.travelling.remove(playerId);
                  }, 1L);
               } else {
                  this.fail(player, "Dimension transition teleport failed.", error);
               }
            });
         }
      });
   }

   private void fail(Player player, String message, Throwable throwable) {
      UUID playerId = player.getUniqueId();
      this.travelling.remove(playerId);
      this.cooldowns.put(playerId, System.currentTimeMillis() + this.config.transitionCooldownMillis());
      if (player.isOnline()) {
         this.plugin.scheduler().runForPlayer(player, () -> {
            player.sendMessage(ChatColor.RED + "[CookiePortal] " + message);
         });
      }

      if (throwable == null) {
         this.plugin.getLogger().warning(message);
      } else {
         this.plugin.getLogger().log(Level.WARNING, message, throwable);
      }

   }

   private World resolveWorld(String configuredName, World.Environment environment) {
      if (configuredName != null && !configuredName.isBlank()) {
         World configured = this.plugin.getServer().getWorld(configuredName);
         return configured != null && configured.getEnvironment() == environment ? configured : null;
      } else {
         return this.plugin.getServer().getWorlds().stream().filter((world) -> world.getEnvironment() == environment).findFirst().orElse(null);
      }
   }

   private void carveHole(Chunk chunk) {
      World world = chunk.getWorld();
      if (this.config.enabled() && this.config.generateHoles() && this.supportsHoles(world)) {
         long hash = this.mix(world.getSeed() ^ (long)chunk.getX() * 341873128712L ^ (long)chunk.getZ() * 132897987541L);
         double roll = (double)(hash >>> 11) * (double)1.110223E-16F;
         boolean generateNewCrack = roll < this.config.holeChance();
         if (generateNewCrack || !(roll >= 0.45)) {
            int radius = this.config.holeRadius();
            int margin = radius + 2;
            int range = 16 - margin * 2;
            int localX = margin + Math.floorMod((int)hash, range);
            int localZ = margin + Math.floorMod((int)(hash >>> 32), range);
            int minY;
            int maxY;
            if (world.getEnvironment() == Environment.NORMAL) {
               minY = world.getMinHeight();
               maxY = Math.min(world.getMaxHeight() - 1, minY + 4);
            } else {
               maxY = this.netherRoofY(world);
               minY = Math.max(world.getMinHeight(), maxY - 4);
            }

            if (generateNewCrack || this.looksLikeLegacySquare(chunk, localX, localZ, radius, minY, maxY)) {
               int layers = maxY - minY + 1;

               for(int x = localX - radius; x <= localX + radius; ++x) {
                  for(int z = localZ - radius; z <= localZ + radius; ++z) {
                     int offsetX = x - localX;
                     int offsetZ = z - localZ;

                     for(int y = minY; y <= maxY; ++y) {
                        Block block = chunk.getBlock(x, y, z);
                        int layer = y - minY;
                        if (generateNewCrack && this.naturalHoleCell(offsetX, offsetZ, layer, layers, radius, hash)) {
                           block.setType(Material.AIR, false);
                        } else if (block.getType().isAir()) {
                           block.setType(this.naturalBedrockMaterial(world, chunk, x, y, z, minY, maxY, hash), false);
                        }
                     }
                  }
               }

            }
         }
      }
   }

   private boolean looksLikeLegacySquare(Chunk chunk, int localX, int localZ, int radius, int minY, int maxY) {
      int total = 0;
      int air = 0;

      for(int x = localX - radius; x <= localX + radius; ++x) {
         for(int z = localZ - radius; z <= localZ + radius; ++z) {
            for(int y = minY; y <= maxY; ++y) {
               ++total;
               if (chunk.getBlock(x, y, z).getType().isAir()) {
                  ++air;
               }
            }
         }
      }

      return total > 0 && air * 100 >= total * 85;
   }

   private boolean naturalHoleCell(int offsetX, int offsetZ, int layer, int layers, int radius, long hash) {
      if (offsetX == 0 && offsetZ == 0) {
         return true;
      } else if (radius > 0 && layers > 0) {
         int distanceToSurface = Math.min(layer, layers - 1 - layer);
         int armLength = Math.max(0, radius - distanceToSurface);
         if (armLength == 0) {
            return false;
         } else {
            boolean upperSurface = layer >= layers / 2;
            long shapeHash = this.mix(hash ^ (upperSurface ? 7640891576956012809L : -4942790177534073029L));
            int direction = Math.floorMod((int)shapeHash, 4);
            int directionX = switch (direction) {
               case 0 -> 1;
               case 1 -> -1;
               default -> 0;
            };

            int directionZ = switch (direction) {
               case 2 -> 1;
               case 3 -> -1;
               default -> 0;
            };
            if (this.onArm(offsetX, offsetZ, directionX, directionZ, armLength)) {
               return true;
            } else if (radius >= 2 && armLength >= 2) {
               boolean turnRight = (shapeHash & 1L) == 0L;
               int branchX = turnRight ? -directionZ : directionZ;
               int branchZ = turnRight ? directionX : -directionX;
               return this.onArm(offsetX, offsetZ, branchX, branchZ, armLength - 1);
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private boolean onArm(int offsetX, int offsetZ, int directionX, int directionZ, int length) {
      for(int step = 1; step <= length; ++step) {
         if (offsetX == directionX * step && offsetZ == directionZ * step) {
            return true;
         }
      }

      return false;
   }

   private Material naturalBedrockMaterial(World world, Chunk chunk, int localX, int y, int localZ, int minY, int maxY, long holeHash) {
      int distanceFromSolidEdge = world.getEnvironment() == Environment.NORMAL ? y - minY : maxY - y;
      if (distanceFromSolidEdge <= 0) {
         return Material.BEDROCK;
      } else {
         int worldX = chunk.getX() * 16 + localX;
         int worldZ = chunk.getZ() * 16 + localZ;
         long cellHash = this.mix(holeHash ^ (long)worldX * 341873128712L ^ (long)worldZ * 132897987541L ^ (long)y * -7046029254386353131L);
         int bedrockChance = switch (distanceFromSolidEdge) {
            case 1 -> 78;
            case 2 -> 55;
            case 3 -> 32;
            default -> 14;
         };
         if (Math.floorMod(cellHash, 100L) < (long)bedrockChance) {
            return Material.BEDROCK;
         } else {
            return world.getEnvironment() == Environment.NETHER ? Material.NETHERRACK : Material.DEEPSLATE;
         }
      }
   }

   private boolean supportsHoles(World world) {
      return world.getEnvironment() == Environment.NORMAL || world.getEnvironment() == Environment.NETHER;
   }

   private int netherRoofY(World world) {
      return Math.min(world.getMaxHeight() - 1, world.getLogicalHeight() - 1);
   }

   private int floor(double value) {
      return (int)Math.floor(value);
   }

   private long mix(long value) {
      value ^= value >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      return value ^ value >>> 33;
   }

   private static enum Transition {
      OVERWORLD_TO_NETHER,
      NETHER_TO_OVERWORLD,
      END_TO_OVERWORLD;

      // $FF: synthetic method
      private static Transition[] $values() {
         return new Transition[]{OVERWORLD_TO_NETHER, NETHER_TO_OVERWORLD, END_TO_OVERWORLD};
      }
   }
}
