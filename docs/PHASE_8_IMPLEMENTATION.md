# Phase 8 — Movement Validation

Status: **READY FOR BASELINE** after the pre-baseline decision pass below (not committed, not baselined, no Phase 9 work).
Base: Phase 7 baseline `537ffde64367835ffeedf63cd19776713196c110`, receipt `060ab2a330af1886e13d1767de00c288f3a02c49` (HEAD, unchanged).
Pinned pair: Paper 26.2 build 124 (`.phase1-runtime/versions/26.2/paper-26.2.jar`) / vanilla 26.2 client. Toolchain Java 25 (`.phase1-tools/jdk-25.0.4.1+1`).

Phase 8 answers one question per observed transition, bounded and without judgement of the player: **does at least one sound,
supported combination of timing hypothesis (Phase 7), external-motion branch (Phase 7), legal client input (Phase 6), vanilla physics
(Phase 5) and observed world (Phase 3/4) explain the movement?** Yes → `VALID`. No, but some relevant branch is incomplete or unsupported
→ `UNCERTAIN`, naming every missing dimension. No, and every relevant dimension is complete → `INVALID`, with an exhaustion proof. It never
picks the "closest" hypothesis, never compares a residual to a threshold, never accumulates evidence over time, and never enforces.

## SCOPE

In scope (implemented, tested, real-client qualified):

* the immutable `MovementValidationResult validate(MovementValidationRequest)` API in `anticheat-simulation`: classification, typed
  reasons, a per-dimension completeness certificate, witnesses (VALID), an exhaustion proof (INVALID), residual diagnostics, work counters
  and provenance; `classify(request, TimingReconstruction.Result)` for a caller that already holds the Phase 7 union;
* the central safety rule (uncertainty dominance) in one place, enforced a second time by the result's constructor;
* consumption of the **full** Phase 7 timing union and Phase 6 candidate union through the existing adapter `TimingReconstruction`
  (one run per request; no physics, timing solver or reconstruction duplicated anywhere in Phase 8);
* additive Phase 6 diagnostics (`ReconstructionResult.Diagnostics`: the nearest excluded exact predictions and the displacement extent of
  the evaluated set / the M3 envelope) so that Speed-/Fly-oriented residuals are derived from the simulated legal set only;
* the live chain in `anticheat-core` (`MovementValidationRunner`, one per session, shard-owned): anchoring at a correction application point
  or an observed rest, environment from the observer world plus the new player-owned `MovementContextLedger` (attributes, effects,
  abilities, game mode, border, entity neighbourhood), frontier and completeness travelling with the chain, failure containment,
  bounded status, fixed-label metrics, a controlled side-chain injection for qualification;
* `/papea2ac validate <player> [view | recent | notable | inject dx dy dz [ticks]]` (status, context line, rest-anchor diagnosis, recent results, the
  notable ring that keeps every INVALID / UNCERTAIN / VALID-under-incomplete result with its full description, and the controlled side-chain injection)
  and the validation line of `/papea2ac status`;
* deterministic fixtures (17), the golden legitimate corpus (Phase 5 native + Phase 6 + Phase 7 trajectories, 32 314 transmissions, zero
  false INVALID), the synthetic impossible corpus (3 290 mutated transmissions, all INVALID), boundary/1-ulp cases, Speed and Fly
  controlled tests, property and metamorphic tests, engine-level integration tests, memory harness, JMH, and the real-client legitimate +
  controlled-impossible qualification recorded in `docs/phase8-artifacts/real-client-evidence.log`.

Out of scope (Phase 9+): violation levels, alerts, setbacks, punishments, temporal accumulation of any kind, "check" objects, tuning knobs,
cheat brands, network emulation, capacity claims.

## MODULE OWNERSHIP

| Module | Phase 8 content | Why |
|---|---|---|
| `anticheat-simulation` | `MovementValidation` (vocabulary), `MovementValidationRequest`, `MovementValidationResult`, `MovementValidator`; additive `ReconstructionResult.Diagnostics`, `InputReconstructor` diagnostic accounting, `InputDomain.describeHistory` | ARCHITECTURE.md A1: "simulation owns input constraints and movement validation"; the classification rule lives next to the union it classifies |
| `anticheat-player` | `ContextEvent`, `MovementContextLedger` | the client-side movement context is player-owned state, like the timing ledger; imports only `model`/`player` |
| `anticheat-core` | `MovementValidationRunner`, `ContextObservationMapper`, `ValidationStatus`, `ValidationEngineStatus`; `PacketEngine` composition | core composes ledgers, world and validator per session on the shard; it never re-derives a verdict |
| `anticheat-platform-paper` | `/papea2ac validate` (view / recent / notable / inject), status line | display only: it prints the validator's words |
| `anticheat-testing` | fixtures, corpora, property/metamorphic tests, engine tests, memory harness, JMH | evidence |

No Bukkit/NMS/Netty outside the platform module; no new dependency; the approved DAG is unchanged (architecture tests).

## PHASE BOUNDARY

`ArchitectureTest.movementValidationStaysWithinThePhase8Boundary` scans every Phase 8 file for enforcement/threshold/brand vocabulary
(violation, VL, alert, setback, punish, kick, ban, threshold, closest, cheat brands, Speed/Fly/Timer "checks"), wall clocks, platform types,
reflection, hash-order collections and transcendental Math; asserts that the validator consumes `TimingReconstruction.run` and never re-runs
timing or reconstruction, that core and platform never build a result or compare a residual to a number, that the frozen engines
(`InputReconstructor`, `TimingReconstruction`, `TimingResolver`, `PhysicsSimulator`) do not know the validation types, and that the player
ledger imports only `model`/`player`. The Phase 6 boundary test now excludes the Phase 8 files by name (`MovementValidat*`) and keeps
forbidding verdict vocabulary in every other simulation file; the Phase 6 diagnostic record was named `Extent` (not "Reach") for that
reason. VALID / UNCERTAIN / INVALID are produced in exactly one class.

## VALIDATION REQUEST

`MovementValidationRequest(timing, frontier, environment, reconstructionBudget, frontierComplete, contentRetained)`:

* `timing` — the Phase 7 `TimingRequest` (ordered event slice since the anchor, anchor sequence/nanos, last sent position/yaw, the
  target transmission, influences in scope, pending corrections, RTT summary, tick-end capability, coverage-break flag, world epoch, profile,
  `TimingBudget`);
* `frontier` — the Phase 7 union frontier (`Node(state, pendingInfluences, label)`) the chain starts from: the previous result's `next()`,
  or the anchor's nodes;
* `environment` — the Phase 5 `PhysicsEnvironment` (observer world view + epoch, collision engine, block behaviours, attributes, effects,
  equipment, entity neighbourhood, world border, external motions);
* `frontierComplete` — whether every earlier step of the chain was complete (false after any incomplete step, re-set to true by an absolute
  correction application point);
* `contentRetained` — false when the Phase 3 world reports `CONTENT_NOT_RETAINED` for the region.

## VALIDATION RESULT

`MovementValidationResult` (immutable record; `describe()` gives bounded lines):

