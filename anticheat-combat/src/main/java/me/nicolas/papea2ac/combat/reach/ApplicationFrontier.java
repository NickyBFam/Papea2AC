package me.nicolas.papea2ac.combat.reach;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nicolas.papea2ac.player.TimingEvent;

/**
 * The newest point in the clientbound stream the client has provably APPLIED before it sent an attack (PHASE_12 R5, mechanics M5).
 *
 * <p>Proof: the 26.2 client handles every clientbound play packet on its main thread in arrival order (one FIFO {@code PacketProcessor}
 * queue), and answers a {@code ClientboundPingPacket} with {@code ServerboundPongPacket} from that handler. The server writes outbound packets
 * on the channel's event loop in capture order (THREADING_MODEL; Phase 7). So when the serverbound stream carries the pong of a PING marker
 * before the attack, every outbound packet captured before that marker was handled by the client before it sent the pong, hence before the
 * attack. Keep-alives are answered off the main thread and prove nothing here. The client ticks completed between the pong and the attack (one
 * {@code ServerboundClientTickEnd} each) are a lower bound on the entity interpolation steps applied since.
 *
 * <p>Pure: computed from the ordered timing events of the session (the ledger ring), never from wall time or RTT.
 */
public record ApplicationFrontier(Status status, long markerSequence, long pongSequence, int ticksSincePong) {
    public enum Status {
        /** A PING marker sent at {@code markerSequence} was answered at {@code pongSequence} before the attack. */
        MARKER_PROVEN,
        /** No answered PING marker in the retained events precedes the attack: only the history horizon bounds the scene window. */
        HORIZON_ONLY
    }
    public static final ApplicationFrontier NONE = new ApplicationFrontier(Status.HORIZON_ONLY, -1, -1, 0);

    /** Every clientbound packet captured strictly before {@link #markerSequence} is applied by the client before the attack. */
    public boolean proves(long outboundSequence) { return status == Status.MARKER_PROVEN && outboundSequence < markerSequence; }

    /**
     * Every answered PING before the attack (oldest marker first), each with the client ticks completed between its pong and the attack. Each is
     * an independent proof (R5); together they bound the weight of every older target position (TargetHull.nested).
     */
    public static List<ApplicationFrontier> all(List<TimingEvent> events, long attackSequence) {
        Map<Long, Long> sentAt = new HashMap<>();
        List<long[]> answered = new java.util.ArrayList<>(); // {markerSequence, pongSequence}
        for (TimingEvent e : events) {
            if (e.sequence() >= attackSequence) break;
            if (e instanceof TimingEvent.MarkerSent s && s.kind() == TimingEvent.MarkerKind.PING) sentAt.put(s.id(), s.sequence());
            else if (e instanceof TimingEvent.MarkerAcknowledged a && a.kind() == TimingEvent.MarkerKind.PING) {
                Long m = sentAt.remove(a.id());
                if (m != null && m < a.sequence()) answered.add(new long[]{m, a.sequence()});
            }
        }
        answered.sort((x, y) -> Long.compare(x[0], y[0]));
        // tick ends after each pong: one pass from the newest pong backwards over the tick ends before the attack
        List<Long> tickEnds = new java.util.ArrayList<>();
        for (TimingEvent e : events) { if (e.sequence() >= attackSequence) break; if (e instanceof TimingEvent.ClientTickEnd) tickEnds.add(e.sequence()); }
        List<ApplicationFrontier> out = new java.util.ArrayList<>(answered.size());
        for (long[] a : answered) {
            int lo = 0, hi = tickEnds.size(); // first tick end after the pong
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (tickEnds.get(mid) > a[1]) hi = mid; else lo = mid + 1; }
            out.add(new ApplicationFrontier(Status.MARKER_PROVEN, a[0], a[1], tickEnds.size() - lo));
        }
        return out;
    }

    /**
     * @param events the session's timing events in sequence order (any retained slice; older events only make the proof stronger)
     * @param attackSequence the attack's capture sequence
     */
    public static ApplicationFrontier of(List<TimingEvent> events, long attackSequence) {
        Map<Long, Long> sentAt = new HashMap<>(); // PING id -> marker capture sequence
        long bestMarker = -1, bestPong = -1;
        for (TimingEvent e : events) {
            if (e.sequence() >= attackSequence) break;
            if (e instanceof TimingEvent.MarkerSent s && s.kind() == TimingEvent.MarkerKind.PING) sentAt.put(s.id(), s.sequence());
            else if (e instanceof TimingEvent.MarkerAcknowledged a && a.kind() == TimingEvent.MarkerKind.PING) {
                Long m = sentAt.get(a.id());
                if (m != null && m < a.sequence() && m > bestMarker) { bestMarker = m; bestPong = a.sequence(); }
            }
        }
        if (bestMarker < 0) return NONE;
        int ticks = 0;
        for (TimingEvent e : events) {
            if (e.sequence() >= attackSequence) break;
            if (e.sequence() > bestPong && e instanceof TimingEvent.ClientTickEnd) ticks++;
        }
        return new ApplicationFrontier(Status.MARKER_PROVEN, bestMarker, bestPong, ticks);
    }
}
