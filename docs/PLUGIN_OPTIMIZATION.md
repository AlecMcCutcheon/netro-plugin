# Plugin optimization: best practices

A reference for keeping Netro (and similar Bukkit/Paper plugins) performant. Covers server setup, threading, hot paths, database use, chunk loading, and code-level habits.

---

## 1. Server and runtime

### Use Paper (or a fork)

- Prefer **Paper** over Spigot for built-in optimizations (async chunk loading, entity activation, tick improvements).
- For heavy servers: **Purpur** or **Pufferfish** can add more gains; stay compatible with Paper API.

### JVM flags

- Use **G1GC** (or ZGC on newer JDKs) and tuned flags (e.g. Aikar’s) to reduce GC spikes.
- Set **-Xms** and **-Xmx** to the same value to avoid heap resizing.
- Allocate enough RAM; more than ~10–12 GB often doesn’t help without tuning.

### Server config (admin)

- **View / simulation distance**: Lower (e.g. 6–8 view, 5 simulation) reduces chunk and entity load.
- **Entity activation range** (e.g. `spigot.yml`): Stops ticking distant entities; big impact.
- **Hopper / redstone**: Use Paper’s hopper optimizations if available.

---

## 2. Threading and scheduler

### Prefer the Bukkit scheduler over raw threads

- Use `Bukkit.getScheduler()` (or `BukkitRunnable`) instead of `new Thread()` so the server can manage and clean up work.
- Reuse the scheduler’s pool instead of creating many one-off threads.

### Keep main thread work short

- **Event handlers and runTask:** Do minimal work; no heavy I/O or long loops.
- Defer heavy work with `runTaskLater(plugin, runnable, delayTicks)` or run it async and then sync back only what must run on the main thread.

### Async for I/O, then sync back for Bukkit API

- **Database, file, HTTP:** Run on an async task (`runTaskAsynchronously`).
- **World, entities, blocks, inventory:** Only touch on the main thread. After async work, use `runTask(plugin, () -> { ... })` to apply results.
- Never call Bukkit API from an async task (e.g. no `World#getEntitiesByClass`, `Location`, block get/set from async).

### Avoid extra runTask layers

- Collapse multiple `runTask` calls in one flow when possible (e.g. one task that does “logic + apply” instead of “logic” → schedule → “apply”) to cut scheduler overhead and latency.

---

## 3. Entity and world access

### Don’t scan all entities every tick

- Calling `world.getEntitiesByClass(...)` or iterating all entities every tick is expensive.
- Prefer:
  - **Event-driven updates:** Listen to spawn/death/move and keep a cached set or count.
  - **Limited scope:** `getNearbyEntities(center, x, y, z)` with small ranges instead of whole-world scans.
- If you must scan (e.g. periodic sync), do it at low frequency (e.g. every N seconds), not every tick.

### Reuse and cache lookups in a single “pass”

- In one logical pass (e.g. one detector fire), fetch cart/player/entity data once and pass it through instead of calling `find(uuid)` or equivalent many times.
- Cache “current station”, “current node”, etc. for that pass instead of re-resolving from DB or world repeatedly.

### Prefer local checks over global iteration

- Use distance checks, chunk bounds, or small `getNearbyEntities` instead of iterating every entity in the world.

---

## 4. Database (SQLite / JDBC)

### Avoid blocking the main thread

- Run queries (read and write) **asynchronously** where possible. Sync back to main only to apply results (e.g. open GUI, update in-memory state).
- Even “fast” SQLite can block; main-thread DB work can cause TPS dips under load.

### Reuse connections; avoid open/close per query

- Keep a single connection (or a small pool) and reuse it. Opening/closing per query is costly.
- For SQLite, one long-lived connection is often enough; ensure access is serialized or thread-safe as needed.

### Batch and reduce round-trips

- Prefer one query that returns the data you need instead of many small queries in a loop.
- Cache read-heavy, rarely-changing data (e.g. rules per context) and invalidate on write.

