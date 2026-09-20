package me.nicolas.papea2ac.physics;
import java.util.*;

/**
 * The answer of one forward step (MOVEMENT_SIMULATION.md: "Every transition is reproducible from the normalized input
 * and ruleset, including uncertainty and operation-budget aborts"). Never a boolean and never a verdict: the {@link Status}
 * says how much of the vanilla tick could be reproduced exactly, {@link #reasons} says why not, {@link #next} is the
 * predicted state (present whenever the arithmetic could be carried through, even for a non-EXACT status, so a caller can
 * still see what the step assumed; absent when nothing sound could be computed), {@link #work} the bounded effort spent,
 * {@link #provenance} the world/profile identity the prediction is valid for. A mismatch between {@code next} and an
 * observed movement is NOT "cheating" - classification is Phase 8's.
 */
public record PhysicsResult(Status status, PhysicsState next, List<Reason> reasons, Work work, Provenance provenance, Trace trace) {
    /** Ordered by severity (first = most severe); the result status is the most severe reason's status, or EXACT. */
    public enum Status {
        NUMERIC_INVALID, COORDINATE_LIMIT, UNAVAILABLE_WORLD, QUERY_LIMIT, UNSUPPORTED_MODE, UNSUPPORTED_POSE, UNSUPPORTED_FLUID, UNSUPPORTED_BORDER, UNSUPPORTED_BLOCK_BEHAVIOUR,
        UNRESOLVED_EXTERNAL_MOTION, UNKNOWN_ATTRIBUTE, UNKNOWN_EFFECT, UNKNOWN_EQUIPMENT, UNKNOWN_ENTITY_NEIGHBORHOOD, UNKNOWN_WORLD_BORDER, INCOMPLETE_WORLD, EXACT;
        public boolean exact() { return this == EXACT; }
    }
    /** A typed, bounded explanation; {@code code.status()} is the severity class it contributes. */
    public record Reason(Code code, String detail) {
        public enum Code {
            NON_FINITE_STATE(Status.NUMERIC_INVALID), NON_FINITE_INPUT(Status.NUMERIC_INVALID), NON_FINITE_ATTRIBUTE(Status.NUMERIC_INVALID),
            COORDINATE_OUT_OF_RANGE(Status.COORDINATE_LIMIT),
            VIEW_UNAVAILABLE(Status.UNAVAILABLE_WORLD), WRONG_WORLD_EPOCH(Status.UNAVAILABLE_WORLD),
            QUERY_BUDGET(Status.QUERY_LIMIT), CELL_BUDGET(Status.QUERY_LIMIT), INSIDE_SAMPLING_BUDGET(Status.QUERY_LIMIT), ENTITY_BUDGET(Status.QUERY_LIMIT), QUERY_REJECTED(Status.QUERY_LIMIT),
            LOCOMOTION_UNSUPPORTED(Status.UNSUPPORTED_MODE), POSE_UNSUPPORTED(Status.UNSUPPORTED_POSE),
            FLUID_CONTACT(Status.UNSUPPORTED_FLUID), BUBBLE_COLUMN(Status.UNSUPPORTED_FLUID),
            BORDER_PROXIMITY(Status.UNSUPPORTED_BORDER),
            PORTAL_CONTACT(Status.UNSUPPORTED_BLOCK_BEHAVIOUR), BLOCK_BEHAVIOUR_UNCLASSIFIED(Status.UNSUPPORTED_BLOCK_BEHAVIOUR), FALL_RESET_RAY(Status.UNSUPPORTED_BLOCK_BEHAVIOUR), INSIDE_SHAPE_UNRESOLVED(Status.UNSUPPORTED_BLOCK_BEHAVIOUR), INSIDE_BLOCK_START(Status.UNSUPPORTED_BLOCK_BEHAVIOUR),
            EXTERNAL_MOTION_UNRESOLVED(Status.UNRESOLVED_EXTERNAL_MOTION),
            ATTRIBUTE_UNKNOWN(Status.UNKNOWN_ATTRIBUTE), ATTRIBUTE_UNSUPPORTED(Status.UNKNOWN_ATTRIBUTE),
            EFFECT_UNKNOWN(Status.UNKNOWN_EFFECT), EFFECTS_INCOMPLETE(Status.UNKNOWN_EFFECT),
            EQUIPMENT_UNKNOWN(Status.UNKNOWN_EQUIPMENT),
            ENTITIES_UNKNOWN(Status.UNKNOWN_ENTITY_NEIGHBORHOOD), PUSH_ORDER_AMBIGUOUS(Status.UNKNOWN_ENTITY_NEIGHBORHOOD),
            BORDER_UNKNOWN(Status.UNKNOWN_WORLD_BORDER),
            CELL_UNKNOWN(Status.INCOMPLETE_WORLD), COLLISION_INCOMPLETE(Status.INCOMPLETE_WORLD), ENVELOPE_GEOMETRY(Status.INCOMPLETE_WORLD), CHUNK_UNLOADED(Status.INCOMPLETE_WORLD);
            private final Status status;
            Code(Status s) { status = s; }
            public Status status() { return status; }
        }
        public Reason { Objects.requireNonNull(code); Objects.requireNonNull(detail); if (detail.length() > 160) detail = detail.substring(0, 160); }
    }
    /** Bounded work performed: collision queries, cells visited by them, shape boxes emitted, inside-sampling cells, entity operations. */
    public record Work(int queries, int cells, int boxes, int insideCells, int entityOperations) {}
    /** What the prediction is valid for. */
    public record Provenance(long worldEpoch, long worldRevision, long worldSequence, String movementProfile, String collisionProfile, String sineDigest) {}
    /**
     * Diagnostic intermediates (bounded; recorded, never a substitute for recomputation): the input basis vector added
     * to the velocity, the speed and ground friction used, the velocity entering the move, the movement requested of the
     * collider (after stuck/edge back-off) and what came back, whether a step-up candidate was taken, whether a jump
     * fired, and the fluid/ground facts the tick observed.
     */
    public record Trace(Vec3d inputVector, float speed, float friction, Vec3d velocityBeforeMove, Vec3d movementRequested, Vec3d movementCollided, boolean steppedUp, boolean jumped, boolean crouchingInput, boolean inFluidBefore) {
        public static final Trace NONE = new Trace(Vec3d.ZERO, 0f, 0f, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, false, false, false, false);
    }
    public PhysicsResult {
        Objects.requireNonNull(status); reasons = List.copyOf(reasons); Objects.requireNonNull(work); Objects.requireNonNull(provenance); Objects.requireNonNull(trace);
        if (status == Status.EXACT && !reasons.isEmpty()) throw new IllegalArgumentException("EXACT with reasons");
    }
    public boolean exact() { return status == Status.EXACT; }
    public boolean hasReason(Reason.Code code) { for (Reason r : reasons) if (r.code() == code) return true; return false; }
    /** One bounded line for diagnostics. */
    public String summary() {
        var sb = new StringBuilder(status.name());
        if (next != null) sb.append(String.format(Locale.ROOT, " pos=(%.5f,%.5f,%.5f) vel=(%.5f,%.5f,%.5f) ground=%s pose=%s", next.position().x(), next.position().y(), next.position().z(), next.velocity().x(), next.velocity().y(), next.velocity().z(), next.onGround(), next.pose()));
        sb.append(" work=q").append(work.queries()).append("/c").append(work.cells()).append("/i").append(work.insideCells());
        int n = 0; for (Reason r : reasons) { if (n++ == 4) { sb.append(" +").append(reasons.size() - 4).append(" more"); break; } sb.append(' ').append(r.code()).append('[').append(r.detail()).append(']'); }
        return sb.toString();
    }
}
