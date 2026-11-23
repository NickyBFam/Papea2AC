# Phase 12 — Reach / hitbox / historical ray tracing

Status: **IN PROGRESS — NOT READY** (offline engine, backtrack refinement, outline table, JMH and memory recorded; VPS qualification and evidence wiring pending). Uncommitted work on `wip/phase12` in `Papea2AC-wip-phase12`, base Phase 10 receipt
`59caec5a0b4bde0c13dadca1807c0b0f3f039a7d` plus the Phase 11 contract snapshot (see INPUTS). Phase 12 cannot be declared final, baselined or
integrated until Phase 11 is human-approved, baselined, receipted and pushed. No commit, push, tag or baseline has been made.

Pinned pair: Paper 26.2 build 124 (`paper-26.2.jar` sha256 `16aada2b…d9c1f9`) and the vanilla 26.2 client (`26.2.jar` sha256
`40896ee9…fa290`, unobfuscated). Every vanilla fact below was read from those two jars' bytecode (javap / java.lang.classfile) unless it says
otherwise. Facts are cited as `Class.method bc <offsets>` so a reviewer can re-read them.

## SCOPE

Phase 12 answers, for every observed ATTACK, one question: **could the vanilla 26.2 client, in some coherent scene consistent with what the
server observed and sent, have produced this attack?** It returns VALID with a concrete legitimate witness, INVALID only when every
admissible scene is covered and fails (an exclusion certificate), or UNCERTAIN with named reasons (COMBAT_MODEL.md pipeline step 5).

It does not judge rotation quality (15), KillAura behaviour (16), clicks (17) or timing (14), and it never mitigates (Phase 10 stays
OBSERVE_ONLY). Whether INVALID becomes Phase 9 evidence is wired only after the engine is qualified; see EVIDENCE.

## INPUTS AND CONTRACT STATE

| Input | Source | State |
|---|---|---|
| `AttackContext`, `EntityLookup`, `EntityRevision`, `EntityKey`, `CombatCompleteness`, `CombatProvenance` | Phase 11 contract **v2**, `phase11-snapshot-2.tar` (sha256 `31db777d…ecfe`, 64/64 file hashes verified). Rebased from snapshot 1 (`a23ff622…`): 20 files updated, 3 added, one clean three-way merge (`anticheat-testing/build.gradle.kts`) | applied to this worktree |
| `VEHICLE_DEPENDENT`, PiercingAttack, capture-clock `nanos`, PlayerInfo reason, wording | CONTRACT_CHANGED-1 (final) | consumed: any position status other than EXACT makes the target UNSUPPORTED (never INVALID); `nanos` is not used |
| Target network position wording | `oracle-to-phase11-06` / P11-F24: the network target is not always the client's interpolation endpoint (local displacement `addDelta`, far snaps that do not cancel interpolation, rotation-only re-targeting) | R4 treats the endpoint as "network target, unless a listed local displacement may apply"; pistons and vehicles make the target UNSUPPORTED |
| Spear STAB | `oracle-to-phase11-01` / P11-F19: a piercing-weapon attack is a target-less `PiercingAttack`; Paper ignores an ATTACK while the main hand holds a piercing weapon | out of v1 (R11) |
| Attacker movement certificate | Phase 8 `MovementValidationRunner` chain (VALID = an exact legal candidate reproduces the claim) | read through a new read-only accessor (CORE COMPOSITION) |
| Attacker attributes / game mode | Phase 8/9 `MovementContextLedger.View` (self attributes incl. `entity_interaction_range`, `scale`; game mode) | available |
| Markers / client tick ends | Phase 7 `TimingLedger` (ping/pong markers, `ServerboundClientTickEnd`) | available |

## PINNED VANILLA MECHANICS (26.2)

### M1. The attack is decided by the client's per-tick pick

