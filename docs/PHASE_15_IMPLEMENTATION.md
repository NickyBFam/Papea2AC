# Phase 15 — Rotation analysis

Status: **PREPARING / independent implementation done**, uncommitted on `wip/phase15`. Base: Phase 10 receipt
`59caec5a0b4bde0c13dadca1807c0b0f3f039a7d` + phase11-snapshot-2 + phase12-snapshot-2 + phase13-snapshot-4 + phase14-snapshot-4 (hashes
verified against the handoffs; the Phase 14 merged diff applied after BOM/CRLF normalization, postimage blobs identical). Not reviewed, not approved, not baselined. Canonical integration into core waits for the
Phase 14 receipt.

## SCOPE

ROADMAP row 15: "Bounded feature windows, legitimate sensitivity/input confounders and diagnostics." This phase delivers:

- the native client's mouse-to-rotation arithmetic as a version-owned numeric profile, with bytecode provenance;
- a bounded, segmented rotation window (the "bounded feature window" PLAYER_STATE assigns to RotationState);
- a deterministic feature extractor, including a sound sensitivity-quantization fit with an explicit resolution floor;
- an uncertainty model and a confounder model that travel with every window;
- legitimate and adversarial rotation corpora, a corpus report and a cost measurement;
- the Phase 15 input to the shared real-cheat qualification matrix (as findings, per the coordinator).

Out of scope: KillAura and every target-relative aim feature (Phase 16), click timing (Phase 17), reach/ray geometry (Phase 12), deterministic
protocol validation of rotation values (Phase 20), calibration of any suspicion value (Phase 16 "calibrated behavioral evidence").

## GOVERNING DECISIONS

- EVIDENCE_MODEL E1: BehavioralFeatureWindow is its own evidence kind; statistical windows need minimum samples, a calibration population,
  confounder handling and held-out validation; a score is not a probability unless calibrated; heuristics cannot cause automated action.
- COMBAT_MODEL: rotation consumers cannot label a legal ray impossible.
- NUMERICAL_POLICY NUM1: client arithmetic reproduced with its own types and order; diagnostics may use StrictMath.
- PLAYER_STATE: RotationState holds raw and normalized angles, field presence, source sequence, current value and a bounded feature window.
- INPUT_RECONSTRUCTION I1: inputs are hypotheses; "controller/modded/translated input semantics are unverified capabilities".
- ARCHITECTURE A1 DAG: `checks` may depend on model, protocol, player, simulation, combat, interaction, evidence (not `version`).

## CLIENT MECHANICS (provenance: `javap -c -p` of the unobfuscated vanilla 26.2 client jar)

| Source | Behavior |
|---|---|
| `MouseHandler.onMove` | Accumulates cursor deltas `accumulatedDX/DY` (double) while the window is active. |
| `MouseHandler.handleAccumulatedMovement` | Once per rendered frame: if the mouse is grabbed and a player exists, `turnPlayer(frameTime)`, then zeroes the accumulators. |
| `MouseHandler.turnPlayer` | `f = sens * 0.6000000238418579 + 0.20000000298023224` (the literals `0.6F`/`0.2F` widened); `k = f*f*f`; smooth camera: `SmoothDouble` on `dx*k*8` (not quantized); first person and scoping: `dx * f^3` (no ×8); otherwise `dx * f^3 * 8`. `invertMouseX`/`invertMouseY` negate before `turn`. |
| `Entity.turn(double,double)` | `float d = (float) arg * 0.15F`; `setXRot(getXRot() + dPitch)`, `setYRot(getYRot() + dYaw)`, then `setXRot(clamp(getXRot(), -90, 90))`. |
| `Entity.setXRot` / `setYRot` | Pitch: `value % 360F` then `Math.clamp(-90, 90)`. Yaw: stored as given, **never wrapped**. Non-finite values are refused. |
| `LocalPlayer.sendPosition` | Sends raw `getYRot()/getXRot()` when `yRot - yRotLast` or `xRot - xRotLast` (float subtraction) is non-zero; then stores them as last. A standing player's 20-tick reminder is position-only. |
| `Options.sensitivity` | `OptionInstance.UnitDouble`, default 0.5 (the UI shows 0 %–200 %; 100 % is 0.5). |

Consequences used by the design:

1. The server-received per-packet float delta equals the client's own `yRot - yRotLast` bit for bit.
2. With raw mouse input every frame step is `(float)(n * k) * 0.15F` for whole counts `n`; both axes share one unit `u = (float) k * 0.15F`.
3. A packet delta is a float sum of several frame steps added to an **unwrapped** yaw accumulator, so it equals `n * u` only up to a bounded
   rounding error that grows with `|yaw|` and with the number of turning frames between two client ticks.

## OBSERVATION MODEL

