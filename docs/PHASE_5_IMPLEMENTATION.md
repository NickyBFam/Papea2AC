# Phase 5 — Physics Simulator — implementation report

Status: **READY FOR HUMAN REVIEW** (implementation engineer: Claude; no self-approval; no baseline created; Phase 6 not started).
Parent: Phase 4 receipt `8bc0cc1ba63dc946003584aaec215f7087308544` (Phase 4 baseline `1685e96519f2eaec3f5b28c12d687e8118809b64`,
manifest composite `42e70b5b…`). Governing decisions: MOVEMENT_SIMULATION.md M1 ("Pure physics advances one versioned step"),
"Step specification" (exact operation order per ruleset), NUMERICAL_POLICY NUM1, WORLD_MODEL W3 (BlockBehaviorQuery ownership),
PLAYER_STATE.md (ExternalMotionInfluence: physics applies, player owns the ledger), COLLISION_ENGINE.md (no universal XYZ ordering),
PERFORMANCE.md (movement geometry 65536 units/event, inside sampling 512 cells/step, entity neighbourhood 32/2048), THREADING_MODEL T1.

## 0. Test counts (project convention: separate counts, never an aggregate alone)

| Suite | Phase 4 | Phase 5 | Delta | Notes |
|---|---|---|---|---|
| `test` | 416 | **484** | +68 | 49 classes; new: `PhysicsCoreMatrixTest` (53), `PhysicsPropertyTest` (5), `PhysicsOracleFixtureTest` (2), `MovementPropertyCoverageTest` (3), `PhysicsQualificationTest` (4), `ArchitectureTest` +1 (physics boundary) |
| `nativeTest` | 124 | **132** | +8 | `NativeMovementProfileTest` (8): block properties, attribute supplier, sine table bits + 100 000 `Mth.sin/cos` lookups, `AttributeInstance.calculateValue` (3 000+ random modifier sets), `Entity.getInputVector` (20 000), `Direction.axisStepOrder`, `AABB.clip`, `BlockGetter.forEachBlockIntersectedBetween` (6 000 traversals) - all bit-level |
| unique total | 540 | **616** | +76 | |
| native differential (process lane) | — | **88/88 scenarios, 3 105/3 105 ticks bit-exact** | | `physicsNativeDifferential`: a pinned Paper 26.2 build 124 server process runs the catalog through its own movement code |
| Phase 1 semantic coverage | 127/127 | **127/127** | 0 | `verifyMandatoryCoverage` |
| other gates | — | `movementPropertyCoverage`, `extractMovementProperties`, `physicsOracleJar`, `physicsNativeDifferential`, `benchmarkPhysics`, `physicsMemoryHarness` | | Gradle tasks in `anticheat-testing` |

## 1. SCOPE

Phase 5 delivers the deterministic vanilla forward model `PhysicsResult simulate(PhysicsState, MovementInput, PhysicsEnvironment)`:
given an EXPLICIT input candidate, a world/collision snapshot, an explicit external-influence decision and explicit
attributes/effects, it predicts the state after ONE client tick of the 26.2 WALKING locomotion (ground, air, jump,
sprint, sneak, crawl-in-a-gap, friction, speed/jump factors, bounce, step, climbing, cobweb/berry/powder-snow stuck
multipliers, honey slide, slime step-on slowdown, levitation, slow falling, external velocity SET/ADD, player pushing
and entity colliders as an interface, world-border proximity as an explicit gap), with position, velocity, pose, the
SIMULATED ground/collision flags, fall distance, stuck multiplier, supporting block, work counters, world provenance
and a rich status. It does **not** infer or enumerate inputs (Phase 6), does not classify VALID/INVALID or cheating
(Phase 8), implements no check, setback or punishment, and does not simulate fluids, elytra, creative flight, vehicles
or swimming (explicit deferrals, §21 and §32).

## 2. MODULE OWNERSHIP (approved DAG, no new module, no new edge)

| Role | Module | Content |
|---|---|---|
| PHYSICS OWNER | `anticheat-physics` | `PhysicsSimulator`, `PhysicsState`, `MovementInput`, `PhysicsEnvironment`, `PhysicsResult`, `Vec3d`, `MovementGeometry`, `InsideBlockSampler`, `FallResetRay`, `Uncertainty`, `PhysicsKernels` |
| INPUT (data) | `anticheat-version` | `MovementProfile` (named numeric constants, attribute definitions, sprint modifier, effect templates, `Mth.SIN` table, client attribute-modifier iteration order), `BlockMovementTable` (per-block friction/speed/jump/bounce/tags/override classification, per-state fluid) - pinned resources `movement-profile-paper-26.2-124.tsv`, `block-movement-paper-26.2-124.tsv`, `mth-sin-paper-26.2-124.bin` |
| INPUT (world) | `anticheat-world` | forward addition `BlockBehaviorQuery` (WORLD_MODEL W3: world owns the behaviour resolver over observed cells; version provides the definitions) |
| INPUT (geometry) | `anticheat-collision` | Phase 4 frozen API: `CollisionEngine.query`, `AxisClip.clip`, `PlayerBox`, `ShapeContext`, `BlockShapeResolver` (powder snow inside shape) |
| INPUT (values) | `anticheat-model` | `Aabb`, `BlockStateKey`, `BlockPos` |
| OUTPUT | `anticheat-core` | `PacketEngine.movementProfile / blockBehaviors / physics / physicsQualification`, `physicsEngineStatus()`, `physicsArm/physicsReport/physicsCancel/physicsOneStep` |
| OUTPUT (display only) | `anticheat-platform-paper` | `/papea2ac physics <player> [step <scenario> \| arm <scenario> \| report \| cancel]` and a `status` line; no physics edge (primitives via core) |
| DEPENDENCY DIRECTION | physics → {model, version, world, collision}; core → physics; platform-paper ↛ physics; **physics ↛ player** | `ArchitectureTest` 9/9 (`physicsModuleStaysWithinThePhase5Boundary`: no Bukkit/NMS, no reflection, no wall clock/randomness, no transcendental Math, no inference/verdict vocabulary) |

## 3. PHASE BOUNDARY

The simulator receives one input and returns one prediction. It never searches inputs, never ranks candidates, never
compares against an observed movement, never emits VALID/INVALID/UNCERTAIN_SEARCH and never triggers actions. The
real-client qualification tool compares predictions with observations under a DECLARED scenario and prints a
discrepancy report; its wording ("QUALIFYING TRACE WITH DISCREPANCIES", never "invalid") and the code path are diagnostic.
`ArchitectureTest.noFutureEngineOrForbiddenMathImplementation` still holds simulation/combat/checks/evidence/mitigation/interaction empty.

