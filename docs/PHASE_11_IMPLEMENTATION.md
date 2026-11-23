# Phase 11 — Combat Engine (descriptive combat-domain infrastructure)

Status: **READY FOR HUMAN REVIEW**. The exact final candidate jar `b6699bed23fe1ce2cf85337930999b5637b4bb62742b9a5430476c3db1707c9c` passed VPS round 3 (solo + PvP + Nether: 1 024 attacks, all found, 0 positive evidence, 0 mitigation effects, 0 movement INVALID). It carries the P11-F10 fix (found live in round 2) and the review-round fixes P11-F12 … F26. Open by owner decision: P11-F11 (accepted limitation) and P11-F27 (known defect, fix kept in Phase 13). Not committed, not baselined, not pushed; no Phase 12 work.
Base: Phase 10 implementation baseline `71f4414c93b0946344c48863f5953077fea54e14`, receipt `59caec5a0b4bde0c13dadca1807c0b0f3f039a7d`
(HEAD = origin/master, unchanged). Pinned pair: Paper 26.2 build 124 (`.phase1-runtime/versions/26.2/paper-26.2.jar`) / vanilla 26.2 client.
Toolchain Java 25 (Temurin 25.0.4.1+1). Final candidate jar `b6699bed23fe1ce2cf85337930999b5637b4bb62742b9a5430476c3db1707c9c` (P11-F10 and P11-F12 … F26; VPS-qualified in round 3). Rounds 1–2 ran `948e06ca…`; the intermediate post-F10 jar `837e1fa7…` was superseded before any live round.

Phase 11 builds the data a later attack-geometry engine (Phase 12) will consume, and judges nothing. For every ATTACK packet a client
sends, it produces an immutable, deterministic, replayable **AttackContext**. The context names:

- the target **incarnation** (network id + generation);
- the **network state of that target as the attacker's own client had been sent it**, just before the attack, with per-field exactness;
- where the attacker's own movement and rotation came from (Phase 2 timeline, Phase 8 validation);
- the timing context (Phase 7);
- an explicit **completeness** record listing every reason the context is not exact.

Missing, evicted, pre-spawn, reused, cross-epoch, cross-session, gap-tainted and unsupported states are first-class answers. None of them
ever becomes a position.

Phase 11 does not decide whether an attack was legitimate. There is no reach, hitbox, ray, rotation, cadence, click, KillAura or
aim logic, no evidence, no mitigation, no kick or ban, and no score. An observed ATTACK packet is not a claim that the server accepted it, that
anything was hit, or that damage followed.

## SCOPE

In scope (implemented, tested, and qualified as stated in the ACCEPTANCE MATRIX):

- **Combat packet normalization.** Serverbound ATTACK / INTERACT become typed combat observations. Clientbound entity packets become
  world-owned entity observations. Two Phase 1 normalizer defects were fixed forward (P11-F1 pose, P11-F2 sync/teleport).
- **Attack observations** with stable, deterministic identity (session + capture sequence), kept strictly apart from non-attack
  interactions.
- **Target identity**: `EntityKey(session, worldEpoch, networkId, incarnation)`; network-id reuse always creates a new generation.
- **Client-observed target state from clientbound packets only**: the 26.2 client's delta codec, position sync, teleports, rotation, head yaw,
  pose, shared flags and passenger relation, bit-exact where the client is deterministic (native differential).
- **Lifecycle**: spawn (bundled or not), relative moves, position sync, absolute/relative teleport, rotation, head rotation, metadata, passengers,
  removal, replacement spawn, world-epoch change and session close.
- **Historical lookup** with the brief's twelve statuses, including QUERY_LIMIT for bounded range queries, and no nearest-match answers.
- **AttackContext**: attacker timeline provenance, Phase 8 validation provenance, timing provenance and completeness.
- **Bounded history** charged to the observer world's private tier, with explicit eviction.
- **Replay**: EntityCapsule / CombatCapsule projections, transport capsule schema 3, eight pinned traces.
- **Diagnostics** (`/papea2ac combat <player> [entities]`) and fixed-cardinality metrics.
- **Tests**: native differential, property, metamorphic, fuzz, attack corpus, memory harness and JMH.

Out of scope (absent by construction, enforced by `ArchitectureTest.combatStaysWithinThePhase11Boundary`):

- reach, hitboxes, entity dimensions, rays, occlusion, eye height (Phase 12);
- rotation heuristics (15), KillAura / combat heuristics (16), click / CPS analysis (17);
- combat evidence (Phase 9 is untouched and receives nothing from combat); mitigation (Phase 10 is untouched and receives nothing from combat);
- kick, ban and alerts;
- interpolation progress / the rendered target position (see TRUTH MODEL; explicitly deferred);
- any claim of server acceptance of an attack (the production transport is capture-only).

## OWNER

| Module | Phase 11 content |
|---|---|
| `anticheat-combat` (owner) | `CombatEngine` (per-session engine: attack contexts, interactions, bounded rings, metrics), `CombatObservation`, `AttackContext`, `CombatProvenance` (Attacker, Timing, MovementValidation), `CombatCompleteness`, `CombatReplayMapper` |
| `anticheat-world` | `EntityHistory` and its vocabulary: `EntityObservation`, `EntityKey`, `EntityRevision`, `EntityLookup`, `EntityReplayMapper`. COMBAT_MODEL: "TargetHistory belongs to the observer WorldView". The bytes are charged to the observer world's private tier (`ObserverWorld.attachEntityHistory`). |
| `anticheat-version` | `CombatProfile` `paper-26.2-124-combat`: the attack packet shape, the delta quantum, the teleport/codec-base rules, the pose/flag data ids and pose names, and "interpolation progress not modelled" |
| `anticheat-replay-schema` | `EntityCapsule` and `CombatCapsule`. `CombatCapsule` is COMBAT_MODEL's `CombatSnapshotDto`; the name follows the accepted `TimelineCapsule` / `WorldCapsule` precedent. |
| `anticheat-core` | `CombatObservationMapper` (the DAG-mandated protocol → domain translation), `CombatRunner` (per-session composition on the shard), `PacketEngine` wiring, and diagnostics rendering (the platform never sees combat types) |
| `anticheat-protocol` | payload schema 3: tag 80 `EntityPositionSync` (P11-F2) and tag 81 `LegacyEntityPosition` (P11-F9) |
| `anticheat-platform-paper` | normalizer fixes (P11-F1, P11-F2) and the `/papea2ac combat` command, which prints the core-rendered lines |

DAG: combat → model, replay-schema, version, player, world, collision. Only checks and core may depend on combat; the platform does not.
Nothing below combat depends on it.

## PHASE BOUNDARY

`ArchitectureTest.combatStaysWithinThePhase11Boundary` enforces the boundary:

- **Vocabulary scan.** It scans every combat source, the world entity-history sources, the core composition and the version profile (comments
  stripped). It rejects reach / hitbox / ray / occlusion / line-of-sight / KillAura / aura / CPS / click-rate / cadence vocabulary, verdict,
  evidence, mitigation and punishment vocabulary, platform types, and reflection.
- **Imports.** Combat imports only its approved edges.
- **Dependents.** No module below combat depends on it.
- **Later-phase classes.** No Reach* / Hitbox* / RayTrace* / Occlusion* / KillAura* / Aura* / Click* / Cps* / Cadence* / RotationAnalysis* /
  AimAnalysis* / CombatHeuristic* class exists anywhere in production sources.
- **Composition.** `CombatRunner` references neither evidence nor mitigation.

The architecture suite passes 15/15.

Phase 9 and Phase 10 are unchanged: combat output is consumed by nothing but the diagnostic command and replay. Live combat produced 0
positive evidence and 0 mitigation effects (REAL-CLIENT VPS QUALIFICATION).

## TRUTH MODEL

1. **Target state is what the observer's client was SENT.** The Phase 1 capture records clientbound packets in the session's ordered sequence.
   The entity history is rebuilt from those packets only. No Bukkit or NMS entity state is read for combat, now or historically, and there
   is no "current server position" fallback anywhere (`CombatEngineTest.everyTargetMissMapsToItsOwnReasonAndNeverToAPosition`,
   `attackerProvenanceNeverFallsBackToACurrentPosition`).
2. **The network target, not the rendered position.** For every incarnation the model holds the client's `VecDeltaCodec` base and the
   network target: the position the packets set, VecDeltaCodec-exact. The client's interpolation endpoint equals it unless the client
   displaced the entity locally during interpolation (`InterpolationData.addDelta`: pistons, `positionRider`), a sync or teleport of more
   than 64 blocks snapped during an active interpolation (the snap is not a cancel), or a rotation-only move re-targeted to the rendered
   position (P11-F24). Phase 12 must treat the endpoint as the network target plus an unmodelled local displacement, never as equal to it.
   The model does not hold the interpolation progress. COMBAT_MODEL permits
   reconstructing "interpolation endpoints/progress" but assigns no phase to progress, and LATENCY_COMPENSATION forbids claiming a client
   application frontier. Progress is therefore **explicitly deferred**, and every context carries the standing limitations
   `INTERPOLATION_PROGRESS_NOT_MODELLED` and `CLIENT_APPLICATION_FRONTIER_UNCLAIMED`. Nothing labels an interpolated position exact.
3. **An observed ATTACK is only an observation.** Every context carries the standing limitation `SERVER_OUTCOME_UNSUPPORTED`. On the
   capture-only production transport there is no certified server outcome (compare P10-F1).
4. **The client's application rules are pinned.** They come from the pinned client jar
   (sha256 `40896ee9…fa290`, bytecode listings in `native-differential-results.json`) and are differentially tested against the server jar's
   natives:

| Clientbound packet | 26.2 client handler | Model |
|---|---|---|
| AddEntity | `recreateFromPacket` → `syncPacketPositionCodec` (base) + `snapTo` | new incarnation; base = target = packet position; pitch `clamp(p % 360, −90, 90)` (P11-F23); pose STANDING, flags 0 (see Design decisions), except a Warden with data 1 (EMERGING on the client): pose UNKNOWN (P11-F22) |
| MoveEntity Pos / PosRot / Rot | `decode(base, delta)` then `setBase(decoded)` then `moveOrInterpolateTo` | `CombatProfile.decodeAxis`: a zero delta keeps the base bit-exact, otherwise `(round(base*4096)+d)/4096`; base = target = decoded; pitch clamped like `Entity.setXRot` (P11-F23) |
| EntityPositionSync | `setBase(position)` then `moveOrInterpolateTo` | base = target = packet position; pitch clamped; restores exactness after a vehicle relation (P11-F12) |
| TeleportEntity | `setValuesFromPositionPacket`: `calculateAbsolute(PositionMoveRotation.of(entity), change, relatives)`; **no codec call** | target = `(relative ? current : 0) + packet` (pitch clamped); base untouched; a relative component is INTERPOLATION_DEPENDENT (it resolves against `PositionMoveRotation.of(entity)`, i.e. the interpolation target while interpolating and the rendered position otherwise, neither of which the model holds; P11-F24) |
| RotateHead | head yaw | head yaw |
| RemoveEntities | removes; `setRemoved` makes every passenger stop riding | incarnation ends (DESPAWNED); its riders are dismounted (VEHICLE_DEPENDENT until a sync, P11-F12) |
| SetEntityData | per accessor | pose (id 6, POSE) by name; shared flags (id 0, BYTE) raw; nothing else is kept |
| EntityEvent 3 | `LivingEntity.handleEntityEvent` → `die()` → `setPose(DYING)` | pose UNKNOWN until the next pose data (P11-F22) |
| SetPassengers | `positionRider` places riders; `stopRiding` leaves them in place; a vehicle carrying the local player is client-authoritative | `vehicleId` on the passenger; TARGET_MOUNTED; rider and observer's vehicle VEHICLE_DEPENDENT (P11-F12, P11-F13) |
| TakeItemEntity | removes a picked-up arrow / trident / emptied item client-side | not modelled: the incarnation ends at the server's RemoveEntities (P11-F21; such entities are not legal attack targets) |
| MoveMinecart (lerp steps) | new-minecart lerp | not reconstructed: the incarnation becomes UNSUPPORTED (sticky) |
| any update for an unknown id | dropped | dropped and counted (`unknownUpdates`) |

