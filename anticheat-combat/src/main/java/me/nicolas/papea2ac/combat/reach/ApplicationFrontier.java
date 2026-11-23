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
     * One pass over the retained timing events, answering {@link #of}, {@link #all} and tick-end counts for any later attack by binary search
     * (finding perf-to-phase12-01: the per-attack rescans dominated the attack cost). For events in sequence order,
     * {@code index(events).all(from, s)} equals {@code all(slice, s)} and {@code newest(from, s)} equals {@code of(slice, s)}, where
     * {@code slice} is the events with sequence ≥ {@code from}: a PING sent before {@code from} is not in the slice, so no answer to it counts;
     * matching otherwise only looks at events before the answer, which the slice and the whole list share.
     */
    public static Index index(List<TimingEvent> events) {
        int pings = 0; for (TimingEvent e : events) if (e instanceof TimingEvent.MarkerSent s && s.kind() == TimingEvent.MarkerKind.PING) pings++;
        IdMap sentAt = new IdMap(pings); // inserts are PING sends only
        long[] markers = new long[8], pongs = new long[8], ticks = new long[64]; int n = 0, t = 0;
        for (TimingEvent e : events) {
            if (e instanceof TimingEvent.MarkerSent s && s.kind() == TimingEvent.MarkerKind.PING) sentAt.put(s.id(), s.sequence());
            else if (e instanceof TimingEvent.MarkerAcknowledged a && a.kind() == TimingEvent.MarkerKind.PING) {
                long m = sentAt.remove(a.id());
                if (m != IdMap.ABSENT && m < a.sequence()) {
                    if (n == markers.length) { markers = java.util.Arrays.copyOf(markers, n * 2); pongs = java.util.Arrays.copyOf(pongs, n * 2); }
                    markers[n] = m; pongs[n] = a.sequence(); n++;
                }
            } else if (e instanceof TimingEvent.ClientTickEnd) {
                if (t == ticks.length) ticks = java.util.Arrays.copyOf(ticks, t * 2);
                ticks[t++] = e.sequence();
            }
        }
        return new Index(java.util.Arrays.copyOf(markers, n), java.util.Arrays.copyOf(pongs, n), java.util.Arrays.copyOf(ticks, t));
    }

    /**
     * PING id → marker capture sequence without boxing (the same put / remove semantics as the HashMap of {@link #all}). Open addressing, linear
     * probing, tombstones; sized for at most {@code maxInserts} inserts so the table never fills past half (no rehash).
     */
    static final class IdMap {
        static final long ABSENT = Long.MIN_VALUE;
        private static final byte EMPTY = 0, FULL = 1, DELETED = 2;
        private final long[] keys, values; private final byte[] state; private final int mask;
        IdMap(int maxInserts) {
            int cap = 16; while (cap < 2 * Math.max(1, maxInserts)) cap <<= 1;
            keys = new long[cap]; values = new long[cap]; state = new byte[cap]; mask = cap - 1;
        }
        private int slot(long k) { long h = k * 0x9E3779B97F4A7C15L; return (int) (h ^ (h >>> 32)) & mask; }
        void put(long k, long v) {
            int i = slot(k), firstFree = -1;
            while (state[i] != EMPTY) {
                if (state[i] == FULL && keys[i] == k) { values[i] = v; return; }
                if (state[i] == DELETED && firstFree < 0) firstFree = i;
                i = (i + 1) & mask;
            }
            if (firstFree < 0) firstFree = i;
            keys[firstFree] = k; values[firstFree] = v; state[firstFree] = FULL;
        }
        long remove(long k) {
            int i = slot(k);
            while (state[i] != EMPTY) {
                if (state[i] == FULL && keys[i] == k) { state[i] = DELETED; return values[i]; }
                i = (i + 1) & mask;
            }
            return ABSENT;
        }
    }

    /** Answered PINGs in answer order ({@code markers[i]} answered at {@code pongs[i]}, ascending) and client tick ends (ascending). */
    public record Index(long[] markers, long[] pongs, long[] tickEnds) {
        /** Same as {@link ApplicationFrontier#all} over the events with sequence in [{@code from}, {@code attackSequence}). */
        public List<ApplicationFrontier> all(long from, long attackSequence) {
            int end = firstAbove(pongs, attackSequence - 1); // answers before the attack
            List<long[]> answered = new java.util.ArrayList<>(end);
            for (int i = 0; i < end; i++) if (markers[i] >= from) answered.add(new long[]{markers[i], pongs[i]});
            answered.sort((x, y) -> Long.compare(x[0], y[0]));
            int ticksBefore = firstAbove(tickEnds, attackSequence - 1);
            List<ApplicationFrontier> out = new java.util.ArrayList<>(answered.size());
            for (long[] a : answered) out.add(new ApplicationFrontier(Status.MARKER_PROVEN, a[0], a[1], ticksBefore - firstAbove(tickEnds, a[1])));
            return out;
        }
        /** Same as {@link ApplicationFrontier#of} over the events with sequence in [{@code from}, {@code attackSequence}). */
        public ApplicationFrontier newest(long from, long attackSequence) {
            int end = firstAbove(pongs, attackSequence - 1); long bestMarker = -1, bestPong = -1;
            for (int i = 0; i < end; i++) if (markers[i] >= from && markers[i] > bestMarker) { bestMarker = markers[i]; bestPong = pongs[i]; }
            if (bestMarker < 0) return NONE;
            return new ApplicationFrontier(Status.MARKER_PROVEN, bestMarker, bestPong, firstAbove(tickEnds, attackSequence - 1) - firstAbove(tickEnds, bestPong));
        }
        /** Client tick ends with sequence in [{@code from}, {@code before}) and above {@code after}. */
        public int tickEndsBetween(long from, long after, long before) {
            return Math.max(0, firstAbove(tickEnds, before - 1) - firstAbove(tickEnds, Math.max(after, from - 1)));
        }
        /** Index of the first value strictly greater than {@code v} (values ascending). */
        static int firstAbove(long[] values, long v) {
            int lo = 0, hi = values.length;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (values[mid] > v) hi = mid; else lo = mid + 1; }
            return lo;
        }
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