| Field | Content |
|---|---|
| `classification` | `VALID` / `UNCERTAIN` / `INVALID` |
| `startSequence`, `endSequence`, `worldEpoch` | the interval |
| `completeness` | 13 named booleans: timing, reconstruction, world, attributes, effects, equipment, entities, border, externalMotion, mechanicSupported, search, observation, frontier; `all()`, `missing()` |
| `validReasons` / `uncertainReasons` / `invalidReasons` | typed enums (see REASON MODEL) |
| `counts` | timing resolutions, hypotheses (complete), explaining hypotheses, reconstructions, legal candidates, legal histories, start/continuation nodes |
| `witnesses` (≤ 4) | hypothesis id, branch id, candidate id, input history, history count, predicted state, residual, applied influences, teleport-application flag |
| `proof` | hypotheses exhausted, reconstructions, evaluations, raw enumerated, excluded exact, excluded by envelope, candidates remaining (0), observation/movement/collision/timing profile ids, world epoch/revision, hypothesis ids (≤ 32) |
| `residual` | observed horizontal / rise from the nearest chain start, legal max horizontal / rise interval, bound kind (`NONE` / `SIMULATED_COMPLETE` / `ENVELOPE`), excesses, nearest excluded predictions (≤ 4) |
| `work` | timing resolutions, hypotheses, reconstructions, evaluations, physics queries, classifier operations |
| `provenance` | world epoch, world revision, anchor/target sequence, timing / movement / observation / collision profile ids |
| `next`, `nextFrontierComplete` | the union frontier for the chain's next transition and whether it is complete |
| `notes` (≤ 16) | bounded, no player data |

The constructor rejects malformed results: VALID needs a witness and a reason; UNCERTAIN needs a reason; INVALID needs `completeness.all()`,
zero legal candidates, no uncertainty reason, `NO_LEGAL_INPUT` and a proof.

## VALID SEMANTICS

`VALID` iff the union has at least one explaining step (`TimingReconstruction.Result.explaining()` non-empty): an exact Phase 6 candidate
under some timing hypothesis, or a correction application point (the target equals the correction's position). Ambiguity is fine: several
explaining hypotheses, several candidates, several input histories all yield VALID with `LEGAL_TIMING_BRANCH_FOUND` /
`EXTERNAL_MOTION_EXPLAINS` / `TELEPORT_APPLICATION_POINT` added to `LEGAL_INPUT_FOUND`. Nothing is chosen, nothing is scored. A VALID
under an incomplete context stays VALID (the found explanation is sound), but the completeness certificate reports the missing dimensions
and `nextFrontierComplete` is false, so no later INVALID can be built on it.

## UNCERTAIN SEMANTICS

`UNCERTAIN` iff no step explains the target and at least one relevant dimension is incomplete or unsupported. Every missing dimension is
named separately (never merged into one flag):

| Source | Reason(s) |
|---|---|
| unobserved chunk / region, world unavailable | `WORLD_INCOMPLETE` |
| Phase 3 `CONTENT_NOT_RETAINED` | `CONTENT_NOT_RETAINED` |
| unknown movement attribute / effect / equipment | `ATTRIBUTE_UNKNOWN`, `EFFECT_UNKNOWN`, `EQUIPMENT_UNKNOWN` |
| entity neighbourhood or push order unknown | `ENTITY_NEIGHBORHOOD_UNKNOWN` |
| world border unknown or moving | `WORLD_BORDER_UNKNOWN` |
| external motion unresolved / expired / overflowed | `EXTERNAL_MOTION_UNRESOLVED`, `EXTERNAL_MOTION_EXPIRED`, `EXTERNAL_MOTION_OVERFLOW` |
| correction outstanding across the interval | `TELEPORT_UNRESOLVED` |
| world epoch change / respawn inside the interval, epoch mismatch | `DISCONTINUITY` |
| timing gap, >3-tick horizon, rotation unresolved | `TIMING_GAP`, `UNSUPPORTED_HORIZON`, `ROTATION_UNRESOLVED` |
| fluid contact, bubble column, elytra/creative flight/spectator/vehicle/pose, other unsupported context | `UNSUPPORTED_FLUID`, `UNSUPPORTED_LOCOMOTION`, `UNSUPPORTED_CONTEXT` |
| a climbable block (ladder, vine, scaffolding) within reach of the transition (P8-F2) | `CLIMBABLE_CONTACT` |
| Phase 6 node/evaluation/tick budget, Phase 7 frontier cap, reconstruction budget, silent-closure bound | `SEARCH_LIMIT` |
| world query budget | `QUERY_LIMIT` |
| loss-class capture break (session discontinuity) | `CAPTURE_GAP` |
| an earlier incomplete step of the chain | `FRONTIER_INCOMPLETE` |
| no supported start (chain not anchored) | `START_UNAVAILABLE` |
| non-finite numbers | `NUMERIC_INVALID` |
| contained exception | `INTERNAL_ERROR` |

Certification limitations are never physical invalidity (brief 73): CAPTURE-ONLY / correlation `UNSUPPORTED` timing statuses map to
`TIMING_INCOMPLETE` / `EXTERNAL_MOTION_OVERFLOW`, and a coverage break drops the chain's anchor.

## INVALID SEMANTICS

`INVALID` iff no step explains the target **and** every dimension of the completeness certificate is true: every timing hypothesis
complete, every reconstruction complete (every legal input of every tick simulated or soundly excluded by the M3 envelope), the world
complete, attributes/effects/equipment known where relevant, entity neighbourhood and border known, no external motion unresolved, no
unsupported mechanic in contact, no search or query bound hit, the observation aligned, and the chain's frontier complete. It is a
physical statement about the modelled domain ("the complete supported hypothesis union is empty"), not a statement of motive; the
result carries the proof and the residual diagnostics. Unknown equipment is only relevant when powder snow is touched (the Phase 5
environment reports `EQUIPMENT_*` only then), so a plain INVALID does not require it.

## COMPLETENESS PROOF

The proof is the exhaustion record of the union the validator was given: `hypothesesExhausted` (every enumerated timing hypothesis),
`reconstructions`, `evaluations`, `rawEnumerated` (every raw input of every tick), `excludedExact`, `excludedEnvelope` (nodes soundly
pruned by the M3 abstract lane), `candidatesRemaining = 0`, the four profile ids, world epoch/revision and the hypothesis ids. The
completeness certificate is derived per dimension from the Phase 7 statuses (`TimingResolution.status()/complete()`), the Phase 6 coverage
record of every step (`ReconstructionResult.Coverage`: search/world/rotation/timing/epoch/physics/environment, with its typed reasons) and
the adapter's notes (frontier cap, reconstruction budget, silent-closure bound). Nothing in the proof is a number compared to a threshold.

## HYPOTHESIS UNION

`TimingReconstruction.run` enumerates every timing hypothesis of the interval (tick alignment × application tick of every outstanding
influence × correction relation), runs Phase 6 for each from the whole frontier and unions the continuations (`next()`), deduplicated by
state + pending-influence set. Phase 8 classifies that union: the explaining steps make VALID; the per-step coverage and the per-resolution
status make the certificate; `next()` becomes the next frontier. The chain therefore carries **every** legal continuation forward — a
transition is judged against the union of all legal pasts, never against one chosen past.

## TIMING INTEGRATION

Statuses consumed: `RESOLVED_UNIQUE` / `RESOLVED_AMBIGUOUS` / `NO_ACK_EVIDENCE` / `INSUFFICIENT_RTT` (complete unless flagged),
`TIMING_GAP`, `SEARCH_LIMIT`, `UNSUPPORTED_HORIZON`, `TELEPORT_UNRESOLVED`, `WORLD_DISCONTINUITY`, `SESSION_DISCONTINUITY`,
`EXTERNAL_MOTION_UNRESOLVED`, `EXPIRED`, `UNSUPPORTED`. RTT is never used by Phase 8; the marker ledger never narrows an application
frontier (`pongBoundsApplication=false` stays). The Phase 7 tests are unchanged in behaviour (their generators were refactored into
reusable `generate(seed)` functions with identical RNG order: fuzz 1981 cases / 13 626 steps / 704 teleports / 41 closures /
862 528 evaluations as before).

## RECONSTRUCTION INTEGRATION

Phase 6 is consumed through the adapter only. Two additive changes to the frozen engine, both derived from work it already does:
`InputReconstructor` nodes carry their concrete chain start (for displacement measured from the start), and the accumulator records the
nearest excluded exact predictions of the last tick (≤ 4) and the extent of the evaluated set (max horizontal, max/min rise) plus, for
single-tick requests whose M3 envelope lane covered every node and input, the envelope's outer bound. `ReconstructionResult` gained the
`Diagnostics` component with the old constructor kept; every Phase 6 gate and fixture digest is unchanged.

## PHYSICS INTEGRATION

No physics in Phase 8. The only simulator calls are `MovementValidator.restFixedPoint` (confirming that a candidate resting state is a
fixed point of two idle ticks before it may anchor a chain) and the collision query of the penetration diagnostic
(`CollisionEngine.query(view, SWIMMING 0.6×0.6 box at the observed feet, ShapeContext.UNKNOWN, epoch).definitelyObstructed()`), which
respects the client's push-out limits by testing the smallest pose box and only adds a reason to an INVALID already established by exhaustion.

## WORLD/COLLISION COMPLETENESS

World completeness comes from Phase 3/4 through Phase 6's coverage (`worldComplete`, `CONTENT_NOT_RETAINED`, `UNAVAILABLE_WORLD`,
`QUERY_LIMIT`) and from the request's `contentRetained` flag; the environment's world epoch must equal the observation's (else
`DISCONTINUITY`, never VALID — property test). An unobserved chunk in reach makes the step incomplete and the chain's frontier incomplete.

