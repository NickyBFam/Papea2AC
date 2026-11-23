package me.nicolas.papea2ac.schema;
import java.util.List;
import java.util.Objects;
/**
 * Phase 12 reach archival projection (COMBAT_MODEL.md "Evidence contains enough geometry and history references to replay, not only a reach
 * number"; REPLAY_SYSTEM.md R2). The retained reach results of one session, primitive fields only: the attack identity, the status / miss
 * classes / reasons (the fixed vocabularies of the engine), the eye source and window bound, the target incarnation, the scene and work counts,
 * and the witness or certificate geometry as raw IEEE values. State is rebuilt by replaying the transport observations; this projection is the
 * comparison target, never a second source of truth.
 */
public record ReachCapsule(int schema, long sessionEpoch, String profileId, List<Result> results) {
    public static final int SCHEMA = 1, MAX_ENTRIES = 64;
    public ReachCapsule {
        Objects.requireNonNull(profileId); Objects.requireNonNull(results);
        if (schema != SCHEMA || sessionEpoch < 1 || results.size() > MAX_ENTRIES || profileId.length() > 96) throw new IllegalArgumentException("reach capsule");
        results = List.copyOf(results);
    }
    /**
     * One result. {@code witness} holds {eyeX, eyeY, eyeZ, yaw, pitch, positionsMinX..MaxZ, maxEntryDistance, range} or is empty; {@code certificate}
     * holds {scenes, minDistance, minResidual} or is empty.
     */
    public record Result(long sequence, String attackId, String status, List<String> missClasses, List<String> reasons, String eyeSource, String window,
                         long markerSequence, long pongSequence, int ticksSincePong, String targetType, int targetNetworkId, long targetIncarnation,
                         int scenes, int workUnits, List<Double> witness, List<Double> certificate, double entityRange, double scale, List<String> notes) {
        public Result {
            Objects.requireNonNull(attackId); Objects.requireNonNull(status); Objects.requireNonNull(eyeSource); Objects.requireNonNull(window); Objects.requireNonNull(targetType);
            missClasses = List.copyOf(missClasses); reasons = List.copyOf(reasons); witness = List.copyOf(witness); certificate = List.copyOf(certificate); notes = List.copyOf(notes);
            if (sequence < 1) throw new IllegalArgumentException("reach result dto");
        }
    }
}
