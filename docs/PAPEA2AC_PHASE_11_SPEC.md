# PHASE 11 — COMBAT ENGINE — IMPLEMENTATION START

You are the Lead Implementation Engineer for:

Papea2AC — PHASE 11: COMBAT ENGINE

Phase 10 is COMPLETE, HUMAN-APPROVED, BASELINED, RECEIPTED, and PUSHED.

Phase 11 is now OPEN.

Do NOT begin Phase 12.
Do NOT implement reach validation.
Do NOT implement hitbox/ray-trace verdicts.
Do NOT implement KillAura heuristics.
Do NOT implement click analysis.
Do NOT implement punishment/mitigation behavior from combat observations.
Do NOT rewrite frozen Phase 0–10 history.
Do NOT commit, baseline, push, or tag during this task.

Repository:

C:\Users\Nicolas\Documents\GitHub\Papea2AC

## APPROVED STARTING BASELINE

Phase 10 implementation baseline:

`71f4414c93b0946344c48863f5953077fea54e14`

Phase 10 receipt / expected HEAD:

`59caec5a0b4bde0c13dadca1807c0b0f3f039a7d`

Phase 10 manifest composite:

`acdbf47a59eac1bc9638fce8054c85a08e6cc7b52999d61b1e3c37d474379f05`

Phase 10 final qualified jar:

`46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd`

Expected:

- HEAD: `59caec5a0b4bde0c13dadca1807c0b0f3f039a7d`
- origin/master: `59caec5a0b4bde0c13dadca1807c0b0f3f039a7d`
- working tree: CLEAN

Production effect mode remains OBSERVE_ONLY.

P10-F1 remains frozen: production transport is CAPTURE-ONLY and authoritative downstream correlation/certification is UNSUPPORTED.

## FIRST ACTION

Run:

```text
git rev-parse HEAD
git status --short
git log --oneline -6
git rev-parse origin/master
```

If HEAD or origin/master is not the Phase 10 receipt above, or if the working tree is not clean: STOP. Do not modify anything until the discrepancy is reported.

## PHASE 11 OBJECTIVE

Build the deterministic combat-domain infrastructure required by later combat checks.

Phase 11 answers questions such as:

- What combat packet/action did the client OBSERVE us receiving?
- Which network entity did that packet refer to?
- What client-observed target state was available at that point?
- What attacker state and timing provenance correspond to the observation?
- Was target history complete enough to query?
- Did entity identity survive spawn/despawn/id-reuse correctly?
- Can the same combat observation be reproduced offline?

Phase 11 DOES NOT answer:

- Was the hit within legal reach?
- Did the ray intersect the hitbox?
- Was the player cheating?
- Was the attack KillAura?
- Was the click rate suspicious?
- Should evidence be created?
- Should mitigation occur?

Conceptually:

Phase 1 ordered packet observations + Phase 2 attacker/player timeline + Phase 3 client-observed world + Phase 7 timing/latency provenance -> Phase 11 combat observation + target-state reconstruction -> Phase 12 reach / hitbox / historical ray trace.

## CORE TRUTH INVARIANT

The following MUST remain structurally impossible to confuse:

ATTACK PACKET OBSERVED != SERVER ACCEPTED ATTACK != TARGET HIT != DAMAGE APPLIED != CLIENT CONFIRMED RESULT

Production transport is CAPTURE-ONLY.
Authoritative downstream packet outcome correlation remains UNSUPPORTED.

Do not infer:

- observed attack packet => server processed attack
- observed attack packet => damage occurred
- hurt animation => this attack caused damage
- velocity => this attack caused knockback
- target missing later => target was killed by this attack
- timeout => attack accepted

Every combat object must preserve observation/provenance semantics.

## READ FIRST

Read at minimum:

