package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import me.nicolas.papea2ac.core.PhysicsQualification;
import me.nicolas.papea2ac.core.ReconstructionTracer;
import me.nicolas.papea2ac.core.TimelineStatus;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import org.junit.jupiter.api.Test;

/** The core reconstruction tracer against a synthetic timeline: membership of a declared input, the negative declaration, rest re-sends, rotation-only samples, and the vocabulary. */
class ReconstructionTracerTest {
    static TimelineStatus status(long movementSeq, double x, double y, double z, boolean onGround, float yaw) {
        return new TimelineStatus(1, movementSeq, 10, 1000, 0, false, 1, movementSeq, true, x, y, z, movementSeq, true, yaw, 0f, onGround, false, -1, false, 0, false, false, 0);
    }
    static final PhysicsQualification.ArmFacts FACTS = new PhysicsQualification.ArmFacts(true, -2.9999984E7, -2.9999984E7, 2.9999984E7, 2.9999984E7, true, false, List.of(), Map.of());
    /** A synthetic timeline the test publishes into; {@link #publish} waits until the tracer's poller fetched the value (the poller can be starved under parallel test load). */
    static final class Timeline implements Function<UUID, Optional<TimelineStatus>> {
        final AtomicReference<TimelineStatus> current = new AtomicReference<>(), fetched = new AtomicReference<>();
        Timeline(TimelineStatus initial) { current.set(initial); }
        @Override public Optional<TimelineStatus> apply(UUID id) { TimelineStatus t = current.get(); fetched.set(t); return Optional.ofNullable(t); }
        void publish(TimelineStatus s) throws InterruptedException { current.set(s); long deadline = System.nanoTime() + 5_000_000_000L; while (fetched.get() != s) { if (System.nanoTime() > deadline) throw new AssertionError("poller did not fetch sample " + s.movementSequence()); Thread.sleep(1); } }
    }
    static ReconstructionTracer tracer(PhysicsScene ps, Timeline tl) { return new ReconstructionTracer(reconstructor(ps), ps.scene.engine, ps.behaviors, tl); }

