package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.collision.PlayerBox;
import java.util.List;
import java.util.Objects;

/**
 * The simulated player's physical state at a tick boundary (MOVEMENT_SIMULATION.md "SimulationCandidate: State/velocity/pose
 * after each step"). Immutable value; exactly the vanilla {@code Entity}/{@code LivingEntity}/{@code Player} fields the
 * 26.2 movement tick reads or writes, nothing observed from packets is mixed in: {@code onGround} here is the SIMULATED
 * ground flag ({@code Entity.onGround} after {@code setOnGroundWithMovement}), never the client's claim (PLAYER_STATE.md
 * keeps the observed flag separately; COLLISION_ENGINE.md: "Ground state claimed by the client is compared with
 * collision-derived support, not trusted as geometry input").
 *
 * <ul>
 * <li>{@code position} - feet position ({@code Entity.position}); {@code velocity} - {@code deltaMovement};</li>
 * <li>{@code pose} - the pose whose dimensions built this tick's bounding box ({@code Entity.getPose()}); the caller must
 *     state it - there is no default ({@link PlayerBox});</li>
 * <li>{@code onGround, horizontalCollision, verticalCollision, verticalCollisionBelow} - the {@code Entity} flags left by the last move;</li>
 * <li>{@code fallDistance} - {@code Entity.fallDistance} (double in 26.2);</li>
 * <li>{@code stuck} - {@code Entity.stuckSpeedMultiplier} (cobweb / berry bush / powder snow), consumed by the next move;</li>
 * <li>{@code inPowderSnow} - {@code Entity.isInPowderSnow} at the end of the tick (becomes {@code wasInPowderSnow});</li>
 * <li>{@code support} - {@code Entity.mainSupportingBlockPos} / {@code onGroundNoBlocks} (the block that carries the entity, chosen by {@code findSupportingBlock});</li>
 * <li>{@code noJumpDelay} - {@code LivingEntity.noJumpDelay};</li>
 * <li>{@code locomotion} - which vanilla travel mode the entity is in; only {@link Locomotion#WALKING} is simulated in Phase 5, every other value is reported UNSUPPORTED_MODE.</li>
 * <li>{@code sprintModifier} - P9-F1: how the client's {@code minecraft:sprinting} MOVEMENT_SPEED modifier relates to its sprint flag
 *     ({@link SprintModifier}). {@code ClientboundUpdateAttributes} replaces the client's modifier set with the server's (which carries the
 *     modifier while the SERVER thinks the player sprints); afterwards {@code LivingEntity.setSprinting} removes/adds it only when the
 *     client's own flag CHANGES. The pair (flag, present) has three behaviourally distinct classes, and only the class is state.</li>
 * </ul>
 */
