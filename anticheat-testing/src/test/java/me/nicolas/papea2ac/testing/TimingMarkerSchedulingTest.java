package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;

/**
 * Core-owned marker issue through the platform port (LATENCY_COMPENSATION.md N1) at the engine level: no marker before the
 * session's own entity is known, at least one per second once it is, a bracket right after an outbound influence, never more
 * than 16 pending against a silent client, RTT samples only from pongs the ledger correlates, a late pong of a replaced session
 * counted as late/unknown (never a sample of the new session), and send failures counted rather than retried.
 */
class TimingMarkerSchedulingTest {
    /** A fake transport: every marker becomes an Outbound PING observation + WriteOutcome on the session's ingress (what the real capture sees), and an answering client pongs after {@code delayNanos}. */
    static final class FakePort implements MarkerPort {
        final PacketEngine.Session session; final boolean answer; final long delayNanos; final boolean fail;
        final AtomicInteger sent = new AtomicInteger(); final List<Integer> ids = new CopyOnWriteArrayList<>(); final AtomicLong wire = new AtomicLong(1000), attempt = new AtomicLong(5000);
        final ScheduledExecutorService client = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "fake-client"); t.setDaemon(true); return t; });
        FakePort(PacketEngine.Session session, boolean answer, long delayNanos, boolean fail) { this.session = session; this.answer = answer; this.delayNanos = delayNanos; this.fail = fail; }
        @Override public boolean sendMarker(UUID player, int id) {
            if (fail) return false;
            sent.incrementAndGet(); ids.add(id);
            long w = wire.incrementAndGet();
            session.ingress.bridge(new Observation.Outbound(w, new PacketPayload.Control(PacketPayload.ControlKind.PING, id)));
            session.ingress.bridge(new Observation.WriteOutcome(w, true));
            if (answer) client.schedule(() -> { long a = attempt.incrementAndGet(); session.ingress.bridge(new Observation.Attempt(a, a, new PacketPayload.Control(PacketPayload.ControlKind.PONG, id))); }, delayNanos, TimeUnit.NANOSECONDS);
            return true;
        }
        void close() { client.shutdownNow(); }
    }
    static void await(PacketTruthTest.Loop loop, long millis, java.util.function.BooleanSupplier c) throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!c.getAsBoolean() && System.nanoTime() < until) { loop.drain(); Thread.sleep(2); }
        loop.drain();
    }
    /** Pumps the bridge for {@code millis} regardless of any condition (the fake port publishes from the shard/client threads through the test loop). */
    static void pump(PacketTruthTest.Loop loop, long millis) throws Exception { await(loop, millis, () -> false); }

    @Test void noMarkerBeforeTheSelfEntityIsKnownThenAtLeastOnePerSecondAndPongsBecomeRttSamples() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop(); FakePort port = null;
        try {
            var session = engine.connect(new UUID(70, 1), loop, TransportCapability.observeOnly());
            port = new FakePort(session, true, 3_000_000L, false); engine.setMarkerPort(port);
            pump(loop, 400);
            assertEquals(0, port.sent.get(), "no marker without the session's own entity id");
            assertEquals(0, engine.timingEngineStatus().markersIssued());
            session.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            FakePort p = port;
            await(loop, 3000, () -> p.sent.get() >= 2);
            assertTrue(port.sent.get() >= 2, "at least one marker per second: " + port.sent.get());
            await(loop, 3000, () -> engine.timingStatus(new UUID(70, 1)).map(TimingStatus::rttPresent).orElse(false));
            TimingStatus st = engine.timingStatus(new UUID(70, 1)).orElseThrow();
            assertTrue(st.rttPresent(), "pongs produce RTT samples"); assertTrue(st.rttSamples() >= 1); assertTrue(st.markersAcknowledged() >= 1);
            assertTrue(st.rttLastNanos() > 0 && st.rttLastNanos() < 2_000_000_000L, "a plausible round trip (fake client answers after ~3 ms; capture nanos are assigned at the bridge drain): " + st.rttLastNanos());
            assertEquals((int) (session.key.epoch() & 0xFF), (port.ids.get(0) >>> 24) & 0xFF, "the session epoch travels in the marker id's high byte");
            var es = engine.timingEngineStatus();
            assertTrue(es.markerPortPresent()); assertTrue(es.markersIssued() >= 2); assertTrue(es.rttSamples() >= 1); assertEquals(0, es.markerSendFailures());
            assertTrue(st.markersPending() <= PacketEngine.MARKER_MAX_PENDING);
        } finally { if (port != null) port.close(); engine.close(); loop.drain(); }
    }
    @Test void anOutboundInfluenceBracketsAMarkerAndASilentClientNeverAccumulatesMoreThanSixteenPending() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop(); FakePort port = null;
        try {
            var session = engine.connect(new UUID(70, 2), loop, TransportCapability.observeOnly());
            port = new FakePort(session, false, 0, false); engine.setMarkerPort(port);
            session.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            FakePort p = port;
            await(loop, 2000, () -> p.sent.get() >= 1);
            int before = port.sent.get();
            // an outbound velocity for the player -> a bracket marker well before the next periodic one
            session.ingress.bridge(new Observation.Outbound(10, new PacketPayload.Velocity(42, new PacketPayload.Position(0.2, 0.4, 0))));
            long t0 = System.nanoTime();
            await(loop, 900, () -> p.sent.get() > before);
            assertTrue(port.sent.get() > before, "bracket marker after an outbound influence");
            assertTrue(System.nanoTime() - t0 < 900_000_000L, "the bracket does not wait for the periodic marker");
            // 40 more influences at ~60 ms: the min gap admits most as brackets, but a silent client caps the pending markers at 16
            for (int i = 0; i < 40; i++) { session.ingress.bridge(new Observation.Outbound(11 + i, new PacketPayload.Velocity(42, new PacketPayload.Position(0.2, 0.4, 0)))); pump(loop, 60); }
            pump(loop, 300);
            TimingStatus st = engine.timingStatus(new UUID(70, 2)).orElseThrow();
            assertTrue(st.markersPending() <= PacketEngine.MARKER_MAX_PENDING, "pending " + st.markersPending());
            assertTrue(port.sent.get() <= PacketEngine.MARKER_MAX_PENDING, "sent " + port.sent.get() + " against a client that never answers");
            assertEquals(port.sent.get(), st.markersSent(), "every issued marker was captured outbound");
            assertFalse(st.rttPresent()); assertEquals(41, st.influences(), "the ledger registered every velocity for the self entity");
        } finally { if (port != null) port.close(); engine.close(); loop.drain(); }
    }
    @Test void aLatePongOfAReplacedSessionIsLateOrUnknownAndNeverASampleOfTheNewOne() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop(); FakePort port = null;
        try {
            UUID u = new UUID(70, 3);
            var first = engine.connect(u, loop, TransportCapability.observeOnly());
            port = new FakePort(first, false, 0, false); engine.setMarkerPort(port);
            first.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            FakePort p = port;
            await(loop, 2000, () -> p.sent.get() >= 1);
            int oldId = port.ids.get(0);
            var second = engine.connect(u, loop, TransportCapability.observeOnly()); // session replacement: the first closes
            engine.setMarkerPort(new FakePort(second, false, 0, true)); // the new session's own markers are not the point here (and send failures are only counted)
            second.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            long before = engine.timingEngineStatus().lateOrUnknownAcks();
            second.ingress.bridge(new Observation.Attempt(1, 1, new PacketPayload.Control(PacketPayload.ControlKind.PONG, oldId)));
            await(loop, 2000, () -> engine.timingEngineStatus().lateOrUnknownAcks() > before);
            assertTrue(engine.timingEngineStatus().lateOrUnknownAcks() > before, "the old session's id never matches the new ledger");
            TimingStatus st = engine.timingStatus(u).orElseThrow();
            assertFalse(st.rttPresent()); assertEquals(1, st.markersLateOrUnknown()); assertEquals(0, st.markersAcknowledged());
            assertNotEquals(first.key.epoch(), second.key.epoch());
            await(loop, 2000, () -> engine.timingEngineStatus().markerSendFailures() >= 1);
            assertTrue(engine.timingEngineStatus().markerSendFailures() >= 1, "a port that cannot hand the marker to the transport is counted, not retried in a loop");
        } finally { if (port != null) port.close(); engine.close(); loop.drain(); }
    }
    @Test void velocityForAnotherEntityIsNeitherAnInfluenceNorABracketAndUnknownSelfIsCounted() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop(); FakePort port = null;
        try {
            UUID u = new UUID(70, 4);
            var session = engine.connect(u, loop, TransportCapability.observeOnly());
            port = new FakePort(session, false, 0, false); engine.setMarkerPort(port);
            long ignoredBefore = engine.timingEngineStatus().velocityIgnoredUnknownSelf();
            session.ingress.bridge(new Observation.Outbound(1, new PacketPayload.Velocity(42, new PacketPayload.Position(0.2, 0.4, 0)))); // self unknown yet
            await(loop, 2000, () -> engine.timingEngineStatus().velocityIgnoredUnknownSelf() > ignoredBefore);
            assertEquals(ignoredBefore + 1, engine.timingEngineStatus().velocityIgnoredUnknownSelf());
            session.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            FakePort p = port;
            await(loop, 2000, () -> p.sent.get() >= 1);
            int before = port.sent.get();
            session.ingress.bridge(new Observation.Outbound(2, new PacketPayload.Velocity(43, new PacketPayload.Position(0.2, 0.4, 0)))); // another entity (P7-F1)
            pump(loop, 400);
            TimingStatus st = engine.timingStatus(u).orElseThrow();
            assertEquals(0, st.influences(), "another entity's velocity is not this player's influence");
            assertEquals(before, port.sent.get(), "and does not bracket a marker");
        } finally { if (port != null) port.close(); engine.close(); loop.drain(); }
    }
}
