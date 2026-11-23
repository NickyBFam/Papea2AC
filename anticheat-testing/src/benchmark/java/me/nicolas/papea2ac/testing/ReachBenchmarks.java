package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
}
