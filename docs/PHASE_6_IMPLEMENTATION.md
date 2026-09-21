# Phase 6 — Input Reconstruction — implementation report

Status: **READY FOR HUMAN REVIEW** (implementation engineer: Claude; no self-approval; no baseline created; Phase 7 not started; one documented Phase 5 forward correction, P6-F1, §30).
Parent: Phase 5 receipt `8494857bbefd071b50f3d88fb1a3f925b0971c39` (Phase 5 baseline `03a538c4496df92df16b7e0ce503bb61750847ae`,
manifest composite `776d5296…`). Governing decisions: INPUT_RECONSTRUCTION.md I1 ("inputs are hypotheses; never claim that
inferred keys were actually pressed"; reported input tried first as an untrusted witness; "Learnable ordering may rank candidates
but never remove admissible classes"), MOVEMENT_SIMULATION.md M1/M2/M3 (concrete witness lane + sound envelope lane; 256 live
nodes / 4096 step evaluations / at most three inferred steps; AbstractState as a finite union of tagged boxes with
`step(gamma(A)) ⊆ gamma(T#(A))`; "INVALID is not enabled even for the pilot"), NUMERICAL_POLICY NUM1 (outward rounding, no
transcendental Math), ARCHITECTURE.md DAG (`simulation` owns "Witness search, interval/constraint solver, envelope and result
mapper"), LATENCY_COMPENSATION.md N1/N2 (no ping rewind; application timing is caller-supplied; ≤ 8 branches), PERFORMANCE.md
"Solver" row, THREADING_MODEL T1.

## 0. Test counts (project convention: separate counts, never an aggregate alone)

| Suite | Phase 5 | Phase 6 | Delta | Notes |
|---|---|---|---|---|
| `test` | 484 | **531** | +47 | 59 classes; new: `InputDomainTest` (4), `TickObservationTest` (3), `InputReconstructorTest` (19), `AbstractTransferTest` (4), `ReconstructionRoundTripTest` (1, 1 200 cases), `MultiTickReconstructionTest` (2), `NativeTrajectoryReconstructionTest` (1, 3 105 ticks), `ReconstructionPropertyTest` (4), `ReconstructionFixtureTest` (1, 24 cases), `ReconstructionTracerTest` (7; three added from real-client findings, §26/§30) (4), `ArchitectureTest` +1 (simulation boundary) |
| `nativeTest` | 132 | **132** | 0 | unchanged (the native lane's Phase 6 pass is inside `physicsNativeDifferential`, see §25) |
| unique total | 616 | **663** | +47 | |
| native reconstruction (process lane) | — | **3 105/3 105 ticks: declared input an EXACT witness, 0 false exclusions** | | against the server's own per-tick states (`native-reconstruction-oracle-results.json`) and, offline, against the digest-verified chain (`native-reconstruction-results.json`); re-run after P6-F1 (§30) with the same result |
| physics native differential (re-run after P6-F1) | 88/88, 3 105/3 105, drift 0.0 | **88/88, 3 105/3 105, drift 0.0** | 0 | `physicsNativeDifferential` now writes `docs/phase6-artifacts/` (the P6-F1 re-pin); 81/88 pinned trajectory digests byte-identical to the frozen Phase 5 record, the 7 that changed are exactly the diagonal-input scenarios (§30) |
| Phase 1 semantic coverage | 127/127 | **127/127** | 0 | `verifyMandatoryCoverage` |
| other gates | — | `reconstructionFixtures`, `reconstructionDistribution` (fails on any false exclusion), `reconstructionMemoryHarness`, `benchmarkReconstruction` | | Gradle tasks in `anticheat-testing` |

## 1. SCOPE

Phase 6 delivers `ReconstructionResult reconstruct(ReconstructionRequest)` in `anticheat-simulation`: given a supported start
frontier (concrete states, or M3 envelopes), the caller's tick-count/alignment hypothesis (1, 2 or 3 client ticks) with one
observation set per tick, the shared Phase 5 environment, known input dimensions and caller-provided external-motion branches,
it returns every LEGAL raw input history that could explain the observed transition, with ambiguity, residuals, coverage,
work accounting and provenance. Phase 5 is consumed unchanged as the forward model (one additive accessor and one shared
kernel, §2). Not delivered, by design: any verdict (VALID/INVALID/CHEATING), violation, alert, setback or punishment
(Phase 8); latency, acknowledgement, velocity-application or teleport timing resolution (Phase 7); fluid, elytra, flight,
vehicle or swimming reconstruction (Phase 5 deferrals, reported as such).

## 2. MODULE OWNERSHIP (approved DAG, no new module, no new edge)

| Role | Module | Content |
|---|---|---|
| INPUT RECONSTRUCTION OWNER | `anticheat-simulation` | `InputDomain`, `TickObservation`, `ReconstructionRequest`, `ReconstructionResult`, `InputReconstructor`, `Interval`, `AbstractState`, `AbstractTransfer` |
| INPUT (physics) | `anticheat-physics` | Phase 5 forward model with one additive accessor, `PhysicsSimulator.resolveAttributes(env, sprinting)` (exposes the attribute values the step already resolves, so the abstract lane never re-implements the folding), `inputVector` made public, and ONE documented semantic correction, **P6-F1** (§30): the client input pipeline `PhysicsKernels.clientMoveVector(forward, strafe, movingSlowly, usingItem, sneakingSpeed)` (float `Vec2.normalized()`, `0.98F`, item-use `0.2F`, sneaking speed, square-movement rescale) replaces the per-axis impulse; the simulator and the abstract lane both call it (one authoritative implementation, `ArchitectureTest`). Straight keys are bit-identical to before; diagonals differ by ~1e-8 block/tick and now match the real client. Native differential re-run after the fix: 88/88 scenarios, 3 105/3 105 ticks bit-exact, drift 0.0 |
| INPUT (version) | `anticheat-version` | forward addition `MovementObservationProfile` (`paper-26.2-124-movement-observation`: position send threshold `Mth.square(2.0E-4)`, 20-tick reminder); P6-F1 constants `MovementProfile.VEC2_NORMALIZE_EPSILON` (`1.0E-4F`) and `SQUARE_MOVEMENT_CAP` (`1.0F`) with the documented client pipeline |
| INPUT (world/collision/model) | `anticheat-world`, `anticheat-collision`, `anticheat-model` | unchanged (views, `CollisionEngine.query` for the envelope region proof, `BlockBehaviorQuery`, `Aabb`) |
| OUTPUT | `anticheat-core` | `PacketEngine.reconstruction` / `reconstructionTracer`, `reconstructionEngineStatus()`, `reconstructArm/Report/Cancel`, `ReconstructionTracer`, `ReconstructionEngineStatus` |
| OUTPUT (display only) | `anticheat-platform-paper` | `/papea2ac reconstruct <player> [arm [declared] \| report \| cancel]` and a `status` line; no simulation edge (primitives via core) |
| DEPENDENCY DIRECTION | simulation → {model, version, world, collision, physics} (declared: also player, replay-schema, unused); core → simulation; platform-paper ↛ simulation; **physics ↛ simulation** | `ArchitectureTest` 10/10: `simulationModuleStaysWithinThePhase6Boundary` (no VALID/INVALID/cheat/violation/alert/setback/punish, no ping/RTT/latency/ACK/rewind/beam, no Bukkit/NMS, no reflection, no wall clock/randomness, no `HashMap`/`HashSet`, no transcendental Math, and the abstract lane calls `PhysicsKernels.clientMoveVector` / `simulator.inputVector` rather than duplicating constants) |

## 3. PHASE BOUNDARY

The reconstructor enumerates legal inputs, runs Phase 5 for explicit candidates, compares predictions with an observation
set, keeps every candidate that satisfies the contract, and reports ambiguity, emptiness and incompleteness. An empty
candidate set is `NO_MODELLED_CANDIDATE` — "no candidate in the currently modelled and qualified reconstruction domain
explains this transition" — never INVALID (the type has no such value). It never chooses an external-motion branch, never
infers tick alignment from arrival time (`TIMING_UNRESOLVED` beyond the caller's hypothesis), never consumes velocity,
never resolves teleports (`DISCONTINUITY`), never ranks by residual, never drops a legal state to save work (`SEARCH_LIMIT`
with `complete=false` instead). The diagnostic tracer checks a DECLARED input for membership only.

## 4. RECONSTRUCTION REQUEST (`ReconstructionRequest`, immutable)

`frontier` (≥ 1 `ConcreteStart(state, label)` or `AbstractStart(region, label)`, ≤ 256), `ticks` (1..3 `TickObservation`s),
`environment` (one shared `PhysicsEnvironment`: one world view per request, §14), `worldEpoch`, `discontinuity`,
`constraints` (`InputDomain.Constraints`), `assumptions` + `previousSprinting` (opt-in, §7), `witnessProposal`
(the client-reported input, tried first), `branches` (≤ 8 `ExternalMotionBranch`: per-tick SET/ADD/UNRESOLVED lists),
`budget` (`SearchBudget`: 256 nodes, 4 096 evaluations, ≤ 3 ticks, 256 materialized histories per node, 64 reported
candidates, 4 096 envelope cells per scan), `envelopePruning`, `observationProfile`. Every field is caller-supplied; nothing
is read from a live server or a clock.

## 5. RESULT MODEL (`ReconstructionResult`, immutable)

`status` (most severe first): NUMERIC_INVALID, WORLD_EPOCH_MISMATCH, DISCONTINUITY, UNAVAILABLE_WORLD, TIMING_UNRESOLVED,
ROTATION_UNRESOLVED, SEARCH_LIMIT, QUERY_LIMIT, UNSUPPORTED_CONTEXT, UNSUPPORTED_FLUID, UNRESOLVED_EXTERNAL_MOTION,
UNKNOWN_ATTRIBUTE, UNKNOWN_EFFECT, UNKNOWN_EQUIPMENT, UNKNOWN_ENTITY_NEIGHBORHOOD, UNKNOWN_WORLD_BORDER, INCOMPLETE_WORLD,
NO_MODELLED_CANDIDATE, ENVELOPE_ONLY, EXACT_AMBIGUOUS, EXACT_UNIQUE. `complete` is true exactly for the last four.
`ambiguity`: NONE / UNIQUE / EQUIVALENT_INPUTS (several raw histories, one bit-identical predicted state) / DISTINCT_STATES.
`coverage`: seven independent booleans (epoch & continuity, timing, rotation, environment, physics support, world, search)
plus ≤ 32 reasons — input ambiguity, missing-world ambiguity and timing ambiguity are never merged into one flag.
`candidates`: ≤ 64 `Candidate`s, each with its branch, start label, raw input histories (≤ 256 materialized + exact count +
per-tick raw-ordinal masks), the predicted `PhysicsState` (or an envelope), `Match` (EXACT_WITNESS / POSSIBLE_INCOMPLETE /
ENVELOPE_POSSIBLE), the residual against the last observation, the physics status and reasons of the last step, and whether it
was the reported input. `counts` (raw per tick, enumerated, simulated, excluded exact, excluded by envelope, undecided,
matching states/histories, unexplored), `work` (evaluations, max live nodes, merges, drops, physics query/cell/inside/entity
sums, envelope transfers/cells), `provenance` (world epoch/revision/sequence, observation sequences, tick count, movement /
observation / collision profile ids), per-branch summaries, ≤ 16 notes. `frontier()` returns the exact-witness states as the
next request's starts; `admits(history)` / `witnesses(history)` answer membership.

## 6. INPUT DOMAIN (`InputDomain`)

The Phase 5 `MovementInput` domain, nothing invented: forward, strafe ∈ {−1, 0, +1}, jump, shift (sneak key), sprinting (the
entity flag the tick reads), usingItem — **144 raw tuples**, exactly M2's "144-state calculation". Verified against the
pinned jar: the server-side `Input` record carries seven booleans and the server accepts all 128 bit patterns; opposing keys
cancel to 0; no analog values (`MovementInput` rejects them). Canonical enumeration order (documented, never a hash order):
lexicographic forward (−1, 0, +1) › strafe (−1, 0, +1) › jump › shift › sprinting › usingItem (false before true);
`ordinal()` is that rank; the reported input, if any, is moved to the front. `input-domain.json` lists the 144 tuples with
ordinals, class representatives and the assumption table. Yaw is not enumerated: it is an observed rotation (§9).

## 7. CLIENT-LEGAL CONSTRAINTS and STATEFUL INPUT

Nothing is removed from the raw domain by default. The legality rules that would shrink it (sprint start needing a forward
impulse, item use ending a sprint, ...) live in client-only code (`LocalPlayer`) that is not in the pinned server jar and
therefore cannot be verified by the controlled extraction; they are listed in `InputDomain.ClientAssumption`, each with its
source, `verified=false`, and a stateful prerequisite (`previousSprinting`, taken from the previous tick's candidate history
or the caller). A caller opts in explicitly; the reconstructor never applies them on its own, and an unknown prerequisite
never rejects (`InputDomainTest.clientAssumptionsAreUnverifiedOptInAndNeverAppliedByDefault`). Genuinely stateful behaviour
that the PHYSICS carries (noJumpDelay, pose, crouch fit, onGround, fall distance, stuck multiplier) is part of the
`PhysicsState` the search propagates, so a context-free key tuple is never assumed: two histories reaching different
states (e.g. jump key held vs released in mid-air, standing vs crouching pose) are different nodes.

Known dimensions (§8 of the brief): `Constraints` pins any dimension; only unknown dimensions are enumerated
(`admittedCount()` = the product of the free ones; a known sprint flag and strafe leave 24 tuples).

## 8. INPUT EQUIVALENCE and CANONICALIZATION

The only context-free equivalence is `forward == strafe == 0 ⇒ usingItem irrelevant` (`0 * 0.2F == 0`), giving 136 classes;
it is used for documentation and counts, not to skip simulation. Every other merge is done AFTER simulation on bit-identical
complete states (`PhysicsState` record equality: −0.0 and 0.0 are distinct states; a node is one physical outcome). A merged
node keeps every raw history (≤ 256 materialized, the exact count always, and per-tick raw-ordinal masks), so ambiguity is
never erased: `standingStillIsAmbiguousBetweenPoseStatesAndEquivalentKeys` (2 states, 8 histories),
`mergedNodesCarryExactlyTheRawInputsThatProduceTheirState` (independent oracle over all 144 inputs per random case).

## 9. OBSERVATION CONTRACT (`TickObservation`)

What constrains a reconstruction, and nothing else: the feet **position** (`Exact` — a transmitted position, numeric equality;
`WithinSendThreshold` — the tick produced no position packet; `Unobserved` — a declared gap), the **yaw** the tick applied
(the packet's rotation when carried, else the last transmitted yaw; `yawKnown=false` → `ROTATION_UNRESOLVED`, never a guessed
aim), and the **onGround claim** as an observation only: it is never a matching constraint (COLLISION_ENGINE.md), the candidate
residual reports agreement (`groundClaimIsReportedNeverUsedAsAConstraint`). Pitch, the horizontal-collision flag, server tick
and arrival time are deliberately outside the contract.

## 10. NETWORK QUANTIZATION and EXACTNESS

`MovementObservationProfile.PAPER_26_2`: `ServerboundMovePlayerPacket` carries full doubles — a transmitted position is compared
EXACTLY (one ulp is a different double; there is no positional epsilon anywhere in Phase 6). The client sends a position only
when `dx*dx + dy*dy + dz*dz > Mth.square(2.0E-4)` against the LAST TRANSMITTED position (or every 20 ticks): a silent tick
therefore constrains the true position to that closed ball, evaluated with the client's own double arithmetic
(`withinSendThreshold`), and rotation is sent whenever it changed, so a packet without rotation fixes the yaw. The threshold is
client-only code (documented behaviour, not extractable from the server jar) — it is version-scoped, named, and its
qualification is part of the real-client section (§29). `TickObservationTest` covers the boundary: at the origin `2.0E-4`
along one axis is inside and `nextUp(2.0E-4)` outside; at x = 100 the client's own `(x - lastX)` rounding makes the nominal
`2.0E-4` transmit; diagonals use the sum of squares, not a per-axis box. Residuals (§23) are relative to the observation set:
0 inside, distance beyond the ball outside.

## 11. M3 ABSTRACT / INTERVAL MODEL (`Interval`, `AbstractState`, `AbstractTransfer`)

`AbstractState` = finite union (≤ 16) of tagged boxes: position and velocity intervals, ground set, pose set, noJumpDelay
range; `TOP` represents everything and excludes nothing. `T#` is implemented for the M2 pilot domain — verified one-step
walking / free flight / known flat contact: WALKING, standing/crouching, zero stuck multiplier, every attribute and effect
known (modelled effects only), KNOWN empty entity neighbourhood, known far border, no unresolved external motion, and a swept
region (position box ⊕ every possible pre-move velocity ⊕ body ⊕ one cell) whose cells are all KNOWN and either plain air
(no fluid, inside effect or climbable) or exactly one complete layer of the SAME full-cube block with speed factor 1, jump
factor 1, restitution 0 and a motion-free `stepOn` (region proof through `CollisionEngine.query` COMPLETE + `BlockBehaviorQuery`,
≤ 4 096 cells). Anything else is TOP. In that region the tick is per-axis and every operation is monotone in its interval
operand, so the concrete tick's own arithmetic on the endpoints bounds every concrete state exactly (IEEE rounding is
monotone) — no widening except at genuine case splits, which are UNIONED, never chosen: the 9e-6 / 0.003 zeroing thresholds,
jump eligibility (`noJumpDelay` range), landing vs staying airborne (±1e-6 band around the floor), slow falling's sign test,
levitation (outward), and `Shapes.collide`'s zeroing of requests below `SHAPE_EPSILON` over a floor (horizontal: union with the
unmoved position; vertical: TOP). The landing height is a point only in the Sterbenz regime (`y ∈ [Yf/2, 2Yf]` or `Yf == 0`)
where `y + (Yf − y) == Yf` exactly; elsewhere TOP. Uses: (a) per-input prefilter — `apply(prepare(box), input)` gives the
input's own envelope for the tick (a point for a concrete start except at case splits), and the concrete step runs only when
it meets the observation set; (b) lookahead — the surviving box propagated through the remaining ticks' full-domain union
must meet every later observation; (c) abstract starts — a caller-supplied region (e.g. a start known only through a silent
tick) is propagated per input and yields ENVELOPE_POSSIBLE candidates, never witnesses (`ENVELOPE_ONLY`). Containment
(TC12): `AbstractTransferTest.everyConcreteSuccessorIsContainedInTheEnvelope` — 238 random boxes around reachable states,
12 sampled states × 144 inputs each, **397 440 concrete successors all inside** the per-input and union envelopes (13 826 TOP
cases excluded nothing); threshold and landing edges tested explicitly. Sampling is a regression check; the per-operator
monotonicity argument is the containment argument.

## 12. CANDIDATE ENUMERATION, SAFE PREFILTERS, DETERMINISM

Per tick, per live node: canonical enumeration (reported input first); for each input, if the envelope prefilter is
available: the per-input envelope must meet this tick's observation and its propagation must meet every later observation,
else the input is EXCLUDED by envelope (counted separately from exact exclusions); otherwise the Phase 5 step runs: EXACT and
inside → node; EXACT and outside → excluded exactly; not EXACT → UNDECIDED (never excluded; continued while inside, reported
otherwise; the result is not complete). Nodes with bit-identical states merge (`LinkedHashMap` in insertion order — no hash
ordering anywhere; `ArchitectureTest` forbids `HashMap`/`HashSet` in the module). The only approximate thing in the prefilter
is the ball's outer cube for silent ticks (a sound superset). Proof of harmlessness: the round-trip gate runs every case with
the prefilter ON and OFF and requires identical candidate sets; the native gate does the same on 3 105 ticks
(`pruningDisagreements = 0`). Same request ⇒ same candidate order, results and work (`sameRequestSameResult`, round trip).

## 13. SINGLE-TICK RECONSTRUCTION

Outcomes are explicit: `holdingWFromRestIsUniquelyReconstructed` (1/144), `standingStillIsAmbiguousBetweenPoseStatesAnd
EquivalentKeys` (2 states / 8 histories), `jumpKeyInMidAirIsAnEquivalentInputNotAGuess`, `anUnreachableObservationIsNo
ModelledCandidateNotAVerdict` (144 excluded, complete, no verdict vocabulary), incomplete outcomes per §15.

## 14. MULTI-TICK RECONSTRUCTION (owned by Phase 6, bounded by M2: at most three inferred steps)

Horizons 1–3 are caller hypotheses (Phase 7 will decide which alignments are credible); a 4-tick request is
`TIMING_UNRESOLVED` and `SearchBudget` refuses `maxTicks > 3`. Intermediate ticks carry their own observation sets: exact,
silent (send-threshold ball around the last transmitted position) or unobserved. State propagation is exact and deduplicated
(bit-identical states merge, all histories kept: `identicalStatesFromDifferentHistoriesMergeIntoOneNodeWithBothHistories
Representable` — 32 histories from two tick-1 parents in one standing node); the envelope lookahead prunes tick-t inputs that
cannot reach the later observations, so an unobserved middle tick stays complete with 145 evaluations instead of exhausting the
cap (`anUnobservedMiddleTickIsSolvedByEnvelopePruningNotByDroppingStates`; without the envelope the same request reports
SEARCH_LIMIT). Round trip (`MultiTickReconstructionTest`): 160 random 2/3-tick histories — 160/160 witnessed under exact
observations, 54/54 admitted under silent middle ticks, and under fully unobserved middle ticks 89 complete (all witnessed)
and 71 `SEARCH_LIMIT` (honestly reported; two unobserved ticks from an arbitrary state exceed 256 live nodes). Horizons 5 and
20: NOT APPLICABLE BY SPEC (M2).

## 15. UNCERTAINTY PROPAGATION (never a default, never an exclusion)

`unknownAttributesEffectsAndEntitiesNeverDefaultAndNeverExclude`, `ReconstructionPropertyTest.unknownWorldOrEnvironmentNever
YieldsCompleteAndNeverExcludes`: with unknown attributes / effects / entity neighbourhood / border, or an unobserved chunk in
reach, every raw input stays undecided or possible, `excludedExact + excludedEnvelope == 0`, `complete == false`, and the true
input is admitted; fluids → `UNSUPPORTED_FLUID`; unsupported locomotion/pose → `UNSUPPORTED_CONTEXT`; unresolved external motion
→ `UNRESOLVED_EXTERNAL_MOTION`; branches are reconstructed separately and reported per branch, never chosen
(`externalMotionBranchesAreReconstructedSeparatelyAndNeverChosen`). Equipment matters only where powder snow is touched (the
physics reports it then). Gates refuse without searching: epoch mismatch, discontinuity, unavailable view, horizon, yaw,
non-finite start (`gatesRefuseWithoutSearching`).

## 16. PROVENANCE and RESIDUAL MODEL

Each candidate: raw history per tick (bounded list + exact count + per-tick ordinal masks: exact per tick, a product superset
across ticks only when a node's histories were capped — `historiesComplete()` tells them apart; every single-tick node is
complete since ≤ 144), predicted state, envelope, match kind, physics status and ≤ 32 reasons, start label, branch id,
reported-input flag. Residual: `dx, dy, dz` (prediction − transmitted position, or − last transmitted for silent ticks),
`outsideDistance` (0 inside the observation set), `groundClaimAgrees/Present`. Never NaN for finite predictions (round trip,
property test). No residual is converted into anything.

## 17. WORK BOUNDS and SEARCH BUDGET

`SearchBudget.DEFAULT` = 256 live nodes, 4 096 physics evaluations (envelope transfers share the cap for abstract starts),
≤ 3 ticks, 256 histories/node, 64 candidates reported, 4 096 envelope cells/scan; the physics keeps its own per-step budget
(64 queries / 16 384 cells / 512 inside cells / 2 048 entity ops). Exhaustion → `SEARCH_LIMIT`, `complete=false`,
`witnessHistoriesUnexplored` and the partial candidate set (`exhaustedBudgetsReportSearchLimitWithPartialEvidenceAndNo
CompletenessClaim`; `aSearchLimitNeverClaimsExhaustivenessAndNeverInventsCandidates`: every candidate of a limited search is
a witness of the full search). `everyRequestStaysInsideItsWorkBoundsAndResidualsAreFinite`.

## 18. NO-FALSE-EXCLUSION GATE and NATIVE TRAJECTORY RECONSTRUCTION

Two lanes, both hard gates:
- `NativeTrajectoryReconstructionTest` (plain `test`): the 88 pinned native trajectories are replayed by the chain whose digest
  is re-checked against the server's pinned digest before use; for each of the 3 105 ticks the declared input is hidden, the
  transition reconstructed, and the declared input must be an EXACT witness: **3 105/3 105 included, 3 105 witnesses,
  0 false exclusions, 0 prefilter disagreements**; distribution 1 324 unique / 250 equivalent-inputs / 1 531 distinct-states /
  0 incomplete; 211 810 physics evaluations with the envelope vs 447 120 without.
- `physicsNativeDifferential` (process lane): the same over the SERVER's own per-tick states of this run (start = server state
  before the tick, observation = server state after it): **3 105/3 105, 0 false exclusions** (`native-reconstruction-oracle-
  results.json`), while the Phase 5 differential stays 88/88, 3 105/3 105 bit-exact, drift 0.0.

