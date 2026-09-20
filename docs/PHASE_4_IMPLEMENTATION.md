# Phase 4 — Collision Engine — implementation report

Status: **READY FOR HUMAN REVIEW** (implementation engineer: Claude; no self-approval; no baseline created; Phase 5 not started).
Parent: Phase 3 receipt `d9d50661c899f6c852247472d7ffde15dca3e78a` (Phase 3 baseline `92159ec5ef8524c5953ecaa22f663c1f556d52ac`,
manifest composite `437de69d…`). Governing decisions: COLLISION_ENGINE.md C1, WORLD_MODEL.md W1/W3, NUMERICAL_POLICY NUM1,
PERFORMANCE.md F1/F2 (Collision row: 4096 cells / 16384 boxes / query; shape cache 32 MiB), THREADING_MODEL T1, VERSION_SUPPORT V1.

## 0. Test counts (project convention: separate counts, never an aggregate alone)

| Suite | Phase 3 | Phase 4 | Delta | Notes |
|---|---|---|---|---|
| `test` | 353 | **416** | +63 | 44 classes; 12 new Phase 4 classes, ArchitectureTest +1 (collision boundary) |
| `nativeTest` | 115 | **124** | +9 | `NativeCollisionShapeTest` (6), `NativeCollisionKernelTest` (3); 124/124 on the final code |
| unique total | 468 | **540** | +72 | |
| Phase 1 semantic coverage | 127/127 | **127/127** | 0 | `verifyMandatoryCoverage` |
| architecture | included in `test` (8) | included (9) | +1 | |
| other gates | — | `shapeCoverage`, `collisionFixtures`, `benchmarkCollision`, `collisionMemoryHarness`, `extractCollisionShapes` | | Gradle tasks in `anticheat-testing` |

## 1. Scope

Phase 4 converts a historical observer-world snapshot (Phase 3) into geometric collision information:
PLAYER TIMELINE + OBSERVER WORLD SNAPSHOT → block/voxel shape resolution → broad-phase region query → candidates →
precise voxel tests → `CollisionResult`. It answers *which shapes intersect this box*, *is this region certainly
free*, *what geometry existed at sequence N*, and *is the answer known / unknown / partial / evicted / outside
coverage*. It does **not** answer movement validity, velocity, gravity/friction or cheating (Phase 5+; §62/§63
below). It also provides the single-axis clip primitive that ARCHITECTURE assigns to collision ("shapes, clipping"),
without any axis ordering, step-up or step composition.

## 2. Module ownership (approved DAG, no new module, no new edge)

| Role | Module | Content |
|---|---|---|
| COLLISION OWNER | `anticheat-collision` | `CollisionEngine`, `BlockShapeResolver`, `CollisionShape`, `VoxelJoin`, `AxisClip`, `ShapeContext`, `ResolvedShape`, `CellStatus`, `CollisionResult`, `PlayerBox`, `CollisionMetrics` |
| INPUT (data) | `anticheat-version` | `BlockShapeTable` (parsed pinned resource `collision-shapes-paper-26.2-124.tsv`), `CollisionProfile` (named numeric constants) |
| INPUT (world) | `anticheat-world` | Phase 3 `ObserverWorld` + forward addition `WorldView` (`currentView()`, `viewAtSequence()`, `WorldSnapshot.view()`), `SectionSnapshot`, `BlockStateDictionary` |
| INPUT (values) | `anticheat-model` | `Aabb` (new), `BlockStateKey`, `BlockPos` |
| OUTPUT | `anticheat-core` | `PacketEngine.collision` (composition root, path 1), `collisionStatus(UUID)` → `CollisionStatus`, `collisionEngineStatus()` → `CollisionEngineStatus` |
| OUTPUT (display only) | `anticheat-platform-paper` | `/papea2ac collision <player>` and a `status` line; no collision edge (primitives via core) |
| DEPENDENCY DIRECTION | collision → {model, version, world}; core → collision; platform-paper ↛ collision | unchanged adjacency, `ArchitectureTest` 9/9 |

No Bukkit/Paper/NMS/Netty type appears in collision, version, world or model (`ArchitectureTest.importsRespectDirectEdgesAndPlatformIsolation`,
`collisionModuleStaysWithinThePhase4Boundary`).

## 3. Geometry types

| Type | Owner | Semantics |
|---|---|---|
| `Aabb` | model | finite-only, min ≤ max, zero extent legal, **strict** vanilla `AABB.intersects`, `move/inflate/expandTowards` with vanilla semantics |
| `CollisionShape` | collision | immutable box list (flat `double[]`, 6 per box) **plus the exact vanilla voxel grid** (per-axis boundary coordinates + filled bitset); canonical `EMPTY`/`FULL_CUBE`; `large()` = a coordinate outside [0,1] |
| `ResolvedShape` | collision | two-sided envelope `lower` (certainly present) / `upper` (possibly present) + `CellStatus` |
| `CollisionResult.Hit` | collision | whole shape at its world origin + cell + status + `definite` flag (grouping preserved for clipping, as vanilla collects whole `VoxelShape`s) |
| `CollisionResult` | collision | bounds, certainty, provenance (world epoch / revision / sequence), ordered hits, per-status counts, cells visited, truncation |
| `PlayerBox` | collision | pose → vanilla `EntityDimensions.makeBoundingBox` (float half-width), no default pose |

No Bukkit `BoundingBox`/`Vector`, no NMS `AABB`/`VoxelShape` anywhere in domain code (gate above).

## 4. Numeric contract

