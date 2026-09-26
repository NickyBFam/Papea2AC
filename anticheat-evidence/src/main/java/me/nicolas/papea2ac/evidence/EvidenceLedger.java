package me.nicolas.papea2ac.evidence;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Rejection;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
/**
 * One session's evidence accumulator (Phase 9 evidence engine; EVIDENCE_MODEL "Violation policy" front half: deduplicate by evidence id,
 * group by shared cause/window, reject uncertain/incomplete records for punitive accumulation). Owned by the session's shard thread
 * ({@link #ingest} asserts ownership); {@link #snapshot()} builds an immutable projection for other threads.
 *
 * <p>Semantics, all deterministic and driven by the records' own sequences and monotonic times (never a clock):
 * <ul>
 * <li>Order: records must arrive with non-decreasing end sequence; an earlier one is counted {@code outOfOrder} and ignored.</li>
 * <li>Identity: an INVALID record that passes the proof gate becomes an {@link EvidenceEvent} whose id is a content digest; the same id
 *     delivered again is a {@code DUPLICATE} (counted, nothing changes) - one physical transition counts once whatever the delivery path.</li>
 * <li>Coverage: VALID and UNCERTAIN add no positive evidence. Both end the consecutive-INVALID run; UNCERTAIN also records a coverage gap
 *     with its reason class. A rejected INVALID (no complete proof) counts as a gap too, never as evidence.</li>
 * <li>Correlation: events of the same world epoch whose observation ranges touch or overlap (the end observation of one transition is the
 *     start observation of the next) and that fall within the correlation window form one group - one causal family whose magnitude is
 *     its maximum member, never a sum (EVIDENCE_MODEL: no independent strikes for one root). Groups are capped; eviction sets
 *     {@code correlationIncomplete} rather than pretending disjointness.</li>
 * <li>Window: the active window is the last {@code windowNanos} before the latest ingested time; older events expire from the active
 *     view deterministically (they stay in the bounded history and totals; expiry never means the Phase 8 result was wrong).</li>
 * <li>World epoch: a record from a new epoch segments the state - the active window, groups and run restart; history and totals remain.</li>
 * <li>Bounds: events, groups, dedup ids and gap records are rings; the oldest is evicted and counted.</li>
 * </ul>
 * No decay function is defined by EVIDENCE_MODEL for this stage, so none is invented: evidence is auditable from events and windows.
 */
public final class EvidenceLedger {
    /** The outcome of one ingest (also the fixed metric families). */
    public enum Outcome { LEGITIMATE_WITNESS, UNRESOLVED, POSITIVE_EVENT, DUPLICATE, PROOF_REJECTED, OUT_OF_ORDER, SESSION_MISMATCH }

    private static final class Group {
        final String id; final long epoch; long firstSeq, lastSeq, firstNanos, lastNanos; int members; final EnumSet<Category> categories = EnumSet.noneOf(Category.class); double maxH, maxV; boolean penetration;
        Group(EvidenceEvent e) { id = e.id(); epoch = e.worldEpoch(); firstSeq = e.startSequence(); lastSeq = e.endSequence(); firstNanos = e.nanos(); lastNanos = e.nanos(); absorb(e); }
        void absorb(EvidenceEvent e) { members++; firstSeq = Math.min(firstSeq, e.startSequence()); lastSeq = Math.max(lastSeq, e.endSequence()); firstNanos = Math.min(firstNanos, e.nanos()); lastNanos = Math.max(lastNanos, e.nanos()); categories.addAll(e.categories()); maxH = Math.max(maxH, e.magnitude().horizontalExcess()); maxV = Math.max(maxV, Math.abs(e.magnitude().verticalExcess())); penetration |= e.magnitude().collisionPenetration(); }
        boolean touches(EvidenceEvent e, long windowNanos) { return e.worldEpoch() == epoch && e.startSequence() <= lastSeq && e.endSequence() >= firstSeq && e.nanos() - lastNanos <= windowNanos; }
    }
    private final SessionKey session; private final EvidenceBounds bounds;
    private final ArrayDeque<EvidenceEvent> events = new ArrayDeque<>(); private final LinkedHashSet<String> knownIds = new LinkedHashSet<>(); private final ArrayDeque<Group> groups = new ArrayDeque<>();
    private final EnumMap<UncertainCode, Long> gapReasons = new EnumMap<>(UncertainCode.class); private final ArrayDeque<UncertainCode> recentGaps = new ArrayDeque<>();
    private long validObserved, uncertainObserved, invalidObserved, proofRejected, duplicates, outOfOrder, validSinceInvalid, uncertainSinceInvalid, evictedEvents, evictedGroups;
    private int currentRun, longestRun, epochSegments; private long latestNanos = Long.MIN_VALUE, lastEndSequence = Long.MIN_VALUE, currentEpoch; private boolean epochKnown, correlationIncomplete;
    private Classification lastClassification = Classification.UNCERTAIN; private Rejection lastRejection; private EvidenceEvent lastEvent;
    private Thread owner;

