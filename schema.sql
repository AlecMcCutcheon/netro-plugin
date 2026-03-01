-- Netro Schema v2

-- ── Stations ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stations (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    address     TEXT NOT NULL UNIQUE,
    world       TEXT NOT NULL,
    sign_x      INTEGER NOT NULL,
    sign_y      INTEGER NOT NULL,
    sign_z      INTEGER NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stations_address ON stations(address);
CREATE INDEX IF NOT EXISTS idx_stations_name    ON stations(name);
CREATE INDEX IF NOT EXISTS idx_stations_world   ON stations(world, sign_x, sign_z);

-- ── Transfer Nodes ────────────────────────────────────────────────────────────
-- A transfer node lives at one station and connects to one other station.
-- Two paired transfer nodes form a complete bidirectional connection.
CREATE TABLE IF NOT EXISTS transfer_nodes (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    station_id      TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    paired_node_id  TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    -- setup state: pending_station | pending_remote | pending_switches | pending_hold | ready
    setup_state     TEXT NOT NULL DEFAULT 'pending_station',
    -- terminal: node where origin and destination are the same station (platform spur)
    is_terminal     INTEGER NOT NULL DEFAULT 0,
    terminal_index  INTEGER,   -- 5th address tier, e.g. 2.4.7.3.0
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tn_station ON transfer_nodes(station_id);

-- Transfer switches: one or more rails on the main line/loop that divert carts
-- into this node's hold siding. Multiple allowed per node.
CREATE TABLE IF NOT EXISTS transfer_switches (
    id              TEXT PRIMARY KEY,
    node_id         TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    x               INTEGER NOT NULL,
    y               INTEGER NOT NULL,
    z               INTEGER NOT NULL,
    invert          INTEGER NOT NULL DEFAULT 0,
    idx             INTEGER NOT NULL  -- order registered, used for display
);

-- Hold siding switches: the two switches at each end of the hold siding.
-- near = closest to station sign, far = closest to main line.
-- Plugin determines near/far automatically from distance to station sign.
CREATE TABLE IF NOT EXISTS hold_switches (
    id              TEXT PRIMARY KEY,
    node_id         TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    x               INTEGER NOT NULL,
    y               INTEGER NOT NULL,
    z               INTEGER NOT NULL,
    invert          INTEGER NOT NULL DEFAULT 0,
    dist_to_station INTEGER NOT NULL  -- computed at registration, used to sort near/far
);

-- Gate slots: powered rails in the hold siding, ordered by distance to station sign.
-- slot_index 0 = closest to station, ascending toward main line.
-- Direction-aware: outbound fills from far end, inbound fills from near end.
CREATE TABLE IF NOT EXISTS gate_slots (
    id              TEXT PRIMARY KEY,
    node_id         TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    x               INTEGER NOT NULL,
    y               INTEGER NOT NULL,
    z               INTEGER NOT NULL,
    slot_index      INTEGER NOT NULL,  -- 0 = nearest station sign
    powered         INTEGER NOT NULL DEFAULT 1,
    UNIQUE(node_id, slot_index)
);

-- ── Junctions ─────────────────────────────────────────────────────────────────
-- A junction sits on a segment between two paired transfer nodes.
-- Same physical geometry as a transfer node hold siding but no station association.
CREATE TABLE IF NOT EXISTS junctions (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    world           TEXT NOT NULL,
    node_a_id       TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    node_b_id       TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    -- reference point for near/far calculation (midpoint of the two entry switches)
    ref_x           INTEGER,
    ref_y           INTEGER,
    ref_z           INTEGER,
    setup_state     TEXT NOT NULL DEFAULT 'pending_switches'
);

CREATE TABLE IF NOT EXISTS junction_switches (
    id              TEXT PRIMARY KEY,
    junction_id     TEXT NOT NULL REFERENCES junctions(id) ON DELETE CASCADE,
    x               INTEGER NOT NULL,
    y               INTEGER NOT NULL,
    z               INTEGER NOT NULL,
    invert          INTEGER NOT NULL DEFAULT 0,
    side            TEXT NOT NULL,  -- 'A' or 'B' (which end of the junction)
    dist_to_ref     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS junction_gate_slots (
    id              TEXT PRIMARY KEY,
    junction_id     TEXT NOT NULL REFERENCES junctions(id) ON DELETE CASCADE,
    x               INTEGER NOT NULL,
    y               INTEGER NOT NULL,
    z               INTEGER NOT NULL,
    slot_index      INTEGER NOT NULL,
    powered         INTEGER NOT NULL DEFAULT 1,
    UNIQUE(junction_id, slot_index)
);

-- ── Routing ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS routing_entries (
    id              TEXT PRIMARY KEY,
    station_id      TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    dest_prefix     TEXT NOT NULL,
    next_hop_node_id TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    cost            INTEGER NOT NULL DEFAULT 0,
    UNIQUE(station_id, dest_prefix)
);

-- ── Cart tracking ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cart_segments (
    cart_uuid           TEXT PRIMARY KEY,
    destination_address TEXT,
    origin_station_id   TEXT REFERENCES stations(id) ON DELETE SET NULL,
    current_node_id     TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    next_node_id        TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    -- segment occupancy: which zone the cart is currently in
    -- format: "node:<id>" or "junction:<id>" or "segment:<nodeA_id>:<nodeB_id>"
    zone                TEXT NOT NULL DEFAULT '',
    state               TEXT NOT NULL DEFAULT 'in_transit',  -- in_transit | held | arrived
    held_at_slot        INTEGER,  -- gate slot index if held
    entered_zone_at     INTEGER NOT NULL
);

-- ── Segment occupancy ─────────────────────────────────────────────────────────
-- Tracks which carts are in which zone of which segment for backpressure checks.
CREATE TABLE IF NOT EXISTS segment_occupancy (
    id              TEXT PRIMARY KEY,
    node_a_id       TEXT NOT NULL,  -- the two transfer nodes defining this segment
    node_b_id       TEXT NOT NULL,
    cart_uuid       TEXT NOT NULL,
    direction       TEXT NOT NULL,  -- 'A_TO_B' or 'B_TO_A'
    zone            TEXT NOT NULL,  -- 'pre_junction_<n>' | 'post_junction_<n>' | 'clear'
    entered_at      INTEGER NOT NULL,
    UNIQUE(node_a_id, node_b_id, cart_uuid)
);

-- ── Station pressure ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS station_pressure (
    station_id          TEXT PRIMARY KEY REFERENCES stations(id) ON DELETE CASCADE,
    inbound_slots_free  INTEGER NOT NULL DEFAULT 0,
    outbound_slots_free INTEGER NOT NULL DEFAULT 0,
    accepting_inbound   INTEGER NOT NULL DEFAULT 1,  -- 0 = backpressure active
    updated_at          INTEGER NOT NULL
);

-- ── Signals / Lecterns ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lecterns (
    id          TEXT PRIMARY KEY,
    station_id  TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    label       TEXT NOT NULL,
    x           INTEGER NOT NULL,
    y           INTEGER NOT NULL,
    z           INTEGER NOT NULL,
    current_level INTEGER NOT NULL DEFAULT 0,
    UNIQUE(station_id, label)
);

CREATE TABLE IF NOT EXISTS signal_bindings (
    id              TEXT PRIMARY KEY,
    station_id      TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    lectern_label   TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    target_level    INTEGER NOT NULL
);

-- ── Named display routes ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS routes (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    color       TEXT NOT NULL DEFAULT 'blue',
    station_ids TEXT NOT NULL  -- JSON ordered array
);

-- ── Compressed segment geometry (for API consumers e.g. Dynmap) ───────────────
CREATE TABLE IF NOT EXISTS segment_polylines (
    id          TEXT PRIMARY KEY,
    node_a_id   TEXT NOT NULL,
    node_b_id   TEXT NOT NULL,
    polyline    TEXT NOT NULL,  -- JSON array of {x,y,z} compressed line segments
    UNIQUE(node_a_id, node_b_id)
);
