package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.world.BlockLookup;
import me.nicolas.papea2ac.world.BlockStateDictionary;
import me.nicolas.papea2ac.world.DimensionProfile;
import me.nicolas.papea2ac.world.SectionSnapshot;
import me.nicolas.papea2ac.world.WorldView;
import java.util.ArrayList;
import java.util.Objects;
/**
 * Phase 4 collision engine: OBSERVER WORLD SNAPSHOT + query box -> {@link CollisionResult}. Pure geometry over an
 * immutable {@link WorldView} (current, historical or published snapshot); no live server access, no physics, no
 * movement judgment. One instance per {@code PacketEngine} (the resolver cache is keyed by the engine-wide dictionary).
 *
 * Broad phase = vanilla {@code BlockCollisions}/{@code Cursor3D}: cells from {@code floor(min - EPS) - 1} to
 * {@code floor(max + EPS) + 1} on every axis, iterated x fastest, then y, then z; a cell on the outer ring is visited
 * only if exactly one of its coordinates is on the ring AND the block has a large shape (fences, walls, piston heads,
 * every non-static kind), or exactly two are on the ring AND the block is a moving piston; corners never. Narrow phase =
 * vanilla candidate acceptance: a full cube must strictly intersect the box ({@code AABB.intersects}); any other shape
 * must overlap it by more than {@code Shapes.EPSILON} on every axis (the voxel-join merge rule). Whole shapes are
 * emitted, never clipped boxes.
 *
 * Coverage: every visited cell contributes one {@link CellStatus}; the world statuses come unchanged from Phase 3, so
 * UNKNOWN != AIR, UNLOADED != EVICTED and CONTENT_NOT_RETAINED survive into the answer. Cells above/below the dimension
 * are counted OUTSIDE_DIMENSION and are exactly empty (no block exists there). A query is COMPLETE only when every
 * non-corner cell in the padded range resolved exactly and the box budget was not exceeded.
 *
 * Bounds (PERFORMANCE.md): {@link CollisionProfile#MAX_CELLS_PER_QUERY} over the padded range (checked with long
 * arithmetic before any allocation - an oversized request is REJECTED, never iterated) and
 * {@link CollisionProfile#MAX_BOXES_PER_QUERY} emitted boxes (exceeding it stops the scan and marks the result
 * truncated + INCOMPLETE). No lock is taken: views are immutable or owner-thread-confined by construction.
 */
public final class CollisionEngine {
    /** Coordinate magnitude beyond which a query is rejected outright (the protocol's 30-bit block coordinate space; matches ProtocolProfile.coordinateLimit). */
    public static final double COORDINATE_LIMIT = 3.2E7;
    private static final CellStatus[] BY_WORLD_STATUS = new CellStatus[BlockLookup.Status.values().length];
    static { for (BlockLookup.Status s : BlockLookup.Status.values()) BY_WORLD_STATUS[s.ordinal()] = s == BlockLookup.Status.KNOWN ? null : CellStatus.valueOf(s.name()); }
    private final CollisionProfile profile; private final BlockShapeResolver resolver; private final CollisionMetrics metrics; private final BlockStateDictionary dictionary;

    public CollisionEngine(CollisionProfile profile, BlockStateDictionary dictionary) {
        this.profile = Objects.requireNonNull(profile); this.dictionary = Objects.requireNonNull(dictionary);
        this.metrics = new CollisionMetrics();
        this.resolver = new BlockShapeResolver(profile, dictionary, metrics);
    }
    public CollisionProfile profile() { return profile; }
    public BlockShapeResolver resolver() { return resolver; }
    public CollisionMetrics metrics() { return metrics; }
    public BlockStateDictionary dictionary() { return dictionary; }

