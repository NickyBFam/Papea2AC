# Phase 3 implementation report — World Reconstruction (observer world)

**PHASE 3 IMPLEMENTATION STATUS: READY FOR BASELINE (human review) — final qualification pass 2026-09-19: P3-C1 RESOLVED (schema bumped to 2); real-client world qualification PERFORMED and PASSED after fixing P3-F3…F13; pre-baseline decisions recorded (P3-F7 CONFORMANT, recording DEGRADED = EXPECTED SAFE LIMITATION, session health = EXPECTED SAFE STATUS; no human exception required)**

Date: 2026-09-18 (implementation), 2026-09-19 (final qualification pass). This is the implementer's report, not a review and not an approval: the implementer does not
self-approve, does not output APPROVE, has not committed, and has not created a Phase 3 baseline. Phase 1 and
Phase 2 remain complete, human-approved and baselined (Phase 1 baseline `35c44f9b…`, Phase 2 baseline
`f24824f9…`, Phase 2 receipt `a95169e8…` = current `HEAD`). Production transport remains **CAPTURE-ONLY**;
authoritative outcome correlation remains **UNSUPPORTED**; Phase 1 semantic coverage remains **127/127, 0
mandatory gaps**. No Phase 4+ code (collision, physics, checks, setbacks, punishments) exists.

**The one sentence that governs this phase:** the observer world answers *"what block/chunk state had Papea2AC
observed as sent to this player at this ordered point?"* — never *"what is the server's world"* and never *"is this
movement physically valid"*. `SERVER WORLD STATE != CLIENT-OBSERVED WORLD STATE`; no code in `anticheat-world` or
in the Phase 3 wiring queries a live Bukkit/NMS world, and `anticheat-world` has no Paper/NMS/Netty dependency
(`ArchitectureTest.worldModuleStaysWithinThePhase3Boundary`, `platformIsolation…`).

## 0. Test counts (Phase 2 convention: separate counts, never an aggregate alone)

| Suite | Phase 2 baseline | Now | Added | Notes |
|---|---|---|---|---|
| `test` | 215 | **353** | +138 | 34 classes; 0 failed, 0 skipped (`clean test --rerun`, fresh XML verified) |
| `nativeTest` | 105 | **115** | +10 | `NativeChunkSectionTest` (8) + `NativePipelineControlObjectTest` (2) against the pinned Paper 26.2 build 124 jar |
| architecture | 6 (in `test`) | 7 (in `test`) | +1 | `worldModuleStaysWithinThePhase3Boundary`; `noFutureEngineOrForbiddenMathImplementation` no longer lists `world` (it is now an implemented module) |
| other verification tasks | — | — | — | `verifyMandatoryCoverage` (127/127), `replayConformance` (pinned Phase 1 capsule), `worldFixtures` (12 digests, generated twice byte-identical), `observerWorldMemoryHarness`, `benchmarkObserverWorld` (JMH) |
| **unique total** | 320 | **468** | +148 | `test` + `nativeTest`; architecture is inside `test`, not double counted |

Phase 3 test classes: `WorldCoordinateTest` 20, `SectionSnapshotTest` 9, `ObserverWorldTest` 30,
`ObserverWorldPropertyTest` 32 (7 properties × seeds), `WorldReplayMapperTest` 6, `WorldFixtureDeterminismTest` 3,
`ObserverWorldIntegrationTest` 4, `PlayerWorldCoherenceTest` 2, `BlockStateIdentityTest` 4, `CapsuleSchemaCompatibilityTest` 6, `LegacyCodecCompatibilityTest` 4 (P3-C1), `ObserverWorldIntegrationTest` grew to 13 and `ObserverWorldTest` to 32 with the real-client regressions, `LiveRecordingTest` +2 (P3-F10/F13), plus `ArchitectureTest`
+1 and `NativeChunkSectionTest` 8 (nativeTest).

## 1. Scope

Implemented: the production-quality observer world reconstructed from **server→client** normalized traffic —
dimension/world epochs, chunk baselines with decoded section content, single/multi-block updates, unloads,
bounded block-entity summaries, view center/radius, block events (recorded, never simulated), coverage model,
copy-on-write immutable sections with known-masks, revision model, bounded history with exact historical lookups,
content-addressed shared section store + per-observer private tier + global accounting, fair eviction with
UNLOADED/EVICTED provenance, replay projection (`WorldCapsule`), deterministic fixtures, property tests,
Phase 1→3 integration, Phase 2+3 coherence, JMH, memory qualification, bounded metrics, the `/papea2ac world`
diagnostic, and a Phase-3-aware Paper smoke.

Not implemented (deferred to Phase 4+, by spec): block shapes/AABBs, collision, physics, movement simulation,
fluid/piston/light semantics beyond recording what was sent, any check, setback or punishment.

## 2. Module ownership (approved DAG, no new module, no new edge)

| Role | Module | Content |
|---|---|---|
| **WORLD MODEL OWNER** | `anticheat-world` (deps: `model`, `version`, `replay-schema` — unchanged) | `ObserverWorld` (reducer/store), `SectionSnapshot`, `ChunkSnapshot`, `WorldCoverage`, `BlockLookup`, `DimensionProfile`, `BlockStateDictionary`, `WorldBudget`, `WorldObservation` (sealed input), `WorldReplayMapper` |
| Platform-independent identities | `anticheat-model` | `ChunkKey`, `BlockPos`, `BlockStateKey` |
| **INPUT MODULES** | `anticheat-protocol` (Phase 1 semantics: `ChunkBaseline`+`ChunkSection`, `JoinGame`/`Respawn` dimension range, `BlockChange`, `SectionBlocksUpdate`, `ChunkUnload`, …), `anticheat-platform-paper` (`PaperSemanticNormalizer` decodes the wire section format into protocol records — the only place NMS is touched), `anticheat-core` (`WorldObservationMapper`: protocol `Observation` → `WorldObservation`, the DAG-mandated translation since `world` may not see `protocol`) | |
| **OUTPUT / CONSUMERS** | `anticheat-core` (`PacketEngine.Session.world`, `WorldStatus` primitive projection, `worldBudgetSnapshot()`), `anticheat-platform-paper` (`/papea2ac world`, status line — via `WorldStatus` only; platform-paper may not depend on `world`), `anticheat-replay-schema` (`WorldCapsule` DTO) | |
| **DEPENDENCY DIRECTION** | `model` ← `world` ← `core` ← `platform-paper`; `replay-schema` is a leaf consumed by `world` (DTO only) | `ArchitectureTest.exactModuleGraph…` unchanged |

## 3. Observer world principle / no live server query

`ObserverWorld.apply` is fed exclusively from `PacketEngine.Shard.run()` with `WorldObservationMapper.map(o)` —
the same ordered `Observation` stream `TruthActor` and `PlayerTimeline` consume, one ingestion point, no second
listener. Only clientbound families are mapped (`Observation.Outbound`, `Tick`); `Attempt`/`Outcome`/control
events map to nothing (they advance the world's "observed through" bound, §17). Nothing in the world path reads
`World`, `Chunk`, `Block` or NMS level state: `grep -rn "getWorld\|getBlockAt\|getChunkAt\|ServerLevel" anticheat-world
anticheat-core` finds nothing.

## 4. World / dimension epoch

`WorldObservation.DimensionEntered` (from `JoinGame` — respawn=false — or `Respawn` — respawn=true). A respawn is
an explicit discontinuity: all chunks are released, tombstones and history dropped, `worldEpoch++`,
`epochStartSequence = sequence`. Historical lookups before the epoch start answer `WRONG_WORLD_EPOCH` (old-epoch
history is not retained). The dimension profile (`minY`, `height`, section count) comes from the packet's
DimensionType holder (`dimensionMinY`/`dimensionHeight`, section-aligned, validated), never hard-coded 0..255
(`dimensionTransitionIsAnExplicitDiscontinuityNothingCarriesOver`, `negativeYAndDimensionExtremesMapToTheCorrectSections`,
fixture `dimensionTransition`).

## 5. Chunk key, 6. Block position

`ChunkKey(x,z)` uses arithmetic shift (`>>4`, floor division — `chunkOfBlockUsesFloorDivisionNotTruncation`), 26-bit
bound (`MAX_ABS`), overflow-safe `distanceTo`. `BlockPos(x,y,z)` holds the protocol range (26/12/26-bit), rejects
out-of-range coordinates instead of wrapping (`coordinatesOutsideTheRepresentableRangeAreRejectedNeverWrapped`),
packs to one long and round-trips across the whole range (property test, 8 seeds × 20 000), locals are always
0..15, `sectionIndex = (y&15)<<8 | (z&15)<<4 | (x&15)` matches the wire (`sectionIndexOrderMatchesTheWire`, verified
against the real `PalettedContainer` in `NativeChunkSectionTest`).

## 7. Chunk baseline (wire → protocol → world)

`PaperSemanticNormalizer.chunkBaseline()` decodes the `ClientboundLevelChunkWithLightPacket` buffer with the
bytecode-verified layout (`LevelChunkSection.write`: short nonEmpty, short fluid, block `PalettedContainer`, biome
`PalettedContainer`; `PalettedContainer.write`: byte bits, palette, fixed-size long[]; strategies 0 single / 1–4
stored at 4 bits / 5–8 hashmap / >8 global; biome 0 / 1–3 / >3). Global-palette sections are re-palettized to an
indirect palette of `BlockState` keys via `Block.BLOCK_STATE_REGISTRY` (memoized). Any decode fault or content above
`INLINE_CONTENT_CAP` (40 KiB) produces `contentRetained=false` with a note — an explicit gap. The protocol
record `ChunkBaseline` carries `List<ChunkSection>`; `SemanticCodec` encodes/decodes it (tag 79) validating bits,
palette size and derived long count **before** allocating. `ObserverWorld.baseline()` interns palette keys,
validates every section (`SectionSnapshot.fromPalette`), acquires each into the shared store, and installs a
`COMPLETE` chunk atomically; a section-count mismatch, malformed section, dictionary exhaustion or shared-partition
rejection yields `CONTENT_NOT_RETAINED` with a note and releases anything acquired for that chunk — never a
half-chunk, never air. Re-baseline of an existing chunk pushes every previous section to history and replaces the
whole chunk (`fullBaselineReplacementReplacesEverythingDeterministically`, fixture `reloadFullResend`).

## 8. Section storage

`SectionSnapshot`: int palette of dictionary ids + packed long[] (values never straddle a long, same layout as the
wire) + optional 64-long `knownMask` (null = complete). Never 4096 objects, no `Map<BlockPos,…>`, no boxing.
Copy-on-write `with(index,id)` grows the palette/bit width only when the state is new; each snapshot owns its arrays
(no parent chains — §26 of the task). `fromPalette`/`fromParts` validate before any index is trusted
(`malformedPaletteInputFailsSafely`, `invalidPackedIndexIsRejectedBeforeAnyCellIsTrusted`,
`paletteLookupDecodesEveryCellAtEveryBitWidth` for 4/5/6/7/8/9/12 bits). An `unknown()` section has an **empty**
palette (no placeholder id can leak into a projection — found by the fixture-determinism test, §20).

## 9. Block state identity / 10. Properties

Identity is `BlockStateKey` = block id + sorted properties (canonical string form round-trips), never a native
numeric state id and never a run-local integer. `BlockStateDictionary` interns keys to ints **per run** for compact
sections; ids are never exported — `WorldReplayMapper` writes canonical keys, and two runs with different id
assignments produce identical projections (`exportIsDeterministic…`, `theSameObservationSequenceProducesTheSameWorldModel`,
every fixture with a perturbed dictionary). `BlockStateIdentityTest`: wire property order, palette slot order, bit
width and dictionary id are all storage details, not identity; the same state from a baseline and from an update is
one identity. Native global id → `BlockState` is proven against the pinned jar (`NativeChunkSectionTest`, 8).

## 11. Full chunk replacement, 12. Single block update, 13. Multi-block update

Single update: `blockChange()` → copy-on-write section replacement in one revision; unaffected cells are preserved
(`randomBlockUpdatesPreserveEveryUnaffectedBlockState`, 5 seeds × 2 000 updates × 64 probes). Multi-block: the whole
batch is validated (same chunk, same section Y, all states resolvable) **before** anything is touched, then applied
as one revision (`multiBlockUpdateAppliesAtomicallyAsOneRevision`); an empty batch commits nothing. Bundle children
are applied in wire order within one sequence via `subIndex` (`aBundleIsAppliedInWireOrderWithinOneSequence`).

## 14. Unload, 15. View changes, 16. Baseline absence / partial baselines

Unload removes current availability and leaves an `UNLOADED` tombstone (sequence, revision, createdSequence);
history keeps the last revision for exact lookups within the horizon; a later update on that chunk creates a
`PARTIAL` chunk noted `AFTER_UNLOADED`, never a resurrected complete one
(`unloadMakesCurrentStateUnavailable…`, fixture `unload`). `ChunkCacheCenter`/`ChunkCacheRadius` update the view
center/radius used only for fair eviction ordering. **A block update without a baseline creates a PARTIAL chunk whose
section knows exactly the observed cells** — neighbours are `UNKNOWN_CELL`, other sections `UNKNOWN_SECTION`,
never air (`singleBlockUpdateWithoutBaselineCreatesAPartialChunkNotAFullOne`,
`partialNeverBecomesCompleteWithoutAQualifyingBaseline` 4 seeds × 3 000, fixture `partialBaseline`). A chunk
becomes `COMPLETE` only through a full baseline (or when every one of a section's 4096 cells has been observed —
`aPartialSectionBecomesCompleteOnlyWhenEveryCellHasBeenObserved`).

## 17–18. Mid-session activation / baseline recovery

Sessions that existed before capture (or before a baseline was sent) simply have `UNKNOWN_CHUNK` for never-sent
chunks; nothing is reconstructed from the server, nothing is force-resent (WORLD_MODEL.md W2). Coverage recovers only
through genuinely observed baselines. **No silent baseline recovery exists anywhere.**

## 19–20. Coverage model — never heals accidentally

`WorldCoverage`: `COMPLETE`, `PARTIAL`, `CONTENT_NOT_RETAINED`, `TRANSPORT_INCOMPLETE`, `UNLOADED`, `EVICTED`,
`UNKNOWN`. `BlockLookup.Status`: `KNOWN`, `UNKNOWN_CELL`, `UNKNOWN_SECTION`, `UNKNOWN_CHUNK`, `UNLOADED`, `EVICTED`,
`CONTENT_NOT_RETAINED`, `TRANSPORT_INCOMPLETE`, `OUTSIDE_DIMENSION`, `DIMENSION_UNKNOWN`, `WRONG_WORLD_EPOCH`,
`HISTORY_NOT_RETAINED`, `FUTURE_SEQUENCE` — a miss never carries a state (record invariant). **Transport breaks (final
semantics after the real-client qualification, P3-F3/F8/F12):** a break is an *event* — a session-sequence gap detected
by the world itself (a dropped capture consumes a sequence number), a loss-class Phase 1 latch reason (`OVERLOADED`,
`LOST_SERVER_OBSERVATION`, `MALFORMED`, `SHARD_FAILED`, `CLOSED`, `WORK_QUOTA`, `INTERNAL_FAILURE`), or the platform
reporting the capture position compromised (`CAPTURE_COMPROMISED:<reason>`). Every chunk held at that moment becomes
`TRANSPORT_INCOMPLETE` and stays so until *that chunk* is re-sent as a full baseline; block updates never heal it;
baselines received after the break are COMPLETE (a fresh authoritative view of their chunk). Outcome-correlation
reasons (`EXPIRED`, `OUTCOME_UNKNOWN`, `CANCELLED`, `TRANSFORMED`) and `PIPELINE_UNCOVERED` (uncovered *packets*,
state-free by the mandatory gate) never break world coverage; uncovered clientbound payloads and non-packet pipeline
objects are not observations. The diagnostic shows `HEALTHY` or `BREAKS=n last=<reason>@<seq>`
(`transportCoverageBreakMarksEveryChunkUntrustworthyUntilANewBaseline`, `aDroppedCaptureLeavesASequenceGap…`,
`outcomeExpiryDegradesTheSessionButNeverBreaksWorldCoverage`, `unknownPlayerHasNoWorldStatusAndTransportDegradeBreaksCoverage`,
fixture `captureCoverageBreak`). `revisionIsMonotonicHistoryStaysBoundedAndUnknownNeverBecomesKnownWithoutObservation`
probes a random never-observed cell after each of 4 000 random observations × 5 seeds.

## 21. Clientbound ordering, 22. Revision model

Ordering is the session capture sequence (+ `subIndex` inside a bundle); non-monotonic pairs and stale session keys
are rejected as no-ops (`sessionReplacementIsStructuralAndLateOldSessionEventsAreRejected`,
`anOldSessionCannotMutateTheNewSessionsWorld` 3 seeds × 1 000). `revision` advances on every material change
(dimension, baseline, section commit, unload, eviction, coverage break, block entity) and never on ticks, view
changes or rejected inputs; the same script yields the same revisions (`revisionsAdvanceDeterministically…`).

## 23–24. Historical lookups and bounds

`blockAtSequence(pos, S)` = the state observed **through** S (inclusive): the oldest history revision of that
section committed after S provides its `before`; a chunk created after S is `UNKNOWN_CHUNK`; a revision that could
have changed the answer but was evicted → `HISTORY_NOT_RETAINED` (never approximated); S beyond the observed bound →
`FUTURE_SEQUENCE`; S before the epoch → `WRONG_WORLD_EPOCH`. History is a ring of section revisions bounded by
`HISTORY_MAX_ENTRIES` = 4096, the 3-second horizon (`HISTORY_HORIZON_NANOS`), and the private byte tier (history is
evicted before chunks). Tests: `historicalExactLookupReturnsTheStateAsOfThatSequence`,
`historyAcrossUnloadIsAnsweredFromTheRetainedRevision`, `evictedHistoryIsReportedNotSilentlyApproximated`,
`historyAlsoExpiresByTheThreeSecondHorizon`. Query complexity: current lookup O(1) hash + O(1) palette decode;
historical lookup O(h) scan of the ring (h ≤ 4096) — adequate for a non-hot forensic path, measured in §29.

## 25–27. Structural sharing, no parent chains, palette safety

Complete sections are content-addressed in `WorldBudget`'s shared store (`acquire` returns the canonical instance,
reference-counted; `release` frees on the last reference). Private sections (created by updates) are owned by one
observer. Every snapshot is a flat array copy — no revision chain to walk. Shared structures are immutable and
content-equal by construction; per-observer coverage/revision semantics live in the observer's `ChunkSnapshot`, never
in the shared section (task §61: no shared mutable world truth).

