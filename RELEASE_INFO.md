# Netro — Rail Network Plugin

Minecraft (Spigot/Paper 1.21.4) plugin for **rail networks**: stations, transfer nodes, terminals, and automated cart routing. Sign-based setup, on-the-fly pathfinding, and rule-driven behavior (speed, switches, release mechanisms).

---

## What's in this release

### Documentation & guide

- **Full guide** — [docs/GUIDE.md](docs/GUIDE.md) covers concepts, station setup, detectors and controllers, terminal flow (ENTRY/READY/CLEAR/RELEASE), rules UI (including Relocate), pairing, commands, and a summary of cause-and-effect for terminals.
- **README** — GitHub README with overview, quick start, commands table, and link to the guide.
- **In-game guide book** (`/netro guide`) — Rewritten with a **clickable table of contents** (click a section to jump to that page), **dark gray/black formatting**, and **section titles always at the top of a page** for easier reading.

### Fixes & behavior

- **CLEAR rule direction** — Rules that fire “when cart clears” now respect the detector sign’s direction (e.g. CLEAR R only fires when the cart is actually leaving to the right). Fixes cases where actions like “set cruise speed” on CLEAR were firing when passing in the opposite (ENTRY) direction.
- **MINV/MAXV removed** — Velocity-clamp roles (MINV_/MAXV_) were removed from detector signs and from the codebase. [Detector] signs now only support ENTRY, READY, and CLEAR. Use rules (e.g. SET_CRUISE_SPEED) for speed control instead.
- **Rules only on ENTRY and CLEAR** — Only ENTRY and CLEAR detector events apply rules. READY (terminals only) is used for slot-holding and release logic; READY detectors do not run the rule engine.

### Features and UI

- **Relocate (one-click)** — From the Rules screen or from **Node Options** (station menu → click a node): click **Relocate**, then click the block you want the detector/controller placed *above*. The plugin moves the existing detector/controller for that node; you do not click the current bulb/sign.
- **Node Options screen** — From the station menu (Railroad Controller → right-click [Station] → click a node), you get **Open rules**, **Relocate**, and **Delete** for that node.
- **Set rail state flow** — After choosing “Set rail state”, close the UI and right-click a **normal rail** with the Railroad Controller to open the shape picker; pick two directions to set the shape. **Cancel** (center button) in the shape picker returns you to the **Action** menu; it does not cancel the whole rule. When **editing** a rule’s rail state, you must pick a rail and shape (no immediate “updated”).
- **Block highlighting** — When in relocate mode, the **block above** the one you’re looking at is highlighted (where the bulb will go). When choosing a rail for “Set rail state”, the rail you’re looking at is highlighted with a slab-sized outline (normal rails only).
- **Rules screen layout** — Relocate is in **slot 52** (next to Close). Pair transfer node uses **Ender Eye**; Create rule uses **Writable Book**.
- **Cart Controller menu** — Middle row: **Stop**, **Lower speed**, **Disable Cruise** (center), **Increase speed**, **Start**; bottom row: **Direction**, **Destination**.

### Other

- **Version** — 1.1.0.

---

## Requirements

- **Minecraft** 1.21.4 (Spigot/Paper or compatible)
- **Java** 17+

---

## Quick start

1. `/netro railroadcontroller` and `/netro cartcontroller` to get the tools.
2. Place a sign: line 1 `[Station]`, line 2 station name (e.g. `Hub`).
3. Place a copper bulb next to a rail; put a `[Terminal]` or `[Transfer]` sign on it with `StationName:NodeName` on line 2.
4. Add roles on lines 3–4 (e.g. `ENTRY L CLEAR R`). For terminals, add one READY (for slot-holding; READY does not apply rules) and a `[Controller]` sign with REL for release.
5. Sneak + right-click the detector sign to open **Rules** and add rules (e.g. set cruise speed when entering and going to X). Use **Relocate** (next to Close) to move a detector or controller: click the block you want it above.
6. Right-click a cart with the Cart Controller to set its **destination** (or `/netro setdestination <dest>`).

See [docs/GUIDE.md](docs/GUIDE.md) and the in-game guide book (`/netro guide`) for full details.
