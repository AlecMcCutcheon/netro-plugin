# Dispatch & junction logic — scenario walkthrough

This document lists **arbitrary scenarios** (carts, directions, junction state) without applying logic at design time. After all scenarios are written, each is evaluated against the current rules to see what the system would do. Any scenario where the logic breaks or a gap appears is flagged for discussion.

**Setup convention:** One segment between node **A** (station A) and node **B** (station B). One junction **J** in the middle. Junction LEFT = nodeA side, RIGHT = nodeB side. Segment occupancy is (A, B) with direction A_TO_B (A→B) or B_TO_A (B→A). Capacity: 2 slots per side at J (LEFT and RIGHT), or single pool of 4 when not using directional queues.

**Rules (short):**
- **Dispatch (canDispatch):** Destination node not full. No opposing → allow. Opposing → need junction(s); for each junction, free slots **for our approach side** ≥ (same-direction count on segment + 1). Committed past last junction → block.
- **ENTRY at junction (shouldDivertJunction):** Remove cart from segment; way ahead clear? If opposing traffic on exit segment → divert (if junction has free slot for our side); else pass.
- **Directionality:** Opposing = opposite direction only. Same direction does not block dispatch.

---

## Part 1: Scenarios (state only)

### Scenario 1
- Segment A–B: **no carts**. Junction J: **empty** (0 LEFT, 0 RIGHT).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 2
- Segment A–B: **1 cart** A→B (on segment, not at junction yet). Junction J: **empty**.
- **Question:** Station at A wants to send a second cart A→B.

### Scenario 3
- Segment A–B: **1 cart** B→A (opposing to A). Junction J: **empty** (2 free LEFT, 2 free RIGHT).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 4
- Segment A–B: **1 cart** B→A. Junction J: **LEFT full** (0 free LEFT), RIGHT has 2 free.
- **Question:** Station at A wants to send one cart A→B.

### Scenario 5
- Segment A–B: **1 cart** B→A. Junction J: **LEFT full, RIGHT full** (0 free both sides).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 6
- Segment A–B: **2 carts** A→B (same direction). Junction J: **empty**.
- **Question:** Station at A wants to send a third cart A→B.

### Scenario 7
- Segment A–B: **2 carts** A→B, **1 cart** B→A (opposing). Junction J: **2 free LEFT**, 2 free RIGHT.
- **Question:** Station at A wants to send a third cart A→B.

### Scenario 8
- Segment A–B: **2 carts** A→B, **1 cart** B→A. Junction J: **1 free LEFT**, 2 free RIGHT.
- **Question:** Station at A wants to send a third cart A→B.

### Scenario 9
- Segment A–B: **no carts**. Junction J: **2 carts held in LEFT** (0 free LEFT), 0 in RIGHT.
- **Question:** Station at B wants to send one cart B→A.

### Scenario 10
- Segment A–B: **1 cart** A→B (moving toward B). Junction J: **1 cart held in LEFT** (1 free LEFT).
- **Question:** Station at B wants to send one cart B→A.

### Scenario 11
- **No junction** on segment A–B. Segment: **1 cart** A→B.
- **Question:** Station at B wants to send one cart B→A.

### Scenario 12
- **No junction** on segment A–B. Segment: **1 cart** A→B.
- **Question:** Station at A wants to send a second cart A→B.

### Scenario 13
- Segment A–B: **1 cart** A→B, **1 cart** B→A** (both on segment, heading toward J). Junction J: **empty** (2 LEFT, 2 RIGHT).
- **Question:** Station at A wants to send another cart A→B.

### Scenario 14
- Segment A–B: **1 cart** in junction (held LEFT; already off segment). **No other carts** on segment.
- **Question:** Station at A wants to send one cart A→B.