- README.md
- docs/ARCHITECTURE.md
- docs/PACKET_PIPELINE.md
- docs/THREADING_MODEL.md
- docs/PLAYER_STATE.md
- docs/PLAYER_TIMELINE.md
- docs/WORLD_MODEL.md
- docs/COLLISION_ENGINE.md
- docs/MOVEMENT_SIMULATION.md
- docs/INPUT_RECONSTRUCTION.md
- docs/LATENCY_COMPENSATION.md
- docs/COMBAT_MODEL.md
- docs/EVIDENCE_MODEL.md
- docs/MITIGATION.md
- docs/REPLAY_SYSTEM.md
- docs/PERFORMANCE.md
- docs/TESTING.md
- docs/VERSION_SUPPORT.md
- docs/AI_DEVELOPMENT_RULES.md
- docs/ROADMAP.md
- docs/PHASE_8_IMPLEMENTATION.md
- docs/PHASE_9_IMPLEMENTATION.md
- docs/PHASE_10_IMPLEMENTATION.md
- docs/PHASE_10_HUMAN_APPROVAL.md
- docs/PHASE_10_BASELINE_RECEIPT.md

Also inspect:

- anticheat-combat
- anticheat-model
- anticheat-protocol
- anticheat-packet
- anticheat-player
- anticheat-world
- anticheat-replay-schema
- anticheat-core
- anticheat-platform-paper
- anticheat-testing

The frozen repository specification is authoritative. If this prompt conflicts with an already-approved exact contract in the repo, the frozen spec wins. If an exact decision required for sound implementation is genuinely absent, STOP and report the missing decision. Do not invent a magic threshold or semantic rule.

## 1. MODULE OWNER

Primary owner: `anticheat-combat`.

Keep combat-domain logic Paper-free. No Bukkit, Paper, NMS, Netty, or reflection inside anticheat-combat.

Use only DAG-approved dependencies. Do not create upward dependencies from player/world/collision/physics/simulation into combat. Do not make combat depend on checks/evidence/mitigation unless the frozen architecture explicitly says otherwise. Core composes. platform-paper displays/integrates only.

## 2. PHASE BOUNDARY

Phase 11 MAY implement:

- combat packet normalization/domain mapping
- attack observation records
- target entity identity tracking
- client-observed combat entity state
- spawn/despawn lifecycle
- entity-ID reuse protection
- position/rotation/pose metadata needed as raw historical facts
- historical combat-target state lookup
- attack-to-target state correlation
- timing provenance
- completeness/uncertainty states
- replay projection
- bounded combat history
- diagnostics
- performance/memory qualification

Phase 11 MUST NOT implement:

- reach limits/verdicts
- eye-to-hitbox distance checks
- hitbox intersection verdicts
- ray tracing
- through-wall verdicts
- attack-angle heuristics
- aim heuristics
- rotation analysis
- KillAura heuristics
- multi-target aura heuristics
- click-pattern analysis
- CPS scoring
- auto-click detection
- combat EvidenceEvents
- combat setback
- kick/ban/punishment score/staff conviction labels

## 3. COMBAT ENGINE IS DESCRIPTIVE

The Phase 11 engine produces descriptive immutable state. It must not expose cheat verdict booleans such as isReachHack/isAura/isInvalidHit/isSuspicious/shouldPunish/shouldSetback.

Expected concepts should resemble the frozen spec, e.g. CombatObservation, AttackObservation, ObservedEntityKey, CombatEntityState, CombatTargetLookup, CombatContext, CombatCompleteness, CombatHistory. Use repository terminology if already frozen.

## 4. ATTACK PACKET SEMANTICS

Identify the exact Minecraft/Paper pinned protocol path for combat interactions from locally pinned version sources/oracle. Do not rely on memory or an online packet table as authority.

Differentiate ATTACK, INTERACT, and INTERACT_AT where applicable. Do not treat every entity interaction as an attack. Preserve only actual version-defined fields; do not invent hand/sequence/hit vector if ATTACK does not contain them. Document the exact wire/domain mapping.

## 5. ATTACK OBSERVATION IDENTITY

Every attack observation must have stable deterministic identity/provenance including enough information for session, capture/world epoch, ordered transport sequence, normalized packet/action, target network entity ID, combat-entity generation if known, attacker timeline reference, observation monotonic/tick provenance, timing status, and completeness. No wall-clock identity.

## 6. ENTITY ID REUSE

A numeric network entity ID is not globally permanent. Explicitly model generation/lifecycle identity so remove+respawn of the same numeric ID cannot bind old history to the new entity.

## 7. SESSION AND WORLD-EPOCH ISOLATION

