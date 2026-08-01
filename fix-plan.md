# CookiePortal - Technical Fix Plan & Bug Resolution Guide
> **Target AI Assistant:** Moonshot AI - Kimi K3  
> **Project:** CookiePortal (Vanilla Packet-Only Portal Rendering Plugin)  
> **Environment:** Paper / Folia 1.21.1 - 1.21.4+, Java 21, PacketEvents 2.11.2+, Maven  

---

## 1. Executive Summary & Objective

This document contains precise, step-by-step instructions for refactoring and fixing critical bugs, memory leaks, reflection failures, and performance bottlenecks in the **CookiePortal** plugin (`E:\SERVER\plugin-pre\Unique\CookiePortal`).

The main issues to resolve are:
1. **Memory Leak in `remoteScenes`**: Portals removed from `PortalRegistry` leave cached `RemoteScene` instances in memory indefinitely.
2. **Session Leak on Player Disconnect**: Players quitting while viewing portals leave active `ViewSession` and `ViewerSession` instances in memory.
3. **Brittle Reflection / Version Locking**: `ReflectionEntityPacketBridge` uses hardcoded Paper 1.21.1 NMS constructor signatures via reflection, failing on other 1.21.x patches. Must be migrated to **PacketEvents 2.11+**.
4. **PacketEvents Dependency & Validation**: Update `plugin.yml` from `softdepend` to `depend: [packetevents]` and add strict version checking (>= 2.11.0) on startup.
5. **Decompilation Artifacts & Leftover Strings**: Remove decompilation syntax noise (e.g. `var10000`, `fChunkX`, `(double)0.0F`) and Spanish debug log leftovers.
6. **TPS & Performance Tuning**: Optimize view angle raycasts, block diff calculation, and chunk loading tickets.

---

## 2. Mandatory Architectural & Build Changes

### 2.1 Dependency Enforcement in `plugin.yml`
- File: `src/main/resources/plugin.yml`
- Change: Replace `softdepend: [packetevents]` with `depend: [packetevents]`.

```yaml
name: CookiePortal
version: '1.3'
main: dev.khoa.plugin.cookieportal.CookiePortalPlugin
description: CookiePortal - packet-only views through vanilla Nether and End portals.
author: Cookie
api-version: '1.21'
load: POSTWORLD
folia-supported: true
depend: [packetevents]

commands:
  cookieportal:
    description: Reload or inspect CookiePortal.
    usage: /cookieportal <reload|info>
    aliases: [cp]
    permission: cookieportal.admin
permissions:
  cookieportal.admin:
    default: op
```

### 2.2 Maven `pom.xml` Dependencies
Ensure `pom.xml` contains proper repository entries and dependency scopes for Paper API 1.21.4 and PacketEvents 2.11.2:
- `paper-api`: `1.21.4-R0.1-SNAPSHOT` (`provided`)
- `packetevents-spigot`: `2.11.2` (`provided`)

---

## 3. Step-by-Step Bug Fixes & Code Modification Specifications

---

### FIX #1: Purge Expired & Orphaned Scenes in `remoteScenes` Map
**File:** `src/main/java/dev/khoa/plugin/cookieportal/render/PortalRenderService.java`  
**Problem:** When a portal is destroyed (broken by player, exploded, etc.), `PortalRegistry` removes it from registered portals, but `remoteScenes.put(key, new RemoteScene(...))` keeps the cached scene with thousands of `BlockData` references in memory.  
**Solution:** In `PortalRenderService#tick()`, add orphaned scene cleanup.

#### Execution Instructions:
In `PortalRenderService.java`, modify the `tick()` method:

```java
private void tick() {
    this.releaseExpiredRemoteChunkTickets();
    
    // CRITICAL FIX: Purge remoteScenes for portals that no longer exist in registry
    this.remoteScenes.keySet().removeIf(key -> !this.registry.contains(key));
    this.pendingRemoteScenes.removeIf(key -> !this.registry.contains(key));

    if (this.plugin.scheduler().isFolia()) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.plugin.scheduler().runForPlayer(player, () -> this.tickPlayer(player));
        }
        this.sessions.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    } else {
        this.registry.removeInvalid();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.tickPlayer(player);
        }
        this.sessions.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }
}
```

