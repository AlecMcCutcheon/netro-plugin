# Netro — Rail Network Plugin

Minecraft (Spigot/Paper 1.21–1.21.11) plugin for **rail networks**: stations, transfer nodes, terminals, and automated cart routing. Sign-based setup, pathfinding with route cache, and rule-driven behavior (speed, switches, release mechanisms).

**Compatible with Minecraft 1.21 through 1.21.11** (Spigot or Paper). Tested with 1.21.11.

---

## What's in this release (1.3.0 Beta)

### Performance optimizations

- **Detector / apply** — Rail and bulb updates are spread across ticks (light work first, then rails, ready center, deferred rail task, title, cruise, bulbs) so no single tick does all work. Cart from the event is reused so we skip entity lookup when valid.
- **Cart chunk loading** — Cart list is fetched **asynchronously**; main thread only applies chunk tickets. **Current-dimension** (current + ahead) chunk loading is **skipped when a player is in the cart** (the player already keeps those chunks loaded). **Portal (other-dimension)** lookup still runs; its **database work is now async** (portal repo uses connection overloads; main thread only applies the returned chunk sets).
- **Terminal polling** — Database and “should we RELEASE?” logic run in async; main thread only does cart/block checks and applies bulb list.
- **Throttling** — Detector runs at most every 250 ms per cart; READY velocity correction is throttled and tuned to reduce jitter while keeping carts centered.

### Route cache and commands

- **Route cache** — Shortest-path results are cached; TTL is based on **Minecraft world time** (ticks), not real time, so cache doesn’t expire while the server is off. Stale-while-revalidate: expired entries are still used and refreshed in the background. One worker processes at most one path per 10 ticks; every 30 minutes all reachable station pairs can be enqueued for refresh. Config: **`route-cache-ttl-ms`** (default 30 min) is converted to world ticks.
- **`/netro clearcache`** — Clears all route caches and the refresh queue. Use after major network changes if you want paths recomputed immediately.
- **`/netro cancel`** — Cancels pending relocate, portal link, or set-rail-state action.
- **`/netro whereami`** — Shows your current region and station (if you’re at one).

### Routes menu and UI

- **Routes (slot 50)** — In the Rules screen, **slot 50** opens the cached routes list. At a **terminal**, shows all cached routes from that station. At a **transfer**, shows only routes that use that transfer as first hop. **Clear all** at terminal clears the whole station’s cache; at transfer clears only that transfer’s routes.
- **Routing cost** — Path cost = experienced blocks (1 block = 1 cost in any dimension). Nether pairing and cross-dimension paths unchanged.

### Portal linking

- **Portal Link** — For paired transfer nodes (Overworld ↔ Nether), you can link each node to the actual portal blocks so routing can cost the hop correctly. In the Rules screen (or from the unpair/confirm flow), open **Portal Link** for the transfer node. Choose **Overworld side** or **Nether side**, then right-click the portal blocks in the world. Set both sides on both nodes for routing to use the link. **Clear** removes the portal link for that node. Use **`/netro cancel`** to cancel a pending portal-link click.

### Rules: set rail state

- **Multiple rails per rule** — A single rule can set **multiple rail states**. Add entries (rail + shape) to the rule’s list; state is applied when the cart is within 2 blocks of each rail, so different directions get the right switches. A 30s timeout and hitting another detector clear the pending list.

### Version

- **1.3.0-beta** (Beta — optimizations and 1.21.11 compatibility)

---

## Requirements

- **Minecraft** 1.21–1.21.11 (Spigot/Paper or compatible). Tested with **1.21.11**.
- **Java** 17+

---

## Quick start

1. `/netro railroadcontroller` and `/netro cartcontroller` to get the tools.
2. Place a sign: line 1 `[Station]`, line 2 station name (e.g. `Hub`).
3. Place a copper bulb next to a rail; put a `[Terminal]` or `[Transfer]` sign on it with `StationName:NodeName` on line 2.
4. Add roles on lines 3–4 (e.g. `ENTRY L CLEAR R`). For terminals, add one READY (for slot-holding; READY does not apply rules) and a `[Controller]` sign with REL for release.
5. Sneak + right-click the detector sign to open **Rules** and add rules (e.g. set cruise speed when entering and going to X). Use **Relocate** (slot 52) to move a detector or controller; use **Routes** (slot 50) to view or clear cached routes.
6. Right-click a cart with the Cart Controller to set its **destination** (or `/netro setdestination <dest>`).

See [docs/GUIDE.md](docs/GUIDE.md) and the in-game guide book (`/netro guide`) for full details.