`anticheat-player` `RotationHistory` (new): actor-owned, fixed 64-entry ring of `(captureSequence, yaw bits, pitch bits, segment)`;
`RotationSeries` is its immutable chronological snapshot. A **segment break** marks a rotation the mouse did not produce:

| Break | Source observation (integration mapping) |
|---|---|
| `SERVER_ROTATION` | Outbound `RotationCorrection` (player_rotation), `LookAt`, `VehicleCorrection`. These carry no client ACK, so the history is broken at send and **every** sample stays in its own segment until a Phase 7a ACK marker written after the send is acknowledged. |
| `TELEPORT_ACCEPTED` | Inbound teleport acceptance of a `TeleportSent` (absolute or relative rotation). |
| `RESPAWN`, `WORLD_CHANGE` | `Respawn`, dimension/world epoch transitions. |
| `VEHICLE_TRANSITION` | Mount/dismount of the observed player (vehicle yaw clamps and passenger turning). |
| `TRANSPORT_GAP` | Any capture gap the transport reports. |
| `OUT_OF_ORDER` | A non-increasing capture sequence (defensive; the actor never links it). |

Deltas are taken only between consecutive samples of the newest segment. Only rotation-bearing movement claims are observed
(`MovementClaim.rotation()` present); position-only packets are not rotation samples.

## FEATURES (`anticheat-checks` `RotationAnalyzer`, one per session, actor-owned)

Evaluated once per `evaluationStride` (20) new rotation observations over the newest segment of the 64-sample window:

| Feature | Meaning |
|---|---|
| samples, deltas, nonZeroDeltas | Coverage of the window |
| meanAbsYaw / maxAbsYaw, meanAbsPitch / maxAbsPitch | Turn magnitude per packet |
| largeYawDeltas | Packets with \|Δyaw\| ≥ 30° (diagnostic edge, not a threshold) |
| exactRepeats | Consecutive non-zero (Δyaw, Δpitch) pairs with identical float bits |
| yawOnlyDeltas | Non-zero pairs with Δpitch exactly 0 |
| pitchOutsideProfile | Samples the native client cannot produce (\|pitch\| > 90) |
| pitchAtClamp | Samples exactly at ±90 (legal) |
| maxAbsYawValue | Largest \|yaw\| (drives float precision) |
| fit | Sensitivity-quantization fit (below) |
| unitContinuity | Fitted unit versus the session's previous fitted unit: NO_PREVIOUS, SAME, CHANGED, NOT_APPLICABLE |

No timing feature is used: rotation values are compared by capture order only, so latency and jitter cannot change a feature.

## SENSITIVITY QUANTIZATION (`QuantizationFit`)

Per-delta error bound for magnitude `a`, endpoint magnitude `Y`, candidate unit `u`, frames bound `F`:
`B = F·ulp(Y) + (a + F·u)·2^-22 + ulp(a)` (one accumulator rounding per frame with overshoot headroom, two float roundings per frame step,
the packet subtraction).

- **Resolution floor:** `16 × (16th smallest B)`. Units below it cannot be told apart from noise (a random delta lands inside a ±u/16 window
  with probability ≤ 1/8) and are not examined. The searched domain is `[max(minUnit, floor), maxUnit]` of the profile.
- **Candidates:** `seed / m`, `m = 1..128`, from the two smallest *precise* deltas, largest unit first.
- **Interval narrowing:** each candidate is an interval; placing a delta at `N` counts intersects it with `[(a−B)/N, (a+B)/N]`. A delta is
  placeable only while `B + N·e ≤ u/16` (e = current half-width), up to four passes.
- **FIT** needs ≥ 16 placed deltas, ≥ 3 distinct counts among the explained ones (one repeated delta is a multiple of each of its divisors),
  and ≥ 90 % explained. **NO_FIT** is claimed only for a complete domain in which every candidate was testable; otherwise
  **SEARCH_INCOMPLETE**. **PRECISION_LIMITED** when the floor exceeds every profile unit; **INSUFFICIENT_SAMPLES** below 16 non-zero deltas.
- Soundness property tested on the corpus: a legitimate raw-input unit inside the searched domain is never reported as NO_FIT.

A fit is a feature, never a verdict. A GCD-aware aim assist fits perfectly (measured below); smooth camera, fractional input, controllers,
sensitivity changes and scoping transitions legitimately fail to fit.

## UNCERTAINTY MODEL

Every `RotationAnalysis` carries `uncertainty` (why it describes less than it could) and `confounders` (legitimate mechanisms that explain the
shape), both EnumSets for deterministic order.

