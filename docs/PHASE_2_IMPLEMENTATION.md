# Phase 2 implementation report — Player Timeline

**PHASE 2 IMPLEMENTATION STATUS: READY FOR HUMAN REVIEW**

Date: 2026-09-18 (final completion pass; supersedes the 2026-09-17 "PARTIAL" checkpoint, whose history is kept
below). This is the implementer's report, not a review and not an approval: the implementer does not self-approve,
does not output APPROVE, and has not committed. Phase 1 remains complete, human-approved, baselined and frozen
(baseline `35c44f9bf7694363d284c646e881351d887cf0bf`, receipt `769100fec8477a5958050c376a86781842df0229`, still
`HEAD`). Production transport remains **CAPTURE-ONLY**; authoritative outcome correlation remains **UNSUPPORTED**;
nothing in Phase 2 changes either. No Phase 3 (world) or Phase 4+ (collision/physics/checks) code exists.

## 0. Test-count reconciliation (210 vs 152)

The earlier checkpoint reported "152 tests" against Phase 1's "210". Those figures measured different task sets:
Phase 1's 210 was `test` + `nativeTest`; the checkpoint's 152 was the `test` task alone. Verified by enumerating
every test class per task from Gradle's XML results (`build/test-results/<task>/*.xml`):

| TASK | PHASE 1 BASELINE COUNT | CURRENT COUNT | ADDED | REMOVED | SKIPPED | EXPLANATION |
|---|---|---|---|---|---|---|
| `test` | 105 | 215 | 110 | 0 | 0 | All Phase 2 tests live here. Every one of the 12 Phase 1 `test` classes is still present with its original method count (ArchitectureTest 6, CheckpointReplayTest 7, FaultInjectionAndFairnessTest 6, LiveRecordingTest 8, LongRunRetentionTest 3, PacketTruthTest 33, ReplayTest 8, ResourceSafetyTest 13, RuntimeControlOrderingTest 6, StaleControlAndResourceAccountingTest 4, TransportPhaseTest 4, WriterWatchdogTest 7 = 105). |
| `nativeTest` | 105 | 105 | 0 | 0 | 0 | Unchanged (14 classes; needs the pinned Paper 26.2 runtime jar). |
| `verifyMandatoryCoverage` | gate, not a count | gate | — | — | — | 127/127, 0 gaps, unchanged. |
| **`test` + `nativeTest`** | **210** | **320** | **110** | **0** | **0** | The only correct like-for-like comparison to Phase 1's 210. |

No Phase 1 test disappeared, was excluded, renamed out of discovery, or stopped running. **Convention from now on:**
every report states `test` and `nativeTest` separately plus their sum; "full suite" alone is never used.
`ArchitectureTest` is one of the `test` classes (6 methods), not a separate task, and is not double-counted.

## 1. Ownership (unchanged, verified against ARCHITECTURE.md's DAG table)

- **TIMELINE OWNER MODULE:** `anticheat-player` — its DAG row already reads "Component/timeline storage and local snapshot mapper" and it already held the exactly-bounded `ObservationRing`/`ProvisionalJournal` primitives.
- **DOMAIN TYPES MODULE:** `anticheat-model` — `Vector3`, `Rotation` (Decision A3 assigns "Position/vector … model internal immutable values").
- **TRANSLATION:** `anticheat-core` (`PlayerObservationMapper`) — the only module legally depending on both `protocol`/`packet` and `player`.
- **REPLAY DTO:** `anticheat-replay-schema` (`TimelineCapsule`), mapper in `anticheat-player` (`TimelineReplayMapper`) — Decision R2's owner-per-domain pattern, mirroring `protocol.TransportReplayMapper`.
- **DIAGNOSTIC PROJECTION:** `anticheat-core` (`TimelineStatus`, primitive-only) because `platform-paper` may not depend on `player`.
- **No new dependency edge anywhere.** `ArchitectureTest.gradleGraphExactlyMatchesApprovedDirectDependencies` and `importsRespectDirectEdgesAndPlatformIsolation` pass.

## 2. Ownership exclusions — specification evidence

Re-read `docs/PLAYER_STATE.md` (component table, lines 17–32) and `docs/PLAYER_TIMELINE.md` in full for this pass.

