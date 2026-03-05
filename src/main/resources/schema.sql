-- Netro Schema v2

-- ── Stations ──────────────────────────────────────────────────────────────────
-- dimension: 0 = Overworld, 1 = Nether (first tier in address, e.g. 0.2.4.7.3)
CREATE TABLE IF NOT EXISTS stations (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    address     TEXT NOT NULL UNIQUE,
    world       TEXT NOT NULL,
    dimension   INTEGER NOT NULL DEFAULT 0,
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

-- Portal link blocks per transfer node. side: 0 = same dimension as node, 1 = other dimension (e.g. nether side when node is OW).
-- For OW–OW pairs we need both sides to compute nether segment (portal A nether → portal B nether). If any block is broken, that side is cleared.
CREATE TABLE IF NOT EXISTS transfer_node_portal_blocks (
    node_id     TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    side        INTEGER NOT NULL DEFAULT 0,
    world       TEXT NOT NULL,
    x           INTEGER NOT NULL,
    y           INTEGER NOT NULL,
    z           INTEGER NOT NULL,
    PRIMARY KEY (node_id, side, world, x, y, z)
);
CREATE INDEX IF NOT EXISTS idx_tn_portal_blocks_node ON transfer_node_portal_blocks(node_id);
CREATE INDEX IF NOT EXISTS idx_tn_portal_blocks_block ON transfer_node_portal_blocks(world, x, y, z);

-- Routing: first-hop is computed on the fly via shortest path (stations + paired transfer nodes); no routing_entries table.

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

-- ── Detectors & Controllers (v3: plugin decides, player executes via copper bulbs) ──
CREATE TABLE IF NOT EXISTS detectors (
    id                  TEXT PRIMARY KEY,
    node_id             TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
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
    created_at          INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_detectors_node ON detectors(node_id);
CREATE INDEX IF NOT EXISTS idx_detectors_rail ON detectors(world, rail_x, rail_y, rail_z);

CREATE TABLE IF NOT EXISTS controllers (
    id                  TEXT PRIMARY KEY,
    node_id             TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
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
CREATE INDEX IF NOT EXISTS idx_controllers_node ON controllers(node_id);

-- Rule-based actions: per transfer or terminal. Replaces station detectors/controllers.
CREATE TABLE IF NOT EXISTS rules (
    id                   TEXT PRIMARY KEY,
    context_type         TEXT NOT NULL,
    context_id           TEXT NOT NULL,
    context_side         TEXT,
    rule_index           INTEGER NOT NULL,
    trigger_type         TEXT NOT NULL,
    destination_positive  INTEGER NOT NULL,
    destination_id       TEXT,
    action_type          TEXT NOT NULL,
    action_data          TEXT,
    created_at           INTEGER NOT NULL,
    station_id           TEXT,
    node_name            TEXT
);
CREATE INDEX IF NOT EXISTS idx_rules_context ON rules(context_type, context_id, context_side);

CREATE TABLE IF NOT EXISTS cart_held_counts (
    node_id             TEXT PRIMARY KEY REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    held_count          INTEGER NOT NULL DEFAULT 0,
    updated_at          INTEGER NOT NULL
);

-- Cached routes: from_station_id → dest_station_id → first_hop_node_id, cost. Used to avoid recomputing Dijkstra when unchanged.
CREATE TABLE IF NOT EXISTS route_cache (
    from_station_id   TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    dest_station_id   TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    first_hop_node_id TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE,
    cost              INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    PRIMARY KEY (from_station_id, dest_station_id)
);
CREATE INDEX IF NOT EXISTS idx_route_cache_from ON route_cache(from_station_id);