### Indexes and schema

- Add indexes for columns used in WHERE / JOIN; avoid full table scans on large tables.

---

## 5. Chunk loading (Paper)

### Use plugin chunk tickets sparingly

- Only keep chunks loaded when the plugin truly needs them (e.g. detector rails, critical blocks).
- Prefer Paper’s `addPluginChunkTicket` (or NamespacedKey-based API where available) and track refs so you remove tickets when no longer needed.

### Skip adding tickets when chunk is already loaded

- If the chunk is already loaded (e.g. by a player), skip adding a ticket and only add when you would otherwise load it. Track which chunks you actually added a ticket for so you only remove those.

### Avoid forcing load “just in case”

- Don’t call `getChunkAt()` or equivalent in hot paths if it can load chunks; use `isChunkLoaded()` first where possible.

---

## 6. Memory and allocations

### Reduce allocations on hot paths

- Reuse collections (clear and refill instead of new ArrayList every tick), reuse DTOs or simple objects where it’s clear and safe.
- Avoid creating large temporary objects (e.g. big arrays or maps) in code that runs every tick or every event.

### Cache expensive lookups

- Cache “by context” or “by id” data (e.g. rules, config) and invalidate when that data is updated (create/update/delete).
- Reduces repeated DB or file reads and repeated parsing.

### Lazy and minimal data

- Don’t load or parse data that isn’t needed for the current code path. Lazy-load where appropriate.

---

## 7. Event handlers and hot paths

### Keep handlers short and cheap

- Event handlers run on the main thread. Do the minimum: validate, maybe schedule a task, then return.
- Move heavy logic (DB, file, complex computation) to async or to a delayed runTask.

### Debounce or throttle frequent events

- For high-frequency events (e.g. move, block physics), use time-based or count-based debouncing so you don’t run expensive logic every single fire.
- Example: only run “cart passed detector” logic once per cart per N ms or per N ticks.

### Avoid nested runTask in the same tick when possible

- Prefer one runTask that does “compute + apply” over “runTask A → inside A schedule runTask B”. Reduces scheduler overhead and keeps logic easier to follow.

---

## 8. Reflection and external calls

### Cache reflection results

- If you use reflection (e.g. for optional APIs like Paper chunk tickets), resolve `Method`/`Field` once at startup or first use and reuse; don’t look up every call.

### Optional / fallback APIs

- When using optional APIs (e.g. Paper-only), check once and cache the result; avoid repeated “is class present” or “is method present” in hot paths.

---

## 9. Profiling and monitoring

### Use a profiler

- **Spark** (often bundled with Paper), **WarmRoast**, or similar to find where time is spent (which events, which methods, which threads).
- Focus on main-thread time and recurring work (every tick, every move).

### Measure before and after

- After optimizations, re-profile to confirm improvements and avoid regressions.

---

## 10. Checklist (quick reference)

| Area | Do | Avoid |
|------|----|--------|
| **Threading** | Async for DB/I/O; sync back for Bukkit API; single runTask when possible | Heavy work on main thread; many nested runTasks |
| **Entities** | Event-driven updates; nearbyEntities with small range; one fetch per pass | getEntitiesByClass every tick; whole-world scans |
| **Database** | Async queries; one connection or small pool; batch/cache reads | Main-thread DB; open/close per query; repeated same query in one pass |
| **Chunks** | Tickets only where needed; skip if already loaded; remove when not needed | Loading chunks in hot path; unnecessary force-load |
| **Memory** | Reuse collections/objects; cache by context; invalidate on write | New large objects every tick; uncached repeated lookups |
| **Events** | Short handlers; debounce; defer heavy work | Expensive logic in event handler; no debounce on high-frequency events |
| **Reflection** | Resolve once; cache Method/Field | Reflection lookup in hot path |

---

*References: Paper/Spigot docs, SpigotMC optimization threads, Aikar’s JVM tuning, server optimization guides (Shockbyte, Paper Chan, etc.).*
