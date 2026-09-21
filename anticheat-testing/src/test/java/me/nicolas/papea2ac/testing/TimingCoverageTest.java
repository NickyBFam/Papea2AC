package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.HealthLatch;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;

/**
 * P7-F4 (real-client qualification): the timing ledger's coverage breaks only on latch reasons that can mean a lost or undecoded
 * observation (the world's filter, P3-F3). The permanent correlation limitation of a real session (PIPELINE_UNCOVERED from the
 * client's brand payload, EXPIRED/OUTCOME_UNKNOWN outcomes) loses no tick end and no marker: it must not degrade alignment to the
 * bounded fallback, and Phase 1's EXPIRED journal outcome is never treated as packet loss (brief 56/57).
 */
class TimingCoverageTest {
    static void await(PacketTruthTest.Loop loop, java.util.function.BooleanSupplier c) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!c.getAsBoolean() && System.nanoTime() < until) { loop.drain(); Thread.sleep(1); }
        loop.drain(); assertTrue(c.getAsBoolean());
    }
    static final PacketPayload.Control TICK_END = new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0);

    @Test void theBenignCorrelationLatchReasonsDoNotBreakTimingCoverage() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(71, 1), loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Uncovered(PacketPayload.Family.UNKNOWN, "minecraft:brand")), System.nanoTime());
            await(loop, () -> session.timeline.snapshot().lastCommittedSequence() == 1);
            assertEquals(HealthLatch.Reason.PIPELINE_UNCOVERED, session.health.get().reason());
            assertTrue(session.timeline.snapshot().lastAppendHadCoverageIssue(), "the timeline keeps Phase 1's sticky coverage issue");
            for (int i = 2; i <= 6; i++) session.ingress.capture(new Observation.Attempt(i, i, TICK_END), System.nanoTime());
            await(loop, () -> session.timing.view().counters().clientTickEnds() == 5);
            assertEquals(0, session.timing.view().counters().coverageBreaks(), "PIPELINE_UNCOVERED loses no tick end: no timing coverage break");
            assertTrue(session.timing.view().tickEndCapable());
        } finally { engine.close(); loop.drain(); }
    }
    @Test void aLossClassLatchReasonBreaksTimingCoverageOnce() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(71, 2), loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Attempt(1, 1, TICK_END), System.nanoTime());
            await(loop, () -> session.timing.view().counters().clientTickEnds() == 1);
            session.health.invalidate(HealthLatch.Reason.OVERLOADED, 1); // what an ingress overflow does: observations may have been lost
            session.ingress.capture(new Observation.Attempt(2, 2, TICK_END), System.nanoTime());
            await(loop, () -> session.timing.view().counters().coverageBreaks() == 1);
            assertEquals(1, session.timing.view().counters().clientTickEnds(), "the observation that carried the break is the break, not a counted tick end");
            session.ingress.capture(new Observation.Attempt(3, 3, TICK_END), System.nanoTime());
            await(loop, () -> session.timing.view().counters().clientTickEnds() == 2);
            assertEquals(1, session.timing.view().counters().coverageBreaks(), "sticky health does not re-break on every later observation; the resolver sees coverageDegraded from the count");
        } finally { engine.close(); loop.drain(); }
    }
}