public record PhysicsState(Vec3d position, Vec3d velocity, PlayerBox.Pose pose, boolean onGround, boolean horizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
                           double fallDistance, Vec3d stuck, boolean inPowderSnow, Support support, int noJumpDelay, Locomotion locomotion, SprintModifier sprintModifier) {
    /** Vanilla travel modes. Phase 5 simulates WALKING (ground/air, including crawling in a one-block gap); the others are explicit deferrals. */
    public enum Locomotion { WALKING, CREATIVE_FLYING, ELYTRA, SWIMMING, RIDING, SLEEPING, SPECTATOR, SPIN_ATTACK, DEAD }
    /**
     * P9-F1: the client's sprint speed modifier relative to its sprint flag. With flag S and presence P, a tick with sprint input R sets
     * (S, P) := (R, R) when R != S ({@code setSprinting} toggles) and leaves it alone otherwise, so:
     * <ul>
     * <li>{@code FOLLOWS} - S == P: the modifier is present iff the tick's input sprints, and stays so (the vanilla steady state, and the
     *     only class the Phase 5-8 fixtures ever visit);</li>
     * <li>{@code STUCK_ON} - S = false, P = true: a snapshot synced while the server still saw the player sprinting reached a client that
     *     had already stopped; the modifier applies to every input until the client next starts sprinting (then FOLLOWS);</li>
     * <li>{@code STUCK_OFF} - S = true, P = false: a snapshot without the modifier reached a sprinting client; absent until it next stops.</li>
     * <li>{@code MAYBE_STUCK_ON} = {FOLLOWS, STUCK_ON} and {@code MAYBE_STUCK_OFF} = {FOLLOWS, STUCK_OFF} - the flag is unknown (an anchor, or a snapshot
     *     that may or may not have come with a flag change). Kept as ONE state while the presence cannot matter or the members agree: the speed
     *     attribute is read only on the ground with a horizontal input, so the reconstructor splits the state into its members exactly on such an
     *     input on which they disagree ({@link #sprintModifierCases}). An idle tick keeps the class as it is (both sprint-key values give one
     *     state, a sound superset of the exact union); a moving tick resolves it exactly (a sprinting input settles MAYBE_STUCK_ON, a walking one
     *     MAYBE_STUCK_OFF). A newly applied snapshot maps every class to the MAYBE class of what it carries (STUCK_OFF under a carrying snapshot and
     *     STUCK_ON under a bare one become FOLLOWS: the flag was already the snapshot's).</li>
     * </ul>
     * ({@code ClientPacketListener.handleUpdateAttributes}: removeModifiers + add the snapshot's.)
     */
    public enum SprintModifier {
        FOLLOWS, STUCK_ON, STUCK_OFF, MAYBE_STUCK_ON, MAYBE_STUCK_OFF;
        /** The class after a snapshot that carries ({@code carried}) or lacks the modifier was applied. */
        public SprintModifier afterSnapshot(boolean carried) { return carried ? (this == STUCK_OFF ? FOLLOWS : MAYBE_STUCK_ON) : (this == STUCK_ON ? FOLLOWS : MAYBE_STUCK_OFF); }
    }
    /** {@code Entity.mainSupportingBlockPos} (absent = none) plus {@code onGroundNoBlocks}. */
    public record Support(boolean present, int x, int y, int z, boolean onGroundNoBlocks) {
        public static final Support NONE = new Support(false, 0, 0, 0, false);
        public static Support at(int x, int y, int z) { return new Support(true, x, y, z, false); }
    }
    public PhysicsState {
        Objects.requireNonNull(position); Objects.requireNonNull(velocity); Objects.requireNonNull(pose); Objects.requireNonNull(stuck); Objects.requireNonNull(support); Objects.requireNonNull(locomotion); Objects.requireNonNull(sprintModifier);
        if (noJumpDelay < 0) throw new IllegalArgumentException("noJumpDelay");
    }
    /** The Phase 5-8 field set: the sprint modifier FOLLOWS the sprint input. */
    public PhysicsState(Vec3d position, Vec3d velocity, PlayerBox.Pose pose, boolean onGround, boolean horizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
                        double fallDistance, Vec3d stuck, boolean inPowderSnow, Support support, int noJumpDelay, Locomotion locomotion) {
        this(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion, SprintModifier.FOLLOWS);
    }
    /** A resting walking state: no velocity, no fall, no stuck multiplier, no support recorded (the first simulated tick establishes it), sprint modifier FOLLOWS. */
    public static PhysicsState resting(double x, double y, double z, PlayerBox.Pose pose, boolean onGround) {
        return new PhysicsState(new Vec3d(x, y, z), Vec3d.ZERO, pose, onGround, false, false, false, 0.0, Vec3d.ZERO, false, Support.NONE, 0, Locomotion.WALKING);
    }
    /**
     * P9-F1: whether the client's MOVEMENT_SPEED set carries the sprint modifier during a tick whose input sprints iff {@code sprinting}; a MAYBE
     * class answers for its members when they agree and {@code null} when they differ (the caller splits, see {@link #sprintModifierCases}).
     */
    public Boolean sprintModifierPresent(boolean sprinting) { return switch (sprintModifier) { case FOLLOWS -> sprinting; case STUCK_ON -> true; case STUCK_OFF -> false; case MAYBE_STUCK_ON -> sprinting ? Boolean.TRUE : null; case MAYBE_STUCK_OFF -> sprinting ? null : Boolean.FALSE; }; }
    /**
     * P9-F1: the class after a tick with {@code input} (a start leaves STUCK_ON, a stop leaves STUCK_OFF; FOLLOWS is absorbing). A MAYBE class is
     * kept on an idle tick (one state for both sprint-key values) and resolved exactly on a moving one; a split input never reaches this method
     * on a MAYBE state ({@link #sprintModifierCases}).
     */
    public SprintModifier sprintModifierAfter(MovementInput input) {
        boolean sprinting = input.sprinting();
        return switch (sprintModifier) { case FOLLOWS -> SprintModifier.FOLLOWS; case STUCK_ON -> sprinting ? SprintModifier.FOLLOWS : SprintModifier.STUCK_ON; case STUCK_OFF -> sprinting ? SprintModifier.STUCK_OFF : SprintModifier.FOLLOWS;
            case MAYBE_STUCK_ON -> !moving(input) || !sprinting ? SprintModifier.MAYBE_STUCK_ON : SprintModifier.FOLLOWS; case MAYBE_STUCK_OFF -> !moving(input) || sprinting ? SprintModifier.MAYBE_STUCK_OFF : SprintModifier.FOLLOWS; };
    }
    /** Whether the speed attribute is read for {@code input} from this state: on the ground with a horizontal input ({@code LivingEntity.travel}: the air acceleration is a constant). */
    public boolean speedMatters(MovementInput input) { return onGround && moving(input); }
    public static boolean moving(MovementInput input) { return input.forward() != 0 || input.strafe() != 0; }
    /**
     * P9-F1: the concrete states to simulate for {@code input}: this state, unless it is a MAYBE class whose members resolve the modifier differently
     * on an input for which the speed matters - then the two member states (each simulated on its own; the caller's evaluation count sees both).
     */
    public List<PhysicsState> sprintModifierCases(MovementInput input) {
        if (!speedMatters(input) || sprintModifierPresent(input.sprinting()) != null) return List.of(this);
        return List.of(withSprintModifier(SprintModifier.FOLLOWS), withSprintModifier(sprintModifier == SprintModifier.MAYBE_STUCK_ON ? SprintModifier.STUCK_ON : SprintModifier.STUCK_OFF));
    }
    /** The same state with the sprint modifier class replaced (anchors and snapshot resets). */
    public PhysicsState withSprintModifier(SprintModifier m) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion, m); }
    /** The Phase 5 record form; the sprint modifier class is appended only when it is not FOLLOWS (the Phase 5-8 fixture digests hash these strings, and every history they pin stays in FOLLOWS). */
    @Override public String toString() {
        return "PhysicsState[position=" + position + ", velocity=" + velocity + ", pose=" + pose + ", onGround=" + onGround + ", horizontalCollision=" + horizontalCollision + ", verticalCollision=" + verticalCollision
            + ", verticalCollisionBelow=" + verticalCollisionBelow + ", fallDistance=" + fallDistance + ", stuck=" + stuck + ", inPowderSnow=" + inPowderSnow + ", support=" + support + ", noJumpDelay=" + noJumpDelay + ", locomotion=" + locomotion
            + (sprintModifier == SprintModifier.FOLLOWS ? "" : ", sprintModifier=" + sprintModifier) + "]";
    }
    public boolean finite() { return position.isFinite() && velocity.isFinite() && Double.isFinite(fallDistance) && stuck.isFinite(); }
    public PhysicsState withVelocity(Vec3d v) { return new PhysicsState(position, v, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withPosition(Vec3d p) { return new PhysicsState(p, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withPose(PlayerBox.Pose p) { return new PhysicsState(position, velocity, p, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withOnGround(boolean g) { return new PhysicsState(position, velocity, pose, g, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withFallDistance(double d) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, d, stuck, inPowderSnow, support, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withSupport(Support s) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, s, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withStuck(Vec3d s) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, s, inPowderSnow, support, noJumpDelay, locomotion, sprintModifier); }
    public PhysicsState withLocomotion(Locomotion l) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, l, sprintModifier); }
}
