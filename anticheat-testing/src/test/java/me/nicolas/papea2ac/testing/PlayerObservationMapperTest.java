package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.core.PlayerObservationMapper;
import me.nicolas.papea2ac.player.*;

/** Regression coverage for the protocol.Observation -> player.PlayerObservation translation, including the
 * null-position/null-rotation field-presence case (a real NPE this mapper originally shipped with; see
 * PHASE_2_IMPLEMENTATION.md's "errors and fixes" section). */
class PlayerObservationMapperTest {
    static final SessionKey KEY = new SessionKey(new UUID(5, 6), 1);
    static Observation attempt(PacketPayload payload) { return new Observation(KEY, 1, 0, 0, new Observation.Attempt(1, 1, payload)); }
    static Observation outbound(PacketPayload payload) { return new Observation(KEY, 1, 0, 0, new Observation.Outbound(1, payload)); }
    static Observation outcome(Observation.Result result, PacketPayload finalPayload) {
        return new Observation(KEY, 1, 0, 0, new Observation.Outcome(1, 1, 1, result, finalPayload, PayloadDigest.of(new PacketPayload.Control(PacketPayload.ControlKind.PING, 0))));
    }

    @Test void movementWithBothFieldsMapsBothAsPresent() {
        var payload = new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), new PacketPayload.Rotation(9, 1), true, false);
        var mapped = (PlayerObservation.MovementClaim) PlayerObservationMapper.map(attempt(payload)).orElseThrow();
        assertTrue(mapped.position().isPresent());
        assertTrue(mapped.rotation().isPresent());
        assertEquals(new Vector3(1, 2, 3), mapped.position().get());
    }

    @Test void movementWithNullRotationMapsRotationAsEmptyWithoutThrowing() {
        var payload = new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false);
        var mapped = (PlayerObservation.MovementClaim) PlayerObservationMapper.map(attempt(payload)).orElseThrow();
        assertTrue(mapped.position().isPresent());
        assertTrue(mapped.rotation().isEmpty());
    }

    @Test void movementWithNullPositionMapsPositionAsEmptyWithoutThrowing() {
        var payload = new PacketPayload.Movement(null, new PacketPayload.Rotation(9, 1), true, false);
        var mapped = (PlayerObservation.MovementClaim) PlayerObservationMapper.map(attempt(payload)).orElseThrow();
        assertTrue(mapped.position().isEmpty());
        assertTrue(mapped.rotation().isPresent());
    }

    @Test void teleportConfirmControlMapsToTeleportAcknowledged() {
        var mapped = (PlayerObservation.TeleportAcknowledged) PlayerObservationMapper.map(attempt(new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM, 42))).orElseThrow();
        assertEquals(42L, mapped.teleportId());
    }

    @Test void unrelatedInboundControlIsNotMapped() {
        assertTrue(PlayerObservationMapper.map(attempt(new PacketPayload.Control(PacketPayload.ControlKind.SWING, 0))).isEmpty());
    }

    @Test void outboundCorrectionMapsToTeleportSent() {
        var payload = new SemanticPayload.Correction(3, new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0, 0), Set.of());
        var mapped = (PlayerObservation.TeleportSent) PlayerObservationMapper.map(outbound(payload)).orElseThrow();
        assertEquals(3L, mapped.teleportId());
        assertEquals(new Vector3(1, 2, 3), mapped.position());
    }

    @Test void outboundVelocityMapsToExternalMotionObserved() {
        var payload = new PacketPayload.Velocity(9, new PacketPayload.Position(0, 1, 0));
        var mapped = (PlayerObservation.ExternalMotionObserved) PlayerObservationMapper.map(outbound(payload)).orElseThrow();
        assertEquals(ExternalMotionInfluence.Mechanism.VELOCITY_REPLACE, mapped.mechanism());
    }

    @Test void explosionWithPlayerKnockbackMapsToExternalMotionObserved() {
        var payload = new PacketPayload.Explosion(new PacketPayload.Position(0, 0, 0), 3f, 5, Optional.of(new PacketPayload.Position(0, 1, 0)));
        var mapped = (PlayerObservation.ExternalMotionObserved) PlayerObservationMapper.map(outbound(payload)).orElseThrow();
        assertEquals(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA, mapped.mechanism());
        assertEquals("explosion", mapped.source());
    }

    @Test void explosionWithoutPlayerKnockbackIsNotMapped() {
        var payload = new PacketPayload.Explosion(new PacketPayload.Position(0, 0, 0), 3f, 5, Optional.empty());
        assertTrue(PlayerObservationMapper.map(outbound(payload)).isEmpty());
    }

    @Test void respawnMapsToDiscontinuity() {
        var mapped = (PlayerObservation.Discontinuity) PlayerObservationMapper.map(outbound(new SemanticPayload.Respawn("minecraft:overworld", (byte) 0))).orElseThrow();
        assertEquals(PlayerObservation.DiscontinuityKind.RESPAWN, mapped.kind());
    }

    @Test void appliedOutcomeWithMovementFinalPayloadMapsToMovementOutcome() {
        var payload = new PacketPayload.Movement(new PacketPayload.Position(4, 5, 6), null, true, false);
        var mapped = (PlayerObservation.MovementOutcome) PlayerObservationMapper.map(outcome(Observation.Result.APPLIED, payload)).orElseThrow();
        assertEquals(PlayerObservation.ApplyResult.APPLIED, mapped.result());
        assertEquals(new Vector3(4, 5, 6), mapped.finalPosition());
    }

    @Test void rejectedOutcomeIsNotMappedBecauseFinalPayloadIsNullAndFamilyCannotBeClassified() {
        assertTrue(PlayerObservationMapper.map(outcome(Observation.Result.REJECTED, null)).isEmpty());
    }

    @Test void tickMapsToTickObserved() {
        var mapped = (PlayerObservation.TickObserved) PlayerObservationMapper.map(new Observation(KEY, 1, 0, 0, new Observation.Tick(77))).orElseThrow();
        assertEquals(77, mapped.serverTick());
    }

    @Test void administrativeEventsAreNeverMapped() {
        assertTrue(PlayerObservationMapper.map(new Observation(KEY, 1, 0, 0, new Observation.ControlChange("RECORDING", "true"))).isEmpty());
        assertTrue(PlayerObservationMapper.map(new Observation(KEY, 1, 0, 0, new Observation.CheckpointMarker(1))).isEmpty());
        assertTrue(PlayerObservationMapper.map(new Observation(KEY, 1, 0, 0, new Observation.WriteOutcome(1, true))).isEmpty());
    }
}