---

### FIX #2: Full Session Cleanup on `PlayerQuitEvent`
**Files:**
1. `src/main/java/dev/khoa/plugin/cookieportal/portal/PortalListener.java`
2. `src/main/java/dev/khoa/plugin/cookieportal/render/PortalRenderService.java`
3. `src/main/java/dev/khoa/plugin/cookieportal/render/entity/PortalEntityRenderService.java`

**Problem:** `PlayerQuitEvent` currently only clears `pendingSizes`. `ViewSession` and `ViewerSession` map entries persist until GC or `tick()` detects null players, leaving fake block states, entity tracking maps, and occlusion states hanging in memory.

#### Execution Instructions:
1. In `PortalRenderService.java`, ensure `clear(Player player)` is public and completely purges all session tracking for that player:
```java
public void clear(Player player) {
    if (player == null) return;
    UUID uuid = player.getUniqueId();
    ViewSession session = this.sessions.remove(uuid);
    if (session != null) {
        this.clearSent(player, session);
        this.restorePortalSurface(player, this.registry.get(session.portalKey));
    }
    this.entityRenderer.clear(player);
    this.entityViewsEnabled.remove(uuid);
    this.pendingEntityRenders.remove(uuid);
}
```

2. In `PortalListener.java`, update `onQuit`:
```java
@EventHandler
public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    this.pendingSizes.remove(player.getUniqueId());
    this.plugin.renderer().clear(player);
}
```

---

### FIX #3: Replace Brittle NMS Reflection with PacketEvents API
**File:** `src/main/java/dev/khoa/plugin/cookieportal/render/entity/PacketEventsEntityPacketBridge.java` (Replacing `ReflectionEntityPacketBridge.java`)  
**Problem:** `ReflectionEntityPacketBridge` uses hardcoded NMS constructors (`ClientboundAddEntityPacket`, `ClientboundMoveEntityPacket$PosRot`, `Vec3`, etc.). When Paper/Minecraft minor versions update, reflection fails silently, returning `UnsupportedEntityPacketBridge` and disabling entity views through portals.  
**Solution:** Rewrite the entity packet bridge to use **PacketEvents 2.11+ API** wrappers.

#### Execution Instructions:
Utilize PacketEvents wrappers for entity packets:
- **Spawn Entity:** `WrapperPlayServerSpawnEntity`
- **Move Entity:** `WrapperPlayServerEntityRelativeMoveAndRotation` / `WrapperPlayServerEntityTeleport`
- **Rotate Entity:** `WrapperPlayServerEntityRotation`
- **Metadata:** `WrapperPlayServerEntityMetadata`
- **Equipment:** `WrapperPlayServerEntityEquipment`
- **Destroy Entity:** `WrapperPlayServerDestroyEntities`

```java
package dev.khoa.plugin.cookieportal.render.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class PacketEventsEntityPacketBridge implements EntityPacketBridge {
    private final CookiePortalPlugin plugin;

    public PacketEventsEntityPacketBridge(CookiePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean supported() {
        return PacketEvents.getAPI().isInitialized();
    }

    @Override
    public String status() {
        return "PacketEvents v" + PacketEvents.getAPI().getVersion() + " Bridge";
    }

    @Override
    public boolean spawn(Player viewer, LivingEntity source, ProjectedEntity projected) {
        try {
            // Build and send PacketEvents Spawn Entity wrapper
            com.github.retrooper.packetevents.protocol.entity.type.EntityType type = 
                EntityTypes.getByName(source.getType().getKey().getKey());
            
            WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                projected.fakeEntityId(),
                source.getUniqueId(),
                type,
                new com.github.retrooper.packetevents.util.Vector3d(projected.x(), projected.y(), projected.z()),
                projected.pitch(),
                projected.yaw(),
                projected.yaw(),
                0,
                null
            );
            
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);
            this.synchronize(viewer, source, projected);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean update(Player viewer, LivingEntity source, ProjectedEntity projected, double x, double y, double z, float yaw, float pitch) {
        // Send relative movement or teleport packet via PacketEvents
        // ...
        return true;
    }

    @Override
    public void synchronize(Player viewer, LivingEntity source, ProjectedEntity projected) {
        // Send metadata and equipment via PacketEvents wrappers
    }

    @Override
    public void destroy(Player viewer, int fakeEntityId) {
        WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(fakeEntityId);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroyPacket);
    }
}
```

