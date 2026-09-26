package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;
/**
 * The live Phase 9 runner behind the validation runner: legitimate walking creates no evidence (VALID witnesses only); a live complete
 * INVALID creates exactly one event; the controlled side-chain injection creates evidence in the SIDE ledger only and the scheduled
 * re-delivery of the same record is a DUPLICATE; a corrupted mapping is contained; and at the engine level a replacement session starts with
 * fresh evidence while the counters stay bounded and UUID-free.
 */
class EvidenceRunnerTest {
    @Test void legitimateWalkingCreatesNoEvidenceAndALiveCompleteInvalidCreatesExactlyOne() {
        PhysicsScene ps = new PhysicsScene(); MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        EvidenceRunner.Metrics em = new EvidenceRunner.Metrics(); EvidenceRunner ev = new EvidenceRunner(MovementValidationRunnerTest.KEY, em); h.runner.attachEvidence(ev);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        MovementValidationRunnerTest.correction(h, 1, 12.5, SURFACE, 12.5, 0f); h.feed();
        PhysicsState post = settled(ps, 8.5, SURFACE, 8.5).withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 8; i++) in.add(MovementInput.forward(0f, i > 2));
        simulate(ps, ps.env(), post, in, List.of(), h.rec, true, false); h.feed();
        EvidenceStatus st = ev.status();
        assertEquals(0, st.activeEvents()); assertEquals(0, st.totalEvents()); assertTrue(st.validObserved() >= 7, st.toString()); assertEquals(h.status().valid(), st.validObserved(), "every live VALID was consumed once"); assertEquals(0, em.positiveEvents.sum()); assertEquals(st.validObserved(), em.legitimateWitness.sum());
        // a live complete INVALID (an impossible transmission under a complete context)
        h.rec.advance(TICK_NANOS); h.rec.movement(12.5 + 1.5, SURFACE, 12.5, 0f, true); h.rec.advance(1_000_000L); h.rec.tickEnd(); h.feed();
        assertEquals("INVALID", h.status().lastClassification(), String.join("\n", h.status().lastResult()));
        st = ev.status(); assertEquals(1, st.activeEvents()); assertEquals(1, st.totalEvents()); assertEquals(1, st.currentRun()); assertEquals(1, em.positiveEvents.sum()); assertEquals(1, em.consumed.sum() - em.legitimateWitness.sum());
        assertTrue(st.live().stream().anyMatch(l -> l.contains("HORIZONTAL_EXCESS") || l.contains("UNEXPLAINED_DISPLACEMENT")), String.join("\n", st.live()));
        assertTrue(st.recent().get(st.recent().size() - 1).contains("POSITIVE_EVENT"), st.recent().toString());
        for (String l : st.live()) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("ban") || lo.contains("kick") || lo.contains("setback") || lo.contains("punish"), l); }
    }
    @Test void theSideChainInjectionFeedsOnlyTheSideLedgerAndItsReDeliveryIsADuplicate() {
        PhysicsScene ps = new PhysicsScene(); MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        EvidenceRunner.Metrics em = new EvidenceRunner.Metrics(); EvidenceRunner ev = new EvidenceRunner(MovementValidationRunnerTest.KEY, em); h.runner.attachEvidence(ev);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        MovementValidationRunnerTest.correction(h, 1, 8.5, SURFACE, 8.5, 0f); h.feed();
        assertTrue(h.runner.inject(new Vector3(0.5, 0, 0), 2).startsWith("INJECTING"));
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 20; i++) idle.add(MovementInput.idle(0f));
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        simulate(ps, ps.env(), rest, idle, List.of(), h.rec, true, false); h.feed();
        EvidenceStatus st = ev.status();
        assertEquals(0, st.totalEvents(), "the live ledger never sees the synthetic transmission"); assertEquals(0, em.positiveEvents.sum());
        assertEquals(1, em.sideConsumed.sum()); assertEquals(1, em.sidePositiveEvents.sum()); assertTrue(st.side().get(0).contains("active=1/1"), String.join("\n", st.side()));
        assertTrue(st.sideRecent().get(0).contains("POSITIVE_EVENT"), st.sideRecent().toString());
        // the scheduled re-delivery runs on the shard at the next tick end: DUPLICATE, still one event
        assertTrue(ev.requestReingestLastSide().startsWith("SCHEDULED"));
        h.rec.advance(TICK_NANOS); h.rec.tickEnd(); h.feed();
        st = ev.status(); assertEquals(1, em.sideDuplicates.sum()); assertTrue(st.side().get(0).contains("active=1/1"), String.join("\n", st.side())); assertTrue(st.sideRecent().stream().anyMatch(l -> l.contains("re-delivery") && l.contains("DUPLICATE")), st.sideRecent().toString());
        assertEquals(0, st.totalEvents()); assertEquals(0, em.positiveEvents.sum()); assertEquals(0, em.internalErrors.sum());
        assertEquals("REFUSED: no side-chain result to re-deliver yet", new EvidenceRunner(new me.nicolas.papea2ac.model.SessionKey(new UUID(9, 9), 3), em).requestReingestLastSide());
    }
    @Test void aReplacementSessionStartsWithFreshEvidenceAtTheEngineLevel() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            UUID u = new UUID(90, 1);
            assertTrue(engine.evidenceStatus(u).isEmpty()); assertEquals("REFUSED: no admitted session", engine.evidenceReingest(u));
            var first = engine.connect(u, loop, TransportCapability.observeOnly());
            long[] n = { 100 }; long[] w = { 1000 };
            first.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            first.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.GameEvent(3, 0f)));
            first.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.Correction(3, new PacketPayload.Position(8.5, 64, 8.5), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), Set.of())));
            first.ingress.bridge(new Observation.WriteOutcome(w[0], true));
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM, 3)));
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new PacketPayload.Movement(new PacketPayload.Position(8.5, 64, 8.5), new PacketPayload.Rotation(0f, 0f), false, false)));
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new PacketPayload.Movement(new PacketPayload.Position(8.6, 64, 8.5), new PacketPayload.Rotation(0f, 0f), true, false)));
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.evidenceStatus(u).map(s -> s.uncertainObserved() >= 1).orElse(false));
            EvidenceStatus before = engine.evidenceStatus(u).orElseThrow();
            assertEquals(0, before.totalEvents(), "an unknown world is UNCERTAIN: no evidence"); assertTrue(before.uncertainObserved() >= 1, before.toString()); assertEquals(1, before.sessionEpoch());
            var es = engine.evidenceEngineStatus(); assertTrue(es.consumed() >= 1); assertEquals(0, es.positiveEvents()); assertEquals(0, es.internalErrors()); assertFalse(es.toString().contains(u.toString()));
            var second = engine.connect(u, loop, TransportCapability.observeOnly());
            TimingMarkerSchedulingTest.pump(loop, 200);
            EvidenceStatus after = engine.evidenceStatus(u).orElseThrow();
            assertEquals(second.key.epoch(), after.sessionEpoch()); assertNotEquals(before.sessionEpoch(), after.sessionEpoch()); assertEquals(0, after.totalEvents()); assertEquals(0, after.uncertainObserved()); assertEquals(0, after.validObserved()); assertTrue(after.recent().isEmpty());
            assertEquals(2, engine.evidenceEngineStatus().sessionsCreated());
        } finally { engine.close(); loop.drain(); }
    }
}
