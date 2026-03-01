# Netro — Detectors & Controllers Design

## Overview

This document describes the physical interaction model for Netro's transfer nodes and junctions. The plugin handles all routing decisions (where a cart should go next, when it is safe to release). Players handle all physical execution (which blocks to throw, which gates to open) via a redstone-native system of **Detectors** and **Controllers**.

The plugin never directly sets rail or powered rail block state. It only toggles **copper bulb** state. Players wire those copper bulbs to whatever redstone they want. The plugin is a smart event source; the player's redstone is the executor.

---

## Core Philosophy

**Plugin decides. Player executes.**

| Responsibility | Owner |
|---|---|
| Which station is the next hop | Plugin (Dijkstra routing) |
| When to hold vs release a cart | Plugin (traffic/backpressure logic) |
| Which direction a cart is traveling | Plugin (derived from sign facing + cart velocity) |
| What physical blocks execute a decision | Player (via copper bulb wiring) |
| What constitutes "cart detected" | Player (via detector placement) |
| Redstone circuit design | Player (completely free) |

---

## Physical Blocks

### Copper Bulb

The shared physical block for both Detectors and Controllers. The plugin reads and sets the copper bulb's powered state. Players attach signs to identify the bulb's role and wire it to their redstone.

**One sign per copper bulb.** Multiple signs on one block are not supported and should not be encouraged — it leads to ambiguous registration and cluttered logic.

### Sign Placement

The sign is placed on any face of the copper bulb that is not blocked by a rail or other block. Ideally placed on the face pointing away from the rail (the back face). The sign's **world-facing direction** (the direction it faces outward toward the reader) is what the plugin uses to compute LEFT and RIGHT. The physical side it is placed on does not matter beyond being readable.

---

## Direction System

### Sign Facing → Left/Right

When a sign is placed on a copper bulb, the plugin reads the sign's `BlockFace` facing (the direction the sign faces outward). From that facing, LEFT and RIGHT are derived as simple 90° rotations:

```
Sign facing NORTH:  LEFT = WEST,  RIGHT = EAST
Sign facing EAST:   LEFT = NORTH, RIGHT = SOUTH
Sign facing SOUTH:  LEFT = EAST,  RIGHT = WEST
Sign facing WEST:   LEFT = SOUTH, RIGHT = NORTH
```

### Cart Direction Detection

When a minecart passes a detector's rail block, its velocity vector is snapped to the nearest cardinal direction (NORTH, SOUTH, EAST, WEST). That cardinal is compared against the computed LEFT and RIGHT faces of the sign. Whichever matches determines which direction label fires.

If the cart's direction matches LEFT → fire LEFT rules.
If the cart's direction matches RIGHT → fire RIGHT rules.

### Java Reference

```java
BlockFace signFacing = sign.getFacing();

BlockFace rotateClockwise(BlockFace f) {
    return switch (f) {
        case NORTH -> EAST;
        case EAST  -> SOUTH;
        case SOUTH -> WEST;
        case WEST  -> NORTH;
        default    -> f;
    };
}

BlockFace left  = rotateCounterClockwise(signFacing);
BlockFace right = rotateClockwise(signFacing);

// Snap cart velocity to cardinal
Vector vel = cart.getVelocity();
BlockFace cartDir = snapToCardinal(vel);

// Match
if (cartDir == left)  → direction = LEFT
if (cartDir == right) → direction = RIGHT
```

---

## Detectors

A Detector is a copper bulb placed adjacent to a rail with a sign identifying it. When a minecart passes the rail block the detector is adjacent to, the plugin reads the cart's direction, evaluates the sign rules, and fires matching events.

The detector's own copper bulb toggles on briefly (then off after ~1 second) whenever any cart passes regardless of direction — so the player always has a basic "cart passed" signal available for their own redstone use without any link configuration needed.

### Sign Format

```
Line 1: [Detector]  OR  [Transfer]  OR  [Terminal]  OR  [Junction]
Line 2: StationName:NodeName  OR  JunctionName  OR  JunctionName:LEFT/RIGHT
Line 3: ROLE:DIRECTION  (or just ROLE if non-directional)
Line 4: ROLE:DIRECTION  (optional second rule)
```

