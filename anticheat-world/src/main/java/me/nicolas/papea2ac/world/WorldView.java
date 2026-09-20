package me.nicolas.papea2ac.world;
import java.util.Objects;
/**
 * Read-only, section-granular resolver over one observer's world at one moment (WORLD_MODEL.md: "WorldView ...
 * returns a read-only resolver"; "Queries expose whether all touched coordinates have coverage"). Three flavours,
 * all returning the same explicit {@link BlockLookup.Status} vocabulary as {@link ObserverWorld#blockAt} /
 * {@link ObserverWorld#blockAtSequence} - a later geometry consumer resolves cells itself from the immutable
 * {@link SectionSnapshot} (never through a mutable map) and inherits every coverage reason unchanged:
 * <ul>
 * <li>{@link ObserverWorld#currentView()} - owner thread, the state after the last apply (valid until the next apply);</li>
 * <li>{@link ObserverWorld#viewAtSequence(long)} - owner thread, the state as observed through a historical session sequence;</li>
 * <li>{@link ObserverWorld.WorldSnapshot#view(BlockStateDictionary)} - any thread, the last published immutable snapshot.</li>
 * </ul>
 * {@link #availability()} is KNOWN when section lookups can be answered at all; FUTURE_SEQUENCE / WRONG_WORLD_EPOCH /
 * DIMENSION_UNKNOWN make every lookup unanswerable and are reported once here so a consumer fails fast and explicitly.
 */
public interface WorldView {
    /** status KNOWN => {@code section} non-null (its cells may still be individually unknown: {@link SectionSnapshot#stateIdAt} returns -1). */
    record SectionLookup(BlockLookup.Status status, SectionSnapshot section, WorldCoverage coverage, long revision) {
        public SectionLookup { Objects.requireNonNull(status); Objects.requireNonNull(coverage); if ((status == BlockLookup.Status.KNOWN) != (section != null)) throw new IllegalArgumentException("section present exactly for KNOWN"); }
        static SectionLookup miss(BlockLookup.Status status, WorldCoverage coverage) { return new SectionLookup(status, null, coverage, -1); }
    }
    BlockLookup.Status availability();
    long worldEpoch();
    /** Null when the dimension range is unknown (then {@link #availability()} is DIMENSION_UNKNOWN). */
    DimensionProfile dimension();
    long revision();
    /** The session sequence this view describes the world "through". */
    long sequence();
    BlockStateDictionary dictionary();
    /** Section by chunk coordinates and dimension section index (0 = the dimension's lowest section). Index outside the dimension => OUTSIDE_DIMENSION. */
    SectionLookup section(int chunkX, int chunkZ, int sectionIndex);
}
