package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import me.nicolas.papea2ac.core.PhysicsQualification;
import me.nicolas.papea2ac.core.TimelineStatus;
import me.nicolas.papea2ac.physics.*;
import org.junit.jupiter.api.Test;

/** The real-client qualification tracer against a synthetic timeline: declared scenarios, refusal paths, bit-exact replay of self-consistent observations, timing ambiguity, one-step diagnostic. */
class PhysicsQualificationTest {
    static TimelineStatus status(long movementSeq, double x, double y, double z, boolean onGround, float yaw) {
        return new TimelineStatus(1, movementSeq, 10, 1000, 0, false, 1, movementSeq, true, x, y, z, movementSeq, true, yaw, 0f, onGround, false, -1, false, 0, false, false, 0);
    }
    /** A synthetic timeline: the test publishes snapshots; the qualification's poller thread fetches them. */
    static final class Timeline implements Function<UUID, Optional<TimelineStatus>> {
        final AtomicReference<TimelineStatus> current = new AtomicReference<>(), fetched = new AtomicReference<>();
        Timeline(TimelineStatus initial) { current.set(initial); }
        @Override public Optional<TimelineStatus> apply(UUID id) { TimelineStatus t = current.get(); fetched.set(t); return Optional.ofNullable(t); }
        /** Publishes one sample and waits until the poller has fetched it (the 2 ms poller can be starved under parallel test load; the real timeline advances at 50 ms per tick). */
        void publish(TimelineStatus s) throws InterruptedException {
            current.set(s); long deadline = System.nanoTime() + 5_000_000_000L;
            while (fetched.get() != s) { if (System.nanoTime() > deadline) throw new AssertionError("poller did not fetch sample " + s.movementSequence() + " within 5 s"); Thread.sleep(1); }
        }
    }
    static final PhysicsQualification.ArmFacts FACTS = new PhysicsQualification.ArmFacts(true, -2.9999984E7, -2.9999984E7, 2.9999984E7, 2.9999984E7, true, false, List.of(), Map.of());

    @Test void armRefusesWithoutARestingObservationAndReportsWithoutTrace() {
        PhysicsScene ps = new PhysicsScene(); AtomicReference<TimelineStatus> ts = new AtomicReference<>();
        PhysicsQualification q = new PhysicsQualification(ps.simulator, ps.scene.engine, ps.behaviors, id -> Optional.ofNullable(ts.get()));
        UUID u = new UUID(1, 2);
        assertTrue(q.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).startsWith("REFUSED"));
        ts.set(status(5, 8.5, SURFACE, 8.5, false, 0f));
        assertTrue(q.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).contains("onGround=false"));
        assertEquals("no armed trace for that player (arm one first)", q.report(u).get(0));
        assertNull(PhysicsQualification.Scenario.parse("fly")); assertEquals(PhysicsQualification.Scenario.W_SPRINT, PhysicsQualification.Scenario.parse("w+sprint"));
    }
    @Test void selfConsistentObservationsReplayBitExact() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline ts = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 30f));
        PhysicsQualification q = new PhysicsQualification(ps.simulator, ps.scene.engine, ps.behaviors, ts);
        UUID u = new UUID(1, 3);
        String armed = q.arm(u, PhysicsQualification.Scenario.W_SPRINT, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        assertTrue(armed.startsWith("ARMED"), armed); assertEquals(1, q.activeTraces());
        // "observations": the simulator's own chained predictions from the rest state under the declared input
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); long seq = 5;
        for (int i = 0; i < 12; i++) { s = ps.step(s, MovementInput.forward(30f, true)).next(); ts.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 30f)); }
        Thread.sleep(30);
        List<String> report = q.report(u);
        String last = report.get(report.size() - 1);
        assertTrue(last.startsWith("QUALIFYING: 12 ticks bit-exact"), String.join("\n", report));
        assertTrue(report.stream().anyMatch(l -> l.startsWith("rest collision: COMPLETE")), String.join("\n", report));
        assertEquals("trace cancelled", q.cancel(u));
    }
    @Test void releaseAllKeysIsAFixedPointAndADriftIsReportedNotJudged() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline ts = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 0f));
        PhysicsQualification q = new PhysicsQualification(ps.simulator, ps.scene.engine, ps.behaviors, ts);
        UUID u = new UUID(1, 4);
        q.arm(u, PhysicsQualification.Scenario.RELEASE_ALL, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        for (int i = 0; i < 3; i++) ts.publish(status(6 + i, 8.5, SURFACE, 8.5, true, 0f));
        Thread.sleep(30);
        List<String> report = q.report(u);
        assertTrue(report.get(report.size() - 1).startsWith("QUALIFYING: release-all-keys keeps the rest position - 3/3"), String.join("\n", report));
        q.cancel(u);
        q.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        for (int i = 0; i < 4; i++) ts.publish(status(20 + i, 8.5, SURFACE, 8.5 + 0.2 * (i + 1), true, 0f));
        Thread.sleep(30);
        report = q.report(u); String verdict = report.get(report.size() - 1);
        assertTrue(verdict.startsWith("QUALIFYING TRACE WITH DISCREPANCIES") || verdict.startsWith("NOT QUALIFYING"), verdict);
        for (String l : report) { assertFalse(l.toLowerCase(Locale.ROOT).contains("cheat")); assertFalse(l.contains("INVALID")); }
    }
    @Test void oneStepDiagnosticStatesItsHypothesis() {
        PhysicsScene ps = new PhysicsScene(); AtomicReference<TimelineStatus> ts = new AtomicReference<>(status(5, 8.5, SURFACE, 8.5, true, 0f));
        PhysicsQualification q = new PhysicsQualification(ps.simulator, ps.scene.engine, ps.behaviors, id -> Optional.ofNullable(ts.get()));
        List<String> out = q.oneStep(new UUID(1, 5), PhysicsQualification.Scenario.JUMP_STATIONARY, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), FACTS);
        assertTrue(out.get(0).contains("declared input, not inferred")); assertTrue(out.get(1).startsWith("result: EXACT"), out.get(1)); assertTrue(out.get(2).contains("y=70.41999998688698"), out.get(2));
    }
}
