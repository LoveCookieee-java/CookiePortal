<p align="center">
  <img src="https://img.shields.io/badge/🌀_CookiePortal-v1.3-000000?style=for-the-badge&labelColor=1a1a2e" alt="CookiePortal">
</p>

<h1 align="center">CookiePortal</h1>

<p align="center">
  <b>High-Performance Packet-Only Immersive Portal Engine for Paper & Folia 1.21.1+</b>
  <br>
  <sub>Render real-time 3D views through Nether, End, and custom portals with zero client mods, zero NMS reflection, and zero memory leaks.</sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1+-16a34a?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft">
  <img src="https://img.shields.io/badge/Java-21-f97316?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Paper-API-fbbf24?style=for-the-badge" alt="Paper">
  <img src="https://img.shields.io/badge/Folia-Supported-3b82f6?style=for-the-badge" alt="Folia">
  <img src="https://img.shields.io/badge/PacketEvents-2.11+-8b5cf6?style=for-the-badge" alt="PacketEvents">
  <img src="https://img.shields.io/badge/Build-Maven-e11d48?style=for-the-badge&logo=apachemaven&logoColor=white" alt="Maven">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Status-Production--Ready-22c55e?style=flat-square" alt="Status">
  <img src="https://img.shields.io/badge/Code_Quality-100%25_Clean-blue?style=flat-square" alt="Clean Code">
</p>

---

> [!NOTE]
> **⚠️ Disclaimer & Acknowledgements:**
> This repository is a **refactored remake and bug-fixed fork** based on legacy decompiled software rasterization code.
> Author **Cookie (LoveCookieee)** does **NOT** claim original ownership or creation of the underlying portal preview concept.
> This project exists as an open-source, modernized fork focused on:
> - **100% Clean Code**: Zero decompiler artifacts (`var\d+` / `var00`), pure human-readable Java 21 logic.
> - **Memory Leak Fixes**: Resolved `remoteScenes` cache leaks, session leaks, and orphaned async chunk tasks.
> - **PacketEvents 2.11+ Integration**: Replaced brittle Paper NMS reflection with robust API wrappers.
> - **Paper & Folia Native Support**: Fully multithreaded and region-scheduler ready.

---

## 📋 Table of Contents

