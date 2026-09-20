# Phase 4 baseline receipt

Date: 2026-09-20.

This receipt is metadata recorded *after* the Phase 4 implementation baseline commit succeeded and its committed
blobs were independently verified against the frozen manifest. It is committed separately from the implementation
baseline, so this receipt's own commit SHA is never required inside the commit it describes (no self-referential
hashing) - the same model as the Phase 0, 1, 2 and 3 receipts.

## Record

```
PHASE:
Phase 4 - Collision Engine

STATUS:
HUMAN-APPROVED IMPLEMENTATION BASELINE

PARENT:
d9d50661c899f6c852247472d7ffde15dca3e78a

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 4 IMPLEMENTATION BASELINE:
1685e96519f2eaec3f5b28c12d687e8118809b64

PHASE 4 MANIFEST:
docs/phase4-artifacts/baseline-manifest.sha256

PHASE 4 MANIFEST COMPOSITE:
42e70b5b840dd4d9585cdd2f44763aeca8634bd5fd9636629564dd00a44fcd6c

BUILD:
PASS

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

COLLISION ENGINE:
PASS

SHAPE COVERAGE:
1196 blocks / 32366 states / 325 unique shapes / 0 unexplained unsupported
(empty/non-colliding 242, full cube 427, exact 121, state-derived 383, neighbour-derived 0,
 position-derived 3, context-derived 1, conservative/uncertain by spec 19)

NATIVE DIFFERENTIAL:
PASS (oracle = vanilla client algorithms in the pinned Paper 26.2 build 124 jar; all 32366 registry states with
grids, 58 320 clip cases, 12 960 candidate-acceptance cases, 3 120 offset positions, 3 072 scaffolding contexts,
1 400 avatar boxes; Paper/Moonrise collide/joinIsNotEmpty are NOT the oracle - 19 / 57 060 sampled divergences)

UNKNOWN/PARTIAL:
PASS (never CLEAR; live UNAVAILABLE(DIMENSION_UNKNOWN) definitelyClear=false; GUI-free UNKNOWN_CHUNK PASS)

CURRENT QUERY:
PASS

HISTORICAL QUERY:
PASS

WORLD EPOCH:
PASS

BOUNDED QUERY:
PASS (4096 cells rejected before work; 16384 boxes -> explicit truncation, INCOMPLETE)

MEMORY QUALIFICATION:
PASS

PERFORMANCE QUALIFICATION:
PASS

PAPER SMOKE:
PASS

REAL CLIENT COLLISION QUALIFICATION:
PASS (26 samples, 20 obstacle stations; qualified jar byte-identical to the rebuilt final jar; geometry only)

PHASE 0 DRIFT:
NONE

PHASE 1/2/3 HISTORICAL BASELINES:
UNCHANGED

PHASE 5:
NOT STARTED

KNOWN FORWARD PERFORMANCE ITEMS:
- pathological bounded 4096-cell query ~132 us / ~685 KB (quiet-host run; 184 us on the noisier final re-run) -
  Phase 5 must not repeatedly issue full-region scans during normal movement simulation
- exact voxel merge ~392 B per non-cube candidate (known optimization target)
- historical view construction dominates historical query cost (~1257 of ~1688 ns quiet-host; ~2017 of ~2628 ns final re-run)

ENVIRONMENTAL / NON-REPRODUCED FLAKE:
one JUnit @TempDir deletion failure in the pre-existing Phase 2 LiveRecordingTest on one Windows clean run;
immediate re-run and the final pre-baseline clean run passed 416/416
```

## Manifest and committed-blob verification

`docs/phase4-artifacts/baseline-manifest.sha256` records SHA-256 hashes for the 54 files of the Phase 4 baseline
set (5 modified tracked files, 49 new files including `docs/PHASE_4_HUMAN_APPROVAL.md`, the pinned collision shape
table resource and the real-client evidence log; the manifest excludes itself), computed over **committed Git blob
content** (`git cat-file blob :<path>` on the staged index, i.e. after `.gitattributes` normalization) rather than
working-tree checkout bytes. It was verified twice before the commit via two independent enumerations
(`git diff --cached --name-only` vs. index-minus-HEAD-tree by blob id) and two independent SHA-256 implementations
(coreutils `sha256sum` vs. Node `crypto`): identical file list, LC_ALL=C ordering, per-file hashes and composite.

After the baseline commit (`1685e96519f2eaec3f5b28c12d687e8118809b64`), every one of the 54 files was re-read from
the **committed blob** (`git cat-file blob 1685e965…:<path>`) and re-hashed: **54 of 54 matched exactly**, the
recomputed composite equals the manifest composite, the commit's changed-file set equals the manifest set plus the
manifest itself (55 files, nothing unintended, nothing missing, nothing deleted), the committed shape table blob is
exactly `0c3e2b3648f9e8fdfd406a5cde6b060677926df2a99729ab175e0befc535ba3b`, the native differential artifact and the
27-entry real-client evidence log are exact, the 30 Phase 0 specification documents are byte-identical to the
Phase 0 baseline `43d8988…`, and the Phase 1, 2 and 3 documents and artifacts are unchanged relative to the parent.
The evidence log (`*.log` is git-ignored by the generic rule) was added explicitly after the secret scan reported
zero matches; no ignored build, runtime, IDE, JDK, heap-dump or benchmark-output directory was staged. Whitespace
check notes exist only in two verbatim artifacts (JMH-written `benchmarks.json`, server-output evidence log), kept
byte-identical on purpose.

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
(the follow-up commit that adds this file; see repository log)
```

## What this receipt does not claim

Same as `docs/PHASE_4_HUMAN_APPROVAL.md`: no independent Senior Code Review was completed (not required by the
current workflow); one real Minecraft client (the owner's) on one host exercised the obstacle course - geometry
only, no movement physics was tested or implied; no second OS/architecture host has been exercised; memory and JMH
figures are empirical estimates and descriptive kernels, not supported capacity. Production transport remains
CAPTURE-ONLY and authoritative outcome correlation remains UNSUPPORTED. Powder snow's entity-attribute branches,
block-entity envelopes, world border, entity colliders, fluid occupancy and ray geometry carry the limitations
stated in PHASE_4_IMPLEMENTATION.md. The Phase 1, 2 and 3 implementation baseline commits are unchanged in history;
the current tree carries the documented Phase 3 read-only forward addition (`WorldView`).
