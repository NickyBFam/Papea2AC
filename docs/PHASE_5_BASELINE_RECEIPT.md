# Phase 5 baseline receipt

Date: 2026-09-20.

This receipt is metadata recorded *after* the Phase 5 implementation baseline commit succeeded and its committed
blobs were independently verified against the frozen manifest. It is committed separately from the implementation
baseline, so this receipt's own commit SHA is never required inside the commit it describes (no self-referential
hashing) - the same model as the Phase 0, 1, 2, 3 and 4 receipts.

## Record

```
PHASE:
Phase 5 - Physics Simulator

STATUS:
HUMAN-APPROVED IMPLEMENTATION BASELINE

PARENT:
8bc0cc1ba63dc946003584aaec215f7087308544

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 4 IMPLEMENTATION BASELINE:
1685e96519f2eaec3f5b28c12d687e8118809b64

PHASE 5 IMPLEMENTATION BASELINE:
03a538c4496df92df16b7e0ce503bb61750847ae

PHASE 5 MANIFEST:
docs/phase5-artifacts/baseline-manifest.sha256

PHASE 5 MANIFEST COMPOSITE:
776d529611850888d0c139544ec0db4d2e1c890b065dc7dbbbbdbc3eaa10ee6a

BUILD:
PASS

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

PHYSICS SIMULATOR:
PASS

PHYSICS BOUNDARY:
PASS (explicit-input transfer function only; no input enumeration/reconstruction, no min-error search,
no VALID/INVALID, no cheat/violation/alert/setback/punishment)

MOVEMENT PROFILE:
paper-26.2-124-movement (profile 4df74792…, sine table 87a67a39…, block movement table de9ab636…;
1196 blocks / 32366 states / 11792 fluid states / 40 effects / 36 attributes)

MOVEMENT PROPERTY COVERAGE:
1196/1196, 0 unexplained gaps
(TRANSPORT_UNSUPPORTED 3, FLUID_DEFERRED 3, INSIDE_EFFECT 4, BOUNCE 17, FRICTION_SPECIAL 4, SPEED_FACTOR 1,
 CLIMBABLE 9, NON_MOTION_OVERRIDE 63, DEFAULT 1092)

NATIVE DIFFERENTIAL:
PASS (oracle = the actual pinned vanilla movement path executed inside the Paper 26.2 build 124 process, never
Moonrise where it differs; 91 scenarios, 88 compared, 3 fluid deferrals, 88/88 single-step exact, 88/88 chained
exact, 3105/3105 ticks bit-exact, worst absolute difference 0.0, 0 oracle errors)

MULTI-TICK DRIFT:
0.0 at 2 / 5 / 20 / 100 ticks

PINNED TRAJECTORIES:
88 native trajectories, replayed deterministically, digests stable

UNCERTAINTY MODEL:
PASS (UNKNOWN != AIR; explicit unknown/unsupported/unresolved statuses; incomplete knowledge never EXACT)

WORK BOUNDS:
PASS (64 queries / 16384 cells / 512 inside cells / 2048 entity operations per step; QUERY_LIMIT explicit)

MEMORY QUALIFICATION:
PASS

PERFORMANCE QUALIFICATION:
PASS

PAPER SMOKE:
PASS

REAL CLIENT PHYSICS QUALIFICATION:
PASS (31 evidence blocks, 0 secret matches; qualified jar byte-identical to the rebuilt final jar; clean traces
rest, W, D, W+sprint, jump, W+jump, W+shift bit-exact with zero physics residuals)

TIMING-AMBIGUOUS TRACE:
W+sprint+jump - NOT QUALIFYING, NOT A PHYSICS FAILURE (1-2 tick key-onset skew; impulse bit-exact natively)

P5-F1 .. P5-F5:
DOCUMENTED / FIXED / VERIFIED (individually, PHASE_5_IMPLEMENTATION.md §31; history unchanged)

FLUID PHYSICS:
EXPLICITLY DEFERRED (UNSUPPORTED_FLUID)

ENTITY PUSHING:
PARTIAL-BY-SPEC (UNKNOWN_ENTITY_NEIGHBORHOOD, PUSH_ORDER_AMBIGUOUS)

PHASE 0 DRIFT:
NONE

PHASE 1/2/3/4 HISTORICAL BASELINES:
UNCHANGED

PHASE 6:
NOT STARTED

KNOWN FORWARD ITEMS:
- fluids / swimming / bubble columns deferred (UNSUPPORTED_FLUID)
- elytra, ability flight, vehicles, riptide, sleeping, spectator deferred (UNSUPPORTED_MODE)
- entity pushing partial-by-spec; not natively differential-tested
- world-border observer state absent (border knowledge is a caller input; UNSUPPORTED_BORDER when near)
- LocalPlayer.moveTowardsClosestSpace push-out not modelled (INSIDE_BLOCK_START)
- Phase 7 timing (packet-to-tick alignment, key-onset skew) unresolved by design
- ~10 KB per step allocation (5 152 - 12 811 B/op under JMH) is an optimization target, not a blocker
- Phase 6 candidate multiplication is ~linear in the number of explicit inputs
  (1/8/32/64 candidates = 3.354/24.727/112.477/226.161 us; descriptive, not capacity)

ENVIRONMENTAL / NON-REPRODUCED FLAKES:
- one JUnit @TempDir deletion failure in the pre-existing Phase 2 LiveRecordingTest on one Windows clean run;
  re-runs and the final pre-baseline clean runs passed 484/484 (not a Phase 5 defect)
- one poller-starvation miss in PhysicsQualificationTest under the parallel --rerun-tasks load (11 of 12
  synthetic samples fetched); hardened test-side only, 4/4 on every rerun since (not a physics failure)
```