`Minecraft.tick` order (client, bc 64–544): `gameMode.tick()` → **`pick(1.0F)`** (bc 93) → `handleKeybinds()` (bc 181; `startAttack` sends
`ServerboundAttackPacket`) → `level.tickEntities()` (bc 244; other entities' interpolation steps, then `LocalPlayer.tick` → `sendPosition`)
→ level tick → `ServerboundClientTickEndPacket` (bc 541). Consequences:

- Every ATTACK in client tick T was aimed with the hit result computed at the **start** of tick T, before T's entity steps and before T's
  movement packet. Several clicks in one tick reuse that one hit result.
- The attack precedes, in the serverbound stream, the movement packet and the tick end of the same client tick.
- `startAttack` only sends an attack when the hit result is an `EntityHitResult` (and the item is enabled / not on cooldown); a miss or a
  block hit sends no ATTACK. A piercing-weapon main hand takes the STAB path instead (no ATTACK).

`Minecraft.pick(float)` → `LocalPlayer.raycastHitResult(1.0F, camera)`:

1. If the **active item** (`LivingEntity.getActiveItem`: the using item while using, else main hand) has `DataComponents.ATTACK_RANGE`, the
   component's own path (`AttackRange.getClosesetHit`) decides; a block hit is filtered at block range. If that gives null or MISS, step 2.
2. Classic path `LocalPlayer.pick(camera, blockRange, entityRange, 1.0F)` (bc 0–186):
   - `d = max(blockRange, entityRange)`; eye `E = camera.getEyePosition(1.0F)`;
   - block hit `B = camera.pick(d, 1.0F, false)`: `Level.clip(ClipContext(E, E + V·d, OUTLINE, Fluid.NONE, camera))`. If B is not MISS, the
     entity search distance becomes `|E−B|` (`dB² = E.distanceToSqr(B.location)`);
   - view `V = camera.getViewVector(1.0F)`; search end `F = E + (V.x·d, V.y·d, V.z·d)`; search box = camera box `expandTowards(V·d).inflate(1)`;
   - entity hit `H = ProjectileUtil.getEntityHitResult(camera, E, F, box, CAN_BE_PICKED, d²)` (below);
   - if `H != null` and `|E−H.location|² < dB²` (strict; for a MISS, `dB²` is the distance to the miss point at `d`) the result is H, then
     **filtered**: `filterHitResult(H, E, entityRange)` keeps it only if `H.location.closerThan(E, entityRange)`, i.e. `|E−H|² < range²`
     (`Vec3.closerThan` bc 19–26, strict), otherwise it becomes a block MISS (no attack).

`ProjectileUtil.getEntityHitResult(shooter, from, to, box, pred, maxD²)` (bc 0–228): for each entity in `level.getEntities(shooter, box, pred)`
(the shooter excluded): `bb = e.getBoundingBox().inflate(e.getPickRadius())`; if `bb.contains(from)`: take it at distance 0 when
`best ≥ 0 && e.canBePickedFromInside()`; else if `bb.clip(from, to)` hits at `d² < best` (or `best == 0`): take it, except that an entity
with the same root vehicle as the shooter only replaces a distance-0 best. **The nearest pickable entity along the ray wins.**

`EntitySelector.CAN_BE_PICKED` = not spectator and `isPickable()`.

### M2. Eye position and view vector (numerics)

- `Entity.getEyePosition(float f)` (bc 0–62): `x = Mth.lerp(f, xo, getX())`, `y = Mth.lerp(f, yo, getY()) + (double) eyeHeight`, `z` likewise.
  `Mth.lerp(double d, a, b) = a + d·(b − a)` (bc 0–8). With f = 1.0 that is `xo + (x − xo)`, **not always bit-equal to x**. `xo` is the
  position at the start of the previous client tick, `x` the position after it.
- `eyeHeight` is the float field set by `refreshDimensions` from the pose's `EntityDimensions.eyeHeight()` (scaled by `scale`), so it follows
  the pose of the previous tick.
- `LocalPlayer.getViewXRot(f)` = `getXRot()`; `getViewYRot(f)` = `getYRot()` unless a passenger (then `rotLerp(f, yRotO, yRot)`, which at
  f = 1.0 is `yRot`). So the pick uses the **current** yaw/pitch, i.e. the rotation after every mouse turn applied before tick T.
- `Entity.calculateViewVector(pitch, yaw)` (bc 0–67): `f = pitch·0.017453292F`, `g = −yaw·0.017453292F` (float), `h = Mth.cos(g)`,
  `i = Mth.sin(g)`, `j = Mth.cos(f)`, `k = Mth.sin(f)` (table lookups, double argument), `V = ((double)(i·j), (double)(−k), (double)(h·j))`.
  `Mth.sin(d) = SIN[(int)((long)(d·10430.378350470453) & 65535)]`, `cos` adds 16384.0 before the cast (the Phase 5 pinned table).

### M3. Same-step rotation (packet order)

Within tick T, nothing changes the local player's yaw/pitch between `pick(1.0F)` and `sendPosition` for a non-passenger: the rotation
the attack used is the rotation **sent in tick T's movement packet**, which follows the ATTACK in the stream; if tick T's movement packet
carries no rotation (unchanged rotation is not sent) it equals the last sent rotation. Tick T ends at the next `ServerboundClientTickEnd`.
This is the "protocol-specific same-step ambiguity where proven" that COMBAT_MODEL.md permits; it is not later aim (a rotation from a later
tick is never used). A passenger's yaw can change inside the tick (vehicle `positionRider` / clamp): passenger attackers are UNSUPPORTED.

### M4. Hitboxes

- `Entity.getBoundingBox()` is the box made at the entity's **current client position** by `EntityDimensions.makeBoundingBox(x, y, z)`
  (bc 0–49): `w = width / 2.0F` (float), box `(x − w, y, z − w, x + w, y + height, z + w)` with float→double widening.
- `LivingEntity.getDimensions(pose)` = `SLEEPING_DIMENSIONS` for SLEEPING, else `getDefaultDimensions(pose).scale(getScale())`
  (`scale` attribute). `LivingEntity.getDefaultDimensions` = type dimensions `.scale(getAgeScale())`, `getAgeScale()` = `isBaby() ? 0.5F : 1.0F`.
  Many types override `getDefaultDimensions` with `isBaby() ? BABY_DIMENSIONS : super…` (bytecode of Zombie, Husk, Cow, Villager, Chicken…).
- Player (`Avatar.getDefaultDimensions`): the `Avatar.POSES` table (STANDING 0.6×1.8 eye 1.62, CROUCHING 0.6×1.5 eye 1.27, FALL_FLYING /
  SWIMMING / SPIN_ATTACK 0.6×0.6 eye 0.4, SLEEPING / DYING 0.2×0.2 fixed).
- `getPickRadius()` is 0 for every pickable target type in the table (only projectiles declare a non-constant radius).
- Extracted table: `anticheat-version/…/entity-geometry-paper-26.2-124.tsv` (158 types, client and server class chains compared: 0
  disagreements beyond the expected RemotePlayer/ServerPlayer split). Summary: `docs/phase12-artifacts/entity-geometry-summary.json`.

### M5. Target position on the client (interpolation)

- Remote entities apply network updates through `moveOrInterpolateTo`. If `getInterpolation()` is null (Entity default) the entity snaps;
  otherwise `InterpolationHandler.interpolateTo` stores the target and **steps** (3 for LivingEntity, `DEFAULT_INTERPOLATION_STEPS`; per-type
  in the table). Each entity tick, `interpolate()`: `pos = lerp(1/steps, pos, target)` per axis (`Mth.lerp(double)`), steps−1. A new update
  resets steps.
- `interpolate()` also adds the entity's **local displacement** since the previous tick to the target when the moved box is collision-free
  (`addDelta`, bc 24–85; oracle-to-phase11-06). A far (> 64 blocks) sync/teleport snaps without cancelling an active interpolation.
- Clientbound packets are handled on the client main thread in FIFO order (`PacketProcessor`: one `ConcurrentLinkedQueue`, drained by
  `processQueuedPackets` once per frame, before that frame's 0–10 ticks; `ClientPacketListener.handleMoveEntity` and
  `ClientCommonPacketListenerImpl.handlePing` both go through `PacketUtils.ensureRunningOnSameThread` → `scheduleIfPossible`). `handlePing`
  answers with `ServerboundPongPacket` from that handler.

### M6. What the server checks (descriptive only)

Paper `ServerGamePacketListenerImpl.handleAttack` (bc 0–311): ignores immobile players; requires a loaded client, non-spectator; kicks on self
attack; requires the target inside the world border; then
`player.isWithinAttackRange(mainHand, target.getBoundingBox(), clientInteractionLeniencyDistance (default 3.0))`, i.e.
`AttackRange.isInRange`: `sqrt(target box distanceToSqr(eye))` within `[min − margin − buffer, max + margin + buffer]`; the default range is
`AttackRange(0, r, 0, r, 0, 1)` with `r = (float) entity_interaction_range`. **Paper accepts attacks up to 6.0 blocks from the box with
default settings.** An ATTACK while the main hand has `PIERCING_WEAPON` is ignored; items / XP orbs / non-attackable arrows kick. None of
this is a Phase 12 input: server acceptance is not legitimacy, and Phase 11 records no server outcome.

### M7. Interaction attributes

`entity_interaction_range` default 3.0 (range 0–64), `block_interaction_range` 4.5 (0–64), `scale` 1.0 (0.0625–16), creative modifier value 2.0
(`Player.CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER_VALUE`; Paper `ServerPlayer.updatePlayerAttributes` bc 37–69 adds/removes the transient
`creative_mode_entity_range` modifier by game mode; the client has no such code, so it learns the value only from the synced attribute
snapshot — to be confirmed live). The
attacker's values come from the synced attribute snapshot (`MovementContextLedger`), computed with the pinned modifier order
(`MovementProfile`), never assumed.

## DESIGN DECISIONS

- **R1 Scene = (eye, view, target box, world).** A scene is one coherent hypothesis: the attacker's eye and pose from one certified
  movement state, the same-step rotation (M3), one target position from one admissible interpolation schedule (R4) with its dimensions from
  the same revision, and the observer world's outline shapes at the attack. At most 64 scenes and 4096 geometry work units per attack
  (COMBAT_MODEL bounds); exceeding either gives UNCERTAIN(SCENE_LIMIT / WORK_LIMIT).
- **R2 Attacker eye (B2, amended for exclusion — owner decision 2026-10-03, see DECISIONS).** `eyeSource = CERTIFIED_CONCRETE` only when
  the Phase 8 chain is anchored and VALID through the movement packet that closed tick T−1 and its frontier states give the position and pose;
  the eye is then `xo + (x − xo)` from the last two certified tick positions with the pose's eye height, evaluated over the small interval
  the lerp rounding allows when `xo` is not certified (exact under Sterbenz; a few ulps otherwise). Only a certified eye can give VALID.
  `ABSTRACT_EXCLUSION_ONLY` when only a sound superset of a legitimate client's eye is known: the attacker's own last position claim, widened
  by the client's send threshold (`LocalPlayer.sendPosition`: a position is sent when it moved more than 2.0E-4 since the last sent one, so an
  unsent tick lies within 2e-4 of the claim) and by every possible pose's eye height. Under the amended rule this set may support INVALID
  (exclusion over the whole set), never VALID. `UNAVAILABLE` otherwise. A claim outside the supported frontier is
  `ATTACKER_FRONTIER_CONFLICT`, routed to movement, never a second reach exclusion.
