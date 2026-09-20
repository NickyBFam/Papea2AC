package me.nicolas.papea2ac.physics;
/**
 * Vanilla {@code Vec3} (the client's phys vector) arithmetic, operation for operation (NUMERICAL_POLICY NUM1: "preserve
 * exact order ... No reassociation or fused multiply-add substitution"). Every method mirrors the client's expression
 * shape so that the doubles produced are bit-identical: {@code add(x,y,z)} is {@code this.x + x}, {@code scale(d)} is
 * {@code this.x * d}, {@code normalize()} uses {@code Math.sqrt} (IEEE-exact, permitted by NUMERICAL_POLICY) and the
 * client's {@code 1.0E-4F} zero guard. Immutable value; the physics step allocates a handful per tick.
 */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);
    public Vec3d add(double dx, double dy, double dz) { return new Vec3d(x + dx, y + dy, z + dz); }
    public Vec3d add(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }
    public Vec3d subtract(Vec3d o) { return new Vec3d(x - o.x, y - o.y, z - o.z); }
    public Vec3d scale(double d) { return new Vec3d(x * d, y * d, z * d); }
    public Vec3d multiply(double mx, double my, double mz) { return new Vec3d(x * mx, y * my, z * mz); }
    public Vec3d multiply(Vec3d o) { return new Vec3d(x * o.x, y * o.y, z * o.z); }
    public double lengthSqr() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(x * x + y * y + z * z); }
    public double horizontalDistanceSqr() { return x * x + z * z; }
    public double distanceToSqr(Vec3d o) { double dx = o.x - x, dy = o.y - y, dz = o.z - z; return dx * dx + dy * dy + dz * dz; }
    public double dot(Vec3d o) { return x * o.x + y * o.y + z * o.z; }
    /** Vanilla {@code Vec3.normalize}: lengths below {@code 1.0E-4F} normalize to ZERO. */
    public Vec3d normalize() { double d = Math.sqrt(x * x + y * y + z * z); return d < 9.999999747378752E-6 ? ZERO : new Vec3d(x / d, y / d, z / d); }
    /** Vanilla {@code Vec3.get(Axis)}. */
    public double get(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
    /** Vanilla {@code Vec3.with(Axis, double)}. */
    public Vec3d with(int axis, double v) { return axis == 0 ? new Vec3d(v, y, z) : axis == 1 ? new Vec3d(x, v, z) : new Vec3d(x, y, v); }
    /** Vanilla {@code Vec3.relative(Direction, double)} for a positive axis direction: {@code x + stepX * d} with the other steps multiplying by 0. */
    public Vec3d relativePositive(int axis, double d) { return new Vec3d(x + (axis == 0 ? 1 : 0) * d, y + (axis == 1 ? 1 : 0) * d, z + (axis == 2 ? 1 : 0) * d); }
    public boolean isFinite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    /** Vanilla {@code Direction.axisStepOrder}: Y first, then the smaller horizontal component last ({@code |x| < |z| ? Y,Z,X : Y,X,Z}). */
    public int[] axisStepOrder() { return Math.abs(x) < Math.abs(z) ? YZX : YXZ; }
    static final int[] YXZ = { 1, 0, 2 }, YZX = { 1, 2, 0 };
}
