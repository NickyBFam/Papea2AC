package me.nicolas.papea2ac.combat.geometry;

import me.nicolas.papea2ac.version.MovementProfile;

/**
 * The vanilla 26.2 client's attack-pick arithmetic, reproduced operation for operation from the pinned client bytecode (Phase 12, R12 /
 * NUMERICAL_POLICY: concrete lanes reproduce the client's algorithm, casts and operation order; no reassociation, no FMA, no epsilon). Every
 * method names the bytecode it follows. Pure and allocation-light: boxes are immutable values, rays are primitives.
 *
 * <p>This class decides nothing; {@code ScenePick} composes it into the client's pick and the reach engine compares the outcome.
 */
public final class PickMath {
    private PickMath() {}

    /** {@code AABB.getDirection} / {@code clipPoint}: the face-plane tolerance (bc ldc2_w 1.0E-7d). */
    public static final double CLIP_EPSILON = 1.0E-7;
    /** {@code Entity.calculateViewVector}: degrees to radians as a float constant (bc ldc 0.017453292f). */
    public static final float DEG_TO_RAD = 0.017453292F;

    /**
     * vanilla {@code AABB}: the constructor normalizes each axis with {@code Math.min / Math.max} (bc 5–63), so a box is always
     * ordered; NaN propagates as Math.min/max propagate it (callers reject non-finite input before building boxes).
     */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static Box of(double x1, double y1, double z1, double x2, double y2, double z2) {
            return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }
        /** {@code AABB.inflate(double)} = {@code inflate(d, d, d)}. */
        public Box inflate(double d) { return inflate(d, d, d); }
        /** {@code AABB.inflate(double,double,double)} (bc 1–69): min − v, max + v, then the normalizing constructor. */
        public Box inflate(double x, double y, double z) { return of(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z); }
        /** {@code AABB.expandTowards(double,double,double)} (bc 36–140): a negative component extends min, a positive one max, zero neither. */
        public Box expandTowards(double x, double y, double z) {
            double a = minX, b = minY, c = minZ, d = maxX, e = maxY, f = maxZ;
            if (x < 0.0) a += x; else if (x > 0.0) d += x;
            if (y < 0.0) b += y; else if (y > 0.0) e += y;
            if (z < 0.0) c += z; else if (z > 0.0) f += z;
            return of(a, b, c, d, e, f);
        }
        /** {@code AABB.contains(double,double,double)} (bc 0–61): half-open, {@code min <= p < max} on every axis. */
        public boolean contains(double x, double y, double z) { return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ; }
        /** {@code AABB.distanceToSqr(Vec3)} (bc 0–88): per axis {@code max(max(min − p, p − max), 0)}, then {@code Mth.lengthSquared}. */
        public double distanceToSqr(double x, double y, double z) {
            double dx = Math.max(Math.max(minX - x, x - maxX), 0.0), dy = Math.max(Math.max(minY - y, y - maxY), 0.0), dz = Math.max(Math.max(minZ - z, z - maxZ), 0.0);
            return lengthSquared(dx, dy, dz);
        }
        /** {@code AABB.intersects(AABB)}: open overlap on every axis. */
        public boolean intersects(Box o) { return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ; }
    }

    /** {@code Mth.lengthSquared(double,double,double)} (bc 0–13): {@code x*x + y*y + z*z} in that order. */
    public static double lengthSquared(double x, double y, double z) { return x * x + y * y + z * z; }

    /** {@code Mth.lerp(double,double,double)} (bc 0–8): {@code a + d * (b − a)}. */
    public static double lerp(double d, double a, double b) { return a + d * (b - a); }

    /** {@code Vec3.distanceToSqr(Vec3)} on {@code this = (ax,ay,az)}, argument {@code (bx,by,bz)} (bc 0–47): {@code d = b − a}, then {@code dx*dx + dy*dy + dz*dz}. */
    public static double distanceToSqr(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * {@code EntityDimensions.makeBoundingBox(double,double,double)} (bc 0–49): {@code w = width / 2.0F} in float, then
     * {@code (x − w, y, z − w, x + w, y + height, z + w)} with float→double widening.
     */
    public static Box makeBoundingBox(float width, float height, double x, double y, double z) {
        float w = width / 2.0F;
        return Box.of(x - (double) w, y, z - (double) w, x + (double) w, y + (double) height, z + (double) w);
    }

    /**
     * {@code EntityDimensions.scale(float,float)} (bc 1–57): a fixed size, or a factor of exactly 1 on both axes, returns the dimensions
     * unchanged; otherwise {@code width * w, height * h, eyeHeight * h} in float.
     */
    public static float[] scale(float width, float height, float eyeHeight, boolean fixed, float w, float h) {
        if (fixed || (w == 1.0F && h == 1.0F)) return new float[]{width, height, eyeHeight};
        return new float[]{width * w, height * h, eyeHeight * h};
    }

    /**
     * {@code Entity.getEyePosition(float)} at partial tick 1.0 (bc 0–62), the value {@code Minecraft.pick(1.0F)} uses:
     * {@code lerp(1.0, xo, x)}, {@code lerp(1.0, yo, y) + (double) eyeHeight}, {@code lerp(1.0, zo, z)}. Not always bit-equal to {@code x}.
     */
    public static double[] eyePosition(double xo, double yo, double zo, double x, double y, double z, float eyeHeight) {
        return new double[]{lerp(1.0, xo, x), lerp(1.0, yo, y) + (double) eyeHeight, lerp(1.0, zo, z)};
    }

    /**
     * {@code Entity.calculateViewVector(float xRot, float yRot)} (bc 0–67) with the client's {@code Mth} sine table:
     * {@code f = xRot * 0.017453292F}, {@code g = -yRot * 0.017453292F}; {@code h = cos(g), i = sin(g), j = cos(f), k = sin(f)}
     * (double-argument table lookups); result {@code ((double)(i*j), (double)(-k), (double)(h*j))}.
     */
    public static double[] viewVector(MovementProfile table, float xRot, float yRot) {
        float f = xRot * DEG_TO_RAD, g = -yRot * DEG_TO_RAD;
        float h = table.cos((double) g), i = table.sin((double) g), j = table.cos((double) f), k = table.sin((double) f);
        return new double[]{(double) (i * j), (double) (-k), (double) (h * j)};
    }

    /** {@code Vec3.add(double,double,double)} of {@code eye} and {@code view * d} per component, as {@code LocalPlayer.pick} builds the ray end (bc 75–101). */
    public static double[] rayEnd(double[] eye, double[] view, double d) {
        return new double[]{eye[0] + view[0] * d, eye[1] + view[1] * d, eye[2] + view[2] * d};
    }

    /**
     * {@code AABB.clip(Vec3 from, Vec3 to)} (bc 0–112): returns the clip parameter {@code t} in (0, 1) of the first face crossed, or
     * {@link Double#NaN} when the segment crosses no face (including a start inside the box). Faces are tried in the order WEST/EAST, DOWN/UP,
     * NORTH/SOUTH; a later face replaces the current best only with a strictly smaller {@code t} ({@code clipPoint} bc 29–100).
     */
    public static double clip(Box b, double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double best = 1.0; boolean hit = false;
        // x faces
        if (dx > CLIP_EPSILON) { double s = clipPoint(dx, dy, dz, b.minX, b.minY, b.maxY, b.minZ, b.maxZ, fx, fy, fz, best); if (!Double.isNaN(s)) { best = s; hit = true; } }
        else if (dx < -CLIP_EPSILON) { double s = clipPoint(dx, dy, dz, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, fx, fy, fz, best); if (!Double.isNaN(s)) { best = s; hit = true; } }
        // y faces: (dy, dz, dx) against (y plane, z range, x range), from (y, z, x)
        if (dy > CLIP_EPSILON) { double s = clipPoint(dy, dz, dx, b.minY, b.minZ, b.maxZ, b.minX, b.maxX, fy, fz, fx, best); if (!Double.isNaN(s)) { best = s; hit = true; } }
        else if (dy < -CLIP_EPSILON) { double s = clipPoint(dy, dz, dx, b.maxY, b.minZ, b.maxZ, b.minX, b.maxX, fy, fz, fx, best); if (!Double.isNaN(s)) { best = s; hit = true; } }
        // z faces: (dz, dx, dy) against (z plane, x range, y range), from (z, x, y)
        if (dz > CLIP_EPSILON) { double s = clipPoint(dz, dx, dy, b.minZ, b.minX, b.maxX, b.minY, b.maxY, fz, fx, fy, best); if (!Double.isNaN(s)) { best = s; hit = true; } }
        else if (dz < -CLIP_EPSILON) { double s = clipPoint(dz, dx, dy, b.maxZ, b.minX, b.maxX, b.minY, b.maxY, fz, fx, fy, best); if (!Double.isNaN(s)) { best = s; hit = true; } }
        return hit ? best : Double.NaN;
    }

    /**
     * {@code AABB.clipPoint} (bc 0–102): {@code s = (plane − fromA) / da}; {@code b = fromB + s * db}; {@code c = fromC + s * dc}; accepted iff
     * {@code 0 < s < best}, {@code bMin − 1e-7 < b < bMax + 1e-7} and {@code cMin − 1e-7 < c < cMax + 1e-7}. Returns s, or NaN when rejected.
     */
    static double clipPoint(double da, double db, double dc, double plane, double bMin, double bMax, double cMin, double cMax, double fromA, double fromB, double fromC, double best) {
        double s = (plane - fromA) / da;
        double b = fromB + s * db, c = fromC + s * dc;
        if (0.0 < s && s < best && bMin - CLIP_EPSILON < b && b < bMax + CLIP_EPSILON && cMin - CLIP_EPSILON < c && c < cMax + CLIP_EPSILON) return s;
        return Double.NaN;
    }

    /** The clip point {@code from.add(t * dx, t * dy, t * dz)} for a parameter returned by {@link #clip} (AABB.clip bc 83–106). */
    public static double[] clipLocation(double t, double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        return new double[]{fx + t * dx, fy + t * dy, fz + t * dz};
    }
}
