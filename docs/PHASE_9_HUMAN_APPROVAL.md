# Phase 9 — Human Approval Record

PHASE: Phase 9 — Evidence Engine
STATUS: HUMAN APPROVED FOR BASELINE (owner authorization received 2026-09-26 after the implementation report, the final live requalification and the controlled checks; the owner explicitly confirmed the baseline and the separate receipt commit)
PARENT: 3cc327df6edd8b7edf8fcb352e2f21e17c7cd579 (Phase 8 baseline receipt)
PHASE 8 IMPLEMENTATION BASELINE: 0333d13ee8346c3909122405a680c71377c48744 (unchanged in history)

TESTS: test 697 / nativeTest 138 / unique 835 (final `clean test --rerun-tasks` and `nativeTest --rerun-tasks` on the baseline code)
PHASE 1: 127/127 semantic coverage, 0 mandatory gaps, transport CAPTURE-ONLY, authoritative downstream correlation UNSUPPORTED (unchanged)
PHASE 1–8 REGRESSION: NONE for the frozen history (Phase 0–8 baselines, receipts, documents and artifacts untouched; pinned digests reproduced). Forward corrections to Phase 5/6/7/8 components (P9-F1…P9-F5) are recorded in `docs/PHASE_9_IMPLEMENTATION.md` and their regenerated reference data lives under `docs/phase9-artifacts/` (the P6-F1 precedent); physics native differential 88/88 scenarios, 3105/3105 ticks bit-exact; legitimate corpus 32451 / 0 INVALID; impossible corpus 3290 / 3290

EVIDENCE CORE: PASS (`anticheat-evidence`: only a completed Phase 8 INVALID with a complete, intact exhaustion proof creates an immutable EvidenceEvent — structural gate, not caller convention; VALID / UNCERTAIN / SEARCH_LIMIT / unsupported / internal error / malformed proof → zero positive evidence)
DEDUPLICATION / SESSION ISOLATION / WORLD-EPOCH SEGMENTATION / WINDOWS / BOUNDS: PASS (content-digest identity, re-delivery → DUPLICATE; per-session ledgers; epoch segments; 30 s window; 256 events / 128 groups / 512 ids)
FALSE-EVIDENCE GATE: 32451 legitimate transitions → 0 positive events, 0 duplicates, 0 proof rejections
IMPOSSIBLE CORPUS: 3290 complete INVALIDs → 3290 events, one each; double delivery → 3290 DUPLICATE
SNAPSHOT: immutable, bounded, no verdict / level / enforcement field (Phase 10 boundary)

FORWARD DEFECTS FOUND BY THE LIVE QUALIFICATION, ROOT-CAUSED FROM THE PINNED CLIENT AND FIXED (no tolerance, no landing/crouch-wide uncertainty, no Evidence Engine semantic change):
P9-F1: server-synced sprint modifier read as the client modifier set → sprint-modifier classes (Phase 5 attribute model)
P9-F2: block states the client and this observer disagree about → BlockDisputeLedger / WORLD_INCOMPLETE (Phase 3/8 world knowledge)
P9-F3: sneak scaling one tick late (LocalPlayer.aiStep) and a resting sneaker anchored standing → start-pose sneak scaling, crouching rest anchor
P9-F4: the client does not tick its player between login/respawn and ServerboundPlayerLoaded → frozen tick ends leave the physics slice
P9-F5: 26.2 animals/mobs push the local player (ClientLevel.getPushableEntities), and bundled spawns never reached the tracker → PUSHER class pinned against the jar, bundle children mapped

REAL CLIENT LEGITIMATE (FINAL): PASS on the exact final jar 297134a6425c03959ad3342e67b1179dda52e0b5ed4262308e89001e240a99da — VPS, Paper 26.2 build 124, two real players (Emojiado vanilla 15049, iDTR Lunar 7134 incl. a death and a cross-dimension teleport): 22183 transitions / 22108 VALID / 75 UNCERTAIN / 0 INVALID / 0 positive evidence (`docs/phase9-artifacts/real-client-evidence-vps.log`)
CONTROLLED INVALID: PASS (side chain: 1 INVALID → exactly 1 event 139df203c905 [HORIZONTAL_EXCESS] excess 0.1726, complete proof; live ledgers untouched)
DUPLICATE EVIDENCE: PASS (re-delivery → DUPLICATE, still 1 event)
PAPER SMOKE: PASS on the exact final jar (≈ 14 h, clean start and stop, all dimensions saved, 0 exceptions)
BASELINE CODE = QUALIFIED CODE: VERIFIED (a `--rerun-tasks` rebuild of the plugin jar from the baseline working tree is byte-identical to the live-qualified jar 297134a6…)

JMH: re-measured on the final code (`docs/phase9-artifacts/benchmarks.json`, `validation-benchmarks.json`); evidence-only kernels unchanged; end-to-end validation slower after P9-F3 (both end poses carried; exact client semantics), worst bounded ≈ 2.1 ms
ARTIFACTS: `docs/phase9-artifacts/` incl. forensic captures 1, 3–9, local and VPS qualification logs (player IPs redacted, RCON housekeeping filtered, secret scan clean), `entity-push-classes-26.2.json`, `verification.json`, `baseline-manifest.sha256`

PHASE 0 DRIFT: NONE
PHASE 0–8 HISTORICAL BASELINES: UNCHANGED (all baseline and receipt commits are ancestors of the parent; no amend, no rebase, no squash; frozen docs and artifacts untouched)
PUSH / TAG: NONE
PHASE 10: NOT STARTED (no mitigation intent, setback, alert, kick, ban, punishment, threshold or level anywhere in production code)
