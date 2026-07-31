package dev.khoa.plugin.cookieportal.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;

public final class PortalRegistry {
   private final CookiePortalPlugin plugin;
   private final Map<String, PortalPlane> portals = new ConcurrentHashMap();
   private final Map<String, String> observedLinks = new ConcurrentHashMap();
   private final Set<String> pendingChunkScans = ConcurrentHashMap.newKeySet();

   public PortalRegistry(CookiePortalPlugin plugin) {
      this.plugin = plugin;
   }

   public void register(Block block) {
      PortalPlane p = PortalPlane.discover(block);
      if (p != null) {
         this.portals.put(p.key(), p);
      }

   }

   public void replace(PortalPlane previous, PortalPlane replacement) {
      if (previous != null && replacement != null) {
         this.portals.remove(previous.key());
         this.portals.put(replacement.key(), replacement);
         this.observedLinks.replaceAll((source, destination) -> destination.equals(previous.key()) ? replacement.key() : destination);
         String previousDestination = (String)this.observedLinks.remove(previous.key());
         if (previousDestination != null) {
            this.observedLinks.put(replacement.key(), previousDestination);
         }

      }
   }

   public boolean contains(String key) {
      return key != null && this.portals.containsKey(key);
   }

   public boolean removeInvalid() {
      boolean removed = this.portals.values().removeIf((p) -> !p.stillExists());
      if (removed) {
         this.removeStaleLinks();
      }

      return removed;
   }

   public boolean removeNear(Location location) {
      if (location != null && location.getWorld() != null) {
         boolean removed = this.portals.values().removeIf((portal) -> portal.worldId().equals(location.getWorld().getUID()) && location.getX() >= (double)(portal.minX() - 2) && location.getX() <= (double)(portal.maxX() + 3) && location.getY() >= (double)(portal.minY() - 2) && location.getY() <= (double)(portal.maxY() + 3) && location.getZ() >= (double)(portal.minZ() - 2) && location.getZ() <= (double)(portal.maxZ() + 3));
         if (removed) {
            this.removeStaleLinks();
         }

         return removed;
      } else {
         return false;
      }
   }

   public Collection<PortalPlane> all() {
      return this.portals.values();
   }

   public void link(PortalPlane source, PortalPlane destination) {
      if (source != null && destination != null) {
         this.observedLinks.put(source.key(), destination.key());
      }
   }

   private void removeStaleLinks() {
      this.observedLinks.entrySet().removeIf((entry) -> !this.portals.containsKey(entry.getKey()) || !this.portals.containsKey(entry.getValue()));
   }

   public PortalPlane destinationFor(PortalPlane source) {
      String linkedKey = (String)this.observedLinks.get(source.key());
      PortalPlane linked = linkedKey == null ? null : (PortalPlane)this.portals.get(linkedKey);
      if (linked != null && linked.stillExists()) {
         return linked;
      } else {
         if (linkedKey != null) {
            this.observedLinks.remove(source.key(), linkedKey);
         }

         World world = source.world();
         if (world == null) {
            return null;
         } else {
            World.Environment wanted = world.getEnvironment() == Environment.NETHER ? Environment.NORMAL : Environment.NETHER;
            double scale = world.getEnvironment() == Environment.NETHER ? (double)8.0F : (double)0.125F;
            double expectedX = source.center().getX() * scale;
            double expectedZ = source.center().getZ() * scale;
            return this.portals.values().stream().filter((p) -> p.world() != null && p.world().getEnvironment() == wanted).min(Comparator.comparingDouble((p) -> {
               double dx = p.center().getX() - expectedX;
               double dz = p.center().getZ() - expectedZ;
               double dy = (p.center().getY() - source.center().getY()) * 0.2;
               return dx * dx + dz * dz + dy * dy;
            })).orElse(null);
         }
      }
   }

   public void scanLoadedChunks() {
      for(World w : this.plugin.getServer().getWorlds()) {
         for(Chunk c : w.getLoadedChunks()) {
            this.scan(c);
         }
      }

   }

   public void scan(Chunk c) {
      World world = c.getWorld();
      int chunkX = c.getX();
      int chunkZ = c.getZ();
      String key = String.valueOf(world.getUID()) + ":" + chunkX + ":" + chunkZ;
      if (this.pendingChunkScans.add(key)) {
         int min = world.getMinHeight();
         int max = world.getMaxHeight();
         UUID worldId = world.getUID();
         ChunkSnapshot snapshot = c.getChunkSnapshot(false, false, false);
         CompletableFuture.runAsync(() -> {
            List<int[]> seeds = new ArrayList();

            try {
               for(int x = 0; x < 16; ++x) {
                  for(int z = 0; z < 16; ++z) {
                     for(int y = min; y < max; ++y) {
                        if (snapshot.getBlockType(x, y, z) == Material.NETHER_PORTAL) {
                           if (y == min || snapshot.getBlockType(x, y - 1, z) != Material.NETHER_PORTAL) {
                              seeds.add(new int[]{(chunkX << 4) + x, y, (chunkZ << 4) + z});
                           }

                           while(y + 1 < max && snapshot.getBlockType(x, y + 1, z) == Material.NETHER_PORTAL) {
                              ++y;
                           }
                        }
                     }
                  }
               }
            } finally {
               this.pendingChunkScans.remove(key);
            }

            for(int[] seed : seeds) {
               World liveWorld = this.plugin.getServer().getWorld(worldId);
               if (liveWorld == null || !this.plugin.isEnabled()) {
                  return;
               }

               Location location = new Location(liveWorld, (double)seed[0], (double)seed[1], (double)seed[2]);
               this.plugin.scheduler().runAt(location, () -> {
                  Block block = liveWorld.getBlockAt(seed[0], seed[1], seed[2]);
                  if (block.getType() == Material.NETHER_PORTAL) {
                     this.register(block);
                  }

               });
            }

         });
      }
   }
}
