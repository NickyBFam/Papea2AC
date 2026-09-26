package me.nicolas.papea2ac.evidence;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
/**
 * The immutable, bounded projection of one session's evidence state - the Phase 10 input boundary. It describes what the completed Phase 8
 * results prove over the active window and nothing more: counts, families, continuity, coverage, gaps, magnitudes, ages and the supporting
 * event ids (bounded). It carries no boolean about the player, no level, no probability, no recommendation; Phase 10 interprets it.
 *
 * <p>Continuity vocabulary (documented in PHASE_9_IMPLEMENTATION.md CONTINUITY): {@code currentRun} = consecutive complete INVALID
 * transitions with nothing but INVALID between them; a VALID or an UNCERTAIN (or a rejected INVALID) ends the run; a world-epoch change
 * segments everything; the active window is the last {@code windowNanos} of monotonic time before the latest ingested record.
 */
public record EvidenceSnapshot(SessionKey session, long worldEpoch, long latestNanos, long windowNanos,
                               int activeEvents, List<CategoryCount> activeByCategory, int totalEvents, int evictedEvents, int expiredEvents,
                               long firstActiveSequence, long lastActiveSequence, int currentRun, int longestRun,
                               long validObserved, long uncertainObserved, long invalidObserved, long proofRejected, long duplicates, long outOfOrder,
                               long validSinceLastInvalid, long uncertainSinceLastInvalid, List<GapCount> gapReasons,
                               int activeGroups, int totalGroups, int evictedGroups, boolean correlationIncomplete, List<GroupSummary> groups,
                               List<MagnitudeSummary> magnitudes, long newestEventAgeNanos, long oldestActiveAgeNanos, int epochSegments,
                               List<String> supportingIds, Classification lastClassification) {
    public record CategoryCount(Category category, int count) { public CategoryCount { Objects.requireNonNull(category); } }
    public record GapCount(UncertainCode reason, long count) { public GapCount { Objects.requireNonNull(reason); } }
    /** One correlation group: events whose observation ranges touch within the window (one causal family; its weight is its maximum member, never a sum). */
    public record GroupSummary(String id, int members, long firstSequence, long lastSequence, long firstNanos, long lastNanos, List<Category> categories, double maxHorizontalExcess, double maxVerticalExcess, boolean collisionPenetration, boolean active) {
        public GroupSummary { Objects.requireNonNull(id); categories = List.copyOf(categories); }
    }
    /** Per family over the active window: event count, maximum proven excess of each kind (typed, never summed across kinds). */
    public record MagnitudeSummary(Category category, int events, double maxHorizontalExcess, double maxVerticalExcess, double maxObservedHorizontal, int penetrations) { public MagnitudeSummary { Objects.requireNonNull(category); } }
    public EvidenceSnapshot {
        Objects.requireNonNull(session); activeByCategory = List.copyOf(activeByCategory); gapReasons = List.copyOf(gapReasons); groups = List.copyOf(groups); magnitudes = List.copyOf(magnitudes); supportingIds = List.copyOf(supportingIds); Objects.requireNonNull(lastClassification);
    }
    public boolean empty() { return totalEvents == 0; }
    /** Bounded neutral lines for the diagnostic; the vocabulary of the record and nothing more. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("evidence session=" + session.epoch() + " worldEpoch=" + worldEpoch + " active=" + activeEvents + "/" + totalEvents + " (evicted " + evictedEvents + ", expired " + expiredEvents + ") window=" + (windowNanos / 1_000_000L) + "ms run=" + currentRun + " longest=" + longestRun + " groups=" + activeGroups + "/" + totalGroups + (correlationIncomplete ? " CORRELATION_INCOMPLETE" : "") + " segments=" + epochSegments);
        out.add("  observed VALID=" + validObserved + " UNCERTAIN=" + uncertainObserved + " INVALID=" + invalidObserved + " proofRejected=" + proofRejected + " duplicates=" + duplicates + " outOfOrder=" + outOfOrder + " sinceLastInvalid: VALID=" + validSinceLastInvalid + " UNCERTAIN=" + uncertainSinceLastInvalid + " last=" + lastClassification);
        if (activeEvents > 0) out.add("  active seq " + firstActiveSequence + ".." + lastActiveSequence + " newestAge=" + (newestEventAgeNanos / 1_000_000L) + "ms oldestActiveAge=" + (oldestActiveAgeNanos / 1_000_000L) + "ms byCategory=" + activeByCategory);
        for (MagnitudeSummary m : magnitudes) out.add(String.format(Locale.ROOT, "  %s: events=%d maxExcess h=%.4f v=%.4f maxObservedH=%.4f penetrations=%d", m.category(), m.events(), m.maxHorizontalExcess(), m.maxVerticalExcess(), m.maxObservedHorizontal(), m.penetrations()));
        for (GroupSummary g : groups) out.add(String.format(Locale.ROOT, "  group %s members=%d seq=%d..%d %s maxExcess h=%.4f v=%.4f%s%s", g.id().substring(0, 12), g.members(), g.firstSequence(), g.lastSequence(), g.categories(), g.maxHorizontalExcess(), g.maxVerticalExcess(), g.collisionPenetration() ? " penetration" : "", g.active() ? "" : " (expired)"));
        if (!gapReasons.isEmpty()) out.add("  gaps=" + gapReasons);
        if (!supportingIds.isEmpty()) { List<String> short12 = new ArrayList<>(); for (String s : supportingIds) short12.add(s.substring(0, 12)); out.add("  supporting=" + short12); }
        return out;
    }
}
