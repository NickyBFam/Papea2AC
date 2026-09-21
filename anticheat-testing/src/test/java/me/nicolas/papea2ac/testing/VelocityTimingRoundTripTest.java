package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 7 velocity round trip (brief sections 30-31, LATENCY_COMPENSATION.md N2 "sound branch retirement"): thousands of hidden
 * truths - a start state, random legal inputs, one to three server influences (velocity SET / explosion ADD) each applied at a
 * hidden client tick after a hidden network lead, the client's send rule and tick ends - walked through TimingResolver +
 * TimingReconstruction from the true start. The gate: at every transmission the TRUE client state is in the union frontier
 * (bit-identical PhysicsState with the right pending set) and the true application tick of every influence is among the
 * explaining hypotheses. Ambiguity is data; exclusion of the truth is the failure. Seeds are printed on failure.
 */
class VelocityTimingRoundTripTest {
    record Stats(int cases, int steps, int uniqueSteps, int ambiguousSteps, int influences, int maxHypotheses, long evaluations) {}

    static Stats run(long seed0, int cases, boolean tickEnds, StringBuilder failures) {
        List<ReconstructionRoundTripTest.World> worlds = ReconstructionRoundTripTest.worlds();
        int steps = 0, unique = 0, ambiguous = 0, influences = 0, maxHyp = 0, done = 0; long evaluations = 0;
        for (int c = 0; c < cases; c++) {
            long seed = seed0 + c; SplittableRandom r = new SplittableRandom(seed);
            ReconstructionRoundTripTest.World w = worlds.get(r.nextInt(worlds.size()));
            double x0 = w.x0() + r.nextInt(5) - 2 + 0.5, z0 = w.z0() + r.nextInt(5) - 2 + 0.5;
            PhysicsState start;
            try { start = settled(w.ps(), x0, SURFACE, z0); } catch (AssertionError e) { continue; }
            int n = 4 + r.nextInt(9); float yaw = ReconstructionRoundTripTest.randomYaw(r);
            List<MovementInput> inputs = new ArrayList<>();
            for (int i = 0; i < n; i++) { if (r.nextInt(8) == 0) yaw = ReconstructionRoundTripTest.randomYaw(r); inputs.add(r.nextInt(4) == 0 ? MovementInput.idle(yaw) : ReconstructionRoundTripTest.randomInput(r, yaw)); }
            List<Influence> infl = new ArrayList<>(); int count = 1 + r.nextInt(3); int lastApp = 0, lastEmit = 0;
            for (int i = 0; i < count; i++) {
                int app = Math.min(n, lastApp + 1 + r.nextInt(3)); if (app <= lastApp) break;
                int lead = Math.min(r.nextInt(3), app - 1); int emit = app - lead; if (emit < lastEmit) { lead = app - lastEmit; emit = lastEmit; }
                boolean set = r.nextInt(3) != 0;
                Vec3d v = set ? new Vec3d((r.nextInt(81) - 40) / 100.0, 0.36 + r.nextInt(10) / 100.0, (r.nextInt(81) - 40) / 100.0) : new Vec3d((r.nextInt(61) - 30) / 100.0, 0.2 + r.nextInt(20) / 100.0, (r.nextInt(61) - 30) / 100.0);
                infl.add(new Influence(set ? TimingEvent.Operation.SET : TimingEvent.Operation.ADD, v, app, lead)); lastApp = app; lastEmit = emit;
            }
            Truth truth;
            try { truth = simulate(w.ps(), w.env(), start, inputs, infl, new Recorder(), tickEnds); } catch (AssertionError e) { continue; }
            List<ChainStep> chain = chain(w.ps(), w.env(), truth, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
            done++; influences += infl.size();
            Set<String> appliedSeen = new HashSet<>();
            for (ChainStep s : chain) {
                steps++; evaluations += s.result().work().evaluations(); maxHyp = Math.max(maxHyp, s.result().work().hypotheses());
                if (s.result().status() == TimingReconstruction.Status.RESOLVED_UNIQUE) unique++; else if (s.result().status() == TimingReconstruction.Status.RESOLVED_AMBIGUOUS) ambiguous++;
                if (s.truthAfter() != null && !s.truthRetained()) { failures.append("seed ").append(seed).append(" world ").append(w.name()).append(": truth state lost at seq ").append(s.target().sequence()).append('\n').append(describe(chain)).append('\n'); break; }
                for (TimingReconstruction.Step st : s.result().explaining()) appliedSeen.addAll(st.hypothesis().appliedInfluences());
            }
            // every influence the truth applied inside a walked transmission must have been applied by SOME explaining hypothesis (the true one at least)
            for (var e : truth.applicationTick().entrySet()) {
                boolean walked = false; for (TruthTick t : truth.ticks()) if (t.index() >= e.getValue() && t.transmission() != null) walked = true;
                if (walked && !appliedSeen.contains(e.getKey())) failures.append("seed ").append(seed).append(": influence ").append(e.getKey()).append(" (applied at tick ").append(e.getValue()).append(") never applied by any explaining hypothesis\n").append(describe(chain)).append('\n');
            }
            if (failures.length() > 20_000) break;
        }
        return new Stats(done, steps, unique, ambiguous, influences, maxHyp, evaluations);
    }

    @Test void thousandsOfHiddenVelocityTimingsAreNeverExcluded() {
        StringBuilder failures = new StringBuilder();
        Stats s = run(7_000, 1_500, true, failures);
        System.out.println("velocity timing round trip: " + s);
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(s.cases() >= 1_000, "cases " + s.cases());
        assertTrue(s.steps() > s.cases() * 3, "steps " + s.steps());
    }
    /** Without the tick-end alignment source (a client that never sent it) the bounded fallback still never excludes the truth - only completeness is lost. */
    @Test void withoutTickEndsTheBoundedFallbackStillRetainsTheTruth() {
        StringBuilder failures = new StringBuilder();
        Stats s = run(9_000, 200, false, failures);
        System.out.println("velocity timing round trip (no tick ends): " + s);
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(s.cases() >= 120, "cases " + s.cases());
    }
    /** A silent client (idle, within the send threshold) still receives the influence: the first transmission after application carries several silent ticks and the branch survives. */
    @Test void aVelocityLandingOnASilentClientIsRetainedAcrossTheSilentTicks() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 6; i++) inputs.add(MovementInput.idle(0f));
        Truth truth = simulate(ps, ps.env(), start, inputs, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.3, 0.4, 0.1), 3, 1)), new Recorder(), true);
        List<ChainStep> chain = chain(ps, ps.env(), truth, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
        assertFalse(chain.isEmpty(), describe(chain));
        for (ChainStep s : chain) if (s.truthAfter() != null) assertTrue(s.truthRetained(), describe(chain));
        assertTrue(chain.get(0).result().resolutions().get(0).hypotheses().get(0).horizon() >= 2, "silent ticks before the first transmission: " + describe(chain));
    }
}
