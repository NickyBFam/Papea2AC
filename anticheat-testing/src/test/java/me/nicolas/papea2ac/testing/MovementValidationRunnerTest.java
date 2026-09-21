package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.MovementValidationRunner;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * The live Phase 8 runner over the synthetic client generator: anchoring at a correction application point and at an observed rest,
 * the chain through legitimate walking (all VALID), the controlled side-chain injection (INVALID on the side chain, the live chain
 * untouched), unsupported locomotion (creative / flight) and a nearby player (entity neighbourhood UNKNOWN) as UNCERTAIN, and a
 * world change dropping the anchor. The runner is fed exactly what the engine feeds it: each timing event after the ledgers applied it.
 */
class MovementValidationRunnerTest {
    static final SessionKey KEY = new SessionKey(new UUID(8, 1), 1);
    static final class Harness {
        final PhysicsScene ps; final TimingLedger timing = new TimingLedger(KEY); final MovementContextLedger context = new MovementContextLedger(KEY); final MovementValidationRunner.Metrics metrics = new MovementValidationRunner.Metrics(); final MovementValidationRunner runner; final Recorder rec;
        Harness(PhysicsScene ps) { this.ps = ps; runner = new MovementValidationRunner(KEY, new MovementValidator(new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator))), ps.simulator, ps.scene.engine, ps.behaviors, TimingProfile.PAPER_26_2, metrics); rec = new Recorder(timing); rec.nanos = System.nanoTime(); context.apply(KEY, new ContextEvent.BorderInitialized(0, 0, 0, 5.9999968E7, 5.9999968E7, 0)); /* every real join sends the border */ }
        /** Feeds every recorded event not yet fed. */
        int fed;
        void feed() { for (; fed < rec.events.size(); fed++) runner.onEvent(rec.events.get(fed), timing.view(), context.view(), ps.scene.world.currentView(), ps.scene.world.worldEpoch()); }
        ValidationStatus status() { return runner.status(); }
        void ctx(ContextEvent e) { context.apply(KEY, e); }
    }
    /** A join-like absolute correction to (x, y, z) confirmed and echoed by the client: the runner's first anchor. */
    static void correction(Harness h, long id, double x, double y, double z, float yaw) {
        h.rec.advance(1_000_000L); h.rec.tickEnd(); h.rec.advance(TICK_NANOS / 8); h.rec.teleport(id, x, y, z, yaw, 0); h.rec.advance(TICK_NANOS / 4); h.rec.confirm(id); h.rec.advance(100_000L); h.rec.movement(x, y, z, yaw, false);
    }

    @Test void aConfirmedCorrectionAnchorsTheChainAndLegitimateWalkingIsValid() {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        h.rec.tickEnd(); h.rec.advance(TICK_NANOS); h.rec.movement(8.5, SURFACE, 8.5, 0f, true); h.feed(); // before any anchor: unanchored
        assertFalse(h.status().anchored()); assertEquals(1, h.metrics.uncertainUnanchored.sum());
        correction(h, 1, 12.5, SURFACE, 12.5, 0f); h.feed();
        assertTrue(h.status().anchored(), h.status().anchorKind()); assertTrue(h.status().anchorKind().startsWith("correction 1")); assertEquals(6, h.status().frontierNodes()); assertTrue(h.status().frontierComplete());
        // P8-F1 (found live): outbound write outcomes are ledger business, never slice events - thousands of them between two transmissions must not exhaust the slice bound
        for (int i = 0; i < 2 * MovementValidationRunner.MAX_SLICE; i++) h.runner.onEvent(new TimingEvent.WriteResolved(h.rec.events.get(h.rec.events.size() - 1).sequence() + 1 + i, h.rec.nanos, 5000 + i, true), h.timing.view(), h.context.view(), ps.scene.world.currentView(), ps.scene.world.worldEpoch());
        assertTrue(h.status().anchored(), "write outcomes never fill the slice: " + h.status().anchorKind());
        PhysicsState post = settled(ps, 8.5, SURFACE, 8.5).withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 6; i++) in.add(MovementInput.forward(0f, i > 2));
        simulate(ps, ps.env(), post, in, List.of(), h.rec, true, false); h.feed();
        ValidationStatus st = h.status();
        assertTrue(st.transitions() >= 5, st.toString()); assertEquals(st.transitions(), st.valid(), String.join("\n", st.recent())); assertEquals(0, st.invalid()); assertEquals("VALID", st.lastClassification());
        assertEquals(st.transitions(), h.metrics.valid.sum()); assertTrue(h.metrics.timingBranches.sum() >= st.transitions());
        for (String l : st.lastResult()) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("violation") || lo.contains("punish"), l); }
    }
    @Test void anObservedRestAnchorsTheChainWhenNoCorrectionIsAvailable() {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        h.rec.tickEnd(); h.rec.advance(TICK_NANOS); h.rec.movement(8.5, SURFACE, 8.5, 0f, true); // first position (no anchor: no reminder yet)
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 20; i++) idle.add(MovementInput.idle(0f));
        simulate(ps, ps.env(), rest, idle, List.of(), h.rec, true); h.feed(); // the 20-tick reminder at the same position: rest fixed point
        assertTrue(h.status().anchored(), h.status().anchorKind()); assertTrue(h.status().anchorKind().startsWith("rest"), h.status().anchorKind());
        // P8-F6 (found live): the real client's 20-tick reminder is POSITION-ONLY unless the view moved - it must anchor too (yaw = the last transmitted one)
        Harness p = new Harness(ps); p.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        p.rec.tickEnd(); p.rec.advance(TICK_NANOS); p.rec.movement(8.5, SURFACE, 8.5, 0f, true);
        for (int i = 0; i < 20; i++) { p.rec.advance(TICK_NANOS); p.rec.tickEnd(); }
        p.rec.advance(1_000_000L); p.rec.add(new TimingEvent.MovementObserved(p.rec.nextSeq(), p.rec.nanos, true, new Vector3(8.5, SURFACE, 8.5), false, new me.nicolas.papea2ac.model.Rotation(0f, 0f), true)); p.feed();
        assertTrue(p.status().anchored(), "a position-only reminder anchors at rest: " + p.status().anchorKind() + " / " + p.status().context()); assertTrue(p.status().anchorKind().startsWith("rest"), p.status().anchorKind());
        simulate(ps, ps.env(), rest, List.of(MovementInput.forward(0f, false), MovementInput.forward(0f, false)), List.of(), h.rec, true, false); h.feed();
        assertEquals("VALID", h.status().lastClassification(), String.join("\n", h.status().recent())); assertEquals(0, h.status().invalid());
    }
    @Test void anInjectedSideChainIsClassifiedWithoutTouchingTheLiveChain() {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        correction(h, 1, 8.5, SURFACE, 8.5, 0f); h.feed();
        assertEquals("REFUSED: 1..40 ticks", h.runner.inject(new Vector3(1, 0, 0), 0));
        long worldRevision = ps.scene.world.currentView().revision(); var timingBefore = h.timing.view().counters(); long influencesBefore = h.timing.view().counters().externalMotions(); int teleportsBefore = h.timing.view().teleports().size();
        String started = h.runner.inject(new Vector3(0.5, 0, 0), 3); assertTrue(started.startsWith("INJECTING 3"), started);
        // the real client keeps standing (idle ticks): the side chain sees +0.5 per tick, the live chain sees the reminder
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 20; i++) idle.add(MovementInput.idle(0f));
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        simulate(ps, ps.env(), rest, idle, List.of(), h.rec, true, false); h.feed();
        ValidationStatus st = h.status();
        assertFalse(st.injected().isEmpty(), st.toString());
        assertTrue(st.injected().get(0).contains("INVALID") && st.injected().get(0).contains("HORIZONTAL_EXCESS"), String.join("\n", st.injected()));
        assertTrue(st.injected().stream().anyMatch(l -> l.contains("side chain ended (INVALID without continuation")), String.join("\n", st.injected()));
        assertEquals(0, st.invalid(), "the live chain never saw the synthetic packets: " + String.join("\n", st.recent())); assertEquals("VALID", st.lastClassification());
        assertEquals(1, h.metrics.injectedRequests.sum()); assertEquals(0, h.metrics.invalid.sum(), "injected results are not live counts");
        // isolation: the observer world, the timing ledger's influences/teleports and its counters (beyond the real events fed) are untouched by the side chain
        assertEquals(worldRevision, ps.scene.world.currentView().revision(), "the world never sees a synthetic transmission");
        assertEquals(influencesBefore, h.timing.view().counters().externalMotions()); assertEquals(teleportsBefore, h.timing.view().teleports().size());
        assertEquals(timingBefore.movements() + 1, h.timing.view().counters().movements(), "only the real 20-tick reminder reached the ledger: " + h.timing.view().counters());
    }
    @Test void creativeFlightAndNearbyPlayersMakeTransitionsUncertainNeverInvalid() {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "creative")); h.ctx(new ContextEvent.AbilitiesUpdated(1, false, true));
        correction(h, 1, 8.5, SURFACE, 8.5, 0f); h.feed();
        assertTrue(h.status().anchored());
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        // an impossible transmission under a flight-capable context: UNSUPPORTED_LOCOMOTION, never INVALID
        h.rec.advance(TICK_NANOS); h.rec.movement(10.0, SURFACE + 2, 8.5, 0f, false); h.rec.advance(1_000_000L); h.rec.tickEnd(); h.feed();
        assertEquals("UNCERTAIN", h.status().lastClassification(), String.join("\n", h.status().lastResult())); assertTrue(String.join("\n", h.status().lastResult()).contains("UNSUPPORTED_LOCOMOTION"));
        assertEquals(1, h.metrics.uncertainUnsupported.sum());
        // survival, but another player right next to the position: entity neighbourhood UNKNOWN
        PhysicsScene ps2 = new PhysicsScene(); Harness g = new Harness(ps2);
        g.ctx(new ContextEvent.GameModeChanged(1, "survival")); g.ctx(new ContextEvent.EntitySpawned(2, 77, "minecraft:player", 9.5, SURFACE, 8.5));
        correction(g, 1, 8.5, SURFACE, 8.5, 0f); g.feed();
        g.rec.advance(TICK_NANOS); g.rec.movement(10.0, SURFACE + 2, 8.5, 0f, false); g.rec.advance(1_000_000L); g.rec.tickEnd(); g.feed();
        assertEquals("UNCERTAIN", g.status().lastClassification(), String.join("\n", g.status().lastResult())); assertTrue(String.join("\n", g.status().lastResult()).contains("ENTITY_NEIGHBORHOOD_UNKNOWN"), String.join("\n", g.status().lastResult()));
        // a cow next to the player is irrelevant to client-side physics: complete context, INVALID stands
        PhysicsScene ps3 = new PhysicsScene(); Harness k = new Harness(ps3);
        List<String> sink = new ArrayList<>(); k.metrics.invalidSink = sink::add; // P8-F7: every live INVALID reaches the platform sink in full
        k.ctx(new ContextEvent.GameModeChanged(1, "survival")); k.ctx(new ContextEvent.EntitySpawned(2, 78, "minecraft:cow", 9.5, SURFACE, 8.5));
        correction(k, 1, 8.5, SURFACE, 8.5, 0f); k.feed();
        k.rec.advance(TICK_NANOS); k.rec.movement(10.0, SURFACE + 2, 8.5, 0f, false); k.rec.advance(1_000_000L); k.rec.tickEnd(); k.feed();
        assertEquals("INVALID", k.status().lastClassification(), String.join("\n", k.status().lastResult()));
        assertEquals(1, sink.size(), sink.toString()); assertTrue(sink.get(0).startsWith("live INVALID session=1 seq=") && sink.get(0).contains("completeness ALL"), sink.get(0));
        assertTrue(k.status().notable().stream().anyMatch(l -> l.contains("INVALID")), "the notable ring keeps it too");
    }
    @Test void anAppliedInfluenceIsRetiredFromTheChainScopeAndAnIncompleteChainReanchorsAtRest() {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        correction(h, 1, 8.5, SURFACE, 8.5, 0f); h.feed();
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        // P8-F4 (found live): a velocity applied at tick 3 with a 1-tick lead, then 30 ticks of landing and rest - the influence must leave the runner's scope once no continuation has it pending
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 30; i++) in.add(MovementInput.idle(0f));
        simulate(ps, ps.env(), rest, in, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.3, 0.4, 0.1), 3, 1)), h.rec, true, false); h.feed();
        ValidationStatus st = h.status();
        assertEquals(0, st.invalid(), String.join("\n", st.recent())); assertTrue(st.valid() >= 10, st.toString());
        assertTrue(st.recent().stream().anyMatch(l -> l.contains("EXTERNAL_MOTION_EXPLAINS")), "the velocity branch explained the flight: " + String.join("\n", st.recent()));
        String started = h.runner.inject(new Vector3(0.5, 0, 0), 1); assertTrue(started.startsWith("INJECTING"), "no stale influence blocks the injection after the flight: " + started);
        // stage an anchored-but-incomplete chain: a zero velocity applied inside a silent stretch (the Phase 7 closure branches influences at its boundary only: complete=false)
        // gives VALID reminders under an incomplete timing union (frontier incomplete); the client keeps standing -> the rest re-anchor restores completeness
        Harness g = new Harness(ps); g.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        correction(g, 1, 8.5, SURFACE, 8.5, 0f); g.feed();
        List<MovementInput> still = new ArrayList<>(); for (int i = 0; i < 110; i++) still.add(MovementInput.idle(0f));
        simulate(ps, ps.env(), rest, still, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.0, 0.0, 0.0), 3, 1)), g.rec, true, false); g.feed();
        ValidationStatus gs = g.status();
        assertEquals(0, gs.invalid(), String.join("\n", gs.notable()));
        assertTrue(gs.notable().stream().anyMatch(l -> l.contains("missing=[") && !l.endsWith("missing=[frontier]")), "some step was VALID under an incomplete union: " + String.join("\n", gs.notable()));
        assertTrue(gs.recent().stream().anyMatch(l -> l.contains("rest re-anchor")), "the chain re-anchored at rest after the incomplete stretch: " + gs.anchorKind() + "\n" + String.join("\n", gs.recent()));
        assertTrue(gs.frontierComplete(), gs.toString()); assertTrue(gs.anchorKind().startsWith("rest"), gs.anchorKind());
    }
    @Test void aWorldChangeOrCoverageBreakDropsTheAnchorAndTheChainReanchorsLater() {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        correction(h, 1, 8.5, SURFACE, 8.5, 0f); h.feed(); assertTrue(h.status().anchored());
        h.rec.advance(TICK_NANOS); h.rec.discontinuity(1); h.feed();
        assertFalse(h.status().anchored()); assertTrue(h.status().anchorKind().contains("world change")); assertEquals(1, h.metrics.anchorsLost.sum());
        correction(h, 2, 20.5, SURFACE, 20.5, 0f); h.feed(); assertTrue(h.status().anchored(), h.status() + " teleports=" + h.timing.view().teleports()); assertEquals(2, h.metrics.anchors.sum());
        h.rec.advance(TICK_NANOS); h.rec.coverageBreak("OVERLOADED"); h.feed();
        assertFalse(h.status().anchored()); assertTrue(h.status().anchorKind().contains("coverage break"));
    }
}
