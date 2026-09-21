package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.PhysicsQualification;
import me.nicolas.papea2ac.core.TimelineStatus;
import me.nicolas.papea2ac.core.TimingTracer;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;

/**
 * The Phase 7 diagnostic tracer ({@code /papea2ac timing <player> arm|report|cancel}) against a real TimingLedger fed by the
 * synthetic client generator: a velocity flight is walked and its influence reported as applied at the true tick, a declared
 * input is reported by membership only, a teleport is reported as an application point, refusals and the vocabulary boundary.
 */
class TimingTracerTest {
    static TimelineStatus rest(long movementSeq, double x, double y, double z, float yaw, boolean onGround) {
        return new TimelineStatus(1, movementSeq, 10, 1000, 0, false, 1, movementSeq, true, x, y, z, movementSeq, true, yaw, 0f, onGround, false, -1, false, 0, false, false, 0);
    }
    static final PhysicsQualification.ArmFacts FACTS = ReconstructionTracerTest.FACTS;
    static TimingTracer tracer(PhysicsScene ps) { return new TimingTracer(reconstructor(ps), ps.scene.engine, ps.behaviors, TimingProfile.PAPER_26_2); }
    static void noVerdicts(List<String> report) { for (String l : report) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("invalid") || lo.contains("violation") || lo.contains("suspicious") || lo.contains("punish"), l); } }

    @Test void aVelocityFlightIsWalkedAndTheInfluenceReportedAppliedAtItsTrueTick() {
        PhysicsScene ps = new PhysicsScene(); TimingTracer t = tracer(ps); UUID u = new UUID(3, 1);
        // the recorder's synthetic clock is far in the past of the report's System.nanoTime(): each interval is evaluated at its own target time, so the influence is not EXPIRED (P7-F7)
        TimingLedger ledger = new TimingLedger(new SessionKey(u, 4)); Recorder rec = new Recorder(ledger);
        String armed = t.arm(u, null, ledger, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 4, FACTS);
        assertTrue(armed.startsWith("ARMED timing"), armed); assertEquals(1, t.activeTraces());
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 8; i++) inputs.add(MovementInput.idle(0f));
        Truth truth = simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), inputs, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.3, 0.4, 0.1), 3, 1)), rec, true);
        String id = truth.applicationTick().keySet().iterator().next();
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(report.get(0).startsWith("timing trace sessionEpoch=4"), text);
        assertTrue(text.contains("influences=1"), text);
        assertTrue(text.contains("influence " + id + " velocity SET (0.3,0.4,0.1)") && text.contains("applied@step1/tick"), "the influence is reported applied in the first walked interval: " + text);
        assertTrue(text.contains("RESOLVED_UNIQUE"), text);
        assertTrue(report.get(report.size() - 1).startsWith("QUALIFYING: every transmission has at least one explaining timing hypothesis"), text);
        noVerdicts(report);
        assertEquals("trace cancelled", t.cancel(u)); assertEquals("no armed trace", t.cancel(u)); assertEquals(0, t.activeTraces());
        assertEquals(List.of("no armed timing trace for that player (arm one first)"), t.report(u));
    }
    @Test void aDeclaredInputIsReportedByMembershipOnly() {
        PhysicsScene ps = new PhysicsScene(); TimingTracer t = tracer(ps); UUID u = new UUID(3, 2);
        TimingLedger ledger = new TimingLedger(new SessionKey(u, 1)); Recorder rec = new Recorder(ledger); rec.nanos = System.nanoTime();
        String armed = t.arm(u, PhysicsQualification.Scenario.HOLD_W, ledger, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        assertTrue(armed.contains("declared HOLD_W, membership only"), armed);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 8; i++) inputs.add(MovementInput.forward(0f, false));
        simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), inputs, List.of(), rec, true);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(text.contains("declared=PRESENT"), text); assertFalse(text.contains("declared=ABSENT"), text);
        assertTrue(text.contains("declaredAbsent=0"), text);
        assertTrue(report.get(report.size() - 1).contains("and the declared input is in the candidate set of every explained interval"), text);
        noVerdicts(report);
        // a wrong declaration is reported absent, never judged
        UUID u2 = new UUID(3, 3); TimingLedger l2 = new TimingLedger(new SessionKey(u2, 1)); Recorder r2 = new Recorder(l2); r2.nanos = System.nanoTime();
        t.arm(u2, PhysicsQualification.Scenario.HOLD_S, l2, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), inputs, List.of(), r2, true);
        List<String> wrong = t.report(u2); String wt = String.join("\n", wrong);
        assertTrue(wrong.get(wrong.size() - 1).startsWith("NOT QUALIFYING") && wt.contains("declared input absent from"), wt);
        assertTrue(wt.contains("not a verdict"), wt);
        noVerdicts(wrong);
    }
    @Test void aTeleportIsReportedAsAnApplicationPointAndTheChainContinuesFromIt() {
        PhysicsScene ps = new PhysicsScene(); TimingTracer t = tracer(ps); UUID u = new UUID(3, 4);
        TimingLedger ledger = new TimingLedger(new SessionKey(u, 1)); Recorder rec = new Recorder(ledger); rec.nanos = System.nanoTime();
        assertTrue(t.arm(u, null, ledger, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).startsWith("ARMED"));
        rec.advance(1_000_000L); rec.tickEnd();
        rec.advance(TICK_NANOS / 8); rec.teleport(9, 12.5, SURFACE, 12.5, 0f, 0);
        rec.advance(TICK_NANOS / 4); rec.confirm(9); rec.advance(100_000L); rec.movement(12.5, SURFACE, 12.5, 0f, true);
        // the client's game thread applied the correction between ticks: the next tick runs from the corrected position (no ground support yet, no velocity)
        PhysicsState post = settled(ps, 8.5, SURFACE, 8.5).withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 4; i++) inputs.add(MovementInput.forward(0f, false));
        simulate(ps, ps.env(), post, inputs, List.of(), rec, true, false);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(text.contains("correction 9 sent@") && text.contains("confirmed@"), text);
        assertTrue(text.contains("teleports sent=1 confirmed=1"), text);
        assertTrue(text.contains("align=TELEPORT_CONFIRM"), "the confirm's position is an application point: " + text);
        assertTrue(report.get(report.size() - 1).startsWith("QUALIFYING"), text);
        noVerdicts(report);
    }
    @Test void refusalsAndEmptyTracesAreExplicit() {
        PhysicsScene ps = new PhysicsScene(); TimingTracer t = tracer(ps); UUID u = new UUID(3, 5);
        TimingLedger ledger = new TimingLedger(new SessionKey(u, 1));
        assertTrue(t.arm(u, null, ledger, rest(100, 8.5, SURFACE + 1, 8.5, 0f, false), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).startsWith("REFUSED: the player must be standing still"));
        assertTrue(t.arm(u, null, ledger, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).startsWith("ARMED"));
        List<String> report = t.report(u);
        assertEquals("NOT QUALIFYING: no timing events observed after arming", report.get(report.size() - 1), String.join("\n", report));
        // a rest position that is not a fixed point of the simulator (mid-air) is refused at report time, not guessed
        UUID u2 = new UUID(3, 6);
        assertTrue(t.arm(u2, null, new TimingLedger(new SessionKey(u2, 1)), rest(100, 8.5, SURFACE + 0.5, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).startsWith("ARMED"));
        List<String> r2 = t.report(u2);
        assertTrue(r2.get(r2.size() - 1).startsWith("NOT QUALIFYING: the observed rest position is not a resting fixed point"), String.join("\n", r2));
        // the trace cap
        for (int i = 0; i < TimingTracer.MAX_TRACES; i++) t.arm(new UUID(4, i), null, new TimingLedger(new SessionKey(new UUID(4, i), 1)), rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        assertEquals(TimingTracer.MAX_TRACES, t.activeTraces());
        assertTrue(t.arm(new UUID(4, 99), null, new TimingLedger(new SessionKey(new UUID(4, 99), 1)), rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS).startsWith("REFUSED: " + TimingTracer.MAX_TRACES + " traces already armed"));
    }
    /** P7-F9: a standing player's 20-tick reminders are walked (they count as steps) but folded into one line, and a closed session is flagged. */
    @Test void restResendsAreFoldedAndAClosedSessionIsFlagged() {
        PhysicsScene ps = new PhysicsScene(); TimingTracer t = tracer(ps); UUID u = new UUID(3, 8);
        TimingLedger ledger = new TimingLedger(new SessionKey(u, 1)); Recorder rec = new Recorder(ledger);
        t.arm(u, null, ledger, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 100; i++) inputs.add(MovementInput.idle(0f)); for (int i = 0; i < 3; i++) inputs.add(MovementInput.forward(0f, false));
        simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), inputs, List.of(), rec, true);
        List<String> report = t.report(u); String text = String.join("\n", report);
        assertTrue(text.contains("(5 rest re-send(s) at the same position resolved uniquely through the silent closure)"), text);
        assertEquals(3, report.stream().filter(l -> l.startsWith("seq=")).count(), "only the walking transmissions are listed: " + text);
        assertTrue(text.contains("summary: steps=8 explained=8"), text);
        assertFalse(text.contains("CLOSED"), text);
        ledger.close();
        List<String> closed = t.report(u);
        assertTrue(closed.get(0).startsWith("NOTE: this trace belongs to session epoch 1, which is CLOSED"), String.join("\n", closed));
    }
    @Test void eventsArePublishedOnlyWhileATraceIsArmed() {
        PhysicsScene ps = new PhysicsScene(); TimingTracer t = tracer(ps); UUID u = new UUID(3, 7);
        TimingLedger ledger = new TimingLedger(new SessionKey(u, 1)); Recorder rec = new Recorder(ledger); rec.nanos = System.nanoTime();
        rec.advance(1_000_000L); rec.tickEnd();
        assertFalse(ledger.view().eventsPublished()); assertTrue(ledger.view().events().isEmpty(), "no published events without a diagnostic");
        t.arm(u, null, ledger, rest(100, 8.5, SURFACE, 8.5, 0f, true), ps.scene.world.currentView(), ps.scene.world.worldEpoch(), 1, FACTS);
        rec.advance(TICK_NANOS); rec.tickEnd();
        assertTrue(ledger.view().eventsPublished()); assertFalse(ledger.view().events().isEmpty());
        t.cancel(u);
        rec.advance(TICK_NANOS); rec.tickEnd();
        assertFalse(ledger.view().eventsPublished()); assertTrue(ledger.view().events().isEmpty(), "cancel stops publishing and drops the published copy");
    }
}