Combat history must never cross SessionKey, world/dimension epoch, or capture discontinuity. Disconnect/reconnect, epoch transitions, and session replacement invalidate incompatible state.

## 8. CLIENT-OBSERVED ENTITY STATE

SERVER ENTITY STATE != CLIENT-OBSERVED ENTITY STATE.

Do not query live Bukkit/Paper entity position and call it historical truth. Reconstruct from ordered packet observations the client could have received. Any current live Bukkit information used only for diagnostics/display must remain outside combat-domain truth and be labeled accordingly.

## 9. ENTITY LIFECYCLE

Model only exact version-specific outbound entity lifecycle/state packets required by the frozen combat model, including as applicable spawn/add, removal, absolute teleport, relative movement, rotation, move+rotation, head rotation if materially required, metadata/pose, and version-specific reset semantics. Use pinned local version implementation as oracle.

## 10. RELATIVE MOVEMENT FIDELITY

Match actual vanilla client/server numeric encoding and application. Do not approximate quantization. Add native/differential tests for encode/decode, accumulation, positive/negative deltas, boundaries, teleport reset, post-teleport movement, rotation-only, and move+rotation. Preserve exact narrowing/scales.

## 11. NETWORK STATE VS RENDER INTERPOLATION

Do not silently equate packet-applied target state with fully reconstructed render/interpolation position. If Phase 11 owns only network-observed state, model exactly that and defer interpolation. If COMBAT_MODEL.md explicitly assigns interpolation to Phase 11, implement exact frozen rules and qualify against the client oracle.

## 12. TARGET METADATA

Track only metadata required by the frozen combat model, such as raw entity type, pose, living/non-living classification, packet-observed UUID, and metadata revision where applicable. Do not build Phase 12 hitboxes yet.

## 13. UNKNOWN IS FIRST CLASS

Combat history must explicitly distinguish outcomes such as FOUND, UNKNOWN, PRE_SPAWN, DESPAWNED, EVICTED, ID_REUSED, WORLD_EPOCH_MISMATCH, SESSION_MISMATCH, FUTURE_SEQUENCE, INCOMPLETE_COVERAGE, UNSUPPORTED, QUERY_LIMIT using frozen names where available.

UNKNOWN_ENTITY must never become entity absent unless absence is actually known. Missing target history must never fall back to current target position.

## 14. NO NEAREST-MATCH LIES

If exact historical state is unavailable, do not substitute nearest earlier/later position and call it exact. Any explicitly permitted bounded fallback must be typed non-exact with provenance. Otherwise return incomplete/unavailable.

## 15. ORDERING

Use existing Phase 1 ordered session ingress. No wall-clock ordering. Correctly distinguish update N then attack N+1 from attack N then update N+1.

## 16. TRANSPORT GAPS

Combat completeness degrades across relevant drops, truncation, shard failure, transport discontinuity, coverage gap, unrecoverable oversize, or session discontinuity. Do not treat benign control-plane observations as target-state breaks merely because they are not target packets.

## 17. TARGET LOOKUP API

Provide deterministic current/historical lookup returning status, entity identity/generation, state if available, exact provenance, completeness, source/update sequence, world epoch, and relevant revision/version info. No Bukkit objects or mutable live references.

## 18. ATTACK CONTEXT

For each ATTACK observation, construct immutable context sufficient for Phase 12: attack observation, attacker session/epoch, attacker historical state result/reference, target identity, target historical/network state result, timing provenance, completeness, and version/profile information. No reach verdict.

## 19. ATTACKER STATE

Attacker movement/rotation comes from existing PlayerTimeline. Do not duplicate Phase 2. Missing exact attacker history must remain incomplete. No current Bukkit-location fallback.

## 20. TIMING

Integrate Phase 7 timing semantics only as descriptive provenance. Do not turn RTT into reach allowance or invent lag compensation distance.

## 21. OBSERVED OUTCOME SIGNALS

Any later hurt animation/damage/velocity/entity-status/removal observation remains observational and not automatically causal to a prior attack unless the model can prove causality. Do not add causal correlation merely for convenience.

## 22. ATTACK COOLDOWN / CADENCE