- **Box convention**: `[min, max]` per axis; vanilla `AABB.intersects` is strict on every axis (face/edge/corner contact is not intersection); `contains` is min-inclusive / max-exclusive. NaN/Infinity throw at construction (`Aabb`, `CollisionShape`, `ShapeContext`); inverted boxes throw; `-0.0` compares equal to `0.0` (not normalized).
- **Tolerances are named, never scattered** (`CollisionProfile`): `SHAPE_EPSILON = 1.0E-7` (vanilla `Shapes.EPSILON`: coordinate-merge tolerance of voxel joins, the `min+ε / max-ε` index probes and the `d >= -ε / d <= ε` contact acceptance of `collideX`); `ABOVE_TOLERANCE = 9.999999747378752E-6` (`(double) 1.0E-5F` of `EntityCollisionContext.isAbove`); `POWDER_SNOW_FALL_DISTANCE = 2.5`; `BROAD_PHASE_PADDING = ε`; `SHULKER_LID_EXTENT = 0.5`. No epsilon compounds per tick; nothing is "close enough".
- **Candidate acceptance** reproduces the client exactly: the `Shapes.block()` singleton uses strict `AABB.intersects`; every other shape uses the vanilla voxel join (`VoxelJoin`: `Shapes.create` discretization of the query box — thinner than ε ⇒ empty, all-1/8-aligned-inside-unit-cube ⇒ cube grid, else single cell — then the `IndirectMerger` / identical / non-overlapping merge per axis and the filled-cell AND). This was necessary, not cosmetic: an "overlap > ε" rule diverged from vanilla at the 1e-7 scale, and whether a shape is collected at all decides whether the clip sees it.
- **Clip** (`AxisClip`) reproduces vanilla `VoxelShape.collideX` over the exact grid, including the penetrated case (a body already inside a 3/4-high box snaps to the internal 1/4 line, as the client does).
- **Position offsets** (`Mth.getSeed`, float `/15f`, double `-0.5)*0.5`, `Mth.clamp`) reproduced bit for bit; verified at 3 120 positions natively.
- **Player box**: `float half = width/2f`, `float h = height`, then double arithmetic — verified against native `makeBoundingBox` at 1 400 positions.
- Coordinates beyond `±3.2E7` (protocol 30-bit space) are REJECTED before any arithmetic; cell ranges use `long`.

## 5. Player box contract

`PlayerBox.of(profile, pose, x, y, z[, scale])` for `STANDING 0.6×1.8`, `CROUCHING 0.6×1.5`, `SWIMMING / FALL_FLYING /
SPIN_ATTACK 0.6×0.6`, `SLEEPING / DYING 0.2×0.2` — dimensions extracted from the pinned server's `Avatar.POSES` /
`STANDING_DIMENSIONS` (table `P` records), never typed by hand. **No entry point assumes a pose**; pose selection
depends on simulated state and is Phase 5's. The diagnostic evaluates STANDING and CROUCHING explicitly and labels both.

## 6. Block shape resolution and version profile

`BlockShapeResolver`: canonical `BlockStateKey` (+ position + `ShapeContext`) → `ResolvedShape`. Geometry comes only
from the version profile's table; the resolver never inspects a live world, never calls a Paper API, never reflects.

**Shape table generation** (§14): `CollisionShapeExtractMain` (nativeTest classpath only, task `extractCollisionShapes`)
walks every state of `Block.BLOCK_STATE_REGISTRY` of the pinned Paper 26.2 build 124, records
`getCollisionShape(EmptyBlockGetter, ZERO, CollisionContext.empty())` as canonical boxes **and** the exact
`DiscreteVoxelShape` grid, classifies each block, and empirically verifies the STATIC claim (same shape at five
positions, under six entity contexts, and against a `BlockGetter` proxy that throws on any access). Result:
`anticheat-version/src/main/resources/me/nicolas/papea2ac/version/collision-shapes-paper-26.2-124.tsv` — 1 196 blocks,
32 366 states, **325 unique shapes**, 208 874 bytes, SHA-256 in `docs/phase4-artifacts/shape-table-summary.json` and
asserted by `BlockShapeTableTest`. Deterministic (LC_ALL=C block order, `Double.toString`, run-length indices),
version pinned (`# profile paper-26.2-124`), reviewable text, bounded, strict parser (any malformed line fails the
load; nothing partial is published).

