# Nether support

This document describes how Nether support works: addressing, pairing, and routing with Overworld-equivalent distance. **Implemented** in the current codebase.

## Coordinate conversion

Minecraft uses an **8:1 ratio** for X and Z between Nether and Overworld:

- **Nether → Overworld:** `overworld_x = nether_x * 8`, `overworld_z = nether_z * 8`
- **Overworld → Nether:** `nether_x = overworld_x / 8`, `nether_z = overworld_z / 8`

Y is not scaled (1:1). The plugin uses `DimensionHelper` for these conversions and for “Overworld-equivalent” block distance.

## Why Overworld-equivalent distance

When choosing a route (e.g. long Overworld path vs Overworld → Nether → … → Nether → Overworld), we need a single notion of “distance” so the pathfinder can prefer the actually shorter path. We express everything in **Overworld-equivalent blocks**:

- **Same dimension Overworld:** 1 block = 1 OW-equivalent block.
- **Same dimension Nether:** 1 block in the Nether = 8 OW-equivalent blocks (the cart moves 8 “OW blocks” per Nether block).
- **Portal hop (OW↔Nether, Overworld distance only):** The Nether station at `(n_x, n_z)` corresponds to Overworld position `(n_x*8, n_z*8)`. The “distance” for that hop is the Overworld distance from the Overworld station to `(n_x*8, n_z*8)`. Portal boundary is unknown, so no Nether block count is added for the portal hop.

So when the cart is at a Nether station, the next hop (to another Nether station or back to Overworld) is always costed in OW-equivalent blocks; the routing logic only needs to know each station’s dimension and coordinates and then use `DimensionHelper.overworldEquivalentBlocks(...)`.

## Addressing

Addresses use a **dimension tier** and a **2D hierarchy** (mainnet → cluster quad → localnet quad → station):

- **Overworld:** dimension `OV` → e.g. `OV:E2:N3:01:02:05` (station), `OV:E2:N3:01:02:05:01` (terminal).
- **Nether:** dimension `NE` → e.g. `NE:W1:S2:02:03:01`, `NE:W1:S2:02:03:01:01`.

Mainnet is a 2D point labeled with cardinals (E/W for X, N/S for Z). Cluster quad and localnet quad are each 1–4 (from sign of local X,Z). Each (cq, lq) identifies a 100m×100m cell. Stations store dimension; address is derived from dimension + block X + block Z + station index in that cell. A migration recomputes addresses from sign coordinates and updates the database and signs.

## Same-dimension rule for setup

Terminals, transfer nodes, detectors, and controllers must be in the **same dimension** as the station (or the node’s station) they belong to. You cannot attach a transfer node or terminal to a station in another dimension, or place a detector/controller sign that points at a node in another dimension. When you use **Station:Node** on a detector or controller sign, the plugin resolves the station by name **in the sign’s dimension only**; if the station exists only in the other dimension, you get a clear error and must create the station in this dimension first. Pairing (linking two nodes across the network) remains allowed across dimensions (OW↔Nether).

## Pairing and routing

- A transfer node in the **Overworld** can be paired with a transfer node in the **Nether** (and vice versa). The user is responsible for the portal that moves carts between dimensions; the plugin only needs to allow the pair and cost the edge correctly.
- **Edge cost** between two stations (same or different dimension) = base hop + `overworldEquivalentBlocks(...) / 100`, using each station’s sign X/Z and dimension. So long Overworld distances can be beaten by a path that goes OW → N → (short N leg) → OW when the N leg is short enough in OW-equivalent terms.

## Implementation status

- **DimensionHelper** — Nether↔OW coordinate conversion and `overworldEquivalentBlocks(...)` for coordinates + dimension (same-dimension OW 1:1, same-dimension Nether 8×, portal hop = OW distance only).
- **Stations** — Schema and model include `dimension`; AddressHelper uses 6-part colon station (e.g. OV:E2:N3:01:02:05) and 7-part terminal (station + :01); StationListener assigns address from 2D quadrant cell (100m).
- **Routing** — `RoutingEngine.edgeCostBetweenStations` uses `DimensionHelper.overworldEquivalentBlocks` so cross-dimension and Nether legs are costed correctly.
- **Migration** — SchemaMigration adds dimension column if missing, backfills dimension, then recomputes every station’s 2D address from sign position and updates DB and signs; clears old-format cart destinations.
- **Same-dimension enforcement** — When placing [Detector], [Transfer], [Terminal], or [Controller] signs, station lookup uses the sign’s dimension; you cannot assign a node to a station in another dimension, or target a node in another dimension.
