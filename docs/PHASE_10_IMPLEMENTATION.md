# Phase 10 — Mitigation / Setback

Status: **READY FOR HUMAN REVIEW**, under the human acceptance adjustment for P10-F1 (not committed, not baselined, no Phase 11 work).
The live controlled setback is EXTERNAL QUALIFICATION REQUIRED: G1 makes a real effect structurally unreachable on the capture-only production
transport, so the qualification requirement (not G1) is waived. Final jar `46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd`.
Base: Phase 9 implementation baseline `594c0b1d78d2e692f1ddb7f06951ddf722657fef`, receipt `d3ab7dee7721c4f19739e0f033fb5d27a03958d9` (HEAD, unchanged).
Pinned pair: Paper 26.2 build 124 (`.phase1-runtime/versions/26.2/paper-26.2.jar`) / vanilla 26.2 client. Toolchain Java 25 (Temurin 25.0.4.1+1).
Human decisions applied: **DECISION 1** (policy `movement-setback-v1`) and **DECISION 3** (TC25 before any ENFORCE use, no waiver).

Phase 10 is the first operational response layer. It reads the immutable Phase 9 output and decides whether it is safe to correct the
player's movement state, and to where. The only action is a **SETBACK** to a previously certified safe state. The pipeline:

1. A versioned evidence policy (`movement-setback-v1`) decides **eligibility** from independent Phase 9 correlation groups.
2. The session-owned mitigation actor then decides whether a correction is **safe now** (session, world epoch, transport health, teleports,
   external motion, locomotion, block disputes, entity neighbourhood, a certified target, its age and distance, collision clearance
   re-certified against the current world, idempotence, cooldown, runtime mode, the global G2 guard).
3. In ENFORCE only, it requires a **durable decision capsule** before an immutable, expiring intent is handed to the Paper main thread.
4. The main thread re-checks everything against the live server and teleports once.

The production and default configuration is **OBSERVE_ONLY**: every decision is computed and recorded, and nothing is applied. ENFORCE is not
reachable from configuration. It exists only for the controlled test-server qualification, behind a JVM flag and a passing TC25 result at the
configured capacity.

Phase 10 never kicks, bans, scores, labels or alerts staff, and never answers "is this player cheating". One Phase 9 event is one
unexplained transition, and it is never a setback.

## SCOPE

In scope (implemented and tested; real-client qualified in OBSERVE_ONLY; the live controlled setback is blocked by G1, see P10-F1):

- **The movement-setback-v1 policy** (`SetbackPolicy`, versioned and reviewable): 4 consecutive independent Phase 9 correlation groups of one
  positive evidence family, with continuity resets.
- **Safe anchors** (`AnchorAdmission`, `SafeAnchor`): admitted only from certified VALID Phase 8 results. They carry full provenance and are
  bounded to 64.
- **The decision order and intent state machine** (`MitigationActor`) of MITIGATION G1: typed outcomes and reasons, idempotent action ids,
  one active correction, deadlines, confirmation timeout, cooldown, loop breaker and recovery.
- **Core composition**:
  - `MitigationRunner` hooks the live chain, the controlled side chain, timing events and platform records.
  - `MitigationPreconditions` is the main-thread order.
  - `DecisionCapture` holds the per-session capsule prehistory.
  - `GlobalProposalGuard` counts G2 proposals before capture.
- **Durable decision capsules** (replay `RollingCapture` + `DurableCapsuleWriter`, the DURABLE_REQUIRED path) and the **TC25** durability gate.
- **The platform executor** (`PaperMitigationExecutor`): destination-region capture, the pure validator, the G2 admission, the G1 latch, and
  exactly one `teleport` call site.
- **Diagnostics** (`/papea2ac mitigation <player>`) and a controlled test mode (the Phase 8 side chain, `/papea2ac validate <player> inject`).
- **Qualification assets**: memory harness, JMH kernels, deterministic fixtures, and the policy / suppression / target / setback artifacts.

Out of scope (closed): kick, ban, temporary ban, punishment points, staff conviction, cheater labels, staff alerts tied to detection strength,
combat / reach / KillAura / velocity / timer / rotation / scaffold / inventory / protocol checks, velocity corrections, vehicle and elytra
corrections. Phase 11+ is not started.

## OWNER

| Component | Module | Role |
|---|---|---|
| `SetbackPolicy`, `MitigationActor`, `AnchorAdmission`, `SafeAnchor`, `SafeAnchorValidator`, `DestinationRegionCapture`, `MitigationIntent`, `MitigationBounds`, `MitigationVocabulary` | **anticheat-mitigation** (owner) | Domain: policy, safety, target, intent lifecycle. No Paper/NMS/Netty; no clock, randomness or hash-order collection (ArchitectureTest). |
| `MitigationRunner`, `MitigationPreconditions`, `DecisionCapture`, `GlobalProposalGuard`, `PacketEngine` wiring | anticheat-core | Composition on the session shard; main-thread precondition order; capsule prehistory; G2 proposal counting. |
| `RollingCapture`, `DurableCapsuleWriter` | anticheat-replay | Rolling prehistory share; durable group-committed capsule storage and its verifying reader. |
| `PaperMitigationExecutor`, `/papea2ac mitigation`, `/papea2ac effects enforce-qualification` | anticheat-platform-paper | The only place a correction is applied. |

Mitigation's DAG edges are model, replay-schema, player, evidence, world and collision, and ArchitectureTest enforces them. No lower module
depends on mitigation. Platform-paper still does not depend on collision: the engine exposes `standingBounds`.

## INPUT CONTRACT

Per validation result, the actor receives:

- the Phase 9 ledger `Outcome` (an enum);
- the immutable `EvidenceEvent` it produced, if any;
- the immutable `EvidenceSnapshot`.

It never reads `EvidenceLedger` internals. It also receives a `CurrentState` value built by the core runner from the session's own published
state. That value holds:

- SessionKey, world epoch and world key;
- position, transport health and generation;
- the unresolved teleport and external-influence counts;
- the locomotion support flag;
- predicates over the dispute ledger and the relevant-entity neighbourhood;
- the current `WorldView`;
- the runtime mode.

Safe anchors arrive as `AnchorAdmission.Facts`, extracted from the Phase 8 `MovementValidationResult` and its certified continuation nodes.
Everything the actor keeps is modelled and bounded (see BOUNDS). The decision is a pure function of these inputs plus that bounded state.

## PHASE BOUNDARY

ArchitectureTest `mitigationStaysWithinThePhase10Boundary` pins the boundary:

- **Forbidden vocabulary.** The mitigation module, `MitigationRunner` and `MitigationPreconditions` may not contain punishment, score, check,
  platform, clock or hash-order vocabulary: kick, ban, punish, cheater, vl, score, confidence, probability, Reach, KillAura, nanoTime,
  currentTimeMillis, Instant, Clock, Random, HashSet/HashMap iteration and Bukkit types.
- **Imports.** The mitigation module imports only its approved edges.
- **One call site.** The executor contains exactly one `.teleport(` and none of kick, ban, setGameMode, setHealth, removePotionEffect,
  setVelocity, inventory clear or walk/fly speed. The plugin contains no kick or ban.
- **Guards before the call.** `MitigationPreconditions.check`, the G2 admission `engine.admitEffect`, the G1 latch `latch.authorize` and the G2
  latch `engine.applyPendingRestriction` all precede the call.
- **No ENFORCE from configuration.** `EngineConfiguration` refuses ENFORCE. Qualification ENFORCE needs `-Dpapea2ac.qualificationEnforce=true`
  plus a TC25 result with `pass=true` and `gateSessions` >= the configured capacity.
- **Real ports.** The engine's actors use the durable capsule port and the G2 proposal guard.

## MITIGATION POLICY

`SetbackPolicy.VERSION = "movement-setback-v1"` (DECISION 1). A setback becomes **evidence-eligible** only when all of these hold:

- **4 independent Phase 9 correlation groups** (`REQUIRED_GROUPS = 4`), consecutive since the last reset;
- all of **one existing positive evidence family** (`EvidenceVocabulary.Category`, the enum names exactly). A group's family is the
  intersection of its members' categories. Eligibility needs the last four groups to share a family, and when several are shared the lowest
  ordinal is reported;
