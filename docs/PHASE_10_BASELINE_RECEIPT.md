# Phase 10 — Baseline Receipt

PHASE: Phase 10 — Mitigation / Setback
STATUS: HUMAN-APPROVED IMPLEMENTATION BASELINE (under the approved P10-F1 acceptance adjustment)
PARENT: d3ab7dee7721c4f19739e0f033fb5d27a03958d9 (Phase 9 baseline receipt)
PHASE 9 IMPLEMENTATION BASELINE: 594c0b1d78d2e692f1ddb7f06951ddf722657fef
PHASE 10 IMPLEMENTATION BASELINE: 71f4414c93b0946344c48863f5953077fea54e14
PHASE 10 MANIFEST: docs/phase10-artifacts/baseline-manifest.sha256. It covers 57 files, hashed with SHA-256 over committed Git blob content
in LC_ALL=C path order. Two independent implementations verified it: against the index before the commit (twice), and against the
committed blobs after it (57/57).
PHASE 10 MANIFEST COMPOSITE: acdbf47a59eac1bc9638fce8054c85a08e6cc7b52999d61b1e3c37d474379f05
FINAL QUALIFIED JAR SHA256: 46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd. A `--rerun-tasks` rebuild of the committed
baseline tree is byte-identical to it.

BUILD: PASS (`clean test --rerun-tasks`, `nativeTest --rerun-tasks`, verifyMandatoryCoverage / replayConformance / movementPropertyCoverage
/ shapeCoverage with pinned digests reproduced, pluginJar, physicsNativeDifferential)
TESTS: test 767 / nativeTest 138 / unique 885 / 0 failures
PHASE 1: 127/127 semantic coverage. Transport CAPTURE-ONLY; authoritative downstream correlation/certification UNSUPPORTED (unchanged).
PHASE 1–9 REGRESSION: NONE.
- Physics native differential 88/88 exact.
- Phase 8 legitimate corpus 32451 / 0 INVALID; impossible corpus 3290 / 3290.
- Phase 9 false evidence 0.
- Timeline, world, collision, reconstruction, timing, validation and evidence fixtures regenerate identically.

MITIGATION POLICY: movement-setback-v1 (DECISION 1).
- A single EvidenceEvent is insufficient.
- Four consecutive independent Phase 9 correlation groups of one evidence family are required, with the same SessionKey and world epoch,
  inside the 30 s window, all complete.
- VALID / UNCERTAIN / incomplete observations, epoch changes and corrections reset the chain.
- No VL, weighting or severity shortcut.
SINGLE EVIDENCE EVENT CAUSES SETBACK: NO
LEGITIMATE CORPUS: 32451 transitions -> 0 eligible, 0 intents, 0 setbacks
FINAL PHASE 9 LIVE TRACE (count level): 22108 VALID + 75 UNCERTAIN -> 0 setbacks
OFFLINE CONTROLLED CORRECTION PIPELINE: PASS (exactly one correction, correct target, no duplicate, confirmation, re-anchor, RECOVERED; correction loop 0)
PROPERTY / METAMORPHIC TESTS: PASS
TC25: PASS at the configured capacity of 32 sessions. STRESS and G2_MAX, with sparse movement, dense combat and chunk churn, were all 100 %
durable within 400 ms, and closure was verified on every capsule (docs/phase10-artifacts/tc25-results.json).
MEMORY: PASS (empty actor 3640 B, full bounded 46976 B; docs/phase10-artifacts/memory-results.json)
PERFORMANCE: PASS (JMH 1.37, 3 forks: no-op 195 ns/op, eligible 4-group cycle 30.7 µs, worst bounded step 11.2 µs; docs/phase10-artifacts/benchmarks.json)

LIVE QUALIFICATION: VPS ONLY. Owner's VPS, Paper 26.2 build 124, online mode, remote vanilla clients over the internet. Localhost served only
as an early plugin-load smoke and does not count as live qualification.
FINAL VPS LEGITIMATE QUALIFICATION: PASS.
- iDTR, jar a464ad44…: 16484 transitions, covering the full movement list, animals, knockback, TNT explosion, teleports, death/respawn and
  the Nether.