## 19. ROUND-TRIP FUZZ

`ReconstructionRoundTripTest`: 1 200 deterministic cases (seed printed; `-Dreconstruction.cases`/`.seed` override) on three
worlds (flat, a wall/slab/stairs/fence/low-ceiling course, ice): random walks from rest (0–24 random inputs, random yaws),
a random raw input, Phase 5 forward, exact observation, reconstruction with the envelope ON and OFF: the original input is an
EXACT witness in every case, both candidate sets identical, every candidate inside the observation, residuals finite,
identical output on re-run. 188 unique / 1 012 ambiguous (random walks are mostly airborne: jump-key and pose equivalences);
1 015 cases used the envelope, 185 ran the concrete lane alone (TOP: obstacles, ice-floor edge cells).

## 20. CANDIDATE DISTRIBUTION (`candidate-distribution.json`; ambiguity is data for Phase 8, never a defect)

| Corpus | ticks | unique | equivalent inputs | distinct states | incomplete | mean physics evaluations / tick | envelope-excluded share |
|---|---|---|---|---|---|---|---|
| native trajectories (88 scenarios) | 3 105 | 42.6 % | 8.1 % | 49.3 % | 0 | 68.2 | 52.6 % |
| random corpus (3 worlds) | 1 500 | 15.5 % | 59.3 % | 25.3 % | 0 | 24.4 | 83.1 % |