## EXTERNAL MOTION

Velocity SET / explosion ADD branches are Phase 7 hypotheses; each explaining branch adds `EXTERNAL_MOTION_EXPLAINS`. Unresolved,
expired or overflowed influences make the external-motion dimension incomplete (`UNCERTAIN`). The live runner refuses to start a side-chain
injection while an influence is pending and ends a side chain when one arrives.

## TELEPORTS

A correction's application point is VALID by `TELEPORT_APPLICATION_POINT` (the union's teleport hypothesis; no physics step). An absolute
correction (relative mask 0) re-anchors the chain (`nextFrontierComplete = result.complete()` regardless of the earlier chain). Pre-application
movement while a correction is outstanding is ordinary movement (fixture `valid_pre_application_reminder`); a correction outstanding across
the interval is `TELEPORT_UNRESOLVED`; a respawn/world change inside the interval is `DISCONTINUITY`. The live runner anchors at every
confirmed absolute correction with six nodes (poses STANDING/CROUCHING/SWIMMING × ground true/false, velocity = the correction's delta, no
jump delay, no fall distance — sound over-approximations of the server-set state).

## SUPPORTED / UNSUPPORTED LOCOMOTION

Supported: walking, sprinting, sneaking, strafing, jumping, falling, collisions, step-up, slabs, stairs, fences/walls, ice, slime, honey,
soul sand, crawling pose, knockback/velocity, explosions, corrections. Ladders/vines/scaffolding are explained when the model reproduces
them (VALID) but are **not qualified for INVALID** (`CLIMBABLE_CONTACT`, P8-F2). Unsupported (UNCERTAIN, never INVALID): any fluid
contact and bubble columns (`UNSUPPORTED_FLUID`), elytra, creative/spectator flight and a flight-capable context (`UNSUPPORTED_LOCOMOTION`
— the runner refuses to validate while `flying || canFly || creative || spectator`), vehicles/riding, powder snow with unknown equipment,
entity pushing with a relevant entity (player, boat/raft, shulker) within 4 blocks or an incomplete tracker
(`ENTITY_NEIGHBORHOOD_UNKNOWN`), levitation/slow falling beyond the Phase 5 model, the in-block push-out
(`Player.moveTowardsClosestSpace`) and >3-tick horizons.

## REASON MODEL

`MovementValidation`: `ValidReason {LEGAL_INPUT_FOUND, LEGAL_TIMING_BRANCH_FOUND, EXTERNAL_MOTION_EXPLAINS, TELEPORT_APPLICATION_POINT}`,
`UncertainReason` (27 values, table above and `CLIMBABLE_CONTACT`), `InvalidReason {NO_LEGAL_INPUT, HORIZONTAL_EXCESS, VERTICAL_EXCESS, IMPOSSIBLE_ASCENT,
IMPOSSIBLE_DESCENT, COLLISION_PENETRATION, UNEXPLAINED_DISPLACEMENT}`. `NO_LEGAL_INPUT` is always present on INVALID; the others are
diagnostics from the exhausted set; `UNEXPLAINED_DISPLACEMENT` when no axis is attributable. Reasons are enums with fixed names, never
free text; the metrics count families of them.

## RESIDUALS

The residual reports the observed displacement from the nearest chain start and the legal extent of the exhausted set: the M3 envelope's
outer bound when it covered the whole single-tick request (`ENVELOPE`), else the max/min of every simulated legal prediction when every
input was simulated (`SIMULATED_COMPLETE`), else `NONE` (no bound is claimed; a note says so). Residuals are diagnostics attached after the
rule decided; they never make or unmake a classification (metamorphic tests: adding uncertainty never yields INVALID, adding a legal
candidate turns INVALID into VALID with identical residual inputs).

## SPEED DIAGNOSTICS

`HORIZONTAL_EXCESS` = observed horizontal displacement exceeds the legal horizontal extent (the bound kind named). Controlled Speed tests:
horizontal displacement scaled ×1.01, ×1.05, ×1.1, ×1.2, ×1.5, ×2.0 on legitimate velocity-corpus transmissions (250 each): 250/250
INVALID at every factor in a complete context. The exact-double observation model (`MovementObservationProfile.PAPER_26_2`) means any
displacement outside the reachable set — even ×1.01 — has no legal candidate; the residual then says by how much the legal extent was
exceeded (`HORIZONTAL_EXCESS` when the bound is exceeded; `UNEXPLAINED_DISPLACEMENT` when the mutated position is inside the extent but
matches no prediction). No speed threshold exists anywhere.

## VERTICAL / FLY DIAGNOSTICS

`VERTICAL_EXCESS` + `IMPOSSIBLE_ASCENT` (rise above the legal maximum) / `IMPOSSIBLE_DESCENT` (below the legal minimum, e.g. hovering
where every legal continuation falls). Controlled Fly tests: upward +0.05, +0.2, +0.5, +1.0 (250 each), hover (airborne, height held: 20/20),
no-gravity (20/20), illegal step +0.7 (250/250), through-floor (250/250): all INVALID in a complete context; airborne hover fixture
`invalid_hover` pinned. Levitation / slow falling are unsupported effects in the Phase 5 model and make the effect dimension incomplete.

