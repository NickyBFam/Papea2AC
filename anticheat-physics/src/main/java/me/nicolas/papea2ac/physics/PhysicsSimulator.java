package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.BlockLookup;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5 deterministic vanilla forward model: {@code simulate(state, input, environment)} predicts the player's state
 * after ONE client tick of the 26.2 WALKING locomotion (ground and air, including crawling), reproducing the client's
 * operation order and arithmetic (MOVEMENT_SIMULATION.md "Step specification"; NUMERICAL_POLICY.md NUM1). The tick, as
 * the pinned jar executes it for the local player (documented per method in PHASE_5_IMPLEMENTATION.md):
 * <ol>
 * <li>external motions the caller applies (velocity packet SET / explosion ADD) - between ticks;</li>
 * <li>{@code Entity.baseTick}: {@code wasInPowderSnow = isInPowderSnow}, fluid contact detection ({@code updateFluidInteraction});</li>
 * <li>{@code LivingEntity.aiStep}: jump-delay countdown, small-velocity zeroing, {@code applyInput} ({@code 0.98F}), the jump,
 *     {@code travel} -> {@code travelInAir}: friction block, {@code moveRelative} (yaw basis from the sine table),
 *     {@code handleOnClimbable}, {@code Entity.move} (stuck multiplier, sneak edge back-off, {@code collide} with step-up,
 *     ground/collision flags, supporting block, fall distance, bounce restitution, block speed factor), climb-up,
 *     levitation / gravity, air and ground drag; then {@code applyEffectsFromBlocks} ({@code stepOn}, inside-block effects),
 *     the entity pushes (26.2 client: every other pushing entity's {@code pushEntities} shoves the local player - P9-F5);</li>
 * <li>{@code Player.tick} tail: the +-2.9999999E7 X/Z clamp and {@code updatePlayerPose}.</li>
 * </ol>
 * The simulator never infers input, never enumerates candidates and never judges: it returns what the vanilla client
 * would compute for the given explicit input, with an explicit status whenever some fact it needed was unknown,
 * unsupported or over budget. It holds no mutable state; one instance serves any number of threads.
 */
public final class PhysicsSimulator {
    private final MovementProfile profile;
    public PhysicsSimulator(MovementProfile profile) { this.profile = java.util.Objects.requireNonNull(profile); }
    public MovementProfile profile() { return profile; }

    public PhysicsResult simulate(PhysicsState state, MovementInput input, PhysicsEnvironment env) {
        java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(input); java.util.Objects.requireNonNull(env);
        if (env.profile() != profile) throw new IllegalArgumentException("environment profile mismatch");
        Uncertainty u = new Uncertainty();
        MovementGeometry geo = new MovementGeometry(env, u);
        PhysicsResult.Provenance prov = new PhysicsResult.Provenance(env.view().worldEpoch(), env.view().revision(), env.view().sequence(), profile.id(), profile.collision().id(), profile.sineDigest());
        // ---- validation gates (NUMERIC_INVALID / COORDINATE_LIMIT / UNSUPPORTED_MODE / UNSUPPORTED_POSE / UNAVAILABLE_WORLD)
        if (!state.finite()) return abort(u, PhysicsResult.Reason.Code.NON_FINITE_STATE, "state carries NaN/Infinity", geo, prov);
        if (Math.abs(state.position().x()) > CollisionEngine.COORDINATE_LIMIT || Math.abs(state.position().z()) > CollisionEngine.COORDINATE_LIMIT || Math.abs(state.position().y()) > CollisionEngine.COORDINATE_LIMIT)
            return abort(u, PhysicsResult.Reason.Code.COORDINATE_OUT_OF_RANGE, "position beyond " + CollisionEngine.COORDINATE_LIMIT, geo, prov);
        if (state.velocity().lengthSqr() > 1.0E12) return abort(u, PhysicsResult.Reason.Code.COORDINATE_OUT_OF_RANGE, "velocity magnitude beyond 1e6", geo, prov);
        if (state.locomotion() != PhysicsState.Locomotion.WALKING) return abort(u, PhysicsResult.Reason.Code.LOCOMOTION_UNSUPPORTED, state.locomotion().name(), geo, prov);
        if (state.pose() != PlayerBox.Pose.STANDING && state.pose() != PlayerBox.Pose.CROUCHING && state.pose() != PlayerBox.Pose.SWIMMING) return abort(u, PhysicsResult.Reason.Code.POSE_UNSUPPORTED, state.pose().name(), geo, prov);
        if (env.view().availability() != BlockLookup.Status.KNOWN) return abort(u, PhysicsResult.Reason.Code.VIEW_UNAVAILABLE, env.view().availability().name(), geo, prov);
        if (env.view().worldEpoch() != env.worldEpoch()) return abort(u, PhysicsResult.Reason.Code.WRONG_WORLD_EPOCH, "view epoch " + env.view().worldEpoch() + " != " + env.worldEpoch(), geo, prov);
        // P11-F27: the client's own attack ran setSprinting(false) before this tick's movement: the sprint modifier was removed, the class is FOLLOWS
        for (PhysicsEnvironment.ExternalMotion m : env.externalMotions()) if (m.operation() == PhysicsEnvironment.ExternalMotion.Operation.ATTACKER_SLOWDOWN) { state = state.withSprintModifier(PhysicsState.SprintModifier.FOLLOWS); break; }
        // ---- attributes and effects (P9-F1: the sprint modifier's presence is the state's class applied to this tick's sprint input, never the snapshot's own copy)
        Boolean present = state.sprintModifierPresent(input.sprinting());
        if (present == null && state.speedMatters(input)) return abort(u, PhysicsResult.Reason.Code.SPRINT_MODIFIER_UNRESOLVED, state.sprintModifier().name() + " not split for " + (input.sprinting() ? "a sprinting" : "a walking") + " ground input", geo, prov);
        boolean sprintModifier = present != null && present; // otherwise the speed attribute is never read this tick (no horizontal input, or airborne: constant air acceleration)
        Attrs a = new Attrs(env, sprintModifier, u);
        if (!a.ok) return abort(u, null, null, geo, prov);
        if (env.effects().knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN) u.add(PhysicsResult.Reason.Code.EFFECTS_INCOMPLETE, "effect list may be incomplete");
        for (PhysicsEnvironment.EffectInstance e : env.effects().active()) if (profile.effect(e.id()) == null) u.add(PhysicsResult.Reason.Code.EFFECT_UNKNOWN, e.id());
        PhysicsEnvironment.EffectInstance levitation = env.effects().find(MovementProfile.EFFECT_LEVITATION), jumpBoost = env.effects().find(MovementProfile.EFFECT_JUMP_BOOST);
        boolean slowFalling = env.effects().find(MovementProfile.EFFECT_SLOW_FALLING) != null, weaving = env.effects().find(MovementProfile.EFFECT_WEAVING) != null;
        float scale = (float) a.scale;
        Vec3d pos = state.position();
        Vec3d v = state.velocity();
        // ---- external motion (applied by the caller's decision before the tick)
        for (PhysicsEnvironment.ExternalMotion m : env.externalMotions()) {
            switch (m.operation()) {
                case SET -> v = m.vector();
                case ADD -> v = v.add(m.vector());
                case UNRESOLVED -> u.add(PhysicsResult.Reason.Code.EXTERNAL_MOTION_UNRESOLVED, m.sourceId());
                case ATTACKER_SLOWDOWN -> v = new Vec3d(v.x() * MovementProfile.ATTACKER_SLOWDOWN_HORIZONTAL, v.y() * 1.0, v.z() * MovementProfile.ATTACKER_SLOWDOWN_HORIZONTAL); // Vec3.multiply(0.6, 1.0, 0.6)
            }
        }
        // ---- baseTick: powder snow memory, fluid contact
        boolean wasInPowderSnow = state.inPowderSnow();
        Aabb box = PlayerBox.of(profile.collision(), state.pose(), scale, pos.x(), pos.y(), pos.z());
        Fluid fluid = fluidContact(geo, u, box);
        if (fluid.touching()) return abort(u, PhysicsResult.Reason.Code.FLUID_CONTACT, (fluid.water ? "water" : "lava") + " height " + fluid.height + " (fluid locomotion is explicitly deferred)", geo, prov);
        // ---- aiStep
        int noJumpDelay = state.noJumpDelay() > 0 ? state.noJumpDelay() - 1 : 0;
        double vx = v.x(), vy = v.y(), vz = v.z();
        if (v.horizontalDistanceSqr() < MovementProfile.PLAYER_HORIZONTAL_ZERO_THRESHOLD_SQR) { vx = 0.0; vz = 0.0; }
        if (Math.abs(vy) < MovementProfile.VELOCITY_ZERO_THRESHOLD) vy = 0.0;
        v = new Vec3d(vx, vy, vz);
        // LocalPlayer input glue: crouching (isMovingSlowly) needs the pose-fit tests at the pre-move position
        ShapeContext ctx = ShapeContext.of(pos.y(), input.shift(), state.fallDistance(), a.canWalkOnPowderSnow);
        boolean fitsCrouching = fits(geo, PlayerBox.Pose.CROUCHING, scale, pos, ctx, "crouch-fit"), fitsStanding = fits(geo, PlayerBox.Pose.STANDING, scale, pos, ctx, "stand-fit");
        // P9-F3 (pinned 26.2 client, LocalPlayer.aiStep): `crouching` is assigned from isShiftKeyDown() BEFORE ClientInput.tick() reads the
        // keyboard, so the sneak scaling of tick T uses the key sampled at T-1, while maybeBackOffFromEdge and updatePlayerPose read the fresh key.
        // updatePlayerPose applies the same rule - fits(CROUCHING) && (key || !fits(STANDING)) - at the same position at the end of T-1, so the
        // stale-key crouch state is exactly "the pose this tick starts in is CROUCHING". The fit probes above stay: LocalPlayer makes these calls
        // and an unknown cell they touch must still make the step uncertain.
        boolean crouching = state.pose() == PlayerBox.Pose.CROUCHING;
        boolean crawling = state.pose() == PlayerBox.Pose.SWIMMING; // isVisuallyCrawling: swimming pose while not in water
        boolean movingSlowly = crouching || crawling;
        // LocalPlayer input pipeline (P6-F1: float normalize, 0.98F, item use, sneaking, square-movement rescale): one authoritative implementation (PhysicsKernels.clientMoveVector), shared with the Phase 6 abstract lane
        float[] move = PhysicsKernels.clientMoveVector(input.forward(), input.strafe(), movingSlowly, input.usingItem(), (float) a.sneakingSpeed);
        float xxa = move[0], zza = move[1];
        boolean jumping = input.jump();
        // block position / supporting block lookups (pre-move)
        int bx = floor(pos.x()), by = floor(pos.y()), bz = floor(pos.z());
        BlockBehaviorQuery.Behavior inBlock = geo.behavior(bx, by, bz);
        int[] below = onPos(geo, u, state.support(), pos, MovementProfile.ON_POS_MOVEMENT_OFFSET);
        BlockBehaviorQuery.Behavior belowBlock = geo.behavior(below[0], below[1], below[2]);
        boolean jumped = false;
        if (jumping) {
            if (state.onGround() && noJumpDelay == 0) {
                // jumpFromGround
                float jumpFactor = blockJumpFactor(inBlock, belowBlock, u);
                float boost = jumpBoost == null ? 0.0f : MovementProfile.JUMP_BOOST_PER_LEVEL * ((float) jumpBoost.amplifier() + 1.0f);
                float power = (float) a.jumpStrength * 1.0f * jumpFactor + boost;
                if (power > MovementProfile.JUMP_POWER_EPSILON) {
                    v = new Vec3d(v.x(), Math.max((double) power, v.y()), v.z());
                    if (input.sprinting()) { float yawRad = input.yaw() * MovementProfile.DEG_TO_RAD; v = v.add((double) (-profile.sin(yawRad)) * MovementProfile.SPRINT_JUMP_IMPULSE, 0.0, (double) profile.cos(yawRad) * MovementProfile.SPRINT_JUMP_IMPULSE); }
                    jumped = true;
                }
                noJumpDelay = MovementProfile.JUMP_DELAY_TICKS;
            }
        } else noJumpDelay = 0;
        // ---- travel -> travelInAir  (aiStep: slow falling / levitation reset the fall distance before travel)
        double fallDistance = state.fallDistance();
        if (slowFalling || levitation != null) fallDistance = 0.0;
        float friction = 1.0f;
        if (state.onGround()) {
            if (!belowBlock.known()) return abort(u, belowBlock.status() == BlockBehaviorQuery.Status.UNKNOWN ? PhysicsResult.Reason.Code.CELL_UNKNOWN : PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, "friction block " + MovementGeometry.describe(belowBlock) + " at " + below[0] + "," + below[1] + "," + below[2], geo, prov);
            friction = MovementProfile.computeModifiedFriction(belowBlock.block().friction(), (float) a.frictionModifier);
        }
        // handleRelativeFrictionAndCalculateMovement: moveRelative
        float speed = state.onGround() ? ((double) friction > MovementProfile.GROUND_SPEED_FRICTION_THRESHOLD ? a.speed * (MovementProfile.GROUND_SPEED_NUMERATOR / (friction * friction * friction)) : a.speed) : (input.sprinting() ? MovementProfile.AIR_SPRINT_SPEED : MovementProfile.AIR_SPEED);
        Vec3d inputVector = inputVector((double) xxa, 0.0, (double) zza, speed, input.yaw());
        v = v.add(inputVector);
        // handleOnClimbable (pre-move block position)
        boolean onClimbableBefore = onClimbable(geo, u, inBlock, bx, by, bz);
        if (onClimbableBefore) {
            fallDistance = 0.0;
            double cx = clamp(v.x(), -MovementProfile.CLIMB_LIMIT, MovementProfile.CLIMB_LIMIT), cz = clamp(v.z(), -MovementProfile.CLIMB_LIMIT, MovementProfile.CLIMB_LIMIT), cy = Math.max(v.y(), -MovementProfile.CLIMB_LIMIT);
            if (cy < 0.0 && !(inBlock.known() && inBlock.block().name().equals("minecraft:scaffolding")) && input.shift()) cy = 0.0;
            v = new Vec3d(cx, cy, cz);
        }
        Vec3d velocityBeforeMove = v;
        // ---- Entity.move(SELF, v)
        Vec3d movement = v; Vec3d stuck = state.stuck();
        if (stuck.lengthSqr() > MovementProfile.MOVE_EPSILON_SQR) { movement = movement.multiply(stuck); stuck = Vec3d.ZERO; v = Vec3d.ZERO; }
        float maxUpStep = (float) a.stepHeight;
        if (movement.y() <= 0.0 && input.shift() && isAboveGround(geo, box, pos, ctx, state.onGround(), fallDistance, maxUpStep)) movement = backOffFromEdge(geo, box, pos, ctx, movement, maxUpStep);
        Vec3d collided = geo.collide(movement, box, pos.x(), pos.z(), state.onGround(), maxUpStep, ctx);
        boolean steppedUp = geo.steppedUp;
        Vec3d newPos = pos; Vec3d segmentOriginal = null;
        double collidedSqr = collided.lengthSqr();
        if (collidedSqr > MovementProfile.MOVE_EPSILON_SQR || movement.lengthSqr() - collidedSqr < MovementProfile.MOVE_EPSILON_SQR) {
            // Entity.move: a fast move (>= 1 block) with a pending fall distance clips a point ray for FALL_DAMAGE_RESETTING blocks / water and resets the fall distance on a hit
            if (fallDistance != 0.0 && collidedSqr >= MovementProfile.FALL_RESET_RAY_LENGTH_SQR && FallResetRay.hits(geo, u, pos, pos.add(collided.normalize().scale(Math.min(collided.length(), 8.0))))) fallDistance = 0.0;
            newPos = pos.add(collided); segmentOriginal = movement;
        }
        Aabb newBox = PlayerBox.of(profile.collision(), state.pose(), scale, newPos.x(), newPos.y(), newPos.z());
        boolean xCollided = !equal(movement.x(), collided.x()), zCollided = !equal(movement.z(), collided.z());
        boolean horizontalCollision = xCollided || zCollided;
        boolean verticalCollision = state.verticalCollision(), verticalCollisionBelow = state.verticalCollisionBelow(), onGround = state.onGround();
        PhysicsState.Support support = state.support();
        // isLocalInstanceAuthoritative is true for the local player: the flags always update
        verticalCollision = movement.y() != collided.y();
        verticalCollisionBelow = verticalCollision && movement.y() < 0.0;
        onGround = verticalCollisionBelow;
        support = checkSupportingBlock(geo, onGround, collided, newBox, newPos, ctx, support);
        // checkFallDamage (LivingEntity -> Entity): fluid re-detection, fall distance accounting, landing
        Fluid fluidAfter = fluidContact(geo, u, newBox);
        boolean inWaterAfter = fluidAfter.touching() && fluidAfter.water;
        if (fluidAfter.touching()) fallDistance = 0.0;
        if (!inWaterAfter && collided.y() < 0.0) fallDistance = fallDistance - (double) (float) collided.y(); // Entity.checkFallDamage: the movement is narrowed to float before subtraction
        if (onGround) fallDistance = 0.0;
        // restituteMovementAfterCollisions
        int[] legacy = onPos(geo, u, support, newPos, MovementProfile.ON_POS_LEGACY_OFFSET);
        BlockBehaviorQuery.Behavior legacyBlock = geo.behavior(legacy[0], legacy[1], legacy[2]);
        if (verticalCollision || horizontalCollision) {
            double b = input.shift() ? 0.0 : a.bounciness;
            Vec3d delta = v, out = v;
            if (xCollided) out = out.with(0, -delta.x() * b);
            if (zCollided) out = out.with(2, -delta.z() * b);
            if (verticalCollision) {
                if (verticalCollisionBelow) {
                    double blockBounce;
                    if (!legacyBlock.known()) { u.add(legacyBlock.status() == BlockBehaviorQuery.Status.UNKNOWN ? PhysicsResult.Reason.Code.CELL_UNKNOWN : PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, "landing block " + MovementGeometry.describe(legacyBlock) + " at " + legacy[0] + "," + legacy[1] + "," + legacy[2]); blockBounce = 0.0; }
                    else blockBounce = (double) legacyBlock.block().bounceRestitution();
                    boolean suppressed = input.shift() || (legacyBlock.known() && legacyBlock.block().suppressesBounce());
                    b = (-delta.y() >= effectiveGravity(delta.y(), slowFalling, a.gravity) && !suppressed) ? Math.max(b, blockBounce) : 0.0;
                }
                double g, l;
                if (b > 0.0) { double r = collided.y() / delta.y(); g = r * effectiveGravity(delta.y(), slowFalling, a.gravity); l = 1.0 + r * ((double) airDrag(a) - 1.0); }
                else { g = 0.0; l = 1.0; }
                out = out.with(1, (g - delta.y()) * l * b);
            }
            v = out;
        }
        // block speed factor (post-move block position)
        int nbx = floor(newPos.x()), nby = floor(newPos.y()), nbz = floor(newPos.z());
        BlockBehaviorQuery.Behavior newInBlock = geo.behavior(nbx, nby, nbz);
        float speedFactor = blockSpeedFactor(geo, u, newInBlock, support, newPos, (float) a.movementEfficiency);
        v = v.multiply((double) speedFactor, 1.0, (double) speedFactor);
        Vec3d entityDelta = v; // the entity's deltaMovement after move (read by getEffectiveGravity below)
        // back in handleRelativeFrictionAndCalculateMovement
        boolean onClimbableAfter = onClimbable(geo, u, newInBlock, nbx, nby, nbz);
        if ((horizontalCollision || jumping) && (onClimbableAfter || (wasInPowderSnow && a.canWalkOnPowderSnow))) v = new Vec3d(v.x(), MovementProfile.CLIMB_UP_SPEED, v.z());
        // levitation / gravity, then drag
        double d = v.y();
        if (levitation != null) d += (MovementProfile.LEVITATION_TARGET_PER_LEVEL * (double) (levitation.amplifier() + 1) - v.y()) * MovementProfile.LEVITATION_RATE;
        else if (MovementGeometry.unloaded(belowBlock)) { u.add(PhysicsResult.Reason.Code.CHUNK_UNLOADED, "friction chunk unloaded: client applies the -0.1 fallback"); d = newPos.y() > env.view().dimension().minY() ? -0.1 : 0.0; }
        else d -= effectiveGravity(entityDelta.y(), slowFalling, a.gravity);
        float g = friction * MovementProfile.computeModifiedFriction(MovementProfile.AIR_FRICTION, (float) a.airDragModifier);
        float h = MovementProfile.computeModifiedFriction(MovementProfile.VERTICAL_DRAG, (float) a.airDragModifier);
        v = new Vec3d(v.x() * (double) g, d * (double) h, v.z() * (double) g);
        // ---- applyEffectsFromBlocks: stepOn, then inside blocks
        boolean inPowderSnow = false;
        if (onGround) {
            if (!legacyBlock.known()) u.add(legacyBlock.status() == BlockBehaviorQuery.Status.UNKNOWN ? PhysicsResult.Reason.Code.CELL_UNKNOWN : PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, "stepOn block " + MovementGeometry.describe(legacyBlock));
            else switch (legacyBlock.block().stepOn()) {
                case NONE, NON_MOTION -> { }
                case SLIME_SLOWDOWN -> { double dy = Math.abs(v.y()); if (dy < MovementProfile.SLIME_STEP_DY_LIMIT && !input.shift()) { double e = MovementProfile.SLIME_STEP_BASE + dy * MovementProfile.SLIME_STEP_SLOPE; v = v.multiply(e, 1.0, e); } }
                case UNSUPPORTED -> u.add(PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, legacyBlock.block().name() + " stepOn class " + legacyBlock.block().stepOnClass());
            }
        }
        InsideBlockSampler sampler = new InsideBlockSampler(env, geo, u, state.pose(), scale, weaving, a.canWalkOnPowderSnow, input.shift(), onGround, ctx, v);
        sampler.run(pos, newPos, segmentOriginal);
        v = sampler.velocity;
        if (sampler.stuck != null) stuck = sampler.stuck;
        if (sampler.resetFall) fallDistance = 0.0;
        inPowderSnow = sampler.inPowderSnow;
        // entity pushes (26.2 client: other entities' pushEntities push the local player; P9-F5)
        v = pushPlayers(geo, u, env, newPos, newBox, v, onClimbableAfter);
        // ---- Player.tick tail: coordinate clamp, updatePlayerPose
        double cxp = clamp(newPos.x(), -MovementProfile.COORDINATE_CLAMP, MovementProfile.COORDINATE_CLAMP), czp = clamp(newPos.z(), -MovementProfile.COORDINATE_CLAMP, MovementProfile.COORDINATE_CLAMP);
        if (cxp != newPos.x() || czp != newPos.z()) { newPos = new Vec3d(cxp, newPos.y(), czp); u.add(PhysicsResult.Reason.Code.COORDINATE_OUT_OF_RANGE, "X/Z clamped to +-2.9999999E7"); }
        ShapeContext ctxAfter = ShapeContext.of(newPos.y(), input.shift(), fallDistance, a.canWalkOnPowderSnow);
        PlayerBox.Pose pose = state.pose();
        if (fits(geo, PlayerBox.Pose.SWIMMING, scale, newPos, ctxAfter, "pose-swim-fit")) {
            PlayerBox.Pose desired = input.shift() ? PlayerBox.Pose.CROUCHING : PlayerBox.Pose.STANDING;
            if (fits(geo, desired, scale, newPos, ctxAfter, "pose-fit")) pose = desired;
            else pose = fits(geo, PlayerBox.Pose.CROUCHING, scale, newPos, ctxAfter, "pose-crouch-fit") ? PlayerBox.Pose.CROUCHING : PlayerBox.Pose.SWIMMING;
        }
        PhysicsState next = new PhysicsState(newPos, v, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, PhysicsState.Locomotion.WALKING, state.sprintModifierAfter(input));
        PhysicsResult.Trace trace = new PhysicsResult.Trace(inputVector, speed, friction, velocityBeforeMove, movement, collided, steppedUp, jumped, crouching, false);
        return new PhysicsResult(u.status(), next, u.reasons(), work(geo), prov, trace);
    }

    /**
     * Phase 6 forward addition (additive; the step's own arithmetic is unchanged): the movement attribute values this step
     * would resolve for the environment ({@code AttributeInstance.calculateValue} with effect templates and the sprint
     * modifier folded in by id, exactly as {@link #simulate}), so the abstract/interval lane never re-implements the folding.
     * {@code sprintModifier} is the PRESENCE of the client's sprint modifier (P9-F1: {@link PhysicsState#sprintModifierPresent}), which
     * {@link #simulate} derives from the state's class and the input's sprint flag; the snapshot's own copy of the modifier is ignored.
     * Returns {@code null} when any movement attribute is UNKNOWN or non-finite (the step would abort with the same reason).
     */
    public ResolvedAttributes resolveAttributes(PhysicsEnvironment env, boolean sprintModifier) {
        Attrs a = new Attrs(env, sprintModifier, new Uncertainty());
        if (!a.ok) return null;
        return new ResolvedAttributes(a.speed, a.speedD, a.gravity, a.jumpStrength, a.stepHeight, a.sneakingSpeed, a.movementEfficiency, a.airDragModifier, a.frictionModifier, a.bounciness, a.scale, a.canWalkOnPowderSnow);
    }
    /** The ten resolved movement attributes ({@code speed} is the {@code (float)} the client reads; {@code speedValue} the double before narrowing) plus the powder-snow boots fact. */
    public record ResolvedAttributes(float speed, double speedValue, double gravity, double jumpStrength, double stepHeight, double sneakingSpeed, double movementEfficiency, double airDragModifier, double frictionModifier, double bounciness, double scale, boolean canWalkOnPowderSnow) {}

    // ---- helpers (each mirrors one vanilla method)

    /** P9-F1: whether the last applied MOVEMENT_SPEED snapshot carries {@code LivingEntity.SPEED_MODIFIER_SPRINTING} (the server syncs its own transient copy to the client). */
    public boolean snapshotCarriesSprintModifier(PhysicsEnvironment env) {
        PhysicsEnvironment.Attribute at = env.attributes().get(MovementProfile.ATTR_MOVEMENT_SPEED);
        if (at == null || at.knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN) return false;
        for (MovementProfile.Modifier m : at.modifiers()) if (m.id().equals(profile.sprintModifier().id())) return true;
        return false;
    }
    /**
     * Resolved movement attributes (vanilla {@code AttributeInstance.calculateValue} with the client's modifier set, effect templates folded in
     * by id). {@code sprintModifier} is the presence of the sprint modifier in the CLIENT's set: the snapshot's own copy is dropped and the
     * modifier re-added iff present, so a snapshot synced while the server still saw the player sprinting cannot make a walking candidate 30% faster (P9-F1).
     */
    private final class Attrs {
        final double speedD, gravity, jumpStrength, stepHeight, sneakingSpeed, movementEfficiency, airDragModifier, frictionModifier, bounciness, scale; final float speed; final boolean canWalkOnPowderSnow, ok;
        Attrs(PhysicsEnvironment env, boolean sprintModifier, Uncertainty u) {
            boolean good = true;
            double[] vals = new double[MovementProfile.MOVEMENT_ATTRIBUTES.size()];
            for (int i = 0; i < vals.length; i++) {
                String id = MovementProfile.MOVEMENT_ATTRIBUTES.get(i);
                PhysicsEnvironment.Attribute at = env.attributes().get(id);
                MovementProfile.AttributeDefinition def = profile.attribute(id);
                if (at.knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN) { u.add(PhysicsResult.Reason.Code.ATTRIBUTE_UNKNOWN, id); good = false; continue; }
                if (!Double.isFinite(at.base())) { u.add(PhysicsResult.Reason.Code.NON_FINITE_ATTRIBUTE, id); good = false; continue; }
                List<MovementProfile.Modifier> mods = at.modifiers(); // copied lazily only when an effect or the sprint modifier must be appended
                for (PhysicsEnvironment.EffectInstance e : env.effects().active()) {
                    MovementProfile.Effect ef = profile.effect(e.id());
                    if (ef == null) continue;
                    for (MovementProfile.EffectTemplate t : ef.templates()) if (t.attribute().equals(id) && !hasId(mods, t.modifierId())) { if (mods == at.modifiers()) mods = new ArrayList<>(mods); mods.add(t.create(e.amplifier())); }
                }
                if (id.equals(MovementProfile.ATTR_MOVEMENT_SPEED)) {
                    boolean carried = hasId(mods, profile.sprintModifier().id());
                    if (sprintModifier && !carried) { if (mods == at.modifiers()) mods = new ArrayList<>(mods); mods.add(profile.sprintModifier()); }
                    else if (!sprintModifier && carried) { mods = new ArrayList<>(mods); mods.removeIf(m -> m.id().equals(profile.sprintModifier().id())); } // P9-F1: the client removed it at its sprint stop
                }
                vals[i] = MovementProfile.calculateValue(def, at.base(), mods);
            }
            ok = good;
            speedD = vals[0]; gravity = vals[1]; jumpStrength = vals[2]; stepHeight = vals[3]; sneakingSpeed = vals[4]; movementEfficiency = vals[5]; airDragModifier = vals[6]; frictionModifier = vals[7]; bounciness = vals[8]; scale = vals[9];
            speed = (float) speedD;
            PhysicsEnvironment.Equipment eq = env.equipment();
            canWalkOnPowderSnow = eq.knowledge() == PhysicsEnvironment.Knowledge.KNOWN && eq.canWalkOnPowderSnow();
        }
        private boolean hasId(List<MovementProfile.Modifier> mods, String id) { for (MovementProfile.Modifier m : mods) if (m.id().equals(id)) return true; return false; }
    }
    private static float airDrag(Attrs a) { return MovementProfile.computeModifiedFriction(MovementProfile.VERTICAL_DRAG, (float) a.airDragModifier); }
    /** {@code LivingEntity.getEffectiveGravity}: slow falling caps gravity at 0.01 while not rising ({@code getGravity} = the attribute; noGravity is not a walking state). */
    private static double effectiveGravity(double deltaY, boolean slowFalling, double gravity) { return deltaY <= 0.0 && slowFalling ? Math.min(gravity, MovementProfile.SLOW_FALLING_GRAVITY) : gravity; }

    /** {@code Entity.getInputVector(input, speed, yaw)}: normalize when longer than 1, scale by speed, rotate by the sine-table basis. */
    public Vec3d inputVector(double x, double y, double z, float speed, float yaw) {
        Vec3d in = new Vec3d(x, y, z);
        double d = in.lengthSqr();
        if (d < MovementProfile.INPUT_LENGTH_SQR_EPSILON) return Vec3d.ZERO;
        Vec3d v = (d > 1.0 ? in.normalize() : in).scale((double) speed);
        float rad = yaw * MovementProfile.DEG_TO_RAD;
        float s = profile.sin(rad), c = profile.cos(rad);
        return new Vec3d(v.x() * (double) c - v.z() * (double) s, v.y(), v.z() * (double) c + v.x() * (double) s);
    }

    /** {@code Entity.getOnPos(offset)}: the supporting block (when recorded) adjusted by the offset rule, else the position-based cell. Returns {x, y, z}. */
    private static int[] onPos(MovementGeometry geo, Uncertainty u, PhysicsState.Support support, Vec3d pos, float offset) {
        if (support.present()) {
            BlockBehaviorQuery.Behavior b = geo.behavior(support.x(), support.y(), support.z());
            if (!MovementGeometry.unloaded(b)) { // getChunkIfLoadedImmediately != null
                if (!(offset > MovementProfile.ON_POS_OFFSET)) return new int[] { support.x(), support.y(), support.z() };
                if (b.status() == BlockBehaviorQuery.Status.UNKNOWN) u.add(PhysicsResult.Reason.Code.CELL_UNKNOWN, "supporting block " + b.worldStatus() + " at " + support.x() + "," + support.y() + "," + support.z());
                boolean keep = b.known() && ((double) offset <= MovementProfile.ON_POS_KEEP_SUPPORT_LIMIT && b.block().fence() || b.block().wall() || b.block().fenceGateBlock());
                if (keep) return new int[] { support.x(), support.y(), support.z() };
                return new int[] { support.x(), floor(pos.y() - (double) offset), support.z() };
            }
        }
        return new int[] { floor(pos.x()), floor(pos.y() - (double) offset), floor(pos.z()) };
    }
    /** {@code Entity.getBlockJumpFactor}: the block at the position, else the block below when that is 1. */
    private static float blockJumpFactor(BlockBehaviorQuery.Behavior in, BlockBehaviorQuery.Behavior below, Uncertainty u) {
        float f = known(in, u, "jump factor") ? in.block().jumpFactor() : 1.0f;
        float g = known(below, u, "jump factor below") ? below.block().jumpFactor() : 1.0f;
        return (double) f == 1.0 ? g : f;
    }
    /** {@code Player/LivingEntity/Entity.getBlockSpeedFactor}: block at the position (water/bubble column short-circuit), else block below when 1; lerped toward 1 by MOVEMENT_EFFICIENCY. */
    private static float blockSpeedFactor(MovementGeometry geo, Uncertainty u, BlockBehaviorQuery.Behavior in, PhysicsState.Support support, Vec3d pos, float efficiency) {
        float f;
        if (!known(in, u, "speed factor")) f = 1.0f;
        else {
            f = in.block().speedFactor();
            String n = in.block().name();
            if (!(n.equals("minecraft:water") || n.equals("minecraft:bubble_column")) && (double) f == 1.0) {
                int[] below = onPos(geo, u, support, pos, MovementProfile.ON_POS_MOVEMENT_OFFSET);
                BlockBehaviorQuery.Behavior b = geo.behavior(below[0], below[1], below[2]);
                f = known(b, u, "speed factor below") ? b.block().speedFactor() : 1.0f;
            }
        }
        return f + efficiency * (1.0f - f); // Mth.lerp(efficiency, f, 1.0F)
    }
    private static boolean known(BlockBehaviorQuery.Behavior b, Uncertainty u, String what) {
        if (b.known()) return true;
        u.add(b.status() == BlockBehaviorQuery.Status.UNKNOWN ? PhysicsResult.Reason.Code.CELL_UNKNOWN : PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, what + ": " + MovementGeometry.describe(b));
        return false;
    }
    /** {@code LivingEntity.onClimbable}: CLIMBABLE tag, or an open trapdoor above a same-facing ladder. */
    private static boolean onClimbable(MovementGeometry geo, Uncertainty u, BlockBehaviorQuery.Behavior in, int x, int y, int z) {
        if (!known(in, u, "climbable")) return false;
        if (in.block().climbable()) return true;
        if (in.block().name().endsWith("_trapdoor") || in.block().name().equals("minecraft:iron_trapdoor")) {
            var key = geo.behavior(x, y, z); // same cell; properties from the dictionary key
            me.nicolas.papea2ac.model.BlockStateKey k = geo.key(key.stateId());
            if (k == null || !"true".equals(k.properties().get("open"))) return false;
            BlockBehaviorQuery.Behavior below = geo.behavior(x, y - 1, z);
            if (!known(below, u, "trapdoor ladder")) return false;
            if (!below.block().name().equals("minecraft:ladder")) return false;
            me.nicolas.papea2ac.model.BlockStateKey lk = geo.key(below.stateId());
            return lk != null && k.properties().get("facing") != null && k.properties().get("facing").equals(lk.properties().get("facing"));
        }
        return false;
    }
    /** {@code Player.canPlayerFitWithinBlocksAndEntitiesWhen(pose)}. */
    private boolean fits(MovementGeometry geo, PlayerBox.Pose pose, float scale, Vec3d pos, ShapeContext ctx, String purpose) {
        Aabb b = PlayerBox.of(profile.collision(), pose, scale, pos.x(), pos.y(), pos.z()).inflate(-MovementProfile.POSE_FIT_DEFLATE);
        return geo.noCollision(b, pos.x(), pos.z(), ctx, purpose);
    }
    /** {@code Player.isAboveGround(maxUpStep)}. */
    private static boolean isAboveGround(MovementGeometry geo, Aabb box, Vec3d pos, ShapeContext ctx, boolean onGround, double fallDistance, float maxUpStep) {
        return onGround || fallDistance < (double) maxUpStep && !canFallAtLeast(geo, box, pos, ctx, 0.0, 0.0, (double) maxUpStep - fallDistance);
    }
    /** {@code Player.canFallAtLeast(dx, dz, dist)}. */
    private static boolean canFallAtLeast(MovementGeometry geo, Aabb box, Vec3d pos, ShapeContext ctx, double dx, double dz, double dist) {
        Aabb probe = new Aabb(box.minX() + MovementProfile.EDGE_PROBE_INSET + dx, box.minY() - dist - MovementProfile.EDGE_PROBE_INSET, box.minZ() + MovementProfile.EDGE_PROBE_INSET + dz,
            box.maxX() - MovementProfile.EDGE_PROBE_INSET + dx, box.minY(), box.maxZ() - MovementProfile.EDGE_PROBE_INSET + dz);
        return geo.noCollision(probe, pos.x(), pos.z(), ctx, "edge");
    }
    /** {@code Player.maybeBackOffFromEdge} inner loops (the caller checked the guards). */
    private static Vec3d backOffFromEdge(MovementGeometry geo, Aabb box, Vec3d pos, ShapeContext ctx, Vec3d movement, float maxUpStep) {
        double d = movement.x(), e = movement.z(), step = MovementProfile.EDGE_BACKOFF_STEP;
        double h = Math.signum(d) * step, i = Math.signum(e) * step, f = (double) maxUpStep;
        while (d != 0.0 && canFallAtLeast(geo, box, pos, ctx, d, 0.0, f)) { if (Math.abs(d) <= step) { d = 0.0; break; } d -= h; }
        while (e != 0.0 && canFallAtLeast(geo, box, pos, ctx, 0.0, e, f)) { if (Math.abs(e) <= step) { e = 0.0; break; } e -= i; }
        while (d != 0.0 && e != 0.0 && canFallAtLeast(geo, box, pos, ctx, d, e, f)) {
            if (Math.abs(d) <= step) d = 0.0; else d -= h;
            if (Math.abs(e) <= step) e = 0.0; else e -= i;
        }
        return new Vec3d(d, movement.y(), e);
    }
    /** {@code Entity.checkSupportingBlock(onGround, movement)}. */
    private static PhysicsState.Support checkSupportingBlock(MovementGeometry geo, boolean onGround, Vec3d movement, Aabb box, Vec3d pos, ShapeContext ctx, PhysicsState.Support previous) {
        if (!onGround) return PhysicsState.Support.NONE; // onGroundNoBlocks = false, mainSupportingBlockPos cleared
        Aabb probe = new Aabb(box.minX(), box.minY() - MovementProfile.SUPPORT_PROBE_DEPTH, box.minZ(), box.maxX(), box.minY(), box.maxZ());
        PhysicsState.Support s = geo.findSupportingBlock(probe, pos, ctx);
        if (!s.present() && !previous.onGroundNoBlocks()) s = geo.findSupportingBlock(probe.move(-movement.x(), 0.0, -movement.z()), pos, ctx);
        return new PhysicsState.Support(s.present(), s.x(), s.y(), s.z(), !s.present());
    }
    /** Fluid contact per {@code EntityFluidInteraction.update}: any fluid cell in the deflated body's cell range whose surface reaches the body bottom. */
    private record Fluid(boolean water, boolean lava, double height) { boolean touching() { return water || lava; } }
    private static Fluid fluidContact(MovementGeometry geo, Uncertainty u, Aabb body) {
        Aabb b = body.inflate(-MovementProfile.FLUID_BOX_DEFLATE);
        int x0 = floor(b.minX()), x1 = (int) Math.ceil(b.maxX()), y0 = floor(b.minY()), y1 = (int) Math.ceil(b.maxY()), z0 = floor(b.minZ()), z1 = (int) Math.ceil(b.maxZ());
        boolean water = false, lava = false; double height = 0.0;
        for (int x = x0; x < x1; x++) for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) {
            BlockBehaviorQuery.Behavior c = geo.behavior(x, y, z);
            if (c.status() == BlockBehaviorQuery.Status.UNKNOWN) { if (c.worldStatus() != BlockLookup.Status.OUTSIDE_DIMENSION) u.add(PhysicsResult.Reason.Code.CELL_UNKNOWN, "fluid detection: " + c.worldStatus() + " at " + x + "," + y + "," + z); continue; }
            if (!c.known()) { u.add(PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, "fluid detection: unsupported state at " + x + "," + y + "," + z); continue; }
            if (!c.hasFluid()) continue;
            BlockBehaviorQuery.Behavior above = geo.behavior(x, y + 1, z);
            float h = above.known() && above.hasFluid() && c.fluid().sameFluid(above.fluid()) ? 1.0f : c.fluid().ownHeight();
            double surface = (double) y + (double) h;
            if (surface >= b.minY()) { double e = surface - b.minY(); if (e > height) height = e; if (c.fluid().water()) water = true; if (c.fluid().lava()) lava = true; }
        }
        return new Fluid(water, lava, height);
    }
    /** Client-side entity pushes: every known pusher whose box intersects the body box pushes this player by {@code Entity.push(Entity)} (26.2: it runs in the PUSHER's {@code pushEntities}, via {@code ClientLevel.getPushableEntities} - P9-F5). */
    private static Vec3d pushPlayers(MovementGeometry geo, Uncertainty u, PhysicsEnvironment env, Vec3d pos, Aabb box, Vec3d v, boolean onClimbable) {
        PhysicsEnvironment.EntityNeighborhood n = env.entities();
        if (n.knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN) { u.add(PhysicsResult.Reason.Code.ENTITIES_UNKNOWN, "nearby players unknown"); return v; }
        int pushed = 0;
        for (PhysicsEnvironment.Pusher p : n.pushers()) {
            geo.entityOps++;
            if (!p.pushable() || onClimbable || !p.box().intersects(box)) continue;
            double dx = pos.x() - p.x(), dz = pos.z() - p.z();
            double d = Math.max(Math.abs(dx), Math.abs(dz));
            if (d >= MovementProfile.PUSH_MIN_SEPARATION) {
                d = Math.sqrt(d); dx /= d; dz /= d;
                double e = 1.0 / d; if (e > 1.0) e = 1.0;
                dx *= e; dz *= e; dx *= MovementProfile.PUSH_STRENGTH; dz *= MovementProfile.PUSH_STRENGTH;
                v = v.add(dx, 0.0, dz); pushed++;
            }
        }
        if (pushed > 1) u.add(PhysicsResult.Reason.Code.PUSH_ORDER_AMBIGUOUS, pushed + " pushers: client iteration order is not reproducible");
        return v;
    }
    private static PhysicsResult abort(Uncertainty u, PhysicsResult.Reason.Code code, String detail, MovementGeometry geo, PhysicsResult.Provenance prov) {
        if (code != null) u.add(code, detail);
        return new PhysicsResult(u.status(), null, u.reasons(), work(geo), prov, PhysicsResult.Trace.NONE);
    }
    private static PhysicsResult.Work work(MovementGeometry g) { return new PhysicsResult.Work(g.queries, g.cells, g.boxes, g.insideCells, g.entityOps); }
    /** {@code Mth.equal(double, double)}. */
    static boolean equal(double a, double b) { return Math.abs(b - a) < MovementProfile.MTH_EQUAL_EPSILON; }
    static int floor(double d) { return (int) Math.floor(d); }
    static double clamp(double v, double min, double max) { return v < min ? min : v > max ? max : v; }
}