## Manifest and committed-blob verification

`docs/phase5-artifacts/baseline-manifest.sha256` records SHA-256 hashes for the 56 files of the Phase 5 baseline
set (5 modified tracked files, 51 new files including `docs/PHASE_5_HUMAN_APPROVAL.md`, the three pinned movement
resources and the real-client evidence log; the manifest excludes itself), computed over **committed Git blob
content** (`git cat-file blob :<path>` on the staged index, i.e. after `.gitattributes` normalization) rather than
working-tree checkout bytes. It was verified twice before the commit via two independent enumerations
(`git diff --cached --name-only` vs. index-minus-HEAD-tree by blob id) and two independent SHA-256 implementations
(coreutils `sha256sum` vs. Node `crypto`): identical file list, LC_ALL=C ordering, per-file hashes and composite.

After the baseline commit (`03a538c4496df92df16b7e0ce503bb61750847ae`), every one of the 56 files was re-read from
the **committed blob** (`git cat-file blob 03a538c4…:<path>`) and re-hashed: **56 of 56 matched exactly**, the
recomputed composite equals the manifest composite, the commit's changed-file set equals the manifest set plus the
manifest itself (57 files: 52 added, 5 modified, nothing deleted, nothing unintended, nothing missing), the committed
pinned resources are exactly `de9ab636…` (block movement table), `4df74792…` (movement profile) and `87a67a39…`
(sine table), the native differential / drift / trajectory artifacts and the 648-line real-client evidence log are
exact, every document of the Phase 0 baseline `43d8988…` is byte-identical, and the Phase 1, 2, 3 and 4 documents
and artifacts are unchanged relative to the parent. The evidence log (`*.log` is git-ignored by the generic rule)
was added explicitly after the secret scan reported zero matches (password / RCON secret / access token / session
token / credential, plus a literal search for the runtime RCON password); no ignored build, runtime, IDE, JDK,
heap-dump, benchmark-output or oracle-runtime directory was staged. Whitespace-check notes exist only in three
verbatim generated artifacts (JMH-written `benchmarks.json`, the server-output evidence log, and the
generator-written `movement-property-coverage.tsv` whose last column is empty for most rows), kept byte-identical
on purpose; all sources are clean.

## Commit lineage

```
Phase 1 implementation baseline:
35c44f9bf7694363d284c646e881351d887cf0bf
  |
  v
Phase 1 receipt commit:
769100fec8477a5958050c376a86781842df0229
  |
  v
Phase 2 implementation baseline:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24
  |
  v
Phase 2 receipt commit:
a95169e861a843e868f58c7b3a87ed050f14004a
  |
  v
Phase 3 implementation baseline:
92159ec5ef8524c5953ecaa22f663c1f556d52ac
  |
  v
Phase 3 receipt commit:
d9d50661c899f6c852247472d7ffde15dca3e78a
  |
  v
Phase 4 implementation baseline:
1685e96519f2eaec3f5b28c12d687e8118809b64
  |
  v
Phase 4 receipt commit:
8bc0cc1ba63dc946003584aaec215f7087308544
  |
  v
Phase 5 implementation baseline:
03a538c4496df92df16b7e0ce503bb61750847ae
  |
  v
Phase 5 receipt commit:
(the follow-up commit that adds this file; see repository log)
```

## What this receipt does not claim

Same as `docs/PHASE_5_HUMAN_APPROVAL.md`: no independent Senior Code Review was completed (not required by the
current workflow); one real Minecraft client (the owner's) on one host exercised the controlled-input scenarios,
and the W+sprint+jump trace remains NOT QUALIFYING because of key-onset timing ambiguity, which is neither a PASS
nor a physics failure; no second OS/architecture host has been exercised; memory and JMH figures are empirical
estimates and descriptive kernels, not supported capacity. Production transport remains CAPTURE-ONLY and
authoritative outcome correlation remains UNSUPPORTED. Fluids, elytra/ability flight/vehicles, swimming, entity
pushing beyond the explicit pusher list, world-border state and the `moveTowardsClosestSpace` push-out carry the
limitations stated in PHASE_5_IMPLEMENTATION.md §32. No input reconstruction, candidate enumeration, verdict or
mitigation behaviour exists. The Phase 1, 2, 3 and 4 implementation baseline commits are unchanged in history; the
current tree carries the documented additive forward items (`WorldView` from Phase 4, `BlockBehaviorQuery` and
`TimelineStatus.movementSequence` from Phase 5).
