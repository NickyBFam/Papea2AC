package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import org.junit.jupiter.api.Test;

/**
 * Multi-tick round trip (§56) within the approved horizon (MOVEMENT_SIMULATION.md M2: at most three inferred steps): random
 * legal 2- and 3-tick histories, simulated forward, reconstructed from (a) every tick observed exactly, (b) the middle ticks
 * silent (send-threshold ball) where the client would indeed have stayed silent, (c) the middle ticks fully unobserved.
 * The original history must stay represented; state deduplication must keep every merged history countable.
 */
class MultiTickReconstructionTest {
    static final long SEED = Long.getLong("reconstruction.seed", 0x3C3C3C3CL);

    @Test void twoAndThreeTickHistoriesAreRepresentedUnderEveryObservationPattern() {
        SplittableRandom r = new SplittableRandom(SEED);
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); InputReconstructor rec = reconstructor(ps);
        int cases = 0, exactPattern = 0, silentPattern = 0, gapPattern = 0, gapLimited = 0, gapIncomplete = 0;
        for (int c = 0; c < 160; c++) {
            String where = "seed=" + SEED + " case=" + c;
            PhysicsState s = settled(ps, 8.5 + r.nextInt(3), SURFACE, 8.5 + r.nextInt(3));
            boolean stillMiddle = c % 3 == 0; // every third case starts at rest and keeps the middle ticks still (idle-class keys, no jump) so the client would have stayed silent
            if (!stillMiddle) for (int i = r.nextInt(8); i > 0; i--) { PhysicsResult pr = ps.step(s, InputDomain.fromOrdinal(r.nextInt(144), 0f), env); if (!pr.exact()) break; s = pr.next(); }
            int h = 2 + r.nextInt(2); float yaw = (float) (r.nextInt(360) - 180);
            List<MovementInput> history = new ArrayList<>(); List<PhysicsState> states = new ArrayList<>(); PhysicsState cur = s; boolean ok = true;
            for (int t = 0; t < h; t++) { MovementInput in = stillMiddle && t < h - 1 ? new MovementInput(0, 0, false, r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), yaw) : InputDomain.fromOrdinal(r.nextInt(144), yaw); PhysicsResult pr = ps.step(cur, in, env); if (!pr.exact()) { ok = false; break; } history.add(in); states.add(pr.next()); cur = pr.next(); }
            if (!ok) continue;
            cases++;
            // (a) exact observations at every tick
            List<TickObservation> exact = new ArrayList<>(); for (int t = 0; t < h; t++) exact.add(observe(states.get(t), yaw, 10 + t));
            ReconstructionResult a = rec.reconstruct(ReconstructionRequest.of(s, exact, env));
            assertTrue(a.complete(), where + " exact pattern: " + a.status() + " " + a.coverage().reasons()); assertTrue(a.witnesses(history), where + " exact pattern lost the history " + keys(history) + "\n" + dump(a));
            for (ReconstructionResult.Candidate cd : a.candidates()) { assertTrue(cd.historiesComplete() || cd.historyCount() > cd.histories().size()); assertEquals(h, cd.input().size()); }
            exactPattern++;
            // (b) silent middle ticks where the client really would have been silent (position within the ball of the LAST transmitted one = the start)
            boolean silentValid = true; Vec3d lastSent = s.position();
            for (int t = 0; t < h - 1; t++) if (!me.nicolas.papea2ac.version.MovementObservationProfile.PAPER_26_2.withinSendThreshold(states.get(t).position().x(), states.get(t).position().y(), states.get(t).position().z(), lastSent.x(), lastSent.y(), lastSent.z())) silentValid = false;
            if (silentValid) {
                List<TickObservation> silent = new ArrayList<>(); for (int t = 0; t < h - 1; t++) silent.add(TickObservation.silent(lastSent.x(), lastSent.y(), lastSent.z(), yaw, 10 + t)); silent.add(exact.get(h - 1));
                ReconstructionResult b = rec.reconstruct(ReconstructionRequest.of(s, silent, env));
                assertTrue(b.admits(history), where + " silent pattern lost the history " + keys(history) + "\n" + dump(b));
                if (b.complete()) assertTrue(b.witnesses(history), where);
                silentPattern++;
            }
            // (c) unobserved middle ticks: bounded search; when complete the history is a witness, when limited it is never claimed exhaustive
            List<TickObservation> gap = new ArrayList<>(); for (int t = 0; t < h - 1; t++) gap.add(TickObservation.unobserved(yaw, 10 + t)); gap.add(exact.get(h - 1));
            ReconstructionResult g = rec.reconstruct(ReconstructionRequest.of(s, gap, env));
            if (g.complete()) { assertTrue(g.witnesses(history), where + " gap pattern lost the history " + keys(history) + "\n" + dump(g)); gapPattern++; }
            else { assertFalse(g.complete()); if (g.status() == ReconstructionResult.Status.SEARCH_LIMIT) gapLimited++; else gapIncomplete++; }
            assertTrue(g.work().evaluations() <= ReconstructionRequest.SearchBudget.DEFAULT.maxEvaluations(), where); assertTrue(g.work().maxLiveNodes() <= ReconstructionRequest.SearchBudget.DEFAULT.maxNodes(), where);
        }
        System.out.println("multi-tick: cases=" + cases + " exactPattern=" + exactPattern + " silentPattern=" + silentPattern + " gapComplete=" + gapPattern + " gapSearchLimited=" + gapLimited + " gapOtherIncomplete=" + gapIncomplete);
        assertTrue(cases >= 100, "cases " + cases); assertTrue(silentPattern >= 5, "silent cases " + silentPattern);
    }
    @Test void identicalStatesFromDifferentHistoriesMergeIntoOneNodeWithBothHistoriesRepresentable() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        // idle, idle+use-item and idle+sprint-flag produce bit-identical states each tick (4 raw tuples per tick), and a tick-1 crouch is undone by a
        // tick-2 stand: after two ticks ONE standing node carries 4 x 4 x 2 = 32 histories from two different tick-1 parents, all representable
        List<TickObservation> obs = observations(ps, env, rest, List.of(IDLE, IDLE));
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env));
        assertTrue(r.complete(), dump(r));
        ReconstructionResult.Candidate standing = r.candidates().stream().filter(c -> c.predicted().pose() == me.nicolas.papea2ac.collision.PlayerBox.Pose.STANDING).findFirst().orElseThrow();
        assertEquals(32, standing.historyCount(), dump(r)); assertTrue(standing.historiesComplete());
        assertTrue(standing.containsHistory(List.of(IDLE, IDLE))); assertTrue(standing.containsHistory(List.of(new MovementInput(0, 0, false, false, true, true, 0f), IDLE)));
        assertTrue(standing.containsHistory(List.of(new MovementInput(0, 0, false, true, false, false, 0f), IDLE)), "crouch then stand reaches the same state");
        assertFalse(standing.containsHistory(List.of(IDLE, new MovementInput(0, 0, false, true, false, false, 0f))), "stand then crouch ends crouching: the other node");
        assertTrue(r.work().mergedHistories() > 0);
        assertEquals(8, standing.ordinalsAt(0).size()); assertEquals(4, standing.ordinalsAt(1).size());
    }
}