- the **same SessionKey** and the **same world/dimension epoch**;
- **within the 30 s Phase 9 window**: expired groups are pruned from the chain, `EVIDENCE_EXPIRED`;
- every group **complete/actionable under the frozen Phase 9 rules**. An incomplete correlation resets the chain
  (`CORRELATION_INCOMPLETE` / `CORRELATION_UNRESOLVED`);
- **no duplicate event or group counts twice**: a Phase 9 duplicate is a no-op, and a group counts once whatever its member count;
- each group **contributes only its largest member**. Magnitude never shortens the requirement.

Continuity:

- Any VALID between qualifying groups resets the chain.
- Any UNCERTAIN or incomplete observation resets it, including an unanchored transmission and a proof-rejected INVALID.
- Different families never combine.
- A world-epoch change resets.
- A correction, proposed or applied, resets both chains.

1, 2 or 3 groups give `NOT_ELIGIBLE INSUFFICIENT_EVIDENCE`. There is no VL, weighting, confidence or severity shortcut.

Eligibility is not an action. It is only the first line of the decision order below.

## EVIDENCE REQUIREMENTS

Exact rule: *eligible ⇔ the chain holds ≥ 4 consecutive independent Phase 9 correlation groups (no VALID, UNCERTAIN, incomplete, epoch
change or correction between them), same SessionKey, same world epoch, all inside the 30 s window, all complete, and the last four share one
evidence family.*

The brief's continuity patterns are tested explicitly in `SetbackPolicyTest`:

| Pattern | Expected | Test |
|---|---|---|
| I I I (three groups) | not eligible | `oneTwoOrThreeGroupsAreNeverEligibleAndFourAre`, `patternIIIIIsEligibleOnlyWithFourConsecutiveGroups` |
| I I I I (four independent groups) | eligible | same |
| I U I | reset | `patternIUIResetsTheChain` |
| I V I | reset | `patternIVIResetsTheChain` |
| I I U I | reset | `patternIIUIResetsTheChain` |
| a touching INVALID run | one group, never eligible | `aConsecutiveRunOfTouchingInvalidsIsOneGroupAndNeverEligible` |

Evidence is never deleted by Phase 10. The Phase 9 ledger is untouched by a setback. Only the mitigation chain (Phase 10 state) restarts at a
correction.

## SETBACK ELIGIBILITY

The decision order is fixed and deterministic (`MitigationActor.decide`). The first failing check determines the typed outcome:

1. policy → `NONE` / `NOT_ELIGIBLE` (INSUFFICIENT_EVIDENCE, EVIDENCE_EXPIRED, CORRELATION_*)
2. session → `SUPPRESSED` SESSION_MISMATCH
3. epoch → WORLD_EPOCH_MISMATCH
4. session disabled → SESSION_DISABLED (loop breaker)
5. active correction or recovery → ACTIVE_CORRECTION / RECOVERY
6. cooldown → COOLDOWN
7. transport → TRANSPORT_UNHEALTHY
8. teleport → UNRESOLVED_TELEPORT
9. external motion → UNRESOLVED_EXTERNAL_MOTION
10. locomotion → UNSUPPORTED_LOCOMOTION
11. target → NO_SAFE_ANCHOR
12. age → `RESYNC_REQUIRED` ANCHOR_TOO_OLD
13. distance → `RESYNC_REQUIRED` ANCHOR_TOO_FAR
14. dispute → BLOCK_DISPUTE
15. entities → ENTITY_STATE_INCOMPLETE
16. clearance re-certified now → COLLISION_OBSTRUCTED / COLLISION_UNKNOWN
17. duplicate → DUPLICATE_ACTION
18. mode → MODE_DISABLED / MODE_OBSERVE_ONLY. The would-be target and action id are recorded, and nothing is applied.
19. G2 proposal guard → GLOBAL_GUARD
20. ENFORCE → `PROPOSED`, then WAITING_CAPTURE

Any exception gives `SUPPRESSED INTERNAL_ERROR`, never a correction.

The brief's decision vocabulary maps onto the frozen MITIGATION state machine:

| Brief concept | Implementation |
|---|---|
| NONE | NONE |
| SETBACK_ELIGIBLE | policy met, then PROPOSED, or SUPPRESSED MODE_OBSERVE_ONLY with target and action id |
| SETBACK_APPLIED | intent terminal RECOVERED, or CONFIRMED_UNRECOVERED |
| SETBACK_SUPPRESSED | SUPPRESSED / RESYNC_REQUIRED with a typed reason |
| SETBACK_FAILED | terminal REJECTED / EXPIRED / SUPERSEDED / RESYNC_REQUIRED with a typed reason |

## SAFE ANCHOR MODEL

A `SafeAnchor` is admitted only from a live VALID Phase 8 result whose certified continuation passes every check, in this order
(`AnchorAdmission.admit`):

1. same SessionKey;
2. same world epoch;
3. VALID with **all 13 completeness dimensions**;
4. supported on-foot locomotion and pose;
5. **a concrete position**: every certified continuation node at the same bit-exact position;
6. finite state, grounded;
7. no unresolved teleport;
8. no unresolved external influence (velocity, explosion);
9. no block dispute;
10. the standing box's **collision clearance CLEAR**: a COMPLETE query with no hit. Unknown is never clear.

Each anchor records its provenance: session, world epoch, world revision, sequence, capture nanos, position, yaw, pitch, grounded, crouching
possibility, source classification `VALID_CONCRETE_GROUNDED`, and the list of reasons it was considered safe. At most 64 are kept. No
unbounded history is kept, and the PlayerTimeline is not duplicated: an anchor is a 12-field value.

## TARGET SELECTION

The target is the **most recent** admitted anchor with the same SessionKey and world epoch that is at or before the first qualifying group's
sequence. On the controlled side chain it is the most recent **live** anchor, because the side chain never produces anchors of its own. The
following then apply:

- **Age:** more than 2 s gives `RESYNC_REQUIRED ANCHOR_TOO_OLD`.
- **Distance:** more than 8 blocks from the current position gives `RESYNC_REQUIRED ANCHOR_TOO_FAR`. There is no long-distance fallback, no
  spawn, no "nearest free point".
- **Re-certification:** clearance is re-certified against the **current** WorldView. A historically safe target in changed geometry is
  suppressed.
- **Main-thread check:** the main thread captures an immutable destination region (at most 4 096 cells, broad-phase padding pinned to Phase 4)
  of the currently loaded world. The same shared shapes validate it (`SafeAnchorValidator`). A missing cell, an incomplete region or an
  uncertain extent is UNKNOWN, which is rejected.

Selection uses no wall clock and no randomness, so the same inputs give the same target
(`MitigationApplicationTest.theActionIdentityIsStableAndDistinguishesEverySourceOfTheDecision` and
`MitigationPropertyTest.noEvidenceMeansNoSetbackAndTheSameStreamMeansTheSameDecisions`). The corpus in `target-selection-results.json`
covers these cases:

- most recent vs older anchor;
- side chain → live anchor;
- too old / too far → RESYNC_REQUIRED;
- no anchor → NO_SAFE_ANCHOR.

## SESSION/EPOCH SAFETY

Session and epoch are checked at four points:

- **Anchors:** foreign anchors are refused.
- **Decisions:** foreign evidence is refused (SESSION_MISMATCH / WORLD_EPOCH_MISMATCH).
- **World-epoch change** (`onEpochChange`, from the Phase 7 timing `Discontinuity`; every decision also compares the epoch it is given): clears the anchors, resets both chains and ends any
  pre-execution intent as SUPERSEDED (WORLD_CHANGED).
- **Session end:** the old session's actor ends everything as SESSION_ENDED.

On the main thread, `engine.currentSession(key)` refuses an intent whose session is no longer current. That is how a correction scheduled
for an old session can never touch a new one (`onlyACurrentClearSameSessionIntentPassesTheMainThreadChecks`, `racesBeforeExecutionCancelTheStaleIntent`).

## WORLD SAFETY

