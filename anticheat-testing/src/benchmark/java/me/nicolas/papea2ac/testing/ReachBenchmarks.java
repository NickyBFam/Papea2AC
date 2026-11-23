package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.geometry.RayBoxBounds;
import me.nicolas.papea2ac.combat.geometry.ScenePick;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.combat.reach.ReachValidation;
import me.nicolas.papea2ac.combat.reach.ReachValidator;
import me.nicolas.papea2ac.combat.reach.TargetHull;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.EntityLookup;

/**
 * Phase 12 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc). Descriptive, one host, never a capacity claim.
 *
 * <p>Per attack (session shard, once per ATTACK when its client tick ends): the whole decision for a certified-eye VALID (one scene, witness +
 * block check), a certified-eye REACH INVALID, an abstract-eye (claim set, two poses) INVALID, and a 2-view moving target with a 12-update
 * window. Kernels: the client-exact pick of one target, the outer exclusion, the robust witness, the interpolation hull over a 12-update window.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class ReachBenchmarks {
    static final EntityGeometryTable TABLE = EntityGeometryTable.paper26_2();
    static final MovementProfile TRIG = MovementProfile.paper26_2();
    static final SessionKey KEY = new SessionKey(new UUID(0x12B, 0x12B), 1);
    static final ReachValidator.AirOracle AIR = (x, y, z) -> Boolean.TRUE;
    static final ApplicationFrontier PROVEN = new ApplicationFrontier(ApplicationFrontier.Status.MARKER_PROVEN, 10, 12, 3);
    static final ReachValidator.AttackerInput ATTACKER = new ReachValidator.AttackerInput(3.0, 4.5, 1.0, false, ReachValidator.HeldItem.CLASSIC, "survival");

    ReachValidator.Input validNear, invalidFar, abstractFar, movingTwoViews;
    double[] eye = {0.5, 65.62, 0.5}, view; PickMath.Box box, hull, core, eyeBox; List<double[]> window12; PickMath.Box lifetime;

    static ReachValidator.TargetInput target(List<double[]> window, PickMath.Box life) {
        return new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:zombie", 9, 1, window, true, false, false, life, PROVEN, "STANDING", 1.0, false);
    }
    static ReachValidator.EyeInput certified() { return new ReachValidator.EyeInput(ReachValidation.EyeSource.CERTIFIED_CONCRETE, List.of(new ReachValidator.EyeCandidate(0.5, 64, 0.5, "STANDING", false, 0, 0, 0)), null, List.of(), false); }

    @Setup public void setup() {
        view = PickMath.viewVector(TRIG, 0f, 0f);
        box = PickMath.makeBoundingBox(0.6F, 1.95F, 0.5, 64, 2.5);
        List<double[]> near = List.of(new double[]{0.5, 64, 2.5}), far = List.of(new double[]{0.5, 64, 4.8});
        validNear = new ReachValidator.Input(KEY, 100, "A1:100", target(near, new PickMath.Box(0.5, 64, 2.5, 0.5, 64, 2.5)), certified(), List.of(new ReachValidator.View(0f, 0f)), ATTACKER, AIR, true);
        invalidFar = new ReachValidator.Input(KEY, 100, "A1:100", target(far, new PickMath.Box(0.5, 64, 4.8, 0.5, 64, 4.8)), certified(), List.of(new ReachValidator.View(0f, 0f)), ATTACKER, AIR, true);
        abstractFar = new ReachValidator.Input(KEY, 100, "A1:100", target(far, new PickMath.Box(0.5, 64, 4.8, 0.5, 64, 4.8)),
            new ReachValidator.EyeInput(ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY, List.of(), new PickMath.Box(0.4998, 63.9998, 0.4998, 0.5002, 64.0002, 0.5002), List.of("STANDING", "CROUCHING"), false),
            List.of(new ReachValidator.View(0f, 0f)), ATTACKER, AIR, true);
        window12 = new ArrayList<>(); for (int i = 0; i < 12; i++) window12.add(new double[]{0.5 + 0.02 * i, 64, 2.4 + 0.03 * i});
        lifetime = new PickMath.Box(0.2, 64, 2.0, 0.9, 64.4, 3.2);
        movingTwoViews = new ReachValidator.Input(KEY, 100, "A1:100", target(window12, lifetime), certified(), List.of(new ReachValidator.View(-3f, 1f), new ReachValidator.View(0f, 0f)), ATTACKER, AIR, true);
        PickMath.Box positions = TargetHull.positions(window12, lifetime, 3, 3);
        hull = RayBoxBounds.hull(positions, 0.6F, 1.95F); core = RayBoxBounds.core(positions, 0.6F, 1.95F); eyeBox = new PickMath.Box(0.5, 65.62, 0.5, 0.5, 65.62, 0.5);
    }

    @Benchmark public ReachValidation decideValidCertified() { return ReachValidator.validate(validNear, TABLE, TRIG, "bench"); }
    @Benchmark public ReachValidation decideInvalidReachCertified() { return ReachValidator.validate(invalidFar, TABLE, TRIG, "bench"); }
    @Benchmark public ReachValidation decideInvalidAbstractEye() { return ReachValidator.validate(abstractFar, TABLE, TRIG, "bench"); }
    @Benchmark public ReachValidation decideMovingTargetTwoViews() { return ReachValidator.validate(movingTwoViews, TABLE, TRIG, "bench"); }
    @Benchmark public ScenePick.Result clientPick() { return ScenePick.pick(eye, view, 3.0, 4.5, box, true, ScenePick.BlockClip.MISS); }
    @Benchmark public RayBoxBounds.OuterBound outerExclusion() { return RayBoxBounds.exclude(eyeBox, view, hull, 3.0); }
    @Benchmark public double robustWitness() { return core == null ? 0 : RayBoxBounds.robustHit(eyeBox, view, core); }
    @Benchmark public PickMath.Box interpolationHull() { return TargetHull.positions(window12, lifetime, 3, 3); }

    /**
     * Through the runner with combat-time markers flowing (perf-to-phase12-01): the observation pipeline in PacketEngine's order (context,
     * timeline, world, timing ledger, combat runner, reach runner) with 40 zombies in view moving every tick and one PING / PONG per tick, so the
     * timing ring holds about 3 s of fighting. {@code withReach=false} is the same pipeline without the reach runner (the Phase 11 cost).
     */
    @State(Scope.Thread) public static class Fight {
        static final long TICK = 50_000_000L, MS = 1_000_000L; static final int SELF = 42, N = 40;
        @Param({ "false", "true" }) public boolean withReach;
        final SessionKey key = new SessionKey(new UUID(0x12CL, 0x12CL), 1);
        ObserverWorld world; PlayerTimeline timeline; TimingLedger timing; MovementContextLedger context; CombatRunner combat; ReachRunner reach;
        long seq, nanos = 1_000_000_000L, ticks, wire = 1000, attempt; int pingId = 0x0100_0000, target;

        @Setup public void setup() {
            world = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary());
            timeline = new PlayerTimeline(key); timing = new TimingLedger(key); context = new MovementContextLedger(key);
            combat = new CombatRunner(key, CombatProfile.PAPER_26_2, world, timeline, timing::view, () -> null, new CombatEngine.Metrics());
            reach = new ReachRunner(key, TABLE, TRIG, CombatProfile.PAPER_26_2, new ReachRunner.Metrics());
            outbound(new SemanticPayload.JoinGame(SELF, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false, -64, 384), 0);
            for (int i = 0; i < N; i++) outbound(CombatCorpus.spawnPayload(100 + i, "minecraft:zombie", (i % 16) * 0.9, 64, 3 + (i / 16) * 0.9), 0);
            for (int t = 0; t < 80; t++) { clientTick(); for (int i = 0; i < N; i++) outbound(move(100 + i), 0); serverTick(); marker(); }
        }
        static SemanticPayload.EntityDelta move(int id) { return new SemanticPayload.EntityDelta(id, (short) 37, (short) 0, (short) -11, new PacketPayload.Rotation(12f, 3f), true, true, true); }
        void apply(Observation.Event e, long dt) {
            seq++; nanos += dt; Observation o = new Observation(key, seq, nanos, 0, e);
            for (ContextEvent ce : ContextObservationMapper.map(o, SELF)) context.apply(key, ce);
            PlayerObservationMapper.map(o, SELF).ifPresent(po -> timeline.append(key, po, seq, nanos));
            if (e instanceof Observation.Outbound) for (WorldObservation w : WorldObservationMapper.map(o)) world.apply(key, w, seq, nanos, null); else world.observeSequence(key, seq, nanos);
            TimingObservationMapper.map(o, SELF, world.worldEpoch()).ifPresent(te -> timing.apply(key, te));
            combat.apply(o, nanos, SELF, world.worldEpoch(), null, false);
            if (withReach) reach.apply(o, combat, timing, context.view(), null, world, SELF);
        }
        void outbound(PacketPayload p, long dt) { apply(new Observation.Outbound(++wire, p), dt); }
        void inbound(PacketPayload p, long dt) { long a = ++attempt; apply(new Observation.Attempt(a, a, p), dt); }
        void clientTick() {
            inbound(new PacketPayload.Movement(new PacketPayload.Position(0.5, 64, 0.5), new PacketPayload.Rotation(0f, 0f), true, false), MS);
            inbound(new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0), 0);
        }
        void serverTick() { apply(new Observation.Tick(++ticks), TICK); }
        void marker() { int id = ++pingId; outbound(new PacketPayload.Control(PacketPayload.ControlKind.PING, id), 0); inbound(new PacketPayload.Control(PacketPayload.ControlKind.PONG, id), 20 * MS); }
    }
    /** One fighting client tick: ATTACK, the tick's movement + tick end (the decision), one combat-time marker. Includes input building. */
    @Benchmark public long attackTick(Fight s) { s.target = (s.target + 1) % Fight.N; s.inbound(new SemanticPayload.Attack(100 + s.target), Fight.MS); s.clientTick(); s.marker(); return s.seq; }
    /** Autoclicker burst: 64 ATTACKs (the pending bound) in one client tick, decided at its tick end. Per burst. */
    @Benchmark public long attackBurst64(Fight s) {
        for (int k = 0; k < 64; k++) { s.target = (s.target + 1) % Fight.N; s.inbound(new SemanticPayload.Attack(100 + s.target), 0); }
        s.clientTick(); s.serverTick(); return s.seq;
    }
}
