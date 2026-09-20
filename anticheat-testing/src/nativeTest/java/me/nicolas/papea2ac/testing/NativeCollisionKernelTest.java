package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.NativeCollisionSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.*;
import me.nicolas.papea2ac.world.BlockStateDictionary;

/**
 * The two geometry kernels against their vanilla originals on every distinct shape of the profile: candidate
 * acceptance ({@code AABB.intersects} for the full cube, {@code Shapes.joinIsNotEmpty(AND)} otherwise) and single-axis
 * clipping ({@code VoxelShape.collide}, {@code Shapes.collide}). Random boxes include exact face contact, EPSILON-scale
 * overlaps, containment and full penetration; the seed is fixed and printed in every failure message.
 */
class NativeCollisionKernelTest {
    static BlockShapeResolver resolver; static List<Object> nativeShapes = new ArrayList<>(); static List<CollisionShape> ours = new ArrayList<>(); static List<String> names = new ArrayList<>();
    @BeforeAll static void setUp() throws Exception {
        NativeCollisionSupport.bootstrap(); resolver = new BlockShapeResolver(CollisionProfile.PAPER_26_2, new BlockStateDictionary(), new CollisionMetrics());
        // one representative native VoxelShape per distinct table shape (first registry state that produces it), plus the vanilla full block
        var seen = new HashSet<CollisionShape>();
        for (int id = 0; id < stateCount(); id++) {
            Object state = state(id); var plan = resolver.plan(keyOf(state)); if (plan.kind() != BlockShapeTable.Kind.STATIC || plan.resting().isEmpty()) continue;
            if (seen.add(plan.resting())) { nativeShapes.add(nativeShape(state, pos(0, 0, 0), emptyCtx)); ours.add(plan.resting()); names.add(keyOf(state).canonical()); }
        }
        assertTrue(ours.size() > 300, "distinct static shapes: " + ours.size());
    }
    static double jitter(Random r) { // a mix of ordinary offsets, exact grid values and EPSILON-scale perturbations
        return switch (r.nextInt(6)) { case 0 -> r.nextInt(-2, 3) * 0.5; case 1 -> r.nextInt(-16, 33) / 16.0; case 2 -> r.nextInt(-2, 3) * 0.5 + (r.nextBoolean() ? 1 : -1) * r.nextInt(1, 30) * 1.0E-8; case 3 -> r.nextInt(-2, 3) * 0.5 + (r.nextBoolean() ? 1 : -1) * 1.0E-7; default -> r.nextDouble(-1.5, 2.5); };
    }
    static Aabb box(Random r) {
        double x0 = jitter(r), y0 = jitter(r), z0 = jitter(r); double w = r.nextInt(4) == 0 ? 0 : Math.abs(jitter(r)) + (r.nextBoolean() ? 0.6 : 0), h = r.nextInt(4) == 0 ? 0 : Math.abs(jitter(r)) + (r.nextBoolean() ? 1.8 : 0), l = Math.abs(jitter(r));
        return new Aabb(x0, y0, z0, x0 + w, y0 + h, z0 + l);
    }

    @Test void candidateAcceptanceMatchesVanillaForEveryShape() throws Exception {
        long seed = 0xC0111DE; var r = new Random(seed); int checked = 0;
        for (int s = 0; s < ours.size(); s++) for (int i = 0; i < 40; i++) {
            int cx = r.nextInt(-3, 3), cy = r.nextInt(-3, 3), cz = r.nextInt(-3, 3); Aabb q = box(r).move(cx, cy, cz);
            boolean expected = vanillaAccepts(nativeShapes.get(s), moved(nativeShapes.get(s), cx, cy, cz), new Aabb(cx, cy, cz, cx + 1, cy + 1, cz + 1), q);
            boolean actual = CollisionEngine.intersects(ours.get(s), cx, cy, cz, q);
            assertEquals(expected, actual, "seed=" + seed + " shape=" + names.get(s) + " at " + cx + "," + cy + "," + cz + " box=" + q); checked++;
        }
        assertTrue(checked > 10000);
    }

    @Test void axisClipMatchesVanillaCollideForEveryShapeIncludingPenetration() throws Exception {
        long seed = 0xC11BED; var r = new Random(seed); int checked = 0, penetrating = 0, moonriseDiffers = 0;
        for (int s = 0; s < ours.size(); s++) for (int i = 0; i < 60; i++) {
            int cx = r.nextInt(-2, 2), cy = r.nextInt(-2, 2), cz = r.nextInt(-2, 2); Aabb b = box(r).move(cx, cy, cz);
            double desired = switch (r.nextInt(5)) { case 0 -> 0.0; case 1 -> (r.nextBoolean() ? 1 : -1) * 5.0E-8; case 2 -> (r.nextBoolean() ? 1 : -1) * r.nextInt(1, 4) * 0.25; default -> r.nextDouble(-3, 3); };
            for (int axis = 0; axis < 3; axis++) {
                Object movedNative = moved(nativeShapes.get(s), cx, cy, cz);
                double expected = vanillaCollide(movedNative, axis, b, desired);
                double actual = AxisClip.clip(AxisClip.Axis.values()[axis], b, desired, ours.get(s), cx, cy, cz);
                if (CollisionEngine.intersects(ours.get(s), cx, cy, cz, b)) penetrating++;
                if (Double.doubleToLongBits(moonriseCollide(movedNative, axis, b, desired)) != Double.doubleToLongBits(expected)) moonriseDiffers++;
                final int fs = s, fa = axis; assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), () -> "seed=" + seed + " shape=" + names.get(fs) + " at " + cx + "," + cy + "," + cz + " box=" + b + " axis=" + fa + " desired=" + desired + " expected=" + expected + " actual=" + actual); checked++;
            }
        }
        assertTrue(penetrating > 500, "penetrating cases exercised: " + penetrating);
        assertTrue(checked > 50000);
        System.out.println("NativeCollisionKernelTest: vanilla-vs-Moonrise server collide divergences on this sample: " + moonriseDiffers + " of " + checked + " (documented; the client algorithm is the reference)");
    }

    @Test void shapesCollideOverManyShapesMatchesTheResultLevelClip() throws Exception {
        long seed = 0x5EED5; var r = new Random(seed);
        for (int i = 0; i < 300; i++) {
            int n = r.nextInt(1, 9); var natives = new ArrayList<Object>(); var hits = new ArrayList<CollisionResult.Hit>();
            for (int k = 0; k < n; k++) { int s = r.nextInt(ours.size()); int cx = r.nextInt(-2, 3), cy = r.nextInt(-2, 3), cz = r.nextInt(-2, 3); natives.add(moved(nativeShapes.get(s), cx, cy, cz)); hits.add(new CollisionResult.Hit(cx, cy, cz, ours.get(s), cx, cy, cz, CellStatus.EXACT, true)); }
            var result = new CollisionResult(new Aabb(-3, -3, -3, 4, 4, 4), CollisionResult.Certainty.COMPLETE, null, 0, 0, 0, hits, new int[CellStatus.values().length], 0, false, "test");
            Aabb b = box(r); double desired = r.nextDouble(-3, 3);
            for (int axis = 0; axis < 3; axis++) {
                double expected = vanillaCollideMany(natives, axis, b, desired);
                double actual = AxisClip.clip(AxisClip.Axis.values()[axis], b, desired, result);
                assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), "seed=" + seed + " i=" + i + " axis=" + axis + " expected=" + expected + " actual=" + actual);
            }
        }
    }
}
