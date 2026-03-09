# Netro plugin: optimization plan

Concrete list of improvements aligned with [PLUGIN_OPTIMIZATION.md](PLUGIN_OPTIMIZATION.md). Items are ordered by impact and feasibility. Status: **Backlog** unless noted.

---

## Already done (reference)

- **Single cart data fetch per detector pass** – One `cartRepo.find(cartUuid)` after unified flow; passed through handleUnreachable, processRule, evaluateRuleEngine.
- **Route cache before Dijkstra** – handleUnreachableAndRedirectToNearestTerminal checks route cache first; skips Dijkstra when route is cached.
- **Single runTask per detector pass** – Logic and apply (bulbs, rail state, READY center) run in one scheduled task.
- **Rules cache** – Rules loaded by context and cached; invalidated on rule create/update/delete.
- **UnifiedCartSeenFlow single find** – One find at start for both “in DB?” and no-dest check.
- **Chunk loading** – Skip adding ticket when chunk already loaded; only load for registered carts without player passenger; cross-dimension portal chunks for both.
- **refreshOpenMenus** – Only collect carts needed for open menus; collectMinecartsByUuid(needed) with early exit; 40 ticks.
- **findMinecartByUuid (GUI)** – World cache; try cached world first; cache updated on menu refresh, main click, destination back, applyRuleCruiseSpeed.
- **ChunkLoadService** – collectMinecartsByUuid(needed) for DB cart UUIDs only; early exit.
- **DetectorRailHandler findMinecartByUuid** – World-scoped where known; DeferredRailStateTask worldName hint when re-finding.
- **BossBar / intervals** – CartController 20 ticks + TTL cache; RailroadController 20; BlockHighlight 10; RegionBoundary 20; refreshOpenMenus 40.
- **Stale cart cleanup** – Runs every 2 min (async DB list, main-thread entity iteration). No getLoadedChunks() (that call is expensive and caused lag spikes).
- **BossBar destination cache** – TTL cache (1.5s) for destination + next node.
- **processRule / evaluateRuleEngine** – nodeCache, stationCache, destDisplayCache per pass (reused, cleared each run); formatDestForLog uses cache.

---

## 1. Entity iteration (getEntitiesByClass)

**Practice:** Avoid scanning all entities every tick; use event-driven updates or limited scope (see PLUGIN_OPTIMIZATION.md §3).

| Where | Status |
|-------|--------|
| **CartControllerGuiListener.refreshOpenMenus** | **Done** – collect only open-menu cart UUIDs; 40 ticks. |
| **CartControllerGuiListener.findMinecartByUuid** | **Done** – world cache; try cached world first. |
| **ChunkLoadService** | **Done** – collect only DB cart UUIDs; early exit. |
| **DetectorRailHandler.findMinecartByUuid** | **Done** – world-scoped where known; DeferredRailStateTask world hint. |
| **NetroPlugin stale cart cleanup** | No getLoadedChunks() (reverted; was causing lag). Iterate worlds + minecarts only. |

---

## 2. Database on main thread

**Practice:** Run DB work async; sync back only when you need to touch Bukkit API (see PLUGIN_OPTIMIZATION.md §4).

| Where | Status |
|-------|--------|
| **All detector / routing / GUI DB calls** | **Backlog** – Async DB layer (high effort). |
| **CartControllerBossBarUpdater.tick** | **Done** – 20 ticks + TTL cache for destination + next node. |
| **refreshOpenMenus** | **Done** – 40 ticks. |

---

## 3. Scheduler and runTask usage

**Practice:** Keep main-thread work short; avoid nested runTask when one task can do the work (see PLUGIN_OPTIMIZATION.md §2).

| Where | Status |
|-------|--------|
| **notifyUnreachableRedirect** | **Done** – World passed from detector; findMinecartByUuid(world, uuid). |
| **GUI flows / UnifiedCartSeenFlow** | No change (required main-thread usage). |

---

## 4. Repeating task intervals

**Practice:** Don't run expensive work every tick if a lower frequency is enough (see PLUGIN_OPTIMIZATION.md §7).

| Where | Status |
|-------|--------|
| **CartControllerBossBarUpdater** | **Done** – 20 ticks + TTL cache. |
| **RailroadControllerBossBarUpdater** | **Done** – 20 ticks. |
| **BlockHighlightTask** | **Done** – 10 ticks. |
| **RegionBoundaryHighlightTask** | **Done** – 20 ticks. |
| **refreshOpenMenus** | **Done** – 40 ticks. |

---

## 5. Caching and allocation

**Practice:** Cache read-heavy data; reuse collections/objects on hot paths (see PLUGIN_OPTIMIZATION.md §6).

| Where | Status |
|-------|--------|
| **Station/Node lookups** | **Done** – nodeCache, stationCache per pass in processRule/evaluateRuleEngine. |
| **formatDestinationId** | **Done** – destDisplayCache per pass; formatDestForLog uses it. |
| **ArrayList/HashMap reuse** | **Backlog** – Optional; minor gain. |

---

## 6. Chunk loading (already partially done)

**Practice:** Only add plugin chunk tickets when needed; don't add if chunk already loaded (see PLUGIN_OPTIMIZATION.md §5).

| Where | Status |
|-------|--------|
| **ChunkLoadService** | **Backlog** – Optional: Paper NamespacedKey chunk tickets if API upgraded. |

---

## 7. Database layer (longer-term)

**Practice:** Async queries, connection reuse, batching (see PLUGIN_OPTIMIZATION.md §4).

| Where | Status |
|-------|--------|
| **Database** | **Backlog** – Async API (high effort): withConnectionAsync, executor, callbacks. |
| **Connection** | No change. |
| **Indexes** | Reviewed; schema has appropriate indexes. |

---

## 8. Event handler and debounce

**Practice:** Debounce high-frequency events; keep handlers cheap (see PLUGIN_OPTIMIZATION.md §7).

| Where | Current behavior | Proposed change | Priority |
|-------|------------------|-----------------|----------|
| **CartListener / VehicleMoveEvent** | Every move event: detector handler called (onCartOnDetectorRail). | Already debounced inside detector (DEBOUNCE_MS_ENTRY_READY, DEBOUNCE_MS_CLEAR). No change unless we see move event still hot in profiler. | — |
| **Block physics / detector** | Multiple blocks (rail + below) can fire for same cart. | We already runTask so the second hit in same tick sees updated state. No change. | — |

---

## Remaining backlog (optional / high effort)

- **Async DB layer** (§2, §7) – Optional async API for read-heavy paths; larger refactor.
- **List reuse** (§5) – Reuse ArrayList/HashMap in detector apply run; minor gain.
- **Paper NamespacedKey chunk tickets** (§6) – If upgrading Paper API.

Use this list for future improvements; re-profile after changes to confirm improvement.
