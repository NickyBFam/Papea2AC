package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import java.util.Optional;
/**
 * The DAG-mandated translation of Phase 1 observations into the player-owned Phase 7 {@link TimingEvent} vocabulary (the same
 * role PlayerObservationMapper plays for the timeline; player cannot see protocol types). It reads only fields the normalizer
 * already produced and carries the observation's own capture sequence and capture-monotonic nanos - never a processing
 * timestamp, never wall time. Velocity is attributed to the player only when its entity id is the session's own
 * (P7-F1: another entity's motion packet is not this player's influence); an unknown self id attributes nothing.
 */
public final class TimingObservationMapper {
    private TimingObservationMapper() {}
    /** ACK inventory of the pinned play protocol as Phase 1 normalizes it: keep-alive (long id) and ping/pong (int id) answered from the client's network thread; teleport confirm from its game thread; tick end once per client tick. */
    public static Optional<TimingEvent> map(Observation o, int selfEntityId, long worldEpochAfter) {
        long seq = o.sequence(), nanos = o.capturedNanos();
        return switch (o.event()) {
            case Observation.Attempt a -> inbound(a.payload(), seq, nanos);
            case Observation.Outbound out -> outbound(out.wireOrdinal(), out.payload(), seq, nanos, selfEntityId, worldEpochAfter);
            case Observation.WriteOutcome w -> Optional.of(new TimingEvent.WriteResolved(seq, nanos, w.wireOrdinal(), w.success()));
            case Observation.Tick t -> Optional.of(new TimingEvent.ServerTick(seq, nanos, t.serverTick()));
            case Observation.Outcome ignored -> Optional.empty();
            case Observation.ControlChange ignored -> Optional.empty();
            case Observation.CheckpointMarker ignored -> Optional.empty();
        };
    }
    private static Optional<TimingEvent> inbound(PacketPayload p, long seq, long nanos) {
        if (p instanceof PacketPayload.Movement m) {
            boolean hasPosition = m.position() != null, hasRotation = m.rotation() != null;
            return Optional.of(new TimingEvent.MovementObserved(seq, nanos, hasPosition, hasPosition ? vector(m.position()) : Vector3.ZERO, hasRotation, hasRotation ? rotation(m.rotation()) : new Rotation(0f, 0f), m.onGround()));
        }
        if (p instanceof PacketPayload.Control c) return switch (c.kind()) {
            case TICK_END -> Optional.of(new TimingEvent.ClientTickEnd(seq, nanos));
            case KEEPALIVE -> Optional.of(new TimingEvent.MarkerAcknowledged(seq, nanos, TimingEvent.MarkerKind.KEEPALIVE, c.value()));
            case PONG -> Optional.of(new TimingEvent.MarkerAcknowledged(seq, nanos, TimingEvent.MarkerKind.PING, c.value()));
            case TELEPORT_CONFIRM -> Optional.of(new TimingEvent.TeleportConfirmed(seq, nanos, c.value()));
            default -> Optional.empty();
        };
        if (p instanceof SemanticPayload.Attack a) return Optional.of(new TimingEvent.LocalAttack(seq, nanos, a.entityId())); // P11-F27: attacker slowdown branch
        return Optional.empty();
    }
    private static Optional<TimingEvent> outbound(long wire, PacketPayload p, long seq, long nanos, int self, long worldEpochAfter) {
        if (p instanceof PacketPayload.Control c) return switch (c.kind()) {
            case KEEPALIVE -> Optional.of(new TimingEvent.MarkerSent(seq, nanos, TimingEvent.MarkerKind.KEEPALIVE, c.value(), wire));
            case PING -> Optional.of(new TimingEvent.MarkerSent(seq, nanos, TimingEvent.MarkerKind.PING, c.value(), wire));
            default -> Optional.empty();
        };
        if (p instanceof SemanticPayload.Correction c) return Optional.of(new TimingEvent.TeleportSent(seq, nanos, c.teleportId(), wire, vector(c.position()), rotation(c.rotation()), vector(c.delta()), relativeMask(c)));
        // The velocity packet travels through the low-precision vector codec: the client applies the DECODED vector (P7-F8), so that is the influence.
        if (p instanceof PacketPayload.Velocity v) return self >= 0 && v.entityId() == self ? Optional.of(new TimingEvent.ExternalMotionSent(seq, nanos, wire, TimingEvent.Operation.SET, lowPrecision(v.vector()), "velocity")) : Optional.empty();
        if (p instanceof PacketPayload.Explosion ex && ex.playerKnockback().isPresent()) return Optional.of(new TimingEvent.ExternalMotionSent(seq, nanos, wire, TimingEvent.Operation.ADD, vector(ex.playerKnockback().get()), "explosion"));
        if (p instanceof SemanticPayload.Respawn) return Optional.of(new TimingEvent.Discontinuity(seq, nanos, worldEpochAfter));
        return Optional.empty();
    }
    static int relativeMask(SemanticPayload.Correction c) {
        int m = 0;
        for (SemanticPayload.Relative r : c.relative()) m |= switch (r) {
            case X -> TimingEvent.RELATIVE_X; case Y -> TimingEvent.RELATIVE_Y; case Z -> TimingEvent.RELATIVE_Z; case Y_ROT -> TimingEvent.RELATIVE_Y_ROT; case X_ROT -> TimingEvent.RELATIVE_X_ROT;
            case DELTA_X -> TimingEvent.RELATIVE_DELTA_X; case DELTA_Y -> TimingEvent.RELATIVE_DELTA_Y; case DELTA_Z -> TimingEvent.RELATIVE_DELTA_Z; case ROTATE_DELTA -> TimingEvent.RELATIVE_ROTATE_DELTA;
        };
        return m;
    }
    private static Vector3 vector(PacketPayload.Position p) { return new Vector3(p.x(), p.y(), p.z()); }
    static Vector3 lowPrecision(PacketPayload.Position p) { double[] q = me.nicolas.papea2ac.version.LowPrecisionVector.quantize(p.x(), p.y(), p.z()); return new Vector3(q[0], q[1], q[2]); }
    private static Rotation rotation(PacketPayload.Rotation r) { return new Rotation(r.yaw(), r.pitch()); }
}
