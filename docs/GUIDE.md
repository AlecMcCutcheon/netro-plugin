# Netro — Rail Network Plugin Guide

This guide describes how the Netro plugin works, how to set it up, and how to use the GUI and rules. It is based on the current codebase behavior.

---

## 1. What Netro Is and Why It Exists

Netro is a Minecraft (Bukkit/Spigot 1.21.4) plugin for **rail networks**: stations, transfer nodes, terminals, and cart routing. It lets you:

- Define **stations** with hierarchical addresses (e.g. `2.4.7.3`).
- Attach **transfer nodes** (switches between lines) and **terminals** (parking slots at a station) to stations.
- Use **detector rails and copper bulbs** with signs to trigger **rules** (e.g. set cart speed, power controllers, change rail shape, set destination when blocked).
- Route carts by **destination address** (station or station + terminal index) using **on-the-fly shortest path**—no stored routing tables; pathfinding runs when a cart needs a next hop.

Carts are tracked in a database (SQLite) so that routing, rules, and “held” state at terminals work across chunk loads and server restarts. There is no collision detection between carts; dispatch is only blocked when the destination node is full or invalid.

---

## 2. Concepts

- **Station** — A named location with a unique address (e.g. `2.4.7.3`). Created by placing a sign.
- **Transfer node** — A switch at a station that can be **paired** to another transfer node at another station (the “other side” of the track). Carts can be routed “via” that pair to reach the other station.
- **Terminal** — A parking slot at a station. Each terminal has an index (0-based). A cart’s destination can be a station (any terminal) or a specific terminal (e.g. `2.4.7.3.1`).
- **Detector** — A sign on a **copper bulb** next to a rail. When a cart passes the rail, the detector “fires” with a **role** and **direction** (see below).
- **Controller** — A sign on a copper bulb that can be turned ON/OFF by rules (e.g. RELEASE to release a held cart, or RULE:N to match rule index N).
- **Rules** — Stored per transfer node or terminal. Each rule has a **trigger** (ENTERING, CLEARING, DETECTED, BLOCKED), an optional **destination condition** (going to / not going to a specific destination, or any / not any), and an **action** (e.g. set cruise speed, SEND_ON, SEND_OFF, set rail state, set destination when blocked).

---

## 3. Setting Up a Station

Stations are **created and removed only by signs**; there is no station create/delete command.

1. Place a **wall sign** (or sign post) where you want the station’s reference point.
2. Line 1: **`[Station]`** (exact, any case).
3. Line 2: **Station name** (e.g. `Hub`, `Snowy2`). Must be unique.
4. Finish editing the sign.

The plugin assigns an **address** from the sign’s block position (hierarchical: mainnet.cluster.localnet.station) and writes it on the sign. Breaking the sign **removes** the station and its address.

- **Chunk loading:** The plugin keeps chunks loaded for detector rails that need to see carts; station and detector positions are used for this.

---

## 4. Detectors and Nodes: [Transfer], [Terminal], [Detector]

Detectors are signs placed on **copper bulbs** that are **adjacent to a rail**. When a cart passes that rail, the detector fires. The sign’s first line determines the type and what roles are allowed.

### 4.1 Sign Types

| Line 1       | Line 2          | Purpose |
|-------------|------------------|--------|
| **`[Transfer]`** | `StationName:NodeName` | Boundary detector for a **transfer node**. If the node does not exist, it is created. Allowed roles: **ENTRY**, **CLEAR**. |
| **`[Terminal]`** | `StationName:NodeName` | Boundary detector for a **terminal**. If the node does not exist, it is created. Allowed roles: **ENTRY**, **CLEAR**, **READY** (at most one READY per terminal). |
| **`[Detector]`** | `StationName:NodeName` or node name | Generic detector tied to an existing node. Allowed roles: ENTRY, READY, CLEAR. |

- **Line 2** for [Transfer] and [Terminal] must be **`StationName:NodeName`** (e.g. `Hub:Main`, `Snowy2:PlatformA`). The station must already exist. If the node does not exist, it is created when you place the sign.
- The **copper bulb** must be **next to a rail**; otherwise the plugin tells you to place the bulb adjacent to a rail.
- Breaking the sign (or the bulb) unregisters the detector.

### 4.2 Roles on the Detector Sign (Lines 3–4)

Roles are written on lines 3 and 4, space-separated. You can add a **direction** so the role only fires when the cart is moving that way (e.g. **ENTRY L** = entering from the left, **CLEAR R** = clearing to the right). Shorthand: **ENT**, **REA**, **CLE**, **REL**; directions **L** / **R** (or LEFT / RIGHT).

- **ENTRY** — Fires when the cart passes the detector in the “entering” direction (into the node). Direction is optional; if set (e.g. `ENTRY L`), it only fires when the cart’s direction matches.
- **CLEAR** — Fires when the cart passes in the “clearing” direction (leaving the node). **Direction is respected**: e.g. `CLEAR R` only fires when the cart is actually leaving to the right, not when entering from the left.
- **READY** — (Terminals only.) One per terminal. When a cart passes, the terminal **holds** the cart (increment held count, cart is “at” that terminal). Used with **RELEASE** on controllers to release the next cart in queue when it’s their turn.
- **RELEASE** — Used on **controller** signs (see below), not on detectors. Controllers with RELEASE are turned ON when the routing logic decides to release a cart from that terminal.

