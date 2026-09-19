package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/**
 * Empirical, JVM-heap-diffing memory harness for the Phase 3 observer world (same methodology as
 * PlayerTimelineMemoryMain: forced GC, Runtime used-heap diff, 3 trials, median; an estimate, never exact heap
 * truth or a supported-capacity claim). The view sizes (1/25/121/289 chunks) are SCALING SCENARIOS, not a claim
 * about any server's actual view distance.
 *
 * Because the architecture is a HYBRID (content-addressed shared section store + per-observer private tier), every
 * view scenario is measured twice: SHARED (all observers were sent identical chunk content - the co-located case)
 * and UNIQUE (every observer's chunks differ - the fully spread-out worst case). The per-observer heap number is the
 * total delta divided by the observer count, so the shared case shows the marginal cost of one more observer of the
 * same terrain while the unique case shows the full per-observer cost. The budget's own charge accounting is
 * reported next to the heap measurement so the charge model can be reconciled with reality.
 *
 * Terrain shape per chunk (24 overworld sections): 8 stone sections with an ore speckle (5-entry palette, 4 bits),
 * 2 mixed surface sections (6-entry palette, 4 bits), 14 single-value air sections - roughly 20 KiB of section
 * content, in the range of a real overworld chunk rather than the best case of uniform sections.
 */
