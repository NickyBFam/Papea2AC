# Phase 0 baseline receipt

PHASE: Phase 0

STATUS: APPROVED BASELINE WITH DEFERRED REVIEW-ARTIFACT RECONCILIATION

BASELINE COMMIT: `c58b69e84d641d6c4d18edf86ffccc6f8726a42a`

BASELINE DATE/TIME: 2026-09-15T22:59:54-04:00

SPECIFICATION MANIFEST: `1a1b987df59ef9fd411528296c2150f2b4fbbb3417ec36cddd286f1c65f64155`

COMMITTED SPECIFICATION MANIFEST: `1a1b987df59ef9fd411528296c2150f2b4fbbb3417ec36cddd286f1c65f64155`

SENIOR ARCHITECTURE REVIEW: APPROVE — [PHASE 0 REVIEW #2: APPROVE](PHASE_0_SENIOR_REVIEW_2.md)

HUMAN APPROVAL: APPROVE — explicit owner authorization to finalize the local baseline, followed by explicit authorization to defer artifact reconciliation.

DEFERRED ISSUE: Review #2 comparison artifact recorded-size discrepancy — unresolved; tracked in [PHASE_0_BASELINE_EXCEPTION.md](PHASE_0_BASELINE_EXCEPTION.md). Reconciliation owner: Senior Architecture Reviewer / Claude when available. No cause or resolution is asserted. If reconciliation discovers specification drift or an invalid Review #2 conclusion, affected implementation must pause for reassessment.

## Verification

The approved manifest's 31 entries were independently hashed from raw working-tree bytes and from staged blobs immediately before the baseline commit. After committing, all 31 entries were independently read using Git's committed blob interface at the baseline revision and hashed again. Every per-file SHA-256 and the composite matched the approved manifest. The composite is SHA-256 over ordinal-path-sorted UTF-8 lines containing the lowercase file SHA-256, two spaces, repository-relative forward-slash path and LF.

All 59 files in the baseline tree were also compared against their pre-commit raw-byte hashes, with exact path-set equality. No expected file was omitted or unexpected file included. The four existing skeleton files were preserved; the source tree contains only the existing empty JavaPlugin and plugin descriptor. No Packet Engine, detections or other Phase 1 implementation exists.

- Review #1 preserved: SHA-256 `86f18bcb1f00d7c8aaf29bb9a80a5261f658476b2b1d9f333d130fa3ae182341`.
- Review #2 preserved: SHA-256 `de382644c5117ad22eec7a6af5f0e5dd192ecce27d339227d7787c6a7af19d18`.
- Existing diff artifact preserved: SHA-256 `57178546195fd526200895e8ac935a9ccd79b871aa9b65a50df74ae81c93425b`.
- Approved specifications and historical review/status statements were not edited. This receipt and the exception supply the subsequent process state.

The repository-local Git setting `core.autocrlf=false` preserves approved raw bytes; no global configuration was changed. Staging used raw blobs without content filters, then verified the index bytes. Local Git configuration is outside the commit. Ignored `.idea/` metadata remains local and excluded; no build output, credentials, local environment files or temporary forensic files were included. The two existing Python files are preserved Phase 0 review utilities, not new implementation.

## Receipt handling

Decision D2 in [AI_DEVELOPMENT_RULES.md](AI_DEVELOPMENT_RULES.md) requires a separate later receipt referencing the real verified baseline and excludes that receipt from the approved specification composite. It does not prescribe a filename or require the baseline commit to contain its own SHA. This receipt uses `docs/PHASE_0_BASELINE_RECEIPT.md` and is recorded in a follow-up metadata-only commit. Its BASELINE COMMIT field identifies the preceding architecture baseline, not the receipt commit. No recursive hash or self-referential commit is required.

## Tracked implementation findings

| Finding | Status and assigned gate |
|---|---|
| NEW-M1 | OPEN / TRACKED — Phase 9, before Phase 10 effects. Name the capsule prehistory source and its session-count dependence; specify the session counts for TC25 durability qualification. |
| NEW-L1 | OPEN / TRACKED — Phase 1 architecture check. Name the destination-region clearance input type and owner; verify model port signatures require no module dependency. |

The full findings and acceptance criteria remain in unchanged [Review #2](PHASE_0_SENIOR_REVIEW_2.md#new-findings).

PHASE 0 GATE: COMPLETE under the explicit human exception.

PHASE 1 GATE: OPEN for a separately authorized implementation task. Opening the gate does not execute or authorize implementation within this baseline task.

No runtime tests, native-client qualification, compilation, heap measurements or load benchmarks were performed for this baseline. Only content, repository and commit integrity were verified. No push or tag was performed.

PHASE 1 HAS NOT BEEN STARTED.