- **R3 Witness.** VALID needs a concrete scene in which the classic pick (M1) returns the target: the ray clips the target box (or starts
  inside it) at squared distance `< range²`, and strictly before the first OUTLINE block hit, with every numeric operation done as the client
  does it. The witness is robust: it must hold over the eye's rounding interval.
- **R4 Target positions.** For the target incarnation the admissible client positions at the attack form a box (`TargetHull`): every
  interpolation step is a convex combination (`lerp(1/k, pos, target)`), so the client position always lies in the box of the window's
  network targets (every update the server sent before it captured the attack, applied or not) plus the residual of older positions (R5),
  outward-rounded. Exact schedule enumeration is NOT used: the client's position depends on its whole application history, so v1 never
  claims one concrete target position. Instead both lanes are "for all": the exclusion lane must miss the union of the boxes over the
  admissible positions (`RayBoxBounds.hull`), and the witness lane must enter their intersection (`RayBoxBounds.core`) in range, so a VALID
  holds whichever admissible position the client really had. Preconditions (else UNSUPPORTED, never INVALID): every window revision EXACT
  (not INTERPOLATION_/VEHICLE_DEPENDENT / UNSUPPORTED), no vehicle relation, no piston block event within a few blocks since the window
  start (pistons displace entities on the client: `addDelta`), the lifetime box complete since the spawn snap.
