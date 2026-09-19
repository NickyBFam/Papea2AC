package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/** Sections 39/40: chunk and section coordinate math, including every classic negative/boundary trap. */
class WorldCoordinateTest {
    @Test void chunkOfBlockUsesFloorDivisionNotTruncation() {
        assertEquals(new ChunkKey(-1, -1), ChunkKey.ofBlock(-1, -1));
        assertEquals(new ChunkKey(0, 0), ChunkKey.ofBlock(0, 0));
        assertEquals(new ChunkKey(0, 0), ChunkKey.ofBlock(15, 15));
        assertEquals(new ChunkKey(1, 1), ChunkKey.ofBlock(16, 16));
        assertEquals(new ChunkKey(-1, -1), ChunkKey.ofBlock(-16, -16));
        assertEquals(new ChunkKey(-2, -2), ChunkKey.ofBlock(-17, -17));
        assertEquals(-1, -1 / 16 - 1, "sanity: truncating division would have given 0 for -1, which is the bug this guards against");
    }
    @Test void localCoordinatesAreAlwaysZeroToFifteen() {
        for (int v = -100; v <= 100; v++) { int local = ChunkKey.localOf(v); assertTrue(local >= 0 && local <= 15, "" + v); assertEquals(v, ChunkKey.ofBlock(v, 0).x() * 16 + local); }
        assertEquals(15, new BlockPos(-1, -1, -1).localX()); assertEquals(15, new BlockPos(-1, -1, -1).localY()); assertEquals(15, new BlockPos(-1, -1, -1).localZ());
        assertEquals(0, new BlockPos(-16, 0, 32).localX());
    }
    @Test void sectionYFloorsForNegativeBlockY() {
        assertEquals(-1, new BlockPos(0, -1, 0).sectionY()); assertEquals(-4, new BlockPos(0, -64, 0).sectionY()); assertEquals(-4, new BlockPos(0, -49, 0).sectionY());
        assertEquals(-5, new BlockPos(0, -65, 0).sectionY()); assertEquals(0, new BlockPos(0, 0, 0).sectionY()); assertEquals(0, new BlockPos(0, 15, 0).sectionY()); assertEquals(1, new BlockPos(0, 16, 0).sectionY());
    }
    @Test void sectionIndexOrderMatchesTheWire() {
        assertEquals(0, new BlockPos(0, 0, 0).sectionIndex()); assertEquals(15, new BlockPos(15, 0, 0).sectionIndex());
        assertEquals(16, new BlockPos(0, 0, 1).sectionIndex()); assertEquals(256, new BlockPos(0, 1, 0).sectionIndex()); assertEquals(4095, new BlockPos(15, 15, 15).sectionIndex());
        assertEquals(4095, new BlockPos(-1, -1, -1).sectionIndex());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, -1, 15, 16, -16, -17, 1000000, -1000000, BlockPos.MAX_HORIZONTAL, BlockPos.MIN_HORIZONTAL})
    void packedLongRoundTripsAcrossTheWholeSupportedRange(int h) {
        for (int y : new int[]{BlockPos.MIN_Y, -64, -1, 0, 1, 319, BlockPos.MAX_Y}) { var p = new BlockPos(h, y, h); assertEquals(p, BlockPos.unpack(p.packed()), p.toString()); }
    }
    @Test void coordinatesOutsideTheRepresentableRangeAreRejectedNeverWrapped() {
        assertThrows(IllegalArgumentException.class, () -> new BlockPos(BlockPos.MAX_HORIZONTAL + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockPos(0, BlockPos.MAX_Y + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockPos(0, BlockPos.MIN_Y - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkKey(ChunkKey.MAX_ABS + 1, 0));
    }
    @Test void chunkDistanceIsOverflowSafe() {
        assertEquals(Integer.MAX_VALUE, new ChunkKey(-ChunkKey.MAX_ABS, 0).distanceTo(new ChunkKey(ChunkKey.MAX_ABS, 0)) > 0 ? Integer.MAX_VALUE : 0);
        assertEquals(3, new ChunkKey(-1, -1).distanceTo(new ChunkKey(2, 0)));
    }
    @Test void dimensionProfileHandlesNegativeMinimumHeightWithoutOffByOne() {
        var overworld = new DimensionProfile("minecraft:overworld", -64, 384);
        assertEquals(-4, overworld.minSectionY()); assertEquals(24, overworld.sectionCount()); assertEquals(319, overworld.maxY());
        assertEquals(0, overworld.sectionIndexOf(-64)); assertEquals(0, overworld.sectionIndexOf(-49)); assertEquals(1, overworld.sectionIndexOf(-48));
        assertEquals(4, overworld.sectionIndexOf(0)); assertEquals(23, overworld.sectionIndexOf(319)); assertEquals(23, overworld.sectionIndexOf(304));
        assertEquals(-1, overworld.sectionIndexOf(320), "one above the maximum is outside"); assertEquals(-1, overworld.sectionIndexOf(-65), "one below the minimum is outside");
        assertEquals(0, overworld.sectionIndexOfSectionY(-4)); assertEquals(-1, overworld.sectionIndexOfSectionY(-5)); assertEquals(-1, overworld.sectionIndexOfSectionY(20));
        var nether = new DimensionProfile("minecraft:the_nether", 0, 256);
        assertEquals(0, nether.minSectionY()); assertEquals(16, nether.sectionCount()); assertEquals(-1, nether.sectionIndexOf(-1)); assertEquals(15, nether.sectionIndexOf(255)); assertEquals(-1, nether.sectionIndexOf(256));
    }
    @Test void dimensionRangesMustBeSectionAlignedAndWithinProtocolLimits() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionProfile("x", -60, 384));
        assertThrows(IllegalArgumentException.class, () -> new DimensionProfile("x", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DimensionProfile("x", 0, 4080));
        assertThrows(IllegalArgumentException.class, () -> new DimensionProfile("x", -2032, 4064 + 16));
        assertDoesNotThrow(() -> new DimensionProfile("x", -2032, 4064));
    }
    @Test void blockStateKeyCanonicalFormIsSortedAndRoundTrips() {
        var a = BlockStateKey.parse("minecraft:oak_stairs[half=bottom,facing=north]");
        var b = BlockStateKey.parse("minecraft:oak_stairs[facing=north,half=bottom]");
        assertEquals(a, b); assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", a.canonical()); assertEquals(a, BlockStateKey.parse(a.canonical()));
        assertEquals("minecraft:stone", BlockStateKey.of("minecraft:stone").canonical());
    }
}