Phase 11 may record timing facts required later but must not classify cadence as cheating. No CPS thresholds/autoclick scoring/timing regularity heuristics.

## 23. COMBAT HISTORY

Combat history must be bounded, deterministic, session-scoped, and epoch-safe. Prefer established structural-sharing/ring-buffer patterns. Use frozen limits. If a required exact bound is absent, STOP and report rather than inventing one.

## 24. ENTITY CAPACITY / SHEDDING

Hostile traffic must not create unbounded tracked entities. Use frozen budget/resource model. Bound failures degrade completeness explicitly; silent eviction must not later become “absent.”

## 25. EVICTION

Eviction is deterministic. Historical lookup for evicted state returns explicit eviction outcome. No resurrection from current server truth. No nearest-state fallback.

## 26. DUPLICATES

Handle duplicate/replayed observations deterministically according to existing packet/session semantics. Duplicate spawn/remove/move/attack must not fabricate generations or certainty.

## 27. MALFORMED / IMPOSSIBLE INPUT

Malformed combat packets/impossible normalized values must not crash shard/server. Contain and classify them. Parser failure is not combat guilt.

## 28. REPLAY SCHEMA

Combat state/observations must be replayable. If replay schema changes incompatibly, increment schema; preserve backward decode; prove OLD->NEW; prove NEW->OLD clean rejection where applicable; reject future schema cleanly; preserve Phase 1–10 fixtures. Do not repeat the Phase 3 schema incident.

## 29. REPLAY DETERMINISM

Same transport observations + player timeline projection + combat/entity observations + version profile must produce identical generations, target histories, attack observations, completeness outcomes, and contexts.

## 30. NO PHASE 9 EVIDENCE

Phase 11 combat observations produce zero new positive EvidenceEvents. Do not wire combat into Evidence Engine yet.

## 31. NO PHASE 10 MITIGATION

Phase 11 never requests setback/correction/kick/ban. Phase 10 remains OBSERVE_ONLY. Combat Engine must not depend on mitigation for correctness.

## 32. NO REACH IMPLEMENTATION

Search for accidental reach logic before review. Forbidden: maxReach, reachThreshold, distance>N verdicts, rayIntersects, hitboxExpansion, through-wall verdicts, LOS attack verdict, historical reach verdict. Descriptive distance only if explicitly allowed by frozen spec and never as verdict.

## 33. NO HITBOX IMPLEMENTATION

Do not build entity AABBs for attack validity in Phase 11. Raw entity type/pose metadata may be tracked for Phase 12.

## 34. NO ROTATION HEURISTICS

Yaw/pitch may be preserved historically but do not score snap/acceleration/smoothness/GCD/aim error/angle-to-target.

## 35. NO KILLAURA HEURISTICS

No multi-target switching score, attack-without-looking verdict, impossible-target-selection heuristic, combat confidence, or bot-like targeting.

## 36. NO CLICK ANALYSIS

No CPS/variance/entropy/autocorrelation/burst scoring/butterfly/jitter classification.

## 37. THREADING

Follow THREADING_MODEL.md. Combat target state should be session/shard-owned where appropriate. No global lock on every entity movement/attack. Publish immutable snapshots/projections across thread boundaries.

## 38. ENTITY SPAWN

Test spawn->lookup, spawn->movement, spawn->attack, spawn->remove, spawn->epoch change, spawn->session end. First exact baseline must be clearly defined; insufficient initial state stays incomplete.

## 39. RELATIVE MOVE BEFORE BASELINE

Relative update without valid baseline must not fabricate zero/current server position. Remain incomplete until a qualifying baseline restores state if protocol permits.

## 40. TELEPORT

Target teleport establishes exact new absolute network state according to oracle. Pre-teleport history remains queryable only within bounded retention. Attack-before and attack-after teleport resolve differently by sequence.

## 41. DESPAWN / REMOVAL

Removal closes the correct generation. Attack against removed target must not bind to a later reused ID.

## 42. DIMENSION / WORLD CHANGE

Epoch transition invalidates incompatible entity state. No cross-dimension target history.

## 43. PLAYER TARGETS

Support target players using packet-observed combat entity state. Do not substitute server Player objects as historical truth. Preserve packet-observed UUID/profile identity where available.

