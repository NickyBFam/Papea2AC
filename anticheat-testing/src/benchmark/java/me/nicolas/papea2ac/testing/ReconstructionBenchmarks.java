package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.ReconstructionRequest.SearchBudget;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Phase 6 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc): one full reconstruction request per
 * family over a Phase 3 observer world - the envelope prefilter, the physics evaluations it lets through, node merging and
 * result materialization all included. Descriptive, not a capacity claim. "Off" variants run the concrete lane alone
 * (every raw candidate simulated); the breakdown kernels time the enumeration, one envelope preparation/application
 * and one observation comparison in isolation.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class ReconstructionBenchmarks {
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt"), SLAB = BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=false]"),
        STAIRS = BlockStateKey.parse("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"), ICE = BlockStateKey.of("minecraft:ice"), FENCE = BlockStateKey.parse("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]"), HONEY = BlockStateKey.of("minecraft:honey_block");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    private static final class W { final ObserverWorld world; long seq; W(ObserverWorld w) { world = w; } void apply(WorldObservation o) { world.apply(world.key(), o, ++seq, seq, null); } void set(int x, int y, int z, BlockStateKey k) { apply(new WorldObservation.BlockChanged(new BlockPos(x, y, z), k)); } }
    private BlockStateDictionary dictionary; private WorldBudget budget; private CollisionEngine engine; private BlockBehaviorQuery behaviors; private PhysicsSimulator simulator; private InputReconstructor reconstructor;
    private PhysicsEnvironment flat, wall, step, stairs, ice, course;
    private ReconstructionRequest idleUnique, idleAmbiguous, walk, strafe, diagonal, sprint, jumpReq, airborneReq, wallReq, stepReq, stairsReq, iceReq, raw32, raw64, fullDomain, multi2, multi3, multi3Gap, empty, worst, walkOff, fullDomainOff;
    private AbstractTransfer transfer; private AbstractState.Box restBox; private AbstractTransfer.Prepared preparedRest; private List<MovementInput> allInputs; private TickObservation walkObs; private PhysicsState resting;
    private MovementInput w;

    static WorldObservation.SectionContent uniform(BlockStateKey s) { return new WorldObservation.SectionContent(s.equals(AIR) ? 0 : 4096, List.of(s), 0, new long[0]); }
    static WorldObservation.SectionContent surface() {
        var palette = List.of(STONE, DIRT, AIR); int bits = 4, perLong = 16; long[] packed = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int y = i >> 8; int slot = y < 4 ? 0 : y < 6 ? 1 : 2; if (slot != 2) nonEmpty++; packed[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static WorldObservation.ChunkBaselineObserved chunk(int x, int z) { var sections = new ArrayList<WorldObservation.SectionContent>(24); for (int i = 0; i < 24; i++) sections.add(i < 8 ? uniform(STONE) : i == 8 ? surface() : uniform(AIR)); return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, ""); }
    private W fresh(int epoch) { var w = new W(new ObserverWorld(new SessionKey(new UUID(6, epoch), epoch), budget, dictionary)); w.apply(OVERWORLD); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.apply(chunk(cx, cz)); return w; }
    private PhysicsEnvironment env(W w) { return PhysicsEnvironment.of(w.world.currentView(), w.world.worldEpoch(), engine, behaviors, MovementProfile.paper26_2()); }
    private PhysicsState settle(PhysicsEnvironment e, double x, double y, double z) { PhysicsState s = PhysicsState.resting(x, y, z, PlayerBox.Pose.STANDING, true); for (int i = 0; i < 2; i++) s = simulator.simulate(s, MovementInput.idle(0f), e).next(); return s; }
    private PhysicsState run(PhysicsEnvironment e, PhysicsState s, MovementInput in, int n) { for (int i = 0; i < n; i++) s = simulator.simulate(s, in, e).next(); return s; }
    private static TickObservation obs(PhysicsState next, float yaw, long seq) { return TickObservation.exact(next.position().x(), next.position().y(), next.position().z(), yaw, next.onGround() ? TickObservation.GroundClaim.TRUE : TickObservation.GroundClaim.FALSE, seq); }
    private ReconstructionRequest single(PhysicsEnvironment e, PhysicsState s, MovementInput in) { PhysicsResult r = simulator.simulate(s, in, e); if (!r.exact()) throw new IllegalStateException(r.summary()); return ReconstructionRequest.of(s, obs(r.next(), in.yaw(), 1), e); }
    private ReconstructionRequest chain(PhysicsEnvironment e, PhysicsState s, List<MovementInput> ins, boolean gapMiddle) {
        List<TickObservation> o = new ArrayList<>(); PhysicsState cur = s;
        for (int i = 0; i < ins.size(); i++) { PhysicsResult r = simulator.simulate(cur, ins.get(i), e); if (!r.exact()) throw new IllegalStateException(r.summary()); cur = r.next(); o.add(gapMiddle && i < ins.size() - 1 ? TickObservation.unobserved(ins.get(i).yaw(), i) : obs(cur, ins.get(i).yaw(), i)); }
        return ReconstructionRequest.of(s, o, e);
    }
    @Setup public void setup() {
        dictionary = new BlockStateDictionary(); budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, WorldBudget.PRIVATE_TARGET_BYTES);
        engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dictionary); behaviors = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2.table(), dictionary); simulator = new PhysicsSimulator(MovementProfile.paper26_2()); reconstructor = new InputReconstructor(simulator);
        w = MovementInput.forward(0f, false); MovementInput idle = MovementInput.idle(0f), wSprint = MovementInput.forward(0f, true), jump = new MovementInput(0, 0, true, false, false, false, 0f), d = new MovementInput(0, -1, false, false, false, false, 0f), wa = new MovementInput(1, 1, false, false, false, false, 0f);
        W f = fresh(1); flat = env(f); resting = settle(flat, 4.5, 70, 4.5); PhysicsState walking = run(flat, resting, w, 10), air = run(flat, resting, jump, 3);
        idleUnique = single(flat, resting, idle).withConstraints(InputDomain.Constraints.NONE.withShift(false).withSprinting(false).withUsingItem(false)); idleAmbiguous = single(flat, resting, idle);
        walk = single(flat, walking, w); walkOff = walk.withEnvelopePruning(false); strafe = single(flat, resting, d); diagonal = single(flat, resting, wa); sprint = single(flat, run(flat, resting, wSprint, 10), wSprint); jumpReq = single(flat, resting, jump); airborneReq = single(flat, air, w);
        W wl = fresh(2); for (int y = 70; y <= 72; y++) for (int x = 3; x <= 6; x++) wl.set(x, y, 6, STONE); wall = env(wl); wallReq = single(wall, run(wall, settle(wall, 4.5, 70, 4.5), w, 12), w);
        W st = fresh(3); st.set(4, 70, 6, SLAB); st.set(4, 70, 7, SLAB); step = env(st); stepReq = single(step, run(step, settle(step, 4.5, 70, 4.5), w, 6), w);
        W sr = fresh(4); sr.set(4, 70, 6, STAIRS); sr.set(4, 70, 7, STONE); sr.set(4, 70, 8, SLAB); stairs = env(sr); stairsReq = single(stairs, run(stairs, settle(stairs, 4.5, 70, 4.5), w, 6), w);
        W ic = fresh(5); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) ic.set(x, 69, z, ICE); ice = env(ic); iceReq = single(ice, run(ice, settle(ice, 4.5, 70, 4.5), w, 10), w);
        // the raw domain sizes are products of 3,3,2,2,2,2: the closest constrained sets to 32 and 64 raw candidates are 36 (shift and item use known) and 72 (item use known)
        raw32 = single(flat, walking, w).withConstraints(new InputDomain.Constraints(InputDomain.Constraints.UNKNOWN, InputDomain.Constraints.UNKNOWN, InputDomain.Constraints.UNKNOWN, 0, InputDomain.Constraints.UNKNOWN, 0));
        raw64 = single(flat, walking, w).withConstraints(new InputDomain.Constraints(InputDomain.Constraints.UNKNOWN, InputDomain.Constraints.UNKNOWN, InputDomain.Constraints.UNKNOWN, InputDomain.Constraints.UNKNOWN, InputDomain.Constraints.UNKNOWN, 0));
        fullDomain = single(flat, walking, w); fullDomainOff = fullDomain.withEnvelopePruning(false);
        multi2 = chain(flat, resting, List.of(w, w), false); multi3 = chain(flat, resting, List.of(w, jump, wSprint), false); multi3Gap = chain(flat, resting, List.of(w, w, w), true);
        empty = ReconstructionRequest.of(resting, TickObservation.exact(5.5, 70, 4.5, 0f, TickObservation.GroundClaim.TRUE, 1), flat);
        W co = fresh(10); Random r = new Random(1); BlockStateKey[] palette = { SLAB, STAIRS, FENCE, ICE, HONEY, STONE };
        for (int i = 0; i < 120; i++) co.set(r.nextInt(16), 70 + r.nextInt(3), r.nextInt(16), palette[r.nextInt(palette.length)]); course = env(co);
        PhysicsState cs = PhysicsState.resting(8.5, 73, 8.5, PlayerBox.Pose.STANDING, false).withVelocity(new Vec3d(0.9, -3.0, 0.9));
        worst = ReconstructionRequest.of(cs, List.of(TickObservation.unobserved(0f, 0), TickObservation.unobserved(0f, 1), TickObservation.exact(9.5, 70, 9.5, 0f, TickObservation.GroundClaim.TRUE, 2)), course).withBudget(SearchBudget.DEFAULT);
        transfer = new AbstractTransfer(simulator, flat, me.nicolas.papea2ac.version.MovementObservationProfile.PAPER_26_2, 4096); restBox = AbstractState.fromConcrete(resting).boxes().get(0); preparedRest = transfer.prepare(restBox, List.of()); allInputs = InputDomain.enumerate(InputDomain.Constraints.NONE, 0f); walkObs = walk.ticks().get(0);
    }

    // ---- full requests
    @Benchmark public ReconstructionResult idleUnique() { return reconstructor.reconstruct(idleUnique); }
    @Benchmark public ReconstructionResult idleAmbiguous() { return reconstructor.reconstruct(idleAmbiguous); }
    @Benchmark public ReconstructionResult walkW() { return reconstructor.reconstruct(walk); }
    @Benchmark public ReconstructionResult strafeD() { return reconstructor.reconstruct(strafe); }
    @Benchmark public ReconstructionResult diagonalWA() { return reconstructor.reconstruct(diagonal); }
    @Benchmark public ReconstructionResult sprint() { return reconstructor.reconstruct(sprint); }
    @Benchmark public ReconstructionResult jump() { return reconstructor.reconstruct(jumpReq); }
    @Benchmark public ReconstructionResult airborneAmbiguity() { return reconstructor.reconstruct(airborneReq); }
    @Benchmark public ReconstructionResult wallCollision() { return reconstructor.reconstruct(wallReq); }
    @Benchmark public ReconstructionResult stepUpSlab() { return reconstructor.reconstruct(stepReq); }
    @Benchmark public ReconstructionResult stairs() { return reconstructor.reconstruct(stairsReq); }
    @Benchmark public ReconstructionResult iceWalk() { return reconstructor.reconstruct(iceReq); }
    @Benchmark public ReconstructionResult rawCandidates36() { return reconstructor.reconstruct(raw32); }
    @Benchmark public ReconstructionResult rawCandidates72() { return reconstructor.reconstruct(raw64); }
    @Benchmark public ReconstructionResult fullDomain144() { return reconstructor.reconstruct(fullDomain); }
    @Benchmark public ReconstructionResult fullDomain144ConcreteOnly() { return reconstructor.reconstruct(fullDomainOff); }
    @Benchmark public ReconstructionResult walkWConcreteOnly() { return reconstructor.reconstruct(walkOff); }
    @Benchmark public ReconstructionResult multiTick2() { return reconstructor.reconstruct(multi2); }
    @Benchmark public ReconstructionResult multiTick3() { return reconstructor.reconstruct(multi3); }
    @Benchmark public ReconstructionResult multiTick3UnobservedMiddle() { return reconstructor.reconstruct(multi3Gap); }
    @Benchmark public ReconstructionResult emptyCandidateSet() { return reconstructor.reconstruct(empty); }
    /** Worst bounded search in this catalog: a fast airborne start over a cluttered course (envelope TOP, concrete lane only) with two unobserved ticks - runs into the solver caps and says so. */
    @Benchmark public ReconstructionResult worstBoundedSearch() { return reconstructor.reconstruct(worst); }
    // ---- breakdown kernels
    @Benchmark public List<MovementInput> enumerate144() { return InputDomain.enumerate(InputDomain.Constraints.NONE, 0f); }
    @Benchmark public AbstractTransfer.Prepared envelopePrepare() { return transfer.prepare(restBox, List.of()); }
    @Benchmark public List<AbstractState.Box> envelopeApplyOne() { return transfer.apply(preparedRest, w); }
    @Benchmark public AbstractState envelopeStepAllInputs() { return transfer.step(AbstractState.of(restBox), allInputs, List.of()); }
    @Benchmark public boolean observationCompare() { return walkObs.position().contains(resting.position(), me.nicolas.papea2ac.version.MovementObservationProfile.PAPER_26_2); }
    @Benchmark public PhysicsResult onePhysicsStep() { return simulator.simulate(resting, w, flat); }
}
