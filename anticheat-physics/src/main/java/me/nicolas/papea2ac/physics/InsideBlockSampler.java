package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.collision.BlockShapeResolver;
import me.nicolas.papea2ac.collision.CollisionShape;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.collision.ResolvedShape;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;

/**
 * Vanilla {@code Entity.applyEffectsFromBlocks} / {@code checkInsideBlocks} / {@code BlockGetter.forEachBlockIntersectedBetween}
 * for one tick's movement, reproduced cell for cell (WORLD_MODEL.md W3: "Sampling of support faces, fluid volume and
 * inside-body cells is version-defined; inside sampling is separately bounded to 512 cells/step"). The movement segment is
 * decomposed per axis in {@code axisStepOrder} of the ORIGINAL (pre-collision) movement; each sub-segment samples the
 * destination body box deflated by {@code 1.0E-5F}: for tiny moves every cell of that box, otherwise the start-box corner
 * cells, a DDA walk of the leading corner ray with per-cell corner spans, then the end-box cells; every non-air cell
 * whose inside shape is the full block (or which the body's centre ray crosses) receives {@code entityInside} once. The
 * motion-relevant effects the profile classifies (cobweb / berry bush / powder snow stuck multipliers, honey wall slide)
 * are applied here in visit order; bubble columns, portals and unclassified overrides are reported.
 */
final class InsideBlockSampler {
    private final PhysicsEnvironment env; private final MovementGeometry geo; private final Uncertainty u;
    private final PlayerBox.Pose pose; private final float scale; private final boolean weaving, canWalkOnPowderSnow, shift, onGround;
    private final ShapeContext ctx;
    private final LongList effectVisited = new LongList();
    // outputs
    Vec3d velocity; Vec3d stuck; boolean resetFall, inPowderSnow;
    private int budget; private Vec3d position;

    InsideBlockSampler(PhysicsEnvironment env, MovementGeometry geo, Uncertainty u, PlayerBox.Pose pose, float scale, boolean weaving, boolean canWalkOnPowderSnow, boolean shift, boolean onGround, ShapeContext ctx, Vec3d velocity) {
        this.env = env; this.geo = geo; this.u = u; this.pose = pose; this.scale = scale; this.weaving = weaving; this.canWalkOnPowderSnow = canWalkOnPowderSnow; this.shift = shift; this.onGround = onGround; this.ctx = ctx; this.velocity = velocity;
        this.budget = env.budget().maxInsideCells();
    }

    /** {@code checkInsideBlocks(List<Movement>)} for the single movement of this tick; {@code original} is the pre-collision movement (null when the move recorded no segment). */
    void run(Vec3d from, Vec3d to, Vec3d original) {
        position = to; // the entity position while effects run is the post-move position, whatever sub-segment is being sampled
        Vec3d delta = to.subtract(from);
        int remaining = 16;
        if (original != null && delta.lengthSqr() > 0.0) {
            Vec3d f = from;
            for (int axis : original.axisStepOrder()) {
                double c = delta.get(axis);
                if (c != 0.0) { Vec3d next = f.relativePositive(axis, c); remaining -= segment(f, next, remaining); f = next; }
            }
        } else remaining -= segment(from, to, 16);
        if (remaining <= 0) segment(to, to, 1);
    }

    private Aabb box(Vec3d at) { return PlayerBox.of(env.profile().collision(), pose, scale, at.x(), at.y(), at.z()); }

    /** {@code Entity.checkInsideBlocks(from, to, collector, visited, maxSteps)}: returns lastStep + 1. */
    private int segment(Vec3d from, Vec3d to, int maxSteps) {
        Aabb box = box(to).inflate(-MovementProfile.INSIDE_SAMPLE_DEFLATE);
        boolean longMove = from.distanceToSqr(to) > MovementProfile.INSIDE_LONG_MOVE * MovementProfile.INSIDE_LONG_MOVE;
        int[] last = { 0 };
        forEachBlockIntersectedBetween(from, to, box, (x, y, z, step) -> {
            if (step >= maxSteps) return false;
            last[0] = step;
            return visit(x, y, z, step, from, to, box, longMove);
        });
        return last[0] + 1;
    }
    /** Cell visitor of the vanilla traversal ({@code BlockGetter.BlockStepVisitor}). */
    public interface Visitor { boolean visit(int x, int y, int z, int step); }

