package me.nicolas.papea2ac.player;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.OptionalLong;
/**
 * Bounded outstanding-marker ledger (LATENCY_COMPENSATION.md "ACK handling state machine": CREATED/SENT -> ACK_OBSERVED ->
 * RECEIPT_RESOLVED, with WRITE_FAILED and EXPIRED as terminal uncertainty paths). One instance per session, so a marker id of
 * an earlier connection can never resolve here (session replacement is structural). Bounds: {@value #MAX_PENDING} pending
 * markers (the oldest is retired as EXPIRED when a newer one needs the slot), {@value #MAX_TOMBSTONES} answered-marker
 * tombstones for duplicate detection, {@value #EXPIRY_NANOS} of pending life. Expiry means "evidence unavailable", never
 * "the client did not receive it": an answer after expiry is counted as LATE and changes nothing.
 */
public final class MarkerLedger {
    public static final int MAX_PENDING = 32, MAX_TOMBSTONES = 64;
    public static final long EXPIRY_NANOS = 10_000_000_000L;

    public record Pending(TimingEvent.MarkerKind kind, long id, long sentSequence, long sentNanos, long wireOrdinal, boolean writeKnown, boolean writeSuccess) {
        Pending resolvedWrite(boolean success) { return new Pending(kind, id, sentSequence, sentNanos, wireOrdinal, true, success); }
    }
    private record Tombstone(TimingEvent.MarkerKind kind, long id) {}
    /** The outcome of an acknowledgement: which marker it answered (or why it answered nothing) and the round-trip sample it yields. */
    public record Answer(Outcome outcome, long sentSequence, long sentNanos, long wireOrdinal, OptionalLong rttNanos) {
        public enum Outcome { RESOLVED, RESOLVED_WRITE_UNKNOWN, DUPLICATE, LATE_OR_UNKNOWN }
        static final Answer DUPLICATE = new Answer(Outcome.DUPLICATE, 0, 0, 0, OptionalLong.empty()), UNKNOWN = new Answer(Outcome.LATE_OR_UNKNOWN, 0, 0, 0, OptionalLong.empty());
    }
    public record Summary(int pending, long sent, long acknowledged, long expired, long failedWrites, long duplicates, long lateOrUnknown, long evictedForCapacity) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0, 0);
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Tombstone> tombstones = new ArrayDeque<>();
    private long sent, acknowledged, expired, failedWrites, duplicates, lateOrUnknown, evictedForCapacity;

    public void sent(TimingEvent.MarkerKind kind, long id, long sequence, long nanos, long wireOrdinal) {
        expire(nanos);
        if (pending.size() >= MAX_PENDING) { pending.removeFirst(); expired++; evictedForCapacity++; }
        pending.addLast(new Pending(kind, id, sequence, nanos, wireOrdinal, false, false)); sent++;
    }
    /** @return whether a pending marker was written with this wire ordinal (the only outbound writes whose outcome is marker evidence). */
    public boolean tracksWire(long wireOrdinal) { for (Pending p : pending) if (p.wireOrdinal() == wireOrdinal) return true; return false; }
    /** A write callback for a wire ordinal; a failed write retires the marker (WRITE_FAILED): its answer can never be a valid sample. */
    public void writeResolved(long wireOrdinal, boolean success) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
            Pending p = it.next();
            if (p.wireOrdinal() != wireOrdinal) continue;
            if (success) { replace(p, p.resolvedWrite(true)); }
            else { it.remove(); failedWrites++; }
            return;
        }
    }
    private void replace(Pending old, Pending updated) {
        ArrayDeque<Pending> copy = new ArrayDeque<>(pending.size());
        for (Pending p : pending) copy.addLast(p == old ? updated : p);
        pending.clear(); pending.addAll(copy);
    }
    /** An acknowledgement: resolves the matching pending marker (receipt evidence + RTT sample), else DUPLICATE / LATE_OR_UNKNOWN. */
    public Answer acknowledged(TimingEvent.MarkerKind kind, long id, long nanos) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
            Pending p = it.next();
            if (p.kind() != kind || p.id() != id) continue;
            it.remove(); acknowledged++; tombstone(kind, id);
            long rtt = nanos - p.sentNanos();
            return new Answer(p.writeKnown() ? Answer.Outcome.RESOLVED : Answer.Outcome.RESOLVED_WRITE_UNKNOWN, p.sentSequence(), p.sentNanos(), p.wireOrdinal(), rtt > 0 ? OptionalLong.of(rtt) : OptionalLong.empty());
        }
        for (Tombstone t : tombstones) if (t.kind() == kind && t.id() == id) { duplicates++; return Answer.DUPLICATE; }
        lateOrUnknown++; return Answer.UNKNOWN;
    }
    private void tombstone(TimingEvent.MarkerKind kind, long id) { if (tombstones.size() >= MAX_TOMBSTONES) tombstones.removeFirst(); tombstones.addLast(new Tombstone(kind, id)); }
    /** Retires pending markers older than the expiry (EXPIRED = evidence unavailable). */
    public void expire(long nowNanos) {
        while (!pending.isEmpty() && nowNanos - pending.peekFirst().sentNanos() >= EXPIRY_NANOS) { Pending p = pending.removeFirst(); expired++; tombstone(p.kind(), p.id()); }
    }
    public int pendingCount() { return pending.size(); }
    /** Oldest-first copy of the pending markers (bounded by MAX_PENDING). */
    public java.util.List<Pending> pending() { return java.util.List.copyOf(pending); }
    public Summary summary() { return new Summary(pending.size(), sent, acknowledged, expired, failedWrites, duplicates, lateOrUnknown, evictedForCapacity); }
}