- **Block disputes** (P9-F2): an active dispute near the target or the current position gives BLOCK_DISPUTE.
- **Entities** (P9-F5): an incomplete relevant-entity neighbourhood near either gives ENTITY_STATE_INCOMPLETE.
- **Clearance:** re-certified on the shard, then validated again on the main thread over the loaded world.
- **Chunks:** a destination chunk that is not loaded gives DESTINATION_UNLOADED. There is never a load.
- **World identity:** a world key other than the anchor's gives WORLD_CHANGED.

Live Bukkit state is used only for application safety, never as history.

## VELOCITY SAFETY

- **Before capture:** any unresolved external influence (velocity, explosion, knockback) gives UNRESOLVED_EXTERNAL_MOTION.
- **Before execution:** an external-motion send (`onExternalMotion`) ends a pending intent as REJECTED UNRESOLVED_EXTERNAL_MOTION.
- **Anchors** require zero pending influences.
- **Velocity corrections are disabled** (MITIGATION: "velocity changes ... are disabled initially"). The executor never calls `setVelocity`,
  and ArchitectureTest pins that.

The Paper 26.2 teleport resets the entity's server-side motion as part of any position correction (`CraftEntity.teleport0` uses
`Vec3.ZERO`). That is why a setback is only admitted with no unresolved external motion: it can then only drop the player's own momentum
toward a grounded, standing target, never a legitimate knockback or explosion.

## TELEPORT SAFETY

- **Unresolved corrections:** a sent, unconfirmed, not superseded correction gives UNRESOLVED_TELEPORT, on the shard (the timing view) and
  again on the main thread.
- **Foreign teleports:** a foreign teleport or respawn observed before execution ends the intent as SUPERSEDED
  (SUPERSEDED_BY_FOREIGN_TELEPORT).
- **Identifying our own correction:** a captured outbound correction is recognised as ours only if its absolute target equals the intent's
  target bit for bit and it carries no relative position flags. Anything else is foreign. Phase 7 timing semantics stay authoritative.
- **Sent is not applied.** SENT means the platform call completed. CONFIRMED means the client's teleport confirm correlated through the
  Phase 7 ledger. RECOVERED means the first coherent live VALID after it. Production transport stays CAPTURE-ONLY, and authoritative
  downstream correlation stays UNSUPPORTED.

## CORRECTION APPLICATION

`PaperMitigationExecutor` runs on the Paper main thread and handles at most 8 intents per tick, polled from a bounded queue of 64; a saturated
queue gives REJECTED QUEUE_SATURATED. For each intent:

1. **Facts.** It gathers the live facts:
   - identity, online state, world key;
   - dead;
   - vehicle, gliding, flying, riptiding, sleeping;
   - game mode;
   - distance;
   - whether the destination chunks are loaded (`isChunkLoaded`, no load).
2. **Region.** It captures the destination region and validates it.
3. **Order.** It runs `MitigationPreconditions.check`, the pure main-thread order.
4. **Admission.** It asks for the G2 admission (`engine.admitEffect`), then the G1 latch (`latch.authorize(generation)`).
5. **Start.** It records STARTED (`:S`) into the session's ordered stream and re-authorizes the latch.
6. **Teleport.** It calls **one** `p.teleport(new Location(world, x, y, z, currentYaw, currentPitch), PLUGIN)`: absolute coordinates, rotation
   preserved, no relative flags. Paper 26.2 rejects YAW/PITCH relative flags.
7. **Outcome.** It records SENT (`:X`) or REJECTED with the reason (`:R:<ordinal>`). Paper or plugin cancellation is REJECTED
   PLATFORM_CANCELLED, never a fake anchor.

Nothing else is touched: no inventory, health, game mode, effects, velocity or walk/fly speed. `shutdown()` rejects every queued intent with
SHUTDOWN before the hooks go.

## POST-SETBACK RESYNC

The correction goes through the ordinary Phase 7 pipeline: its outgoing packet and teleport id are captured, and the client's confirm is
correlated. Phase 8's correction semantics re-anchor the live chain at the confirmed correction, so pre-setback state is never validated as
if continuity had held.

The actor moves SENT_AWAITING_CONFIRM → CONFIRMED → **RECOVERY**. While an intent is active or recovering, evidence inputs reset the chain
instead of contributing. The first coherent live VALID ends it as RECOVERED. If none arrives within the 5 s recovery horizon, the terminal is
CONFIRMED_UNRECOVERED.

`MitigationIntegrationTest.enforceAppliesExactlyOneCorrectionAndRecoversWithoutALoop` runs the real runner through capture, confirmation,
re-anchor, legitimate movement and RECOVERED. It proves no live INVALID, no evidence and no second setback.

## IDEMPOTENCE

The action identity is `actionId` = SHA-256(policy | session | epoch | world epoch | live/side | anchor sequence | group ids), 64 hex. The
same evidence and target always yield the same id, and every source of the decision changes it. The protections are:

- only one active correction;
- 8 predecessor ids remembered, so a repeat is SUPPRESSED DUPLICATE_ACTION;
- a duplicate capture receipt queues nothing;
- a duplicate platform record or confirmation is idempotent;
- reprocessing the same Phase 9 duplicate is a no-op (`reprocessingTheSameEvidenceNeverSchedulesASecondAction`).

## COOLDOWN

A **2 s** cooldown after a SENT correction (COOLDOWN) and the loop breaker (**3 failed corrections within 30 s** → SESSION_DISABLED, plus an
operational log warning) are MITIGATION G1 safety limits against correction spam. They are not detection thresholds and they never escalate.

The G2 global guard (MITIGATION G2) counts **proposed** actionable decisions **before capture/queue delay** (`GlobalProposalGuard`, on the
shards). Within any 1 s, 3 distinct sessions trip it. Within 10 s, max(3, ceil(0.20·N)) distinct sessions trip it. Tripping latches an
out-of-band restriction:

- every further proposal is SUPPRESSED GLOBAL_GUARD (no capsule, no intent);
- main-thread admission refuses;
- the executor applies OBSERVE_ONLY at its next tick and logs `GlobalAnomalySuspend`.

Only an explicit re-enable resets it. The main-thread `RuntimeControl` arbiter keeps the same thresholds at execution.
`aCorrelatedFaultSuspendsGloballyBeforeAnyLoopBreaker` shows six simultaneously eligible sessions: two propose, the rest are refused, the
guard trips, and no session's loop breaker fires.

## BOUNDS

| State | Bound |
|---|---|
| Safe anchors | 64 per session |
| Chain | 8 groups per policy; two policies (live, side) |
| Group families | 64; member ids 8 |
| Predecessor ids | 8 |
| Active intents | 1 |
| Recent decisions / outcomes / teleports | fixed rings |
| Anchor age / distance | 2 s / 8 blocks → RESYNC_REQUIRED |
| Intent deadline / confirm timeout / recovery horizon | 500 ms / 5 s / 5 s |
| Main-thread queue / per tick | 64 / 8 |
| Destination region | 4 096 cells |
| G2 distinct-session window | admitted capacity (1 024) |
| Rolling capsule share | q = min(256 KiB, 48 MiB / N), global 48 MiB ROLLING pool; checkpoint every 250 ms or at a quarter share |
| Capsule | ≤ 4 096 frames, ≤ 8 MiB |
| Durable writer | queue 256; jobs charged their retained bytes to the 16 MiB IO pool; batches ≤ 64 jobs / 8 MiB; retention 7 days / 5 GiB |

## MEMORY

`gradlew :anticheat-testing:mitigationMemoryHarness` → `docs/phase10-artifacts/memory-results.json`. The method is forced GC plus used-heap
diff, 3 trials, median; per-step garbage comes from thread-allocation counters. These are one-host estimates, not a capacity claim. The
shared collision engine and observer world are Phase 4/5 session state and are excluded.

| State | Retained |
|---|---|
| Empty per-session mitigation actor (two v1 policies, bounded rings) | 3 640 B |
| Typical legitimate session (20 anchors, VALID stream, one isolated group) | 9 360 B |
| Full bounded actor (64 anchors, full chains and family maps, predecessor ids, decision/outcome rings = retained diagnostic history) | 46 976 B |
| One SafeAnchor / one pending MitigationIntent | 168 B / 264 B |
| Core MitigationRunner (excluding the actor) | 2 576 B |
| G2 proposal guard at its reachable maximum (the burst gate caps it at 2 distinct sessions per second) | 544 B |
| DecisionCapture at a full 256 KiB share of movement frames: heap / charged to the ROLLING pool | 62 912 B / 171 288 B |