## 44. NON-PLAYER TARGETS

Support relevant non-player living entities needed for qualification. Do not assume player dimensions/metadata. Raw state now; hitbox geometry later.

## 45. VEHICLES / MOUNTS

If riding/passenger relations materially alter frozen combat-model state and Phase 11 owns them, model them. Otherwise expose explicit unsupported/incomplete dimension. Do not assume unmounted geometry.

## 46. POSE

If pose metadata is needed later, preserve exact observed pose/provenance. Missing pose is UNKNOWN, never default standing.

## 47. INVISIBILITY / SPECTATOR / GAME MODE

Do not invent attack-eligibility rules. Track only what frozen combat model requires.

## 48. ENTITY INTERPOLATION / LERP

If frozen combat model requires client lerp state, separate packet target, interpolation target, and render/current interpolated state with exact provenance. Otherwise defer. Never half-implement interpolation and label exact.

## 49. VERSION PROFILE

All version-dependent combat semantics go through approved version abstraction/profile. Use repository-pinned current version as authority.

## 50. NATIVE / VANILLA ORACLE

Build combat/entity differential oracle against actual pinned vanilla client/server paths where practical; never use Moonrise shortcuts as semantic oracle. Include spawn application, relative movement, teleport, rotation, metadata/pose where owned, removal, ID reuse, attack action decoding. Report exact case counts.

## 51. PROPERTY TESTS

At minimum prove:

- same inputs => same combat output
- unknown target never becomes exact
- removing history input cannot improve completeness
- adding transport gap cannot improve completeness
- session change invalidates old target
- epoch change invalidates old target
- despawn prevents old generation reuse
- reused numeric ID creates distinct generation
- pre-spawn attack cannot resolve exact target
- evicted history cannot resolve FOUND
- current Bukkit state is never historical fallback
- ATTACK observation never implies server acceptance
- non-ATTACK interaction never becomes ATTACK
- bounds never exceeded

## 52. METAMORPHIC TESTS

Prove deleting relevant packets can only preserve/reduce certainty; inserting coverage gap cannot improve exactness; changing session/epoch invalidates prior identity; remove+respawn same numeric ID cannot preserve generation; moving an entity update from before attack to after changes historical context; duplicates cannot create extra certainty or generations.

## 53. FUZZ

Fuzz spawn/move/rotate/teleport/metadata/remove/attack/epoch/session-close sequences. Assert no crash, cross-generation/session leak, impossible exact lookup, bound violation, or determinism failure.

## 54. ATTACK CORPUS

Create deterministic fixtures for known/unknown target, before/after spawn, before/after move, before/after teleport, before/after remove, ID reuse, player, mob, stationary/moving, rotation update, pose update if owned, transport gap, history eviction, epoch change, and session replacement. Every fixture has explicit descriptive expectation and no cheat verdict.

## 55. REPLAY FIXTURES

Pin stationary target, moving target, teleporting target, despawn/reuse, player, mob, gap/incomplete, and epoch-transition traces. Replay twice must be identical.

## 56. MEMORY

Measure empty combat session, typical registry, typical PvP session, full bounded registry, full bounded historical state, attack-history storage, replay projection overhead. No unbounded per-entity history. Report per-session and global/shared retained memory separately.

## 57. PERFORMANCE

Measure attack ingest known/unknown target, current/historical lookup, relative move, teleport, spawn, despawn, ID reuse, full bounded registry, mixed combat stream, and no-combat path. Combat infrastructure must not materially slow ordinary movement with no combat.

## 58. JMH

Use established methodology unless PERFORMANCE.md changed it: JMH 1.37, Temurin 25, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc. Report operation latency, allocation, and conditions. No unsupported server-capacity claims.

## 59. METRICS

Fixed-cardinality only. Possible counters: attacks observed, non-attack interactions, target lookup found/incomplete, unknown target, generations, removals, history evictions, query limits, replay failures. No UUID/entity ID/player/chunk/session labels.

## 60. DIAGNOSTIC COMMAND

Add bounded `/papea2ac combat <player>` style diagnostics showing session/epoch, combat status, recent attacks, target network ID/generation, lookup status, attacker timeline status, target provenance, timing, completeness reasons, history usage/bounds. No CHEATER/REACH HACK/KILLAURA/BAN/PUNISH/confidence score.

