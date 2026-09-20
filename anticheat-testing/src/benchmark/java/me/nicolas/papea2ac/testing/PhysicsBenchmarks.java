package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Phase 5 JMH kernels (same methodology as Phases 1-4: JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc).
 * Descriptive anomaly detection for one forward step per movement family, never a capacity claim. Worlds are Phase 3
 * observer worlds fed through the production apply() path; every step is a real {@code PhysicsSimulator.simulate} over
 * a known environment (the numbers therefore include the Phase 4 collision queries the step issues). "candidateScaling"
 * runs N independent explicit inputs from one state - the shape of Phase 6's witness expansion - to show that the cost
 * is linear in the candidate count (the simulator itself never enumerates).
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class PhysicsBenchmarks {
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt"), ICE = BlockStateKey.of("minecraft:ice"), SOUL_SAND = BlockStateKey.of("minecraft:soul_sand"),
        HONEY = BlockStateKey.of("minecraft:honey_block"), WATER = BlockStateKey.parse("minecraft:water[level=0]"), COBWEB = BlockStateKey.of("minecraft:cobweb"), SLAB = BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=false]"),
        STAIRS = BlockStateKey.parse("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"), FENCE = BlockStateKey.parse("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]"), LADDER = BlockStateKey.parse("minecraft:ladder[facing=north,waterlogged=false]");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    private static final class W { final ObserverWorld world; long seq; W(ObserverWorld w) { world = w; } void apply(WorldObservation o) { world.apply(world.key(), o, ++seq, seq, null); } void set(int x, int y, int z, BlockStateKey k) { apply(new WorldObservation.BlockChanged(new BlockPos(x, y, z), k)); } }
    private BlockStateDictionary dictionary; private WorldBudget budget; private CollisionEngine engine; private BlockBehaviorQuery behaviors; private PhysicsSimulator simulator;
    private PhysicsEnvironment flat, wall, step, stairs, ice, soul, water, web, ladder, course;
    private PhysicsState resting, walking, sprinting, airborne, atWall, atStep, onIce, onSoul, inWater, atWeb, onLadder, courseStart;
    private MovementInput idle, w, wSprint, jump, wJump;
    private MovementInput[] candidates64;

    static WorldObservation.SectionContent uniform(BlockStateKey s) { return new WorldObservation.SectionContent(s.equals(AIR) ? 0 : 4096, List.of(s), 0, new long[0]); }
    static WorldObservation.SectionContent surface() {
        var palette = List.of(STONE, DIRT, AIR); int bits = 4, perLong = 16; long[] packed = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int y = i >> 8; int slot = y < 4 ? 0 : y < 6 ? 1 : 2; if (slot != 2) nonEmpty++; packed[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static WorldObservation.ChunkBaselineObserved chunk(int x, int z) { var sections = new ArrayList<WorldObservation.SectionContent>(24); for (int i = 0; i < 24; i++) sections.add(i < 8 ? uniform(STONE) : i == 8 ? surface() : uniform(AIR)); return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, ""); }
    private W fresh(int epoch) { var w = new W(new ObserverWorld(new SessionKey(new UUID(5, epoch), epoch), budget, dictionary)); w.apply(OVERWORLD); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.apply(chunk(cx, cz)); return w; }
    private PhysicsEnvironment env(W w) { return PhysicsEnvironment.of(w.world.currentView(), w.world.worldEpoch(), engine, behaviors, MovementProfile.paper26_2()); }
    private PhysicsState settle(PhysicsEnvironment e, double x, double y, double z) { PhysicsState s = PhysicsState.resting(x, y, z, PlayerBox.Pose.STANDING, true); for (int i = 0; i < 2; i++) s = simulator.simulate(s, idle, e).next(); return s; }
    private PhysicsState run(PhysicsEnvironment e, PhysicsState s, MovementInput in, int n) { for (int i = 0; i < n; i++) s = simulator.simulate(s, in, e).next(); return s; }

    @Setup public void setup() {
        dictionary = new BlockStateDictionary(); budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, WorldBudget.PRIVATE_TARGET_BYTES);
        engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dictionary); behaviors = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2.table(), dictionary); simulator = new PhysicsSimulator(MovementProfile.paper26_2());
        idle = MovementInput.idle(0f); w = MovementInput.forward(0f, false); wSprint = MovementInput.forward(0f, true); jump = new MovementInput(0, 0, true, false, false, false, 0f); wJump = new MovementInput(1, 0, true, false, false, false, 0f);
        W f = fresh(1); flat = env(f); resting = settle(flat, 4.5, 70, 4.5); walking = run(flat, resting, w, 20); sprinting = run(flat, resting, wSprint, 20); airborne = run(flat, resting, jump, 3);
        W wl = fresh(2); for (int y = 70; y <= 72; y++) for (int x = 3; x <= 6; x++) wl.set(x, y, 6, STONE); wall = env(wl); atWall = run(wall, settle(wall, 4.5, 70, 4.5), w, 12);
        W st = fresh(3); st.set(4, 70, 6, SLAB); st.set(4, 70, 7, SLAB); step = env(st); atStep = run(step, settle(step, 4.5, 70, 4.5), w, 6);
        W sr = fresh(4); sr.set(4, 70, 6, STAIRS); sr.set(4, 70, 7, STONE); sr.set(4, 70, 8, SLAB); stairs = env(sr);
        W ic = fresh(5); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) ic.set(x, 69, z, ICE); ice = env(ic); onIce = run(ice, settle(ice, 4.5, 70, 4.5), w, 10);
        W so = fresh(6); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) so.set(x, 69, z, SOUL_SAND); soul = env(so); onSoul = run(soul, settle(soul, 4.5, 69.875, 4.5), w, 5);
        W wa = fresh(7); for (int x = 3; x <= 6; x++) for (int z = 3; z <= 6; z++) { wa.set(x, 69, z, WATER); wa.set(x, 70, z, WATER); } water = env(wa); inWater = PhysicsState.resting(4.5, 70, 4.5, PlayerBox.Pose.STANDING, false);
        W we = fresh(8); we.set(4, 70, 4, COBWEB); we.set(4, 71, 4, COBWEB); web = env(we); atWeb = settle(flat, 4.5, 70, 4.5);
        W la = fresh(9); for (int y = 70; y <= 76; y++) la.set(4, y, 6, STONE); for (int y = 70; y <= 75; y++) la.set(4, y, 5, LADDER); ladder = env(la); onLadder = run(ladder, settle(ladder, 4.5, 70, 4.5), w, 8);
        W co = fresh(10); Random r = new Random(1); BlockStateKey[] palette = { SLAB, STAIRS, FENCE, ICE, HONEY, COBWEB, LADDER, STONE, SOUL_SAND };
        for (int i = 0; i < 120; i++) co.set(r.nextInt(16), 70 + r.nextInt(3), r.nextInt(16), palette[r.nextInt(palette.length)]); course = env(co); courseStart = PhysicsState.resting(8.5, 73, 8.5, PlayerBox.Pose.STANDING, false);
        candidates64 = new MovementInput[64]; for (int i = 0; i < 64; i++) candidates64[i] = new MovementInput((i % 3) - 1, ((i / 3) % 3) - 1, (i / 9) % 2 == 0, (i / 18) % 2 == 0, (i / 36) % 2 == 0, false, (i * 37) % 360);
    }

    @Benchmark public PhysicsResult idleOnGround() { return simulator.simulate(resting, idle, flat); }
    @Benchmark public PhysicsResult walkOnGround() { return simulator.simulate(walking, w, flat); }
    @Benchmark public PhysicsResult sprintOnGround() { return simulator.simulate(sprinting, wSprint, flat); }
    @Benchmark public PhysicsResult airborne() { return simulator.simulate(airborne, w, flat); }
    @Benchmark public PhysicsResult jumpFromGround() { return simulator.simulate(resting, jump, flat); }
    @Benchmark public PhysicsResult wallCollision() { return simulator.simulate(atWall, w, wall); }
    @Benchmark public PhysicsResult stepUpSlab() { return simulator.simulate(atStep, w, step); }
    @Benchmark public PhysicsResult stairsAndSlabCourse() { PhysicsState s = settle(stairs, 4.5, 70, 4.5); return simulator.simulate(run(stairs, s, w, 6), w, stairs); }
    @Benchmark public PhysicsResult iceWalk() { return simulator.simulate(onIce, w, ice); }
    @Benchmark public PhysicsResult soulSandWalk() { return simulator.simulate(onSoul, w, soul); }
    @Benchmark public PhysicsResult waterContactDeferred() { return simulator.simulate(inWater, w, water); }
    @Benchmark public PhysicsResult cobwebEntry() { return simulator.simulate(atWeb, w, web); }
    @Benchmark public PhysicsResult ladderClimb() { return simulator.simulate(onLadder, wJump, ladder); }
    @Benchmark public PhysicsResult externalVelocityApplied() { return simulator.simulate(resting, idle, flat.withExternalMotions(List.of(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, new Vec3d(0.8, 0.5, 0.3), "bench")))); }
    /** Worst bounded case in this catalog: a fast airborne move through a cluttered course (largest swept box, inside sampling of many cells). */
    @Benchmark public PhysicsResult worstBoundedCourseStep() { return simulator.simulate(courseStart.withVelocity(new Vec3d(0.9, -3.0, 0.9)), wJump, course); }
    @Benchmark public PhysicsState twentyTickChainedWalk() { return run(flat, resting, wSprint, 20); }
    @Benchmark public void candidateScaling1(Blackhole bh) { bh.consume(simulator.simulate(walking, candidates64[0], flat)); }
    @Benchmark public void candidateScaling8(Blackhole bh) { for (int i = 0; i < 8; i++) bh.consume(simulator.simulate(walking, candidates64[i], flat)); }
    @Benchmark public void candidateScaling32(Blackhole bh) { for (int i = 0; i < 32; i++) bh.consume(simulator.simulate(walking, candidates64[i], flat)); }
    @Benchmark public void candidateScaling64(Blackhole bh) { for (int i = 0; i < 64; i++) bh.consume(simulator.simulate(walking, candidates64[i], flat)); }
}
