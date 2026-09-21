package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import java.util.Objects;

/**
 * What was OBSERVED about the end of one client tick - the observation set O a candidate's prediction is compared with
 * (MOVEMENT_SIMULATION.md M2 step 1). Exactly these fields constrain a reconstruction, nothing else (§9):
 * <ul>
 * <li>{@link Position} - the feet position: {@link Exact} (a transmitted position, compared by numeric equality: the
 *     protocol carries full doubles), {@link WithinSendThreshold} (the tick produced no position packet: the true position is
 *     inside the client's silence ball around the last TRANSMITTED position, {@link MovementObservationProfile}), or
 *     {@link Unobserved} (a caller-declared gap with no packet knowledge - constrains nothing);</li>
 * <li>{@code yaw} - the rotation the client applied to this tick's input basis: the packet's rotation when the packet carried one,
 *     otherwise the last transmitted yaw (the client sends a rotation whenever it changed). {@code yawKnown = false} means the
 *     caller could not establish it (no rotation ever observed, or an unaligned rotation change inside a gap): the tick is then
 *     not simulable - ROTATION_UNRESOLVED, never a guessed aim (INPUT_RECONSTRUCTION.md);</li>
 * <li>{@link GroundClaim} - the client's onGround FLAG as sent. It is an observation, never physics truth and never a matching
 *     constraint here (COLLISION_ENGINE.md; PLAYER_STATE.md keeps the claim separately): the result reports whether the
 *     candidate's simulated ground flag agrees, for Phase 8;</li>
 * <li>{@code sequence} - the timeline sequence of the observation (provenance).</li>
 * </ul>
 * Pitch, horizontal-collision flag, server tick and arrival time are deliberately not part of the contract: they do not
 * enter the vanilla position arithmetic (pitch) or are Phase 7 timing inputs.
 */
public record TickObservation(Position position, float yaw, boolean yawKnown, GroundClaim ground, long sequence) {
    public enum GroundClaim { TRUE, FALSE, ABSENT }
    public sealed interface Position permits Exact, WithinSendThreshold, Unobserved {
        /** Whether the predicted feet position is inside the observation set. */
        boolean contains(Vec3d p, MovementObservationProfile profile);
        /** Whether this observation pins the position to a point. */
        default boolean exact() { return this instanceof Exact; }
    }
    /** A transmitted position (doubles as sent). Membership is numeric equality per axis (so {@code -0.0 == 0.0}; NaN never matches). */
    public record Exact(double x, double y, double z) implements Position {
        public Exact { if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("non-finite observation"); }
        @Override public boolean contains(Vec3d p, MovementObservationProfile profile) { return p.x() == x && p.y() == y && p.z() == z; }
    }
    /** No position was transmitted for this tick: the client found {@code |p - lastSent|^2 <= threshold} (its own double arithmetic). */
    public record WithinSendThreshold(double lastSentX, double lastSentY, double lastSentZ) implements Position {
        public WithinSendThreshold { if (!Double.isFinite(lastSentX) || !Double.isFinite(lastSentY) || !Double.isFinite(lastSentZ)) throw new IllegalArgumentException("non-finite observation"); }
        @Override public boolean contains(Vec3d p, MovementObservationProfile profile) { return p.isFinite() && profile.withinSendThreshold(p.x(), p.y(), p.z(), lastSentX, lastSentY, lastSentZ); }
    }
    /** A declared gap: nothing is known about this tick's position (it constrains nothing; the reconstruction stays multi-tick-bounded). */
    public record Unobserved() implements Position {
        public static final Unobserved INSTANCE = new Unobserved();
        @Override public boolean contains(Vec3d p, MovementObservationProfile profile) { return p.isFinite(); }
    }
    public TickObservation {
        Objects.requireNonNull(position); Objects.requireNonNull(ground);
        if (yawKnown && !Float.isFinite(yaw)) throw new IllegalArgumentException("yaw");
    }
    public static TickObservation exact(double x, double y, double z, float yaw, GroundClaim ground, long sequence) { return new TickObservation(new Exact(x, y, z), yaw, true, ground, sequence); }
    public static TickObservation silent(double lastSentX, double lastSentY, double lastSentZ, float yaw, long sequence) { return new TickObservation(new WithinSendThreshold(lastSentX, lastSentY, lastSentZ), yaw, true, GroundClaim.ABSENT, sequence); }
    public static TickObservation unobserved(float yaw, long sequence) { return new TickObservation(Unobserved.INSTANCE, yaw, true, GroundClaim.ABSENT, sequence); }
    public static TickObservation yawUnknown(Position position, long sequence) { return new TickObservation(position, 0f, false, GroundClaim.ABSENT, sequence); }

    /**
     * Diagnostic residual of a prediction against this observation (§23): per-axis {@code predicted - reference} where the
     * reference is the transmitted position (Exact) or the last transmitted position (WithinSendThreshold), the distance by
     * which the prediction lies OUTSIDE the observation set (0 when inside), and whether the simulated ground flag agrees
     * with the claim. Never a verdict; never NaN for finite predictions.
     */
    public record Residual(double dx, double dy, double dz, double outsideDistance, boolean inside, boolean groundClaimAgrees, boolean groundClaimPresent) {
        public boolean finite() { return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz) && Double.isFinite(outsideDistance); }
    }
    public Residual residual(Vec3d predicted, boolean predictedOnGround, MovementObservationProfile profile) {
        boolean inside = position.contains(predicted, profile);
        boolean present = ground != GroundClaim.ABSENT, agrees = !present || (ground == GroundClaim.TRUE) == predictedOnGround;
        return switch (position) {
            case Exact e -> { double dx = predicted.x() - e.x(), dy = predicted.y() - e.y(), dz = predicted.z() - e.z(); yield new Residual(dx, dy, dz, inside ? 0.0 : Math.sqrt(dx * dx + dy * dy + dz * dz), inside, agrees, present); }
            case WithinSendThreshold w -> { double dx = predicted.x() - w.lastSentX(), dy = predicted.y() - w.lastSentY(), dz = predicted.z() - w.lastSentZ(); double d = Math.sqrt(dx * dx + dy * dy + dz * dz), r = Math.sqrt(profile.positionSendThresholdSqr()); yield new Residual(dx, dy, dz, inside ? 0.0 : Math.max(0.0, d - r), inside, agrees, present); }
            case Unobserved u -> new Residual(0.0, 0.0, 0.0, 0.0, inside, agrees, present);
        };
    }
}
