package me.nicolas.papea2ac.schema;
import java.util.List;
import java.util.Objects;
/**
 * Combat observation archival projection (COMBAT_MODEL.md: "Combat-local ReplayMapper exports CombatSnapshotDto in replay-schema"). The retained
 * attack contexts and non-attack interactions of one session, primitive fields only: identity (session, sequence, attempt, ingress ordinal), the
 * target resolution (status, incarnation, network state and its exactness), the attacker provenance, the timing status and the completeness
 * reasons. It carries no verdict of any kind: there is no field that could hold one.
 */
public record CombatCapsule(int schema, long sessionEpoch, String profileId, List<Attack> attacks, List<Interaction> interactions) {
    public static final int SCHEMA = 1, MAX_ENTRIES = 64;
    public CombatCapsule {
        Objects.requireNonNull(profileId); Objects.requireNonNull(attacks); Objects.requireNonNull(interactions);
        if (schema != SCHEMA || sessionEpoch < 1 || attacks.size() > MAX_ENTRIES || interactions.size() > MAX_ENTRIES || profileId.length() > 64) throw new IllegalArgumentException("combat capsule");
        attacks = List.copyOf(attacks); interactions = List.copyOf(interactions);
    }
    /** The target as resolved at the observation's sequence; {@code revisionSequence} -1 and NaN-free zeros when the status is not FOUND. */
    public record Target(String status, int networkId, long incarnation, String type, long revisionSequence, double x, double y, double z, String positionStatus,
                         float yaw, float pitch, String rotationStatus, String pose, int vehicleId, String detail) {
        public Target { Objects.requireNonNull(status); Objects.requireNonNull(type); Objects.requireNonNull(positionStatus); Objects.requireNonNull(rotationStatus); Objects.requireNonNull(pose); Objects.requireNonNull(detail); }
    }
    public record Attack(long sequence, long attemptId, long ingressOrdinal, int targetId, long worldEpoch, Target target,
                         String attackerStatus, long attackerRecordSequence, long attackerPositionSequence, double attackerX, double attackerY, double attackerZ,
                         boolean attackerRotationKnown, float attackerYaw, float attackerPitch, long attackerRotationSequence,
                         String validationClassification, long validationEndSequence, boolean validationAnchored,
                         String timingStatus, long lastTickEndSequence, long rttSamples, boolean rttStale, String completeness, List<String> reasons) {
        public Attack { Objects.requireNonNull(target); Objects.requireNonNull(attackerStatus); Objects.requireNonNull(validationClassification); Objects.requireNonNull(timingStatus); Objects.requireNonNull(completeness); reasons = List.copyOf(reasons); if (sequence < 1) throw new IllegalArgumentException("attack dto"); }
    }
    public record Interaction(long sequence, long attemptId, long ingressOrdinal, int targetId, long worldEpoch, String hand, double x, double y, double z, boolean secondaryAction, Target target) {
        public Interaction { Objects.requireNonNull(hand); Objects.requireNonNull(target); if (sequence < 1) throw new IllegalArgumentException("interaction dto"); }
    }
}
