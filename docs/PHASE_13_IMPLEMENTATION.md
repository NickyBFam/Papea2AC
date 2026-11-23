# Phase 13 — Velocity / Knockback

Status: **IN PROGRESS — offline work complete where independent; melee push exclusion WAITING_FOR_CONTRACT PHASE12_REACH_CONTRACT_READY.**
Not committed, not baselined, no live qualification yet. The fenced timing profile is opt-in; the live engine keeps `PAPER_26_2` until the
user decides (it is coupled to Phase 12's marker-scheduling proposal).

Base: Phase 10 receipt `59caec5a0b4bde0c13dadca1807c0b0f3f039a7d`, plus Phase 11 snapshot 1 (`phase11-snapshot-1.tar`, SHA-256 `a23ff622…`;
CONTRACT_CHANGED-1 is pending and does not touch the parts Phase 13 uses), plus this working tree. Pinned pair: Paper 26.2 build 124 / vanilla
26.2 client (client jar SHA-256 `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290`). Toolchain Temurin 25.0.4.1+1.

ROADMAP: "13 Velocity/knockback — Shared simulation consumption and impulse ordering; no duplicate motion formula." Phase 13 adds no motion
formula. It makes the existing Phase 7 → 6 → 5 → 8 chain able to decide knockback application, and it records the outcome per influence.

## 1. Inventory: what existed (Phases 5, 7, 8, 9, 11)

- **Phase 5:** `PhysicsEnvironment.ExternalMotion` SET/ADD/UNRESOLVED applied before a tick's movement. Server knockback reaches the
  client as a velocity SET in the `LpVec3` codec (`LowPrecisionVector`, P7-F8) and explosions as a full-precision ADD.
- **Phase 7:**
  - Influences are registered at their outbound wire position (self entity only, P7-F1).
  - Each enumerates apply@j for every admissible tick j ≥ earliest, plus "later" until the 3 s application horizon (EXPIRED =
    unresolved, never "not applied").
  - Wire order is application order; there are at most 8 unresolved influences.
  - Marker answers never narrow (`pongBoundsApplication=false`).
- **Phase 8:** VALID iff some hypothesis × input explains; INVALID only with all 13 completeness dimensions true. Any pusher within 4
  blocks makes the entity neighbourhood UNKNOWN (P8-F3, P9-F5).
- **Phase 9:** one causal family per root (movement, velocity and ground mismatches from one simulation are one group).
- **Phase 11 WIP:** P11-F10. An influence that leaves the chain unresolved stays OUTSTANDING until a later answered marker, a confirmed
  correction or a world change.

**Gap (confirmed offline, `KnockbackApplicationFenceTest.aCancelledKnockbackIsNeverContradictedWithoutAnApplicationBound`, corpus class
C-cancel frozen 0/40):** the "later" branch explains a client that cancels knockback until the influence expires. After that nothing
contradicts it. Full anti-knockback is undetectable under the Phase 7 profile.

## 2. Native facts (P13-N1, P13-F2): the client-jar oracle

`ClientApplicationFactsTest` reads the pinned client jar with `java.lang.classfile`. It is skipped where the jar is absent and fails on
any other SHA. It pins the facts below in `docs/phase13-artifacts/client-application-facts.json`:

| Id | Fact |
|---|---|
| N1a | `ClientCommonPacketListenerImpl.handlePing` → `ensureRunningOnSameThread` → pong built and sent: **the pong is sent from the game thread** (Phase 7 said network thread: P13-F1, notes only) |
| N1b | `handleKeepAlive` → `sendWhen`, no thread hop: keep-alive is **not** a fence |
| N1c | `handleSetEntityMotion`, `handleExplosion`, `handleBundlePacket`, `handleMovePlayer` hop to the game thread first; `Entity.lerpMotion` = `setDeltaMovement`, not overridden on the LocalPlayer chain |
| N1d | `PacketProcessor`: one `ConcurrentLinkedQueue` drained by polling until empty; its only client call site is `Minecraft.runTick`, before the frame's `tick()` calls |
| F2a | `Minecraft.tick`: `handleKeybinds` (attacks) before `ClientLevel.tickEntities` (local movement); `MultiPlayerGameMode.attack` sends the ATTACK packet before `Player.attack` |
| F2b | `Player.causeExtraKnockback`: `multiply(0.6, 1.0, 0.6)` then `setSprinting(false)`; `hurtClient` is true-capable for RemotePlayer, VehicleEntity, ShulkerBullet, BlockAttachedEntity, ItemFrame, EndCrystal, ExperienceOrb, ItemEntity (base Entity: false) |

**Consequence (N1):** let V be an influence and P a ping written after it (higher wire ordinal), with the pong answered at capture
sequence s. Then V was applied to the client's motion before the first client tick whose packets follow s. LATENCY_COMPENSATION N1
permits this ("A proved target-specific response may tighten application intervals"). It is a client fact, not a trust in the client: a
forged early pong can only make a cheater's own account stricter, never an honest one.

## 3. Application deadline (opt-in `TimingProfile.PAPER_26_2_FENCED`)

- `TimingLedger` keeps the last 32 answered markers (`AnsweredMarker`: marker sequence and wire ordinal, answer sequence and nanos). It
  publishes them in `View.answeredMarkers` (copied only when an answer arrives).
- `TimingRequest.answeredMarkers` carries them; the runner passes them. The legacy constructors are kept.
- `TimingResolver` applies fences only when `profile.pongBoundsApplication()`, alignment is TICK_END_EXACT, no correction is pending, and
  no correction was written after the earliest in-scope influence:
  - `deadline(V)` = the first intermediate tick whose end follows the fence answer, else the target's tick; 0 = no fence in this
    interval.
  - Each influence takes ticks [earliest, deadline]. "later" exists only without a deadline. Wire order still holds.
  - An empty option set is a protocol/model contradiction, reported UNSUPPORTED, never INVALID.
- **Branching silent closure** (standing clients). If every influence that may apply inside the silent stretch has a fence,
  `TimingReconstruction.branchingClosure` runs EXACT per-tick frontiers up to the last decision tick:
  - at each tick a node may apply the next wire-order prefix of plan influences (as reconstruction branches);
  - after tick c, nodes still holding an influence whose deadline is c are dropped;
  - past the last decision tick the Phase 7 superset fixed point covers the rest, per pending class.
  - Otherwise the closure keeps the Phase 7 behaviour (incomplete).
- `TimingHypothesis.ClosurePlan` carries the plan; `ClosurePlan.NONE` and the legacy constructor keep Phase 7 shapes.
- Under `PAPER_26_2` the resolver code path is untouched (legacy branch). All pinned Phase 7/8 fixtures and digests are unchanged:
  839/839 tests passed after this change.

## 4. Attacker slowdown (P13-F2; Phase 11 lists it as open known defect P11-F27; the user kept the fix in Phase 13)

- **Defect in frozen Phase 8.** A legitimate sprint hit on a painting, item frame, leash knot, shulker bullet or end crystal (no pusher
  near) is a complete-certificate INVALID, because the client's own ×0.6 slowdown is in no model.
  `AttackSlowdownReproTest.aLegitimateSprintHitOnAnItemFrameIsACompleteInvalidUnderThePhase8Model` reproduces it.
- **Fix.**
  - `TimingEvent.LocalAttack` (from the inbound `SemanticPayload.Attack`).
  - `ExternalMotion.Operation.ATTACKER_SLOWDOWN` with `MovementProfile.ATTACKER_SLOWDOWN_HORIZONTAL = 0.6`. The simulator multiplies
    (0.6, 1.0, 0.6) and resets the sprint class to FOLLOWS; the envelope goes TOP.
  - `TimingResolver` places each attack on the tick whose movement follows it, and branches every slowdown count 0..n per tick after
    that tick's drained influences (cap `MAX_ATTACK_VARIANTS` = 8).
  - An unplaceable attack makes the account incomplete. That covers the closure, inexact alignment and an attack before a relative
    correction.
- **Limit.** Keep-sprint stays undetectable (both branches open: cooldown and held-item knockback are not observed).
- **Hand-over reference.** `Papea2AC-coordination/findings/phase13-to-phase11-01*`: the diff against 59caec5 verified on a clean
  export, 768/769, where the single failure is environmental (no .git).

## 5. Knockback attribution (observe-only)

- `KnockbackAttribution.attribute(request, result)` (simulation) reads the Phase 8 classification, the continuation frontier and the
  fence. It produces one outcome per in-scope influence: APPLIED / PENDING / EXCLUDED (FENCED_APPLICATION_EXCLUDED or
  ALL_APPLICATION_ALTERNATIVES_EXCLUDED) / UNRESOLVED.
- `VelocityRunner` (core, shard-owned) keeps the latest outcome per influence: settled once, 64 influences, 48 lines. It has fixed-label
  metrics and a published status, exposed as `PacketEngine.velocityStatus(UUID)`.
- It feeds neither evidence nor mitigation: the movement INVALID is already the one causal family (EVIDENCE_MODEL).
- Boundary: `VelocityArchitectureTest`, plus the Phase 6 boundary now excludes `Knockback*` by name, as it does for Timing* and
  MovementValidat*.

## 6. Synthetic corpus (`VelocityCorpus`, `VelocityCorpusTest`; seed 1300, 40 per class, live runner, truth by construction)

| Class | Frozen `PAPER_26_2` | Fenced |
|---|---|---|
| 7 legitimate classes (single moving / standing, combo-3, explosion ADD, stall without answer, latency 50/150/300 ms, hit-while-hit with attack slowdown) | 0 INVALID (10 615 transitions) | 0 INVALID (identical counts) |
| C-cancel | 0/40 detected | **40/40** |
| C-horizontal-0, C-vertical-0, C-reverse, C-scale-40-95 | 35/40 each | **39/40** each (1 UNCERTAIN) |
| C-delay-past-fence | 0/40 | **40/40** |

Gates (enforced by the test): 0 false INVALID in every legitimate class on both profiles; ≥ 80 % detection on the fenced profile for
cancel, H0, V0, reverse and delay-past-fence.

## 7. Uncertainty / completeness model (what stays UNCERTAIN)

- No fence yet: the influence stays pending and "not yet" stays open.
- Pong suppression: OUTSTANDING (P11-F10).
- Expired influences.
- A correction after the influence: fences off for the interval.
- Fence after the end of a silent stretch.
- Unplaced attacks; more than 8 slowdown variants; more than 8 influences (overflow).
- **Any pusher within 4 blocks** (all melee) and every existing Phase 8 reason.
- UNCERTAIN carries zero weight.

## 8. Waiting on PHASE12_REACH_CONTRACT_READY: melee push exclusion

Melee knockback needs a proof that no remote entity's box intersected the local player's box during the interval. The 26.2 client pushes
the local player only on intersection (`ClientLevel.getPushableEntities`). That proof needs entity dimensions (type, baby, pose, `scale`
attribute), which the Phase 12 contract owns, and interpolation bounds over Phase 11 network targets (the rendered position lies in the
hull of recent targets). The design is reserved and nothing is guessed until the contract lands.

## 9. Deferred / open

- Live (VPS) qualification on a Phase 13 candidate jar, after the user decides the fenced profile and markers.
- The `/papea2ac velocity` command, wired at integration.
- Keep-sprint detection (needs cooldown and held-item facts).
- Closure branching for attacks inside silent stretches.
- Canonical integration after the Phase 12 receipt.

## Forward defects

| Id | What | Status |
|---|---|---|
| P13-F1 | Phase 7 notes say pongs are answered on the network thread; the client answers on the game thread | notes fixed forward (TimingProfile javadoc); finding `phase13-to-coordinator-01` |
| P13-F2 | Client attacker slowdown absent from every model: complete false INVALID on a sprint-punch of a painting / item frame / crystal | FIXED in this tree (+ tests); findings `phase13-to-coordinator-02`, `phase13-to-phase11-01` |

## User-approved decisions

- **2026-10-03 06:23Z — fenced profile by default in the Phase 13 candidate jar** (user choice "Fenced by default", relayed by the coordinator;
  project-chat record `cmsg_01FUQ2LLJHEChW2uRE3N7p6jDhhAhF4bBb1UgCZab7ckKC`).
  - Preconditions: the offline corpus is rerun on Phase 12's combat-time marker cadence once thread 4 files it (one marker per tick while
    the player has attacked in the last 3 s, ≥ 50 ms apart, ≤ 16 pending). Evidence and mitigation stay observe-only.
  - Until those preconditions are met, `PacketEngine.timingProfile` stays `PAPER_26_2`, and `VelocityArchitectureTest` pins that state.
