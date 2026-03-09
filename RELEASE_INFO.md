# Netro — Rail Network Plugin

Minecraft (Spigot/Paper **1.21–1.21.11**, tested with 1.21.11) plugin for **rail networks**: stations, transfer nodes, terminals, and automated cart routing. Sign-based setup, pathfinding with route cache, and rule-driven behavior (speed, switches, release mechanisms).

---

## What's in this release (1.3.0 Beta)

**Performance optimizations** (behavior unchanged; less main-thread work):

- **Detector / apply** — Rail and bulb updates spread across ticks (rails → ready center → deferred rail → title → cruise → bulbs). Cart from the event is reused to skip entity lookup.
- **Cart chunk loading** — Cart list fetched **async**; main thread only applies tickets. Current-dim loading **skipped when a player is in the cart**. Portal (other-dim) lookup unchanged; its **DB work is now async**.
- **Terminal polling** — DB and RELEASE logic run async; main thread only does cart/block checks and applies bulb list.
- **Throttling** — Detector at most every 250 ms per cart; READY velocity correction throttled and tuned.

**Compatibility** — 1.21–1.21.11 (tested with 1.21.11). All 1.2.0 features (route cache, routes menu, portal linking, multi-rail rules, etc.) unchanged.

---

## Requirements

- **Minecraft** 1.21–1.21.11 (Spigot/Paper). **Java** 17+

---

## Quick start

1. `/netro railroadcontroller` and `/netro cartcontroller` to get the tools.
2. Place a sign: line 1 `[Station]`, line 2 station name (e.g. `Hub`).
3. Place a copper bulb next to a rail; put a `[Terminal]` or `[Transfer]` sign on it with `StationName:NodeName` on line 2.
4. Add roles on lines 3–4 (e.g. `ENTRY L CLEAR R`). For terminals, add one READY (for slot-holding; READY does not apply rules) and a `[Controller]` sign with REL for release.
5. Sneak + right-click the detector sign to open **Rules** and add rules (e.g. set cruise speed when entering and going to X). Use **Relocate** (slot 52) to move a detector or controller; use **Routes** (slot 50) to view or clear cached routes.
6. Right-click a cart with the Cart Controller to set its **destination** (or `/netro setdestination <dest>`).

See [docs/GUIDE.md](docs/GUIDE.md) and the in-game guide book (`/netro guide`) for full details.
