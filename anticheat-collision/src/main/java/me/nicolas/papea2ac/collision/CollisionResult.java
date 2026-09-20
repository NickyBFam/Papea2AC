package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.Aabb;
import java.util.*;
/**
 * Immutable answer to one collision query (task §35/§36 - never a boolean). Carries the query bounds, the world
 * provenance it was answered against (world epoch, revision, session sequence "through"), the ordered list of
 * intersecting shapes, one count per {@link CellStatus}, the work performed and a four-valued certainty:
 * <ul>
 * <li>{@link Certainty#COMPLETE} - every visited cell resolved exactly; {@code hits} is the whole truth;</li>
 * <li>{@link Certainty#INCOMPLETE} - some cell is unknown / context-dependent / dynamic / unsupported, or the box
 *     budget truncated the scan: {@code hits} are still real geometry, but absence of hits proves nothing;</li>
 * <li>{@link Certainty#UNAVAILABLE} - the world view cannot answer at all (future sequence, wrong world epoch,
 *     unknown dimension, history evicted): no cell was examined;</li>
 * <li>{@link Certainty#REJECTED} - the query itself was invalid (oversized, outside the coordinate limit).</li>
 * </ul>
 * Each {@link Hit} is a whole block shape at its world position (grouping preserved for {@link AxisClip}, exactly
 * like vanilla collects whole {@code VoxelShape}s). {@code definite} hits are geometry that is certainly present;
 * non-definite hits are the possible-only part of an envelope (a lid that may be open, a piston that may be moving).
 * Order is the deterministic broad-phase order (z outer, y, x inner - vanilla {@code Cursor3D}), so equal inputs give
 * equal results (record equality on every field).
 */
public record CollisionResult(Aabb bounds, Certainty certainty, CellStatus unavailableReason, long worldEpoch, long revision, long sequence,
                              List<Hit> hits, int[] statusCounts, int cellsVisited, boolean truncated, String profile) {
    public enum Certainty { COMPLETE, INCOMPLETE, UNAVAILABLE, REJECTED }
    /** One intersecting shape: block cell, the shape (block-local) and its world origin (cell + any deterministic offset), the cell's status, and whether it is definite (lower bound) or possible-only (upper bound remainder). */
    public record Hit(int x, int y, int z, CollisionShape shape, double originX, double originY, double originZ, CellStatus status, boolean definite) {
        public Hit { Objects.requireNonNull(shape); Objects.requireNonNull(status); }
        public Aabb worldBox(int box) { return shape.box(box, originX, originY, originZ); }
    }
    public CollisionResult {
        Objects.requireNonNull(bounds); Objects.requireNonNull(certainty); Objects.requireNonNull(hits); Objects.requireNonNull(statusCounts); Objects.requireNonNull(profile);
        if (statusCounts.length != CellStatus.values().length) throw new IllegalArgumentException("status counts");
        hits = List.copyOf(hits); statusCounts = statusCounts.clone();
    }
    /** Defensive copy (the record accessor would expose the internal array). */
    @Override public int[] statusCounts() { return statusCounts.clone(); }
    public int count(CellStatus status) { return statusCounts[status.ordinal()]; }
    public int hitCount() { return hits.size(); }
    /** Some geometry is CERTAINLY inside the query bounds (sound for an "obstructed" conclusion under any certainty). */
    public boolean definitelyObstructed() { for (Hit h : hits) if (h.definite()) return true; return false; }
    /** The bounds are CERTAINLY free of block geometry: requires a COMPLETE scan with no hit of any kind. */
    public boolean definitelyClear() { return certainty == Certainty.COMPLETE && hits.isEmpty(); }
    /** Convenience (allocates): every box of every definite hit, in result order. */
    public List<Aabb> definiteBoxes() { var out = new ArrayList<Aabb>(); for (Hit h : hits) if (h.definite()) for (int i = 0; i < h.shape().boxCount(); i++) out.add(h.worldBox(i)); return out; }
    /** Convenience (allocates): every box of every hit, definite or possible, in result order - the upper envelope. */
    public List<Aabb> possibleBoxes() { var out = new ArrayList<Aabb>(); for (Hit h : hits) for (int i = 0; i < h.shape().boxCount(); i++) out.add(h.worldBox(i)); return out; }
    public int boxCount() { int n = 0; for (Hit h : hits) n += h.shape().boxCount(); return n; }
    /** Bounded one-line summary for diagnostics (counts only, never a dump). */
    public String summary() {
        var sb = new StringBuilder(certainty.name());
        if (unavailableReason != null) sb.append('(').append(unavailableReason).append(')');
        sb.append(" hits=").append(hits.size()).append(" boxes=").append(boxCount()).append(" cells=").append(cellsVisited).append(truncated ? " TRUNCATED" : "");
        for (CellStatus s : CellStatus.values()) if (statusCounts[s.ordinal()] > 0) sb.append(' ').append(s.name()).append('=').append(statusCounts[s.ordinal()]);
        return sb.toString();
    }
    @Override public boolean equals(Object o) {
        return o instanceof CollisionResult r && bounds.equals(r.bounds) && certainty == r.certainty && unavailableReason == r.unavailableReason && worldEpoch == r.worldEpoch && revision == r.revision
            && sequence == r.sequence && hits.equals(r.hits) && Arrays.equals(statusCounts, r.statusCounts) && cellsVisited == r.cellsVisited && truncated == r.truncated && profile.equals(r.profile);
    }
    @Override public int hashCode() { return Objects.hash(bounds, certainty, unavailableReason, worldEpoch, revision, sequence, hits, Arrays.hashCode(statusCounts), cellsVisited, truncated, profile); }
    @Override public String toString() { return "CollisionResult[" + summary() + "]"; }
}