| Uncertainty | When |
|---|---|
| INSUFFICIENT_SAMPLES | < 16 non-zero deltas in the used segment |
| SEGMENT_BREAK_IN_WINDOW | The window spans a break; only the newest segment was used |
| PRECISION_LIMITED | Floor above the profile range, deltas left unplaced in a FIT, or a NO_FIT whose floor is above the minimum unit |
| SEARCH_INCOMPLETE | No unit, but the search could not reach or test every legitimate unit |
| PITCH_CLAMP_EXCLUDED | Pitch deltas touching ±90 were excluded (the clamp truncates whole counts) |
| TICK_RATE_UNRESOLVED | The Phase 14 tick-rate signal is incomplete; the frames bound is unbounded, so no fit is attempted (PRECISION_LIMITED) |
| TICK_RATE_CHANGED_IN_WINDOW | The window starts before the latest ticking-state change; no fit is attempted |
| PROFILE_UNVERIFIED | The session's client is not the qualified native profile |

Confounders (SMOOTH_CAMERA, NON_RAW_OR_FRACTIONAL_INPUT, SENSITIVITY_CHANGED, SCOPING_TRANSITION, CONTROLLER_OR_INPUT_MOD,
NON_NATIVE_CLIENT) are all listed whenever the fit is not FIT: the server cannot observe smooth camera, raw-input state, the options screen,
controller mods or client brand reliably, so the feature must not pretend to exclude them.

`RotationAnalysis.calibrated()` is `false`. No calibration population, held-out validation or reviewed policy exists, so the violation policy
must give rotation windows zero punitive weight. There is no INVALID, score, confidence or probability anywhere in the module (enforced by
`ArchitectureTest.rotationAnalysisStaysWithinThePhase15Boundary`).

## BOUNDS AND MEMORY

- Window: 64 samples × (8 + 4 + 4 + 4) bytes = 1 280 bytes of arrays plus ~64 bytes of counters per session; fixed, never grows.
- Analyzer state: two scalars per session.
- One evaluation: one snapshot (four arrays ≤ 64), at most 2 × 128 = 256 candidates × 4 passes × ≤ 126 deltas, with early abandonment.
- Evaluation cadence: once per 20 rotation observations (≈ 1 s of continuous turning at 20 Hz); idle players cost nothing.

## PERFORMANCE PLAN

Measured (`rotationCorpusReport`, one thread, JIT, median of 2 000 evaluations, not JMH): **5.3 µs** per full-window evaluation on a
legitimate window, **3.1 µs** on a smooth-aim (no-fit) window. At one evaluation per player-second this is ≈ 5 ms of CPU per second for 1 000
continuously turning players. Remaining work before qualification: a JMH benchmark in `src/benchmark` (allocation rate per evaluation, worst
case at 256 candidates), and inclusion in the Phase 24 load matrix.

## CORPORA AND MEASURED RESULTS

`RotationCorpus` (testing): a seeded model of the 26.2 client (frame turning through `RotationKinematics`, `SmoothDouble` transcribed from
bytecode, scoping, inversion, fractional input, per-tick send rule) with synthetic human motion, plus seven synthetic automation shapes.
Synthetic motion exercises the arithmetic; it is not evidence about real players. Real players and real cheat clients come from the shared
VPS matrix (`Papea2AC-coordination/qualification/P15_ROTATION_MATRIX.md`).

`docs/phase15-artifacts/rotation-corpus-report.json` (30 streams × 1 200 ticks per scenario, sensitivities 0–1):

| Scenario | Windows | FIT | NO_FIT | SEARCH_INCOMPLETE | PRECISION_LIMITED | Notes |
|---|---|---|---|---|---|---|
| legit raw | 1 616 | 1 614 | 0 | 2 | 0 | every FIT is the true unit |
| legit smooth camera | 1 795 | 0 | 172 | 1 623 | 0 | confounders listed |
| legit fractional input | 1 800 | 0 | 178 | 1 622 | 0 | confounders listed |
| legit scoping | 1 640 | 1 298 | 0 | 342 | 0 | FIT = scoped unit |
| legit yaw ≈ 20 000 | 1 616 | 1 596 | 0 | 4 | 16 | |
| legit pitch clamp | 1 568 | 1 332 | 0 | 236 | 0 | |
| legit sensitivity change | 1 616 | 1 577 | 1 | 38 | 0 | 33 CHANGED; 8 straddling windows fit a common divisor |
| legit teleport | 1 620 | 1 608 | 0 | 7 | 0 | 5 insufficient after the break |
| adv smooth aim assist | 1 800 | 0 | 1 441 | 359 | 0 | |
| adv GCD-aware aim assist | 1 759 | **1 759** | 0 | 0 | 0 | known bypass of the quantization feature |
| adv snap aim | 230 | 0 | 201 | 29 | 0 | 226 with large deltas |
| adv spin 45°/tick | 1 800 | 0 | 0 | 600 | 1 200 | 1 800 with exact repeats |
| adv pitch out of range | 1 787 | 1 787 | 0 | 0 | 0 | 1 787 with pitchOutsideProfile |
| adv random noise | 1 800 | 0 | 134 | 1 666 | 0 | |
| adv yaw-only assist | 1 800 | 0 | 9 | 1 791 | 0 | |