**Line 1 — detector type:** `[Transfer]` = boundary detector for a transfer node (one per node; Line 2 must be `StationName:NodeName` for a non-terminal node). `[Terminal]` = boundary detector for a terminal (Line 2 = `StationName:NodeName` for a terminal node). `[Junction]` = detector for one side of a junction (Line 2 = junction name only, no colon). `[Detector]` = generic detector (any target; use for station detectors with ROUTE or when you don't need type validation). The plugin validates that [Transfer]/[Terminal]/[Junction] match the resolved target. See **Physical anchors: Transfer, Terminal, Junction** for full design.

**Line 2 — target:**
- **Transfer nodes / terminals:** Use `StationName:NodeName` (e.g. `MainHub:WestMine`). Node names can repeat across stations, so the station disambiguates. Case-insensitive.
- **Junctions:** Use the junction name only (e.g. `JunctionAlpha`). Junctions are globally named, so no colon.

If you use a bare node name with no colon and only one station has a node with that name, it still resolves (backward compatible). If multiple stations have the same node name, use `StationName:NodeName`.

Line 2 and 3: one or more rules per line, space-separated. A rule is a `ROLE` (or 3-letter shorthand) optionally with `:LEFT`/`:RIGHT` or `:L`/`:R`. Up to 4 rules total are stored (first 4 from line 2 and 3 combined).

**3-letter shorthand (no collisions):** ENT=ENTRY, REA=READY, CLE=CLEAR (detectors); DIV=DIVERT, DIV+=DIVERT+, REL=RELEASE, NOD=NOT_DIVERT, NOD+=NOT_DIVERT+ (controllers).

**Example — bidirectional siding entrance (transfer node at MainHub named WestMine):**
```
[Detector]
MainHub:WestMine
ENTRY:LEFT
CLEAR:RIGHT
```
Meaning: when a cart passes going leftward (relative to this sign's facing), fire ENTRY. When a cart passes going rightward, fire CLEAR.

**Example — center of siding:**
```
[Detector]
MainHub:WestMine
READY
```
No direction suffix — fires whenever any cart reaches this point regardless of which way it came from.

**Example — other end of bidirectional siding:**
```
[Detector]
MainHub:WestMine
ENTRY:RIGHT
CLEAR:LEFT
```

**Example — junction (bare name):**
```
[Detector]
JunctionAlpha
ENTRY:LEFT
CLEAR:RIGHT
```

### Detector Roles

| Role | Meaning | Direction needed? |
|---|---|---|
| `ENTRY` | A cart has entered the hold siding | Yes — which end it entered from |
| `READY` | A cart has fully stopped in the siding and is held | No |
| `CLEAR` | A cart has exited the siding and is back on the main line | Yes — which end it exited from |

### Registration

Detectors register automatically when a sign with `[Detector]`, `[Transfer]`, `[Terminal]`, or `[Junction]` on line 1 is placed on a copper bulb adjacent to a rail. The plugin resolves the target from line 2 (StationName:NodeName or junction name), computes the LEFT/RIGHT faces from the sign's world-facing, parses line 3 and line 4 for rules, and stores the registration. No command needed.

**Validation and feedback:** If something is wrong (unknown target, wrong type, missing rules), the plugin sends a message to the player in **chat only**. The sign is never overwritten with error text; the player can correct the sign and try again.

**Create-on-first:** If the target does not exist yet but the context is valid, the plugin creates it and then registers the detector:
- **[Transfer]** or **[Terminal]** with `StationName:NodeName` where the **station exists** but the **node does not** → the plugin creates the transfer node or terminal at that station and registers the detector. Pair later with `/transfer pair` or the pairing wand.
- **[Junction]** with a **junction name** that does not exist yet → the plugin creates the junction (with that name and the bulb’s world/coords) and registers the detector. Register the other side when ready; use `/junction segment` to attach the junction to a segment.

The same create-on-first behavior applies when using **/absorb** on a detector sign (e.g. after a DB reset).

**Sign styling on success:** When registration succeeds, the sign is styled: line 1 = type (bold + color by type), line 2 = target (white), lines 3–4 = rules (gray). Station signs similarly get line 1 = [Station] (blue bold), line 2 = name (white), line 3 = address (gray).

---

## Controllers

A Controller is a copper bulb with a sign identifying it. The plugin activates (turns on or off) the copper bulb's powered state when the routing engine fires a matching event. The player wires the copper bulb to whatever physical mechanism executes the action.

### Sign Format

```
Line 1: [Controller]
Line 2: StationName:NodeName  OR  JunctionName
Line 3: ROLE:DIRECTION  (or just ROLE)
Line 4: ROLE:DIRECTION  (optional second rule)
```

Same format as detectors for line 2 (Station:Node for transfer nodes/terminals, bare name for junctions). Multiple rules on one controller means that controller activates for any matching event.

**Example — transfer switch on the A side:**
```
[Controller]
MainHub:WestMine
DIVERT:LEFT
```
Activates (bulb turns ON) when the plugin wants to divert a cart entering from the left. Player wires to the transfer switch mechanism.

**Example — gate that serves both directions:**
```
[Controller]
MainHub:WestMine
RELEASE:LEFT
RELEASE:RIGHT
```
Activates when the plugin wants to release a cart in either direction. Player wires to the gate that opens regardless of direction.

**Example — main line stays open when not diverting (NOT_DIVERT):**
```
[Controller]
MainHub:WestMine
NOT_DIVERT:LEFT
```
Activates when the plugin decided **not** to divert a cart entering from the left (cart passes through). Turns OFF when we do divert. Use this to keep the main-line path open by default and only switch when DIVERT is active.

### Controller Roles

| Role | Plugin fires ON when... | Plugin fires OFF when... |
|---|---|---|
| `DIVERT` | Cart needs to leave main line into siding (ENTRY) | Only when next ENTRY says don't divert (CLEAR does **not** touch plain DIVERT) |
| `DIVERT+` | Same as DIVERT (ENTRY) | **CLEAR** detector fires — any direction (both sides reset) |
| `NOT_DIVERT` | Cart is **not** diverting (ENTRY) | Only when next ENTRY says divert (CLEAR does **not** touch plain NOT_DIVERT) |
| `NOT_DIVERT+` | Same as NOT_DIVERT (ENTRY) | **CLEAR** detector fires — any direction |
| `RELEASE` | Cart is safe to leave siding (READY) | CLEAR detector fires |

**The + suffix (DIVERT+, NOT_DIVERT+):** Controllers **without** + are driven only by ENTRY; their state is never changed by CLEAR. Controllers **with** + are turned **off** when CLEAR fires (all directions for that node/junction), so both DIVERT+ and NOT_DIVERT+ go off on CLEAR. Use **DIVERT+:LEFT** or **NOT_DIVERT+:LEFT** when you want that bulb to reset (turn off) when the cart clears; use **DIVERT:LEFT** or **NOT_DIVERT:LEFT** when you want the bulb to stay in its last ENTRY state until the next cart's ENTRY. CLEAR never turns any controller **on**; it only turns off RELEASE and the + roles.

### Registration

Same as detectors — automatic on sign placement. No command needed. Validation errors (unknown target, missing rules) are shown in chat only; the sign is not overwritten. On success, the sign receives the same styling (line 1 = type bold+color, line 2 = target, lines 3–4 = rules).

---

## Event Flow

### Full bidirectional siding sequence

```
Cart approaches on main line
        ↓
[Detector: ENTRY:LEFT] fires
        ↓
Plugin: should this cart divert?
  → Check routing table for cart's destination
  → Check segment ahead (is it safe to proceed without holding?)
  → If hold needed: fire [Controller: DIVERT:LEFT] ON, [Controller: NOT_DIVERT:LEFT] OFF
  → If no hold needed: fire [Controller: DIVERT:LEFT] OFF, [Controller: NOT_DIVERT:LEFT] ON (cart passes through)
        ↓
Player's redstone throws transfer switch
Cart diverts into siding, rolls to stop
        ↓
[Detector: READY] fires
        ↓
Plugin: cart is now held
  → Record cart as held at this node
  → Check downstream: is next segment clear?
  → Is destination node accepting carts?
  → If safe: fire [Controller: RELEASE:LEFT] ON  (or RIGHT depending on which way it exits)
  → If not safe: wait, retry when downstream pressure clears
        ↓
Player's redstone opens gate
Cart exits siding onto main line
        ↓
[Detector: CLEAR:RIGHT] fires  (cart exited toward B side)
        ↓
Plugin: fire [Controller: DIVERT:LEFT] OFF
  → Siding is now empty
  → Check if another cart is queued → fire RELEASE again if so
  → Update station pressure
```

### Pass-through (no hold needed)

```
Cart approaches
        ↓
[Detector: ENTRY:LEFT] fires
        ↓
Plugin: cart does not need to stop here
  → No controllers fired
  → Cart passes through on main line unaffected
```

---

## Station detector and station controller (first decision only)

**No changes to existing detector/controller behavior.** ENTRY still causes divert/not divert; READY, CLEAR, RELEASE, DIVERT, NOT_DIVERT, and the rest of the decision tree at a transfer node (the "mini junction") stay exactly as they are. Once the cart is on a transfer node, that logic works as today. The **station** detector and **station** controller are **additive** and handle only the **very first decision**: which terminal or transfer node do we send this cart to? That happens **before** the cart is on any branch. Once the cart is on a branch, it’s hard to reroute. So:

### Station detector (decision point)

A **station-level** detector at the rail **before** the junction.

**Sign format:**
```
Line 1: [Detector]
Line 2: Station1
Line 3: ROUTE ROU:LEFT
Line 4: ROU:RIGHT
```

- **Line 2** — station name only (e.g. `Station1`, `MainHub`). No node or junction.
- **Lines 3–4** — one or more rules per line, **space-separated** (same as other detectors). Up to **4 rules total** stored (first 4 from lines 3 and 4 combined). Rules: **ROUTE** or **ROU** (with optional **:LEFT** / **:RIGHT** / **:L** / **:R**); **SET_DEST** or **SET_DST** (with **:L** / **:R**). When **SET_DEST** is present, **line 4** is the destination string (e.g. `Snowy2` or `Snowy2:0`). No direction = bidirectional for ROUTE; SET_DEST with direction fires only when the cart goes that way. **SET_DEST rule:** When the cart passes and the rule’s direction matches, the plugin overwrites that cart’s destination with line 4 (station name or Name:TerminalIndex). Then normal ROUTE/TRANSFER logic applies. With direction = only fire when cart goes that way and only update that direction's TRANSFER/NOT_TRANSFER controller rules. Schema supports rule_1 … rule_4.
 means “when a cart passes going left, ”; 
When a cart passes: SET_DEST is applied first if it matches (destination overwritten from line 4); then ROUTE (if present) runs and sets station controllers: TRANSFER **on** for the chosen destination; NOT_TRANSFER **on** for all other branches (for that direction).

**Destination resolution (Name:TerminalIndex):** Destinations can be given as a **station name** (e.g. `Snowy2` → any terminal at that station) or **Name:TerminalIndex** (e.g. `Snowy2:0` → that terminal’s address). This resolution is used for SET_DEST line 4, **/setdestination**, and **/dns** lookup.

### Station controller (destination-keyed)

Keyed by **destination** (which branch this bulb switches toward). **Own roles only: TRANSFER and NOT_TRANSFER** — no DIVERT, READY, CLEAR, RELEASE. NOT_TRANSFER works like NOT_DIVERT: the bulb turns **on** when we are *not* sending the cart to that branch (e.g. keep main line open by default). **Sign format:** Same layout as other controllers — line 2 = target; lines 3–4 = rules, **multiple rules per line allowed, space-separated** (same as other controllers). Up to **4 rules total** stored (first 4 from lines 3 and 4 combined).
```
Line 1: [Controller]
Line 2: Station1:TransA-F
Line 3: TRANSFER:LEFT NOT_TRANSFER:RIGHT
Line 4: TRA
```
- **Line 2** — **target**: must be **Station:Node** or **Station:Terminal** (e.g. `Station1:TransA-F`, `MainHub:WestMine`). Station name only is not allowed; each controller is for one specific node or terminal so the plugin can set TRANSFER for that destination and NOT_TRANSFER for others. **Lines 3–4** — one or more rules per line, space-separated. Each rule: **TRANSFER** or **TRA**, **NOT_TRANSFER** or **NOT_TRA**, with optional **:LEFT** / **:RIGHT** / **:L** / **:R**. Schema supports up to 4 rules (rule_1 … rule_4). Controllers can be **direction-specific** (e.g. TRANSFER:LEFT, NOT_TRANSFER:RIGHT) so they line up with the station detector’s optional direction: when the detector has `:LEFT` or `:RIGHT`, only the controller rules for that direction are updated. When station detector fires and routing says "send to WestMine," plugin turns ON TRANSFER for that target’s controller(s) and ON NOT_TRANSFER for other branches’ controller(s) (for the matching direction if detector is direction-specific). May have **own small set of commands** — to be defined.

### Clearing the transfer signal when the cart arrives

When that **node or terminal** later registers an **ENTRY** detection (cart has arrived on that branch), the plugin **clears** the transfer signal: turn off TRANSFER for that destination's station controller(s). So the switch resets after the cart has committed to the branch.

### No-destination rule (carts with no destination)

When the station detector fires and the cart **has no destination**, the plugin applies a **no-destination rule** instead of leaving the cart without a route:

1. **Set destination to nearest available terminal** — The plugin looks for an available terminal (ready, with capacity) at the **current station** first. If none, it looks at other stations that have an available terminal and picks one (e.g. first by name; “nearest” by routing cost can be refined later). The cart’s destination is set to that terminal’s address, and normal routing runs (TRANSFER for that terminal, NOT_TRANSFER for others).
2. **If no terminals exist** — The cart is **removed**: deleted from the DB (cart_segments, segment_occupancy) and the minecart entity is despawned.

So carts without a destination are either sent to an available terminal or removed. There is no “hold at station with NOT_TRANSFER only” for no-destination carts anymore.

When the station detector fires but the cart **has a destination** and routing returns **no next hop** (no route, or cart not in DB), the plugin still sets **NOT_TRANSFER on all nodes** at that station so the cart stays at the station.

### Summary

| Concept | Where | Meaning |
|--------|--------|--------|
| **Station detector** | Rail **before** junction (decision point) | `[Detector]` / Line 2: station name / Line 3–4: rules (ROUTE, SET_DEST:L/R, etc.). Line 4 = destination when SET_DEST used (e.g. Snowy2 or Snowy2:0). Cart here → SET_DEST overwrites dest if matched; ROUTE → set TRANSFER/NOT_TRANSFER by target. |
| **Station controller** | At each branch switch (before the node) | `[Controller]` / Line 2: Station:Destination (e.g. Station1:TransA-F) / Line 3–4: rules (space-separated, up to 4 total). TRANSFER or TRA, NOT_TRANSFER or NOT_TRA, with :LEFT/:RIGHT/:L/:R. TRANSFER = on when sending to X; NOT_TRANSFER = on when *not* sending to X. Cleared when ENTRY fires at that node/terminal. |
| **Existing detector/controller** | At the transfer node / junction | Unchanged. ENTRY → divert/not divert; READY/CLEAR/RELEASE; DIVERT/NOT_DIVERT. |

### Nodes without detectors or controllers (no built-in junction)

A transfer node **does not require** any detectors or controllers. The system supports “bare” nodes that are just a link in the graph.

- **If you don’t place detectors/controllers at a node:** When a cart passes that node’s rails, the plugin finds no detectors there and does nothing at that block. No ENTRY, READY, or CLEAR fires; no DIVERT/RELEASE or hold logic runs. The **transfer pair** still exists in the DB and **routing** (e.g. `getNextHopNodeAtStation`, pathfinding) still uses it. So you can have a pair that is only a through link — no physical junction at that node.
- **Use cases:** Defer the “junction” job to a **downstream junction** in the middle of the segment (place detectors/controllers there instead), or have no junction at all on that segment (simple A↔B link).
- **Collision prevention:** Segment occupancy and collision checks are driven by detector events (e.g. when ENTRY/READY would update segment state). If a node has **no** detectors, that node never reports carts entering or leaving the segment, so **collision prevention is effectively skipped for that segment** (or relies on a junction elsewhere on the segment if you have one). That is acceptable if you design the layout accordingly (e.g. single-cart segment or junction in the middle handling conflicts).

So: the pair still works; you just don’t get a built-in junction at that node unless you add detectors and controllers there.

### Deletion and re-registration (Absorb)

When a **transfer node or terminal** is deleted (`/transfer delete` or `/terminal delete`), the database removes any detectors and controllers that referenced that node (FK `ON DELETE CASCADE`). When a **station** is deleted (e.g. station sign broken), the database removes that station’s nodes (cascade) and all **station_detectors** and **station_controllers** for that station.

To re-register signs after recreating nodes/stations (or after importing a world without the plugin DB), use **`/absorb`**:

- **Station sign** — Only a **`[Station]`** sign can recreate a station. Look at the station sign (or run `/absorb` and right-click it in wizard mode). If no station exists at that block, the station is recreated (name from line 2, address from line 3 or generated). If a station already exists at that sign, nothing is done. The station’s physical location is the sign block; detectors/controllers do not recreate stations.
- **Detector or controller sign** — Must be on a **copper bulb**. Look at it and run `/absorb`, or run `/absorb` and right-click it in wizard mode. The target (node, junction, or station) must already exist:
  - **Station detector** (line 2 = station name): if that station does not exist, you get *“Station X does not exist. Absorb a station sign for that station first (or create with /station create).”* Absorb the **station sign** first to recreate the station, then absorb the detector.
  - **Station controller** (line 2 = Station:Node): same rule; absorb a station sign first if the station was removed, then recreate the transfer node, then absorb the controller.
  - **Node/junction detector or controller**: if the transfer node or terminal does not exist, you get a message and a **clickable** *[Create transfer node]* / *[Create terminal]* that runs `/transfer create &lt;name&gt;` or `/terminal create &lt;name&gt;`. After creating the node (and clicking the station sign in the wizard), run `/absorb` again on the detector/controller sign.
- If a detector or controller is **already registered** at that bulb and you run `/absorb` again, the record is **refreshed** with the same id (re-read from the sign and re-insert), so existing links are preserved.

**Order when recreating from scratch:** Absorb station signs (or create with `/station create`) → create transfer nodes/terminals (`/transfer create` / `/terminal create`, then click station sign) → absorb detector and controller signs. (Target design: creation only via [Transfer]/[Terminal]/[Junction] signs — see Physical anchors section.)

**ENTRY** is defined as: *“A cart has just arrived at this transfer node or terminal”* — i.e. first detection of the cart **on** that branch, not the moment we decide to send it there.

- **ENTRY does not decide** whether to divert. It only signals “cart is now on this node’s/terminal’s track.”
- **ENTRY does not set** DIVERT/NOT_DIVERT. Those are driven by the **station detector** at the decision point (see below).
- ENTRY can still be used for counting, logging, or resetting switch state after the cart has committed (e.g. turn off DIVERT+ so the switch returns to default until the next decision).

READY, CLEAR, and RELEASE stay as they are: they describe hold/release **at** that node or junction (cart stopped in siding, cart left siding, gate open).

### Simplified model: transfer nodes as segment boundaries only (alternative)

An alternative design simplifies roles by **not** allowing built-in junctions on transfer nodes:

- **Transfer nodes** have detectors with **ENTRY** and **CLEAR only**. No DIVERT, READY, or RELEASE at the node. Semantics match junctions so they are functionally the same:
- **CLEAR** (at a transfer node) = cart is **clearing that node** and **entering the segment** (leaving the node onto the main line) → **register on** the segment.
- **ENTRY** (at a transfer node) = cart is **entering that node** (into the station) and **clearing off the segment** (arrived from the segment) → **take off** the segment.

So at each end of a segment: when the cart leaves the node onto the segment you get **CLEAR** (onto segment); when the cart arrives from the segment at the node you get **ENTRY** (off segment). Same as junction: ENTRY = entering the thing (off segment), CLEAR = clearing the thing (onto segment).

- **Junctions** are the **only** place that have DIVERT, READY, and RELEASE (and hold slots). Junctions sit *on* the segment (between two transfer nodes). Each side of the junction has ENTRY/CLEAR in the junction sense (cart entered holding area / cart left holding area). So "entry" and "clear" at a junction keep their current meaning (at the junction's holding area), while at a **transfer node** they mean "entered/cleared the segment."

**Summary:** Transfer nodes = segment boundaries only; ENTRY/CLEAR mean the same as at junctions (ENTRY = entering the thing → off segment, CLEAR = clearing the thing → onto segment). Junctions = only place with diversion and hold/release. This avoids built-in junctions on transfer nodes and makes segment vs node semantics consistent. Cleaning up transfer nodes this way can simplify the routing and collision logic (one rule everywhere; no special “node with built-in junction” case).

**Junction layout (bidirectional segment):** Picture one bidirectional rail segment with a junction in the middle. There is **segment to the left** of the junction and **segment to the right** of the junction. The junction has detectors on **either side** of the junction. On the **left side** of the junction (the detector facing the left segment): **ENTRY:LEFT** = cart coming from the left → cart is **entering the junction**. When a cart is coming from the right and passes that detector (or the cart leaves the junction toward the left), that’s **CLEAR** = cart is **clearing the junction** (leaving the junction past that detector). So in the context of the junction, **ENTRY** = cart is now **entering the junction** (from that direction); **CLEAR** = cart is now **clearing the junction** (exiting the junction past that detector). The direction (LEFT/RIGHT) labels which side of the junction the detector is on (segment to the left vs segment to the right of the junction).

**Segment registration (on vs off the segment):** Same rule for nodes, junctions, and terminals:

| Where | Event | Meaning for the segment |
|--------|--------|-------------------------|
| **Node** | ENTRY | Cart is **entering the node** (into the station) → **take off** the segment. |
| **Node** | CLEAR | Cart is **clearing the node** (leaving onto the segment) → **register on** that segment. |
| **Terminal** | ENTRY | Cart is **entering the terminal** (into the station) → **take off** the segment. |
| **Terminal** | CLEAR | Cart is **clearing the terminal** (leaving onto the segment) → **register on** that segment. |
| **Junction** | ENTRY | Cart is **entering the junction** (into the holding area) → **take off** the segment. |
| **Junction** | CLEAR | Cart is **clearing the junction** (back onto the main line) → **register on** that segment. |

So everywhere: **ENTRY** = entering the thing (node, terminal, or junction) → cart comes **off** the segment. **CLEAR** = clearing the thing → cart goes **onto** the segment. Terminals work the same way: if a terminal is at the end of a segment, ENTRY = into terminal (off segment), CLEAR = leaving terminal (onto segment).

**Junction decision at ENTRY (order of arrival).** At the moment ENTRY fires for a cart at the junction, we decide: is the way ahead clear (no opposing traffic on the segment we would exit onto)? If not, we **divert this cart** into the junction so it waits; the other can pass, then this one is released when clear. Order of arrival is who triggered ENTRY first (no distance/time guess). Segment occupancy is the only signal: when a cart registers on a segment (CLEAR at node or junction), it is “on segment”; when it leaves (ENTRY at node or junction), it is off. Dispatch is blocked only when there is **opposing** traffic and (no junction or all junctions full). If the junction is full but there is no opposing traffic, dispatch is allowed. When there is opposing traffic, we do not only check “is there a free slot?” — we check **how many** carts are already on the segment in **our** direction. If the junction has only one slot but two carts are already on the segment toward the junction, sending a third would let one divert but the other would have nowhere to go (collision). So we require **junction free slots ≥ (same-direction count + 1)** when there is opposing traffic. No opposing traffic: dispatch allowed (same-direction carts will pass through); junction full is OK. **Dispatch decision variables** (what we consider when deciding to send a cart onto a segment): (1) **Destination node** — full? block. (2) **Opposing traffic?** — carts on segment in the opposite direction; if yes and no junction, block; if yes and junction(s), require junction capacity for same-direction queue. (3) **Same-direction count** — number of carts already on segment in our direction; when opposing, need junction free slots ≥ this count + 1. (4) **Junction free slots** — only checked when there is opposing traffic; must be ≥ same_direction_count + 1. **Directional:** when the junction has directional queues (LEFT/RIGHT), we use free slots **for the approach direction** only (e.g. approaching from nodeA = LEFT side; capacity and held count for that side). So a junction with one free slot on the LEFT and none on the RIGHT allows dispatch from the nodeA side but not from the nodeB side when opposing. (5) **Committed past last junction** — opposing cart already past the last junction; block. Not currently used but could be extended: segment downstream of junction clear, station downstream capacity, etc. **Directionality:** occupancy is per direction; only **opposite** direction counts as opposing. If one cart is on the segment in one direction, another cart from the **same** station going the **same** direction does not collide — same-direction traffic is allowed (no block). **Station routing and terminals.** When the station decides the quickest way is via a given transfer node/segment, it checks whether that segment has opposing traffic (e.g. a cart just left the junction heading this way). If the segment is not clear, the station does not route the cart onto it; it sends the cart to an available terminal at that station if one exists. The cart's destination is not cleared when waiting at that terminal (only cleared when READY at the terminal that matches the cart's destination). When releasing from a terminal, the plugin checks the segment and junction(s) the cart would move onto, so the terminal acts as overflow storage until the way is clear.

### Multiple junctions and per-junction specifications

A segment can have **any number of junctions** (e.g. A — J1 — J2 — B). The logic applies **iteratively** to each junction; no recursion is required.

- **Order along the segment:** Junctions are ordered by geometry (distance from the dispatch node) so that “first” and “last” junction are well-defined. The **last junction** is the one we meet last when traveling from dispatch node to destination (closest to the destination node). This order is used for (1) the “committed past last junction” check and (2) the per-junction capacity check (we evaluate every junction in travel order).
- **Per-junction capacity:** Each junction has its **own** capacity for LEFT and RIGHT (stored per junction; default 2 per side if not set). When checking whether we can dispatch, we require **for each junction** that free slots **for our approach side** at that junction are ≥ (same-direction count on segment + 1) when there is opposing traffic. So one junction may allow 2 on the LEFT and another may allow 1 on the LEFT; each is checked independently. Held counts (how many carts are currently in that junction’s LEFT/RIGHT queue) are also per junction.
- **Committed past last junction:** We block dispatch only when an **opposing** cart has already **passed the last junction** (i.e. has left that junction and is on the segment between that junction and us). That is detected by segment occupancy **zone**: when a cart CLEARs from a junction it is re-registered on the segment with zone `junction:<junctionId>` (or `junction:<junctionId>:LEFT` etc.). So “committed past last junction” = opposing carts on the segment whose zone equals or starts with `junction:<lastJunctionId>`. We do **not** block for opposing carts that are still before the last junction (e.g. between two junctions, or before the first junction). This avoids over-blocking when there are multiple junctions.
- **Summary:** N junctions are handled by iterating the ordered list once; each junction uses its own capacity_left/capacity_right and held counts; “last junction” is geometric (last in travel order); “committed” is defined by zone so only carts that have left the last junction trigger a block.

### Unified flow when a detector sees a cart

The entire unified flow lives in **one class**: `UnifiedCartSeenFlow` (single file). Detectors trigger and update segment occupancy and collision prevention. All of that runs in **one ordered flow** each time a cart is seen on a detector rail:

1. **No-dest / not in DB:** If the cart is not in the DB, add it. If the cart has no destination (or was just added), apply the no-destination rule from the current detector’s station context: set destination to the nearest terminal or remove the cart (and despawn) if no terminals exist.
2. **Reconcile this cart’s segment:** A cart can only be on **one segment at a time** (physically). So we clear this cart from every segment in `segment_occupancy`. ENTRY/CLEAR logic later will remove it from the segment it left and add it to the segment it entered (CLEAR). That keeps the invariant: each cart appears on at most one segment. (Multiple carts can still be on the same segment — e.g. same direction or junction slots — so collision logic is unchanged.)
3. **Trim ghosts:** Other carts that are in `segment_occupancy` and could affect collision checks might no longer exist in the world (destroyed, unloaded). Before running collision prevention, we list all cart UUIDs in `segment_occupancy`, and for any that do not exist as entities in the world we call `onCartRemoved` and `deleteCart` so collision logic does not see ghosts.
4. **Collision prevention and detector logic:** Station loop (ROUTE, SET_DEST, TRANSFER/NOT_TRANSFER) and node/junction loop (ENTRY, READY, CLEAR, DIVERT, RELEASE) run after steps 1–3, so they see a consistent DB and segment occupancy.

When a cart is removed (vehicle destroyed or stale cleanup), `onCartRemoved` clears held state and segment occupancy for that cart. CLEAR only calls `upsertSegmentOccupancy`; the unified flow already cleared this cart at step 2.

### Routing decision log (queryable)

When the plugin decides where to send a cart at a station (next hop = transfer node or terminal), it can **store that decision and the reason** in the database so it can be queried later (e.g. “why did this cart get sent to the terminal instead of the segment?”).

- **What is stored:** For each “next hop” decision: cart, station, preferred node (first hop toward destination), chosen node (same or a terminal if dispatch was blocked), whether dispatch was allowed, block reason (if any), and destination. The table is pruned to the **last 5 entries per cart** on each insert so it stays bounded.
- **Config:** `routing-decision-log` (default true) — when true, each next-hop decision is written to the `routing_decision_log` table. `routing-debug` (default false) — when true, the same information is also printed to the server console (like detector debug).
- **Querying:** API `getLastRoutingDecisions(cartUuid, limit)` returns the last N decisions for a cart. Command `/netro routinglog <cart_uuid>` prints the last 5 to the sender. No file logging; data lives only in the database and is retained until pruned (per cart) or the cart is removed.

### Terminal vs transfer node: READY and RELEASE

**Terminals have queues** for both arrival (destination clear, who to release for departure) and for holding carts when the preferred segment is blocked. **Transfer nodes** (in the simplified model) are segment boundaries only (ENTRY/CLEAR); they do not have DIVERT/READY/RELEASE. **Terminals** are dead ends at a station where carts either arrive at their destination or depart from that terminal (or wait when routed there because the segment was blocked). The plugin treats them as follows:

| Aspect | Transfer node | Terminal |
|--------|----------------|----------|
| **READY** | Cart is held in the siding; destination is unchanged. | If the cart’s **destination is this terminal** (same station + terminal index), the plugin **clears the cart’s destination** (arrived). If the cart still has a different destination or a player set a new one, destination is **not** cleared (wrong terminal or departing). |
| **RELEASE** | Fired when the first cart in line **and** the way is clear (e.g. `canDispatch` on the segment to the paired node). | Fired for the **first cart in line** (FIFO). Same held-count and ordering as junctions: count increments on READY, decrements on CLEAR; the first cart in the held list gets RELEASE when the way is clear. Terminals have no paired segment, so “way clear” is satisfied by FIFO ordering (release next when the previous cart has cleared). |
| **CLEAR** | Decrements held count; RELEASE turns off; next cart in line is re-evaluated for RELEASE (way clear). | Same: decrement, RELEASE off, then RELEASE for the next cart in line if any. |

So at a **terminal**, READY both holds the cart and, when the destination matched this terminal, marks it as “arrived” by removing its destination. RELEASE at a terminal uses the same counter and FIFO logic as junctions (track how many carts came in; assume they still exist unless the cart is removed by a player); the first in line is released when appropriate.

### Release order (FIFO vs FILO)

Held carts are ordered by **slot index** (order they hit READY: first cart = slot 0, next = slot 1, etc.). By default, **FIFO** (first-in first-out): the cart at slot 0 gets RELEASE first. That fits a **queue** layout where the first cart in is at the front (nearest the exit).

If your siding is a **straight line** where the first carts that arrive go to the **back** and the newest carts are at the **front** (blocking the ones in the back), you want the **front** cart released first. Set **release order** to **FILO** (first in, last out): the plugin then treats the *last* cart in the held list (highest slot) as the one to release next. Same counter and CLEAR logic; only which cart gets RELEASE changes.

This option exists for **transfer nodes**, **terminals**, and **junctions**:

- **`/transfer release-order <station:node> fifo|filo`** — transfer node
- **`/terminal release-order <station:node> fifo|filo`** — terminal
- **`/junction release-order <name> fifo|filo`** — junction

**`/transfer info`** and **`/junction info`** show the current release order. Default is **fifo**. **lifo** is accepted as an alias for **filo**.

### Bidirectional junction: two directional queues

Some transfer nodes (and junctions) are built **bidirectionally**: one holding area on the **left** and one on the **right** (e.g. one physical slot per direction). The plugin supports this with **per-direction queues** and **direction on the detector as a queue label** (not cart direction).

**READY / CLEAR: direction is the queue label.** For READY and CLEAR, the **rule’s** direction (e.g. READY:LEFT, CLEAR:RIGHT) does **not** mean “match the cart’s travel direction.” It means “**which queue** this detector feeds.” So you place a READY:LEFT detector in the left holding slot and a READY:RIGHT detector in the right. When a cart stops on the left detector, READY fires with rule direction LEFT → the cart is added to the **left queue**. When a cart stops on the right detector, READY:RIGHT → added to the **right queue**. No need to store or inherit ENTRY direction; the detector’s rule simply labels the queue (left, right, or no direction = single pool). Same for CLEAR: CLEAR:LEFT in the left slot means “when a cart leaves here, remove it from the left queue.”

- **READY** (no direction) = single pool (current behavior): one queue, RELEASE with no direction.
- **READY:LEFT** = this detector adds to the **left** queue. Fires whenever a cart is on this detector (no cart-direction check).
- **READY:RIGHT** = adds to the **right** queue.
- **CLEAR:LEFT** / **CLEAR:RIGHT** = remove from that queue when a cart clears this detector.

**RELEASE with direction.** When the plugin releases a cart from the left queue it fires **RELEASE:LEFT** only (so only controllers with rule RELEASE:LEFT turn on). When it releases from the right queue it fires **RELEASE:RIGHT** only. So the controller’s direction (RELEASE:LEFT, RELEASE:RIGHT) is used the same way as for DIVERT: “which physical side this bulb controls.” After any CLEAR, the plugin re-evaluates left, right, and single pool and turns on RELEASE for each queue that has a cart that can go.

**Summary:** ENTRY still determines cart direction for DIVERT/NOT_DIVERT (detector detects, controller direction matches that). For READY/CLEAR the detector’s direction is only a **queue label** (left, right, or either). RELEASE:LEFT and RELEASE:RIGHT are fired per queue so only the correct gate opens.

### Station detector (decision point)

A **station-level** detector that is **not** tied to a specific transfer node or terminal. It is placed at the **decision point** — the rail **before** the junction where the plugin must choose which branch the cart will take.

- **Sign format (concept):** e.g. `[Detector]` with line 2 = **station name only** (e.g. `MainHub`) to mean “decision point for this station.” No `StationName:NodeName` — the detector does not “belong” to a node.
- **When a cart passes:** Plugin runs routing for that cart at this station: “Where should this cart go?” (transfer node A, terminal T, or “through without holding”). Routing returns the **chosen destination** (node id or terminal id).
- **Plugin then:** Activates the **station controller(s)** whose **target** matches that destination (see below). So the switch that sends carts *to* node A is turned ON; others (for the same junction) can be turned OFF or NOT_DIVERT as needed.

So the “which branch?” decision and the setting of DIVERT/NOT_DIVERT happen at the **station detector**, not at ENTRY.

### Station controller (destination-keyed)

A controller that is tied to the **destination** (which branch it physically controls), not to “the node we’re at.” When the station detector fires and routing says “send to node A,” the plugin turns ON the controller(s) that are labeled “I control the switch to **node A**” (or terminal A).

- **Sign format (concept):** Line 2 = **target** — the transfer node or terminal this bulb’s redstone actually switches carts **toward**. e.g. `MainHub:WestMine` meaning “this bulb drives the switch that sends carts into the WestMine branch.” Same `StationName:NodeName` or terminal name as today, but the **meaning** is “destination” not “we are at this node.”
- **When station detector fires:** Plugin gets “send to MainHub:WestMine” → find all **station controllers** whose target is `MainHub:WestMine` → set those to DIVERT (ON). Optionally set controllers for other branches at the same junction to NOT_DIVERT (OFF).
- **No node_id/junction_id “ownership”** in the old sense; the controller is keyed by **target node/terminal id** so the plugin knows which bulb to activate for which routing decision.

So: **station detector** = “cart is at the decision point; run routing.” **Station controller** = “I am the switch for destination X; turn me on when routing says send to X.”

### Summary

| Concept | Where | Meaning |
|--------|--------|--------|
| **Station detector** | Rail **before** junction (decision point) | “Cart here → run routing → get destination → drive controllers by target.” |
| **Station controller** | At each branch switch | “I control the switch to destination X.” Target = node or terminal name. Activated when routing says “send to X.” |
| **ENTRY** | On the branch (after the switch) | “Cart has just arrived at this node/terminal.” Detection only; no DIVERT/NOT_DIVERT. |
| **READY / CLEAR / RELEASE** | At the node/junction siding | Unchanged: hold slot, left siding, gate open. |

**Implementation note:** The current code still ties ENTRY to the routing decision (ENTRY fires → `shouldDivert` → set DIVERT/NOT_DIVERT for that node). The intended model above requires: (1) ENTRY to stop setting DIVERT/NOT_DIVERT; (2) a new “station” detector type and “station” controller type (or new roles) with target-based activation; (3) station detector handler that runs routing and activates station controllers by destination. Station detector/controller are additive; ENTRY and existing behavior stay unchanged.

---

## Transfer Node Setup (Full Example)

### Physical layout

```
[Main line A] ──[Switch A]──[Siding entrance]──[Siding]──[Gate]──[Siding exit]──[Switch B]──[Main line B]
```

### What the player places

**At siding entrance (Switch A end):**
- Copper bulb adjacent to the approach rail, sign on back face:
  ```
  [Detector]
  MainHub-WestMine
  ENTRY:LEFT
  CLEAR:RIGHT
  ```
- Copper bulb for the divert mechanism, sign on any face:
  ```
  [Controller]
  MainHub-WestMine
  DIVERT:LEFT
  ```
  Wire this bulb to the redstone that throws Switch A.

**In the middle of the siding:**
- Copper bulb adjacent to the "stop" rail where carts come to rest:
  ```
  [Detector]
  MainHub-WestMine
  READY
  ```

**At the gate:**
- Copper bulb adjacent to the gate powered rail:
  ```
  [Controller]
  MainHub-WestMine
  RELEASE:LEFT
  RELEASE:RIGHT
  ```
  Wire this bulb to the powered rail (or whatever mechanism opens the gate).

**At siding exit (Switch B end):**
- Copper bulb adjacent to the exit rail:
  ```
  [Detector]
  MainHub-WestMine
  ENTRY:RIGHT
  CLEAR:LEFT
  ```
- Copper bulb for divert from B side:
  ```
  [Controller]
  MainHub-WestMine
  DIVERT:RIGHT
  ```
  Wire to Switch B mechanism.

### Commands needed

```
/transfer create MainHub-WestMine   → click station sign → done
/transfer pair MainHub-WestMine WestMine-MainHub
```

That's it. All physical setup is sign placement and redstone wiring. No wizard, no invert flags, no block registration commands.

---

## Junction Setup

Junctions use the same detector/controller system. A junction is just a named entity with a hold siding on a segment between two transfer nodes.

```
/junction create TunnelMid
/junction segment TunnelMid MainHub-WestMine WestMine-MainHub
```

Then in-world the player places the same detector/controller signs referencing `TunnelMid` instead of a transfer node name. The plugin checks junctions on a segment before dispatching a cart, same as before. The physical execution is all player redstone.

---

## Multi-Cart Queue (Multiple Gate Slots)

The plugin tracks how many carts are currently held at a node (count of READY fires minus count of CLEAR fires). It fires RELEASE once per cart when conditions allow.

The player builds as many gate slots as they want in their siding. Their redstone handles advancing the queue — e.g. when RELEASE fires, their circuit powers the first gate rail, the cart rolls forward, hits a detector, which triggers the next gate to open, etc. The plugin only fires RELEASE and observes CLEAR. All sequencing within the siding is player redstone.

If the player wants a simple 1-cart siding they wire RELEASE directly to one powered rail. If they want a 4-cart buffer they build whatever redstone circuit sequences those 4 rails on each RELEASE pulse. The plugin contract is the same either way.

---

## Data Model

### Tables

**transfer_nodes**
```
id, name, station_id, paired_node_id, is_terminal, terminal_index, setup_state, created_at
```
Same as before. No transfer_switches, hold_switches, or gate_slots tables needed.

**detectors**
```
id, node_id, world, x, y, z,
rail_x, rail_y, rail_z,          -- the adjacent rail block being watched
sign_facing,                     -- BlockFace the sign faces (for LEFT/RIGHT computation)
rule_1_role, rule_1_direction,  -- first rule (required)
rule_2_role, rule_2_direction,  -- optional
rule_3_role, rule_3_direction,  -- optional
rule_4_role, rule_4_direction,  -- optional (up to 4 rules from sign lines 2–3)
created_at
```

**controllers**
```
id, node_id, world, x, y, z,
sign_facing,
rule_1_role, rule_1_direction,
rule_2_role, rule_2_direction,
rule_3_role, rule_3_direction,
rule_4_role, rule_4_direction,
created_at
```

**station_detectors** (when implemented)
Same pattern as detectors but keyed by station (no node_id). Supports **up to 4 rules** (rule_1 … rule_4) from lines 3–4 combined, space-separated. Example columns: id, station_id, world, x, y, z, rail_x, rail_y, rail_z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, created_at.

**station_controllers** (when implemented)
Same pattern as controllers but keyed by station + target (destination node/terminal). Supports **up to 4 rules** (rule_1 … rule_4) from lines 3–4 combined, space-separated. Example columns: id, station_id, target_node_id (or target identifier), world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, created_at.

**cart_held_counts**
```
node_id, held_count, updated_at
```
Simple counter. Incremented on READY, decremented on CLEAR. Plugin uses this to decide when to fire RELEASE and when to update backpressure.

### Removed tables (legacy block registration)
- `transfer_switches`, `hold_switches`, `gate_slots` — plugin no longer registers or controls these; detector/controller (copper bulb) model only.
- `junction_switches`, `junction_gate_slots` — same for junctions.
- **Lecterns** and **signal_bindings** remain in the schema for the signal/lectern feature.

---

## Sign Auto-Registration

When any sign is placed in the world:

1. Check if line 1 is `[Detector]`, `[Transfer]`, `[Terminal]`, `[Junction]`, or `[Controller]`
2. If yes — check the copper bulb the sign is attached to
3. If the adjacent block is a copper bulb — proceed with registration
4. Resolve target from line 2 (StationName:NodeName or junction name; junction name may include `:LEFT`/`:RIGHT`)
5. If not found: for `[Junction]` create the junction; for `[Transfer]`/`[Terminal]` create the node if the station exists; otherwise send an error message to the player in **chat only** (do not write to the sign) and stop
6. Read sign's `BlockFace` facing, compute LEFT and RIGHT faces
7. Find adjacent rail block (scan the 4 horizontal neighbors of the copper bulb)
8. Parse line 3 and line 4 for ROLE:DIRECTION rules
9. Insert into detectors or controllers table
10. Apply sign styling (line 1 = type bold+color, line 2 = target, lines 3–4 = rules). Do not overwrite with error text.

When a sign is broken, the registration is removed automatically.

---

## Parsing Rules

```java
// Input: "ENTRY:LEFT", "READY", "RELEASE:RIGHT", "CLEAR"
String[] parts = line.trim().toUpperCase().split(":");
String role      = parts[0];                              // ENTRY | READY | CLEAR | DIVERT | RELEASE
String direction = parts.length > 1 ? parts[1] : null;   // LEFT | RIGHT | null

// null direction = fires regardless of cart direction
```

Valid roles: `ENTRY`, `READY`, `CLEAR`, `DIVERT`, `RELEASE`
Valid directions: `LEFT`, `RIGHT`, or omitted (null = any)

Invalid combinations (e.g. `READY:LEFT`) should be silently treated as non-directional since READY is inherently positional not directional.

---

## Physical anchors: Transfer, Terminal, Junction (design)

This section describes a **target design** where transfer nodes, terminals, and junctions are **only created via physical detector blocks** (or absorb). There is no “create in DB only” path: you create a [Transfer], [Terminal], or a **pair** of [Junction] detectors; that is how entities come into existence.

### Creation rule: no DB-only creation

- **Transfer nodes** and **terminals** are created when you place (or absorb) a [Transfer] or [Terminal] sign for a node that doesn’t exist yet. The node is created for the station from line 2 and linked to that detector.
- **Junctions** are created when you have **both** sides of the junction: either you place (or absorb) the second [Junction] detector after the first, or you absorb the first and the logic sees the other side already queued. So creation always requires the physical detector(s).

Commands like `/transfer create`, `/terminal create`, `/junction create` (that create an entity without a detector) are **removed or deprecated**. Creation is **only** by placing or absorbing the appropriate sign(s).

### Sign line 1: what this detector is

| Line 1   | Meaning |
|----------|--------|
| `[Transfer]` | Boundary detector for a **transfer node**. Line 2 = `StationName:NodeName`. Exactly one such detector per transfer node (the one that does ENTRY/CLEAR for the segment). |
| `[Terminal]` | Boundary detector for a **terminal**. Line 2 = `StationName:NodeName` (terminal node). Exactly one per terminal. |
| `[Detector]` | Generic detector (current behaviour). Can be used for extra detectors at a node (e.g. READY in the middle of a siding) or for backward compatibility. |
| `[Junction]` | Boundary detector for a **junction**, one **side** of the junction. Line 2 = `JunctionName:LEFT` or `JunctionName:RIGHT` to identify which side. Exactly two per junction (LEFT and RIGHT). |

So we reference the block by what it actually is: Transfer, Terminal, or Junction (side), not just “detector”.

- **Transfer** and **Terminal** signs still trigger the same ENTRY/CLEAR (and optional READY) rules and still drive the same downstream controllers. The only change is identity (line 1) and the fact that this detector is the **canonical** boundary for that node.
- **Junction** signs are the two boundary detectors; they still fire ENTRY/CLEAR (and READY/RELEASE on the junction side) and drive junction controllers. Each junction has exactly two such detectors (LEFT and RIGHT).

### Linking in the database

- **Transfer node:** Optional `boundary_detector_id` (or equivalent) on `transfer_nodes` pointing to the detector row that is this node’s boundary. At most one detector is the “transfer boundary” per node.
- **Terminal:** Same idea: terminal nodes (transfer_nodes with is_terminal) have an optional `boundary_detector_id` for the one terminal boundary detector.
- **Junction:** Two detectors per junction. Options: (a) `junction_boundary_detectors (junction_id, detector_id, side)` with side = LEFT or RIGHT, or (b) `junction_left_detector_id` and `junction_right_detector_id` on `junctions`. Either way, “junction detectors” in info = these two.

Detector table may get an optional `detector_kind` or we infer from line 1 at registration and store it (e.g. `TRANSFER_BOUNDARY`, `TERMINAL_BOUNDARY`, `JUNCTION_LEFT`, `JUNCTION_RIGHT`) so we can query “how many transfer detectors?” vs “how many generic detectors?”.

### Info output

For **transfer info**, **terminal info**, and **junction info**, in addition to detector count and controller count we show the **entity-specific** detectors:

- **Transfer info:** Detector count, controller count, **Transfer detector:** &lt;id or “not set”&gt;.
- **Terminal info:** Same, **Terminal detector:** &lt;id or “not set”&gt;.
- **Junction info:** Detector count, controller count, **Junction detectors:** LEFT = &lt;id&gt;, RIGHT = &lt;id&gt; (or “not set” for a side).

The “detector count” is total detectors tied to that node/junction; the “Transfer/Terminal/Junction detector(s)” are the canonical boundary one(s).

### Controllers: ENTRY/CLEAR as signals

Transfer nodes and terminals no longer have DIVERT/READY/RELEASE in the routing sense, but we still want a **signal** from ENTRY and CLEAR for other uses (e.g. lighting, other redstone). So:

- A **controller** tied to a transfer node or terminal with rule **ENTRY** or **CLEAR** (optionally with :LEFT/:RIGHT) should fire when the boundary detector fires that role. That gives the player a copper bulb output “cart entered” / “cart cleared” to wire to lights or other logic.
- Same for junctions: controllers with ENTRY/CLEAR at the junction still fire when the junction boundary detectors fire. The boundary detectors are still the ones that trigger those controllers.

So: the boundary detectors (Transfer, Terminal, Junction LEFT/RIGHT) are the physical anchors and the source of ENTRY/CLEAR events that drive both plugin logic (segment occupancy, etc.) and optional controller outputs for signaling.

### Creation flows

**Transfer and Terminal (one detector each)**

- **Place** a [Transfer] or [Terminal] sign with Line 2 = `StationName:NodeName`.  
  - If that node **does not exist:** create the transfer node (or terminal) for that station and register this detector as its boundary.  
  - If it **exists:** register this detector and link it as the boundary (or no-op if already linked).
- **Absorb** (slash `/absorb` or **click with the absorb wand** on the sign): same logic. If the node doesn’t exist, create it for the station and link this detector; if it exists, do nothing (or link). So an existing [Transfer] or [Terminal] sign can be absorbed to create the entity if it’s not there yet.

**Junction (pair of detectors)**

A junction is only created when **both** sides (LEFT and RIGHT) are present. The flow is:

1. **Place the first** [Junction] sign (e.g. Line 2 = `JunctionName:LEFT`).  
   - The junction doesn’t exist yet → **queue** this detector (pending junction; “waiting for the other side”). Do not create the junction in the DB yet. Optionally show feedback: “Junction ‘JunctionName’ waiting for RIGHT side.”
2. **Complete the pair** in one of two ways:
   - **Place the second** [Junction] sign (e.g. `JunctionName:RIGHT`). The system sees the other side already queued → create the junction, link both detectors, clear the queue.
   - **Absorb** the first sign (with `/absorb` or **wand click**). Absorb logic must **understand when the other side has been queued or is being absorbed**: if there is a queued detector for the other side of the same junction, create the junction and link both detectors. If you absorb the second sign instead, same check: other side queued → create junction and link both.

So: creation requires either **two placed signs** (LEFT then RIGHT, or vice versa) or **one placed + one absorbed**, with absorb aware of the “other side queued” state so the pair is completed.

**Storing the queued side:** The first junction detector (e.g. LEFT) must be stored somewhere so that placing or absorbing the second (RIGHT) can complete the pair. Options: (a) a **pending junction** table (e.g. `junction_pending`: junction name, side LEFT/RIGHT, detector id or block location, station/world) so the other side can be looked up by junction name; (b) create a junction row with only one side linked and the other “pending”. Absorb (and second-sign place) then looks up by junction name and either finds the pending record or the half-created junction and completes it.

### Absorb triggers: command and wand

Absorb runs the same create-or-link logic whether it is triggered by:

- The **slash command** (e.g. `/absorb` while targeting the sign or block), or  
- **Clicking the sign (or copper bulb) with the absorb wand**.

In both cases, absorb must:

- For [Transfer] / [Terminal]: create node if missing (for the station on line 2), else do nothing / link.
- For [Junction]: if the junction already exists, link this detector as LEFT or RIGHT. If the junction does not exist, check for a **queued** detector for the other side of the same junction name; if found, create the junction and link both detectors; if not found, **queue** this detector (waiting for the other side).

So absorb is the way to “create if it doesn’t exist” for transfer/terminal, and to **complete** a junction when the other side was already queued (or to queue this side until the other is placed or absorbed).

### Summary (creation only via detectors)

| Entity    | Sign line 1   | Line 2              | Creation requirement | Absorb / place effect |
|-----------|---------------|----------------------|----------------------|------------------------|
| Transfer  | `[Transfer]`   | `StationName:NodeName` | One detector         | Create node if missing, link detector; else no-op / link. |
| Terminal  | `[Terminal]`  | `StationName:NodeName` | One detector         | Create terminal if missing, link detector; else no-op / link. |
| Junction  | `[Junction]`  | `JunctionName:LEFT` or `:RIGHT` | **Pair** of detectors | First: queue. Second (place or absorb): create junction and link both. Absorb must understand “other side queued”. |

No DB-only creation. Detectors still trigger the same downstream controllers; info shows detector count, controller count, and the transfer/terminal/junction-specific detector(s). ENTRY/CLEAR controllers continue to provide a signal for lighting or other uses.

---

## Wand-based linking and junction segment registration (design)

Today, **pairing** (link transfer node A with transfer node B) and **junction segment** (tell the plugin “this junction sits on the segment between node A and node B”) are separate steps and often done via commands. This section describes a **wand-based flow** so you can do both by clicking on the physical detectors in order. Two wands are enough: one for **pairing**, one for **segment/junction** (or one wand with two modes).

### Current process (for context)

- **Pairing:** Two transfer nodes (usually at different stations) are linked so that carts can route from one to the other. Today: `/transfer pair <nodeA> <nodeB>` (by name or id). That creates the “segment” between those two nodes for routing; the segment has no junctions until you register them.
- **Junction segment:** A junction is placed physically between two nodes. The plugin needs to know “this junction lies on the segment between node A and node B” so it can order junctions (A — J — B) and run dispatch/divert logic. Today: `/junction segment <junctionName> <nodeA> <nodeB>`.

So: pair first (A–B), then register junction(s) on that segment (A–J–B). The wand flows below mirror that without needing to type node/junction names.

### Pairing wand

- **Tool:** A dedicated “pairing” wand (or a wand in “pair” mode).
- **Flow:**  
  1. Click on **transfer detector A** (the copper bulb/sign for the first node).  
  2. Click on **transfer detector B** (the other station’s transfer detector that should be paired with A).  
- **Effect:** Create or update the pair A–B (same as `/transfer pair`). Routing table rebuild. Feedback: “Paired &lt;A&gt; with &lt;B&gt;.”
- **Rules:** A and B must be transfer nodes (not terminals), typically at different stations. If either node is already paired, this can replace the pair (or warn and confirm).

So: no typing; you physically click “this node” then “that node” to pair them.

### Segment / junction wand (register junction on segment)

- **Tool:** A “segment” or “junction segment” wand (or same wand in “segment” mode).
- **Purpose:** Define “segment from node A to node B” and which junction(s) lie on it, in order (A — J1 — J2 — … — B), by clicking on the detectors in travel order.

**Basic flow (one junction, A — J — B):**

1. Click **transfer detector A** (node at station A).  
2. Click **one side** of a junction detector (e.g. JunctionAlpha:LEFT).  
3. Click the **other side** of the **same** junction (JunctionAlpha:RIGHT).  
4. Click **transfer detector B** (node at station B).

**Effect:** The plugin infers segment A–B and that JunctionAlpha is on it, in order: A → J(LEFT) → J(RIGHT) → B. It creates or updates the junction’s segment (node_a_id, node_b_id) and junction order so that routing and “committed past last junction” use the correct geometry. So one pass of the wand replaces “pair A–B then junction segment JunctionAlpha A B”.

**Multiple junctions (A — J1 — J2 — B):**

- Same idea, but after step 2 you click a **different** junction’s side (e.g. JunctionBeta:LEFT) instead of the other side of the first junction.  
- Flow example: Click A → Click J1 side → Click **J2** side (other junction) → Click B. The plugin infers two junctions, J1 then J2, and creates/registers both on segment A–B.  
- So: if the second click after a junction side is **another junction’s side** (not the other side of the same junction), we add another junction to the segment and continue. We then need one more “node” click to close the segment (B). So the sequence can be: A → J1-left → J1-right → J2-left → J2-right → B, or A → J1 side → J2 side → B (if we infer the “through” side). Exact click sequence can be defined so that “click junction side, then click other junction side” means “those two junctions are adjacent on the segment.”

**Left/Right rule along the segment:**

- Along the segment we want a consistent direction: e.g. one end is “A”, the other “B”, and each junction has a LEFT (toward A) and RIGHT (toward B). So we only connect the **right** side of one junction to the **left** side of the next (and vice versa): J1’s RIGHT is adjacent to J2’s LEFT in the middle.  
- The wand should enforce or infer this: e.g. when you click in order A → … → B, the first junction side you click is the “toward A” side (LEFT of that junction), the other side of that junction is “toward B” (RIGHT). For the next junction, its “toward A” side (LEFT) is the one you click next. So the allowed chain is: node A, then junction sides in order (left then right of J1, then left then right of J2, …), then node B. Alternatively we allow “right of J1 then left of J2” as the only valid way to connect two junctions (so you can’t connect LEFT–LEFT or RIGHT–RIGHT). Document: **only the right side of one junction connects to the left side of another** (and vice versa) along the segment so the path is linear and ordered.

### Re-registering a segment (insert junction in the middle)

- If a segment A–B **already exists** (with or without junctions), you can **re-run the segment wand** on that segment to **redo** that stretch and insert a new junction (or change junction order).  
- Flow: Click transfer detector A → click (existing or new) junction side(s) in order → click transfer detector B.  
- **Effect:** The plugin treats this as “re-register segment A–B with these junctions in this order.” If a new junction was added in the middle (e.g. A — J1 — **Jnew** — J2 — B), the new segment definition replaces or updates the previous one so Jnew is now part of the segment. So you don’t need a separate “insert junction” command; you just redo the segment with the wand and click the new junction’s sides in the right place in the sequence.

### Summary (wands)

| Wand / mode   | Clicks | Effect |
|---------------|--------|--------|
| **Pairing**   | Transfer detector A → Transfer detector B | Pair node A with node B (like `/transfer pair`). |
| **Segment**  | Transfer A → Junction side(s) in order → Transfer B | Define segment A–B and which junction(s) are on it and in what order. Re-clicking re-registers and can insert new junctions. |
| **Rule**     | Along segment, only connect RIGHT of one junction to LEFT of the next (and vice versa). | Keeps segment order unambiguous (A — J1 — J2 — … — B). |

If the first click is on a **transfer detector** that is **already paired**, the plugin can assume you’re in “segment” mode (you’re defining or updating the segment and its junctions) rather than “pair” mode, so one wand could support both: first click on unpaired transfer → second click on transfer = pair; first click on paired transfer (or first click transfer, second click junction side) = segment/junction registration.

| Command | What it does |
|---|---|
| **Creation (only via detectors)** | Transfer/terminal: place [Transfer]/[Terminal] sign (or absorb existing sign) → creates node if missing. Junction: place or absorb **pair** of [Junction] signs (LEFT + RIGHT) → creates junction when both sides present. No DB-only create. |
| `/transfer create` | **Deprecated/removed.** Use [Transfer] sign + place or absorb. |
| `/transfer pair <A> <B>` | Links two nodes, triggers routing table rebuild |
| `/transfer delete <n>` | Removes node and all associated detectors/controllers |
| `/transfer status` | Overview of all nodes and their state |
| `/transfer info <n>` | Detail for one node including transfer detector, controller count |
| `/terminal create` | **Deprecated/removed.** Use [Terminal] sign + place or absorb. |
| `/terminal delete <n>` | Removes terminal |
| `/junction create` | **Deprecated/removed.** Use pair of [Junction] signs (place both sides or absorb when other side queued). |
| `/junction segment <j> <A> <B>` | Assigns junction to segment between two transfer nodes (can be replaced by **segment wand**: click A → junction side(s) → B). |
| **Pairing wand** | Click transfer detector A, then transfer detector B → pair A–B (replaces typing `/transfer pair`). |
| **Segment wand** | Click transfer A → junction side(s) in order → transfer B → register segment A–B with junction(s). Re-clicking re-registers and can insert new junctions. Only connect RIGHT of one junction to LEFT of next. |
| `/absorb` (or wand click) | Create-or-link from sign: transfer/terminal → create if missing; junction → queue or complete pair if other side queued. |
| `/station info` | Nearest station details |
| `/station setaddress <a>` | Override address |
| `/setdestination <address\|name>` | Set cart destination (must be riding) |
| `/dns list [cluster\|main <n>]` | Browse stations |
| `/dns lookup <n>` | Find station by name |
| `/route create / list` | Named display routes for map tools |

No `/signal`, `/setgateway`, `/setroute` commands. Creation is only by placing or absorbing the physical detector(s); wand click on sign runs same logic as `/absorb`.

---

## Cart controller GUI: Stop vs Cruise and detector priority

The **Cart Controller** item opens a chest GUI (main menu: speed +/-, Stop, Start, direction, destination). Velocity behavior depends on **mode** and **who controls** the cart.

### Modes

- **Stopped** — Stop was pressed: velocity was set to zero once and cached; cruise is off. Speed +/- only updates the stored speed level and does **not** change the cart’s velocity. Rails and manual control (e.g. pushing, powered rails) still affect the cart. Velocity is applied again only when the player presses **Start**.
- **Cruise** — Start was pressed: the controller may re-apply the set speed periodically (cruise control) so the cart maintains speed. Speed +/- updates the level and immediately applies the new speed.

### Detector / READY priority

When a **detector** or **READY** hold applies velocity to the cart, that has **priority** over the controller:

- **MINV_ / MAXV_** — When station or node/junction detectors apply a velocity clamp (min/max speed), the plugin notifies with the **applied speed magnitude**. The cart controller (1) **yields**: cruise is turned off without setting velocity to zero, so detectors/rails keep control until the player presses Start; (2) **updates the stored cruise speed** to match that magnitude (speed level 1–10 is set so that the controller’s effective speed equals the MINV/MAXV value). If the user has the GUI open, they see the updated speed; when they press Start later, that stored speed is used.
- **READY** — When a terminal READY hold is registered (cart held at center), the same yield is used (no magnitude to sync). The cruise re-apply task skips any cart that is in READY hold. There is **no** short time-window skip for “recently controlled by detector”—only READY hold causes the task to skip.

### Cruise re-apply task

A periodic task (same 20-tick loop as the GUI lore refresh) re-applies the set speed for carts in **cruise** mode only when:

- The cart is **not** in READY hold.
- The cart entity exists and is valid.

If the cart is in READY hold, the controller does not set velocity. There is no time-based “recently controlled” skip: MINV/MAXV update the stored speed and yield cruise; when the player presses Start again, the (possibly detector-updated) stored speed is used.

### Summary

| Situation | Controller applies velocity? |
|-----------|------------------------------|
| Stopped mode | No (only Start applies cached × level) |
| Cruise mode, no READY hold | Yes (re-apply set speed) |
| READY hold active | No (yield; READY has priority) |
| MINV/MAXV applied | No (yield; stored cruise speed is updated to match detector speed; GUI shows it; Start resumes with that speed) |

---

## To-do (implementation)

- [x] **Migrations removed** — schema.sql is the single source of truth; recreate DB in test env if needed.
- [x] **Backwards-compat bloat removed** — legacy no-op methods (e.g. JunctionRepository insertJunctionSwitch/insertJunctionGateSlot/isBlockUsedByAnyJunction) and legacy comments stripped.
- [x] **Pairing wand** — `/netro pairingwand` gives the wand; click transfer detector A then transfer detector B to pair (replaces typing `/transfer pair`).
- [x] **Segment wand** — `/netro segmentwand` gives the wand; click transfer A → junction detector(s) in order → transfer B to register segment with junction(s).
- [ ] **Creation only via detectors** — [Transfer]/[Terminal]/[Junction] sign types, absorb create-or-link, junction queue (two sides); deprecate `/transfer create`, `/terminal create`, `/junction create` (design done, implementation pending).
- [ ] **Absorb wand click** — ensure absorb (create-or-link) runs on wand click on sign (already in place for absorb wand; extend for [Transfer]/[Terminal]/[Junction] when implemented).

---

## Summary

The system is divided cleanly into two layers:

**Plugin layer** — routing decisions, timing, traffic management, backpressure. Expressed through copper bulb state changes (on/off). Never touches rail blocks directly.

**Player layer** — physical execution, redstone circuit design, gate sequencing, switch mechanisms. Reads copper bulb state via comparators or direct wiring. Complete freedom in implementation.

The interface between the two layers is the copper bulb. The sign on the bulb is the configuration. Direction is derived from the sign's world-facing, never from commands or wizard clicks. One sign per copper bulb. Two rules per sign maximum.