Only **ENTRY** and **CLEAR** apply rules; **READY** detectors do not run the rule engine.

So: **entry vs clear** is determined by **direction**. One detector can have both `ENTRY L` and `CLEAR R` (or no direction for “any direction” for ENTRY/CLEAR where the code allows it). The important fix for “set cruise speed on CLEAR” is that CLEAR now only fires when the cart’s direction matches the rule’s direction (e.g. clearing to the right), not when entering.

---

## 5. Controllers

A **controller** is a sign on a copper bulb that the plugin can power ON or OFF. Used for:

- **RELEASE** — Release mechanism at a terminal. When the plugin decides “release the next cart from this terminal,” it turns ON controllers that have **REL** (RELEASE) on their sign. Usually wired to a mechanism that lets the cart leave the hold area.
- **RULE:N** — N is a rule index (0, 1, 2, …). When a rule with action **SEND_ON** or **SEND_OFF** fires, the plugin finds controllers with **RULE:N** for that node and sets them ON or OFF. Optional direction: **RULE:0:L** / **RULE:0:R**.

Controller sign: Line 1 **`[Controller]`**, Line 2 **StationName:NodeName** (same as detectors). Lines 3–4: at least one of **REL** or **RULE:N** (with optional :L/:R).

---

## 6. Terminals: ENTRY, CLEAR, READY, and RELEASE

Terminals are “parking slots” at a station. The flow is:

1. **ENTRY** — Cart passes a detector in the “entering” direction. No slot booking; just used for rules (e.g. “when entering and going to X, set speed”).
2. **READY** — Cart passes the **READY** detector (one per terminal). The plugin:
   - Increments the **held count** for that terminal.
   - Marks the cart as **held** at that node (zone `node:<nodeId>`).
   - If the cart’s destination is **this terminal**, destination is cleared (cart arrived).
   - Decides whether this cart is **first in line** to be released (by queue order and “release reversed” setting). If yes and the cart has a valid destination (and dispatch is allowed), it turns **RELEASE** controllers ON so the mechanism can release this cart.
   - Schedules a short “center hold” so the cart stays near the detector for about a second (READY hold).
3. **CLEAR** — Cart passes the detector in the “clearing” direction (leaving the terminal). The plugin:
   - **Decrements** the held count for that terminal.
   - Clears the cart’s “held” state.
   - Turns OFF all RELEASE controllers for that node.
   - If there are still carts in the queue, it may turn RELEASE ON for the **next** cart to be released (so the next cart can leave).

So: **READY** = “cart has entered the slot and is held”; **RELEASE** = “power the release mechanism for the next cart that should leave”; **CLEAR** = “cart has left the slot, update count and turn off release.”

---

## 7. Pairing Transfer Nodes

Transfer nodes at two different stations can be **paired**. Routing uses these pairs to compute paths (e.g. “from station A, first hop toward station B is this transfer node, because it’s paired to a node at B”).

1. Open **Railroad Controller** (from `/netro railroadcontroller`).
2. **Right-click the [Station] sign** of the station that has the transfer node you want to pair.
3. In the station menu, click the **transfer node** (not a terminal).
4. Click **Open rules**.
5. In the Rules screen, click **Pair transfer node…**.
6. Choose the **other station** and then the **other node**. Confirm.

Both nodes now reference each other. Unpairing is done in the same Rules UI (confirm unpair). Routing is computed on the fly, so no “rebuild routes” step is needed.

---

## 8. Rules UI and Creating Rules

### 8.1 Opening the Rules UI

- **Sneak + right-click** a **[Transfer]** or **[Terminal]** sign (the sign on the copper bulb).  
  This opens the **Rules** screen for that detector’s node (transfer or terminal).

You can also reach rules from the **station menu**: Railroad Controller → right-click [Station] sign → click a node. That opens **Node Options** (Open rules, Relocate, Delete). Click **Open rules** for the Rules screen, or **Relocate** to move this node’s detector/controller without opening rules.

### 8.2 Rules Screen Layout

- **Slots 0–44** — List of existing rules (by rule index).
- **Slot 45** — “Default blocked policy” (when the chosen next hop is blocked; not a deletable rule).
- **Slot 46** — **Pair transfer node…** (transfer context only).
- **Slot 49** — **Create rule**.
- **Slot 52** — **Relocate** — Move this node's detector or controller. Click Relocate, then click the block you want it placed *above* (one-click; you do not click the current bulb). The block *above* your target is where the bulb and sign go. Also in Node Options (station menu → click node).
- **Slot 53** — **Close**.

Click **Create rule** to add a new rule. You then go through: **Trigger** → **Destination** → **Action**. While choosing a block for **Relocate** or a rail for **Set rail state**, the block you’re looking at is **highlighted** (relocate: the block above, where the bulb will go; set rail state: slab-sized outline on normal rails only).

### 8.3 Step 1: Trigger

