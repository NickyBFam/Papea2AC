package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;

/**
 * Empirical memory harness for Phase 6 (same methodology as the Phase 2-5 harnesses: forced GC, Runtime used-heap diff,
 * 3 trials, median; estimates, never a supported-capacity claim), plus a thread-allocation measurement of the garbage one
 * reconstruction request produces. Measures: the reconstructor itself (stateless), the retained size of a result if a
 * consumer kept 100 of them (frontier continuation), and the bytes allocated per request for the main families with the
 * envelope prefilter on and off.
 */
public final class ReconstructionMemoryMain {
    private ReconstructionMemoryMain() {}
    private static void settle() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    private static long usedBytes() { var rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }
    private static double median(long[] v) { long[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }
    interface Scenario { Object build(); }
    static double measure(Scenario s) {
        long[] deltas = new long[3];
        for (int t = 0; t < 3; t++) { settle(); long before = usedBytes(); Object keep = s.build(); settle(); deltas[t] = usedBytes() - before; if (keep.hashCode() == 42) System.out.print(""); }
        return median(deltas);
    }
    record Family(String id, java.util.function.Supplier<ReconstructionRequest> request) {}
    static long allocPerRequest(InputReconstructor rec, ReconstructionRequest req) {
        for (int i = 0; i < 300; i++) rec.reconstruct(req);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean(); long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id); int n = 50;
        for (int i = 0; i < n; i++) rec.reconstruct(req);
        return (mx.getThreadAllocatedBytes(id) - before) / n;
    }
    public static void main(String[] args) throws IOException {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); InputReconstructor rec = reconstructor(ps);
        PhysicsState air = ps.step(rest, JUMP).next();
        PhysicsScene wallScene = new PhysicsScene(); for (int x = 6; x <= 10; x++) { wallScene.set(x, 70, 9, WorldTestSupport.STONE); wallScene.set(x, 71, 9, WorldTestSupport.STONE); } PhysicsState nearWall = wallScene.step(wallScene.step(settled(wallScene, 8.5, SURFACE, 8.5), W).next(), W).next();
        List<Family> families = List.of(
            new Family("idle_unique_constrained", () -> ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(IDLE)), env).withConstraints(InputDomain.Constraints.NONE.withShift(false).withSprinting(false).withUsingItem(false))),
            new Family("idle_ambiguous", () -> ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(IDLE)), env)),
            new Family("walk", () -> ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(W)), env)),
            new Family("sprint", () -> ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(W_SPRINT)), env)),
            new Family("jump", () -> ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(JUMP)), env)),
            new Family("airborne", () -> ReconstructionRequest.of(air, observations(ps, env, air, List.of(W)), env)),
            new Family("wall_collision_concrete_lane", () -> ReconstructionRequest.of(nearWall, observations(wallScene, wallScene.env(), nearWall, List.of(W)), wallScene.env())),
            new Family("multi_tick_3", () -> ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(W, W, W)), env)),
            new Family("empty_candidate_set", () -> ReconstructionRequest.of(rest, TickObservation.exact(9.5, 70.0, 8.5, 0f, TickObservation.GroundClaim.TRUE, 1), env)));
        double reconstructor = measure(() -> new InputReconstructor(ps.simulator));
        double retained100 = measure(() -> { var keep = new ArrayList<ReconstructionResult>(100); ReconstructionRequest r = ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(IDLE)), env); for (int i = 0; i < 100; i++) keep.add(rec.reconstruct(r)); return keep; });
        double retained100Walk = measure(() -> { var keep = new ArrayList<ReconstructionResult>(100); ReconstructionRequest r = ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(W)), env); for (int i = 0; i < 100; i++) keep.add(rec.reconstruct(r)); return keep; });
        StringBuilder sb = new StringBuilder("{\n  \"methodology\": \"forced GC + Runtime used-heap diff, 3 trials, median; per-request allocation = ThreadMXBean allocated bytes over 50 requests after 300 warm-up requests; estimates, not supported capacity\",\n");
        sb.append("  \"reconstructorBytes\": ").append((long) reconstructor).append(",\n  \"retainedBytesPer100ResultsIdleAmbiguous\": ").append((long) retained100).append(",\n  \"retainedBytesPer100ResultsWalkUnique\": ").append((long) retained100Walk).append(",\n  \"perRequestAllocationBytes\": {\n");
        for (int i = 0; i < families.size(); i++) {
            Family f = families.get(i); ReconstructionRequest req = f.request().get();
            long on = allocPerRequest(rec, req), off = allocPerRequest(rec, req.withEnvelopePruning(false)); // one profile instance: the reconstructor serves every scene
            ReconstructionResult res = rec.reconstruct(req);
            sb.append("    \"").append(f.id()).append("\": {\"envelopeOn\": ").append(on).append(", \"envelopeOff\": ").append(off).append(", \"evaluationsOn\": ").append(res.work().evaluations()).append(", \"status\": \"").append(res.status()).append("\"}").append(i + 1 < families.size() ? ",\n" : "\n");
            System.out.println(f.id() + " alloc/request on=" + on + " B off=" + off + " B evaluations=" + res.work().evaluations() + " " + res.status());
        }
        sb.append("  },\n  \"perPlayerRetained\": \"nothing: the reconstructor is stateless; requests and results are values the caller owns and drops (frontier continuation keeps only the exact-witness states of the last result)\",\n");
        sb.append("  \"bounds\": \"256 live nodes, 256 materialized histories per node, 64 reported candidates, 32 coverage reasons, 16 notes, 4096 envelope cells per scan (ReconstructionRequest.SearchBudget.DEFAULT)\"\n}\n");
        Files.writeString(Path.of(args[0]), sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
    }
}
