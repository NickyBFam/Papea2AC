package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.collision.PlayerBox;
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
 * </ul>
 */
public record PhysicsState(Vec3d position, Vec3d velocity, PlayerBox.Pose pose, boolean onGround, boolean horizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
                           double fallDistance, Vec3d stuck, boolean inPowderSnow, Support support, int noJumpDelay, Locomotion locomotion) {
    /** Vanilla travel modes. Phase 5 simulates WALKING (ground/air, including crawling in a one-block gap); the others are explicit deferrals. */
    public enum Locomotion { WALKING, CREATIVE_FLYING, ELYTRA, SWIMMING, RIDING, SLEEPING, SPECTATOR, SPIN_ATTACK, DEAD }
    /** {@code Entity.mainSupportingBlockPos} (absent = none) plus {@code onGroundNoBlocks}. */
    public record Support(boolean present, int x, int y, int z, boolean onGroundNoBlocks) {
        public static final Support NONE = new Support(false, 0, 0, 0, false);
        public static Support at(int x, int y, int z) { return new Support(true, x, y, z, false); }
    }
    public PhysicsState {
        Objects.requireNonNull(position); Objects.requireNonNull(velocity); Objects.requireNonNull(pose); Objects.requireNonNull(stuck); Objects.requireNonNull(support); Objects.requireNonNull(locomotion);
        if (noJumpDelay < 0) throw new IllegalArgumentException("noJumpDelay");
    }
    /** A resting walking state: no velocity, no fall, no stuck multiplier, no support recorded (the first simulated tick establishes it). */
    public static PhysicsState resting(double x, double y, double z, PlayerBox.Pose pose, boolean onGround) {
        return new PhysicsState(new Vec3d(x, y, z), Vec3d.ZERO, pose, onGround, false, false, false, 0.0, Vec3d.ZERO, false, Support.NONE, 0, Locomotion.WALKING);
    }
    public boolean finite() { return position.isFinite() && velocity.isFinite() && Double.isFinite(fallDistance) && stuck.isFinite(); }
    public PhysicsState withVelocity(Vec3d v) { return new PhysicsState(position, v, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion); }
    public PhysicsState withPosition(Vec3d p) { return new PhysicsState(p, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion); }
    public PhysicsState withPose(PlayerBox.Pose p) { return new PhysicsState(position, velocity, p, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion); }
    public PhysicsState withOnGround(boolean g) { return new PhysicsState(position, velocity, pose, g, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, locomotion); }
    public PhysicsState withFallDistance(double d) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, d, stuck, inPowderSnow, support, noJumpDelay, locomotion); }
    public PhysicsState withSupport(Support s) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, s, noJumpDelay, locomotion); }
    public PhysicsState withStuck(Vec3d s) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, s, inPowderSnow, support, noJumpDelay, locomotion); }
    public PhysicsState withLocomotion(Locomotion l) { return new PhysicsState(position, velocity, pose, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, stuck, inPowderSnow, support, noJumpDelay, l); }
}
