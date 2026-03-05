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
- **Terminal held = detection + timeout** — At terminals, “held” is set true while the READY detector sees a cart. When the cart leaves detection, a 5-second timeout starts; after 5s held is set false and RELEASE turns off, unless CLEAR fires first (then held is set false immediately). No held count; transfer nodes do not track occupancy.

### 2D addressing and Nether support

- **2D addresses** — Station addresses are **6-part colon-separated** (e.g. `OV:E2:N3:01:02:05`). Terminal = **7-part** (e.g. `OV:E2:N3:01:02:05:01`). Only colon format. Mainnet is one token (E/W, N/S). Cluster quad and localnet quad are each **1–4** from sign of local X,Z. Each cell is 100m×100m. Dimension **OV** / **NE**.
- **Migration** — On first run (and whenever stations still have an old address format): add dimension column if missing; backfill dimension; **recompute every station’s address from its sign block position (X, Z)** using 6-part colon format and update DB and signs; migrate rules' destination_id where possible; clear old-format cart destinations.
- **Nether pairing and routing** — Transfer nodes can be paired cross-dimension (Overworld to Nether). Routing cost = experienced blocks (1 block = 1 cost in any dimension). Paths like OW to Nether to Nether to OW are costed correctly.

### Features and UI

- **Relocate (one-click)** — From the Rules screen or from **Node Options** (station menu → click a node): click **Relocate**, then click the block you want the detector/controller placed *above*. The plugin moves the existing detector/controller for that node; you do not click the current bulb/sign.
- **Node Options screen** — From the station menu (Railroad Controller → right-click [Station] → click a node), you get **Open rules**, **Relocate**, and **Delete** for that node.
- **Set rail state flow** — After choosing “Set rail state”, close the UI and right-click a **normal rail** with the Railroad Controller to open the shape picker; pick two directions to set the shape. **Cancel** (center button) in the shape picker returns you to the **Action** menu. Multiple rails per rule; deferred application (when cart within 2 blocks of each rail); 30s timeout and new detector cancel the list. When **editing** a rule’s rail state, you must pick a rail and shape (no immediate “updated”).
- **Block highlighting** — When in relocate mode, the **block above** the one you’re looking at is highlighted (where the bulb will go). When choosing a rail for “Set rail state”, the rail you’re looking at is highlighted with a slab-sized outline (normal rails only).
- **Rules screen layout** — **Routes** in **slot 50** (cached routes from this station/node); Relocate in **slot 52** (next to Close). Pair transfer node uses **Ender Eye**; Create rule uses **Writable Book**.
- **Routes menu** — At a **terminal**, shows all cached routes from that station. At a **transfer**, shows only routes that use that transfer as first hop. **Clear all** at terminal clears the whole station’s cache; at transfer clears only that transfer’s routes.
- **Cart Controller menu** — Middle row: **Stop**, **Lower speed**, **Disable Cruise** (center), **Increase speed**, **Start**; bottom row: **Direction**, **Destination**.

### Route cache and commands

- **Route cache** — Shortest-path results are cached; TTL is based on **Minecraft world time** (ticks), not real time, so cache doesn’t expire while the server is off. Stale-while-revalidate: expired entries are still used and refreshed in the background. One worker processes at most one path per 10 ticks; every 30 minutes all reachable station pairs can be enqueued for refresh. Config: **`route-cache-ttl-ms`** (default 30 min) is converted to world ticks.
- **`/netro clearcache`** — Clears all route caches and the refresh queue. Use after major network changes if you want paths recomputed immediately.
- **`/netro cancel`** — Cancels pending relocate, portal link, or set-rail-state action.
- **`/netro whereami`** — Shows your current region and station (if you’re at one).

### Other

- **Version** — 1.2.0.

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
