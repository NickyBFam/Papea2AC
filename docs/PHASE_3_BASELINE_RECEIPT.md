# Phase 3 baseline receipt

Date: 2026-09-19.

This receipt is metadata recorded *after* the Phase 3 implementation baseline commit succeeded and its committed
blobs were independently verified against the frozen manifest. It is committed separately from the implementation
baseline, so this receipt's own commit SHA is never required inside the commit it describes (no self-referential
hashing) - the same model as the Phase 0, Phase 1 and Phase 2 receipts.

## Record

```
PHASE:
Phase 3 - World Reconstruction

STATUS:
HUMAN-APPROVED IMPLEMENTATION BASELINE

PARENT:
a95169e861a843e868f58c7b3a87ed050f14004a

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 3 MANIFEST:
docs/phase3-artifacts/baseline-manifest.sha256

PHASE 3 MANIFEST COMPOSITE:
437de69d8e4be2deb5d98552677658c95d190032e60c95e37eb1ef66e871ab6a

BUILD:
PASS

TESTS:
test 353
nativeTest 115
unique total 468
0 failed
0 skipped

PHASE 1 SEMANTIC COVERAGE:
127/127

PHASE 1 MANDATORY GAPS:
0

OBSERVER WORLD MODEL:
PASS

CHUNK BASELINES / SECTION DECODING:
PASS

BLOCK UPDATES (SINGLE / MULTI):
PASS

CHUNK UNLOAD / RELOAD:
PASS

COVERAGE PROVENANCE (UNLOADED / EVICTED / CONTENT_NOT_RETAINED / TRANSPORT_INCOMPLETE):
PASS

UNKNOWN NEVER BECOMES AIR WITHOUT QUALIFYING OBSERVATION:
PASS

DIMENSION / WORLD EPOCH DISCONTINUITY:
PASS

SESSION REPLACEMENT / ISOLATION:
PASS

REVISION MODEL / IMMUTABLE SNAPSHOTS:
PASS

WORLD BUDGET / GLOBAL ACCOUNTING:
PASS

REPLAY PROJECTION (WorldCapsule / WorldReplayMapper):
PASS

PROPERTY TESTS:
PASS

MEMORY QUALIFICATION:
PASS

PERFORMANCE QUALIFICATION:
PASS

PAPER SMOKE:
PASS

REAL CLIENT WORLD QUALIFICATION:
PASS (15/15 scenarios on the final build, 2026-09-19)

P3-C1 CODEC / SCHEMA COMPATIBILITY:
RESOLVED - SCHEMA 2 (schema 1 permanently decodable; schema >= 3 rejected clearly)

P3-F3..P3-F13:
DOCUMENTED, REGRESSION-TESTED AND REQUALIFIED

P3-F7 INGRESS SIZING:
CONFORMANT - FLOOR EXCEEDED FOR REAL-TRAFFIC SAFETY
(192 KiB guaranteed slice; arena <= 4 MiB / 4096 records; bulk <= 4 MiB; 32 MiB engine-wide
InfrastructurePool; worst case sessions x 192 KiB + 32 MiB = ~224 MiB at 1024 sessions)

RECORDING DEGRADED AT FRAME 2:
EXPECTED SAFE LIMITATION

REAL-CLIENT SESSION HEALTH NEVER HEALTHY:
EXPECTED SAFE STATUS

HUMAN EXCEPTION REQUIRED:
NO

PHASE 1 HISTORICAL BASELINE:
UNCHANGED

PHASE 1 FORWARD FIXES DISCOVERED DURING PHASE 3:
DOCUMENTED

PHASE 2 HISTORICAL BASELINE:
UNCHANGED

PHASE 2 COMPATIBILITY:
PRESERVED (production transport CAPTURE-ONLY; authoritative outcome correlation UNSUPPORTED)

PHASE 0 DRIFT:
NONE

PHASE 4:
NOT STARTED
```

## Manifest and committed-blob verification

`docs/phase3-artifacts/baseline-manifest.sha256` records SHA-256 hashes for the 73 files of the Phase 3 baseline
set (29 modified tracked files, 44 new files including `docs/PHASE_3_HUMAN_APPROVAL.md` and the real-client evidence
log; the manifest excludes itself), computed over **committed Git blob content** (`git cat-file blob :<path>` on the
staged index, i.e. after `.gitattributes` normalization) rather than working-tree checkout bytes. It was verified
twice before the commit via two independent enumerations (`git diff --cached --name-only` vs. index-minus-HEAD-tree
by blob id) and two independent SHA-256 implementations (coreutils `sha256sum` vs. Node `crypto`): identical file
list, LC_ALL=C ordering, per-file hashes and composite.

After the baseline commit (`92159ec5ef8524c5953ecaa22f663c1f556d52ac`), every one of the 73 files was re-read from
the **committed blob** (`git cat-file blob 92159ec5…:<path>`) and re-hashed: **73 of 73 matched exactly**, the
recomputed composite equals the manifest composite, the commit's changed-file set equals the manifest set plus the
manifest itself (74 files, nothing unintended, nothing missing, nothing deleted), the 30 Phase 0 specification
documents are byte-identical to the Phase 0 baseline `43d8988…`, and the Phase 1 and Phase 2 documents and
artifacts are unchanged relative to the parent. The real-client evidence log (`*.log` is git-ignored by the
generic rule) was added explicitly after the secret scan reported zero matches; no ignored build, runtime, IDE,
JDK or benchmark output directory was staged.

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
(the follow-up commit that adds this file; see repository log)
```

## What this receipt does not claim

Same as `docs/PHASE_3_HUMAN_APPROVAL.md`: no independent Senior Code Review was completed (not required by the
current workflow), one real Minecraft client (the owner's) on one host has been exercised - not a multi-player or
second-OS/architecture qualification - and no external or native fault/load qualification beyond what is documented
as locally simulated has occurred. Memory and JMH figures are empirical estimates and descriptive kernels, not
supported capacity. Production transport remains CAPTURE-ONLY and authoritative outcome correlation remains
UNSUPPORTED. No collision, physics, movement validation, check, setback or punishment behavior exists or was
qualified. The Phase 1 and Phase 2 implementation baseline commits are unchanged in history; the current tree is not
"Phase 1/2 byte-for-byte unchanged" - it carries the documented forward fixes P2-F1 and P3-F3..P3-F13 and the
schema-2 codec migration (P3-C1).
