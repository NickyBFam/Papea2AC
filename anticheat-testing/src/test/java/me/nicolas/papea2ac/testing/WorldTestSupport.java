package me.nicolas.papea2ac.testing;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/** Builders for world observations in tests: packs palette indices exactly like the wire (y<<8|z<<4|x, values never straddle a long). */
final class WorldTestSupport {
    private WorldTestSupport() {}
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt"),
        WATER = BlockStateKey.parse("minecraft:water[level=0]"), STAIRS = BlockStateKey.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    static final WorldObservation.DimensionEntered NETHER = new WorldObservation.DimensionEntered("minecraft:the_nether", 0, 256, true);
    static final int OVERWORLD_SECTIONS = 24;

    static WorldObservation.SectionContent uniformSection(BlockStateKey state) { return new WorldObservation.SectionContent(state.equals(AIR) ? 0 : 4096, List.of(state), 0, new long[0]); }

    /** Section whose cell states are given by a function of (x,y,z); palette built in first-seen order. */
    static WorldObservation.SectionContent section(java.util.function.IntFunction<BlockStateKey> stateAtIndex) {
        var palette = new ArrayList<BlockStateKey>(); var slots = new HashMap<BlockStateKey, Integer>(); int[] idx = new int[4096]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { var s = stateAtIndex.apply(i); Integer slot = slots.get(s); if (slot == null) { slot = palette.size(); palette.add(s); slots.put(s, slot); } idx[i] = slot; if (!s.equals(AIR)) nonEmpty++; }
        if (palette.size() == 1) return new WorldObservation.SectionContent(nonEmpty, palette, 0, new long[0]);
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)), perLong = 64 / bits;
        long[] packed = new long[SectionSnapshot.packedLongs(bits)];
        for (int i = 0; i < 4096; i++) packed[i / perLong] |= ((long) idx[i]) << ((i % perLong) * bits);
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static int index(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    /** Overworld-shaped chunk: sections below index 8 (y < 64) stone, index 8 a stone/dirt/air surface, the rest air. */
    static WorldObservation.ChunkBaselineObserved baseline(int cx, int cz) {
        var sections = new ArrayList<WorldObservation.SectionContent>();
        for (int i = 0; i < OVERWORLD_SECTIONS; i++) {
            if (i < 8) sections.add(uniformSection(STONE));
            else if (i == 8) sections.add(section(idx -> { int y = idx >> 8; return y < 4 ? STONE : y < 6 ? DIRT : AIR; }));
            else sections.add(uniformSection(AIR));
        }
        return new WorldObservation.ChunkBaselineObserved(cx, cz, true, sections, 0, "");
    }
    static WorldObservation.ChunkBaselineObserved baseline(int cx, int cz, List<WorldObservation.SectionContent> sections) { return new WorldObservation.ChunkBaselineObserved(cx, cz, true, sections, 0, ""); }
    static WorldObservation.ChunkBaselineObserved unretained(int cx, int cz, String note) { return new WorldObservation.ChunkBaselineObserved(cx, cz, false, List.of(), 0, note); }

    static ObserverWorld world(SessionKey key, WorldBudget budget, BlockStateDictionary dictionary, long firstSeq) {
        var w = new ObserverWorld(key, budget, dictionary);
        w.apply(key, OVERWORLD, firstSeq, 0, null);
        return w;
    }
}