- **Effects/Attributes:** the 14-row component table has no Effects or Attributes component. The Observation Matrix row "Attributes/effects/equipment/abilities → Physics, input/interaction reach and mode" routes them to physics/interaction consumers (Phase 5+). Phase 2 does not invent an unassigned persistent component. **NOT OWNED BY PHASE 2.**
- **Vehicle:** no Vehicle component row. "vehicle relation" appears only inside `EntityTracker` ("Incarnation, push/collider eligibility …, vehicle relationships"), which is world-module territory (WORLD_MODEL.md W3, `NearbyMotionEntities`), and the Matrix row "Vehicle move, steer/paddle, mount controls → Mode and vehicle motion; loss invalidates prior walking frontier" is a capability/mode concern for the movement-validation phases. **NOT OWNED BY PHASE 2.**
- **Inventory:** `InventoryState` exists as a row, but its contents are "Window epoch/state ID, relevant items/components, using-item alternatives; Protocol-bounded active window; 256 pending deltas/256 KiB cap" — window/state-id reconciliation the Matrix assigns to "Inventory/use/placement reconciliation", i.e. the interaction module (DAG row `interaction`). The originating Phase 2 task scoped inventory to "only if PLAYER_STATE.md assigns it here"; it assigns it to interaction reconciliation, not to the timeline foundation. **NOT OWNED BY PHASE 2.**
- **MovementState's 256-attempt/256 KiB/500 ms provisional journal:** already Phase 1's `TruthActor.journal` (`new ProvisionalJournal<>(256, 262144)`, 500 ms timeout) — not duplicated. **ALREADY OWNED BY PHASE 1.**
- **ConnectionState / connection epoch:** `SessionKey.epoch()`; a reconnect gets a new `PlayerTimeline` instance. **ALREADY OWNED BY PHASE 1.**

Nothing was added merely to increase scope.

## 3. What is implemented (final)

Core design frozen from the 2026-09-17 checkpoint (no opportunistic redesign): `PlayerState` component directory
(`MovementState`, `RotationState`, `TeleportState`, `VelocityState`+`ExternalMotionInfluence`, `TickState`),
pure `PlayerStateReducer`, `PlayerTimeline` over the existing `ObservationRing`, `TimelineLookup` result semantics,
`PlayerObservationMapper`, `Vector3`/`Rotation`, structural session isolation. Changes made in this pass, each with
a regression test:

- **`TimelineRecord.source`** — the original causal `PlayerObservation`, so replay re-runs the reducer against
  inputs (REPLAY_SYSTEM.md R1) instead of storing derived output. `chargedBytes()` now includes `source.chargedBytes()`.
- **`TimelineRecord.transportDegradeReason`** + `PlayerTimeline.append(…, transportDegradeReason)` — Phase 1's
  sticky `HealthLatch` reason travels with every record (as an added `TRANSPORT_<reason>` uncertainty reason), so a
  session degraded *before* its first observation (pre-login truncation, `OVERLOADED` at bind) never yields a
  certain record. Live append and replay share one construction path (`PlayerTimeline.commit`).
- **Replay boundary** — `TimelineCapsule` (replay-schema, primitive-only, 1:1 with `PlayerObservation`'s sealed
  variants, `complete`/`incompleteReason` with the constructor refusing `complete=true` + a reason) and
  `TimelineReplayMapper` (`export`/`restore`/`export(timeline)`/`replay`). `export(timeline)` derives
  `complete=false` automatically whenever any record was ever evicted.
- **`PacketEngine.timelineStatus(UUID)`/`TimelineStatus`** and the bounded **`/papea2ac timeline <player>`** command.
- **Phase 1 fix** (below), `DeliberateCloseRaceTest`.

## 4. Phase 1 defects found by Phase 2 work (per the Phase 1 deviation protocol)

