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
CREATE TABLE IF NOT EXISTS transfer_nodes (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    station_id      TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    paired_node_id  TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    setup_state     TEXT NOT NULL DEFAULT 'pending_station',
    is_terminal     INTEGER NOT NULL DEFAULT 0,
    terminal_index  INTEGER,
    release_reversed INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    UNIQUE(station_id, name)
);
CREATE INDEX IF NOT EXISTS idx_tn_station ON transfer_nodes(station_id);

-- ── Junctions ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS junctions (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    world           TEXT NOT NULL,
    node_a_id       TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    node_b_id       TEXT REFERENCES transfer_nodes(id) ON DELETE SET NULL,
    ref_x           INTEGER,
    ref_y           INTEGER,
    ref_z           INTEGER,
    setup_state     TEXT NOT NULL DEFAULT 'pending_switches',
    release_reversed INTEGER NOT NULL DEFAULT 0
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
    zone                TEXT NOT NULL DEFAULT '',
    state               TEXT NOT NULL DEFAULT 'in_transit',
    held_at_slot        INTEGER,
    entered_zone_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS segment_occupancy (
    id              TEXT PRIMARY KEY,
    node_a_id       TEXT NOT NULL,
    node_b_id       TEXT NOT NULL,
    cart_uuid       TEXT NOT NULL,
    direction       TEXT NOT NULL,
    zone            TEXT NOT NULL,
    entered_at      INTEGER NOT NULL,
    UNIQUE(node_a_id, node_b_id, cart_uuid)
);

CREATE TABLE IF NOT EXISTS station_pressure (
    station_id          TEXT PRIMARY KEY REFERENCES stations(id) ON DELETE CASCADE,
    inbound_slots_free  INTEGER NOT NULL DEFAULT 0,
    outbound_slots_free INTEGER NOT NULL DEFAULT 0,
    accepting_inbound   INTEGER NOT NULL DEFAULT 1,
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

CREATE TABLE IF NOT EXISTS routes (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    color       TEXT NOT NULL DEFAULT 'blue',
    station_ids TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS segment_polylines (
    id          TEXT PRIMARY KEY,
    node_a_id   TEXT NOT NULL,
    node_b_id   TEXT NOT NULL,
    polyline    TEXT NOT NULL,
    UNIQUE(node_a_id, node_b_id)
);

-- ── Detectors & Controllers (v3: plugin decides, player executes via copper bulbs) ──
CREATE TABLE IF NOT EXISTS detectors (
    id                  TEXT PRIMARY KEY,
    node_id             TEXT REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    junction_id         TEXT REFERENCES junctions(id) ON DELETE CASCADE,
    world               TEXT NOT NULL,
    x                   INTEGER NOT NULL,
    y                   INTEGER NOT NULL,
    z                   INTEGER NOT NULL,
    rail_x              INTEGER NOT NULL,
    rail_y              INTEGER NOT NULL,
    rail_z              INTEGER NOT NULL,
    sign_facing         TEXT NOT NULL,
    rule_1_role         TEXT NOT NULL,
    rule_1_direction    TEXT,
    rule_2_role         TEXT,
    rule_2_direction    TEXT,
    rule_3_role         TEXT,
    rule_3_direction    TEXT,
    rule_4_role         TEXT,
    rule_4_direction    TEXT,
    created_at          INTEGER NOT NULL,
    CHECK ((node_id IS NOT NULL AND junction_id IS NULL) OR (node_id IS NULL AND junction_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_detectors_node ON detectors(node_id);
CREATE INDEX IF NOT EXISTS idx_detectors_junction ON detectors(junction_id);
CREATE INDEX IF NOT EXISTS idx_detectors_rail ON detectors(world, rail_x, rail_y, rail_z);

CREATE TABLE IF NOT EXISTS controllers (
    id                  TEXT PRIMARY KEY,
    node_id             TEXT REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    junction_id         TEXT REFERENCES junctions(id) ON DELETE CASCADE,
    world               TEXT NOT NULL,
    x                   INTEGER NOT NULL,
    y                   INTEGER NOT NULL,
    z                   INTEGER NOT NULL,
    sign_facing         TEXT NOT NULL,
    rule_1_role         TEXT NOT NULL,
    rule_1_direction    TEXT,
    rule_2_role         TEXT,
    rule_2_direction    TEXT,
    rule_3_role         TEXT,
    rule_3_direction    TEXT,
    rule_4_role         TEXT,
    rule_4_direction    TEXT,
    created_at          INTEGER NOT NULL,
    CHECK ((node_id IS NOT NULL AND junction_id IS NULL) OR (node_id IS NULL AND junction_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_controllers_node ON controllers(node_id);
CREATE INDEX IF NOT EXISTS idx_controllers_junction ON controllers(junction_id);

-- Station-level detector (ROUTE): decision point before junction. Line 2 = station name; rules ROUTE/ROU.
CREATE TABLE IF NOT EXISTS station_detectors (
    id                  TEXT PRIMARY KEY,
    station_id          TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    world               TEXT NOT NULL,
    x                   INTEGER NOT NULL,
    y                   INTEGER NOT NULL,
    z                   INTEGER NOT NULL,
    rail_x              INTEGER NOT NULL,
    rail_y              INTEGER NOT NULL,
    rail_z              INTEGER NOT NULL,
    sign_facing         TEXT NOT NULL,
    rule_1_role         TEXT NOT NULL,
    rule_1_direction    TEXT,
    rule_2_role         TEXT,
    rule_2_direction    TEXT,
    rule_3_role         TEXT,
    rule_3_direction    TEXT,
    rule_4_role         TEXT,
    rule_4_direction    TEXT,
    set_dest_value      TEXT,
    created_at          INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_station_detectors_station ON station_detectors(station_id);
CREATE INDEX IF NOT EXISTS idx_station_detectors_rail ON station_detectors(world, rail_x, rail_y, rail_z);

-- Station-level controller (TRANSFER/NOT_TRANSFER): target = which node/terminal this switch sends toward.
CREATE TABLE IF NOT EXISTS station_controllers (
    id                  TEXT PRIMARY KEY,
    station_id          TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    target_node_id      TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    world               TEXT NOT NULL,
    x                   INTEGER NOT NULL,
    y                   INTEGER NOT NULL,
    z                   INTEGER NOT NULL,
    sign_facing         TEXT NOT NULL,
    rule_1_role         TEXT NOT NULL,
    rule_1_direction    TEXT,
    rule_2_role         TEXT,
    rule_2_direction    TEXT,
    rule_3_role         TEXT,
    rule_3_direction    TEXT,
    rule_4_role         TEXT,
    rule_4_direction    TEXT,
    created_at          INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_station_controllers_station ON station_controllers(station_id);
CREATE INDEX IF NOT EXISTS idx_station_controllers_target ON station_controllers(station_id, target_node_id);

CREATE TABLE IF NOT EXISTS cart_held_counts (
    node_id             TEXT PRIMARY KEY REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    held_count          INTEGER NOT NULL DEFAULT 0,
    left_held_count     INTEGER NOT NULL DEFAULT 0,
    right_held_count    INTEGER NOT NULL DEFAULT 0,
    updated_at          INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS junction_held_counts (
    junction_id         TEXT PRIMARY KEY REFERENCES junctions(id) ON DELETE CASCADE,
    held_count          INTEGER NOT NULL DEFAULT 0,
    left_held_count     INTEGER NOT NULL DEFAULT 0,
    right_held_count    INTEGER NOT NULL DEFAULT 0,
    capacity_left       INTEGER DEFAULT 2,
    capacity_right      INTEGER DEFAULT 2,
    updated_at          INTEGER NOT NULL
);

-- Last N routing decisions per cart (why we sent to a node or to a terminal). Pruned to 5 per cart on insert.
CREATE TABLE IF NOT EXISTS routing_decision_log (
    id                  TEXT PRIMARY KEY,
    cart_uuid           TEXT NOT NULL,
    created_at          INTEGER NOT NULL,
    station_id          TEXT NOT NULL,
    decision_type       TEXT NOT NULL,
    preferred_node_id   TEXT,
    chosen_node_id      TEXT NOT NULL,
    can_dispatch        INTEGER NOT NULL,
    block_reason        TEXT,
    destination_address TEXT
);
CREATE INDEX IF NOT EXISTS idx_routing_decision_log_cart ON routing_decision_log(cart_uuid, created_at DESC);
