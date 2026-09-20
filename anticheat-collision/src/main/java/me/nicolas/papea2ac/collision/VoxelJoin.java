package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.CollisionProfile;
/**
 * Exact emulation of the vanilla client's candidate acceptance for a non-singleton block shape:
 * {@code Shapes.joinIsNotEmpty(shape.move(cell), Shapes.create(queryBox), BooleanOp.AND)}. This is NOT an
 * "overlap > epsilon" approximation: vanilla merges the two coordinate lists per axis ({@code IndirectMerger}),
 * collapsing coordinates closer than {@code Shapes.EPSILON} in a specific order, and only then asks whether some
 * merged cell is filled in both shapes. The query box is first discretized exactly like {@code Shapes.create}:
 * a box thinner than EPSILON on any axis is the empty shape; a box whose six coordinates are all 1/8-aligned inside
 * the unit cube becomes a cube grid; anything else is a single cell with its own two coordinates per axis.
 * Whether a shape is collected at all decides whether the clip primitive later sees it, so this must be exact
 * (verified against the untouched vanilla merger code path in {@code NativeCollisionKernelTest}).
 */
final class VoxelJoin {
    private VoxelJoin() {}
    private static final double EPS = CollisionProfile.SHAPE_EPSILON;

    static boolean intersects(CollisionShape s, double ox, double oy, double oz, Aabb q) {
        // Shapes.create(query): thinner than EPSILON on an axis -> empty shape -> AND with anything is false
        if (q.maxX() - q.minX() < EPS || q.maxY() - q.minY() < EPS || q.maxZ() - q.minZ() < EPS) return false;
        int bx = findBits(q.minX(), q.maxX()), by = findBits(q.minY(), q.maxY()), bz = findBits(q.minZ(), q.maxZ());
        boolean cube = bx >= 0 && by >= 0 && bz >= 0; // all three axes 1/8-aligned inside [0,1]: a discretized cube grid over the whole unit cube
        double[] qx, qy, qz; int qx0, qx1, qy0, qy1, qz0, qz1; // box coordinate lists and filled cell ranges [q?0, q?1)
        if (cube) {
            int lx = 1 << bx, ly = 1 << by, lz = 1 << bz;
            qx = range(lx); qy = range(ly); qz = range(lz);
            qx0 = (int) Math.round(q.minX() * lx); qx1 = (int) Math.round(q.maxX() * lx); qy0 = (int) Math.round(q.minY() * ly); qy1 = (int) Math.round(q.maxY() * ly); qz0 = (int) Math.round(q.minZ() * lz); qz1 = (int) Math.round(q.maxZ() * lz);
        } else {
            qx = new double[] { q.minX(), q.maxX() }; qy = new double[] { q.minY(), q.maxY() }; qz = new double[] { q.minZ(), q.maxZ() };
            qx0 = 0; qx1 = 1; qy0 = 0; qy1 = 1; qz0 = 0; qz1 = 1;
        }
        // public joinIsNotEmpty prelude: separated by more than EPSILON on some axis (filled bounds, not grid bounds) -> false
        Aabb b = s.bounds();
        if (b.maxX() + ox < qx[qx0] - EPS || qx[qx1] < b.minX() + ox - EPS) return false;
        if (b.maxY() + oy < qy[qy0] - EPS || qy[qy1] < b.minY() + oy - EPS) return false;
        if (b.maxZ() + oz < qz[qz0] - EPS || qz[qz1] < b.minZ() + oz - EPS) return false;
        Merged mx = merge(s, 0, ox, qx), my = merge(s, 1, oy, qy), mz = merge(s, 2, oz, qz);
        int nx = s.gridSize(0), ny = s.gridSize(1), nz = s.gridSize(2);
        for (int i = 0; i < mx.length; i++) {
            int x1 = mx.first[i], x2 = mx.second[i]; if (x1 < 0 || x1 >= nx || x2 < qx0 || x2 >= qx1) continue;
            for (int j = 0; j < my.length; j++) {
                int y1 = my.first[j], y2 = my.second[j]; if (y1 < 0 || y1 >= ny || y2 < qy0 || y2 >= qy1) continue;
                for (int k = 0; k < mz.length; k++) {
                    int z1 = mz.first[k], z2 = mz.second[k]; if (z1 < 0 || z1 >= nz || z2 < qz0 || z2 >= qz1) continue;
                    if (s.gridFull(x1, y1, z1)) return true;
                }
            }
        }
        return false;
    }
    private static double[] range(int parts) { double[] r = new double[parts + 1]; for (int i = 0; i <= parts; i++) r[i] = (double) i / parts; return r; }
    /** Vanilla {@code Shapes.findBits}: the smallest 2^i grid (i <= 3) on which both coordinates sit within EPSILON*2^i, or -1. */
    static int findBits(double min, double max) {
        if (min < -EPS || max > 1.0000001) return -1;
        for (int i = 0; i <= 3; i++) {
            int j = 1 << i; double d = min * j, e = max * j;
            boolean b1 = Math.abs(d - (double) Math.round(d)) < EPS * (double) j, b2 = Math.abs(e - (double) Math.round(e)) < EPS * (double) j;
            if (b1 && b2) return i;
        }
        return -1;
    }
    /** Merged index lists for one axis ({@code first}: shape cell index, {@code second}: box cell index, {@code length} = usable intervals = resultLength - 1). */
    static final class Merged { final int[] first, second; final int length; Merged(int[] f, int[] s, int len) { first = f; second = s; length = len; } }
    /** Vanilla {@code createIndexMerger(list1 = shape coords + offset, list2 = box coords, excludeUpper = false, excludeLower = false)} for BooleanOp.AND. */
    static Merged merge(CollisionShape s, int axis, double offset, double[] box) {
        int n1 = s.gridSize(axis) + 1, n2 = box.length;
        // non-overlapping lists: no merged cell can be inside both
        if (s.gridCoord(axis, n1 - 1) + offset < box[0] - EPS || box[n2 - 1] < s.gridCoord(axis, 0) + offset - EPS) return new Merged(new int[0], new int[0], 0);
        if (n1 == n2) { // IdenticalMerger: element-wise equal lists
            boolean same = true; for (int i = 0; i < n1 && same; i++) same = s.gridCoord(axis, i) + offset == box[i];
            if (same) { int[] idx = new int[n1 - 1]; for (int i = 0; i < idx.length; i++) idx[i] = i; return new Merged(idx, idx, n1 - 1); }
        }
        // IndirectMerger(list1, list2, false, false)
        int cap = n1 + n2; int[] first = new int[cap], second = new int[cap]; double d = Double.NaN; int k = 0, m = 0, n = 0;
        while (true) {
            boolean bl4 = m >= n1, bl5 = n >= n2;
            if (bl4 && bl5) break;
            boolean bl3 = !bl4 && (bl5 || s.gridCoord(axis, m) + offset < box[n] + EPS);
            if (bl3) { m++; if (n == 0 || bl5) continue; } else { n++; if (m == 0 || bl4) continue; }
            int o = m - 1, p = n - 1; double e = bl3 ? s.gridCoord(axis, o) + offset : box[p];
            if (!(d >= e - EPS)) { first[k] = o; second[k] = p; k++; d = e; } else { first[k - 1] = o; second[k - 1] = p; }
        }
        return new Merged(first, second, Math.max(1, k) - 1);
    }
}
