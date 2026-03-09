# Netro — Rail Network Plugin

**Netro** is a Minecraft (Spigot/Paper 1.21–1.21.11) plugin for **rail networks**: stations, transfer nodes, terminals, and automated cart routing. Build complex rail systems with sign-based setup, destination-based pathfinding, and rule-driven behavior (speed, switches, release mechanisms).

---

## Overview

- **Stations** — Create stations by placing a `[Station]` sign. Each station gets a unique **2D** address: **6-part** colon-separated (e.g. `OV:E2:N3:01:02:05`). **7-part** = terminal (station + `:01`). Dimension **OV** / **NE**; mainnet E/W and N/S; cluster/localnet 01–04. Each cell 100m×100m. A **migration** recomputes all stations’ addresses from sign position and updates DB and signs. Full breakdown in the [guide](docs/GUIDE.md#21-address-system-coordinates--address).
- **Transfer nodes & terminals** — Place **copper bulbs** next to rails and put **`[Transfer]`** or **`[Terminal]`** signs on them with `StationName:NodeName` on line 2. Nodes are created automatically when the station exists.
- **Detectors & controllers** — Detector signs (ENTRY, CLEAR, READY) fire when carts pass; **controller** signs (RELEASE, RULE:N) are turned ON/OFF by the plugin to drive redstone or copper bulb logic.
- **Rules** — Attach rules to any transfer or terminal node: **when** (entering, clearing, detected, blocked), **destination** (going to X, not going to X, any, none), and **action** (set cruise speed, turn bulb on/off, set rail state, set destination when blocked). Open the Rules UI by **sneak + right-click** on a `[Transfer]` or `[Terminal]` sign with the Railroad Controller, or from the station menu.
- **Routing** — Carts have a **destination** (station or station:terminal). Pathfinding runs **on the fly**; results are **cached** (TTL in Minecraft world time, stale-while-revalidate, proactive refresh of reachable pairs). Pair transfer nodes at two stations to define links (including **cross-dimension**: Overworld ↔ Nether); cost = experienced blocks (1 block = 1 cost). If there is **no route** to the destination, the plugin redirects to the **nearest available terminal** and notifies (title/chat). **`/netro clearcache`** clears all route caches.
- **Terminals** — One **READY** detector per terminal. When a cart passes it the plugin marks the cart as held (detection-based; 5s timeout after cart leaves unless CLEAR fires first); **RELEASE** controllers are turned ON when it’s that cart's turn to leave; **CLEAR** fires when a cart leaves and sets held false, cancels the timeout, and turns RELEASE off.

Carts are stored in SQLite so routing, rules, and terminal state persist across chunk loads and restarts. No collision detection between carts. Dispatch is only blocked when the destination is invalid or when it is a terminal that already has a cart held (one slot per terminal); transfer nodes do not track occupancy.

---

## Quick start

1. Get the **Railroad Controller** and **Cart Controller**:  
   `/netro railroadcontroller` · `/netro cartcontroller`
2. Create a **station**: place a sign, line 1 `[Station]`, line 2 the station name (e.g. `Hub`).
3. Place a **copper bulb** next to a rail and put a **`[Terminal]`** or **`[Transfer]`** sign on it; line 2 = `StationName:NodeName` (e.g. `Hub:Main`).
4. On lines 3–4 of the detector sign, add roles: e.g. `ENTRY CLEAR` or `ENTRY L CLEAR R` (optional direction L/R).
5. For terminals, add one **READY** role and a **`[Controller]`** sign with **REL** (RELEASE) for the release mechanism.
6. **Sneak + right-click** the detector sign to open **Rules** and add rules (e.g. “when entering and going to X, set cruise speed”).
7. Right-click a cart with the Cart Controller to set its **destination** (or use `/netro setdestination <destination>`).

---

## Commands

| Command | Description |
|--------|-------------|
| `/netro cancel` | Cancel pending action (relocate, portal link, set rail state). |
| `/netro clearcache` | Clear all route caches and refresh queue. |
| `/netro guide` | Get the in-game guide book. |
| `/netro cartcontroller` | Give the Cart Controller (set destination, cruise speed). |
| `/netro railroadcontroller` | Give the Railroad Controller (station menu, rules, rail direction). |
| `/netro setdestination <dest>` | Set a cart’s destination (address, name, or Station:Terminal). |
| `/netro station list` | List all stations. |
| `/netro dns [prefix]` | List or resolve station addresses. |
| `/netro whereami` | Show current region and station (if at one). |
| `/netro debug` | Toggle debug logging (console). |

---

## Documentation

- **[docs/GUIDE.md](docs/GUIDE.md)** — Full guide: concepts, address format (6-part station, 7-part terminal; colon-separated), Nether support (pairing, routing, cost = experienced blocks), route cache (world-time TTL, clearcache), migration, station setup, detectors and controllers, terminal flow (ENTRY/READY/CLEAR/RELEASE), rules UI (Routes slot 50, Relocate, deferred SET_RAIL_STATE), pairing, and commands.
- **[docs/NETHER_DESIGN.md](docs/NETHER_DESIGN.md)** — Nether coordinate conversion and Overworld-equivalent distance (for routing).

---

## Inspiration

Netro was inspired by [*The New SMARTEST Metro System in Minecraft*](https://www.youtube.com/watch?v=nSqEuU0z6X0), a pure-redstone metro that models the internet as a **network of networks**: local networks connect via a **default gateway** to the district layer, district networks via a gateway to the region layer, and region stations form the top-level network. Addressing there is a **three-color combination** (region + district + local station); each station has **routing tables** that send the cart to the correct track or to the default gateway. The world is divided into **predetermined zones**; once routing is set up for that zoning, you never touch it again.

In Netro the same *idea* (layered, network-style addressing; scalable world coverage) is kept, but the *mechanism* is different: **no default gateway**—pathfinding is connection-based over paired transfer nodes, and we don't force routing into a gateway structure because the Minecraft world is **coordinate-based** (addresses come from block position), not a manual grid. There are no clear visual grids in the world that tell you whether you're in one cluster or mainnet versus another, so the goal was to make setup **less complicated** for users and have routing **inherently work for the whole world** without manual zoning. That trade-off is probably not as computationally efficient as designating default gateways, but we use **address-aware cost** so paths prefer moving toward the destination in hierarchy; the pathfinder also uses the address to **guide which stations it checks first** and **stops as soon as the route is known**, so it still explores fewer stations than a blind search.

---

## Requirements

- **Minecraft** 1.21–1.21.11 (Spigot/Paper or compatible). See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
- **Java** 17+.

---

## License

This project is licensed under the **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)**. Use is allowed for **non-commercial purposes only**; you must give appropriate **attribution** (credit) to the author. See [LICENSE](LICENSE) in this repository.