    /** The {@code checkInsideBlocks} visitor lambda. */
    private boolean visit(int x, int y, int z, int step, Vec3d from, Vec3d to, Aabb box, boolean longMove) {
        if (budget-- <= 0) { u.add(PhysicsResult.Reason.Code.INSIDE_SAMPLING_BUDGET, "inside sampling exceeded " + env.budget().maxInsideCells() + " cells"); return false; }
        geo.insideCells++;
        BlockBehaviorQuery.Behavior b = geo.behavior(x, y, z);
        if (b.status() == BlockBehaviorQuery.Status.UNKNOWN) { u.add(PhysicsResult.Reason.Code.CELL_UNKNOWN, "inside sampling: " + b.worldStatus() + " at " + x + "," + y + "," + z); return true; }
        if (b.status() == BlockBehaviorQuery.Status.UNSUPPORTED) { u.add(PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, "unsupported state at " + x + "," + y + "," + z); return true; }
        if (b.air()) return true;
        BlockMovementTable.Block block = b.block();
        boolean inside;
        if (!block.insideShapeOverride()) inside = true; // getEntityInsideCollisionShape = Shapes.block()
        else if (block.insideEffect() == BlockMovementTable.InsideEffect.POWDER_SNOW) {
            // PowderSnowBlock: the entity's own collision shape, or the full block when that is empty
            BlockShapeResolver.Entry e = env.collision().resolver().entry(b.stateId());
            ResolvedShape r = env.collision().resolver().resolve(e, x, y, z, ctx);
            if (!r.status().exact()) { u.add(PhysicsResult.Reason.Code.INSIDE_SHAPE_UNRESOLVED, "powder snow inside shape " + r.status() + " at " + x + "," + y + "," + z); inside = true; }
            else if (r.lower().isEmpty()) inside = true;
            else inside = collidedWithShapeMovingFrom(from, to, r.lower(), x, y, z);
        } else if (block.insideEffect() == BlockMovementTable.InsideEffect.NON_MOTION) inside = false; // cauldrons, tripwire: no motion consequence either way
        else { u.add(PhysicsResult.Reason.Code.INSIDE_SHAPE_UNRESOLVED, block.name() + " inside shape override at " + x + "," + y + "," + z); inside = true; }
        boolean fluid = b.hasFluid() && collidedWithFluid(b, x, y, z, from, to);
        if (!inside && !fluid) return true;
        if (!effectVisited.add(key(x, y, z))) return true;
        if (inside) applyInside(block, x, y, z, position, longMove ? box.intersects(x, y, z, x + 1, y + 1, z + 1) : true);
        return true;
    }
    /** The motion-relevant part of {@code BlockState.entityInside} for the classified vanilla classes. */
    private void applyInside(BlockMovementTable.Block block, int x, int y, int z, Vec3d position, boolean endBoxIntersects) {
        switch (block.insideEffect()) {
            case NONE, NON_MOTION -> { }
            case WEB -> makeStuck(weaving ? new Vec3d(MovementProfile.WEB_WEAVING_XZ, MovementProfile.WEB_WEAVING_Y, MovementProfile.WEB_WEAVING_XZ) : new Vec3d(MovementProfile.WEB_STUCK_XZ, MovementProfile.WEB_STUCK_Y, MovementProfile.WEB_STUCK_XZ));
            case SWEET_BERRY_BUSH -> makeStuck(new Vec3d(MovementProfile.BERRY_STUCK_XZ, MovementProfile.BERRY_STUCK_Y, MovementProfile.BERRY_STUCK_XZ));
            case POWDER_SNOW -> {
                BlockBehaviorQuery.Behavior in = geo.behavior((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()));
                if (in.status() == BlockBehaviorQuery.Status.UNKNOWN) u.add(PhysicsResult.Reason.Code.CELL_UNKNOWN, "powder snow: own block position " + in.worldStatus());
                else if (in.known() && in.block().insideEffect() == BlockMovementTable.InsideEffect.POWDER_SNOW) makeStuck(new Vec3d(MovementProfile.POWDER_SNOW_STUCK_XZ, MovementProfile.POWDER_SNOW_STUCK_Y, MovementProfile.POWDER_SNOW_STUCK_XZ));
                inPowderSnow = true; // InsideBlockEffectType.FREEZE
            }
            case HONEY_SLIDE -> { if (isSlidingDown(x, y, z, position)) { doSlideMovement(); resetFall = true; } }
            case BUBBLE_COLUMN -> u.add(PhysicsResult.Reason.Code.BUBBLE_COLUMN, "bubble column at " + x + "," + y + "," + z + (endBoxIntersects ? "" : " (swept only)"));
            case PORTAL -> u.add(PhysicsResult.Reason.Code.PORTAL_CONTACT, block.name() + " at " + x + "," + y + "," + z);
            case UNSUPPORTED -> u.add(PhysicsResult.Reason.Code.BLOCK_BEHAVIOUR_UNCLASSIFIED, block.name() + " entityInside class " + block.insideClass());
        }
    }
    private void makeStuck(Vec3d v) { resetFall = true; stuck = v; }
    /** {@code HoneyBlock.isSlidingDown}. */
    private boolean isSlidingDown(int bx, int by, int bz, Vec3d p) {
        if (onGround) return false;
        if (p.y() > (double) by + MovementProfile.HONEY_TOP - 1.0E-7) return false;
        if (oldDeltaY(velocity.y()) >= MovementProfile.HONEY_SLIDE_DY) return false;
        double d = Math.abs((double) bx + 0.5 - p.x()), e = Math.abs((double) bz + 0.5 - p.z());
        float width = PlayerBox.dimensions(env.profile().collision(), pose)[0] * scale;
        double f = MovementProfile.HONEY_REACH + (double) (width / 2.0f);
        return d + 1.0E-7 > f || e + 1.0E-7 > f;
    }
    /** {@code HoneyBlock.doSlideMovement}. */
    private void doSlideMovement() {
        Vec3d v = velocity;
        if (oldDeltaY(v.y()) < MovementProfile.HONEY_FAST_DY) { double d = MovementProfile.HONEY_TARGET_DY / oldDeltaY(v.y()); velocity = new Vec3d(v.x() * d, newDeltaY(MovementProfile.HONEY_TARGET_DY), v.z() * d); }
        else velocity = new Vec3d(v.x(), newDeltaY(MovementProfile.HONEY_TARGET_DY), v.z());
    }
    private static double oldDeltaY(double d) { return d / MovementProfile.HONEY_DRAG + MovementProfile.HONEY_GRAVITY; }
    private static double newDeltaY(double d) { return (d - MovementProfile.HONEY_GRAVITY) * MovementProfile.HONEY_DRAG; }

