package dev.khoa.plugin.cookieportal.render;

import dev.khoa.plugin.cookieportal.portal.PortalPlane;
import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class PortalProjection {
   private PortalProjection() {
   }

   static boolean rayPassesOpening(Location eye, Location target, PortalPlane portal) {
      Vector origin = eye.toVector();
      Vector ray = target.toVector().subtract(origin);
      if (ray.lengthSquared() < 1.0E-8) {
         return false;
      } else {
         double plane = portal.axis() == PortalPlane.Axis.X ? (double)portal.minZ() + (double)0.5F : (double)portal.minX() + (double)0.5F;
         double component = portal.axis() == PortalPlane.Axis.X ? ray.getZ() : ray.getX();
         if (Math.abs(component) < 1.0E-7) {
            return false;
         } else {
            double t = (plane - (portal.axis() == PortalPlane.Axis.X ? origin.getZ() : origin.getX())) / component;
            if (!(t <= (double)0.0F) && !(t >= (double)1.0F)) {
               Vector hit = origin.clone().add(ray.multiply(t));
               BoundingBox box = portal.openingBox();
               return box.contains(hit);
            } else {
               return false;
            }
         }
      }
   }
}