**Classification** (vanilla's own `dynamicShape` flag, confirmed by the empirical probes — `variesWithoutDynamicFlag: []`):

| Kind | Blocks | Rule in the resolver |
|---|---|---|
| STATIC | 1 173 | table shape, exact |
| OFFSET_XZ | bamboo, pointed_dripstone, sulfur_spike | base shape + `Mth.getSeed(x,0,z)` offset (exact; base recovered from the origin shape by undoing the exactly-dyadic seed-0 offset) |
| CONTEXT | scaffolding | one of six extracted variants chosen by `isAbove(block)`, `isAbove(below block)`, `descending` (exact with context; envelope without) |
| ENTITY_CONTEXT | powder_snow | `fallDistance > 2.5` → falling shape; boots ∧ above ∧ ¬descending → full; else empty (exact with context; envelope without) |
| BLOCK_ENTITY | 17 shulker boxes, moving_piston | `lower` = resting (closed body / nothing), `upper` = resting ∪ maximal travel (lid +0.5 toward facing / ±1 cell along the facing axis); never exact |

Unknown block, missing/extra/unknown property ⇒ `UNSUPPORTED_BLOCK_STATE` (no geometry claimed, status carries the
uncertainty); an unresolvable dictionary id ⇒ the same. A malformed key never throws (§40).

## 7. Shape storage and cache

- One `CollisionShape` instance per table index, interned at resolver construction (325 instances, 125 KiB) and shared by every state and every session (§11/§12).
- Per-engine `AtomicReferenceArray<Entry>` of `BlockStateDictionary.CAPACITY` (65 536) slots keyed by run-local id: bounded by the dictionary, thread-safe, write-once-per-id (identical value on a race), immutable entries, profile-scoped through the engine. UNSUPPORTED is memoized (deterministic ⇒ exact, not a poisoned cache). Measured: empty engine 0.65 MB (mostly the slot array); every one of the 32 366 registry states resolved: +2.1 MB (≈66 B/entry) — a worst case no real run reaches (only observed states are interned).
- Cache eviction is not needed (finite by state space) and would change performance only, never results.
- No per-player, per-world or per-query global cache exists.

## 8. State-dependent and neighbour-dependent shapes

State-derived exactness is total by construction: slab type, stair facing/half/shape, door facing/open/hinge/half,
trapdoor facing/open/half, fence and wall connections, gate facing/open, snow layers, bed part/facing, chest type/
facing, etc. are all block-state properties and the table is keyed by the full canonical property set (383 blocks,
22 689 states are STATE_DERIVED_EXACT). **Neighbour-dependent shapes: none in this profile.** Every connection-
dependent family carries its connections in the state the server sends; the extractor proved no STATIC block reads
the level when resolving its shape. If a future profile introduced level reads, the extractor would classify the
block LEVEL-dependent and the resolver would report it as uncertain (BLOCK_ENTITY path) — never assume disconnected,
never assume air. Neighbouring *world* cells are still consulted by the broad phase (the vanilla ±1 ring), and an
unknown neighbour makes the query INCOMPLETE (a 1.5-high fence in an unobserved cell could reach into the box).

## 9. Unknown / coverage semantics and the conservative envelope

Every visited cell gets exactly one `CellStatus`: `EXACT`, `EXACT_OFFSET`, `EXACT_CONTEXT`, `EXACT_EMPTY`,
`CONTEXT_REQUIRED`, `DYNAMIC_BLOCK_ENTITY`, `UNSUPPORTED_BLOCK_STATE`, and the Phase 3 world statuses one-to-one
(`UNKNOWN_CELL`, `UNKNOWN_CHUNK`, `UNKNOWN_SECTION`, `UNLOADED`, `EVICTED`, `CONTENT_NOT_RETAINED`,
`TRANSPORT_INCOMPLETE`, `OUTSIDE_DIMENSION`, `DIMENSION_UNKNOWN`, `WRONG_WORLD_EPOCH`, `HISTORY_NOT_RETAINED`,
`FUTURE_SEQUENCE`). Certainty: `COMPLETE` (all cells exact, no truncation), `INCOMPLETE`, `UNAVAILABLE` (view
unusable: reason reported, no cell examined), `REJECTED` (invalid query). Rules proven by tests:

- UNKNOWN ≠ AIR: an unobserved cell, chunk, section, unloaded/evicted/not-retained chunk never contributes "clear"; `definitelyClear()` requires COMPLETE and zero hits of any kind.
- Partial chunks: known cells yield exact geometry (still reported as definite hits); unknown cells stay unknown; the query is INCOMPLETE.
- UNLOADED vs EVICTED stay distinct in the counts; CONTENT_NOT_RETAINED is never substituted by generic geometry.
- World epoch: the caller passes the player state's world epoch; a mismatch is `UNAVAILABLE(WRONG_WORLD_EPOCH)`; after a respawn the old chunks are gone from the new epoch (coordinates alone never resolve).
- Cells above/below the dimension are `OUTSIDE_DIMENSION` and exactly empty (no block exists there); they do not make a query incomplete.

**Conservative envelope** (§10, MOVEMENT_SIMULATION M3 direction rule): each uncertain cell yields `lower` (certainly
present) and `upper` (possibly present). Hits from `lower` are `definite`; the remainder of `upper` is a possible-only
hit. Direction per consumer: an *obstructed* conclusion may only use definite hits (sound under any certainty); a
*clear* conclusion needs `upper` empty everywhere, i.e. COMPLETE with no hit. Unknown world cells produce no
geometry at all — they widen certainty, they never invent solid or air. `AxisClip.clip(result)` uses definite hits
only (possible-only geometry is not a collider), so later movement validation cannot conclude INVALID from something
Phase 4 merely could not know.

## 10. Broad phase and query API

`CollisionEngine.query(WorldView view, Aabb bounds, ShapeContext context, long expectedWorldEpoch)`:

1. `view.availability()` ≠ KNOWN ⇒ UNAVAILABLE(reason); epoch mismatch ⇒ UNAVAILABLE(WRONG_WORLD_EPOCH); any coordinate beyond ±3.2E7 ⇒ REJECTED.
2. Cell range = vanilla `Cursor3D`: `floor(min − ε) − 1 … floor(max + ε) + 1` per axis (long arithmetic); if the padded volume exceeds 4 096 cells ⇒ REJECTED before any allocation or iteration (§20).
3. Iterate x fastest, then y, then z (vanilla order; documented; deterministic). Corner ring cells are never visited; face-ring cells only for large shapes (fences, walls, piston heads, every non-static kind); edge-ring cells only for moving pistons — exactly vanilla `BlockCollisions`.
4. Per cell: section memo (one lookup per section run), `stateIdAt` (−1 ⇒ UNKNOWN_CELL), cached `Entry`, resolve, vanilla candidate acceptance, emit whole-shape hits; stop with `truncated` when more than 16 384 boxes were emitted.
5. Result: certainty, counts, hits, `cellsVisited`, provenance from the view (epoch / revision / sequence-through), profile id.

The API is never boolean-only (§36): `definitelyObstructed()`, `definitelyClear()`, certainty, `unavailableReason`,
counts and provenance are all first-class. Results are immutable (`List.copyOf`, defensive array copies) and equal by
value; a repeated query on the same snapshot is `equals()` (property test, 1 500 random queries).

## 11. Historical query and player/world coherence

`WorldView` is the Phase 4 forward addition to Phase 3's world module (read-only; the cell-level Phase 3 lookups are
unchanged and remain the oracle — `WorldViewTest` proves equivalence over a 220-step random script × 300 cells for
every retained sequence, the current state and the published snapshot):