By category (native): ground walk 85.5 % unique (1 151 ticks), sprint 87.3 %, sneak 88.2 %, item use 100 %, ground jump
29.6 % unique / 70.4 % distinct (jump-key + pose variants), ground still 100 % distinct (standing vs crouching), airborne
100 % non-unique (jump key held/released differs only in `noJumpDelay`, pose). Native groups without an envelope (collision,
step, pose, sneak, stuck, climb, bounce, factor, mixed) cost ≈ 110–144 evaluations/tick; free-flight/flat groups ≈ 2–50.
Max matching states on a native tick: 34; max histories: 48.

## 21. ENVIRONMENT REUSE

One `PhysicsEnvironment` (world view, collision engine, behaviour cache, profile, attributes, effects, entities, border,
equipment) per request, shared by every node, tick and candidate; branches only swap the external-motion list. The envelope
prepares one region proof per node per tick and applies it per input. Nothing is rebuilt per candidate.

## 22. MEMORY (`reconstructionMemoryHarness`, `docs/phase6-artifacts/memory-results.json`)

Reconstructor: 16 B (stateless; one instance serves every thread). Retained per 100 kept results: 153 KB (idle-ambiguous,
2 nodes × 8 histories) / 80 KB (walk-unique); results are values the caller drops; frontier continuation keeps only the
exact-witness states. Per-request allocation (envelope ON / OFF): constrained idle 39 KB / 154 KB; walk 89 KB / 1.18 MB;
sprint 89 KB / 1.17 MB; jump 106 KB / 1.17 MB; airborne 108 KB / 1.06 MB; idle-ambiguous 147 KB / 1.18 MB; empty set 97 KB /
1.17 MB; wall collision (TOP, concrete lane: 144 steps) 1.29 MB / 1.27 MB; multi-tick 3 (exact observations) 1.04 MB /
3.67 MB. The envelope lane costs ≈ 0.6 KB per input (Interval/Box records) — a known optimization target (a primitive
envelope kernel), not a blocker; the concrete lane costs ≈ 8 KB per physics step (Phase 5). No per-player retention.