    @Test void declaredInputIsPresentInEveryReconstructedTransition() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline tl = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 30f)); ReconstructionTracer t = tracer(ps, tl); UUID u = new UUID(2, 1);
        String armed = t.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS); assertTrue(armed.startsWith("ARMED"), armed);
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); long seq = 5;
        for (int i = 0; i < 8; i++) { s = ps.step(s, MovementInput.forward(30f, false)).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 30f)); }
        Thread.sleep(30);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(report.get(report.size() - 1).startsWith("QUALIFYING: the declared input is in the candidate set of every reconstructed transition (8)"), text);
        assertTrue(text.contains("unique=8"), text); assertTrue(text.contains("alignedH1=8"), text);
        for (String l : report) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("invalid") || lo.contains("violation"), l); }
        assertEquals("trace cancelled", t.cancel(u));
    }
    @Test void aWrongDeclarationIsReportedAbsentNotJudged() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline tl = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 0f)); ReconstructionTracer t = tracer(ps, tl); UUID u = new UUID(2, 2);
        t.arm(u, PhysicsQualification.Scenario.HOLD_S, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); long seq = 5;
        for (int i = 0; i < 5; i++) { s = ps.step(s, W).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 0f)); }
        for (int i = 0; i < 3; i++) { s = ps.step(s, IDLE).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 0f)); } // the release tail is idle - honestly idle, never "present"
        Thread.sleep(30);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(report.get(report.size() - 1).startsWith("NOT QUALIFYING: the declared input is absent from 5 transition(s)"), text);
        assertTrue(text.contains("declaredAbsent=5"), text); assertTrue(text.contains("W-----"), "the candidates themselves are listed: " + text);
        assertTrue(text.contains("prePressIdle=3") && text.contains("releaseIdle=0"), "the declared keys were never present, so the idle tail is not a release: " + text);
    }
    @Test void restResendsAndRotationOnlySamplesAreHandledWithoutAVerdict() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline tl = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 0f)); ReconstructionTracer t = tracer(ps, tl); UUID u = new UUID(2, 3);
        t.arm(u, null, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        long seq = 5;
        tl.publish(status(++seq, 8.5, SURFACE, 8.5, true, 0f)); // periodic re-send at rest
        tl.publish(status(seq, 8.5, SURFACE, 8.5, true, 45f));   // rotation-only: yaw changed, no new position
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        for (int i = 0; i < 4; i++) { s = ps.step(s, MovementInput.forward(45f, false)).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 45f)); }
        Thread.sleep(30);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(text.contains("reconstructed=4"), text); assertTrue(text.contains("noCandidate=0"), text);
        assertFalse(text.contains("QUALIFYING"), "no declaration, no qualification line: " + text);
    }
    /** Real-client finding (Phase 6 qualification): with every node incomplete (here an unknown entity neighbourhood) a rest re-send and a counted transition have matching states but no EXACT witness to continue the chain from - the report must say so, not throw. */
    @Test void incompleteStatesNeverLeaveTheChainWithoutAFrontier() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline tl = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 0f)); ReconstructionTracer t = tracer(ps, tl); UUID u = new UUID(2, 4);
        PhysicsQualification.ArmFacts entitiesUnknown = new PhysicsQualification.ArmFacts(true, -2.9999984E7, -2.9999984E7, 2.9999984E7, 2.9999984E7, false, false, List.of(), Map.of());
        t.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, entitiesUnknown);
        long seq = 5;
        tl.publish(status(++seq, 8.5, SURFACE, 8.5, true, 0f)); // rest re-send: the closure has incomplete states only
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        for (int i = 0; i < 3; i++) { s = ps.step(s, W).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 0f)); }
        Thread.sleep(30);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(text.contains("inexactRestClosures=1"), text);
        assertTrue(text.contains("UNKNOWN_ENTITY_NEIGHBORHOOD"), text); assertTrue(text.contains("incomplete=1"), text);
        assertTrue(text.contains("chain stops here: no exact witness state to continue from"), text);
        assertTrue(text.contains("declared=PRESENT"), "the declared input is still admitted by the incomplete candidates: " + text);
        for (String l : report) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("invalid") || lo.contains("violation"), l); }
    }
    /** Real-client finding (Phase 6 qualification, S2): the deceleration after the keys are released is reconstructed as idle; the accounting knows the release phase and a scenario is still QUALIFYING. */
    @Test void theReleaseTailIsExplainedByIdleAfterTheHeldPhase() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline tl = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 0f)); ReconstructionTracer t = tracer(ps, tl); UUID u = new UUID(2, 5);
        t.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); long seq = 5;
        for (int i = 0; i < 2; i++) { s = ps.step(s, IDLE).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 90f * i)); } // rotation before the press: a counted idle transition
        for (int i = 0; i < 5; i++) { s = ps.step(s, MovementInput.forward(90f, false)).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 90f)); }
        for (int i = 0; i < 4; i++) { s = ps.step(s, MovementInput.idle(90f)).next(); tl.publish(status(++seq, s.position().x(), s.position().y(), s.position().z(), s.onGround(), 90f)); }
        Thread.sleep(30);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(report.get(report.size() - 1).startsWith("QUALIFYING: the declared input is in the candidate set of every reconstructed transition (5)"), text);
        assertTrue(text.contains("declaredAbsent=0") && text.contains("releaseIdle=4"), text);
        assertTrue(text.contains("released=IDLE"), text);
    }
    /** A transition no modelled input explains (here a 3-block displacement in one packet) stops the chain with the nearest inputs and their residuals as a diagnostic - attributable, never a verdict. */
    @Test void anUnexplainedTransitionListsTheNearestInputsWithoutAVerdict() throws Exception {
        PhysicsScene ps = new PhysicsScene(); Timeline tl = new Timeline(status(5, 8.5, SURFACE, 8.5, true, 0f)); ReconstructionTracer t = tracer(ps, tl); UUID u = new UUID(2, 6);
        t.arm(u, PhysicsQualification.Scenario.HOLD_W, ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        tl.publish(status(6, 8.5, SURFACE, 11.5, true, 0f));
        Thread.sleep(30);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(text.contains("NO CANDIDATE under H<=3"), text);
        assertTrue(text.contains("nearest (diagnostic only, one tick from 1 frontier state(s), yaw 0.0): W-J-R-@start d=("), text); // the sprint-jump is the nearest legal input to a 3-block hop
        assertTrue(text.contains("noCandidate=1"), text);
        for (String l : report) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("invalid") || lo.contains("violation"), l); }
    }
    @Test void yawAlignmentsEnumerateEverySwitchTick() {
        List<float[]> one = ReconstructionTracer.yawAlignments(3, 0f, List.of(45f), 45f);
        assertEquals(3, one.size(), "switch at tick 1, 2 or 3");
        assertArrayEquals(new float[] { 45f, 45f, 45f }, one.get(0)); assertArrayEquals(new float[] { 0f, 45f, 45f }, one.get(1)); assertArrayEquals(new float[] { 0f, 0f, 45f }, one.get(2));
        assertEquals(1, ReconstructionTracer.yawAlignments(2, 0f, List.of(), 90f).size()); assertArrayEquals(new float[] { 0f, 90f }, ReconstructionTracer.yawAlignments(2, 0f, List.of(), 90f).get(0));
    }
}
