# Home Screen Tile Manager (`hmgapi-tile-manager`)

A **Material Design 3** launcher & menu-editor customization mod for **Hyundai, Kia, and Genesis Gen5W** widescreen head units running **[HMG-API](https://github.com/Open-HMG/HMG-API)** (`>= 1.0.0`).

Declutter your Gen5W **All Menus / Home Screen** and **Home Screen Menu Editor** by hiding unused built-in apps (*Voice Memo*, *Kia/Bluelink Connect*, *Valet Mode*, *Quiet Mode*, *HD Radio Data*, etc.) or background mod shortcuts, automatically closing empty grid gaps, and non-destructively backing up hidden tiles so they return to their exact saved slot whenever you toggle them back on.

> **Runs on 100% stock upstream HMG-API (`>= 1.0.0`)** — uses only official public `launcher.*` and `homesetting.*` observe hooks with zero `/system` file modifications and zero core patches.

---

## Features

- **Show or Hide Any Stock or Mod Tile (`live: true`)**
  - Works across both **built-in Gen5W system apps** (`itemId 1..38`) and **HMG-API / NEMESIS / sideloaded mod tiles** (`itemId >= 9000`).
  - Filters hidden tiles out of both the **Gen5W Home Screen (`com.android.launcher3`)** and the **Home Screen Menu Editor (`com.hkmc.system.app.homesetting`)**, applying changes live without rebooting the head unit.
- **Per-Profile Non-Destructive Backup & Restore**
  - Before hiding any tile from `content://com.hkmc.system.app.homesetting.provider/all_menu`, Tile Manager records its exact `packageName`, `className`, `itemId`, and `slot` per driver profile (*Driver 1*, *Driver 2*, *Guest*).
  - Toggling a tile back **ON** (or tapping **Show All**) restores it to its original slot if empty—or the next open slot—while automatically pruning backups for any mod/app uninstalled while hidden so phantom blank tiles never appear.
- **Full Stock *Edit Home Icons* Integration & One-Tap Launch**
  - Tap **Edit Home Icons** directly from Tile Manager's header to open Gen5W's native multi-page drag-and-drop icon arranger on top of Tile Manager (press **Back** to return right to Tile Manager).
  - Reorder tiles or place intentional multi-page spacers in stock *Edit Home Icons*—Tile Manager preserves your custom order and spacing across reloads and reboots even while other tiles remain hidden.
- **Event-Driven Empty Gap Compaction**
  - Optional **Close Empty Gaps** toggle packs visible tiles left whenever you hide a tile or turn compaction ON, without stripping intentional multi-page layouts saved afterward.
- **Material Design 3 Widescreen Interface (`1920×720`)**
  - Adaptive **Day / Night** M3 tonal surfaces (`UiModeManager`), animated stadium switches, genuine Gen5W launcher icons (`Launcher_WP3`) alongside custom mod icons, **All Home Tiles / Packages & Mods / System Apps** navigation rail, one-tap **Show All** self-healing recovery, and safety prompts before hiding critical management apps (*HMG-API*, *NEMESIS*, *Tile Manager*, *Setup*).
  - Supports **NEMESIS Manager → Launch**, **HMG-API Star (★) Key** binding, and stock-gradient Gen5W launcher tiles.

---

## Requirements

- A **Hyundai, Kia, or Genesis** vehicle with a **Gen5W** navigation head unit whose launcher & menu editor hook points are mapped in HMG-API.
- **[HMG-API](https://github.com/Open-HMG/HMG-API)** `1.0.0` or newer installed ([Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)).
- **NEMESIS** (`>= 1.16`) if installing from the **NEMESIS Package Store** or a NEMESIS USB stick.

---

## Installation

Install **[HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)** first, then install Home Screen Tile Manager using whichever method you prefer:

| Release Asset | Use For |
|---|---|
| **`hmgapi-tile-manager-v<version>-nemesis-package.zip`** | **NEMESIS Package Store** (`hmgapi.hmgapi-tile-manager`) or `nemesis install` *(do not unzip onto a flash drive)* |
| **`hmgapi-tile-manager-v<version>-nemesis-usb.zip`** | Installing via **NEMESIS** from a FAT32 / exFAT USB flash drive |
| **`hmgapi-tile-manager-v<version>-hmgapi-usb.zip`** | Installing via the **HMG-API** app (**Updates** tab) from a FAT32 / exFAT USB flash drive |
| **`hmgapi-tile-manager-v<version>.zip`** | Signed HMG-API mod package on its own (`hmgapid engine install`) |

### Option 1 — NEMESIS Package Store
Search for **Home Screen Tile Manager** (`hmgapi.hmgapi-tile-manager`) in the **NEMESIS Package Store** and tap **Install**. Tapping **Launch** in NEMESIS Manager opens **Tile Manager** directly.

### Option 2 — With NEMESIS (USB Flash Drive)
1. Unzip **`hmgapi-tile-manager-v<version>-nemesis-usb.zip`** to the root of a **FAT32** or **exFAT** USB drive so `NEMESIS.data` and `packages/` are at the top level (keep only one NEMESIS USB package on the stick).
2. With Android booted on the head unit, plug the USB drive into the car's data USB port and accept the **Home Screen Tile Manager** install prompt.
3. Visit the Home Screen once so the launcher indexes your tiles, then open **Tile Manager**.

### Option 3 — With the HMG-API App (USB Flash Drive)
1. Unzip **`hmgapi-tile-manager-v<version>-hmgapi-usb.zip`** to the root of a **FAT32** or **exFAT** USB drive so the drive contains `HMGAPI/updates/hmgapi-tile-manager-v<version>.zip`.
2. Plug the drive into the car, open the **HMG-API** app → **Updates**, install **Home Screen Tile Manager**, and tap **Apply changes**.

---

## Compatibility

Home Screen Tile Manager uses HMG-API's `launcher.*` and `homesetting.*` hook points (mapped across EV6, Ioniq 5, Niro, Sportage, Kona, Telluride, and other Gen5W platforms in HMG-API `>= 1.0.0`) and automatically disables itself cleanly on unmapped firmware.

| Vehicle | Firmware Tested | HMG-API |
|---|---|---|
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`, `NEEV23SE`, US) | `260507`, `260903` | `1.1.0-rc.3`, `1.1.1-rc.4` |

Tried it on another Hyundai, Kia, or Genesis Gen5W vehicle? Open a [Car Compatibility Report](../../issues/new?template=car-report.yml) to add your model to the table!

---

## Building from Source

```sh
./build.sh
```

`build.sh` automatically downloads and SHA-256-verifies the official HMG-API Mod SDK pinned in [`hmgapi-sdk.json`](hmgapi-sdk.json), validates hook points against `Open-HMG/HMG-API`, compiles `hooks.jar` and `TileManager.apk`, runs `TileStoreTest`, `test.sh`, and `check-scripts.sh`, and signs all four distribution archives into `dist/`.

- **Author Key UUID:** `28ad71e0-07c0-8e61-8bf3-380cc71f9280`
- **License:** [GNU Affero General Public License v3.0](LICENSE) (`AGPL-3.0`)