### Scenario 15
- Segment A–B: **1 cart** held in junction LEFT. **1 cart** on segment B→A (approaching J from RIGHT).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 16
- Segment A–B: **2 carts** A→B on segment. **1 cart** B→A on segment. Junction J: **0 free LEFT**, 2 free RIGHT.
- **Question:** Station at A wants to send a third cart A→B.

### Scenario 17
- Segment A–B: **1 cart** A→B. Junction J: **1 cart** held RIGHT (1 free RIGHT). No other carts.
- **Question:** Station at B wants to send one cart B→A.

### Scenario 18
- Segment A–B: **1 cart** B→A. Junction J: **0 free RIGHT** (RIGHT full), 2 free LEFT.
- **Question:** Station at B wants to send one cart B→A.

### Scenario 19
- Segment A–B: **3 carts** A→B on segment. **1 cart** B→A. Junction J: **2 free LEFT**, 2 free RIGHT (capacity 2 per side).
- **Question:** Station at A wants to send a fourth cart A→B.

### Scenario 20
- Segment A–B: **1 cart** A→B has just triggered **ENTRY at J from LEFT** (cart removed from segment; decision: divert or pass?). **1 cart** B→A still on segment (hasn’t reached J).
- **Question:** What does shouldDivertJunction do for the cart that just ENTRY’d from LEFT?

### Scenario 21
- Segment A–B: **1 cart** B→A has just triggered **ENTRY at J from RIGHT**. **1 cart** A→B still on segment (hasn’t reached J).
- **Question:** What does shouldDivertJunction do for the cart that just ENTRY’d from RIGHT?

### Scenario 22
- Segment A–B: **1 cart** A→B has just triggered **ENTRY at J from LEFT**. **No** other carts on segment. Junction J: **1 free LEFT**.
- **Question:** What does shouldDivertJunction do?

---

## Part 2: Outcomes (applying the logic)

Below we apply the documented rules to each scenario and state the outcome. If a scenario exposes a gap or contradiction, we stop and call it out.

---

### Scenario 1
- **State:** Empty segment, empty junction.
- **Dispatch A→B:** canDispatch(A, B). No opposing; junctions exist but we don’t check capacity when no opposing. Committed check: no B_TO_A carts past J. **Result: ALLOW.** Cart is sent onto segment.

---

### Scenario 2
- **State:** 1 cart A→B on segment, junction empty.
- **Dispatch A→B:** No opposing (only same direction). **Result: ALLOW.** Same-direction traffic allowed.

---

### Scenario 3
- **State:** 1 cart B→A (opposing), J empty.
- **Dispatch A→B:** Opposing = true. sameDirectionCount(A→B) = 0, slotsNeeded = 1. Approach from A = LEFT. Junction has 2 free LEFT. 2 ≥ 1. **Result: ALLOW.**

---

### Scenario 4
- **State:** 1 cart B→A, J LEFT full (0 free LEFT), RIGHT has 2 free.
- **Dispatch A→B:** Opposing. slotsNeeded = 1. Free slots for LEFT = 0. 0 < 1. **Result: BLOCK.** “Junction has 0 slot(s) for LEFT approach, need 1.”

---

### Scenario 5
- **State:** 1 cart B→A, J both sides full.
- **Dispatch A→B:** Opposing, slotsNeeded = 1, 0 free LEFT. **Result: BLOCK.**

---

### Scenario 6
- **State:** 2 carts A→B, J empty.
- **Dispatch A→B:** No opposing. **Result: ALLOW.**

---

### Scenario 7
- **State:** 2 carts A→B, 1 cart B→A, J has 2 free LEFT.
- **Dispatch A→B:** Opposing. sameDirectionCount = 2, slotsNeeded = 3. We need 3 free on LEFT; junction has 2 per side. 2 < 3. **Result: BLOCK.** Prevents sending a third when only 2 can be held on LEFT.

---

### Scenario 8
- **State:** 2 carts A→B, 1 cart B→A, J has 1 free LEFT.
- **Dispatch A→B:** Opposing, slotsNeeded = 3, 1 free LEFT. 1 < 3. **Result: BLOCK.**

