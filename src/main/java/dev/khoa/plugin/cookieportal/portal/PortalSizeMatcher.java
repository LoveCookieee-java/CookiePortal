package dev.khoa.plugin.cookieportal.portal;

import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

final class PortalSizeMatcher {
   private final CookiePortalPlugin plugin;

   PortalSizeMatcher(CookiePortalPlugin plugin) {
      this.plugin = plugin;
   }

   boolean matchAndLink(PortalPlane source, PortalPlane destination) {
      if (source != null && destination != null) {
         if (!this.plugin.settings().matchDestinationSize()) {
            this.complete(source, destination, destination);
            return true;
         } else {
            World world = destination.world();
            if (world != null && destination.stillExists()) {
               int currentWidth = this.openingWidth(destination);
               int currentHeight = this.openingHeight(destination);
               int targetWidth = Math.max(currentWidth, this.clamp(this.openingWidth(source), 2, 21));
               int targetHeight = Math.max(currentHeight, this.clamp(this.openingHeight(source), 3, 21));
               if (targetWidth == currentWidth && targetHeight == currentHeight) {
                  this.complete(source, destination, destination);
                  return true;
               } else {
                  int destinationMinU = destination.axis() == PortalPlane.Axis.X ? destination.minX() : destination.minZ();
                  int destinationMaxU = destination.axis() == PortalPlane.Axis.X ? destination.maxX() : destination.maxZ();
                  int centerTwice = destinationMinU + destinationMaxU;
                  int minU = Math.floorDiv(centerTwice - (targetWidth - 1), 2);
                  int bottom = destination.minY();
                  Orientable portalData = (Orientable)Material.NETHER_PORTAL.createBlockData();
                  portalData.setAxis(destination.axis() == PortalPlane.Axis.X ? Axis.X : Axis.Z);

                  for(int u = minU - 1; u <= minU + targetWidth; ++u) {
                     for(int y = bottom - 1; y <= bottom + targetHeight; ++y) {
                        boolean frame = u == minU - 1 || u == minU + targetWidth || y == bottom - 1 || y == bottom + targetHeight;
                        this.blockAt(world, destination, u, y).setBlockData((BlockData)(frame ? Material.OBSIDIAN.createBlockData() : portalData), false);
                     }
                  }

                  PortalPlane resized = PortalPlane.discover(this.blockAt(world, destination, minU, bottom));
                  this.complete(source, destination, resized == null ? destination : resized);
                  return true;
               }
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private void complete(PortalPlane source, PortalPlane previousDestination, PortalPlane destination) {
      this.plugin.registry().replace(previousDestination, destination);
      this.plugin.registry().link(source, destination);
      this.plugin.renderer().refreshNear(source);
      this.plugin.renderer().refreshNear(destination);
   }

   private int openingWidth(PortalPlane plane) {
      return plane.axis() == PortalPlane.Axis.X ? plane.maxX() - plane.minX() + 1 : plane.maxZ() - plane.minZ() + 1;
   }

   private int openingHeight(PortalPlane plane) {
      return plane.maxY() - plane.minY() + 1;
   }

   private Block blockAt(World world, PortalPlane plane, int horizontal, int y) {
      return plane.axis() == PortalPlane.Axis.X ? world.getBlockAt(horizontal, y, plane.minZ()) : world.getBlockAt(plane.minX(), y, horizontal);
   }

   private int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }
}