- `ObserverWorld.currentView()` — owner thread, valid until the next apply;
- `ObserverWorld.viewAtSequence(S)` — owner thread; indexes the revision history **once** per view (oldest revision after S per section), same semantics as `blockAtSequence` (FUTURE_SEQUENCE / WRONG_WORLD_EPOCH / HISTORY_NOT_RETAINED / UNKNOWN_CHUNK-before-creation…);
- `WorldSnapshot.view(dictionary)` — any thread, the published immutable snapshot (used by the diagnostic).

Coherence (§24): world baseline at A, player B, block change C, player D ⇒ the query at B is clear, at D obstructed,
at C inclusive; removing the block later never changes the past (`CollisionQueryTest`, `CollisionPropertyTest`:
a historical query never reads a later revision, checked over 120 placements × all sequences).

## 12. Memory model (`docs/phase4-artifacts/memory-results.json`, `collisionMemoryHarness`)

| Item | Measured (median of 3, forced-GC heap diff) |
|---|---|
| Parsed shape table (shared once per JVM) | 1.03 MB (resource 208 874 B; 32 366 states → 325 shapes, dedup 99.6×) |
| Interned shape instances | 125 608 B |
| Empty engine (slot array + shapes + metrics) | 650 712 B |
| Engine with **every** registry state resolved (worst case) | 4.87 MB total; resolver cache ≈ 2.14 MB (≈66 B/entry) |
| 1 000 retained results (37-cell standing query, 1 hit) | 260 040 B (≈260 B each; consumers should not retain results) |

Against the 32 MiB shape-cache partition: < 6 MB at the absolute worst case, nothing per player, nothing per world.
Temporary allocation per query: the result record, its hit list and one `SectionLookup` per section run; the
voxel-join emulation allocates three small merged-index arrays per non-cube candidate (measured by JMH `-prof gc`,
§13). No section-level acceleration index was added: uniform (single-palette) sections are already O(1) per cell and
the per-cell cost is an array read + one cached entry, so an index would cost memory for no measurable gain at the
query sizes Phase 5 will issue (measured before adopting, §38).

## 13. Performance (`docs/phase4-artifacts/benchmarks.json`, JMH 1.37, Temurin 25, 3 forks, 5×1 s warmup, 10×1 s measurement, `-prof gc`)

Two runs are recorded, both on the final production code (Windows 11, Temurin 25.0.4.1, `benchmarkCollision`).
Run 1 (2026-09-19 14:15, quiet host) is the documented measurement; run 2 (2026-09-20, the final pre-baseline
re-run after a whitespace-level tidy of the historical view's key packing; it is the committed
`docs/phase4-artifacts/benchmarks.json`) landed on a noisier host - every kernel is 1.2-1.7x slower with error
bars up to 8x wider while the allocation profile is byte-identical, which is host noise, not a code change. Neither
run is a capacity claim.

| Kernel | run 1 ns/op (±err) | run 2 ns/op (±err) | alloc B/op (both) |
|---|---|---|---|
| fullCubeLookup (cached entry) | 6.2 ± 0.0 | 6.9 ± 2.0 | 0 |
| complexStateShapeLookup (inner-left stairs, resolve) | 6.2 ± 0.0 | 6.8 ± 0.0 | 0 |
| contextDependentShapeResolution (scaffolding + body context) | 7.0 ± 0.5 | 10.0 ± 1.8 | 24 |
| positionOffsetShapeResolution (bamboo, translated shape) | 39.6 ± 0.3 | 70.5 ± 9.3 | 288 |
| singleSectionBroadPhaseStandingClear (standing body, 37 cells) | 347.5 ± 2.1 | 587.8 ± 7.7 | 416 |
| noHitQueryInAir | 308.0 ± 17.5 | 409.2 ± 4.7 | 416 |
| fullyInsideStone (2 hits) | 363.2 ± 42.1 | 421.0 ± 42.6 | 664 |
| multiSectionMultiChunkBroadPhase (2 chunks × 2 sections) | 907.2 ± 105.2 | 1 091.6 ± 150.5 | 2 600 |
| manyHitQueryDenseNonFullShapes (fences/stairs/walls) | 459.5 ± 2.2 | 670.4 ± 94.5 | 1 216 |
| standingOnSlabAmongStairsAndWalls | 463.5 ± 6.3 | 779.7 ± 10.9 | 1 216 |
| unknownRegionQuery (all cells UNKNOWN_CHUNK) | 278.9 ± 1.2 | 353.7 ± 3.4 | 480 |
| historicalViewConstructionOnly (600-revision history) | 1 256.8 ± 10.6 | 2 016.7 ± 41.0 | 224 |
| historicalQueryIncludingViewConstruction | 1 687.6 ± 18.3 | 2 628.3 ± 191.8 | 824 |
| **pathologicalFullBudgetCauldronRegion** (4 096 cells, ≈1 100 × 15-box cauldrons, truncated at 16 384 boxes) | **132 289 ± 4 883** | 183 952 ± 41 496 | 685 329 |
| clipYAgainstManyHits (27 cauldron hits) | 971.4 ± 16.2 | 1 673.8 ± 153.9 | 64 |
| candidateAcceptanceVoxelJoin (exact merger, one fence vs body) | 63.3 ± 0.9 | 110.2 ± 18.5 | 392 |

