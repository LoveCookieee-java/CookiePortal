package dev.khoa.plugin.cookieportal.render.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * Modern cross-version PacketEvents 2.11+ implementation for spawning, updating,
 * and synchronizing fake entity projections through portals without NMS reflection.
 */
public final class PacketEventsEntityPacketBridge implements EntityPacketBridge {

    private static final double RELATIVE_MOVE_LIMIT = 7.9;
    private final CookiePortalPlugin plugin;

    public PacketEventsEntityPacketBridge(CookiePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean supported() {
        try {
            return PacketEvents.getAPI().isInitialized();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String status() {
        return "PacketEvents v" + PacketEvents.getAPI().getVersion() + " Bridge";
    }

    @Override
    public boolean spawn(Player viewer, LivingEntity source, ProjectedEntity projected) {
        if (viewer == null || !viewer.isOnline() || source == null || projected == null) {
            return false;
        }

        try {
            EntityType entityType = EntityTypes.getByName(source.getType().getKey().getKey());
            if (entityType == null) {
                return false;
            }

            Vector3d position = new Vector3d(projected.x(), projected.y(), projected.z());
            Vector3d velocity = new Vector3d(
                source.getVelocity().getX(),
                source.getVelocity().getY(),
                source.getVelocity().getZ()
            );

            WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                projected.fakeEntityId(),
                Optional.of(source.getUniqueId()),
                entityType,
                position,
                projected.pitch(),
                projected.yaw(),
                projected.yaw(),
                0,
                Optional.of(velocity)
            );

            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);
            this.synchronize(viewer, source, projected);
            return true;
        } catch (Throwable throwable) {
            if (this.plugin.settings().debug()) {
                this.plugin.getLogger().log(Level.WARNING, "Failed to spawn projected entity via PacketEvents", throwable);
            }
            return false;
        }
    }

    @Override
    public boolean update(Player viewer, LivingEntity source, ProjectedEntity projected, double x, double y, double z, float yaw, float pitch) {
        if (viewer == null || !viewer.isOnline() || projected == null) {
            return false;
        }

        try {
            double deltaX = x - projected.x();
            double deltaY = y - projected.y();
            double deltaZ = z - projected.z();

            boolean isFarMove = Math.abs(deltaX) > RELATIVE_MOVE_LIMIT 
                || Math.abs(deltaY) > RELATIVE_MOVE_LIMIT 
                || Math.abs(deltaZ) > RELATIVE_MOVE_LIMIT;

            if (isFarMove) {
                // Large displacement: Teleport entity packet
                WrapperPlayServerEntityTeleport teleportPacket = new WrapperPlayServerEntityTeleport(
                    projected.fakeEntityId(),
                    new Vector3d(x, y, z),
                    yaw,
                    pitch,
                    source.isOnGround()
                );
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleportPacket);
            } else {
                boolean moved = Math.abs(deltaX) > 0.001 || Math.abs(deltaY) > 0.001 || Math.abs(deltaZ) > 0.001;
                boolean rotated = Math.abs(yaw - projected.yaw()) > 0.1f || Math.abs(pitch - projected.pitch()) > 0.1f;

                if (moved) {
                    WrapperPlayServerEntityRelativeMoveAndRotation movePacket = new WrapperPlayServerEntityRelativeMoveAndRotation(
                        projected.fakeEntityId(),
                        deltaX,
                        deltaY,
                        deltaZ,
                        yaw,
                        pitch,
                        source.isOnGround()
                    );
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, movePacket);
                } else if (rotated) {
                    WrapperPlayServerEntityRotation rotatePacket = new WrapperPlayServerEntityRotation(
                        projected.fakeEntityId(),
                        yaw,
                        pitch,
                        source.isOnGround()
                    );
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, rotatePacket);
                }
            }

            projected.updatePose(x, y, z, yaw, pitch);
            return true;
        } catch (Throwable throwable) {
            if (this.plugin.settings().debug()) {
                this.plugin.getLogger().log(Level.WARNING, "Failed to update projected entity via PacketEvents", throwable);
            }
            return false;
        }
    }

    @Override
    public void synchronize(Player viewer, LivingEntity source, ProjectedEntity projected) {
        if (viewer == null || !viewer.isOnline() || source == null || projected == null) {
            return;
        }

        try {
            this.sendEquipment(viewer, source, projected.fakeEntityId());
        } catch (Throwable throwable) {
            if (this.plugin.settings().debug()) {
                this.plugin.getLogger().log(Level.WARNING, "Failed to synchronize projected entity metadata/equipment", throwable);
            }
        }
    }

    @Override
    public void destroy(Player viewer, int fakeEntityId) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }

        try {
            WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(fakeEntityId);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroyPacket);
        } catch (Throwable throwable) {
            if (this.plugin.settings().debug()) {
                this.plugin.getLogger().log(Level.WARNING, "Failed to destroy projected entity via PacketEvents", throwable);
            }
        }
    }

    private void sendEquipment(Player viewer, LivingEntity source, int fakeEntityId) {
        EntityEquipment equipment = source.getEquipment();
        if (equipment == null) {
            return;
        }

        List<Equipment> equipmentList = new ArrayList<>();
        addEquipmentSlot(equipmentList, EquipmentSlot.MAIN_HAND, equipment.getItemInMainHand());
        addEquipmentSlot(equipmentList, EquipmentSlot.OFF_HAND, equipment.getItemInOffHand());
        addEquipmentSlot(equipmentList, EquipmentSlot.HELMET, equipment.getHelmet());
        addEquipmentSlot(equipmentList, EquipmentSlot.CHEST_PLATE, equipment.getChestplate());
        addEquipmentSlot(equipmentList, EquipmentSlot.LEGGINGS, equipment.getLeggings());
        addEquipmentSlot(equipmentList, EquipmentSlot.BOOTS, equipment.getBoots());

        if (!equipmentList.isEmpty()) {
            WrapperPlayServerEntityEquipment equipmentPacket = new WrapperPlayServerEntityEquipment(
                fakeEntityId,
                equipmentList
            );
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, equipmentPacket);
        }
    }

    private static void addEquipmentSlot(List<Equipment> list, EquipmentSlot slot, ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            com.github.retrooper.packetevents.protocol.item.ItemStack peItem = SpigotConversionUtil.fromBukkitItemStack(item);
            list.add(new Equipment(slot, peItem));
        }
    }
}
