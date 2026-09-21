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
 * Phase 7 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc). Two families, reported separately (brief
 * section 69): TIMING-ONLY kernels (ledger updates, RTT sample, ACK register/resolve, velocity/teleport register, TimingResolver
 * alone for 1/2/4-branch, 2/3-tick gaps and the worst bounded enumeration) and END-TO-END kernels (timing + Phase 6 + Phase 5:
 * one-hypothesis, velocity 2-branch, velocity 4-branch, teleport application, 2/3-tick gaps, worst bounded). Descriptive kernels
 * on one host, never a capacity claim.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class TimingBenchmarks {
    static final BlockStateKey AIR = BlockStateKey.of("minecraft:air"), STONE = BlockStateKey.of("minecraft:stone"), DIRT = BlockStateKey.of("minecraft:dirt");
    static final WorldObservation.DimensionEntered OVERWORLD = new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false);
    static final long TICK = 50_000_000L;
    private static final class W { final ObserverWorld world; long seq; W(ObserverWorld w) { world = w; } void apply(WorldObservation o) { world.apply(world.key(), o, ++seq, seq, null); } }
    private BlockStateDictionary dictionary; private WorldBudget budget; private CollisionEngine engine; private BlockBehaviorQuery behaviors; private PhysicsSimulator simulator; private InputReconstructor reconstructor;
    private TimingResolver resolver; private TimingReconstruction timing; private PhysicsEnvironment flat; private PhysicsState resting; private TimingReconstruction.Frontier frontier;
    private TimingRequest oneBranch, twoBranch, fourBranch, gap2, gap3, teleportReq, worst; private TimingReconstruction.Frontier teleportFrontier;
    private SessionKey key; private TimingLedger ledger; private long seq = 1, nanos = 1_000_000_000L; private RttEstimator estimator; private MarkerLedger markers; private int markerId;

    static WorldObservation.SectionContent uniform(BlockStateKey s) { return new WorldObservation.SectionContent(s.equals(AIR) ? 0 : 4096, List.of(s), 0, new long[0]); }
    static WorldObservation.SectionContent surface() {
        var palette = List.of(STONE, DIRT, AIR); int bits = 4, perLong = 16; long[] packed = new long[256]; int nonEmpty = 0;
        for (int i = 0; i < 4096; i++) { int y = i >> 8; int slot = y < 4 ? 0 : y < 6 ? 1 : 2; if (slot != 2) nonEmpty++; packed[i / perLong] |= ((long) slot) << ((i % perLong) * bits); }
        return new WorldObservation.SectionContent(nonEmpty, palette, bits, packed);
    }
    static WorldObservation.ChunkBaselineObserved chunk(int x, int z) { var sections = new ArrayList<WorldObservation.SectionContent>(24); for (int i = 0; i < 24; i++) sections.add(i < 8 ? uniform(STONE) : i == 8 ? surface() : uniform(AIR)); return new WorldObservation.ChunkBaselineObserved(x, z, true, sections, 0, ""); }
    private static TimingEvent.MovementObserved tx(long seq, long nanos, PhysicsState s, float yaw) { return new TimingEvent.MovementObserved(seq, nanos, true, new Vector3(s.position().x(), s.position().y(), s.position().z()), true, new Rotation(yaw, 0f), s.onGround()); }

    /** A request: anchor tick end, {@code velocities} influences, k-1 silent ticks (tick ends), the target after simulating the truth from the resting state. */
    private TimingRequest build(int velocities, int k, boolean teleport) {
        List<TimingEvent> ev = new ArrayList<>(); long s = 100, n = 1_000_000_000L; List<TimingEvent.ExternalMotionSent> sent = new ArrayList<>();
        ev.add(new TimingEvent.ClientTickEnd(++s, n += 1_000_000L));
        List<PhysicsEnvironment.ExternalMotion> applied = new ArrayList<>();
        for (int i = 0; i < velocities; i++) { TimingEvent.ExternalMotionSent m = new TimingEvent.ExternalMotionSent(++s, n += 1_000_000L, 200 + i, TimingEvent.Operation.SET, new Vector3(0.2 + 0.05 * i, 0.4, 0.0), "velocity"); ev.add(m); sent.add(m); if (i == 0) applied.add(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, new Vec3d(0.2, 0.4, 0.0), m.id())); }
        PhysicsState cur = resting;
        for (int t = 1; t < k; t++) { cur = simulator.simulate(cur, MovementInput.idle(0f), t == 1 && !applied.isEmpty() ? flat.withExternalMotions(applied) : flat).next(); ev.add(new TimingEvent.ClientTickEnd(++s, n += TICK)); }
        TimingEvent.TeleportSent corr = null;
        if (teleport) { corr = new TimingEvent.TeleportSent(++s, n += 1_000_000L, 9, 300, new Vector3(12.5, 70, 12.5), new Rotation(0f, 0f), Vector3.ZERO, 0); ev.add(corr); ev.add(new TimingEvent.TeleportConfirmed(++s, n += 20_000_000L, 9)); TimingEvent.MovementObserved tgt = new TimingEvent.MovementObserved(++s, n += 100_000L, true, new Vector3(12.5, 70, 12.5), true, new Rotation(0f, 0f), true); ev.add(tgt);
            List<TimingLedger.PendingTeleport> tp = List.of(new TimingLedger.PendingTeleport(corr, tgt.sequence() - 1, n - 100_000L, false, false));
            return new TimingRequest(ev, 100, 1_000_000_000L, true, new Vector3(4.5, 70, 4.5), 0f, tgt, sent, tp, RttEstimator.Summary.EMPTY, true, false, 0, n + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT); }
        cur = simulator.simulate(cur, MovementInput.forward(0f, false), k == 1 && !applied.isEmpty() ? flat.withExternalMotions(applied) : flat).next();
        TimingEvent.MovementObserved tgt = tx(++s, n += TICK, cur, 0f); ev.add(tgt);
        return new TimingRequest(ev, 100, 1_000_000_000L, true, new Vector3(4.5, 70, 4.5), 0f, tgt, sent, List.of(), RttEstimator.Summary.EMPTY, true, false, 0, n + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
    }
    @Setup public void setup() {
        dictionary = new BlockStateDictionary(); budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, WorldBudget.PRIVATE_TARGET_BYTES);
        engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dictionary); behaviors = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2.table(), dictionary); simulator = new PhysicsSimulator(MovementProfile.paper26_2()); reconstructor = new InputReconstructor(simulator);
        resolver = new TimingResolver(); timing = new TimingReconstruction(resolver, reconstructor);
        W f = new W(new ObserverWorld(new SessionKey(new UUID(7, 1), 1), budget, dictionary)); f.apply(OVERWORLD); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) f.apply(chunk(cx, cz));
        flat = PhysicsEnvironment.of(f.world.currentView(), f.world.worldEpoch(), engine, behaviors, MovementProfile.paper26_2());
        PhysicsState s = PhysicsState.resting(4.5, 70, 4.5, PlayerBox.Pose.STANDING, true); for (int i = 0; i < 2; i++) s = simulator.simulate(s, MovementInput.idle(0f), flat).next(); resting = s; frontier = TimingReconstruction.Frontier.of(resting);
        oneBranch = build(0, 1, false); twoBranch = build(1, 1, false); fourBranch = build(2, 1, false); gap2 = build(0, 2, false); gap3 = build(0, 3, false); teleportReq = build(0, 1, true); teleportFrontier = frontier;
        // worst bounded timing enumeration: three influences over three ticks (hits the 16-hypothesis cap)
        worst = build(3, 3, false);
        key = new SessionKey(new UUID(7, 2), 1); ledger = new TimingLedger(key); estimator = new RttEstimator(); markers = new MarkerLedger();
    }

    // ---- timing-only kernels
    @Benchmark public boolean rttSampleUpdate() { return estimator.accept(20_000_000L + (seq++ & 7) * 1_000_000L, nanos += TICK); }
    @Benchmark public MarkerLedger.Answer ackRegisterAndResolve() { int id = ++markerId; markers.sent(TimingEvent.MarkerKind.PING, id, seq++, nanos, 1000 + id); return markers.acknowledged(TimingEvent.MarkerKind.PING, id, nanos += 20_000_000L); }
    @Benchmark public boolean ledgerVelocityRegister() { return ledger.apply(key, new TimingEvent.ExternalMotionSent(seq++, nanos += TICK, seq, TimingEvent.Operation.SET, new Vector3(0.2, 0.4, 0), "velocity")); }
    @Benchmark public boolean ledgerTeleportRegisterAndConfirm() { long id = seq; ledger.apply(key, new TimingEvent.TeleportSent(seq++, nanos += TICK, id, seq, new Vector3(1, 70, 1), new Rotation(0f, 0f), Vector3.ZERO, 0)); return ledger.apply(key, new TimingEvent.TeleportConfirmed(seq++, nanos += 1_000_000L, id)); }
    @Benchmark public boolean ledgerTickEnd() { return ledger.apply(key, new TimingEvent.ClientTickEnd(seq++, nanos += TICK)); }
    @Benchmark public TimingResolution timingOnlyOneBranch() { return resolver.resolve(oneBranch); }
    @Benchmark public TimingResolution timingOnlyTwoBranch() { return resolver.resolve(twoBranch); }
    @Benchmark public TimingResolution timingOnlyFourBranch() { return resolver.resolve(fourBranch); }
    @Benchmark public TimingResolution timingOnlyTeleport() { return resolver.resolve(teleportReq); }
    @Benchmark public TimingResolution timingOnlyGap2() { return resolver.resolve(gap2); }
    @Benchmark public TimingResolution timingOnlyGap3() { return resolver.resolve(gap3); }
    @Benchmark public TimingResolution timingOnlyWorstBounded() { return resolver.resolve(worst); }
    // ---- end to end: timing + Phase 6 + Phase 5
    @Benchmark public TimingReconstruction.Result endToEndOneBranch() { return timing.run(oneBranch, frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
    @Benchmark public TimingReconstruction.Result endToEndVelocityTwoBranch() { return timing.run(twoBranch, frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
    @Benchmark public TimingReconstruction.Result endToEndVelocityFourBranch() { return timing.run(fourBranch, frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
    @Benchmark public TimingReconstruction.Result endToEndTeleport() { return timing.run(teleportReq, teleportFrontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
    @Benchmark public TimingReconstruction.Result endToEndGap2() { return timing.run(gap2, frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
    @Benchmark public TimingReconstruction.Result endToEndGap3() { return timing.run(gap3, frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
    @Benchmark public TimingReconstruction.Result endToEndWorstBounded() { return timing.run(worst, frontier, flat, ReconstructionRequest.SearchBudget.DEFAULT); }
}
