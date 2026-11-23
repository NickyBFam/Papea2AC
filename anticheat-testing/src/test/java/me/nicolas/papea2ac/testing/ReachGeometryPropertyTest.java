package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.geometry.RayBoxBounds;
import me.nicolas.papea2ac.combat.geometry.ScenePick;
import me.nicolas.papea2ac.version.MovementProfile;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 geometry soundness against the client-exact pick ({@link ScenePick} over {@link PickMath}, which NativeReachGeometryTest proves
 * bit-exact against the pinned 26.2 client):
 * <ul>
 * <li>an outer exclusion (ANGLE / REACH) is never contradicted by any concrete eye and target position sampled inside the sets, corners and
 *     edges included;</li>
 * <li>a robust witness always corresponds to a concrete client HIT for every sampled eye and position, at a distance no larger than the bound;</li>
 * <li>simple fixed scenes give the vanilla answer (3.0 strict boundary, from-inside, occlusion ordering).</li>
 * </ul>
 */
class ReachGeometryPropertyTest {
    static final MovementProfile TABLE = MovementProfile.paper26_2();
    static final float W = 0.6F, H = 1.8F;

    static double[] view(float pitch, float yaw) { return PickMath.viewVector(TABLE, pitch, yaw); }

    /** A point of a box: corners (and edge midpoints) with probability, otherwise uniform. */
    static double[] sample(Random r, PickMath.Box b) {
        double[] lo = {b.minX(), b.minY(), b.minZ()}, hi = {b.maxX(), b.maxY(), b.maxZ()}, p = new double[3];
        for (int a = 0; a < 3; a++) {
            int k = r.nextInt(5);
            p[a] = k == 0 ? lo[a] : k == 1 ? hi[a] : lo[a] + r.nextDouble() * (hi[a] - lo[a]);
        }
        return p;
    }

    static ScenePick.Result concrete(double[] eye, double[] v, double[] pos, float w, float h, double range) {
        PickMath.Box box = PickMath.makeBoundingBox(w, h, pos[0], pos[1], pos[2]);
        return ScenePick.pick(eye, v, range, 4.5, box, true, ScenePick.BlockClip.MISS);
    }

