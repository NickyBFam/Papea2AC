package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.player.ExternalMotionInfluence;
import me.nicolas.papea2ac.player.PlayerObservation;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import java.util.Optional;

/**
 * The DAG-mandated translation point from Phase 1's protocol.Observation into player-owned PlayerObservation
 * values: anticheat-player's allowed dependencies are model and replay-schema only (ARCHITECTURE.md), so it
 * cannot see protocol/packet types itself. core already depends on both, making it the only module legally
 * allowed to perform this mapping (Decision A3's "New-type rule"). This intentionally narrow, explicit
 * per-family mapping - not a generic reflection/visitor over every PacketPayload - is what "no second packet
 * listener, no re-normalization" means in practice: it reads only fields Phase 1's normalizer already produced.
 */
public final class PlayerObservationMapper {
    private PlayerObservationMapper() {}

    public static Optional<PlayerObservation> map(Observation observation) {
        return switch (observation.event()) {
            case Observation.Attempt a -> mapInbound(a.payload());
            case Observation.Outbound o -> mapOutbound(o.payload());
            case Observation.Outcome o -> mapOutcome(o);
            case Observation.Tick t -> Optional.of(new PlayerObservation.TickObserved(t.serverTick()));
            case Observation.WriteOutcome ignored -> Optional.empty();
            case Observation.ControlChange ignored -> Optional.empty();
            case Observation.CheckpointMarker ignored -> Optional.empty();
        };
    }

    private static Optional<PlayerObservation> mapInbound(PacketPayload payload) {
        if (payload instanceof PacketPayload.Movement m)
            return Optional.of(new PlayerObservation.MovementClaim(
                    m.position() == null ? Optional.empty() : Optional.of(vector(m.position())),
                    m.rotation() == null ? Optional.empty() : Optional.of(rotation(m.rotation())),
                    m.onGround(), m.horizontalCollision()));
        if (payload instanceof PacketPayload.Control c && c.kind() == PacketPayload.ControlKind.TELEPORT_CONFIRM)
            return Optional.of(new PlayerObservation.TeleportAcknowledged(c.value()));
        return Optional.empty();
    }

    private static Optional<PlayerObservation> mapOutbound(PacketPayload payload) {
        if (payload instanceof SemanticPayload.Correction c)
            return Optional.of(new PlayerObservation.TeleportSent(c.teleportId(), vector(c.position()), rotation(c.rotation())));
        if (payload instanceof PacketPayload.Velocity v)
            // Set Entity Velocity replaces the entity's motion vector in vanilla semantics; PLAYER_STATE.md
            // Decision S2 forbids inferring the VECTOR from the source label, not classifying the packet's
            // own well-known mechanism. A version-profile module owning this distinction authoritatively is
            // a documented future refinement (see PHASE_2_IMPLEMENTATION.md).
            return Optional.of(new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_REPLACE,
                    "entity:" + v.entityId(), vector(v.vector()), 0));
        if (payload instanceof PacketPayload.Explosion ex && ex.playerKnockback().isPresent())
            return Optional.of(new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA,
                    "explosion", vector(ex.playerKnockback().get()), 0));
        if (payload instanceof SemanticPayload.Respawn)
            // Vanilla's Respawn packet covers both true respawn and dimension-change transitions; disambiguating
            // which requires correlating against previously-known dimension identity, which is the World
            // Model's job (a later phase). Phase 2 only needs to know a discontinuity boundary occurred.
            return Optional.of(new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN));
        return Optional.empty();
    }

    private static Optional<PlayerObservation> mapOutcome(Observation.Outcome outcome) {
        boolean concreteFinalPayload = outcome.result() == Observation.Result.APPLIED || outcome.result() == Observation.Result.TRANSFORMED_APPLIED;
        if (concreteFinalPayload && outcome.finalPayload() instanceof PacketPayload.Movement m)
            return Optional.of(new PlayerObservation.MovementOutcome(mapResult(outcome.result()), vector(m.position())));
        return Optional.empty();
    }

    private static PlayerObservation.ApplyResult mapResult(Observation.Result result) {
        return switch (result) {
            case APPLIED -> PlayerObservation.ApplyResult.APPLIED;
            case TRANSFORMED_APPLIED -> PlayerObservation.ApplyResult.TRANSFORMED_APPLIED;
            case REJECTED -> PlayerObservation.ApplyResult.REJECTED;
            case FAILED -> PlayerObservation.ApplyResult.FAILED;
            case UNKNOWN -> PlayerObservation.ApplyResult.UNKNOWN;
        };
    }

    private static Vector3 vector(PacketPayload.Position position) { return new Vector3(position.x(), position.y(), position.z()); }
    private static Rotation rotation(PacketPayload.Rotation rotation) { return new Rotation(rotation.yaw(), rotation.pitch()); }
}