## WORK BOUNDS

Phase 7 `TimingBudget.DEFAULT` (hypothesis cap, frontier cap, reconstruction/evaluation budgets) and Phase 6 `SearchBudget.DEFAULT`
(nodes, evaluations, ticks, histories, reported candidates, envelope cells) bound every request; exceeding any of them is `SEARCH_LIMIT`
(never INVALID: property test `searchExhaustionNeverProducesInvalid`). The classifier itself is linear in the number of steps and
candidates (`work.classifierOperations`), allocates only the result, and holds no cache (results are values; the frontier is the only
carried state). The live runner bounds its event slice (1 024 events), recent rings (16 lines), injection (≤ 40 ticks) and drops the
anchor rather than growing.

## FALSE-POSITIVE TESTING

`ValidationCorpus` validates chains (Phase 7 → 6 → 5 → 8) over: the Phase 7 fuzz scenarios (1 981 cases / 13 626 transmissions), the
velocity corpus with and without tick ends (1 500 / 11 558 and 200 / 1 549), the teleport corpus (500 / 2 877) and the pinned Phase 5
native trajectories (88 / 2 704). Result (`false-positive-results.json`, `legitimate-corpus-results.json`, `native-validation-results.json`,
`timing-validation-results.json`): **32 314 transmissions, 32 312 VALID, 2 UNCERTAIN (`SEARCH_LIMIT`, native corpus), 0 INVALID**;
≥ 99.93 % VALID per class (gate ≥ 95 %). The truth branch was never lost (`truthLost = 0`). `ValidationFixtureMain` exits non-zero on any
false INVALID.

## IMPOSSIBLE-MOVEMENT TESTING

`ValidationCorpus.impossibleCorpus`: legitimate velocity-corpus chains up to one transmission mutated by a family (horizontal ×1.01 … ×2.0,
upward +0.05 … +1.0, hover, no-gravity, step +0.7, through-floor, 1 ulp): **3 290 mutated transmissions, 3 290 INVALID, 0 UNCERTAIN, 0 VALID**
(`detection-results.json`, `invalid-corpus-results.json`; gate ≥ 80 % on strong classes). Determined model behaviour (brief 49): under the
exact-double observation model a 1-ulp shift of a legal position is outside the reachable set in a complete context and is INVALID
(250/250); this is documented, not tuned. Fixtures pin one case per INVALID family and the boundary cases.

## REAL CLIENT QUALIFICATION

Owner session (vanilla 26.2 client `Emojiado`, local Paper 26.2-124 on 127.0.0.1:25576, both on one host, **survival** mode so that the
locomotion is supported). Full log: `docs/phase8-artifacts/real-client-evidence.log` (RCON blocks, secret-scanned; git-ignored by `*.log`,
to be force-added at baseline time like Phase 6/7's).

The session ran through six jars because it found defects (P8-F1, P8-F3, P8-F4, P8-F6 below, each fixed and re-verified live); the
**final jar `e55aca05a746a027`** (byte-identical to `anticheat-platform-paper/build/libs/anticheat-platform-paper-1.0-SNAPSHOT-plugin.jar`)
repeated the essentials. No INVALID was ever produced on the live chain in any session (engine counters on the final jar at the smoke stop:
`requests=258 VALID=258 UNCERTAIN=0 INVALID=0 internalErrors=0 injected=1`). Rounds are the evidence-log labels.

| Round | Jar | Course | Live chain result | Notes |
|---|---|---|---|---|
| S0/L0 | 16e8e090 | join in creative, switch to survival, teleport | anchor lost within a second ("event slice bound"), one early INVALID unreadable | **P8-F1** found: write outcomes filled the event slice; the 16-line ring lost the origin |
| L1 | 10cce50c | join, teleport, idle | correction anchor, application point VALID, 23/23 VALID | rest reminders VALID (2 nodes) |
| L3 round 1 | 10cce50c | walk, sprint, strafe, diagonal, jumps, sprint-jumps | 3616/3616 VALID | |
| L4 round 2 | 10cce50c | corridor wall/corner collision, slab + overhead slab, stairs + 1-block drop, fence line, cobblestone wall line | 4020/4020 VALID | |
| L5 round 3 | 10cce50c | teleport onto a 4-block tower, fall, ladder, crouch-walk, shield-walk | 4515 VALID, 1 UNCERTAIN (no continuation), anchor lost, 129 reminders START_UNAVAILABLE | **P8-F3** found (entity tracker overflow: every VALID incomplete, no rest anchor); the unexplained step recorded as P8-F2 |
| L8 round 4 | 94ad57d3 | ladder up/down, tracer armed | 254/254 VALID, tracer 220/220 steps explained (climb 0.1176/tick reproduced) | context complete (13 attributes, border known, 0 relevant entities) |
| L10 round 5 | 94ad57d3 | crouch-walk, shield-walk, shield on the ladder, sneak + shield on the ladder | 858/858 VALID | P8-F2 not reproduced |
| L12–L16 round 6 | 94ad57d3 | ice, slime (walk + bounces), honey, soul sand; then each strip alone | ice 1661/1661, slime 1865/1865, honey 1982/1982 VALID and complete; honey→soul sand step **UNCERTAIN [SEARCH_LIMIT]** (not INVALID), anchor lost | P8-F5 recorded; the frontier-incomplete origin scrolled out (notable ring fixed afterwards) |
| L18–L24 round 7 | 94ad57d3 | server velocity (0.3,0.5,0.2), knockback (`/damage` mob_attack), three velocities 300 ms apart, creeper explosion (radius 3) | 2221 VALID, 0 INVALID through every flight; influences 0→7 | injections then refused: **P8-F4** found (applied influences never retired) |
| I1–I8 | 39d447d6 | eight side-chain injections while standing | I1 0.5/tick INVALID [HORIZONTAL_EXCESS] (legal h≤0.3274, excess 0.1726); I2 0.42/tick INVALID [HORIZONTAL_EXCESS]; I3 0.31/tick INVALID [UNEXPLAINED_DISPLACEMENT]; I4 +0.1 hold INVALID; I5 +1.0 INVALID [VERTICAL_EXCESS, IMPOSSIBLE_ASCENT] (excess 0.58); I6 +0.05/tick INVALID; I7 −0.5 INVALID [IMPOSSIBLE_DESCENT, COLLISION_PENETRATION]; I8 +0.7/0.3 INVALID [IMPOSSIBLE_ASCENT] | live chain untouched (76/76 VALID); every side chain ended after its first transmission |
| L26–L29 | 39d447d6 | walk into a 3×3 water pool, out, stand | water step **UNCERTAIN [UNSUPPORTED_FLUID]**, anchor dropped; no rest anchor for two minutes one block from the pool; re-anchored at rest 4 blocks away | P8-F6 recorded (root cause found in the final pass) |
| L30/L31 | 39d447d6 | disconnect / reconnect | session 2: fresh runner, anchored at the join correction, 15/15 VALID | engine counters continue, no INVALID |
| F1–F3 | e47ecc75 | walk/sprint/jumps/sprint-jumps; honey → soul sand | 924/924 VALID (one VALID under a search bound, then a rest re-anchor); honey→soul sand 1128/1128 VALID, complete | P8-F5 did not recur |
| F4/F5 | e47ecc75 | water, then standing 1.5 and 0.36 blocks from the pool | UNSUPPORTED_FLUID; rest anchor at both distances | |
| F6/F7 | e47ecc75 | velocity, injection | flight EXTERNAL_MOTION_EXPLAINS; injection INJECTING (P8-F4 fixed) but the chain stayed incomplete while standing without moving the mouse | **P8-F6 root cause**: position-only reminders never anchored |
| G1–G5 | **e55aca05 (final)** | join, stand (no mouse), walk, stand; velocity then rest without mouse; injection 0.5/tick; disconnect/reconnect; clean stop | 99/99 VALID; flight explained then **"rest re-anchor: the chain is complete again"** without rotation; injection INVALID [HORIZONTAL_EXCESS], live 146/146 VALID; session 2 13/13 VALID; `Disabling Papea2AC`, all dimensions saved, 0 exceptions in every Phase 8 run log | PASS |

Also recorded, honestly: the course was run in survival with Resistance V for the knockback/explosion tests (`mobGriefing` off, difficulty easy only
for the creeper, restored); the Phase 7 tracer's own silent-closure cap made its round-5 trace NOT QUALIFYING (a tracer limitation, the live chain
was 858/858 VALID); the honey→soul sand break (P8-F5) and the unexplained round-3 step (P8-F2) are recorded with their numbers and did not
reproduce offline or in the final pass. Nothing was tuned to make a case pass.

