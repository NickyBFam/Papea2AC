package me.nicolas.papea2ac.collision;
import java.util.concurrent.atomic.LongAdder;
/**
 * Fixed-shape, engine-wide collision counters (task §58): no player, position or block label ever becomes a key.
 * Cheap to bump from any thread; read as an immutable {@link Snapshot}.
 */
public final class CollisionMetrics {
    public final LongAdder queries = new LongAdder(), complete = new LongAdder(), incomplete = new LongAdder(), unavailable = new LongAdder(), rejected = new LongAdder(),
        truncated = new LongAdder(), cellsVisited = new LongAdder(), hits = new LongAdder(), boxesEmitted = new LongAdder(),
        shapeCacheHits = new LongAdder(), shapeCacheMisses = new LongAdder(), unsupportedStates = new LongAdder(), contextRequired = new LongAdder(), dynamicCells = new LongAdder(),
        worldUnknownCells = new LongAdder(), historicalMisses = new LongAdder();
    public record Snapshot(long queries, long complete, long incomplete, long unavailable, long rejected, long truncated, long cellsVisited, long hits, long boxesEmitted,
                           long shapeCacheHits, long shapeCacheMisses, long unsupportedStates, long contextRequired, long dynamicCells, long worldUnknownCells, long historicalMisses) {}
    public Snapshot snapshot() {
        return new Snapshot(queries.sum(), complete.sum(), incomplete.sum(), unavailable.sum(), rejected.sum(), truncated.sum(), cellsVisited.sum(), hits.sum(), boxesEmitted.sum(),
            shapeCacheHits.sum(), shapeCacheMisses.sum(), unsupportedStates.sum(), contextRequired.sum(), dynamicCells.sum(), worldUnknownCells.sum(), historicalMisses.sum());
    }
}
