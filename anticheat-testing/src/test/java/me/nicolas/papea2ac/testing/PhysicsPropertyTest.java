package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.collision.CollisionResult;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.physics.PhysicsResult.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 property tests over a cluttered course with pseudo-random states and inputs (NUMERICAL_POLICY "Containment
 * testing includes signed zero, near-table-index boundaries, grazing collision, subnormal/large finite values,
 * multi-step error propagation"): determinism, finiteness, status/reason consistency, bounded work, no penetration of
 * definite geometry after an EXACT step, pose closure and stuck-multiplier consumption.
 */
class PhysicsPropertyTest {
    static final Set<Status> NO_STATE = EnumSet.of(Status.NUMERIC_INVALID, Status.COORDINATE_LIMIT, Status.UNAVAILABLE_WORLD, Status.UNSUPPORTED_MODE, Status.UNSUPPORTED_POSE, Status.UNSUPPORTED_FLUID, Status.UNKNOWN_ATTRIBUTE);

    static PhysicsScene course(long seed) {
        PhysicsScene ps = new PhysicsScene(); Random r = new Random(seed);
        BlockStateKey[] palette = { SLAB_BOTTOM, SLAB_TOP, STAIRS_STRAIGHT, FENCE_POST, WALL_POST, ICE, SLIME, HONEY, SOUL_SAND, COBWEB, LADDER, CARPET, SNOW_2, TRAPDOOR_OPEN, GATE_CLOSED, BlockStateKey.of("minecraft:stone"), BlockStateKey.of("minecraft:stone"), SCAFFOLDING, POWDER_SNOW };
        for (int i = 0; i < 90; i++) ps.set(r.nextInt(16), 70 + r.nextInt(3), r.nextInt(16), palette[r.nextInt(palette.length)]);
        return ps;
    }
    static MovementInput input(Random r) { return new MovementInput(r.nextInt(3) - 1, r.nextInt(3) - 1, r.nextInt(4) == 0, r.nextInt(6) == 0, r.nextInt(3) == 0, r.nextInt(12) == 0, r.nextFloat() * 720f - 360f); }

    @Test void deterministicFiniteAndStatusConsistentOverRandomTicks() {
        PhysicsScene ps = course(1); Random r = new Random(2); PhysicsEnvironment env = ps.env();
        PhysicsState s = standingAt(8.5, 73, 8.5).withVelocity(new Vec3d(0, -0.0784, 0)); int exact = 0, ticks = 0;
        for (int i = 0; i < 1500; i++) {
            MovementInput in = input(r);
            PhysicsResult a = ps.simulator.simulate(s, in, env), b = ps.simulator.simulate(s, in, env);
            assertEquals(a, b, "determinism at tick " + i);
            assertEquals(a.exact(), a.reasons().isEmpty(), a.summary());
            if (a.next() == null) assertTrue(NO_STATE.contains(a.status()), a.summary()); else { assertTrue(a.next().finite(), a.summary()); assertTrue(EnumSet.of(PlayerBox.Pose.STANDING, PlayerBox.Pose.CROUCHING, PlayerBox.Pose.SWIMMING).contains(a.next().pose())); }
            assertTrue(a.work().queries() <= PhysicsEnvironment.Budget.DEFAULT.maxQueries() && a.work().insideCells() <= PhysicsEnvironment.Budget.DEFAULT.maxInsideCells(), a.summary());
            if (a.exact()) exact++;
            ticks++;
            if (a.next() == null || a.next().position().y() < 60) { s = standingAt(2 + r.nextInt(12) + 0.5, 73, 2 + r.nextInt(12) + 0.5).withVelocity(new Vec3d(0, -0.0784, 0)); continue; }
            s = a.next();
        }
        assertTrue(exact > ticks * 0.9, "exact " + exact + "/" + ticks);
    }
    @Test void exactStepsNeverLeaveTheBodyInsideDefiniteGeometry() {
        PhysicsScene ps = course(3); Random r = new Random(4); PhysicsEnvironment env = ps.env(); int checked = 0;
        PhysicsState s = standingAt(8.5, 73, 8.5).withVelocity(new Vec3d(0, -0.0784, 0));
        for (int i = 0; i < 1200; i++) {
            PhysicsResult a = ps.simulator.simulate(s, input(r), env);
            if (a.next() == null || a.next().position().y() < 60) { s = standingAt(2 + r.nextInt(12) + 0.5, 73, 2 + r.nextInt(12) + 0.5).withVelocity(new Vec3d(0, -0.0784, 0)); continue; }
            Aabb before = PlayerBox.of(PROFILE_C(), s.pose(), s.position().x(), s.position().y(), s.position().z());
            boolean startClear = ps.scene.query(before.inflate(-1e-6), ShapeContext.of(s.position().y(), false, s.fallDistance(), false)).definitelyClear();
            if (a.exact() && startClear && !a.hasReason(PhysicsResult.Reason.Code.INSIDE_BLOCK_START)) {
                Aabb after = PlayerBox.of(PROFILE_C(), a.next().pose(), a.next().position().x(), a.next().position().y(), a.next().position().z()).inflate(-1e-6);
                CollisionResult q = ps.scene.query(after, ShapeContext.of(a.next().position().y(), false, a.next().fallDistance(), false));
                assertFalse(q.definitelyObstructed(), "penetration after tick " + i + ": " + a.summary() + " " + q.summary());
                checked++;
            }
            s = a.next();
        }
        assertTrue(checked > 500, "checked " + checked);
    }
    @Test void stuckMultiplierIsConsumedByExactlyOneMove() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5).withStuck(new Vec3d(0.25, 0.05, 0.25)).withVelocity(new Vec3d(0.2, -0.0784, 0.2));
        PhysicsResult r = ps.step(s, MovementInput.idle(0f));
        assertEquals(Vec3d.ZERO, r.next().stuck()); assertEquals(0.05, r.trace().movementRequested().x(), 1e-15); assertEquals(Vec3d.ZERO.x(), r.trace().velocityBeforeMove().x() * 0.0);
        PhysicsResult r2 = ps.step(r.next(), MovementInput.idle(0f)); assertEquals(0.0, r2.trace().movementRequested().x(), "velocity was zeroed by the stuck move");
    }
    @Test void signedZeroAndTinyValuesDoNotBreakTheStep() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        for (Vec3d v : List.of(new Vec3d(-0.0, -0.0784, -0.0), new Vec3d(Double.MIN_VALUE, -0.0784, -Double.MIN_VALUE), new Vec3d(1e-300, -0.0784, 0), new Vec3d(0, -0.0, 0))) {
            PhysicsResult r = ps.step(s.withVelocity(v), MovementInput.idle(0f)); assertNotNull(r.next()); assertTrue(r.next().finite()); assertEquals(Status.EXACT, r.status(), r.summary());
        }
        for (float yaw : new float[] { -0.0f, 359.9999f, 1e-30f, 1e30f, -1e30f }) { PhysicsResult r = ps.step(s, MovementInput.forward(yaw, true)); assertTrue(r.next().finite(), "yaw " + yaw); }
    }
    @Test void allocationPerStepIsBounded() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env(); MovementInput in = MovementInput.forward(0f, true);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 2000; i++) s = ps.simulator.simulate(s, in, env).next(); // warm-up
        s = settled(ps, 8.5, SURFACE, 8.5); long id = Thread.currentThread().threadId(); long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1000; i++) s = ps.simulator.simulate(s, in, env).next();
        long per = (mx.getThreadAllocatedBytes(id) - before) / 1000;
        assertTrue(per < 20000, "allocation per step " + per + " B (bounded; the measured figure is the memory harness's: about 9-15 KB, dominated by the Phase 4 CollisionResult values)");
    }
    private static me.nicolas.papea2ac.version.CollisionProfile PROFILE_C() { return CollisionTestSupport.PROFILE; }
}
