package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 7 teleport round trip (brief sections 32, 40, 64): hidden truths where the server sends an absolute correction while the
 * client keeps ticking (0-2 pre-application ticks, its movement packets are pre-application), then applies it - confirm followed
 * by the corrected position - and moves on from the corrected state. The gate: pre-application transmissions are explained as
 * ordinary movement flagged PRE_APPLICATION, the confirm's position is the APPLICATION_POINT (no physics bridge across it), the
 * post-teleport state is in the union frontier, and later transmissions are explained from it. Supersession and a stale confirm
 * are exercised explicitly.
 */
class TeleportTimingRoundTripTest {
    record Case(long seed, List<ChainStep> chain, int preTicks, TimingEvent.MovementObserved applicationTx, Recorder rec) {}

    /** One generated teleport scenario (the Phase 7 gate generator, reused by the Phase 8 corpus): pre-application ticks, the correction, its application point, post ticks; null when unsimulable. */
    record TeleportScenario(PhysicsState start, float yaw, Recorder rec, List<PhysicsState> preStates, List<TimingEvent.MovementObserved> preTx, PhysicsState post, TimingEvent.MovementObserved applied, List<PhysicsState> postStates, List<TimingEvent.MovementObserved> postTx, int before, int lead) {
        PhysicsState truthFor(TimingEvent.MovementObserved target) { for (int k = 0; k < preTx.size(); k++) if (preTx.get(k) == target) return preStates.get(k); if (target == applied) return post; for (int k = 0; k < postTx.size(); k++) if (postTx.get(k) == target) return postStates.get(k); return null; }
    }
    static TeleportScenario generate(long seed, PhysicsScene ps, PhysicsEnvironment env) {
        SplittableRandom r = new SplittableRandom(seed);
        PhysicsState start = settled(ps, 8.5 + r.nextInt(3), SURFACE, 8.5 + r.nextInt(3));
        float yaw = ReconstructionRoundTripTest.randomYaw(r);
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd();
        int before = 1 + r.nextInt(3), lead = r.nextInt(3), after = 2 + r.nextInt(3);
        List<MovementInput> pre = new ArrayList<>(); for (int i = 0; i < before + lead; i++) pre.add(r.nextInt(3) == 0 ? MovementInput.idle(yaw) : ReconstructionRoundTripTest.randomInput(r, yaw));
        // pre-application ticks (the correction is sent after `before` ticks and applied after `lead` more)
        Truth truthPre = null; PhysicsState s = start; double lx = start.position().x(), ly = start.position().y(), lz = start.position().z();
        List<PhysicsState> preStates = new ArrayList<>(); List<TimingEvent.MovementObserved> preTx = new ArrayList<>();
        long teleportId = 40 + r.nextInt(5); double tx = 12.5 + r.nextInt(4), tz = 12.5 + r.nextInt(4);
        for (int i = 1; i <= before + lead; i++) {
            rec.advance(TICK_NANOS / 2);
            if (i == before + 1) { rec.teleport(teleportId, tx, SURFACE, tz, yaw, 0); rec.advance(1_000_000L); }
            PhysicsResult pr = ps.simulator.simulate(s, pre.get(i - 1), env); if (!pr.exact()) return null; s = pr.next(); preStates.add(s);
            boolean send = !OBS.withinSendThreshold(s.position().x(), s.position().y(), s.position().z(), lx, ly, lz);
            TimingEvent.MovementObserved m = null; if (send) { m = rec.movement(s.position().x(), s.position().y(), s.position().z(), yaw, s.onGround()); lx = s.position().x(); ly = s.position().y(); lz = s.position().z(); }
            preTx.add(m); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2);
        }
        if (lead == 0) { rec.advance(TICK_NANOS / 8); rec.teleport(teleportId, tx, SURFACE, tz, yaw, 0); } // sent in the gap after the last pre tick, applied before the next
        // application: confirm + corrected position from the client's game thread, in the gap before the next tick
        rec.advance(TICK_NANOS / 4); rec.confirm(teleportId); rec.advance(100_000L);
        TimingEvent.MovementObserved applied = rec.movement(tx, SURFACE, tz, yaw, s.onGround());
        PhysicsState post = s.withPosition(new Vec3d(tx, SURFACE, tz)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        // post-teleport ticks
        List<MovementInput> postInputs = new ArrayList<>(); for (int i = 0; i < after; i++) postInputs.add(r.nextInt(3) == 0 ? MovementInput.idle(yaw) : ReconstructionRoundTripTest.randomInput(r, yaw));
        Recorder shared = rec; PhysicsState ps2 = post; List<PhysicsState> postStates = new ArrayList<>(); List<TimingEvent.MovementObserved> postTx = new ArrayList<>();
        lx = tx; ly = SURFACE; lz = tz;
        for (int i = 1; i <= after; i++) {
            shared.advance(TICK_NANOS / 2);
            PhysicsResult pr = ps.simulator.simulate(ps2, postInputs.get(i - 1), env); if (!pr.exact()) return null; ps2 = pr.next(); postStates.add(ps2);
            boolean send = !OBS.withinSendThreshold(ps2.position().x(), ps2.position().y(), ps2.position().z(), lx, ly, lz);
            TimingEvent.MovementObserved m = null; if (send) { m = shared.movement(ps2.position().x(), ps2.position().y(), ps2.position().z(), yaw, ps2.onGround()); lx = ps2.position().x(); ly = ps2.position().y(); lz = ps2.position().z(); }
            postTx.add(m); shared.advance(1_000_000L); shared.tickEnd(); shared.advance(TICK_NANOS / 2);
        }
        return new TeleportScenario(start, yaw, rec, preStates, preTx, post, applied, postStates, postTx, before, lead);
    }
    static Case run(long seed, PhysicsScene ps, PhysicsEnvironment env) {
        TeleportScenario sc = generate(seed, ps, env); if (sc == null) return null;
        PhysicsState start = sc.start(); float yaw = sc.yaw(); Recorder rec = sc.rec(); List<PhysicsState> preStates = sc.preStates(); List<TimingEvent.MovementObserved> preTx = sc.preTx(); PhysicsState post = sc.post(); TimingEvent.MovementObserved applied = sc.applied(); List<PhysicsState> postStates = sc.postStates(); List<TimingEvent.MovementObserved> postTx = sc.postTx(); int before = sc.before(), lead = sc.lead();
        // chain from the true start and check the truth at each transmission
        TimingReconstruction timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator));
        TimingReconstruction.Frontier frontier = TimingReconstruction.Frontier.of(start);
        long anchorSeq = 100, anchorNanos = 1_000_000_000L; boolean anchorInTick = true; Vector3 lastSent = new Vector3(start.position().x(), start.position().y(), start.position().z());
        List<ChainStep> chain = new ArrayList<>(); int from = 0;
        for (int i = 0; i < rec.events.size(); i++) {
            TimingEvent e = rec.events.get(i);
            if (!(e instanceof TimingEvent.MovementObserved target)) continue;
            TimingRequest req = new TimingRequest(new ArrayList<>(rec.events.subList(from, i + 1)), anchorSeq, anchorNanos, anchorInTick, lastSent, yaw, target, List.of(), rec.ledger.view().teleports(), rec.ledger.view().rtt(), true, false, 0, rec.nanos + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
            from = i + 1;
            TimingReconstruction.Result res = timing.run(req, frontier, env, ReconstructionRequest.SearchBudget.DEFAULT);
            PhysicsState truth = null;
            for (int k = 0; k < preTx.size(); k++) if (preTx.get(k) == target) truth = preStates.get(k);
            if (target == applied) truth = post;
            for (int k = 0; k < postTx.size(); k++) if (postTx.get(k) == target) truth = postStates.get(k);
            boolean retained = false; for (TimingReconstruction.Node n : res.next().nodes()) if (n.state().equals(truth)) retained = true;
            chain.add(new ChainStep(target, res, truth, retained));
            if (res.next().isEmpty()) break;
            frontier = res.next(); anchorSeq = target.sequence(); anchorNanos = target.nanos(); lastSent = target.position(); anchorInTick = res.targetInTick();
        }
        return new Case(seed, chain, lead, applied, rec);
    }

    @Test void hundredsOfHiddenTeleportOrderingsSurviveTimingResolution() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env();
        StringBuilder failures = new StringBuilder(); int cases = 0, applicationSteps = 0, preSteps = 0;
        for (long seed = 30_000; seed < 30_500; seed++) {
            Case c = run(seed, ps, env); if (c == null) continue; cases++;
            for (ChainStep s : c.chain()) {
                if (s.truthAfter() != null && !s.truthRetained()) { failures.append("seed ").append(seed).append(": truth lost at seq ").append(s.target().sequence()).append('\n').append(describe(c.chain())).append('\n'); break; }
                if (s.target() == c.applicationTx()) {
                    applicationSteps++;
                    boolean point = s.result().steps().stream().anyMatch(st -> st.hypothesis().teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT && st.explains());
                    if (!point) failures.append("seed ").append(seed).append(": the confirm's position was not resolved as the application point\n").append(describe(c.chain())).append('\n');
                    if (s.result().steps().stream().anyMatch(st -> st.hypothesis().horizon() > 0)) failures.append("seed ").append(seed).append(": a physics hypothesis bridged the correction\n");
                } else if (s.target().sequence() < c.applicationTx().sequence()) {
                    boolean afterSend = s.result().resolutions().stream().anyMatch(res -> res.hypotheses().stream().anyMatch(h -> h.teleport() == TimingHypothesis.TeleportRelation.PRE_APPLICATION));
                    if (afterSend) preSteps++;
                }
            }
            if (failures.length() > 20_000) break;
        }
        System.out.println("teleport timing round trip: cases=" + cases + " applicationSteps=" + applicationSteps + " preApplicationSteps=" + preSteps);
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(cases >= 300 && applicationSteps == cases, "cases " + cases + " application steps " + applicationSteps);
        assertTrue(preSteps > 50, "pre-application transmissions were exercised: " + preSteps);
    }
    /** Supersession: A sent, B sent, the client confirms A then B; the chain lands on B's position; a stale confirm of an unknown id resolves nothing. */
    @Test void supersessionAndStaleConfirmsResolveOnlyWhatTheyProve() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd();
        rec.teleport(1, 12.5, SURFACE, 12.5, 0f, 0); rec.teleport(2, 14.5, SURFACE, 14.5, 0f, 0); rec.confirm(77);
        rec.advance(2_000_000L); rec.confirm(1); rec.advance(100_000L); TimingEvent.MovementObserved a = rec.movement(12.5, SURFACE, 12.5, 0f, true);
        rec.advance(1_000_000L); rec.confirm(2); rec.advance(100_000L); TimingEvent.MovementObserved b = rec.movement(14.5, SURFACE, 14.5, 0f, true);
        rec.advance(TICK_NANOS); // the corrected position was sent between ticks: the next tick (its movement, then its tick end) follows without an intervening tick end
        PhysicsState post = start.withPosition(new Vec3d(14.5, SURFACE, 14.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        PhysicsResult pr = ps.simulator.simulate(post, MovementInput.forward(0f, false), env); assertTrue(pr.exact());
        TimingEvent.MovementObserved c = rec.movement(pr.next().position().x(), pr.next().position().y(), pr.next().position().z(), 0f, pr.next().onGround()); rec.advance(1_000_000L); rec.tickEnd();
        TimingReconstruction timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator));
        TimingReconstruction.Frontier f = TimingReconstruction.Frontier.of(start); long anchor = 100, anchorNanos = 1_000_000_000L; boolean inTick = true; Vector3 last = new Vector3(8.5, SURFACE, 8.5); int from = 0;
        for (TimingEvent.MovementObserved target : List.of(a, b, c)) {
            int idx = rec.events.indexOf(target);
            TimingRequest req = new TimingRequest(new ArrayList<>(rec.events.subList(from, idx + 1)), anchor, anchorNanos, inTick, last, 0f, target, List.of(), rec.ledger.view().teleports(), rec.ledger.view().rtt(), true, false, 0, rec.nanos + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
            from = idx + 1;
            TimingReconstruction.Result res = timing.run(req, f, env, ReconstructionRequest.SearchBudget.DEFAULT);
            assertFalse(res.next().isEmpty(), String.join("\n", res.describe()));
            if (target == a || target == b) assertTrue(res.steps().stream().allMatch(st -> st.hypothesis().teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT), String.join("\n", res.describe()));
            else assertEquals(TimingReconstruction.Status.RESOLVED_UNIQUE, res.status(), String.join("\n", res.describe()));
            f = res.next(); anchor = target.sequence(); anchorNanos = target.nanos(); last = target.position(); inTick = res.targetInTick();
        }
        assertEquals(1, rec.ledger.view().counters().staleTeleportConfirms());
        assertTrue(f.nodes().stream().anyMatch(n -> n.state().equals(pr.next())), "the post-supersession truth continues");
    }
}
