# Phase 5 human approval record

Date: 2026-09-20.

The human project owner reviewed `docs/PHASE_5_IMPLEMENTATION.md` (implementation report, final physics native
differential, multi-tick drift, movement property coverage, JMH/memory qualification, Paper smoke, real-client
controlled-input physics qualification) together with its verification artifacts under `docs/phase5-artifacts/`,
performed the real-client controlled-input qualification personally (in-game `Emojiado`), and authorized creation
of the Phase 5 implementation baseline. This record is written by the implementation engineer at the owner's
instruction; the engineer does not self-approve. No independent Senior Code Review is claimed - the current project
workflow does not require one.

```
PHASE:
Phase 5 - Physics Simulator

STATUS:
HUMAN APPROVED FOR BASELINE

IMPLEMENTATION ENGINEER:
Claude

PARENT:
8bc0cc1ba63dc946003584aaec215f7087308544

PHASE 4 IMPLEMENTATION BASELINE:
1685e96519f2eaec3f5b28c12d687e8118809b64

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

TESTS:
test 484
nativeTest 132
unique total 616
0 failed
0 skipped

PHASE 1 COVERAGE:
127/127

PHASE 1 MANDATORY GAPS:
0

PHYSICS OWNER MODULE:
anticheat-physics (inputs: model, version, world, collision; output: core; platform-paper displays only,
no physics edge; physics never imports player, Bukkit or NMS)

PHYSICS BOUNDARY:
PASS (explicit-input single-tick transfer function; no input enumeration, no input reconstruction,
no min-error search, no VALID/INVALID, no cheat/violation/alert/setback/punishment vocabulary;
ArchitectureTest physicsModuleStaysWithinThePhase5Boundary)

PHYSICS SIMULATOR:
PASS

MOVEMENT PROFILE:
paper-26.2-124-movement (movement-profile sha256 4df74792…, sine table sha256 87a67a39…,
block movement table sha256 de9ab636…: 1196 blocks / 32366 states / 11792 fluid states / 40 effects / 36 attributes)

MOVEMENT PROPERTY COVERAGE:
1196/1196 blocks classified, 0 unexplained gaps
(TRANSPORT_UNSUPPORTED 3, FLUID_DEFERRED 3, INSIDE_EFFECT 4, BOUNCE 17, FRICTION_SPECIAL 4, SPEED_FACTOR 1,
 CLIMBABLE 9, NON_MOTION_OVERRIDE 63, DEFAULT 1092)

NATIVE DIFFERENTIAL:
PASS - oracle = the actual pinned vanilla movement path executed inside the Paper 26.2 build 124 server process
(LivingEntity.aiStep / Entity.move on a connection-less server player; never a re-implementation, never
Paper/Moonrise where it differs from the client). 91 scenarios, 88 compared, 3 fluid scenarios explicitly
deferred (UNSUPPORTED_FLUID), 88/88 single-step exact, 88/88 chained exact, 3105/3105 ticks bit-exact,
worst absolute difference 0.0, 0 oracle errors.

MULTI-TICK DRIFT:
0.0 at 2 / 5 / 20 / 100 ticks (chained prediction vs. the native trajectory)

PINNED TRAJECTORIES:
88 native trajectories pinned (docs/phase5-artifacts/physics-fixtures/native-trajectories.json), replayed
deterministically, digests stable across regeneration

UNCERTAINTY MODEL:
PASS (world coverage, unknown attribute/effect/equipment/entity-neighbourhood/world-border, unresolved external
motion, unsupported mode/pose/fluid/border/block-behaviour and inside-block start are explicit statuses;
UNKNOWN != AIR; incomplete knowledge is never reported as EXACT)

WORK BOUNDS:
PASS (64 collision queries / 16384 cells / 512 inside cells / 2048 entity operations per step;
QUERY_LIMIT reported, never silently truncated)

REAL CLIENT PHYSICS QUALIFICATION:
PASS (owner's Minecraft Java 26.2 client, Paper 26.2 build 124, 2026-09-20; 31 RCON evidence blocks,
0 secret matches; the qualified plugin jar sha256 19c340ee0d7a3175… is byte-identical to the jar rebuilt from
the final code). Clean controlled-input traces, all with zero physics residuals:
release-all-keys 30/30 idle samples at rest; hold W 40/40 bit-exact; hold D 40/40 bit-exact;
W+sprint 40/40 bit-exact; jump stationary 40/40 bit-exact; W+jump 40/40 bit-exact;
W+shift 40/40 bit-exact (status UNKNOWN_ENTITY_NEIGHBORHOOD: a dropped item within 8 blocks).

TIMING-AMBIGUOUS TRACE:
W+sprint+jump - NOT QUALIFYING, NOT A PHYSICS FAILURE
(1-2 tick key-onset skew between the declared keys and the client's actual input in S8/S8b; the sprint-jump
impulse itself is bit-exact in the native lane; declaration ambiguity belongs to Phase 6, not to the physics)

P5-F1 (fall distance float narrowing, fallDistance - (double)(float)dy):
DOCUMENTED / FIXED / VERIFIED (native differential, every airborne scenario)

P5-F2 (Slow Falling / Levitation reset fall distance before travel):
DOCUMENTED / FIXED / VERIFIED (native differential, slow_falling)

P5-F3 (fall-damage-resetting point ray, exact traverseBlocks + fall_damage_resetting tag / water):
DOCUMENTED / FIXED / VERIFIED (native differential, fall_20 / explosion_launch)

P5-F4 (void_air outside the dimension build height, KNOWN not UNKNOWN):
DOCUMENTED / FIXED / VERIFIED (matrix fallIsBoundedByTerminalVelocity + native differential)

P5-F5 (per-step allocation 16.5 KB -> ~10 KB: calculateValue fast paths, lazy modifier copies, primitive cell sets):
DOCUMENTED / FIXED / VERIFIED (property test allocation bound, JMH -prof gc, memory harness)

FLUID PHYSICS:
EXPLICITLY DEFERRED (detected and refused with UNSUPPORTED_FLUID; 3 differential scenarios deferred by design)

ENTITY PUSHING:
PARTIAL-BY-SPEC (explicit pusher list only; UNKNOWN_ENTITY_NEIGHBORHOOD when the neighbourhood is not known,
PUSH_ORDER_AMBIGUOUS when more than one pusher; not natively differential-tested)

PERFORMANCE QUALIFICATION:
PASS (JMH 1.37, Temurin 25, 3 forks, 5x1s/10x1s, -prof gc; idle 2650.5 ns, walk 3497.1 ns, sprint 3566.6 ns,
airborne 2212.6 ns, jump 2404.4 ns, wall 4207 ns, step-up slab 4853.2 ns, stairs/slab 9-step course 28760 ns,
20-tick chained walk 66812.8 ns; candidate scaling 1/8/32/64 = 3.354/24.727/112.477/226.161 us, linear;
descriptive kernels, not capacity claims - see docs/phase5-artifacts/benchmarks.json)

MEMORY QUALIFICATION:
PASS (block movement table 1.66 MB + movement profile 0.28 MB shared once; behaviour cache estimate 1.62 MB;
stateless simulator, nothing retained per player; per-step allocation 8 347 (jump) - 14 417 (ladder) B in the
harness, 5 152 - 12 811 B/op under JMH, ~10 KB typical - a known optimization target)

PAPER SMOKE:
PASS (Done (5.665s), 0 exceptions, 507 diagnostic steps, clean Disabling with the player connected)

PHASE 0 DRIFT:
NONE

PHASE 1/2/3/4 HISTORICAL BASELINES:
UNCHANGED

PHASE 3 FORWARD ADDITION:
BlockBehaviorQuery - a world-owned, read-only movement-behaviour reader over WorldView (WORLD_MODEL W3);
additive, no Phase 3 semantics changed

PHASE 2 FORWARD ADDITION:
TimelineStatus.movementSequence - additive field, no timeline semantics changed

PHASE 4 COLLISION API:
FROZEN AND UNTOUCHED (consumed through CollisionEngine.query, AxisClip.clip, PlayerBox, ShapeContext)

PRODUCTION TRANSPORT:
CAPTURE-ONLY

AUTHORITATIVE OUTCOME CORRELATION:
UNSUPPORTED

SCHEMA:
2 current, 1 readable (unchanged)

PHASE 6:
NOT STARTED
```

