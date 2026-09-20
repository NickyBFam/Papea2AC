package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.BlockShapeTable;
import java.util.Arrays;
/**
 * Immutable block-local collision geometry: an ordered list of axis-aligned boxes stored flat (6 doubles per box:
 * minX minY minZ maxX maxY maxZ) plus the exact vanilla voxel grid (per-axis cell boundaries and a filled bitset).
 * The boxes answer intersection questions; the grid is what {@code VoxelShape.collide} walks, so {@link AxisClip}
 * reproduces vanilla even when a body already penetrates a shape (it clips to internal grid lines). Coordinates are
 * relative to the block's origin and may leave [0,1] (fences/walls are 1.5 high, piston heads reach into the base
 * cell) - see {@link #large()}. Canonical instances: {@link #EMPTY} and {@link #FULL_CUBE}; the resolver interns one
 * instance per table shape index so identical geometry is shared across all states and sessions (task §12).
 * Equality is by content (boxes and grid). Box order is the extractor's canonical (lexicographic) order.
 */
public final class CollisionShape {
    public static final CollisionShape EMPTY = new CollisionShape(new double[0], new double[0], new double[0], new double[0], new long[0]);
    public static final CollisionShape FULL_CUBE = new CollisionShape(new double[] { 0, 0, 0, 1, 1, 1 }, new double[] { 0, 1 }, new double[] { 0, 1 }, new double[] { 0, 1 }, new long[] { 1 });
    private final double[] b, xs, ys, zs; private final long[] filled; private final boolean large, fullCube; private final Aabb bounds;