## 61. FORENSIC DEBUGGING

Any one-shot forensic capture must be explicitly armed, bounded, diagnostic-only, off by default, and non-semantic. No unbounded combat packet dumps.

## 62. PAPER SMOKE

Before live qualification: build exact candidate jar, run Paper smoke, verify plugin load, combat initialization, diagnostic command, ordinary joins/leaves, no exceptions, zero Phase 9 evidence from combat, zero Phase 10 effects from combat.

## 63. REAL-CLIENT QUALIFICATION ENVIRONMENT

ALL real-client/live gameplay qualification must be performed on the VPS. Local machine may run unit/native/replay/fuzz/JMH/memory/static analysis/local plugin-load smoke. Localhost gameplay never satisfies live acceptance.

## 64. VPS SAFETY

Use secure VPS workflow. Open only Minecraft port required. Do not expose RCON publicly. Do not alter unrelated services. Redact IPs/credentials/RCON/SSH/access tokens from committed logs. After qualification stop Minecraft server, remove temporary firewall rule, verify Minecraft/RCON ports no longer exposed, leave unrelated services untouched.

## 65. REAL-CLIENT TRACK A — LEGITIMATE COMBAT OBSERVATION

Exact final candidate jar on VPS. Exercise stationary/moving mob attacks, sprint/jump/crouch/diagonal attacks, target move before/after attack, target teleport if practical, target despawn/death if practical, multiple entities, repeated attacks, and non-attack interaction where possible. If second player available also PvP moving/stationary combinations. Expected: correct reconstruction, generations correct, no cross-target leakage, no exceptions, 0 combat evidence, 0 mitigation actions.

## 66. REAL-CLIENT TRACK B — ENTITY ID / LIFECYCLE

Exercise spawn/movement/removal/new targets/epoch-session cleanup. If deterministic numeric entity-ID reuse cannot safely be forced live, keep proof in deterministic/native integration tests and say live reuse not forced.

## 67. REAL-CLIENT TRACK C — WORLD/DIMENSION

Exercise dimension/world transition if practical. Verify old targets unavailable in new epoch, new registry clean per spec, no cross-world attack context, no exceptions.

## 68. REAL-CLIENT TRACK D — REMOTE NETWORK

Record observed remote RTT/jitter. Do not claim artificial latency unless actually injected. Remote uncertainty must not create false exactness.

## 69. LIVE ACCEPTANCE COUNTS

Record attacks, non-attack interactions, target lookup FOUND, incomplete/unknown, generations, removals, epoch resets, exceptions, positive evidence, and mitigation/effect count. Legitimate Phase 11 live qualification requires 0 positive evidence and 0 mitigation effects.

## 70. PHASE 10 REGRESSION

Preserve OBSERVE_ONLY, P10-F1 unchanged, G1 unchanged, P10-F2 preserved, Phase 10 corpora unchanged. Do not weaken transport certification for combat.

## 71. PHASE 9 REGRESSION

Evidence Engine unchanged. Phase 11 combat creates no positive evidence. Existing movement evidence tests remain green.

## 72. PHASE 8 REGRESSION

Frozen corpus remains 32,451 legitimate / 0 INVALID and 3,290/3,290 impossible. Do not alter movement validation for combat convenience.

## 73. PHASE 7 REGRESSION

Timing/velocity/teleport gates remain green. Combat consumes descriptive timing only and does not change Phase 7 semantics.

## 74. PHASE 5/6 REGRESSION

Physics and reconstruction remain frozen: established physics qualification 88/88, 3,105 ticks, 0 drift; reconstruction no-false-exclusion PASS. Modify only if genuine earlier-phase defect discovered.

## 75. EARLIER-PHASE DEFECT PROTOCOL

Any earlier-phase defect discovered now becomes P11-F<n>, recording origin component/phase, failure, root cause, smallest sound forward fix, regression test, qualification, and historical baseline unchanged. Production-semantic changes after live qualification stale the affected live qualification; requalify exact final jar on VPS.

## 76. PHASE 11 DEFECTS

Use P11-F<n> for Phase 11-local defects. Document every qualification-discovered issue, including those fixed before review. No silent fixes.