    /**
     * Collision geometry intersecting {@code bounds} in {@code view}. {@code expectedWorldEpoch} is the epoch the caller's
     * player state belongs to (PLAYER_STATE.worldEpoch); a mismatch is UNAVAILABLE(WRONG_WORLD_EPOCH) - coordinates alone
     * never resolve across a dimension change. {@code context} may be {@link ShapeContext#UNKNOWN}.
     */
    public CollisionResult query(WorldView view, Aabb bounds, ShapeContext context, long expectedWorldEpoch) {
        Objects.requireNonNull(view); Objects.requireNonNull(bounds); Objects.requireNonNull(context);
        metrics.queries.increment();
        int[] counts = new int[CellStatus.values().length];
        BlockLookup.Status availability = view.availability();
        if (availability != BlockLookup.Status.KNOWN) { metrics.unavailable.increment(); if (availability == BlockLookup.Status.HISTORY_NOT_RETAINED || availability == BlockLookup.Status.FUTURE_SEQUENCE || availability == BlockLookup.Status.WRONG_WORLD_EPOCH) metrics.historicalMisses.increment(); return unavailable(view, bounds, BY_WORLD_STATUS[availability.ordinal()], counts); }
        if (expectedWorldEpoch != view.worldEpoch()) { metrics.unavailable.increment(); return unavailable(view, bounds, CellStatus.WRONG_WORLD_EPOCH, counts); }
        if (Math.abs(bounds.minX()) > COORDINATE_LIMIT || Math.abs(bounds.maxX()) > COORDINATE_LIMIT || Math.abs(bounds.minZ()) > COORDINATE_LIMIT || Math.abs(bounds.maxZ()) > COORDINATE_LIMIT
            || Math.abs(bounds.minY()) > COORDINATE_LIMIT || Math.abs(bounds.maxY()) > COORDINATE_LIMIT) { metrics.rejected.increment(); return rejected(view, bounds, counts); }
        final double pad = CollisionProfile.BROAD_PHASE_PADDING;
        long x0 = (long) Math.floor(bounds.minX() - pad) - 1, x1 = (long) Math.floor(bounds.maxX() + pad) + 1;
        long y0 = (long) Math.floor(bounds.minY() - pad) - 1, y1 = (long) Math.floor(bounds.maxY() + pad) + 1;
        long z0 = (long) Math.floor(bounds.minZ() - pad) - 1, z1 = (long) Math.floor(bounds.maxZ() + pad) + 1;
        long nx = x1 - x0 + 1, ny = y1 - y0 + 1, nz = z1 - z0 + 1; // each < 2^27 after the limit check, so the product fits a long without overflow
        if (nx * ny > CollisionProfile.MAX_CELLS_PER_QUERY || nx * ny * nz > CollisionProfile.MAX_CELLS_PER_QUERY) { metrics.rejected.increment(); return rejected(view, bounds, counts); }
        DimensionProfile dim = view.dimension();
        int minSectionY = dim.minSectionY(), sectionCount = dim.sectionCount();
        ArrayList<CollisionResult.Hit> hits = null; int boxes = 0, visited = 0; boolean truncated = false;
        // one-entry section memo: consecutive cells in x share a section 15 times out of 16
        int memoCx = Integer.MIN_VALUE, memoCz = Integer.MIN_VALUE, memoSi = Integer.MIN_VALUE; WorldView.SectionLookup memo = null;
        scan:
        for (long zl = z0; zl <= z1; zl++) {
            int z = (int) zl, zr = (zl == z0 || zl == z1) ? 1 : 0;
            for (long yl = y0; yl <= y1; yl++) {
                int y = (int) yl, yr = (yl == y0 || yl == y1) ? 1 : 0;
                int sectionIndex = (y >> 4) - minSectionY; boolean outside = sectionIndex < 0 || sectionIndex >= sectionCount;
                for (long xl = x0; xl <= x1; xl++) {
                    int ring = zr + yr + ((xl == x0 || xl == x1) ? 1 : 0);
                    if (ring == 3) continue; // vanilla never visits corners
                    int x = (int) xl; visited++;
                    if (outside) { counts[CellStatus.OUTSIDE_DIMENSION.ordinal()]++; continue; }
                    int cx = x >> 4, cz = z >> 4;
                    if (cx != memoCx || cz != memoCz || sectionIndex != memoSi) { memo = view.section(cx, cz, sectionIndex); memoCx = cx; memoCz = cz; memoSi = sectionIndex; }
                    if (memo.status() != BlockLookup.Status.KNOWN) { counts[BY_WORLD_STATUS[memo.status().ordinal()].ordinal()]++; metrics.worldUnknownCells.increment(); continue; }
                    SectionSnapshot section = memo.section();
                    int id = section.stateIdAt(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
                    if (id < 0) { counts[CellStatus.UNKNOWN_CELL.ordinal()]++; metrics.worldUnknownCells.increment(); continue; }
                    BlockShapeResolver.Entry entry = resolver.entry(id);
                    if ((ring == 1 && !entry.large()) || (ring == 2 && !entry.movingPiston())) { // face ring: only shapes that can reach into the box; edge ring: only moving pistons
                        counts[(entry.constant() ? entry.exact.status() : CellStatus.EXACT).ordinal()]++; continue; // resolved exactly as "cannot reach": counted under its own exact status
                    }
                    ResolvedShape r = entry.constant() && entry.exact.lower().isEmpty() ? entry.exact : resolver.resolve(entry, x, y, z, context);
                    counts[r.status().ordinal()]++;
                    if (r.lower().isEmpty() && r.upper().isEmpty()) continue;
                    double ox = x, oy = y, oz = z;
                    if (!r.lower().isEmpty() && intersects(r.lower(), ox, oy, oz, bounds)) {
                        if (hits == null) hits = new ArrayList<>(8);
                        hits.add(new CollisionResult.Hit(x, y, z, r.lower(), ox, oy, oz, r.status(), true)); boxes += r.lower().boxCount();
                    }
                    if (r.upper() != r.lower() && !r.upper().isEmpty() && intersects(r.upper(), ox, oy, oz, bounds)) {
                        if (hits == null) hits = new ArrayList<>(8);
                        hits.add(new CollisionResult.Hit(x, y, z, r.upper(), ox, oy, oz, r.status(), false)); boxes += r.upper().boxCount();
                    }
                    if (boxes > CollisionProfile.MAX_BOXES_PER_QUERY) { truncated = true; metrics.truncated.increment(); break scan; }
                }
            }
        }
        boolean complete = !truncated;
        for (CellStatus s : CellStatus.values()) if (counts[s.ordinal()] > 0 && !s.exact() && s != CellStatus.OUTSIDE_DIMENSION) { complete = false; break; }
        metrics.cellsVisited.add(visited); if (hits != null) { metrics.hits.add(hits.size()); metrics.boxesEmitted.add(boxes); }
        if (complete) metrics.complete.increment(); else metrics.incomplete.increment();
        return new CollisionResult(bounds, complete ? CollisionResult.Certainty.COMPLETE : CollisionResult.Certainty.INCOMPLETE, null, view.worldEpoch(), view.revision(), view.sequence(),
            hits == null ? java.util.List.of() : hits, counts, visited, truncated, profile.id());
    }

    /** Vanilla candidate acceptance: the Shapes.block() singleton -> strict AABB intersection; any other shape -> the exact voxel join ({@link VoxelJoin}). */
    public static boolean intersects(CollisionShape shape, double ox, double oy, double oz, Aabb q) {
        if (shape.isFullCube()) return q.intersects(ox, oy, oz, ox + 1, oy + 1, oz + 1);
        return VoxelJoin.intersects(shape, ox, oy, oz, q);
    }
    private CollisionResult unavailable(WorldView view, Aabb bounds, CellStatus reason, int[] counts) {
        return new CollisionResult(bounds, CollisionResult.Certainty.UNAVAILABLE, reason, view.worldEpoch(), view.revision(), view.sequence(), java.util.List.of(), counts, 0, false, profile.id());
    }
    private CollisionResult rejected(WorldView view, Aabb bounds, int[] counts) {
        return new CollisionResult(bounds, CollisionResult.Certainty.REJECTED, null, view.worldEpoch(), view.revision(), view.sequence(), java.util.List.of(), counts, 0, false, profile.id());
    }
}