## 28–32. Memory budget, global accounting, fair eviction, eviction semantics, re-observation

Per-observer private tier: `PRIVATE_FLOOR_BYTES` 768 KiB (engine default) / `PRIVATE_TARGET_BYTES` 2 MiB
(PERFORMANCE.md tiers). Enforcement after every apply: evict history first, then the chunk farthest from the view
center (EVICTED tombstone, distinct from UNLOADED). Shared partition `SHARED_PARTITION_BYTES` = 96 MiB
(PERFORMANCE.md G partition): an admission failure yields `CONTENT_NOT_RETAINED` + `budgetRejections++`
(`sharedBudgetExhaustionDegradesCoverageWithoutFabricatingState`). Global counters are fixed-shape
(`WorldBudget.Snapshot`: observerWorlds, chunks, complete/partial, evicted/unloaded, sections, revisions,
historyEvictions, unknownLookups, coverageBreaks, budgetRejections, private/shared bytes, sharedSections,
sharedHits). `close()` and the supervisor path `retireAfterOwnerTermination()` (refused while the owner thread is
alive) return accounting to baseline (`closeReleasesEverything…`, `supervisorReclaimsAWorldOnlyAfterItsOwnerThreadTerminated`,
engine tests assert `sharedBytes == 0` after engine close). Re-observation after eviction/unload starts PARTIAL until
a new baseline (`evictionIsDistinctFromUnload…`, fixture `evictedThenPartialUpdate`).

**Defect found by the property tests and fixed (P3-F1, Phase 3 code, no baseline touched):** `releaseChunk`
released a chunk's shared references *before* computing its private charge, so a section whose reference count
dropped to zero was subtracted as if private, driving `privateBytes` negative under eviction/close. Fixed by settling
the charge before releasing; regression `evictingTheSoleHolderOfASharedSectionNeverDrivesThePrivateChargeNegative`
(verified to fail on the old ordering) plus `unloadRemovesCurrentAvailabilityAndEvictionFreesChargedMemory`
(3 seeds × 400, recomputes the charge from the retained chunks after every step).

## 33–37. Block entities, light, fluid, pistons, client-side temporary state

Block entities: bounded per-chunk summaries (type, encoded bytes, sequence; `MAX_BLOCK_ENTITIES` 256), never NBT
content, gone with the chunk (removal therefore needs no separate observation — item 25 "if relevant"). Light data
is not modelled (recorded only as `hasLight`). Fluid state: only the section's `fluidCount` crosses the wire; no
fluid semantics. Pistons/moving blocks and client-side predicted/temporary block state: recorded as bounded
`BlockEvent`s (`BLOCK_EVENT_MAX` 256, `blockEventsAreRecordedBoundedNeverSimulated`) — never simulated. All of this
is Phase 4+ semantics by spec.

## 38–40. Dimension height, chunk math, section Y math

`DimensionProfile` validates section alignment and protocol limits (`dimensionRangesMustBeSectionAlignedAndWithinProtocolLimits`),
`sectionIndexOf(blockY)` = `(blockY >> 4) - minSectionY` with negative minima and no off-by-one
(`dimensionProfileHandlesNegativeMinimumHeightWithoutOffByOne`, fixture `negativeCoordinateChunk`).

## 41–43. Lookup result type, query immutability, reader concurrency

`BlockLookup(status, stateId, state, revision, chunkCoverage)`; queries never mutate (a lookup only increments the
`unknownLookups` metric). The owner thread queries the live store; other threads use the last published immutable
`WorldSnapshot` (`Map.copyOf`, published on ticks/structural changes — the /papea2ac world path)
(`publishedSnapshotIsImmutableAndAnswersLookupsCrossThread`, `ownershipIsAsserted`).

## 44–45. Player/world ordering coherence, bundle ordering

`PlayerTimeline` and `ObserverWorld` share the session capture sequence. A world-silent sequence (movement claim,
outcome) advances the world's "observed through" bound via `observeSequence()` (called by the shard when an
observation maps to no world observation), so `player(S)` always pairs with `world through S` instead of
`FUTURE_SEQUENCE` (`PlayerWorldCoherenceTest`: baseline at A, movement at B, block update at C, movement at D —
`player(B)` pairs with the pre-update world, `player(D)` with the updated one, through the real engine too;
`observeSequenceAdvancesTheObservedThroughBoundWithoutARevision`). No collision is invoked anywhere.

## 46–49. Failure containment, allocation failure, disconnect, reconnect

A `RuntimeException` in the shard loop fails the session (Phase 1 path); the world for a dead shard is reclaimed by
the supervisor only after the owner thread terminated. Shared-partition rejection and dictionary exhaustion are
explicit coverage outcomes. Disconnect → `Session.close()` → `world.close()` on the shard releases every reference
(`clientboundWorldPacketsCapturedThroughTheRealEngine…` asserts `sharedBytes == 0` after engine close). Reconnect
= new `Session` = new `ObserverWorld` instance = structural isolation; nothing carries over, the old session's
shared references are released (`reconnectGetsAFreshEmptyWorldAndTheOldSessionsSharedChargeIsReleased`).

## 50–51. Replay projection; replay never increases knowledge

R2 pattern: DTO `WorldCapsule` in `anticheat-replay-schema`, mapper `WorldReplayMapper` in `anticheat-world`.
Canonical keys (never dictionary ids), chunks sorted by (x,z), block entities by packed position, known-masks and
tombstones (with `createdSequence`) carried verbatim, `transportBroken`/reason carried. `restore()` re-acquires
shared sections so accounting is exact and rejects corrupt capsules without installing anything
(`restoreRejectsCorruptCapsulesWithoutInstallingAnything`). `export → restore → export` is byte-identical with a
different dictionary; PARTIAL stays PARTIAL, unknown cells never decode as air
(`partialStaysPartialAndUnknownCellsNeverDecodeAsAir`, `contentNotRetainedTransportIncompleteAndEvictedAreCarriedVerbatim`).

## 52. Deterministic fixtures

12 code-defined fixtures (no chunk dumps): `fullChunkBaseline`, `negativeCoordinateChunk`, `singleBlockUpdate`,
`multiBlockUpdate`, `unload`, `reloadFullResend`, `partialBaseline`, `evictedThenPartialUpdate`,
`dimensionTransition`, `captureCoverageBreak`, `contentNotRetainedBaseline`, `reconnectNewSession`. Pinned SHA-256
digests of the canonical `WorldCapsule` projection in `docs/phase3-artifacts/world-fixtures.json`
(`worldFixtures` task; regenerated twice — byte-identical, `9c6d5451…` file digest). `WorldFixtureDeterminismTest`
replays each twice in-process with a perturbed dictionary and against the pinned digest; a changed digest is a
reviewed event, never a silent update.

**Defect found by the fixtures (P3-F2, Phase 3 code):** `SectionSnapshot.unknown()` used palette slot 0 (dictionary
id 0) as a placeholder that the projection rendered through `dictionary.key(0)`, leaking a dictionary-order-dependent
key into partial sections. Fixed: the unknown section has an empty palette; `with()` seeds a single-slot palette on
the first observed cell; `fromParts` round-trips the empty form.

## 53. Core unit-test matrix (task §53, 40 items)