    @Test void fixedScenesGiveTheVanillaAnswer() {
        double[] v = view(0f, 0f); // looking +z
        assertEquals(0.0, v[0], 1e-6); assertEquals(1.0, v[2], 1e-6);
        double[] eye = {0.5, 65.62, 0.5};
        // target box face 2.999 ahead: hit; exactly 3.0 ahead (the box half-width is the float 0.3F, as makeBoundingBox widens it): refused by the strict closerThan
        ScenePick.Result near = ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 0.5, 64.0, 0.5 + 2.999 + 0.3), true, ScenePick.BlockClip.MISS);
        assertEquals(ScenePick.Outcome.HIT, near.outcome(), near.toString());
        ScenePick.Result at = ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 0.5, 64.0, 0.5 + 3.0 + (double) (W / 2.0F)), true, ScenePick.BlockClip.MISS);
        assertNotEquals(ScenePick.Outcome.HIT, at.outcome(), at.toString());
        // beside the ray: angle miss
        assertEquals(ScenePick.Outcome.MISS_ANGLE, ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 2.5, 64.0, 2.5), true, ScenePick.BlockClip.MISS).outcome());
        // far ahead on the ray: reach miss
        assertEquals(ScenePick.Outcome.MISS_REACH, ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 0.5, 64.0, 5.0), true, ScenePick.BlockClip.MISS).outcome());
        // eye inside the target box: picked at distance 0
        ScenePick.Result inside = ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 0.5, 64.0, 0.5), true, ScenePick.BlockClip.MISS);
        assertTrue(inside.fromInside()); assertEquals(ScenePick.Outcome.HIT, inside.outcome());
        // a block outline hit closer than the entity occludes it
        ScenePick.Result occ = ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 0.5, 64.0, 3.0), true, ScenePick.BlockClip.hit(0.5, 65.62, 1.5));
        assertEquals(ScenePick.Outcome.MISS_OCCLUDED, occ.outcome());
        // unknown blocks: undecided, never a miss class
        assertEquals(ScenePick.Outcome.UNDECIDED_BLOCKS, ScenePick.pick(eye, v, 3.0, 4.5, PickMath.makeBoundingBox(W, H, 0.5, 64.0, 2.0), true, ScenePick.BlockClip.UNKNOWN).outcome());
    }

    @Test void outerExclusionIsNeverContradictedByAConcreteScene() {
        Random r = new Random(0x12_1001L); int excluded = 0, angle = 0, reach = 0, samples = 0;
        for (int i = 0; i < 4_000; i++) {
            double ex = (r.nextDouble() - 0.5) * 2000, ey = 60 + r.nextDouble() * 20, ez = (r.nextDouble() - 0.5) * 2000;
            double eyeSpread = r.nextInt(3) == 0 ? 0 : r.nextDouble() * 0.05;
            PickMath.Box eye = new PickMath.Box(ex, ey, ez, ex + eyeSpread, ey + eyeSpread, ez + eyeSpread);
            float pitch = (float) ((r.nextDouble() - 0.5) * 180), yaw = (float) ((r.nextDouble() - 0.5) * 720);
            double[] v = view(pitch, yaw);
            // target positions near the ray at 1..6 blocks, spread 0..0.8
            double dist = 1 + r.nextDouble() * 5, spread = r.nextDouble() * 0.8;
            double px = ex + v[0] * dist + (r.nextDouble() - 0.5) * 1.6, py = ey - 1.6 + v[1] * dist + (r.nextDouble() - 0.5) * 1.6, pz = ez + v[2] * dist + (r.nextDouble() - 0.5) * 1.6;
            PickMath.Box positions = new PickMath.Box(px, py, pz, px + spread * r.nextDouble(), py + spread * r.nextDouble(), pz + spread * r.nextDouble());
            float w = r.nextBoolean() ? W : (float) (0.2 + r.nextDouble() * 2), h = r.nextBoolean() ? H : (float) (0.2 + r.nextDouble() * 3);
            double range = r.nextInt(4) == 0 ? 2 + r.nextDouble() * 3 : 3.0;
            RayBoxBounds.OuterBound ob = RayBoxBounds.exclude(eye, v, RayBoxBounds.hull(positions, w, h), range);
            if (ob.exclusion() == RayBoxBounds.Exclusion.POSSIBLE) continue;
            excluded++; if (ob.exclusion() == RayBoxBounds.Exclusion.ANGLE) angle++; else reach++;
            for (int s = 0; s < 200; s++) {
                samples++;
                ScenePick.Result c = concrete(sample(r, eye), v, sample(r, positions), w, h, range);
                assertNotEquals(ScenePick.Outcome.HIT, c.outcome(), () -> "exclusion " + ob + " contradicted at case " + "; eye " + eye + " positions " + positions);
            }
        }
        System.out.println("[phase12-geometry] outer exclusions=" + excluded + " angle=" + angle + " reach=" + reach + " concrete samples=" + samples + " contradictions=0");
        assertTrue(excluded > 500, "the generator must exercise exclusions: " + excluded);
        assertTrue(angle > 50 && reach > 50, "both classes exercised: angle " + angle + " reach " + reach);
    }

    @Test void robustWitnessAlwaysCorrespondsToAConcreteHit() {
        Random r = new Random(0x12_1002L); int witnesses = 0, samples = 0;
        for (int i = 0; i < 4_000; i++) {
            double ex = (r.nextDouble() - 0.5) * 2000, ey = 60 + r.nextDouble() * 20, ez = (r.nextDouble() - 0.5) * 2000;
            double eyeSpread = r.nextInt(2) == 0 ? 0 : Math.ulp(ex) * 4;
            PickMath.Box eye = new PickMath.Box(ex, ey, ez, ex + eyeSpread, ey + eyeSpread, ez + eyeSpread);
            float pitch = (float) ((r.nextDouble() - 0.5) * 60), yaw = (float) ((r.nextDouble() - 0.5) * 720);
            double[] v = view(pitch, yaw);
            double dist = 0.8 + r.nextDouble() * 2.6, spread = r.nextDouble() * 0.3;
            double px = ex + v[0] * dist + (r.nextDouble() - 0.5) * 0.4 - spread / 2, py = ey - 1.2 + v[1] * dist + (r.nextDouble() - 0.5) * 0.4, pz = ez + v[2] * dist + (r.nextDouble() - 0.5) * 0.4 - spread / 2;
            PickMath.Box positions = new PickMath.Box(px, py, pz, px + spread, py + spread * r.nextDouble(), pz + spread);
            PickMath.Box core = RayBoxBounds.core(positions, W, H);
            if (core == null) continue;
            double bound = RayBoxBounds.robustHit(eye, v, core);
            if (Double.isNaN(bound) || bound >= 3.0 * (1 - 1e-9)) continue;
            witnesses++;
            for (int s = 0; s < 200; s++) {
                samples++;
                ScenePick.Result c = concrete(sample(r, eye), v, sample(r, positions), W, H, 3.0);
                assertEquals(ScenePick.Outcome.HIT, c.outcome(), () -> "robust witness " + bound + " contradicted: " + c + " eye " + eye + " positions " + positions);
                assertTrue(Math.sqrt(c.hitDistSqr()) <= bound * (1 + 1e-9) + 1e-9, "entry distance above the bound: " + Math.sqrt(c.hitDistSqr()) + " > " + bound);
            }
        }
        System.out.println("[phase12-geometry] robust witnesses=" + witnesses + " concrete samples=" + samples + " contradictions=0");
        assertTrue(witnesses > 500, "the generator must exercise witnesses: " + witnesses);
    }

    @Test void coreAndHullBracketEveryAdmissibleBox() {
        Random r = new Random(0x12_1003L);
        for (int i = 0; i < 20_000; i++) {
            double x = (r.nextDouble() - 0.5) * 6e7, y = (r.nextDouble() - 0.5) * 600, z = (r.nextDouble() - 0.5) * 6e7, s = r.nextDouble();
            PickMath.Box positions = new PickMath.Box(x, y, z, x + s, y + s, z + s);
            float w = (float) (r.nextDouble() * 3), h = (float) (r.nextDouble() * 3);
            PickMath.Box hull = RayBoxBounds.hull(positions, w, h), core = RayBoxBounds.core(positions, w, h);
            for (int k = 0; k < 8; k++) {
                double[] p = sample(r, positions); PickMath.Box b = PickMath.makeBoundingBox(w, h, p[0], p[1], p[2]);
                assertTrue(hull.minX() <= b.minX() && hull.minY() <= b.minY() && hull.minZ() <= b.minZ() && b.maxX() <= hull.maxX() && b.maxY() <= hull.maxY() && b.maxZ() <= hull.maxZ(), "hull must contain every box");
                if (core != null) assertTrue(b.minX() <= core.minX() && b.minY() <= core.minY() && b.minZ() <= core.minZ() && core.maxX() <= b.maxX() && core.maxY() <= b.maxY() && core.maxZ() <= b.maxZ(), "every box must contain the core");
            }
        }
    }
}