## 4. PHYSICS STATE (`PhysicsState`, immutable record)

| Field | Vanilla source | Notes |
|---|---|---|
| `position` | `Entity.position` | feet |
| `velocity` | `Entity.deltaMovement` | the value carried between ticks |
| `pose` | `Entity.getPose()` | STANDING / CROUCHING / SWIMMING(crawling) simulated; FALL_FLYING/SPIN_ATTACK/SLEEPING/DYING → UNSUPPORTED_POSE; **no default pose** (`PlayerBox` contract) |
| `onGround`, `horizontalCollision`, `verticalCollision`, `verticalCollisionBelow` | `Entity` flags after `setOnGroundWithMovement` | SIMULATED; the client's claimed onGround stays in `MovementState` (player module) and is never read here |
| `fallDistance` | `Entity.fallDistance` (double) | |
| `stuck` | `Entity.stuckSpeedMultiplier` | consumed by the next move (cobweb / berry bush / powder snow) |
| `inPowderSnow` | `Entity.isInPowderSnow` at tick end | becomes `wasInPowderSnow` |
| `support` | `Entity.mainSupportingBlockPos` + `onGroundNoBlocks` | drives friction / speed-factor / jump-factor block choice (`getOnPos`) |
| `noJumpDelay` | `LivingEntity.noJumpDelay` | |
| `locomotion` | travel mode | WALKING simulated; CREATIVE_FLYING, ELYTRA, SWIMMING, RIDING, SLEEPING, SPECTATOR, SPIN_ATTACK, DEAD → UNSUPPORTED_MODE |

`PhysicsState.resting(x, y, z, pose, onGround)` builds a zero-velocity stand; two idle ticks make it vanilla-consistent
(resting velocity −0.0784, simulated onGround, supporting block) - exactly as a player who has stood still.

## 5. MOVEMENT INPUT (`MovementInput`)

Legal domain, verified against the pinned jar and documented in the record: `forward`, `strafe` ∈ {−1, 0, +1}
(`KeyboardInput.calculateImpulse`: opposing keys cancel; the server-side `Input` record carries seven booleans; the
client never sends scaled analog values), `jump`, `shift` (the sneak KEY: `isShiftKeyDown()` is what edge back-off,
bounce suppression, ladder hold and the crouch pose read), `sprinting` (the entity sprint FLAG in effect during the
tick's travel - whether the client toggled it is Phase 6's inference), `usingItem`, `yaw` (float degrees). The profile
applies the scaling the client applies: sneak × `SNEAKING_SPEED` attribute (0.3) when moving slowly (crouching or
crawling), item use × 0.2F, then `LivingEntity.applyInput` × 0.98F; a diagonal pair is normalized to length 1 by
`getInputVector` (the 0.98 factor vanishes). `strafe = +1` is LEFT (facing +z at yaw 0 the left hand points to +x).
Constructor rejects out-of-domain keys and non-finite yaw.

