package me.nicolas.papea2ac.schema;
import java.util.List;
import java.util.Objects;
/**
 * Player Timeline archival projection (REPLAY_SYSTEM.md Decision R2: replay-schema owns this DTO;
 * anticheat-player's TimelineReplayMapper/TimelineCodec are the only writer/reader). Carries the ORIGINAL
 * causal observations, not the derived PlayerState/TimelineRecord - mirroring TransportCapsule's own
 * established pattern (protocol.TransportReplayMapper exports Observations, not TruthActor's derived
 * Snapshot), so replay re-runs the same pure PlayerStateReducer against restored inputs rather than
 * deserializing an opaque output. No live implementation object, no Paper/NMS type, ever crosses this file.
 */
public record TimelineCapsule(int schema, long sessionEpoch, List<Entry> entries, boolean complete, String incompleteReason) {
    public TimelineCapsule {
        Objects.requireNonNull(entries); Objects.requireNonNull(incompleteReason);
        if (schema != 1 || sessionEpoch < 1 || entries.size() > 4096 || incompleteReason.length() > 128)
            throw new IllegalArgumentException("timeline capsule");
        if (complete && !incompleteReason.isEmpty()) throw new IllegalArgumentException("a complete capsule cannot also carry an incomplete reason");
        entries = List.copyOf(entries);
    }
    /** transportDegradeReason: the Phase 1 transport health reason in force when this entry was appended ("" if healthy). Carried explicitly so an incomplete-coverage entry can never replay as a certain one. */
    public record Entry(long captureSequence, long monotonicNanos, Observation observation, String transportDegradeReason) {
        public Entry {
            Objects.requireNonNull(observation); Objects.requireNonNull(transportDegradeReason);
            if (captureSequence < 1 || transportDegradeReason.length() > 32) throw new IllegalArgumentException("entry");
        }
    }
    /** Mirrors player.PlayerObservation's sealed variants 1:1, using only primitive fields (schema DTOs never import a domain module). */
    public sealed interface Observation {}
    public record Movement(boolean hasPosition, double x, double y, double z, boolean hasRotation, float yaw, float pitch, boolean onGround, boolean horizontalCollision) implements Observation {}
    public record Outcome(String result, double x, double y, double z) implements Observation {
        public Outcome { Objects.requireNonNull(result); }
    }
    public record TeleportSent(long teleportId, double x, double y, double z, float yaw, float pitch) implements Observation {
        public TeleportSent { if (teleportId < 0) throw new IllegalArgumentException("teleportId"); }
    }
    public record TeleportAcknowledged(long teleportId) implements Observation {
        public TeleportAcknowledged { if (teleportId < 0) throw new IllegalArgumentException("teleportId"); }
    }
    public record ExternalMotion(String mechanism, String source, double x, double y, double z, long correlationId) implements Observation {
        public ExternalMotion { Objects.requireNonNull(mechanism); Objects.requireNonNull(source); if (correlationId < 0) throw new IllegalArgumentException("correlationId"); }
    }
    public record Discontinuity(String kind) implements Observation {
        public Discontinuity { Objects.requireNonNull(kind); }
    }
    public record Tick(long serverTick) implements Observation {
        public Tick { if (serverTick < 0) throw new IllegalArgumentException("serverTick"); }
    }
    public record CoverageGap(String reason) implements Observation {
        public CoverageGap { Objects.requireNonNull(reason); if (reason.length() > 64) throw new IllegalArgumentException("reason"); }
    }
    public record SessionClosed() implements Observation {}
}
