# Phase 17 — Click analysis / AutoClicker — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03). **No click analysis exists, and Phase 17 is not open.** Phase 11 records attack timing facts only
and classifies nothing. Every expected outcome below is an acceptance target. Cells that depend on its contract say **WAITING ON the
Phase 17 contract**.

Shared rules: [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: ROADMAP (Phase 17 = protocol action timing separated from physical-click inference; **no unsupported CPS-only guilt**),
EVIDENCE_MODEL (behavioral windows, calibration, no automated action from heuristics), Phase 11 spec §22 (cadence is recorded, never
classified there).

## 1. What the server can and cannot see

- The server never sees a mouse click. It sees protocol actions: ATTACK, the arm swing, item use (use item, use item on block, use on
  entity), block dig start/abort/finish, held-slot changes. One physical click can produce several of these, or none.
- Facts to establish on the vanilla 26.2 client oracle before Phase 17 relies on them, recorded in the trial sheet header:
  - whether a click on air sends a swing (a "miss");
  - whether several clicks inside one client tick each produce an action;
  - that holding attack does not repeat attacks on an entity;
  - that holding use repeats on a fixed tick delay (a legitimate machine-regular rhythm);
  - that mining sends repeated swings while the key is held (a legitimate high-rate swing stream).
- Arrival times include network jitter and batching. Inter-click intervals measured at the server are a noisy view of client-tick-aligned
  actions, and tick-end alignment (Phase 7) is the better clock.

## 2. Setup

- Arena: the overworld platform; a NoAI husk pen; a block wall for mining; open air for air clicking; a bridging lane for right-click placing.
- **Per-tester input sheet:** mouse model, switch type, debounce setting if known, clicking styles the tester uses (normal, jitter, butterfly,
  drag), and any mouse-software macro feature (must be **off** for tier L).
- Trials last at least 60 s; 10 s gaps.

## 3. Legitimate controls

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| CL-L01 | Normal clicking on a husk, 5–8 CPS | S | N | No positive window |
| CL-L02 | Jitter clicking, 10–14 CPS | S/M | N | No positive window |
| CL-L03 | Butterfly clicking, 15–25 CPS | S/M | N | No positive window |
| CL-L04 | Drag clicking (with a mouse that supports it), 20–40 CPS bursts | S | N | No positive window. Very high legitimate CPS is the main reason CPS alone cannot be guilt |
| CL-L05 | A mouse with switch double-click wear, or a low debounce setting | S | N | No positive window |
| CL-L06 | Holding use: eating, drawing a bow, placing blocks while held (fixed repeat delay) | S/M | N | No positive window (machine-regular rhythm by design) |
| CL-L07 | Mining while holding attack (repeated swings) | S | N | No positive window |
| CL-L08 | Air clicking (no target), and clicking while switching hotbar slots | S | N | No positive window |
| CL-L09 | PvP combos on the testers' usual clients, 2 player-hours per tester | M | N | No positive window |
| CL-L10 | Low FPS (10–20): clicks bunched into fewer ticks | S | N | No positive window |
| CL-L11 | Spear stab clicking (each click sends a STAB player action and a swing, never an ATTACK) | S | N | Phase 11 contract version 2 (P11-F19) records each stab as a target-less `CombatObservation.PiercingAttack`, counted as `stabs=`, never an `AttackContext` (the fix ships in the Phase 11 candidate jar after snapshot 2). On earlier jars a stab is captured as a dig. Stabs are a separate action kind in the click stream. No positive window |

## 4. Blatant cheat settings (tier R)

| ID | Module / setting (descriptive) | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| CL-B01 | AutoClicker, left, 20 CPS, no randomization | S | N | Positive window only from features beyond rate (regularity, tick alignment); CPS alone is not guilt |
| CL-B02 | AutoClicker, left, 15 CPS fixed interval | S, M | N | As CL-B01 |
| CL-B03 | AutoClicker, left, 50+ CPS | S | N | As CL-B01; whether an implausible rate can be a protocol-level finding is WAITING ON the Phase 17 contract |
| CL-B04 | Right-click AutoClicker (placing/using) at 20 CPS | S | N | Positive window, distinguished from CL-L06 holding use |
| CL-B05 | AutoClicker while holding a spear (stab spam) | S | N | Phase 11 contract version 2 (P11-F19) records each stab as a target-less `CombatObservation.PiercingAttack`, counted as `stabs=`, never an `AttackContext` (the fix ships in the Phase 11 candidate jar after snapshot 2). Measure the `stabs=` rate |

## 5. Subtle cheat settings (tier R) — measured, no pass threshold

| ID | Module / setting | M/S | Latency | What to measure |
|---|---|---|---|---|
| CL-S01 | AutoClicker 9–13 CPS with Gaussian or uniform randomization | S, M | N | Window outcomes; time to first positive |
| CL-S02 | Jitter or butterfly emulation presets | S, M | N | As above |
| CL-S03 | "Only while holding the button" / "only on entities" | M | N | As above |
| CL-S04 | Cooldown-aware clicking (one click at full attack charge) | M | N | As above |
| CL-S05 | Mouse-software macro (hardware-level, outside the game) | S | N | Not a client cheat module and possibly indistinguishable. Whether it counts as cheating is the owner's policy, not this matrix's; label it separately |
| CL-S06 | Anticheat-specific clicker bypass modes (record the mode name) | S, M | N | Outcome per mode |

## 6. Latency-sensitive cells

Run CL-L02, CL-L03, CL-L09, CL-B02 and CL-S01 under N, J, S2 and P1. Jitter and stalls distort inter-arrival times; features must be measured
against tick-end alignment where available. **0 positive windows** on legitimate cells in every profile.

## 7. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| CL-FP01 | Drag and butterfly clicking (very high legitimate CPS) |
| CL-FP02 | Faulty or low-debounce mouse switches (double clicks) |
| CL-FP03 | Holding use: fixed repeat delay looks machine-regular |
| CL-FP04 | Mining swing streams |
| CL-FP05 | Network jitter and batching distorting intervals |
| CL-FP06 | Low FPS bunching several clicks into one tick |
| CL-FP07 | Double counting with Phase 16 combat windows from the same attacks |

## 8. Expected detector behavior (acceptance targets)

- Protocol action timing is reported separately from any physical-click inference (ROADMAP). **No finding from CPS alone.**
- Output: calibrated BehavioralFeatureWindow records; no automated action under the initial policy.
- **WAITING ON the Phase 17 contract:** the action stream used (attacks, swings, uses), the clock (receipt time versus tick-end index), the
  features, window, minimum samples, calibration, and any protocol-level bound (CL-B03).
- Legitimate gate (proposed): **0 positive windows** across a held-out legitimate set of at least 10 player-hours from at least 2 distinct
  people, covering every clicking style in §3 that the testers can do.
- Blatant gate (proposed): CL-B01, CL-B02 and CL-B04 positive in ≥ 80 % of 60 s held-out trials.
- Subtle: measured and reported only.
- Always: 0 mitigation effects.

## 9. Telemetry to capture

Per action (Phase 17 must expose a bounded per-window export with optional raw action timing for the qualification round):

- trial ID; capture sequence; monotonic receipt time; nearest client tick-end index;
- action kind: ATTACK (with the Phase 11 context ID), spear STAB (target-less `PiercingAttack`, `stabs=`), swing (hand), use item, use item on block, use on entity, dig start/abort/finish,
  held-slot change;
- whether the action was a miss (swing without ATTACK), a hit, or a held repeat;
- per window: action counts per kind, the interval distribution in ticks and in nanoseconds, and the Phase 17 features, sample count,
  suspicion value, calibration ID and evidence ID.

Per trial: the common commands; the tester's input sheet version; the screen recording for tier R.

## 10. Corpus discipline and run order

- Label before running; freeze before tuning; held-out split by session and player.
- VPS rounds run only on the exact Phase 17 candidate jar, after its offline qualification is complete.
