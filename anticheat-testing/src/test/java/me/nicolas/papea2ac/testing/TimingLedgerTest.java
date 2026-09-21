package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.player.*;
import org.junit.jupiter.api.Test;
/** RTT estimator, marker ledger and timing ledger: bounds, robustness, session isolation, expiry semantics (brief sections 5, 8, 9, 38, 39, 53). */
class TimingLedgerTest {
    static final long MS = 1_000_000L;

    @Test void stableRttConvergesAndJitterShrinks() {
        RttEstimator e = new RttEstimator(); long t = 0;
        for (int i = 0; i < 40; i++) { t += 1000 * MS; assertTrue(e.accept(20 * MS, t)); }
        RttEstimator.Summary s = e.summary();
        assertEquals(40, s.samples()); assertEquals(32, s.windowSamples()); assertEquals(20 * MS, s.minNanos()); assertEquals(20 * MS, s.maxNanos());
        assertTrue(Math.abs(s.smoothedNanos() - 20 * MS) < MS, "smoothed " + s.smoothedNanos()); assertTrue(s.jitterNanos() < MS, "jitter " + s.jitterNanos());
        assertEquals(0, s.outliers()); assertFalse(s.stale(t + 1000 * MS)); assertTrue(s.stale(t + 31_000 * MS));
    }
    @Test void jitterWidensTheEnvelopeAndOneOutlierDoesNotPoisonTheEstimate() {
        RttEstimator e = new RttEstimator(); long t = 0; SplittableRandom r = new SplittableRandom(11);
        for (int i = 0; i < 30; i++) { t += 1000 * MS; e.accept((20 + r.nextInt(20)) * MS, t); }
        RttEstimator.Summary before = e.summary();
        assertTrue(before.jitterNanos() > 2 * MS, "jitter reflects dispersion: " + before.jitterNanos()); assertTrue(before.envelopeHighNanos() >= before.maxNanos());
        t += 1000 * MS; e.accept(5000 * MS, t); // one 5 s spike
        RttEstimator.Summary after = e.summary();
        assertEquals(1, after.outliers()); assertTrue(after.smoothedNanos() < 60 * MS, "smoothed not poisoned: " + after.smoothedNanos());
        assertEquals(5000 * MS, after.maxNanos(), "the spike stays visible in the window maximum"); assertTrue(after.envelopeHighNanos() >= 5000 * MS);
        assertEquals(5000 * MS, after.lastNanos());
    }
    @Test void aPersistentShiftReseedsAfterThreeConsecutiveOutliers() {
        RttEstimator e = new RttEstimator(); long t = 0;
        for (int i = 0; i < 20; i++) { t += 1000 * MS; e.accept(20 * MS, t); }
        for (int i = 0; i < 3; i++) { t += 1000 * MS; e.accept(400 * MS, t); }
        assertEquals(400 * MS, e.summary().smoothedNanos(), "re-seeded at the new level"); assertEquals(3, e.summary().outliers());
        t += 1000 * MS; e.accept(400 * MS, t); assertEquals(3, e.summary().outliers(), "the new level is no longer an outlier");
    }
    @Test void malformedSamplesAreRejectedNeverNaNOrInfinity() {
        RttEstimator e = new RttEstimator();
        assertFalse(e.accept(0, 1)); assertFalse(e.accept(-5, 1)); assertFalse(e.accept(RttEstimator.MAX_SAMPLE_NANOS + 1, 1)); assertFalse(e.accept(Long.MAX_VALUE, 1));
        assertEquals(4, e.summary().rejected()); assertFalse(e.summary().hasSamples()); assertEquals(-1, e.summary().ageNanos(5));
        assertTrue(e.accept(Long.MAX_VALUE / 4 > RttEstimator.MAX_SAMPLE_NANOS ? 30 * MS : 1, Long.MAX_VALUE - 10)); // a huge monotonic timestamp is fine
        RttEstimator.Summary s = e.summary(); assertTrue(s.smoothedNanos() > 0 && s.jitterNanos() >= 0 && s.envelopeHighNanos() >= s.envelopeLowNanos());
    }
    @Test void staleAndAbsentRttAreReportedNotInvented() {
        RttEstimator.Summary none = RttEstimator.Summary.EMPTY; assertFalse(none.hasSamples()); assertTrue(none.stale(0));
        RttEstimator e = new RttEstimator(); e.accept(30 * MS, 1000 * MS); assertFalse(e.summary().stale(1001 * MS)); assertTrue(e.summary().stale(1000 * MS + RttEstimator.Summary.STALE_AFTER_NANOS + 1));
    }

