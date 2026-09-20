package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.BlockShapeTable;
import me.nicolas.papea2ac.world.BlockStateDictionary;

/**
 * Paper-free family matrix (task §49 items 10-29, §51): expected geometry is stated independently from documented
 * vanilla block geometry (sixteenths), never read back from the table, so the table+resolver are checked against an
 * external statement of what the block IS - the native run adds the bit-exact oracle on top.
 */
class BlockShapeResolverTest {
    static BlockStateDictionary dict; static BlockShapeResolver r; static CollisionMetrics metrics;
    @BeforeAll static void setUp() { dict = new BlockStateDictionary(); metrics = new CollisionMetrics(); r = new BlockShapeResolver(PROFILE, dict, metrics); }
    static CollisionShape shape(String canonical) { var e = r.plan(BlockStateKey.parse(canonical)); assertTrue(e.supported(), canonical); var res = r.resolve(e, 0, 0, 0, ShapeContext.UNKNOWN); assertTrue(res.exact(), canonical + " -> " + res.status()); return res.lower(); }
    static Set<List<Double>> boxes(CollisionShape s) { var out = new HashSet<List<Double>>(); for (int i = 0; i < s.boxCount(); i++) out.add(List.of(s.minX(i), s.minY(i), s.minZ(i), s.maxX(i), s.maxY(i), s.maxZ(i))); return out; }
    static List<Double> px(int x0, int y0, int z0, int x1, int y1, int z1) { return List.of(x0 / 16.0, y0 / 16.0, z0 / 16.0, x1 / 16.0, y1 / 16.0, z1 / 16.0); }
    static boolean covers(CollisionShape s, double x, double y, double z) { for (int i = 0; i < s.boxCount(); i++) if (s.minX(i) <= x && x < s.maxX(i) && s.minY(i) <= y && y < s.maxY(i) && s.minZ(i) <= z && z < s.maxZ(i)) return true; return false; }
    static double topArea(CollisionShape s, double y) { double a = 0; for (int i = 0; i < s.boxCount(); i++) if (s.minY(i) <= y && y < s.maxY(i)) a += (s.maxX(i) - s.minX(i)) * (s.maxZ(i) - s.minZ(i)); return a; }

