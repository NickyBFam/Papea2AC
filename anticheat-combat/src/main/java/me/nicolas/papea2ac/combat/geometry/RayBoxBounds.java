package me.nicolas.papea2ac.combat.geometry;

/**
 * Sound outer and inner bounds for "a ray from any eye in an eye box, along one exact view vector, meets any box inside a target hull"
 * (PHASE_12 R3 / R4 / R12). Every bound is rounded outward (Math.nextDown / nextUp on each primitive), so:
 *
 * <ul>
 * <li>{@link #exclude} answering {@link Exclusion#ANGLE} or {@link Exclusion#REACH} is a proof that the vanilla client's pick (whose box clip
 *     accepts face crossings up to {@link PickMath#CLIP_EPSILON} outside a face, and whose range test is {@code distance² < range²} on the
 *     rounded clip point) cannot return any box inside the hull for any eye inside the eye box;</li>
 * <li>{@link #robustHit} answering a distance bound is a proof that for every eye in the eye box and every box position in the hull, the ray
 *     passes through the box's interior and enters it no farther than that bound (or starts inside it).</li>
 * </ul>
 *
 * The view vector is exact (the same-step rotation, M3). Positions are boxes (min/max per axis). Nothing here allocates more than its result.
 */
public final class RayBoxBounds {
    private RayBoxBounds() {}

    /** Absolute widening applied to every hull face on top of the client's clip tolerance: covers the rounding of the client's clip parameter and location (|error| far below 1e-9 for |coordinate| ≤ 3.2e7). */
    public static final double ROUNDING_PAD = 1.0E-9;
    /** Relative margin on distances compared with the range (the client's squared-distance rounding is ≤ a few ulps; this is ~1e3 times that). */
    public static final double RELATIVE_MARGIN = 1.0E-12;

    public enum Exclusion { POSSIBLE, ANGLE, REACH }

    /** Result of the outer test: the class and the smallest distance any eye could see the (tolerance-inflated) hull at; NaN when the ray misses. */
    public record OuterBound(Exclusion exclusion, double minDistance) {}

    static double down(double v) { return Math.nextDown(v); }
    static double up(double v) { return Math.nextUp(v); }

    /**
     * Outer test. The ray from eye {@code E} meets point {@code P} of the hull at parameter {@code s ≥ 0} iff {@code s·V ∈ P − E}; over all
     * eyes and hull points that is the Minkowski box {@code D = [hull.min − eye.max, hull.max − eye.min]} (outward). The hull is first widened
     * by the client's clip tolerance and {@link #ROUNDING_PAD}. If D contains the origin an eye may start inside a box: POSSIBLE. Otherwise the
     * slab interval {@code [sIn, sOut]} of the ray from the origin through D is computed outward; an empty interval is ANGLE; an entry whose
     * distance {@code sIn·|V|} is at least the range (with margin) is REACH.
     */
    public static OuterBound exclude(PickMath.Box eye, double[] view, PickMath.Box hull, double range) {
        double tol = PickMath.CLIP_EPSILON + ROUNDING_PAD;
        double[] dMin = {down(down(hull.minX() - tol) - eye.maxX()), down(down(hull.minY() - tol) - eye.maxY()), down(down(hull.minZ() - tol) - eye.maxZ())};
        double[] dMax = {up(up(hull.maxX() + tol) - eye.minX()), up(up(hull.maxY() + tol) - eye.minY()), up(up(hull.maxZ() + tol) - eye.minZ())};
        if (dMin[0] <= 0 && 0 <= dMax[0] && dMin[1] <= 0 && 0 <= dMax[1] && dMin[2] <= 0 && 0 <= dMax[2]) return new OuterBound(Exclusion.POSSIBLE, 0.0);
        double sIn = 0.0, sOut = Double.POSITIVE_INFINITY;
        for (int a = 0; a < 3; a++) {
            double v = view[a];
            if (v == 0.0) { if (dMin[a] > 0 || dMax[a] < 0) return new OuterBound(Exclusion.ANGLE, Double.NaN); continue; }
            double s1, s2;
            if (v > 0) { s1 = down(dMin[a] / v); s2 = up(dMax[a] / v); }
            else { s1 = down(dMax[a] / v); s2 = up(dMin[a] / v); }
            if (s1 > sIn) sIn = s1;
            if (s2 < sOut) sOut = s2;
        }
        if (sIn > sOut || sOut < 0) return new OuterBound(Exclusion.ANGLE, Double.NaN);
        double norm = down(Math.sqrt(down(down(down(view[0] * view[0]) + down(view[1] * view[1])) + down(view[2] * view[2]))));
        double minDistance = down(Math.max(sIn, 0.0) * norm);
        // the client accepts distance² < range²; excluded only when even the smallest possible distance is at or beyond the range with margin
        if (minDistance >= up(range * (1.0 + RELATIVE_MARGIN))) return new OuterBound(Exclusion.REACH, minDistance);
        return new OuterBound(Exclusion.POSSIBLE, minDistance);
    }

