package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.PlayerObservationMapper;
import me.nicolas.papea2ac.core.TimingObservationMapper;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.PlayerObservation;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;

/**
 * Phase 7 observation -> TimingEvent translation: the ACK inventory of the pinned protocol as Phase 1 normalizes it (tick end,
 * keep-alive, pong, teleport confirm inbound; keep-alive/ping markers, corrections, velocity, explosion knockback, respawn
 * outbound), the two clocks carried verbatim (capture sequence and capture-monotonic nanos, never a processing time), the
 * relative-flag mask, and P7-F1: a velocity packet is this player's influence only when it names the session's own entity.
 */
class TimingObservationMapperTest {
    static final SessionKey KEY = new SessionKey(new UUID(7, 6), 3);
    static final int SELF = 42;
    static Observation attempt(long seq, long nanos, PacketPayload p) { return new Observation(KEY, seq, nanos, 0, new Observation.Attempt(seq, seq, p)); }
    static Observation outbound(long seq, long nanos, long wire, PacketPayload p) { return new Observation(KEY, seq, nanos, 0, new Observation.Outbound(wire, p)); }
    static TimingEvent map(Observation o) { return TimingObservationMapper.map(o, SELF, 5).orElseThrow(); }

    @Test void inboundAcksCarryTheirOwnCaptureClocks() {
        var tickEnd = (TimingEvent.ClientTickEnd) map(attempt(11, 1_000_000_001L, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
        assertEquals(11, tickEnd.sequence()); assertEquals(1_000_000_001L, tickEnd.nanos());
        var keep = (TimingEvent.MarkerAcknowledged) map(attempt(12, 5, new PacketPayload.Control(PacketPayload.ControlKind.KEEPALIVE, 123456789012345L)));
        assertEquals(TimingEvent.MarkerKind.KEEPALIVE, keep.kind()); assertEquals(123456789012345L, keep.id());
        var pong = (TimingEvent.MarkerAcknowledged) map(attempt(13, 6, new PacketPayload.Control(PacketPayload.ControlKind.PONG, 0x03000001L)));
        assertEquals(TimingEvent.MarkerKind.PING, pong.kind()); assertEquals(0x03000001L, pong.id());
        var confirm = (TimingEvent.TeleportConfirmed) map(attempt(14, 7, new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM, 9)));
        assertEquals(9, confirm.teleportId()); assertEquals(14, confirm.sequence()); assertEquals(7, confirm.nanos());
    }
    @Test void movementKeepsFieldPresenceAndOnGround() {
        var both = (TimingEvent.MovementObserved) map(attempt(1, 1, new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), new PacketPayload.Rotation(90f, -10f), true, false)));
        assertTrue(both.hasPosition() && both.hasRotation() && both.onGround()); assertEquals(new Vector3(1, 2, 3), both.position()); assertEquals(90f, both.rotation().yaw());
        var rotOnly = (TimingEvent.MovementObserved) map(attempt(2, 2, new PacketPayload.Movement(null, new PacketPayload.Rotation(45f, 0f), false, false)));
        assertFalse(rotOnly.hasPosition()); assertTrue(rotOnly.hasRotation()); assertFalse(rotOnly.onGround());
        var posOnly = (TimingEvent.MovementObserved) map(attempt(3, 3, new PacketPayload.Movement(new PacketPayload.Position(4, 5, 6), null, true, false)));
        assertTrue(posOnly.hasPosition()); assertFalse(posOnly.hasRotation());
    }
    @Test void unrelatedInboundControlsAreNotTimingEvents() {
        for (var k : new PacketPayload.ControlKind[] { PacketPayload.ControlKind.SWING, PacketPayload.ControlKind.INPUT, PacketPayload.ControlKind.ACTION, PacketPayload.ControlKind.HELD_ITEM, PacketPayload.ControlKind.CAMERA, PacketPayload.ControlKind.PADDLE, PacketPayload.ControlKind.TICK_STEP, PacketPayload.ControlKind.PING })
            assertTrue(TimingObservationMapper.map(attempt(1, 1, new PacketPayload.Control(k, 1)), SELF, 0).isEmpty(), k.name());
    }
    @Test void outboundMarkersKeepKindIdAndWireOrdinal() {
        var ping = (TimingEvent.MarkerSent) map(outbound(20, 30, 77, new PacketPayload.Control(PacketPayload.ControlKind.PING, 0x03000001L)));
        assertEquals(TimingEvent.MarkerKind.PING, ping.kind()); assertEquals(0x03000001L, ping.id()); assertEquals(77, ping.wireOrdinal()); assertEquals(20, ping.sequence()); assertEquals(30, ping.nanos());
        var keep = (TimingEvent.MarkerSent) map(outbound(21, 31, 78, new PacketPayload.Control(PacketPayload.ControlKind.KEEPALIVE, 55L)));
        assertEquals(TimingEvent.MarkerKind.KEEPALIVE, keep.kind()); assertEquals(55L, keep.id());
        // an outbound PONG/TICK_END makes no sense on the wire; the mapper ignores unknown outbound controls rather than inventing a marker
        assertTrue(TimingObservationMapper.map(outbound(22, 32, 79, new PacketPayload.Control(PacketPayload.ControlKind.PONG, 1)), SELF, 0).isEmpty());
    }
    @Test void writeOutcomeAndServerTickMap() {
        var w = (TimingEvent.WriteResolved) map(new Observation(KEY, 5, 6, 0, new Observation.WriteOutcome(77, false)));
        assertEquals(77, w.wireOrdinal()); assertFalse(w.success());
        var t = (TimingEvent.ServerTick) map(new Observation(KEY, 6, 7, 0, new Observation.Tick(1234)));
        assertEquals(1234, t.serverTick());
    }
    @Test void correctionMapsToTeleportSentWithTheRelativeMask() {
        var c = new SemanticPayload.Correction(17, new PacketPayload.Position(1, 70, 1), new PacketPayload.Position(0.1, 0, -0.2), new PacketPayload.Rotation(30f, 5f), EnumSet.of(SemanticPayload.Relative.X, SemanticPayload.Relative.Y_ROT, SemanticPayload.Relative.DELTA_Y, SemanticPayload.Relative.ROTATE_DELTA));
        var ts = (TimingEvent.TeleportSent) map(outbound(30, 40, 81, c));
        assertEquals(17, ts.teleportId()); assertEquals(81, ts.wireOrdinal()); assertEquals(new Vector3(1, 70, 1), ts.position()); assertEquals(30f, ts.rotation().yaw()); assertEquals(new Vector3(0.1, 0, -0.2), ts.delta());
        assertTrue(ts.relative(TimingEvent.RELATIVE_X)); assertTrue(ts.relative(TimingEvent.RELATIVE_Y_ROT)); assertTrue(ts.relative(TimingEvent.RELATIVE_DELTA_Y)); assertTrue(ts.relative(TimingEvent.RELATIVE_ROTATE_DELTA));
        assertFalse(ts.relative(TimingEvent.RELATIVE_Y)); assertFalse(ts.relative(TimingEvent.RELATIVE_Z)); assertFalse(ts.relative(TimingEvent.RELATIVE_X_ROT)); assertFalse(ts.relative(TimingEvent.RELATIVE_DELTA_X)); assertFalse(ts.relative(TimingEvent.RELATIVE_DELTA_Z));
        var absolute = (TimingEvent.TeleportSent) map(outbound(31, 41, 82, new SemanticPayload.Correction(18, new PacketPayload.Position(1, 70, 1), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), Set.of())));
        assertEquals(0, absolute.relativeMask());
    }
    /** P7-F1 (real-client qualification): another entity's velocity packet is not this player's influence. */
    @Test void velocityIsAttributedOnlyToTheSessionsOwnEntity() {
        var self = new PacketPayload.Velocity(SELF, new PacketPayload.Position(0.2, 0.4, 0));
        var other = new PacketPayload.Velocity(SELF + 1, new PacketPayload.Position(0.2, 0.4, 0));
        var m = (TimingEvent.ExternalMotionSent) map(outbound(40, 50, 90, self));
        assertEquals(TimingEvent.Operation.SET, m.operation()); assertEquals("velocity", m.source()); assertEquals(90, m.wireOrdinal()); assertEquals("m90", m.id());
        // P7-F8: the influence is the vector the client decodes from the low-precision codec (15 data bits over an integer scale), not the server's doubles
        double[] q = me.nicolas.papea2ac.version.LowPrecisionVector.quantize(0.2, 0.4, 0);
        assertEquals(new Vector3(q[0], q[1], q[2]), m.vector()); assertNotEquals(0.2, m.vector().x()); assertEquals(0.2, m.vector().x(), 1e-4);
        double[] live = me.nicolas.papea2ac.version.LowPrecisionVector.quantize(0.3, 0.5, 0.2); // the qualification vector: residuals +6.1e-6, +3.05e-5, +2.44e-5 seen on the real client
        assertEquals(0.3000061, live[0], 1e-7); assertEquals(0.5000305, live[1], 1e-7); assertEquals(0.2000244, live[2], 1e-7);
        assertArrayEquals(new double[] { 0, 0, 0 }, me.nicolas.papea2ac.version.LowPrecisionVector.quantize(1e-5, -2e-5, 0), "below the codec's minimum: the zero vector");
        assertArrayEquals(new double[] { 0, 0, 0 }, me.nicolas.papea2ac.version.LowPrecisionVector.quantize(Double.NaN, 0, 0));
        var timeline = (PlayerObservation.ExternalMotionObserved) PlayerObservationMapper.map(outbound(46, 56, 96, self), SELF).orElseThrow();
        assertEquals(m.vector(), timeline.vector(), "the timeline sees the same decoded vector");
        assertTrue(TimingObservationMapper.map(outbound(41, 51, 91, other), SELF, 0).isEmpty(), "another entity's motion");
        assertTrue(TimingObservationMapper.map(outbound(42, 52, 92, self), -1, 0).isEmpty(), "unknown self: the timing layer attributes nothing (counted by the engine)");
        // the Phase 2 timeline mapper applies the same rule with a known self id and keeps its old behaviour without one
        assertTrue(PlayerObservationMapper.map(outbound(43, 53, 93, other), SELF).isEmpty());
        assertInstanceOf(PlayerObservation.ExternalMotionObserved.class, PlayerObservationMapper.map(outbound(44, 54, 94, self), SELF).orElseThrow());
        assertInstanceOf(PlayerObservation.ExternalMotionObserved.class, PlayerObservationMapper.map(outbound(45, 55, 95, other)).orElseThrow(), "no self identity: pre-P7-F1 behaviour kept for identity-less callers");
    }
    @Test void explosionKnockbackIsAnAdditiveInfluenceAndRespawnIsADiscontinuity() {
        var ex = (TimingEvent.ExternalMotionSent) map(outbound(50, 60, 100, new PacketPayload.Explosion(new PacketPayload.Position(0, 0, 0), 3f, 5, Optional.of(new PacketPayload.Position(0.5, 0.7, -0.1)))));
        assertEquals(TimingEvent.Operation.ADD, ex.operation()); assertEquals("explosion", ex.source()); assertEquals(new Vector3(0.5, 0.7, -0.1), ex.vector());
        assertTrue(TimingObservationMapper.map(outbound(51, 61, 101, new PacketPayload.Explosion(new PacketPayload.Position(0, 0, 0), 3f, 5, Optional.empty())), SELF, 0).isEmpty());
        var d = (TimingEvent.Discontinuity) map(outbound(52, 62, 102, new SemanticPayload.Respawn("minecraft:the_nether", (byte) 0, 0, 256)));
        assertEquals(5, d.newWorldEpoch());
    }
    @Test void administrativeAndOutcomeObservationsAreNeverTimingEvents() {
        assertTrue(TimingObservationMapper.map(new Observation(KEY, 1, 0, 0, new Observation.ControlChange("SELF_ENTITY", "42")), SELF, 0).isEmpty());
        assertTrue(TimingObservationMapper.map(new Observation(KEY, 2, 0, 0, new Observation.CheckpointMarker(1)), SELF, 0).isEmpty());
        var mv = new PacketPayload.Movement(new PacketPayload.Position(4, 5, 6), null, true, false);
        assertTrue(TimingObservationMapper.map(new Observation(KEY, 3, 0, 0, new Observation.Outcome(1, 1, 1, Observation.Result.APPLIED, mv, PayloadDigest.of(mv))), SELF, 0).isEmpty(), "outcomes are Phase 1/2 facts, not timing evidence");
    }
}