## MEMORY

`memory-results.json` (forced GC + used-heap diff, 3 trials, median; thread-allocation counters; estimates on one host, not capacity):

| Measure | Bytes |
|---|---:|
| `MovementValidator` (stateless) | 16 |
| `MovementContextLedger` empty / at every bound | 368 / 54368 |
| `MovementValidationRunner` empty / anchored with the 48-line recent ring full | 1432 / 12552 |
| retained per 100 VALID results / per 100 INVALID results | 96480 / 59680 |

| Family | classifier-only alloc B/result | end-to-end alloc B/result | classification | retained result bytes |
|---|---:|---:|---|---:|
| valid_one_tick | 1,896 | 131,931 | VALID | 864 |
| valid_velocity_2_branches | 1,968 | 167,328 | VALID | 960 |
| invalid_horizontal_excess | 2,456 | 82,704 | INVALID | 592 |
| uncertain_world_incomplete | 1,608 | 749,176 | UNCERTAIN | 432 |
| two_tick_gap | 1,768 | 1,117,318 | VALID | 896 |
| three_tick_gap | 1,768 | 6,178,436 | VALID | 960 |

Per session retained: one `MovementContextLedger` and one `MovementValidationRunner` (frontier ≤ the Phase 7 frontier cap, one last
result, two 16-line rings, a ≤ 1 024-event slice); the validator is stateless; results are values the caller drops. Metrics are
`LongAdder`s with fixed labels; nothing is keyed by UUID beyond the session map.

## PERFORMANCE

`benchmarks.json` (JMH 1.37, Temurin 25, 3 forks, 5×1 s warmup, 10×1 s measurement, `-prof gc`; descriptive kernels, never a capacity
claim; final-code record taken after the baseline verification lane, with the P8-F2 climbable-reach scan included in every classify). Validation-only
(the classifier over a precomputed Phase 7 union) versus end to end (timing + Phase 6 + Phase 5 + classifier):

| Kernel | ns/op (±) | alloc B/op | Family |
|---|---:|---:|---|
| `classifyValidSimple` | 897 ± 49 | 1,320 | validation-only |
| `classifyValidVelocity` | 944 ± 19 | 1,536 | validation-only |
| `classifyInvalidHorizontal` | 1,806 ± 21 | 1,856 | validation-only |
| `classifyUncertainWorld` | 421 ± 12 | 1,120 | validation-only |
| `classifyWorstBounded` | 990 ± 12 | 1,136 | validation-only |
| `endToEndValidSimple` | 48,769 ± 1,247 | 113,360 | end to end |
| `endToEndValidAmbiguous` | 90,670 ± 2,128 | 208,169 | end to end |
| `endToEndValidVelocity` | 76,987 ± 2,697 | 195,931 | end to end |
| `endToEndUncertainWorldIncomplete` | 287,029 ± 4,591 | 722,994 | end to end |
| `endToEndUncertainSearchLimit` | 3,250 ± 131 | 10,733 | end to end |
| `endToEndInvalidHorizontal` | 41,049 ± 1,573 | 105,731 | end to end |
| `endToEndInvalidVertical` | 42,417 ± 1,118 | 105,843 | end to end |
| `endToEndTwoBranch` | 76,053 ± 2,176 | 195,889 | end to end |
| `endToEndFourBranch` | 104,433 ± 1,843 | 278,574 | end to end |
| `endToEndGap2` | 526,322 ± 10,358 | 1,326,990 | end to end |
| `endToEndGap3` | 2,458,875 ± 60,322 | 5,941,144 | end to end |
| `endToEndWorstBounded` | 1,892,573 ± 53,183 | 6,777,281 | end to end |

The classifier adds about 0.4–1.8 µs to any transition (1.1–1.9 KB allocated, the result itself; the climbable-reach scan of a few dozen block cells is included); the cost of validation is the cost of the Phase 7/6/5 union it consumes
(dominated by multi-tick silent closures and multi-influence branching, unchanged from Phase 7). Hot-path awareness: one union run per
transmission on the shard, no allocation beyond the result, no cache.

## THREADING AND FAILURE CONTAINMENT

The runner is owned by the session's shard thread (`assertOwner`); the platform reads a volatile immutable `ValidationStatus` and calls
`inject` under the runner's monitor. No main-thread pipeline, no blocking. Any exception inside `onEvent` is contained: counted
(`internalErrors`), the chain drops its anchor (`UNCERTAIN` until re-anchored), the line is recorded as `UNCERTAIN [INTERNAL_ERROR]`;
inside the validator it becomes `UNCERTAIN / INTERNAL_ERROR`; inside the side chain it ends the side chain only. Session replacement gives a
fresh runner (engine test); a world change or coverage break drops the anchor and clears the slice and influences. Server ticks and write
outcomes never enter the runner's slice (P8-F1); the status is re-published only for events that can change it.

## KNOWN LIMITATIONS

1. **Exact observation model.** Any transmitted position outside the exact reachable set is INVALID in a complete context. This is the
   Phase 6 model (`MovementObservationProfile.PAPER_26_2`: exact doubles); a client-side effect the model does not know (a mod's rounding,
   an unmodelled mechanic) would make the context incomplete only if Phase 5/6 report it; otherwise it would show as INVALID. The real-client
   qualification over the supported course produced no INVALID; Phase 9 must not act on INVALID without the completeness certificate and
   the residual being reviewed for exactly this class.
2. **Anchoring.** A chain needs a supported start: a confirmed absolute correction (join, teleport) or a 20-tick rest at the same position with
   the ground claim (position-only reminders included, P8-F6; the rest fixed point must be exact, so a rest inside or touching an unsupported
   mechanic never anchors). Until then every transmission is `UNCERTAIN / START_UNAVAILABLE`; after an unknown-world step, an unsupported
   mechanic or a lost continuation the chain re-anchors at the next correction or rest, and an anchored chain whose union became incomplete
   (a search bound, a velocity inside a silent stretch) re-anchors at the next rest once no influence is pending.
