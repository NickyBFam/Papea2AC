package me.nicolas.papea2ac.model;
/** Implemented by mitigation in its later phase; no clearance implementation in Phase 1. */
public interface SafeAnchorValidationPort {
    ClearanceResult validate(DestinationRegion region, Bounds destination);
    enum ClearanceResult { CLEAR, OBSTRUCTED, UNKNOWN }
    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("bounds");
        }
    }
}
