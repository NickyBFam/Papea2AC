package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.SafeAnchorValidationPort;
/**
 * The exact bounded cell range the main thread captures for a destination (MITIGATION.md: "the main thread captures a bounded immutable
 * destination region; the validator uses the same shared geometry"), shared by the platform capture and {@link SafeAnchorValidator} so both
 * sides agree on what "complete" means.
 */
public final class DestinationRegionCapture {
    private DestinationRegionCapture() {}
    /** Model bound of a {@link me.nicolas.papea2ac.model.DestinationRegion}. */
    public static final int MAX_CELLS = 4096;
    /** Inclusive cell range [x0,y0,z0]..[x1,y1,z1] of the padded broad phase of {@code b}. */
    public record CellRange(int x0, int y0, int z0, int x1, int y1, int z1) {
        public long cells() { return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1); }
    }
    /** The broad-phase padding of the pinned collision profile ({@code CollisionProfile.BROAD_PHASE_PADDING}; equality pinned by a test). */
    public static final double PAD = 1.0E-7;
    public static CellRange range(SafeAnchorValidationPort.Bounds b) {
        final double pad = PAD;
        return new CellRange((int) Math.floor(b.minX() - pad) - 1, (int) Math.floor(b.minY() - pad) - 1, (int) Math.floor(b.minZ() - pad) - 1,
            (int) Math.floor(b.maxX() + pad) + 1, (int) Math.floor(b.maxY() + pad) + 1, (int) Math.floor(b.maxZ() + pad) + 1);
    }
    /** Port bounds of a box (e.g. the standing box at a target, from {@link AnchorAdmission#standingBox}). */
    public static SafeAnchorValidationPort.Bounds bounds(Aabb a) { return new SafeAnchorValidationPort.Bounds(a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()); }
}
