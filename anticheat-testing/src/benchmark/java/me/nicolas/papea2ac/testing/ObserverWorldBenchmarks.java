package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/** Phase 3 JMH kernels (same methodology as PacketBenchmarks / PlayerTimelineBenchmarks): descriptive anomaly
 * detection for the observer-world hot paths, never a production-capacity claim. Overworld-shaped 24-section chunks. */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=5,time=1) @Measurement(iterations=10,time=1) @Fork(value=3,jvmArgsAppend={"-Xms512m","-Xmx512m"})
@State(Scope.Thread)
public class ObserverWorldBenchmarks {
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt"),
        STAIRS = BlockStateKey.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    private final SessionKey key = new SessionKey(new UUID(3, 3), 1);
    /** One sequence per world: the world treats a gap in its session sequence as a dropped observation (coverage break), so counters must not be shared across worlds. */
    private static final class W { final ObserverWorld world; long seq; W(ObserverWorld w) { world = w; } boolean apply(WorldObservation o) { return world.apply(world.key(), o, ++seq, seq, null); } }
    private WorldBudget budget; private BlockStateDictionary dictionary;
    private W ingestWorld, uniqueIngestWorld, updateWorld, lookupWorld, historyWorld, fullHistoryWorld, unloadWorld, wideWorld;
    private WorldObservation.ChunkBaselineObserved sharedBaseline; private WorldObservation.ChunkBaselineObserved[] uniqueBaselines;
    private WorldObservation.SectionBlocksChanged multi, multiAlt; private SectionSnapshot section;
    private int cursor;
    private final BlockPos[] cells = new BlockPos[64];

    static WorldObservation.SectionContent uniform(BlockStateKey s) { return new WorldObservation.SectionContent(s.equals(AIR) ? 0 : 4096, List.of(s), 0, new long[0]); }
    static WorldObservation.SectionContent surface(int salt) {
        var palette = List.of(STONE, DIRT, AIR); int bits = 4, perLong = 16; long[] packed = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int y = i >> 8; int slot = y < 4 ? 0 : y < 6 ? 1 : 2; if ((i + salt) % 131 == 0) slot = 1; if (slot != 2) nonEmpty++; packed[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static WorldObservation.ChunkBaselineObserved chunk(int x, int z, int salt) {
        var sections = new ArrayList<WorldObservation.SectionContent>(24);
        for (int i = 0; i < 24; i++) sections.add(i < 8 ? uniform(STONE) : i == 8 ? surface(salt) : uniform(AIR));
        return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, "");
    }
    private W fresh() { var w = new W(new ObserverWorld(key, budget, dictionary)); w.apply(OVERWORLD); return w; }

    @Setup public void setup() {
        budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, WorldBudget.PRIVATE_TARGET_BYTES); dictionary = new BlockStateDictionary();
        sharedBaseline = chunk(0, 0, 0);
        uniqueBaselines = new WorldObservation.ChunkBaselineObserved[64]; for (int i = 0; i < 64; i++) uniqueBaselines[i] = chunk(i, 0, i + 1);
        ingestWorld = fresh(); uniqueIngestWorld = fresh();
        updateWorld = fresh(); updateWorld.apply(sharedBaseline);
        for (int i = 0; i < 64; i++) cells[i] = new BlockPos(i & 15, 64 + ((i >> 4) & 3), (i >> 2) & 15);
        var changes = new ArrayList<WorldObservation.BlockChange>(); for (int i = 0; i < 16; i++) changes.add(new WorldObservation.BlockChange(new BlockPos(i, 66, 7), STAIRS));
        multi = new WorldObservation.SectionBlocksChanged(0, 4, 0, changes);
        var alt = new ArrayList<WorldObservation.BlockChange>(); for (int i = 0; i < 16; i++) alt.add(new WorldObservation.BlockChange(new BlockPos(i, 66, 7), DIRT));
        multiAlt = new WorldObservation.SectionBlocksChanged(0, 4, 0, alt);
        lookupWorld = fresh(); for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) lookupWorld.apply(chunk(x, z, 0)); // 121 chunks
        historyWorld = fresh(); historyWorld.apply(sharedBaseline);
        for (int i = 0; i < 2048; i++) historyWorld.apply(new WorldObservation.BlockChanged(cells[i & 63], (i & 1) == 0 ? STAIRS : DIRT));
        fullHistoryWorld = fresh(); fullHistoryWorld.apply(sharedBaseline);
        for (int i = 0; i < ObserverWorld.HISTORY_MAX_ENTRIES; i++) fullHistoryWorld.apply(new WorldObservation.BlockChanged(cells[i & 63], (i & 1) == 0 ? STAIRS : DIRT));
        unloadWorld = fresh(); unloadWorld.apply(sharedBaseline);
        wideWorld = lookupWorld;
        section = SectionSnapshot.uniform(dictionary.intern(STONE)).with(0, dictionary.intern(DIRT));
    }

    /** Full 24-section baseline whose content is already in the shared store (the common many-observers case): resend/replace path incl. history push. */
    @Benchmark public boolean fullChunkBaselineIngestSharedHit() { return ingestWorld.apply(sharedBaseline); }
    /** Baseline whose section 8 content is unique per chunk (shared-store miss + acquire); rotates over 64 chunks so replacement, not growth, dominates. */
    @Benchmark public boolean fullChunkBaselineIngestUniqueContent() { return uniqueIngestWorld.apply(uniqueBaselines[(cursor++) & 63]); }
    @Benchmark public boolean singleBlockUpdate() { return updateWorld.apply(new WorldObservation.BlockChanged(cells[(cursor++) & 63], (cursor & 1) == 0 ? STAIRS : DIRT)); }
    @Benchmark public boolean multiBlockSectionUpdate16() { return updateWorld.apply(((cursor++) & 1) == 0 ? multi : multiAlt); }
    @Benchmark public BlockLookup currentBlockLookup() { return lookupWorld.world.blockAt(cells[(cursor++) & 63]); }
    @Benchmark public BlockLookup currentBlockLookupViaSnapshot() { return lookupWorld.world.snapshot().blockAt(cells[(cursor++) & 63], dictionary); }
    /** Exact historical lookup against a 2048-entry history ring (linear scan of the ring is the documented cost model). */
    @Benchmark public BlockLookup historicalBlockLookup() { return historyWorld.world.blockAtSequence(cells[(cursor++) & 63], historyWorld.world.lastSequence() - 1024); }
    @Benchmark public SectionSnapshot sectionCopyOnWrite() { return section.with((cursor++) & 4095, (cursor & 1) == 0 ? 0 : 1); }
    /** Unload of a present chunk followed by its re-baseline (a bare unload of an absent chunk is trivial); subtract fullChunkBaselineIngestSharedHit for the unload alone. */
    @Benchmark public boolean chunkUnloadAndRebaseline() { unloadWorld.apply(new WorldObservation.ChunkUnloaded(0, 0)); return unloadWorld.apply(sharedBaseline); }
    /** History already at its bound (HISTORY_MAX_ENTRIES or the private byte tier, whichever binds first - here the tier): every update evicts the oldest revision (steady-state overwrite cost). */
    @Benchmark public boolean historyEvictionAtCapacity() { return fullHistoryWorld.apply(new WorldObservation.BlockChanged(cells[(cursor++) & 63], (cursor & 1) == 0 ? STAIRS : DIRT)); }
    /** Snapshot publication over a 121-chunk world (immutable map copy on tick/structural change). */
    @Benchmark public ObserverWorld.WorldSnapshot publishSnapshot121Chunks() { wideWorld.world.publishNow(); return wideWorld.world.snapshot(); }
}
