# Phase 8 — Baseline Receipt

PHASE: Phase 8 — Movement Validation
STATUS: HUMAN-APPROVED IMPLEMENTATION BASELINE
PARENT: 060ab2a330af1886e13d1767de00c288f3a02c49 (Phase 7 baseline receipt)
PHASE 7 IMPLEMENTATION BASELINE: 537ffde64367835ffeedf63cd19776713196c110
PHASE 8 IMPLEMENTATION BASELINE: 0333d13ee8346c3909122405a680c71377c48744
PHASE 8 MANIFEST: docs/phase8-artifacts/baseline-manifest.sha256 (48 files, SHA-256 over committed Git blob content, LC_ALL=C path order; verified by two independent implementations against the index before the commit and against the committed blobs after it)
PHASE 8 MANIFEST COMPOSITE: fabfde975cb7f1be45c91ad9bfc88681308af2ab413c7730197de2a7622ba94a
FINAL BASELINE JAR SHA256: 280db1652624420a6ea4167489cc8627ec1da54bbcb5ad3409358ae201a7532c

BUILD: PASS (`clean test --rerun-tasks`, `nativeTest --rerun-tasks`, verifyMandatoryCoverage / replayConformance / movementPropertyCoverage / shapeCoverage pinned digests reproduced, pluginJar)
TESTS: test 647 / nativeTest 137 / unique 784 (one earlier `--rerun-tasks` pass had a single transient failure in the Phase 1–3 timing-sensitive `LiveRecordingTest.awaitTerminationDrainsClosingSessionsAndReleasesTheirWorlds` — "Failed to close extension context" — which passed on the repeated full run and in isolation; not a Phase 8 file)
PHASE 1: 127/127 semantic coverage, 0 mandatory gaps, transport CAPTURE-ONLY, authoritative downstream correlation UNSUPPORTED, schema 2 current / 1 readable
PHASE 2–4: PASS (timeline / world / collision fixtures identical; UNKNOWN != AIR; UNLOADED != EVICTED; shape coverage unchanged)
PHASE 5: PASS (native differential 88/88 scenarios, 3105/3105 ticks, drift 0.0; movement-property coverage 1196/1196; P6-F1 retained; physics untouched)
PHASE 6: PASS (reconstruction fixtures and no-false-exclusion identical; additive diagnostics only)
PHASE 7: PASS (26733/26733 timing truth, 1700/1700 velocity, 500/500 teleport, 1981/1981 fuzz, 0 truth excluded; generators refactored with identical statistics)

VALIDATION: PASS
VALID: PASS (explaining step → VALID; ambiguity fine; witnesses ≤ 4)
UNCERTAIN: PASS (27 typed reasons, every incomplete dimension named)
INVALID: PASS (complete supported exhausted model only; exhaustion proof)
INVALID COMPLETENESS: PASS (13 dimensions: timing, reconstruction, world, attributes, effects, equipment, entities, border, externalMotion, mechanicSupported, search, observation, frontier — each individually proven to block INVALID; enforced by the classifier and the result constructor)
INCOMPLETE -> INVALID: IMPOSSIBLE
LEGITIMATE CORPUS: 32314 transitions / 32312 VALID / 2 UNCERTAIN (SEARCH_LIMIT) / 0 INVALID
NATIVE CORPUS: 2704 / 0 INVALID
TIMING CORPUS: 29610 VALID / 0 false INVALID
IMPOSSIBLE CORPUS: 3290/3290 INVALID
SEARCH_LIMIT -> UNCERTAIN: PASS (QUERY_LIMIT -> UNCERTAIN: PASS)
UNKNOWN WORLD -> UNCERTAIN: PASS
UNSUPPORTED LOCOMOTION -> UNCERTAIN: PASS (fluid, creative/spectator/flight, vehicle, climbable contact)
EXTERNAL MOTION UNRESOLVED -> UNCERTAIN: PASS
CLIMBABLE_CONTACT: CONSERVATIVE UNCERTAINTY GATE (a climbable block within reach makes the mechanic dimension incomplete: VALID when explained, UNCERTAIN otherwise, never INVALID)

