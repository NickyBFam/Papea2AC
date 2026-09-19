package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.player.*;

/**
 * Proves the full Phase 1 -> ordered session -> Phase 2 append pipeline end to end through PacketEngine's
 * real Shard/TruthActor wiring - not a re-normalization or a second listener, the same Observation stream
 * TruthActor itself consumes (PacketEngine.Shard.run()).
 */
class PlayerTimelineIntegrationTest {
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }

    /** Session/shard cleanup (ingress release, active-map removal) completes on the event loop; PacketTruthTest.Loop
     * only runs posted tasks when drained, so cleanup-observing awaits must drain on every poll, unlike
     * awaits that only read the timeline's published Snapshot. */
    static void awaitDraining(PacketTruthTest.Loop loop, java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) { loop.drain(); Thread.sleep(1); }
        loop.drain();
        assertTrue(condition.getAsBoolean());
    }

    @Test void movementCapturedThroughTheRealEngineReachesTheSessionsTimeline() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(11, 12);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            assertEquals(new Vector3(1, 2, 3), session.timeline.currentState().movement().position());
            var snapshot = engine.timelineSnapshot(player).orElseThrow();
            assertEquals(1, snapshot.lastCommittedSequence());
        } finally { engine.close(); loop.drain(); }
    }

    @Test void unknownPlayerHasNoTimelineSnapshot() {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        try { assertTrue(engine.timelineSnapshot(new UUID(0, 0)).isEmpty()); assertTrue(engine.timelineStatus(new UUID(0, 0)).isEmpty()); }
        finally { engine.close(); }
    }

    /** The primitive-only diagnostic projection the /papea2ac timeline command displays (platform-paper may not depend on anticheat-player). */
    @Test void timelineStatusProjectsThePublishedSnapshotWithoutLeakingPlayerTypes() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(25, 26);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            var fresh = engine.timelineStatus(player).orElseThrow();
            assertFalse(fresh.hasPosition()); assertFalse(fresh.hasRotation()); assertFalse(fresh.teleportSent());
            session.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            var status = engine.timelineStatus(player).orElseThrow();
            assertTrue(status.hasPosition()); assertEquals(1.0, status.x()); assertEquals(2.0, status.y()); assertEquals(3.0, status.z());
            assertFalse(status.hasRotation(), "MOVE carries no rotation on the wire");
            assertEquals(1, status.retainedRecords()); assertEquals(session.key.epoch(), status.sessionEpoch());
        } finally { engine.close(); loop.drain(); }
    }

    @Test void pipelineDegradeIsRecordedAsACoverageGapNotSilentlyDropped() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {});
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(13, 14);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            // An explicitly uncovered semantic family degrades TruthActor's health (PIPELINE_UNCOVERED); the
            // Timeline must record that fact rather than silently dropping or mis-mapping the observation.
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Uncovered(PacketPayload.Family.UNKNOWN, "test")), System.nanoTime());
            await(() -> !session.health.get().healthy());
            // Deep ring queries (stateAtSequence etc.) are owner-shard-thread-only by design (THREADING_MODEL.md);
            // a cross-thread test reads the published, thread-safe Snapshot instead, exactly as a real
            // cross-thread diagnostic reader would.
            await(() -> session.timeline.snapshot().lastAppendHadCoverageIssue());
        } finally { engine.close(); loop.drain(); }
    }

    @Test void sessionCloseAppendsAFinalSessionClosedRecord() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(15, 16);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            session.close();
            // A cross-thread reader only ever sees the published Snapshot; proving the close was appended
            // means proving lastCommittedSequence advanced past the movement claim, not inspecting the ring.
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 2);
        } finally { engine.close(); loop.drain(); }
    }

    @Test void reconnectGetsABrandNewTimelineInstanceNeverReusingOldMutableHistory() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(17, 18);
        try {
            var first = engine.connect(player, loop, TransportCapability.observeOnly());
            first.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
            await(() -> first.timeline.snapshot().lastCommittedSequence() == 1);
            var second = engine.connect(player, loop, TransportCapability.observeOnly());
            assertNotSame(first.timeline, second.timeline);
            assertEquals(0, second.timeline.snapshot().lastCommittedSequence());
        } finally { engine.close(); loop.drain(); }
    }

    // 21/23: capture epoch change / global capture boundary - the engine-wide admission gate, not a
    // per-session control. Disabling it closes every active session (appending SessionClosed, never a silent
    // drop); re-enabling admits a fresh epoch on the next connect.
    @Test void globalCaptureDisableClosesActiveSessionsWithARecordedSessionClosedThenReenablingStartsAFreshEpoch() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(19, 20);
        try {
            var first = engine.connect(player, loop, TransportCapability.observeOnly());
            first.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
            await(() -> first.timeline.snapshot().lastCommittedSequence() == 1);
            engine.setCaptureEnabled(false);
            await(() -> first.timeline.snapshot().lastCommittedSequence() == 2);

            engine.setCaptureEnabled(true);
            var second = engine.connect(player, loop, TransportCapability.observeOnly());
            assertTrue(second.key.epoch() > first.key.epoch());
            assertNotSame(first.timeline, second.timeline);
        } finally { engine.close(); loop.drain(); }
    }

    // 32: shutdown - engine.close() must cleanly close every active session (SessionClosed recorded), never hang or throw.
    @Test void engineShutdownClosesEverySessionsTimelineCleanly() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(21, 22);
        var session = engine.connect(player, loop, TransportCapability.observeOnly());
        session.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
        await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
        engine.close();
        loop.drain();
        await(() -> session.timeline.snapshot().lastCommittedSequence() == 2);
        await(engine::terminated);
    }

    // Section 23: a realistic full session through the real Phase 1 pipeline, never bypassing it by calling
    // the reducer directly. Exercises position-only, rotation-only, position+rotation, a teleport
    // sent/acknowledged pair, an external-motion (velocity) observation, and disconnect, then verifies
    // ordering, current state, and session cleanup.
    @Test void realisticFullSessionThroughTheActualPhase1PipelineProducesTheExpectedOrderedTimeline() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(23, 24);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            long now = System.nanoTime();

            // position + rotation
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), new PacketPayload.Rotation(10, 5), true, false)), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 1);
            assertEquals(new Vector3(1, 2, 3), session.timeline.currentState().movement().position());
            assertEquals(new Rotation(10, 5), session.timeline.currentState().rotation().rotation());

            // position-only (rotation omitted on the wire)
            session.ingress.capture(new Observation.Attempt(2, 2, new PacketPayload.Movement(new PacketPayload.Position(4, 5, 6), null, true, false)), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 2);
            assertEquals(new Vector3(4, 5, 6), session.timeline.currentState().movement().position());
            assertEquals(new Rotation(10, 5), session.timeline.currentState().rotation().rotation(), "rotation must be unchanged when the wire omitted it");

            // rotation-only (position omitted on the wire)
            session.ingress.capture(new Observation.Attempt(3, 3, new PacketPayload.Movement(null, new PacketPayload.Rotation(90, -10), true, false)), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 3);
            assertEquals(new Vector3(4, 5, 6), session.timeline.currentState().movement().position(), "position must be unchanged when the wire omitted it");
            assertEquals(new Rotation(90, -10), session.timeline.currentState().rotation().rotation());

            // server-issued teleport (sent, not yet applied)
            session.ingress.capture(new Observation.Outbound(1, new SemanticPayload.Correction(9, new PacketPayload.Position(0, 100, 0), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0, 0), Set.of())), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 4);
            assertTrue(session.timeline.currentState().teleport().active().isPresent());
            assertFalse(session.timeline.currentState().teleport().active().get().acknowledged());

            // client confirms the teleport ID
            session.ingress.capture(new Observation.Attempt(4, 4, new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM, 9)), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 5);
            assertTrue(session.timeline.currentState().teleport().active().orElseThrow().acknowledged());

            // movement resumes after the teleport
            session.ingress.capture(new Observation.Attempt(5, 5, new PacketPayload.Movement(new PacketPayload.Position(0, 100, 0), null, true, false)), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 6);

            // external motion (velocity) observation
            session.ingress.capture(new Observation.Outbound(2, new PacketPayload.Velocity(player.hashCode(), new PacketPayload.Position(0, 1, 0))), now);
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 7);
            assertEquals(1, session.timeline.currentState().velocity().active().size());

            // disconnect
            session.close();
            await(() -> session.timeline.snapshot().lastCommittedSequence() == 8);
            awaitDraining(loop, () -> engine.sessions() == 0);
        } finally { engine.close(); loop.drain(); }
    }
}
