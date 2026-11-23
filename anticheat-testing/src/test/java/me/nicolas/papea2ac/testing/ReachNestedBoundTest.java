package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.reach.TargetHull;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.version.OutlineShapeTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 backtrack refinement (every answered marker at once) and the outline table:
 * <ul>
 * <li>the prefix-weight linear program is solved exactly (brute force over every feasible vertex on small instances; never below a random
 *     feasible point);</li>
 * <li>the nested box is never wider than the single-newest-frontier box, and it narrows when older proofs exist;</li>
 * <li>the outline table resolves known states (full cube, bottom slab, offset plants) and refuses unknown ones.</li>
 * </ul>
 * (The nested box's soundness against an exact client simulation is in {@code ReachValidatorTest.theInterpolationHullContainsEveryClientPosition}.)
 */
class ReachNestedBoundTest {
    @Test void thePrefixWeightProgramIsSolvedExactly() {
        Random r = new Random(0x12_3001L);
        for (int c = 0; c < 20_000; c++) {
            int n = 1 + r.nextInt(6); double[] d = new double[n], caps = new double[n];
            for (int i = 0; i < n; i++) { d[i] = (r.nextDouble() - 0.5) * 4; caps[i] = r.nextInt(4) == 0 ? 1.0 : r.nextDouble(); }
            double lp = TargetHull.maxPrefixWeights(d, caps);
            // brute force: P_t takes the previous value or its effective cap (suffix minimum), monotone; enumerate all 2^n paths
            double[] eff = new double[n]; double m = 1.0; for (int t = n - 1; t >= 0; t--) { m = Math.min(m, caps[t]); eff[t] = m; }
            double best = 0.0;
            for (int mask = 0; mask < (1 << n); mask++) {
                double p = 0.0, v = 0.0; boolean ok = true;
                for (int t = 0; t < n; t++) { if ((mask & (1 << t)) != 0) { if (eff[t] < p) { ok = false; break; } p = eff[t]; } v += d[t] * p; }
                if (ok) best = Math.max(best, v);
            }
            assertEquals(best, lp, 1e-12, () -> "LP " + Arrays.toString(d) + " caps " + Arrays.toString(caps));
            for (int k = 0; k < 20; k++) { // random feasible points never exceed it
                double p = 0.0, v = 0.0; for (int t = 0; t < n; t++) { p = Math.min(eff[t], p + r.nextDouble() * (eff[t] - p)); v += d[t] * p; }
                assertTrue(v <= lp + 1e-12, "feasible point above the optimum");
            }
        }
    }

    @Test void theNestedBoxIsNeverWiderAndNarrowsWithOlderProofs() {
        Random r = new Random(0x12_3002L); int narrower = 0;
        for (int c = 0; c < 5_000; c++) {
            int n = 10 + r.nextInt(40); List<double[]> h = new ArrayList<>(); double x = 0, y = 64, z = 0;
            for (int i = 0; i < n; i++) { x += (r.nextDouble() - 0.3) * 0.3; z += (r.nextDouble() - 0.5) * 0.3; h.add(new double[]{x, y, z}); }
            PickMath.Box life = ReachValidatorTest.box(h);
            int newest = n - 1 - r.nextInt(3);
            PickMath.Box single = TargetHull.positions(h.subList(newest, n), life, 3, 1);
            List<TargetHull.Bound> bounds = new ArrayList<>(); for (int i = 1; i <= newest; i++) bounds.add(new TargetHull.Bound(i, Math.max(1, newest - i + 1)));
            PickMath.Box nested = TargetHull.nested(h, life, 3, bounds);
            double eps = 1e-9;
            assertTrue(nested.minX() >= single.minX() - eps && nested.maxX() <= single.maxX() + eps && nested.minZ() >= single.minZ() - eps && nested.maxZ() <= single.maxZ() + eps,
                () -> "nested " + nested + " wider than single " + single);
            if (nested.maxX() - nested.minX() < single.maxX() - single.minX() - 1e-6) narrower++;
        }
        assertTrue(narrower > 2_500, "older proofs narrow the box in most cases: " + narrower);
    }

    @Test void theOutlineTableResolvesKnownStatesAndRefusesUnknownOnes() {
        OutlineShapeTable t = OutlineShapeTable.paper26_2();
        assertTrue(t.blockCount() > 1000, "every block of the pinned registry");
        var stone = t.lookup(BlockStateKey.of("minecraft:stone"));
        assertEquals(OutlineShapeTable.Kind.STATIC, stone.kind()); assertArrayEquals(new double[]{0, 0, 0, 1, 1, 1}, stone.boxes(), 0.0);
        var air = t.lookup(BlockStateKey.of("minecraft:air")); assertTrue(air.empty(), "air has no outline");
        var slab = t.lookup(new BlockStateKey("minecraft:oak_slab", new TreeMap<>(Map.of("type", "bottom", "waterlogged", "false"))));
        assertEquals(OutlineShapeTable.Kind.STATIC, slab.kind()); assertArrayEquals(new double[]{0, 0, 0, 1, 0.5, 1}, slab.boxes(), 0.0);
        var grass = t.lookup(BlockStateKey.of("minecraft:short_grass"));
        assertEquals(OutlineShapeTable.Kind.OFFSET, grass.kind(), "short grass has an outline that moves with the per-position offset"); assertFalse(grass.empty());
        assertNull(t.lookup(BlockStateKey.of("minecraft:not_a_block")));
        assertNull(t.lookup(new BlockStateKey("minecraft:oak_slab", new TreeMap<>(Map.of("type", "sideways", "waterlogged", "false")))), "unknown value");
    }
}