## 23. PERFORMANCE (`benchmarkReconstruction`, JMH 1.37, Temurin 25, 3 forks, 5×1 s warm-up, 10×1 s measurement, `-prof gc`)

| Kernel | time/op (±err) | alloc B/op |
|---|---|---|
| idleUnique | 19.6 µs ± 331.1 ns | 34,077 |
| idleAmbiguous | 71.1 µs ± 7903.2 ns | 132,638 |
| walkW | 33.1 µs ± 595.8 ns | 84,870 |
| strafeD | 31.9 µs ± 2924.2 ns | 84,851 |
| diagonalWA | 33.1 µs ± 982.1 ns | 85,824 |
| sprint | 32.2 µs ± 525.6 ns | 83,744 |
| jump | 46.1 µs ± 4043.4 ns | 101,827 |
| airborneAmbiguity | 25.1 µs ± 4435.7 ns | 82,262 |
| wallCollision | 1057.7 µs ± 19.9 µs | 1,280,539 |
| stepUpSlab | 906.6 µs ± 97.8 µs | 1,430,854 |
| stairs | 963.5 µs ± 14.1 µs | 1,433,471 |
| iceWalk | 30.7 µs ± 3282.8 ns | 84,848 |
| rawCandidates36 | 18.5 µs ± 3147.1 ns | 42,171 |
| rawCandidates72 | 28.1 µs ± 1585.6 ns | 56,368 |
| fullDomain144 | 33.7 µs ± 632.5 ns | 84,894 |
| fullDomain144ConcreteOnly | 788.2 µs ± 15.1 µs | 1,224,547 |
| walkWConcreteOnly | 784.8 µs ± 15.1 µs | 1,222,627 |
| multiTick2 | 95.1 µs ± 755.1 ns | 261,611 |
| multiTick3 | 412.5 µs ± 40.8 µs | 1,384,475 |
| multiTick3UnobservedMiddle | 42928.3 µs ± 264.7 µs | 160,094,974 |
| emptyCandidateSet | 25.3 µs ± 130.6 ns | 76,568 |
| worstBoundedSearch | 5389.9 µs ± 657.6 µs | 6,689,803 |
| enumerate144 | 921.0 ns ± 20.3 ns | 5,224 |
| envelopePrepare | 8918.6 ns ± 124.7 ns | 8,024 |
| envelopeApplyOne | 76.4 ns ± 0.6 ns | 296 |
| envelopeStepAllInputs | 31.1 µs ± 525.4 ns | 98,800 |
| observationCompare | 1.4 ns ± 0.2 ns | 0 |
| onePhysicsStep | 3100.1 ns ± 435.2 ns | 7,187 |

