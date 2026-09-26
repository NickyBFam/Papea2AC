# Phase 9 — Baseline Receipt

PHASE: Phase 9 — Evidence Engine
STATUS: HUMAN-APPROVED IMPLEMENTATION BASELINE
PARENT: 3cc327df6edd8b7edf8fcb352e2f21e17c7cd579 (Phase 8 baseline receipt)
PHASE 8 IMPLEMENTATION BASELINE: 0333d13ee8346c3909122405a680c71377c48744
PHASE 9 IMPLEMENTATION BASELINE: 594c0b1d78d2e692f1ddb7f06951ddf722657fef
PHASE 9 MANIFEST: docs/phase9-artifacts/baseline-manifest.sha256 (111 files, SHA-256 over committed Git blob content, LC_ALL=C path order; verified by two independent implementations against the index before the commit and against the committed blobs after it)
PHASE 9 MANIFEST COMPOSITE: ea67331fd43a67e0290831a7fed9c8b0a1825411c16f7579668124714b60b7bd
FINAL BASELINE JAR SHA256: 297134a6425c03959ad3342e67b1179dda52e0b5ed4262308e89001e240a99da (the live-qualified jar; a `--rerun-tasks` rebuild of the baseline code is byte-identical)

BUILD: PASS (`clean test --rerun-tasks`, `nativeTest --rerun-tasks`, verifyMandatoryCoverage / replayConformance / movementPropertyCoverage / shapeCoverage pinned digests reproduced, pluginJar)
TESTS: test 697 / nativeTest 138 / unique 835
PHASE 1: 127/127 semantic coverage, 0 mandatory gaps, transport CAPTURE-ONLY, authoritative downstream correlation UNSUPPORTED
PHASE 2–4: PASS (timeline / world / collision fixtures identical; shape coverage unchanged)
PHASE 5: PASS (native differential 88/88 scenarios, 3105/3105 ticks bit-exact single-step and chained after the P9-F3 correction of model and oracle harness; movement-property coverage 1196/1196)
PHASE 6–8: forward corrections P9-F1…P9-F5 (documented in docs/PHASE_9_IMPLEMENTATION.md; regenerated reference data under docs/phase9-artifacts/, the P6-F1 precedent); legitimate corpus 32451 / 0 INVALID; impossible corpus 3290 / 3290; timing truth 0 lost

EVIDENCE: PASS
STRUCTURAL GATE: PASS (only a completed Phase 8 INVALID with a complete, intact exhaustion proof creates one immutable EvidenceEvent)
VALID / UNCERTAIN -> POSITIVE EVIDENCE: 0 (gaps and coverage only)
DEDUPLICATION: PASS (content-digest identity; re-delivery -> DUPLICATE; 512-id ring)
SESSION ISOLATION / WORLD-EPOCH SEGMENTATION / WINDOW / EXPIRY: PASS
FALSE-EVIDENCE GATE: 32451 legitimate transitions -> 0 positive events
IMPOSSIBLE CORPUS: 3290 -> 3290 events; double delivery -> 3290 DUPLICATE
SNAPSHOT: immutable, bounded, no verdict / level / enforcement field

P9-F1: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (sprint-modifier classes)
P9-F2: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (block-dispute ledger; ack does not settle)
P9-F3: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (start-pose sneak scaling per LocalPlayer.aiStep; crouching rest anchor)
P9-F4: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (frozen tick ends between login/respawn and ServerboundPlayerLoaded leave the physics slice)
P9-F5: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (PUSHER entity class pinned against the 26.2 jar; bundle children mapped by the context mapper)

MEMORY: PASS (docs/phase9-artifacts/memory-results.json; bounded ledger and snapshots)
PERFORMANCE: PASS (final-code JMH: evidence-only ingest 27–42 ns VALID/UNCERTAIN, ~1.6 µs INVALID, ~1.1 µs duplicate, full-bounded snapshot ~7.4 µs; Phase 8 kernels after the forward corrections in validation-benchmarks.json: end to end VALID simple ~60 µs, 2/4 branches ~93/130 µs, gap2 ~510 µs, gap3 ~2.69 ms, worst bounded ~2.12 ms; descriptive, no capacity claim)
PAPER SMOKE: PASS ON EXACT FINAL BASELINE JAR (≈ 14 h, clean start and stop, all dimensions saved, 0 exceptions)
REAL CLIENT: PASS — VPS, two real players (Emojiado vanilla, iDTR Lunar), 22183 transitions / 22108 VALID / 75 UNCERTAIN / 0 INVALID / 0 positive evidence on the exact final jar (docs/phase9-artifacts/real-client-evidence-vps.log)
CONTROLLED INVALID: PASS (1 INVALID -> exactly 1 event 139df203c905; live ledgers untouched)
DUPLICATE EVIDENCE: PASS (re-delivery -> DUPLICATE)
FALSE INVALIDS ON FINAL QUALIFIED CODE: 0
QUALIFICATION LOGS: force-added (15 files under docs/phase9-artifacts/: forensic captures 1, 3–9, local and VPS evidence and server logs; secret scan clean; player IPs redacted)

PHASE 0 DRIFT: NONE
PHASE 0–8 HISTORICAL BASELINES: UNCHANGED (all 18 baseline/receipt commits are ancestors; no amend, rebase, squash or rewrite; frozen docs and artifacts untouched)
PUSH / TAG: NONE
PHASE 10: NOT STARTED

KNOWN FORWARD ITEMS:

- any pushing entity within 4 blocks makes a transition UNCERTAIN (P9-F5 precision cost; a sound bound on client-side entity positions could tighten it)
- far, partially retained terrain can leave a chain unanchored (START_UNAVAILABLE UNCERTAIN, never INVALID)
- P9-F3 carries both end poses: end-to-end validation cost grew (exact client semantics; provably identical pose continuations could be merged)
- evidence is per session; cross-session and cross-engine correlation wait for later phases
- fluids / flight / vehicles / other unsupported mechanics remain UNCERTAIN; equipment knowledge remains incomplete
- snapshot interpretation, mitigation and any operational action belong to Phase 10
