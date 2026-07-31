package dev.khoa.plugin.cookieportal.render.entity;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public interface EntityPacketBridge {
   boolean supported();

   String status();

   boolean spawn(Player var1, LivingEntity var2, ProjectedEntity var3);

   boolean update(Player var1, LivingEntity var2, ProjectedEntity var3, double var4, double var6, double var8, float var10, float var11);

   void synchronize(Player var1, LivingEntity var2, ProjectedEntity var3);

   void destroy(Player var1, int var2);
}
