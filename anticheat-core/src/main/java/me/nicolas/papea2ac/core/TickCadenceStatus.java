package me.nicolas.papea2ac.core;
import java.util.List;
/**
 * Primitive-only projection of one session's Phase 14 cadence state for platform-paper (which may not depend on player or simulation):
 * the stream counts, the fence correlation hygiene, the anchor retention, the classification counts, the number of distinct exclusion
 * runs (consecutive INVALID targets count once), the largest excess observed with its description, the last classification and a bounded
 * ring of recent notable lines, plus the per-anchor window telemetry (the newest {@code TickCadenceRunner.WINDOWS} classifications with their
 * tightest window: span, proven ticks, bound, margin). Nothing here is a level, a score or an action.
 */
public record TickCadenceStatus(long sessionEpoch, int segment, long tickEnds, long movements, long induced, long pingsSent, long pingsAnswered, long unknownAnswers, long failedPings,
                            int pendingPings, int anchors, long anchorsThinned, long anchorsExpired, long coverageBreaks, String tickRate, long tickRateChanges,
                            long valid, long uncertain, long invalid, long exclusionRuns, long largestExcess, String largestExcessWindow, long movementBeyondTickEnds, String last, List<String> recent, List<String> windows) {
    public TickCadenceStatus { recent = List.copyOf(recent); windows = List.copyOf(windows); }
}