- **2026-10-03 — the attacker-slowdown fix (P13-F2) stays in Phase 13** (user choice on the decision card, relayed by the coordinator). Phase 11
  lists it as the open known defect P11-F27.

## Update 2026-10-03 (after the decisions)

- **Phase 12 combat-time marker cadence** (finding `phase12-to-phase13-01`: one ping per 50 ms for 3 s after each attack, ≤ 16 pending):
  - The corpus was rerun with a ping in every inter-tick gap, answered FIFO, as `VelocityCorpus.run(..., combatMarkers=true)`.
  - Fenced + combat markers: 0 false INVALID in all 7 legitimate classes, and **40/40 detected in every strong cheat class** (cancel, H0,
    V0, reverse, scale 40–95 %, delay past the fence).
  - Frozen + combat markers is unchanged from frozen alone (cancel 0/40): markers alone do not close the gap; the fence rule does.
  - Results are in `docs/phase13-artifacts/velocity-corpus-results.json` (four profiles). `VelocityCorpusTest` gates both fenced
    variants.
- **Test-harness defect found and fixed** while rerunning. `VelocityTestSupport.Harness` fed the runner the recorder's final ledger view,
  so with dense markers the 32-answer list had already evicted the fences. Production feeds the view at each event (`PacketEngine`
  applies the event, then calls `onEvent`). The harness now applies each event to the session ledger first, the engine order.
