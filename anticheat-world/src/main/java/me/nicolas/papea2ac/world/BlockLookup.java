package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.BlockStateKey;
import java.util.Objects;
/**
 * Explicit block-lookup result. Never null-for-everything: every miss names its reason so a later consumer cannot
 * treat "not observed" as air, or an evicted chunk as still visible. {@code state} is non-null only for KNOWN.
 */
public record BlockLookup(Status status, int stateId, BlockStateKey state, long revision, WorldCoverage chunkCoverage) {
    public enum Status {
        KNOWN,
        /** The chunk is (at least partially) observed but this particular cell never was. */
        UNKNOWN_CELL,
        /** No observation for this chunk in this world epoch. */
        UNKNOWN_CHUNK,
        /** A section inside an observed chunk that has no data at all (e.g. a partial chunk's untouched section). */
        UNKNOWN_SECTION,
        UNLOADED,
        EVICTED,
        CONTENT_NOT_RETAINED,
        TRANSPORT_INCOMPLETE,
        OUTSIDE_DIMENSION,
        /** The dimension's vertical range is not yet known (capture started after JoinGame/Respawn), so absolute Y cannot be placed. */
        DIMENSION_UNKNOWN,
        WRONG_WORLD_EPOCH,
        /** The requested sequence lies before the retained history window (or before this chunk's baseline). */
        HISTORY_NOT_RETAINED,
        /** The requested sequence is newer than anything this world has committed. */
        FUTURE_SEQUENCE
    }
    public BlockLookup {
        Objects.requireNonNull(status); Objects.requireNonNull(chunkCoverage);
        if ((status == Status.KNOWN) != (state != null)) throw new IllegalArgumentException("state must be present exactly for KNOWN");
    }
    public static BlockLookup known(int stateId, BlockStateKey state, long revision, WorldCoverage coverage) { return new BlockLookup(Status.KNOWN, stateId, state, revision, coverage); }
    public static BlockLookup miss(Status status, WorldCoverage coverage) { return new BlockLookup(status, -1, null, -1, coverage); }
    public boolean known() { return status == Status.KNOWN; }
}