---

### Scenario 9
- **State:** No carts on segment, J has 2 held LEFT (0 free LEFT), 0 RIGHT.
- **Dispatch B→A:** We’re at B, so direction B→A. Opposing = carts A_TO_B = none. No opposing. **Result: ALLOW.** Junction full on LEFT doesn’t matter when no opposing.

---

### Scenario 10
- **State:** 1 cart A→B on segment, 1 held in J LEFT (1 free LEFT).
- **Dispatch B→A:** Opposing = A_TO_B = 1 cart. sameDirectionCount(B→A) = 0, slotsNeeded = 1. Approach from B = RIGHT. Free RIGHT = 2. 2 ≥ 1. **Result: ALLOW.**

---

### Scenario 11
- **State:** No junction, 1 cart A→B.
- **Dispatch B→A:** Opposing = A_TO_B = 1. junctions.isEmpty() → block. **Result: BLOCK.** “Opposing traffic on segment, no junction to mediate.”

---

### Scenario 12
- **State:** No junction, 1 cart A→B.
- **Dispatch A→B (second cart):** No opposing (same direction). **Result: ALLOW.**

---

### Scenario 13
- **State:** 1 A→B, 1 B→A on segment, J empty.
- **Dispatch A→B:** Opposing = 1 (B→A). sameDirectionCount = 1, slotsNeeded = 2. 2 free LEFT. 2 ≥ 2. **Result: ALLOW.**

---

### Scenario 14
- **State:** 1 cart in J (LEFT), no carts on segment (that cart was removed from segment at ENTRY).
- **Dispatch A→B:** Opposing = B_TO_A = 0. No opposing. **Result: ALLOW.**

---

### Scenario 15
- **State:** 1 in J LEFT, 1 on segment B→A.
- **Dispatch A→B:** Opposing = B_TO_A = 1. sameDirectionCount(A→B) = 0, slotsNeeded = 1. Free LEFT = 1 (one slot used by held cart). 1 ≥ 1. **Result: ALLOW.**

---

### Scenario 16
- **State:** 2 A→B, 1 B→A on segment; J 0 free LEFT, 2 free RIGHT.
- **Dispatch A→B:** Opposing. sameDirectionCount = 2, slotsNeeded = 3. Free LEFT = 0. 0 < 3. **Result: BLOCK.**

---

### Scenario 17
- **State:** 1 A→B on segment, 1 held in J RIGHT (1 free RIGHT).
- **Dispatch B→A:** Opposing = A_TO_B = 1. slotsNeeded = 1. Approach from B = RIGHT. Free RIGHT = 1. 1 ≥ 1. **Result: ALLOW.**

---

### Scenario 18
- **State:** 1 B→A on segment, J RIGHT full (0 free RIGHT), 2 free LEFT.
- **Dispatch B→A (second cart):** Opposing = A_TO_B = 0 (no cart A→B). Wait — we’re sending B→A. So “our” direction is B_TO_A. Opposing = A_TO_B. There is 1 cart B→A on segment; that’s same direction. So opposing count = 0. No opposing. **Result: ALLOW.** (Same-direction doesn’t block.)

---

### Scenario 19
- **State:** 3 A→B, 1 B→A on segment. J 2 free LEFT, 2 free RIGHT (capacity 2 per side).
- **Dispatch A→B:** Opposing. sameDirectionCount = 3, slotsNeeded = 4. Free LEFT = 2. 2 < 4. **Result: BLOCK.** Even though total junction capacity might be 4, we use **directional** capacity: only 2 on LEFT. So we correctly block.

---