The server never sends TeleportEntity for ordinary tracking (`ServerEntity` uses deltas plus EntityPositionSync). The only sender is
`Entity.sendTeleportTransitionToRidingPlayers`: the relative variant goes to the controlling passenger, the absolute one to other riders.

## ATTACK OBSERVATION MODEL

- **26.2 wire.** `ServerboundAttackPacket(entityId)` carries the target network id only: no position, no hand. It is a distinct packet
  (`CombatProfile.attackPacketIsDistinct`). `ServerboundInteractPacket(entityId, hand, location, usingSecondaryAction)` always carries its
  location. There is no INTERACT/INTERACT_AT split in 26.2. These facts are pinned against the native record components.
- **Interact location (P11-F25).** The location is the hit point minus the target's client-side position (`MultiPlayerGameMode.interact`),
  LP-quantized (`Vec3.LP_STREAM_CODEC`) and server-decoded: an offset from the target, never a world position. The `x / y / z` components keep
  their names; `CombatProfile.interactLocationRelativeToTarget()` and this note define them.
- **Spear STAB (P11-F19).** With a piercing weapon in the main hand, `Minecraft.startAttack` sends `ServerboundPlayerActionPacket(STAB)`
  instead of an attack packet. It names no target (the server's `PiercingWeapon.attack` picks them). It becomes `CombatObservation.PiercingAttack`:
  counted (`stabs`), never an attack context, never a target resolution, never in the attack ring or counters. Kinetic (charge) spear damage
  has no serverbound packet at all, and an ATTACK sent while holding a spear is recorded as an ATTACK although Paper ignores it (the held item is
  not modelled; SERVER_OUTCOME_UNSUPPORTED covers it).
- **Observations.** `CombatObservation.Attack(sequence, attemptId, ingressOrdinal, targetId, capturedNanos)`,
  `Interaction(..., hand, x, y, z, secondaryAction)` and `PiercingAttack(sequence, attemptId, ingressOrdinal, capturedNanos)`. All are immutable and
  validated at construction (malformed values are rejected, never repaired: `CombatFuzzTest.malformedValuesAreRejectedAtConstructionNeverInside`).
- **Degraded observations (P11-F14).** An ATTACK or INTERACT whose own observation carries a clientbound coverage break (a loss latched
  before it; the world may have recorded only the break) is still counted and given a context. A target answer that would claim provable
  state (FOUND, UNKNOWN, PRE_SPAWN, DESPAWNED) becomes INCOMPLETE_COVERAGE.
- **Identity.** An attack's identity is `session + capture sequence`, rendered `A<sessionEpoch>:<sequence>`. It is unique and deterministic,
  with no wall-clock component.
- **Separation.** An INTERACT is recorded as an `InteractionRecord` with its own target resolution. It never enters the attack ring or the
  attack counters (`anInteractionIsNeverAnAttack`, corpus `interaction-is-not-an-attack`, native `ServerboundInteractPacket` → Interaction).
- **Self.** The observer's own entity is the attacker, owned by the Phase 2 timeline. It is excluded from the entity history, so an attack on
  it resolves UNKNOWN (corpus `self-is-not-a-target`).

## ENTITY IDENTITY / GENERATION

`EntityKey(session, worldEpoch, networkId, incarnation)`. Incarnations are issued monotonically per session history. A new incarnation
starts on:

- a spawn after a removal;
- a spawn over a live id (`ClientLevel.addEntity` replaces the entity: the old one ends `REPLACED_BY_SPAWN`);
- any spawn in a new world epoch.

A key lookup never resolves to a different incarnation:

- `ID_REUSED` — the key's incarnation ended and the same network id belonged to a later incarnation at the queried sequence;
- `SESSION_MISMATCH` / `WORLD_EPOCH_MISMATCH` — the key names another session or epoch.

Packet-observed identity (UUID and type from AddEntity) is preserved per incarnation for players and non-players alike. The network id
remains part of the lifecycle identity.

## ENTITY LIFECYCLE

Spawn → movement → attack → removal / replacement → epoch change → session close are each covered by EntityHistoryTest, the corpus, the
property tests and the fuzz. Rules:

- **Baseline.** An incarnation's first valid baseline is its spawn (AddEntity): target and codec base exact. A move, sync, teleport, data or
  passenger update for an id with no live incarnation is dropped, exactly as the client drops it, and counted. It never fabricates a position
  (`anUpdateBeforeAnyBaselineIsNeverFabricated`). The id stays UNKNOWN, or INCOMPLETE_COVERAGE after a coverage break, until a spawn.
- **Removal** ends exactly the live incarnation (DESPAWNED). An attack on a removed id answers DESPAWNED and never binds to a later reuse.
- **World epoch change** (respawn / dimension change, the observer world's epoch) ends every incarnation and discards the epoch's structures.
  An attack in the new epoch never resolves to the old epoch's id history, and the old keys answer WORLD_EPOCH_MISMATCH.
- **Session close** ends everything. A reconnect is a new `SessionKey`, and nothing crosses sessions.
- **Bundles.** A bundle's children apply in wire order under the bundle's sequence, with increasing sub-indices, so an attack (always a later
  sequence) sees all of them or none.
- **Legacy captures** (payload schema 1/2): see P11-F9. A tag-16 position with an empty relative set is kept ambiguous and makes the
  incarnation UNSUPPORTED rather than guessed exact.

## TARGET STATE MODEL

`EntityRevision` (immutable) holds:

- identity and ordering: sequence, sub-index, capture nanos (the observation's capture clock, P11-F15), cause (SPAWN / MOVE / MOVE_ROT /
  ROT / SYNC / TELEPORT / TELEPORT_REL / HEAD / DATA / MOUNT / DISMOUNT / DISMOUNT_VEHICLE_GONE / OBSERVER_ABOARD / OBSERVER_LEFT / POSE:* /
  UNSUPPORTED:*);
- position: the target x/y/z and its `positionStatus` (EXACT / INTERPOLATION_DEPENDENT / UNSUPPORTED / VEHICLE_DEPENDENT), plus the codec
  base x/y/z;
- rotation: yaw, pitch and `rotationStatus`, plus head yaw;
- metadata: pose (or `poseOpaque`), shared flags, metadata revision;
- the vehicle id.

Numerics are the client's:

- the delta decode is bit-exact against the native `VecDeltaCodec` (200 000 single cases and 71 975 chained steps);
- absolute teleports are bit-exact against `PositionMoveRotation.calculateAbsolute` (20 000 cases, P11-F7).

UNSUPPORTED is sticky per incarnation: a later packet never re-claims exactness for a mechanism the model does not reconstruct.
VEHICLE_DEPENDENT (P11-F12, P11-F13) covers three cases:
- a rider, from its mount, including an entity riding the observer;
- a dismounted rider, or the rider of a removed or replaced vehicle;
- the vehicle carrying the observer, which is client-authoritative (its rotation too, while the observer is aboard).

It survives relative moves and teleports and is cleared only by an EntityPositionSync once no relation holds. The server forces that sync at the
rider's next tracker update. The revision exposes no mutable reference; lookups return values.

**Design decisions** (for review):

- **D1 — history owner.** `EntityHistory` lives in `anticheat-world` (COMBAT_MODEL: "TargetHistory belongs to the observer WorldView"). It
  shares the world's private byte tier. The combat module reads it; it never owns target state.
- **D2 — pose at spawn.** STANDING (flags 0) at spawn is the client's actual synced state, not a default filled in for missing data. The
  client entity defines `DATA_POSE = STANDING`, and pairing sends exactly the non-default values
  (`ServerEntity.trackedDataValues = entityData.getNonDefaultValues()`, bytecode-checked, sent by `sendPairingData` in the spawn bundle). So
  "no pose value received" means "the client holds STANDING". A pose that genuinely is unknown (an OPAQUE field from an older capture, or a
  pose name outside the profile) is UNKNOWN, never STANDING, and adds TARGET_POSE_UNKNOWN (brief section 46). Two client-side pose changes
  are exceptions (P11-F22): a Warden spawned with data 1 is EMERGING on the client, and entity event 3 runs `die()`, which sets DYING before
  the server's pose data. Both make the pose UNKNOWN until the next pose data. A remote player's pose is the synced value only
  (`RemotePlayer.updatePlayerPose` is a no-op), so D2 holds for players.
- **D3 — deep-slot policy.** The 32 deep histories go to the 32 live entities whose network target is nearest the observer's claimed position
  (retention priority only, never a verdict). The core rebalances once per second of server ticks (P11-F8). Ended incarnations keep their
  deep ring and slot until the horizon leaves only their final revision (P11-F5).
- **D4 — tier sharing order.** When the observer world needs room it sheds world history first, then entity deep revisions (optional depth),
  then chunks. Entity summaries are never shed for world data. A spawn that cannot be stored even as a summary is refused and marks the epoch
  capacity-incomplete.
- **D5 — interpolation deferral.** Only the network target is modelled; it is not always the interpolation endpoint (P11-F24); see TRUTH MODEL.
- **D6 — world vs entity-summary priority is first-come (P11-F11, accepted by the owner as a known limitation on 2026-10-03).** D4 orders what is shed, not who wins a full
  tier. The world can evict its farthest chunks to make room (its entity term counts entity bytes), but an entity summary can shed only
  entity deep revisions and ended incarnations, never a chunk. So when ordinary block traffic fills the 768 KiB floor first, new spawns
  are refused and the epoch latches INCOMPLETE_COVERAGE (capacity). The frozen PERFORMANCE / WORLD_MODEL text shares one world/entity
  quota, calls 256 summaries a ceiling rather than a preallocation, and allows the floor to "cause local explicit coverage loss". It sets no
  priority between chunks and summaries, so Phase 11 does not invent one (brief sections 23–24).

## ORDERING

- **Ordering source.** Everything follows the Phase 1 ordered session sequence (`Observation.sequence`); wall-clock time is never used for
  ordering. `CombatRunner.apply` runs after the observer world and the player timeline have seen the same observation.
- **Lookup semantics.** A lookup at sequence S answers the state in effect just before S. A target update at N followed by an attack at N+1
  is in the attack's context; an attack at N followed by an update at N+1 is not, even when callbacks run close together
  (`orderingDecidesWhichStateAnAttackSees`, the metamorphic shift test, and corpus `attack-immediately-before/after-relative-move`,
  `attack-before/after-teleport`).
- **Rejection.** Out-of-order or duplicate input — an entity observation not strictly after the last `(sequence, subIndex)`, or an attack
  not after the engine's last — is refused, counted and never applied (P11-F6: the refusal republishes the status).
- **Duplicates follow Phase 1 semantics.** Every observation has a unique ordered sequence, so a re-delivered spawn, removal, relative update
  or attack (same sequence) is refused and adds neither certainty nor a generation. A genuinely new packet is not a duplicate:
  - a second AddEntity for a live id at a later sequence is a replacement (the 26.2 client builds a new entity, and so does the model);
  - a second removal of an ended id is dropped like any update for an unknown id;
  - a relative update re-sent at a later sequence is applied again, exactly as the client applies it.

## TIMING PROVENANCE

`CombatProvenance.Timing.of(TimingLedger.View, attackSequence, attackNanos)` is descriptive only (Phase 7 semantics unchanged):

- **Status.** `TICK_ALIGNED` (the last client tick end strictly before the attack), `NO_TICK_END_YET`, `TICK_END_UNSUPPORTED`, or
  `UNAVAILABLE` (no ledger).
- **RTT summary.** Samples, min, max, smoothed, jitter and age. `rttStale` is set when the newest sample is older than LATENCY_COMPENSATION's
  ten-second marker retention.

RTT is never an allowance, a rewind or a tolerance. Remote network uncertainty can only add incompleteness
(`TIMING_UNAVAILABLE`, `TIMING_TICK_END_UNAVAILABLE`, `TIMING_RTT_UNKNOWN_OR_STALE`), never exactness.

**Attacker provenance** comes from `PlayerTimeline.stateBefore(attackSequence)`: the latest record strictly before the attack, never
crossing a world epoch and never a later aim (COMBAT_MODEL). The status mirrors the timeline lookup: FOUND / EVICTED / PRE_CAPTURE /
NEVER_EXISTED / WORLD_EPOCH_CROSSING / INCOMPLETE_COVERAGE. The claimed position is recorded as a raw client claim with its sequence and
outcome. Rotation is recorded with a known flag, and an unknown rotation adds ATTACKER_ROTATION_UNKNOWN. The core supplies the Phase 8
validation provenance (anchored, anchor kind, last classification, last end sequence); combat does not depend on simulation.
`movementValidatedThrough` is true only when a VALID anchored chain covers the claimed position's sequence. Raw claims are never promoted to
eyes: COMBAT_MODEL B2's certified-trajectory eyes are Phase 12.

## COMPLETENESS MODEL

`CombatCompleteness(status, reasons, standing)`. `COMPLETE` holds exactly when `reasons` is empty, and it is never a verdict either way.

- Target reasons: TARGET_UNKNOWN, TARGET_PRE_SPAWN, TARGET_DESPAWNED, TARGET_ID_REUSED, TARGET_EVICTED, TARGET_EPOCH_MISMATCH,
  TARGET_SESSION_MISMATCH, TARGET_FUTURE_SEQUENCE, TARGET_COVERAGE, TARGET_UNSUPPORTED_MOTION, TARGET_QUERY_LIMIT,
  TARGET_POSITION_INTERPOLATION_DEPENDENT, TARGET_ROTATION_INTERPOLATION_DEPENDENT, TARGET_POSE_UNKNOWN, TARGET_MOUNTED,
  TARGET_VEHICLE_DEPENDENT (P11-F12, P11-F13), TARGET_PLAYER_INFO_UNTRACKED (every player target, P11-F20).
- Attacker reasons: ATTACKER_HISTORY_UNAVAILABLE, ATTACKER_ROTATION_UNKNOWN, ATTACKER_MOVEMENT_UNVALIDATED.
- Timing reasons: TIMING_UNAVAILABLE, TIMING_TICK_END_UNAVAILABLE, TIMING_RTT_UNKNOWN_OR_STALE.
- Standing limitations, always present on the current transport and model: SERVER_OUTCOME_UNSUPPORTED, INTERPOLATION_PROGRESS_NOT_MODELLED,
  CLIENT_APPLICATION_FRONTIER_UNCLAIMED.

The mapping from each lookup status to its reason is total, and every miss maps to its own reason
(`everyTargetMissMapsToItsOwnReasonAndNeverToAPosition`).

## HISTORICAL LOOKUP

The API (owner thread, value results, no Bukkit objects, no live references):

- `lookupId(session, epoch, networkId, sequence)` — whichever incarnation owned the id just before `sequence`;
- `lookupKey(EntityKey, sequence)` — one incarnation, never a reuse;
- `revisions(EntityKey, from, to, limit)` → `RevisionRange` — typed, never silently truncated or padded;
- `liveEntities([limit])` — a diagnostics snapshot.

An `EntityLookup` carries status, key (identity + generation), type, UUID, the revision exactly when FOUND, the spawn / end / queried
sequences, the world epoch, and a detail string. The statuses are exactly the brief's twelve (the frozen names):

| Status | Meaning (never a position) |
|---|---|
| FOUND | the revision in effect just before the queried sequence (per-field exactness inside) |
| UNKNOWN | no incarnation of this id was observed in the epoch, and nothing makes that unprovable |
| PRE_SPAWN | spawned at or after the queried sequence |
| DESPAWNED | removed / replaced / epoch-ended before the queried sequence, id free |
| EVICTED | the needed state was retained once and has aged out (ring, horizon, demotion, capacity) |
| ID_REUSED | the key's incarnation ended; the id belonged to a later incarnation |
| WORLD_EPOCH_MISMATCH / SESSION_MISMATCH | the query names another epoch / session |
| FUTURE_SEQUENCE | not observed yet |
| INCOMPLETE_COVERAGE | a clientbound coverage break (sequence gap, a loss-class latch reason, a compromised capture position — the world's truth, reused), or the 256-entity capacity |
| UNSUPPORTED | moved by an unreconstructed mechanism (minecart lerp steps, legacy ambiguous position) |
| QUERY_LIMIT | a range query matched more revisions than its limit (≤ 96); the prefix is flagged partial |

Key lookups claim no more than the history proves (P11-F17):
- An ended key queried after a later coverage break or capacity refusal answers INCOMPLETE_COVERAGE, not DESPAWNED, because a lost respawn of
  the id is possible.
- A key whose incarnation was never issued for that network id (or epoch) answers UNKNOWN, not EVICTED. The check uses a 256-entry record of
  forgotten (incarnation → id) pairs; an older key is assumed issued, since keys only come from the history.

`target-lookup-results.json` reproduces one case per status (12/12). A `RevisionRange` is FOUND only when complete. Otherwise it is a flagged
partial prefix: EVICTED (older revisions aged out), INCOMPLETE_COVERAGE (a break inside the range) or QUERY_LIMIT.

## BOUNDS / EVICTION

| Bound | Value | On reaching it |
|---|---|---|
| retained incarnations (live + recently ended) | 256 (256 B charged each) | the oldest ended incarnation is forgotten first; with 256 live, a new spawn is refused → INCOMPLETE_COVERAGE (capacity), never a silent drop |
| deep histories | 32 (nearest, D3) | others keep only their current revision (historical lookups say EVICTED) |
| revisions per deep history | 96 (160 B charged each) | the oldest is evicted (EVICTED for the sequences it covered) |
| revision horizon | 3 s (the newest older revision is kept as the floor) | trimmed; an ended incarnation with only its final revision releases its slot |
| attack contexts / interactions per session | 64 / 64 | oldest dropped (diagnostic ring; contexts are consumed at creation) |
| byte tier | the observer world's private tier (768 KiB floor) | D4 order |

Coverage truth reuses the world's (P3-F3). Loss-class latch reasons, compromised capture positions and sequence gaps break coverage.
EXPIRED, OUTCOME_UNKNOWN, CANCELLED, TRANSFORMED and PIPELINE_UNCOVERED are not breaks, and benign control-plane observations never create
one. A gap taints from the first missing sequence (P11-F4).

## REPLAY

- **Projections.** `EntityReplayMapper` exports `EntityCapsule` (schema 1) and `CombatReplayMapper` exports `CombatCapsule` (schema 1).
  Each is primitive-only, canonical-encoded (fixed field order, raw IEEE bits) and SHA-256 digested. The combat projection has no verdict
  field. State is rebuilt by replaying the transport observations; the projection is the comparison target, not a second source of truth.
- **One clock (P11-F15).** The entity history stamps revisions and trims its 3 s horizon with the observation's capture clock
  (`Observation.capturedNanos`), the same clock as Phase 7 timing and `CombatObservation.capturedNanos`. Its output depends on the observations
  only, never on when the shard processed them (`Phase11QaAdversarialTest`, the backlog case: identical range answers and digests).
- **Transport.** Transport capsule and payload schema 3: tags 80 and 81 only (schema-1/2 bytes are unchanged and still decode; the 80/81
  tags are rejected inside them). `replayConformance` digests are unchanged.
- **Pinned traces.** Eight pinned traces (stationary, moving, teleporting (with a sync), despawn/reuse, player, mob, gap/incomplete, epoch
  transition), taken from the corpus, are each fed twice into fresh `CombatRunner`s and once through encoded schema-3 capsules. A loss
  closes a capsule segment with a Barrier; the next segment opens with a Checkpoint, as the recorder does. All three replays equal the live
  state digest (`replay-results.json`, 0 failures). The capsule round trip includes EntityPositionSync.

## THREADING

- **Shard confinement.** `EntityHistory`, `CombatEngine` and `CombatRunner` are confined to the session shard. They record the first owner
  thread and throw on any other.
- **Published snapshots.** Other threads see only immutable published values (volatile):
  - the engine's `Status`, including the recent attack contexts;
  - the runner's entity snapshot (status + at most 16 immutable lookups), refreshed every 20 server ticks (P11-F8);
  - the error count and last error.
- **Diagnostics rendering.** The command builds text on the caller's thread from those values (`CombatRunner.lines`).
- **Paper main thread.** Nothing in Phase 11 runs on it. There are no locks on the hot path.
- **Session guard (P11-F18).** The runner refuses an observation of another session before touching any state, and counts it as an internal
  error (no coverage taint, since the foreign sequence must not move this session's ordering).
- **Failure containment.** A failure inside the runner is contained: it is counted, entity coverage is tainted `COMBAT_INTERNAL_ERROR`
  (never an exact answer after an error), and the session keeps running (`anInternalFailureIsContainedCountedAndTaintsCoverage`).

## MEMORY

`combatMemoryHarness` → `memory-results.json` uses forced GC, a used-heap diff, 3 trials and the median. The figures are one-host estimates,
not capacity claims.

| Scenario (per session unless stated) | Retained heap | Charged to the tier |
|---|---|---|
| empty combat session (`CombatRunner`: empty history + engine + snapshot; the observer world and timeline it shares are excluded) | 2 336 B | 0 |
| typical entity registry (40 mobs, 3 s of per-tick moves: 32 deep × ~60 revisions + 8 summaries) | 275 320 B | ~317 440 B |
| typical PvP session (10 players + 20 mobs moving, 64 attacks + 64 interactions) | 131 160 B | — |
| full bounded registry (256 live summaries, 32 deep × 96 revisions) | 527 576 B | 557 056 B = the ceiling 256 × 256 + 32 × 96 × 160 |
| attack-history storage (full 64 + 64 rings, beyond an empty engine) | 35 128 B | — |
| replay projection, full bounded registry (EntityCapsule; transient) | 438 144 B heap, 425 034 B encoded | — |
| replay projection, typical PvP (CombatCapsule; transient) | 32 376 B | — |
| global/shared: engine-wide metrics (one per PacketEngine) | 1 008 B | — |

Per-operation garbage (thread allocation): an attack with a known target is ~1 007 B (the immutable context); with an unknown target
~1 091 B; a deep relative move ~244 B (one revision); a current lookup ~104 B; a historical lookup ~136 B. The forgotten-key record (P11-F17,
at most 256 entries) is heap-only and not charged to the tier; it is included in the full-bounded figure above.

No per-entity history is unbounded. At the full bound the heap is below the charged bytes, so the tier accounting is conservative. Replay
projections exist only while an export runs; nothing is retained per session for replay. The global/shared structures are one fixed-cardinality
`CombatEngine.Metrics` per PacketEngine and the immutable static profile.

## PERFORMANCE

`benchmarkCombat` → `benchmarks.json`: JMH 1.37, Temurin 25.0.4.1+1, 3 forks, 5 × 1 s warmup, 10 × 1 s measurement, `-prof gc`, heap
512 MiB, one host. Descriptive only; no whole-server capacity claim. 30 measurements per kernel; error is the 99.9% interval.

| Kernel (per operation) | Time | Allocation |
|---|---|---|
| **no-combat path**: a serverbound movement observation through the core `CombatRunner` | 8.9 ± 0.4 ns | 80 B (the benchmark's own Observation) |
| **no-combat path**: a server tick, 40 tracked entities (amortized; every 20th tick refreshes the snapshot) | 83.7 ± 0.4 ns | 213 B |
| ATTACK ingest, known target (lookup + attacker/timing provenance + completeness + immutable context) | 133.0 ± 0.8 ns | 717 B |
| ATTACK ingest, unknown target | 137.7 ± 4.0 ns | 811 B |
| current target lookup | 9.2 ± 0.6 ns | 72 B |
| historical target lookup (inside the ring) | 20.5 ± 0.5 ns | 72 B |
| relative move of a deep-history entity | 366.9 ± 2.4 ns | 162 B |
| absolute teleport | 207.0 ± 28.9 ns | 282 B |
| spawn + removal (one pair) | 406.2 ± 2.6 ns | 1 056 B |
| id reuse (spawn over a live id: end + new incarnation) | 198.9 ± 3.5 ns | 920 B |
| relative move in a FULL bounded registry (256 summaries, 32 × 96 rings) | 195.0 ± 2.1 ns | 200 B |
| nearest-32 deep-slot rebalance of a full registry (once per second per session) | 2 469 ± 131 ns | 4 768 B |
| mixed stream: 8 moves + 1 attack + 1 lookup (+ a spawn/remove every 16th op) | 2 365 ± 108 ns | 2 318 B |
| **no-combat path vs entity count** (P11-F26): a serverbound movement with 0 / 40 / 256 tracked entities | 9.1 ± 0.7 / 12.3 ± 1.7 / 8.7 ± 0.3 ns | 80 B |
| one clientbound entity move with 1 / 40 / 256 tracked entities | 209.5 ± 19.2 / 193.6 ± 29.9 / 207.6 ± 2.7 ns | 336 / 341 / 377 B |
| SetPassengers mount/dismount toggle in a 256-entity view | 1 725 ± 344 ns | 6 527 B |
| projectile churn in a 200-entity view (spawn, 3 moves, remove) | 1 783 ± 74 ns | 4 213 B |

**The no-combat path.** A serverbound movement observation through the core `CombatRunner` costs the mapping checks and an observed-sequence
bump, and no longer grows with the number of tracked entities (P11-F26: before the fix, 1 272 ns at 256 entities on the perf thread's run). A server tick is amortized: every 20th tick refreshes the entity snapshot and rebalances deep slots (P11-F8). Before P11-F8 a tick cost
~14.6 µs and ~61 KB of garbage per session.

## DIAGNOSTICS

`/papea2ac combat <player> [entities]` (permission as the other `/papea2ac` diagnostics). The lines are rendered in core
(`PacketEngine.combatDiagnosticLines`):

- the session / world epoch and the entity-snapshot sequence, with the standing limitations spelled out;
- entity registry usage against its bounds (live/256, ended, deep/32, bytes, generations, removals, replacements, epoch resets, evictions,
  promotions/demotions, unknown updates, relative teleports, unsupported motions, range queries, query limits);
- honesty markers (eviction floor, coverage break sequence and reason, capacity overflow, internal errors);
- attack / interaction counters (found, not found, complete, incomplete, rejected);
- up to 12 recent attack contexts. Each shows the id, the target network id → lookup status, key (#id/incarnation@epoch), type, the revision
  with exactness markers, the attacker status / claim / rotation / validation, the timing status and RTT, and the completeness reasons plus
  standing;
- recent interactions, and optionally 16 live entities.

The lines contain no verdict, label, score, CHEATER / REACH / KILLAURA / BAN / PUNISH / CONFIDENCE vocabulary, and no UUID
(`CombatRunnerTest`).

Metrics (fixed cardinality, no UUID / entity id / name / chunk / session label): attacks, interactions, targetFound, targetIncomplete,
contextsComplete, contextsIncomplete, rejectedOutOfOrder, internalErrors, byTargetStatus (12 fixed statuses), and the entity lifecycle totals of
closed sessions (generations, removals, replacements, epoch resets, closed sessions). `/papea2ac status` prints them as one core-rendered
`combat …` line. The line's entity totals are closed sessions (folded once, at close) plus every live session's last snapshot; a closed runner
is excluded from the live sum, so nothing is counted twice (`CombatRunnerTest.theEngineWideCombatLineCountsLiveAndClosedSessionsOnce`, which
drives a real PacketEngine session). The live qualification counts come from this line, because per-session counters vanish with their
session.

No forensic combat packet dump was added (brief section 61). The existing, explicitly armed recording facility is unchanged.

## NATIVE DIFFERENTIAL

`NativeCombatDifferentialTest` (nativeTest lane, the pinned Paper jar's natives, never stand-ins) → `native-differential-results.json`:

| Area | Cases | Result |
|---|---|---|
| `VecDeltaCodec` single decodes (setBase + decode vs `CombatProfile.decodeAxis`, bit-exact per axis; grid, off-grid, ±3e7, −0.0, border) | 200 000 (3 590 all-zero) | 0 mismatches |
| move chains: native codec (decode → setBase; sync sets base; teleport leaves it) vs `EntityHistory` target and base and vs the P11-F3 ledger, after every step | 2 000 chains × 40: 71 975 moves, 4 054 teleports, 3 971 syncs | 0 mismatches |
| `PositionMoveRotation.calculateAbsolute`: absolute = (0 + packet) from any client position (EXACT); relative = client position + packet (INTERPOLATION_DEPENDENT) | 20 000 (19 474 position-dependent pairs) | 0 mismatches |
| profile constants: Pose names in ordinal order (18); `DATA_POSE` id 6; `DATA_SHARED_FLAGS_ID` id 0; Attack components `[entityId]`; Interact components `[entityId, hand, location, usingSecondaryAction]`; codec quantum 4096 | all | match |
| native packets → real normalizer → Phase 11 mappers → history: AddEntity (spawn), MoveEntity.PosRot (position, rotation bits), SetEntityData (pose CROUCHING, flags), RemoveEntities (DESPAWNED), a native respawn of the same id (new incarnation, UUID/type preserved, old key ID_REUSED), ServerboundAttack, ServerboundInteract | pipeline | match |
| `NativeSemanticTest`: EntityPositionSync distinct (P11-F2); TeleportEntity keeps its relative flags; POSE kind (P11-F1) | — | match |

The client handler facts (which handler touches the codec base) come from the vanilla client jar's bytecode, recorded in the artifact.

## PROPERTY TESTS

`CombatPropertyTest` (60 seeds × 700 operations) runs against an **independent, unbounded reference model** (full history, no eviction, its
own delta decode). Whatever the bounded history answers FOUND must equal the reference exactly: it may know less, never more and never
differently. The other properties:

- same inputs → same projection;
- unknown / evicted / pre-spawn / gap-tainted never resolve FOUND;
- bounds are never exceeded;
- session and epoch changes invalidate every old identity;
- reused ids are distinct generations, and pre-spawn is never exact;
- an attack never implies acceptance, and an interaction never becomes an attack.

Removing input or adding a gap cannot improve completeness; this is covered by the metamorphic tests below. Current Bukkit state is never a
fallback: there is no such code path (architecture scan) and no miss carries a position (`CombatEngineTest`).

## METAMORPHIC TESTS

`CombatMetamorphicTest` works on one fixed generated observation list and its variants, compared point by point at identical sequences:

- deleting a relevant packet (25 seeds × 4 deletions) can only keep or lower certainty, and every exact answer of the variant equals the
  unbounded truth;
- inserting a coverage gap cannot make any lookup more exact;
- a session / epoch change invalidates prior identity;
- remove + respawn never keeps the old generation;
- shifting an update across an attack changes what the attack sees;
- duplicates add neither certainty nor generations.

This test found P11-F4.

## FUZZ

`CombatFuzzTest` runs 40 seeds × 1 500 steps of random spawn / relative move / rotation / absolute and relative teleport / sync / metadata /
passengers / removal / attack / interaction / epoch change / coverage loss / head rotation. Values are hostile: ±32768 deltas, ±3e7
coordinates, 300 ids against the 256 capacity. After every step it checks:

- no exception;
- no cross-generation, cross-id, cross-session or cross-epoch leak;
- no exact answer across a coverage loss;
- all bounds held (retained ≤ 256, deep ≤ 32, bytes ≤ tier, rings ≤ 64).

Each seed's whole run (digests + status) is identical on re-run. Malformed values are rejected at construction.

## PAPER SMOKE

The smoke ran on the exact candidate jar `948e06ca…` on the VPS, as a transient `papea2ac` unit (Temurin 25, `-Xms2G -Xmx4G`), before any
player joined. `real-client-server-vps-jar948e06ca.log` has the server log; `real-client-evidence.log` has the RCON transcripts.

- The plugin loads and enables in OBSERVE_ONLY (`capture=true effectsSuspended=true`).
- The architecture holds at runtime: the platform prints only core-rendered combat lines.
- The combat engine initializes: `/papea2ac status` shows the new `combat …` line (all zero), and `/papea2ac combat <player>` answers correctly
  for an offline player.
- A join and a leave were clean: the session was admitted, closed, and folded into the totals exactly once (`sessions{live=0 closed=1}`).
- The server log has 0 exceptions, errors or SEVERE lines.
- Combat produced 0 Phase 9 positive evidence and 0 Phase 10 effects.

## REAL-CLIENT VPS QUALIFICATION

The environment is the owner's VPS; localhost was never used for a live gate.

**Setup.** Emojiado played on a vanilla 26.2 client over the real internet. The whitelist was enforced; 25565/tcp was opened only for the
session. RCON was bound on the VPS but never exposed (no firewall rule), and every command ran over localhost. The world ran with natural mob
spawning off, so only the qualification targets existed. The player had Resistance V + Saturation (no damage, no hunger).

**Evidence helper.** A VPS-side helper showed a per-player bossbar and logged the engine status and the player's combat, timing and mitigation
lines every ~30 s. Round 1 covered 13:33–14:01 UTC. The second player (iDTR) was unavailable, so the PvP part of Track A is **pending a second
round** with the same jar.

**After the session:** the server was stopped cleanly (all dimensions saved), the helper stopped, and the temporary firewall rule deleted.
Neither 25565 nor 25575 is listening on the VPS or reachable from outside, and unrelated VPS services were untouched.

**Track A — legitimate combat (single player).**
- **Targets:** stationary husks (no AI), moving husks, cows, a pig and villagers, first near spawn and then in a walled arena (x 30–50,
  y 150).
- **Actions:** repeated attacks on the same entity; attacks while sprinting, jumping (claimed y +0.79…+1.25), crouching and moving
  diagonally; a mob killed by the player; right-click interactions without attacking.
- **Target teleported mid-fight (twice):**
  - near spawn, the husk was at x = 6.00582 before the teleport and 11.00582 after: a +5 block move the server sent as a relative-move delta,
    decoded exactly;
  - in the arena, attacks saw the husk at exactly 44.5 after the jump.
- **A dying target:** the last attack on a killed husk shows pose **DYING**, a live SetEntityData pose update (P11-F1).
- **Interactions:** 222, e.g. the villager (#117, incarnation 39) and the cow; the client sends a MAIN_HAND and an OFF_HAND INTERACT per
  right-click. None was ever counted as an attack.
- **Result:** every attack context names the right target, generation and epoch; there were 0 cross-target leaks and 0 exceptions.

**Track B — lifecycle.** Spawns, movement, removals (kills, deaths, item pickups, XP orbs), new targets and epoch/session cleanup were all
exercised:
- 20 chickens were summoned (generations 67 → 87), then killed. The drops and XP orbs raised generations to 120; removals went 53 → 78.
- With 42 live entities, all 32 deep-history slots were in use and the nearest-32 policy rotated slots (demotions observed).
- **Live numeric id reuse was not forced.** A running Paper server issues entity ids from a monotonic counter, so a live id is not reused
  within a server run; the one "same mob" that came back after a dimension change got a new network id (#192 → #996) after its chunk reloaded.
  Id-reuse safety is proven by the deterministic, property, metamorphic and native tests (a native respawn of the same id is a new incarnation,
  and the old key answers ID_REUSED).

**Track C — world / dimension.**
- **Overworld → Nether:** worldEpoch 0 → 1; every Overworld incarnation ended (`ended=0` in the new epoch); 106 Nether entities tracked
  fresh; coverage intact.
- **Nether attacks** resolved to epoch-1 keys (`#276/i125@e1`). That husk's teleport arrived as a live **EntityPositionSync** (P11-F2).
- **Nether → Overworld:** worldEpoch 2; attacks resolved to `@e2` keys. No attack ever resolved across epochs.
- **Unknown updates.** After each dimension change the server sends RemoveEntities for the entities of the world the player left. They
  arrive after the epoch reset and are dropped, as the vanilla client drops them, and counted (`unknownUpdates` 42 = the 42 Overworld entities
  live just before; then 154).

**Track D — remote network (observed, not injected).** Emojiado → VPS round-trip time over 3 192 marker samples: min 36.5 ms, smoothed
44.7 ms, max 92.5 ms, jitter 11.1 ms, 12 outliers, 0 rejected. Every attack context carried it as descriptive timing (TICK_ALIGNED,
`rtt~44ms`), never as an allowance. No artificial latency was injected, and none is claimed.

**Live acceptance counts** (engine-wide `/papea2ac status` after the session closed; round 1):

| Count | Value |
|---|---|
| combat attack observations | **390** |
| non-attack entity interactions | **222** |
| target lookup FOUND | **390** |
| target lookup incomplete / unknown | **0** |
| attack contexts COMPLETE / INCOMPLETE | 0 / 390 — every one INCOMPLETE because the attacker's movement was not validated through the attack (`ATTACKER_MOVEMENT_UNVALIDATED`: Phase 8 validation stayed unanchored among mobs, which is the entity-neighbourhood rule) |
| entity generations | **767** |
| entity removals | **599** |
| epoch resets | **2** |
| exceptions (server log) / combat internal errors | **0** / **0** |
| positive evidence (Phase 9) | **0** |
| mitigation effects (Phase 10) | **0** (OBSERVE_ONLY; executor sent=0, proposals=0) |
| movement validation during combat | 11 855 requests, 1 027 VALID, 10 828 UNCERTAIN, **0 INVALID** |

**Round 2 — PvP** (18:07–18:22 UTC, same jar `948e06ca…`, new server log). Emojiado played with a second vanilla player, **PotSG_**
(iDTR was unavailable). PotSG_ was whitelisted for the round only and removed afterwards. They played in the walled arena, both in adventure
mode after the first step so a sword swing cannot break the glass, with Resistance V, Saturation and iron swords. Each step was confirmed
through the chat (PotSG_ answered "listo").

The steps:
1. PotSG_ attacks while moving around Emojiado, who stands still (repeated after the event below).
2. Both players moving.
3. PotSG_ stands still and Emojiado walks back and forth in reach.
4. Roles reversed.

Findings:
- **Target state.** Every PvP attack context names the other player as a `minecraft:player` target with a stable incarnation
  (`#30/i7`, `#157/i57`). The target state interleaves relative moves and live **EntityPositionSync** revisions (knockback pinning a
  player into the corner), and shows the sprint flag live (`flags=8`).
- **Network.** PotSG_ ~96–110 ms, steady, 0 expired markers. Emojiado had a **server-to-client stall**: RTT up to 9.1 s and 46 expired
  markers, while the server stayed at 20.0 TPS and the plugin queue at 0/8192.
- **Movement false positive.** During that stall, **one movement INVALID and one Phase 9 positive event** were recorded for Emojiado while
  he was being hit: h = 0.4000 against a legal 0.327, rise 0.3608, which is the PvP knockback applied late. See **P11-F10**. It did not recur
  in the rest of the round (≈ 100 more PvP hits on a steady connection), and no mitigation effect followed (OBSERVE_ONLY, executor sent=0).
- **Round 2 counts** (after both sessions closed):
  - 122 attacks, 122 found, 0 not found; 0 interactions;
  - 71 generations, 56 removals, 0 epoch resets;
  - 0 exceptions and 0 combat errors;
  - movement: 8 621 requests, 1 INVALID;
  - **1 positive evidence event (P11-F10)**;
  - 0 mitigation effects.
- **Cleanup.** Same shutdown as round 1: whitelist restored to iDTR and Emojiado, server stopped cleanly, rule deleted, ports closed from
  outside.

Round 2 therefore **fails the "0 positive evidence during live combat" gate** on jar `948e06ca…`. The cause is the earlier-phase defect
P11-F10, not the combat engine (Phase 11 observes and never feeds evidence). The fix changes production code, so the fixed jar requires a
new VPS qualification before READY (brief section 75).

**Round 3 — requalification of the exact final candidate jar** `b6699bed23fe1ce2cf85337930999b5637b4bb62742b9a5430476c3db1707c9c`
(2026-10-03 13:40–14:01 UTC; it carries P11-F10 and P11-F12 … F26). The server log is `real-client-server-vps-round3-jarb6699bed.log`; the
RCON transcripts (with the jar hash in the round header) are appended to `real-client-evidence.log`. Players: Emojiado and PotSG_ (whitelisted
for the round only, removed afterwards), vanilla 26.2 clients over the real internet. The intermediate jar `837e1fa7…` never saw a player.

- **Smoke on this jar.** The plugin loaded in OBSERVE_ONLY with 0 errors, the `combat …` status line and `/papea2ac combat` answered, two
  sessions joined and closed cleanly (`sessions{live=0 closed=2}`), and the server stopped cleanly ("All dimensions are saved").
- **Track A (solo).** In the glass arena: still and moving husks attacked while sprinting, jumping, crouching and moving diagonally; 94
  right-clicks on a villager, a cow, chickens and the horse (MAIN_HAND and OFF_HAND, never attacks); 10 chickens killed.
  - **Mounted.** Emojiado rode a saddled horse and hit a still husk from the saddle (attacker claims at y 151.797, the riding height).
  - **The ridden horse, live (P11-F13).** While ridden, its position and rotation read `[VEHICLE_DEPENDENT]`. After Emojiado got off, its
    position stayed VEHICLE_DEPENDENT while its rotation was EXACT again (healed by the next rotation packet), and a later position sync
    restored the position, as designed.
- **Track A (PvP, with PotSG_).** The same four steps as round 2:
  1. PotSG_ attacks a still Emojiado;
  2. both players moving;
  3. PotSG_ still, Emojiado moving;
  4. roles reversed.

  Each player target was `minecraft:player` with a stable incarnation, its state interleaving relative moves and live EntityPositionSync
  revisions under knockback, and carrying TARGET_PLAYER_INFO_UNTRACKED (P11-F20).
- **Track B (lifecycle).** 380 generations, 239 removals (kills, drops, XP orbs, clears), no capacity refusal, coverage intact throughout.
- **Track C (world).** Overworld → Nether → Overworld: epoch resets 2; the Nether attacks resolved to `@e1` keys (`#175/i83@e1`), none across
  epochs.
- **Track D (remote network, observed, not injected).**
  - Emojiado: smoothed 47.4 ms (min 38.0, max 65.7), jitter 9.3 ms, 2 534 markers, 0 expired.
  - PotSG_: smoothed 102.3 ms (min 96.9, max 183.0), jitter 5.3 ms, 2 095 markers, 0 expired.

  No stall like round 2's occurred, so the P11-F10 path was not re-triggered live. That fix stays qualified by its deterministic reproduction
  through the real runner.
- **Avoided by design.** No sprint or knockback hits on paintings, item frames, boats or minecarts (P11-F27, open known defect, owner decision).

**Round 3 counts** (engine-wide `/papea2ac status` after both sessions closed):

| Count | Value |
|---|---|
| combat attack observations | **1 024** |
| non-attack entity interactions | **94** |
| target lookup FOUND | **1 024** |
| target lookup incomplete / unknown | **0** |
| attack contexts COMPLETE / INCOMPLETE | 0 / 1 024 (ATTACKER_MOVEMENT_UNVALIDATED among mobs and players, the entity-neighbourhood rule; plus TARGET_PLAYER_INFO_UNTRACKED on player targets) |
| entity generations | **380** |
| entity removals | **239** |
| epoch resets | **2** |
| exceptions (server log) / combat internal errors | **0** / **0** |
| positive evidence (Phase 9) | **0** |
| mitigation effects (Phase 10) | **0** (OBSERVE_ONLY; executor sent=0 for both players) |
| movement validation during combat | 29 279 requests, 1 023 VALID, 28 256 UNCERTAIN, **0 INVALID** |

**Cleanup.** The evidence helper was stopped, PotSG_ removed from the whitelist (iDTR and Emojiado remain), the server stopped cleanly, and the
temporary `ufw allow 25565/tcp` rule deleted. Nothing listens on 25565 or 25575 on the VPS, and both ports are closed from outside. Unrelated
VPS services were untouched. Logs were checked for the RCON password (none) and player IPs (redacted).

Round 3 **passes** every live gate on the exact final candidate jar.

## KNOWN LIMITATIONS

- **Rendered position.** Interpolation progress (the rendered target) is not modelled, and every context says so. The network target is not
  always the client's interpolation endpoint: a local displacement during interpolation (`addDelta`), a far snap during an active interpolation
  and a rotation-only re-target can move the endpoint (P11-F24). Phase 12 must treat the endpoint as the network target plus an unmodelled
  local displacement.
- **Player-info precondition (P11-F20).** The client drops a player spawn whose UUID has no PlayerInfo entry. PlayerInfo packets are not
  captured, so a plugin that breaks the order could make the model track a player the client never had. Every player target therefore
  carries TARGET_PLAYER_INFO_UNTRACKED. Capturing PlayerInfo is a payload-schema addition left for later.
- **Client-side pickup (P11-F21).** `ClientboundTakeItemEntity` removals (arrows, tridents, emptied items) are not modelled. The incarnation ends at
  the server's RemoveEntities. These entities are not legal attack targets (Paper kicks with `invalid_entity_attacked`).
- **Spear (P11-F19).** A STAB names no target. Kinetic spear damage has no packet. The held item is not modelled.
- **No server outcome.** An observed attack is not a server-accepted attack; the transport is capture-only (P10-F1 applies unchanged).
- **Relative teleport components** are INTERPOLATION_DEPENDENT. They reach an observer only for the vehicle it controls.
- **Legacy (payload schema 1/2) captures** cannot tell a position sync from an absolute teleport. The combat history marks such incarnations
  UNSUPPORTED; the movement ledger keeps its pre-Phase-11 reading (P11-F9).
- **Unreconstructed mechanisms.** New-minecart lerp steps are UNSUPPORTED. Vehicle passengers are reported (TARGET_MOUNTED,
  TARGET_VEHICLE_DEPENDENT), not modelled as geometry. The vehicle carrying the observer is VEHICLE_DEPENDENT whatever its controllability, because
  type and saddle state are not tracked (conservative).
- **Metadata** beyond pose and shared flags is not retained. Entity dimensions, eye height and interaction attributes belong to Phase 12
  profiles.
- **Deep history** is kept only for the 32 nearest entities. Farther ones keep their current revision only, and historical lookups for
  them say EVICTED.
- **Entity admission on a full floor tier (P11-F11).** After enough block traffic fills the 768 KiB world/entity floor, new spawns are
  refused, and lookups of unknown ids answer INCOMPLETE_COVERAGE for the rest of the epoch. The answer is honest (never a wrong FOUND), but
  the coverage depends on arrival order (D6).
- **Snapshot staleness.** The diagnostic entity snapshot can be up to 1 s stale (attack contexts are live).
- **Attacker provenance** is raw-claim provenance plus Phase 8 validation. Certified-trajectory eyes (COMBAT_MODEL B2) are Phase 12.
- **Live numeric id reuse was not forced.** Paper issues entity ids from a monotonic counter, so a live id is not reused within a server
  run; id-reuse safety is qualified by the deterministic, property, metamorphic and native tests.

## DEFERRED TO PHASE 12+

Attack geometry: eyes from certified trajectories, rays, target dimensions, hitboxes, occlusion, reach comparison, scene hypotheses and the
64-attack acknowledgement wait (Phase 12). Interpolation progress, if Phase 12 needs it. Rotation analysis (15). KillAura / combat heuristics
(16). Click analysis (17). Any combat evidence or combat mitigation.

## DEFECTS

Earlier-phase defects are fixed forward; no frozen history was rewritten and every Phase 0–10 baseline is unchanged. Phase 11-local
defects are listed too, including those found and fixed during development.

### P11-F1 — the normalizer recorded the entity POSE as OPAQUE (origin: Phase 1, `PaperSemanticNormalizer`) — FIXED

- **Observed:** `SetEntityData` pose fields reached the capture as `OPAQUE` with an empty value, so the target's pose was unknowable.
- **Root cause:** the scalar serializer table (BYTE, INT, LONG, FLOAT, STRING, BOOLEAN, DIRECTION) did not include `EntityDataSerializers.POSE`.
- **Fix:** POSE is a scalar kind whose value is the Pose constant name.
- **Regression:** `NativeCombatDifferentialTest` (native CROUCHING through the normalizer), `NativeSemanticTest`, corpus `pose-update`.
  Older captures keep OPAQUE and are treated as UNKNOWN (corpus `pose-opaque`).
- **Qualification:** native + live (pose values observed live, see the VPS section).

### P11-F2 — position sync and teleport were conflated (origin: Phase 1, normalizer + protocol) — FIXED

- **Observed:** `ClientboundEntityPositionSyncPacket` and `ClientboundTeleportEntityPacket` both became `EntityPosition`.
- **Root cause:** a shared case in the normalizer. The 26.2 client applies them differently: a sync resets the delta-codec base; a teleport
  sets only the target, relative to the current position.
- **Fix:** a new `SemanticPayload.EntityPositionSync` and payload/container schema 3 (tag 80). Schemas 1/2 still decode, and tag 80 is
  rejected in them. `ContextObservationMapper` maps the sync to `EntityMovedTo`, preserving Phase 8/9 behaviour.
- **Regression:** `CapsuleSchemaCompatibilityTest`, `LegacyCodecCompatibilityTest`, `NativeSemanticTest`, the native move-chain differential.
- **Replay:** `replayConformance` unchanged.

### P11-F3 — the movement context ledger decoded entity deltas inexactly and read teleports as absolute positions (origin: Phase 8, `MovementContextLedger` / `ContextObservationMapper`) — FIXED

- **Observed:** relative moves were applied as `position + delta/4096` (off the client's value by up to 0.5/4096 block per axis), and
  a relative TeleportEntity was stored as the absolute coordinate. A teleport also leaves the client's codec base behind, so the ledger's
  later deltas diverged from the client.
- **Fix:**
  - exact `VecDeltaCodec` decode (`decodeAxis`-equivalent; the tracked position equals the codec base whenever the entity is certain);
  - a new `ContextEvent.EntityTeleported`: a teleported entity is **uncertain**, which makes the neighbourhood UNKNOWN (conservative)
    until a position sync, respawn or removal.
- **Reach:** TeleportEntity reaches an observer only for a vehicle it rides.
- **Regression:** `MovementContextLedgerTest.relativeMovesDecodeLikeTheClientDeltaCodec`,
  `aTeleportMakesTheNeighbourhoodUnknownUntilThePositionIsResynced`, and the native chain differential (ledger column).
- **Corpora:** Phase 8/9/10 corpora unchanged (ValidationCorpusTest; `validationFixtures`, `evidenceFixtures` and `mitigationFixtures` reproduce the frozen artifacts).

### P11-F4 — a sequence gap tainted entities from the arriving sequence instead of the first missing one (Phase 11-local, `EntityHistory.noteGap`) — FIXED

- **Found by:** `CombatMetamorphicTest` (seed 2). A lookup inside the lost range answered FOUND.
- **Fix:** the taint starts at `lastSequence + 1`.
- **Phase 3 check:** `ObserverWorld.noteGap` records its break at the arriving sequence too, but there that value is diagnostic only. Block
  coverage is per chunk and current (TRANSPORT_INCOMPLETE), and historical block views report PARTIAL coverage, so the pattern is not a
  Phase 3 defect.

### P11-F5 — ended incarnations dropped their deep history immediately (Phase 11-local) — FIXED

- **Observed:** historical lookups just before a removal lost exactness at the removal.
- **Fix:** an ended incarnation keeps its ring and slot (still ≤ 32 in total, including ended) until the horizon leaves only its final
  revision.

### P11-F6 — a refused out-of-order attack did not republish the engine status (Phase 11-local, `CombatEngine`) — FIXED

- **Observed:** the rejected count was stale in diagnostics.
- **Fix:** refusals republish the status.
- **Regression:** `CombatFuzzTest.malformedValuesAreRejectedAtConstructionNeverInside`.

### P11-F7 — absolute teleports differed from `calculateAbsolute` on −0.0 and unclamped pitch (Phase 11-local, `EntityHistory.teleported`) — FIXED

- **Found by:** the native differential. The client computes `(relative ? current : 0) + packet` (so −0.0 lands on +0.0) and clamps pitch to
  [−90, 90].
- **Fix:** the model now does the same.
- **Regression:** the native teleport differential, bit-exact.

### P11-F8 — per-tick diagnostics publication and deep-slot rebalance were costly (Phase 11-local, `CombatRunner`) — FIXED

- **Found by:** JMH. A server tick cost ~14.6 µs and ~61 KB of garbage per session: every tick rendered describe() strings for up to 16
  entities, and rebalance sorted every live entity.
- **Fix:**
  - the entity snapshot holds immutable lookups (no strings) and refreshes every 20 ticks; text is rendered on the reader's thread;
  - attack contexts are read live from the engine's published status;
  - rebalance runs on the same 1 s cadence.
- **Result:** 202 ns and 215 B per amortized tick (JMH), down from ~14.6 µs and ~61 KB.
- **Regression:** `CombatRunnerTest.attackContextsAreLiveWhileTheEntitySnapshotRefreshesOncePerSecond`.

### P11-F9 — pre-schema-3 captures' tag-16 positions were read with schema-3 teleport semantics (Phase 11-local, follow-on of P11-F2) — FIXED

- **Observed (review):** before schema 3, tag 16 carried both syncs (the vast majority) and absolute teleports. After the split, the Phase
  11 mappers read every legacy tag 16 as a TeleportEntity:
  - the combat history would have decoded later deltas from a stale base while labelling them EXACT;
  - the P11-F3 ledger would have made nearly every tracked entity uncertain for the rest of a legacy replay.
- **Fix:** a schema-1/2 tag 16 with an empty relative set decodes as the explicitly ambiguous `SemanticPayload.LegacyEntityPosition`
  (decode-only; re-encoded as schema-3 tag 81, charged identically to the tag-16 record it was). A non-empty relative set can only have been
  a teleport and stays `EntityPosition`.
  - The ledger maps the legacy record to `EntityMovedTo` (the pre-Phase-11 reading, correct for the position under both interpretations).
  - The combat history marks the incarnation UNSUPPORTED (`LEGACY_SYNC_OR_TELEPORT`), never guessed exact.
- **Regression:** `CapsuleSchemaCompatibilityTest.schema3AddsOnlyTheSyncAndLegacyPositionTags`,
  `MovementContextLedgerTest.aLegacyAmbiguousPositionKeepsThePrePhase11Reading`, corpus `legacy-capture-position`.

### P11-F10 — an influence past the application horizon silently left the validation chain, so a rest re-anchor could claim completeness while PvP knockback was still undelivered (origin: Phase 8, `MovementValidationRunner` chain scope over Phase 7 influences; found live in the Phase 11 PvP round) — FIXED, requalified (round 3, jar `b6699bed…`)

- **Observed (VPS, round 2, jar `948e06ca…`).** Emojiado's connection stalled server-to-client (RTT up to 9.1 s, 46 expired markers)
  while PotSG_ was hitting him. One movement transition was INVALID [NO_LEGAL_INPUT, HORIZONTAL_EXCESS]: h = 0.4000 observed against
  h ≤ 0.3274 legal, rise 0.3608. Phase 9 made one positive event of it; nothing was mitigated (OBSERVE_ONLY). 0.4 is exactly the vanilla
  knockback strength.
- **Root cause.**
  - `TimingProfile.applicationHorizonNanos` (3 s) bounds how long an influence stays branchable as "not yet applied". Past it the influence
    is, by the profile's own definition, "EXPIRED = unresolved, never *not applied*".
  - The runner nevertheless removed such an influence from the chain's scope. At the next 20-tick reminder at rest, the P8-F4 rest
    re-anchor declared the chain complete again. When the stalled client finally applied the knockback, the complete chain had no branch
    for it.
  - Equivalent paths had the same flaw: the influences cleared when the chain drops, and those cleared at a rest anchor from the unanchored
    state.
  - **Reproduced deterministically** through the real runner (`MovementValidationRunnerTest.aKnockbackAppliedAfterTheApplicationHorizonIsNeverInvalid`):
    a SET (0.4, 0.36, 0) velocity sent at tick 10 and applied at tick 90 while the client stands and transmits its reminders gave exactly
    the live INVALID (h = 0.4000, rise 0.3600) before the fix.
- **Fix (the smallest sound one; strictly more conservative than the frozen behaviour, no new application claim).** An influence that
  leaves the chain's scope without an observed application — past the horizon, dropped with the chain, or cleared at an anchor — retires
  only under a fence that orders after it:
  - a **marker the client answered that was sent after it** (receipt: the stream is in order);
  - a **confirmed correction sent after it** (`teleportConfirmIsGameThreadFence`, already claimed);
  - a **world change**.

  Until then it is **OUTSTANDING**: no rest anchor or re-anchor may restore completeness, and a correction anchor stays incomplete. A marker
  answer is used only to *permit* the retirement the bare horizon used to grant, never to advance an application frontier (N1 unchanged:
  `pongBoundsApplication` stays unclaimed).
- **Precision.** Retirement on a healthy connection is unchanged: markers flow about once per second, so receipt is evidenced within one
  RTT. `aReceivedInfluenceStillRetiresAtTheHorizonAndTheChainReanchors` proves it. Only a real stall keeps the chain UNCERTAIN, until the
  client's answers resume.
- **Regression:**
  - the reproduction above: no INVALID, the influence OUTSTANDING, no rest re-anchor during the stall, the late application UNCERTAIN, and
    completeness restored once a later marker is answered;
  - the healthy-connection retirement test;
  - `anAppliedInfluenceIsRetiredFromTheChainScopeAndAnIncompleteChainReanchorsAtRest`, whose synthetic stream had no marker traffic, now
    carries one answered marker as real sessions do.
- **Corpora:** unchanged. The full suite passes (825 tests), and the Phase 7/8/9/10 fixture artifacts regenerate semantically identical (Phase 8 32 451 / 0 INVALID and 3 290 / 3 290; the Phase 9 evidence corpus; the Phase 10 policy corpus). No corpus stream holds an influence that outlives the horizon without an answered marker, so the new rule never fires there.
- **Qualification:** the final jar `b6699bed…` was requalified on the VPS in round 3 (brief section 75): 0 positive evidence, 0 INVALID. **Historical baselines:** unchanged (a forward fix in the
  working tree).

### P11-F11 — entity summaries lose a full private tier to world traffic depending on arrival order (Phase 11-local, `EntityHistory.admit` vs `ObserverWorld.enforcePrivateBudget`; found by the cross-phase perf thread on snapshot 1) — ACCEPTED KNOWN LIMITATION (owner decision 2026-10-03)

- **Observed (offline harness `CrossPhasePathologyMain`, Phase 3 `mixedUpdatesHistory121` world, 768 KiB floor, then 256 spawns through the
  real `CombatRunner`):**
  - world traffic first → 5 / 256 entities live, 251 spawns refused, 14 chunks evicted;
  - spawns first → 256 / 256 live, 0 refused, 142 chunks evicted.
- **Root cause.** The admission is asymmetric (D6): the world may evict chunks for entity bytes, but entity summary admission may not evict
  chunks.
- **Safety.** Every refused spawn latches the epoch's capacity overflow, and lookups answer INCOMPLETE_COVERAGE, never FOUND or absent. The
  frozen model permits this explicit coverage loss at the floor tier. Live rounds 1–2 never hit it (round 1: 767 generations, 0
  incomplete).
- **Decision (owner, 2026-10-03): accepted as the floor-tier limitation.** The alternatives were reserving the 64 KiB summary ceiling inside
  the tier, or letting a refused summary evict the farthest chunk. Either would have set a priority the frozen spec does not, and changed the
  Phase 3 world floor. No code change; KNOWN LIMITATIONS lists it.

### P11-F12 … P11-F25 — independent review of the post-F10 tree (Phase 11-local) — FIXED or DOCUMENTED

Found by the adversarial QA thread (QA-01 … QA-07) and by the vanilla-client oracle thread (oracle-01 … oracle-07) on snapshot 1. Every QA
finding came with a failing reproduction (`Phase11QaAdversarialTest`, `Phase11QaMetamorphicTest`, now in the suite and green). The oracle's
own differential against the pinned 26.2 client (real wire encoding) had 0 mismatches. It covered 60 400 entity moves and syncs, 66 304 rotations,
20 000 teleports, 20 000 attacks and 20 000 interactions, and it confirmed that there is no INTERACT_AT split.

| Id | Source | Defect | Fix | Regression |
|---|---|---|---|---|
| F12 | QA-01 (HIGH) | a dismounted rider got its pre-mount position back as EXACT; a removed vehicle's id stayed on its riders | VEHICLE_DEPENDENT from mount until the next sync; riders dismounted on vehicle removal / replacement | QA test; corpus `dismount-before-resync`, `vehicle-removed`, `mounted-target` |
| F13 | QA-02 | relations involving the observer were dropped and both sides labelled EXACT | `Passengers` keeps `vehicleIsObserver` / `carriesObserver`; riders of the observer and the observer's vehicle are VEHICLE_DEPENDENT | QA tests; corpus `rides-observer`, `observer-vehicle` |
| F14 | QA-03 | an ATTACK on a break-only (degraded) observation was silently dropped | counted and resolved against the broken coverage | QA test; corpus `degraded-attack` |
| F15 | QA-04 | entity-history output depended on shard processing time | capture clock for revisions and the horizon | QA backlog test |
| F16 | QA-05 | a protocol-valid passenger list of 65–256 became an internal error | bound aligned to the protocol (256) | QA test |
| F17 | QA-06 | `lookupKey` claimed DESPAWNED across a later coverage loss, and EVICTED for a never-issued key | INCOMPLETE_COVERAGE / UNKNOWN (HISTORICAL LOOKUP) | QA tests |
| F18 | QA-07 | the runner applied another session's observation | refused and counted before any state change | QA test |
| F19 | oracle-01 (MEDIUM) | the spear's STAB attack produced no combat observation | `PiercingAttack` (target-less, counted as `stabs`) | corpus `spear-stab-is-not-a-targeted-attack` |
| F20 | oracle-02 | a player spawn the client drops (no PlayerInfo) was tracked EXACT | TARGET_PLAYER_INFO_UNTRACKED on every player target; profile flag | corpus `target-player` |
| F21 | oracle-03 | client-side pickup removals are not modelled | documented (KNOWN LIMITATIONS) | — |
| F22 | oracle-04 | death event 3 / Warden EMERGING change the pose on the client without data | pose UNKNOWN until the next pose data | corpus `death-event-before-pose-sync` |
| F23 | oracle-05 | spawn / move / sync pitch stored raw; the client clamps | `CombatProfile.clientPitch` | corpus `out-of-range-pitch` |
| F24 | oracle-06 | "network target = interpolation endpoint" overstated | wording (TRUTH MODEL, profile, contract); no model change | — |
| F25 | oracle-07 | INTERACT location presented as a world position | documented as a target-relative LP offset; profile flag | — |
| F28 | perf-03 | `releaseRiders` copies and scans every live incarnation on every removal (projectile churn ≈ 2.4× the P11-F26 prototype; results unaffected) | **deferred** by scope (the owner limited the final change to P11-F27); recorded for the first post-Phase-11 change | — |
| F29 | perf-coordinator-01 | pre-existing, baselined Phase 8 cost (`MovementContextLedger.apply` → `publish` copies and sorts the whole tracked-entity list on every entity move: ≈ 2.8 µs and 8 KB per move with 256 entities in view); not a correctness defect and not introduced by Phase 11 | **deferred** by scope; owner to route (Phase 13 entity neighbourhood, or accept as a known cost) | — |
| F26 | perf-02 | every observation scanned every retained incarnation for the horizon trim, so the no-combat path grew with entity count (1 272 ns per movement at 256 entities) | `trimAt` lower bound: the scan runs only when a revision can actually age out (results-identical; the perf thread's proposal) | `CombatEntityCountBenchmarks` (0 / 40 / 256 entities); fixtures and replays unchanged |

### P11-F27 — FIXED FORWARD IN PHASE 11: the attacker's own client slows and un-sprints after a sprint or knockback hit; no frozen model knew it (origin: Phase 7 timing mapping and resolution — `TimingObservationMapper` never turned the client's ATTACK into a timing event, so `TimingResolver` had no branch for it — and Phase 5 physics — `PhysicsEnvironment` / `PhysicsSimulator` had no operation for the slowdown; it surfaced as a Phase 8 movement INVALID feeding Phase 9 evidence. Found by the Phase 13 thread as P13-F2.)

- **Mechanism (pinned 26.2 client).** `MultiPlayerGameMode.attack` sends the ATTACK packet from `handleKeybinds`, then runs `Player.attack` locally,
  before `LocalPlayer.tick` moves the player. When the target's `hurtClient` is true and the knockback is positive (a sprint hit, or an
  ATTACK_KNOCKBACK attribute / knockback enchantment), `causeExtraKnockback` multiplies the attacker's motion by (0.6, 1.0, 0.6) and calls
  `setSprinting(false)`, which removes the sprint speed modifier. `hurtClient` is true for remote players, boats, rafts and minecarts
  (VehicleEntity), paintings and leash knots (BlockAttachedEntity), item frames, shulker bullets, end crystals, items and orbs. It is false
  for mobs, animals, villagers and armor stands.
- **Effect before the fix.** A legitimate sprint hit on a painting or item frame (non-pushers) with no player, boat or minecart within 4
  blocks was a complete-certificate movement INVALID and one Phase 9 positive event. Boats and minecarts were already UNCERTAIN as nearby
  colliders / pushers. PvP was not exposed, because the opponent is within 4 blocks. Phase 10 is OBSERVE_ONLY, so nothing was acted on.
- **Fix (minimal; ported from the Phase 13 hand-over `phase13-to-phase11-01`, 8 production files; no velocity detector, no anti-knockback
  verdict, no Phase 13 evidence policy, no fenced profile).**
  - `TimingObservationMapper` maps the inbound `SemanticPayload.Attack` to the new `TimingEvent.LocalAttack(sequence, nanos, targetEntityId)`;
    `TimingLedger` records it in order. It keys on the client's own packet whatever the target, never on server acceptance.
  - `TimingResolver` places each attack on the client tick whose movement follows it. For n attacks in a tick, every slowdown count 0..n is
    a branch (at most `MAX_ATTACK_VARIANTS` = 8 per timing account; beyond that only none/all, and the account is incomplete), applied after
    that tick's drained server influences. An attack that cannot be placed exactly (inside the silent closure, inexact alignment, before a
    relative correction) makes the account incomplete, so it can only give UNCERTAIN, never INVALID.
  - `PhysicsEnvironment.ExternalMotion.Operation.ATTACKER_SLOWDOWN`: `PhysicsSimulator` applies `v = (vx * 0.6, vy, vz * 0.6)`
    (`MovementProfile.ATTACKER_SLOWDOWN_HORIZONTAL`) and resets the sprint-modifier class to FOLLOWS. `AbstractTransfer` returns TOP on it,
    which never prunes and so never makes a wrong exclusion.
- **Honesty.** The server cannot see the attack cooldown, the held item's knockback or, in general, whether the target's `hurtClient` held,
  so both outcomes stay open. A legitimate hit is explained by the slowdown branch. Missing knowledge degrades to UNCERTAIN / incomplete,
  never to INVALID, and nothing is VALID by fabrication: a VALID transition is an exact replay of a branch the client can actually take.
  Limitation: a client that skips the slowdown (keep-sprint) is explained by the no-slowdown branch and stays undetectable here.
- **Regression (`AttackerSlowdownTest`, 9 tests, through the real `MovementValidationRunner` and frozen `TimingProfile.PAPER_26_2`).**
  - A sprint hit on a painting, item frame, boat and minecart with no player nearby: 0 INVALID. Painting and item frame are fully VALID;
    boat and minecart are VALID or UNCERTAIN (nearby collider), never INVALID.
  - A knockback-item hit while walking on each of the four: 0 INVALID.
  - A server knockback drained in the attack tick on each of the four: 0 INVALID.
  - Recorded attack without a slowdown: VALID. Attacks while standing: 0 INVALID.
  - The mapper turns the inbound attack payload into `LocalAttack` with its own sequence and clock.
  - Teeth: the same slowdown without an observed attack is still INVALID, which is the pre-fix behaviour this fix removes.
- **Corpora.** Phase 8 32 451 / 0 INVALID and 3 290 / 3 290, the Phase 9 evidence corpus and the Phase 10 policy corpus are unchanged (see
  FINAL VERIFICATION). Frozen streams contain no attack, so the new branch never fires there.
- **Qualification.** This changes production semantics on the live-qualified path, so round 3 on `b6699bed…` is superseded. The final jar
  needs its own VPS round (round 4). Historical baselines are unchanged (a forward fix in the working tree).

F12 and F13 change production semantics on the live path, so the post-F10 jar `837e1fa7…` was superseded before any live round, and the final candidate
`b6699bed…` was requalified on the VPS (round 3, PASS). The oracle-08 follow-up (five comments still carrying the pre-F24 wording) was reworded on the
same lines after round 3. The rebuilt jar is still byte-identical to `b6699bed…`. Contract changes are published as `CONTRACT_CHANGED-1` (coordination handoffs).

Test-harness corrections (not product defects): `CombatTestSupport.Rig.epoch` skipped observing its own sequence (phantom gaps), and one
early property formulation compared divergent random streams (replaced by the metamorphic relation).

## ACCEPTANCE MATRIX

| # | Requirement (brief section) | Result | Proof |
|---|---|---|---|
| 1 | Module owner / DAG (1) | PASS | OWNER; ArchitectureTest (combat edges, no dependents below, platform free of combat) |
| 2 | Phase boundary: no reach / hitbox / ray / rotation / KillAura / click / evidence / mitigation / punishment (2, 30–36, 80) | PASS | `combatStaysWithinThePhase11Boundary`; later-phase class names absent; `noVerdictFieldExistsAnywhereInTheCombatTypes` |
| 3 | Combat engine is descriptive (3) | PASS | no verdict field in any combat type or capsule; the corpus asserts no verdict vocabulary |
| 4 | Attack packet semantics exact for 26.2 (4) | PASS | native record components; `NativeSemanticTest`; native pipeline; the oracle thread's client differential (20 000 attacks, 20 000 interactions, 0 mismatches); spear STAB recorded as a target-less PiercingAttack (P11-F19); an ATTACK while holding a spear is recorded though Paper ignores it (held item not modelled; SERVER_OUTCOME_UNSUPPORTED) |
| 5 | Attack observation identity: stable, deterministic (5) | PASS | `A<epoch>:<seq>`; `sameInputsGiveTheSameContexts`; corpus determinism |
| 6 | Entity id reuse → distinct generation, never linked back (6, 41) | PASS | EntityHistoryTest, property, metamorphic, corpus `id-reuse` / `id-replaced-by-spawn`, native respawn |
| 7 | Session and world-epoch isolation (7, 42) | PASS | property, metamorphic, corpus `epoch-change` / `session-replacement`; live Track C (epochs 0 → 1 → 2, no cross-epoch attack) |
| 8 | Client-observed entity state from packets only; never live Bukkit truth (8, 43) | PASS | TRUTH MODEL; no server-state read in combat/world/core combat code (architecture scan); `attackerProvenanceNeverFallsBackToACurrentPosition` |
| 9 | Entity lifecycle (9, 38) | PASS | EntityHistoryTest (spawn → lookup / move / attack / remove / epoch / session end); corpus; fuzz; live Track B (767 generations, 599 removals) |
| 10 | Relative movement fidelity (10) | PASS | native codec 200 000 + chains 71 975 steps bit-exact |
| 11 | Network state vs render interpolation separated; progress deferred, never labelled exact (11, 48) | PASS | TRUTH MODEL; standing limitations on every context |
| 12 | Target metadata: pose / flags raw; unknown pose never STANDING (12, 46) | PASS | P11-F1; `poseIsRawAndUnknownIsNeverStanding`; corpus `pose-update` / `pose-opaque`; D2 |
| 13 | Unknown is first class, with the twelve statuses (13) | PASS | `target-lookup-results.json` 12/12 |
| 14 | No nearest-match lies (14) | PASS | eviction answers EVICTED; RevisionRange never padded; property test vs the unbounded reference |
| 15 | Ordering by session sequence (15) | PASS | `orderingDecidesWhichStateAnAttackSees`; metamorphic shift; corpus before/after pairs |
| 16 | Transport gaps degrade completeness (16) | PASS | the world's truth reused; gap taint (P11-F4); corpus `transport-gap`; `worldCoverageBreaksReachTheEntityHistory` |
| 17 | Target lookup API (17) | PASS | HISTORICAL LOOKUP |
| 18 | AttackContext (18) | PASS | CombatEngineTest; corpus contexts |
| 19 | Attacker state from PlayerTimeline, no later aim (19) | PASS | `CombatProvenance.Attacker`; CombatEngineTest |
| 20 | Timing provenance descriptive (20) | PASS | `timingProvenanceIsDescriptive`; live RTT 44.7 ms smoothed, jitter 11.1 ms, descriptive only |
| 21 | Observed outcome signals stay observational; no causal linkage (21) | PASS | no hurt / damage / velocity signal is linked to an attack (COMBAT_MODEL assigns no causal correlation to Phase 11); removal is lifecycle only; SERVER_OUTCOME_UNSUPPORTED standing; `anAttackObservationNeverImpliesAcceptance…` |
| 22 | Attack cooldown / cadence: timing facts only, never classified (22) | PASS | each attack keeps its capture nanos, sequence and timing provenance; no CPS / interval / regularity logic exists (architecture scan) |
| 23 | Combat history bounded (23) | PASS | 64 / 64 rings; `outOfOrderInputIsRejectedAndBoundsHold` |
| 24 | Entity capacity / shedding (24) | PASS | `capacityIsNeverSilentlyExceeded`, `aTightByteTierShedsDeepHistoryBeforeRefusing`, corpus `capacity-refusal` |
| 25 | Eviction honest (25) | PASS | `deepHistoryIsBoundedAndHonest`, `summaryEntitiesKeepOnlyTheirCurrentStateAndSayEvicted`, corpus `history-eviction` |
| 26 | Duplicates add nothing (26) | PASS | a re-delivered spawn / remove / relative update / attack (same Phase 1 sequence) is refused: `outOfOrderAndDuplicateObservationsAreRejected`, metamorphic `duplicateObservationsAddNeitherCertaintyNorGenerations` (all observation kinds + a duplicate attack) |
| 27 | Malformed / impossible input rejected (27) | PASS | `malformedValuesAreRejectedAtConstructionNeverInside`; fuzz hostile values |
| 28 | Replay schema (28) | PASS | EntityCapsule / CombatCapsule schema 1; transport schema 3; CapsuleSchemaCompatibilityTest |
| 29 | Replay determinism (29, 55) | PASS | `replay-results.json`: 8 traces, twice + capsule, 0 failures |
| 30 | No Phase 9 evidence from combat (30, 71) | PASS | combat feeds no evidence (architecture); round 3 on the final jar: **0** positive events in 1 024 attacks (rounds 1: 0; round 2 on `948e06ca…`: 1, P11-F10, fixed). P11-F27 (attacker slowdown after a sprint hit on a painting / frame / vehicle away from players) is an open known defect kept in Phase 13 by the owner; round 3 avoided that action |
| 31 | No Phase 10 mitigation from combat (31, 70) | PASS | architecture; Phase 10 artifacts reproduce unchanged; OBSERVE_ONLY; live 0 (executor sent=0) |
| 32 | Threading (37) | PASS | THREADING; owner checks |
| 33 | Relative move before baseline never fabricated (39) | PASS | `anUpdateBeforeAnyBaselineIsNeverFabricated` |
| 34 | Teleport per the client oracle; before/after resolve differently (40) | PASS | native `calculateAbsolute` 20 000 bit-exact (P11-F7); corpus teleport pairs |
| 35 | Player and non-player targets (43, 44) | PASS | corpus `target-player` / `target-mob`; UUID preserved (native respawn); live non-player targets (husk, cow, pig, villager, chicken) PASS; live player targets observed in round 2 (PvP); every player target carries TARGET_PLAYER_INFO_UNTRACKED (P11-F20) |
| 36 | Vehicles / mounts: explicit incomplete (45) | PASS | TARGET_MOUNTED + VEHICLE_DEPENDENT through dismount until a sync, vehicle removal, riders of the observer and the observer's vehicle (P11-F12, P11-F13); corpus `mounted-target`, `dismount-before-resync`, `vehicle-removed`, `rides-observer`, `observer-vehicle` |
| 37 | Invisibility / spectator / game mode: no invented rules (47) | PASS | only raw shared flags kept; no eligibility logic |
| 38 | Version profile (49) | PASS | `CombatProfile` `paper-26.2-124-combat`, natively pinned |
| 39 | Native / vanilla oracle (50) | PASS | NATIVE DIFFERENTIAL |
| 40 | Property tests (51) | PASS | CombatPropertyTest |
| 41 | Metamorphic tests (52) | PASS | CombatMetamorphicTest |
| 42 | Fuzz (53) | PASS | CombatFuzzTest |
| 43 | Attack corpus (54) | PASS | 32 fixtures, 0 mismatches, deterministic |
| 44 | Memory bounded and measured (56) | PASS | MEMORY |
| 45 | Performance / JMH incl. the no-combat path (57, 58) | PASS | PERFORMANCE: 13 kernels recorded (JMH 1.37, 3 forks, 5 × 1 s + 10 × 1 s, -prof gc); no-combat movement ~120 ns, tick ~202 ns amortized |
| 46 | Metrics fixed cardinality (59) | PASS | DIAGNOSTICS; `CombatRunnerTest` (no UUID) |
| 47 | Diagnostic command (60) | PASS | DIAGNOSTICS; used throughout the live session |
| 48 | Forensic debugging bounded / absent (61) | PASS | none added |
| 49 | Paper smoke (62) | PASS | PAPER SMOKE (`948e06ca…`) and round 3 on the final jar (load in OBSERVE_ONLY, combat line and command, 2 clean join/close cycles, 0 exceptions, 0 evidence, 0 effects) |
| 50 | Real-client VPS Track A (65) | PASS | round 3 on the final jar: solo (still / moving husks, sprint / jump / crouch / diagonal, interactions, kills, mounted attacks, ridden horse VEHICLE_DEPENDENT live) and PvP (4 steps with PotSG_); 1 024 attacks, all FOUND, 0 cross-target leaks, 0 exceptions, 0 evidence, 0 mitigation |
| 51 | Real-client VPS Track B (66) | PASS | idem (live numeric id reuse not forced; proven in deterministic and native tests) |
| 52 | Real-client VPS Track C (67) | PASS | idem |
| 53 | Real-client VPS Track D (68) | PASS | idem (observed RTT; no artificial latency injected or claimed) |
| 54 | Live counts recorded (69) | PASS | rounds 1, 2 and 3 (REAL-CLIENT VPS QUALIFICATION); round 3 is the final-jar record |
| 55 | Phase 10 regression: OBSERVE_ONLY, P10-F1 / G1 unchanged, P10-F2 preserved, corpora unchanged (70) | PASS | `mitigationFixtures` reproduces `docs/phase10-artifacts` byte-for-byte; MitigationActorTest and friends green |
| 56 | Phase 9 regression (71) | PASS | evidence tests green; `evidenceFixtures` unchanged (key order only); combat produces nothing for Phase 9 |
| 57 | Phase 8 regression: 32 451 / 0 INVALID; 3 290 / 3 290 (72) | PASS | ValidationCorpusTest green; `validationFixtures` unchanged (P11-F3 changes no corpus count) |
| 58 | Phase 7 regression (73) | PASS | timing tests green; `timingFixtures` unchanged |
| 59 | Phase 5/6 regression: 88/88 differential, 0 drift; no-false-exclusion (74) | PASS | `physicsNativeDifferential` 88/88 exact single-step and chained; reconstruction tests green |
| 60 | Earlier-phase defect protocol (75) | PASS | P11-F1, F2, F3 and F10 recorded with origin, root cause, fix and regression; baselines unchanged; F10 and F12 … F26 requalified on the final jar (round 3) |
| 61 | Phase 11 defects documented, no silent fixes (76) | PASS | DEFECTS P11-F4 … F9, F11 (accepted limitation), F12 … F26 (independent review), F27 (open known defect, owner decision), F28 (deferred cost item) |
| 62 | Documentation and artifacts (77, 78) | PASS | this document; `docs/phase11-artifacts/` (no baseline manifest) |
| 63 | Static / architecture checks (80) | PASS | ArchitectureTest 15/15 |
| 64 | TODO / FIXME review (81) | PASS | none in Phase 11 changes |
| 65 | Final verification lane (82) | PASS | FINAL VERIFICATION (the live gates are rows 49–54) |
| 66 | No commit / baseline / push / tag; Phase 12 absent (84) | PASS | HEAD `59caec5` unchanged; working tree only |

## FINAL VERIFICATION

Isolated worktree `wip/phase11` (HEAD `59caec5…` plus this working tree, hash-identical to the main checkout's WIP before the review round), Temurin 25.0.4.1+1. It was run after the last production change (P11-F26). Earlier runs are superseded.

| Step | Result |
|---|---|
| `gradlew clean test` | **841 tests, 0 failures** (825 before the review round + the QA reproductions and new tests) |
| `gradlew nativeTest` | **143 tests, 0 failures** |
| `verifyMandatoryCoverage` | PASS, 127/127 (0 missing) |
| `replayConformance` | PASS (pinned digests unchanged) |
| `movementPropertyCoverage`, `shapeCoverage` | PASS |
| `pluginJar` | `b6699bed23fe1ce2cf85337930999b5637b4bb62742b9a5430476c3db1707c9c` (reproducible: a clean, cache-less rebuild is byte-identical). Supersedes `837e1fa7…` (post-F10) and `948e06ca…` (rounds 1–2) |
| `physicsNativeDifferential` | 91 scenarios, 88 compared, **88/88 exact** single-step and chained, 3 deferred fluid, 0 oracle errors |
| Fixture regeneration (timeline, world, collision, reconstruction, timing, validation, evidence, mitigation) | exit 0 |
| Phase 0–10 artifacts | unchanged (see note) |
| `combatFixtures` | 40 fixtures, 0 mismatches, 12/12 lookup statuses, 8 replay traces, 0 failures; deterministic |
| `combatMemoryHarness` | recorded again after the review round (MEMORY) |
| `benchmarkCombat` | recorded again after P11-F26, including the 0 / 40 / 256-entity kernels (PERFORMANCE) |
| ArchitectureTest | 15/15 (includes `combatStaysWithinThePhase11Boundary`) |
| `git diff --check` | clean |
| TODO / FIXME / HACK / TEMP / XXX | none |

Note on the Phase 0–10 artifacts: regeneration rewrote some frozen files, and which ones varies between JVM runs. The Phase 5/9/10 fixture
mains write some maps with `Map.of`, whose iteration order is randomized per JVM. This run rewrote `phase9-artifacts/window-results.json` and
`phase10-artifacts/{setback,suppression}-results.json` (key order only; semantically equal) and `phase9-artifacts/native-differential-results.json`
(the oracle server's run duration only); the previous run hit `phase5-artifacts/movement-property-coverage.json` instead. All were
verified semantically equal and restored from HEAD, so no frozen artifact changed. The Phase 11 fixture main writes insertion-ordered maps and
is byte-stable.

Phase 1–10 regressions: none.
- **Phase 8:** 32 451 / 0 INVALID; the impossible corpus 3 290 / 3 290 (ValidationCorpusTest; `validationFixtures` unchanged).
- **Phase 9:** 0 false evidence and dedup PASS; `evidenceFixtures` unchanged.
- **Phase 10:** `mitigationFixtures` reproduces the frozen artifacts (legitimate → 0 eligible, impossible → 0 eligible). OBSERVE_ONLY,
  P10-F1 / G1 unchanged, P10-F2 preserved.
- **Phases 5/6/7:** timing, reconstruction and physics gates green.
