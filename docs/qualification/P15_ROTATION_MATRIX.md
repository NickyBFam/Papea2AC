# Phase 15 — Rotation analysis — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03, revision 3: folds in `phase15-to-qualification-01`, `-02` and `-03`). **No rotation analysis is
qualified.** Phase 15 is in progress on `wip/phase15` (design: `docs/PHASE_15_IMPLEMENTATION.md` there). Feature and status names below come
from that design and must be rechecked against Phase 15's published contract when it lands.

Shared rules: [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: ROADMAP (Phase 15 = bounded feature windows, legitimate sensitivity and input confounders, diagnostics; Phase 16 =
calibrated behavioral evidence), EVIDENCE_MODEL E1 (statistical windows need minimum samples, a calibration population, confounder handling
and held-out validation; a score is not a probability unless calibrated), COMBAT_MODEL ("rotation/click/KillAura consumers cannot label a
legal ray impossible").

## 1. What Phase 15 produces, and what it does not

- Phase 15 emits **uncalibrated feature windows**: features, a sensitivity-quantization fit status, the fitted unit, and confounder and
  uncertainty sets. It emits **no score, suspicion value, positive flag or verdict** (`calibrated()` is false, and an architecture test
  forbids that vocabulary in the module).
- Every "positive window" or "suspicion" gate belongs to **Phase 16**, which calibrates on Phase 15 features. See
  [P16_KILLAURA_MATRIX.md](P16_KILLAURA_MATRIX.md) §8.
- This matrix therefore qualifies the **features**: that they are correct on legitimate input, that confounders are named instead of turning
  into anomalies, and what each cheat setting does to them.
- Deterministic protocol facts (non-finite values, pitch outside the client clamp) belong to Phase 20. Phase 15 only records
  `pitchOutsideProfile` as a feature.

## 2. Phase 15 window facts (from its design)

- Window: the newest segment of the last 64 rotation-bearing packets, evaluated every 20 packets.
- Fit: at least 16 non-zero deltas. Status FIT, NO_FIT, SEARCH_INCOMPLETE, PRECISION_LIMITED or INSUFFICIENT_SAMPLES.
- Expected unit for raw mouse input: `unit = (float)(f^3 * 8) * 0.15F` with `f = s * 0.6F + 0.2F`, where `s` is the options.txt
  `mouseSensitivity` double (0–1; the UI shows 0–200 %, so 100 % = 0.5). Spyglass scope: `f^3 * 0.15` (no ×8).
- Features include `largeYawDeltas`, `exactRepeats`, `pitchOutsideProfile` and `unitContinuity` (NO_PREVIOUS / SAME / CHANGED / NOT_APPLICABLE).
- Server-applied rotations (`player_rotation`, `player_look_at`, teleports, respawn, vehicle) break the segment
  (`SEGMENT_BREAK_IN_WINDOW`). Samples stay isolated until an ACK marker written after the send is acknowledged.
- Confounders named in windows include SMOOTH_CAMERA, NON_RAW_OR_FRACTIONAL_INPUT and CONTROLLER_OR_INPUT_MOD. The server cannot observe
  them, so **every** non-FIT window lists all confounders. Whether a legitimate trial was confounded is therefore judged from the tester's
  sheet, never from the window.
- No timing features: monotonic receipt times are not used.

## 3. Setup

- Arena: the overworld platform with aim targets (NoAI husks at 3, 5 and 8 blocks, a moving cow, a strafing second player) and an open area
  for non-combat camera movement.
- **Per-tester confounder sheet** (before the first trial and whenever something changes):
  - the exact `mouseSensitivity` line from `options.txt` (full double, not only the UI percent);
  - raw input on or off, `invertMouseX`, invert Y, OS pointer acceleration;
  - mouse model and DPI;
  - client and version, zoom mods (and what they do to sensitivity), freelook or perspective mods, smooth camera use, controller mods;
  - frame-rate cap, the FPS counter reading, display refresh rate.
- Trials last at least 60 s (more than one 64-packet window); 10 s gaps.

## 4. Legitimate controls — feature-level targets

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| ROT-L01 | Free camera movement, no combat, 5 min per tester | S/M | N | FIT, with the unit matching the sheet; **0 NO_FIT windows in trials whose sheet is confounder-free** (raw input on, smooth camera off, no controller, zoom or input mod, vanilla or recorded client, no sensitivity change during the trial); SEARCH_INCOMPLETE, PRECISION_LIMITED and INSUFFICIENT_SAMPLES are allowed and counted; `pitchOutsideProfile = 0` |
| ROT-L01b | As ROT-L01 after about 50 full turns in one direction (\|yaw\| in the thousands) | S | N | PRECISION_LIMITED rather than NO_FIT |
| ROT-L02 | Aim at static targets (flicks between husks at 3/5/8 blocks) | S | N | As ROT-L01 |
| ROT-L03 | Tracking a moving cow and a strafing player | M | N, D150 | As ROT-L01 |
| ROT-L04 | Sensitivity sweep: 1 %, 50 %, 100 %, 200 %, with raw input on | S/M | N | FIT at each sheet unit |
| ROT-L04b | The same sweep with raw input off | S/M | N | FIT, or NO_FIT / SEARCH_INCOMPLETE with NON_RAW_OR_FRACTIONAL_INPUT listed. A listed confounder is the pass condition |
| ROT-L05 | Sensitivity changed mid-session (no reconnect) | S/M | N | `unitContinuity = CHANGED`, then FIT at the new unit. Report the number of windows between the change and the first FIT |
| ROT-L06 | Spyglass, and a zoom mod (Lunar or OptiFine-style), with aim while zoomed | S | N | Spyglass: FIT at the scoped unit. Zoom mod: a different unit or no fit, with confounders listed; record what the mod does |
| ROT-L07 | Smooth (cinematic) camera on | S/M | N | NO_FIT or SEARCH_INCOMPLETE with SMOOTH_CAMERA listed (pass condition) |
| ROT-L08 | Freelook / perspective mod (the camera moves without rotation packets) | S/M | N | No rotation samples while freelooking; record the window statuses |
| ROT-L09 | Controller via a controller mod; touchpad; very high DPI (≥ 3 200) | S/M | N | NO_FIT or SEARCH_INCOMPLETE with CONTROLLER_OR_INPUT_MOD listed is legitimate (pass condition); high DPI on a mouse should FIT |
| ROT-L10 | Low FPS (10–20) and unstable FPS | S/M | N | As ROT-L01 |
| ROT-L10b | Uncapped FPS (≥ 400), with the FPS counter recorded | S/M | N | As ROT-L01. This tests Phase 15's assumption of at most 20 turning frames per client tick |
| ROT-L11 | Server-applied rotations: `/rotate` (present in 26.x), `/tp @s ~ ~ ~ ~90 ~` (relative yaw), absolute `/tp` rotation, respawn facing | S | N | No delta across a server-applied rotation; `SEGMENT_BREAK_IN_WINDOW` present |
| ROT-L12 | Riding (horse, boat, minecart): vehicle turns and passenger look | M | N | Segment breaks where the vehicle applies rotation; otherwise as ROT-L01 |
| ROT-L13 | Building and bridging: repeated look-down / look-forward patterns | M | N | As ROT-L01 |
| ROT-L14 | Extreme pitch: straight up and down (±90 clamp) | S | N | `pitchOutsideProfile = 0`; values at the clamp are legal |
| ROT-L15 | Long PvP sessions by each remote tester on their usual client | M | N | As ROT-L01 over at least 2 player-hours per tester |
| ROT-C01 | Tier C: a tester turning under `/tick rate 5`, and under `/tick freeze` | S/M | N | Measured; expected uncertain. Handling waits on the Phase 14 tick-rate contract |

## 5. Blatant cheat settings (tier R) — measured feature outcomes

| ID | Module / setting (descriptive) | M/S | Latency | What to record |
|---|---|---|---|---|
| ROT-B01 | Aimbot / aura rotations with instant snap to the target | S, M | N | Fit-status distribution, `largeYawDeltas` |
| ROT-B02 | Aura with silent rotations (camera unchanged, packets rotated) | M | N | Fit-status distribution |
| ROT-B03 | Rotations with no sensitivity quantization (raw float aim) | S, M | N | Fit-status distribution. Offline synthetic: smooth aim assist gave 1 441/1 800 NO_FIT, 359 SEARCH_INCOMPLETE, 0 FIT |
| ROT-B04 | Constant-speed linear rotation toward the target | M | N | Fit-status distribution, `exactRepeats` |
| ROT-B05 | Out-of-range pitch sent by a "derp" module | S | N | `pitchOutsideProfile > 0` recorded. The deterministic protocol finding is Phase 20's |
| ROT-B06 | Spin (continuous 360° yaw at high speed) | S | N | `exactRepeats` and `largeYawDeltas` high; a single repeated delta never FITs |

## 6. Subtle cheat settings (tier R) — measured

| ID | Module / setting | M/S | Latency | What to record |
|---|---|---|---|---|
| ROT-S01 | Aim assist with smoothing and randomization (low strength) | M | N | Fit-status distribution |
| ROT-S02 | Aura rotations with a "GCD fix" / sensitivity-quantization emulation | M | N | Expected **FIT**: a known, measured bypass of the quantization feature (offline synthetic: 1 759/1 759 FIT). Report it as such |
| ROT-S03 | Rotation with jitter or noise added | M | N | Fit-status distribution |
| ROT-S04 | Aim assist only on click, or only within a small field of view | M | N | Fit-status distribution, segment by segment |
| ROT-S05 | Anticheat-specific rotation bypass modes (record the mode name) | M | N | Per mode |
| ROT-S06 | Human-like "legit aura" presets (speed curves, overshoot, reaction delay) | M | N | Per preset |

## 7. Latency-sensitive cells

Phase 15 uses no timing features, so latency cannot change a fit. Run ROT-L03 and ROT-B01 once under J and S2 as a check, and confirm that
server-applied rotations sent during a stall stay isolated until an ACK marker after them is acknowledged.

## 8. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| ROT-FP01 | Sensitivity, DPI and raw-input variety; sensitivity changed mid-session |
| ROT-FP02 | Zoom (spyglass, zoom mods), cinematic camera |
| ROT-FP03 | Freelook / perspective mods (common on Lunar) |
| ROT-FP04 | Controllers, touchpads, accessibility tools |
| ROT-FP05 | Server-applied rotations without an ACK (`player_rotation`, `player_look_at`, teleports, respawn, vehicle) |
| ROT-FP06 | Very high FPS (more float roundings per tick) and very low FPS |
| ROT-FP07 | Large accumulated yaw (float precision) |
| ROT-FP08 | Pitch at the clamp (±90) |
| ROT-FP09 | Server tick-rate changes and freeze |

## 9. Acceptance targets

- Feature correctness on tier L: the fitted unit matches the sheet in every FIT window of a confounder-free trial. **0 NO_FIT windows in trials whose sheet is confounder-free** (raw input on, smooth camera off, no controller, zoom or input mod, vanilla or recorded client, no sensitivity change during the trial); SEARCH_INCOMPLETE, PRECISION_LIMITED and INSUFFICIENT_SAMPLES are allowed and counted.
  `pitchOutsideProfile = 0` on every legitimate window.
- Confounded legitimate rows (ROT-L04b, ROT-L07, ROT-L09): the relevant confounder appears in the window's set. That is the pass
  condition, not an anomaly.
- Cheat cells are measured and reported as fit-status distributions. ROT-S02 is reported as a bypass of the quantization feature.
- Volume: at least 10 player-hours of legitimate windows from at least 2 distinct people, covering every row of §4. This corpus is also the
  Phase 16 calibration input, so it is labelled before running and split by session and player.
- Always: 0 mitigation effects and no evidence from Phase 15 alone.

## 10. Telemetry to capture

Per window (Phase 15 exposes this): capture range, segment, every feature, fit status, unit, unit uncertainty, resolution floor, and the
uncertainty and confounder sets.

Raw per-packet yaw/pitch float bits need `papea2ac recording <name> on`. Check the capsule size first (Phase 9 saw 75-byte stubs).

Per trial: the common commands; the tester's confounder sheet version; for tier R, the screen recording with the module HUD visible.

## 11. Jar and run order

- Offline first, on `wip/phase15`: `./gradlew :anticheat-testing:test --tests "*RotationAnalysisTest"` and
  `:anticheat-testing:rotationCorpusReport`.
- The live round needs Phase 15's candidate jar, which exists only after integration (after the Phase 14 receipt). It runs on that exact
  jar, after the offline qualification is complete, and never tunes it.