    private CollisionShape(double[] boxes, double[] xs, double[] ys, double[] zs, long[] filled) {
        this.b = boxes; this.xs = xs; this.ys = ys; this.zs = zs; this.filled = filled;
        boolean l = false;
        for (double v : boxes) if (v < 0.0 || v > 1.0) { l = true; break; }
        large = l;
        fullCube = boxes.length == 6 && boxes[0] == 0 && boxes[1] == 0 && boxes[2] == 0 && boxes[3] == 1 && boxes[4] == 1 && boxes[5] == 1 && xs.length == 2 && ys.length == 2 && zs.length == 2;
        if (boxes.length == 0) bounds = null;
        else {
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int i = 0; i < boxes.length; i += 6) { x0 = Math.min(x0, boxes[i]); y0 = Math.min(y0, boxes[i + 1]); z0 = Math.min(z0, boxes[i + 2]); x1 = Math.max(x1, boxes[i + 3]); y1 = Math.max(y1, boxes[i + 4]); z1 = Math.max(z1, boxes[i + 5]); }
            bounds = new Aabb(x0, y0, z0, x1, y1, z1);
        }
    }
    /** Table shape: boxes plus the extracted exact grid. */
    public static CollisionShape of(double[] boxes, BlockShapeTable.Grid grid) {
        validate(boxes);
        if (boxes.length == 0) return EMPTY;
        if (grid == null) throw new IllegalArgumentException("non-empty shape needs a grid");
        var s = new CollisionShape(boxes.clone(), grid.xs().clone(), grid.ys().clone(), grid.zs().clone(), grid.filled().clone());
        return s.fullCube ? FULL_CUBE : s;
    }
    /** Synthesized shape (tests, envelopes): the grid is derived from the box edges (the merged grid vanilla would build for {@code Shapes.or} of exactly these boxes, without 1/8 sub-cells). */
    public static CollisionShape of(double... boxes) {
        validate(boxes);
        if (boxes.length == 0) return EMPTY;
        double[] xs = edges(boxes, 0), ys = edges(boxes, 1), zs = edges(boxes, 2);
        int nx = xs.length - 1, ny = ys.length - 1, nz = zs.length - 1; long[] filled = new long[(nx * ny * nz + 63) / 64];
        for (int x = 0; x < nx; x++) for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++) {
            double cx = (xs[x] + xs[x + 1]) / 2, cy = (ys[y] + ys[y + 1]) / 2, cz = (zs[z] + zs[z + 1]) / 2;
            for (int i = 0; i < boxes.length; i += 6) if (boxes[i] <= cx && cx < boxes[i + 3] && boxes[i + 1] <= cy && cy < boxes[i + 4] && boxes[i + 2] <= cz && cz < boxes[i + 5]) { int idx = (x * ny + y) * nz + z; filled[idx >>> 6] |= 1L << (idx & 63); break; }
        }
        var s = new CollisionShape(boxes.clone(), xs, ys, zs, filled);
        return s.fullCube ? FULL_CUBE : s;
    }
    public static CollisionShape of(Aabb box) { return of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()); }
    private static void validate(double[] boxes) {
        if (boxes.length % 6 != 0) throw new IllegalArgumentException("box arity");
        for (double v : boxes) if (!Double.isFinite(v)) throw new IllegalArgumentException("non-finite box");
        for (int i = 0; i < boxes.length; i += 6) if (boxes[i] > boxes[i + 3] || boxes[i + 1] > boxes[i + 4] || boxes[i + 2] > boxes[i + 5]) throw new IllegalArgumentException("inverted box");
    }
    private static double[] edges(double[] boxes, int axis) {
        double[] e = new double[boxes.length / 3]; int n = 0;
        for (int i = 0; i < boxes.length; i += 6) { e[n++] = boxes[i + axis]; e[n++] = boxes[i + axis + 3]; }
        Arrays.sort(e); int m = 0;
        for (int i = 0; i < e.length; i++) if (i == 0 || e[i] != e[i - 1]) e[m++] = e[i];
        return Arrays.copyOf(e, m);
    }
    public int boxCount() { return b.length / 6; }
    public boolean isEmpty() { return b.length == 0; }
    /** Exactly one box equal to [0,1]^3 with a single-cell grid - the vanilla {@code Shapes.block()} identity fast path. */
    public boolean isFullCube() { return fullCube; }
    /** Vanilla {@code hasLargeCollisionShape}: some coordinate outside [0,1]; such a shape is also collected from the broad phase's face ring. */
    public boolean large() { return large; }
    public double minX(int box) { return b[box * 6]; }
    public double minY(int box) { return b[box * 6 + 1]; }
    public double minZ(int box) { return b[box * 6 + 2]; }
    public double maxX(int box) { return b[box * 6 + 3]; }
    public double maxY(int box) { return b[box * 6 + 4]; }
    public double maxZ(int box) { return b[box * 6 + 5]; }
    public Aabb box(int box) { return new Aabb(minX(box), minY(box), minZ(box), maxX(box), maxY(box), maxZ(box)); }
    public Aabb box(int box, double ox, double oy, double oz) { return new Aabb(minX(box) + ox, minY(box) + oy, minZ(box) + oz, maxX(box) + ox, maxY(box) + oy, maxZ(box) + oz); }
    // exact grid accessors (AxisClip)
    public int gridSize(int axis) { return (axis == 0 ? xs : axis == 1 ? ys : zs).length - 1; }
    public double gridCoord(int axis, int i) { return (axis == 0 ? xs : axis == 1 ? ys : zs)[i]; }
    public boolean gridFull(int x, int y, int z) { int i = (x * (ys.length - 1) + y) * (zs.length - 1) + z; return (filled[i >>> 6] & (1L << (i & 63))) != 0; }
    /** Bounding box of the filled region (block-local, cached; vanilla {@code VoxelShape.min/max}); EMPTY has none and throws. */
    public Aabb bounds() {
        if (bounds == null) throw new IllegalStateException("empty shape has no bounds");
        return bounds;
    }
    /** Same geometry translated - boxes and grid coordinates alike, exactly like vanilla {@code VoxelShape.move} (the grid is kept, coordinates are offset). Allocates; offset blocks are rare. */
    public CollisionShape translated(double dx, double dy, double dz) {
        if (b.length == 0) return EMPTY;
        double[] c = new double[b.length];
        for (int i = 0; i < b.length; i += 6) { c[i] = b[i] + dx; c[i + 1] = b[i + 1] + dy; c[i + 2] = b[i + 2] + dz; c[i + 3] = b[i + 3] + dx; c[i + 4] = b[i + 4] + dy; c[i + 5] = b[i + 5] + dz; }
        double[] nxs = xs.clone(), nys = ys.clone(), nzs = zs.clone();
        for (int i = 0; i < nxs.length; i++) nxs[i] += dx; for (int i = 0; i < nys.length; i++) nys[i] += dy; for (int i = 0; i < nzs.length; i++) nzs[i] += dz;
        return new CollisionShape(c, nxs, nys, nzs, filled);
    }
    /** Union as a plain box list with an edge-derived grid (no voxel merge): sound as an upper bound, used for the envelope of context-dependent cells. */
    public CollisionShape unionWith(CollisionShape other) {
        if (other.b.length == 0) return this; if (b.length == 0) return other;
        double[] c = Arrays.copyOf(b, b.length + other.b.length); System.arraycopy(other.b, 0, c, b.length, other.b.length);
        return of(c);
    }
    /** Canonical archival/fixture form: boxes as Double.toString values separated by spaces; "" for EMPTY. */
    public String canonical() {
        var sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) { if (i > 0) sb.append(' '); sb.append(b[i]); }
        return sb.toString();
    }
    @Override public boolean equals(Object o) { return o instanceof CollisionShape s && Arrays.equals(b, s.b) && Arrays.equals(xs, s.xs) && Arrays.equals(ys, s.ys) && Arrays.equals(zs, s.zs) && Arrays.equals(filled, s.filled); }
    @Override public int hashCode() { return Arrays.hashCode(b) * 31 + Arrays.hashCode(ys); }
    @Override public String toString() { return "CollisionShape[" + (isEmpty() ? "empty" : fullCube ? "full" : canonical()) + "]"; }
}
