package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Phase 4 test scaffolding: an observer world (Phase 3, owner thread = test thread) with the Phase 3 overworld baseline
 * chunk layout (stone below y=68, dirt 68..69, air from 70) plus a collision engine, and helpers to place observed
 * blocks through the SAME single ordered path the shard uses (BlockChanged observations with increasing sequences).
 * Nothing here reads a live server; every block is an observed clientbound fact.
 */
final class CollisionTestSupport {
    private CollisionTestSupport() {}
    static final CollisionProfile PROFILE = CollisionProfile.PAPER_26_2;
    static final BlockStateKey SLAB_BOTTOM = BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=false]"), SLAB_TOP = BlockStateKey.parse("minecraft:oak_slab[type=top,waterlogged=false]"),
        SLAB_DOUBLE = BlockStateKey.parse("minecraft:oak_slab[type=double,waterlogged=false]"), FENCE_POST = BlockStateKey.parse("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"),
        FENCE_NS = BlockStateKey.parse("minecraft:oak_fence[east=false,north=true,south=true,waterlogged=false,west=false]"), WALL_POST = BlockStateKey.parse("minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"),
        GATE_CLOSED = BlockStateKey.parse("minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]"), GATE_OPEN = BlockStateKey.parse("minecraft:oak_fence_gate[facing=north,in_wall=false,open=true,powered=false]"),
        DOOR_CLOSED = BlockStateKey.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"), DOOR_OPEN = BlockStateKey.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=true,powered=false]"),
        TRAPDOOR_CLOSED = BlockStateKey.parse("minecraft:oak_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]"), TRAPDOOR_OPEN = BlockStateKey.parse("minecraft:oak_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]"),
        SNOW_2 = BlockStateKey.parse("minecraft:snow[layers=2]"), CARPET = BlockStateKey.of("minecraft:white_carpet"), LADDER = BlockStateKey.parse("minecraft:ladder[facing=north,waterlogged=false]"),
        BED_HEAD = BlockStateKey.parse("minecraft:red_bed[facing=north,occupied=false,part=head]"), CHEST = BlockStateKey.parse("minecraft:chest[facing=north,type=single,waterlogged=false]"),
        HOPPER = BlockStateKey.parse("minecraft:hopper[enabled=true,facing=down]"), CAULDRON = BlockStateKey.of("minecraft:cauldron"), STAIRS_STRAIGHT = STAIRS,
        SCAFFOLDING = BlockStateKey.parse("minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]"), POWDER_SNOW = BlockStateKey.of("minecraft:powder_snow"),
        BAMBOO = BlockStateKey.parse("minecraft:bamboo[age=0,leaves=none,stage=0]"), SHULKER = BlockStateKey.parse("minecraft:shulker_box[facing=up]"), MOVING_PISTON = BlockStateKey.parse("minecraft:moving_piston[facing=east,type=normal]"),
        BOGUS = BlockStateKey.of("minecraft:not_a_block"), MALFORMED = BlockStateKey.parse("minecraft:oak_slab[type=sideways,waterlogged=false]");

    static final class Scene {
        final SessionKey key; final BlockStateDictionary dictionary = new BlockStateDictionary(); final WorldBudget budget; final ObserverWorld world; final CollisionEngine engine; long seq;
        Scene(long sessionEpoch, WorldBudget budget) {
            key = new SessionKey(new UUID(0x4C, sessionEpoch), sessionEpoch); this.budget = budget;
            world = new ObserverWorld(key, budget, dictionary); engine = new CollisionEngine(PROFILE, dictionary);
            apply(OVERWORLD);
        }
        Scene() { this(1, new WorldBudget()); }
        long apply(WorldObservation o) { long s = ++seq; if (!world.apply(key, o, s, s, null)) throw new IllegalStateException("apply refused at " + s); return s; }
        long baseline(int cx, int cz) { return apply(WorldTestSupport.baseline(cx, cz)); }
        long set(int x, int y, int z, BlockStateKey state) { return apply(new WorldObservation.BlockChanged(new BlockPos(x, y, z), state)); }
        long mark() { long s = ++seq; world.observeSequence(key, s, s); return s; }
        CollisionResult query(Aabb box) { return engine.query(world.currentView(), box, ShapeContext.UNKNOWN, world.worldEpoch()); }
        CollisionResult query(Aabb box, ShapeContext ctx) { return engine.query(world.currentView(), box, ctx, world.worldEpoch()); }
        CollisionResult queryAt(long sequence, Aabb box) { return engine.query(world.viewAtSequence(sequence), box, ShapeContext.UNKNOWN, world.worldEpoch()); }
    }
    /** Standing player box with feet at (x, y, z). */
    static Aabb standing(double x, double y, double z) { return PlayerBox.of(PROFILE, PlayerBox.Pose.STANDING, x, y, z); }
    /** Number of cells vanilla visits for a box: padded range minus the 8 corners. */
    static int expectedCells(Aabb b) {
        long x0 = (long) Math.floor(b.minX() - 1.0E-7) - 1, x1 = (long) Math.floor(b.maxX() + 1.0E-7) + 1, y0 = (long) Math.floor(b.minY() - 1.0E-7) - 1, y1 = (long) Math.floor(b.maxY() + 1.0E-7) + 1, z0 = (long) Math.floor(b.minZ() - 1.0E-7) - 1, z1 = (long) Math.floor(b.maxZ() + 1.0E-7) + 1;
        return (int) ((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)) - 8;
    }
    static List<Aabb> boxesAt(CollisionResult r, int x, int y, int z) { var out = new ArrayList<Aabb>(); for (var h : r.hits()) if (h.x() == x && h.y() == y && h.z() == z) for (int i = 0; i < h.shape().boxCount(); i++) out.add(h.worldBox(i)); return out; }
}
