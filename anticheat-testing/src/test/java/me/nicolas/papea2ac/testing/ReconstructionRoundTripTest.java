package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 round-trip gate (§55): deterministic random legal states reached by random walks from rest on several worlds,
 * a random legal input, Phase 5 forward simulation, the exact observable transition fed to the reconstructor - the original
 * input must be an EXACT witness of the result (never excluded), every candidate's prediction must satisfy the
 * observation, the envelope prefilter must not change the candidate set (pruning on == pruning off), and the same request
 * must reproduce the same result. The seed and case index are printed on failure.
 */
class ReconstructionRoundTripTest {
    static final long SEED = Long.getLong("reconstruction.seed", 0x6A6A6A6AL);
    static final int CASES = Integer.getInteger("reconstruction.cases", 1200);

    /** A world plus its environment for the walks: flat baseline, a wall/step course, an ice floor. */
    record World(String name, PhysicsScene ps, PhysicsEnvironment env, double x0, double z0) {}
    static List<World> worlds() {
        List<World> w = new ArrayList<>();
        PhysicsScene flat = new PhysicsScene(); w.add(new World("flat", flat, flat.env(), 8.5, 8.5));
        PhysicsScene course = new PhysicsScene();
        for (int x = 4; x <= 12; x++) { course.set(x, 70, 12, WorldTestSupport.STONE); course.set(x, 71, 12, WorldTestSupport.STONE); } // wall at z=12
        for (int x = 6; x <= 8; x++) course.set(x, 70, 4, me.nicolas.papea2ac.model.BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=false]")); // slab row south of the start cells
        course.set(10, 70, 3, me.nicolas.papea2ac.model.BlockStateKey.parse("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")); course.set(10, 70, 2, WorldTestSupport.STONE);
        course.set(4, 70, 7, me.nicolas.papea2ac.model.BlockStateKey.parse("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"));
        for (int x = 2; x <= 4; x++) course.set(x, 72, 9, WorldTestSupport.STONE); // low ceiling strip
        w.add(new World("course", course, course.env(), 8.5, 8.5));
        PhysicsScene ice = new PhysicsScene(); for (int x = 0; x <= 15; x++) for (int z = 0; z <= 15; z++) ice.set(x, 69, z, ICE); w.add(new World("ice", ice, ice.env(), 8.5, 8.5));
        return w;
    }
    static MovementInput randomInput(SplittableRandom r, float yaw) { return InputDomain.fromOrdinal(r.nextInt(InputDomain.RAW_SIZE), yaw); }
    static float randomYaw(SplittableRandom r) { return (float) (r.nextInt(720) * 0.5 - 180.0); }

    @Test void originalInputIsNeverExcludedAndPruningChangesNothing() {
        SplittableRandom r = new SplittableRandom(SEED);
        List<World> worlds = worlds();
        int exactUnique = 0, ambiguous = 0, skippedInexact = 0, top = 0, pruned = 0;
        for (int c = 0; c < CASES; c++) {
            World w = worlds.get(r.nextInt(worlds.size()));
            String where = "seed=" + SEED + " case=" + c + " world=" + w.name();
            PhysicsState s; try { s = settled(w.ps(), w.x0() + r.nextInt(5) - 2, SURFACE, w.z0() + r.nextInt(5) - 2); } catch (AssertionError e) { skippedInexact++; continue; }
            int walk = r.nextInt(25); float yaw = randomYaw(r);
            for (int i = 0; i < walk; i++) { if (r.nextInt(6) == 0) yaw = randomYaw(r); PhysicsResult pr = w.ps().step(s, randomInput(r, yaw), w.env()); if (pr.next() == null || !pr.exact()) break; s = pr.next(); }
            if (!s.finite()) continue;
            if (r.nextInt(6) == 0) yaw = randomYaw(r);
            MovementInput in = randomInput(r, yaw);
            PhysicsResult pr = w.ps().step(s, in, w.env());
            if (pr.next() == null || !pr.exact()) { skippedInexact++; continue; } // out of the walking domain (fluid/unsupported): not a round-trip case
            TickObservation obs = observe(pr.next(), yaw, 1000 + c);
            InputReconstructor rec = reconstructor(w.ps());
            ReconstructionRequest on = ReconstructionRequest.of(s, obs, w.env()), off = on.withEnvelopePruning(false);
            ReconstructionResult a = rec.reconstruct(on), b = rec.reconstruct(off), again = rec.reconstruct(on);
            assertTrue(a.witnesses(List.of(in)), where + ": original input excluded\n" + InputDomain.describe(in) + "\n" + dump(a));
            assertTrue(b.witnesses(List.of(in)), where + ": original input excluded without pruning\n" + dump(b));
            assertTrue(a.complete(), where + ": expected a complete search: " + a.status() + " " + a.coverage().reasons());
            assertEquals(candidateSet(b), candidateSet(a), where + ": pruning changed the candidate set\n" + dump(a) + "\n--- off\n" + dump(b));
            assertEquals(a.status(), b.status(), where); assertEquals(a.counts().matchingStates(), b.counts().matchingStates(), where); assertEquals(a.counts().matchingHistories(), b.counts().matchingHistories(), where);
            assertEquals(dump(a), dump(again), where + ": non-deterministic");
            for (ReconstructionResult.Candidate cd : a.candidates()) { assertTrue(obs.position().contains(cd.predicted().position(), me.nicolas.papea2ac.version.MovementObservationProfile.PAPER_26_2), where + ": candidate outside the observation"); assertTrue(cd.residual().finite(), where); }
            if (a.status() == ReconstructionResult.Status.EXACT_UNIQUE) exactUnique++; else ambiguous++;
            if (a.counts().excludedEnvelope() > 0) pruned++; else top++;
        }
        System.out.println("round-trip: cases=" + CASES + " exactUnique=" + exactUnique + " ambiguous=" + ambiguous + " skippedInexact=" + skippedInexact + " envelopeUsed=" + pruned + " envelopeTop=" + top);
        assertTrue(exactUnique + ambiguous >= CASES / 2, "too few round-trip cases ran: " + (exactUnique + ambiguous));
    }
    /** The set of (history keys, match kind, predicted state) of a result, order-independent. */
    static Set<String> candidateSet(ReconstructionResult r) {
        Set<String> out = new TreeSet<>();
        for (ReconstructionResult.Candidate c : r.candidates()) { for (List<MovementInput> h : c.histories()) out.add(keys(h) + " " + c.match() + " " + (c.predicted() == null ? "-" : c.predicted().toString())); if (c.historyCount() > c.histories().size()) out.add("count " + c.historyCount() + " " + (c.predicted() == null ? "-" : c.predicted().toString())); }
        return out;
    }
}
