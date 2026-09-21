# Phase 8 — Human Approval Record

PHASE: Phase 8 — Movement Validation
STATUS: HUMAN APPROVED FOR BASELINE (owner authorization received 2026-09-21 after the implementation report and the final pre-baseline decision pass)
PARENT: 060ab2a330af1886e13d1767de00c288f3a02c49 (Phase 7 baseline receipt)
PHASE 7 IMPLEMENTATION BASELINE: 537ffde64367835ffeedf63cd19776713196c110 (unchanged in history)

TESTS: test 647 / nativeTest 137 / unique 784 (final `clean test --rerun-tasks` and `nativeTest --rerun-tasks` on the baseline code)
PHASE 1: 127/127 semantic coverage, 0 mandatory gaps, transport CAPTURE-ONLY, authoritative downstream correlation UNSUPPORTED, schema 2 current / 1 readable (unchanged)
PHASE 2–7 REGRESSION: NONE (pinned fixture digests reproduced; physics native differential 88/88, 3105/3105 ticks, drift 0.0; movement-property coverage 1196/1196; Phase 6 no-false-exclusion and round trips; Phase 7 26733/26733 timing truth, 1700/1700 velocity, 500/500 teleport, 1981/1981 fuzz, 0 truth excluded)

VALIDATION CORE: PASS (`MovementValidator` is the only producer of VALID / UNCERTAIN / INVALID; explaining step → VALID; no witness + any incomplete or unsupported dimension → UNCERTAIN naming each; no witness + complete supported exhausted model → INVALID with an exhaustion proof)
INVALID COMPLETENESS INVARIANT: PASS (13 dimensions — timing, reconstruction, world, attributes, effects, equipment, entities, border, externalMotion, mechanicSupported, search, observation, frontier — each individually proven to block INVALID by `ValidationSafetyTest`; enforced by the classifier and again by the `MovementValidationResult` constructor)
INCOMPLETE -> INVALID: IMPOSSIBLE BY CONSTRUCTION

LEGITIMATE CORPUS: 32314 transitions / 32312 VALID / 2 UNCERTAIN (SEARCH_LIMIT) / 0 INVALID
NATIVE CORPUS: 2704 transitions / 0 INVALID
TIMING CORPUS: 29610 transitions VALID with the true branch retained / 0 false INVALID
IMPOSSIBLE CORPUS: 3290 / 3290 INVALID (0 UNCERTAIN, 0 VALID)
SEARCH_LIMIT / QUERY_LIMIT -> UNCERTAIN: PASS
UNKNOWN WORLD / CONTENT_NOT_RETAINED / UNKNOWN ATTRIBUTE / UNKNOWN EFFECT / ENTITIES / BORDER -> UNCERTAIN: PASS
EXTERNAL MOTION UNRESOLVED -> UNCERTAIN: PASS
UNSUPPORTED LOCOMOTION (fluid, flight/creative/spectator, vehicle, climbable contact) -> UNCERTAIN: PASS

REAL CLIENT LEGITIMATE: PASS (owner session, vanilla 26.2 client on the pinned Paper 26.2-124: idle, walk, sprint, strafe, diagonal, jumps, sprint jumps, 4-block fall, wall/corner, slab, stairs, fence, wall, ice, slime, honey, soul sand, ladder, crouch, shield, knockback, explosion, teleports, three velocities, disconnect/reconnect; `docs/phase8-artifacts/real-client-evidence.log`)
FINAL LADDER RECHECK (baseline jar 280db1652624420a…): 322 VALID / 2 UNCERTAIN / 0 INVALID
CONTROLLED IMPOSSIBLE: PASS (eight side-chain injections INVALID with the expected diagnostics on jar 39d447d6, repeated on e47ecc75, e55aca05 and 8fedca08; live chain untouched)
LIVE UNCERTAINTY: PASS (water → UNSUPPORTED_FLUID; creative → UNSUPPORTED_LOCOMOTION)
PAPER SMOKE: PASS on the exact baseline jar (enable, healthy status, clean disable, 0 exceptions)

P8-F2: REAL FALSE-INVALID DEFECT DISCOVERED PRE-BASELINE (two live INVALIDs on a legitimate ladder routine with sideways input, jar 8fedca08) / FIXED CONSERVATIVELY VIA CLIMBABLE_CONTACT (climbable within reach → mechanic dimension incomplete: VALID when explained, UNCERTAIN otherwise, never INVALID) / REGRESSION TESTED (`ValidationSafetyTest`, fixture `uncertain_climbable_contact`) / LIVE RECHECKED (same routine on the baseline jar: 0 INVALID) — the underlying ladder/sideways-input Phase 5 model discrepancy is a documented forward follow-up; the Phase 5 historical baseline is unchanged
P8-F5: EXPECTED BOUNDED SEARCH EVENT / NEVER INVALID (UNCERTAIN [SEARCH_LIMIT]; 1 timing hypothesis, 4 Phase 6 calls, `SearchBudget.DEFAULT` inside the silent closure, world/collision complete; final jar honey→soul sand 1128/1128 VALID; offline VALID)
P8-F1..P8-F7: DOCUMENTED individually in `docs/PHASE_8_IMPLEMENTATION.md` (FORWARD DEFECTS table + FINAL PRE-BASELINE DECISIONS) and `docs/phase8-artifacts/verification.json` (`forwardDefects`, `preBaselineDecisions`); P8-F7 (one unreadable live INVALID on the superseded first build 16e8e090, not reproduced, log-sink safeguard added) is transparently recorded

EXACT OBSERVATION MODEL: ACCEPTED FOR PHASE 8 (no arbitrary tolerance; safe only because INVALID additionally requires the complete supported model in every dimension and the corpora / live qualification show zero false INVALIDs on the final code)
PHASE 9 EVIDENCE BOUNDARY: DOCUMENTED (a single Phase 8 INVALID must never independently trigger enforcement; Phase 9 must consume the completeness certificate, invalid proof, residual diagnostics, context provenance, timing/reconstruction completeness and uncertainty/support state; Phase 8 INVALID means "no legal vanilla explanation exists in the complete currently-supported model for this transition", never proven intent)
CONTROLLED INJECTION: SIDE-CHAIN ONLY (architecture + runner regressions)

FINAL BASELINE JAR: 280db1652624420a6ea4167489cc8627ec1da54bbcb5ad3409358ae201a7532c (byte-identical rebuild of the jar the final manual ladder re-check ran on; a later reason-only refinement was withdrawn before the baseline because strict classification invariance could not be proven)

PHASE 0 DRIFT: NONE
PHASE 0–7 HISTORICAL BASELINES: UNCHANGED (all baseline and receipt commits are ancestors of the parent; no amend, no rebase; frozen docs and artifacts untouched)
PHASE 9: NOT STARTED (no violation level, accumulation, alert, setback, kick, ban, punishment or enforcement threshold anywhere in production code)
