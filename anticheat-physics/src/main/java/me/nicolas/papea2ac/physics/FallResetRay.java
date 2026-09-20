package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;

/**
 * Vanilla {@code Entity.move}'s fall-distance-resetting ray: {@code level.clip(new ClipContext(position, position +
 * collided.normalize() * min(|collided|, 8), FALLDAMAGE_RESETTING, WATER))} - a POINT ray walked with
 * {@code BlockGetter.traverseBlocks} (start cell first, then a DDA); a cell counts when it carries the
 * {@code fall_damage_resetting} tag (its shape is the full block, so entering the cell through a face is a hit) or when
 * the ray crosses a water column's fluid box ({@code FlowingFluid.getShape}: height 1 when the same fluid is above,
 * else the own height). Anything but a MISS resets the fall distance. Unknown cells are recorded and treated as no hit.
 */
final class FallResetRay {
    private FallResetRay() {}
    static boolean hits(MovementGeometry geo, Uncertainty u, Vec3d from, Vec3d to) {
        if (from.equals(to)) return false;
        double d = lerp(-1.0E-7, to.x(), from.x()), e = lerp(-1.0E-7, to.y(), from.y()), f = lerp(-1.0E-7, to.z(), from.z());
        double g = lerp(-1.0E-7, from.x(), to.x()), h = lerp(-1.0E-7, from.y(), to.y()), i = lerp(-1.0E-7, from.z(), to.z());
        int j = floor(g), k = floor(h), l = floor(i);
        if (test(geo, u, j, k, l, from, to)) return true;
        double m = d - g, n = e - h, o = f - i;
        int p = sign(m), q = sign(n), r = sign(o);
        double s = p == 0 ? Double.MAX_VALUE : (double) p / m, t = q == 0 ? Double.MAX_VALUE : (double) q / n, uu = r == 0 ? Double.MAX_VALUE : (double) r / o;
        double v = s * (p > 0 ? 1.0 - frac(g) : frac(g)), w = t * (q > 0 ? 1.0 - frac(h) : frac(h)), x = uu * (r > 0 ? 1.0 - frac(i) : frac(i));
        int guard = 0;
        while (v <= 1.0 || w <= 1.0 || x <= 1.0) {
            if (v < w) { if (v < x) { j += p; v += s; } else { l += r; x += uu; } }
            else if (w < x) { k += q; w += t; } else { l += r; x += uu; }
            if (test(geo, u, j, k, l, from, to)) return true;
            if (++guard > 64) { u.add(PhysicsResult.Reason.Code.QUERY_REJECTED, "fall-reset ray traversal exceeded 64 cells"); return false; }
        }
        return false;
    }
    /** The {@code Level.clip} tester for one cell under FALLDAMAGE_RESETTING / WATER: block hit or fluid hit. */
    private static boolean test(MovementGeometry geo, Uncertainty u, int x, int y, int z, Vec3d from, Vec3d to) {
        BlockBehaviorQuery.Behavior b = geo.behavior(x, y, z);
        if (b.status() == BlockBehaviorQuery.Status.UNKNOWN) { if (b.worldStatus() != me.nicolas.papea2ac.world.BlockLookup.Status.OUTSIDE_DIMENSION) u.add(PhysicsResult.Reason.Code.CELL_UNKNOWN, "fall-reset ray: " + b.worldStatus() + " at " + x + "," + y + "," + z); return false; }
        if (!b.known()) { u.add(PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, "fall-reset ray: unsupported state at " + x + "," + y + "," + z); return false; }
        if (b.block().fallDamageResetting() && InsideBlockSampler.clip(x, y, z, x + 1, y + 1, z + 1, from, to) != null) return true;
        if (b.hasFluid() && b.fluid().water()) {
            BlockBehaviorQuery.Behavior above = geo.behavior(x, y + 1, z);
            float height = above.known() && above.hasFluid() && b.fluid().sameFluid(above.fluid()) ? 1.0f : b.fluid().ownHeight();
            if (InsideBlockSampler.clip(x, y, z, x + 1, y + (double) height, z + 1, from, to) != null) return true;
        }
        return false;
    }
    private static double lerp(double delta, double start, double end) { return start + delta * (end - start); }
    private static int floor(double d) { return (int) Math.floor(d); }
    private static int sign(double d) { return d == 0.0 ? 0 : d > 0.0 ? 1 : -1; }
    private static double frac(double d) { return d - (double) (long) Math.floor(d); }
}
