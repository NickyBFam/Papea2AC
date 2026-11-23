package me.nicolas.papea2ac.schema;
import java.util.List;
import java.util.Objects;
/**
 * Observer entity-history archival projection (REPLAY_SYSTEM.md R2: replay-schema owns the DTO; anticheat-world's EntityReplayMapper is the only
 * writer). A coherent state projection of one observer's entity history: epoch scope, the honesty markers (eviction floor, coverage break,
 * capacity overflow) and every retained incarnation with its retained network revisions. Primitive fields only; unknown stays explicit
 * (empty pose = UNKNOWN, -1 flags = UNKNOWN). No capture time is carried: the projection compares identically across replays.
 */
public record EntityCapsule(int schema, long sessionEpoch, long worldEpoch, long epochStartSequence, long lastSequence, long nextIncarnation, long evictionFloor,
                            long coverageBreakSequence, String coverageBreakReason, long capacityOverflowSequence, List<Entity> entities) {
    public static final int SCHEMA = 1, MAX_ENTITIES = 256, MAX_REVISIONS = 96;
    public EntityCapsule {
        Objects.requireNonNull(coverageBreakReason); Objects.requireNonNull(entities);
        if (schema != SCHEMA || sessionEpoch < 1 || worldEpoch < 0 || lastSequence < 0 || nextIncarnation < 1 || entities.size() > MAX_ENTITIES || coverageBreakReason.length() > 64) throw new IllegalArgumentException("entity capsule");
        entities = List.copyOf(entities);
    }
    public record Revision(long sequence, int subIndex, String cause, double x, double y, double z, String positionStatus, double baseX, double baseY, double baseZ,
                           float yaw, float pitch, String rotationStatus, float headYaw, String pose, boolean poseOpaque, int sharedFlags, long metadataRevision, int vehicleId) {
        public Revision { Objects.requireNonNull(cause); Objects.requireNonNull(positionStatus); Objects.requireNonNull(rotationStatus); Objects.requireNonNull(pose); if (sequence < 1 || cause.length() > 48 || pose.length() > 32) throw new IllegalArgumentException("revision dto"); }
    }
    public record Entity(int networkId, long incarnation, String type, long uuidMost, long uuidLeast, long spawnSequence, long endSequence, String endCause,
                         long taintSequence, String taintReason, boolean deep, List<Revision> revisions) {
        public Entity {
            Objects.requireNonNull(type); Objects.requireNonNull(endCause); Objects.requireNonNull(taintReason); Objects.requireNonNull(revisions);
            if (networkId < 0 || incarnation < 1 || revisions.isEmpty() || revisions.size() > MAX_REVISIONS || type.length() > 128) throw new IllegalArgumentException("entity dto");
            revisions = List.copyOf(revisions);
        }
    }
}