Per-step allocation:

| Step | Allocated |
|---|---|
| Legitimate step (ledger + actor) | 1 400 B |
| Anchor admission, including the clearance query | 1 472 B |
| One observation exported and appended to the rolling share | 1 588 B |

Nothing grows with session age. Every collection is bounded (see BOUNDS). The rolling share's accounting is conservative: it charges 512 B per
input against about 170 B of real heap.

## PERFORMANCE

`gradlew :anticheat-testing:benchmarkMitigation` → `docs/phase10-artifacts/benchmarks.json`. It uses JMH 1.37 on Temurin 25.0.4.1+1: 3 forks,
5×1 s warmup, 10×1 s measurement, `-prof gc`, `-Xms512m -Xmx512m`.

The kernels run against the **real** observer world and collision engine of the actor test harness. Each kernel's path is asserted once in
`@Setup`: for example, the cycle must end `MODE_OBSERVE_ONLY`, the unsafe kernel `BLOCK_DISPUTE`, and the insufficient kernel
`INSUFFICIENT_EVIDENCE`. The results are descriptive, from one host, and never a capacity claim.

| Kernel (brief item) | ns/op (± 99.9 %) | B/op |
|---|---|---|
| `legitimateStep`: a VALID through ledger and actor (**no-op evaluation**) | 194.6 ± 6.6 | 1 040 |
| `uncertainStep`: an UNCERTAIN (chain reset) | 282.6 ± 6.9 | 1 411 |
| `insufficientEvidence`: anchor + one INVALID group (Phase 9 event creation included) + the VALID reset | 11 339 ± 85 | 14 686 |
| `admissionOfValidResult`: **anchor lookup/admission**, facts plus the standing-box clearance query | 759.1 ± 5.1 | 1 192 |
| `observeOnlyCycle`: **eligible mitigation**, i.e. anchor, 4 independent groups, target selection, re-certified **target safety check**, suppression, reset | 30 704 ± 576 | 42 896 |
| `suppressedUnsafeTarget`: the same cycle ending BLOCK_DISPUTE | 32 223 ± 614 | 42 981 |
| `enforceProposeExpire`: an ENFORCE proposal (G2 gate, intent, action id, capture request) then deadline expiry, the **post-setback bookkeeping** path | 26 508 ± 251 | 37 717 |
| `duplicateEvidence`: **duplicate handling**, the Phase 9 duplicate plus the actor no-op | 1 771.8 ± 10.2 | 4 928 |
| `actionIdDigest`: the action identity (SHA-256) | 320.3 ± 8.5 | 1 064 |
| `fullBoundedStep`: anchor + an INVALID group on the **full bounded state** (worst bounded) | 11 198 ± 84 | 12 344 |
| `mixedStream100`: **mixed stream** of 100 records (60 % VALID with anchors, 25 % UNCERTAIN, 15 % INVALID) | 523 883 ± 57 766 (≈ 5.2 µs/record) | 639 196 |
| `observeExportAppend`: one observation into the rolling share (per packet on the shard) | 655.3 ± 28.3 | 1 588 |
| `promoteFullShare`: capsule promotion at a full share (per decision) | 4 205.5 ± 72.1 | 4 584 |
| `encodeDeflateFullShare`: encode + deflate of that capsule (I/O thread, per capsule, before fsync) | 617 924 ± 3 876 | 668 655 |

Mitigation is substantially cheaper than validation. The per-result actor cost of a legitimate step is about 0.2 µs, against 48.8 µs for the
Phase 8 end-to-end simple VALID and 1.89 ms for the worst bounded case. Mitigation never reruns Phase 5/6 simulation: it reads the Phase 8
result, the Phase 9 snapshot and one bounded clearance query per anchor. The eligible-path cost (about 31 µs per 4-group cycle, 6 decisions)
is dominated by the Phase 9 INVALID ingest and the collision re-certification. The per-packet capsule prehistory cost is 0.66 µs. Durable
latency, including fsync, is measured by TC25.

## TC25 — DURABLE DECISION CAPSULES

TESTING TC25: *"Sparse movement, dense combat and chunk-churn capsules: measured size/closure and >=99% durable within 400 ms under declared
load; otherwise mitigation stays off"* (Phase 9 before 10). DECISION 3 step 4 runs it without any waiver.
`gradlew :anticheat-testing:tc25` → `docs/phase10-artifacts/tc25-results.json` and `tc25-result.properties`.

**The code under test is production code.**

- `DecisionCapture` is the engine's per-session prehistory. It reserves the fair ROLLING share lazily and exports every observation,
  administrative ones included. It checkpoints every 250 ms, or at once when a segment reaches a quarter of the share. It promotes the oldest
  intact contiguous run through the decision point.
- `DurableCapsuleWriter`:
  - **Encoding:** CapsuleCodec, then deflate.
  - **Batch data file:** written to a temporary file, `force(true)`, then an atomic move.
  - **Manifest:** carries a SHA-256 per capsule; written to a temporary file, `force(true)`, then an atomic move.
  - **Receipt:** issued only after both moves.
  - **Group commit:** a burst shares the two flushes.

Only the traffic is synthetic. It is protocol-shaped, using the real `PacketPayload`/`SemanticPayload` records and an Attempt/Outcome pair
per inbound packet:

- **Sparse movement:** walking, tick ends, keepalives, 2 nearby entities.
- **Dense combat:** 12 CPS swing and attack, knockback and health, 24 moving entities, projectiles; about 660 observations/s per session.
- **Chunk churn:** 30 blocks/s, view distance 10, a Paper-like send rate of 4 chunks per tick. The chunk sections are wire-packed, with a
  mixed underground palette, surface and trees.

It is generated in real time at 20 TPS, with one shard thread per 128 sessions as in the engine.

Host: Windows 11, 24 logical CPUs, Temurin 25.0.4.1, 2 GiB heap. Capsules went to the repository's local disk (`build/tc25-capsules`, NTFS).
One host; not a capacity claim.

**The declared load, per run:** N sessions active for a 4 s warmup plus 20 s measured.

- **STRESS:** every session requests a capsule at the per-session cooldown bound (1 per 2 s, random phase). One correlated burst of **all N
  sessions in the same tick** comes at mid-window. This is far above anything the G2 guard lets reach capture.
- **G2_MAX:** 2 requests/s from rotating sessions, the highest rate the G2 burst gate admits without suspending.

A request succeeds only if a complete capsule was promoted and accepted, and its receipt was durable within 400 ms of the decision point.
Promotion, queueing and I/O all count, and refusals count as misses. **Closure** is measured on every durable capsule, read back from disk by
the verifying reader. That checks the manifest digest, a bounded inflate, the decode, a leading checkpoint, contiguous inputs, no barrier
and completeness.

**The gate:** Review-2 NEW-M1's acceptance says the gate "must hold at the maximum configured certified-session capacity for the tier under
test". That is `EngineConfiguration` default capacity = **32**. Both loads must pass there. The plugin refuses qualification ENFORCE unless
`gateSessions` ≥ its configured capacity.

| N | Scenario | Load | Durable ≤ 400 ms | p99 latency | Prehistory span p50 (min–max) | Closure verified |
|---|---|---|---|---|---|---|
| **32** | sparse movement | STRESS | **100 %** (335/335) | 82.4 ms | 2 744 ms (2 591–2 890) | 335/335 |
| **32** | sparse movement | G2_MAX | **100 %** (40/40) | 11.7 ms | 2 710 ms | 40/40 |
| **32** | dense combat | STRESS | **100 %** (335/335) | 78.5 ms | 498 ms (386–610) | 335/335 |
| **32** | dense combat | G2_MAX | **100 %** (40/40) | 11.6 ms | 493 ms | 40/40 |
| **32** | chunk churn | STRESS | **100 %** (333/333) | 197.7 ms | 94 ms (45–204) | 333/333 |
| **32** | chunk churn | G2_MAX | **100 %** (40/40) | 17.1 ms | 99 ms | 40/40 |
| 128 | sparse / dense / chunk | STRESS | 94.9 % / 95.0 % / 91.1 % | 173 / 152 / 503 ms | 2 739 / 496 / 94 ms | all verified |
| 128 | sparse / dense / chunk | G2_MAX | 100 % / 100 % / 100 % | 10–23 ms | as at 32 | all verified |
| 1000 | sparse / dense / chunk | STRESS | 93.1 % / 92.8 % / 44.2 % | 202 / 212 / 644 ms | 447 / 61 / 0.07 ms | all verified |
| 1000 | sparse / dense / chunk | G2_MAX | 100 % / 100 % / 100 % | 16–19 ms | 413 / 47 / 0.07 ms | all verified |