    @Test void markerLedgerResolvesInOrderAndCountsDuplicatesLateAndFailedWrites() {
        MarkerLedger m = new MarkerLedger();
        m.sent(TimingEvent.MarkerKind.PING, 1, 10, 0, 100); m.sent(TimingEvent.MarkerKind.PING, 2, 11, 5 * MS, 101); m.sent(TimingEvent.MarkerKind.KEEPALIVE, 7, 12, 6 * MS, 102);
        assertEquals(3, m.pendingCount());
        m.writeResolved(100, true); m.writeResolved(102, false); // the keep-alive write failed: it can never yield a sample
        assertEquals(2, m.pendingCount()); assertEquals(1, m.summary().failedWrites());
        MarkerLedger.Answer a = m.acknowledged(TimingEvent.MarkerKind.PING, 1, 30 * MS);
        assertEquals(MarkerLedger.Answer.Outcome.RESOLVED, a.outcome()); assertEquals(30 * MS, a.rttNanos().getAsLong()); assertEquals(100, a.wireOrdinal());
        assertEquals(MarkerLedger.Answer.Outcome.DUPLICATE, m.acknowledged(TimingEvent.MarkerKind.PING, 1, 31 * MS).outcome());
        assertEquals(MarkerLedger.Answer.Outcome.LATE_OR_UNKNOWN, m.acknowledged(TimingEvent.MarkerKind.PING, 99, 31 * MS).outcome());
        assertEquals(MarkerLedger.Answer.Outcome.LATE_OR_UNKNOWN, m.acknowledged(TimingEvent.MarkerKind.KEEPALIVE, 7, 31 * MS).outcome(), "a failed write's answer is unknown, not a sample");
        MarkerLedger.Answer b = m.acknowledged(TimingEvent.MarkerKind.PING, 2, 40 * MS); assertEquals(MarkerLedger.Answer.Outcome.RESOLVED_WRITE_UNKNOWN, b.outcome()); assertEquals(35 * MS, b.rttNanos().getAsLong());
        assertEquals(1, m.summary().duplicates()); assertEquals(2, m.summary().lateOrUnknown()); assertEquals(2, m.summary().acknowledged()); assertEquals(0, m.pendingCount());
    }
    @Test void expiryIsEvidenceUnavailableAndALateAnswerChangesNothing() {
        MarkerLedger m = new MarkerLedger();
        m.sent(TimingEvent.MarkerKind.PING, 5, 10, 0, 100);
        m.expire(MarkerLedger.EXPIRY_NANOS - 1); assertEquals(1, m.pendingCount());
        m.expire(MarkerLedger.EXPIRY_NANOS); assertEquals(0, m.pendingCount()); assertEquals(1, m.summary().expired());
        MarkerLedger.Answer late = m.acknowledged(TimingEvent.MarkerKind.PING, 5, MarkerLedger.EXPIRY_NANOS + MS);
        assertEquals(MarkerLedger.Answer.Outcome.DUPLICATE, late.outcome(), "an expired marker leaves a tombstone: its late answer is not a sample and not an unknown id");
        assertTrue(late.rttNanos().isEmpty());
    }
    @Test void pendingMarkersAndTombstonesAreBounded() {
        MarkerLedger m = new MarkerLedger();
        for (int i = 0; i < 100; i++) m.sent(TimingEvent.MarkerKind.PING, i, 10 + i, i, 100 + i);
        assertEquals(MarkerLedger.MAX_PENDING, m.pendingCount()); assertEquals(100 - MarkerLedger.MAX_PENDING, m.summary().evictedForCapacity());
        for (int i = 0; i < 100; i++) m.acknowledged(TimingEvent.MarkerKind.PING, i, 1000 + i);
        assertEquals(0, m.pendingCount()); assertEquals(MarkerLedger.MAX_PENDING, m.summary().acknowledged());
        for (int i = 0; i < 100; i++) m.acknowledged(TimingEvent.MarkerKind.PING, i, 2000 + i); // duplicates only for the tombstoned tail
        assertTrue(m.summary().duplicates() <= MarkerLedger.MAX_TOMBSTONES && m.summary().duplicates() > 0);
    }