## 77. DOCUMENTATION

Create `docs/PHASE_11_IMPLEMENTATION.md` with at least:

SCOPE; OWNER; PHASE BOUNDARY; TRUTH MODEL; ATTACK OBSERVATION MODEL; ENTITY IDENTITY / GENERATION; ENTITY LIFECYCLE; TARGET STATE MODEL; ORDERING; TIMING PROVENANCE; COMPLETENESS MODEL; HISTORICAL LOOKUP; BOUNDS / EVICTION; REPLAY; THREADING; MEMORY; PERFORMANCE; DIAGNOSTICS; NATIVE DIFFERENTIAL; PROPERTY TESTS; METAMORPHIC TESTS; FUZZ; PAPER SMOKE; REAL-CLIENT VPS QUALIFICATION; KNOWN LIMITATIONS; DEFERRED TO PHASE 12+; DEFECTS; ACCEPTANCE MATRIX.

## 78. PHASE 11 ARTIFACTS

Create `docs/phase11-artifacts/` with stable artifacts such as verification.json, combat-corpus-results.json, entity-lifecycle-results.json, target-lookup-results.json, native-differential-results.json, replay-results.json, memory-results.json, benchmarks.json, real-client-evidence.log. Do NOT create a baseline manifest yet.

## 79. ACCEPTANCE MATRIX

Every requirement must be PASS, FAIL, EXTERNAL QUALIFICATION REQUIRED, or NOT APPLICABLE BY SPEC. No “mostly/probably/looks good.”

## 80. STATIC / ARCHITECTURE CHECKS

Prove anticheat-combat has no Paper/NMS/Netty; combat does not depend on evidence/mitigation; physics/collision/simulation/player/world do not depend upward on combat unless frozen DAG permits; Phase 12 reach classes absent; Phase 15 rotation heuristic classes absent; Phase 16 KillAura classes absent; Phase 17 click-analysis classes absent.

## 81. TODO / FIXME REVIEW

Search Phase 11 changes for TODO/FIXME/HACK/TEMP/XXX. Remove or explicitly document/accept every occurrence.

## 82. FINAL VERIFICATION

Before READY run at minimum:

```text
gradlew.bat clean test
gradlew.bat nativeTest
gradlew.bat :anticheat-testing:verifyMandatoryCoverage
gradlew.bat :anticheat-platform-paper:pluginJar
```

plus established architecture tests, Phase 5 physics gates, Phase 6 reconstruction gates, Phase 7 timing gates, Phase 8 validation corpora, Phase 9 evidence gates, Phase 10 mitigation gates, Phase 11 corpus/lifecycle/historical/property/metamorphic/fuzz/replay/memory/JMH/static analysis.

Then run `git diff --check`, inspect generated files, and perform Paper smoke + real-client qualification on the exact final candidate jar.

## 83. READY RULE

Report `PHASE 11 IMPLEMENTATION STATUS: READY FOR HUMAN REVIEW` only if:

- attack semantics exact for pinned version
- attack/non-attack distinguished
- observations immutable/deterministic
- lifecycle works
- ID reuse safe
- session/epoch isolation works
- client-observed target state never replaced by live Bukkit truth
- historical lookup honest
- unknown/incomplete first class
- relative movement fidelity qualified
- teleport/removal qualified
- replay deterministic
- memory bounded
- performance measured
- property/metamorphic/fuzz pass
- Phase 1–10 regression passes
- VPS real-client qualification passes
- live combat creates 0 positive evidence
- live combat creates 0 mitigation effects
- exact final candidate jar is VPS-qualified jar
- Phase 12 absent
- no commit/baseline/push/tag occurred

## 84. DO NOT COMMIT

At the end: DO NOT commit, baseline, push, tag, or begin Phase 12. Return the review report first.

## FINAL OUTPUT FORMAT

Return:

