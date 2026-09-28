# Phase 10 — Human Approval Record

PHASE: Phase 10 — Mitigation / Setback
STATUS: HUMAN APPROVED FOR BASELINE. Owner authorization was received on 2026-09-27, after the implementation report, the P10-F1 decision
and the exact-final-jar VPS recheck: "PHASE 10 IS APPROVED FOR BASELINE PREPARATION". It applies the previously approved P10-F1 acceptance
adjustment.
PARENT: d3ab7dee7721c4f19739e0f033fb5d27a03958d9 (Phase 9 baseline receipt)
PHASE 9 IMPLEMENTATION BASELINE: 594c0b1d78d2e692f1ddb7f06951ddf722657fef (unchanged in history)
FINAL QUALIFIED JAR: 46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd. A `--rerun-tasks` rebuild of the baseline tree is
byte-identical to it.

TESTS: test 767 / nativeTest 138 / unique 885 / 0 failures (final lane, rerun after the P10-F2 fix)
PHASE 1: 127/127 semantic coverage. Transport is CAPTURE-ONLY, and authoritative downstream correlation/certification is UNSUPPORTED
(unchanged).
PHASE 1–9 REGRESSION: NONE.
- Phase 8 legitimate corpus: 32 451 / 0 INVALID.
- Impossible corpus: 3 290 / 3 290.
- Phase 9 false evidence: 0.
- Physics differential: 88/88 exact.
- The frozen Phase 0–9 documents, artifacts, baselines and receipts are untouched.

## Policy — movement-setback-v1 (DECISION 1)

- **A single Phase 9 EvidenceEvent is insufficient.** One complete INVALID is one event, never a setback. 1, 2 or 3 groups are
  NOT_ELIGIBLE INSUFFICIENT_EVIDENCE.
- **Eligibility requires four independent Phase 9 correlation groups**, consecutive since the last reset, which must all:
  - share one existing positive evidence family (`EvidenceVocabulary.Category`);
  - belong to the same SessionKey and the same world epoch;
  - fall inside the 30 s window;
  - be complete/actionable under the frozen Phase 9 rules.
- **Counting:** duplicates never count, and each group contributes only its largest member. There is no VL, weighting, confidence or
  severity shortcut.
- **Resets:** any VALID, UNCERTAIN or incomplete observation between qualifying groups resets the chain. So do an epoch change and a
  correction. Different families never combine.
- **Eligibility is not an action.** A fixed safety decision order follows it (session, epoch, transport health, teleports, external motion,
  locomotion, certified target, 2 s age, 8-block distance, dispute, entities, re-certified clearance, idempotence, mode, the G2 proposal
  guard). ENFORCE also requires a durable decision capsule before any intent reaches the main thread.

## P10-F1 acceptance adjustment

**The decision.**
- G1 remains unchanged: effect admission requires a HEALTHY transport latch.
- Production transport is CAPTURE-ONLY, with authoritative outcome correlation/certification UNSUPPORTED. Every real session is therefore
  `EXPIRED_OBSERVE_ONLY`, and G1 correctly prevents real effect admission.
- There is no bypass, no qualification-only or debug exception, no reinterpretation of EXPIRED_OBSERVE_ONLY as HEALTHY, and no production
  ENFORCE.

**The live result**, accepted as correct behaviour:
- 4/4 independent HORIZONTAL_EXCESS side-chain groups made movement-setback-v1 evidence-eligible.
- G1 then produced SUPPRESSED TRANSPORT_UNHEALTHY, with no proposal, no capsule for a correction intent, no setback intent, no teleport and
  no real effect.

**Phase 10 ships OBSERVE_ONLY.** The real controlled setback remains EXTERNAL QUALIFICATION REQUIRED until an authoritative transport
correlation/certification capability satisfying G1 exists. The qualification requirement, and not G1, was waived for READY. The offline
correction pipeline is qualified: exactly one correction, correct target, no duplicate, confirmation, re-anchor, RECOVERED, no loop.

## P10-F2 accepted

`/papea2ac effects resume` could throw and leave the main-thread arbiter in qualification ENFORCE while the sessions were told
OBSERVE_ONLY. The fix makes the arbiter transition first, so resume from ENFORCE uses `restrict(OBSERVE_ONLY)`.

- **Regression:** `MitigationApplicationTest.resumingFromQualificationEnforceRestrictsTheArbiterAndTheSessionsTogether`.
- **Lane:** the full verification lane is green after the fix.
- **Live:** verified on the VPS, both flagged (ENFORCE → resume, 0 exceptions) and in OBSERVE_ONLY with the player online (suspend → resume
  ×2, 0 exceptions).

## Qualification

TC25: PASS at the configured capacity of 32 sessions. STRESS and G2_MAX, with sparse movement, dense combat and chunk churn, were all 100 %
durable within 400 ms, and closure was verified on every capsule.

LIVE QUALIFICATION IS VPS-ONLY (owner decision, 2026-09-27). It ran on the owner's VPS: Paper 26.2 build 124, online mode, remote vanilla
clients over the internet. Localhost was used only for an early plugin-load smoke, which does not count as live qualification.

REAL CLIENT LEGITIMATE: PASS. Across both Phase 10 jars: 18 104 transitions, 0 INVALID, 0 positive evidence, 0 setbacks.
- `a464ad44…`, iDTR: 16 484 transitions, including the full movement list, animals, knockback, TNT explosion, teleports, death/respawn and
  the Nether.
- Remote RTT 37–178 ms.

EXACT-FINAL-JAR VPS RECHECK: PASS.
- Jar 46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd, OBSERVE_ONLY, no qualification flag, client Emojiado.
- 1 620 transitions, all VALID; 0 INVALID; 0 positive evidence; 0 setbacks.
- P10-F2 suspend/resume: PASS live. ENFORCE without the qualification flag: REFUSED as designed.

Stray connection: one unknown client (`luckysystems`) reached only the pre-login stage and disconnected while the temporary port was open.
It is not a qualification client and is not counted.

VPS cleanup: the Minecraft server is stopped, the temporary ufw 25565 rule is removed, and nothing listens on 25565/25575. Unrelated
services are untouched.

MEMORY / JMH: PASS.
- Memory: empty actor 3 640 B, full bounded 46 976 B.
- JMH: no-op 195 ns/op, eligible cycle 30.7 µs, worst bounded step 11.2 µs.

PRODUCTION EFFECT MODE: OBSERVE_ONLY. ENFORCE is not reachable from configuration. Qualification ENFORCE requires a JVM flag plus a passing
TC25 result at the configured capacity.

PHASE 0 DRIFT: NONE
PHASE 0–9 HISTORICAL BASELINES: UNCHANGED
PUSH / TAG: NONE
PHASE 11: CLOSED (not started; no combat checks, kicks, bans, punishments or staff enforcement anywhere)
