# Cleanup and Current State

This file records what was removed and what the codebase uses today.

## Done

- **Migrations removed** — Database initializes from `schema.sql` only. No in-DB migration logic.
- **Legacy block tables removed from use** — The plugin no longer creates or uses:
  - `transfer_switches`, `hold_switches`, `gate_slots` (transfer/terminal)
  - `junction_switches`, `junction_gate_slots` (junction)
- **Detector/controller model only** — Physical execution is via **detectors** (copper bulb + sign) and **controllers** (copper bulb + sign). The plugin toggles copper bulbs; players wire them to redstone/rails.
- **Pairing and segment wands** — Implemented. Use pairing wand to link two transfer nodes; use segment wand to register junction segment (transfer A → junction(s) → transfer B).
- **Backwards-compat code trimmed** — Removed unused `JunctionRepository` methods; comments updated to drop “legacy” wording where appropriate.

## Still in schema (intended)

- **Lecterns** and **signal_bindings** — Used by the signals/lectern feature. Not part of the detector/controller redesign.

## Optional future

- **Creation only via detectors** — Today nodes/terminals can still be created with `/transfer create` / `/terminal create`; junctions via create/setup. Target: create transfer/terminal only by placing a [Transfer]/[Terminal] detector (or absorb); junctions only from a pair of detectors with a “queue” until the other side is placed.
- **Absorb wand** — Click-based absorb flow (e.g. click station then detectors) instead of or in addition to current `/absorb` usage.

---

*Main design doc: **DESIGN_DETECTORS_CONTROLLERS.md**.*
