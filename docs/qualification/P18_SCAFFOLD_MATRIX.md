# Phase 18 — Placement / Scaffold — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03). **No placement validation or scaffold detector exists, and Phase 18 is not open.** Every expected
outcome below is an acceptance target. Cells that depend on its contract say **WAITING ON the Phase 18 contract**.

Shared rules: [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: ROADMAP (Phase 18 = anticheat-interaction placement/use/break/entity-action rules, face/cursor/timing, overlay reconciliation,
and checks-owned scaffold features), COMBAT_MODEL (placement and non-attack interaction belong to anticheat-interaction and share world/collision
primitives; no duplicated geometry), MITIGATION ("retroactive inventory/placement cancellation is outside the initial design").

## 1. Two kinds of result

- **Deterministic placement rules** (interaction engine): a placement whose clicked face cannot be seen from any admissible eye position, a
  cursor position outside the clicked face, a target block beyond the block interaction range, or a placement against air with nothing to
  click. These can be DeterministicExclusions under a complete certificate, with the same eye provenance rules as combat (certified
  trajectories).
- **Scaffold features** (checks): placement rhythm, look-down patterns, sprint-bridging without sneaking, held-slot switches. These are
  behavioral windows.

Many human bridging techniques are fast and look automated. The legitimate set must include them.

## 2. Setup

- Arena: a void-edge bridging lane at y 150 (40 blocks long, with a safety floor of slime or water 10 blocks below), a towering column, a
  ceiling lane, and a placement wall with visible and hidden faces (blocks behind glass, under slabs, around corners).
- Facts to record from the 26.2 profile in the trial sheet header: `block_interaction_range` base value and the creative modifier, the
  cursor (hit vector) encoding, the block-change acknowledgement sequence semantics, the "inside block" flag.
- Testers in survival with stacks of full blocks, slabs and stairs; Resistance V and Slow Falling for fall safety.
- Trials: one bridge length (40 blocks) or 60 s, whichever is longer; 10 s gaps.

## 3. Legitimate controls

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| SC-L01 | Sneak bridging (shift at the edge), slow | M | N | 0 INVALID; no positive window |
| SC-L02 | Speed bridging (ninja: tap shift), 40 blocks | M | N | 0 INVALID; no positive window |
| SC-L03 | Breezily bridging (diagonal, no sneak) | M | N | 0 INVALID; no positive window |
| SC-L04 | God bridging and moonwalk (high placement rate, no sneak), if a tester can do it | M | N | 0 INVALID; no positive window |
| SC-L05 | Telly bridging (sprint-jump, place under while airborne) | M | N | 0 INVALID; no positive window |
| SC-L06 | Towering (jump and place under) 20 blocks | S | N | 0 INVALID; no positive window |
| SC-L07 | Placing against the side and the top of blocks at the edge of the range; against slabs, stairs, fences | S | N | 0 INVALID |
| SC-L08 | Placing on a face visible only through a gap (glass pane gap, slab window) | S | N | VALID where a clear sight line exists; never INVALID from a partial occluder |
| SC-L09 | Creative mode and `/attribute … block_interaction_range` 6.0 placements | S | N | 0 INVALID |
| SC-L10 | `scale` 0.5 and 2.0 on the builder | S/M | N | 0 INVALID |
| SC-L11 | Placement that the server rejects (protected area via a spawn-protection radius, or a full inventory edge case): ghost block and reconciliation | S | N | Overlay reconciles; 0 INVALID |
| SC-L12 | Fast hotbar switching while building (several block types) | M | N | No positive window |
| SC-L13 | Long free building sessions by remote testers on their usual clients | M | N | 0 INVALID; no positive window in at least 2 player-hours per tester |

## 4. Blatant cheat settings (tier R)

| ID | Module / setting (descriptive) | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| SC-B01 | Scaffold, sprinting, silent rotations (camera forward, packets look down) | M | N | Behavioral positive window; any face not visible from an admissible eye is a deterministic placement finding in the same group |
| SC-B02 | Scaffold with no rotation at all (places while looking forward) | M | N | Deterministic face-visibility or cursor finding expected where the certificate is complete |
| SC-B03 | Scaffold "expand" (places 2–5 blocks ahead) | M | N | Range finding where beyond the block interaction range under a complete certificate |
| SC-B04 | Tower module (fast vertical towering faster than jump physics) | S | N | Movement validation finds the ascent; placement shares the causal group |
| SC-B05 | Scaffold with a spoofed held slot (switches the slot only in packets) | M | N | WAITING ON the Phase 18 contract (held-slot/inventory semantics may be Phase 19) |
| SC-B06 | Placement through walls (face behind solid blocks) | S | N | Deterministic occlusion finding under a complete certificate |

## 5. Subtle cheat settings (tier R) — measured, no pass threshold

| ID | Module / setting | M/S | Latency | What to measure |
|---|---|---|---|---|
| SC-S01 | "Legit scaffold": real rotations, auto-sneak at edges (eagle) | M | N | Window outcomes; this mimics SC-L02 |
| SC-S02 | Scaffold with rotation smoothing and randomized delay | M | N | As above |
| SC-S03 | Automated god bridge or telly bridge presets | M | N | Compare with SC-L04 and SC-L05 |
| SC-S04 | Safewalk only (never falls off edges, otherwise legit placing) | M | N | Movement/physics outcome; whether it is detectable at all |
| SC-S05 | "Keep Y" scaffold while jumping | M | N | As above |
| SC-S06 | Anticheat-specific scaffold bypass modes (record the mode name) | M | N | Outcome per mode |

## 6. Latency-sensitive cells

Run SC-L02, SC-L05, SC-L11, SC-B01 and SC-S01 under N, D150, D300, J and S2. Block-change acknowledgements arrive late under latency, so the
client's world (the overlay) is ahead of the confirmed server world: a block the client placed a moment ago is a legal support for the next
placement even if the server has not confirmed it yet. Legitimate cells: **0 INVALID** in every profile.

## 7. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| SC-FP01 | Fast human bridging techniques (ninja, breezily, god bridge, moonwalk, telly) |
| SC-FP02 | Placing against blocks the client placed but the server has not acknowledged yet (overlay) |
| SC-FP03 | Rejected placements, ghost blocks and their reconciliation |
| SC-FP04 | Faces seen through gaps; non-full blocks (slabs, stairs, fences, panes, snow layers) |
| SC-FP05 | Range attributes, creative mode and `scale` |
| SC-FP06 | Eye height changes while sneaking at the edge (sneak toggles inside the placement tick) |
| SC-FP07 | Latency, jitter and stalls |
| SC-FP08 | Double counting with movement findings from the same transmissions (tower, safewalk) |

## 8. Expected detector behavior (acceptance targets)

- Placement rules: VALID with a witness, INVALID only under a complete certificate, otherwise UNCERTAIN with a reason. Eyes from certified
  trajectories. Face visibility, cursor-on-face, range and support are distinct reasons.
- Scaffold features: calibrated BehavioralFeatureWindow records; no automated action under the initial policy.
- Placement, scaffold-feature and movement findings from the same transmissions form one capped group.
- No retroactive placement cancellation (MITIGATION); OBSERVE_ONLY.
- **WAITING ON the Phase 18 contract:** the rule list and reason vocabulary, the overlay reconciliation model, which features are scaffold
  features, window, minimum samples and calibration, and the held-slot question (SC-B05).
- Legitimate gate (proposed): **0 INVALID and 0 positive windows** across tier L; at least 3 000 legitimate placements in total and 300 per
  latency profile, plus at least 10 player-hours of building from at least 2 distinct people for the behavioral features.
- Blatant gate (proposed): ≥ 80 % deterministic findings on SC-B02, SC-B03 and SC-B06 placements whose impossibility is independently
  proven; SC-B01 positive in ≥ 80 % of held-out trials.
- Subtle: measured and reported only.
- Always: 0 mitigation effects.

## 9. Telemetry to capture

Per placement or use (Phase 18 must expose a bounded per-interaction export):

- trial ID; capture sequence; monotonic receipt time; tick-end index;
- the use-item-on-block fields exactly as received: hand, block position, face, cursor (hit vector) components, inside-block flag, sequence;
- held slot and item at that moment, and recent held-slot changes;
- the rotation in effect and its source sequence; eye provenance (`eyeSource`), pose and sneak state from the `ServerboundPlayerInput`
  flags;
- the block-change acknowledgement for the sequence, the server's resulting block update, and the overlay state the client could have seen
  (acknowledged / pending / rejected);
- `block_interaction_range`, game mode and `scale` in effect;
- the Phase 18 result: rule status, reason, witness or certificate ID, feature-window values, sample count, suspicion value, evidence ID
  and group.

Per trial: the common commands; the screen recording for tier R.

## 10. Corpus discipline and run order

- Label before running; freeze the behavioral corpus before tuning; held-out split by session and player.
- VPS rounds run only on the exact Phase 18 candidate jar, after its offline qualification is complete.
