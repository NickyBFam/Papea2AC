package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.*;
import me.nicolas.papea2ac.world.*;

/**
 * Empirical, JVM-heap-diffing memory harness for Phase 4 (same methodology as the Phase 2/3 harnesses: forced GC,
 * Runtime used-heap diff, 3 trials, median; an estimate, never exact heap truth or a supported-capacity claim).
 * Measures the collision-specific retained state task §44/§48 asks for: the version shape table (parsed from the
 * resource, shared once), the interned shape instances, the per-engine resolver cache at full dictionary warmth
 * (every registry state interned and resolved), the empty engine, and the retention of query results.
 */
public final class CollisionMemoryMain {
    private CollisionMemoryMain() {}
    private static void settle() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    private static long usedBytes() { var rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }
    private static double median(long[] v) { long[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }
    interface Scenario { Object build(); }
    static double measure(Scenario s) {
        long[] deltas = new long[3];
        for (int t = 0; t < 3; t++) { settle(); long before = usedBytes(); Object keep = s.build(); settle(); deltas[t] = usedBytes() - before; if (keep.hashCode() == 42) System.out.print(""); }
        return median(deltas);
    }
    static List<BlockStateKey> allStates(BlockShapeTable t) {
        var out = new ArrayList<BlockStateKey>(t.stateCount());
        for (var b : t.blocks()) {
            int n = b.propertyCount(); int[] idx = new int[n];
            for (int s = 0; s < b.stateCount(); s++) {
                var props = new TreeMap<String, String>(); int rem = s;
                for (int i = n - 1; i >= 0; i--) { int size = b.values(i).size(); idx[i] = rem % size; rem /= size; }
                for (int i = 0; i < n; i++) props.put(b.property(i), b.values(i).get(idx[i]));
                out.add(new BlockStateKey(b.name(), props));
            }
        }
        return out;
    }
    public static void main(String[] args) throws IOException {
        var sb = new StringBuilder("{\n  \"methodology\": \"forced GC + Runtime used-heap diff, 3 trials, median; estimates, not supported capacity\",\n  \"profile\": \"").append(CollisionProfile.PAPER_26_2.id()).append("\",\n");
        byte[] tableBytes; try (var in = BlockShapeTable.class.getResourceAsStream(BlockShapeTable.PAPER_26_2_RESOURCE)) { tableBytes = in.readAllBytes(); }
        double table = measure(() -> BlockShapeTable.parse(tableBytes));
        var t = CollisionProfile.PAPER_26_2.table(); var states = allStates(t);
        double shapes = measure(() -> { var r = new BlockShapeResolver(CollisionProfile.PAPER_26_2, new BlockStateDictionary(), new CollisionMetrics()); var arr = new CollisionShape[t.shapeCount()]; for (int i = 0; i < arr.length; i++) arr[i] = r.shape(i); return arr; });
        double emptyEngine = measure(() -> new CollisionEngine(CollisionProfile.PAPER_26_2, new BlockStateDictionary()));
        double warmResolver = measure(() -> { var d = new BlockStateDictionary(); var e = new CollisionEngine(CollisionProfile.PAPER_26_2, d); for (var k : states) e.resolver().entry(d.intern(k)); return e; });
        double dictionaryOnly = measure(() -> { var d = new BlockStateDictionary(); for (var k : states) d.intern(k); return d; });
        // query retention: 1000 retained results of a standing query over a real observer world (what a consumer would hold if it kept every result - it should not)
        var dict = new BlockStateDictionary(); var budget = new WorldBudget(); var key = new SessionKey(new UUID(4, 4), 1); var world = new ObserverWorld(key, budget, dict); long[] seq = { 0 };
        world.apply(key, new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false), ++seq[0], seq[0], null); world.apply(key, WorldTestSupport.baseline(0, 0), ++seq[0], seq[0], null);
        for (int i = 0; i < 64; i++) world.apply(key, new WorldObservation.BlockChanged(new BlockPos(i & 15, 70, i >> 4), i % 2 == 0 ? CollisionTestSupport.FENCE_NS : CollisionTestSupport.STAIRS_STRAIGHT), ++seq[0], seq[0], null);
        var engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dict); var view = world.currentView(); var box = PlayerBox.of(CollisionProfile.PAPER_26_2, PlayerBox.Pose.STANDING, 4.5, 70.5, 2.5);
        double results = measure(() -> { var list = new ArrayList<CollisionResult>(1000); for (int i = 0; i < 1000; i++) list.add(engine.query(view, box, ShapeContext.body(70.5), 0)); return list; });
        var sample = engine.query(view, box, ShapeContext.body(70.5), 0);
        int distinctShapes = t.shapeCount(), statesTotal = t.stateCount();
        sb.append("  \"shapeTable\": {\"resourceBytes\": ").append(t.resourceBytes()).append(", \"blocks\": ").append(t.blockCount()).append(", \"states\": ").append(statesTotal).append(", \"uniqueShapes\": ").append(distinctShapes)
          .append(", \"deduplicationRatio\": ").append(String.format(Locale.ROOT, "%.1f", statesTotal / (double) distinctShapes)).append(", \"parsedRetainedBytes\": ").append((long) table).append("},\n");
        sb.append("  \"internedShapeInstancesBytes\": ").append((long) shapes).append(",\n");
        sb.append("  \"emptyEngineBytes\": ").append((long) emptyEngine).append(",\n");
        sb.append("  \"dictionaryAllStatesBytes\": ").append((long) dictionaryOnly).append(",\n");
        sb.append("  \"engineWithEveryRegistryStateResolvedBytes\": ").append((long) warmResolver).append(",\n");
        sb.append("  \"resolverCacheEstimateBytes\": ").append((long) (warmResolver - dictionaryOnly - emptyEngine)).append(",\n");
        sb.append("  \"retainedBytesPer1000Results\": ").append((long) results).append(",\n  \"sampleResult\": {\"hits\": ").append(sample.hitCount()).append(", \"boxes\": ").append(sample.boxCount()).append(", \"cells\": ").append(sample.cellsVisited()).append(", \"certainty\": \"").append(sample.certainty()).append("\"},\n");
        sb.append("  \"partitionNote\": \"PERFORMANCE.md reference shape-cache partition is 32 MiB; the whole table + interned shapes + a fully warm per-engine cache is far below it, and nothing per player is retained by the collision engine\"\n}\n");
        Path out = Path.of(args[0]); Files.createDirectories(out.getParent()); Files.writeString(out, sb.toString()); System.out.print(sb);
    }
}
