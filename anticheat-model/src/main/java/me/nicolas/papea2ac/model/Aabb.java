package me.nicolas.papea2ac.model;
/**
 * Immutable platform-independent axis-aligned box (ARCHITECTURE.md A3: AABB is a model value, never a Bukkit or
 * NMS type). Numeric contract (PHASE_4_IMPLEMENTATION.md "Numeric contract"):
 * <ul>
 * <li>all six coordinates must be finite; NaN/Infinity is a protocol-safety violation and throws, never clamps;</li>
 * <li>{@code min <= max} on every axis (a zero-extent box is legal: it is a degenerate box that intersects nothing
 *     under the strict rule below; a negative extent is rejected, never silently swapped);</li>
 * <li>{@link #intersects} is the vanilla {@code AABB.intersects} rule - STRICT inequalities, so two boxes that
 *     merely share a face, edge or corner do NOT intersect and there is no epsilon here at all;</li>
 * <li>{@code -0.0} compares equal to {@code 0.0} everywhere (IEEE comparison), it is not normalized.</li>
 * </ul>
 * No geometry algorithm lives here (model holds values); intersection/clipping engines are in anticheat-collision.
 */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Aabb {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ))
            throw new IllegalArgumentException("non-finite box");
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("inverted box");
    }
    /** Strict overlap on every axis (vanilla {@code AABB.intersects}): touching is not intersecting. */
    public boolean intersects(Aabb o) {
        return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
    }
    public boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1) {
        return minX < x1 && maxX > x0 && minY < y1 && maxY > y0 && minZ < z1 && maxZ > z0;
    }
    public Aabb move(double dx, double dy, double dz) { return new Aabb(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz); }
    /** Vanilla {@code AABB.inflate}: grows (or shrinks, for a negative value) every face by the same amount. */
    public Aabb inflate(double d) { return new Aabb(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d); }
    /** Vanilla {@code AABB.expandTowards}: extends only the face that lies in the direction of the displacement. */
    public Aabb expandTowards(double dx, double dy, double dz) {
        return new Aabb(dx < 0 ? minX + dx : minX, dy < 0 ? minY + dy : minY, dz < 0 ? minZ + dz : minZ,
                        dx > 0 ? maxX + dx : maxX, dy > 0 ? maxY + dy : maxY, dz > 0 ? maxZ + dz : maxZ);
    }
    public double sizeX() { return maxX - minX; }
    public double sizeY() { return maxY - minY; }
    public double sizeZ() { return maxZ - minZ; }
    public boolean contains(double x, double y, double z) { return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ; }
}
