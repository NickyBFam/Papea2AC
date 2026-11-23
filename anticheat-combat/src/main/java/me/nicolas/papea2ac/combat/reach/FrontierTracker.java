package me.nicolas.papea2ac.combat.reach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import me.nicolas.papea2ac.player.TimingEvent;

/**
 * Incremental form of {@link ApplicationFrontier.Index} (finding perf-to-phase12-01): fed every event the session's timing ledger accepted,
 * in order, each with its acceptance ordinal (0, 1, 2, …). The ledger's ring holds a suffix of those events: every event whose ordinal is at
 * least {@code oldestOrdinal} (the ring only evicts from the front). Queries with that bound answer exactly as {@link ApplicationFrontier.Index}
 * built from the ring's events: a PING whose send left the ring has no answer in the ring's pass, so its pairs are dropped; matching otherwise
 * only looks at events before the answer, which this pass and the ring's share. Owner thread only; memory is bounded by the ring (entries older
 * than the oldest retained ordinal are trimmed).
 */
public final class FrontierTracker {
    private record Sent(long sequence, long ordinal) {}
    private final HashMap<Long, Sent> sentAt = new HashMap<>();
    private final java.util.ArrayDeque<long[]> sentOrder = new java.util.ArrayDeque<>(); // {id, ordinal}, oldest first, for trimming
    // answered pairs in answer order: marker sequence, marker ordinal, pong sequence, pong ordinal
    private long[] mSeq = new long[16], mOrd = new long[16], pSeq = new long[16], pOrd = new long[16]; private int aHead, aEnd;
    // client tick ends in order: sequence, ordinal
    private long[] tSeq = new long[64], tOrd = new long[64]; private int tHead, tEnd;

    /** One accepted event, at its acceptance ordinal (strictly increasing). */
    public void accepted(TimingEvent e, long ordinal) {
        if (e instanceof TimingEvent.MarkerSent s && s.kind() == TimingEvent.MarkerKind.PING) { sentAt.put(s.id(), new Sent(s.sequence(), ordinal)); sentOrder.addLast(new long[]{s.id(), ordinal}); }
        else if (e instanceof TimingEvent.MarkerAcknowledged a && a.kind() == TimingEvent.MarkerKind.PING) {
            Sent m = sentAt.remove(a.id());
            if (m != null && m.sequence() < a.sequence()) {
                if (aEnd == mSeq.length) { compactAnswers(); if (aEnd == mSeq.length) { int n = mSeq.length * 2; mSeq = Arrays.copyOf(mSeq, n); mOrd = Arrays.copyOf(mOrd, n); pSeq = Arrays.copyOf(pSeq, n); pOrd = Arrays.copyOf(pOrd, n); } }
                mSeq[aEnd] = m.sequence(); mOrd[aEnd] = m.ordinal(); pSeq[aEnd] = a.sequence(); pOrd[aEnd] = ordinal; aEnd++;
            }
        } else if (e instanceof TimingEvent.ClientTickEnd) {
            if (tEnd == tSeq.length) { compactTicks(); if (tEnd == tSeq.length) { int n = tSeq.length * 2; tSeq = Arrays.copyOf(tSeq, n); tOrd = Arrays.copyOf(tOrd, n); } }
            tSeq[tEnd] = e.sequence(); tOrd[tEnd] = ordinal; tEnd++;
        }
    }

    /** Forgets everything older than the ring's oldest retained ordinal (they can never be in the ring again). */
    public void trim(long oldestOrdinal) {
        while (!sentOrder.isEmpty() && sentOrder.peekFirst()[1] < oldestOrdinal) { long[] s = sentOrder.pollFirst(); Sent cur = sentAt.get(s[0]); if (cur != null && cur.ordinal() == s[1]) sentAt.remove(s[0]); }
        while (aHead < aEnd && pOrd[aHead] < oldestOrdinal) aHead++; // its marker is older still
        while (tHead < tEnd && tOrd[tHead] < oldestOrdinal) tHead++;
    }

    public void clear() { sentAt.clear(); sentOrder.clear(); aHead = aEnd = 0; tHead = tEnd = 0; }

    /** As {@link ApplicationFrontier.Index#all}, over the ring's events (ordinal ≥ {@code oldestOrdinal}). */
    public List<ApplicationFrontier> all(long from, long attackSequence, long oldestOrdinal) {
        int end = firstAbove(pSeq, aHead, aEnd, attackSequence - 1);
        List<long[]> answered = new ArrayList<>(end - aHead);
        for (int i = aHead; i < end; i++) if (mOrd[i] >= oldestOrdinal && mSeq[i] >= from) answered.add(new long[]{mSeq[i], pSeq[i]});
        answered.sort((x, y) -> Long.compare(x[0], y[0]));
        int t0 = firstOrdinalAtLeast(oldestOrdinal), ticksBefore = firstAbove(tSeq, t0, tEnd, attackSequence - 1);
        List<ApplicationFrontier> out = new ArrayList<>(answered.size());
        for (long[] a : answered) out.add(new ApplicationFrontier(ApplicationFrontier.Status.MARKER_PROVEN, a[0], a[1], ticksBefore - firstAbove(tSeq, t0, tEnd, a[1])));
        return out;
    }

    /** As {@link ApplicationFrontier.Index#newest}. */
    public ApplicationFrontier newest(long from, long attackSequence, long oldestOrdinal) {
        int end = firstAbove(pSeq, aHead, aEnd, attackSequence - 1); long bestMarker = -1, bestPong = -1;
        for (int i = aHead; i < end; i++) if (mOrd[i] >= oldestOrdinal && mSeq[i] >= from && mSeq[i] > bestMarker) { bestMarker = mSeq[i]; bestPong = pSeq[i]; }
        if (bestMarker < 0) return ApplicationFrontier.NONE;
        int t0 = firstOrdinalAtLeast(oldestOrdinal);
        return new ApplicationFrontier(ApplicationFrontier.Status.MARKER_PROVEN, bestMarker, bestPong, firstAbove(tSeq, t0, tEnd, attackSequence - 1) - firstAbove(tSeq, t0, tEnd, bestPong));
    }

    /** As {@link ApplicationFrontier.Index#tickEndsBetween}. */
    public int tickEndsBetween(long from, long after, long before, long oldestOrdinal) {
        int t0 = firstOrdinalAtLeast(oldestOrdinal);
        return Math.max(0, firstAbove(tSeq, t0, tEnd, before - 1) - firstAbove(tSeq, t0, tEnd, Math.max(after, from - 1)));
    }

    private int firstOrdinalAtLeast(long ordinal) {
        int lo = tHead, hi = tEnd;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (tOrd[mid] >= ordinal) hi = mid; else lo = mid + 1; }
        return lo;
    }
    private static int firstAbove(long[] values, int lo, int hi, long v) {
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (values[mid] > v) hi = mid; else lo = mid + 1; }
        return lo;
    }
    private void compactAnswers() {
        if (aHead == 0) return; int n = aEnd - aHead;
        System.arraycopy(mSeq, aHead, mSeq, 0, n); System.arraycopy(mOrd, aHead, mOrd, 0, n); System.arraycopy(pSeq, aHead, pSeq, 0, n); System.arraycopy(pOrd, aHead, pOrd, 0, n);
        aHead = 0; aEnd = n;
    }
    private void compactTicks() {
        if (tHead == 0) return; int n = tEnd - tHead;
        System.arraycopy(tSeq, tHead, tSeq, 0, n); System.arraycopy(tOrd, tHead, tOrd, 0, n);
        tHead = 0; tEnd = n;
    }
}
