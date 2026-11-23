package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.player.TickCadenceEvent;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import java.util.ArrayList;
import java.util.List;
/**
 * The DAG-mandated translation of Phase 1 observations into the player-owned Phase 14 {@link TickCadenceEvent} vocabulary (player cannot see
 * protocol types). It reads only fields the normalizer already produced and carries the observation's own capture sequence and
 * capture-monotonic nanos. Inbound: the client tick end, every player movement packet, the pong. Outbound: every ping (whoever issued it),
 * failed writes, the ticking state, and the packets whose client handler answers with one movement packet outside the client tick - the
 * position correction, the rotation correction and an entity teleport (counted for every entity: the client answers only for its removed
 * vehicle, so counting all of them can only enlarge the allowance). Bundles are opened; their children keep the bundle's wire ordinal.
 */
public final class TickCadenceObservationMapper {
    private TickCadenceObservationMapper() {}
    public static List<TickCadenceEvent> map(Observation o) {
        long seq = o.sequence(), nanos = o.capturedNanos();
        List<TickCadenceEvent> out = new ArrayList<>(1);
        switch (o.event()) {
            case Observation.Attempt a -> inbound(a.payload(), seq, nanos, out);
            case Observation.Outbound ob -> outbound(ob.wireOrdinal(), ob.payload(), seq, nanos, out);
            case Observation.WriteOutcome w -> { if (!w.success()) out.add(new TickCadenceEvent.WriteFailed(seq, nanos, w.wireOrdinal())); }
            case Observation.Outcome ignored -> { }
            case Observation.Tick ignored -> { }
            case Observation.ControlChange ignored -> { }
            case Observation.CheckpointMarker ignored -> { }
        }
        return out;
    }
    private static void inbound(PacketPayload p, long seq, long nanos, List<TickCadenceEvent> out) {
        if (p instanceof PacketPayload.Movement) out.add(new TickCadenceEvent.Movement(seq, nanos));
        else if (p instanceof PacketPayload.Control c) {
            if (c.kind() == PacketPayload.ControlKind.TICK_END) out.add(new TickCadenceEvent.TickEnd(seq, nanos));
            else if (c.kind() == PacketPayload.ControlKind.PONG) out.add(new TickCadenceEvent.PingAnswered(seq, nanos, c.value()));
        }
    }
    private static void outbound(long wire, PacketPayload p, long seq, long nanos, List<TickCadenceEvent> out) {
        switch (p) {
            case PacketPayload.Bundle b -> { for (PacketPayload child : b.children()) outbound(wire, child, seq, nanos, out); }
            case PacketPayload.Control c when c.kind() == PacketPayload.ControlKind.PING -> out.add(new TickCadenceEvent.PingSent(seq, nanos, c.value(), wire));
            case PacketPayload.TickRate t -> out.add(new TickCadenceEvent.TickRate(seq, nanos, wire, t.rate(), t.frozen()));
            case SemanticPayload.Correction c -> out.add(new TickCadenceEvent.InducedSent(seq, nanos, wire, TickCadenceEvent.Induced.CORRECTION));
            case SemanticPayload.RotationCorrection r -> out.add(new TickCadenceEvent.InducedSent(seq, nanos, wire, TickCadenceEvent.Induced.ROTATION));
            case SemanticPayload.EntityPosition e -> out.add(new TickCadenceEvent.InducedSent(seq, nanos, wire, TickCadenceEvent.Induced.ENTITY_TELEPORT));
            default -> { }
        }
    }
}