| # | Item | Test |
|---|---|---|
| 1 | empty observer world | `emptyObserverWorldKnowsNothingAndSaysSo` |
| 2 | full chunk baseline | `fullChunkBaselineMakesEveryCellKnown` |
| 3 | block lookup | same + `currentBlockLookup` benchmarks |
| 4 | unknown chunk | `unknownChunkSectionAndInvalidYAreDistinctResults` |
| 5 | unknown section | same |
| 6 | partial chunk | `singleBlockUpdateWithoutBaselineCreatesAPartialChunkNotAFullOne` |
| 7 | single block update | `singleBlockUpdateOnACompleteChunkKeepsCompletenessAndLeavesThePriorRevisionImmutable` |
| 8 | multi-block update | `multiBlockUpdateAppliesAtomicallyAsOneRevision` |
| 9 | immutable previous revision | item 7 test + `copyOnWriteLeavesTheOriginalUntouched…` |
| 10 | full baseline replacement | `fullBaselineReplacementReplacesEverythingDeterministically` |
| 11 | chunk unload | `unloadMakesCurrentStateUnavailableWithUnloadProvenanceAndReObservationStaysPartial` |
| 12 | unload vs eviction | `evictionIsDistinctFromUnloadAndReObservationAfterEvictionStaysPartial` |
| 13 | re-observation after unload | item 11 |
| 14 | re-observation after eviction | item 12 |
| 15 | negative chunk coordinates | `negativeChunkCoordinatesAndBoundariesResolveToTheRightChunk`, `chunkOfBlockUsesFloorDivisionNotTruncation` |
| 16 | chunk boundaries | same |
| 17 | negative Y | `negativeYAndDimensionExtremesMapToTheCorrectSections`, `sectionYFloorsForNegativeBlockY` |
| 18 | dimension minimum height | same + `dimensionProfileHandlesNegativeMinimumHeightWithoutOffByOne` |
| 19 | dimension maximum height | same |
| 20 | invalid Y | `unknownChunkSectionAndInvalidYAreDistinctResults` (OUTSIDE_DIMENSION) |
| 21 | palette lookup | `paletteLookupDecodesEveryCellAtEveryBitWidth` |
| 22 | malformed palette input | `malformedPaletteInputFailsSafely` |
| 23 | invalid packed index | `invalidPackedIndexIsRejectedBeforeAnyCellIsTrusted` |
| 24 | block entity update | `blockEntityObservationsAreBoundedSummariesOnTheChunk` |
| 25 | block entity removal | NOT APPLICABLE BY SPEC (summaries die with the chunk; no removal packet is modelled) |
| 26 | dimension/world transition | `dimensionTransitionIsAnExplicitDiscontinuityNothingCarriesOver` |
| 27 | capture epoch break | `sessionReplacementIsStructuralAndLateOldSessionEventsAreRejected` |
| 28 | transport coverage break | `transportCoverageBreakMarksEveryChunkUntrustworthyUntilANewBaseline`, `degradedTransportReasonOnApplyBreaksCoverageTheSameWay` |
| 29 | prefix incomplete | `prefixIncompleteBaselineIsExplicitNotAirFilled` |
| 30 | history exact lookup | `historicalExactLookupReturnsTheStateAsOfThatSequence` |
| 31 | history eviction | `evictedHistoryIsReportedNotSilentlyApproximated`, `historyAlsoExpiresByTheThreeSecondHorizon` |
| 32 | session replacement | item 27 |
| 33 | late old-session event | item 27 + property `anOldSessionCannotMutateTheNewSessionsWorld` |
| 34 | budget exhaustion | `sharedBudgetExhaustionDegradesCoverageWithoutFabricatingState` |
| 35 | global accounting release | `closeReleasesEverythingAndGlobalAccountingReturnsToBaseline` |
| 36 | deterministic revision | `revisionsAdvanceDeterministicallyAndOnlyOnMaterialChange` |
| 37 | replay projection | `WorldReplayMapperTest` (6) |
| 38 | replay coverage preservation | `partialStaysPartial…`, `contentNotRetained…CarriedVerbatim`, `evictedTombstonesSurvive…` |
| 39 | fixture determinism | `WorldFixtureDeterminismTest` (3) |
| 40 | shutdown cleanup | item 35 + `supervisorReclaimsAWorldOnlyAfterItsOwnerThreadTerminated` + engine-close assertions |

## 54. Property / fuzz tests (`ObserverWorldPropertyTest`, seeds printed in every failure message)

| Property | Seeds × steps |
|---|---|
| chunk coordinate conversions round-trip; locals always 0..15 | 8 × 20 000 |
| revision monotonic; history ≤ 4096; private tier respected; unknown never becomes known without observation | 5 × 4 000 |
| partial never becomes complete without a qualifying baseline | 4 × 3 000 |
| unload removes current availability; eviction frees charged memory (charge recomputed from retained chunks each step); close releases all | 3 × 400 |
| old session cannot mutate new session | 3 × 1 000 |
| same observation sequence ⇒ same world model (different dictionaries) | 4 × 1 500 |
| random block updates preserve unaffected block states | 5 × 2 000 (× 64 probes) |

## 55–57. Phase 1 → Phase 3 integration, Phase 2 + 3 coherence, version/registry

`ObserverWorldIntegrationTest` (4): real `SemanticPayload.JoinGame`/`ChunkBaseline`(with `ChunkSection`s)/
`BlockChange`/`SectionBlocksUpdate`/`ChunkUnload`/`Bundle` captured through `PacketEngine.connect` +
`ingress.capture` → shard → mapper → world → `WorldSnapshot.blockAt` / `worldStatus` / `worldBudgetSnapshot`;
transport degrade classification, gap detection, bulk pool, flood drain, oversize bundles, capture-compromised breaks
(13 tests after the real-client fixes); reconnect isolation and release. `PlayerWorldCoherenceTest` (2), §44.
`BlockStateIdentityTest` (4), §9; the native id path in `NativeChunkSectionTest` (8) — no future version adapters were
built.

## 58. Performance (JMH 1.37; `docs/phase3-artifacts/benchmarks.json`)

Configuration (identical to Phases 1–2): JMH 1.37, `Mode.AverageTime`, 3 forks, 5 × 1 s warmup, 10 × 1 s
measurement (Cnt 30), `-Xms512m -Xmx512m`, `-prof gc`, JDK 25.0.4.1+1-LTS (Temurin, `.phase1-tools`), Windows 11
amd64, no concurrent load (the run overlapped with nothing else; an earlier run that overlapped a test execution was
discarded). Overworld-shaped 24-section chunks; descriptive anomaly-detection kernels, **not a capacity claim**.

| Kernel | avgt ns/op (± 99.9 %) | alloc B/op | Reading |
|---|---|---|---|
| `fullChunkBaselineIngestSharedHit` (24 sections, content already shared; replace path incl. history push) | **19 173 ± 137** | 8 688 | ≈ 0.8 µs/section: palette intern + validation scan of 4096 cells + shared lookup |
| `fullChunkBaselineIngestUniqueContent` (shared-store miss + acquire) | **20 780 ± 61** | 10 504 | +1.6 µs for the acquire of the unique section |
| `singleBlockUpdate` | **535 ± 7** | 2 376 | one copy-on-write section (2 KiB packed) + chunk + history record |
| `multiBlockSectionUpdate16` | **841 ± 14** | 2 576 | batched `withAll`: 16 cells for 1.6 × the cost of one (was 5 930 ns / 33 872 B before the fix) |
| `currentBlockLookup` (owner thread, 121-chunk world) | **17.4 ± 0.5** | 40 | hash + palette decode; only the `BlockLookup` record allocates |
| `currentBlockLookupViaSnapshot` (cross-thread immutable snapshot) | **19.5 ± 0.1** | 40 | same path through `WorldSnapshot` |
| `historicalBlockLookup` (2048-entry history ring, exact) | **2 598 ± 48** | 64 | O(h) ring scan — forensic path |
| `sectionCopyOnWrite` (`SectionSnapshot.with`, 4-bit section) | **328 ± 4** | 2 096 | the 2 KiB packed copy dominates |
| `chunkUnloadAndRebaseline` | **19 247 ± 93** | 8 952 | ≈ ingest cost ⇒ the unload itself is ≈ 75 ns / 264 B |
| `historyEvictionAtCapacity` (every update evicts the oldest revision) | **537 ± 7** | 2 376 | eviction adds nothing measurable over `singleBlockUpdate` |
| `publishSnapshot121Chunks` (`Map.copyOf` on tick) | **2 629 ± 32** | 3 592 | ≈ 22 ns/chunk per publication; tick-bounded cadence |

Relative to Phase 2 kernels (movement append 47 ns, exact historical lookup 4.7 µs) the world's hot paths are in
the same order of magnitude; baseline ingest at ~19 µs per chunk means a 441-chunk view is fully reconstructed in
≈ 8.5 ms of shard time spread over the chunk-send window — no main-thread work is involved anywhere.

### §58 addendum — final re-run after the real-client fixes (2026-09-19, quiet host, same config)

| Kernel | avgt ns/op | alloc B/op | vs implementation-pass run |
|---|---|---|---|
| fullChunkBaselineIngestSharedHit | 12 186 ± 51 | 8 720 | −36 % time, +32 B |
| fullChunkBaselineIngestUniqueContent | 12 965 ± 49 | 10 536 | −38 %, +32 B |
| singleBlockUpdate | 312 ± 4 | 2 376 | −42 %, alloc unchanged |
| multiBlockSectionUpdate16 | 484 ± 6 | 2 576 | −42 %, unchanged |
| currentBlockLookup / ViaSnapshot | 11.0 / 13.0 | 40 / 40 | −37 %, unchanged |
| historicalBlockLookup | 1 361 ± 19 | 64 | −48 %, unchanged |
| sectionCopyOnWrite | 150 ± 6 | 2 096 | −54 %, unchanged |
| chunkUnloadAndRebaseline | 12 546 ± 90 | 9 016 | −35 %, +64 B |
| historyEvictionAtCapacity | 314 ± 3 | 2 376 | −42 %, unchanged |
| publishSnapshot121Chunks | 1 330 ± 8 | 3 624 | −49 %, +32 B (rendering the 8-entry update ring) |

Every kernel — including `sectionCopyOnWrite` and `currentBlockLookup`, which no real-client fix touches — runs 35–54 % faster
than the implementation-pass run while allocation per op is unchanged to within 64 B: this is host-state drift (the same
phenomenon seen in the other direction on the Phase 1 kernels), not an effect of the gap check, the per-chunk break
bookkeeping or the block-update ring, all of which are allocation-free on the hot path by construction. Rankings and
the cost model are unchanged; the file `benchmarks.json` holds this final run.

## 59–61. Memory qualification (`docs/phase3-artifacts/memory-results.json`, `observerWorldMemoryHarness`)

Method: forced-GC heap diff, 3 trials, median, N observers sharing one `WorldBudget`/dictionary; realistic terrain
(8 ore-speckled 4-bit stone sections, 2 mixed surface sections, 14 single-value air sections ≈ 21 KiB charged per
unique chunk). View sizes are **scaling scenarios**, not a server configuration. **The memory model is a HYBRID:**
shared immutable content-addressed baseline store (charged once globally, reference-counted) + per-observer private
delta (update-created sections, history, chunk/tombstone overhead). SHARED = every observer was sent the same chunk
content (co-located players); UNIQUE = every observer's chunks differ (fully spread out) — these are the 0 % and 100 %
divergence bounds WORLD_MODEL.md W2 asked Phase 3 to measure.

| Scenario | Observers | Chunks/obs | Content | Median heap / observer | Charged / observer | Shared total | Private total | Rejections / evictions |
|---|---|---|---|---|---|---|---|---|
| emptyWorld | 200 | 0 | — | **2.1 KB** | 0 | 0 | 0 | 0 / 0 |
| oneChunk (shared) | 200 | 1 | shared | **2.6 KB** | 394 B | 21.2 KB | 57.6 KB | 0 / 0 |
| oneChunkUnique | 100 | 1 | unique | **25.9 KB** | 21.5 KB | 2.12 MB | 28.8 KB | 0 / 0 |
| view25Shared | 50 | 25 | shared | **23.6 KB** | 17.8 KB | 529 KB | 360 KB | 0 / 0 |
| view25Unique | 10 | 25 | unique | **587 KB** | 536 KB | 5.29 MB | 72 KB | 0 / 0 |
| view121Shared | 50 | 121 | shared | **92.8 KB** | 86 KB | 2.56 MB | 1.74 MB | 0 / 0 |
| view121Unique | 10 | 121 | unique | **2.74 MB** | 2.60 MB | 25.6 MB | 348 KB | 0 / 0 |
| view289Shared | 20 | 289 | shared | **408 KB** | 389 KB | 6.12 MB | 1.66 MB | 0 / 0 |
| view289Unique | 10 | 289 | unique | **6.51 MB** | 6.20 MB | 61.2 MB | 832 KB | 0 / 0 |
| view441Shared (W2 distance 10) | 10 | 441 | shared | **1.12 MB** | 1.06 MB | 9.34 MB | 1.27 MB | 0 / 0 |
| view441Unique | 4 | 441 | unique | **9.95 MB** | 9.46 MB | 37.3 MB | 508 KB | 0 / 0 |
| view1089Shared (W2 distance 16) | 5 | 1089 | shared | **4.87 MB** | 4.65 MB | 21.7 MB | 1.57 MB | 0 / 0 |
| view1089Unique | 4 | 1089 | unique | **23.0 MB** | 22.0 MB | 86.8 MB (of 96 MiB) | 1.25 MB | 0 / 0 (a 5th observer would be rejected) |
| mixedUpdatesHistory121 (3 000 updates, floor tier 768 KiB) | 20 | 121 | shared | **905 KB** | 881 KB | 1.91 MB | 15.7 MB | 0 / **136** (≈7 chunks per observer evicted: wide-spread updates privatise ~360 sections ≈ the floor tier) |
| worstRetentionTier289 (20 000 updates, target tier 2 MiB) | 10 | 289 | shared | **2.59 MB** | 2.52 MB | 4.28 MB | 19.2 MB (+1.72 MB history) | 0 / 0 |

Readings: a unique realistic chunk costs ≈ 21.5 KB charged / 25.9 KB heap; one more observer of already-shared
content costs ≈ 288 B charged / ≈ 350 B heap per chunk (chunk snapshot + sections array + map entry); the per-observer
private delta is hard-bounded by the tier (measured 905 KB and 2.59 MB heap against 768 KiB / 2 MiB charged tiers —
real heap ≈ 1.05–1.25 × charged). The **hybrid** model is confirmed: shared baseline charged once, observer delta per
session. Comparison with WORLD_MODEL.md W2's arithmetic (41 MiB / 102 MiB per observer for full materialization at
distance 10/16 at 4 KiB/section): measured unique reconstruction is 9.95 MB / 23.0 MB — ~4× smaller thanks to
single-value air sections and 4-bit palettes — and shared reconstruction is 1.12 MB / 4.87 MB per observer.

## 60. Player-count scaling estimates (descriptive; NOT supported player counts)

Derived from the measured constants above (heap): unique chunk 25.9 KB; shared-content marginal 0.35 KB per chunk per
observer; private delta ≤ 768 KiB (floor) or 2 MiB (target) per observer, only when updates/history fill it.
**These are SCALING ESTIMATES for the observer-world component only — not supported player counts** (they exclude
Phase 1 session reservations, timelines, replay partitions and the server's own heap; PERFORMANCE.md's admission
equation governs capacity).