### Scenario 20
- **State:** Cart C1 just ENTRY at J from LEFT (removed from segment). Cart C2 is B→A still on segment.
- **shouldDivertJunction(C1, J, LEFT):** C1 is off segment. Way ahead for LEFT = segment toward B; opposing = A_TO_B. C2 is B→A, so segment has 1 cart B_TO_A. Opposing to A_TO_B is B_TO_A. So findOpposingCarts(A, B, "A_TO_B") returns C2. Way not clear. Junction has room for LEFT (assume yes). **Result: DIVERT.** C1 goes into LEFT queue. Correct: C2 can then pass when it reaches J.

---

### Scenario 21
- **State:** Cart C2 (B→A) just ENTRY at J from RIGHT. Cart C1 (A→B) still on segment.
- **shouldDivertJunction(C2, J, RIGHT):** C2 off segment. Way ahead for RIGHT = segment toward A; opposing = B_TO_A. C1 is A→B = A_TO_B. So findOpposingCarts(A, B, "B_TO_A") returns C1. Way not clear. **Result: DIVERT.** C2 goes into RIGHT queue. C1 can pass when it reaches J. Correct.

---

### Scenario 22
- **State:** Cart C1 just ENTRY at J from LEFT. No other carts on segment. J has 1 free LEFT.
- **shouldDivertJunction(C1, J, LEFT):** Way ahead (toward B) = opposing A_TO_B = none. Way clear. **Result: NOT_DIVERT (pass through).** Correct.

---

## Part 3: Gap check

- Scenarios 1–22 were applied; all had a clear outcome consistent with the rules.
- **Potential edge:** Scenario 19 assumes per-side capacity is 2. If a junction were configured with different capacities per side, the current code uses a fixed `CAPACITY_PER_SIDE = 2`. That’s a design choice, not a logic bug.
- **Committed past last junction:** No scenario in this set had an opposing cart with zone indicating “past the junction”; adding one would be a good extra scenario (e.g. cart B→A with zone past J → dispatch A→B should block).
- **Multiple junctions on one segment:** Current code iterates all junctions and requires slotsNeeded for **our** approach per junction. For a segment A—J1—J2—B, “our approach” for J1 is from A (LEFT of J1); for J2 it depends on J2’s nodeA/nodeB. If J2’s endpoints are “mid” and B, then from A we might be “approaching J2 from mid” — the code uses j.getNodeAId().equals(fromNodeId), so fromNodeId is always A. So for J2, if nodeA=mid, nodeB=B, then fromNodeId A ≠ mid, so approachSide = RIGHT. That might or might not match physical layout; worth a separate scenario doc if segments with multiple junctions are used.

**Conclusion:** No scenario in this set exposed a clear logic break or gap. If you want to stress-test further, we can add scenarios for: committed-past-junction, multiple junctions, or terminal release timing.

---

## Part 4: Second set of scenarios (arbitrary — state only)

These scenarios do not repeat the first set. They are written without applying the rule set; we evaluate them in Part 5.

### Scenario 23
- Segment A–B: **1 cart** B→A. Cart’s **zone is not** `pre_junction_%` (i.e. it has already passed J; committed past junction). Junction J: **empty**.
- **Question:** Station at A wants to send one cart A→B.

### Scenario 24
- Segment A–B: **1 cart** A→B. Cart’s **zone is not** `pre_junction_%` (committed past J, between J and B). Junction J: **empty**.
- **Question:** Station at B wants to send one cart B→A.

### Scenario 25
- Junction J has **capacity 1 per side** (1 LEFT, 1 RIGHT). Segment A–B: **1 cart** A→B, **1 cart** B→A. J: **0 held LEFT, 0 held RIGHT** (1 free each).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 26
- Junction J: **capacity 1 per side**. Segment: **1 cart** A→B, **1 cart** B→A. J: **LEFT full** (1 held), RIGHT 0 held.
- **Question:** Station at A wants to send one cart A→B.

### Scenario 27
- Segment A–B: **2 carts** A→B on segment, **1 cart** A→B held in J LEFT, **1 cart** B→A on segment. J: **1 free LEFT** (capacity 2).
- **Question:** Station at A wants to send another cart A→B.

