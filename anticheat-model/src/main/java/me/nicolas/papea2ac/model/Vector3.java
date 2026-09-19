package me.nicolas.papea2ac.model;
/** Immutable platform-independent position/displacement value. Finite-only: NaN/Infinity are protocol-safety violations, never silently clamped. */
public record Vector3(double x, double y, double z) {
    public Vector3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("non-finite vector");
    }
    public static final Vector3 ZERO = new Vector3(0, 0, 0);
    public Vector3 minus(Vector3 other) { return new Vector3(x - other.x, y - other.y, z - other.z); }
    public Vector3 plus(Vector3 other) { return new Vector3(x + other.x, y + other.y, z + other.z); }
    public double distanceSquared(Vector3 other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