| Players | 121 chunks, all co-located (shared) | 289 chunks, all co-located | 441 chunks, all co-located | 289 chunks, all spread out (unique) | Worst private delta (floor / target) |
|---|---|---|---|---|---|
| 1 | 3.2 MB | 7.6 MB | 11.6 MB | 6.5 MB | 0.75 / 2 MB |
| 10 | 3.6 MB | 8.5 MB | 13.0 MB | 65 MB | 7.5 / 20 MB |
| 50 | 5.3 MB | 12.5 MB | 19.1 MB | **shared partition full at ≈15 players** (≈4 700 unique chunks); further baselines CONTENT_NOT_RETAINED | 37.5 / 100 MB |
| 100 | 7.4 MB | 17.6 MB | 26.8 MB | same cap | 75 / 200 MB |
| 250 | 13.8 MB | 32.7 MB | 49.8 MB | same cap | 188 / 500 MB |

Model classification: **hybrid — shared baseline (content-addressed, charged once globally, capped at 96 MiB) +
per-observer private delta (bounded by tier)**. Real servers sit between the two columns: players in the same area
share content; the shared partition bounds total *distinct* chunk content server-wide (≈4 700 realistic chunks),
not the player count. Raising `SHARED_PARTITION_BYTES` is the documented lever (PERFORMANCE.md: "increase G
explicitly for greater shared-world diversity") and is a reviewer/operator decision, not something Phase 3 changes.

## 62. Hot-path allocation review

Single update: one `SectionSnapshot` (palette clone + packed clone + mask clone), one `ChunkSnapshot` (sections
array clone), one `SectionRevision` history record, one `BlockPos`/`BlockChanged` from the mapper — no boxing, no
streams, no per-block objects, no string keys per block (`BlockStateKey` is interned once per state per run).
Multi-block: the same plus one `int[]` for pre-validated ids — **after the JMH finding**: the first `-prof gc` run showed 33.9 KB/op for a 16-cell update because each cell copied the section (a 4096-cell packet would have allocated ~8 MiB); `SectionSnapshot.withAll` now copies once per batch (equivalence proven by `batchedUpdateEqualsSequentialUpdatesFromEveryStartingShape`). Baseline ingest: one `int[]` palette + one
`SectionSnapshot` per section, `ArrayList` of acquired sections, one `ChunkSnapshot`. Current lookup: zero
allocation except the `BlockLookup` record. JMH `-prof gc` numbers in §58 confirm the allocation per op.

## 63. Metrics

Bounded, fixed-shape, no per-player/per-chunk labels: `WorldBudget.Snapshot` (§28) surfaced by
`PacketEngine.worldBudgetSnapshot()` and the `/papea2ac status` `world …` line; per-session `WorldStatus` for the
diagnostic only. Added by the real-client pass: engine metrics `BULK_SHED` and `WORK_QUOTA_DEFERRED`; per-session
`blockUpdates`, `transportBreaks`/last break, and the last 8 block updates (rendered at publish time).

## 64. World diagnostic command — IMPLEMENTED

`/papea2ac world <player>`: session epoch, world epoch, dimension + Y range, revision, last sequence, chunk
count, complete/partial/contentNotRetained/transportIncomplete, evicted/unloaded, private/history bytes, history
entries/evictions, coverage status (HEALTHY / TRANSPORT_BROKEN:reason). Summary only — never a chunk or block dump.
Via core's primitive `WorldStatus` (platform-paper has no `world` dependency).

## 65. Paper smoke (Phase-3-aware, final production code)

Run after the last production code change (`SectionSnapshot.withAll` + `ObserverWorld.observeSequence`), with the
rebuilt `pluginJar` (contains `me/nicolas/papea2ac/world/*`, `WorldStatus`, `WorldCapsule`) installed as
`.phase1-runtime/plugins/Papea2AC.jar`; pinned Paper 26.2 build 124, Temurin Java 25.0.4.1 (`.phase1-tools`),
console commands from `smoke-commands-phase3.txt` paced 2 s apart starting 5 s after `Done`; log
`.phase1-runtime/smoke-run-phase3.log` (git-ignored runtime, like the Phase 1/2 logs).

| Check (task §65) | Result |
|---|---|
| server starts | `Done (8.490s)` |
| plugin enables | `Enabling Papea2AC v1.0-SNAPSHOT`, only the expected `DEVELOPMENT OBSERVE_ONLY` warning |
| Phase 1 early transport healthy | 0 × "Early transport capture unavailable"; status shows the Phase 1 counters at their zero-session baselines |
| Phase 2 timeline initializes | `papea2ac timeline Steve` → `Player is not online.` (command path live, no crash) |
| Phase 3 world service initializes | status line `world observers=0 chunks=0 complete=0 partial=0 sections=0 sharedBytes=0/100663296 sharedSections=0 sharedHits=0 privateTier=786432 revisions=0 historyEvictions=0 evicted=0 unloaded=0 coverageBreaks=0 budgetRejections=0` (engine-wide `WorldBudget` live) |
| existing commands work | `status`, `player`, `timeline`, `capture off/on`, `effects suspend/resume`, `recording … on`, `checkpoint` all answered as in Phase 2 |
| world diagnostic command | `papea2ac world Steve` → `Player is not online.`; `papea2ac world` (wrong arity) and `papea2ac bogus` → usage line now listing `world <name>` |
| plugin disables | `Disabling Papea2AC v1.0-SNAPSHOT` |
| server stops cleanly | chunk systems halted, exit code 0 |
| exceptions | **0** (`grep -c "Exception\|ERROR"` = 0) |
| client | **none connected — no client claim is made** |

A first attempt that piped all console lines at once (no pacing) made Paper's own command dispatcher throw
`NullPointerException … CommandSourceStack.getLevel() is null` for every command issued in the same tick as `Done`,
including vanilla `stop`; that is a harness timing artifact in Paper's console path (no Papea2AC frame in the stack),
not a plugin fault, and the run was discarded and repeated with pacing — recorded here so the log history is honest.

## 66. Real client world qualification

See the dedicated section below (prepared environment, exact sequence, status).

## 67–71. Containment and regressions

No collision, physics, shape, AABB, velocity or check code exists in `anticheat-world` (architecture test forbids
the tokens and transcendental `Math`). Phase 2 remains sound: all 215 Phase 2 tests present and passing; the only
Phase 2 test edits are constructor-shape adaptations for `Respawn` (two new int fields); the only Phase 1 test edits are the same `Respawn`/`JoinGame` shape adaptations in two native tests and `ArchitectureTest` (`world` removed from the "no future engine" list because it is now an implemented module, replaced by the stricter `worldModuleStaysWithinThePhase3Boundary`). Phase 1/2 baselines are untouched in history. Phase 1 remains sound:
`verifyMandatoryCoverage` 127/127, `nativeTest` 113/113 (105 Phase 1 + 8 new), the pinned conformance capsule
still replays to its expected digests (`ReplayTest.committedConformanceCapsuleMatchesExpectedCheckpoints`).

**P3-C1** — resolved by the final qualification pass; see the dedicated section below.

## 72. Test-count reporting — §0.

## P3-C1 — RESOLVED: SCHEMA BUMPED TO 2 (payload schema byte and TransportCapsule container schema)

**Serialized contract analysis (wire facts, not repository history).** A capsule is two layers: the container
(`CapsuleCodec`, magic + `schema` int + header + length-prefixed, checksummed frames) and, inside `Input` frames,
opaque payload blobs (`PayloadCodec`: leading payload-schema byte, then a tag and a tag-specific body; the decoder
is strict — trailing bytes and EOF are errors). Phase 3 had extended three bodies in place under payload-schema 1:

| Question | Answer (verified by `LegacyCodecCompatibilityTest`, which compiles the real Phase 2 baseline codec from git at `f24824f9` and runs it in an isolated class loader) |
|---|---|
| A. Can a Phase 1/2 schema-1 decoder decode a newly encoded Phase 3 capsule? | **No.** Tags 59/74/79 carry 8 (59, 74) or more (79) extra bytes → the old decoder throws `trailing payload bytes`; oversize (>64 KiB) payloads fail its limit. Other tags decoded — an old decoder would silently read a partially-different format. |
| B. Can the Phase 3 decoder (before this fix) decode every valid older schema-1 capsule? | **No.** Old 59/74/79 bodies end where the new decoder expects more fields → `EOFException`. |
| C. Did any existing tag change byte layout or semantics? | **Yes**: 59 JoinGame (+2 ints), 74 Respawn (+2 ints), 79 ChunkBaseline (+bool, +UTF, +count, +sections); plus `PayloadCodec.LIMIT` 64 → 128 KiB. |
| D. Were only optional/self-delimiting fields appended compatibly? | **No** — fixed fields with no presence flag; both decoders are strict about length. |
| E. Can an older decoder safely skip the additions? | **No** (no length prefix per body inside the payload; it cannot know where the additions end). |
| F. One stable schema-1 wire format? | **No — two incompatible formats were both labelled 1.** That is exactly the situation the decision rule forbids. |
| G. Are runtime/user-generated Phase 1/2 capsule files possible? | **Yes**: `CaptureWriter` writes promoted recordings (`/papea2ac recording … on`, `checkpoint`) to disk on any Phase 1/2 build; the Phase 2 smoke exercised the command path. "No committed capsule depends on it" is irrelevant to the contract. |

**Decision: bump.** `PayloadCodec.SCHEMA = 2` (written), `LEGACY_SCHEMA = 1` (decoded);
`TransportCapsule.CURRENT_SCHEMA = 2` (written by `LiveCapture`/`CaptureWriter`), `LEGACY_SCHEMA = 1` (decoded);
anything else fails clearly at both layers (`unsupported payload schema N`, `unsupported capsule schema N`,
`TransportCapsule` constructor rejects schema ∉ {1,2}). Schema-2 layouts are the Phase 3 layouts; schema-1 bodies
of 59/74/79 decode through their legacy layout: `JoinGame`/`Respawn` get the explicit **NOT-OBSERVED range sentinel
(0,0)** (`hasDimensionRange() == false`; the world model then has no dimension profile and answers
`DIMENSION_UNKNOWN` — it never guesses 0..255 or -64..320; a legacy `Respawn` is still an epoch discontinuity),
`ChunkBaseline` decodes to its summary-only form (`contentRetained=false`, note `NOT_DECODED`). Schema 2 also
raised the capsule payload slot to `Payload.MAX_BYTES = 128 KiB = PayloadCodec.LIMIT` (asserted equal) so every
payload the codec can produce fits a frame — `aRealisticMaximalChunkBaselineFitsACapsulePayload` proves a 24-section,
16-entry, 5-property palette baseline (above the normalizer's inline cap) exports without `EXPORT_ENCODE_FAILURE`.

**Hash preservation.** `PayloadDigest` no longer feeds the transport schema byte into the digest (it feeds the constant
digest-form byte 1, then the semantic body): a payload whose layout did not change digests to exactly the value a
Phase 2 build computed (`digestsOfLayoutUnchangedPayloadsArePreservedAcrossTheSchemaBump`, checked against the real
Phase 2 `PayloadDigest`), the pinned Phase 1 conformance capsule (schema 1) replays to its pinned digests under the new
decoder AND after re-sealing as a schema-2 container (`theMigrationPreservesThePinnedPhase1ConformanceDigests`), and
recorded `originalDigest` values are carried verbatim. Only the three tags whose *content* grew digest differently —
they are different observations. Recomputed digests of legacy 59/74/79 payloads (sentinel form) therefore differ from
the original run's; this is inherent to any migration of data that lacked the field and is documented, not hidden.

**Tests (10 new, all in `test`).** `LegacyCodecCompatibilityTest` (4; requires `git` + a JDK compiler, both part of
the project toolchain): OLD FORMAT → NEW DECODER with bytes from the *real* Phase 2 encoder (payloads and a whole
schema-1 container), NEW FORMAT → OLD DECODER (the real Phase 2 decoder rejects schema-2 payloads at the schema byte
with `payload schema` and schema-2 containers with `capsule schema` — never a partial misread), digest preservation,
and proof that the git-free fixtures are the genuine old bytes. `CapsuleSchemaCompatibilityTest` (6; no git): pinned
schema-1 hex fixtures decode, the encoder writes 2 at both layers and the limits agree, legacy JoinGame/Respawn → world
`DIMENSION_UNKNOWN` / epoch discontinuity, future schema 3 (container, constructor, payload byte) fails clearly, the
pinned Phase 1 conformance replays identically as schema 1 and re-sealed as schema 2, maximal chunk baseline fits.
`ReplayTest.payloadBytesAreDefensive…` now asserts the schema byte symbolically; every test that builds a *current*
capsule uses `TransportCapsule.CURRENT_SCHEMA`.

**Migration notes for operators/reviewers.** No file conversion is needed: schema-1 files stay readable forever (the
legacy path is a permanent, tested part of the decoder, per REPLAY_SYSTEM.md "migrations are explicit and preserve
original hashes"); new recordings are schema 2; a Phase 1/2 build cannot read them and says so. World reconstruction
from a legacy recording is DIMENSION_UNKNOWN by construction (no range was recorded) — expected and honest.

Phase 1 kernels were re-run after the digest change (JMH, same config, quiet host; the frozen
`docs/phase1-artifacts/benchmarks.json` was **not** overwritten — restored byte-identical, sha `a595fb78…`):
normalize 14.9, enqueueDequeue 37.9, journal 35.6, sequence 53.0, correlate 1 117 ns/op (alloc unchanged at 2 048 B/op).
All five kernels — including `journal`/`sequence`, which no Phase 2/3 change touches — sit 7–20 % above the Phase 1
session's numbers, and all five kernels use `Movement` payloads (the untouched non-semantic digest branch), so the
shift is host drift between sessions, not the P3-C1 change.

## Real-client world qualification — PERFORMED 2026-09-19: PASS (after seven defects found and fixed)

**Session facts.** Client: Minecraft Java 26.2 (owner's launcher account, in-game name `Emojiado`, offline-mode UUID
`d15aa978-aee9-3e55-a98a-cfca4a1c40f9`); server: Paper 26.2 build 124 on Temurin 25.0.4.1, `127.0.0.1:25576`,
view distance 6 (up to 213 chunks), creative flight used for movement stress; every diagnostic was taken over RCON with
`.phase1-runtime/qual.js` and is reproduced verbatim in `docs/phase3-artifacts/real-client-evidence.log` (91 timestamped
samples across 14 plugin builds; the server log `.phase1-runtime/qualification-run*.log` is git-ignored). The owner
performed only legitimate actions: joining, flying/walking, placing and breaking three dirt blocks, disconnecting and
reconnecting; teleports and the dimension change were issued from RCON. No cheat detection exists or was tested.

**Method.** The failure rule was applied literally: every time a scenario revealed a correctness issue the session
was interrupted, the defect fixed with a GUI-free regression test, the complete local verification re-run, the plugin
jar rebuilt, the server restarted, and the affected scenario repeated. Seven fixes were needed before the sequence
ran through clean; all are in the current tree, none touches a Phase 1/2 baseline commit (deviation protocol, as
P2-F1), and each one below names the real-client evidence that exposed it.

### Defects found by the real client (all fixed, all regression-tested)

| Id | Where | What the client showed | Root cause | Fix / regression test |
|---|---|---|---|---|
| **P3-F3** | core `PacketEngine`, `WorldObservationMapper`, world `ObserverWorld` | first join: `coverage=TRANSPORT_BROKEN:EXPIRED`, `complete=0 transportIncomplete=189`, `revision` inflating ~20/s | every unhealthy Phase 1 latch reason was treated as a world coverage break; `EXPIRED` (no certified outcome within 0.5 s — the *normal* state while correlation is UNSUPPORTED) is an outcome-correlation fact, not a clientbound loss; Phase 1 re-degrades every tick | `coverageBreakReason`: only loss-class reasons break the world (`OVERLOADED`, `LOST_SERVER_OBSERVATION`, `MALFORMED`, `SHARD_FAILED`, `CLOSED`, `WORK_QUOTA`, `INTERNAL_FAILURE`); breaks are idempotent. Because the latch keeps only its FIRST reason, the world now detects dropped captures itself through session-sequence gaps (`SEQUENCE_GAP:<n>`; the ingress consumes a sequence number for a dropped capture — proven against the real `ConnectionIngress`). Tests: `outcomeExpiryDegradesTheSessionButNeverBreaksWorldCoverage`, `aDroppedCaptureLeavesASequenceGapThatBreaksWorldCoverage`, `repeatedCoverageBreaksAreIdempotent`, `coverageBreakReasonClassifiesOnlyClientboundRelevantReasons` |
| **P3-F4** | packet `ConnectionIngress`, protocol `PacketPayload`/`ChunkBaseline` | `INGRESS_DROPPED=8` on join | chunk section content (5–50 KiB per baseline) was charged to the 192 KiB ingress record arena | PERFORMANCE.md bulk pool: `bulkBytes()` charged to a 4 MiB/session exposure ceiling; at the ceiling the content is *shed* (`CONTENT_NOT_RETAINED:BULK_EXPOSURE_CEILING`), never the record. Tests: `chunkContentIsChargedToTheBulkPoolAndShedNeverDroppedAtTheCeiling`, `aJoinSizedChunkBurstThroughTheRealEngineLosesNothing` |
| **P3-F5** | core `PacketEngine` shard loop, packet `ConnectionIngress` | `INGRESS_DROPPED=974` on join, then 18 | the shard parked 100 µs after *every* pass (≥ 1 ms on Windows) — ~32 observations/ms against a join flush of 2 000–3 000 packets in one tick — and nothing woke an idle shard when the burst started | park only on an idle pass; `onEnqueue` wake (shard unpark) on the queue's empty→non-empty transition. Tests: `aJoinSizedPacketFloodIsDrainedWithoutIngressDrops` (reproduced the drop at observation 952 before the fix), `theFirstObservationWakesAnIdleShardPromptly` |
| **P3-F6** | core `PacketEngine` shard loop | `SEQUENCE_GAP` with `INGRESS_DROPPED=0` while flying | the work-quota branch *silently dropped* an observation whose cost exceeded the 2048-unit slice (a near-maximal entity `Bundle`) or found the bucket empty (`deferred=null`) | the slice is fairness, not a size limit: an oversize observation runs alone; quota exhaustion defers (backpressure) with a bounded metric `WORK_QUOTA_DEFERRED`. Test: `anOversizeBundleIsProcessedNotSilentlyDropped` |
| **P3-F7** | packet `ConnectionIngress`, core session | `INGRESS_DROPPED` 21→30 in fast flight with p99 capture-to-process delay ≤ 2 ms (consumer not slow) | the Phase 1 floor arena (192 KiB ≈ 245 entity observations) cannot absorb one real tick's flush (300–600 entity packets when mob-dense chunks load; ~3 000 at join) | arena 4 MiB / 4 096 records per session, composed into the engine-wide 32 MiB infrastructure pool with a guaranteed 192 KiB floor slice per session (see FINAL PRE-BASELINE DECISIONS: CONFORMANT — floor exceeded). Phase 1 test `oneFloodedSessionCannotStarveControlProcessingForItself` adapted (12 000-record flood still saturates) |
| **P3-F8** | world `ObserverWorld` | after a gap the world stayed `TRANSPORT_BROKEN` forever although the server re-sent every chunk | the break was a sticky *session* state (mirroring the latch); a full chunk baseline received after a gap is a fresh authoritative view of that chunk — refusing it is not "no accidental healing", it is refusing the qualifying observation | per-chunk transport coverage: a break marks the chunks held *at that moment* `TRANSPORT_INCOMPLETE` until each is re-baselined; later baselines are COMPLETE; updates never heal or break; `transportBroken` is now "breaks occurred" (count + last reason/sequence shown as `BREAKS=n last=<reason>@<seq>`). Fixture `captureCoverageBreak` regenerated as a reviewed event (file digest `9c6d5451…` → `d4095e08…`). Tests updated: `transportCoverageBreakMarksEveryChunkUntrustworthyUntilANewBaseline`, gap/replay tests |
| **P3-F9** | protocol `SemanticPayload.BlockState/Property` charges | 664 drops / 48 breaks in max-speed flight after F7 | block states were charged at the 256-char worst case (640 + 1 152 B *per property*): one multi-block update of a few dozen stair/fence states weighed ~344 KiB in the arena and the work quota | charges now reflect the retained strings (4-property stairs ≈ 540 B, a 64-cell update ≈ 43 KiB). Test: `multiBlockUpdatesWithPropertyRichStatesAreChargedRealisticallyAndNeverDropped`. After this fix: 30 s of max-speed flight (5 023 unloads, ≈1 700 obs/s) → **0 drops, 0 breaks, 0 quota deferrals**, p99 delay ≤ 1 ms |
| **P3-F10** | core `SessionRecording` | every session recording exported empty: `EXPORT_ENCODE_FAILURE:Attempt/Handshake:input time/identity` | the recording's time origin is taken at `connect()`, but a real session starts with the pre-login journal captured seconds earlier by the early-transport hook → negative `capturedNanos` at frame 1 | origin = earliest referenced capture time (fixed at the first frame). Test: `observationsCapturedBeforeTheSessionExistedStillRecord` |
| **P3-F11** | platform `PaperCapture` | the world (and Phase 1's latch) saw an "uncovered clientbound packet" at every protocol switch: `net.minecraft.network.UnconfiguredPipelineHandler$OutboundConfigurationTask` | the pipeline carries non-packet control objects through the same handlers; they were normalized as `Uncovered(UNKNOWN)` observations, degrading every real session at login and ending its recording at frame 2 | only `net.minecraft.network.protocol.*` objects are observations; control objects pass through un-numbered. Native tests: `aProtocolSwitchTaskWrittenThroughTheHookedPipelineIsNeitherCapturedNorADegrade`; Phase 1 `NativeFaultTest.unexpectedSemanticDegrades…` adapted to a real uncovered packet (chat_command) |
| **P3-F12** | core `PacketEngine`/`WorldObservationMapper`, platform `PaperCapture` | reconnect: `BREAKS=1 last=PIPELINE_UNCOVERED@6` | `PIPELINE_UNCOVERED` is raised by uncovered inbound/outbound *packets* (the client's `minecraft:brand` custom payload at play start) — irrelevant to the world; the one world-relevant cause (our capture handler removed/displaced) was indistinguishable | `PIPELINE_UNCOVERED` no longer breaks the world; `PaperCapture` reports the capture-position cases through `Session.captureCompromised(reason)`, which the shard turns into an explicit `CAPTURE_COMPROMISED:<reason>` break on the next ordered observation. Tests: updated `unknownPlayerHasNoWorldStatusAndTransportDegradeBreaksCoverage`, native `removingTheRealCaptureHandlerBreaksWorldCoverageExplicitly` |
| **P3-F13** | platform `Papea2AC.onDisable`, core shard closing branch | every server stop logged `Exception in thread "Papea2AC-shard-0" … zip file closed` | `engine.close()` returned before the shard drained; Bukkit closed the plugin jar; the session's final export then failed *before* `actor.close()/world.close()` ran | robust closing branch (release always runs) + bounded `awaitTermination(2 s)` in `onDisable`. Test: `awaitTerminationDrainsClosingSessionsAndReleasesTheirWorlds`; live: server stopped with the player connected — clean disable, no exception |

Also added for evidence quality (bounded, summary-only): `/papea2ac world` now prints `blockUpdates=<n>` and the last
8 observed block updates (`state @ x,y,z seq …`), rendered at publish time (nothing formatted on the hot path), plus
`inspectCapsule` (`CapsuleInspectMain`, read-only capsule inspection).

### Scenario results (final build; evidence-log labels in brackets)

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | JOIN | **PASS** | [A JOIN (P3-F9/F11/F12 builds), K1] `sessionEpoch=1 worldEpoch=0 dimension=minecraft:overworld y[-64..319]`, 213/213 chunks COMPLETE, `revision=214` (= 1 dimension + 213 baselines exactly), `coverage=HEALTHY`, `INGRESS_DROPPED=0`, `coverageBreaks=0`; SessionKey epoch increments per connection |
| 2 | Normal movement / chunk loading | **PASS** | [B MOVEMENT] a minute of flight incl. max speed: 2 001 unloads, 175/175 complete, HEALTHY, 0 drops, 0 breaks, p99 capture-to-process 262 µs; stress after P3-F9: 5 023 unloads, 213/213, 0 drops, 0 breaks, 0 quota deferrals |
| 3 | Positive chunk boundary 15→16 | **PASS** | [C1–C4] observed position 15.5 → 16.5 (block 15 in chunk 0, block 16 in chunk 1), one chunk column rolled (`unloaded` +15), 213/213 complete, HEALTHY |
| 4 | Negative boundary −1→0 | **PASS** | [D1–D4] observed −0.5 → 0.5: block −1 resolves to chunk −1 by floor division, block 0 to chunk 0; column rolled the other way, no gap or duplication |
| 5 | Block place | **PASS** | [E1 06:57:57] recent updates: `minecraft:dirt @ 1,67,9`, `@ 1,67,8`, `@ 1,67,7` (each echoed twice by the server: placement + sequence-ack resend) beside the player at (−0.6, 67, 8.3); chunk stays COMPLETE (updates never change coverage) |
| 6 | Block break | **PASS** | [F1 06:58:25] `minecraft:air @ 1,67,7 / 1,67,8 / 1,67,9` replaced the dirt at exactly the three positions — a cell becomes air only through a qualifying observation |
| 7 | Chunk unload | **PASS** | [G1–G2] teleport 400 blocks away: `unloaded` 0 → 213 (every spawn chunk explicitly UNLOADED by the server), `evicted=0` — the two provenances stay distinct; new area 213/213 complete |
| 8 | Chunk reload / baseline recovery | **PASS** | [G3–G4] back at spawn: 213/213 COMPLETE again through full baselines (`unloaded=426`), HEALTHY — the only healing path is a qualifying full baseline |
| 9 | Long-distance teleport | **PASS** | [H1–H2] 20 000 blocks: 213/213 complete of the new area, `unloaded=639`, no stale chunk reused, 0 drops/breaks |
| 10 | Dimension/world change and return | **PASS** | [I1–I4] Nether: `worldEpoch` 0→1, `minecraft:the_nether y[0..255]`, 213/213 of the new epoch; return: `worldEpoch=2`, overworld `y[-64..319]`, 213/213 — old coordinates never resolve in a new epoch |
| 11 | Disconnect / reconnect | **PASS** | [J0–J2, final-build J1–J2] after disconnect: `sessions=0`, `reservedBytes` back to 256 MiB, `observers=0 chunks=0 sections=0 sharedBytes=0`; after reconnect with the same UUID: `sessionEpoch` 2 (later 3), fresh `worldEpoch=0` world, own counters from zero |
| 12 | SessionKey / session isolation | **PASS** | same UUID → new epoch every time (an unplanned double-connect at 03:08 gave epochs 1→2 with a clean release in between); no old-session state became current; regression `anOldSessionCannotMutateTheNewSessionsWorld` covers the late-callback case GUI-free |
| 13 | UNKNOWN/PARTIAL semantics | **PASS** | `partial=0` throughout (every chunk arrived as a full baseline before any update); `contentNotRetained=4` observed once at x≈−13 300 (mountain/cave chunks over the 40 KiB inline cap — reported, not hidden); `evicted=36` once under extreme churn at the 768 KiB floor tier (explicit EVICTED, distinct from 2 897 UNLOADED); `transportIncomplete` appeared only with real gaps and recovered per chunk; unknown never became air (the only air transitions observed were the three breaks) |
| 14 | Resource accounting before/during/after | **PASS** | T0 `observers=0 chunks=0 sharedBytes=0 privateTier=786432 sessions=0`; during: 213 chunks ≈ 4.09 MB shared + 61–415 KB private/observer; after cleanup back to T0 exactly (three separate disconnect cycles); `budgetRejections=0` |
| 15 | P3-F1 regression | **PASS** | no negative private/shared value in 91 samples, no double release (shared store returns to exactly 0 on every close), no observer-world leak (`observers` always = online sessions) |
| — | Performance sanity (§7) | **PASS, with the F3–F9 fixes** | no `Can't keep up`, no Netty warnings, no `Papea2AC` exception in the final runs; capture-to-process avg 0.5 ms, p95 ≤ 131 µs, p99 ≤ 1 ms under stress; no `BULK_SHED`, no `budgetRejections`, no unexpected `CONTENT_NOT_RETAINED` under ordinary play (the 4 cases were genuine >40 KiB chunks) |

### Findings recorded, not fixed (outside the world model's scope — reviewer decisions)

- **Phase 1 session health on a real client**: the latch's first reason is `EXPIRED` (or `PIPELINE_UNCOVERED` from the
  client brand payload) within a second of joining, so `certificationAvailable=false` and status
  `EXPIRED_OBSERVE_ONLY`/`PIPELINE_UNCOVERED_OBSERVE_ONLY` for every real session while correlation is UNSUPPORTED.
  Expected under CAPTURE-ONLY, but it means Phase 1's sticky first-reason latch carries no information after the first
  second — the reason the world needed its own loss signals (gap detection, `captureCompromised`).
- **Phase 1 rolling recordings on a real client** now encode (P3-F10/F11) but still stop at frame 2 with `DEGRADED`
  (the first inbound attempt expires): the fail-closed recording semantics make real-session recordings unusable in
  this mode. Reviewer decision on Phase 1 recording semantics; `inspectCapsule` shows the state of any capsule.
- **PERFORMANCE.md tier composition** vs the measured real-client needs (P3-F7): resolved in FINAL PRE-BASELINE DECISIONS (conformant; engine-wide pool added).

### Preparation (kept for reproducibility)

The environment as prepared before the session (the sequence table below was executed as described).

**Environment (prepared 2026-09-19, final code, plugin jar rebuilt after the last production change):**

| Item | Value |
|---|---|
| Server | Paper 26.2 build 124 (pinned), `.phase1-runtime/paper.jar`, Temurin Java 25.0.4.1 (`.phase1-tools`) |
| Address | `127.0.0.1:25576` (local machine only) |
| Client version required | **Minecraft Java Edition 26.2** |
| Mode | `online-mode=false` (a normal launcher account joins; the UUID is the offline-derived one — the SessionKey still identifies the session), `gamemode=survival`, `spawn-protection=0` (blocks can be placed/broken at spawn without op), `view-distance=6` (169 chunks), `simulation-distance=4` |
| Diagnostics path | RCON on `127.0.0.1:25575` (dev password in the git-ignored `server.properties`, never in docs); `node .phase1-runtime/rcon.js "<cmd>"`, evidence recorder `node .phase1-runtime/qual.js "<label>" "<cmd>"…` appends to `.phase1-runtime/qualification-evidence.log` |
| Pre-join baseline (T0) | recorded: `observers=0 chunks=0 complete=0 partial=0 sections=0 sharedBytes=0/100663296 privateTier=786432 revisions=0 evicted=0 unloaded=0 coverageBreaks=0 budgetRejections=0`, `sessions=0` |

**What the owner does:** join `127.0.0.1:25576` with a 26.2 client and tell the implementer the in-game name.
Everything else (op, gamemode, teleports, dimension change, diagnostics, evidence) is driven from RCON.

**Sequence (task §4), with the exact commands the implementer runs at each step and what must be observed:**

| Step | Owner action | Implementer RCON commands | Expected evidence (`/papea2ac world <name>` unless noted) |
|---|---|---|---|
| A JOIN | join | `papea2ac world <name>`, `papea2ac player <name>`, `papea2ac status` | session present (`sessionEpoch` = 1 for a first join), `worldEpoch=0`, dimension `minecraft:overworld y[-64..319]`, `chunks` growing toward ~169 `complete`, `partial=0`, `coverage=HEALTHY`; status `world observers=1`, shared bytes > 0 |
| B MOVEMENT | walk ~100 blocks in one direction | repeat `papea2ac world <name>` | `chunks` stays ≈ view size, `unloaded` grows as chunks leave the view, `revision` increases, `coverage=HEALTHY`, `partial=0` |
| C +BOUNDARY 15→16 | (teleported) | `tp <name> 15.5 <y> 8.5` then `tp <name> 16.5 <y> 8.5`, `papea2ac player <name>` | position x 15.5 → 16.5 is chunk 0 → chunk 1; new column of chunks at +x baselines (chunks/unloaded shift by one column) |
| D −BOUNDARY −1→0 | (teleported) | `tp <name> -0.5 <y> 8.5` then `tp <name> 0.5 <y> 8.5` | block x −1 is chunk −1 (floor division), x 0 is chunk 0; counts shift by one column the other way, no gap/duplication |
| E BLOCK PLACE | place 3 blocks at a known spot | `papea2ac world <name>` before/after | `revision` +1 per placed block (server sends BlockChange), `partial` unchanged (chunk stays COMPLETE), `coverage=HEALTHY` |
| F BLOCK BREAK | break them | same | `revision` +1 per break; state replaced (air) — verified via revision delta and later replay of the same cell being resent |
| G UNLOAD/RELOAD | (teleported ≥ 2 × view distance away and back) | `tp <name> 400 <y> 400`, wait ~10 s, `papea2ac world <name>`, then `tp <name> 8 <y> 8`, wait, `papea2ac world <name>` | after leaving: `unloaded` ≈ previous chunk count (server-sent unloads), `evicted=0` (distinct counter); after returning: `complete` back to ≈ 169 through full baselines, `partial=0` |
| H TELEPORT | (teleported far) | `tp <name> 20000 <y> 20000` | chunk count restarts from the new area; old chunks appear as `unloaded`, never as current |
| I DIMENSION | (teleported) | `execute in minecraft:the_nether run tp <name> 0 70 0` then back with `execute in minecraft:overworld run tp <name> 8 <y> 8` | `worldEpoch` 0 → 1 → 2, dimension `minecraft:the_nether y[0..255]` then overworld again, `chunks` restarts at each epoch (old coordinates never resolve in the new epoch) |
| J DISCONNECT/RECONNECT | disconnect, wait 5 s, reconnect | `papea2ac status` after disconnect; `papea2ac world <name>` after reconnect | after disconnect: `world observers=0 chunks=0 sharedBytes=0` (**P3-F1 check: no negative/leaked accounting**), `sessions=0`; after reconnect: `sessionEpoch` 2, `worldEpoch=0`, empty world refilling — nothing from session 1 |

§5 semantics: every `partial`/`contentNotRetained`/`transportIncomplete`/`evicted`/`unloaded` count is read back
verbatim; ordinary local play at view 6 is expected to show `contentNotRetained=0` and `budgetRejections=0` —
if either is non-zero the cause is recorded, not explained away. §6 accounting: T0 vs post-disconnect must match
(`observers`, `chunks`, `sharedBytes`, `privateBytes` all back to zero). §7 performance sanity: server log watched
for `Can't keep up`, Netty warnings, GC pauses and any `Papea2AC` exception during the session.

## FINAL PRE-BASELINE DECISIONS (2026-09-19)

Three reviewer questions were left open by the real-client qualification. Each is resolved here against the
frozen Phase 0 wording, with code changed only where a real conformance gap existed (P3-F7's missing global bound).
No Phase 0 specification was edited.

### Decision 1 — P3-F7: PERFORMANCE.md conformance of the ingress/bulk composition

**The exact Phase 0 contract (PERFORMANCE.md, "Initial resource budget registry", quoted):**
- "All figures below are design estimates requiring retained-heap verification, not measured minima."
- "Two tiers: CORE_OBSERVATION **floor** = 2 MiB/session; QUALIFICATION target = 4 MiB/session. Floor components are timeline 512 KiB, private world/entity 768 KiB, **ingress/bridge/control 256 KiB**, pending outcome journal 256 KiB, and other components/metadata 256 KiB." … "Until measured implementations fit, these tiers cannot be advertised as supported footprints."
- Table row "Ingress / bridge / control | 1024 /256 /16 records; **combined tier arena 256 KiB floor**, fixed latches | Barrier and explicit gap; no hidden queue".
- Table row "Bulk / scratch / global infrastructure | 2 MiB single payload, **4 MiB/session exposure ceiling; total within 32 MiB infrastructure pool** | Reject capture/coverage before allocation".
- "Reference global partition G = 256 MiB: shared world 96 MiB, shape cache 32 MiB, replay 96 MiB, **other queues/bulk/worker scratch/control 32 MiB**. These are partitions, not extra memory outside B."
- Decision F2: "All live allocations/pins are admitted against a global AC budget B; at least 20% stays unassigned for measured overhead/transients. A session's ceiling is not allocated at login." and "It never silently **lowers** a floor."

**Classification.** The 256 KiB (192 KiB ingress + 64 KiB bridge) arena figure is **B. MINIMUM/FLOOR** — the text
calls it a floor three times, forbids *lowering* it, and labels every figure a design estimate pending measurement.
The bulk figures are an explicit ceiling design (4 MiB/session **within a 32 MiB pool**) that the Phase 3 bulk pool
was already built to. The 1024-record count is a per-session ceiling estimate under the same "design estimates"
caveat; the binding contract for the whole family is the *global* one: everything in flight must sit inside the
32 MiB "other queues/bulk/worker scratch/control" partition of G, so that per-session ceilings compose into B.

**What was still missing before this pass.** The per-session ceilings (4 MiB arena, 4 MiB bulk) were hard bounds,
but nothing composed them: N sessions could hold N × 8 MiB in flight, outside any partition — the one genuine
conformance gap. It is closed by `InfrastructurePool` (`anticheat-packet`), one engine-wide 32 MiB pool: each
session's arena bytes **above its guaranteed 192 KiB floor slice** and **all** bulk content are reserved from the pool
before they are held; the floor slice itself is never pooled (it is the tier reservation's own "ingress 192 KiB").
`/papea2ac status` shows `infrastructurePool=<used>/33554432`.

**Safety analysis of the final composition (per session: 192 KiB guaranteed arena slice, ≤ 4 MiB arena, ≤ 4 MiB bulk;
globally: one 32 MiB pool):**

| Property | How it holds |
|---|---|
| Both per-session figures hard bounded | `BoundedMailbox` byte limit `INGRESS_ARENA_BYTES` (4 MiB) and record limit 4096; `BULK_CEILING_BYTES` (4 MiB) CAS-checked in `reserveBulk`; a single payload can never exceed `PayloadCodec.LIMIT` (128 KiB) |
| Nothing grows with traffic | every admitted observation is charged before it is queued (`reserveArena`/`reserveBulk`); rejection happens before any allocation is retained |
| Reservations/accounting explicit | arena bytes (`arenaBytes`), pooled share (`poolArena`), bulk (`bulkBytes`) per session; `InfrastructurePool.used()` globally; `retainedBytes()` sums queue + bridge + bulk |
| Bulk cannot accumulate across passes | bulk is released in `poll()` for every dequeued observation, in the drop path, and in `releaseAfterClose()` (which now drains through `poll()`); `InfrastructurePoolTest` asserts the pool returns to exactly 0 after poll and after close |
| Release paths complete | success → poll; offer failure → `releaseArena` + `releaseBulk`; pool failure → bulk released, record dropped as accounted `INGRESS_FULL`; close → `releaseAfterClose` drains via `poll()`; `InfrastructurePool.release` throws on over-release (never silently negative) |
| Arithmetic cannot overflow | per-session counters are `int` bounded by 4 MiB + 4 MiB; pool arithmetic is `long` with limit ≤ 2^40 and CAS admission (`current > limit − bytes`) — no wrap |
| Overload explicit | shared-pool or arena exhaustion → `OVERLOADED` latch + `INGRESS_FULL` metric + a session-sequence gap the world reports as `SEQUENCE_GAP`; bulk exhaustion → `BULK_SHED` metric + `CONTENT_NOT_RETAINED:BULK_EXPOSURE_CEILING` |
| Netty never blocks | `capture()` is non-blocking: CAS/short uncontended monitor for arithmetic only (measured: `sequence` kernel 53 → 69 ns/op), no waits, no retries |
| One player cannot cause unbounded global allocation | per-session ceilings (8 MiB) AND the shared 32 MiB pool; a flooding session that exhausts the pool sheds/drops **its own** overflow, while every other session keeps its 192 KiB guaranteed slice (`sessionsShareOneBoundAndExhaustionIsExplicitNeverBlocking`) |
| Composition with the other budgets | shared world 96 MiB (`WorldBudget`, unchanged), replay 96 MiB (`ReplayQuota`, unchanged), per-session tiers (`SESSION_BYTES` 2 MiB × sessions: timeline 512 KiB + private world 768 KiB + ingress/bridge 256 KiB + journal 256 KiB + other 256 KiB — the arena floor slice is inside it), infrastructure pool 32 MiB = G's fourth partition |

**Worst-case aggregate exposure (in-flight ingress + bulk, all sessions):** `sessions × 192 KiB` (each inside its own
2 MiB tier reservation) **+ 32 MiB** (pool), i.e. for the engine's maximum 1 024 sessions: 192 MiB inside reservations
already admitted against B + 32 MiB partition = **224 MiB**, versus the unbounded `sessions × 8 MiB = 8 GiB` the
per-session ceilings alone allowed. With the default B = 1 GiB and 20 % headroom the previous accounting stands; no
admission formula changes.

**Measured real-traffic requirement (why the floor was exceeded):** a real join flushes ≈ 3 000 packets in one tick
(≈ 2.4 MB charged), a tick loading mob-dense chunks 300–600 (≈ 0.5 MB); the 192 KiB floor dropped 8–974 observations
per join and 21–664 during flight (`real-client-evidence.log`); the final composition ran a 5 023-unload flight with
0 drops. Reverting to the floor alone would reintroduce packet loss on every join.

**P3-F7 SPEC DECISION: CONFORMANT — FLOOR EXCEEDED FOR REAL-TRAFFIC SAFETY.** No Phase 0 specification modified; no
human exception required. (Retained for the reviewer: the 1024-record estimate is now 4096 per session, still inside
the same partitions.)

### Decision 2 — Phase 1 recordings ending `DEGRADED` at frame 2

**Exactly why.** `PacketEngine.Shard.run` calls `recording.incomplete()` on the first health-generation bump
(`LiveCapture.incomplete("DEGRADED")` — the capture then stops appending and exports with `complete=false`). On a real
client the first bump is `EXPIRED`: the provisional-outcome journal's 500 ms timeout on the first inbound attempt
(`TruthActor.tick` → `ProvisionalJournal.expired`). Under CAPTURE-ONLY transport there is no outcome source at all, so
no attempt can ever be certified and the timeout is guaranteed; the handshake captured by the early-transport hook is
already older than 500 ms when the session binds, so the barrier lands at frame 2.

| Question | Answer |
|---|---|
| A. Expected because authoritative correlation is unsupported? | **Yes.** `EXPIRED` is the designed consequence of UNSUPPORTED correlation (PERFORMANCE.md "Provisional outcome journal … 500 ms → UNCERTAIN_PIPELINE_OUTCOME, no later dependent promotion"). |
| B. Does the recording intentionally stop the certified stream once correlation is unavailable? | **Yes.** REPLAY_SYSTEM.md: the capsule footer carries "covered sequence range, complete/incomplete flag, **terminal reason**", and "the default action policy requires a **complete** capsule through the decision point". THREADING_MODEL.md: after a barrier the sequencer "stops accepting validation continuity for that generation … evidence from an older generation may be retained diagnostically but cannot admit new actions". The capture terminates at the barrier with the reason, claiming nothing beyond it. |
| C. Are observed packets still available elsewhere? | **Yes:** the live observer world (Phase 3), the player timeline (Phase 2), `TruthActor.history()` (bounded ring), the bounded diagnostics (`/papea2ac world/timeline/player/status`), and — for diagnostics — the operator re-arm path below. Raw packet bytes are by design "optional adapter-debug attachments" and are not retained. |
| D. Matches REPLAY_SYSTEM.md / PACKET_PIPELINE.md? | **Yes** (quotes above; PACKET_PIPELINE.md: "Queue/bulk exhaustion latches a transport health barrier before any later decision can be actionable"). |
| E. Prevents Phase 3 replay/world diagnostics? | **No.** The world model, its `WorldCapsule` projection and its diagnostics do not depend on the transport recording; `WorldCapsule` co-recording into capture files is a documented future item. |
| F. Safe fail-closed or accidental over-degradation? | **Safe fail-closed**, with one property proven this pass: because the latch keeps its first reason and never bumps its generation again, an operator who disables and re-enables recording **after** the barrier gets a checkpoint-seeded capsule that records normally (`CheckpointReplayTest.aRecordingReArmedAfterTheFirstBarrierRecordsNormally`: initial capsule `DEGRADED`, re-armed capsule `complete=true`, `recordingEpoch=2`, checkpoint opened in the degraded generation). The two-frame session-start capsule is therefore a correct statement of the certified prefix, not a loss of capability. |

**RECORDING DEGRADED DECISION: EXPECTED SAFE LIMITATION.** Capture-only production + unsupported authoritative
correlation means a recording cannot claim a certified durable stream beyond the approved boundary; the boundary on a
real client is the first inbound attempt's 500 ms expiry. Documented; no code change; operator path verified by test.

### Decision 3 — real-client session health never `HEALTHY`

**First reason latched:** `EXPIRED` (observed on every join; `PIPELINE_UNCOVERED` won the race once, from the client's
`minecraft:brand` custom payload — an intentionally-unsupported serverbound packet — when it was processed before the
first journal tick). Trigger: `TruthActor.tick` finds a provisional attempt past its 500 ms deadline; time: within the
first second of every real session (the handshake's capture predates the session). Expected on every real client under
CAPTURE-ONLY: yes, deterministically.

- **Meaning:** "not production-qualified for certification", not "transport unhealthy". `HealthLatch`'s `HEALTHY` is
  defined by Phase 1 as *certified continuity available* (PACKET_PIPELINE.md separates "the client-claimed/provisional
  path" from "the accepted server projection and certified supported frontier"); without an outcome source the
  frontier cannot exist, so `HEALTHY` is architecturally impossible in this mode — explicitly, not accidentally.
- **Effect on later decisions:** `certificationAvailable=false` and status `EXPIRED_OBSERVE_ONLY` (correct: nothing
  is certified); Phase 2 timeline records carry the sticky reason as uncertainty (correct: every position is a
  provisional claim); the session-start recording terminates (Decision 2). **World coverage is unaffected** (P3-F3:
  only loss-class reasons break it) and the world detects real losses itself (`SEQUENCE_GAP`, `CAPTURE_COMPROMISED`),
  which is the Phase 3 answer to the first-reason latch carrying no further information.
- **Recoverable dimension:** none within the Phase 1 model — the latch "cannot heal" by design (THREADING_MODEL.md:
  one barrier per generation, no continuity afterwards). The world's per-chunk recovery (P3-F8) is the recoverable
  dimension that exists, and it lives in the world model, not in the latch.
- **Conflation:** yes, the single first-reason slot carries both a permanent qualification fact (`EXPIRED` in this
  mode) and operational transport loss (`OVERLOADED`, `LOST_SERVER_OBSERVATION`, …). It does not affect correctness
  today (nothing is actionable under CAPTURE-ONLY, and Phase 3 does not read the latch for coverage), and the status
  string already names the reason and the mode. Splitting the certification frontier from operational transport
  health is recorded as a Phase 1 forward refinement for the phase that introduces supported correlation; changing
  it now would weaken nothing but would also prove nothing.

**SESSION HEALTH DECISION: EXPECTED SAFE STATUS** (`HEALTHY` = certified continuity, unattainable by definition in
CAPTURE-ONLY/UNSUPPORTED mode; the label does not poison world coverage). Documented; no code change.

### The eleven live findings — individual records

Common to all eleven: **HISTORICAL BASELINE MODIFIED: NO** (`35c44f9b…` Phase 1 and `769100fe…` its receipt, and the
Phase 2 baseline `f24824f9…`, are untouched); **FORWARD CODE FIXED: YES** (the fixes live in the working tree that becomes
the Phase 3 baseline); **REAL-CLIENT REQUALIFICATION:** the affected scenario was repeated on the rebuilt jar and the
full 15-scenario sequence passed on the final build (`real-client-evidence.log`).

| Id | Original phase / component | Live failure | Root cause | Fix | Regression test | Requalified by |
|---|---|---|---|---|---|---|
| P3-F3 | Phase 3 core wiring (`PacketEngine`, `WorldObservationMapper`) + world | `TRANSPORT_BROKEN:EXPIRED`, `complete=0`, revision +20/s at first join | every latch reason treated as a world break; per-tick re-degrade; first-reason latch hides later loss | loss-class reasons only; idempotent breaks; own gap detection | `outcomeExpiryDegradesTheSessionButNeverBreaksWorldCoverage`, `aDroppedCaptureLeavesASequenceGap…`, `repeatedCoverageBreaksAreIdempotent` | JOIN, movement |
| P3-F4 | Phase 1 `ConnectionIngress` / Phase 3 `ChunkBaseline` charge | `INGRESS_DROPPED=8` at join | chunk content charged to the 192 KiB record arena | bulk pool, shed never dropped | `chunkContentIsChargedToTheBulkPool…`, `aJoinSizedChunkBurst…` | JOIN |
| P3-F5 | Phase 1 shard loop / ingress | `INGRESS_DROPPED=974`, then 18 | unconditional park ≥ 1 ms/pass; no wake on enqueue | park only when idle; `onEnqueue` wake | `aJoinSizedPacketFloodIsDrainedWithoutIngressDrops`, `theFirstObservationWakesAnIdleShardPromptly` | JOIN |
| P3-F6 | Phase 1 shard loop | `SEQUENCE_GAP` with 0 ingress drops | oversize/over-quota observation silently dropped | oversize runs alone; quota defers (`WORK_QUOTA_DEFERRED`) | `anOversizeBundleIsProcessedNotSilentlyDropped` | movement |
| P3-F7 | Phase 1 ingress sizing | drops in fast flight, p99 delay ≤ 2 ms | floor arena < one tick's flush | 4 MiB arena / 4096 records + **engine-wide 32 MiB pool** (this pass) | `InfrastructurePoolTest` (3), adapted `oneFloodedSessionCannotStarve…` | stress flight (5 023 unloads, 0 drops) |
| P3-F8 | Phase 3 world | world never recovered after a gap | sticky session-wide break | per-chunk coverage; post-break baselines COMPLETE | `transportCoverageBreakMarksEveryChunkUntrustworthyUntilANewBaseline` (+ fixture regen) | reload, stress |
| P3-F9 | Phase 1 `SemanticPayload` charges | 664 drops / 48 breaks in flight | 640 + 1 152 B/property worst-case charge | charges from actual strings | `multiBlockUpdatesWithPropertyRichStatesAreCharged…` | stress flight |
| P3-F10 | Phase 1 `SessionRecording` | every recording `EXPORT_ENCODE_FAILURE` at frame 1 | origin after pre-login journal times | origin = earliest referenced capture | `observationsCapturedBeforeTheSessionExistedStillRecord` | recording checkpoint |
| P3-F11 | Phase 1 `PaperCapture` | `PIPELINE_UNCOVERED` at every protocol switch | pipeline control objects captured as packets | only `net.minecraft.network.protocol.*` are observations | native `aProtocolSwitchTaskWrittenThroughTheHookedPipeline…` | JOIN |
| P3-F12 | Phase 3 wiring + Phase 1 `PaperCapture` | `BREAKS=1 PIPELINE_UNCOVERED@6` on reconnect | uncovered packets vs compromised capture indistinguishable | `PIPELINE_UNCOVERED` not a world break; `captureCompromised` → `CAPTURE_COMPROMISED:<reason>` | native `removingTheRealCaptureHandlerBreaksWorldCoverageExplicitly` | reconnect |
| P3-F13 | Phase 1 `Papea2AC.onDisable` / shard close | `zip file closed` at every stop; release skipped | disable returned before drain | robust closing branch + `awaitTermination(2 s)` | `awaitTerminationDrainsClosingSessionsAndReleasesTheirWorlds` | stop with player connected |

**PHASE 1 HISTORICAL BASELINE: UNCHANGED. PHASE 1 FORWARD COMPATIBILITY FIXES DISCOVERED DURING PHASE 3:**
P3-F4 (ingress charge), P3-F5, P3-F6, P3-F7, P3-F9, P3-F10, P3-F11, P3-F12 (platform part), P3-F13 — plus P2-F1 from
Phase 2. P3-F3, P3-F8 and the world half of P3-F12 are Phase 3 code.

### Evidence log check

`docs/phase3-artifacts/real-client-evidence.log` was scanned for `password`, `token`, `credential`, `secret`, `rcon`
before being kept: no matches. It contains the local address `127.0.0.1:25575/25576`, the in-game name and the
offline-mode UUID of the owner's own test account (the owner performed the session and chose to keep them).

### Verification after this pass (production code changed: `InfrastructurePool`, `ConnectionIngress`, `PacketEngine`, `Papea2AC` status line)

`clean test --rerun` **353/0/0**; `nativeTest --rerun` **115/0/0**; `verifyMandatoryCoverage` 127/127;
`replayConformance` 4/4 pinned digests; `pluginJar` built and smoke-run (start `Done (7.157s)`, status shows
`infrastructurePool=0/33554432`, all commands answer, `stop` → clean `Disabling`, no exception);
`worldFixtures` ×2 byte-identical (`d4095e08…`); `observerWorldMemoryHarness` re-run (unchanged within noise);
Phase 1 JMH re-run on the touched ingress path (`sequence` kernel 53.0 → 69.4 ns/op = the pool accounting's two
uncontended monitor sections; `enqueueDequeue` 37.9 → 38.4; the rest within drift; frozen Phase 1 artifact restored
byte-identical); Phase 3 JMH not re-run (world code untouched this pass); architecture 7/7; `-Werror` clean;
`git diff --check` clean; 0 TODO/FIXME/HACK.

## Known limitations

1. **Shared-partition bound on world diversity.** With realistic terrain (~21 KiB charged per unique chunk) the
   96 MiB shared partition admits ~4 700 distinct chunks server-wide: about 15 fully spread-out observers at 289
   chunks each, ~10 at 441 (view distance 10). Beyond that, baselines are admitted as `CONTENT_NOT_RETAINED`
   (explicit coverage loss, never fabricated state). Co-located players share content and cost only their private
   delta. PERFORMANCE.md anticipates exactly this ("increase G explicitly for greater shared-world diversity").
2. Historical lookup is an O(h) scan of the history ring (h ≤ 4096) — forensic path, not hot.
3. Sections above `INLINE_CONTENT_CAP` (40 KiB per chunk payload) are not retained inline → `CONTENT_NOT_RETAINED`.
4. Block entities are summaries only; light, fluid, piston semantics are not modelled (Phase 4+).
5. Charged bytes underestimate real heap by ~1.05–1.25× (chunk overhead, map entries); see memory table.
6. `WorldCapsule` is not yet co-recorded into `CaptureWriter` files (same status as `TimelineCapsule`).
7. Ingress in-flight exposure: 192 KiB guaranteed per session (inside the tier) + up to 4 MiB arena / 4 MiB bulk per session drawn from one 32 MiB engine-wide pool; when the pool is exhausted a flooding session sheds its own bulk / drops its own overflow explicitly (P3-F7, conformant with the PERFORMANCE.md floor and G partition).
8. Extreme creative flight at the 768 KiB floor tier evicts far chunks (explicit `EVICTED`, observed 36 of 213 once) — coverage loss is honest, not silent.
9. Legacy (schema-1) recordings reconstruct no world: their JoinGame/Respawn carried no dimension range, so the observer world is DIMENSION_UNKNOWN for them by construction (P3-C1).

## Deferred to Phase 4+

Block shapes/AABBs, collision queries, movement simulation, fluid/piston/light physics, neighbor-sensitive shape
hypotheses, checks, setbacks, punishments, and any use of the observer world for validity judgments.

## Acceptance matrix

Status vocabulary: PASS / FAIL / EXTERNAL QUALIFICATION REQUIRED / NOT APPLICABLE BY SPEC.

| # | Criterion (task §) | Status | Evidence / test / artifact |
|---|---|---|---|
| 1 | World owner module per approved DAG, no new module/edge (§1) | PASS | §2; `ArchitectureTest` 7/7 |
| 2 | Observer world principle, no live server query (§2–3) | PASS | §3; grep; `worldModuleStaysWithinThePhase3Boundary` |
| 3 | World/dimension epoch (§4) | PASS | §4; `dimensionTransitionIsAnExplicitDiscontinuity…`, fixture `dimensionTransition` |
| 4 | Chunk key floor math, bounds (§5, §39) | PASS | `WorldCoordinateTest` 20; property 8×20 000 |
| 5 | Block position range/packing/section index (§6, §40) | PASS | same; `NativeChunkSectionTest` |
| 6 | Chunk baseline ingest incl. wire section decode (§7) | PASS | §7; `NativeChunkSectionTest` 8, `fullChunkBaselineMakesEveryCellKnown`, integration test |
| 7 | Section storage compact/palette/no per-block objects (§8) | PASS | §8; `SectionSnapshotTest` 9; JMH alloc/op |
| 8 | Block state identity platform-independent (§9–10, §57) | PASS | `BlockStateIdentityTest` 4; fixtures with perturbed dictionary |
| 9 | Full chunk replacement (§11) | PASS | `fullBaselineReplacementReplacesEverythingDeterministically`, fixture `reloadFullResend` |
| 10 | Single block update (§12) | PASS | `singleBlockUpdateOnACompleteChunk…`; property 5×2 000 |
| 11 | Multi-block update atomic, in order (§13, §45) | PASS | `multiBlockUpdateAppliesAtomicallyAsOneRevision`, `batchedUpdateEqualsSequentialUpdates…`, `aBundleIsAppliedInWireOrder…` |
| 12 | Chunk unload (§14) | PASS | `unloadMakesCurrentStateUnavailable…`, fixture `unload` |
| 13 | View/tracking changes (§15) | PASS | `ViewCenter`/`ViewRadius` mapped; eviction ordering test |
| 14 | Baseline absence → PARTIAL, never air (§16) | PASS | `singleBlockUpdateWithoutBaselineCreatesAPartialChunk…`, property 4×3 000, fixture `partialBaseline` |
| 15 | Mid-session activation / no silent baseline recovery (§17–18) | PASS | §17; UNKNOWN_CHUNK semantics; no resend/reconstruct code |
| 16 | Coverage model with all states (§19) | PASS | `WorldCoverage`, `BlockLookup.Status`; `unknownChunkSectionAndInvalidYAreDistinctResults` |
| 17 | Coverage never heals accidentally; recovers only through a qualifying full baseline (§20, P3-F8) | PASS | property `…UnknownNeverBecomesKnownWithoutObservation`, `transportCoverageBreak…`, `prefixIncompleteBaseline…` |
| 18 | Clientbound ordering by capture sequence (§21) | PASS | apply() ordering guard; `sessionReplacementIsStructural…` |
| 19 | Revision model deterministic (§22) | PASS | `revisionsAdvanceDeterministicallyAndOnlyOnMaterialChange`; property |
| 20 | Historical lookup exact, explicit misses (§23) | PASS | `historicalExactLookup…`, `evictedHistoryIsReportedNotSilentlyApproximated`, `PlayerWorldCoherenceTest` |
| 21 | History bounds 4096 / 3 s / tier (§24) | PASS | `historyAlsoExpiresByTheThreeSecondHorizon`; property history ≤ 4096; JMH `historyEvictionAtCapacity` |
| 22 | Structural sharing immutable, no parent chains (§25–26, §61) | PASS | §25; `contentEqualityMakesIdenticalBaselinesShareable`; memory table shared vs unique |
| 23 | Palette/storage safety (§27) | PASS | `malformedPaletteInputFailsSafely`, `invalidPackedIndexIsRejected…`, codec validates before allocation |
| 24 | Per-observer memory budget (§28) | PASS | property `…EvictionFreesChargedMemory`; memory table (tier-bounded scenarios) |
| 25 | Global world budget + accounting (§29) | PASS | `sharedBudgetExhaustionDegradesCoverage…`, `closeReleasesEverything…`; engine tests `sharedBytes == 0` |
| 26 | Fair eviction (§30) | PASS | farthest-from-view-center; `evictionIsDistinctFromUnload…`, fixture `evictedThenPartialUpdate` |
| 27 | Eviction semantics distinct from unload (§31) | PASS | same |
| 28 | Re-observation after eviction (§32) | PASS | same |
| 29 | Block entity observations bounded (§33) | PASS | `blockEntityObservationsAreBoundedSummariesOnTheChunk` |
| 30 | Light data (§34) | NOT APPLICABLE BY SPEC | recorded as `hasLight` only |
| 31 | Fluid state (§35) | NOT APPLICABLE BY SPEC | `fluidCount` only; semantics Phase 4+ |
| 32 | Piston / moving blocks (§36) | NOT APPLICABLE BY SPEC | recorded as bounded `BlockEvent`, never simulated |
| 33 | Client-side temporary block state (§37) | NOT APPLICABLE BY SPEC | observation only |
| 34 | Dimension height from packet (§38) | PASS | `JoinGame`/`Respawn` dimension range; `dimensionRangesMustBeSectionAligned…`; native tests assert -64/384 |
| 35 | Block lookup result type (§41) | PASS | `BlockLookup` record invariant |
| 36 | Query immutability (§42) | PASS | `publishedSnapshotIsImmutable…`; lookups only touch metrics |
| 37 | Reader concurrency (§43) | PASS | published `WorldSnapshot`; `ownershipIsAsserted` |
| 38 | Player + world ordering coherence (§44, §56) | PASS | `PlayerWorldCoherenceTest` 2; `observeSequence…` |
| 39 | Bundle ordering (§45) | PASS | `aBundleIsAppliedInWireOrderWithinOneSequence` |
| 40 | Failure containment (§46) | PASS | shard failure path unchanged; `supervisorReclaimsAWorldOnlyAfterItsOwnerThreadTerminated` |
| 41 | Resource-allocation failure explicit (§47) | PASS | budget rejection → CONTENT_NOT_RETAINED; dictionary full → coverage break |
| 42 | Session disconnect cleanup (§48) | PASS | engine close assertions; `closeReleasesEverything…` |
| 43 | Reconnect isolation (§49) | PASS | `reconnectGetsAFreshEmptyWorld…`, fixture `reconnectNewSession` |
| 44 | Replay integration (§50) | PASS | `WorldReplayMapperTest` 6; `WorldCapsule` |
| 45 | Replay does not increase knowledge (§51) | PASS | `partialStaysPartialAndUnknownCellsNeverDecodeAsAir`, `…CarriedVerbatim` |
| 46 | Deterministic fixtures (§52) | PASS | `world-fixtures.json` (12), regenerated twice byte-identical; `WorldFixtureDeterminismTest` 3 |
| 47 | Core unit-test matrix 40 items (§53) | PASS | §53 table (item 25 NOT APPLICABLE BY SPEC) |
| 48 | Property/fuzz tests with seeds (§54) | PASS | `ObserverWorldPropertyTest` 32 |
| 49 | Phase 1 → Phase 3 integration (§55) | PASS | `ObserverWorldIntegrationTest` 4 |
| 50 | Phase 2 + 3 coherence (§56) | PASS | `PlayerWorldCoherenceTest` 2 |
| 51 | Version/registry tests (§57) | PASS | `BlockStateIdentityTest`, `NativeChunkSectionTest` |
| 52 | JMH benchmarks (§58) | PASS | `benchmarks.json`; §58 |
| 53 | Memory qualification (§59) | PASS | `memory-results.json`; §59 (incl. W2 distance 10/16) |
| 54 | Player-count estimates (§60) | PASS | §60 (descriptive, hybrid model stated) |
| 55 | Memory duplication review (§61) | PASS | content-addressed immutable sharing; §25, §59 |
| 56 | Hot-path allocation (§62) | PASS | §62; JMH `-prof gc`; `withAll` batch fix |
| 57 | Metrics bounded (§63) | PASS | `WorldBudget.Snapshot`; status line |
| 58 | World diagnostic command (§64) | PASS | `/papea2ac world`; smoke log |
| 59 | Phase-3-aware Paper smoke (§65) | PASS | §65; `smoke-run-phase3.log` |
| 60 | Real client world qualification (§66) | PASS | performed 2026-09-19 with a Minecraft 26.2 client: 15/15 scenarios PASS on the final build; `real-client-evidence.log`; seven defects (P3-F3…F13) fixed with regressions |
| 61 | No collision / physics / cheat detection (§67–69) | PASS | architecture test; grep |
| 62 | Phase 2 remains sound (§70) | PASS | 215 Phase 2 tests present and passing |
| 63 | Phase 1 remains sound (§71) | PASS | 127/127, nativeTest 113/113, conformance capsule replays as schema 1 and re-sealed as schema 2; P3-C1 RESOLVED (schema 2, legacy decode proven against the real Phase 2 codec) |
| 64 | Test-count reporting convention (§72) | PASS | §0 |
| 65 | Documentation (§73) | PASS | this document |
| 66 | Verification artifacts (§74) | PASS | `docs/phase3-artifacts/{verification,benchmarks,memory-results,world-fixtures}.json` |
| 67 | git hygiene (diff --check, no generated files, no TODO/FIXME/HACK) | PASS | final verification |
| 68 | No commit / no self-approval / no Phase 4 (§77–78) | PASS | `git status`; this report |
| 69 | P3-C1 codec/schema compatibility (final pass §1–2) | PASS | schema bumped to 2; OLD→NEW and NEW→OLD proven with the real Phase 2 codec; `CapsuleSchemaCompatibilityTest` 6, `LegacyCodecCompatibilityTest` 4 |

## Final verification

All on the final code (after `withAll`/`observeSequence` and the P3-C1 schema-2 change), in this order, from `HEAD a95169e8…` with the Phase 3
working tree:

| Step (task §76) | Result |
|---|---|
| `gradlew clean test --rerun` | BUILD SUCCESSFUL — **353 tests, 0 failed, 0 skipped** (34 classes; re-run after the pre-baseline pool change) |
| `gradlew nativeTest --rerun` | BUILD SUCCESSFUL — **115 tests, 0 failed, 0 skipped** |
| `gradlew :anticheat-testing:verifyMandatoryCoverage` | PASS — 0 missing mandatory entries (**127/127**) |
| `gradlew :anticheat-testing:replayConformance` | the 4 pinned Phase 1 digests reproduced (`ef9e260b…`, `6fb8f8ab…` ×2, `9373be93…`) from the schema-1 artifact under the schema-2 decoder |
| `gradlew :anticheat-platform-paper:pluginJar` | BUILD SUCCESSFUL — `anticheat-platform-paper-1.0-SNAPSHOT-plugin.jar` (543 817 bytes) |
| architecture / dependency tests | `ArchitectureTest` 7/7 inside `test` (exact 20-module graph, platform isolation, leaves, Phase 3 boundary, frozen Phase 0 Markdown hashes) |
| configured static analysis | `-Xlint:all -Werror` on every module (the project's configured analysis; no other analyzer exists or is claimed) |
| Phase 3 JMH | `benchmarkObserverWorld` re-run on the final code (world hot path changed: gap detection, per-chunk breaks, block-update ring) → `docs/phase3-artifacts/benchmarks.json`, see §58 addendum |
| Phase 1 JMH (digest path touched by P3-C1) | `benchmark` re-run on a quiet host; frozen Phase 1 artifact restored byte-identical; uniform host drift only (see P3-C1 section) |
| Phase 3 memory harness | `observerWorldMemoryHarness` → `docs/phase3-artifacts/memory-results.json` (§59) |
| fixture determinism | `worldFixtures` regenerated 3× on the final code — file digest `d4095e08…` each time (changed from `9c6d5451…` by the reviewed P3-F8 regeneration of `captureCoverageBreak`); `WorldFixtureDeterminismTest` 3/3 |
| `git diff --check` | clean |
| TODO/FIXME/HACK search (`*.java`, `*.kts`) | 0 |
| Paper smoke after the final production change | the qualification server on the final jar: start `Done (5.161s)`, plugin enabled, `papea2ac status`/`world` via RCON with a real client joined, `stop` with the player connected → clean `Disabling Papea2AC`, 0 exceptions (`.phase1-runtime/qualification-run-2.log`) |
| git hygiene | no generated/runtime/IDE content in the tree (`docs/phase3-artifacts/*.json` are the intended summarized evidence, no chunk dumps); `.phase1-runtime`/`.phase1-tools`/`build` remain ignored |
| commit / baseline / approval | **not committed, no Phase 3 baseline created, no APPROVE output, Phase 4 not started** |
