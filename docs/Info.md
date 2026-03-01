# Netro — System Documentation

A distributed intelligent minecart routing network for Minecraft (Paper 1.20+). Carts are addressed packets; stations are routers; transfer nodes and junctions are the physical switching layer. **Physical setup (detectors, controllers, copper bulbs, wands) and full command reference:** see **[DESIGN_DETECTORS_CONTROLLERS.md](DESIGN_DETECTORS_CONTROLLERS.md)**. Scenario diagrams and dispatch behaviour: **[OVERVIEW.md](OVERVIEW.md)**.

---

## Table of Contents

1. [Core Concepts](#core-concepts)
2. [Address System](#address-system)
3. [Commands (summary)](#commands-summary)
4. [Routing Logic](#routing-logic)
5. [Traffic Management](#traffic-management)
6. [Backpressure & Rerouting](#backpressure--rerouting)
7. [Signal System](#signal-system)
8. [DNS System](#dns-system)
9. [API](#api)

---

## Core Concepts

### Stations

A station is a named sign placed in the world. It gets a hierarchical address from world coordinates. A station is the logical identity of a stop; it does not perform switching itself.

### Transfer Nodes

A transfer node is the physical switching hardware at a station. One station can have many transfer nodes — one per connection to another station, plus terminal nodes for platform tracks. Every connection requires **two** transfer nodes (one at each end), created and then linked (e.g. with the **pairing wand**).

### Junctions

A junction is an optional mid-segment traffic control point between two transfer nodes. It provides a hold siding so two carts can be on the same segment without collision. Segment and junction order are registered (e.g. with the **segment wand**).

### Terminals

A terminal is a transfer node where the owning and destination station are the same — a dead-end platform. Terminals get a fifth address tier (e.g. `2.4.7.3.1`) so you can route to a specific platform or use the four-part address for any available terminal.

---

## Address System

Addresses are assigned automatically when a station sign is placed, from world coordinates in nested zones.

```
mainnet . cluster . localnet . station
  2    .    4    .     7    .    3
```

| Tier | Zone size | Example |
|------|-----------|---------|
| Mainnet | 4000 blocks | `2` |
| Cluster | 500 blocks | `2.4` |
| Localnet | 100 blocks | `2.4.7` |
| Station | sequential within localnet | `2.4.7.3` |
| Terminal | sequential within station | `2.4.7.3.1` |

The routing engine is a graph of transfer node connections; tier boundaries do not change routing. Shortest path is always used.

### Terminal Addresses

- `2.4.7.3` → any available terminal at that station  
- `2.4.7.3.0` / `2.4.7.3.1` → specific terminal

A four-part destination uses whichever terminal has capacity; a five-part destination targets one terminal.

---

## Commands (summary)

| Command | Description |
|---------|-------------|
| `/station` | `info`, `setaddress`. Station sign: `[Station]` on line 1, name on line 2. |
| `/setdestination <address \| name>` | Set cart destination (while riding). |
| `/dns` | `list`, `list cluster`, `list main <n>`, `lookup <name>` — browse/lookup stations; results clickable to set destination. |
| `/signal` | `register` (right-click lectern), `bind <label> <event> <0-15>`, `list`. |
| `/route` | `create`, `list` — named display routes for map tools. |

**Transfer, terminal, junction, absorb, pairing wand, segment wand:** see **[DESIGN_DETECTORS_CONTROLLERS.md](DESIGN_DETECTORS_CONTROLLERS.md)** (Command Summary).

---

## Routing Logic

When two transfer nodes are linked (e.g. via pairing wand or `/transfer pair`), the routing engine runs Dijkstra and updates routing tables. Each entry has a destination prefix, next-hop node, and cost. Prefix matching tries the most specific first (station → localnet → cluster → mainnet). Distant stations cluster into single routing entries.

---

## Traffic Management

**Without junctions:** Segment is single-track; only one cart on the segment at a time.  
**With junctions:** Carts can share the segment; dispatch checks opposing traffic and "committed past last junction" and junction capacity. See **[OVERVIEW.md](OVERVIEW.md)** for scenario diagrams and outcomes.

---

## Backpressure & Rerouting

When a destination node is full, the hold propagates backward; stations track free inbound/outbound capacity. If a cart is held longer than the reroute timeout (e.g. 30s), the engine can try an alternate next-hop; if one exists, the cart can be released via the other node.

---

## Signal System

Lecterns near stations can be registered as signal outputs. The plugin drives comparator level (0–15) via lectern state.

1. Place a lectern near a station.  
2. `/signal register` → right-click lectern → type label in chat.  
3. `/signal bind <label> <event> <level>`.  
4. Wire redstone from the lectern comparator.

**Events:** `cart_routed`, `cart_routed_north` (etc.), `cart_arrived`, `station_clear`.

---

## DNS System

Scoping is based on your position relative to the nearest station:

- `/dns list` — same localnet  
- `/dns list cluster` — full cluster  
- `/dns list main 3` — mainnet 3  
- `/dns lookup <name>` — find by name from anywhere  

Results are clickable to set destination (four-part = any terminal, five-part = specific terminal).

---

## API

```java
NetroAPI api = ((NetroPlugin) Bukkit.getPluginManager().getPlugin("Netro")).getAPI();
```

**Representative methods:** `getStation`, `getAllStations`, `getTransferNodesForStation`, `getTerminals`, `getJunction`, `getJunctionsOnSegment`, `getCartData`, `getStationPressure`, `getSegmentPolyline`, `getRoutes`. See `NetroAPI` for the full list.

---

## Quick reference

- **Design and commands (transfer, terminal, junction, wands, absorb):** **[DESIGN_DETECTORS_CONTROLLERS.md](DESIGN_DETECTORS_CONTROLLERS.md)**  
- **Dispatch scenarios and diagrams:** **[OVERVIEW.md](OVERVIEW.md)**  
- **Doc index:** **[README.md](README.md)**