Only **ENTRY** and **CLEAR** detector events apply rules. **READY** (terminals only) is for slot-holding; READY detectors do *not* run rules.

| Trigger    | When it fires |
|-----------|----------------|
| **When cart enters** | **ENTERING** — Fires when the detector fires with **ENTRY**. READY does not apply rules. |
| **When cart clears** | **CLEARING** — Fires when the detector fires with **CLEAR** (cart leaving). Direction on the detector sign is respected (e.g. CLEAR R only when actually clearing to the right). |
| **When terminal blocked** | **BLOCKED** — When the next hop (e.g. a terminal) is full or invalid. You then pick a “redirect” destination; the rule’s action can be **Set destination** to that redirect. One such rule per blocked-hop case. |
| **When cart detected** | **DETECTED** — Fires when the detector fires with **ENTRY** or **CLEAR** (the two roles that apply rules). |

### 8.4 Step 2: Destination

- **Going to destination…** — Opens a list of destinations (stations/terminals). The rule fires only when the cart’s destination **matches** the chosen one (or resolves to it).
- **Not going to destination…** — Fires when the cart’s destination does **not** match the chosen one.
- **Any destination** — Cart has some destination (rule fires regardless of which).
- **Not any destination** — Cart has no destination.

Destination is compared against the cart’s current destination (or the “local” next-hop destination used for routing). So you can have “when ENTERING and going to StationB:0, set cruise speed.”

### 8.5 Step 3: Action

| Action | Effect |
|--------|--------|
| **Turn bulb ON** | **SEND_ON** — Set controller bulbs with the matching **RULE:N** for this node to ON. |
| **Turn bulb OFF** | **SEND_OFF** — Set those bulbs to OFF. |
| **Set rail state** | **SET_RAIL_STATE** — Change the detector rail’s shape (N/S/E/W/curves). Close the UI, then right-click a **normal rail** with the Railroad Controller to open the shape picker; pick two directions to set the shape. **Cancel** (center) in the shape picker returns to the Action menu; it does not cancel the rule. When editing a rule’s rail state, you must pick a rail and shape (no immediate “updated”). |
| **Set cart speed (cruise)** | **SET_CRUISE_SPEED** — Set the cart’s cruise speed (0.0–9.9 in the GUI, stored as magnitude 0–1). The cart’s “cruise” mode is turned on so it keeps that speed until something else (e.g. READY hold) overrides it. |

For **BLOCKED** trigger, the action is typically **Set destination** to a redirect (e.g. another terminal or station).

---

## 9. Cart Controller and Destinations

- **Cart Controller** — Get it with **`/netro cartcontroller`**. Right-click a cart (or use while in the cart) to open the **cart menu**.
- In the cart menu: **Stop** (cruise off), **Lower speed**, **Disable Cruise** (center), **Increase speed**, **Start** (cruise on); **Direction** (reverse); **Destination** (station or station:terminal, e.g. `Snowy2` or `Snowy2:0`). Adjust speed (1–10) when in cruise.

- **Set destination (command)** — **`/netro setdestination <address\|name\|StationName:TerminalIndex\|StationName:TerminalName>`**  
  Examples: `2.4.7.3`, `Snowy2`, `Snowy2:0`, `Snowy2:Platform A`. You can look at a cart and run the command, or use the cart menu.

Carts without a destination can be assigned one by the plugin (e.g. nearest available terminal) when they pass a detector; otherwise they may be removed from tracking if there are no terminals.

---

## 10. Commands

All commands are under **`/netro`**:

| Subcommand | Purpose |
|------------|--------|
| **debug** | Toggle debug logging (detector and routing) to the server console. |
| **guide** | Give the Netro guide book. |
| **station list** | List all station names. (Stations are created/removed only via [Station] signs.) |
| **setdestination** | Set a cart’s destination (see above). |
| **dns** | Address lookup: list stations by prefix, or resolve a name/address. |
| **cartcontroller** | Give the Cart Controller item. |
| **railroadcontroller** | Give the Railroad Controller item (station menu, rules, rail direction). |

---

## 11. Summary: Entry and Clear vs Ready and Release

- **ENTRY** / **CLEAR** on a **detector sign** define **when** the detector “fires” (by cart direction).  
  **ENTERING** / **CLEARING** in **rules** match those events. **Only ENTRY and CLEAR apply rules**; READY does not.
- **READY** (one per terminal) is the moment the cart is **held** at that terminal slot (count incremented, zone set, release decision made). READY detectors do *not* run the rule engine.
- **RELEASE** on a **controller** is the output: “power the release mechanism” when the plugin decides to release the next cart from that terminal.
- **CLEAR** on the detector is when the cart **leaves** the slot (count decremented, release turned off, next cart in queue may get RELEASE).

So: **ENTRY** → cart entering the node (rules can fire); **READY** → cart held in slot (no rules); **RELEASE** → mechanism on; **CLEAR** → cart left, update state (rules can fire).

---

*This guide reflects the plugin behavior as implemented in the codebase (sign-based creation, on-the-fly routing, rule triggers and actions, and terminal ENTRY/READY/CLEAR/RELEASE flow).*