- The exact-final-jar recheck below adds 1620.
- Total 18104 legitimate transitions / 0 INVALID / 0 positive evidence / 0 setbacks.
EXACT-FINAL-JAR VPS RECHECK: PASS.
- Jar 46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd, OBSERVE_ONLY, no qualification flag, client Emojiado.
- 1620 transitions, all VALID; 0 INVALID; 0 positive evidence; 0 setbacks.
- P10-F2 suspend/resume PASS live. ENFORCE without the qualification flag REFUSED.
LEGITIMATE LIVE INVALID: 0
LEGITIMATE LIVE POSITIVE EVIDENCE: 0
LEGITIMATE LIVE SETBACKS: 0
REAL CONTROLLED SETBACK: EXTERNAL QUALIFICATION REQUIRED. It is blocked by G1 because production transport correlation/certification is
UNSUPPORTED. Live, 4/4 independent HORIZONTAL_EXCESS groups made the policy eligible, and the result was SUPPRESSED TRANSPORT_UNHEALTHY: no
proposal, no capsule, no intent, no teleport, no real effect.
STRAY CONNECTION: one unknown client (luckysystems) reached only the pre-login stage while the temporary port was open. It is not a
qualification client and is not counted.
QUALIFICATION LOGS: 6 files force-added under docs/phase10-artifacts/ (VPS evidence and server logs, and the local plugin-load smoke log).
Player IPs are redacted; the RCON-password / credential / key scan is clean.
VPS AFTER QUALIFICATION: the Minecraft server is stopped and the temporary ufw 25565 rule removed; nothing listens on 25565/25575.
Unrelated services are untouched.

P10-F1: ACCEPTED (human decision). G1's effect-admission gate is intentionally unreachable on the capture-only production transport. It is a
cross-phase capability blocker (a Phase 1 transport-certification dependency), not a false mitigation result.
- G1 is unchanged, and there is no bypass.
- Phase 10 ships OBSERVE_ONLY.
- The real controlled setback remains EXTERNAL QUALIFICATION REQUIRED until an authoritative transport correlation/certification capability
  exists.
- No code change.
P10-F2: DOCUMENTED / FIXED / TESTED / LIVE RECHECKED. `/papea2ac effects resume` from qualification ENFORCE now restricts the arbiter first.
- Regression: MitigationApplicationTest.resumingFromQualificationEnforceRestrictsTheArbiterAndTheSessionsTogether.
- Verified on the VPS, both flagged and in OBSERVE_ONLY with the player online.

PRODUCTION EFFECT MODE: OBSERVE_ONLY. ENFORCE is not reachable from configuration. Qualification ENFORCE requires a JVM flag plus a TC25 pass
at the configured capacity.

PHASE 0 DRIFT: NONE
PHASE 0–9 HISTORICAL BASELINES: UNCHANGED. All 20 baseline/receipt commits are ancestors, with no amend, rebase, squash or rewrite. The
frozen docs and docs/phase0..9-artifacts are untouched.
PUSH / TAG: NONE
PHASE 11: NOT STARTED

KNOWN FORWARD ITEMS:

- An authoritative transport correlation/certification capability satisfying G1 is required before any real effect can be admitted. The
  live controlled setback waits for it (P10-F1).
- Under movement-setback-v1, live-chain setbacks mostly resolve RESYNC_REQUIRED (the anchor is older than 2 s) — conservative; a human policy
  decision if changed.
- Decision capsules are transport-scope (no domain-state closure before the checkpoint). Their prehistory shrinks with session count and
  bulk traffic (NEW-M1 measured in TC25).
- Windows cannot force directory entries (NTFS journaling); POSIX forces them.
- Latency was qualified only on a real remote network (no artificial injection).