**Result: TC25 PASS at the configured capacity (32), for both loads.** No capsule failed closure in any run (33 898 read back). Every
STRESS miss at 128 and 1000 is `WRITER_REFUSED`. That is the specified 16 MiB IO pool (REPLAY_SYSTEM "Queue capacity is 16 MiB globally")
refusing an all-session burst, without blocking. At 1000, chunk churn also saturated the harness's own shard threads (2 948 tick overruns).

**Session-count dependence (NEW-M1).** The capsule prehistory source is the replay ROLLING share, q = min(256 KiB, 48 MiB/N), not the
timeline ring (see `DecisionCapture`):

- Up to 192 sessions q = 256 KiB. That holds about 2.7 s of sparse movement, about 0.5 s of dense combat and about 0.1 s of chunk churn.
- At 1000 sessions (q ≈ 48 KiB), sparse movement keeps about 0.4 s and dense combat about 50 ms.
- Chunk churn keeps a complete but **near-empty** capsule: a checkpoint plus the newest one or two frames.

Capsules stay complete at every N measured, while their prehistory shrinks with N and with bulk traffic, exactly as REPLAY_SYSTEM warns
("opportunities, not guarantees"). Under the G2-reachable load, durability passes at every N measured.

## DIAGNOSTICS

`/papea2ac mitigation <player>` prints bounded lines:

- the session, the policy version, the mode, and each chain's progress as `n/4`;
- the intent state, action id, cooldown and recent failures;
- the anchor count with the latest anchor's provenance and age, and the last anchor refusal;
- the last decision (outcome, reason, chain, family, target provenance, action id) and the last six decisions;
- the recent intent outcomes;
- the G2 guard status, executor suspends and capsule counters.

No ban recommendation, kick recommendation or cheater label is ever shown. Metrics are fixed-cardinality `LongAdder`s plus an
`EnumMap<Reason, LongAdder>`, with no UUID labels:

- evaluations, eligible, suppressed, resync, proposed, queued, sent, confirmed, recovered, failures;
- duplicates dropped, sessions disabled, anchors admitted, anchors refused, internal errors;
- per-reason counts;
- capsules promoted, unavailable, durable, failed.

## REPLAY

The decision is a pure function of the pinned inputs: EvidenceSnapshot, the event, the ledger outcome, the CurrentState value and the
bounded anchor/chain/intent state. The mitigation domain has no clock, randomness or hash-order iteration (ArchitectureTest). Same inputs
give the same decision, the same target and the same action id, which the property test checks over 60 seeds against an independent
reference. The synthetic corpus is regenerated twice by `mitigationFixtures` and must be identical, with SHA-256 digests in
`mitigation-fixtures/manifest.json`.

The durable decision capsule is the Phase 1 `TransportCapsule`: the normalized transport inputs from a coherent checkpoint through the
decision point. Its capsule id is the intent's action id. It starts at a mid-session checkpoint. That checkpoint carries the session's
ingress, processing and revision ordinals, the protocol state, the capture flag and the effect mode, but **not** a closure of the observer
world, timeline, validation frontier or evidence state from before it.

So the decision capsule preserves exactly what the transport showed around the decision, and it is complete and durable before any effect.
It does not by itself let an offline tool re-derive the decision from nothing. The in-process determinism above is proven. A full
checkpoint/blob closure of the domain state (REPLAY_SYSTEM "Full checkpoint/blob closure must fit an 8 MiB capsule reservation") is not
implemented; see KNOWN LIMITATIONS. `DurableCapsuleWriter.read` is the verifying reader. It resolves the promoted manifest, checks the
SHA-256, does a bounded inflate, and decodes and cross-checks the frame count and completeness.

## REAL-CLIENT QUALIFICATION

The owner's VPS was used (Linux 6.8, Temurin 25.0.4.1+1, Paper 26.2-124, online mode) with **a real remote player** (`iDTR`) on the vanilla
26.2 client over the public internet. The jar was `a464ad44…` (the P10-F2 fix came later; see its entry for the jar confirmation). Every
command is transcribed in `real-client-evidence-vps.log`, and the server logs are `real-client-server-vps-jara464ad44-{legitimate,controlled}.log`.
Player IP addresses are redacted, and the logs were scanned for the RCON password: 0 hits.

**Paper smoke.** All live qualification counts only from the VPS (owner decision, 2026-09-27); the early local `.phase1-runtime` start
(`real-client-evidence.log`) was a plugin-load smoke only. On the VPS, on each start:

- the plugin enabled in OBSERVE_ONLY;
- 0 plugin exceptions (the one exception in the logs is P10-F2, below);
- diagnostics answered;
- `/papea2ac effects enforce-qualification` was **refused** without `-Dpapea2ac.qualificationEnforce=true`, even with a passing TC25 file.

**REAL CLIENT LEGITIMATE: PASS.** This ran in OBSERVE_ONLY in the default configuration, with no qualification flag.

The player covered, on his own:

- walk, sprint, diagonal, jump, sprint-jump;
- crouch, including at block edges;
- shield and eating, including at low food, where sprint is disabled;
- stairs and slabs;
- packed ice, honey, soul sand;
- a 5-high ladder;
- block placement and breaking while moving.

From the console:

- animals next to him: correctly UNCERTAIN `ENTITY_NEIGHBORHOOD_UNKNOWN`, never INVALID;
- three controlled server knockbacks: 60 transitions, all VALID;
- a TNT explosion on an obsidian platform: the player was pushed 0.67 blocks, all VALID;
- several teleports;
- death and respawn: world epoch 0 → 1;
- the Nether and back: epochs 2 → 3, with mitigation following every epoch.

| Session | Transitions | VALID | UNCERTAIN | INVALID | Positive evidence | Eligible / proposals / setbacks |
|---|---|---|---|---|---|---|
| Legitimate course | 12 920 | 12 904 | 16 | **0** | **0** | 0 / 0 / **0** |
| Live chain during the controlled run | 3 564 | 3 564 | 0 | **0** | **0** | 0 / 0 / **0** |
| **Total** | **16 484** | 16 468 | 16 | **0** | **0** | **0 setbacks** |

The 16 UNCERTAIN are the conservative cases: water (fluid deferred), the animal neighbourhood, and 7-tick silent stretches (search limit).

**REAL CLIENT SETBACKS ON LEGITIMATE: 0.**

**Exact-final-jar recheck (VPS, 2026-09-27, jar `46758015…`, OBSERVE_ONLY, no qualification flag).** The remote vanilla client (`Emojiado`)
joined the VPS over the internet (RTT 37–58 ms, jitter 6 ms). It covered walk, sprint, jump, sprint-jump, crouch, diagonals, stairs/slabs and
block place/break, then three console knockbacks and a teleport. Result: **1 620 transitions, all VALID, 0 UNCERTAIN, 0 INVALID, 0 positive
evidence, 0 proposals, 0 setbacks**. With the player online, the P10-F2 path `effects suspend` → `effects resume` (twice) kept effects
suspended with 0 exceptions, and `effects enforce-qualification` stayed refused without the flag. Evidence: `real-client-evidence-vps.log`
and `real-client-server-vps-jar46758015-final-recheck.log`. After the recheck the VPS Minecraft server was stopped, the temporary
`ufw allow 25565/tcp` rule was removed, and nothing listens on 25565/25575 (verified from outside too); unrelated VPS services were untouched.

Live legitimate total across both jars: **18 104 transitions, 0 INVALID, 0 positive evidence, 0 setbacks**.