### Scenario 28
- Segment A–B: **no carts**. Junction J: **1 cart held in LEFT**, **1 cart held in RIGHT**.
- **Question:** Station at A wants to send one cart A→B.

### Scenario 29
- Segment A–B: **1 cart** B→A on segment. Junction J: **1 held LEFT, 1 held RIGHT** (0 free LEFT, 0 free RIGHT).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 30
- Cart C1 (A→B) has just triggered **ENTRY at J from LEFT**. The only other cart is C2 (B→A), which is **held in J RIGHT** (not on segment).
- **Question:** What does shouldDivertJunction do for C1 (from LEFT)?

### Scenario 31
- **Two junctions** on segment: A — J1 — mid — J2 — B. Segment: **1 cart** B→A (somewhere on A–B). J1 and J2 each have **2 free** on the relevant approach side.
- **Question:** Station at A wants to send one cart A→B.

### Scenario 32
- Segment A–B: **no carts**. Junction J: **empty**. **Destination node B has 0 free slots** (full).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 33
- Segment A–B: **2 carts** B→A on segment. Junction J: **1 cart** A→B held in LEFT (1 free LEFT).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 34
- Cart C (B→A) has just triggered **ENTRY at J from RIGHT**. The only other cart is D (A→B), **held in J LEFT** (not on segment).
- **Question:** What does shouldDivertJunction do for C (from RIGHT)?

### Scenario 35
- **No junction** on segment A–B. Segment: **1 cart** A→B, **1 cart** B→A.
- **Question:** Station at A wants to send a second cart A→B.

### Scenario 36
- Segment A–B: **1 cart** B→A on segment. Junction J: **2 free LEFT**. A cart is **at terminal at station A**; preferred next hop is B.
- **Question:** Does getNextHopNodeAtStation return B or a terminal?

### Scenario 37
- Segment A–B: **1 cart** B→A on segment. Junction J: **0 free LEFT**. A cart is **at terminal at station A**; preferred next hop is B.
- **Question:** Does getNextHopNodeAtStation return B or a terminal?

### Scenario 38
- Segment A–B: **1 cart** A→B on segment (only). Junction J: **1 cart** B→A held in RIGHT. **No other carts**.
- **Question:** Station at B wants to send one cart B→A.

### Scenario 39
- Junction J: **capacity 1 per side**. Segment: **no carts**. J: **1 cart held in LEFT** (0 free LEFT), **0 in RIGHT** (1 free RIGHT).
- **Question:** Station at A wants to send one cart A→B.

### Scenario 40
- Segment A–B: **3 carts** B→A on segment. Junction J: **2 free LEFT**, 2 free RIGHT.
- **Question:** Station at A wants to send one cart A→B.

---

## Part 5: Outcomes for second set (applying the rules)

We now apply the same rule set to scenarios 23–40. Any gap or ambiguity is called out in Part 6.

---

### Scenario 23
- **State:** 1 cart B→A on segment, zone NOT pre_junction (committed past J). J empty.
- **Dispatch A→B:** canDispatch: destination OK. Junctions exist. **Committed check:** findCommittedCarts(A, B, "B_TO_A", J) returns that cart (B_TO_A, zone not pre_junction). **Result: BLOCK.** “Opposing cart committed past last junction.” Correct: we must not send toward an opposing cart that has already passed the junction.

---

### Scenario 24
- **State:** 1 cart A→B on segment, zone not pre_junction (past J, between J and B). Dispatch from B to A.
- **Dispatch B→A:** Opposing = A_TO_B. findCommittedCarts(B, A, "B_TO_A", lastJunction): on segment (B,A), B_TO_A = A→B. We find A→B carts with zone not pre_junction. That cart qualifies. **Result: BLOCK.** Correct.

---

