package me.nicolas.papea2ac.player;
import java.util.Objects;
/**
 * Explicit historical-lookup result. A query never silently returns the nearest available record: every
 * miss states exactly why, so a consumer cannot mistake stale/absent history for a legitimate answer
 * (PLAYER_TIMELINE.md's query semantics; PACKET_PIPELINE.md's general "unknown is not a default").
 */
public sealed interface TimelineLookup {
    record Found(TimelineRecord record) implements TimelineLookup { public Found { Objects.requireNonNull(record); } }
    /** The sequence was once retained but has since aged out of the bounded ring (count/byte/horizon eviction). */
    record Evicted(long requestedSequence) implements TimelineLookup {}
    /** The sequence has never been produced: it is beyond the highest committed sequence, or below 1. */
    record NeverExisted(long requestedSequence) implements TimelineLookup {}
    /** The sequence predates this Timeline instance's own observation window (e.g. a pre-existing session that reconnected mid-capture, or a query issued before the first record was ever appended). */
    record PreCapture(long requestedSequence) implements TimelineLookup {}
    /** A world epoch boundary (respawn/dimension change) lies between the requested sequence and the reference point, so the answer would silently cross a discontinuity if returned; the caller must query per-epoch instead. */
    record WorldEpochCrossing(long requestedSequence, long fromWorldEpoch, long toWorldEpoch) implements TimelineLookup {}
    /** A coverage gap (Phase 1 health degrade) makes the record present but not trustworthy for the caller's stated purpose. */
    record IncompleteCoverage(long requestedSequence, String reason) implements TimelineLookup { public IncompleteCoverage { Objects.requireNonNull(reason); } }
}
