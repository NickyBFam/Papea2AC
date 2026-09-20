package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Phase 4 JMH kernels (task §46/§47; same methodology as the Phase 1-3 benchmarks: JMH 1.37, 3 forks, 5x1s warmup,
 * 10x1s measurement, -prof gc). Descriptive anomaly detection for the collision hot paths, never a capacity claim.
 * Worlds are Phase 3 observer worlds fed through the same apply() path production uses; queries are player-sized
 * boxes unless stated. The pathological region is a 12x12x12 block of cauldrons (15 boxes each) - the densest static
 * shape of the profile - queried at the full 4096-cell budget.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class CollisionBenchmarks {
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt"),
        STAIRS = BlockStateKey.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]"), FENCE = BlockStateKey.parse("minecraft:oak_fence[east=true,north=true,south=true,waterlogged=false,west=true]"),
        WALL = BlockStateKey.parse("minecraft:cobblestone_wall[east=low,north=tall,south=low,up=true,waterlogged=false,west=none]"), CAULDRON = BlockStateKey.of("minecraft:cauldron"),
        SLAB = BlockStateKey.parse("minecraft:oak_slab[type=top,waterlogged=false]"), SCAFFOLDING = BlockStateKey.parse("minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]"), BAMBOO = BlockStateKey.parse("minecraft:bamboo[age=0,leaves=none,stage=0]");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    private static final class W { final ObserverWorld world; long seq; W(ObserverWorld w) { world = w; } void apply(WorldObservation o) { world.apply(world.key(), o, ++seq, seq, null); } void set(int x, int y, int z, BlockStateKey k) { apply(new WorldObservation.BlockChanged(new BlockPos(x, y, z), k)); } }
    private BlockStateDictionary dictionary; private WorldBudget budget; private CollisionEngine engine; private BlockShapeResolver resolver;
    private W flat, dense, pathological, historical; private long historicalSequence;
    private int stoneId, stairsId, fenceId, bambooId, scaffoldId;
    private Aabb standing, standingOnSlab, multiSection, bodyOverFences, pathologicalBox, noHitAir, noHitStone;
    private ShapeContext body;
    private CollisionResult manyHitResult;
    private WorldView flatView, denseView, pathologicalView;

    static WorldObservation.SectionContent uniform(BlockStateKey s) { return new WorldObservation.SectionContent(s.equals(AIR) ? 0 : 4096, List.of(s), 0, new long[0]); }
    static WorldObservation.SectionContent surface() {
        var palette = List.of(STONE, DIRT, AIR); int bits = 4, perLong = 16; long[] packed = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int y = i >> 8; int slot = y < 4 ? 0 : y < 6 ? 1 : 2; if (slot != 2) nonEmpty++; packed[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static WorldObservation.ChunkBaselineObserved chunk(int x, int z) {
        var sections = new ArrayList<WorldObservation.SectionContent>(24);
        for (int i = 0; i < 24; i++) sections.add(i < 8 ? uniform(STONE) : i == 8 ? surface() : uniform(AIR));
        return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, "");
    }
    private W fresh(int epoch) { var w = new W(new ObserverWorld(new SessionKey(new UUID(4, epoch), epoch), budget, dictionary)); w.apply(OVERWORLD); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.apply(chunk(cx, cz)); return w; }

    @Setup public void setup() {
        dictionary = new BlockStateDictionary(); budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, WorldBudget.PRIVATE_TARGET_BYTES);
        engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dictionary); resolver = engine.resolver();
        stoneId = dictionary.intern(STONE); stairsId = dictionary.intern(STAIRS); fenceId = dictionary.intern(FENCE); bambooId = dictionary.intern(BAMBOO); scaffoldId = dictionary.intern(SCAFFOLDING);
        flat = fresh(1);
        dense = fresh(2); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) dense.set(x, 70, z, (x + z) % 3 == 0 ? FENCE : (x + z) % 3 == 1 ? STAIRS : WALL);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) if ((x * 7 + z) % 5 == 0) dense.set(x, 71, z, SLAB);
        pathological = fresh(3); for (int x = 1; x <= 12; x++) for (int y = 71; y <= 82; y++) for (int z = 1; z <= 12; z++) pathological.set(x, y, z, CAULDRON);
        historical = fresh(4); for (int i = 0; i < 600; i++) { historical.set(i % 16, 71 + (i / 16) % 3, (i / 48) % 16, i % 2 == 0 ? STONE : AIR); if (i == 300) historicalSequence = historical.seq; }
        standing = PlayerBox.of(CollisionProfile.PAPER_26_2, PlayerBox.Pose.STANDING, 4.5, 70, 4.5);
        standingOnSlab = PlayerBox.of(CollisionProfile.PAPER_26_2, PlayerBox.Pose.STANDING, 4.5, 70.9, 4.5);
        multiSection = PlayerBox.of(CollisionProfile.PAPER_26_2, PlayerBox.Pose.STANDING, 15.7, 79.2, 15.7); // spans two chunks in x/z and two sections in y
        bodyOverFences = PlayerBox.of(CollisionProfile.PAPER_26_2, PlayerBox.Pose.STANDING, 4.5, 70.5, 4.5);
        pathologicalBox = new Aabb(0, 70, 0, 12.5, 82.5, 12.5); // 16x16x16 padded = 4096 cells, ~1700 cauldrons
        noHitAir = PlayerBox.of(CollisionProfile.PAPER_26_2, PlayerBox.Pose.STANDING, 4.5, 100, 4.5);
        noHitStone = new Aabb(4.2, 40, 4.2, 4.8, 41.8, 4.8);
        body = ShapeContext.body(70.5);
        flatView = flat.world.currentView(); denseView = dense.world.currentView(); pathologicalView = pathological.world.currentView();
        manyHitResult = engine.query(pathologicalView, new Aabb(1, 71, 1, 4, 74, 4), body, 0);
    }
    // ---- shape lookup
    @Benchmark public BlockShapeResolver.Entry fullCubeLookup() { return resolver.entry(stoneId); }
    @Benchmark public ResolvedShape complexStateShapeLookup() { return resolver.resolve(resolver.entry(stairsId), 3, 70, 3, body); }
    @Benchmark public ResolvedShape contextDependentShapeResolution() { return resolver.resolve(resolver.entry(scaffoldId), 3, 70, 3, body); }
    @Benchmark public ResolvedShape positionOffsetShapeResolution() { return resolver.resolve(resolver.entry(bambooId), 12345, 70, -678, body); }
    // ---- queries
    @Benchmark public CollisionResult singleSectionBroadPhaseStandingClear() { return engine.query(flatView, standing, body, 0); }
    @Benchmark public CollisionResult multiSectionMultiChunkBroadPhase() { return engine.query(flatView, multiSection, body, 0); }
    @Benchmark public CollisionResult noHitQueryInAir() { return engine.query(flatView, noHitAir, body, 0); }
    @Benchmark public CollisionResult fullyInsideStone() { return engine.query(flatView, noHitStone, body, 0); }
    @Benchmark public CollisionResult manyHitQueryDenseNonFullShapes() { return engine.query(denseView, bodyOverFences, body, 0); }
    @Benchmark public CollisionResult standingOnSlabAmongStairsAndWalls() { return engine.query(denseView, standingOnSlab, body, 0); }
    @Benchmark public CollisionResult pathologicalFullBudgetCauldronRegion() { return engine.query(pathologicalView, pathologicalBox, body, 0); }
    @Benchmark public CollisionResult historicalQueryIncludingViewConstruction() { return engine.query(historical.world.viewAtSequence(historicalSequence), standing, body, 0); }
    @Benchmark public WorldView historicalViewConstructionOnly() { return historical.world.viewAtSequence(historicalSequence); }
    @Benchmark public CollisionResult unknownRegionQuery() { return engine.query(flatView, new Aabb(500.2, 70, 500.2, 500.8, 71.8, 500.8), body, 0); }
    // ---- clip primitive
    @Benchmark public double clipYAgainstManyHits() { return AxisClip.clip(AxisClip.Axis.Y, new Aabb(1.2, 73.0, 1.2, 1.8, 74.8, 1.8), -0.5, manyHitResult); }
    @Benchmark public void candidateAcceptanceVoxelJoin(Blackhole bh) { var s = resolver.entry(fenceId).resting(); bh.consume(CollisionEngine.intersects(s, 4, 70, 4, bodyOverFences)); }
}