Reading: a player-sized query costs ≈0.3–0.5 µs and ≈0.4–1.2 KB (the result record, its hit list and one
`SectionLookup` per section run); the historical view index costs ≈1.3 µs per view at 600 retained revisions;
the worst legal query (the full 4 096-cell budget packed with the densest shape of the profile) costs ≈130 µs and
≈0.7 MB of transient garbage — bounded, and far outside any body-sized query Phase 5 will issue. The exact voxel-join
emulation is the only kernel that allocates per candidate (392 B); it runs once per non-cube candidate cell.
Kernels: full-cube lookup, complex state lookup, context/offset resolution, single-section and multi-section/multi-
chunk broad phase, no-hit (air) and fully-inside-stone queries, many-hit dense fences/stairs/walls, standing on a
slab among them, pathological full-budget cauldron region (4 096 cells, ≈1 700 × 15-box shapes → truncated at
16 384 boxes), historical query (with and without view construction), unknown-region query, clip over many hits,
voxel-join candidate acceptance. These are descriptive kernels, not capacity claims.

## 14. Native differential testing (`nativeTest`, pinned Paper 26.2 build 124)

| Test | Oracle | Cases |
|---|---|---|
| `everyRegistryStateRestingShapeAndGridMatchesNative` | `BlockStateBase.getCollisionShape(EmptyBlockGetter, ZERO, empty)` boxes **and** `DiscreteVoxelShape` grid | all 32 366 states (offset blocks at the origin through the resolver) |
| `everyFullCubeStateUsesTheSingletonBlockShape` | identity with `Shapes.block()` | 3 037 full-cube states |
| `scaffoldingContextRuleMatchesNativeAroundEveryPredicateBoundary` | real `EntityCollisionContext` | 32 states × 4 y × 2 descending × 12 bottoms around the 1e-5 boundaries = 3 072 |
| `positionOffsetBlocksMatchNativeAtManyPositions` | native shape at position | 52 states × 60 positions = 3 120 |
| `powderSnowNullEntityPathAndEmptyContextAreEmptyLikeNative` | native (no entity) | entity-attribute branches cannot be exercised without a Level-backed entity: rule read from bytecode, documented limitation |
| `avatarPoseBoxesMatchNativeMakeBoundingBox` | `EntityDimensions.makeBoundingBox` of `Avatar.POSES` | 7 poses × 200 positions |
| `candidateAcceptanceMatchesVanillaForEveryShape` | vanilla `joinIsNotEmpty` **via its untouched private merger path** + `AABB.intersects` for the singleton | 324 shapes × 40 boxes (ε-scale jitter, containment, zero width) |
| `axisClipMatchesVanillaCollideForEveryShapeIncludingPenetration` | vanilla `VoxelShape.collideX` (bit equality) | 324 shapes × 60 boxes × 3 axes = 58 320, > 500 penetrating |
| `shapesCollideOverManyShapesMatchesTheResultLevelClip` | vanilla `Shapes.collide` loop | 300 multi-shape sets × 3 axes |

**Finding recorded during this work:** Paper replaces `VoxelShape.collide` and `Shapes.joinIsNotEmpty` with
Moonrise re-implementations; the vanilla `collideX` and the private merger path are still in the jar and are what the
native *client* runs, so they are the oracle. Moonrise's server-side `collide` diverged from the client algorithm in
19 of 57 060 sampled cases (ε-scale contact acceptance). Papea2AC reproduces the client.

## 15. Property testing (`CollisionPropertyTest`, seed `0xC0DEC0DE` printed on failure)

Shape boxes ordered and inside the [−1, 2] block-relative envelope; grid ⇔ boxes consistency for all 325 shapes;
identical states resolve identically (one random state per block, plan and cached entry); full-cube acceptance ≡
strict AABB intersection (20 000 boxes); unknown never becomes clear, every padded cell visited exactly once, one
status per cell, deterministic order and equality (1 500 random queries over a mixed world of stone/slabs/fences/
stairs/cauldrons/scaffolding/shulkers/unsupported); historical queries never read a later revision and epoch
mismatch never succeeds; oversized queries always REJECTED with zero work and in-bound ones exactly `cells − 8`;
table classification invariants for every block.

## 16. Fixtures (`docs/phase4-artifacts/collision-fixtures.json`, `collisionFixtures`)

Eight deterministic fixtures — simpleFullCube, complexShapes (14 families in three rows), contextAndOffsetShapes,
unknownWorld, coverageStates (unloaded / not retained / unsupported / unknown edge), historicalState, worldEpochMismatch,
boundsAndRejection — each a scripted observer world plus queries; digest = SHA-256 over the canonical rendering of every
result (certainty, provenance, counts, hits with world boxes and definite flags), pinned against the table digest.
`CollisionFixtureDeterminismTest`: identical render with perturbed run-local ids and a different budget; pinned digests;
specified outcomes.

## 17. Shape coverage inventory (`docs/phase4-artifacts/shape-coverage.tsv/.json`, gate `ShapeCoverageInventoryTest`)

| Classification | Blocks | States |
|---|---|---|
| EMPTY / NON-COLLIDING | 242 | 5 066 |
| FULL CUBE | 427 | 3 037 |
| EXACT (single state, partial) | 121 | 1 375 |
| STATE-DERIVED EXACT | 383 | 22 689 |
| NEIGHBOR-DERIVED EXACT | 0 | 0 (none exist in this profile — see §8) |
| POSITION-DERIVED EXACT | 3 | 52 |
| CONTEXT-DERIVED EXACT | 1 | 32 |
| CONSERVATIVE / UNCERTAIN BY SPEC | 19 | 115 (powder snow entity attributes; 17 shulker boxes and moving_piston are block-entity driven) |
| UNSUPPORTED | **0** | **0** |

