package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.*;
import me.nicolas.papea2ac.world.*;

/**
 * Empirical, JVM-heap-diffing memory harness for Phase 5 (same methodology as the Phase 2-4 harnesses: forced GC,
 * Runtime used-heap diff, 3 trials, median; an estimate, never exact heap truth or a supported-capacity claim), plus a
 * thread-allocation measurement of the per-step garbage. Measures the physics-specific retained state: the movement
 * profile (block movement table, effect/attribute tables, the 256 KiB sine table), the world-owned block behaviour
 * cache at full dictionary warmth, the simulator itself (stateless), the retention of results if a consumer kept them,
 * and the bytes allocated by one step for the main movement families.
 */
public final class PhysicsMemoryMain {
    private PhysicsMemoryMain() {}
    private static void settle() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    private static long usedBytes() { var rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }
    private static double median(long[] v) { long[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }
    interface Scenario { Object build(); }
    static double measure(Scenario s) {
        long[] deltas = new long[3];
        for (int t = 0; t < 3; t++) { settle(); long before = usedBytes(); Object keep = s.build(); settle(); deltas[t] = usedBytes() - before; if (keep.hashCode() == 42) System.out.print(""); }
        return median(deltas);
    }
    static long allocPerStep(PhysicsScenarios.Scenario sc, int ticks) {
        ScenarioWorld w = new ScenarioWorld(sc); PhysicsEnvironment env = w.environment(); PhysicsState s = sc.start().state();
        for (int i = 0; i < 3000; i++) { PhysicsResult r = w.simulator.simulate(s, sc.input(sc.ticks().get(i % sc.ticks().size())), env); s = r.next() == null ? sc.start().state() : r.next(); if (i % sc.ticks().size() == sc.ticks().size() - 1) s = sc.start().state(); }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean(); long id = Thread.currentThread().threadId();
        s = sc.start().state(); long before = mx.getThreadAllocatedBytes(id); int n = 0;
        for (int rep = 0; rep < 20; rep++) { s = sc.start().state(); for (int i = 0; i < Math.min(ticks, sc.ticks().size()); i++) { PhysicsResult r = w.simulator.simulate(s, sc.input(sc.ticks().get(i)), env); n++; if (r.next() == null) break; s = r.next(); } }
        return (mx.getThreadAllocatedBytes(id) - before) / n;
    }
    public static void main(String[] args) throws IOException {
        var sb = new StringBuilder("{\n  \"methodology\": \"forced GC + Runtime used-heap diff, 3 trials, median; per-step allocation = ThreadMXBean allocated bytes over 20 repetitions of the scenario after 3000 warm-up steps; estimates, not supported capacity\",\n  \"profile\": \"").append(MovementProfile.paper26_2().id()).append("\",\n");
        byte[] blockBytes, profileBytes, sinBytes;
        try (var in = BlockMovementTable.class.getResourceAsStream(BlockMovementTable.PAPER_26_2_RESOURCE)) { blockBytes = in.readAllBytes(); }
        try (var in = MovementProfile.class.getResourceAsStream(MovementProfile.PAPER_26_2_RESOURCE)) { profileBytes = in.readAllBytes(); }
        try (var in = MovementProfile.class.getResourceAsStream(MovementProfile.PAPER_26_2_SIN_RESOURCE)) { sinBytes = in.readAllBytes(); }
        double blockTable = measure(() -> BlockMovementTable.parse(blockBytes));
        double profile = measure(() -> MovementProfile.parse("m", profileBytes, sinBytes, BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2));
        var states = CollisionMemoryMain.allStates(CollisionProfile.PAPER_26_2.table());
        double behaviorsWarm = measure(() -> { var d = new BlockStateDictionary(); var q = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2.table(), d); for (var k : states) q.forState(d.intern(k)); return q; });
        double dictionaryOnly = measure(() -> { var d = new BlockStateDictionary(); for (var k : states) d.intern(k); return d; });
        double simulator = measure(() -> new PhysicsSimulator(MovementProfile.paper26_2()));
        PhysicsScenarios.Scenario walk = PhysicsScenarios.all().stream().filter(s -> s.id().equals("walk_forward")).findFirst().orElseThrow();
        double retained1000 = measure(() -> { ScenarioWorld w = new ScenarioWorld(walk); PhysicsEnvironment env = w.environment(); var keep = new ArrayList<PhysicsResult>(1000); PhysicsState s = walk.start().state(); for (int i = 0; i < 1000; i++) { PhysicsResult r = w.simulator.simulate(s, walk.input(walk.ticks().get(0)), env); keep.add(r); s = r.next(); } return keep; });
        sb.append("  \"blockMovementTableBytes\": ").append((long) blockTable).append(",\n  \"movementProfileBytes\": ").append((long) profile).append(",\n  \"sineTableResourceBytes\": ").append(sinBytes.length).append(",\n");
        sb.append("  \"blockBehaviorCacheEveryStateBytes\": ").append((long) behaviorsWarm).append(",\n  \"dictionaryOnlyEveryStateBytes\": ").append((long) dictionaryOnly).append(",\n  \"behaviorCacheEstimateBytes\": ").append((long) (behaviorsWarm - dictionaryOnly)).append(",\n");
        sb.append("  \"simulatorBytes\": ").append((long) simulator).append(",\n  \"retainedBytesPer1000Results\": ").append((long) retained1000).append(",\n  \"perStepAllocationBytes\": {\n");
        String[] ids = { "idle_ground", "walk_forward", "sprint_forward", "fall_20", "jump_stationary", "wall_forward", "step_up_slab", "ice_walk", "cobweb_walk_in", "ladder_climb", "mixed_course_random_100" };
        Map<String, PhysicsScenarios.Scenario> byId = new HashMap<>(); for (var s : PhysicsScenarios.all()) byId.put(s.id(), s);
        for (int i = 0; i < ids.length; i++) { long a = allocPerStep(byId.get(ids[i]), 40); sb.append("    \"").append(ids[i]).append("\": ").append(a).append(i + 1 < ids.length ? ",\n" : "\n"); System.out.println(ids[i] + " alloc/step=" + a + " B"); }
        sb.append("  },\n  \"perPlayerRetained\": \"nothing: the simulator is stateless, PhysicsState/PhysicsResult are values the caller owns; the behaviour cache is engine-wide and bounded by the dictionary (65536 states)\"\n}\n");
        Files.writeString(Path.of(args[0]), sb.toString());
        System.out.println(sb);
    }
}