### Scenario 25
- **State:** J capacity 1 per side. 1 A→B, 1 B→A on segment. J 1 free LEFT, 1 free RIGHT.
- **Dispatch A→B:** Opposing. sameDirectionCount(A→B) = 1, slotsNeeded = 2. Approach LEFT. Free LEFT = 1. 1 < 2. **Result: BLOCK.** We need 2 slots (one for the cart on segment, one for the new cart); only 1 on LEFT. **Gap check:** Rule says “free slots for our approach side ≥ sameDirectionCount + 1”. So we need 2; we have 1. Block is correct. No gap.

---

### Scenario 26
- **State:** J 1 per side. 1 A→B, 1 B→A. J LEFT full (0 free LEFT).
- **Dispatch A→B:** Opposing, slotsNeeded = 2, free LEFT = 0. **Result: BLOCK.**

---

### Scenario 27
- **State:** 2 A→B on segment, 1 A→B in J LEFT, 1 B→A on segment. J 1 free LEFT.
- **Dispatch A→B:** Opposing. sameDirectionCount = **segment only** = 2. slotsNeeded = 3. Free LEFT = 1. 1 < 3. **Result: BLOCK.** (If we had counted the cart in junction as “same direction” we’d get 3, need 4, still block.)

---

### Scenario 28
- **State:** No carts on segment. J: 1 held LEFT, 1 held RIGHT.
- **Dispatch A→B:** Opposing = B_TO_A on segment = 0. No opposing. **Result: ALLOW.**

---

### Scenario 29
- **State:** 1 B→A on segment. J 0 free LEFT, 0 free RIGHT (both sides full).
- **Dispatch A→B:** Opposing. slotsNeeded = 1. Free LEFT = 0. **Result: BLOCK.**

---

### Scenario 30
- **State:** C1 (A→B) just ENTRY at J from LEFT. C2 (B→A) is held in J RIGHT, not on segment.
- **shouldDivertJunction(C1, J, LEFT):** Way ahead = segment toward B. Opposing to A_TO_B = B_TO_A **on segment**. C2 is not on segment. So opposing list empty. Way clear. **Result: NOT_DIVERT (pass through).** C1 passes; C2 remains held until released by controller logic.

---

### Scenario 31
- **State:** Two junctions J1, J2 on A—mid—B. 1 cart B→A on segment. Dispatch A→B.
- **Dispatch A→B:** Opposing. slotsNeeded = 1. For **each** junction we need free slots for our approach. From A: J1 approach = LEFT (nodeA=A). J2: if nodeA=mid, nodeB=B, then fromNodeId A ≠ mid → approach = RIGHT. So we need 1 free LEFT at J1 and 1 free RIGHT at J2. If both have room, **Result: ALLOW.** **Gap check:** Code iterates junctions and uses j.getNodeAId().equals(fromNodeId) for approach. For a segment A–B with two junctions, findOnSegment(A, B) order matters. If J2’s nodeA is “mid”, fromNodeId A is not mid, so we demand RIGHT at J2. That is consistent with “approach from A” meaning we approach J2 from the mid side (RIGHT of J2). No gap if junction order and nodeA/nodeB are configured correctly.

---

### Scenario 32
- **State:** Empty segment and J. Destination B full.
- **Dispatch A→B:** canDispatch first checks freeSlots at toNodeId. freeSlots == 0. **Result: BLOCK.** “Destination transfer node is full.”

---

### Scenario 33
- **State:** 2 B→A on segment, 1 A→B in J LEFT (1 free LEFT).
- **Dispatch A→B:** Opposing = 2. sameDirectionCount(A→B) = 0, slotsNeeded = 1. Free LEFT = 1. 1 ≥ 1. **Result: ALLOW.**

---

### Scenario 34
- **State:** C (B→A) just ENTRY at J from RIGHT. D (A→B) held in J LEFT, not on segment.
- **shouldDivertJunction(C, J, RIGHT):** Way ahead for RIGHT = toward A. Opposing to B_TO_A = A_TO_B on segment = 0. Way clear. **Result: NOT_DIVERT (pass through).**