Stray connection: while the temporary port was open overnight, the final-jar server log shows one unknown client, `luckysystems`, reaching
only the pre-login stage and disconnecting (`lost connection: Disconnected`, 2026-09-27 12:24:57 server time). It never logged in, never had a
session and is not a qualification client. Its address is redacted like every other, and nothing it did is counted in any figure above.
The only qualification clients are `iDTR` and `Emojiado`.

**Latency.** The player was remote over the real internet: RTT minimum 52 ms, smoothed 58 ms, maximum 178 ms, jitter 6.5 ms, 2 703 marker
samples, 0 expired, 0 late. There were no setbacks and no INVALID from timing. No artificial latency injection was used, and no broader
network qualification is claimed.

**REAL CLIENT CONTROLLED SETBACK: EXTERNAL QUALIFICATION REQUIRED.** It is blocked by G1, because production transport
correlation/certification is UNSUPPORTED (P10-F1). The run went as follows:

1. The server was restarted with `-Dpapea2ac.qualificationEnforce=true`, with TC25 passed, so DECISION 3 steps 5–6 applied.
2. The player walked on the obsidian platform with 64 certified anchors (`VALID+ALL13, concrete, grounded, standing-box-CLEAR`).
3. `/papea2ac effects enforce-qualification` enabled ENFORCE.
4. Four side-chain injections followed, at `+0.5 x`, each from a different live anchor.

Result:

- **4/4 independent HORIZONTAL_EXCESS groups reached movement-setback-v1 eligibility.** Side evidence showed 4 events, 4 groups, excess
  h ≈ 0.055, and the decisions went `NOT_ELIGIBLE INSUFFICIENT_EVIDENCE` at 1/4, 2/4 and 3/4.
- **At 4/4, eligibility did not bypass G1.** The decision was **SUPPRESSED TRANSPORT_UNHEALTHY**. The session's transport latch was
  `EXPIRED_OBSERVE_ONLY`, as every real capture-only session is (`correlation=UNSUPPORTED`, `certificationAvailable=false`).
- **LIVE EFFECT ADMISSION: SUPPRESSED TRANSPORT_UNHEALTHY.** There were zero proposals, zero G2 proposals, zero capsules (no
  `decision-capsules` directory was ever created), zero queued intents and zero teleports.
- **The player was unaffected:** the live chain stayed VALID, health 20, food 20.
- **The server then returned to OBSERVE_ONLY,** and was finally restarted **without** the qualification flag. The ENFORCE command is refused
  again.

Per the human decision on P10-F1, this track is **neither PASS nor FAIL**. The frozen safety gate behaved correctly, and it makes a real
effect structurally unreachable on the current production transport. The correction pipeline itself is qualified offline:
`MitigationIntegrationTest.enforceAppliesExactlyOneCorrectionAndRecoversWithoutALoop` shows exactly one correction, the correct target, no
duplicate, confirmation, re-anchor, legitimate movement and RECOVERED, with no loop. The state-machine, idempotence, target-selection and
application tests also pass.

## KNOWN LIMITATIONS

1. **Live-chain setbacks are structurally rare under movement-setback-v1. This needs a human decision if not intended.**
   - Four *independent* Phase 9 groups need a certified re-anchor between INVALID runs.
   - The target must be at or before the first group and at most 2 s old.
   - So a real sustained cheat on the live chain mostly ends `RESYNC_REQUIRED ANCHOR_TOO_OLD`, which is recorded and applied as nothing. It
     does not produce a correction.
   - This is the conservative direction required by the brief ("no long-distance fallback"). The controlled qualification exercises the full
     path through the side chain, whose target is the newest live anchor.
2. **The decision capsule is transport-scope.** It is complete and durable (DURABLE_REQUIRED holds under the Phase 1 capsule definition), but
   it has no domain-state closure before its checkpoint (see REPLAY). Its prehistory shrinks with the session count and with bulk traffic,
   down to near-empty chunk-churn capsules at 1000 sessions (TC25).
3. **TC25 is one host.** The traffic is synthetic but protocol-shaped. The gate holds at the configured capacity (32). Beyond it, STRESS
   bursts exceed the specified 16 MiB IO pool (non-blocking refusals), while the G2-reachable load passes at every N measured.
4. **Windows directory durability.** POSIX forces the directory after the two atomic moves. Windows cannot open a directory channel, so
   rename durability relies on NTFS metadata journaling.
5. **Sent is not applied.** A setback is SENT when the platform call completes, CONFIRMED only when the client's teleport confirm correlates
   (Phase 7), and RECOVERED at the first coherent VALID. Production transport stays CAPTURE-ONLY, and downstream correlation stays
   UNSUPPORTED.
6. **Momentum.** The Paper teleport resets server-side motion. A setback is admitted only without unresolved external motion, so it can drop
   only the player's own momentum, never a legitimate knockback or explosion.
7. **No live effect is possible today (P10-F1).** G1 admits an effect only through a HEALTHY transport latch. Production transport is
   CAPTURE-ONLY with certification UNSUPPORTED, so every real session is `EXPIRED_OBSERVE_ONLY` and ENFORCE can never apply a correction.
   Phase 10 therefore ships OBSERVE_ONLY. The controlled live setback waits for an authoritative transport-correlation capability.
8. **Latency** was qualified only with a real remote player (RTT 52–178 ms, jitter 6.5 ms). There was no artificial latency injection and no
   broader network claim.

## DEFERRED TO PHASE 11+

- Velocity corrections (a reviewed mode-specific policy).
- Vehicle, elytra and swimming corrections (on-foot only).
- Staff alerts, operator workflows, exemptions and permissions.
- Damage rollback and inventory/placement cancellation (dedicated transaction semantics).
- Combat mitigation and every Phase 11 check.
- Any enabling of ENFORCE outside the controlled qualification (a human decision).

## FORWARD DEFECTS (P10-F<n>)

### P10-F1 — G1 effect admission is intentionally unreachable on the capture-only production transport (cross-phase capability blocker; human decision recorded)

- **Originating phase/component:** the Phase 1 transport contract (CAPTURE-ONLY, authoritative outcome correlation UNSUPPORTED) against
  Phase 10 enforcement (MITIGATION G1: `HEALTHY(g) → EFFECT_ADMITTED(g, actionId)`).
- **Failure observed:** during the live controlled qualification on the VPS, movement-setback-v1 became eligible (4/4 independent
  HORIZONTAL_EXCESS groups). The actor suppressed with TRANSPORT_UNHEALTHY. No proposal, capsule or correction was emitted.
- **Root cause:** a real Paper session latches `EXPIRED_OBSERVE_ONLY` within seconds, because no authoritative outcome correlation exists
  (known since P3-F3), and the latch is sticky for the session. The offline correction tests start from a healthy latch.
- **Classification:** not a false mitigation result, and not a reason to weaken G1. It is a capability blocker between the frozen Phase 1
  transport contract and Phase 10 enforcement.
- **Resolution (human decision):**
  - G1 is unchanged.
  - Phase 10 ships OBSERVE_ONLY, and production/default ENFORCE remains unavailable while transport certification is unavailable.
  - There is no hidden, qualification-only, debug or test-server bypass.
  - The offline correction-pipeline tests and the live legitimate qualification are preserved.
  - The real-client controlled-setback track is EXTERNAL QUALIFICATION REQUIRED until an authoritative transport-correlation/certification
    capability satisfying G1 exists.
- **Code change:** none.
- **Regression:** `MitigationActorTest.everySafetyPreconditionSuppressesWithItsOwnReason` (TRANSPORT_UNHEALTHY).
- **Historical baselines:** unchanged.

### P10-F2 — `/papea2ac effects resume` could not leave qualification ENFORCE (origin: `PacketEngine.resumeEffects`, pre-Phase 10 code) — FIXED

- **Failure:** in the live controlled run, `/papea2ac effects resume` threw `IllegalStateException: cannot resume through enforce` (a command
  exception in the server log).
