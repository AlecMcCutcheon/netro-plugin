# Netro — Rail Network Plugin

Minecraft (Spigot/Paper 1.21.4) plugin for **rail networks**: stations, transfer nodes, terminals, and automated cart routing. Sign-based setup, on-the-fly pathfinding, and rule-driven behavior (speed, switches, release mechanisms).

---

## What's in this release

### Documentation & guide

- **Full guide** — [docs/GUIDE.md](docs/GUIDE.md) covers concepts, station setup, detectors and controllers, terminal flow (ENTRY/READY/CLEAR/RELEASE), rules UI, pairing, commands, and a summary of cause-and-effect for terminals.
- **README** — GitHub README with overview, quick start, commands table, and link to the guide.
- **In-game guide book** (`/netro guide`) — Rewritten with a **clickable table of contents** (click a section to jump to that page), **dark gray/black formatting**, and **section titles always at the top of a page** for easier reading.

### Fixes & behavior

- **CLEAR rule direction** — Rules that fire “when cart clears” now respect the detector sign’s direction (e.g. CLEAR R only fires when the cart is actually leaving to the right). Fixes cases where actions like “set cruise speed” on CLEAR were firing when passing in the opposite (ENTRY) direction.
- **MINV/MAXV removed** — Velocity-clamp roles (MINV_/MAXV_) were removed from detector signs and from the codebase. [Detector] signs now only support ENTRY, READY, and CLEAR. Use rules (e.g. SET_CRUISE_SPEED) for speed control instead.

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
4. Add roles on lines 3–4 (e.g. `ENTRY L CLEAR R`). For terminals, add one READY and a `[Controller]` sign with REL for release.
5. Sneak + right-click the detector sign to open **Rules** and add rules (e.g. set cruise speed when entering and going to X).
6. Right-click a cart with the Cart Controller to set its **destination** (or `/netro setdestination <dest>`).

See [docs/GUIDE.md](docs/GUIDE.md) and the in-game guide book (`/netro guide`) for full details.