Every required family of the task (slabs, stairs, fences, walls, gates, doors, trapdoors, ladders, carpets, snow,
beds, chests, anvils, cactus, cakes, farmland/path, hoppers, cauldrons, scaffolding, powder snow, shulker boxes,
pistons/heads/moving piston, panes/bars, soul sand, honey, enchanting table, end portal frame, bamboo, dripstone,
lily pad, composter, candles, lanterns, chains, bells, grindstone, stonecutter, lectern, campfire, brewing stand,
daylight detector, sea pickle, turtle egg, big dripleaf, azalea, sculk sensor, mud, heavy core, decorated pot,
sniffer egg) is present and not a generic cube. Non-full geometry is never hidden behind a full cube.

## 18. Metrics (`CollisionMetrics`, fixed keys, shown in `/papea2ac status`)

queries, complete, incomplete, unavailable, rejected, truncated, cellsVisited, hits, boxesEmitted, shapeCacheHits,
shapeCacheMisses, unsupportedStates, contextRequired, dynamicCells, worldUnknownCells, historicalMisses. No player,
position or block label is ever a key.

## 19. Diagnostic command (§54) — IMPLEMENTED

`/papea2ac collision <player>`: session/world epoch, revision, sequence-through, profile, observed position; for
STANDING and CROUCHING explicitly: box, certainty (+reason), cells, hits, boxes, truncation, definitelyObstructed /
definitelyClear, status counts, and at most 8 boxes per pose. Computed on the main thread from the published world
snapshot view and the published timeline position — geometry only, never a validity judgment. `/papea2ac status`
adds one `collision …` line (profile, table identity, counters).

## 20. Threading and failure containment

Queries run against immutable snapshots or owner-thread-confined views; no lock is taken; the resolver cache is a
lock-free write-once array. A malformed or unknown state resolves to UNSUPPORTED (memoized) without exception; the
engine never throws for any finite input (non-finite input is refused by the value types before reaching it).
Table load failure is a hard failure of engine construction (nothing partially initialized is published).

## 21. Real-client collision qualification (§53–§56) — PERFORMED 2026-09-19/20: PASS

Setup: pinned Paper 26.2 build 124 (Temurin 25.0.4.1, `.phase1-runtime`, 127.0.0.1:25576, offline mode, view
distance 6) with the **final** plugin jar (byte-identical to the rebuilt jar of the final code); owner's native
Minecraft Java 26.2 client, in-game name `Emojiado` (OP). Evidence: `docs/phase4-artifacts/real-client-evidence.log`
(27 RCON samples, 0 secret matches; the RCON password lives only in the git-ignored `server.properties`); server
log `.phase1-runtime/qualification-run-phase4.log` (git-ignored; 0 exceptions, clean `Disabling Papea2AC` with the
player still connected). The obstacle course was built with `.phase1-runtime/course.js -37 67 20` (git-ignored
dev script; layout reproduced below) — a 66×13 stone platform at y=66 along z=20 crossing x=−16, 0 and 16:

| Station | x (z=20) | Reading (`/papea2ac collision Emojiado`, both poses) | Verdict |
|---|---|---|---|
| Join | spawn 0.5,67,8.5 | COMPLETE, 37 cells, 0 hits, clear | PASS |
| A full-block corridor 1 wide 2 high | −35..−32 | COMPLETE, 52 cells, 36 exact solids, 0 hits; body centred (z 20.28–20.88 between wall faces 20.0/21.0) | PASS |
| B bottom slab | −29 | feet at **67.5** (slab top), COMPLETE clear | PASS |
| B top slab overhead, standing | −27 | client stopped the 1.8 body at the slab's west face: box maxX = **−27.0**, 0 hits | PASS |
| B top slab overhead, crouching under it | −27 | same position: STANDING **definitelyObstructed** by `[−27,68.5,20 → −26,69,21]`; CROUCHING **clear** | PASS (pose contract) |
| C stairs (east-facing) lower step | −24 | feet at **67.5**, box touching the riser, COMPLETE | PASS |
| D fence line (1.5 high) | −19 | contact at box maxX = **−18.625** (arm face 6/16); could not be jumped | PASS |
| E wall line | −15 | contact at **−14.6875** (low-wall side face 5/16) | PASS |
| F fence gate closed / open | −11 | closed: contact at **−10.625**; opened by command: standing inside the gate cell, 0 hits, EXACT count 23→22 | PASS |
| G door closed / open | −7 | closed east-facing door: contact at **−7.0** (west 3/16); opened (2-cell batch update observed): leaf on the north face z 20.0–20.19, body from z 20.23, clear | PASS |
| H trapdoor closed / open | −3 | closed: feet at **67.1875** (3/16 plate); open: vertical plate on the west edge, client stood the body on its top edge at **68.0** | PASS |
| I ladder (3 high) | 0 | mid-air at y 67.23, contact at box maxX = **0.8125** (ladder plate 13/16) | PASS (shape only) |
| J carpet / snow 4 / snow 7 | 5 / 7 / 8 | feet at **67.0625** / **67.375** / **67.75** = 1/16, (4−1)·2/16, (7−1)·2/16 | PASS |
| K narrow 1×2 opening | 11 | body inside the hole, 16 solid cells around, 0 hits, head under the lintel | PASS |
| L fence pair across the chunk boundary | 15/16 | contact at **15.375** (post face); padded scan into chunk (1,1) all known, COMPLETE | PASS |
| L cauldron (15 boxes, hollow) | 19 | feet at **67.25** (floor plate 4/16) inside the inner walls | PASS |
| L hopper (13 boxes) | 21 | feet at **67.6875** (bowl floor 11/16) | PASS |
| M scaffolding (context) | 24,z22 | feet at 68.0; cell `EXACT_CONTEXT=1` (body context supplied), COMPLETE | PASS |
| M shulker box (block entity) | 24 | **INCOMPLETE**, one **possible** hit `[24,67,20 → 25,68.5,21]` (lid envelope), definitelyObstructed=false, definitelyClear=false | PASS (envelope) |
| N unknown-world live check | 3005,67,3005 | `capture off → tp → capture on` re-admitted the player as session epoch 2 with no JoinGame: `UNAVAILABLE(DIMENSION_UNKNOWN)`, definitelyClear=**false**; UNKNOWN_CHUNK form covered GUI-free by `CollisionEngineIntegrationTest` | PASS (never claims clear) |
| Epoch tracking | (death) | the player was killed mid-session: the readout followed the respawn to worldEpoch 1 | PASS |