    @Test void theLedgerIsSessionScopedMonotonicAndBounded() {
        SessionKey a = new SessionKey(new UUID(1, 1), 1), b = new SessionKey(new UUID(1, 1), 2);
        TimingLedger la = new TimingLedger(a), lb = new TimingLedger(b);
        la.apply(a, new TimingEvent.MarkerSent(1, 0, TimingEvent.MarkerKind.PING, 42, 7));
        assertFalse(lb.apply(a, new TimingEvent.MarkerAcknowledged(2, 20 * MS, TimingEvent.MarkerKind.PING, 42)), "an event tagged with the old session is refused by the replacement ledger");
        assertTrue(lb.apply(b, new TimingEvent.MarkerAcknowledged(2, 20 * MS, TimingEvent.MarkerKind.PING, 42)));
        assertEquals(0, lb.view().rtt().samples(), "the old session's marker never yields a sample in the new session"); assertEquals(1, lb.view().markers().lateOrUnknown());
        assertFalse(la.apply(a, new TimingEvent.ClientTickEnd(1, 5)), "non-monotonic sequence is a no-op");
        assertTrue(la.apply(a, new TimingEvent.MarkerAcknowledged(3, 25 * MS, TimingEvent.MarkerKind.PING, 42))); assertEquals(1, la.view().rtt().samples()); assertEquals(25 * MS, la.view().rtt().lastNanos());
        la.close(); assertFalse(la.apply(a, new TimingEvent.ClientTickEnd(4, 30 * MS))); assertTrue(la.view().closed());
        // bounds: the event ring and the pending correction list
        TimingLedger big = new TimingLedger(b); long seq = 1;
        for (int i = 0; i < 5000; i++) big.apply(b, new TimingEvent.ClientTickEnd(seq++, i * MS));
        assertTrue(big.view().counters().evictions() > 0); assertEquals(5000, big.view().counters().clientTickEnds());
        for (int i = 0; i < 20; i++) big.apply(b, new TimingEvent.TeleportSent(seq++, 6_000 * MS, i, 1000 + i, Vector3.ZERO, new Rotation(0, 0), Vector3.ZERO, 0));
        assertEquals(TimingLedger.MAX_PENDING_TELEPORTS, big.view().teleports().size());
        assertTrue(big.view().events().isEmpty(), "events are not published unless a diagnostic asks");
        // the diagnostic capture (P7-F5): only events after the switch, independent of the ring's horizon, bounded, server ticks and unrelated writes excluded (P7-F6)
        big.publishEvents(true); big.apply(b, new TimingEvent.ClientTickEnd(seq++, 6_001 * MS));
        assertEquals(1, big.view().events().size()); assertTrue(big.view().eventsPublished());
        for (int i = 0; i < 5000; i++) { big.apply(b, new TimingEvent.ClientTickEnd(seq++, 10_000 * MS + i * 1000 * MS)); big.apply(b, new TimingEvent.ServerTick(seq++, 10_000 * MS + i * 1000 * MS, i)); big.apply(b, new TimingEvent.WriteResolved(seq++, 10_000 * MS + i * 1000 * MS, 500_000 + i, true)); }
        assertEquals(TimingLedger.PUBLISHED_EVENTS, big.view().events().size(), "capped, not evicted by the ring horizon");
        assertTrue(big.view().events().stream().noneMatch(e -> e instanceof TimingEvent.ServerTick || e instanceof TimingEvent.WriteResolved));
        assertEquals(big.view().events().get(0).sequence() + 1, big.view().events().get(1).sequence(), "tick ends kept in order from the switch on");
        big.publishEvents(false); big.apply(b, new TimingEvent.ClientTickEnd(seq++, 20_000_000 * MS));
        assertTrue(big.view().events().isEmpty(), "cancel drops the capture");
    }
    @Test void teleportSupersessionAndFailedWritesAreTracked() {
        Recorder rec = new Recorder();
        TimingEvent.TeleportSent a = rec.teleport(1, 1, 70, 1, 0f, 0); rec.teleport(2, 2, 70, 2, 0f, 0);
        rec.write(a.wireOrdinal(), false);
        assertTrue(rec.ledger.view().teleports().get(0).writeFailed());
        rec.confirm(2);
        var v = rec.ledger.view();
        assertTrue(v.teleports().stream().anyMatch(t -> t.teleportId() == 2 && t.confirmed()));
        assertTrue(v.teleports().stream().anyMatch(t -> t.teleportId() == 1 && t.superseded()), v.teleports().toString());
        assertEquals(1, v.counters().failedWrites()); assertEquals(1, v.counters().teleportsConfirmed());
    }
    @Test void ackRoundTripSamplesComeOnlyFromMatchingMarkersAndNeverGoNegative() {
        Recorder rec = new Recorder();
        for (int i = 0; i < 10; i++) { rec.marker(TimingEvent.MarkerKind.PING, 100 + i); rec.advance(15 * MS + i * MS); rec.ack(TimingEvent.MarkerKind.PING, 100 + i); rec.advance(1000 * MS); }
        rec.ack(TimingEvent.MarkerKind.PING, 555); rec.ack(TimingEvent.MarkerKind.KEEPALIVE, 100);
        RttEstimator.Summary s = rec.ledger.view().rtt();
        assertEquals(10, s.samples()); assertEquals(15 * MS, s.minNanos()); assertEquals(24 * MS, s.maxNanos()); assertEquals(2, rec.ledger.view().markers().lateOrUnknown());
        assertTrue(s.smoothedNanos() >= 15 * MS && s.smoothedNanos() <= 24 * MS);
    }
}