- **Root cause:** `resumeEffects()` first told every session OBSERVE_ONLY, then called `RuntimeControl.resumeObservation()`. That method
  refuses while the main-thread arbiter is in ENFORCE, so the sessions ended in OBSERVE_ONLY (no effect could start: actors propose only from
  their own mode) while the arbiter stayed ENFORCE. The code predates Phase 10: ENFORCE was unreachable before, so the Phase 1 test covered
  resume only from DISABLED.
- **Smallest forward fix:** from ENFORCE, resume uses the arbiter's own restriction (`restrict(OBSERVE_ONLY)`). Otherwise it resumes as
  before. The arbiter transitions first, so a refusal can never leave the two views disagreeing.
- **Regression:** `MitigationApplicationTest.resumingFromQualificationEnforceRestrictsTheArbiterAndTheSessionsTogether` covers
  ENFORCE → resume → no admission, a repeated resume, and ENFORCE → suspend → resume. The Phase 1 `RuntimeControlOrderingTest` is unchanged
  and green.
- **Qualification:**
  - The full lane was rerun on the fixed tree: 767 + 138 tests, all gates.
  - The new jar `46758015…` differs from the live-qualified `a464ad44…` only in the `PacketEngine` compilation unit (`PacketEngine.class` and
    its synthetic `$1`, whose line numbers shift); all 701 other entries are byte-identical.
  - Live on the VPS (`real-client-server-vps-jar46758015-p10f2.log`), qualification ENFORCE → `effects resume` →
    `effectsSuspended=true`, then ENFORCE → suspend → resume, with 0 exceptions.
  - The server was finally restarted on `46758015…` without the flag, and ENFORCE is refused.
  - The legitimate live course ran on `a464ad44…`; the exact final jar `46758015…` was then rechecked live on the VPS with a remote client
    (1 620 transitions, 0 INVALID, 0 setbacks), including suspend → resume with the player online (0 exceptions).
- **Historical baselines:** unchanged.

No other earlier-phase defect was found. The Phase 0–9 models, artifacts and baselines are unchanged.