- **Default switched.** `PacketEngine.timingProfile = TimingProfile.PAPER_26_2_FENCED` (user-approved), pinned by
  `VelocityArchitectureTest`. `PAPER_26_2` is unchanged and still claims no fence. Evidence and mitigation stay observe-only.
- **Rebased onto Phase 11 snapshot 2** (SHA-256 `31db777d…`; 3 overlapping files merged cleanly with `git merge-file`). `gradlew test`
  860/860 and `nativeTest` 143/143.

## Update 2026-10-03: melee push exclusion (PHASE12_REACH_CONTRACT_READY v1 received)

- **Base.** Phase 12 snapshot 1 (SHA-256 `e6463b75…`, 33/33 file hashes OK) applied over Phase 11 snapshot 2. Three overlapping files
  (`PacketEngine`, `MovementValidationRunner`, `ArchitectureTest`) merged cleanly.
- **`core.PushExclusion`** (attached to the live runner as a `MovementValidationRunner.NeighborhoodProof`). When a relevant entity inside
  the Phase 8 radius would make the neighbourhood UNKNOWN, it tries to prove that no entity box can have intersected the player's box
  during the transition:
  - **Player region:** the last transmitted position ± 2e-4 (client send rule: every silent tick stays inside it), the target and the
    anchor states, at standing height and width × the player's scale (proven applied, or never synced = 1.0).
  - **Entity bound:** the Phase 12 shared bound `ReachValidator.possibleEntityBox`, built by `ReachRunner.entityInput`. It is the
    `TargetHull` interpolation bound of the network targets since the newest answered marker before the anchor, grown by the widest and
    tallest admissible dimensions (scale, baby, pose), plus 0.25 on every side (boats push with a box inflated by 0.2).
  - **Decision:** if no relevant entity intersects, the neighbourhood is NONE for that transition. If any bound is unknown or intersects,
    it stays UNKNOWN.