P8-F1: DOCUMENTED / FIXED / TESTED (write outcomes never enter the live event slice)
P8-F2: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (real false-INVALID defect discovered pre-baseline: two live INVALIDs on a legitimate ladder routine with sideways input; resolved conservatively via CLIMBABLE_CONTACT; the ladder/sideways-input Phase 5 model discrepancy is a forward follow-up; the Phase 5 historical baseline is unchanged)
P8-F3: DOCUMENTED / FIXED / TESTED (only physics-relevant entities tracked; bound 1024)
P8-F4: DOCUMENTED / FIXED / TESTED (applied influences retired from the live scope)
P8-F5: EXPECTED BOUNDED SEARCH EVENT / NEVER INVALID
P8-F6: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED (position-only rest reminders anchor)
P8-F7: DOCUMENTED (one unreadable live INVALID on the superseded first build 16e8e090; not reproduced on the final code in two repeats of the same flow; live-INVALID log sink safeguard added)
P8-F1..P8-F7: DOCUMENTED (docs/PHASE_8_IMPLEMENTATION.md, docs/phase8-artifacts/verification.json)

MEMORY: PASS (validator 16 B; context ledger 368 B empty / 54 368 B at every bound; runner 1 432 B empty / 12 656 B anchored with the 48-line ring full; results are values; no per-player validation history)
PERFORMANCE: PASS (final-code JMH record including the climbable-reach scan: classifier-only 0.4–1.8 µs; end to end VALID simple ~49 µs, velocity ~77 µs, INVALID ~41–42 µs, world incomplete ~287 µs, search limit ~3.2 µs, 2/4 branches ~76/104 µs, gap2 ~526 µs, gap3 ~2.46 ms, worst bounded ~1.89 ms; descriptive, no capacity claim)
PAPER SMOKE: PASS ON EXACT FINAL BASELINE JAR (enable, healthy status, clean disable, 0 exceptions / 0 errors; evidence block B1)
REAL CLIENT: PASS (legitimate course, controlled impossible injections, live uncertainty, reconnect — docs/phase8-artifacts/real-client-evidence.log)
MANUAL LADDER QUALIFICATION JAR: 280db1652624420a6ea4167489cc8627ec1da54bbcb5ad3409358ae201a7532c (322 VALID / 2 UNCERTAIN / 0 INVALID on the routine that had produced the two P8-F2 INVALIDs)
FINAL REASON-REFINED BASELINE JAR: NONE — the reason-only refinement tried after the ladder re-check was withdrawn before the baseline (strict classification invariance not provable for an unknown, physics-untouched cell inside the climbable margin); the baseline jar is the byte-identical rebuild of the manually qualified jar
FINAL REFINEMENT: WITHDRAWN / BASELINE = LIVE-QUALIFIED CODE
FALSE INVALIDS ON FINAL QUALIFIED CODE: 0

EXACT OBSERVATION MODEL: ACCEPTED FOR PHASE 8 (no arbitrary tolerance)
PHASE 9 EVIDENCE BOUNDARY: DOCUMENTED (a single Phase 8 INVALID must never independently trigger enforcement; Phase 9 consumes the completeness certificate, invalid proof, residual diagnostics, context provenance, timing/reconstruction completeness and uncertainty/support state; Phase 8 INVALID = "no legal vanilla explanation exists in the complete currently-supported model for this transition", never proven intent)
CONTROLLED INJECTION: SIDE-CHAIN ONLY
PHASE 0 DRIFT: NONE
PHASE 0–7 HISTORICAL BASELINES: UNCHANGED (all 14 baseline/receipt commits are ancestors; no amend, rebase or rewrite; frozen docs and artifacts untouched)
PHASE 9: NOT STARTED

KNOWN FORWARD ITEMS:

- deeper ladder/sideways-input discrepancy remains a Phase 5-model follow-up; Phase 8 conservatively prevents INVALID through CLIMBABLE_CONTACT
- exact observation model requires Phase 9 evidence policy before any operational action
- relevant entity proximity can force UNCERTAIN
- equipment knowledge remains incomplete
- fluids / flight / vehicles / other unsupported mechanics remain UNCERTAIN
- search-limit cases remain UNCERTAIN
- client push-out / moveTowardsClosestSpace remains unsupported
