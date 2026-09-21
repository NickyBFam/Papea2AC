package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.physics.PhysicsEnvironment.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.ReconstructionRequest.SearchBudget;
import org.junit.jupiter.api.Test;

/**
 * Deterministic property tests (§54) beyond the round-trip and native gates: merged nodes keep exactly the raw inputs that
 * produce their state, a search limit never claims exhaustiveness and never invents candidates, an unknown world or unknown
 * attributes never yield COMPLETE and never exclude, and every request stays inside its work bounds. Seed printed on failure.
 */
class ReconstructionPropertyTest {
    static final long SEED = Long.getLong("reconstruction.seed", 0x5A5A5A5AL);

    static PhysicsState randomState(SplittableRandom r, PhysicsScene ps, PhysicsEnvironment env) {
        PhysicsState s = settled(ps, 7.5 + r.nextInt(3), SURFACE, 7.5 + r.nextInt(3));
        for (int i = r.nextInt(12); i > 0; i--) { PhysicsResult pr = ps.step(s, InputDomain.fromOrdinal(r.nextInt(144), (float) (r.nextInt(360) - 180)), env); if (!pr.exact()) break; s = pr.next(); }
        return s;
    }
    @Test void mergedNodesCarryExactlyTheRawInputsThatProduceTheirState() {
        SplittableRandom r = new SplittableRandom(SEED); PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); InputReconstructor rec = reconstructor(ps);
        int merges = 0;
        for (int c = 0; c < 120; c++) {
            PhysicsState s = randomState(r, ps, env); float yaw = (float) (r.nextInt(360) - 180);
            MovementInput in = InputDomain.fromOrdinal(r.nextInt(144), yaw); PhysicsResult pr = ps.step(s, in, env); if (!pr.exact()) continue;
            ReconstructionResult res = rec.reconstruct(ReconstructionRequest.of(s, observe(pr.next(), yaw, c), env));
            // independent oracle: simulate every raw input, group the exact predictions by state
            Map<PhysicsState, List<MovementInput>> byState = new LinkedHashMap<>();
            for (MovementInput cand : InputDomain.enumerate(InputDomain.Constraints.NONE, yaw)) { PhysicsResult q = ps.step(s, cand, env); if (q.exact() && q.next().position().equals(pr.next().position())) byState.computeIfAbsent(q.next(), k -> new ArrayList<>()).add(cand); }
            assertEquals(byState.size(), res.counts().matchingStates(), "seed=" + SEED + " case=" + c + "\n" + dump(res));
            for (ReconstructionResult.Candidate cd : res.candidates()) {
                List<MovementInput> expected = byState.get(cd.predicted()); assertNotNull(expected, "seed=" + SEED + " case=" + c + " unexpected state " + cd.predicted());
                assertEquals(expected.size(), cd.historyCount(), "seed=" + SEED + " case=" + c + " " + keys(cd.input()));
                for (MovementInput e : expected) assertTrue(cd.containsHistory(List.of(e)), "seed=" + SEED + " case=" + c + " missing " + InputDomain.describe(e));
                if (cd.historyCount() > 1) merges++;
            }
        }
        assertTrue(merges > 20, "merges " + merges);
    }
    @Test void aSearchLimitNeverClaimsExhaustivenessAndNeverInventsCandidates() {
        SplittableRandom r = new SplittableRandom(SEED + 1); PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); InputReconstructor rec = reconstructor(ps);
        int limited = 0;
        for (int c = 0; c < 80; c++) {
            PhysicsState s = randomState(r, ps, env); float yaw = (float) (r.nextInt(360) - 180);
            MovementInput in = InputDomain.fromOrdinal(r.nextInt(144), yaw); PhysicsResult pr = ps.step(s, in, env); if (!pr.exact()) continue;
            ReconstructionRequest full = ReconstructionRequest.of(s, observe(pr.next(), yaw, c), env).withEnvelopePruning(false);
            ReconstructionResult complete = rec.reconstruct(full);
            SearchBudget small = new SearchBudget(1 + r.nextInt(4), 1 + r.nextInt(200), 3, 256, 64, 4096);
            ReconstructionResult part = rec.reconstruct(full.withBudget(small));
            String where = "seed=" + SEED + " case=" + c + " budget=" + small;
            assertTrue(part.work().evaluations() <= small.maxEvaluations(), where); assertTrue(part.work().maxLiveNodes() <= small.maxNodes(), where);
            if (part.status() == ReconstructionResult.Status.SEARCH_LIMIT) { limited++; assertFalse(part.complete(), where); assertFalse(part.coverage().searchComplete(), where); }
            for (ReconstructionResult.Candidate cd : part.candidates()) for (List<MovementInput> h : cd.histories()) assertTrue(complete.witnesses(h), where + ": a limited search invented " + keys(h));
        }
        assertTrue(limited > 30, "limited " + limited);
    }
    @Test void unknownWorldOrEnvironmentNeverYieldsCompleteAndNeverExcludes() {
        SplittableRandom r = new SplittableRandom(SEED + 2); PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); InputReconstructor rec = reconstructor(ps);
        for (int c = 0; c < 60; c++) {
            PhysicsState s = randomState(r, ps, env); float yaw = (float) (r.nextInt(360) - 180);
            MovementInput in = InputDomain.fromOrdinal(r.nextInt(144), yaw); PhysicsResult pr = ps.step(s, in, env); if (!pr.exact()) continue;
            TickObservation obs = observe(pr.next(), yaw, c); String where = "seed=" + SEED + " case=" + c;
            for (PhysicsEnvironment bad : List.of(env.withAttributes(Attributes.ALL_UNKNOWN), env.withEffects(Effects.UNKNOWN), env.withEntities(EntityNeighborhood.UNKNOWN), env.withBorder(WorldBorder.UNKNOWN))) { // equipment matters only where powder snow is touched (physics reports it then)
                ReconstructionResult res = rec.reconstruct(ReconstructionRequest.of(s, obs, bad));
                assertFalse(res.complete(), where + " " + res.status()); assertEquals(0, res.counts().excludedExact() + res.counts().excludedEnvelope(), where + " " + res.status() + " excluded under unknown facts");
                assertTrue(res.admits(List.of(in)), where + " " + res.status()); assertFalse(res.coverage().all(), where);
            }
            // an unobserved chunk in reach (x, z >= 32 are never observed): never COMPLETE, the true input still admitted, nothing excluded
            PhysicsState edge = standingAt(31.2, SURFACE, 31.2).withVelocity(new Vec3d(0.0, -0.0784000015258789, 0.0)); PhysicsResult pe = ps.step(edge, in, env);
            if (pe.next() != null) { ReconstructionResult res = rec.reconstruct(ReconstructionRequest.of(edge, observe(pe.next(), yaw, c), env)); assertFalse(res.complete(), where + " " + dump(res)); assertFalse(res.coverage().worldComplete(), where); assertTrue(res.admits(List.of(in)), where + "\n" + dump(res)); }
        }
    }
    @Test void everyRequestStaysInsideItsWorkBoundsAndResidualsAreFinite() {
        SplittableRandom r = new SplittableRandom(SEED + 3); PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); InputReconstructor rec = reconstructor(ps);
        for (int c = 0; c < 60; c++) {
            PhysicsState s = randomState(r, ps, env); float yaw = (float) (r.nextInt(360) - 180); int h = 1 + r.nextInt(3);
            List<TickObservation> ticks = new ArrayList<>(); PhysicsState cur = s; boolean ok = true;
            for (int t = 0; t < h; t++) { PhysicsResult pr = ps.step(cur, InputDomain.fromOrdinal(r.nextInt(144), yaw), env); if (!pr.exact()) { ok = false; break; } cur = pr.next(); ticks.add(r.nextInt(3) == 0 && t < h - 1 ? TickObservation.unobserved(yaw, t) : observe(cur, yaw, t)); }
            if (!ok) continue;
            ReconstructionResult res = rec.reconstruct(ReconstructionRequest.of(s, ticks, env)); String where = "seed=" + SEED + " case=" + c;
            assertTrue(res.work().evaluations() <= SearchBudget.DEFAULT.maxEvaluations(), where); assertTrue(res.work().maxLiveNodes() <= SearchBudget.DEFAULT.maxNodes(), where); assertTrue(res.candidates().size() <= SearchBudget.DEFAULT.maxCandidatesReported(), where);
            for (ReconstructionResult.Candidate cd : res.candidates()) { if (cd.residual() != null) assertTrue(cd.residual().finite(), where); assertTrue(cd.histories().size() <= SearchBudget.DEFAULT.maxHistoriesPerNode(), where); }
            assertEquals(res.status().completeSearch(), res.complete(), where);
        }
    }
}