3. **Entity neighbourhood.** Any player, boat/raft or shulker within 4 blocks (or an incomplete entity tracker) makes every transition UNCERTAIN
   (`ENTITY_NEIGHBORHOOD_UNKNOWN`); entity pushing is not modelled.
4. **Unknown equipment.** Equipment is never known to the runner (the inventory is not observed); it only matters on powder snow.
5. **Unsupported locomotion set** as listed; fluids in particular make a large part of any real world UNCERTAIN.
6. **Search-bound labelling.** A silent closure that stops on an incomplete world is reported by Phase 7 as a bound (`SEARCH_LIMIT` next to
   `WORLD_INCOMPLETE`); the metric family precedence puts world incompleteness first.
7. **Side-chain injection** drops markers and pending influences (sound: it only widens the timing union) and is a qualification
   instrument, not a test of packet handling.

## DEFERRED TO PHASE 9+

Any consequence of a result: violation levels, alerts, setbacks, punishments, temporal accumulation, per-player history, the policy that
decides which INVALID/UNCERTAIN patterns matter, external configuration, dashboards. Also deferred: fluid physics, entity pushing, vehicles,
elytra, levitation/slow falling, the in-block push-out, an observed-equipment source.

## FINAL PRE-BASELINE DECISIONS

Decision pass performed 2026-09-21. Final jar `280db1652624420a6ea4167489cc8627ec1da54bbcb5ad3409358ae201a7532c` (byte-identical to
`anticheat-platform-paper/build/libs/anticheat-platform-paper-1.0-SNAPSHOT-plugin.jar` and to the jar the final manual ladder
re-check ran on; test 647/647, nativeTest 137/137, gates reproduced). A one-line "reason refinement" of `climbableInReach` (an unknown cell
would no longer add `CLIMBABLE_CONTACT` next to `WORLD_INCOMPLETE`) was tried after that re-check and **withdrawn before the baseline**: strict
classification invariance could not be proven for one corner (an unknown cell inside the ±1-block margin that the physics never queried
would have turned a conservative UNCERTAIN into an INVALID), so the baseline ships exactly the live-qualified code; the redundant reason is
cosmetic (`ValidationEngineIntegrationTest` pins the pair `[WORLD_INCOMPLETE, CLIMBABLE_CONTACT]`). Production changes made in this pass, each
re-verified live: the classifier's safety net (an adapter-level incompleteness that no dimension named forbids INVALID as `SEARCH_LIMIT`),
the live-INVALID log sink (`Metrics.invalidSink`, platform display only) and the `CLIMBABLE_CONTACT` rule below. None can turn an UNCERTAIN
or a VALID into an INVALID; the corpora and fixtures were regenerated (legitimate 32 314 / 0 INVALID, impossible 3 290 / 3 290, 17 fixtures).

**P8-F2 CLASSIFICATION: A — RESOLVED DEFECT (scoped).** The round-3 observation (jar 10cce50c, live chain, `UNCERTAIN [FRONTIER_INCOMPLETE]`,
1 complete timing hypothesis, 1 complete reconstruction, every dimension of the step true except the inherited frontier flag, nearest
`W+A, sneak, using item` 1.626e-4 off in z on the ladder, never INVALID, preceding steps VALID, following START_UNAVAILABLE) was
**reproduced in this pass as two live INVALIDs on a legitimate player** (jar 8fedca08, targeted repeat of shift + W+A / W+D with the shield
raised on the ladder): seq 22037 (ground, ladder base, h = 0.1416, nearest strafe prediction 2.9e-2 away) and seq 32883 (climbing, rise
0.1176, nearest `W` 5.6e-5 away), both `completeness ALL`, 1 hypothesis, 144 evaluations, `UNEXPLAINED_DISPLACEMENT` - the safeguard sink
recorded them in full. No packet replay exists for either (the recorder was not armed); the synthetic ladder chains are VALID, so the
discrepancy is a client/model difference the Phase 5 differential does not cover (sideways input against a ladder). Resolution within Phase
8's scope: `MovementValidator.climbableInReach` — a climbable block within one block horizontally and from one below to two above the feet
of any frontier start or the target (unknown cells and oversized regions count as contact) makes the mechanic dimension incomplete:
`CLIMBABLE_CONTACT`, VALID when a legal candidate explains the step, UNCERTAIN otherwise, never INVALID. Regression
`ValidationSafetyTest.aClimbableWithinReachForbidsInvalidButKeepsLegitimateClimbsValid` (impossible next to the ladder → UNCERTAIN, a
12-tick climb → VALID with the mechanic dimension incomplete, the same impossible step three blocks away → INVALID) and fixture
`uncertain_climbable_contact`. Live re-verification on the final jar: the identical routine 322 VALID / 2 UNCERTAIN / 0 INVALID, log sink
empty. The model discrepancy itself is recorded for a Phase 5 follow-up (a ladder + sideways-input native differential scenario); Phase 8's
INVALID cannot reach it any more.

**P8-F5 CLASSIFICATION: B — EXPECTED BOUNDED SEARCH EVENT.** Jar 94ad57d3, live chain, round 6d. `UNCERTAIN [SEARCH_LIMIT]`, completeness
missing [reconstruction, search]; never INVALID. Timing: 1 hypothesis, complete. Reconstruction: 4 calls (the silent closure of the 2-node
rest frontier at the honey/stone boundary, then the first walking tick, observed h = 0.098), the Phase 6 `SearchBudget.DEFAULT` bound reached
inside the closure. World and collision complete. No continuation, anchor dropped, later re-anchored at rest. Final jar: the same honey →
soul sand crossing 1128/1128 VALID and complete; offline the same walk is VALID (`ValidationLiveReproTest`). SEARCH_LIMIT → UNCERTAIN is
enforced in the classifier, by the property test `searchExhaustionNeverProducesInvalid` (40 seeds), by the fixture `uncertain_search_limit`,
by `ValidationSafetyTest` (query limit) and by the result constructor.

**INVALID STRUCTURAL SAFETY: PASS.** `MovementValidationResult`'s constructor rejects INVALID unless all 13 completeness dimensions are true,
`legalCandidates == 0`, no witness, no uncertainty reason, `NO_LEGAL_INPUT` present and an exhaustion proof with `hypothesesExhausted > 0`
and `candidatesRemaining == 0` (`ValidationSafetyTest.theConstructorRejectsInvalidWheneverAnySingleCompletenessDimensionIsFalse` proves each
dimension individually). Mapping of the reviewer's list onto the dimensions: timing → `timing`; reconstruction → `reconstruction`; world and
collision → `world` (Phase 4 collision incompleteness reaches Phase 8 as Phase 6 `worldComplete=false` / `UNAVAILABLE_WORLD`) plus
`reconstruction`; attributes → `attributes`; effects → `effects`; external motion → `externalMotion`; supported locomotion →
`mechanicSupported` (fluids, flight, vehicles, climbables); observation → `observation`; search → `search`; query limit → `search`
(+ `QUERY_LIMIT`); epoch/session continuity → `observation` (+ `DISCONTINUITY` / `CAPTURE_GAP`); numeric validity → `observation`
(+ `NUMERIC_INVALID`); plus `equipment`, `entities`, `border`, `frontier`. The classifier is the only producer (architecture test);
`refuse`/`unavailable` build UNCERTAIN only; the runner and the platform never construct a result. Safety net: an adapter
`Result.complete=false` that no dimension named is treated as a search bound.

