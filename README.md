# Netro

**Netro** is a Minecraft (Spigot/Paper 1.21) plugin for **rail networks**: stations, transfer nodes, terminals, and automated cart routing. Build complex rail systems with sign-based setup, destination-based pathfinding, and rule-driven behavior (speed, switches, release mechanisms).

---

## Overview

- **Stations** — Create stations by placing a `[Station]` sign. Each station gets a unique hierarchical address (e.g. `2.4.7.3`) and can have multiple **transfer nodes** (switches between lines) and **terminals** (parking slots).
- **Transfer nodes & terminals** — Place **copper bulbs** next to rails and put **`[Transfer]`** or **`[Terminal]`** signs on them with `StationName:NodeName` on line 2. Nodes are created automatically when the station exists.
- **Detectors & controllers** — Detector signs (ENTRY, CLEAR, READY) fire when carts pass; **controller** signs (RELEASE, RULE:N) are turned ON/OFF by the plugin to drive redstone or copper bulb logic.
- **Rules** — Attach rules to any transfer or terminal node: **when** (entering, clearing, detected, blocked), **destination** (going to X, not going to X, any, none), and **action** (set cruise speed, turn bulb on/off, set rail state, set destination when blocked). Open the Rules UI by **sneak + right-click** on a `[Transfer]` or `[Terminal]` sign with the Railroad Controller, or from the station menu.
- **Routing** — Carts have a **destination** (station or station:terminal). Pathfinding runs **on the fly** (no stored route tables). Pair transfer nodes at two stations to define links; the plugin finds shortest paths and dispatches carts only when the next hop is valid.
- **Terminals** — One **READY** detector per terminal holds carts in a queue; **RELEASE** controllers are turned ON to let the next cart out when it’s their turn; **CLEAR** fires when a cart leaves and updates the slot count.

Carts are stored in SQLite so routing, rules, and terminal state persist across chunk loads and restarts. No collision detection between carts—only destination and slot availability are enforced.

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
| `/netro guide` | Get the in-game guide book. |
| `/netro cartcontroller` | Give the Cart Controller (set destination, cruise speed). |
| `/netro railroadcontroller` | Give the Railroad Controller (station menu, rules, rail direction). |
| `/netro setdestination <dest>` | Set a cart’s destination (address, name, or Station:Terminal). |
| `/netro station list` | List all stations. |
| `/netro dns [prefix]` | List or resolve station addresses. |
| `/netro debug` | Toggle debug logging (console). |

---

## Documentation

- **[docs/GUIDE.md](docs/GUIDE.md)** — Full guide: concepts, station setup, detectors and controllers, terminal flow (ENTRY/READY/CLEAR/RELEASE), rules UI, pairing, and commands.

---

## Requirements

- **Minecraft** 1.21 (Spigot/Paper or compatible).
- **Java** 17+.

---

## License

See [LICENSE](LICENSE) in this repository.