- **Shared code.** Two additive methods in Phase 12 files carry this. They were requested in `phase13-to-phase12-02` with the exact diff.
- **Tests.** `MeleePushExclusionTest`:
  - without the proof, melee stays UNCERTAIN;
  - with it, a cancelled melee knockback is INVALID and honest ones are VALID;
  - a touching attacker or an unknown bound stays UNCERTAIN;
  - the Phase 12 bound for a player is the 0.6 × 1.8 box, and an unknown scale grows it to scale 16.
- **Corpus** (`VelocityCorpusTest`, fenced + combat markers):

  | Attacker | Legitimate classes | Cheat classes |
  |---|---|---|
  | 2.5 blocks away, on the side the knockback pushes from | 0 false INVALID (≈ 0.3 % UNCERTAIN when the walk turns toward the attacker) | 90–97.5 % detected |
  | 0.4 blocks away | 0 false INVALID, every transition UNCERTAIN | none detected (contact possible) |

- **Limitations.**
  - Entities whose Phase 12 bound is unknown keep melee UNCERTAIN: VARIABLE types, dynamic interpolation, mounted or locally displaced
    entities, unknown lifetime, unknown scale (the bound grows to scale 16).
  - A branching closure that stops on an incomplete reconstruction is labelled SEARCH_LIMIT rather than the underlying reason. That is a
    label only; the result is UNCERTAIN.