## Scope of this approval

Approval covers the Phase 5 scope as documented: the platform-independent, explicit-input, single-tick vanilla
movement transfer function (`PhysicsSimulator` with `MovementGeometry`, `InsideBlockSampler`, `FallResetRay`,
`PhysicsKernels`), the version-pinned movement profile and block movement table extracted through the controlled
nativeTest process (attributes with the client's fastutil iteration order, effects, the `Mth.SIN` table), the
explicit uncertainty vocabulary (`PhysicsResult`/`Uncertainty`), the work budget, the world-owned
`BlockBehaviorQuery` forward addition, the core composition (`PhysicsEngineStatus`, `PhysicsQualification`), the
`/papea2ac physics` declared-input diagnostic, the native differential/multi-tick/trajectory/coverage/JMH/memory
harnesses and their artifacts. It does not approve any input reconstruction, candidate enumeration, minimum-error
search, verdict, check, setback or punishment behaviour - none exists.

## Review facts preserved with this approval

- Oracle: the native differential drives the ACTUAL pinned vanilla movement path inside the Paper process
  (`OraclePlayer extends ServerPlayer`, connection-less, `isClientAuthoritative=false`); Moonrise is never
  substituted where it differs from the client. 88/88 scenarios exact, 3105/3105 ticks bit-exact single-step and
  chained, drift 0.0 at 2/5/20/100 ticks, 0 oracle errors; 3 fluid scenarios deferred by design.
- Real client: the qualified jar sha256 matched the rebuilt final jar. Every trace whose declared keys matched what
  the client actually sent is bit-exact for its full window (rest, W, D, W+sprint, jump, W+jump, W+shift). The
  W+sprint+jump trace is NOT QUALIFYING because of a 1-2 tick key-onset skew (TIMING AMBIGUOUS): it must not be
  rewritten as a PASS, and it is not a physics failure.
- Fluids are refused with UNSUPPORTED_FLUID; entity pushing is PARTIAL-BY-SPEC (UNKNOWN_ENTITY_NEIGHBORHOOD,
  PUSH_ORDER_AMBIGUOUS); elytra, ability flight, vehicles, riptide, sleeping, spectator, swimming, world border
  proximity (UNSUPPORTED_BORDER / UNKNOWN_WORLD_BORDER) and `LocalPlayer.moveTowardsClosestSpace` push-out
  (INSIDE_BLOCK_START) are explicit deferrals.
- Allocation: 5 152 - 12 811 B/op under JMH, ~10 KB per typical step; an optimization target, not a blocker.
- P5-F1 .. P5-F5 are preserved individually in `PHASE_5_IMPLEMENTATION.md` §31 with originating component,
  failure, root cause, fix, regression test and native verification; no history was rewritten to hide them.
- Environmental / non-reproduced flakes (not Phase 5 defects): one JUnit `@TempDir` deletion failure in the
  pre-existing Phase 2 `LiveRecordingTest` on one Windows clean run (re-runs passed); one poller-starvation miss in
  `PhysicsQualificationTest` under the parallel `--rerun-tasks` load (samples 11/12), hardened test-side only
  (the synthetic timeline waits for the poller's fetch); the final clean runs passed 484/484 and 132/132.

## Known limitations carried into the baseline (all explicit, see PHASE_5_IMPLEMENTATION.md §32)

- Fluids / swimming / bubble columns: detected, deferred (UNSUPPORTED_FLUID).
- Elytra, ability flight, vehicles, riptide, sleeping, spectator: UNSUPPORTED_MODE.
- Entity pushing: explicit pusher list only, partial by specification; not natively differential-tested.
- World border: observer state is absent; border knowledge is a caller input (UNSUPPORTED_BORDER when near).
- `moveTowardsClosestSpace` push-out is not modelled (INSIDE_BLOCK_START).
- Shift-key (crouch slowdown) tick-1 timing is a documented client-side assumption (held in the S7b trace).
- Phase 7 timing (packet-to-tick alignment) is unresolved by design; Phase 6 candidate multiplication is ~linear
  in the number of explicit inputs.
