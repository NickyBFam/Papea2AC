# Phase 16 — Combat heuristics / KillAura — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03). **No KillAura or combat-heuristic detector exists, and Phase 16 is not open.** Every expected outcome
below is an acceptance target. Cells that depend on its contract say **WAITING ON the Phase 16 contract**.

Shared rules: [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: ROADMAP (Phase 16 = calibrated behavioral evidence with held-out benign/adversarial evaluation), EVIDENCE_MODEL
(BehavioralFeatureWindow; calibration; one causal group per root; heuristics cannot cause automated action in the initial policy),
COMBAT_MODEL ("rotation/click/KillAura consumers cannot label a legal ray impossible"), INTAVE_REFERENCE_ANALYSIS (automated combat can follow
legal protocol behavior; legal aim is not proof of a human, and suspicion is not proof of impossibility).

## 1. Dependencies

KillAura analysis consumes other phases' outputs and must not duplicate them:

- Phase 11 AttackContext (target identity, generation, completeness; `PHASE11_COMBAT_CONTRACT_READY` v1);
- Phase 12 geometry results (reach miss, angular miss, occlusion): a through-wall or out-of-range attack is Phase 12's deterministic finding,
  and Phase 16 must not count it a second time (TC20);
- Phase 15 rotation windows and Phase 17 click-timing windows.

A Phase 16 qualification round is therefore meaningful only on a jar that already contains Phases 12, 15 and 17.

## 2. Setup

- Arena: the overworld platform. Single-target pen (one NoAI husk), multi-target pen (6 husks around the player at 2.5 blocks, including two
  behind them), a moving-mob pen (cows with AI), a wall lane (target behind full blocks), and a PvP area for the second player.
- Trials last at least 60 s of active combat; 10 s gaps; kill and replace mobs between trials.
- Testers: Resistance V, Saturation.

## 3. Legitimate controls

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| KA-L01 | Single-target fight vs a NoAI husk, spam clicking | S | N | No positive window |
| KA-L02 | Mob crowd: 6+ hostile mobs (zombies with AI) around the player, switching targets quickly | M | N | No positive window |
| KA-L03 | Sword sweep attacks (one ATTACK packet, several entities damaged server-side) | S | N | No positive window; one packet stays one attack |
| KA-L04 | Mob farm style: standing and hitting mobs arriving through a hole | S | N | No positive window |
| KA-L05 | PvP: W-tap, strafing, combos, rod/bow switching, between two vanilla players | M | N, D150, J | No positive window |
| KA-L06 | Fast 180° turns to hit a player who jumped over the tester | M | N | No positive window |
| KA-L07 | Attacking while bridging or while eating/blocking (item use interleaved) | M | N | No positive window |
| KA-L08 | Fighting with low FPS and with zoom or freelook mods (remote client) | M | N | No positive window |
| KA-L09 | Team fight: 2 testers vs mobs, overlapping targets | M | N | No positive window |
| KA-L10 | Long free PvP sessions on the testers' usual clients | M | N | No positive window in at least 2 player-hours per tester |
| KA-L11 | Spear fighting: stabs at single and multiple mobs, plus charged (kinetic) hits | M | N | Phase 11 contract version 2 (P11-F19) records each stab as a target-less `CombatObservation.PiercingAttack`, counted as `stabs=`, never an `AttackContext` (the fix ships in the Phase 11 candidate jar after snapshot 2). Kinetic hits send no packet. No positive window, and stabs are never counted as targeted attacks |

## 4. Blatant cheat settings (tier R)

| ID | Module / setting (descriptive) | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| KA-B01 | Multi / switch aura, 360° field of view, hitting targets behind the player | S, M | N | Positive window within the contract's sample minimum; any out-of-range or wrong-angle attack is a Phase 12 finding in the same causal group (through-wall only once OCCLUSION is built) |
| KA-B02 | Aura with no rotations (attacks without facing) | S | N | Positive window, or a Phase 12 angular-miss finding; one group |
| KA-B03 | Aura with silent rotations (packets rotated, camera fixed) | M | N | Positive window |
| KA-B04 | Aura with a "no swing" option (attack without the arm-swing packet) | S | N | WAITING ON the Phase 16 contract: is the attack/swing pairing a protocol rule (Phase 20) or a feature here? First check the vanilla 26.2 order of ATTACK and swing on the client oracle |
| KA-B05 | Aura at 15–20 attacks/s ignoring the attack cooldown | S | N | Positive window (with Phase 17 timing) |
| KA-B06 | Aura through walls | S | N | Phase 12 has no OCCLUSION exclusion, even with the contract version 2 outline table (known gap), so expect no Phase 12 finding unless the attack also misses on REACH or ANGLE. Once OCCLUSION exists, Phase 16 adds no separate weight for it |
| KA-B07 | Aura while holding a spear (stab spam, or ATTACK packets that the server ignores while a spear is held) | S, M | N | Phase 11 contract version 2 (P11-F19) records each stab as a target-less `CombatObservation.PiercingAttack`, counted as `stabs=`, never an `AttackContext` (the fix ships in the Phase 11 candidate jar after snapshot 2). Report what is observable |

## 5. Subtle cheat settings (tier R) — measured, no pass threshold

| ID | Module / setting | M/S | Latency | What to measure |
|---|---|---|---|---|
| KA-S01 | Single-target aura, field of view 60–90°, range 3.0, smoothed rotations, 8–12 randomized attacks/s | M | N | Window outcomes; time to first positive |
| KA-S02 | Trigger bot (attacks only when the crosshair is already on a target) | M | N | As above. A trigger bot sends human aim with machine timing; report whether it is distinguishable at all |
| KA-S03 | "Legit aura" presets with reaction delay, miss chance and overshoot | M | N | As above |
| KA-S04 | Aura only when the player is attacked, or only on players | M | N | As above |
| KA-S05 | Aura with cooldown-aware timing (attacks at full charge only) | M | N | As above |
| KA-S06 | Anticheat-specific aura bypass modes (record the mode name) | M | N | Outcome per mode |

## 6. Latency-sensitive cells

Run KA-L05, KA-L10, KA-B01 and KA-S01 under N, D150, J, S2 and P1. Timing-derived features (attack spacing, reaction time to a target
appearing) must not create positives on legitimate players under jitter or after stalls. Reaction time measured from the server's send of a
target update to the attack's receipt includes RTT; the contract must say how that is handled (**WAITING ON the Phase 16 contract**).

## 7. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| KA-FP01 | Mob crowds and farms (many targets, fast switching) |
| KA-FP02 | Sweep attacks (several entities damaged from one packet) |
| KA-FP03 | Skilled PvP players: fast turns, consistent timing, W-tap |
| KA-FP04 | Targets jumping over or behind the player |
| KA-FP05 | Latency: target updates arrive late, so attacks look early or late |
| KA-FP06 | Remote client mods (freelook, zoom, hit color) and low FPS |
| KA-FP07 | Double counting with Phase 12, 15 or 17 findings from the same attacks |
| KA-FP08 | Spear stabs: target-less, so the server picks the targets; must not look like attacks without a target |

## 8. Expected detector behavior (acceptance targets)

- Output: calibrated BehavioralFeatureWindow records with sample counts and feature provenance. Deterministic geometry stays in Phase 12.
- Phase 15 emits uncalibrated rotation features only (`phase15-to-qualification-01`). Every rotation-based "positive window" gate, including
  the former P15 targets for snap, raw-float and linear aim (ROT-B01, ROT-B03, ROT-B04), belongs here. It is calibrated on the labelled
  P15 legitimate corpus (P15 §9) and evaluated on held-out sessions. ROT-S02 ("GCD fix") defeats the quantization feature by design, so
  Phase 16 must not rely on quantization alone.
- Combat heuristic, rotation, click and geometry findings from the same attacks form one capped group (EVIDENCE_MODEL general rule).
- Under the initial policy: no automated action from heuristics.
- **WAITING ON the Phase 16 contract:** features, window, minimum samples, calibration method and population, suspicion scale, the swing
  pairing question (KA-B04).
- Legitimate gate (proposed): **0 positive windows** across a held-out legitimate set of at least 10 player-hours from at least 2 distinct
  people covering every row of §3.
- Blatant gate (proposed): KA-B01, KA-B02, KA-B03 and KA-B05 positive in ≥ 80 % of 60 s held-out trials.
- Subtle: measured and reported only. KA-S02 (trigger bot) is expected to be the hardest; report it plainly.
- Always: 0 mitigation effects.

## 9. Telemetry to capture

Per attack: the Phase 11/12 per-attack fields (see [P12_REACH_MATRIX.md](P12_REACH_MATRIX.md) §9), plus:

- swing packets around the attack (sequence, receipt time, hand) and their order relative to the ATTACK;
- the target's angular position relative to the attacker's rotation at the attack (from Phase 12 geometry, never from live server state);
- the target switch count per window and the number of distinct targets per second;
- the attack cooldown progress at the attack, if the profile can derive it;
- the time since the target became visible to the client (first network update after spawn or after leaving occlusion);
- Phase 15 and Phase 17 window IDs covering the attack;
- Phase 16 window results: features, sample count, suspicion value, calibration ID, evidence ID and group.

Per trial: the common commands; `papea2ac combat <p>` every 2–3 s until a per-attack export exists; the screen recording for tier R.

## 10. Corpus discipline and run order

- Label before running; freeze before tuning; held-out split by session and player.
- VPS rounds run only on the exact Phase 16 candidate jar (containing 12, 15 and 17), after its offline qualification is complete.