Reading: NO_FIT appears on legitimate confounded input (smooth camera, fractional input, a sensitivity change) and never on legitimate raw
input; it separates nothing on its own. These are shape statistics on synthetic streams, not detection rates.

## TESTS

`RotationAnalysisTest` (21): exact copy of the profile into the kinematics; client types and order (including that `n` counts are not always
`n` units in float); pitch clamp/`% 360` and unwrapped yaw; bounded/segmented/ordered history; no delta across a teleport; legitimate raw
input over sensitivities 0–1 (never NO_FIT inside the searched domain, fitted unit equals the true unit, stable continuity); inversion;
scoping; pitch clamp exclusion; 60 frames per tick never NO_FIT; large yaw → precision-limited, never NO_FIT; sensitivity change → CHANGED; smooth camera and fractional input
→ confounders listed; seven adversarial shapes produce their features and never carry weight; determinism; 200 random fuzz runs with
extreme floats never throw and stay bounded; empty history. `ArchitectureTest` gains the Phase 15 boundary and drops `checks` from the
must-be-empty list.

## INTEGRATION PLAN (waits for the Phase 14 receipt)

1. Core composition: copy `RotationProfile.NATIVE_26_2` into `RotationKinematics`; `profileVerified` from the session's protocol profile.
2. Session actor: feed `RotationHistory.observe` from rotation-bearing `MovementClaim`s at their capture sequence; map the break table above;
   run `RotationAnalyzer.offer` after each observation.
3. Evidence: map each window to a BehavioralFeatureWindow record with zero punitive weight (evidence-local mapper), root = the window's
   capture range.
4. Replay: `RotationSeries` floats are already raw bits; add a rotation section to the player snapshot DTO.
5. Diagnostics: a bounded `/papea2ac rotation <player>` view and the per-window export the qualification matrix asks for (§9 telemetry).
6. Tick rate (consumes `PHASE14_TIMER_CONTRACT_READY` v2, `phase14-snapshot-4.tar` SHA-256 `e0231884…` verified): `core.RotationTickConditionMapper` maps
   `TickCadenceRunner.tickRateSignal()` to `ClientTickCondition` - `resolved = complete`, `maxTickPeriodNanos` = max of
   `TickCadenceProfile.clientTickPeriod(rate, frozen).maxNanos()` over the candidates, `stableSinceSequence = changeSequence`. The analyzer scales
   the frames bound by `period / 50 ms` (`/tick rate 5` -> 80, rate 1 -> 400; frozen ticks every 50 ms, `/tick step` uses the running
   period, both covered by the candidate max). Incomplete signal -> TICK_RATE_UNRESOLVED; window starting before the latest change ->
   TICK_RATE_CHANGED_IN_WINDOW; both skip the fit. The signal is read while handling the rotation observation, which the contract states is
   the correct view for an inbound packet. Phase 15 does not apply the Phase 14 snapshot to its own tree; the mapping is wired at integration.

## KNOWN LIMITATIONS

- `F = 20` turning frames per client tick is an assumption. Measured offline at 60 frames per tick (≈ 1 200 FPS): 427 in-domain legitimate windows, 0 NO_FIT (the bound is worst-case; real rounding errors are far smaller). Live confirmation is the matrix cell ROT-L10b.
- Smooth camera, fractional/OS-accelerated input, controllers and zoom mods are indistinguishable from smooth automation by quantization.
- GCD-aware automation defeats the quantization feature completely; other features (and Phase 16) must carry it.
- No real-player or real-cheat data yet; all numbers above are synthetic.
- Sensitivity values outside 0–1 (hand-edited options) are assumed impossible because the option is a `UnitDouble`; this is not
  independently verified against the codec and is a qualification check.

## VERIFICATION (this working tree)

| Step | Result |
|---|---|
| `gradlew --offline test` on the full stack (Temurin 25.0.4.1+1) | **949 tests, 0 failures** |
| `gradlew --offline test` on 59caec5 alone (earlier) | 783 tests, 0 failures |
| `RotationAnalysisTest` (21, incl. the Phase 14 signal mapping) + `ArchitectureTest` (18) on the stack | green; high-FPS 427 windows / 0 NO_FIT; `/tick rate 5` with the scaled bound 399 / 0 NO_FIT; raw corpus 1 283 / 1 283 FIT |
| `rotationCorpusReport` | `docs/phase15-artifacts/rotation-corpus-report.json` |
| nativeTest, pluginJar, JMH | not run (no integration yet) |