```text
PHASE 11 IMPLEMENTATION STATUS:
READY FOR HUMAN REVIEW / NOT READY

HEAD:
<sha>

ORIGIN/MASTER:
<sha>

WORKING TREE:
<summary>

BUILD:
PASS / FAIL

TEST COUNTS:
test <n>
nativeTest <n>
unique <n>

PHASE 1 COVERAGE:
x/127

PHASE 1–10 REGRESSION:
NONE / details

COMBAT OWNER:
<module>

COMBAT TRUTH MODEL:
<summary>

ATTACK OBSERVED IMPLIES SERVER ACCEPTANCE:
YES / NO

ATTACK PACKET SEMANTICS:
PASS / FAIL

NON-ATTACK INTERACTION SEPARATION:
PASS / FAIL

ENTITY IDENTITY MODEL:
<summary>

ENTITY GENERATION / ID REUSE:
PASS / FAIL

ENTITY LIFECYCLE:
PASS / FAIL

RELATIVE MOVEMENT FIDELITY:
PASS / FAIL

TARGET TELEPORT:
PASS / FAIL

TARGET REMOVAL:
PASS / FAIL

SESSION ISOLATION:
PASS / FAIL

WORLD EPOCH ISOLATION:
PASS / FAIL

TARGET STATE SOURCE:
<summary>

LIVE BUKKIT USED AS HISTORICAL TRUTH:
YES / NO

HISTORICAL TARGET LOOKUP:
PASS / FAIL

UNKNOWN / INCOMPLETE MODEL:
PASS / FAIL

ATTACK CONTEXT:
PASS / FAIL

TIMING PROVENANCE:
PASS / FAIL

REACH VERDICT IMPLEMENTED:
YES / NO

HITBOX/RAYTRACE IMPLEMENTED:
YES / NO

ROTATION HEURISTICS IMPLEMENTED:
YES / NO

KILLAURA HEURISTICS IMPLEMENTED:
YES / NO

CLICK ANALYSIS IMPLEMENTED:
YES / NO

COMBAT POSITIVE EVIDENCE:
<n>

COMBAT MITIGATION EFFECTS:
<n>

REPLAY:
PASS / FAIL

REPLAY DETERMINISM:
PASS / FAIL

PROPERTY TESTS:
PASS / FAIL

METAMORPHIC TESTS:
PASS / FAIL

FUZZ:
PASS / FAIL

NATIVE DIFFERENTIAL:
PASS / FAIL

NATIVE DIFFERENTIAL CASES:
<details>

MEMORY:
PASS / FAIL

EMPTY COMBAT SESSION MEMORY:
<result>

TYPICAL COMBAT SESSION MEMORY:
<result>

FULL BOUNDED COMBAT MEMORY:
<result>

JMH:
PASS / FAIL

NO-COMBAT PATH:
<result>

ATTACK INGEST KNOWN TARGET:
<result>

ATTACK INGEST UNKNOWN TARGET:
<result>

CURRENT TARGET LOOKUP:
<result>

HISTORICAL TARGET LOOKUP:
<result>

ENTITY MOVE UPDATE:
<result>

FULL BOUNDED:
<result>

PAPER SMOKE:
PASS / FAIL

FINAL CANDIDATE JAR:
<full SHA-256>

REAL CLIENT ENVIRONMENT:
VPS

REAL CLIENT LEGITIMATE COMBAT:
PASS / FAIL

LIVE ATTACK OBSERVATIONS:
<n>

LIVE TARGET LOOKUP FOUND:
<n>

LIVE TARGET LOOKUP INCOMPLETE/UNKNOWN:
<n>

LIVE ENTITY GENERATIONS:
<n>

LIVE ENTITY REMOVALS:
<n>

LIVE EPOCH RESETS:
<n>

LIVE EXCEPTIONS:
<n>

LIVE POSITIVE EVIDENCE:
<n>

LIVE MITIGATION EFFECTS:
<n>

REMOTE RTT/JITTER:
<result>

NEW DEFECTS:
<none/list>

KNOWN LIMITATIONS:
<list>

PHASE 0 DRIFT:
NONE / details

PHASE 0–10 HISTORICAL BASELINES:
UNCHANGED / details

PHASE 12:
NOT STARTED

REMAINING BLOCKERS:
<none/list>

PHASE 11 IMPLEMENTATION STATUS:
READY FOR HUMAN REVIEW / NOT READY
```

Do not commit.
Do not baseline.
Do not push.
Do not tag.
Do not begin Phase 12.