    /** {@code Entity.collidedWithFluid}: the fluid column box {@code (x, y, z) .. (x+1, y + height, z+1)}, height 1 when the same fluid is above. */
    private boolean collidedWithFluid(BlockBehaviorQuery.Behavior b, int x, int y, int z, Vec3d from, Vec3d to) {
        BlockBehaviorQuery.Behavior above = geo.behavior(x, y + 1, z);
        float h = above.known() && above.hasFluid() && b.fluid().sameFluid(above.fluid()) ? 1.0f : b.fluid().ownHeight();
        return collidedAlongVector(box(from), to.subtract(from), new Aabb(x, y, z, x + 1, y + (double) h, z + 1));
    }
    private boolean collidedWithShapeMovingFrom(Vec3d from, Vec3d to, CollisionShape shape, int x, int y, int z) {
        Aabb body = box(from); Vec3d v = to.subtract(from);
        for (int i = 0; i < shape.boxCount(); i++) if (collidedAlongVector(body, v, shape.box(i, x, y, z))) return true;
        return false;
    }
    /** {@code AABB.collidedAlongVector(vec, [box])} for one box. */
    private static boolean collidedAlongVector(Aabb body, Vec3d vec, Aabb b) {
        Vec3d center = center(body), end = center.add(vec);
        Aabb inflated = inflate(b, body.sizeX() * 0.5 - 1.0E-7, body.sizeY() * 0.5 - 1.0E-7, body.sizeZ() * 0.5 - 1.0E-7);
        if (inflated.contains(end.x(), end.y(), end.z()) || inflated.contains(center.x(), center.y(), center.z())) return true;
        return clip(inflated.minX(), inflated.minY(), inflated.minZ(), inflated.maxX(), inflated.maxY(), inflated.maxZ(), center, end) != null;
    }
    private static Vec3d center(Aabb b) { return new Vec3d(b.minX() + 0.5 * (b.maxX() - b.minX()), b.minY() + 0.5 * (b.maxY() - b.minY()), b.minZ() + 0.5 * (b.maxZ() - b.minZ())); }
    private static Aabb inflate(Aabb b, double x, double y, double z) { return new Aabb(b.minX() - x, b.minY() - y, b.minZ() - z, b.maxX() + x, b.maxY() + y, b.maxZ() + z); }