---

### Scenario 35
- **State:** No junction. 1 A→B, 1 B→A on segment. Dispatch A→B (second A→B).
- **Dispatch A→B:** Opposing = findOpposingCarts(A, B, "A_TO_B") = B_TO_A = 1 cart. junctions.isEmpty() → block. **Result: BLOCK.**

---

### Scenario 36
- **State:** 1 B→A on segment, J 2 free LEFT. Cart at terminal A, next hop B.
- **getNextHopNodeAtStation:** canDispatch(cart, A, B) = true (opposing, need 1, have 2 LEFT). So we return the transfer node for B (proceed to B). **Result: next hop = B.**

---

### Scenario 37
- **State:** 1 B→A on segment, J 0 free LEFT. Cart at terminal A, next hop B.
- **getNextHopNodeAtStation:** canDispatch(cart, A, B) = false. So we return an available **terminal** at A (hold at terminal, destination not cleared). **Result: next hop = terminal at A.**

---

### Scenario 38
- **State:** 1 A→B on segment. 1 B→A held in J RIGHT. Dispatch B→A.
- **Dispatch B→A:** Opposing = A_TO_B on segment = 1. sameDirectionCount(B→A) = 0, slotsNeeded = 1. Approach from B = RIGHT. Free RIGHT = 1 (one slot used by held cart). 1 ≥ 1. **Result: ALLOW.**

---

### Scenario 39
- **State:** J capacity 1 per side. No carts on segment. J 0 free LEFT, 1 free RIGHT.
- **Dispatch A→B:** Opposing = B_TO_A = 0. No opposing. **Result: ALLOW.** (Junction full on our side doesn’t block when there’s no opposing traffic.)

---

### Scenario 40
- **State:** 3 B→A on segment. J 2 free LEFT, 2 free RIGHT.
- **Dispatch A→B:** Opposing = 3. sameDirectionCount(A→B) = 0, slotsNeeded = 1. Free LEFT = 2. 2 ≥ 1. **Result: ALLOW.**

---

## Part 6: Gap check — second set

- **Scenarios 23–40:** All had a well-defined outcome under the current rules.
- **Scenario 25 / 26 / 39:** Confirm that “capacity 1 per side” is either supported in config or that the code’s capacity logic matches the scenario (e.g. if the code assumes 2 per side, scenario 25 would still block when only 1 free LEFT and we need 2).
- **Scenario 31:** Multiple junctions depend on junction order from findOnSegment and on nodeA/nodeB matching physical layout (approach side). No inconsistency found; worth testing in implementation.
- **Committed check:** findCommittedCarts uses segment (fromNodeId, toNodeId) and direction B_TO_A. For dispatch from A to B we check (A, B, B_TO_A) = opposing carts committed. For dispatch from B to A we check (B, A, B_TO_A). On (B,A), B_TO_A is A→B, so we correctly block when an A→B cart is committed. The SQL uses `zone NOT LIKE 'pre_junction_%'` and does **not** filter by lastJunctionId (the ID is passed but not in the query). So “committed” means “any opposing cart on this segment whose zone is not pre_junction”. That may over-block if we have multiple junctions and “past J1” is not the same as “past last junction J2”. **Potential gap:** If there are two junctions J1, J2, an opposing cart past J1 but not past J2 might have zone not pre_junction (e.g. between J1 and J2). We would block dispatch from A even though the cart is not past the *last* junction. So **committed-past-last-junction** may be under-specified when there are multiple junctions: the code currently treats “zone not pre_junction” as “committed”, but the rule says “committed past *last* junction”. **→ STOP FOR DISCUSSION:** Should findCommittedCarts restrict to carts that are past the *last* junction (e.g. by zone or position), or is “any non–pre_junction opposing cart” the intended behavior for multi-junction segments?