---

### FIX #4: Startup PacketEvents Version Validation
**File:** `src/main/java/dev/khoa/plugin/cookieportal/CookiePortalPlugin.java`  
**Problem:** Ensuring PacketEvents version >= 2.11.0 is active.  

#### Execution Instructions:
In `CookiePortalPlugin.java`:
```java
private boolean checkPacketEvents() {
    org.bukkit.plugin.Plugin pe = this.getServer().getPluginManager().getPlugin("packetevents");
    if (pe == null || !pe.isEnabled()) {
        this.getLogger().severe("==========================================================");
        this.getLogger().severe(" CookiePortal REQUIRES packetevents plugin in /plugins!");
        this.getLogger().severe(" Required version: 2.11.0 or newer");
        this.getLogger().severe("==========================================================");
        return false;
    }

    String version = pe.getDescription().getVersion();
    if (!isPacketEventsVersionOk(version)) {
        this.getLogger().severe("==========================================================");
        this.getLogger().severe(" CookiePortal REQUIRES packetevents 2.11.0 or newer!");
        this.getLogger().severe(" Found version: " + version);
        this.getLogger().severe("==========================================================");
        return false;
    }

    this.getLogger().info("PacketEvents detected successfully: v" + version);
    return true;
}
```

---

### FIX #5: Remove Spanish Decompiler Strings & Syntax Artifacts
**Files:** All Java source files.  
**Changes:**
1. Replace `"No se pudo capturar un chunk de la escena "` in `PortalRenderService.java` line 529 with `"Failed to capture scene chunk for portal: "`
2. Replace decompiled variable names:
   - `var10000`, `var10001` -> meaningful names like `portalKey`, `projectionId`.
   - `fChunkX`, `fChunkZ` -> `chunkX`, `chunkZ`.
3. Remove redundant float/double type casts (e.g. `(double)0.0F` -> `0.0`).

---

### FIX #6: Performance & TPS Optimizations
1. **Look Dot Threshold Tuning (`PortalConfig.java` & `config.yml`)**:
   - Default `look-threshold` in `config.yml`: change from `0.35` to `0.45` to prevent unnecessary raycasting when players glance sideways past portals.
2. **Batch Block Changes**:
   - In `PlatformCompatibility.java`, optimize `sendBlockChanges` to group block changes by `Chunk` / section when using `player.sendMultiBlockChange(...)`.

---

## 4. Verification & Testing Checklist

- [ ] **Build Validation**: Execute `mvn clean package` on Java 21 to ensure zero compilation warnings/errors.
- [ ] **Memory Leak Test**: Create 20 nether portals, break them, force garbage collection, and verify `remoteScenes` map size returns to 0.
- [ ] **Player Quit Test**: Connect a player, look through a portal, disconnect, and check `sessions` maps in `PortalRenderService` and `PortalEntityRenderService` are completely empty.
- [ ] **PacketEvents 2.11+ Verification**: Test on Paper 1.21.1 and Paper 1.21.4 with PacketEvents 2.11.2 loaded. Ensure mobs/players across portals spawn smoothly without NMS reflection errors.
- [ ] **Folia Compatibility Test**: Run on a Folia 1.21.4 test server, verify regional thread scheduling is clean.
