package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.version.TimingProfile;
import me.nicolas.papea2ac.world.*;
/**
 * Phase 8 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc). Two families, reported separately (brief
 * section 64): VALIDATION-ONLY kernels ({@code classify} over a precomputed Phase 7 result: the classification rule, completeness
 * certificate, witnesses, proof, residual and result construction alone) and END-TO-END kernels (timing + Phase 6 + Phase 5 + the
 * classifier through {@code validate}): VALID simple / ambiguous / velocity, UNCERTAIN world-incomplete / search-limit, INVALID
 * horizontal / vertical excess, 2 and 4 timing branches, 2/3-tick gaps and the worst bounded enumeration. Descriptive kernels on
 * one host, never a capacity claim.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class ValidationBenchmarks {
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    static final long TICK = 50_000_000L;
    private static final class W { final ObserverWorld world; long seq; W(ObserverWorld w) { world = w; } void apply(WorldObservation o) { world.apply(world.key(), o, ++seq, seq, null); } }
    private PhysicsSimulator simulator; private TimingReconstruction timing; private MovementValidator validator; private PhysicsEnvironment flat, partial; private PhysicsState resting;
    private TimingReconstruction.Frontier frontier, ambiguous, two;
    private MovementValidationRequest validSimple, validAmbiguous, validVelocity, uncertainWorld, uncertainSearch, invalidHorizontal, invalidVertical, twoBranch, fourBranch, gap2, gap3, worst;
    private TimingReconstruction.Result rValidSimple, rValidVelocity, rInvalidHorizontal, rUncertainWorld, rWorst;

    static WorldObservation.SectionContent uniform(BlockStateKey s) { return new WorldObservation.SectionContent(s.equals(AIR) ? 0 : 4096, List.of(s), 0, new long[0]); }
    static WorldObservation.SectionContent surface() {
        var palette = List.of(STONE, DIRT, AIR); int bits = 4, perLong = 16; long[] packed = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int y = i >> 8; int slot = y < 4 ? 0 : y < 6 ? 1 : 2; if (slot != 2) nonEmpty++; packed[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static WorldObservation.ChunkBaselineObserved chunk(int x, int z) { var sections = new ArrayList<WorldObservation.SectionContent>(24); for (int i = 0; i < 24; i++) sections.add(i < 8 ? uniform(STONE) : i == 8 ? surface() : uniform(AIR)); return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, ""); }
    private static TimingEvent.MovementObserved tx(long seq, long nanos, Vec3d p, boolean ground, float yaw) { return new TimingEvent.MovementObserved(seq, nanos, true, new Vector3(p.x(), p.y(), p.z()), true, new Rotation(yaw, 0f), ground); }

    /** A timing request: anchor tick end, {@code velocities} influences, k-1 silent ticks, then the target = truth after one forward tick, shifted by {@code shift}. */
    private TimingRequest build(int velocities, int k, Vec3d shift) {
        List<TimingEvent> ev = new ArrayList<>(); long s = 100, n = 1_000_000_000L; List<TimingEvent.ExternalMotionSent> sent = new ArrayList<>();
        ev.add(new TimingEvent.ClientTickEnd(++s, n += 1_000_000L));
        List<PhysicsEnvironment.ExternalMotion> applied = new ArrayList<>();
        for (int i = 0; i < velocities; i++) { TimingEvent.ExternalMotionSent m = new TimingEvent.ExternalMotionSent(++s, n += 1_000_000L, 200 + i, TimingEvent.Operation.SET, new Vector3(0.2 + 0.05 * i, 0.4, 0.0), "velocity"); ev.add(m); sent.add(m); if (i == 0) applied.add(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, new Vec3d(0.2, 0.4, 0.0), m.id())); }
        PhysicsState cur = resting;
        for (int t = 1; t < k; t++) { cur = simulator.simulate(cur, MovementInput.idle(0f), t == 1 && !applied.isEmpty() ? flat.withExternalMotions(applied) : flat).next(); ev.add(new TimingEvent.ClientTickEnd(++s, n += TICK)); }
        cur = simulator.simulate(cur, MovementInput.forward(0f, false), k == 1 && !applied.isEmpty() ? flat.withExternalMotions(applied) : flat).next();
        Vec3d p = new Vec3d(cur.position().x() + shift.x(), cur.position().y() + shift.y(), cur.position().z() + shift.z());
        TimingEvent.MovementObserved tgt = tx(++s, n += TICK, p, cur.onGround() && shift.y() == 0, 0f); ev.add(tgt);
        return new TimingRequest(ev, 100, 1_000_000_000L, true, new Vector3(4.5, 70, 4.5), 0f, tgt, sent, List.of(), RttEstimator.Summary.EMPTY, true, false, 0, n + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
    }
    private MovementValidationRequest req(TimingRequest t, TimingReconstruction.Frontier f, PhysicsEnvironment env, ReconstructionRequest.SearchBudget b) { return new MovementValidationRequest(t, f, env, b, true, true); }
    @Setup public void setup() {
        var dictionary = new BlockStateDictionary(); var budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, WorldBudget.PRIVATE_TARGET_BYTES);
        var engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dictionary); var behaviors = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2.table(), dictionary); simulator = new PhysicsSimulator(MovementProfile.paper26_2());
        timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(simulator)); validator = new MovementValidator(timing);
        W f = new W(new ObserverWorld(new SessionKey(new UUID(8, 1), 1), budget, dictionary)); f.apply(OVERWORLD); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) f.apply(chunk(cx, cz));
        flat = PhysicsEnvironment.of(f.world.currentView(), f.world.worldEpoch(), engine, behaviors, MovementProfile.paper26_2());
        W g = new W(new ObserverWorld(new SessionKey(new UUID(8, 2), 1), budget, dictionary)); g.apply(OVERWORLD); // no chunk observed: an unknown world
        partial = PhysicsEnvironment.of(g.world.currentView(), g.world.worldEpoch(), engine, behaviors, MovementProfile.paper26_2());
        PhysicsState s = PhysicsState.resting(4.5, 70, 4.5, PlayerBox.Pose.STANDING, true); for (int i = 0; i < 2; i++) s = simulator.simulate(s, MovementInput.idle(0f), flat).next(); resting = s; frontier = TimingReconstruction.Frontier.of(resting);
        // ambiguous: the correction-style anchor (the resting state with and without the ground flag both explain the same successor)
        ambiguous = new TimingReconstruction.Frontier(List.of(new TimingReconstruction.Node(resting, Set.of(), "ground"), new TimingReconstruction.Node(resting.withSupport(PhysicsState.Support.NONE), Set.of(), "air")));
        two = ambiguous;
        validSimple = req(build(0, 1, Vec3d.ZERO), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        validAmbiguous = req(build(0, 1, Vec3d.ZERO), ambiguous, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        validVelocity = req(build(1, 1, Vec3d.ZERO), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        uncertainWorld = req(build(0, 1, Vec3d.ZERO), frontier, partial, ReconstructionRequest.SearchBudget.DEFAULT);
        uncertainSearch = req(build(0, 1, new Vec3d(0.5, 0, 0)), two, flat, new ReconstructionRequest.SearchBudget(1, 4096, 3, 256, 64, 4096)); // no explaining step and a frontier cap of 1 against 2 nodes: SEARCH_LIMIT, never INVALID
        invalidHorizontal = req(build(0, 1, new Vec3d(0.5, 0, 0)), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        invalidVertical = req(build(0, 1, new Vec3d(0, 0.5, 0)), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        twoBranch = validVelocity; fourBranch = req(build(2, 1, Vec3d.ZERO), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        gap2 = req(build(0, 2, Vec3d.ZERO), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); gap3 = req(build(0, 3, Vec3d.ZERO), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        worst = req(build(3, 3, Vec3d.ZERO), frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT);
        rValidSimple = run(validSimple); rValidVelocity = run(validVelocity); rInvalidHorizontal = run(invalidHorizontal); rUncertainWorld = run(uncertainWorld); rWorst = run(worst);
        check(validSimple, MovementValidation.Classification.VALID); check(validAmbiguous, MovementValidation.Classification.VALID); check(validVelocity, MovementValidation.Classification.VALID);
        check(uncertainWorld, MovementValidation.Classification.UNCERTAIN); check(uncertainSearch, MovementValidation.Classification.UNCERTAIN);
        check(invalidHorizontal, MovementValidation.Classification.INVALID); check(invalidVertical, MovementValidation.Classification.INVALID);
        check(fourBranch, MovementValidation.Classification.VALID); check(gap2, MovementValidation.Classification.VALID); check(gap3, MovementValidation.Classification.VALID);
    }
    // shared with EvidenceBenchmarks (Phase 9 end-to-end kernels reuse the Phase 8 fixtures)
    MovementValidator validator() { return validator; }
    MovementValidationRequest validSimple() { return validSimple; }
    MovementValidationRequest invalidHorizontal() { return invalidHorizontal; }
    private TimingReconstruction.Result run(MovementValidationRequest r) { return timing.run(r.timing(), r.frontier(), r.environment(), r.reconstructionBudget()); }
    private void check(MovementValidationRequest r, MovementValidation.Classification expected) { var v = validator.validate(r); if (v.classification() != expected) throw new IllegalStateException("kernel fixture expected " + expected + " but got " + v.describe()); }

    // ---- validation-only kernels: the classifier over a precomputed Phase 7 result
    @Benchmark public MovementValidationResult classifyValidSimple() { return validator.classify(validSimple, rValidSimple); }
    @Benchmark public MovementValidationResult classifyValidVelocity() { return validator.classify(validVelocity, rValidVelocity); }
    @Benchmark public MovementValidationResult classifyInvalidHorizontal() { return validator.classify(invalidHorizontal, rInvalidHorizontal); }
    @Benchmark public MovementValidationResult classifyUncertainWorld() { return validator.classify(uncertainWorld, rUncertainWorld); }
    @Benchmark public MovementValidationResult classifyWorstBounded() { return validator.classify(worst, rWorst); }
    // ---- end to end: timing + Phase 6 + Phase 5 + classifier
    @Benchmark public MovementValidationResult endToEndValidSimple() { return validator.validate(validSimple); }
    @Benchmark public MovementValidationResult endToEndValidAmbiguous() { return validator.validate(validAmbiguous); }
    @Benchmark public MovementValidationResult endToEndValidVelocity() { return validator.validate(validVelocity); }
    @Benchmark public MovementValidationResult endToEndUncertainWorldIncomplete() { return validator.validate(uncertainWorld); }
    @Benchmark public MovementValidationResult endToEndUncertainSearchLimit() { return validator.validate(uncertainSearch); }
    @Benchmark public MovementValidationResult endToEndInvalidHorizontal() { return validator.validate(invalidHorizontal); }
    @Benchmark public MovementValidationResult endToEndInvalidVertical() { return validator.validate(invalidVertical); }
    @Benchmark public MovementValidationResult endToEndTwoBranch() { return validator.validate(twoBranch); }
    @Benchmark public MovementValidationResult endToEndFourBranch() { return validator.validate(fourBranch); }
    @Benchmark public MovementValidationResult endToEndGap2() { return validator.validate(gap2); }
    @Benchmark public MovementValidationResult endToEndGap3() { return validator.validate(gap3); }
    @Benchmark public MovementValidationResult endToEndWorstBounded() { return validator.validate(worst); }
}
