# Phase 4 human approval record

Date: 2026-09-20.

The human project owner reviewed `docs/PHASE_4_IMPLEMENTATION.md` (implementation report, native differential
verification, Paper smoke, real-client collision qualification) together with its verification artifacts under
`docs/phase4-artifacts/`, performed the real-client obstacle-course qualification personally, and authorized creation
of the Phase 4 implementation baseline. This record is written by the implementation engineer at the owner's
instruction; the engineer does not self-approve. No independent Senior Code Review is claimed - the current project
workflow does not require one.

```
PHASE:
Phase 4 - Collision Engine

STATUS:
HUMAN APPROVED FOR BASELINE

IMPLEMENTATION ENGINEER:
Claude

PARENT:
d9d50661c899f6c852247472d7ffde15dca3e78a

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

TESTS:
test 416
nativeTest 124
unique total 540
0 failed
0 skipped

PHASE 1 COVERAGE:
127/127

PHASE 1 MANDATORY GAPS:
0

COLLISION OWNER MODULE:
anticheat-collision (inputs: model, version, world; output: core; platform-paper displays primitives only)

COLLISION ENGINE:
PASS

SHAPE COVERAGE:
1196 blocks / 32366 states / 325 unique shapes / 0 unexplained unsupported
(242 empty/non-colliding, 427 full cube, 121 exact, 383 state-derived exact, 0 neighbour-derived,
 3 position-derived exact, 1 context-derived exact, 19 conservative/uncertain by spec)

NATIVE DIFFERENTIAL:
PASS - oracle = the vanilla client algorithms present in the pinned Paper 26.2 build 124 jar
(BlockStateBase.getCollisionShape + DiscreteVoxelShape grid for all 32366 states, vanilla VoxelShape.collideX for
58 320 clip cases, the private index-merger join for 12 960 candidate-acceptance cases, Mth.getSeed offsets,
EntityCollisionContext scaffolding contexts, EntityDimensions.makeBoundingBox avatar boxes). Paper's Moonrise
collide/joinIsNotEmpty are NOT the oracle: they diverged from the client algorithm in 19 of 57 060 sampled cases.

REAL CLIENT COLLISION QUALIFICATION:
PASS (Minecraft Java 26.2 client, Paper 26.2 build 124, 2026-09-19/20; 26 evidence samples, 20 obstacle stations;
the qualified plugin jar is byte-identical to the jar rebuilt from the final code; geometry only - no movement
physics was tested or implied)

UNKNOWN/PARTIAL SEMANTICS:
PASS (UNKNOWN/PARTIAL != CLEAR; live result UNAVAILABLE(DIMENSION_UNKNOWN) definitelyClear=false;
GUI-free UNKNOWN_CHUNK regression PASS; known geometry is returned with incomplete certainty, air is never fabricated)

QUERY BOUNDS:
PASS (4096-cell padded scan rejected before work; 16384 emitted boxes -> explicit truncation, INCOMPLETE)

PERFORMANCE QUALIFICATION:
PASS (JMH 1.37, Temurin 25, 3 forks, 5x1s/10x1s, -prof gc; see docs/phase4-artifacts/benchmarks.json)

MEMORY QUALIFICATION:
PASS (parsed table 1.03 MB shared once; worst-case fully warm engine < 6 MB of the 32 MiB shape-cache partition;
nothing retained per player)

PAPER SMOKE:
PASS (0 exceptions; clean Disabling with the player connected)

PHASE 0 DRIFT:
NONE

PHASE 1/2/3 HISTORICAL BASELINES:
UNCHANGED

PHASE 3 FORWARD ADDITION:
read-only WorldView (currentView / viewAtSequence / snapshot view) - additive, equivalence-tested against the
Phase 3 cell-level lookups; no Phase 3 semantics changed

PRODUCTION TRANSPORT:
CAPTURE-ONLY

AUTHORITATIVE OUTCOME CORRELATION:
UNSUPPORTED

SCHEMA:
2 current, 1 readable (unchanged)

PHASE 5:
NOT STARTED
```

## Scope of this approval

Approval covers the Phase 4 scope as documented: platform-independent geometry values (`Aabb`; `CollisionShape` with
the exact vanilla voxel grid), the version-pinned collision shape table extracted through the controlled nativeTest
process, the deterministic block shape resolver (static / position-offset / context / entity-context / block-entity
families, UNSUPPORTED for anything outside the profile), the bounded vanilla broad phase and exact candidate
acceptance, the two-sided conservative envelope and the explicit per-cell certainty vocabulary, current and
historical collision queries with world-epoch safety, the single-axis clip primitive (no ordering, no step, no
movement composition), the player box contract with no assumed pose, metrics, the `/papea2ac collision` diagnostic,
and the core composition. It does not approve any movement simulation, physics, input reconstruction, check,
setback or punishment behaviour - none exists.

## Review facts preserved with this approval

- Vanilla oracle: the native differential suite compares against the vanilla client code paths, never against
  Paper/Moonrise's optimized replacements (19 / 57 060 sampled divergences observed).
- Pathological bounded query: the 4096-cell, 15-box-cauldron region costs about 132 289 ns/op and 685 KB/op with
  the 16384-box truncation - bounded, not a blocker, and a standing warning for Phase 5: normal movement simulation
  must not repeatedly issue full-region scans.
- Historical query cost: about 1 687.6 ns/op, of which about 1 256.8 ns is Phase 3 historical view construction.
- Exact voxel merge: about 392 B allocated per non-cube candidate; a known optimization target.
- Environmental / non-reproduced flake: one JUnit @TempDir deletion failure in the pre-existing Phase 2
  `LiveRecordingTest` on a Windows clean run; the immediate re-run and the final clean verification passed 416/416.
  Not a Phase 4 correctness failure; recorded, not deleted.

## Known limitations carried into the baseline (all explicit, see PHASE_4_IMPLEMENTATION.md §22-§23)

- Powder snow entity-attribute branches are specified from the server bytecode and unit-tested; only the null-entity
  path is natively differential-tested.
- Block-entity-driven shapes (shulker lids, moving pistons) are envelopes, never exact; context-dependent cells need a
  `ShapeContext`, otherwise an explicit envelope is returned.
- World border, entity-vs-entity colliders, fluid occupancy and ray geometry are not part of Phase 4.
- An unknown neighbour cell makes a query INCOMPLETE even when only a large shape could have reached in.