Reading: a full-domain reconstruction of a walking tick costs 33.1 µs with the envelope prefilter (one region proof, 144 envelope applications, 1 physics step) versus 784.8 µs with the concrete lane alone (144 physics steps) - 23.7x; the empty candidate set costs 25.3 µs (no physics step at all); contexts outside the envelope pilot (wall, step, stairs) cost the concrete lane's 906-1057 µs; the worst bounded search (two unobserved ticks from a fast airborne start over a cluttered course) runs into the solver caps at 5390 µs and says SEARCH_LIMIT. Breakdown: enumeration 0.9 µs, envelope preparation (region proof) 8.9 µs, one envelope application 76.4 ns, the full-domain union step 31.1 µs, one observation comparison 1.4 ns, one physics step 3.10 µs. Descriptive kernels on one host, not capacity.

Measurement provenance: the table is the run taken AFTER P6-F1 on the final code (`benchmarks.json`, 2026-09-20 evening, machine
otherwise idle, 11% load). The morning run on the pre-P6-F1 code is kept alongside as `benchmarks-pre-p6f1.json`. The host was
measurably slower in the evening: kernels that P6-F1 cannot touch moved by the same factor as the rest (`enumerate144` 544 -> 921 ns,
x1.69; `envelopePrepare` 5.4 -> 8.9 µs, x1.66; `fullDomain144ConcreteOnly` x1.69; `observationCompare` 1.1 -> 1.4 ns), so the
x1.5-2.0 across the table is host drift (memory-bound kernels moved most, the compute-bound `onePhysicsStep` only x1.14), not the
kernel change. The kernel's own cost, host-normalized by the control kernels: `envelopeApplyOne` 30 -> 76 ns raw = about +15 ns per
input application (a float normalize, three scales and the square-movement rescale replace four float multiplies), i.e. about +2 µs
on a full-domain envelope pass; allocation is unchanged or lower everywhere (the returned `float[2]` is scalar-replaced). A first
evening run taken while the operator's game client was still open was discarded as contended (kept in the session scratch only).

`multiTick3UnobservedMiddle` (W, W, W with the first two ticks unobserved) is a SEARCH_LIMIT case: every tick-1 input is admitted (nothing observed), the 134 distinct tick-1 states each run the per-input lookahead for tick 2, and the result honestly reports `complete=false` at 42.9 ms (21.4 ms in the morning run) and 160 MB of transient garbage per request. The garbage is the lookahead's full-domain union steps (`envelopeStepAllInputs`: 144 boxed applications + hull merges, ≈ 100 KB each) - the one allocation hotspot of the phase, listed in §31 as an optimization target (a primitive, allocation-free envelope kernel), not a blocker: the case is bounded by the M2 caps and rare (one unobserved tick is routinely complete at 145 evaluations). A whole-node reach check before the per-input checks was measured as pure overhead (multiTick2 50 → 123 µs in the morning run, no change on the unobserved case) and not kept.

## 24. REPLAY FIXTURES