- **R5 Window lower bound (backtrack).** An update U is **proven applied** before the attack when a marker sent after U (wire order) was
  answered by a pong that precedes the attack in the serverbound stream (M5: FIFO main-thread handling). The number of client tick ends
  between that pong and the attack is a lower bound on the interpolation steps applied since U. Positions older than the newest proven-applied
  update are admissible only with the residual weight the interpolation leaves them: after `m` proven ticks the old position's weight is at
  most `((steps−1)/steps)^m` (each tick's `lerp(1/k)` with `k ≤ steps`), applied to the distance from the window to the incarnation's
  lifetime box (`TargetHull`). Combat-time markers (owner decision 2026-10-03, see DECISIONS) make the proven frontier at most about one
  tick old during a fight. Without any marker evidence the whole lifetime box is admissible and the result's window is `HORIZON_ONLY`.
  **Refinement (owner: "build it now", 2026-10-03): every answered marker at once** (`TargetHull.nested`). Each answered marker `t` proves
  that the revision in effect when the client handled it was applied at least `m_t` ticks before the attack, so the total weight of every
  older target is at most `c_t = ((steps−1)/steps)^{m_t}`. Per axis, with the retained history since the spawn split at those revisions,
  the extreme coordinate is the optimum of a small linear program over non-decreasing prefix weights `0 ≤ P_t ≤ c_t`, solved exactly
  (`TargetHull.maxPrefixWeights`, a dynamic program over the effective caps). With a marker every tick in combat, a target position from
  `k` ticks before the newest applied update keeps at most `(2/3)^k` of its weight, so a backtracking client is excluded once the target has
  moved enough during those `k` ticks.
- **R6 Dimensions.** A target's box is exact when its type's dimension chain is the plain one (type dimensions × `scale`, or the Avatar pose
  table) and `scale`, pose and baby flag are known at the same revision. Babyable types use `{adult, baby}`: the exclusion lane takes the
  union box, the witness lane needs the flag. Types with any other dimension/bounding-box override (slime size, armor stand, shulker,
  interaction, end crystal…) are UNSUPPORTED until modelled; UNSUPPORTED is never INVALID.
- **R7 Occlusion.** Blocks occlude only through their **outline** shapes (ClipContext OUTLINE, fluids ignored), not collision shapes
  (tall grass, flowers, torches have outlines without collision). The outline table (`OutlineShapeTable`, generated by
  `extractOutlineShapes` from the pinned server: every state's `getShape(EmptyBlockGetter, ZERO, CollisionContext.empty())`, classified
  STATIC / OFFSET / CONTEXT / LEVEL by position, held-item context and throwing-getter probes) gives each crossed cell's boxes: a VALID
  witness requires every cell the ray can cross up to the target's entry (plus margin) to be known and each of its outline boxes to be
  soundly missed or met only beyond that entry (`RayBoxBounds.exclude` with the client's clip tolerance); OFFSET outlines are widened by their
  maximum offsets; CONTEXT / LEVEL outlines and unobserved cells are UNCERTAIN(BLOCKS_UNRESOLVED). Exclusion ignores blocks (blocks only
  remove hits), so no OCCLUSION INVALID is produced yet.
- **R8 Other entities.** The nearest pickable entity wins (M1), so another entity in front would have taken the hit. v1 does **not** use
  other entities to exclude (their interpolation state is as uncertain as the target's) and a VALID witness records
  `INTERPOSED_ENTITIES_NOT_EVALUATED`.
- **R9 Miss classes.** For an all-scenes failure the certificate names one class per scene and the result keeps them apart: REACH (the ray
  hits the box but at distance ≥ range), ANGLE (the ray misses the box), OCCLUSION (a block outline is hit first), INVALID_TARGET (the target
  cannot be picked: self, spectator, `isPickable` false). A mixed certificate is reported as mixed, never collapsed.
- **R10 Server leniency is not an input.** Paper's 3.0-block buffer (M6) never widens or narrows a Phase 12 decision.
- **R11 Attack-range items.** v1 supports the classic path only. An attacker whose active item is unknown, or has `attack_range`, or is a
  piercing weapon, gets UNCERTAIN(ATTACK_RANGE_ITEM_UNSUPPORTED / HELD_ITEM_UNKNOWN). The held-item knowledge needed is listed under
  OPEN CONTRACT NEEDS.
- **R12 Numerics.** Concrete lanes reproduce the client's operations exactly (no reassociation, float casts where the client casts, table
  trig). Exclusion lanes use outward-rounded intervals (NUMERICAL_POLICY). No epsilon is added to make a case pass.
- **R13 Player targets.** Phase 11 contract v2 puts `TARGET_PLAYER_INFO_UNTRACKED` on every player target. It does not block a reach
  INVALID: Phase 12 does not read the Phase 11 completeness reasons as gates, only the geometric inputs (position statuses, pose, scale,
  vehicle relations), and PlayerInfo affects neither the player's box (Avatar pose table × `scale`; `RemotePlayer.updatePlayerPose` is a
  no-op, so the synced pose is the client's) nor its position. What PlayerInfo carries that matters is the target's game mode: a spectator
  is not pickable, which only removes hits, so every exclusion stays sound. A VALID on a player target notes that the spectator status was
  not evaluated. So PvP reach INVALID is possible in v1 (R-L15 / R-B07 cells count).

## RESULT TYPE (published in PHASE12_REACH_CONTRACT_READY v1)

`combat.reach.ReachValidation(session, sequence, attackId, status, missClasses, reasons, eyeSource, frontier, targetType, targetNetworkId,
targetIncarnation, scenes, workUnits, witness, certificate, entityRange, scale, profileId, notes)`: statuses VALID / UNCERTAIN / INVALID;
miss classes REACH / ANGLE / OCCLUSION / INVALID_TARGET; 27 fixed reasons; eye source CERTIFIED_CONCRETE / ABSTRACT_EXCLUSION_ONLY /
UNAVAILABLE; `frontier` = the window bound (`ApplicationFrontier`, MARKER_PROVEN / HORIZON_ONLY, with marker / pong sequences and proven
ticks). The constructor rejects a VALID without a witness or a certified eye, an INVALID without a certificate, an UNCERTAIN without a reason.

## WHAT PHASE 12 KEEPS ITSELF (not in the Phase 11 contract)

`core.ReachRunner` keeps small bounded ledgers from the same ordered observations:
1. **Target lifetime box** per incarnation since its spawn snap (any non-EXACT revision, a coverage break or an eviction makes it unknown).
2. **Target metadata**: the `scale` attribute (synced attributes, computed with the pinned modifier order) and the baby flag (the type's
   `DATA_BABY_ID` from the extracted table), with the sequence of the last change; a change after the proven frontier makes the value unknown
   (the client may still hold the old one) and the dimensions widen to min/max.
3. **Attacker held-item path** (R11): CLASSIC only in survival/adventure while no item with `attack_range` / `piercing_weapon` /
   `kinetic_weapon` among its components was ever sent to the player (container content / slot / cursor packets carry the full component key
   set); otherwise HELD_ITEM_UNKNOWN for the session. Phase 19 owns real inventory tracking.
4. **The observer's vehicle** (SetPassengers) for the passenger rule.
5. **Certified eye**: the live Phase 8 chain's frontier states through the read-only `MovementValidationRunner.frontierStates()`.

## DECISIONS (owner, 2026-10-03)

- **D12-1 — COMBAT_MODEL B2 amended for exclusion.** Asked because B2 as written ("raw claimed position is not an independent eye source")
  would leave almost every real attack UNCERTAIN: in the Phase 11 VPS rounds 0 of 390 attacks among mobs had certified movement
  (`ATTACKER_MOVEMENT_UNVALIDATED`; Phase 8 stays unanchored next to entities). Options offered: keep B2 strict; amend for exclusion only
  (recommended); fix Phase 8 anchoring first. **Chosen: amend for exclusion.** The attacker's own position claims (widened by the 2e-4 send
  threshold and every pose's eye height) may bound the eye for INVALID; VALID still requires a certified concrete eye. Rationale: a
  legitimate client's position packets are its true positions, so an exclusion over the claim set cannot flag an honest player; only a client
  lying about its own position is affected, and it cannot obtain VALID from a claim. This is a Phase 12 spec decision for review; it does not
  edit the frozen COMBAT_MODEL.md text. Implemented as `ReachValidator.Input.exclusionPermittedForAbstractEye` (true in production).
- **D12-2 — combat-time markers (a user-approved change to the frozen Phase 7 marker scheduler).** Asked because ~1 Hz markers bound the
  admissible target window only to about one second, so 100–300 ms backtrack would pass. Options offered: current cadence; combat-time
  per-tick markers (recommended); 3 s horizon only. **Chosen: combat-time markers.** `PacketEngine.issueMarker`: an ATTACK from the player
  opens a 3 s window (`COMBAT_MARKER_WINDOW_NANOS`) in which a marker is due every `MARKER_MIN_GAP_NANOS` = 50 ms (one per tick), under the
  unchanged 16-pending cap; outside the window the Phase 7 cadence (1 s periodic + brackets after influences/corrections) is unchanged.
  Tests: `TimingMarkerSchedulingTest.anAttackOpensThreeSecondsOfPerTickMarkersUnderTheSameCaps`,
  `combatTimeMarkersRespectTheSixteenPendingCapAgainstASilentClient`. Phase 7 gates rerun: see VERIFICATION. Phase 13 confirmed it does not
  need the change; Phase 14's timer model sees the new cadence (finding filed).

## IMPLEMENTATION MAP

| Module / file | Content |
|---|---|
| `anticheat-version` `EntityGeometryTable` + resource `entity-geometry-paper-26.2-124.tsv` | 158 entity types: dimensions, dimension class (97 PLAIN/PLAIN_LIVING, 28 BABY, 2 AVATAR, 31 VARIABLE), baby dimensions + data id, interpolation steps (115 × 3, 33 snap, 10 dynamic), pick radius, pickability; Avatar pose table, living sleeping box; interaction / scale attribute defaults and bounds; creative modifier. Generated by `extractEntityGeometry` (nativeTest, both jars) |
| `anticheat-combat` `combat.geometry.PickMath` | Client-exact primitives: AABB constructor / inflate / expandTowards / contains / distanceToSqr / intersects / clip (1e-7 face tolerance, face order), Vec3 distance, `Mth.lerp`, eye position, view vector (pinned sine table), `makeBoundingBox`, `EntityDimensions.scale` |
| `combat.geometry.ScenePick` | The classic pick for one target (M1): block raycast input, segment shortening at a block hit, entity clip, strict `<` range filter; miss classification |
| `combat.geometry.RayBoxBounds` | Outer exclusion (Minkowski box, outward rounding, clip tolerance) and robust inner witness (inward rounding, entry-face threshold check); hull / core of the admissible boxes |
| `anticheat-version` `OutlineShapeTable` + resource `outline-shapes-paper-26.2-124.tsv` | Every block state's outline boxes (1 196 blocks, 32 366 states, 859 distinct shapes) with kind STATIC 1 138 / OFFSET 39 / LEVEL 17 (shulker boxes) / CONTEXT 2 (light, scaffolding) and the maximum per-position offsets. Generated by `extractOutlineShapes` (nativeTest, pinned server), summary `outline-shapes-summary.json` |
| `combat.reach.ApplicationFrontier` | The newest answered PING before the attack and the client ticks since its pong (R5); `all(...)` returns every answered PING; `index(events)` answers both for any attack from one pass |
| `combat.reach.FrontierTracker` | The same answers kept incrementally from the timing ledger's accepted events (perf-to-phase12-01): the ring is the accepted events from the eviction count on, so no per-attack copy of the ring |
| `combat.reach.TargetHull` | Admissible position box: window targets + `((steps−1)/steps)^m` residual of the lifetime box (R4/R5); `nested(...)` with every answered marker (`Bound`, exact `maxPrefixWeights`) |
| `combat.reach.ReachValidator` / `ReachValidation` | The pure decision (R1–R13) and its result; `CellOutlines` (per-cell outline boxes for the witness); `possibleEntityBox` (Phase 13 push exclusion, finding phase13-to-phase12-02) |
| `combat.reach.ReachReplayMapper` + `schema.ReachCapsule` | Replay projection, canonical bytes, SHA-256 |
| `anticheat-core` `ReachRunner` | Per-session composition: ledgers, pending attacks until their client tick ends, expiry, results ring, metrics, diagnostics; retained history + marker bounds per target; observer-world outlines per crossed cell; `entityInput(...)` for any entity by network id (Phase 13) |
| `core.PacketEngine` (changed) | Combat-time markers (D12-2), ReachRunner wiring, `reachStatusLine()`, reach lines in `combatDiagnosticLines` |
| `core.MovementValidationRunner` (changed) | Read-only `frontierStates()` |
| `platform-paper` `Papea2AC` (changed) | prints the reach status line in `/papea2ac status` |
| `anticheat-testing` | `NativeReachGeometryTest`, `EntityGeometryExtractMain` (nativeTest); `ReachGeometryPropertyTest`, `ReachValidatorTest`, `ReachRunnerIntegrationTest`, `ReachCorpus(Test/Main)`, `ReachReplayTest`, `ReachNestedBoundTest`, cadence tests in `TimingMarkerSchedulingTest`, Phase 12 boundary in `ArchitectureTest`; `OutlineShapeExtractMain` (nativeTest); `ReachBenchmarks` (JMH), `ReachMemoryMain`; tasks `extractEntityGeometry`, `extractOutlineShapes`, `reachFixtures`, `benchmarkReach`, `reachMemoryHarness` |

## VERIFICATION (2026-10-03, Temurin 25.0.4.1+1, worktree = 59caec5 + phase11-snapshot-2 + Phase 12)

| Step | Result |
|---|---|
| `gradlew test nativeTest :anticheat-testing:verifyMandatoryCoverage` (lane after the perf-to-phase12-01 change) | **1 018/1 018 test results (test + nativeTest), 0 failures, verifyMandatoryCoverage PASS**; `git diff --check` clean |
| perf-to-phase12-01 (attack input building) is result-identical | `reach-corpus-results.json` byte-identical; a runner probe (12 sessions, ~29 000 attacks, bursts, unanswered and late pongs, ring evictions) gives the same digest of every published status and retained result on snapshot 2 and on this tree; `FrontierTracker` equals the ring-copy index on >5 000 queries through count and horizon evictions; the index equals the slice scans on 40 000 queries |
| JMH attack path (`benchmarks-attack-path.json`, `attackTick` / `attackBurst64` through the runner, 40 zombies, a marker per tick) | reach adds **4.8 µs and 16 KB per fighting tick** (6.5 vs 1.7 µs; was about 50 µs and 63 KB in perf's snapshot-2 measurement) and 137 µs / 261 KB per 64-attack burst (2.1 µs per attack) |
| `ReachNestedBoundTest` | the prefix-weight program equals brute force on 20 000 random instances; the nested box is never wider than the single-frontier box (5 000 histories) and narrower in most; outline lookups |
| `NativeReachGeometryTest` (client jar child-first, bit-exact) | clip 400 000 random + 200 000 adversarial, box ops 100 000, view vector 200 000, eye 100 000, dimensions 50 000, sine table 65 536: **0 mismatches** (`native-geometry-results.json`) |
| `ReachGeometryPropertyTest` | 2 766 outer exclusions checked against 553 200 concrete client scenes and 3 705 robust witnesses against 741 000: **0 contradictions** |
| `ReachValidatorTest` | 13/13, incl. the interpolation hull against an exact simulation of the client `InterpolationHandler` under random application schedules: 54 259 checks (54 056 through the nested bound), **0 escapes** |
| `TimingMarkerSchedulingTest` (Phase 7 + D12-2) | 6/6; `timingFixtures` regenerates the frozen Phase 7 artifacts byte-identical |
| `reachFixtures` (`reach-corpus-results.json`, 400 scenarios per class, run twice, identical) | see CORPUS |
| `extractEntityGeometry` | table sha256 recorded in `entity-geometry-summary.json`; client/server class chains: 0 disagreements besides RemotePlayer/ServerPlayer |
| JMH (`benchmarkReach`, `benchmarks.json`, under the coordination perf lock; the validator alone) | per decision 0.89–1.50 µs (INVALID certified 0.97, INVALID abstract eye 1.06, VALID certified 1.50, moving target two views 0.89), 1.2–1.7 KB allocated; client pick 20 ns; interpolation hull 149 ns; outer exclusion 192 ns; robust witness 188 ns and 0 B. One host, not capacity |
| Memory harness (`reachMemoryHarness`) | per session: empty runner 2.5 KB; full target ledgers 66 KB; 64 pending attacks 53 KB; 64 results 44 KB. Per attack + deciding tick end 5.1 KB garbage; tick end without an attack 352 B. Shared: metrics 1.8 KB, the two immutable tables |

## CORPUS (offline, `ReachCorpus`)

Client-tick simulation: a server update every tick, random downlink / jitter / uplink (1–3 ticks; the high-latency class 4–11 down, 4–9 up,
jitter 4), the exact 26.2 interpolation, a combat-time PING after every update, and the client-exact pick deciding whether a click is sent. The
validator receives exactly what the runner derives (retained history, a bound for every answered marker, lifetime box). Half the attackers
have a certified eye, half the claim set (D12-1). The "single" columns are the v1 rule (newest answered marker only), run on the same
scenarios.

| Class | Attacks | VALID (single) | INVALID (single) | INVALID share | Notes |
|---|---|---|---|---|---|
| legit stationary / walking / knockback / high latency | 3 868 / 3 455 / 845 / 3 311 | 2 070 / 1 119 / 234 / 459 (2 070 / 79 / 4 / 64) | **0 / 0 / 0 / 0** | 0 | the legitimate gate: 0 INVALID in 11 479 |
| reach 3.1 / 3.5 / 4.0 / 6.0 | 273 / 927 / 1 377 / 1 140 | 0 | 66 / 672 / 1 075 / 1 140 (3 / 140 / 352 / 1 140) | 24 / 72 / 78 / 100 % | REACH |
| hitbox +0.2 / +1.0 | 1 798 / 4 321 | 0 | 966 / 3 905 (133 / 2 388) | 54 / 90 % | mostly ANGLE |
| backtrack 4 / 8 ticks (prompt pongs) | 3 462 / 3 461 | 1 028 / 905 (92 / 78) | **179 / 420** (2 / 1) | **5.2 / 12.1 %** | ANGLE 112 / 284, REACH 67 / 136; see KNOWN LIMITATIONS |

The low VALID share for moving targets is expected: the server cannot know which of the last ~RTT worth of updates the client had applied, so
a VALID must hold over that whole motion.

## KNOWN LIMITATIONS (v1)

- **Backtrack is excluded only when the target moved enough.** With every answered marker (R5 refinement) an old position keeps at most
  `(2/3)^k` of its weight, so 5.2 % (4 ticks) and 12.1 % (8 ticks) of backtracked attacks are INVALID in the corpus, up from 2 / 1 attacks.
  A backtrack against a slow or stationary target stays inside the admissible box and is UNCERTAIN or VALID by construction.
- **No OCCLUSION exclusion.** The outline table only strengthens the VALID witness (a cell with a known outline the ray soundly misses no
  longer blocks VALID); CONTEXT / LEVEL outlines (light, scaffolding, shulker boxes) and unobserved cells stay UNCERTAIN(BLOCKS_UNRESOLVED).
- **Other entities on the ray are not evaluated** (a VALID records it).
- **Attack-range items, piercing weapons, creative mode** (held-item path unknown) and **passenger attackers**: UNCERTAIN.
- **VARIABLE-dimension types** (slimes, magma cubes, armor stands, shulkers, interactions, item frames, ender dragon, phantoms, pufferfish …):
  UNCERTAIN.
- **Silent ticks / unaligned claims** give only the abstract eye (no VALID). **No-tick-end clients** resolve with both rotations.
- **Attribute changes** after the proven frontier make the range or scale unknown at that attack.
- **No live qualification yet** (VPS rounds wait for the candidate jar and for Phase 11's baseline).

## QUALIFICATION

- The shared real-cheat matrix `Papea2AC-coordination\qualification\P12_REACH_MATRIX.md` (thread 9) is Phase 12's VPS plan; corrections go
  there as findings. Live rounds run only on Phase 12's exact candidate jar after offline qualification and after Phase 11 is baselined.

## PROGRESS

| Item | State |
|---|---|
| Pinned mechanics M1–M7 from bytecode | done |
| Entity geometry extraction | done (incl. baby dimensions / data ids, pickability, interpolation steps) |
| Geometry kernels + client-jar differential | done |
| Target hull, application frontier, validator, result type | done |
| Core composition, metrics, diagnostics, status line, replay projection | done |
| D12-1 (B2 amendment) and D12-2 (combat-time markers) | done |
| Corpus, property and replay tests | done |
| Backtrack refinement (every answered marker) | done (corpus above) |
| JMH record, memory harness | done |
| Outline shape table (VALID witness) | done; OCCLUSION exclusion not built |
| Phase 13 entity-bounds API (`possibleEntityBox`, `entityInput`) | adopted |
| perf-to-phase12-01 (per-attack timing ring scans) | fixed, result-identical; perf to re-measure |
| Evidence wiring (planned category REACH_EXCLUSION) | not started (needs qualification and review) |
| PHASE12_REACH_CONTRACT_READY | published v1 (see coordination handoffs) |
| VPS qualification | not started |