    @Test void fullCubesAirAndFluidsAndNonCollidingPlants() {
        assertTrue(shape("minecraft:stone").isFullCube()); assertTrue(shape("minecraft:glass").isFullCube()); assertTrue(shape("minecraft:barrier[waterlogged=false]").isFullCube());
        assertSame(CollisionShape.FULL_CUBE, shape("minecraft:stone")); assertSame(shape("minecraft:stone"), shape("minecraft:dirt"), "identical geometry is one shared instance");
        for (String s : List.of("minecraft:air", "minecraft:water[level=0]", "minecraft:lava[level=0]", "minecraft:short_grass", "minecraft:torch", "minecraft:cobweb", "minecraft:oak_sign[rotation=0,waterlogged=false]", "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]", "minecraft:oak_pressure_plate[powered=false]", "minecraft:snow[layers=1]", "minecraft:oak_fence_gate[facing=north,in_wall=false,open=true,powered=false]"))
            assertTrue(shape(s).isEmpty(), s + " has no collision");
        assertEquals(CellStatus.EXACT_EMPTY, r.resolve(r.plan(BlockStateKey.of("minecraft:air")), 0, 0, 0, ShapeContext.UNKNOWN).status());
    }
    @Test void slabsBottomTopDouble() {
        assertEquals(Set.of(px(0, 0, 0, 16, 8, 16)), boxes(shape("minecraft:oak_slab[type=bottom,waterlogged=false]")));
        assertEquals(Set.of(px(0, 8, 0, 16, 16, 16)), boxes(shape("minecraft:oak_slab[type=top,waterlogged=true]")));
        assertTrue(shape("minecraft:oak_slab[type=double,waterlogged=false]").isFullCube());
        assertEquals(boxes(shape("minecraft:oak_slab[type=bottom,waterlogged=false]")), boxes(shape("minecraft:stone_brick_slab[type=bottom,waterlogged=false]")), "slab geometry is material-independent");
    }
    @Test void stairsStraightInnerOuterAndUpperHalfForEveryFacing() {
        for (String facing : List.of("north", "south", "west", "east")) {
            var straight = shape("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]");
            assertEquals(1.0, topArea(straight, 0.25), 1e-12, "bottom stairs stand on a full bottom slab"); assertEquals(0.5, topArea(straight, 0.75), 1e-12, "straight: the upper step covers half the block");
            assertEquals(0.75, topArea(shape("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=inner_left,waterlogged=false]"), 0.75), 1e-12, "inner corner: three quarters");
            assertEquals(0.75, topArea(shape("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=inner_right,waterlogged=false]"), 0.75), 1e-12);
            assertEquals(0.25, topArea(shape("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=outer_left,waterlogged=false]"), 0.75), 1e-12, "outer corner: one quarter");
            assertEquals(0.25, topArea(shape("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=outer_right,waterlogged=false]"), 0.75), 1e-12);
            var top = shape("minecraft:oak_stairs[facing=" + facing + ",half=top,shape=straight,waterlogged=false]");
            assertEquals(1.0, topArea(top, 0.75), 1e-12, "upper stairs hang from a full top slab"); assertEquals(0.5, topArea(top, 0.25), 1e-12);
        }
        // the step of a north-facing bottom stair is on the north (low z) side, and so on for each facing
        var n = shape("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"); assertTrue(covers(n, 0.5, 0.75, 0.25)); assertFalse(covers(n, 0.5, 0.75, 0.75));
        var s = shape("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"); assertTrue(covers(s, 0.5, 0.75, 0.75)); assertFalse(covers(s, 0.5, 0.75, 0.25));
        var w = shape("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]"); assertTrue(covers(w, 0.25, 0.75, 0.5)); assertFalse(covers(w, 0.75, 0.75, 0.5));
        var e = shape("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"); assertTrue(covers(e, 0.75, 0.75, 0.5)); assertFalse(covers(e, 0.25, 0.75, 0.5));
        var inner = shape("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]"); assertTrue(covers(inner, 0.5, 0.75, 0.25)); assertEquals(1, 4 - (int) Math.round(topArea(inner, 0.75) * 4), "exactly one quadrant missing");
        var outer = shape("minecraft:oak_stairs[facing=north,half=bottom,shape=outer_left,waterlogged=false]"); assertEquals(1, (int) Math.round(topArea(outer, 0.75) * 4), "exactly one quadrant present");
    }
    @Test void fencesAndTheirConnectionsAreStateDerivedAndOneAndAHalfHigh() {
        var post = shape("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        assertEquals(Set.of(px(6, 0, 6, 10, 24, 10)), boxes(post)); assertTrue(post.large(), "1.5 high: reaches into the cell above");
        var ns = shape("minecraft:oak_fence[east=false,north=true,south=true,waterlogged=false,west=false]");
        assertTrue(covers(ns, 0.5, 1.2, 0.05) && covers(ns, 0.5, 1.2, 0.95), "north and south arms, full 1.5 height"); assertFalse(covers(ns, 0.5, 0.5, 0.3) == false, "post between the arms");
        assertFalse(covers(ns, 0.1, 0.5, 0.5), "no east/west arm when not connected"); assertFalse(covers(ns, 0.3, 0.5, 0.05), "arms are 4/16 wide (6..10)"); assertTrue(covers(ns, 0.4, 0.5, 0.05));
        var all = shape("minecraft:oak_fence[east=true,north=true,south=true,waterlogged=false,west=true]"); assertEquals(1.5, all.bounds().maxY()); assertEquals(0.0, all.bounds().minX()); assertEquals(1.0, all.bounds().maxX());
        assertEquals(boxes(post), boxes(shape("minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]")));
    }
    @Test void wallsPostAndSideHeightsAndPanes() {
        assertEquals(Set.of(px(4, 0, 4, 12, 24, 12)), boxes(shape("minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")), "wall post 8x24x8");
        var low = shape("minecraft:cobblestone_wall[east=low,north=none,south=none,up=false,waterlogged=false,west=low]");
        assertEquals(1.5, low.bounds().maxY(), "wall collision is 1.5 high even for low walls"); assertTrue(covers(low, 0.95, 0.5, 0.5)); assertFalse(covers(low, 0.5, 0.5, 0.05));
        var tall = shape("minecraft:cobblestone_wall[east=none,north=tall,south=tall,up=false,waterlogged=false,west=none]"); assertTrue(covers(tall, 0.5, 1.2, 0.05));
        assertEquals(Set.of(px(7, 0, 7, 9, 16, 9)), boxes(shape("minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]")));
        assertTrue(covers(shape("minecraft:iron_bars[east=true,north=false,south=false,waterlogged=false,west=false]"), 0.95, 0.5, 0.5));
    }
    @Test void gatesDoorsAndTrapdoorsRotateWithState() {
        assertEquals(Set.of(px(0, 0, 6, 16, 24, 10)), boxes(shape("minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]")), "closed gate: wall across, 1.5 high");
        assertEquals(Set.of(px(6, 0, 0, 10, 24, 16)), boxes(shape("minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]")));
        assertTrue(shape("minecraft:oak_fence_gate[facing=north,in_wall=false,open=true,powered=false]").isEmpty(), "open gate: passable");
        assertEquals(Set.of(px(0, 0, 6, 16, 24, 10)), boxes(shape("minecraft:oak_fence_gate[facing=north,in_wall=true,open=false,powered=false]")), "in_wall lowers the outline only; the collision wall stays 1.5 high");
        assertEquals(Set.of(px(0, 0, 13, 16, 16, 16)), boxes(shape("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]")), "closed north-facing door occupies the south face");
        assertEquals(Set.of(px(0, 0, 0, 3, 16, 16)), boxes(shape("minecraft:oak_door[facing=north,half=lower,hinge=left,open=true,powered=false]")), "opened on the left hinge it swings to the west face");
        assertEquals(Set.of(px(13, 0, 0, 16, 16, 16)), boxes(shape("minecraft:oak_door[facing=north,half=lower,hinge=right,open=true,powered=false]")), "right hinge: east face");
        assertEquals(boxes(shape("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]")), boxes(shape("minecraft:iron_door[facing=north,half=upper,hinge=left,open=false,powered=true]")), "upper half and material share the geometry");
        assertEquals(Set.of(px(0, 0, 0, 16, 3, 16)), boxes(shape("minecraft:oak_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]")));
        assertEquals(Set.of(px(0, 13, 0, 16, 16, 16)), boxes(shape("minecraft:oak_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]")));
        assertEquals(Set.of(px(0, 0, 13, 16, 16, 16)), boxes(shape("minecraft:oak_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]")), "open trapdoor stands against its hinge face");
        assertEquals(Set.of(px(0, 0, 0, 3, 16, 16)), boxes(shape("minecraft:oak_trapdoor[facing=east,half=top,open=true,powered=false,waterlogged=false]")));
    }
    @Test void snowLayersCarpetLadderBedChestHopperCauldronAndOtherPartialBlocks() {
        assertTrue(shape("minecraft:snow[layers=1]").isEmpty(), "one layer: no collision");
        for (int layers = 2; layers <= 8; layers++) assertEquals(Set.of(px(0, 0, 0, 16, 2 * (layers - 1), 16)), boxes(shape("minecraft:snow[layers=" + layers + "]")), "snow collision is one layer (2/16) below its visual top");
        assertEquals(Set.of(px(0, 0, 0, 16, 1, 16)), boxes(shape("minecraft:white_carpet"))); assertEquals(boxes(shape("minecraft:white_carpet")), boxes(shape("minecraft:moss_carpet")));
        assertEquals(Set.of(px(0, 0, 13, 16, 16, 16)), boxes(shape("minecraft:ladder[facing=north,waterlogged=false]"))); assertEquals(Set.of(px(13, 0, 0, 16, 16, 16)), boxes(shape("minecraft:ladder[facing=west,waterlogged=false]")));
        var bed = shape("minecraft:red_bed[facing=north,occupied=false,part=head]"); assertEquals(0.5625, bed.bounds().maxY(), "bed top at 9/16"); assertTrue(bed.boxCount() >= 2, "bed = mattress + legs");
        assertEquals(Set.of(px(1, 0, 1, 15, 14, 15)), boxes(shape("minecraft:chest[facing=north,type=single,waterlogged=false]"))); assertEquals(boxes(shape("minecraft:chest[facing=north,type=single,waterlogged=false]")), boxes(shape("minecraft:ender_chest[facing=east,waterlogged=false]")));
        assertTrue(shape("minecraft:chest[facing=north,type=left,waterlogged=false]").bounds().sizeX() > 0.875, "a double chest half reaches the shared edge");
        var hopper = shape("minecraft:hopper[enabled=true,facing=down]"); assertEquals(13, hopper.boxCount()); assertFalse(covers(hopper, 0.5, 0.8, 0.5), "hopper bowl is hollow"); assertTrue(covers(hopper, 0.5, 0.5, 0.5), "hopper funnel is solid"); assertTrue(covers(hopper, 0.05, 0.9, 0.5), "hopper rim");
        var cauldron = shape("minecraft:cauldron"); assertEquals(15, cauldron.boxCount()); assertFalse(covers(cauldron, 0.5, 0.5, 0.5), "cauldron interior is hollow"); assertTrue(covers(cauldron, 0.5, 0.2, 0.5), "cauldron floor plate (3/16..4/16)"); assertFalse(covers(cauldron, 0.5, 0.1, 0.5), "hollow under the floor between the legs"); assertTrue(covers(cauldron, 0.05, 0.1, 0.05), "leg"); assertEquals(boxes(cauldron), boxes(shape("minecraft:water_cauldron[level=3]")));
        assertEquals(Set.of(px(1, 0, 1, 15, 15, 15)), boxes(shape("minecraft:cactus[age=0]"))); assertEquals(Set.of(px(0, 0, 0, 16, 14, 16)), boxes(shape("minecraft:soul_sand")));
        assertEquals(Set.of(px(0, 0, 0, 16, 15, 16)), boxes(shape("minecraft:farmland[moisture=0]"))); assertEquals(Set.of(px(0, 0, 0, 16, 15, 16)), boxes(shape("minecraft:dirt_path")));
        assertEquals(Set.of(px(1, 0, 1, 15, 15, 15)), boxes(shape("minecraft:honey_block"))); assertEquals(Set.of(px(0, 0, 0, 16, 12, 16)), boxes(shape("minecraft:enchanting_table")));
        assertEquals(Set.of(px(0, 0, 0, 16, 13, 16)), boxes(shape("minecraft:end_portal_frame[eye=false,facing=north]"))); assertTrue(shape("minecraft:end_portal_frame[eye=true,facing=north]").boxCount() == 2);
        assertEquals(1.0, shape("minecraft:anvil[facing=north]").bounds().maxY()); assertTrue(shape("minecraft:anvil[facing=north]").boxCount() >= 3);
        assertEquals(Set.of(px(0, 0, 0, 16, 2, 16)), boxes(shape("minecraft:repeater[delay=1,facing=north,locked=false,powered=false]")));
        assertEquals(Set.of(px(1, 0, 1, 15, 8, 15)), boxes(shape("minecraft:cake[bites=0]"))); assertEquals(Set.of(px(13, 0, 1, 15, 8, 15)), boxes(shape("minecraft:cake[bites=6]")));
        assertEquals(1.0, shape("minecraft:composter[level=0]").bounds().maxY()); assertFalse(covers(shape("minecraft:composter[level=0]"), 0.5, 0.5, 0.5), "composter is hollow"); assertEquals(boxes(shape("minecraft:composter[level=0]")), boxes(shape("minecraft:composter[level=8]")), "the fill level is visual only: the collision shape does not change");
        assertTrue(shape("minecraft:lily_pad").bounds().maxY() <= 0.125, "lily pad is a thin plate");
    }
    @Test void scaffoldingIsExactWithContextAndAnEnvelopeWithout() {
        var e = r.plan(SCAFFOLDING); assertEquals(BlockShapeTable.Kind.CONTEXT, e.kind()); assertTrue(e.large());
        var above = r.resolve(e, 10, 64, 10, ShapeContext.of(65.0, false, 0, false)); assertEquals(CellStatus.EXACT_CONTEXT, above.status()); assertTrue(above.lower().boxCount() >= 5, "stable: top plate + four legs"); assertEquals(1.0, above.lower().bounds().maxY()); assertTrue(covers(above.lower(), 0.5, 0.95, 0.5), "top plate"); assertFalse(covers(above.lower(), 0.5, 0.5, 0.5), "hollow between the legs"); assertTrue(covers(above.lower(), 0.05, 0.5, 0.05), "leg");
        var descending = r.resolve(e, 10, 64, 10, ShapeContext.of(65.0, true, 0, false)); assertTrue(descending.lower().isEmpty(), "sneaking down through scaffolding (distance 0, not bottom)");
        var inside = r.resolve(e, 10, 64, 10, ShapeContext.of(64.5, false, 0, false)); assertTrue(inside.lower().isEmpty());
        var bottomUnstable = r.resolve(r.plan(BlockStateKey.parse("minecraft:scaffolding[bottom=true,distance=3,waterlogged=false]")), 0, 0, 0, ShapeContext.of(0.5, false, 0, false));
        assertEquals(Set.of(px(0, 0, 0, 16, 2, 16)), boxes(bottomUnstable.lower()), "unstable bottom scaffolding: 2/16 floor");
        var env = r.resolve(e, 10, 64, 10, ShapeContext.UNKNOWN); assertEquals(CellStatus.CONTEXT_REQUIRED, env.status()); assertTrue(env.lower().isEmpty()); assertFalse(env.upper().isEmpty()); assertFalse(env.exact());
        assertTrue(metrics.contextRequired.sum() > 0);
    }
    @Test void powderSnowRequiresEntityAttributes() {
        var e = r.plan(POWDER_SNOW); assertEquals(BlockShapeTable.Kind.ENTITY_CONTEXT, e.kind());
        var env = r.resolve(e, 0, 70, 0, ShapeContext.UNKNOWN); assertEquals(CellStatus.CONTEXT_REQUIRED, env.status()); assertTrue(env.lower().isEmpty()); assertTrue(env.upper().isFullCube());
        assertTrue(r.resolve(e, 0, 70, 0, ShapeContext.body(71.0)).lower().isEmpty(), "no boots: sinks in");
        assertTrue(r.resolve(e, 0, 70, 0, ShapeContext.of(71.0, false, 0, true)).lower().isFullCube(), "leather boots, above, not sneaking: walks on it");
        assertTrue(r.resolve(e, 0, 70, 0, ShapeContext.of(71.0, true, 0, true)).lower().isEmpty(), "sneaking sinks even with boots");
        assertEquals(0.8999999761581421, r.resolve(e, 0, 70, 0, ShapeContext.of(71.0, false, 2.6, true)).lower().bounds().maxY(), "falling more than 2.5 blocks: the falling shape regardless of boots");
        assertEquals(CellStatus.EXACT_CONTEXT, r.resolve(e, 0, 70, 0, ShapeContext.of(71.0, false, 2.6, true)).status());
    }
    @Test void offsetBlocksArePositionDerivedAndDeterministic() {
        var e = r.plan(BAMBOO); assertEquals(BlockShapeTable.Kind.OFFSET_XZ, e.kind()); assertTrue(e.large()); assertFalse(e.constant());
        ResolvedShape a = r.resolve(e, 0, 64, 0, ShapeContext.UNKNOWN), b = r.resolve(e, 1, 64, 0, ShapeContext.UNKNOWN), a2 = r.resolve(e, 0, 200, 0, ShapeContext.UNKNOWN);
        assertEquals(CellStatus.EXACT_OFFSET, a.status()); assertNotEquals(a.lower(), b.lower(), "different columns, different offsets"); assertEquals(a.lower(), a2.lower(), "the offset depends on x and z only");
        assertEquals(Set.of(List.of(0.15625, 0.0, 0.15625, 0.34375, 1.0, 0.34375)), boxes(a.lower()), "seed(0,0) offsets the 3/16 stalk by -0.25 on both axes");
        var drip = r.resolve(r.plan(BlockStateKey.parse("minecraft:pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]")), 5, 3, -7, ShapeContext.UNKNOWN);
        assertTrue(Math.abs(drip.lower().bounds().minX() + drip.lower().bounds().maxX() - 1.0) <= 0.25 + 1e-9, "dripstone offset is clamped to 0.125");
        assertEquals(r.resolve(e, 123456, 1, -654321, ShapeContext.UNKNOWN), r.resolve(e, 123456, 99, -654321, ShapeContext.UNKNOWN));
    }
    @Test void blockEntityDrivenShapesAreEnvelopesNeverExact() {
        var shulker = r.resolve(r.plan(SHULKER), 0, 0, 0, ShapeContext.body(1.0)); assertEquals(CellStatus.DYNAMIC_BLOCK_ENTITY, shulker.status());
        assertTrue(shulker.lower().isFullCube(), "the closed body is always there"); assertEquals(1.5, shulker.upper().bounds().maxY(), "the lid may be open by up to 0.5 toward facing=up");
        var west = r.resolve(r.plan(BlockStateKey.parse("minecraft:red_shulker_box[facing=west]")), 0, 0, 0, ShapeContext.UNKNOWN); assertEquals(-0.5, west.upper().bounds().minX());
        var piston = r.resolve(r.plan(MOVING_PISTON), 0, 0, 0, ShapeContext.UNKNOWN); assertEquals(CellStatus.DYNAMIC_BLOCK_ENTITY, piston.status());
        assertTrue(piston.lower().isEmpty(), "nothing is certainly present while a block is in motion"); assertEquals(-1.0, piston.upper().bounds().minX()); assertEquals(2.0, piston.upper().bounds().maxX()); assertEquals(0.0, piston.upper().bounds().minY());
        assertTrue(r.plan(MOVING_PISTON).movingPiston()); assertFalse(r.plan(SHULKER).movingPiston()); assertTrue(r.plan(SHULKER).large());
        assertTrue(metrics.dynamicCells.sum() >= 3);
    }
    @Test void unsupportedAndMalformedStatesNeverBecomeAirOrSolidAndNeverThrow() {
        for (var key : List.of(BOGUS, MALFORMED, BlockStateKey.parse("minecraft:stone[level=3]"), BlockStateKey.of("minecraft:oak_slab"), BlockStateKey.of("papea2ac:unresolved"))) {
            var e = r.plan(key); assertFalse(e.supported(), key.canonical()); assertTrue(e.large(), "an unknown shape may reach anywhere: treated as large for the broad phase");
            var res = r.resolve(e, 1, 2, 3, ShapeContext.body(2)); assertEquals(CellStatus.UNSUPPORTED_BLOCK_STATE, res.status()); assertTrue(res.lower().isEmpty()); assertTrue(res.upper().isEmpty(), "no geometry is invented; the status carries the uncertainty");
        }
        assertTrue(metrics.unsupportedStates.sum() >= 5);
    }
    @Test void perIdCacheIsDeterministicSharedAndBounded() {
        int stone = dict.intern(BlockStateKey.of("minecraft:stone")), slab = dict.intern(SLAB_TOP), bogus = dict.intern(BOGUS);
        long misses = metrics.shapeCacheMisses.sum();
        BlockShapeResolver.Entry e1 = r.entry(stone), e2 = r.entry(stone); assertSame(e1, e2, "one immutable plan per run-local id"); assertSame(CollisionShape.FULL_CUBE, e1.resting());
        assertSame(r.entry(slab), r.entry(slab)); assertNotSame(r.entry(slab), e1);
        assertFalse(r.entry(bogus).supported()); assertSame(r.entry(bogus), r.entry(bogus), "UNSUPPORTED is memoized (deterministic), not recomputed");
        assertEquals(misses + 3, metrics.shapeCacheMisses.sum()); assertTrue(metrics.shapeCacheHits.sum() >= 3);
        assertFalse(r.entry(-1).supported()); assertFalse(r.entry(BlockStateDictionary.CAPACITY).supported()); assertFalse(r.entry(60000).supported(), "an id the dictionary never issued");
        assertEquals(r.plan(SLAB_TOP).resting(), new BlockShapeResolver(PROFILE, new BlockStateDictionary(), new CollisionMetrics()).plan(SLAB_TOP).resting(), "two resolvers over the same profile agree");
    }
}
