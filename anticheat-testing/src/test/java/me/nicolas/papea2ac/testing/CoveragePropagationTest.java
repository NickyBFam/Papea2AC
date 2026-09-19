package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.protocol.*;

/** Section 19: Phase 1 coverage must propagate into Phase 2 and never heal merely because later packets are valid. */
class CoveragePropagationTest {
    static void await(java.util.function.BooleanSupplier c) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!c.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(c.getAsBoolean());
    }
    static final PacketPayload.Movement MOVE = new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false);

    // Transport degradation boundary: a later, perfectly valid movement must still be uncertain because the latch is sticky.
    @Test void validPacketsAfterADegradeStayUncertainBecausePhase1HealthIsSticky() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {});
        var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(50, 1), loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Uncovered(PacketPayload.Family.UNKNOWN, "test")), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            assertTrue(session.timeline.snapshot().lastAppendHadCoverageIssue());
            session.ingress.capture(new Observation.Attempt(2, 2, MOVE), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 2);
            assertTrue(session.timeline.snapshot().lastAppendHadCoverageIssue(), "a valid packet after a sticky degrade must not be recorded as certain");
        } finally { engine.close(); loop.drain(); }
    }

    // A session that is degraded BEFORE its first observation (pre-login truncation / OVERLOADED at bind) never yields a certain record.
    @Test void sessionAlreadyDegradedBeforeItsFirstObservationNeverProducesACertainRecord() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {});
        var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(50, 2), loop, TransportCapability.observeOnly());
            session.health.invalidate(HealthLatch.Reason.OVERLOADED, 0); // what PaperCapture.bind() does on truncation
            session.ingress.capture(new Observation.Attempt(1, 1, MOVE), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            assertTrue(session.timeline.snapshot().lastAppendHadCoverageIssue());
        } finally { engine.close(); loop.drain(); }
    }

    // Phase 1 queue/journal overload: 257 outcome-less attempts overflow TruthActor's 256-entry journal -> OVERLOADED -> CoverageGap.
    @Test void phase1JournalOverloadSurfacesAsACoverageGap() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {});
        var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(50, 3), loop, new TransportCapability(true, true, true, true));
            for (int i = 1; i <= 257; i++) session.ingress.capture(new Observation.Attempt(i, i, MOVE), System.nanoTime());
            await(() -> session.health.get().reason() == HealthLatch.Reason.OVERLOADED);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 257);
            assertTrue(session.timeline.snapshot().lastAppendHadCoverageIssue());
        } finally { engine.close(); loop.drain(); }
    }

    // Recording epoch change: RECORDING controls are administrative - they must neither advance nor degrade the timeline.
    @Test void recordingControlIsAdministrativeAndLeavesTheTimelineUntouched() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(50, 4), loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Attempt(1, 1, MOVE), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            assertTrue(engine.setRecordingEnabled(new UUID(50, 4), true)); loop.drain();
            assertTrue(engine.setRecordingEnabled(new UUID(50, 4), false)); loop.drain();
            await(() -> session.actor.snapshot().processedSequence() == 3);
            assertEquals(1, session.timeline.snapshot().lastCommittedSequence(), "controls are not player observations");
            assertFalse(session.timeline.snapshot().lastAppendHadCoverageIssue());
        } finally { engine.close(); loop.drain(); }
    }

    // Replay must reproduce transport-degraded records exactly (never upgrade them to certain).
    @Test void transportDegradedRecordsReplayAsDegraded() {
        var key = new SessionKey(new UUID(50, 5), 1);
        var t = new PlayerTimeline(key);
        t.append(key, new PlayerObservation.MovementClaim(Optional.of(new Vector3(1, 2, 3)), Optional.empty(), true, false), 1, 0, "OVERLOADED");
        var original = t.allRecords().get(0);
        assertEquals(List.of("TRANSPORT_OVERLOADED"), original.uncertaintyReasons());
        var replay = TimelineReplayMapper.replay(TimelineReplayMapper.export(t, key.epoch()));
        assertEquals(t.allRecords(), replay.records());
        assertFalse(replay.records().get(0).certain());
    }

    // Section 18: current state remains usable after its own historical entry has been evicted.
    @Test void currentStateSurvivesEvictionOfItsOwnHistoricalEntry() {
        var key = new SessionKey(new UUID(50, 6), 1);
        var t = new PlayerTimeline(key);
        t.append(key, new PlayerObservation.MovementClaim(Optional.of(new Vector3(7, 8, 9)), Optional.empty(), true, false), 1, 0);
        // Only a horizon-old tick arrives afterwards: the movement record ages out, but current state must keep the position.
        t.append(key, new PlayerObservation.TickObserved(1), 2, PlayerTimeline.HORIZON_NANOS + 1);
        t.expire(2 * PlayerTimeline.HORIZON_NANOS + 2);
        assertEquals(0, t.snapshot().recordCount(), "everything has aged out of history");
        assertEquals(new Vector3(7, 8, 9), t.currentState().movement().position(), "current state is not history and must not be erased by eviction");
        assertInstanceOf(TimelineLookup.Evicted.class, t.stateAtSequence(1));
        assertInstanceOf(TimelineLookup.Evicted.class, t.stateAtSequence(2));
    }
}