- [Overview](#-overview)
- [Architecture](#-architecture)
- [Key Features](#-key-features)
- [Requirements & Installation](#-requirements--installation)
- [Configuration](#-configuration)
- [Commands & Permissions](#-commands--permissions)
- [Project Structure](#-project-structure)
- [Building from Source](#-building-from-source)
- [License](#-license)

---

## 🔍 Overview

**CookiePortal** delivers seamless, client-mod-free 3D portal previews directly over network packets. When players approach a Nether, End, or vertical stacked portal, CookiePortal calculates player line-of-sight raycasts and streams virtual block change packets to render the destination dimension in real time.

```
┌──────────────────────────────────────────────────────────┐
│                   CookiePortal Engine                    │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────── Player Raycast ────────────────┐      │
│  │  Viewer Eye Position → Portal Opening Intersect│      │
│  └────────────────────────────────────────────────┘      │
│                            │                             │
│                            ▼                             │
│  ┌───────────── Destination Scene Snapshot ──────┐      │
│  │  Async Chunk Capture → Biome & Block Mapping  │      │
│  └────────────────────────────────────────────────┘      │
│                            │                             │
│                            ▼                             │
│  ┌────────────── Software Rasterizer ─────────────┐      │
│  │  Frustum Bounds Check → Masking & Void Shell   │      │
│  └────────────────────────────────────────────────┘      │
│                            │                             │
│                            ▼                             │
│  ┌───────────── PacketEvents 2.11+ Bridge ────────┐      │
│  │  Client-Only BlockChange & MultiBlockChange    │      │
│  │  Entity Projection & Local Occlusion Hiding    │      │
│  └────────────────────────────────────────────────┘      │
│                            │                             │
│                            ▼                             │
│  ┌──────────────── Minecraft Client ──────────────┐      │
│  │  Immersive 3D Window View into Destination     │      │
│  └────────────────────────────────────────────────┘      │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## ✨ Key Features

<details>
<summary><b>🔐 Permission-Gated Immersive Preview (<code>portal.see</code>)</b></summary>

- Only authorized players with the `portal.see` (or `cookieportal.see`) permission view the 3D immersive portal preview.
- Non-permitted players see standard opaque purple Vanilla portal blocks (`Material.NETHER_PORTAL`) and experience normal Vanilla teleport delays.
- Prevents unnecessary packet bandwidth usage on players who do not need portal previews.

</details>

<details>
<summary><b>🌈 Biome & Dimension-Aware Atmosphere Fog</b></summary>

Dynamically spawns atmospheric particles at the depth boundary tailored to the destination biome:
- **Nether Warped Forest**: Cyan/teal spores (`WARPED_SPORE`) + soul fire flames.
- **Nether Crimson Forest**: Red spores (`CRIMSON_SPORE`) + flames.
- **Nether Soul Sand Valley**: Soul particles (`SOUL`) + soul flames.
- **Nether Basalt Deltas**: White ash + ash clouds.
- **The End**: End rod particles (`END_ROD`) + Ender portal atmosphere.
- **Overworld**: Sky clouds (`CLOUD`) + atmospheric white ash.

</details>

<details>
<summary><b>🏰 Expanded & Sealed Virtual Room Shell Box</b></summary>

- Renders a clean, structured virtual room shell (`28 x 28 x 20` blocks) around the destination view.
- Outer boundaries are sealed with natural dimension-appropriate materials (`BLACKSTONE` / `NETHERRACK` in Nether, `END_STONE` in End, `STONE` / `DEEPSLATE` in Overworld), preventing background terrain leaks.

</details>

<details>
<summary><b>👥 Multi-Player & Multi-Side Isolation</b></summary>

- Block change packets are sent via network packets **exclusively to individual player channels**.
- Players standing on opposite sides of a portal or in different dimensions view their own independent, thread-safe, client-side packet streams with zero conflict or interference.

</details>

<details>
<summary><b>⚡ Folia & Paper Native Multithreading</b></summary>

- Built from the ground up for Paper and Folia region schedulers.
- Uses concurrent thread-safe data structures (`ConcurrentHashMap`) and async chunk snapshots.

</details>

---

## 📥 Requirements & Installation

### Requirements

| Requirement | Version |
|:---|:---|
| Server Platform | Paper or Folia 1.21.1+ |
| Java Runtime | Java 21+ |
| Required Dependency | [PacketEvents 2.11.2+](https://github.com/retrooper/packetevents) |

### Installation Steps

1. Download `CookiePortal-1.3.jar` from Releases or build from source.
2. Place `CookiePortal-1.3.jar` into your server's `plugins/` folder.
3. Ensure `PacketEvents 2.11.2+` is installed on your server.
4. Start your server to generate `config.yml`.
5. Grant permissions `portal.see` to allowed groups.

---

## ⚙️ Configuration

<details>
<summary><b>Full <code>config.yml</code> Reference</b> (click to expand)</summary>

```yaml
# CookiePortal 1.3 - Configuration

render:
  enabled: true
  activation-distance: 20
  update-interval-ticks: 2

  # Dimensions of the virtual room rendered behind the portal
  room-width: 28
  room-height: 28
  room-depth: 20

  # Max block change packets updated per cycle to maintain high FPS
  max-block-changes-per-update: 10000

  # Precision of the player's gaze threshold for rendering
  look-dot-threshold: 0.35

  restore-vanilla-surface-distance: 20
  source-refresh-ticks: 40

animation:
  enabled: true
  duration-ticks: 24

entities:
  enabled: true
  maximum-per-portal: 8

debug: false

portal:
  match-destination-size: true

end-portal:
  enabled: true
  preview:
    enabled: true
    activation-distance: 12
    update-interval-ticks: 2
    depth: 9
    particle-minimum-distance: 7
  sky-arrival:
    enabled: true
    height-above-ground: 52
    hover-ticks: 40
    descent-speed: 0.42
    fall-protection-ticks: 80

dimension-stacking:
  enabled: false
  transition-cooldown-millis: 3000
  worlds:
    overworld: ""
    nether: ""
  holes:
    enabled: false
    chance-per-chunk: 0.18
    radius: 1
  nether-arrival-y: 102
  overworld-arrival-y: -50
  end-drop-height: 356
```

</details>

---

## 💬 Commands & Permissions

### Commands

| Command | Description | Permission |
|:---|:---|:---|
| `/cookieportal reload` | Reloads plugin configuration | `cookieportal.admin` |
| `/cookieportal info` | Displays portal registry status & active views | `cookieportal.admin` |

**Aliases**: `/cp`

### Permissions

| Permission | Description | Default |
|:---|:---|:---:|
| `portal.see` | Allows players to see the 3D Immersive Portal preview | `op` |
| `cookieportal.see` | Alias permission for viewing portal previews | `op` |
| `cookieportal.admin` | Access to `/cookieportal` admin commands | `op` |

---

## 📁 Project Structure

```
CookiePortal/
├── pom.xml                                 # Maven build manifest
├── README.md
├── LICENSE                                 # MIT License
├── mvnw.cmd / .mvn/                        # Maven Wrapper
└── src/
    └── main/
        ├── java/dev/khoa/plugin/cookieportal/
        │   ├── CookiePortalPlugin.java            # Plugin main entrypoint
        │   ├── command/
        │   │   └── CookiePortalCommand.java       # Admin command handler
        │   ├── config/
        │   │   └── PortalConfig.java              # Configuration manager
        │   ├── dimension/
        │   │   ├── DimensionStackConfig.java      # Vertical stacking config
        │   │   └── DimensionStackService.java     # Vertical travel service
        │   ├── end/
        │   │   ├── EndPortalConfig.java           # End portal config
        │   │   └── EndPortalService.java          # End portal sky-arrival service
        │   ├── platform/
        │   │   ├── BukkitPortalScheduler.java     # Bukkit task scheduler
        │   │   ├── FoliaPortalScheduler.java      # Folia region scheduler
        │   │   ├── PlatformCompatibility.java     # Platform scheduler compatibility
        │   │   └── PortalScheduler.java           # Scheduler abstraction interface
        │   ├── portal/
        │   │   ├── InstantPortalTravelService.java# Instant travel handler
        │   │   ├── PortalListener.java            # Event listener
        │   │   ├── PortalPlane.java               # Portal plane geometry model
        │   │   ├── PortalRegistry.java            # Thread-safe portal registry
        │   │   └── PortalSizeMatcher.java         # Destination frame matcher
        │   └── render/
        │       ├── PortalProjection.java          # Raycast mathematics
        │       ├── PortalRenderService.java       # Software rasterizer & renderer
        │       ├── ProjectedBlockProtectionListener.java # Block protection
        │       ├── ViewSession.java               # Per-player view session state
        │       └── entity/
        │           ├── EntityPacketBridge.java    # PacketEvents bridge interface
        │           ├── PacketEventsEntityPacketBridge.java # PacketEvents 2.11+ bridge
        │           ├── PortalEntityRenderService.java   # Entity projection service
        │           ├── ProjectedEntity.java        # Projected entity model
        │           └── UnsupportedEntityPacketBridge.java # Fallback bridge
        └── resources/
            ├── config.yml
            └── plugin.yml
```

---

## 🔨 Building from Source

```bash
# Clone the repository
git clone https://github.com/LoveCookieee-java/CookiePortal.git
cd CookiePortal

# Package using Maven Wrapper
./mvnw clean package      # Linux / macOS
.\mvnw.cmd clean package  # Windows
```

Output artifact: `target/CookiePortal-1.3.jar`

---

## 📄 License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) for details.

---

<p align="center">
  <sub>Built with ☕ and 🌀 by <b>Cookieee</b></sub>
</p>
