# Phase 0 baseline exception

STATUS: DEFERRED REVIEW-ARTIFACT RECONCILIATION

HUMAN AUTHORIZATION: APPROVED TO BASELINE WITH DEFERRED RECONCILIATION

APPROVED SPECIFICATION MANIFEST: `1a1b987df59ef9fd411528296c2150f2b4fbbb3417ec36cddd286f1c65f64155`

REVIEW #2 RECORDED DIFF SIZE: 221770 bytes

OBSERVED RAW DIFF SIZE: 223534 bytes

OBSERVED LF-NORMALIZED SIZE: 221814 bytes

KNOWN SPECIFICATION DRIFT: NONE DETECTED

REVIEW #1: PRESERVED

REVIEW #2: PRESERVED

RECONCILIATION OWNER: Senior Architecture Reviewer / Claude when available

The discrepancy remains **unresolved**. The human project owner explicitly authorized the local Phase 0 baseline despite this deferred review-metadata issue because the authoritative 31-file specification manifest matches, no specification drift has been identified, and both reviews remain preserved. Normalized comparison content matches independent regeneration; the review's recorded byte-size claim has not been reproduced. No root cause is asserted.

This exception permits neither specification drift nor modification of existing review artifacts. It is separate metadata outside the approved specification composite. No approved specification, review, or comparison artifact is changed by this exception.

Claude must reconcile this discrepancy when available. If that investigation discovers specification drift or an invalid Review #2 conclusion, affected implementation work must pause and the relevant architecture and implementation must be reassessed. The discrepancy must not be represented as resolved before reconciliation.

The baseline task authorizes local commits only, with no push, tag, or Phase 1 implementation. Phase 1 eligibility still requires exact approved specification bytes, a verified baseline commit and a separate revision receipt.

## Other tracked findings

- NEW-M1 remains open for Phase 9, before Phase 10 effects: name the capsule prehistory source and its session-count dependence, and specify the session counts at which TC25's durability threshold must hold. See [Review #2](PHASE_0_SENIOR_REVIEW_2.md#new-findings).
- NEW-L1 remains open for the Phase 1 architecture check: name the clearance destination-region input type and declaring module; ensure the dependency-free model module introduces no dependency through its port signatures. See [Review #2](PHASE_0_SENIOR_REVIEW_2.md#new-findings).