The Phase 0 open item **NEW-M1** (Review #2; assigned "Phase 9, before Phase 10 effects", deferred by Phase 9) is addressed here without
editing frozen documents:

- The capsule prehistory source is the replay ROLLING share (`DecisionCapture`), which is N-dependent.
- TC25's gate is held at the configured certified-session capacity.
- The measured N-dependence is in the TC25 table.

Folding this statement into REPLAY_SYSTEM/TESTING is left to the human review.

Phase 10 issues found and fixed during qualification (Phase 10 code, not forward defects):

- **Chunk churn left no capsule.** A single 250 ms segment of chunk churn outgrew a 256 KiB share and was dropped. Size-triggered checkpoints
  fixed it.
- **Bursts saturated the writer.** Per-capsule double fsync on one I/O thread, plus a 2× IO charge, made correlated bursts miss 400 ms or be
  refused. Group commit and retained-bytes charging fixed it.
- **G2 counted too late.** Proposals were counted only at main-thread execution, not "before capture/queue delay". `GlobalProposalGuard`
  fixed it.
- **The rolling-share lease leaked.** It was released only on the dead-shard path, not on normal session close.
- **The metamorphic test caught a policy non-monotonicity** under inserted resets (seed 32 of `MitigationPropertyTest.addingUncertaintyNeverMakesMitigationMoreAggressive`). The chain and the group families were rewritten: consecutive groups since the last reset, and reset-independent per-group families.

## ACCEPTANCE MATRIX

| # | Requirement (brief section) | Status | Evidence |
|---|---|---|---|
| 1 | Module ownership: anticheat-mitigation, DAG respected, no Paper/NMS in the domain | PASS | ArchitectureTest (edges parsed from ARCHITECTURE.md; `mitigationStaysWithinThePhase10Boundary`) |
| 2 | Hard phase boundary: no kick/ban/points/labels/combat/Phase 11 checks | PASS | ArchitectureTest forbidden vocabulary and calls; Phase 11 modules empty |
| 3 | Consumes the immutable EvidenceSnapshot, no ledger internals; extra state modelled and bounded | PASS | INPUT CONTRACT; BOUNDS |
| 4 | One event is not a setback; explicit policy (DECISION 1) | PASS | `oneTwoOrThreeGroupsAreNeverEligibleAndFourAre`; `aSingleControlledInvalidIsNeverASetback` (impossible corpus: 0 eligible) |
| 5 | No legacy VL counter | PASS | ArchitectureTest (`vl`, `score`, `confidence` forbidden); policy is structural |
| 6–7 | Setback target is a previously certified safe state; explicit anchor criteria | PASS | SAFE ANCHOR MODEL; `anchorAdmissionRefusesEveryUnsafeState` |
| 8 | Never across session/world/dimension/capture epoch | PASS | `aWorldEpochChangeResetsAndEvidenceNeverCrossesIt`, `anotherSessionsEvidenceIsRefused`, `racesBeforeExecutionCancelTheStaleIntent`, main-thread `currentSession` |
| 9 | Unresolved teleport → no setback; Phase 7 authoritative | PASS | `everySafetyPreconditionSuppressesWithItsOwnReason` (UNRESOLVED_TELEPORT), main-thread recheck |
| 10 | Unresolved external motion → no setback | PASS | UNRESOLVED_EXTERNAL_MOTION (decision), `onExternalMotion` (pre-execution REJECTED) |
| 11 | Block dispute → no setback | PASS | BLOCK_DISPUTE (decision, target and current position) |
| 12 | Incomplete entity neighbourhood → no setback | PASS | ENTITY_STATE_INCOMPLETE |
| 13 | VALID/UNCERTAIN/INVALID never directly cause mitigation | PASS | policy continuity tests; legitimate corpus 0 |
| 14–15 | Immutable typed decision/result with typed reasons | PASS | `MitigationVocabulary` (DecisionOutcome, 44 Reasons, IntentState, Terminal) |
| 16 | One owned platform integration | PASS | `PaperMitigationExecutor`, exactly one `.teleport(` |
| 17–18 | Correction content: position only, rotation preserved, no unrelated state, legitimate velocity never erased | PASS | CORRECTION APPLICATION; VELOCITY SAFETY; ArchitectureTest forbidden calls |
| 19 | Post-setback resync boundary | PASS | POST-SETBACK RESYNC; `enforceAppliesExactlyOneCorrectionAndRecoversWithoutALoop` |
| 20 | No self-feeding loop | PASS | same test (no INVALID, no evidence, no second setback); `theCorrectionLifecycleRecoversWithoutFeedingItself` |
| 21 | Evidence not deleted by a setback; mitigation chain restarts (documented) | PASS | EVIDENCE REQUIREMENTS |
| 22 | Cooldown as documented safety limit, not detection strength | PASS | COOLDOWN (MITIGATION G1: 2 s, 3/30 s) |
| 23 | Duplicates never counted | PASS | `duplicatesNeverCountAndAGroupCountsOnceWhateverItsMembers`; property `addingDuplicatesNeverMakesMitigationMoreAggressive` |
| 24 | I I I / I U I / I V I / I I U I | PASS | `patternIIIIIsEligibleOnlyWithFourConsecutiveGroups`, `patternIUIResetsTheChain`, `patternIVIResetsTheChain`, `patternIIUIResetsTheChain` |
| 25 | Deterministic target selection | PASS | `theSameStreamAlwaysYieldsTheSameVerdict`; property determinism; fixture rerun identical |
| 26 | Bounded anchor history | PASS | 64 anchors; memory at bounds 46 976 B |
| 27 | Anchor provenance visible in diagnostics/replay | PASS | `SafeAnchor.describe`, `/papea2ac mitigation`, decision records |
| 28 | No live Bukkit world as history | PASS | anchors from Phase 8 results only; live world used only for application safety |
| 29 | Collision safety: UNKNOWN never clear | PASS | `theDestinationIsClearOnlyWhenEveryCellIsKnownAndEmpty`; `theTargetIsRecertifiedAgainstTheCurrentWorld` |
| 30 | Pose safety, revalidated in changed geometry | PASS | standing-box clearance re-certified at decision and on the main thread |
| 31 | Bounded target age | PASS | 2 s (MITIGATION G1) → RESYNC_REQUIRED |
| 32 | Most recent safe anchor preference | PASS | TARGET SELECTION; `target-selection-results.json` |
| 33 | Failure containment | PASS | `anInternalErrorNeverProducesACorrection`; executor catches, rejects INTERNAL_ERROR |
| 34 | Stable action identity, no concurrent duplicates | PASS | `theActionIdentityIsStableAndDistinguishesEverySourceOfTheDecision`; `reprocessingTheSameEvidenceNeverSchedulesASecondAction` |
| 35 | Sent ≠ applied | PASS | SENT / CONFIRMED / RECOVERED distinct; CAPTURE-ONLY preserved |
| 36 | Clean immutable domain boundary | PASS | MitigationIntent, Decision, SafeAnchor, action id, Outcome |
| 37 | Threading: shard-owned, main-thread application, no global lock per movement | PASS | owner-confined actor; the G2 guard is touched only at ENFORCE proposals |
| 38 | Session replacement | PASS | SESSION_ENDED; `currentSession` refusal |
| 39 | World change | PASS | `onEpochChange` → SUPERSEDED WORLD_CHANGED; main-thread world key |
| 40 | Teleport race | PASS | `racesBeforeExecutionCancelTheStaleIntent` |
| 41 | Velocity race | PASS | same (UNRESOLVED_EXTERNAL_MOTION) |
| 42 | Block change race | PASS | `theTargetIsRecertifiedAgainstTheCurrentWorld`; main-thread region validation |
| 43 | Evidence staleness | PASS | `evidenceThatLeftTheWindowNeverCounts` (EVIDENCE_EXPIRED) |
| 44 | No staff alert policy | PASS | diagnostic only; the loop-breaker and G2 log lines are operational, not detection alerts |
| 45 | Diagnostic command | PASS | `/papea2ac mitigation <player>` (DIAGNOSTICS) |
| 46 | Controlled test mode separated | PASS | side chain; `theSideChainDecidesOnItsOwnEvidenceButTargetsLiveAnchors`; `observeOnlyFindsTheSideChainEligibleAndAppliesNothing` |
| 47 | Property tests | PASS | `MitigationPropertyTest` (independent reference, 60 seeds) plus the decision-order tests |
| 48 | Metamorphic tests | PASS | uncertainty / duplicates / removed anchors / session change / epoch change / unknown collision: never more aggressive |
| 49 | Phase 8/9 legitimate corpus: 0 setbacks, 0 eligible | PASS | 32 451 transitions → 0 eligible, 0 intents, 0 setbacks |
| 50 | Final Phase 9 live trace: 0 setbacks | PASS | 22 108 VALID + 75 UNCERTAIN (count level) → 0 |
| 51 | Single controlled INVALID: no setback | PASS | impossible corpus: every case one event, 0 eligible |
| 52 | Synthetic mitigation corpus with exact expectations | PASS | 40 cases, 0 mismatches (`theSyntheticCorpusMatchesEveryExactExpectation`) |
| 53 | Application tests: one correction, no duplicate, stale/wrong session/wrong epoch rejected | PASS | `MitigationApplicationTest`, `MitigationIntegrationTest`, `onlyACurrentClearSameSessionIntentPassesTheMainThreadChecks` |
| 54 | Supported Paper API | PASS | `Player.teleport(Location, PLUGIN)`. The only reflection is the existing platform block-state normalizer (region capture). |
| — | TC25 durable decision capsules (DECISION 3 step 4) | PASS | TC25 section: 100 % at the configured capacity 32, both loads, three scenarios |
| — | G2 proposal-time counting and correlated-fault suspension | PASS | `GlobalProposalGuardTest`; `aCorrelatedFaultSuspendsGloballyBeforeAnyLoopBreaker` |
| 55–56 | Real client, legitimate course: 0 setbacks | PASS | VPS, remote vanilla clients: 16 484 transitions on `a464ad44…` plus the exact-final-jar recheck of 1 620 on `46758015…`; 0 INVALID, 0 evidence, 0 setbacks |
| 55 B | Real client, controlled mitigation | EXTERNAL QUALIFICATION REQUIRED | Blocked by G1: production transport correlation/certification UNSUPPORTED (P10-F1). Live: 4/4 groups eligible → SUPPRESSED TRANSPORT_UNHEALTHY, 0 effects. Offline correction pipeline PASS. |
| 57 | Latency qualification | PASS (real remote network only) | RTT 52–178 ms, jitter 6.5 ms, 0 setbacks, 0 INVALID; no artificial injection claimed |
| 58–59 | Setback UX safety and post-setback validation | PASS offline; live EXTERNAL QUALIFICATION REQUIRED | Offline integration test; live correction unreachable under G1 (P10-F1). The live player stayed connected and movable with unchanged state throughout. |
| 60 | Performance measured | PASS | PERFORMANCE |
| 61 | Memory measured and bounded | PASS | MEMORY |
| 62 | JMH (1.37, Temurin 25, 3 forks, 5×1 s, 10×1 s, -prof gc) | PASS | `benchmarks.json` |
| 63 | Fixed-cardinality metrics, no UUID labels | PASS | DIAGNOSTICS |
| 64 | Replay determinism | PASS | REPLAY (the transport-scope capsule limitation is recorded) |
| 65–68 | Phase 1–9 regressions | PASS | FINAL VERIFICATION |
| 69 | Forward defect protocol | PASS | FORWARD DEFECTS |
| 70–71 | Documentation and artifacts | PASS | this document; `docs/phase10-artifacts/` |
| 74 | Final verification lane | PASS | FINAL VERIFICATION |
| — | G1 unchanged; no bypass; production/default OBSERVE_ONLY | PASS | P10-F1 decision; ArchitectureTest (no ENFORCE from configuration); the qualification flag is not set on the final server |
| — | P10-F2 fixed with regression | PASS | `resumingFromQualificationEnforceRestrictsTheArbiterAndTheSessionsTogether` |
| — | Phase 11 absent | PASS | combat/checks/interaction modules empty (ArchitectureTest) |

## FINAL VERIFICATION

Lane `lane10.sh`, Temurin 25.0.4.1+1, HEAD `d3ab7de…` plus this working tree. It was run before the live qualification, and **rerun after the
P10-F2 fix**; the figures are from the rerun:

| Step | Result |
|---|---|
| `gradlew clean test` | **767 tests, 0 failures** |
| `gradlew nativeTest` | **138 tests, 0 failures** (885 unique) |
| `verifyMandatoryCoverage` | PASS, 127/127 |
| `replayConformance` | PASS (digests unchanged) |
| `movementPropertyCoverage` | PASS |
| `shapeCoverage` | PASS |
| `pluginJar` | `46758015c7e5f0e73ffbb17d86e9502d01c81c86b0bdf5392f6a346febbe38bd` (final; after P10-F2). The live-qualified predecessor was `a464ad44…`. |
| `physicsNativeDifferential` | 91 scenarios, 88 compared, **88/88 exact** single-step and chained, 3 deferred fluid, 0 oracle errors |
| Fixture regeneration (timeline, world, collision, reconstruction, timing, validation, evidence) | exit 0 |
| Phase 0–9 artifacts | unchanged (see note) |
| `mitigationFixtures` | 40 synthetic cases, 0 mismatches, deterministic; legitimate 32 451 → 0 eligible; impossible 3 290 → 0 eligible |
| TC25 | PASS at capacity 32 |
| `mitigationMemoryHarness`, `benchmarkMitigation` | recorded |
| ArchitectureTest | 14/14 |
| `git diff --check` | clean |
| TODO/FIXME/HACK | none |

Note on the Phase 0–9 artifacts: regeneration rewrote `phase5-artifacts/movement-property-coverage.json` and
`phase9-artifacts/window-results.json`. Both differ only in `Map.of` key order. It also rewrote
`phase9-artifacts/native-differential-results.json`, which differs only in the oracle server's run duration. All three were restored from
HEAD, so no frozen artifact changed.

Phase 1–9 regressions: none. The Phase 8 corpus is 32 451 / 0 INVALID and the impossible corpus 3 290 / 3 290. The Phase 9 corpus shows 0
false evidence, 3 290 / 3 290 impossible, and dedup PASS. The Phase 7 timing gates and Phase 5/6 physics/reconstruction are green inside
`test`/`nativeTest`.
