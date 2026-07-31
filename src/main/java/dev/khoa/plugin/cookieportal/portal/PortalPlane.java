package dev.khoa.plugin.cookieportal.portal;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public record PortalPlane(UUID worldId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Axis axis) {
   public static PortalPlane discover(Block seed) {
      if (!seed.getType().name().equals("NETHER_PORTAL")) {
         return null;
      } else {
         Set<Block> found = new HashSet();
         ArrayDeque<Block> open = new ArrayDeque();
         open.add(seed);

         while(!open.isEmpty() && found.size() < 4096) {
            Block b = (Block)open.removeFirst();
            if (b.getType().name().equals("NETHER_PORTAL") && found.add(b)) {
               open.add(b.getRelative(1, 0, 0));
               open.add(b.getRelative(-1, 0, 0));
               open.add(b.getRelative(0, 1, 0));
               open.add(b.getRelative(0, -1, 0));
               open.add(b.getRelative(0, 0, 1));
               open.add(b.getRelative(0, 0, -1));
            }
         }

         if (found.isEmpty()) {
            return null;
         } else {
            int x1 = Integer.MAX_VALUE;
            int y1 = Integer.MAX_VALUE;
            int z1 = Integer.MAX_VALUE;
            int x2 = Integer.MIN_VALUE;
            int y2 = Integer.MIN_VALUE;
            int z2 = Integer.MIN_VALUE;

            for(Block b : found) {
               x1 = Math.min(x1, b.getX());
               y1 = Math.min(y1, b.getY());
               z1 = Math.min(z1, b.getZ());
               x2 = Math.max(x2, b.getX());
               y2 = Math.max(y2, b.getY());
               z2 = Math.max(z2, b.getZ());
            }

            Axis axis = ((Orientable)seed.getBlockData()).getAxis() == org.bukkit.Axis.X ? PortalPlane.Axis.X : PortalPlane.Axis.Z;
            return new PortalPlane(seed.getWorld().getUID(), x1, y1, z1, x2, y2, z2, axis);
         }
      }
   }

   public World world() {
      return Bukkit.getWorld(this.worldId);
   }

   public Location center() {
      return new Location(this.world(), (double)(this.minX + this.maxX + 1) / (double)2.0F, (double)(this.minY + this.maxY + 1) / (double)2.0F, (double)(this.minZ + this.maxZ + 1) / (double)2.0F);
   }

   public Vector normal(int side) {
      return this.axis == PortalPlane.Axis.X ? new Vector(0, 0, side) : new Vector(side, 0, 0);
   }

   public BoundingBox openingBox() {
      return (new BoundingBox((double)this.minX, (double)this.minY, (double)this.minZ, (double)(this.maxX + 1), (double)(this.maxY + 1), (double)(this.maxZ + 1))).expand(0.02);
   }

   public String key() {
      return this.worldId + ":" + this.minX + ":" + this.minY + ":" + this.minZ + ":" + this.axis;
   }

   public boolean stillExists() {
      World w = this.world();
      if (w == null) {
         return false;
      } else {
         int chunkX = this.minX >> 4;
         int chunkZ = this.minZ >> 4;
         if (!w.isChunkLoaded(chunkX, chunkZ)) {
            return true;
         } else {
            return w.getBlockAt(this.minX, this.minY, this.minZ).getType() == Material.NETHER_PORTAL;
         }
      }
   }

   public static enum Axis {
      X,
      Z;

      // $FF: synthetic method
      private static Axis[] $values() {
         return new Axis[]{X, Z};
      }
   }
}
