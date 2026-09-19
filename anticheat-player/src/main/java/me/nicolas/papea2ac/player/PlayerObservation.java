package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.model.Rotation;
import java.util.Objects;
import java.util.Optional;
/**
 * Player-module-owned input contract fed by core's translation of Phase 1's {@code Observation} stream
 * (see anticheat-core's PlayerObservationMapper). Deliberately does not import protocol/packet types:
 * the player module's approved dependencies are model and replay-schema only (ARCHITECTURE.md's DAG).
 * This is a narrow, explicitly-scoped subset of the full packet observation matrix: only the families
 * PLAYER_STATE.md assigns to MovementState/RotationState/TeleportState/VelocityState/TickState plus the
 * discontinuity/coverage/lifecycle signals the Timeline itself must record. Combat, interaction, inventory,
 * vehicle, world/chunk and evidence families are intentionally excluded from Phase 2 (see PHASE_2_IMPLEMENTATION.md).
 */
public sealed interface PlayerObservation {
    int chargedBytes();
    /** Mirrors Observation.Result (protocol) without importing it; player owns its own small vocabulary at this boundary. */
    enum ApplyResult { APPLIED, TRANSFORMED_APPLIED, REJECTED, FAILED, UNKNOWN }
    /** Matches PLAYER_TIMELINE.md exactly: world epoch increments only on dimension/respawn baseline change.
     * Reconnect is a distinct connection-epoch event handled structurally by session replacement (a new
     * connection gets a brand-new PlayerTimeline instance, never a mid-session discontinuity record). */
    enum DiscontinuityKind { RESPAWN, DIMENSION_CHANGE }

    /**
     * An inbound client movement claim: provisional, never itself proof of server acceptance. position/rotation
     * are independently optional because the wire protocol itself has position-only, rotation-only and
     * combined movement variants (Phase 1's PacketPayload.Movement legitimately carries a null position or
     * rotation for "field not present in this packet", per its own "omitted movement fields remain omitted"
     * contract) - this is exactly why PLAYER_STATE.md keeps MovementState and RotationState as separate
     * components rather than one bundled record.
     */
    record MovementClaim(Optional<Vector3> position, Optional<Rotation> rotation, boolean onGround, boolean horizontalCollision) implements PlayerObservation {
        public MovementClaim { Objects.requireNonNull(position); Objects.requireNonNull(rotation); }
        public int chargedBytes() { return 96; }
    }
    /**
     * A correlated outcome whose final payload is concretely known to be movement-shaped. Only fed for
     * APPLIED/TRANSFORMED_APPLIED (Observation.Outcome.finalPayload is only guaranteed non-null for those
     * two results); a REJECTED/FAILED/UNKNOWN movement outcome cannot be family-classified from the bare
     * Outcome event alone (finalPayload is null there) without re-deriving Phase 1's private attempt-outcome
     * journal correlation inside TruthActor, which Phase 2 deliberately does not duplicate. Consumers needing
     * pipeline-level rejection/degradation visibility use CoverageGap / PacketEngine.coverage() instead.
     */
    record MovementOutcome(ApplyResult result, Vector3 finalPosition) implements PlayerObservation {
        public MovementOutcome { Objects.requireNonNull(result); Objects.requireNonNull(finalPosition); }
        public int chargedBytes() { return 96; }
    }
    /** Outbound server-issued position/rotation correction (setback/teleport). Sent is not applied. */
    record TeleportSent(long teleportId, Vector3 position, Rotation rotation) implements PlayerObservation {
        public TeleportSent { Objects.requireNonNull(position); Objects.requireNonNull(rotation); if (teleportId < 0) throw new IllegalArgumentException("teleportId"); }
        public int chargedBytes() { return 96; }
    }
    /** Inbound teleport-confirm control packet. Confirms receipt of the ID only, never client physics application. */
    record TeleportAcknowledged(long teleportId) implements PlayerObservation {
        public TeleportAcknowledged { if (teleportId < 0) throw new IllegalArgumentException("teleportId"); }
        public int chargedBytes() { return 32; }
    }
    /** A captured external-motion result (S2 taxonomy, owned by ExternalMotionInfluence.Mechanism). source is a free-form identity from Phase 1's normalized identity space (e.g. an entity type or "explosion"). */
    record ExternalMotionObserved(ExternalMotionInfluence.Mechanism mechanism, String source, Vector3 vector, long correlationId) implements PlayerObservation {
        public ExternalMotionObserved { Objects.requireNonNull(mechanism); Objects.requireNonNull(source); Objects.requireNonNull(vector); }
        public int chargedBytes() { return 96; }
    }
    /** A boundary the World Model will later own the detail of; Phase 2 only records that continuity broke and why. */
    record Discontinuity(DiscontinuityKind kind) implements PlayerObservation {
        public Discontinuity { Objects.requireNonNull(kind); }
        public int chargedBytes() { return 32; }
    }
    record TickObserved(long serverTick) implements PlayerObservation {
        public TickObserved { if (serverTick < 0) throw new IllegalArgumentException("serverTick"); }
        public int chargedBytes() { return 32; }
    }
    /** Fed when core observes a Phase 1 health-latch degrade; lets the Timeline mark subsequent records with reduced coverage without re-deriving Phase 1's own certification logic. */
    record CoverageGap(String reason) implements PlayerObservation {
        public CoverageGap { Objects.requireNonNull(reason); if (reason.length() > 64) throw new IllegalArgumentException("reason"); }
        public int chargedBytes() { return 96; }
    }
    record SessionClosed() implements PlayerObservation {
        public int chargedBytes() { return 16; }
    }
}