**P2-F1 — latent close race marked complete recordings INCOMPLETE (Phase 1, `PacketEngine.Shard.run`).**
`Session.close()` sets `closing=true` then `ConnectionIngress.close()` invalidates health to `CLOSED` **on the
caller's thread**. If that lands between the shard's pre-process `boundary` read and its post-process generation
check, the pre-existing line `if(recording!=null && boundary.generation()!=health.generation()) recording.incomplete()`
marked a deliberate close as data loss — although the closing branch then `finish()`es the same prefix as complete.
The window was nanoseconds in Phase 1; Phase 2's mapper/reducer/append between `process()` and the check widened it
to microseconds, making `LiveRecordingTest.actualEngineConsumptionAndCloseProduceReplayableDiagnostic` fail 4/4 on
this machine. Diagnosed with a temporary stack-trace print on the health transition (removed), not by guessing.
**Minimal fix:** a generation bump whose reason is `CLOSED` while `session.closing` is already set is a deliberate
close, not a degrade — neither `recording.incomplete()` nor a `CoverageGap` fires for it. All other `CLOSED`
sources (dispatch-rejected drain on `ConnectionIngress` line 55, `SHARD_FAILED`, `fenceShard`) still degrade
because `closing` is not set or the reason differs. Regression: `DeliberateCloseRaceTest` (40 amplified cycles,
every capsule asserted complete, plus the timeline's movement record asserted certain), and the original test now
passes 3/3 reruns. Architecture unchanged; this is the only Phase 1 production line semantically altered.

P2-F1 deviation record (for future archaeology):

```
DEVIATION:                    P2-F1
ORIGINAL PHASE:               Phase 1
DISCOVERED DURING:            Phase 2 (2026-09-18 completion pass)
AFFECTED FILE:                anticheat-core/src/main/java/me/nicolas/papea2ac/core/PacketEngine.java
                              (Shard.run(), per-observation post-process check)
ROOT CAUSE:                   Session.close() sets closing=true and then ConnectionIngress.close() invalidates the
                              session HealthLatch to CLOSED on the CALLER's thread (test/main thread), while the
                              shard thread reads `boundary = health.get()` before actor.process(o) and compares
                              against health.get() after it. A close landing inside that window is a generation
                              bump the pre-existing code could not distinguish from a real degrade.
FAILURE MODE:                 recording.incomplete() was called for a deliberate close, so a replay capsule that the
                              closing branch then finish()ed as complete was exported INCOMPLETE. Also, Phase 2's
                              wiring would have appended a CoverageGap for the close. Latent (nanosecond window) in
                              Phase 1; Phase 2's mapper/reducer/append inside the window made
                              LiveRecordingTest.actualEngineConsumptionAndCloseProduceReplayableDiagnostic fail 4/4.
FIX:                          `deliberateClose = after.reason()==CLOSED && session.closing.get()`;
                              `degraded = boundary.generation()!=after.generation() && !deliberateClose`; only
                              `degraded` triggers recording.incomplete() and the CoverageGap. Every other CLOSED /
                              SHARD_FAILED source (dispatch-rejected drain, fenceShard, shard death) still degrades
                              because closing is not set or the reason differs. One boolean condition; no
                              architecture, ordering, or ownership change.
REGRESSION TEST:              anticheat-testing/src/test/java/me/nicolas/papea2ac/testing/DeliberateCloseRaceTest.java
                              (40 amplified connect→capture→close cycles, every capsule asserted complete, movement
                              record asserted certain) + the original LiveRecordingTest method, 3/3 reruns.
PHASE 1 BASELINE MODIFIED:    NO — commit 35c44f9bf7694363d284c646e881351d887cf0bf is unchanged in history
CURRENT FORWARD CODE FIXED:   YES — the fix ships in the Phase 2 implementation baseline
```

**P2-F2 (checkpoint pass) — null `position`/`rotation` on `PacketPayload.Movement`** (field-presence): mapper NPE,
fixed by independent `Optional`s; regression `movementWithNull{Rotation,Position}Maps…WithoutThrowing`.

## 5. Replay projection

`PlayerTimeline`/`PlayerState` → `TimelineRecord.source` (the causal input) → `TimelineCapsule.Entry`
(`captureSequence`, `monotonicNanos`, primitive observation, `transportDegradeReason`) → `TimelineReplayMapper.replay`
re-runs `PlayerStateReducer` from `PlayerState.INITIAL`. Tested (`TimelineReplayMapperTest`, 13;
`CoveragePropagationTest.transportDegradedRecordsReplayAsDegraded`): state→projection for every variant,
encode/decode round trip for every variant, deterministic repeated encoding, whole-capsule replay equal to the
original `TimelineRecord` list and final state, incomplete coverage cannot decode as complete (constructor rejects
it), evicted history exports `complete=false` with `PREFIX_EVICTED`, world-epoch discontinuity reproduced,
coverage-gap and transport-degraded records replay uncertain. No live object, no Paper/NMS type crosses the DTO.
Not done, deliberately: writing `TimelineCapsule` into `CaptureWriter`'s on-disk `TransportCapsule` files. The
projection boundary and codec-level contract exist; file co-recording is a replay-module change (replay may not
import player) that belongs with the replay-driver work of a later phase.

## 6. Fixtures

`TimelineFixtures` (code-defined, no binaries): basicMovement, positionOnly, rotationOnly, positionAndRotation,
teleportObservation, velocityObservation, captureBoundary, worldDiscontinuity, reconnectNewSession. Pinned SHA-256
digests in `docs/phase2-artifacts/timeline-fixtures.json` (generated by `:anticheat-testing:timelineFixtures`;
generated twice and byte-identical). `TimelineFixtureDeterminismTest` asserts in-process repeat equality and the
pinned digests; a changed digest fails CI and is a reviewed regeneration, never a silent update.

## 7. Property / fuzz testing

`PlayerTimelinePropertyTest` — seeded `java.util.Random` (no new dependency), fixed seeds `{1..5}`/`{1..3}`,
seed and step in every failure message: monotonic sequence + bounds never exceeded (6000 random steps),
current state == independently re-derived reducer output, duplicate/out-of-order never mutate state
(`assertSame`), identical input ⇒ identical timeline, old-epoch key never mutates a replacement, lookup statuses
correct after arbitrary eviction, world-epoch transition never answers `Found` across the boundary.

## 8. Invalid numeric input

`Vector3`/`Rotation` reject NaN/±Infinity at construction (`IllegalArgumentException`) — never coerced. Negative
zero is finite and preserved bit-exactly. Absurd finite coordinates (1e300) are accepted unclamped: Phase 2 owns
finiteness only; range limits "come from the version profile, not arbitrary clamps" (PACKET_PIPELINE.md). The
reducer/timeline never sees a non-finite value (`PlayerTimelineAdditionalTest`, 3 tests).

## 9. Time model

Audited every `currentTimeMillis`/`Instant.now`/`nanoTime` in Phase 2 code: `anticheat-player` has **none**;
Phase 2 code in `core` only passes the shard loop's existing `System.nanoTime()` as `monotonicNanos`.
- **sequence** (`captureSequence` = `Observation.sequence()`, Phase 1's per-session counter): the sole ordering key.
- **logical step** (`TickState.logicalStep`): +1 per `TickObserved`, wall-clock-free.
- **server tick observation** (`TickState.lastServerTick`): from Paper's tick counter via Phase 1 normalization.
- **monotonic timestamp** (`TimelineRecord.monotonicNanos`): eviction horizon and diagnostics only, never ordering.
- **wall-clock**: unused anywhere in Phase 2.

## 10. Snapshot immutability

Every component is a record whose fields are primitives, immutable records (`Vector3`, `Rotation`), `Optional`s of
those, or `List.copyOf` lists (`TeleportState.diagnosticPredecessors`, `VelocityState.active/diagnostics`); no
array, map, buffer, native object or mutable container exists anywhere in `PlayerState`. The reducer never mutates
in place. `retainedHistoricalSnapshotIsUntouchedByLaterMutation` advances the timeline 49 times after retaining a
state and asserts value and reference identity of the retained components.

## 11. Retention / eviction resource safety

`evictedRecordsBecomeEligibleForGarbageCollection` (`WeakReference` to an evicted record cleared after GC) proves
no index, cache, listener, debug reference, or current-state chain retains an evicted record. Structural sharing
chains only reference *components*, never earlier `TimelineRecord`s or the ring. `ObservationRing.evictions()` and
`recordBytes()` are exposed in the published snapshot for accounting.

## 12. Query complexity (measured, corrected from the checkpoint's wording)

| Operation | Complexity | Measured (JMH, this pass) |
|---|---|---|
| append | O(1) amortized (+ reducer) | see §13 |
| snapshot publication / `currentState()` | O(1) volatile read | see §13 |
| exact lookup `stateAtSequence` | **O(n)** copy (`ObservationRing.snapshot()` materializes the retained window) + O(log n) binary search | see §13 |
| previous lookup `stateBefore` | O(n) copy + O(n) reverse scan | see §13 |
| range lookup `snapshotRange` | O(n) copy + O(range) | see §13 |
| eviction | O(1) per evicted record | see §13 |

The checkpoint described lookups as "non-full-scan"; that was imprecise. There is no per-record decoding or
transformation, but `ObservationRing.snapshot()` (a Phase 1 primitive also used by `TruthActor`) copies the whole
retained window. At the 4096 ceiling this is single-digit microseconds and ~8 KB of transient allocation per
query; these are later-phase/diagnostic paths, not per-packet paths, so a second index was **not** added
(originating task §12: do not over-engineer if the bounded ring already gives appropriate performance). If a later
phase queries per tick, `ObservationRing` needs an index-addressable view — a Phase 1 primitive change to be
reviewed then, not smuggled in now.

## 13. JMH results

Same methodology as Phase 1 (`PacketBenchmarks`): JMH 1.37, 3 forks, 5×1 s warmup, 10×1 s measurement,
`-Xms512m -Xmx512m`, `-prof gc`, AverageTime ns/op; host Windows 11 amd64, Temurin JDK 25 toolchain; raw JSON in
`docs/phase2-artifacts/benchmarks.json`. Final-code run (after `source`/transport fields):

| Benchmark | ns/op (avgt, 30 samples) | error (99.9%) | alloc B/op |
|---|---|---|---|
| movementAppend (position-only claim) | 46.8 | ±4.7 | 312 |
| rotationAppend (rotation-only claim) | 56.3 | ±0.8 | 304 |
| positionAndRotationAppend | 58.5 | ±2.5 | 352 |
| snapshotPublication (`snapshot()` read) | 0.62 | ±0.002 | ≈0 |
| exactHistoricalLookup (2048 retained, hit) | 4,733 | ±531 | 8,408 |
| previousStateLookup (2048 retained) | 6,759 | ±354 | 8,432 |
| rangeLookup (101 of 2048) | 5,750 | ±126 | 8,440 |
| evictionAtFullCapacity (append at 4096 ceiling) | 49.0 | ±1.0 | 296 |

(Checkpoint-code run for comparison, before `source`/transport fields: appends 34–36 ns, lookups 2.9–3.6 µs,
eviction 48 ns. The append increase is the retained `source` reference plus the transport-reason merge; the
lookup increase is run-to-run variance on an O(n) copy, not a code-path change. A first final-code run that
overlapped the memory harness was discarded and repeated with nothing else executing.)

Baseline-day re-verification (2026-09-18, same config, nothing else running) reproduced the table within its
confidence intervals: appends 49.0 / 50.6 / 52.6 ns, snapshot 0.54 ns, exact 4,859 ns, previous 5,755 ns, range
6,078 ns, eviction 48.7 ns. `benchmarks.json` keeps the documented run above; run-to-run spread on the O(n)
lookups (~±15%) is the expected noise of a bulk copy, not a code change.

Descriptive anomaly detection only, not a capacity claim. Appends allocate ~300–350 B/op (one record, one/two
components, one `PlayerState`); lookups' ~8.4 KB/op is the window copy discussed in §12.

## 14. Memory qualification (empirical, JOL not added)

`:anticheat-testing:playerTimelineMemoryHarness` — forced-GC heap diff over 200 independent timelines, 3 trials,
median (`docs/phase2-artifacts/memory-results.json`, final-code run):

| Workload (512 records unless noted) | bytes / timeline | bytes / retained record |
|---|---|---|
| idle/minimal | 446 | — |
| movement-heavy | 121,276 | ≈ 237 |
| rotation-heavy | 117,180 | ≈ 229 |
| mixed movement/rotation | 119,228 | ≈ 233 |
| teleport/velocity-heavy | 245,860 | ≈ 480 (each teleport/impulse also grows a bounded component list) |
| **at retention maximum** (4096 appends at the 512 KiB floor tier → byte bound binds at ≈2,520 retained records of 208 charged bytes) | **697,221** | ≈ 277 |

Measured retained heap runs ≈1.3× the charged estimate (object headers, `Optional`/`List` wrappers, the retained
`source`), so the floor tier's *real* footprint is ≈680 KiB, not 512 KiB. This is a reconciliation, not exact heap
truth or a supported footprint.

## 15. Scaling estimates (timeline memory only; SCALING ESTIMATES, not supported player counts)

Using the retention-maximum median (697,221 B, worst case) and the mixed workload (119,228 B, typical):

| Timelines | worst case (retention max) | typical (512-record mixed) |
|---|---|---|
| 100 | ≈ 69.7 MB | ≈ 11.9 MB |
| 250 | ≈ 174.3 MB | ≈ 29.8 MB |
| 500 | ≈ 348.6 MB | ≈ 59.6 MB |
| 1000 | ≈ 697.2 MB | ≈ 119.2 MB |

These exclude Phase 1's per-session reservation (2 MiB admission), world/replay partitions, and server heap; they
say nothing about CPU or supported capacity (PERFORMANCE.md F1).

## 16–17. Bounds and entry accounting

All three limits are enforced in the `PlayerTimeline` path through `ObservationRing` (count 4096, byte tier 512 KiB
floor / 1 MiB target — the only two accepted values —, horizon 3 s): `countBoundEvicts…WhenByteBudgetHasHeadroom`
(target tier, 4097 appends → exactly 4096), `byteBoundEvictsBeforeTheCountCeiling…` (floor tier, byte bound binds
first), `timeBoundEvictsPurelyOnElapsedHorizon…` (two records, 3 s apart), `noBoundIsEverExceededRegardlessOfWhichBindsFirst`
(5000 appends, both ceilings asserted every step). Order: expire by time first, then evict oldest while count ≥ 4096
or bytes would exceed the tier. Entry charge = `112 + 24·reasons + source.chargedBytes()` — varies with the
observation kind (32–96 B) and reasons, a documented conservative model (§14 shows real ≈1.3×). `int` arithmetic
cannot overflow: max charge 112+96+96 = 304 B × 4096 ≪ 2³¹.

## 18. Current state vs retained history

`currentState()` is a field separate from the ring; `currentStateSurvivesEvictionOfItsOwnHistoricalEntry` evicts
everything and asserts the position survives while `stateAtSequence` reports `Evicted`.

## 19. Coverage propagation (`CoveragePropagationTest`, `PlayerTimelineIntegrationTest`)

pre-existing degrade (pre-login truncation / `OVERLOADED` at bind) ⇒ every record uncertain; valid packets after a
degrade stay uncertain (sticky latch, never healed); Phase 1 journal overload (257 attempts) ⇒ `OVERLOADED` ⇒
uncertain; capture OFF closes sessions with a recorded `SessionClosed`, capture ON admits a fresh epoch; transport
degradation (`Uncovered` family) ⇒ `CoverageGap`; recording-epoch controls are administrative and leave the timeline
untouched; incomplete prefix ⇒ capsule `complete=false`.

## 20–22. Discontinuity, teleport, velocity semantics

World epoch increments on `Discontinuity` (RESPAWN/DIMENSION_CHANGE); `stateBefore` refuses to cross it
(`WorldEpochCrossing`), property-tested; range queries return records tagged with their own `worldEpoch`, never
merged. Teleport: `TeleportSent` + `TeleportState.PendingTeleport.acknowledged` means "a teleport-confirm control
packet referenced this ID", explicitly not application; the diagnostic prints `confirmPacketSeen`. The reducer
never moves `MovementState.position` on a teleport — only a subsequent claim does. Velocity: `ExternalMotionObserved`
/ `ExternalMotionInfluence` — "observed", never "applied"; no consumption/resolution (LATENCY_COMPENSATION N2's
automaton is Phase 7's), so >8 unresolved influences report `overflow=true` (`UNCERTAIN_APPLICATION_OVERFLOW`).

## 23. Phase 1 integration test

`realisticFullSessionThroughTheActualPhase1PipelineProducesTheExpectedOrderedTimeline`: connect → position+rotation
→ position-only → rotation-only → outbound `Correction` (teleport sent) → `TELEPORT_CONFIRM` → movement → outbound
`Velocity` → disconnect, all through `PacketEngine.connect`/`ingress.capture` (never the reducer directly),
asserting ordering (sequences 1–8), current state after each step, teleport ack state, one active influence, and
session cleanup (`engine.sessions()==0`).

## 24. Paper wiring

`anticheat-platform-paper` contains no timeline code (`grep -rn timeline` in its `src/main` finds only the command
text). `Papea2AC.onEnable` constructs one `PacketEngine`; `PacketEngine.Session` constructs its `PlayerTimeline`;
the only appends happen inside `Shard.run()` on the owning shard thread. No second listener, no second normalizer,
no Bukkit event touches timeline state.

## 25/36. Paper smoke (final code, rerun after the last production change)

Pinned Paper 26.2 build 124 jar, Temurin **Java 25** (`.phase1-tools/jdk-25.0.4.1+1`), rebuilt `pluginJar`
(contains `PlayerTimeline`, `TimelineStatus`, `TimelineCapsule`), console commands from `smoke-commands-phase2.txt`,
log `.phase1-runtime/smoke-run-phase2.log`: `Done (7.447s)`, `Enabling`/`Disabling` clean, **0 exceptions, 0
"Early transport capture unavailable" warnings**, `papea2ac timeline Steve` → "Player is not online." (no crash),
usage line lists `timeline`, `status`/`player`/`capture off|on`/`effects suspend|resume`/`recording`/`checkpoint`
all behave as in Phase 1 with zero-session baselines, `stop` → exit code 0. No client connected.

## 26. Diagnostic command decision

**IMPLEMENTED** (`/papea2ac timeline <player>`): the existing flat per-player subcommand pattern made it a
~15-line addition; it shows session epoch, last sequence, retained count/charged bytes, evictions, world epoch,
revision, coverage flag, observed position/rotation/onGround, server tick, teleport sent/id/confirm-seen, active
external-motion count/overflow — via the primitive-only `TimelineStatus` (no `player` import in platform-paper).
Never dumps history.

## 27. Real client timeline qualification

**PENDING.** `.phase1-runtime/client*` shows a prior session launched a native client as a probe (render thread
started, stopped after ~5 s); no in-game session was ever driven. This non-interactive session cannot legitimately
drive a GUI client, and automating the UI is out of bounds. The manual script (stand/walk/sprint/jump/rotate/look/
position+rotation/reconnect, inspect `/papea2ac timeline`) remains for a human with the client.

## 28–29. Phase 3 / Phase 4+ containment

No block/chunk/world-revision/collision/fluid/piston code; no collision query, physics, prediction, input
reconstruction, VALID/INVALID, Speed/Fly/Timer/Reach/combat, setback. `noFutureEngineOrForbiddenMathImplementation`
passes. Phase 2 flags nobody.

## 30. Phase 1 regression

`verifyMandatoryCoverage` 127/127, 0 gaps; `nativeTest` 105/105; all 105 Phase 1 `test` methods present and
passing; `frozenSpecificationAndReviewsStillMatchBaseline` passes; CAPTURE-ONLY / UNSUPPORTED unchanged
(`/papea2ac status` still prints `correlation=UNSUPPORTED`).

## 33. Acceptance matrix

Status vocabulary: PASS / FAIL / EXTERNAL QUALIFICATION REQUIRED / NOT APPLICABLE BY SPEC.

| # | Criterion | Status | Evidence | Test / artifact |
|---|---|---|---|---|
| 1 | Timeline owner module per approved DAG, no new edge | PASS | §1 | ArchitectureTest (6) |
| 2 | Component PlayerState, no monolith | PASS | §3 | PlayerStateReducerTest |
| 3 | Pure deterministic Paper-free reducer | PASS | §3, §7 | PlayerStateReducerTest, PlayerTimelinePropertyTest |
| 4 | Ordering = Phase 1 sequence only | PASS | §9 | nonMonotonicSequenceIsRejected…, property tests |
| 5 | Time model: no wall clock | PASS | §9 | audit (grep) |
| 6 | Snapshot immutability incl. indirect | PASS | §10 | retainedHistoricalSnapshotIsUntouched… |
| 7 | Retention bound enforced | PASS | §16 | noBoundIsEverExceeded… |
| 8 | Entry limit 4096 | PASS | §16 | countBoundEvicts… |
| 9 | Byte limit (floor/target tiers only) | PASS | §16 | byteBoundEvicts… |
| 10 | Time limit 3 s | PASS | §16 | timeBoundEvicts… |
| 11 | Deterministic eviction, GC-eligible | PASS | §11 | evictionOrderIsDeterministic…, evictedRecordsBecomeEligible… |
| 12 | Query API with explicit statuses | PASS | §12 | PlayerTimelineTest, lookupStatusesRemainCorrect… |
| 13 | Coverage propagation from Phase 1 | PASS | §19 | CoveragePropagationTest (6) |
| 14 | Session replacement isolation | PASS | — | reconnectGetsABrandNewTimeline…, oldSessionEventsNeverMutate… |
| 15 | World/dimension discontinuity | PASS | §20 | stateBeforeRefusesToSilentlyCross…, worldEpochTransition… |
| 16 | Teleport is observation only | PASS | §21 | teleportSentThenAcknowledged…, TimelineStatus naming |
| 17 | Velocity is observation only | PASS | §22 | externalMotionOverflows… |
| 18 | Replay projection / mapper | PASS | §5 | TimelineReplayMapperTest (13) |
| 19 | Fixture determinism | PASS | §6 | TimelineFixtureDeterminismTest, timeline-fixtures.json |
| 20 | Property tests | PASS | §7 | PlayerTimelinePropertyTest (7×seeds) |
| 21 | Test matrix (35 categories) | PASS | matrix below | — |
| 22 | Phase 1 integration test | PASS | §23 | PlayerTimelineIntegrationTest (9) |
| 23 | Memory qualification | PASS | §14 | memory-results.json |
| 24 | Scaling estimates | PASS | §15 | this document |
| 25 | JMH | PASS | §13 | benchmarks.json |
| 26 | Paper wiring | PASS | §24 | grep, smoke |
| 27 | Phase-2-aware Paper smoke | PASS | §25 | smoke-run-phase2.log |
| 28 | Diagnostic command | PASS | §26 | smoke log line 216, 88 |
| 29 | Real client qualification | EXTERNAL QUALIFICATION REQUIRED | §27 | — |
| 30 | Phase 1 regression none | PASS | §30 | verifyMandatoryCoverage, nativeTest |
| 31 | No Phase 3/4+ code | PASS | §28 | noFutureEngineOrForbiddenMath… |
| 32 | Effects/Attributes, Vehicle, Inventory | NOT APPLICABLE BY SPEC | §2 | — |
| 33 | git hygiene (diff --check, no generated files, no TODO) | PASS | §34 | — |
| 34 | No commit / no self-approval | PASS | `git status` | — |

### Test-matrix audit (originating task §6)

| # | Category | Status | Test |
|---|---|---|---|
| 1 | empty timeline | PASS | emptyTimelineReportsInitialStateAndZeroesEverywhere |
| 2 | first observation | PASS | appendAdvancesCurrentStateAndCommittedSequence |
| 3 | position-only | PASS | positionOnlyClaimLeavesRotation…, movementWithNullRotationMaps… |
| 4 | rotation-only | PASS | rotationOnlyClaimLeavesMovement…, movementWithNullPositionMaps… |
| 5 | position+rotation | PASS | movementClaimUpdatesBothComponents… |
| 6 | on-ground | PASS | onGroundAndHorizontalCollisionFlagsAreCarriedExactly |
| 7 | sequence monotonicity | PASS | nonMonotonicSequenceIsRejected…, property #1 |
| 8 | duplicate sequence | PASS | same + duplicateAndOutOfOrder… |
| 9 | out-of-order | PASS | same |
| 10 | immutable snapshots | PASS | retainedHistoricalSnapshotIsUntouched… |
| 11 | current-state publication | PASS | snapshotIsReadableWithoutOwnershipFromAnyThread, timelineStatusProjects… |
| 12 | exact lookup | PASS | stateAtSequenceFindsAnExactCommittedRecord |
| 13 | previous lookup | PASS | stateBeforeWithinTheSameEpochFindsTheRecord |
| 14 | range lookup | PASS | snapshotRangeReturnsOnlyTheClosedIntervalRequested |
| 15 | missing sequence | PASS | stateAtSequenceBeyondLastCommittedIsNeverExisted, …IsPreCapture |
| 16 | evicted sequence | PASS | stateAtSequenceForAnAgedOutRecordIsEvicted |
| 17 | retention bound | PASS | noBoundIsEverExceeded… |
| 18 | deterministic eviction | PASS | evictionOrderIsDeterministicAndReproducible |
| 19 | reconnect same UUID | PASS | reconnectGetsABrandNewTimeline… |
| 20 | late old-session event | PASS | staleSessionAppendIsRejected…, oldSessionEventsNeverMutate… |
| 21 | capture epoch change | PASS | globalCaptureDisableClosesActiveSessions… |
| 22 | prefix incomplete | PASS | evictedHistoryProducesAnHonestlyIncompleteCapsule |
| 23 | global capture boundary | PASS | globalCaptureDisableClosesActiveSessions… |
| 24 | transport degradation boundary | PASS | pipelineDegradeIsRecordedAsACoverageGap…, validPacketsAfterADegradeStayUncertain… |
| 25 | world/dimension discontinuity | PASS | discontinuityIncrementsWorldEpoch…, worldEpochDiscontinuityIsReproduced… |
| 26 | teleport observation | PASS | teleportSentThenAcknowledged…, teleportAcknowledgedWithUnknownId… |
| 27 | velocity observation | PASS | externalMotionOverflows…, outboundVelocityMapsTo… |
| 28 | optional owned effects/attributes | NOT OWNED BY PHASE 2 | §2 |
| 29 | optional owned vehicle state | NOT OWNED BY PHASE 2 | §2 |
| 30 | invalid numeric input | PASS | nonFiniteCoordinatesAreRejected…, negativeZero…, absurdButFinite… |
| 31 | session close | PASS | sessionCloseAppendsAFinalSessionClosedRecord, closedTimelineRejectsFurtherAppends |
| 32 | shutdown | PASS | engineShutdownClosesEverySessionsTimelineCleanly |
| 33 | replay projection | PASS | TimelineReplayMapperTest |
| 34 | fixture determinism | PASS | TimelineFixtureDeterminismTest |
| 35 | memory/accounting release | PASS | evictedRecordsBecomeEligibleForGarbageCollection, memory harness |

## 34. Final verification (final code)

`gradlew clean test` PASS (`test` 215); `nativeTest` PASS (105); `verifyMandatoryCoverage` 127/127;
`:anticheat-platform-paper:pluginJar` PASS; ArchitectureTest 6/6; static analysis = the build's own
`-Xlint:all -Werror` (passes); `benchmarkPlayerTimeline` PASS; `playerTimelineMemoryHarness` PASS;
`timelineFixtures` reproducible; `git diff --check` clean (tracked and, via `add -N`, untracked);
TODO/FIXME/HACK: none in Phase 2 sources; no `catch(Throwable|Exception)` in Phase 2 production code.

## Known limitations

- Lookups are O(n) window copies (§12); adequate at n ≤ 4096 for non-hot paths only.
- `TimelineCapsule` is not yet co-recorded into `CaptureWriter` files (§5).
- `ExternalMotionInfluence` is never resolved/consumed until Phase 7; long sessions with >8 unresolved impulses report overflow.
- REJECTED/FAILED/UNKNOWN movement outcomes are not family-classifiable from the bare `Outcome` (Phase 1's private journal); coverage of those surfaces as transport uncertainty, not per-record outcome.
- `MovementClaim` cannot express "position present but Y omitted" finer than the wire's position/rotation split — matching Phase 1's normalizer.
- Charged bytes under-estimate real heap by ≈1.3× (§14).
- Real-client qualification pending (§27).

## History

- 2026-09-17 checkpoint: core foundation implemented; replay/JMH/memory/property/fixtures/smoke deferred; P2-F2 found and fixed.
- 2026-09-18 completion pass: everything above; P2-F1 found and fixed.

**No commit has been made. Baselining is a separate future task for the human project owner.**
