package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.MovementValidationRunnerTest.KEY;
import static me.nicolas.papea2ac.testing.MovementValidationRunnerTest.correction;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.EvidenceRunner;
import me.nicolas.papea2ac.core.MitigationRunner;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.ContextEvent;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 end to end through the REAL live runner: safe anchors come from real Phase 8 results (VALID, all 13 dimensions, concrete, grounded,
 * standing box clear); four controlled side-chain injections - each from a different live anchor, so four independent Phase 9 groups - make the
 * SIDE policy eligible while the live chain stays legitimate; the target is the most recent LIVE anchor. In OBSERVE_ONLY nothing is applied. In
 * ENFORCE exactly one intent reaches the main-thread queue after the durable receipt; the correction is captured and confirmed through the
 * ordinary Phase 7 pipeline, the live chain re-anchors at it (Phase 8 correction semantics = the post-setback resync boundary), legitimate
 * movement from the target validates normally and the intent ends RECOVERED - no INVALID, no evidence, no second setback from the correction.
 */
class MitigationIntegrationTest {
    static final class Rig {
        final PhysicsScene ps = new PhysicsScene(); final MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        final List<MitigationIntent> captured = new ArrayList<>(), queued = new ArrayList<>(); EffectMode mode;
        final MitigationActor.Metrics metrics = new MitigationActor.Metrics();
        final MitigationActor actor = new MitigationActor(KEY, MitigationBounds.DEFAULT, new AnchorAdmission(ps.scene.engine, p -> CollisionTestSupport.standing(p.x(), p.y(), p.z())), i -> { captured.add(i); return true; }, i -> { queued.add(i); return true; }, (s0, n0) -> true, metrics);
        final EvidenceRunner evidence = new EvidenceRunner(KEY, new EvidenceRunner.Metrics());
        Rig(EffectMode mode) {
            this.mode = mode;
            h.runner.attachEvidence(evidence); h.runner.attachMitigation(new MitigationRunner(KEY, actor, new HealthLatch(), () -> this.mode, () -> "minecraft:overworld", metrics));
            h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        }
        PhysicsState walk(PhysicsState from, int ticks) {
            List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < ticks; i++) in.add(MovementInput.forward(0f, false));
            Truth t = simulate(ps, ps.env(), from, in, List.of(), h.rec, true, false); h.feed(); return t.after(ticks);
        }
        /** One controlled impossible transmission on the side chain (+0.5 x in one tick): emitted at the next REAL tick end of the live walk. */
        void injectOnce() { String r = h.runner.inject(new Vector3(0.5, 0, 0), 1); assertTrue(r.startsWith("INJECTING"), r); }
    }

    static PhysicsState start(Rig r) {
        correction(r.h, 1, 8.5, SURFACE, 8.5, 0f); r.h.feed();
        return settled(r.ps, 8.5, SURFACE, 8.5).withPosition(new Vec3d(8.5, SURFACE, 8.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
    }

    @Test void observeOnlyFindsTheSideChainEligibleAndAppliesNothing() {
        Rig r = new Rig(EffectMode.OBSERVE_ONLY); PhysicsState s = start(r);
        s = r.walk(s, 6);
        assertTrue(r.actor.status().anchors() > 0, "real VALID results were admitted as safe anchors: refusal=" + r.actor.status().lastAnchorRefusal());
        for (int k = 0; k < 4; k++) { r.injectOnce(); s = r.walk(s, 2); }
        var st = r.actor.status();
        List<MitigationActor.Decision> side = st.recentDecisions().stream().filter(MitigationActor.Decision::sideChain).toList();
        assertTrue(side.stream().anyMatch(d -> d.reason() == Reason.MODE_OBSERVE_ONLY && d.target() != null), "the side policy became eligible with a live target: " + side.stream().map(MitigationActor.Decision::describe).toList());
        assertTrue(r.captured.isEmpty() && r.queued.isEmpty(), "OBSERVE_ONLY applies nothing");
        assertEquals(0, r.h.status().invalid(), "the live chain stayed legitimate"); assertEquals(0, st.liveChain());
        assertEquals(4, r.evidence.snapshot(true).totalEvents(), "four independent side-chain events");
    }
    @Test void enforceAppliesExactlyOneCorrectionAndRecoversWithoutALoop() {
        Rig r = new Rig(EffectMode.ENFORCE); PhysicsState s = start(r);
        s = r.walk(s, 6);
        for (int k = 0; k < 4 && r.captured.isEmpty(); k++) { r.injectOnce(); if (r.captured.isEmpty()) s = r.walk(s, 2); }
        assertEquals(1, r.captured.size(), "one intent proposed: " + r.actor.status().recentDecisions().stream().map(MitigationActor.Decision::describe).toList());
        MitigationIntent it = r.captured.get(0); assertTrue(it.sideChain()); assertEquals(IntentState.WAITING_CAPTURE, r.actor.state());
        r.actor.onCaptureReceipt(it.actionId(), true, "capsule:" + it.shortId(), r.h.rec.nanos);
        assertEquals(1, r.queued.size()); assertEquals(IntentState.QUEUED, r.actor.state());
        // the main thread applies it: STARTED, then the platform teleport is captured outbound and confirmed by the client (Phase 7 pipeline)
        r.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.STARTED, null, r.h.rec.nanos);
        Vector3 t = it.anchor().position();
        correction(r.h, 41, t.x(), t.y(), t.z(), 0f); r.h.feed();
        // captured at the exact target and confirmed; the client's position echo is validated by the ordinary pipeline as a VALID transition that
        // explains the correction's application - the coherent confirmed baseline - so the intent may already be RECOVERED here
        assertEquals(1, r.metrics.sent.sum(), "the captured correction was adopted as ours (exact target, absolute coordinates)"); assertEquals(1, r.metrics.confirmed.sum());
        assertTrue(r.actor.state() == IntentState.RECOVERY || (r.actor.state() == IntentState.IDLE && r.actor.status().recentOutcomes().get(0).terminal() == Terminal.RECOVERED), r.actor.status().recentOutcomes().toString());
        // legitimate movement from the target validates normally; the first coherent VALID ends the recovery
        PhysicsState after = settled(r.ps, t.x(), t.y(), t.z()).withPosition(new Vec3d(t.x(), t.y(), t.z())).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        r.walk(after, 8);
        assertEquals(IntentState.IDLE, r.actor.state()); assertEquals(Terminal.RECOVERED, r.actor.status().recentOutcomes().get(0).terminal());
        assertEquals(0, r.h.status().invalid(), "no live INVALID was caused by the correction"); assertEquals(0, r.evidence.status().totalEvents(), "no live evidence");
        assertEquals(1, r.queued.size(), "no second setback"); assertEquals(1, r.metrics.sent.sum()); assertEquals(1, r.metrics.recovered.sum());
    }
}
