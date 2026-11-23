package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TickCadenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TickCadenceEvent;
import me.nicolas.papea2ac.player.TickCadenceLedger;
import org.junit.jupiter.api.Test;
/** Phase 14 cadence ledger: fence correlation, counts frozen at the pong, conservative id matching, bounds, segments, ownership and isolation. */
class TickCadenceLedgerTest {
    long seq = 0;
    TickCadenceEvent.PingSent ping(long nanos, long id, long wire) { return new TickCadenceEvent.PingSent(++seq, nanos, id, wire); }
    TickCadenceEvent.PingAnswered pong(long nanos, long id) { return new TickCadenceEvent.PingAnswered(++seq, nanos, id); }
    TickCadenceEvent.TickEnd tick(long nanos) { return new TickCadenceEvent.TickEnd(++seq, nanos); }
    TickCadenceEvent.Movement move(long nanos) { return new TickCadenceEvent.Movement(++seq, nanos); }

    @Test void anAnchorFreezesTheCountsAtThePongAndTheContextOfItsPing() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, new TickCadenceEvent.InducedSent(++seq, 1, 1, TickCadenceEvent.Induced.CORRECTION));
        assertTrue(l.apply(KEY, ping(10 * MS, 7, 2)).isEmpty());
        l.apply(KEY, new TickCadenceEvent.InducedSent(++seq, 11 * MS, 3, TickCadenceEvent.Induced.ROTATION));
        l.apply(KEY, tick(20 * MS)); l.apply(KEY, move(21 * MS)); l.apply(KEY, tick(70 * MS));
        var a = l.apply(KEY, pong(80 * MS, 7)).orElseThrow();
        assertEquals(10 * MS, a.pingNanos()); assertEquals(80 * MS, a.answerNanos()); assertEquals(2, a.pingWireOrdinal()); assertEquals(1, a.inducedBeforePing(), "induced packets before the ping on the wire");
        assertEquals(2, a.tickEnds()); assertEquals(1, a.movements()); assertEquals(1, l.anchors().size());
        l.apply(KEY, tick(90 * MS));
        assertEquals(2, l.anchors().get(0).tickEnds(), "an anchor never changes after it is created");
        assertEquals(3, l.view().counters().tickEnds()); assertEquals(2, l.view().counters().induced());
    }

    @Test void aPongResolvesTheOldestPingWithItsIdAndUnknownOrDuplicatePongsCreateNothing() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, ping(1 * MS, 5, 1)); // a plugin's ping with the same id, sent first
        l.apply(KEY, ping(2 * MS, 5, 2));
        var first = l.apply(KEY, pong(50 * MS, 5)).orElseThrow();
        assertEquals(1 * MS, first.pingNanos(), "the oldest same-id ping: the window can only start earlier");
        var second = l.apply(KEY, pong(51 * MS, 5)).orElseThrow();
        assertEquals(2 * MS, second.pingNanos());
        assertTrue(l.apply(KEY, pong(52 * MS, 5)).isEmpty(), "a third answer has nothing left to resolve");
        assertTrue(l.apply(KEY, pong(53 * MS, 99)).isEmpty());
        assertEquals(2, l.view().counters().unknownAnswers()); assertEquals(2, l.view().counters().pingsAnswered());
    }

    @Test void aFailedPingWriteIsForgottenAndALatePongStillAnchors() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, ping(0, 1, 10)); l.apply(KEY, new TickCadenceEvent.WriteFailed(++seq, 1 * MS, 10));
        assertTrue(l.apply(KEY, pong(40 * MS, 1)).isEmpty(), "the client never received a failed ping");
        assertEquals(1, l.view().counters().failedPings());
        l.apply(KEY, ping(100 * MS, 2, 11));
        assertTrue(l.apply(KEY, pong(30 * S, 2)).isPresent(), "a pong 30 s late is still a fence (no time expiry for anchoring)");
    }

    @Test void pendingPingsAreBoundedByCapacityOnly() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        for (int i = 1; i <= TickCadenceLedger.MAX_PENDING_PINGS + 5; i++) l.apply(KEY, ping(i * MS, i, i));
        assertEquals(TickCadenceLedger.MAX_PENDING_PINGS, l.view().pendingPings()); assertEquals(5, l.view().counters().pingsEvictedForCapacity());
        assertTrue(l.apply(KEY, pong(S, 1)).isEmpty(), "the evicted oldest ping cannot be answered");
        assertTrue(l.apply(KEY, pong(S + 1, 6)).isPresent());
    }

    @Test void anchorsAreDenseThenThinnedThenExpiredAndNeverExceedTheirBound() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        long t = 0;
        for (int i = 1; i <= 20 * 700; i++) { t += 50 * MS; l.apply(KEY, ping(t, i, i)); l.apply(KEY, tick(t + 10 * MS)); l.apply(KEY, pong(t + 40 * MS, i)); } // 20 Hz for 700 s
        var anchors = l.anchors();
        assertTrue(anchors.size() <= TickCadenceLedger.DEFAULT_MAX_ANCHORS, "bounded: " + anchors.size());
        var last = anchors.get(anchors.size() - 1);
        assertTrue(last.answerNanos() - anchors.get(0).pingNanos() <= 600 * S, "never older than the window bound");
        for (int i = 1; i < anchors.size() - TickCadenceLedger.DEFAULT_RECENT_ANCHORS; i++) assertTrue(anchors.get(i).answerNanos() - anchors.get(i - 1).answerNanos() >= 2 * S, "sparse anchors at least 2 s apart");
        for (int i = anchors.size() - TickCadenceLedger.DEFAULT_RECENT_ANCHORS + 1; i < anchors.size(); i++) assertEquals(anchors.get(i - 1).pingNanos() + 50 * MS, anchors.get(i).pingNanos(), "the newest anchors are dense");
        assertTrue(l.view().counters().anchorsThinned() > 10_000); assertTrue(l.view().counters().anchorsExpired() > 0);
        assertTrue(anchors.get(0).answerNanos() < last.answerNanos() - 500 * S, "about ten minutes of history survive the thinning: " + (last.answerNanos() - anchors.get(0).answerNanos()) / 1e9);
    }

    @Test void aCoverageBreakStartsANewSegmentAndSharesItsObservationSequence() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, ping(0, 1, 1)); l.apply(KEY, pong(40 * MS, 1));
        long at = ++seq;
        l.apply(KEY, new TickCadenceEvent.CoverageBreak(at, 50 * MS, "GAP"));
        assertTrue(l.apply(KEY, new TickCadenceEvent.TickEnd(at, 50 * MS)).isEmpty(), "same sequence as the break: accepted");
        assertEquals(1, l.view().counters().tickEnds());
        assertEquals(1, l.view().segment()); assertEquals(0, l.anchors().size());
        l.apply(KEY, ping(S, 2, 2)); var a = l.apply(KEY, pong(S + 40 * MS, 2)).orElseThrow();
        assertEquals(1, a.segment());
    }

    @Test void staleSessionClosedAndOutOfOrderEventsChangeNothing() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, tick(MS)); l.apply(KEY, tick(2 * MS));
        assertTrue(l.apply(new SessionKey(new UUID(1, 2), 1), tick(3 * MS)).isEmpty());
        l.apply(KEY, new TickCadenceEvent.TickEnd(1, 4 * MS));
        assertEquals(2, l.view().counters().tickEnds(), "a decreasing sequence is rejected"); assertEquals(2, l.view().counters().rejected());
        l.close(); l.apply(KEY, tick(5 * MS));
        assertTrue(l.view().closed()); assertEquals(2, l.view().counters().tickEnds());
    }

    @Test void ownershipIsEnforcedAndTheViewIsReadableAnywhere() throws Exception {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, tick(MS));
        Throwable[] failure = new Throwable[1]; long[] seen = new long[1];
        Thread other = new Thread(() -> { seen[0] = l.view().counters().tickEnds(); try { l.apply(KEY, new TickCadenceEvent.TickEnd(99, 2 * MS)); } catch (Throwable t) { failure[0] = t; } });
        other.start(); other.join();
        assertEquals(1, seen[0]); assertInstanceOf(IllegalStateException.class, failure[0]);
    }

    static final TickCadenceLedger.RateState R20 = TickCadenceLedger.RateState.NOMINAL, R10 = new TickCadenceLedger.RateState(10f, false), F10 = new TickCadenceLedger.RateState(10f, true);
    TickCadenceEvent.TickRate rate(long nanos, long wire, TickCadenceLedger.RateState s) { return new TickCadenceEvent.TickRate(++seq, nanos, wire, s.rate(), s.frozen()); }

    @Test void theTickRateSignalSettlesAtTheFirstPongWhosePingFollowedTheChangeOnTheWire() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        assertEquals(TickCadenceLedger.TickRateSignal.UNOBSERVED, l.tickRateSignal(), "nothing observed yet: no rate may be assumed");
        l.apply(KEY, rate(MS, 1, R20)); // the join packet
        var joining = l.tickRateSignal();
        assertEquals(TickCadenceLedger.TickRateSignal.Status.TRANSITION, joining.status()); assertFalse(joining.complete(), "the state before the first packet is unknown");
        l.apply(KEY, ping(2 * MS, 1, 2)); l.apply(KEY, pong(50 * MS, 1));
        var settled = l.tickRateSignal();
        assertEquals(TickCadenceLedger.TickRateSignal.Status.SETTLED, settled.status()); assertEquals(java.util.List.of(R20), settled.candidates()); assertTrue(settled.complete()); assertEquals(seq, settled.settledSequence());
        // /tick rate 10: a ping sent BEFORE the change on the wire proves nothing about it
        l.apply(KEY, ping(S, 2, 3)); long change = (l.apply(KEY, rate(S + MS, 4, R10)).isEmpty() ? seq : -1);
        l.apply(KEY, pong(S + 40 * MS, 2));
        var transition = l.tickRateSignal();
        assertEquals(TickCadenceLedger.TickRateSignal.Status.TRANSITION, transition.status()); assertEquals(java.util.List.of(R20, R10), transition.candidates()); assertTrue(transition.complete()); assertEquals(change, transition.changeSequence());
        // the next ping (after the change) settles it
        l.apply(KEY, ping(2 * S, 3, 5)); l.apply(KEY, pong(2 * S + 40 * MS, 3));
        assertEquals(java.util.List.of(R10), l.tickRateSignal().candidates()); assertEquals(TickCadenceLedger.TickRateSignal.Status.SETTLED, l.tickRateSignal().status());
        // /tick freeze then a re-send of the same state
        l.apply(KEY, rate(3 * S, 6, F10)); l.apply(KEY, rate(3 * S + MS, 7, F10));
        assertEquals(java.util.List.of(R10, F10), l.tickRateSignal().candidates(), "consecutive identical states fold");
        assertEquals(3, l.view().counters().tickRateChanges(), "20 (join), 10, frozen; the re-send is not a change");
    }

    @Test void moreUnsettledChangesThanTheBoundOverflowAndACoverageBreakForgetsTheRate() {
        TickCadenceLedger l = new TickCadenceLedger(KEY, 600 * S);
        l.apply(KEY, rate(MS, 1, R20)); l.apply(KEY, ping(2 * MS, 1, 2)); l.apply(KEY, pong(50 * MS, 1));
        for (int i = 0; i <= TickCadenceLedger.MAX_PENDING_RATE_CHANGES; i++) l.apply(KEY, rate(S + i, 3 + i, i % 2 == 0 ? R10 : R20));
        assertEquals(TickCadenceLedger.TickRateSignal.Status.OVERFLOW, l.tickRateSignal().status()); assertFalse(l.tickRateSignal().complete());
        l.apply(KEY, ping(2 * S, 2, 100)); l.apply(KEY, pong(2 * S + 40 * MS, 2));
        assertEquals(TickCadenceLedger.TickRateSignal.Status.SETTLED, l.tickRateSignal().status(), "a fence after every change settles even an overflow");
        assertEquals(java.util.List.of(R10), l.tickRateSignal().candidates());
        l.apply(KEY, new TickCadenceEvent.CoverageBreak(++seq, 3 * S, "GAP"));
        assertEquals(TickCadenceLedger.TickRateSignal.UNOBSERVED, l.tickRateSignal());
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceEvent.TickRate(1, 0, 1, 0f, false));
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceLedger(KEY, 0));
    }

    @Test void theProfileTurnsATickingStateIntoTheClientTickPeriod() {
        var p = me.nicolas.papea2ac.version.TickCadenceProfile.PAPER_26_2;
        assertEquals(new me.nicolas.papea2ac.version.TickCadenceProfile.TickPeriod(50 * MS, 50 * MS), p.clientTickPeriod(20f, false));
        assertEquals(new me.nicolas.papea2ac.version.TickCadenceProfile.TickPeriod(50 * MS, 50 * MS), p.clientTickPeriod(100f, false), "a faster server never speeds the client up");
        assertEquals(new me.nicolas.papea2ac.version.TickCadenceProfile.TickPeriod(100 * MS, 100 * MS), p.clientTickPeriod(10f, false));
        assertEquals(new me.nicolas.papea2ac.version.TickCadenceProfile.TickPeriod(333_333_333L, 333_333_333L), p.clientTickPeriod(3f, false));
        assertEquals(new me.nicolas.papea2ac.version.TickCadenceProfile.TickPeriod(S, S), p.clientTickPeriod(0.25f, false), "the client clamps the rate to at least 1");
        assertEquals(new me.nicolas.papea2ac.version.TickCadenceProfile.TickPeriod(50 * MS, 100 * MS), p.clientTickPeriod(10f, true), "frozen: 50 ms, or the rate period during a /tick step");
        assertTrue(p.clientTickPeriod(20f, true).fixed());
    }
}