**METAMORPHIC SAFETY RECHECK: PASS.** VALID + legal branch → VALID and INVALID + legal branch → VALID (`ValidationPropertyTest`, 120 seeds);
INVALID + incomplete branch → UNCERTAIN, QUERY_LIMIT → UNCERTAIN (legitimate and impossible observation alike), UNRESOLVED external motion →
UNCERTAIN, climbable in reach → never INVALID (`ValidationSafetyTest`); UNCERTAIN + more uncertainty never INVALID, SEARCH_LIMIT / unknown
world / CONTENT_NOT_RETAINED / unknown attribute / unknown effect / entities / border → UNCERTAIN (`ValidationPropertyTest`,
`MovementValidatorTest`, fixtures); unsupported fluid (fixture + live water), flight / creative / spectator / unknown game mode (runner
refuses: `MovementValidationRunnerTest`, live creative join and hover), vehicles (Phase 5 `LOCOMOTION_UNSUPPORTED` → `UNSUPPORTED_LOCOMOTION`
through `mechanic()`).

**EXACT OBSERVATION MODEL DECISION: ACCEPTED FOR PHASE 8.** Phase 8 keeps the exact-double observation model of Phase 6. This is safe for
Phase 8 only because INVALID additionally requires the complete supported model in every dimension (climbables now excluded), and because the
legitimate corpus (32 314 transmissions), the native corpus (2 704) and the live qualification on the final code show zero false INVALIDs.
No tolerance was introduced in this pass. P8-F2 is the concrete example of what an exact model does when the client and the model differ by
5e-5: it is why INVALID must stay a physical-explainability result and never an enforcement decision. Forward requirement (recorded here and in
`verification.json`): **PHASE 9 MUST NOT TREAT A SINGLE INVALID CLASSIFICATION AS ENFORCEMENT EVIDENCE WITHOUT READING** the completeness
certificate, the invalid proof, the residual diagnostics (a P8-F2-like case shows as `UNEXPLAINED_DISPLACEMENT` with a residual far below any
physical excess and `excess h = v = 0`), the context provenance, the timing/reconstruction completeness and the unsupported/uncertain
reasons. Phase 9 owns evidence policy; Phase 8 owns only the physical explainability of one transition.

**PHASE 9 EVIDENCE BOUNDARY: DOCUMENTED.** Production code contains no violation level, evidence accumulation, strike counter, decay, staff
alert, setback, kick, ban, punishment or enforcement threshold (searched; the only matches are Phase 1 infrastructure health alerts, Phase 5
physics constants named "threshold" by vanilla, the Phase 7 RTT outlier bound, and the word "setback" describing a server correction packet).
INVALID remains a per-transition physical-explainability result.

**CONTROLLED INJECTION ISOLATION: SIDE-CHAIN ONLY — PASS.** The runner holds no `PlayerTimeline`, `ObserverWorld`, `TimingLedger` or
`MovementContextLedger` reference (it receives published views), never calls `apply()`, and `inject` is reachable only through
`PacketEngine.validationInject` from the platform command (architecture test). A side chain copies the live chain, lives in its own doubled
sequence space, passes no live corrections or influences, is refused while an influence is pending and ended by a live correction, influence,
world change or coverage break; its results are counted as `injectedRequests` only and never enter the live rings, the live counts, the log
sink, the timing ledger, the world or the timeline (`MovementValidationRunnerTest`: world revision, ledger influences/teleports/movement
counters and live counts unchanged; `ValidationEngineIntegrationTest`: live requests unchanged; live: 76/76, 558/558 and 146/146 VALID around
the injections).

**LIVE FALSE-POSITIVE AUDIT.** Final code (jar 280db165): 322 VALID / 2 UNCERTAIN / 0 INVALID on the ladder routine that had produced the
two P8-F2 INVALIDs on 8fedca08. Earlier code states on the same session day: e55aca05 and 8fedca08 (before the climbable rule) 258/258 and
558/558 VALID on the general course, then the two P8-F2 INVALIDs on the targeted ladder repeat (now impossible by construction); 10cce50c,
94ad57d3, 39d447d6, e47ecc75: 0 live INVALID across ≈14 000 transitions; 16e8e090: ONE unreadable live INVALID (P8-F7). Side-chain
injections are not live-chain INVALIDs.

**CORPUS AUDIT (regenerated on the final code, frozen Phase 1–7 artifacts untouched):** legitimate 32 314 / 0 INVALID; native 2 704 / 0
INVALID; timing corpus 29 610 transmissions VALID with the true branch retained; impossible 3 290 / 3 290 INVALID; search-limited
legitimate cases UNCERTAIN (2).

## FORWARD DEFECTS (P8-F<n>)

