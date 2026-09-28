package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.collision.BlockShapeResolver;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.collision.ResolvedShape;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.DestinationRegion;
import me.nicolas.papea2ac.model.SafeAnchorValidationPort;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
/**
 * The model {@link SafeAnchorValidationPort} implemented by mitigation with the shared collision geometry (MITIGATION.md: "Mitigation
 * implements model SafeAnchorValidationPort using world/collision, and core injects it into the platform executor. The main thread
 * captures a bounded immutable destination region; the validator uses the same shared geometry. Reject unknown coverage").
 *
 * <p>Every cell of the broad-phase range of the destination box (padded by one cell on every axis, corners included - a superset of what
 * vanilla visits) must be present in the captured region; a missing cell, an incomplete region, an unsupported or context-dependent shape
 * whose possible extent reaches the box is UNKNOWN; any definite shape intersecting the box (vanilla acceptance rule) is OBSTRUCTED.
 */
public final class SafeAnchorValidator implements SafeAnchorValidationPort {
    private final BlockShapeResolver resolver;
    public SafeAnchorValidator(CollisionEngine collision) { this.resolver = Objects.requireNonNull(collision).resolver(); }

    @Override public ClearanceResult validate(DestinationRegion region, Bounds destination) {
        Objects.requireNonNull(region); Objects.requireNonNull(destination);
        if (!region.complete()) return ClearanceResult.UNKNOWN;
        Aabb box = new Aabb(destination.minX(), destination.minY(), destination.minZ(), destination.maxX(), destination.maxY(), destination.maxZ());
        DestinationRegionCapture.CellRange rg = DestinationRegionCapture.range(destination);
        if (rg.cells() > DestinationRegionCapture.MAX_CELLS) return ClearanceResult.UNKNOWN;
        int x0 = rg.x0(), x1 = rg.x1(), y0 = rg.y0(), y1 = rg.y1(), z0 = rg.z0(), z1 = rg.z1();
        Map<Long, Integer> cells = new HashMap<>(region.cells().size() * 2);
        for (DestinationRegion.Cell c : region.cells()) cells.put(key(c.x(), c.y(), c.z()), c.blockStateId());
        boolean unknown = false;
        for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            Integer id = cells.get(key(x, y, z));
            if (id == null) return ClearanceResult.UNKNOWN; // UNKNOWN != AIR: an uncaptured cell is never clear
            ResolvedShape r = resolver.resolve(resolver.entry(id), x, y, z, ShapeContext.UNKNOWN);
            if (!r.lower().isEmpty() && CollisionEngine.intersects(r.lower(), x, y, z, box)) return ClearanceResult.OBSTRUCTED;
            if (!r.status().exact() && !r.upper().isEmpty() && CollisionEngine.intersects(r.upper(), x, y, z, box)) unknown = true;
        }
        return unknown ? ClearanceResult.UNKNOWN : ClearanceResult.CLEAR;
    }
    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
}