Documented assumption (client-only code not present in the server jar): the crouch slowdown uses the tick's own shift
key (`LocalPlayer.aiStep` computes `crouching` from `isShiftKeyDown()`; in some client versions the key state used there is
the previous tick's). The real-client W+shift scenario exercises this at the transition tick only.

## 6. MOVEMENT PROFILE (`MovementProfile`, version-scoped, id `paper-26.2-124-movement`)

Every constant names its vanilla source with the exact Java type: `INPUT_FRICTION 0.98F`, `USE_ITEM_INPUT_FACTOR 0.2F`,
`VELOCITY_ZERO_THRESHOLD 0.003` / `PLAYER_HORIZONTAL_ZERO_THRESHOLD_SQR 9.0E-6`, `JUMP_DELAY_TICKS 10`,
`GROUND_SPEED_NUMERATOR 0.21600002F` / threshold `0.6` (double compare), `AIR_FRICTION 0.91F`, `VERTICAL_DRAG 0.98F`,
`AIR_SPRINT_SPEED 0.025999999F`, `AIR_SPEED 0.02F`, levitation `0.05·(amp+1)`, rate `0.2`, slow falling cap `0.01`,
`JUMP_POWER_EPSILON 1.0E-5F`, `JUMP_BOOST_PER_LEVEL 0.1F`, `SPRINT_JUMP_IMPULSE 0.2`, climb clamp `±0.15F` (widened
`0.15000000596046448`), climb-up `0.2`, `MOVE_EPSILON_SQR 1.0E-7`, step bottom epsilon `(double)1.0E-5F`, `Mth.equal`
epsilon `(double)1.0E-5F`, support probe depth `1.0E-6`, `getOnPos` offsets `0.500001F / 0.2F / 1.0E-5F`, edge back-off
step `0.05`, edge probe inset `1.0E-7`, pose-fit deflate `1.0E-7`, coordinate clamp `2.9999999E7`, inside-sample deflate
`(double)1.0E-5F`, long-move `0.99999`, tiny-move `(1.0E-5F)²`, segment epsilon `9.999999439624929E-11`, slime step
`0.1 / 0.4 / 0.2`, cobweb `(0.25, (double)0.05F, 0.25)`, weaving `(0.5, 0.25, 0.5)`, berry `((double)0.8F, 0.75, (double)0.8F)`,
powder snow `((double)0.9F, 1.5, (double)0.9F)`, honey `0.9375 / −0.08 / −0.13 / −0.05 / 0.4375 / (double)0.98F / 0.08`,
push `(double)0.01F / (double)0.05F`, fluid box deflate `0.001`, `SIN_SCALE 10430.378350470453`, `DEG_TO_RAD 0.017453292F`.

Data extracted from the pinned jar (`MovementPropertyExtractMain`, nativeTest lane, never production; `extractMovementProperties`):
- `Player.createAttributes()` supplier: 36 attributes with player default, `RangedAttribute` min/max (sanitize bounds) and registry default;
- `LivingEntity.SPEED_MODIFIER_SPRINTING` = `minecraft:sprinting 0.30000001192092896 ADD_MULTIPLIED_TOTAL`;
- all 40 mob effects with their attribute templates (Speed `+0.20000000298023224·(amp+1)`, Slowness `−0.15000000596046448·(amp+1)` on `movement_speed`, ...);
- `Mth.SIN`: 65 536 float bits (`mth-sin-paper-26.2-124.bin`, sha256 `87a67a39fff59636…`); the generating formula `(float)Math.sin(i·2π/65536)` reproduces every entry (documentation; the resource bits are authoritative) and 18 conformance vectors are checked at load time (a host that fails them refuses the profile - INCOMPATIBLE_NUMERIC_ENVIRONMENT);
- block movement table: 1 196 blocks × {`getFriction`, `getSpeedFactor`, `getJumpFactor`, `getBounceRestitution`} (float bits), tags `climbable / fences / walls / fence_gates / suppresses_bounce / can_glide_through / fall_damage_resetting` (from the jar's own data-pack JSON), `FenceGateBlock` and `isAir` markers, the DECLARING CLASS of every `entityInside / stepOn / fallOn / getEntityInsideCollisionShape` override, and 11 792 block states with a fluid state (type, amount, source, own height).

`MovementProfile.calculateValue` is vanilla `AttributeInstance.calculateValue` including the client's per-operation
modifier ORDER (fastutil `Object2ObjectOpenHashMap`, 32 slots, `HashCommon.mix(Identifier.hashCode()) & 31`, linear
probing, descending iteration) so several `ADD_MULTIPLIED_TOTAL` modifiers (Speed + sprinting) multiply in the client's
order - verified bit-level against a real `AttributeInstance` on 3 000+ random sets.

## 7. NUMERIC FIDELITY

`Vec3d` mirrors `Vec3` expression shapes; every float/double cast is placed where the client places it (`(double)(float)`
narrowing of the fall-distance movement, `(float)` attribute reads, float impulse products, widened float literals).
`Math.sqrt` (IEEE-exact) is the only host math; sin/cos are table lookups. No FMA, no reassociation. Kernels are
natively differential-tested bit-level: `getInputVector` (20 000 cases: keys, sneak/use scaled inputs, yaws up to
±50 000°), `Mth.sin/cos` (100 000 lookups), `AABB.clip`, `axisStepOrder`, the inside-block traversal (6 000 rays) and
`calculateValue`. Whole ticks are bit-exact against the server process for all 88 walking scenarios (§25).

## 8. GROUND / AIR MOVEMENT (`LivingEntity.travelInAir`)

`posBelow = getBlockPosBelowThatAffectsMyMovement()` (support-aware `getOnPos(0.500001F)`: fences/walls/gates keep the
supporting cell for offsets ≤ 0.5, otherwise `atY(floor(y − offset))` - the block under a slab decides slab friction, as in vanilla);
`f = onGround ? computeModifiedFriction(block.getFriction(), (float)FRICTION_MODIFIER) : 1.0F`;
`speed = onGround ? ((double)f > 0.6 ? getSpeed() · (0.21600002F / f³) : getSpeed()) : (sprinting ? 0.025999999F : 0.02F)`
(`Player.getSpeed()` = `(float)MOVEMENT_SPEED` with the sprint modifier); `moveRelative` adds `getInputVector(input, speed, yaw)`;
`handleOnClimbable`; `move`; after the move `v.y = 0.2` on `(horizontalCollision || jumping) && (onClimbable || (wasInPowderSnow && boots))`;
then `d = v.y` → levitation `d += (0.05·(amp+1) − v.y)·0.2` / gravity `d −= getEffectiveGravity()` / client fallback `−0.1` when the
friction chunk is UNLOADED; finally `(v.x·g, d·h, v.z·g)` with `g = f · computeModifiedFriction(0.91F, AIR_DRAG_MODIFIER)`,
`h = computeModifiedFriction(0.98F, AIR_DRAG_MODIFIER)`. Air control is the same path with `f = 1` and the flying speed.

## 9. GRAVITY

`getEffectiveGravity()`: `deltaMovement.y <= 0 && SLOW_FALLING ? min(GRAVITY, 0.01) : GRAVITY` where the entity delta is
the post-move (post speed-factor) value; gravity is the attribute (0.08 default), sanitized to [−1, 1]. Terminal velocity
emerges (`−3.92`); the long-fall scenarios (`fall_100_ticks`, `fall_20`) are bit-exact for 100 ticks.

## 10. JUMP (`LivingEntity.aiStep` → `jumpFromGround`)

`noJumpDelay` decrements first; velocity zeroing (players: horizontal pair when `x²+z² < 9e-6`, y when `|y| < 0.003`);
`jumping && onGround && noJumpDelay == 0` → `power = (float)JUMP_STRENGTH · 1.0F · getBlockJumpFactor() + jumpBoostPower`
(`0.1F·(amp+1)`), `if (power > 1e-5F) { y = max(power, y); if sprinting add (−sin(yaw)·0.2, 0, cos(yaw)·0.2) }`,
`noJumpDelay = 10`; `!jumping → noJumpDelay = 0`. `getBlockJumpFactor` = block at the position, else the block below
when 1.0 (honey 0.5). 0.42 → apex 1.2522, landing on tick 12, re-jump on tick 13 with the key held: matrix + native.

## 11. SPRINT

`sprinting` is a declared flag: it adds the `minecraft:sprinting` transient modifier (`+30 % ADD_MULTIPLIED_TOTAL`,
deduplicated by id against server-sent modifiers), selects the 0.025999999F air speed and the sprint-jump impulse.
The client's sprint start/stop logic (`hasEnoughImpulseToStartSprinting`, collision stop, hunger) is Phase 6 input logic.

## 12. FRICTION

Block friction from the table (0.6 default, ice / packed ice / frosted ice 0.98, blue ice 0.989, slime 0.8), modified by
`FRICTION_MODIFIER`; `g = f·0.91F` on ground, `0.91F` in air; the ground speed formula uses `0.21600002F / f³` only when
`(double)f > 0.6`. Ice/blue ice/packed ice/slime scenarios bit-exact.

## 13. MOVEMENT FACTORS

`getBlockSpeedFactor` (Player → LivingEntity → Entity): block at the post-move position, `water` / `bubble_column`
short-circuit, else the block below when 1.0; `Mth.lerp((float)MOVEMENT_EFFICIENCY, factor, 1.0F)`; multiplied into
the post-move velocity (x/z) inside `move`. Soul sand 0.4, honey 0.4 (+ jump 0.5). Inventory: `movement-property-coverage.tsv`.

## 14. COLLISION INTEGRATION

`MovementGeometry` composes the Phase 4 primitives exactly as vanilla `Entity.collide` does: entity hard colliders
(env), world border (proximity → UNSUPPORTED_BORDER; UNKNOWN → gap), then block shapes from `CollisionEngine.query`
over the swept box (`box.expandTowards(movement)`) in broad-phase order; `collideWithShapes` applies `Shapes.collide` per
axis (`AxisClip.clip`, early exit below `1e-7`) against the accumulated-moved box. Only DEFINITE hits clip; envelope
(possible-only) hits, INCOMPLETE certainty, UNAVAILABLE views and REJECTED queries are recorded as reasons (§24).
`Level.noCollision` (pose fit, edge back-off) and `Level.findSupportingBlock` (nearest cell centre, ties by `Vec3i.compareTo`)
are built from the same query. `getBlockCollisions` semantics = Phase 4 acceptance (strict cube intersection / exact voxel join).

## 15. STEP ALGORITHM (vanilla 26.2 `Entity.collide`)

Axis order: `Direction.axisStepOrder(movement)` = **Y first, then X before Z unless |x| < |z|** (context-dependent, never
a fixed XYZ). After the first clip: `if (maxUpStep > 0 && (collidedDown || onGround) && (xBlocked || zBlocked))`:
`box2 = collidedDown ? box.move(0, collided.y, 0) : box`; `box3 = box2.expandTowards(x, maxUpStep, z)` (+ `(0, −1e-5F, 0)`
when not collidedDown); colliders of `box3`; candidates `collectCandidateStepUpHeights(box2, colliders, maxUpStep, (float)collided.y)`
= every collider Y edge `(float)(edge − box2.minY)` in `[0, maxUpStep]` except the achieved value, deduplicated, ascending;
the first candidate whose `collideWithShapes((x, h, z), box2)` yields more horizontal progress wins and is shifted back by
`box.minY − box2.minY`. Consequence verified natively and in the matrix: with the default 0.6 step height a full block is
NOT stepped (vanilla players jump/auto-jump), slabs/stairs are; `STEP_HEIGHT 1.25` steps a block; fences/walls (1.5) block.

## 16. GROUND DETECTION

`horizontalCollision = !Mth.equal(m.x, c.x) || !Mth.equal(m.z, c.z)`; `verticalCollision = m.y != c.y`;
`verticalCollisionBelow = verticalCollision && m.y < 0`; `onGround = verticalCollisionBelow` (the local player is
authoritative, so the flags update every move); `checkSupportingBlock` probes `[minY − 1e-6, minY]` under the new box,
then the box moved back by `−collided.xz` when nothing was found and `onGroundNoBlocks` was false. Simulated onGround
is a distinct field from the observed claim (PLAYER_STATE), and the first tick from a zero-velocity stand yields
`onGround = false` exactly like the client after a teleport (movement.y = 0 clips nothing).

## 17. POSES

Box per pose from the Phase 4 table (`Avatar.POSES`), scaled by the `SCALE` attribute. `updatePlayerPose` at tick end:
`canPlayerFitWithinBlocksAndEntitiesWhen(pose)` = `noCollision(box(pose).deflate(1e-7))`; desired = shift ? CROUCHING : STANDING;
fallback CROUCHING then SWIMMING (crawling). Crouching input (`isMovingSlowly`) = `fits(CROUCHING) && (shift || !fits(STANDING))`
or the SWIMMING pose out of water. Unknown/unsupported poses are refused (UNSUPPORTED_POSE); nothing assumes standing.

## 18. ATTRIBUTES

Supplied by the caller per attribute as `Attribute(knowledge, provenance, base, modifiers)`: PROFILE_DEFAULT (a fresh
player), SERVER_SENT (attribute packet base) or UNKNOWN. UNKNOWN for any of the ten movement attributes
(`movement_speed, gravity, jump_strength, step_height, sneaking_speed, movement_efficiency, air_drag_modifier,
friction_modifier, bounciness, scale`) aborts with UNKNOWN_ATTRIBUTE (no state, no legacy constant). Values are
sanitized with the registry bounds. `AIR_DRAG_MODIFIER` and `FRICTION_MODIFIER` (new 26.x) and `BOUNCINESS` (entity
restitution) are honoured. Scenario `attr_*` (8 attributes) bit-exact natively.

## 19. EFFECTS — PASS for the modelled set

Speed / Slowness through their attribute templates (folded by modifier id), Jump Boost (`0.1F·(amp+1)`), Levitation,
Slow Falling (gravity cap + fall-distance reset before travel), Weaving (cobweb multiplier). An effect id outside the
profile's 40 → UNKNOWN_EFFECT; an incomplete effect list → UNKNOWN_EFFECT. Dolphin's Grace and Conduit Power belong to
the deferred fluid mode. All effect scenarios bit-exact natively (the oracle uses real `MobEffectInstance`s).

## 20. EXTERNAL VELOCITY / EXPLOSION IMPULSE — PASS (forward application only)

`PhysicsEnvironment.externalMotions`: `SET` (velocity packet: `lerpMotion` replaces `deltaMovement`) and `ADD`
(explosion knockback added) are applied in order before the tick; `UNRESOLVED` (a declared influence with unknown
vector/application) makes the result UNRESOLVED_EXTERNAL_MOTION. The decision WHETHER an influence applies at this tick
is the caller's (Phase 7 consumption semantics); nothing is consumed automatically. Native scenarios start with the
applied velocity (`knockback_*`, `explosion_launch`) and are bit-exact for 30-80 ticks.

## 20b. ENTITY PUSHING — PARTIAL-BY-SPEC (interface implemented, not natively verified)

`EntityNeighborhood`: hard colliders (`canBeCollidedWith` entities: boats, shulkers) enter `collide` / `noCollision` as
`Shapes.create(box)` colliders in vanilla order (before blocks); pushable players enter client-side `pushEntities`
(`Entity.push`: `absMax`, `sqrt`, `min(1, 1/d)`, `0.05F`) - the client pushes only players. UNKNOWN neighbourhood →
UNKNOWN_ENTITY_NEIGHBORHOOD (never "empty"); two or more pushers → PUSH_ORDER_AMBIGUOUS (the client's entity
iteration order is not reproducible). Unit-tested from the bytecode formula; the oracle cannot host a second local
player, so pushing is not in the native lane (Phase 7 entity qualification).

## 21. FLUIDS — EXPLICITLY DEFERRED (detection implemented)

Fluid contact is detected exactly as `EntityFluidInteraction.update`: cells of the body box deflated by 0.001, a fluid
whose surface (`own height`, or 1.0 when the same fluid is above) reaches the body bottom; waterlogged states count.
Any water/lava contact → UNSUPPORTED_FLUID (no state), before and after the move (fall distance reset on entry).
Bubble columns → UNSUPPORTED_FLUID. Unknown cells in the fluid range → INCOMPLETE_WORLD (never "no water"). The
observer world carries fluid states through the same block-state keys (verified: `water_deferred`, `lava_deferred`,
`waterlogged_slab_deferred` are refused; the oracle ran them for the record). `travelInFluid`, flow pushing,
`jumpInLiquid`, swimming pose logic, Dolphin's Grace: Phase 5 deferral, listed in §32.

## 22. CLIMBING

`onClimbable`: `CLIMBABLE` tag (ladder, vines, scaffolding, weeping/twisting vines, cave vines, ...) or an open trapdoor
above a same-facing ladder; `handleOnClimbable`: `fallDistance = 0`, x/z clamped to ±0.15F, y ≥ −0.15F, and y = 0 when
descending with shift (not on scaffolding); climb-up 0.2 on horizontal collision / jump. Scaffolding's context shape
(descending) comes from Phase 4. Scenarios `ladder_*`, `vine_climb`, `trapdoor_ladder`, `scaffolding_*` bit-exact.

## 23. SPECIAL BLOCKS (inside-block sampling + step-on + bounce)

`applyEffectsFromBlocks`: `stepOn` of the legacy on-pos block when on ground (slime: `|dy| < 0.1 && !shift → ×(0.4 + |dy|·0.2)`),
then `checkInsideBlocks` with the exact 26.2 traversal (`BlockGetter.forEachBlockIntersectedBetween`: per-axis
decomposition of the actual movement in `axisStepOrder` of the requested movement; the deflated destination box; start
corners, the DDA of the leading corner ray with per-cell corner spans, end corners; visited-set; effect-visited-set;
`entityInside` when the inside shape is the full block or the body centre ray crosses it (`AABB.collidedAlongVector`)):
cobweb / weaving, sweet berry bush, powder snow (own-cell rule + FREEZE flag), honey slide (`isSlidingDown` /
`doSlideMovement`), portals → UNSUPPORTED, bubble column → fluid deferral, 21 NON_MOTION override classes ignored by
definition, unclassified → UNSUPPORTED_BLOCK_BEHAVIOUR (0 in the profile). Bounce: `restituteMovementAfterCollisions`
(`b = shift ? 0 : BOUNCINESS`; below: `−dy >= gravity && !shift && !SUPPRESSES_BOUNCE → max(b, restitution)`; slime 1.0,
beds 0.75, honey suppresses; `r = c.y/d.y`, `g = r·gravity`, `l = lerp(r, 1, 0.98F)`, `out.y = (g − d.y)·l·b`). The
fall-damage-resetting point ray (`FALLDAMAGE_RESETTING` tag + water) for fast moves is reproduced (`FallResetRay`).
The `LocalPlayer.moveTowardsClosestSpace` push-out (body already inside a collider at tick start) is not modelled and
is reported (INSIDE_BLOCK_START).

## 24. UNCERTAINTY MODEL (`PhysicsResult.Status`, most severe first)

NUMERIC_INVALID (NaN/Infinity in state, input or attributes) · COORDINATE_LIMIT (beyond ±3.2E7, |v| > 1e6, or the client's
±2.9999999E7 clamp fired) · UNAVAILABLE_WORLD (view not KNOWN, wrong world epoch) · QUERY_LIMIT (queries / cells / inside
cells / entity operations over budget, oversized query rejected) · UNSUPPORTED_MODE · UNSUPPORTED_POSE · UNSUPPORTED_FLUID ·
UNSUPPORTED_BORDER (within `isInsideCloseToBorder`) · UNSUPPORTED_BLOCK_BEHAVIOUR (portal, unclassified override,
push-out, unresolved inside shape) · UNRESOLVED_EXTERNAL_MOTION · UNKNOWN_ATTRIBUTE · UNKNOWN_EFFECT · UNKNOWN_EQUIPMENT
(boots mattered for a context shape) · UNKNOWN_ENTITY_NEIGHBORHOOD · UNKNOWN_WORLD_BORDER · INCOMPLETE_WORLD (any cell the
step read was UNKNOWN_*/UNLOADED/EVICTED/CONTENT_NOT_RETAINED/TRANSPORT_INCOMPLETE, a query was INCOMPLETE, an envelope
hit was possible-only) · EXACT (no reasons at all). Every reason carries a bounded detail (≤ 32 reasons, deduplicated).
The predicted state is still returned for the non-aborting classes so a caller sees what the step assumed (lower-bound
geometry), with the status making it explicit; EXACT with a non-empty reason list is rejected by the record itself.
Collision certainty propagates unchanged: PARTIAL/UNKNOWN/EVICTED/CONTENT_NOT_RETAINED/WRONG_EPOCH/REJECTED never become EXACT
(matrix `unknownCellsAreNeverAir`, `wrongWorldEpochIsUnavailable`).

## 25. NATIVE DIFFERENTIAL (`physicsNativeDifferential`)

Oracle: the pinned Paper 26.2 build 124 server itself, started as an isolated process (void superflat, offline, no
rcon, `pause-when-empty-seconds=-1`) with a test-only plugin (`anticheat-testing/src/oracle`, never shipped, compiled
against the pinned jar with `paperweight-mappings-namespace: mojang`). `OraclePlayer extends ServerPlayer` is never
connected or added to the level; three authority flags are overridden (`isClientAuthoritative=false`,
`canSimulateMovement=true`, `isEffectiveAi=true`) so the shared code executes its LOCAL-CLIENT branches
(`Entity.move` flag updates, `checkFallDamage`, `restituteMovementAfterCollisions`, `travel` from `aiStep`); packets
and damage are neutralised (`resendPossiblyDesyncedDataValues`, `onEffectAdded/onEffectsRemoved` keep only the
attribute modifiers, `hurtServer` false, `mayfly` short-circuits fall damage). Per tick the plugin performs only the
LocalPlayer glue (`setOldPosAndRot`, `wasInPowderSnow`, `updateFluidInteraction`, `updateSwimming`, crouch / sneak-speed /
item-use input scaling into `xxa/zza`, `setJumping/setShiftKeyDown/setSprinting/setYRot`, Paper's `lastJumpTime` gate
reset) and then calls the jar's `aiStep()` and `updatePlayerPose()`. Blocks are placed with `UPDATE_KNOWN_SHAPE` so the
scenario states are exact; `refreshDimensions()` after attributes (the scale attribute). The Moonrise `Entity.collide`
is the jar's; the per-axis clip kernel is separately vanilla-exact from Phase 4.

Comparison (`PhysicsOracleMain`): every tick, bit-level on x, y, z, vx, vy, vz, fallDistance, stuck; exact on onGround,
horizontal/vertical collision flags, pose, inPowderSnow, noJumpDelay, supporting block, onGroundNoBlocks. Two modes:
SINGLE-STEP (simulator starts from the oracle's previous state) and CHAINED (simulator follows its own predictions).

Result (`docs/phase5-artifacts/native-differential-results.json`): **91 scenarios, 88 compared, 88/88 exact single-step,
88/88 exact chained, 3 105/3 105 ticks bit-exact, worst absolute difference 0.0**, 3 fluid scenarios refused as
UNSUPPORTED_FLUID as expected, 0 oracle errors; server run ≈ 9 s. Cases: idle, walk (4 directions, 4 yaws incl. 37.5°,
−123.4°), strafe, diagonal, sprint, use-item, sneak, sprint stop, jump / forward jump / sprint jump (0°, 45°) / jump tap
and hold, fall 20 and 100 ticks, air strafe, air sprint, wall, corner, ceiling (block, slab), step up slab / stairs,
failed 2-high step, fence, wall block, step while sprinting, step-height attribute, crouch under slab ceiling, crawl gap,
sneak edge back-off (straight, diagonal), walk off edge, ice / blue ice / packed ice, slime walk / bounce / suppressed
bounce, bed bounce, honey walk / jump / wall slide, soul sand walk / sprint jump, cobweb walk-in / fall-through, berry
bush, powder snow fall-in / boots walk / boots climb, magma, ladder climb / hold / slide / jump, vine, trapdoor ladder,
scaffolding climb / descend, Speed II, Speed I + sprint, Slowness, Jump Boost II, Slow Falling, Levitation, Weaving,
attributes (speed 0.2, gravity 0.04, jump 0.6, sneaking 0.5, scale 0.5 in a gap, bounciness 0.5, air drag 0.5, friction
modifier 0.5 on ice, movement efficiency 0.5 on soul sand), knockback (free, into a wall), explosion launch (80 ticks),
and a 100-tick pseudo-random key course (all keys, random yaws, mixed geometry).

Defects the harness found and fixed before the final run (P5-F1..F3, §31): fall distance narrowing
`fallDistance − (double)(float)dy`; slow falling / levitation resetting the fall distance before travel; the fall-reset
ray. Oracle-side fixes (not simulator defects): `refreshDimensions` after attributes; connection-less effect and
damage hooks.

## 26. MULTI-TICK DIFFERENTIAL

CHAINED mode is the multi-tick differential: horizons 2/5/20/100 (`multi-tick-drift-results.json`): **max drift 0.0 at
every horizon** for every compared scenario (the 100-tick fall and the 100-tick random course included). Long-run:
the offline `PhysicsOracleFixtureTest` replays all 88 pinned native trajectories (SHA-256 over the raw bits of every
tick) on every `test` run, and `fallIsBoundedByTerminalVelocity` runs 300 chained ticks (terminal −3.92, finite).

## 27. PERFORMANCE (`benchmarkPhysics`, JMH 1.37, Temurin 25, 3 forks, 5×1 s warm-up, 10×1 s measurement, `-prof gc`)

See `docs/phase5-artifacts/benchmarks.json`; the table in §27a is filled from that run. Every kernel is a full
`simulate` over a real observer world and therefore includes the Phase 4 collision queries the step issues (idle: 5-6
queries; step-up / edge cases: 7+). Candidate scaling 1/8/32/64 runs N explicit inputs from one state and is linear
(the simulator never enumerates). Not a capacity claim.

## 27a. JMH results

| Kernel | ns/op (±err) | alloc B/op |
|---|---|---|
| airborne | 2212.6 ± 75.8 | 5,912 |
| candidateScaling1 | 3354.1 ± 43.5 | 7,344 |
| candidateScaling32 | 112477.2 ± 784.6 | 265,169 |
| candidateScaling64 | 226161.4 ± 1925.5 | 529,026 |
| candidateScaling8 | 24726.6 ± 371.3 | 57,766 |
| cobwebEntry | 2750.4 ± 12.1 | 6,760 |
| externalVelocityApplied | 3919.2 ± 39.5 | 7,416 |
| iceWalk | 2745.8 ± 18.0 | 6,680 |
| idleOnGround | 2650.5 ± 29.5 | 5,939 |
| jumpFromGround | 2404.4 ± 10.8 | 5,152 |
| ladderClimb | 3327.6 ± 38.4 | 10,856 |
| soulSandWalk | 4954.4 ± 29.6 | 12,811 |
| sprintOnGround | 3566.6 ± 16.7 | 7,035 |
| stairsAndSlabCourse | 28760.0 ± 125.3 | 63,318 |
| stepUpSlab | 4853.2 ± 33.8 | 11,352 |
| twentyTickChainedWalk | 66812.8 ± 907.6 | 142,008 |
| walkOnGround | 3497.1 ± 83.7 | 7,168 |
| wallCollision | 4207.0 ± 36.1 | 7,757 |
| waterContactDeferred | 228.9 ± 3.2 | 912 |
| worstBoundedCourseStep | 4540.5 ± 33.0 | 10,960 |

Candidate scaling: 1 → 3354.1 ns, 8 → 24726.6 ns (7.37×), 32 → 112477.2 ns (33.5×), 64 → 226161.4 ns (67.4×): linear in the number of explicit inputs, as expected of a stateless per-candidate step.

## 28. MEMORY (`physicsMemoryHarness`, `docs/phase5-artifacts/memory-results.json`)

| Item | Bytes | Note |
|---|---|---|
| block movement table (parsed) | 1 661 744 | shared once per JVM (1 196 blocks + 11 792 fluid states) |
| movement profile (attributes, effects, sine table) | 279 096 | sine table 262 144 B of it |
| block behaviour cache, every registry state resolved | 1 622 416 (estimate over the dictionary) | engine-wide, bounded by the 65 536-state dictionary |
| simulator | 16 | stateless |
| retained per 1 000 results if a consumer kept them | 385 360 | ≈ 385 B per result; nothing is retained by the engine |
| per-step allocation | harness 8 347 (jump) … 14 417 (ladder) B; JMH gc.alloc.rate.norm 5 152 (jump) … 12 811 (soul sand) B/op | dominated by the 5-7 Phase 4 `CollisionResult` values per step (status-count arrays, hit lists) - the known optimization target |
| per player retained | 0 | `PhysicsState`/`PhysicsResult` are caller-owned values |

Total Phase 5 retained state ≈ 3.6 MB worst case inside the 32 MiB shape-cache/infrastructure partitions; nothing scales with players or traffic.

## 29. REAL CLIENT QUALIFICATION

Tooling: `/papea2ac physics <player> arm <scenario>` records the observed rest position (requires observed onGround)
and the published world snapshot; a 2 ms poller collects the following movement observations (≤ 64, 60 s); `/papea2ac
physics <player> report` replays the DECLARED scenario (release_all, hold_w, hold_s, hold_a, hold_d, w_sprint,
jump_stationary, w_jump, w_shift, w_sprint_jump) from a two-idle-tick rest state through the simulator and prints
predicted vs observed per tick with bit-exactness, the first mismatch, the simulator statuses, a timing-ambiguity check
(first sample matching tick 2 better than tick 1 → NOT QUALIFYING) and the world revision / collision summary of the
start. Facts read on the main thread (border, nearby entities, boots, effects, attribute bases) are labelled
server-current. Nothing infers keys; a discrepancy is reported as such. Evidence: `docs/phase5-artifacts/real-client-evidence.log`
(RCQ section §29a, filled during the session with the owner).

## 29a. Real-client evidence

Performed 2026-09-20 (owner's native Minecraft Java 26.2 client, in-game `Emojiado`, OP; pinned Paper 26.2 build 124 at
`.phase1-runtime`, 127.0.0.1:25576, offline mode, the FINAL plugin jar, sha256 `19c340ee0d7a3175…`, byte-identical to
`anticheat-platform-paper/build/libs/anticheat-platform-paper-1.0-SNAPSHOT-plugin.jar`). Arena: the flat Phase 4 course
platform (y = 66, feet at 67.0), open lanes at z ≈ 24–26 away from the station line. Evidence:
`docs/phase5-artifacts/real-client-evidence.log` (31 RCON blocks, 0 secret matches; the RCON password lives only in the
git-ignored `server.properties`). Every trace: observed rest state → declared keys → 40 moving samples compared tick by
tick, bit-level on x/y/z; world coverage at the start COMPLETE; attributes/effects/border read server-current on the main
thread (labelled); "entitiesNearby" from `getNearbyEntities(8,8,8)`.

| # | Declared scenario | Result | Notes |
|---|---|---|---|
| S1 | release all keys | **QUALIFYING** - 30/30 samples identical to rest; prediction: no movement | rest is a fixed point of the simulator (support −36,66,25, velocity (0, −0.0784, 0)) |
| S2 | hold W | recorded, **declared-input mismatch** (not a physics discrepancy) | the client was sprinting (toggle): observed 0.1274 first tick and 0.2806/tick steady = exactly 1.3 × the walk values |
| S2b | hold W (sprint off) | **QUALIFYING - 40/40 ticks BIT-EXACT** | 0.098 first tick, converging to 0.2158/tick |
| S3 | hold D | **QUALIFYING - 40/40 ticks BIT-EXACT** | strafe right, yaw −92° basis through the sine table |
| S4 | W + sprint | recorded, NOT QUALIFYING (obstacle) | performed 2 blocks from the door station: the observed track stops at an unobserved-in-lane obstacle and turns; first sample not a rest start |
| S4b/S4c | W + sprint | re-armed twice while the player relocated (no movement performed) | logged for completeness |
| S4d | W + sprint (facing west, clear lane) | **QUALIFYING - 40/40 ticks BIT-EXACT** | 0.1274 first tick, 0.2806/tick steady; sprint modifier + 0.026 air speed path |
| S5 | jump stationary | **QUALIFYING - 40/40 ticks BIT-EXACT** | two full jumps: 0.42 → apex 68.2522 → landing tick 12 → re-jump tick 13 with the key held (noJumpDelay) |
| S6 | W + jump | NOT QUALIFYING (timing-skewed) | Space registered 2 ticks after W: ticks 1–2 are the exact walking values 0.098 / 0.1515, the jump starts at tick 3 |
| S6b | W + jump (simultaneous press) | **QUALIFYING - 40/40 ticks BIT-EXACT** | jump + forward air control + landing + re-jump |
| S7 | W + shift | recorded, declared-input mismatch + UNKNOWN_ENTITY_NEIGHBORHOOD | the observed track is a full-speed walk in the opposite direction (keys differed from the declaration); a dropped item was within 8 blocks (2 entities removed by command) |
| S7b | W + shift (shift first, then W) | **QUALIFYING - 40/40 ticks BIT-EXACT**, status UNKNOWN_ENTITY_NEIGHBORHOOD | sneak walk 0.0294 first tick (0.098 × SNEAKING_SPEED 0.3), CROUCHING pose; the environment flag came from a Rabbit Hide item 6 blocks away (items do not affect movement; the tool refuses to call the neighbourhood KNOWN) - positions bit-exact regardless |
| S8 | W + sprint + jump | NOT QUALIFYING (timing-skewed, 2 ticks) | ticks 1–2 = sprint-walk 0.1274 / 0.197, jump from tick 3 |
| S8b | W + sprint + jump (simultaneous) | NOT QUALIFYING (timing-skewed, 1 tick) | tick 1 = sprint-walk 0.127, tick 2 = jump start 67.42; the sprint-jump impulse is bit-exact in the native lane (`sprint_jump`, `sprint_jump_yaw`) |
| final | status with the player connected, clean stop | PASS | `diagnosticSteps=507 diagnosticExact=387` (the non-exact steps are the entity-flagged S7 traces); `Disabling Papea2AC` before the player's disconnect, 0 exceptions |

Reading: every trace whose declared keys matched what the client actually sent is bit-exact for its full 40-tick window
(6 of the 6 controlled scenarios that were cleanly performed: release-all, W, D, W+sprint, jump, W+jump, plus W+shift).
The non-qualifying traces are all attributable to the DECLARATION (sprint toggle, key-onset skew of 1–2 ticks, an
obstacle in the lane, opposite keys), which is exactly the ambiguity Phase 6's input reconstruction owns; none shows a
physics residual, and none is called "cheating" anywhere. The tick-1 shift-key assumption of §5 held in S7b (the first
sneak tick already moved at 0.3 × speed with the shift-first protocol).

## 30. PAPER SMOKE

Paper 26.2 build 124 with the final jar: `Done (5.665s)`, plugin enabled with the physics status line
(`physics profile=paper-26.2-124-movement sha256=4df7479291b0ab9c sine=87a67a39fff59636 blockTable=de9ab636d80e6abb blocks=1196 fluidStates=11792 effects=40 attributes=36`),
one admitted session, 507 diagnostic steps during the qualification, `stop` with the player connected → `Disabling Papea2AC`
logged before `Emojiado lost connection`, 0 exceptions in `.phase1-runtime/qualification-run-phase5.log` (git-ignored),
stderr holds only the usual JLine/Unsafe warnings. Shards drained within the 2 s disable window (no force-fence warning).

## 31. Forward defects (P5-F<n>)

| Id | Found by | Defect | Fix |
|---|---|---|---|
| P5-F1 | native differential (every airborne scenario, tick 1) | fall distance accumulated `fallDistance − dy` in double; the client narrows the movement to float (`fallDistance − (double)(float)dy`) | `PhysicsSimulator` fall-distance update |
| P5-F2 | native differential (`slow_falling`) | Slow Falling / Levitation reset the fall distance BEFORE travel (`aiStep`), not only through gravity | reset added before travel |
| P5-F3 | native differential (`fall_20`, `explosion_launch` reported UNSUPPORTED) | the fall-damage-resetting point ray for ≥ 1-block moves was a blanket "unsupported"; vanilla only resets on FALLDAMAGE_RESETTING blocks / water along the ray | `FallResetRay` (exact `traverseBlocks` + tagged-block / water-column clip); `fall_damage_resetting` tag extracted |
| P5-F4 | matrix (`fallIsBoundedByTerminalVelocity`) | cells above/below the dimension were treated as UNKNOWN for behaviour lookups; vanilla returns `void_air` | `BlockBehaviorQuery` answers KNOWN void_air outside the build height |
| P5-F5 | property test (allocation) | 16.5 KB/step: per-attribute 32-slot order tables and boxed cell sets | fast paths in `calculateValue`, lazy modifier copies, primitive cell sets (≈ 10 KB/step) |

Harness notes (not physics defects, recorded during the pre-baseline clean verification):

- `PhysicsQualificationTest.selfConsistentObservationsReplayBitExact` failed once under the parallel `--rerun-tasks`
  load: the synthetic timeline published a new sample every 12 ms and the qualification's 2 ms poller thread was starved
  long enough to miss one (`samples=11` of 12; from t=3 every observation equalled the prediction for t+1). The real
  timeline advances one movement sample per 50 ms tick. Test-only hardening: the synthetic timeline now waits until the
  poller has fetched each published sample before publishing the next (no production change); 4/4 on every rerun since,
  484/484 in the final clean runs.
- One JUnit `@TempDir` deletion failure in the pre-existing Phase 2 `LiveRecordingTest` on one Windows clean run
  during the Phase 5 verification: ENVIRONMENTAL / NON-REPRODUCED (immediate re-runs and the final clean runs passed);
  not a Phase 5 defect, recorded rather than deleted (same flake as recorded in the Phase 4 receipt).

## 32. KNOWN LIMITATIONS / DEFERRED TO PHASE 6+

- Fluids (water, lava, bubble columns): detected and refused (UNSUPPORTED_FLUID); locomotion, flow pushing, fluid jump, swimming pose, Dolphin's Grace/Conduit deferred.
- Elytra (`travelFallFlying`, fireworks), creative flight, vehicles/riding, riptide, sleeping, spectator: UNSUPPORTED_MODE.
- Entity pushing and entity colliders: interface only, not natively differential-tested (no second local player in the oracle); order-ambiguous with ≥ 2 pushers.
- World border: proximity is UNSUPPORTED_BORDER (the inverted border shape is not clipped); border knowledge is a caller input - the observer world does not yet track border packets (they are captured by Phase 1; wiring is a Phase 6/7 item). The diagnostic passes the server-current border.
- `LocalPlayer.moveTowardsClosestSpace` (push-out when already inside a block) not modelled: INSIDE_BLOCK_START.
- Crouch-slowdown shift-key timing (previous vs current tick) is a documented client-side assumption; checked at the transition tick in the real-client W+shift scenario.
- Soul Speed / Depth Strider / Swift Sneak enchantments are attribute modifiers the server sends; only the sent modifiers are modelled (no enchantment logic).
- Abstract/interval transfer functions (MOVEMENT_SIMULATION M3 "conservative transfer = envelope primitives") are NOT delivered in Phase 5; no exclusion profile exists and INVALID stays disabled (M2 pilot condition), so nothing depends on them yet. They are the first Phase 6 work item.
- Per-step allocation ≈ 10-15 KB (Phase 4 result values); a clip-only query path is the optimization target.
- Client-only code (`LocalPlayer`, `KeyboardInput`) is absent from the server jar: the input scaling glue is specified from knowledge of the shared code and verified end-to-end by the real-client scenarios, not by the oracle.

## 33. ACCEPTANCE MATRIX

| Requirement | Evidence | Result |
|---|---|---|
| Deterministic forward step, immutable state, explicit input | `PhysicsSimulator`, `PhysicsPropertyTest.deterministicFiniteAndStatusConsistentOverRandomTicks`, `resultsAreDeterministicAndInputsImmutable` | PASS |
| Numeric fidelity (order, casts, tables) | `NativeMovementProfileTest` (8 kernels, bit-level), native differential 3 105 ticks | PASS |
| Ground/air, gravity, jump, sprint, friction, factors, step, ground detection | matrix (§0 tests), native scenarios | PASS |
| Collision integration with uncertainty propagation | `unknownCellsAreNeverAir`, `wrongWorldEpochIsUnavailable`, `historicalViewsCarryTheirSequenceIntoProvenance`, `worldBorderUnknownDegradesAndProximityIsUnsupported` | PASS |
| Poses without default | `crouchesUnderALowCeilingAndCrawlsInAOneBlockGap`, `unsupportedLocomotionAndPosesAreExplicit`, native `crouch_under_slab_ceiling`, `crawl_gap` | PASS |
| Attributes / effects supplied by caller, unknown → status | `unknownAttributeAbortsAndUnknownEffectDegrades`, `attributesDriveSpeedGravityAndJump`, `speedAndSlownessEffectsChangeTheSpeedAttribute` | PASS |
| External velocity SET/ADD, unresolved | `externalMotionSetReplacesAndAddAdds`, `unresolvedExternalMotionIsNeverExact`, native knockback/explosion | PASS |
| Entity pushing interface | `entityCollidersBlockAndUnknownNeighbourhoodDegrades`, `nearbyPlayersPushAndTwoPushersAreOrderAmbiguous` | PARTIAL-BY-SPEC |
| Fluids detected, explicitly deferred | `fluidsAreDetectedAndExplicitlyDeferred`, 3 native deferrals | EXPLICITLY DEFERRED |
| Climbing, special blocks, bounce | matrix + native (ladder/vine/trapdoor/scaffolding/web/berry/powder snow/honey/slime/bed) | PASS |
| Work bounds | `queryBudgetsAreEnforcedAndNormalStepsAreCheap`, property bound checks | PASS |
| Query budgets / no pathological scans | ≤ 7 queries, ≤ ~400 cells per normal step; inside sampling ≤ 512 | PASS |
| Movement property coverage inventory, 0 unexplained gaps | `movement-property-coverage.tsv/json`, `MovementPropertyCoverageTest` | PASS |
| Native differential, multi-tick, drift | §25/§26 | PASS |
| Replay fixtures | `physics-fixtures/native-trajectories.json`, `PhysicsOracleFixtureTest` | PASS |
| Performance / memory | §27/§28 | PASS (descriptive) |
| Real-client controlled-input qualification | §29a | see §29a |
| Regression Phases 1-4 | full `test` 484/484, `nativeTest` 132/132, `verifyMandatoryCoverage` 127/127, replay conformance, shape coverage | PASS |
| Phase 6 implementation | none | NONE |
