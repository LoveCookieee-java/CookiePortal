# 🌀 CookiePortal

**CookiePortal** is a high-performance, packet-only Immersive Portal preview plugin for **Paper** and **Folia** (Minecraft 1.21.1+).

> [!NOTE]
> **Disclaimer & Acknowledgement**:
> This project is a **refined remake and bug-fixed refactor** based on decompiled legacy plugin code. The author **Cookie (LoveCookieee)** does **NOT** claim original ownership or creation of the core software rasterization concept. This repository serves as an open-source, modernized fork dedicated to:
> - 100% clean, readable, human-maintained Java 21 codebase (zero decompiler artifacts).
> - Eliminating all memory leaks, session leaks, and orphaned async scene tasks.
> - Full cross-versionPacketEvents 2.11+ entity projection without brittle NMS reflection.
> - High-performance Paper & Folia multithreading support.

---

## ✨ Features

- **Seamless Portal Previews**: Look through Nether, End, and Custom portals into destination dimensions with real-time packet rasterization.
- **Permission-Gated Viewing (`portal.see`)**: Only players with the `portal.see` permission view the immersive 3D preview. Non-permitted players see standard Vanilla portal blocks and travel normally.
- **Dynamic Dimension & Biome Atmosphere**:
  - Biome-aware particle fog for Nether biomes (Warped Forest cyan spores, Crimson Forest red spores, Soul Sand Valley, Basalt Deltas).
  - Atmospheric End Rod & Ender particles for The End.
  - Clear sky atmospheric clouds for Overworld.
- **Entity Projection**: Real-time cross-dimensional entity preview using PacketEvents 2.11+ API wrappers.
- **Dimension Stacking (Optional)**: Seamless vertical falling transitions (End -> Overworld -> Nether).
- **Folia & Paper Native Support**: Fully compatible with Folia region threading and Paper async chunk scheduling.

---

## ⚙️ Configuration & Permissions

### Permissions
| Permission | Description | Default |
| :--- | :--- | :--- |
| `portal.see` | Allows players to see the Immersive Portal preview | `op` |
| `cookieportal.see` | Alias permission for viewing portal previews | `op` |
| `cookieportal.admin` | Access to `/cookieportal` admin commands | `op` |

### Commands
| Command | Description |
| :--- | :--- |
| `/cookieportal reload` (or `/cp reload`) | Reloads runtime configuration |
| `/cookieportal info` (or `/cp info`) | Displays active portals, views, and dimension status |

---

## 🛠️ Requirements & Building

### Requirements
- **Server**: Paper or Folia 1.21.1+ (Java 21 required).
- **Dependencies**: [PacketEvents 2.11.2+](https://github.com/retrooper/packetevents) installed on the server.

### Building from Source
```bash
# Clone the repository
git clone https://github.com/LoveCookieee-java/CookiePortal.git
cd CookiePortal

# Package using Maven Wrapper
./mvnw clean package  # Linux / macOS
.\mvnw.cmd clean package  # Windows
```
The compiled output will be located in `target/CookiePortal-1.3.jar`.

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for details.
