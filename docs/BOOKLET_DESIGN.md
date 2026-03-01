# Netro In-Game Booklet — Design & Constraints

## Purpose

A written book given to players in-game that explains setup, detector/controller roles, and cause–effect (e.g. ENTRY → DIVERT). Table of contents with **clickable links** (`change_page`) so players can jump to sections. Use **shorthand** after the first pages to save space.

---

## Minecraft Book Constraints (research)

| Constraint | Value | Notes |
|------------|--------|--------|
| **Lines per page** | 14 max | Extra lines are cut off. |
| **Characters per line** | ~23–25 | Font is not monospace; width varies. |
| **Characters per page** | 255 max | Hard limit in NBT/JSON. |
| **Click events** | `change_page`, `run_command`, `open_url`, `suggest_command`, `copy_to_clipboard` | **`change_page`** is the only one that keeps the book open (value = page number as string, e.g. `"5"`). Use for ToC. |
| **Book close on click** | Yes for `run_command` / `open_url` | So ToC should use `change_page`, not run commands. |

**Recommendation:** Aim for **~20–22 characters per line** and **12–13 lines per page** to stay safe across clients; avoid long words at line breaks.

---

## Book Structure (proposed)

1. **Title page** — "Netro Guide", short one-line blurb.
2. **Table of contents** — Clickable entries; each link uses `change_page` to jump to the first page of that section (e.g. "Signals & shorthand → p.4", "Setup: transfer node → p.6", "Cause & effect → p.10").
3. **Signals & shorthand** — One or two pages listing:
   - **Detector roles:** ENT=ENTRY, REA=READY, CLE=CLEAR (and :L/:R or :LEFT/:RIGHT).
   - **Controller roles:** DIV=DIVERT, DIV+=DIVERT+, NOD=NOT_DIVERT, NOD+=NOT_DIVERT+, REL=RELEASE; TRANSFER / NOT_TRANSFER for station.
   - **Direction:** L=LEFT, R=RIGHT.
   - Use these shorthands everywhere else in the book.
4. **Setup sections** — Short steps: station sign → transfer/terminal detector → controller bulbs → pairing wand → segment wand → junction detectors. Each section 1–2 pages; link from ToC. **Create-on-first:** Placing a [Transfer] or [Terminal] sign with `Station:Node` creates the node if the station exists; placing a [Junction] sign with a new junction name creates the junction. Errors are shown in chat only (sign is not overwritten). Valid signs get color styling.
5. **Cause & effect** — One page (or two) with clear pairs, e.g.:
   - **ENT:L** fires → plugin may turn **DIV:L** ON and **NOD:L** OFF (or opposite if pass-through).
   - **REA** fires → when safe, **REL:L** or **REL:R** turns ON.
   - **CLE:R** fires → **DIV+:L** and **NOD+:L** turn OFF; **REL** turns OFF.
   - **CLE** never turns a controller ON; only OFF for + roles and REL.
6. **Optional** — Junction LEFT/RIGHT rule (only RIGHT of one junction connects to LEFT of next); absorb; wands.

---

## Cause–Effect Summary (for booklet text)

| When this detector fires… | …these controller bulbs are driven |
|---------------------------|-------------------------------------|
| **ENTRY:LEFT** (cart entering from left) | DIVERT:LEFT and NOT_DIVERT:LEFT (ON/OFF by routing decision). |
| **ENTRY:RIGHT** | Same for RIGHT. |
| **READY** (cart stopped in siding) | When safe to release: RELEASE:LEFT or RELEASE:RIGHT ON. |
| **CLEAR** (cart left siding) | DIVERT+, NOT_DIVERT+, RELEASE → OFF (CLEAR never turns any ON). |

Use shorthands (ENT, REA, CLE, DIV, NOD, REL) in the book after the glossary.

---

## Implementation

- [x] **Command** — `/netro book` or `/netro guide` (player-only) gives one written book. Implemented in `NetroCommand`; book built in `dev.netro.util.NetroBook`.
- [x] **Page builder** — Uses **Spigot's `BookMeta.Spigot().setPages(BaseComponent[]...)`** with `net.md_5.bungee.api.chat.TextComponent` and `ClickEvent.Action.CHANGE_PAGE`. Passing raw JSON strings to `BookMeta.setPages(List<String>)` causes the client to show literal brackets/text; the Spigot component API serializes correctly so the client renders formatted pages and clickable links.
- [x] **Content** — Title, ToC (links to pages 3–9), signals & shorthand (detector ENT/REA/CLE, controller DIV/NOD/REL, station TRANSFER/NOT_TRANSFER), setup (station, transfer), wands, cause–effect, junction rule.
- [ ] **Testing** — Give book in-game; verify ToC links jump correctly and text fits without truncation on a few clients/resolutions.

---

## References

- **Design (detectors/controllers, roles, cause–effect):** [DESIGN_DETECTORS_CONTROLLERS.md](DESIGN_DETECTORS_CONTROLLERS.md)
- **Overview (commands, scenarios):** [OVERVIEW.md](OVERVIEW.md)
- Minecraft: Written book NBT `pages` = array of JSON strings; each string is one page. Click events in JSON: `"clickEvent":{"action":"change_page","value":"4"}`.