| Id | Found | Status | What |
|---|---|---|---|
| P8-F1 | live, first look | FIXED (+ test) | outbound write outcomes (`WriteResolved`, hundreds per second of entity traffic) filled the live event slice: "event slice bound" dropped the anchor within a second. Write outcomes never enter the slice. |
| P8-F2 | live, round 3 (jar 10cce50c) and the decision pass (jar 8fedca08), live chain | RESOLVED DEFECT (scoped, + test + fixture); model discrepancy handed to Phase 5 follow-up | Round 3: `UNCERTAIN [FRONTIER_INCOMPLETE]`, nearest `W+A, sneak, using item` on the ladder off by 1.626e-4 in z (rise 0.1176 = ladder climb tick), every other dimension complete - protected from INVALID only by the inherited frontier flag. Decision pass, targeted repeat (shift + W+A / W+D with the shield raised on the ladder): **two live INVALIDs on a legitimate player** - seq 22037 `[NO_LEGAL_INPUT, UNEXPLAINED_DISPLACEMENT]` h = 0.1416 on the ground at the ladder base, nearest `D` off by (1.65e-2, 0, 2.43e-2); seq 32883 rise 0.1176 (climbing), nearest `W` off by 5.6e-5 in z; completeness ALL, 1 hypothesis, 1 reconstruction, 144 evaluations each - i.e. false INVALIDs from an unexplained model/client discrepancy on ladders. Fix: `CLIMBABLE_CONTACT` - a climbable block within one block horizontally / -1..+2 vertically of any frontier start or the target makes the mechanic dimension incomplete (VALID when explained, UNCERTAIN otherwise, never INVALID); regression `ValidationSafetyTest.aClimbableWithinReachForbidsInvalidButKeepsLegitimateClimbsValid`, fixture `uncertain_climbable_contact`. Re-verified live on the final jar: the same routine 322 VALID / 2 UNCERTAIN / **0 INVALID**. The discrepancy itself (5e-5 .. 3e-2 on ladders with sideways input) is a Phase 5 model question, recorded for follow-up; it is not reachable as INVALID any more. |
| P8-F3 | live, round 3 | FIXED (+ test) | the context ledger tracked every entity the client was shown; an ordinary world overflowed the 256 bound, the neighbourhood became UNKNOWN, every VALID incomplete and the rest anchor impossible. Only players, boats/rafts and shulkers are tracked (bound 1024, honest overflow). |
| P8-F4 | live, round 7 | FIXED (+ test) | applied influences were never retired from the runner's scope (stale influences in every request, injections refused). Retired once no continuation has them pending, and after the profile horizon. |
| P8-F5 | live, round 6d, jar 94ad57d3, live chain | EXPECTED BOUNDED SEARCH EVENT | `UNCERTAIN [SEARCH_LIMIT]` at seq 624972→625397, completeness missing [reconstruction, search]: one timing hypothesis (complete), four Phase 6 reconstruction calls (the silent closure of a 2-node rest frontier at the honey/stone boundary, then the first walking tick, observed h = 0.098), the Phase 6 `SearchBudget.DEFAULT` bound reached inside the closure, world/collision complete, no continuation. Never INVALID. Offline (`ValidationLiveReproTest`) and on the final jar (F3: 1128/1128 VALID) the same crossing is VALID; the same class recurred once on the final jar as a VALID under a search bound (F2) and the chain re-anchored at rest. |
| P8-F7 | live, first look, jar 16e8e090 (pre-fix), live chain | NON-REPRODUCED OBSERVATION; safeguard added | the engine counted ONE live INVALID (chain `transitions=20 VALID=18 UNCERTAIN=1 INVALID=1`, `anchors=3/lost=3`) during the day-one flow: join in creative → refused (UNSUPPORTED_LOCOMOTION) → `gamemode survival` + `effect clear` + `tp` → INVALID on the second validated transition → rest anchor → 18 VALID. Its description scrolled out of the then 16-line ring before it could be read; that build carried P8-F1/F3/F4/F6, and the join spot is next to the Phase 4 course whose ladder station is 40 blocks away (so P8-F2's mechanism does not obviously apply). Because the owner is a legitimate player, it was by definition a false INVALID of that superseded build; its transition is unknown. The identical flow was repeated twice on the final code (hovering in creative during the switch, and joining in creative): `UNSUPPORTED_LOCOMOTION` then a clean re-anchor, 0 INVALID. Safeguard: every live INVALID is now written in full to the server log through the platform sink (`Metrics.invalidSink`, display only) and kept in the notable ring, so no live INVALID can be lost again - which is exactly how P8-F2 was then caught in full. |
| P8-F6 | live, water test / final pass | FIXED (+ test) | the standing client's 20-tick reminder is position-only (PosRot only when the view moved) and both rest-anchor paths required a rotation: rest anchoring worked only when the owner moved the mouse. The yaw falls back to the last transmitted one; confirmed live ("rest re-anchor" without any mouse movement). |

Design changes made during the session (not defects): rest re-anchoring of an anchored-but-incomplete chain; the `notable` ring (INVALID / UNCERTAIN /
VALID-under-incomplete with the origin kept); the context line and the rest-anchor diagnosis in `validate view`; the recent ring at 48 lines; the sound outer
extent for correction anchors (envelope ∪ exact simulation); the search-limit metric family ranked after world/context incompleteness.

## ACCEPTANCE MATRIX

| Criterion | Result |
|---|---|
| Validation owner = `anticheat-simulation`; core composes; platform displays | PASS (architecture test) |
| Hard phase boundary: no VL, alert, setback, punishment, threshold, "closest", cheat brand in any Phase 8 file; no Phase 9 code | PASS |
| Rich immutable result: classification, typed reasons, completeness certificate, witnesses, proof, residuals, work, provenance | PASS |
| VALID semantics (any explaining branch; ambiguity fine; nothing chosen) | PASS |
| UNCERTAIN semantics (every incomplete/unsupported dimension named; 26 reasons) | PASS |
| INVALID semantics (complete union empty; exhaustion proof; central rule + constructor enforcement) | PASS |
| Uncertainty dominance implemented centrally | PASS (property tests) |
| Full Phase 7 timing union consumed; no timing/physics/reconstruction duplicated | PASS (architecture test; 16 610 timing-corpus transmissions VALID) |
| Full Phase 6 candidate union consumed; witnesses with history counts | PASS |
| External motion (velocity/explosion) branches; unresolved → UNCERTAIN | PASS (synthetic + live) |
| Teleport safety (application point, pre-application, unresolved, discontinuity, epoch mismatch) | PASS |
| Unsupported mechanics → UNCERTAIN (fluid, flight, creative, spectator, vehicles, entities, border, >3 ticks, search/query limits, capture gap) | PASS (fixtures, property tests, live water / creative) |
| Residual / Speed / Fly diagnostics derived from the simulated legal set only; no thresholds | PASS |
| Collision-penetration diagnostic respecting push-out limits | PASS (fixture + live I7) |
| No temporal accumulation / VL / alerts / setbacks | PASS |
| Deterministic fixtures for the 12 listed kinds | PASS (17 cases, pinned digests, `ValidationFixtureTest`) |
| Golden legitimate corpus; ZERO false INVALID; ≥ 95 % VALID per supported class | PASS (32 314 transmissions, 0 INVALID, ≥ 99.93 % VALID) |
| Native corpus validation never INVALID | PASS (88 trajectories, 2 704 transmissions) |
| Synthetic impossible corpus ≥ 80 % INVALID on strong classes; detection report | PASS (3 290 / 3 290 INVALID) |
| Boundary and 1-ULP cases (documented model behaviour) | PASS |
| Speed tests 1.01×…2.0×; Fly tests | PASS (synthetic 250/250 each; live injections) |
| No cheat brand dependency | PASS |
| Property + metamorphic tests | PASS (6 properties) |
| Legitimate and impossible fuzz | PASS (seeded corpora) |
| Memory measured; bounded runner/ledger; no UUID metric labels | PASS (`memory-results.json`) |
| JMH for the listed kernels; validation-only vs end-to-end | PASS (`benchmarks.json`, 17 kernels) |
| Threading (shard-owned, no main-thread pipeline); failure containment | PASS (engine test, contained INTERNAL_ERROR path) |
| Session replacement / world epoch safety | PASS (engine test + live reconnect) |
| `/papea2ac validate <player>` diagnostic; status line | PASS |
| Real-client legitimate qualification (idle, walk, sprint, strafe, diagonal, jump, sprint jump, repeated jumps, fall, wall/corner, slab, stairs, fence, ice, slime, honey, soul sand, ladder, crouch, use-item, knockback, explosion, teleport, multiple velocities, disconnect/reconnect) with no INVALID | PASS (final jar + earlier jars; 0 live INVALID in every session) |
| Real-client controlled impossible qualification (horizontal excess, hover, upward excess, collision penetration, illegal step) | PASS (8 injections INVALID on jar 39d447d6, repeated on the final jar) |
| Live uncertainty test | PASS (water → UNSUPPORTED_FLUID) |
| Paper smoke (clean stop, 0 exceptions) | PASS |
| Phase 1–7 regression (nativeTest, pinned gates, native differential, fixture regeneration, Phase 7 statistics) | PASS |
| Forward-defect protocol P8-F<n> | PASS (7 recorded: 5 fixed with tests, 1 expected bounded search event, 1 non-reproduced observation on a superseded build with a safeguard) |
| Commit / baseline / Phase 9 | NOT DONE BY DESIGN (owner gate) |