Every surface height and contact face matched the resolved geometry to the double; no defect was found, so the
qualified build is the final build. Not exercised live: dimension/world transition (the respawn epoch change was
exercised instead; Phase 3 qualified dimension transitions and Phase 4 tests cover epoch mismatch). Engine counters
at the end of the full session: 52 queries, 50 complete, 2 incomplete (the shulker envelope), 0 rejected,
0 truncated, 0 unsupported states, 2 dynamic cells, 0 world-unknown cells, cache 2 319 hits / 29 misses.

**Environmental note (not a Phase 4 defect):** the first `gradlew clean test` of the final verification failed once
in the Phase 2 `LiveRecordingTest` with JUnit's "Failed to delete temp directory" (a Windows file-handle timing
issue in `@TempDir` cleanup); the immediate re-run, every earlier run and the final pre-baseline clean run (2026-09-20) passed 416/416. Classified ENVIRONMENTAL / NON-REPRODUCED FLAKE; recorded, not deleted.

## 22. Known limitations

1. Powder snow's entity-attribute branches are specified from the server bytecode and unit-tested, but not natively differential-tested (no Level-backed entity exists in the test harness); the null-entity path is.
2. Block-entity-driven shapes (shulker lids, moving pistons) are envelopes: Phase 3 does not track block-entity animation or piston progress; Phase 4 never claims exactness for them.
3. Context-dependent cells need a `ShapeContext`; which context applies to a step is Phase 5's decision. Without it the result is an explicit envelope.
4. World border, entity-vs-entity colliders and the fluid occupancy contract are not part of Phase 4 (§23); nothing in the result pretends otherwise.
5. Historical views index the revision history once per view (O(history) per view, up to 4 096 entries) — measured in the benchmarks; Phase 5 issues one view per step.
6. The voxel-join emulation allocates small index arrays per non-cube candidate; it is exact rather than allocation-free. Measured by `-prof gc`; an allocation-free fast path is a Phase 23 refinement within the same semantics.
7. An unknown neighbour cell makes a query INCOMPLETE even when only a large shape (1.5-high fence) or a moving piston could have reached in — deliberately strict; the counts say which cells were unknown.

## 23. Deferred to Phase 5+ (explicit)

Movement clipping *order* and step-up, sweep, gravity/friction/drag/velocity, input reconstruction, VALID/INVALID,
any check; fluid occupancy / `BlockBehaviorQuery` semantics (WORLD_MODEL W3: world/physics); entity-vs-entity
colliders and pushing (Phase 3 never delivered `NearbyMotionEntities`; the entity neighbourhood is a Phase 3-owned
prerequisite that remains open and is recorded here, not silently absorbed); world border geometry (Phase 1 captures
the border packets, the observer world does not reduce them yet); ray geometry (`RayQuery`, roadmap Phase 12);
safe-anchor clearance service (Phase 10).

## 24. Acceptance matrix

Status vocabulary: PASS / FAIL / EXTERNAL QUALIFICATION REQUIRED / NOT APPLICABLE BY SPEC.