    /**
     * Inner test (robust witness). The core box {@code C = [hull.max_placement + box.min ... ]} is supplied by the caller as the intersection of
     * every admissible target box; the ray from every eye in the eye box must pass through C's interior. Using the Minkowski box
     * {@code K = [C.min − eye.min, C.max − eye.max]} shrunk inward by {@link #ROUNDING_PAD} (every eye sees all of K's points), the slab
     * interval of the origin ray through K is computed inward ({@code sIn} rounded up, {@code sOut} rounded down). A non-empty interval with
     * {@code sOut > max(sIn, 0)} proves an interior crossing; the returned value is an upper bound on the entry distance {@code sIn·|V|} into
     * every admissible box (they all contain C). Returns NaN when no robust crossing is proven. A start inside C is distance 0.
     */
    public static double robustHit(PickMath.Box eye, double[] view, PickMath.Box core) {
        double pad = ROUNDING_PAD;
        double[] kMin = {up(up(core.minX() + pad) - eye.minX()), up(up(core.minY() + pad) - eye.minY()), up(up(core.minZ() + pad) - eye.minZ())};
        double[] kMax = {down(down(core.maxX() - pad) - eye.maxX()), down(down(core.maxY() - pad) - eye.maxY()), down(down(core.maxZ() - pad) - eye.maxZ())};
        for (int a = 0; a < 3; a++) if (!(kMin[a] < kMax[a])) return Double.NaN; // the core is thinner than the eye uncertainty
        if (kMin[0] < 0 && 0 < kMax[0] && kMin[1] < 0 && 0 < kMax[1] && kMin[2] < 0 && 0 < kMax[2]) return 0.0; // every eye inside the core
        double sIn = 0.0, sOut = Double.POSITIVE_INFINITY; int entryAxis = -1;
        for (int a = 0; a < 3; a++) {
            double v = view[a];
            if (v == 0.0) { if (!(kMin[a] < 0 && 0 < kMax[a])) return Double.NaN; continue; }
            double s1, s2;
            if (v > 0) { s1 = up(kMin[a] / v); s2 = down(kMax[a] / v); }
            else { s1 = up(kMax[a] / v); s2 = down(kMin[a] / v); }
            if (s1 > sIn) { sIn = s1; entryAxis = a; }
            if (s2 < sOut) sOut = s2;
        }
        if (!(sIn < sOut) || sOut <= 0) return Double.NaN;
        double norm = up(Math.sqrt(up(up(up(view[0] * view[0]) + up(view[1] * view[1])) + up(view[2] * view[2]))));
        double entry = up(sIn * norm);
        // The client only tests a face pair when the segment's component on that axis exceeds 1e-7 (AABB.getDirection bc 0-311). The segment
        // is at least as long as the entry distance (the caller proves no block ends the segment before the entry), so the entry face is
        // tested when |v| * entryDistanceLowerBound > 2e-7 (twice the threshold: a margin for the segment's rounding).
        if (entryAxis >= 0 && !(Math.abs(view[entryAxis]) * down(sIn * down(Math.sqrt(down(down(down(view[0] * view[0]) + down(view[1] * view[1])) + down(view[2] * view[2]))))) > 2 * PickMath.CLIP_EPSILON)) return Double.NaN;
        return entry;
    }

    /**
     * The intersection of the target box over every admissible position: positions in {@code [pMin, pMax]} per axis, box offsets from the
     * position {@code [-w, 0, -w]..[w, h, w]} with {@code w = width/2} as the client computes it ({@link PickMath#makeBoundingBox}).
     * Rounded inward. Null when empty.
     */
    public static PickMath.Box core(PickMath.Box positions, float width, float height) {
        float w = width / 2.0F;
        double minX = up(positions.maxX() - (double) w), minY = positions.maxY(), minZ = up(positions.maxZ() - (double) w);
        double maxX = down(positions.minX() + (double) w), maxY = down(positions.minY() + (double) height), maxZ = down(positions.minZ() + (double) w);
        if (!(minX < maxX && minY < maxY && minZ < maxZ)) return null;
        return new PickMath.Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** The union of the target box over every admissible position, rounded outward. */
    public static PickMath.Box hull(PickMath.Box positions, float width, float height) {
        float w = width / 2.0F;
        return new PickMath.Box(down(positions.minX() - (double) w), positions.minY(), down(positions.minZ() - (double) w),
            up(positions.maxX() + (double) w), up(positions.maxY() + (double) height), up(positions.maxZ() + (double) w));
    }
}
