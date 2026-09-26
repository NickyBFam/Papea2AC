package me.nicolas.papea2ac.evidence;
/**
 * The hard bounds of one session's evidence state (EVIDENCE_MODEL "Bounded storage": 256 evidence summaries and 128 correlation groups
 * per session; the 30-second correlation window). Everything beyond a bound is evicted deterministically (oldest first) and counted;
 * nothing grows without bound. These are storage/window limits, never cheat thresholds.
 *
 * <ul>
 * <li>{@code maxEvents} - retained EvidenceEvents (a ring; the oldest is evicted and counted);</li>
 * <li>{@code maxGroups} - correlation groups (union of events whose observation ranges touch, within the window); eviction sets the
 *     snapshot's {@code correlationIncomplete} flag, which forbids treating the remainder as disjoint;</li>
 * <li>{@code dedupIds} - remembered event ids for idempotent re-delivery (a ring, at least {@code maxEvents});</li>
 * <li>{@code windowNanos} - the active/correlation window measured from the latest ingested monotonic time (30 s);</li>
 * <li>{@code supportingIds} - event ids carried by a snapshot; {@code gapRecords} - recent uncertainty gaps carried by a snapshot.</li>
 * </ul>
 */
public record EvidenceBounds(int maxEvents, int maxGroups, int dedupIds, long windowNanos, int supportingIds, int gapRecords) {
    public static final EvidenceBounds DEFAULT = new EvidenceBounds(256, 128, 512, 30_000_000_000L, 16, 32);
    public EvidenceBounds {
        if (maxEvents < 1 || maxGroups < 1 || dedupIds < maxEvents || windowNanos <= 0 || supportingIds < 1 || gapRecords < 1) throw new IllegalArgumentException("evidence bounds");
    }
}