| # | Criterion (task §) | Status | Evidence |
|---|---|---|---|
| 1 | Module ownership per DAG, no cycle (§1) | PASS | §2; `ArchitectureTest` 9/9 |
| 2 | Platform-independent geometry types (§2) | PASS | §3; boundary test |
| 3 | Numeric contract, named tolerances (§3) | PASS | §4; `AabbTest`, native kernel tests |
| 4 | Player box contract, no silent standing (§4) | PASS | §5; `AxisClipAndPlayerBoxTest`, native pose test |
| 5 | Deterministic resolver from normalized state (§5) | PASS | §6; `BlockShapeResolverTest` |
| 6 | Vanilla fidelity over generic cubes (§6) | PASS | §17 inventory, native all-states test |
| 7 | State-dependent shapes (§7) | PASS | §8; `BlockShapeResolverTest` families |
| 8 | Neighbour-dependent shapes never assumed (§8) | PASS | §8 (none in profile; unknown neighbours → INCOMPLETE) |
| 9 | Explicit collision certainty (§9) | PASS | §9; `CellStatus`, `CollisionQueryTest` |
| 10 | Sound conservative envelope (§10) | PASS | §9; `ResolvedShape`, clip uses definite only |
| 11 | Compact immutable shapes (§11) | PASS | `CollisionShape`; §12 |
| 12 | Bounded thread-safe immutable cache (§12) | PASS | §7; `perIdCacheIsDeterministicSharedAndBounded` |
| 13 | Version abstraction (§13) | PASS | table in version, resolver in collision, no reflection in production |
| 14 | Controlled shape table generation (§14) | PASS | `CollisionShapeExtractMain`, `extractCollisionShapes`, pinned digest |
| 15 | Native differential testing (§15) | PASS | §14, 9 nativeTests |
| 16 | Shape coverage inventory, zero unexplained unsupported (§16) | PASS | §17; gate test |
| 17 | AABB intersection semantics (§17) | PASS | `AabbTest`; strict + voxel join native tests |
| 18 | Axis clipping primitives only (§18) | PASS | `AxisClip`; no ordering/step; native bit-equality |
| 19 | Broad phase correctness (§19) | PASS | §10; property test cell counts |
| 20 | Query size guard, overflow-safe (§20) | PASS | 4 096-cell REJECTED before work; property test |
| 21 | Deterministic iteration order (§21) | PASS | §10; equality/order tests |
| 22 | Current world query API (§22) | PASS | `CollisionEngine.query` |
| 23 | Historical query (§23) | PASS | §11; `WorldView.viewAtSequence` |
| 24 | Player/world coherence (§24) | PASS | `playerWorldTemporalCoherenceBeforeAndAfterABlockChange` |
| 25 | UNKNOWN ≠ AIR (§25) | PASS | `CollisionQueryTest`, property test, integration test |
| 26 | Partial chunks (§26) | PASS | `partialChunkKnownCellsAreExactAndUnknownCellsStayUnknown` |
| 27 | CONTENT_NOT_RETAINED preserved (§27) | PASS | coverage test / fixture |
| 28 | UNLOADED vs EVICTED (§28) | PASS | coverage test |
| 29 | World epoch safety (§29) | PASS | `wrongWorldEpochAndDimensionChangeAreExplicitFailures` |
| 30 | Moving/dynamic blocks → uncertainty (§30) | PASS | BLOCK_ENTITY envelopes |
| 31 | Fluids: no physics (§31) | PASS | fluids EMPTY; occupancy deferred (§23) |
| 32 | Climbables: geometry only (§32) | PASS | ladder shape; no climbing |
| 33 | No movement-affecting properties mixed in (§33) | PASS | none in collision; boundary test |
| 34 | Entity collision decision documented (§34) | PASS | deferred (§23) |
| 35–36 | Rich immutable result, no boolean API (§35/§36) | PASS | `CollisionResult` |
| 37 | Fast paths with correctness first (§37) | PASS | singleton cube / empty / uniform sections; unknown never fast-pathed to clear |
| 38 | Section acceleration measured before adopting (§38) | PASS | not adopted, reasons in §12 |
| 39 | No live world lookups (§39) | PASS | boundary test (`getBlockAt/getBlockState/getChunkAt/Bukkit/getHandle` forbidden) |
| 40–41 | Failure containment, cache failure (§40/§41) | PASS | UNSUPPORTED memoized; strict table load |
| 42–43 | Threading, determinism (§42/§43) | PASS | §20; property tests |
| 44–45 | Memory bounds, allocation review (§44/§45) | PASS | §12; JMH `-prof gc` |
| 46–48 | JMH kernels, worst case, table memory (§46–§48) | PASS | §13 addendum; §12 |
| 49 | Core test matrix (45 items) (§49) | PASS | mapped in §25 |
| 50–51 | Native matrix, exhaustive families (§50/§51) | PASS | all 32 366 states natively; families in `BlockShapeResolverTest` |
| 52 | Property tests (§52) | PASS | §15 |
| 53–56 | Real-client obstacle course / diagnostic / unknown live check | PASS | §21: 20 stations, all matched; diagnostic implemented; unknown-world check never claimed clear |
| 57 | Fixtures (§57) | PASS | §16 |
| 58 | Metrics (§58) | PASS | §18 |
| 59–61 | Phase 1/2/3 regression gates | PASS | full suites re-run; 127/127; coherence tests |
| 62–63 | No physics, no checks | PASS | boundary test; §23 |
| 64–66 | Documentation, artifacts, matrix | PASS | this file, `docs/phase4-artifacts/` |

## 25. Core test matrix mapping (§49)

1 empty world `emptyWorldQueryIsUnknownNeverClear` · 2 full cube / 3 air `standingOnDirt…` · 4 face touch / 5 overlap
`exactFaceTouchOverlapAndTinyOffsets` · 6 negative coords / 7 chunk boundary / 8 section boundary / 9 negative Y
`negativeCoordinatesChunkBoundary…` · 10–12 slabs `slabsBottomTopDouble` · 13–16 stairs `stairsStraightInnerOuter…` ·
17–18 fences `fencesAndTheirConnections…` · 19–20 walls `wallsPostAndSideHeightsAndPanes` · 21 gate / 22 door / 23 trapdoor
`gatesDoorsAndTrapdoorsRotateWithState` · 24 snow / 25 carpet / 26 ladder / 27 bed / 28 chest / 29 hopper+cauldron
`snowLayersCarpetLadderBed…` · 30 partial chunk / 31 unknown section `partialChunk…` · 32 unknown neighbour
`unknownNeighbourChunk…` · 33 unloaded / 34 evicted / 35 content-not-retained `unloadedAndEvicted…` · 36 wrong epoch
`wrongWorldEpoch…` · 37 historical `historicalQueryReturns…` · 38 coherence `playerWorldTemporalCoherence…` · 39 oversized
`oversizedAndOutOfRange…` · 40 malformed `malformedOrUnknownObservedStates…` · 41 cache determinism
`perIdCacheIsDeterministicSharedAndBounded` · 42 immutable result `resultsAreImmutableDeterministicAndOrdered` ·
43 session isolation / 45 cleanup `sessionsAreIsolatedAndCleanup…`, `engineCloseReleases…` · 44 fixtures
`CollisionFixtureDeterminismTest`. Extra: truncation, context/dynamic envelopes, outside-dimension, metrics, engine
integration (`CollisionEngineIntegrationTest`), world-view equivalence (`WorldViewTest`).
