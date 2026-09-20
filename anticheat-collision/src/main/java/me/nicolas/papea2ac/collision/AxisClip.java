package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.CollisionProfile;
/**
 * Single-axis clipping primitive (ARCHITECTURE.md: collision owns "shapes, clipping"; COLLISION_ENGINE.md assigns the
 * narrow-phase clip to this module). This is vanilla {@code Shapes.collide(axis, box, shapes, desired)} /
 * {@code VoxelShape.collideX} reproduced over the shape's exact voxel grid: index probes at {@code min + EPSILON} /
 * {@code max - EPSILON}, the first filled column beyond the body in the direction of travel decides, and a contact is
 * accepted when {@code d >= -EPSILON} (forward) / {@code d <= EPSILON} (backward), so a body may end up to EPSILON
 * inside a face, exactly as the client does. Because the grid (not the merged box list) is walked, a body that already
 * penetrates a shape clips to the shape's internal grid lines like vanilla.
 *
 * Deliberately NOT here (Phase 5): the per-axis evaluation ORDER of a movement step, step-up, the sweep over
 * multiple steps, and any velocity/gravity/friction. Phase 5 composes these primitives under the version's rules;
 * "Do not invent a universal XYZ ordering" (COLLISION_ENGINE.md).
 */
public final class AxisClip {
    public enum Axis { X, Y, Z }
    private AxisClip() {}
    private static final double EPS = CollisionProfile.SHAPE_EPSILON;

    /** Vanilla {@code Shapes.collide} over the DEFINITE hits of a result (possible-only envelope hits are not colliders). */
    public static double clip(Axis axis, Aabb box, double desired, CollisionResult result) {
        for (CollisionResult.Hit h : result.hits()) {
            if (!h.definite()) continue;
            if (Math.abs(desired) < EPS) return 0.0;
            desired = clip(axis, box, desired, h.shape(), h.originX(), h.originY(), h.originZ());
        }
        return desired;
    }
    /** Vanilla {@code VoxelShape.collide(axis, box, desired)} against one shape placed at (ox, oy, oz). */
    public static double clip(Axis axis, Aabb box, double desired, CollisionShape shape, double ox, double oy, double oz) {
        if (shape.isEmpty()) return desired;
        if (Math.abs(desired) < EPS) return 0.0;
        int a = axis.ordinal(), aY = (a + 1) % 3, aZ = (a + 2) % 3; // roles of the two other axes only affect iteration order, never the result
        double oA = offset(a, ox, oy, oz), oY = offset(aY, ox, oy, oz), oZ = offset(aZ, ox, oy, oz);
        double maxA = max(box, a), minA = min(box, a);
        int i = findIndex(shape, a, oA, minA + EPS), j = findIndex(shape, a, oA, maxA - EPS);
        int k = Math.max(0, findIndex(shape, aY, oY, min(box, aY) + EPS)), l = Math.min(shape.gridSize(aY), findIndex(shape, aY, oY, max(box, aY) - EPS) + 1);
        int m = Math.max(0, findIndex(shape, aZ, oZ, min(box, aZ) + EPS)), n = Math.min(shape.gridSize(aZ), findIndex(shape, aZ, oZ, max(box, aZ) - EPS) + 1);
        int o = shape.gridSize(a);
        if (desired > 0.0) {
            for (int p = j + 1; p < o; p++) for (int q = k; q < l; q++) for (int r = m; r < n; r++) if (full(shape, a, p, q, r)) {
                double d = (shape.gridCoord(a, p) + oA) - maxA;
                if (d >= -EPS) desired = Math.min(desired, d);
                return desired;
            }
        } else if (desired < 0.0) {
            for (int p = i - 1; p >= 0; p--) for (int q = k; q < l; q++) for (int r = m; r < n; r++) if (full(shape, a, p, q, r)) {
                double d = (shape.gridCoord(a, p + 1) + oA) - minA;
                if (d <= EPS) desired = Math.max(desired, d);
                return desired;
            }
        }
        return desired;
    }
    /** Vanilla {@code VoxelShape.findIndex}: largest cell-boundary index whose (offset) coordinate is <= value, in [-1, size]. */
    static int findIndex(CollisionShape shape, int axis, double offset, double value) {
        int lo = 0, hi = shape.gridSize(axis) + 1;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (value < shape.gridCoord(axis, mid) + offset) hi = mid; else lo = mid + 1; }
        return lo - 1;
    }
    /** isFullWide under the axis cycle: (p, q, r) are indices along (moving axis, next axis, next-next axis). */
    private static boolean full(CollisionShape shape, int a, int p, int q, int r) {
        return switch (a) { case 0 -> shape.gridFull(p, q, r); case 1 -> shape.gridFull(r, p, q); default -> shape.gridFull(q, r, p); };
    }
    private static double offset(int axis, double ox, double oy, double oz) { return axis == 0 ? ox : axis == 1 ? oy : oz; }
    private static double min(Aabb b, int axis) { return axis == 0 ? b.minX() : axis == 1 ? b.minY() : b.minZ(); }
    private static double max(Aabb b, int axis) { return axis == 0 ? b.maxX() : axis == 1 ? b.maxY() : b.maxZ(); }
}
