# Phase 2 baseline receipt

Date: 2026-09-18.

This receipt is metadata recorded *after* the Phase 2 implementation baseline commit succeeded and its committed
blobs were independently verified against the frozen manifest. It is committed separately from the implementation
baseline, so this receipt's own commit SHA is never required inside the commit it describes (no self-referential
hashing) - the same model as the Phase 0 and Phase 1 receipts.

## Record

```
PHASE:
Phase 2 - Player Timeline

STATUS:
HUMAN-APPROVED IMPLEMENTATION BASELINE

PARENT:
769100fec8477a5958050c376a86781842df0229

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 2 MANIFEST:
docs/phase2-artifacts/baseline-manifest.sha256

PHASE 2 MANIFEST COMPOSITE:
087d7e2305c82ee613b164f852626e7beea925c8564e7021c111a068cbcd23e3

BUILD:
PASS

TESTS:
test 215
nativeTest 105
unique total 320
0 failed
0 skipped

PHASE 1 SEMANTIC COVERAGE:
127/127

PHASE 1 MANDATORY GAPS:
0

PLAYER TIMELINE:
PASS

STATE REDUCER:
PASS

ORDERING:
PASS

TIME MODEL:
PASS

SNAPSHOT IMMUTABILITY:
PASS

RETENTION:
PASS

EVICTION:
PASS

QUERY API:
PASS

COVERAGE:
PASS

SESSION REPLACEMENT:
PASS

WORLD/DIMENSION DISCONTINUITY:
PASS

TELEPORT OBSERVATION:
PASS

VELOCITY OBSERVATION:
PASS

REPLAY PROJECTION:
PASS

PROPERTY TESTS:
PASS

MEMORY QUALIFICATION:
PASS

PERFORMANCE QUALIFICATION:
PASS

PAPER SMOKE:
PASS

REAL CLIENT TIMELINE QUALIFICATION:
PENDING

PHASE 1 BASELINE:
IMMUTABLE IN HISTORY

PHASE 1 COMPATIBILITY:
PRESERVED (production transport CAPTURE-ONLY; authoritative outcome correlation UNSUPPORTED)

PHASE 1 FORWARD DEVIATION:
P2-F1 - documented latent defect correction (PacketEngine.java)

PHASE 0 DRIFT:
NONE

PHASE 3:
NOT STARTED
```

## Manifest and committed-blob verification

`docs/phase2-artifacts/baseline-manifest.sha256` records SHA-256 hashes for the 41 files of the Phase 2 baseline
set, computed over **committed Git blob content** (`git cat-file blob :<path>` on the staged index, i.e. after
`.gitattributes` normalization) rather than working-tree checkout bytes - deliberately, so that the Phase 1
manifest's expected `gradlew.bat` CRLF/LF exception cannot recur. It was verified twice before the commit via two
independent enumerations (`git diff --cached` vs. index-minus-HEAD-tree) and two independent SHA-256
implementations (coreutils `sha256sum` vs. `openssl dgst`): identical file list, ordering, per-file hashes and
composite.

After the baseline commit (`f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24`), every one of the 41 files was re-read from
the **committed blob** (`git cat-file blob f24824f9…:<path>`) and re-hashed: **41 of 41 matched exactly**, the
recomputed composite equals the manifest composite, the commit's changed-file set equals the manifest set plus the
manifest itself (42 files, nothing unintended, nothing missing, nothing deleted), the P2-F1 fix is present in the
committed `PacketEngine.java`, the 15 Phase 0 specification documents are byte-identical to the Phase 0 baseline
`c58b69e8…`, and the Phase 1 documents/artifacts are unchanged relative to the parent.

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
(the follow-up commit that adds this file; see repository log)
```

## What this receipt does not claim

Same as `docs/PHASE_2_HUMAN_APPROVAL.md`: no independent Senior Code Review was completed (not required by the
current workflow), no real Minecraft client has connected, no second OS/architecture host has been exercised, and no
external or native fault/load qualification beyond what is documented as locally simulated has occurred. Memory and
JMH figures are empirical estimates and descriptive kernels, not supported capacity. Production transport remains
CAPTURE-ONLY and authoritative outcome correlation remains UNSUPPORTED. The Phase 1 implementation baseline commit is
unchanged in history; the current tree is not "Phase 1 byte-for-byte unchanged" - it carries the documented P2-F1
forward fix.