    public EvidenceLedger(SessionKey session, EvidenceBounds bounds) { this.session = Objects.requireNonNull(session); this.bounds = Objects.requireNonNull(bounds); }
    public EvidenceLedger(SessionKey session) { this(session, EvidenceBounds.DEFAULT); }
    private void assertOwner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("evidence ledger ownership"); }
    public SessionKey session() { return session; }
    public EvidenceBounds bounds() { return bounds; }

    /** Consumes one completed Phase 8 result projection. Never throws for a well-formed record; a record of another session is refused. */
    public Outcome ingest(ValidationRecord r) {
        assertOwner(); Objects.requireNonNull(r);
        if (!r.session().equals(session)) return Outcome.SESSION_MISMATCH;
        if (lastEndSequence != Long.MIN_VALUE && r.endSequence() < lastEndSequence) { outOfOrder++; return Outcome.OUT_OF_ORDER; }
        // a world-epoch change segments the state before the record is considered
        if (epochKnown && r.worldEpoch() != currentEpoch) segment();
        currentEpoch = r.worldEpoch(); epochKnown = true;
        lastEndSequence = r.endSequence(); latestNanos = Math.max(latestNanos, r.nanos()); lastClassification = r.classification();
        switch (r.classification()) {
            case VALID -> { validObserved++; validSinceInvalid++; currentRun = 0; return Outcome.LEGITIMATE_WITNESS; }
            case UNCERTAIN -> { uncertainObserved++; uncertainSinceInvalid++; currentRun = 0; gap(r); return Outcome.UNRESOLVED; }
            case INVALID -> {
                invalidObserved++;
                Optional<Rejection> rej = EvidenceEvent.check(r);
                if (rej.isPresent()) { proofRejected++; lastRejection = rej.get(); currentRun = 0; uncertainSinceInvalid++; gapRejected(r); return Outcome.PROOF_REJECTED; }
                EvidenceEvent e = EvidenceEvent.from(r).orElseThrow();
                if (knownIds.contains(e.id())) { duplicates++; return Outcome.DUPLICATE; }
                remember(e); return Outcome.POSITIVE_EVENT;
            }
        }
        throw new IllegalStateException("classification");
    }
    private void remember(EvidenceEvent e) {
        knownIds.add(e.id()); while (knownIds.size() > bounds.dedupIds()) { Iterator<String> it = knownIds.iterator(); it.next(); it.remove(); }
        events.addLast(e); while (events.size() > bounds.maxEvents()) { events.removeFirst(); evictedEvents++; }
        lastEvent = e; currentRun++; longestRun = Math.max(longestRun, currentRun); validSinceInvalid = 0; uncertainSinceInvalid = 0;
        Group g = null; for (Group c : groups) if (c.touches(e, bounds.windowNanos())) { g = c; break; }
        if (g != null) g.absorb(e);
        else { groups.addLast(new Group(e)); while (groups.size() > bounds.maxGroups()) { groups.removeFirst(); evictedGroups++; correlationIncomplete = true; } }
    }
    private void gap(ValidationRecord r) {
        for (UncertainCode c : r.uncertainReasons()) { gapReasons.merge(c, 1L, Long::sum); recentGaps.addLast(c); }
        if (r.uncertainReasons().isEmpty()) { gapReasons.merge(UncertainCode.INTERNAL_ERROR, 1L, Long::sum); recentGaps.addLast(UncertainCode.INTERNAL_ERROR); }
        while (recentGaps.size() > bounds.gapRecords()) recentGaps.removeFirst();
    }
    private void gapRejected(ValidationRecord r) { for (UncertainCode c : r.uncertainReasons()) gapReasons.merge(c, 1L, Long::sum); }
    private void segment() {
        epochSegments++; currentRun = 0; correlationIncomplete = false;
        groups.clear(); // events and groups of the previous epoch leave the active view (the events stay in history and totals)
    }
    /** The latest ingested monotonic time (window anchor); Long.MIN_VALUE before any record. */
    public long latestNanos() { return latestNanos; }
    public Optional<EvidenceEvent> lastEvent() { return Optional.ofNullable(lastEvent); }
    public Optional<Rejection> lastRejection() { return Optional.ofNullable(lastRejection); }

    /** Immutable projection at the latest ingested time (deterministic: no clock is read). */
    public EvidenceSnapshot snapshot() { return snapshot(latestNanos); }
    /** Immutable projection as of {@code asOfNanos} (>= the latest ingested time; used by the runner to age evidence between records). */
    public EvidenceSnapshot snapshot(long asOfNanos) {
        long now = Math.max(asOfNanos, latestNanos);
        EnumMap<Category, Integer> byCat = new EnumMap<>(Category.class); EnumMap<Category, double[]> mag = new EnumMap<>(Category.class); EnumMap<Category, Integer> pen = new EnumMap<>(Category.class);
        int active = 0; long firstSeq = 0, lastSeq = 0, newestAge = 0, oldestAge = 0; boolean any = false; List<String> supporting = new ArrayList<>();
        for (EvidenceEvent e : events) {
            boolean inWindow = e.worldEpoch() == currentEpoch && now - e.nanos() <= bounds.windowNanos();
            if (!inWindow) continue;
            active++; if (!any) { firstSeq = e.startSequence(); oldestAge = now - e.nanos(); any = true; } lastSeq = e.endSequence(); newestAge = now - e.nanos();
            for (Category c : e.categories()) { byCat.merge(c, 1, Integer::sum); double[] m = mag.computeIfAbsent(c, k -> new double[3]); m[0] = Math.max(m[0], e.magnitude().horizontalExcess()); m[1] = Math.max(m[1], Math.abs(e.magnitude().verticalExcess())); m[2] = Math.max(m[2], e.magnitude().observedHorizontal()); if (e.magnitude().collisionPenetration()) pen.merge(c, 1, Integer::sum); }
        }
        // supporting ids: the most recent active events, bounded
        List<EvidenceEvent> recent = new ArrayList<>(); Iterator<EvidenceEvent> it = events.descendingIterator();
        while (it.hasNext() && recent.size() < bounds.supportingIds()) { EvidenceEvent e = it.next(); if (e.worldEpoch() == currentEpoch && now - e.nanos() <= bounds.windowNanos()) recent.add(e); }
        for (int i = recent.size() - 1; i >= 0; i--) supporting.add(recent.get(i).id());
        List<EvidenceSnapshot.CategoryCount> cats = new ArrayList<>(); for (Map.Entry<Category, Integer> en : byCat.entrySet()) cats.add(new EvidenceSnapshot.CategoryCount(en.getKey(), en.getValue()));
        List<EvidenceSnapshot.MagnitudeSummary> mags = new ArrayList<>(); for (Map.Entry<Category, double[]> en : mag.entrySet()) mags.add(new EvidenceSnapshot.MagnitudeSummary(en.getKey(), byCat.get(en.getKey()), en.getValue()[0], en.getValue()[1], en.getValue()[2], pen.getOrDefault(en.getKey(), 0)));
        List<EvidenceSnapshot.GroupSummary> gs = new ArrayList<>(); int activeGroups = 0; int shown = 0;
        for (Iterator<Group> gi = groups.descendingIterator(); gi.hasNext();) { Group g = gi.next(); boolean act = now - g.lastNanos <= bounds.windowNanos(); if (act) activeGroups++; if (shown < bounds.supportingIds()) { gs.add(0, new EvidenceSnapshot.GroupSummary(g.id, g.members, g.firstSeq, g.lastSeq, g.firstNanos, g.lastNanos, new ArrayList<>(g.categories), g.maxH, g.maxV, g.penetration, act)); shown++; } }
        List<EvidenceSnapshot.GapCount> gaps = new ArrayList<>(); for (Map.Entry<UncertainCode, Long> en : gapReasons.entrySet()) gaps.add(new EvidenceSnapshot.GapCount(en.getKey(), en.getValue()));
        int expiredNow = 0; for (EvidenceEvent e : events) if (!(e.worldEpoch() == currentEpoch && now - e.nanos() <= bounds.windowNanos())) expiredNow++;
        return new EvidenceSnapshot(session, currentEpoch, latestNanos == Long.MIN_VALUE ? 0 : now, bounds.windowNanos(), active, cats, events.size(), (int) Math.min(Integer.MAX_VALUE, evictedEvents), expiredNow, firstSeq, lastSeq, currentRun, longestRun,
            validObserved, uncertainObserved, invalidObserved, proofRejected, duplicates, outOfOrder, validSinceInvalid, uncertainSinceInvalid, gaps, activeGroups, groups.size(), (int) Math.min(Integer.MAX_VALUE, evictedGroups), correlationIncomplete, gs, mags, newestAge, oldestAge, epochSegments, supporting, lastClassification);
    }
    /** Bounded copy of the retained events (oldest first; diagnostics and replay comparison). */
    public List<EvidenceEvent> events() { return List.copyOf(events); }
}