public final class ObserverWorldMemoryMain {
    private ObserverWorldMemoryMain() {}
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt"),
        GRASS = BlockStateKey.parse("minecraft:grass_block[snowy=false]"), GRAVEL = BlockStateKey.of("minecraft:gravel"), COAL = BlockStateKey.of("minecraft:coal_ore"),
        IRON = BlockStateKey.of("minecraft:iron_ore"), COPPER = BlockStateKey.of("minecraft:copper_ore"), ANDESITE = BlockStateKey.of("minecraft:andesite"), STAIRS = BlockStateKey.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);

    private static void settle() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    private static long usedBytes() { var rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }
    private static double median(long[] v) { long[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }

    static WorldObservation.SectionContent packed(List<BlockStateKey> palette, java.util.function.IntUnaryOperator slotAt) {
        int bits = 4, perLong = 16; long[] words = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int slot = slotAt.applyAsInt(i); if (!palette.get(slot).equals(AIR)) nonEmpty++; words[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, words);
    }
    /** 64-bit finalizer mix: (salt, section, cell) -> well-spread int, so distinct salts really are distinct content (a linear hash collapsed to 53 variants). */
    static int mix(long v) { v = (v ^ (v >>> 33)) * 0xff51afd7ed558ccdL; v = (v ^ (v >>> 33)) * 0xc4ceb9fe1a85ec53L; return (int) ((v ^ (v >>> 33)) & 0x7fffffff); }
    /** Deterministic terrain for chunk (x,z) with a content salt (the salt makes content differ per chunk; SHARED scenarios give every observer the same salt per chunk, UNIQUE ones a per-observer salt). */
    static WorldObservation.ChunkBaselineObserved terrain(int x, int z, int salt) {
        var sections = new ArrayList<WorldObservation.SectionContent>(24);
        var ore = List.of(STONE, ANDESITE, COAL, IRON, COPPER); var surface = List.of(DIRT, GRASS, STONE, GRAVEL, AIR, ANDESITE);
        for (int s = 0; s < 24; s++) {
            final int section = s;
            if (s < 8) sections.add(packed(ore, i -> { int h = mix(((long) salt << 32) ^ ((long) section << 16) ^ i) % 53; return h < 44 ? 0 : h < 49 ? 1 : h < 51 ? 2 : h < 52 ? 3 : 4; }));
            else if (s < 10) sections.add(packed(surface, i -> { int y = i >> 8; int h = mix(((long) salt << 32) ^ ((long) section << 16) ^ i) % 17; return section == 8 ? (y < 12 ? (h < 15 ? 2 : 5) : y < 15 ? 0 : 1) : (h == 0 && y < 2 ? 3 : 4); }));
            else sections.add(new WorldObservation.SectionContent(0, List.of(AIR), 0, new long[0]));
        }
        return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, "");
    }

    record Scenario(String name, int observers, int radius, boolean unique, int updates, int privateTier) {
        int chunks() { return radius < 0 ? 0 : (2 * radius + 1) * (2 * radius + 1); }
    }
    record Result(Scenario scenario, long[] trialsPerObserver, long sharedBytes, long privateBytes, long historyBytes, long budgetRejections, long evictedChunks, long chargedPerObserver) {}

    private static Result measure(Scenario sc) {
        long[] deltas = new long[3]; long shared = 0, priv = 0, hist = 0, rejections = 0, evicted = 0;
        for (int trial = 0; trial < 3; trial++) {
            settle(); long before = usedBytes();
            var budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, sc.privateTier()); var dictionary = new BlockStateDictionary();
            var worlds = new ArrayList<ObserverWorld>(sc.observers());
            for (int o = 0; o < sc.observers(); o++) {
                var key = new SessionKey(new UUID(3, o + 1), 1); var w = new ObserverWorld(key, budget, dictionary); long seq = 0;
                w.apply(key, OVERWORLD, ++seq, seq, null); w.apply(key, new WorldObservation.ViewCenter(0, 0), ++seq, seq, null);
                for (int x = -sc.radius(); x <= sc.radius(); x++) for (int z = -sc.radius(); z <= sc.radius(); z++) w.apply(key, terrain(x, z, (x * 31 + z) * 7919 + (sc.unique() ? (o + 1) * 1_000_003 : 0)), ++seq, seq, null); // per-chunk content; identical across observers unless unique
                var r = new Random(o);
                for (int u = 0; u < sc.updates(); u++) {
                    int cx = r.nextInt(2 * sc.radius() + 1) - sc.radius(), cz = r.nextInt(2 * sc.radius() + 1) - sc.radius();
                    w.apply(key, new WorldObservation.BlockChanged(new BlockPos((cx << 4) + r.nextInt(16), 60 + r.nextInt(30), (cz << 4) + r.nextInt(16)), (u & 1) == 0 ? STAIRS : DIRT), ++seq, seq, null);
                    if ((u & 63) == 63) w.apply(key, new WorldObservation.TickObserved(u), ++seq, seq, null);
                }
                w.publishNow(); worlds.add(w);
            }
            settle(); long after = usedBytes();
            deltas[trial] = (after - before) / sc.observers();
            var snap = budget.snapshot(); shared = snap.sharedBytes(); rejections = snap.budgetRejections(); evicted = snap.evictedChunks();
            priv = 0; hist = 0; for (var w : worlds) { priv += w.privateBytes(); hist += w.snapshot().historyBytes(); }
            if (worlds.get(0).closed()) throw new AssertionError(); // keep reachable through the window
            for (var w : worlds) w.close();
        }
        long charged = (shared + priv + hist) / sc.observers();
        return new Result(sc, deltas, shared, priv, hist, rejections, evicted, charged);
    }

    public static void main(String[] args) throws IOException {
        int floor = WorldBudget.PRIVATE_FLOOR_BYTES, target = WorldBudget.PRIVATE_TARGET_BYTES;
        var scenarios = List.of(
            new Scenario("emptyWorld", 200, -1, false, 0, floor),
            new Scenario("oneChunk", 200, 0, false, 0, floor),
            new Scenario("oneChunkUnique", 100, 0, true, 0, floor),
            new Scenario("view25Shared", 50, 2, false, 0, floor),
            new Scenario("view25Unique", 10, 2, true, 0, floor),
            new Scenario("view121Shared", 50, 5, false, 0, floor),
            new Scenario("view121Unique", 10, 5, true, 0, floor),
            new Scenario("view289Shared", 20, 8, false, 0, floor),
            new Scenario("view289Unique", 10, 8, true, 0, floor),
            // WORLD_MODEL.md W2 asks Phase 3 to measure view distance 10/16 (441/1089 columns) at both divergence bounds; the 1089-unique case is expected to hit the shared partition and show explicit budget rejections
            new Scenario("view441Shared", 10, 10, false, 0, floor),
            new Scenario("view441Unique", 4, 10, true, 0, floor),
            new Scenario("view1089Shared", 5, 16, false, 0, floor),
            new Scenario("view1089Unique", 4, 16, true, 0, floor),
            new Scenario("mixedUpdatesHistory121", 20, 5, false, 3000, floor),
            new Scenario("worstRetentionTier289", 10, 8, false, 20000, target));
        var results = new ArrayList<Result>(); for (var sc : scenarios) { System.out.println("measuring " + sc.name()); results.add(measure(sc)); }

        var json = new StringBuilder("{\n  \"methodology\": \"forced-GC heap diff (Runtime totalMemory-freeMemory), 3 trials per scenario, median reported, per-observer average over N observers sharing ONE WorldBudget/BlockStateDictionary - an empirical estimate, not exact heap truth or a capacity claim. SHARED = identical chunk content for every observer (co-located), UNIQUE = distinct content per observer (spread out). chargedBytesPerObserver = (sharedBytes + privateBytes + historyBytes) / observers from the budget's own accounting.\",\n");
        json.append("  \"terrain\": \"24 sections: 8 ore-speckled stone (5-entry palette, 4 bits), 2 mixed surface (6-entry palette, 4 bits), 14 single-value air\",\n  \"memoryModel\": \"hybrid: content-addressed shared immutable section store (reference-counted, ").append(WorldBudget.SHARED_PARTITION_BYTES).append(" bytes global) + per-observer private tier (").append(floor).append(" floor / ").append(target).append(" target bytes) for update-created sections and history\",\n  \"scenarios\": {\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i); var s = r.scenario();
            json.append("    \"").append(s.name()).append("\": {\"observers\": ").append(s.observers()).append(", \"chunksPerObserver\": ").append(s.chunks()).append(", \"uniqueContent\": ").append(s.unique())
                .append(", \"updatesPerObserver\": ").append(s.updates()).append(", \"privateTier\": ").append(s.privateTier())
                .append(", \"trialsHeapBytesPerObserver\": ").append(Arrays.toString(r.trialsPerObserver())).append(", \"medianHeapBytesPerObserver\": ").append((long) median(r.trialsPerObserver()))
                .append(", \"chargedBytesPerObserver\": ").append(r.chargedPerObserver()).append(", \"sharedBytesTotal\": ").append(r.sharedBytes()).append(", \"privateBytesTotal\": ").append(r.privateBytes())
                .append(", \"historyBytesTotal\": ").append(r.historyBytes()).append(", \"budgetRejections\": ").append(r.budgetRejections()).append(", \"evictedChunks\": ").append(r.evictedChunks()).append("}");
            json.append(i + 1 < results.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        Path out = Path.of(args[0]); Files.createDirectories(out.getParent()); Files.writeString(out, json.toString());
        System.out.println(json);
    }
}