    // ---- BlockGetter.forEachBlockIntersectedBetween and its DDA
    static void forEachBlockIntersectedBetween(Vec3d from, Vec3d to, Aabb box, Visitor visitor) {
        Vec3d movement = to.subtract(from);
        if (movement.lengthSqr() < MovementProfile.INSIDE_TINY_MOVE_SQR) {
            int x0 = floor(box.minX()), y0 = floor(box.minY()), z0 = floor(box.minZ()), x1 = floor(box.maxX()), y1 = floor(box.maxY()), z1 = floor(box.maxZ());
            for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (!visitor.visit(x, y, z, 0)) return; // BlockPos.betweenClosed: x fastest
            return;
        }
        LongList visited = new LongList();
        Aabb start = box.move(-movement.x(), -movement.y(), -movement.z());
        if (!corners(floor(start.minX()), floor(start.minY()), floor(start.minZ()), floor(start.maxX()), floor(start.maxY()), floor(start.maxZ()), movement, visited, false, 0, visitor)) return;
        int steps = alongTravel(visited, movement, box, visitor);
        if (steps < 0) return;
        corners(floor(box.minX()), floor(box.minY()), floor(box.minZ()), floor(box.maxX()), floor(box.maxY()), floor(box.maxZ()), movement, visited, true, steps + 1, visitor);
    }
    /** {@code BlockPos.betweenCornersInDirection}: iterate the cell box from the corner the movement points away from, axes in {@code axisStepOrder} (first slowest). */
    private static boolean corners(int ax, int ay, int az, int bx, int by, int bz, Vec3d dir, LongList visited, boolean onlyNew, int step, Visitor visitor) {
        int minX = Math.min(ax, bx), minY = Math.min(ay, by), minZ = Math.min(az, bz), maxX = Math.max(ax, bx), maxY = Math.max(ay, by), maxZ = Math.max(az, bz);
        int[] start = { dir.x() >= 0 ? minX : maxX, dir.y() >= 0 ? minY : maxY, dir.z() >= 0 ? minZ : maxZ };
        int[] span = { maxX - minX, maxY - minY, maxZ - minZ };
        int[] order = dir.axisStepOrder();
        int[] sign = { dir.x() >= 0 ? 1 : -1, dir.y() >= 0 ? 1 : -1, dir.z() >= 0 ? 1 : -1 };
        int a0 = order[0], a1 = order[1], a2 = order[2];
        int[] p = new int[3];
        for (int i = 0; i <= span[a0]; i++) for (int j = 0; j <= span[a1]; j++) for (int k = 0; k <= span[a2]; k++) {
            p[a0] = start[a0] + sign[a0] * i; p[a1] = start[a1] + sign[a1] * j; p[a2] = start[a2] + sign[a2] * k;
            long key = key(p[0], p[1], p[2]);
            if (onlyNew) { if (!visited.add(key)) continue; }
            if (!visitor.visit(p[0], p[1], p[2], step)) return false;
            if (!onlyNew) visited.add(key);
        }
        return true;
    }
    /** {@code BlockGetter.addCollisionsAlongTravel}. */
    private static int alongTravel(LongList visited, Vec3d movement, Aabb box, Visitor visitor) {
        double sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        int[] corner = furthestCorner(movement);
        Vec3d center = center(box);
        Vec3d end = new Vec3d(center.x() + sx * 0.5 * (double) corner[0], center.y() + sy * 0.5 * (double) corner[1], center.z() + sz * 0.5 * (double) corner[2]);
        Vec3d start = end.subtract(movement);
        int ix = floor(start.x()), iy = floor(start.y()), iz = floor(start.z());
        int gx = sign(movement.x()), gy = sign(movement.y()), gz = sign(movement.z());
        double tdx = gx == 0 ? Double.MAX_VALUE : (double) gx / movement.x(), tdy = gy == 0 ? Double.MAX_VALUE : (double) gy / movement.y(), tdz = gz == 0 ? Double.MAX_VALUE : (double) gz / movement.z();
        double tx = tdx * (gx > 0 ? 1.0 - frac(start.x()) : frac(start.x())), ty = tdy * (gy > 0 ? 1.0 - frac(start.y()) : frac(start.y())), tz = tdz * (gz > 0 ? 1.0 - frac(start.z()) : frac(start.z()));
        int steps = 0;
        while (tx <= 1.0 || ty <= 1.0 || tz <= 1.0) {
            if (tx < ty) { if (tx < tz) { ix += gx; tx += tdx; } else { iz += gz; tz += tdz; } }
            else if (ty < tz) { iy += gy; ty += tdy; } else { iz += gz; tz += tdz; }
            Vec3d hit = clip(ix, iy, iz, ix + 1, iy + 1, iz + 1, start, end);
            if (hit == null) continue;
            steps++;
            double cx = clamp(hit.x(), (double) ((float) ix + 1.0E-5f), (double) ix + 1.0 - 9.999999747378752E-6);
            double cy = clamp(hit.y(), (double) ((float) iy + 1.0E-5f), (double) iy + 1.0 - 9.999999747378752E-6);
            double cz = clamp(hit.z(), (double) ((float) iz + 1.0E-5f), (double) iz + 1.0 - 9.999999747378752E-6);
            int ox = floor(cx - sx * (double) corner[0]), oy = floor(cy - sy * (double) corner[1]), oz = floor(cz - sz * (double) corner[2]);
            if (!corners(ix, iy, iz, ox, oy, oz, movement, visited, true, steps, visitor)) return -1;
        }
        return steps;
    }
    /** {@code BlockGetter.getFurthestCorner} (component permutation reproduced as compiled). */
    private static int[] furthestCorner(Vec3d v) {
        double dx = Math.abs(v.dot(new Vec3d(1, 0, 0))), dy = Math.abs(v.dot(new Vec3d(0, 1, 0))), dz = Math.abs(v.dot(new Vec3d(0, 0, 1)));
        int sx = v.x() >= 0 ? 1 : -1, sy = v.y() >= 0 ? 1 : -1, sz = v.z() >= 0 ? 1 : -1;
        if (dx <= dy && dx <= dz) return new int[] { -sx, -sz, sy };
        if (dy <= dz) return new int[] { sz, -sy, -sx };
        return new int[] { -sy, sx, -sz };
    }
    /** Vanilla {@code AABB.clip(min.., max.., from, to)}: the entry point on the box surface, or null. */
    static Vec3d clip(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3d from, Vec3d to) {
        double[] t = { 1.0 };
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        boolean hit = false;
        if (dx > 1.0E-7) hit = clipPoint(t, hit, dx, dy, dz, minX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
        else if (dx < -1.0E-7) hit = clipPoint(t, hit, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
        if (dy > 1.0E-7) hit = clipPoint(t, hit, dy, dz, dx, minY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
        else if (dy < -1.0E-7) hit = clipPoint(t, hit, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
        if (dz > 1.0E-7) hit = clipPoint(t, hit, dz, dx, dy, minZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
        else if (dz < -1.0E-7) hit = clipPoint(t, hit, dz, dx, dy, maxZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
        if (!hit) return null;
        double d = t[0];
        return from.add(d * dx, d * dy, d * dz);
    }
    private static boolean clipPoint(double[] t, boolean hit, double da, double db, double dc, double plane, double bMin, double bMax, double cMin, double cMax, double a, double b, double c) {
        double s = (plane - a) / da; double b2 = b + s * db; double c2 = c + s * dc;
        if (0.0 < s && s < t[0] && bMin - 1.0E-7 < b2 && b2 < bMax + 1.0E-7 && cMin - 1.0E-7 < c2 && c2 < cMax + 1.0E-7) { t[0] = s; return true; }
        return hit;
    }
    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL); }
    /** Tiny allocation-light set of cell keys (a traversal visits tens of cells; the budget caps it at 512). */
    static final class LongList {
        private long[] a = new long[16]; private int n;
        boolean add(long v) { for (int i = 0; i < n; i++) if (a[i] == v) return false; if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; return true; }
    }
    private static int floor(double d) { return (int) Math.floor(d); }
    private static int sign(double d) { return d == 0.0 ? 0 : d > 0.0 ? 1 : -1; }
    private static double frac(double d) { return d - (double) (long) Math.floor(d); }
    private static double clamp(double v, double min, double max) { return v < min ? min : v > max ? max : v; }
}