`docs/phase6-artifacts/reconstruction-fixtures/reconstruction-fixtures.json`: 24 pinned cases (unique walk, unique sprint at
yaw 37.5°, ambiguous idle, airborne jump key, wall push, no modelled candidate, unknown world / attribute / effects / entities,
external motion unresolved, external motion branches, search limit, multi-tick 2/3 exact, silent middle, unobserved middle,
world epoch mismatch, discontinuity, fluid deferred, envelope-only abstract start, known constraints + reported input, step-up
slab, ice deceleration silent tick), each with status, completeness, ambiguity, exact counts, evaluations and a SHA-256 of the
canonical candidate set. `ReconstructionFixtureTest` replays them; `reconstructionFixtures` regenerates (a changed digest is a
reviewed event). No live object or cache is serialized.

## 25. NATIVE LANE

`PhysicsOracleMain` gained a Phase 6 pass that runs after the Phase 5 comparison (same server process, same scenario run):
per tick, the reconstructor is given the oracle's own state before the tick and its state after it; the declared input must be
an exact witness. Output `native-reconstruction-oracle-results.json`. This run: 3 105 ticks, 3 105 included, 0 false exclusions,
1 324 unique / 250 equivalent / 1 531 distinct / 0 incomplete — identical to the offline lane, as it must be (the chain is the
server's trajectory bit for bit).

Since P6-F1 the `physicsNativeDifferential` task writes `docs/phase6-artifacts/` (`native-differential-results.json`,
`multi-tick-drift-results.json`, `physics-fixtures/native-trajectories.json`, `native-reconstruction-oracle-results.json`) and the
plain-lane tests (`PhysicsOracleFixtureTest`, `NativeTrajectoryReconstructionTest`, `ReconstructionDistributionMain`) replay the
Phase 6 pin; `docs/phase5-artifacts/` is the frozen pre-P6-F1 record and was not modified. The oracle glue mirrors the client
pipeline (`PhysicsOraclePlugin`: the jar's own `Vec2.normalized()`/`Mth.sqrt`, the inline square-movement helper, and
`OraclePlayer.applyInput()` overridden to a no-op because the client applies the `0.98F` inside its pipeline).

## 26. DIAGNOSTIC (`/papea2ac reconstruct <player> [arm [declared] | report | cancel]`, `ReconstructionTracer`)

`arm` snapshots the published rest position, the world view and main-thread facts (border, effects, boots, attribute bases,
"no entities nearby") and starts the 2 ms poller, which records position-bearing samples (movement sequence advanced) and
rotation-only samples (yaw changed without a new position). `report` walks the samples from the rest hypothesis: for each
transmitted position it tries the 1-, 2- and 3-tick hypotheses (silent intermediate ticks in the send-threshold ball, every
yaw switch alignment a rotation-only sample allows), takes the first hypothesis with candidates for continuation (the
exact-witness states become the next frontier; identical re-sends at rest become the frontier's one-tick silent closure),
and prints per transition: hypothesis statuses, matching states/histories, evaluations, the first four candidates, and — only
if a scenario was declared — whether the declared keys are PRESENT in the candidate set (as k idle ticks then the declared
keys, k < H). Never more than 40 transition lines, 512 requests, 64 samples, 60 s. It never prints VALID/INVALID/CHEATING
(`ReconstructionTracerTest`), and a wrong declaration is reported ABSENT, not judged.

Three accounting/diagnostic changes came out of the real-client session (all tracer-only, `anticheat-core`, each with a
regression test): (1) a rest re-send or a counted transition whose matching states are all INCOMPLETE (no exact witness -
seen live with a creeper inside the entity neighbourhood) keeps the previous frontier / stops the chain with an explicit line
instead of failing (`incompleteStatesNeverLeaveTheChainWithoutAFrontier`); (2) the declared-scenario accounting knows the
three phases of a controlled scenario - whole idle ticks before the press (`pre-press=IDLE`), the held keys
(`declared=PRESENT/ABSENT`), and the release tail explained by the keys-then-idle history (`released=IDLE`, only after the
keys were present at least once) - so a scenario's own deceleration is not counted against it while a wrong declaration
stays ABSENT for every held tick (`theReleaseTailIsExplainedByIdleAfterTheHeldPhase`, negative test with a tail); (3) a NO
CANDIDATE line is followed by the three nearest one-tick inputs from the frontier states with their residuals
(`nearest (diagnostic only, ...)`), which is what made P6-F1 attributable in one look
(`anUnexplainedTransitionListsTheNearestInputsWithoutAVerdict`).

## 27. PAPER SMOKE

Pinned Paper 26.2 build 124 at `.phase1-runtime`, offline mode, Java 25 (`.phase1-tools/jdk-25.0.4.1+1`). Four plugin builds
were cycled during the session (start / clean stop each time, always `Disabling Papea2AC` before the player's disconnect, 0
ERROR/Exception lines in every instance log except the first, whose single exception is the tracer defect fixed by change (1)
of §26): `3839ef366f20836c` (initial), `88e9137074a64169` (tracer empty-frontier fix), `3bd4b86d69aaadf8` (release-phase
accounting), `589674560dc02e6a` (nearest diagnostic), and the FINAL jar `d9b5f0f96cac0c92` (P6-F1) - Done (7.519s), enabled,
`reconstruction rawDomain=144 classes=136 observation=paper-26.2-124-movement-observation caps=256nodes/4096evals/3ticks`,
final status with the player connected: 1 151 diagnostic requests, 508 declared-present transitions, 1 582 samples, world
213/213 chunks complete, collision 1 375 783 queries all COMPLETE, then `stop` with the player connected:
`[Papea2AC] Disabling Papea2AC` (log line 227) precedes `Emojiado lost connection` (line 229), 0 exceptions. Logs:
`.phase1-runtime/qualification-run-phase6*.log` (git-ignored). The RCON password never appears in any evidence file (scanned).

## 28. REAL CLIENT QUALIFICATION — protocol

Same setup as Phase 5 (owner's native Minecraft Java 26.2 client, pinned Paper 26.2 build 124 at `.phase1-runtime`, the FINAL
plugin jar). Per scenario: the operator arms `/papea2ac reconstruct <player> arm <declared>` while the player stands still,
the player performs the declared keys for ~2 s, `report`. Success condition (§60 of the brief): the declared input is PRESENT
in the candidate set of every reconstructed transition (uniqueness not required). A trace whose tick alignment is ambiguous
(key-onset skew, merged/dropped first packet) is marked NOT QUALIFYING — TIMING AMBIGUOUS, never shifted into a pass and never
called a physics failure. The negative test (§63) arms an intentionally wrong declaration and expects ABSENT.

## 29. REAL CLIENT QUALIFICATION — evidence

Owner-operated session on 2026-09-20/21 (UTC), in-game name `Emojiado`, `127.0.0.1:25576`, world set to peaceful after a creeper
walked into the S3 trace (S3 note), Phase 4 course rebuilt (`course.js -37 67 20`) plus a 13x3 ice strip for S18. Full transcript:
`docs/phase6-artifacts/real-client-evidence.log` (92 blocks; every arm, report, note, jar cycle). Numbers below are the FINAL
jar unless marked; "PRESENT n" = the declared keys were in the candidate set of all n counted held transitions; "unique" =
EXACT_UNIQUE transitions; release tails are the idle-explained deceleration ticks.

| # | Scenario (declared) | Result | Detail |
|---|---|---|---|
| S1 | rest (`release_all`, camera nudges only) | **QUALIFYING** | PRESENT 5/5, 0 absent; each transition EXACT_AMBIGUOUS between idle and the crouch-toggle-in-place equivalents (16 histories, 2 states). Pure rest gives only same-position re-sends (silent closures, no counted transition): S1/S1b were NOT QUALIFYING by the tracer's own >= 3 rule, and S1b also filled the 64-sample cap with continuous mouse motion - protocol, not physics |
| S2 | hold W | **QUALIFYING** | PRESENT 30 (29 unique `W-----`), 8 release ticks idle; re-check on the final jar PRESENT 43 (42 unique) |
| S3 | hold S | **QUALIFYING** | PRESENT 36 (35 unique), 8 release idle. First attempt: a creeper within the entity neighbourhood made every candidate UNKNOWN_ENTITY_NEIGHBORHOOD - reported as coverage, chain stopped honestly (after the fix of §26 (1)); hostiles removed, peaceful |
| S4 | hold A | **QUALIFYING** | PRESENT 27 (26 unique); the envelope prefilter left one input to simulate on most ticks (`eval=1`) |
| S5 | hold D | **QUALIFYING** | PRESENT 37 (36 unique) |
| S6 | W+A (`w_a`) | **QUALIFYING (after P6-F1)** | Phase 5 model: the first moving tick had NO MODELLED CANDIDATE under H<=3; nearest input `WA----` at residual 1.014e-8 along the motion -> P6-F1 (§30). Final jar: PRESENT 36 (35 unique `WA----`), 8 release idle, 0 absent |
| S7 | W+D (`w_d`) | **QUALIFYING** | PRESENT 46 (45 unique `WD----`) |
| S8 | W+sprint | **QUALIFYING** | PRESENT 41 (40 unique `W---R-`), 8 release idle |
| S9 | jump in place | **QUALIFYING** | PRESENT 24/24 over two full arcs (67.42, 67.7532, 68.0013, ..., 68.2522, ..., 67.0), every tick exact; airborne ticks EXACT_AMBIGUOUS (jump key held or not is indistinguishable in the air) |
| S10 | W+jump | **QUALIFYING** | PRESENT 47/47, 0 absent, all one-tick aligned |
| S11 | W+sprint+jump | **QUALIFYING** | PRESENT 43/43, all one-tick aligned (Phase 5 had to mark this timing-ambiguous; the candidate-set formulation absorbs the key-onset alignment) |
| S12 | W+shift | **QUALIFYING** | PRESENT 53 (52 unique `W--C--`) |
| S13 | W while using an item (shield raised) | **QUALIFYING** | first trace PRESENT 5 (thin: the sample cap filled during item selection), repeat PRESENT 41 (40 unique `W----U`) |
| S14 | airborne input change (no declaration) | covered (partial) | two attempts: `W-J---` take-off, `W-----` airborne, **W released mid-air** reconstructed exactly as airborne idle (y 68.02 / 67.50 descending), landing, then `-A----`/`-AJ---` on the ground; 35 + 31 transitions, 0 no-candidate. A W->A switch WITHIN the ~0.6 s airtime was not achieved by the operator in either attempt (human timing; airborne strafing itself is covered by the oracle's `air_strafe` scenario). Not a physics or reconstruction limitation; recorded as performed |
| S15 | walk into a wall (3-high stone, x=11) | **QUALIFYING** | PRESENT 13/13; the contact tick (x=10.700 exactly) is EXACT_AMBIGUOUS over 21 histories that all end clamped with the velocity zeroed; pressing against the wall is silent |
| S16 | bottom slab (step up 0.5) | **QUALIFYING** | PRESENT 24/24; step onto the slab (y 67.5 at x=-29.17) exact |
| S17 | stairs onto a block | **QUALIFYING** | PRESENT 25/25; y 67.5 at x=-24.12 then 68.0 at -23.69, exact; 8 release idle |
| S18 | ice: walk, release, slide | **QUALIFYING** | PRESENT 21 held, then 30 slide ticks all explained by idle (the whole ice deceleration tick by tick) |
| S19 | ladder climb | **QUALIFYING** | PRESENT 46/46; x clamped at 0.5125 against the ladder's 3/16 shape, +0.1176/tick up to 69.506, release descent exact |
| S20 | NEGATIVE: declared `hold_s`, operator held W | **ABSENT 44/44** | candidate set `W-----` (EXACT_UNIQUE), summary "NOT QUALIFYING: the declared input is absent from 44 transition(s) - a declaration/alignment discrepancy to investigate, not a verdict" |

Timing: every counted transition in every QUALIFYING trace was explained by the one-tick hypothesis (`alignedH1` = reconstructed,
`gapHypotheses=0`); no trace had to be marked TIMING AMBIGUOUS. Three traces expired (the tracer's 60 s arm lifetime) and one
was cancelled after the operator repositioned; they were re-armed, never shifted. No line of any report contains VALID,
INVALID, CHEATING or VIOLATION.

## 30. Forward defects (P6-F<n>)

| Id | Origin | Finding | Fix | Regression | Historical baseline |
|---|---|---|---|---|---|
| **P6-F1** | Real-client qualification S6 (W+A): NO MODELLED CANDIDATE; nearest input `WA----` at residual (+7.2e-9, 0, -7.1e-9), exactly radial, i.e. the client's diagonal impulse is 1.0141e-7 relatively SHORTER than the model's | The 26.2 client pre-processes the key vector in FLOAT (client-only `LocalPlayer` code, invisible to the server-side oracle whose `LivingEntity.applyInput()` is only `xxa *= 0.98F; zza *= 0.98F`): `Vec2(strafe, forward).normalized()`, `scale(0.98F)`, `scale(0.2F)` while using an item, `scale(sneakingSpeed)` while moving slowly, then the square-movement rescale (`u = v.scale(1/|v|)`, `min(|v| * sqrt(1 + (min/max)^2), 1)`). On a diagonal this yields `0.7071067F` per component - one float ulp below 1/sqrt(2), length 1 - 1.014e-7 - which the double `getInputVector` no longer renormalizes; the Phase 5 model fed `(0.98F, 0.98F)` to the double normalization (length exactly 1). Numerically the ONLY float pipeline family reproducing the measured -1.0141e-7 (brute-forced over normalize/scale/square orderings). Straight keys are bit-identical under both pipelines (all Phase 5 evidence stands); 8 of the 32 (forward, strafe, slow, use) tuples differ: the four walking/sprinting diagonals by -1.014e-7 relative, the four sneak+use diagonals by -6.3e-8 | `PhysicsKernels.clientMoveVector` (one implementation for the simulator and the abstract lane; `scaledImpulse` removed), `MovementProfile.VEC2_NORMALIZE_EPSILON`/`SQUARE_MOVEMENT_CAP` documented; oracle glue mirrors the pipeline (`PhysicsOraclePlugin.squareMovement`, `OraclePlayer.applyInput` no-op) | `physicsNativeDifferential` re-run: 88/88, 3 105/3 105 single and chained, drift 0.0, Phase 6 gate 3 105/3 105; pinned trajectories: **81/88 digests byte-identical** to the frozen Phase 5 record, the 7 changed are exactly `diagonal_forward_left`, `diagonal_back_right`, `walk_yaw_180_strafe`, `air_strafe`, `wall_diagonal_corner`, `packed_ice_strafe`, `mixed_course_random_100` (all contain diagonal ticks); reconstruction fixtures 23/24 identical, `multi_tick_2_exact` candidate-set digest re-pinned (counts identical); test lane 531/531; in-game: S6 and S7 QUALIFYING on the fixed jar, straight-key re-check (W) unchanged | Phase 5 baseline `03a538c4` and `docs/phase5-artifacts/` untouched (frozen pre-P6-F1 record); the re-pin lives in `docs/phase6-artifacts/physics-fixtures/` |

Scope of the P6-F1 verification: the walking diagonal is pinned by controlled measurement (S6, S7); straight keys, sneaking
and item use are pinned by Phase 5/6 in-game traces (S2-S5, S12, S13); the sneak+use DIAGONAL order of the two client scale
steps (which changes the last float bit, -6.3e-8) is taken from the client pipeline as written and is not separately measured
(§31).

Phase 6's own defects found and fixed during qualification (not earlier-phase defects; tracer only): the empty-frontier
`IndexOutOfBoundsException` on incomplete-only closures (one exception in the first instance log), the release-tail
accounting, and the missing NO-CANDIDATE diagnostic - §26.

Harness note (not a defect): the first candidate-membership API bounded the materialized histories at 8 and reported a
capped node's missing histories as "excluded" (4 native ticks against a wall where 12 raw inputs share one blocked state);
the API now materializes ≤ 256 histories per node, keeps the exact count and per-tick ordinal masks, and answers membership
exactly for every single-tick node.

## 31. KNOWN LIMITATIONS / DEFERRED TO PHASE 7+

- Tick alignment, packet merging/dropping, latency, ACK/velocity/teleport timing: Phase 7. Phase 6 evaluates the caller's
  hypotheses (≤ 3 ticks) and reports TIMING_UNRESOLVED / DISCONTINUITY otherwise.
- Client-side legality rules (sprint start/stop, item-use sprint) are UNVERIFIED (client-only code) and opt-in; the raw domain
  is the full 144.
- The envelope pilot domain is flat contact / free flight over plain air; obstacles, slabs, stairs, ladders, special blocks,
  fluids, entities and unknown cells make the envelope TOP and the concrete lane runs every admitted candidate (≈ 144 physics
  steps per node per tick) — bounded, measured, not a blocker.
- Two fully unobserved ticks from an arbitrary state frequently exceed the 256-node cap (SEARCH_LIMIT, honest); one unobserved
  tick is routinely complete with the lookahead. Horizons beyond three: NOT APPLICABLE BY SPEC.
- The position-send threshold is client-documented behaviour, version-scoped; a different client build needs its own profile.
- Fluids, elytra, ability flight, vehicles, swimming, entity pushing beyond the explicit list, world border state,
  `moveTowardsClosestSpace`: Phase 5 deferrals, reported as UNSUPPORTED_*/UNKNOWN_* statuses.
- The reported input (`ServerboundPlayerInputPacket`, captured as a control bit set in Phase 1) is supported by the engine as a
  witness proposal but the diagnostic tracer does not yet feed it (no timeline projection of the claim yet): forward item.
- Envelope-lane allocation (~0.6 KB per input) is an optimization target before Phase 8.
- P6-F1 pins the client input pipeline for every straight key and the walking/sprinting diagonals by measurement; the
  sneak+use diagonal (last-bit order of the two client scale steps) follows the pipeline as written and is unmeasured.
- The diagnostic tracer's 60 s arm lifetime and 64-sample cap are tight for a human-in-the-loop protocol (three traces
  expired, continuous mouse motion fills the cap in ~3 s); diagnostic parameters, not engine limits.
- A mid-air W->A key switch within one jump's airtime was not achieved by the operator; the airborne input change that was
  performed (W released mid-air) reconstructed exactly, and airborne strafing is covered by the oracle's `air_strafe`.

## 32. ACCEPTANCE MATRIX

| Requirement | Evidence | Result |
|---|---|---|
| Owner module, DAG, boundary | §2, `ArchitectureTest` 10/10 | PASS |
| Reconstruction API (immutable request/result, no mutable physics internals) | §4, §5 | PASS |
| Input domain (144, canonical, machine-verifiable) | `InputDomainTest`, `input-domain.json` | PASS |
| Client-legal constraints | unverified, opt-in, documented (§7) | PASS (nothing excluded by default) |
| Stateful input model | physics state propagated; `previousSprinting` prerequisite | PASS |
| Observation contract | §9, `TickObservationTest` | PASS |
| Network quantization / exactness | §10, `MovementObservationProfile` | PASS |
| M3 abstract/interval model | §11, `AbstractTransferTest` (397 440 containments) | PASS (pilot domain) |
| Single-tick reconstruction | §13 | PASS |
| Multi-tick reconstruction (≤ 3) | §14 | PASS |
| Safe prefiltering (no candidate lost) | ON == OFF on 1 200 + 3 105 cases | PASS |
| Input equivalence / provenance | §8, property test | PASS |
| Uncertainty propagation | §15 | PASS |
| Work bounds / search budget | §17 | PASS |
| No-false-exclusion gate | §18: 3 105/3 105 (both lanes) | PASS |
| Round-trip fuzz | §19: 1 200/1 200 | PASS |
| Native trajectory reconstruction | §18 | PASS |
| Ambiguity fixtures | §24 (idle, airborne, wall push, silent ice) | PASS |
| Observation quantization tests | `TickObservationTest` | PASS |
| Property tests | `ReconstructionPropertyTest` (4) + round trip | PASS |
| Replay fixtures | §24 | PASS |
| Diagnostic | §26 (three tracer fixes from the live session, each regression-tested) | PASS |
| Memory | §22 | PASS (descriptive) |
| JMH | §23 | see §23 |
| Candidate distribution | §20 | PASS |
| Paper smoke | §27 | PASS |
| Real-client qualification | §29: 18 declared scenarios QUALIFYING (declared input present in every counted transition), negative test ABSENT 44/44, 0 timing-ambiguous traces; airborne W->A switch recorded as performed (partial) | PASS |
| Regression Phases 1–5 | test 531/531, nativeTest 132/132, coverage 127/127, physics native 88/88 & 3 105/3 105 & drift 0.0 (re-run after P6-F1), shape coverage, collision native, replay conformance | PASS (with the documented P6-F1 forward correction) |
| Phase 7 implementation | none | NONE |
| Phase 8 validation | none | NONE |
