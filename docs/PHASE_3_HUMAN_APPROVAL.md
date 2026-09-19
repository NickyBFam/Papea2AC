# Phase 3 human approval record

Date: 2026-09-19.

The human project owner reviewed `docs/PHASE_3_IMPLEMENTATION.md` (implementation report, final qualification pass,
real-client world qualification and the final pre-baseline decisions) together with its verification artifacts under
`docs/phase3-artifacts/`, performed the real-client qualification session personally, and authorized creation of the
Phase 3 implementation baseline. This record is written by the implementation engineer at the owner's instruction;
the engineer does not self-approve. No independent Senior Code Review is claimed - the current project workflow does
not require one.

```
PHASE:
Phase 3 - World Reconstruction

STATUS:
HUMAN APPROVED FOR BASELINE

IMPLEMENTATION ENGINEER:
Claude

PARENT COMMIT:
a95169e861a843e868f58c7b3a87ed050f14004a

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

TESTS:
test 353
nativeTest 115
unique total 468
0 failed
0 skipped

PHASE 1 COVERAGE:
127/127

PHASE 1 GAPS:
0

OBSERVER WORLD MODEL:
PASS

REAL CLIENT WORLD QUALIFICATION:
PASS (Minecraft Java 26.2 client, Paper 26.2 build 124, 2026-09-19; 15/15 scenarios on the final build)

P3-C1:
RESOLVED - SCHEMA 2 (schema 1 permanently decodable; schema >= 3 rejected clearly)

P3-F3..P3-F13:
DOCUMENTED AND REQUALIFIED

P3-F7:
CONFORMANT - FLOOR EXCEEDED FOR REAL-TRAFFIC SAFETY

GLOBAL INFRASTRUCTURE POOL:
32 MiB (per session: 192 KiB guaranteed ingress slice, arena <= 4 MiB / 4096 records, bulk <= 4 MiB;
worst-case engine exposure sessions x 192 KiB + 32 MiB = ~224 MiB at 1024 sessions)

RECORDING DEGRADED AT FRAME 2:
EXPECTED SAFE LIMITATION (documented; operator re-arm path proven)

REAL-CLIENT SESSION HEALTH:
EXPECTED SAFE STATUS (HEALTHY = certified continuity, unattainable under CAPTURE-ONLY / UNSUPPORTED correlation)

HUMAN EXCEPTION REQUIRED:
NO

PRODUCTION TRANSPORT:
CAPTURE-ONLY

AUTHORITATIVE OUTCOME CORRELATION:
UNSUPPORTED

PAPER SMOKE:
PASS

PHASE 0 DRIFT:
NONE

PHASE 1 HISTORICAL BASELINE:
UNCHANGED

PHASE 1 FORWARD FIXES DISCOVERED DURING PHASE 3:
DOCUMENTED (P3-F4, P3-F5, P3-F6, P3-F7, P3-F9, P3-F10, P3-F11, P3-F12 platform part, P3-F13)

PHASE 2 HISTORICAL BASELINE:
UNCHANGED

PHASE 2 COMPATIBILITY:
PRESERVED (215/215 Phase 2 tests retained and passing; test-only shape adaptations documented)

PHASE 4:
NOT STARTED
```

## Scope of this approval

Approval covers the Phase 3 scope as documented: the observer world reconstructed from clientbound traffic only
(`anticheat-world`: dimension/world epochs, chunk baselines with decoded section content, single/multi-block updates,
unloads, bounded block-entity summaries, per-chunk coverage model with explicit UNLOADED/EVICTED/CONTENT_NOT_RETAINED/
TRANSPORT_INCOMPLETE provenance and recovery only through qualifying full baselines, copy-on-write immutable sections
with known-masks, revision model, bounded exact history, content-addressed shared section store with per-observer
private tier and global accounting, replay projection `WorldCapsule`/`WorldReplayMapper`), the core wiring into the
single ordered ingestion point, the `/papea2ac world` diagnostic, the P3-C1 schema-2 migration of the transport
capsule/payload codec, and the eleven forward corrections found by the real-client qualification (P3-F3..P3-F13),
including the engine-wide `InfrastructurePool`. It does not approve any collision, physics, movement-validation,
check, setback or punishment behavior - none exists.

## Known limitations carried into the baseline (all explicit, see PHASE_3_IMPLEMENTATION.md)

- The 96 MiB shared world partition bounds distinct chunk content server-wide (~4 700 realistic chunks); beyond it
  baselines are admitted as CONTENT_NOT_RETAINED.
- Chunk payloads above the 40 KiB inline cap are CONTENT_NOT_RETAINED; light/fluid/piston semantics are not modelled
  (Phase 4+); block entities are summaries only.
- Under CAPTURE-ONLY / UNSUPPORTED correlation the Phase 1 session health is never HEALTHY and the session-start
  recording terminates at its first barrier; both are the designed fail-closed semantics and do not affect world
  coverage.
- Legacy (schema-1) recordings reconstruct no world (no dimension range was recorded).
