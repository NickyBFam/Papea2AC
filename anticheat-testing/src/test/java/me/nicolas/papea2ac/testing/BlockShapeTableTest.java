package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.version.*;

/** The version-pinned shape table as production loads it: identity, coverage, strict lookup semantics and a strict parser. */
class BlockShapeTableTest {
    static final BlockShapeTable T = BlockShapeTable.paper26_2();
    @Test void tableIdentityAndCoverageMatchTheExtractionSummary() throws Exception {
        assertEquals("paper-26.2-124", T.profile());
        assertEquals(1196, T.blockCount()); assertEquals(32366, T.stateCount()); assertTrue(T.shapeCount() >= 320 && T.shapeCount() <= 340, "shapes: " + T.shapeCount());
        assertEquals(0, T.boxCount(0), "index 0 is the empty shape"); assertNull(T.grid(0));
        String summary = Files.readString(Path.of(System.getProperty("repo.root")).resolve("docs/phase4-artifacts/shape-table-summary.json"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("\"tableSha256\": \"" + T.digest() + "\""), "committed summary names the loaded resource digest " + T.digest());
        assertTrue(summary.contains("\"tableBytes\": " + T.resourceBytes()));
        assertEquals(CollisionProfile.PAPER_26_2.table(), T, "one shared instance per profile");
    }
    @Test void lookupIsExactNeverAGuess() {
        var stone = T.lookup(BlockStateKey.of("minecraft:stone")); assertNotNull(stone); assertEquals(1, T.boxCount(stone.shapeIndex())); assertEquals(BlockShapeTable.Kind.STATIC, stone.block().kind());
        assertNull(T.lookup(BlockStateKey.of("minecraft:not_a_block")), "unknown block");
        assertNull(T.lookup(BlockStateKey.of("minecraft:oak_slab")), "missing properties are not defaulted");
        assertNull(T.lookup(BlockStateKey.parse("minecraft:oak_slab[type=sideways,waterlogged=false]")), "unknown property value");
        assertNull(T.lookup(BlockStateKey.parse("minecraft:oak_slab[type=top,waterlogged=false,extra=1]")), "extra property");
        assertNull(T.lookup(BlockStateKey.parse("minecraft:stone[facing=north]")), "property on a property-less block");
        BlockShapeTable.Entry slabTop = T.lookup(BlockStateKey.parse("minecraft:oak_slab[type=top,waterlogged=false]")), slabBottom = T.lookup(BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=true]"));
        assertNotEquals(slabTop.shapeIndex(), slabBottom.shapeIndex()); assertArrayEquals(new double[] { 0, 0.5, 0, 1, 1, 1 }, T.shape(slabTop.shapeIndex())); assertArrayEquals(new double[] { 0, 0, 0, 1, 0.5, 1 }, T.shape(slabBottom.shapeIndex()));
        // mixed-radix indexing: every state of a block maps to a distinct in-range index
        var stairs = T.block("minecraft:oak_stairs"); var seen = new HashSet<Integer>();
        for (String f : stairs.values(0)) for (String h : stairs.values(1)) for (String s : stairs.values(2)) for (String w : stairs.values(3)) {
            var key = BlockStateKey.parse("minecraft:oak_stairs[facing=" + f + ",half=" + h + ",shape=" + s + ",waterlogged=" + w + "]");
            int idx = stairs.stateIndex(key); assertTrue(idx >= 0 && idx < 80); assertTrue(seen.add(idx));
        }
        assertEquals(80, seen.size());
    }
    @Test void largeFlagAndNamedEntriesAndPoses() {
        var fence = T.lookup(BlockStateKey.parse("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"));
        assertTrue(T.large(fence.shapeIndex()), "fence posts are 1.5 high: they reach into the cell above");
        assertFalse(T.large(T.lookup(BlockStateKey.of("minecraft:stone")).shapeIndex()));
        assertEquals(T.lookup(BlockStateKey.of("minecraft:stone")).shapeIndex(), T.namedShape("full_cube").intValue());
        assertArrayEquals(new double[] { 0, 0, 0, 1, 0.8999999761581421, 1 }, T.shape(T.namedShape("powder_snow_falling")));
        assertArrayEquals(new float[] { 0.6f, 1.8f }, T.poseDimensions("STANDING")); assertArrayEquals(new float[] { 0.6f, 1.5f }, T.poseDimensions("CROUCHING")); assertArrayEquals(new float[] { 0.6f, 0.6f }, T.poseDimensions("SWIMMING")); assertArrayEquals(new float[] { 0.2f, 0.2f }, T.poseDimensions("SLEEPING"));
        assertNull(T.poseDimensions("FLYING_SQUIRREL"));
        var grid = T.grid(T.lookup(BlockStateKey.parse("minecraft:oak_slab[type=top,waterlogged=false]")).shapeIndex());
        assertEquals(2, grid.ny()); assertFalse(grid.isFull(0, 0, 0)); assertTrue(grid.isFull(0, 1, 0));
    }
    @Test void everyDynamicKindIsExactlyTheExtractedFamily() {
        var kinds = new TreeMap<BlockShapeTable.Kind, List<String>>();
        for (var b : T.blocks()) if (b.kind() != BlockShapeTable.Kind.STATIC) kinds.computeIfAbsent(b.kind(), k -> new ArrayList<>()).add(b.name());
        assertEquals(List.of("minecraft:bamboo", "minecraft:pointed_dripstone", "minecraft:sulfur_spike"), kinds.get(BlockShapeTable.Kind.OFFSET_XZ));
        assertEquals(List.of("minecraft:scaffolding"), kinds.get(BlockShapeTable.Kind.CONTEXT));
        assertEquals(List.of("minecraft:powder_snow"), kinds.get(BlockShapeTable.Kind.ENTITY_CONTEXT));
        assertEquals(18, kinds.get(BlockShapeTable.Kind.BLOCK_ENTITY).size(), "17 shulker boxes (16 dyes + undyed) + moving piston"); assertTrue(kinds.get(BlockShapeTable.Kind.BLOCK_ENTITY).contains("minecraft:moving_piston"));
        assertEquals(0.25f, T.block("minecraft:bamboo").maxHorizontalOffset()); assertEquals(0.125f, T.block("minecraft:pointed_dripstone").maxHorizontalOffset());
    }
    @Test void parserRejectsMalformedTablesInsteadOfLoadingPartially() {
        String good = "# profile x\nS 0\nG 0 0 0 0\nS 1 0.0 0.0 0.0 1.0 1.0 1.0\nG 1 1 1 1 0.0 1.0 0.0 1.0 0.0 1.0 8\nS 2 0.0 0.0 0.0 1.0 0.9 1.0\nG 2 1 1 1 0.0 1.0 0.0 0.9 0.0 1.0 8\nB minecraft:a 2 STATIC t=x,y\nI 1 0\nX full_cube 1\nX powder_snow_falling 2\nP STANDING 0.6 1.8\n";
        var t = BlockShapeTable.parse(good.getBytes(StandardCharsets.UTF_8)); assertEquals(1, t.blockCount()); assertEquals(1, t.lookup(BlockStateKey.parse("minecraft:a[t=x]")).shapeIndex());
        for (String bad : List.of(good.replace("I 1 0", "I 1"), good.replace("I 1 0", "I 1 0 1"), good.replace("S 2 0.0 0.0 0.0 1.0 0.9 1.0", "S 2 0.0 0.0 0.0 1.0 NaN 1.0"), good.replace("S 2 0.0 0.0 0.0 1.0 0.9 1.0", "S 2 1.0 0.0 0.0 0.0 0.9 1.0"),
                good.replace("minecraft:a 2 STATIC", "minecraft:a 3 STATIC"), good.replace("X full_cube 1\n", ""), good.replace("G 1 1 1 1 0.0 1.0 0.0 1.0 0.0 1.0 8", "G 1 1 1 1 0.0 1.0 0.0 1.0 0.0 1.0 0"), good.replace("B minecraft:a", "Q minecraft:a"), good + "B minecraft:a 1 STATIC\nI 1\n"))
            assertThrows(RuntimeException.class, () -> BlockShapeTable.parse(bad.getBytes(StandardCharsets.UTF_8)), bad);
    }
}
