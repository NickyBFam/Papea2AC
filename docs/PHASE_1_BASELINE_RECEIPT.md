# Phase 1 baseline receipt

Date: 2026-09-16.

This receipt is metadata recorded *after* the Phase 1 implementation baseline commit succeeded and its
committed blobs were independently verified against the frozen pre-commit manifest. It is committed
separately from the implementation baseline itself, so that this receipt's own commit SHA is never required
to compute a hash inside the commit it describes (no self-referential hashing).

## Record

```
PHASE:
Phase 1

STATUS:
HUMAN-APPROVED IMPLEMENTATION BASELINE

PARENT:
43d8988862be0823e78d023a9d75854bab69a6cc

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

PHASE 1 MANIFEST COMPOSITE:
f32173c2b3441f48406e04f2635b7552b9c7360cbf7b63dbfcb1bbdca8a18d1f

BUILD:
PASS

TESTS:
210 passed / 0 failed / 0 skipped

MANDATORY SEMANTIC COVERAGE:
127/127

MANDATORY GAPS:
0

P1-H1:
PASS

P1-H2:
PASS

P1-H3:
PASS

P1-M1 LOCAL:
PASS

LOCAL HARDENING:
PASS

PRODUCTION TRANSPORT:
CAPTURE-ONLY

AUTHORITATIVE OUTCOME CORRELATION:
UNSUPPORTED

PHASE 0 DRIFT:
NONE

HUMAN APPROVAL:
APPROVED

ASTRA SENIOR REVIEW:
NOT COMPLETED / NOT REQUIRED BY CURRENT PROJECT WORKFLOW

EXTERNAL QUALIFICATION PENDING:
- real client end-to-end
- second OS/architecture
- external/native fault-load qualification where applicable

PHASE 2:
NOT STARTED
```

## Manifest and committed-blob verification

`docs/phase1-artifacts/baseline-manifest.sha256` records SHA-256 hashes for the 158 files that made up the
Phase 1 baseline review set, computed from the working tree immediately before staging, and verified twice
via two fully independent list+hash passes (identical file list, identical per-file hashes, identical
composite both times) before the baseline commit was created.

After the baseline commit (`35c44f9bf7694363d284c646e881351d887cf0bf`), every one of those 158 files was
re-read from the **committed git blob** (via `git show HEAD:<path>`, not the working tree) and re-hashed.
**157 of 158 matched the frozen manifest exactly, byte for byte.** The one exception, `gradlew.bat`, differs
in raw SHA-256 because `.gitattributes` (added as part of this same baseline, to fix a real pre-existing
line-ending inconsistency - see `docs/PHASE_1_HARDENING.md` and the manifest's own notes) declares it
`text eol=crlf`: git stores the blob LF-normalized internally and re-applies CRLF on checkout, so the
working-tree bytes (CRLF, hashed into the manifest) and the raw blob bytes (LF, as `git show` returns them)
legitimately differ at the byte level. This is not corruption: `git diff HEAD -- gradlew.bat` returns empty,
confirming git's own attribute-aware equality check sees no content difference at all. Committed blob
verification is recorded as successful with this one documented, explained, and independently confirmed
exception.

## Commit lineage

```
Phase 0 receipt commit:
43d8988862be0823e78d023a9d75854bab69a6cc
  |
  v
Phase 1 implementation baseline:
35c44f9bf7694363d284c646e881351d887cf0bf
  |
  v
Phase 1 receipt commit:
(recorded in a follow-up commit that adds this file; see repository log)
```

## What this receipt does not claim

Same as `docs/PHASE_1_HUMAN_APPROVAL.md`: no independent Senior Code Review was completed, no real
Minecraft client has connected, no second OS/architecture has been exercised, and no external or native
fault/load qualification beyond what is documented as locally simulated has occurred. Production transport
remains CAPTURE-ONLY and authoritative outcome correlation remains UNSUPPORTED.
