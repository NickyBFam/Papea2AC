package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceSnapshot;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
/**
 * The versioned, human-approved evidence policy {@value #VERSION} (Phase 10 Decision 1). It answers one question - "does the Phase 9
 * evidence of this session meet the policy?" - and nothing about safety or targets. Session/shard-owned; every transition is driven by
 * the ordered observations the owner feeds it, so a replay of the same stream yields the same chain and the same verdict.
 *
 * <p>The rule, exactly as approved:
 * <ul>
 * <li>{@value #REQUIRED_GROUPS} independent Phase 9 correlation groups, consecutive (nothing but positive evidence between them);</li>
 * <li>all in the same {@link SessionKey} and world epoch, all active in the 30-second Phase 9 evidence window;</li>
 * <li>all of the same existing evidence family ({@link Category}, Phase 9 names unchanged);</li>
 * <li>only complete, actionable Phase 9 events count (the POSITIVE_EVENT outcome - the proof gate is Phase 9's); a duplicate never counts;</li>
 * <li>each group counts once whatever its member count (its contribution is its maximum member, never a sum - EVIDENCE_MODEL.md);</li>
 * <li>any VALID observation, any UNCERTAIN / incomplete observation (including an unanchored START_UNAVAILABLE and a proof-rejected
 *     INVALID), a world-epoch change or a correlation that Phase 9 marks incomplete resets the chain;</li>
 * <li>magnitude never shortens the requirement; there is no score, level, weight or confidence.</li>
 * </ul>
 *
 * <p>Family membership is conservative: a group belongs to family F only if EVERY member event of it carries F (the intersection over ALL
 * of its members ever seen, tracked per Phase 9 group id independently of chain resets), which is never more permissive than "its maximum
 * member carries F". The chain is simply the consecutive groups since the last reset (bounded, pruned by the window); the verdict looks at
 * the last {@value #REQUIRED_GROUPS} of them and requires a family common to all four - different families therefore never combine, and an
 * inserted reset can only shorten the chain to a suffix of what it would otherwise be (the metamorphic property "more uncertainty is never
 * more aggressive" holds by construction).
 *
 * <p>Note (documented in PHASE_10_IMPLEMENTATION.md): Phase 9 merges transitions whose observation ranges touch, so a consecutive run of
 * INVALID transitions is ONE group. Independent groups arise only when the validation chain re-anchors between INVALID bursts without an
 * intervening VALID/UNCERTAIN observation.
 */
public final class SetbackPolicy {
    public static final String VERSION = "movement-setback-v1";
    public static final int REQUIRED_GROUPS = 4;
    /** Chain entries kept (bounded; eligibility needs only {@value #REQUIRED_GROUPS}). */
    public static final int MAX_CHAIN = 8;
    /** Member event ids remembered per chain group (bounded; the group identity is Phase 9's group id). */
    public static final int MAX_MEMBER_IDS = 8;
    /** Group families remembered (bounded, least recently updated evicted; Phase 9 keeps at most 128 groups, the window a few dozen). */
    public static final int MAX_GROUP_FAMILIES = 64;

    /** One group of the current chain as the policy sees it. */
    public record ChainGroup(String groupId, Set<Category> family, long firstSequence, long lastSequence, long firstNanos, long lastNanos, int members, List<String> memberIds) {
        public ChainGroup { Objects.requireNonNull(groupId); EnumSet<Category> f = EnumSet.noneOf(Category.class); f.addAll(family); family = Collections.unmodifiableSet(f); memberIds = List.copyOf(memberIds); }
    }
    /** The policy answer: eligible or not, with the typed reason and the chain it was computed from. */
    public record Verdict(boolean eligible, Reason reason, int groups, Category family, List<ChainGroup> chain, long firstSequence, long lastSequence, String policyVersion) {
        public Verdict { chain = List.copyOf(chain); Objects.requireNonNull(reason); Objects.requireNonNull(policyVersion); }
        /** The Phase 9 group ids that met the policy (the decision's source evidence). */
        public List<String> groupIds() { List<String> out = new ArrayList<>(); for (ChainGroup g : chain) out.add(g.groupId()); return out; }
        public List<String> eventIds() { List<String> out = new ArrayList<>(); for (ChainGroup g : chain) out.addAll(g.memberIds()); return out; }
    }

    private final class Entry {
        final String groupId; long firstSeq, lastSeq, firstNanos, lastNanos; int members; final ArrayDeque<String> ids = new ArrayDeque<>();
        Entry(String groupId, EvidenceEvent e) { this.groupId = groupId; firstSeq = e.startSequence(); lastSeq = e.endSequence(); firstNanos = e.nanos(); lastNanos = e.nanos(); absorbIdOnly(e); }
        void absorb(EvidenceEvent e) { firstSeq = Math.min(firstSeq, e.startSequence()); lastSeq = Math.max(lastSeq, e.endSequence()); firstNanos = Math.min(firstNanos, e.nanos()); lastNanos = Math.max(lastNanos, e.nanos()); absorbIdOnly(e); }
        private void absorbIdOnly(EvidenceEvent e) { members++; if (!ids.contains(e.id())) { ids.addLast(e.id()); while (ids.size() > MAX_MEMBER_IDS) ids.removeFirst(); } }
        EnumSet<Category> family() { EnumSet<Category> f = groupFamilies.get(groupId); return f == null ? EnumSet.noneOf(Category.class) : EnumSet.copyOf(f); }
        ChainGroup view() { return new ChainGroup(groupId, family(), firstSeq, lastSeq, firstNanos, lastNanos, members, new ArrayList<>(ids)); }
    }

    private final SessionKey session;
    private final ArrayDeque<Entry> chain = new ArrayDeque<>();
    /** Per Phase 9 group: the categories EVERY member carried (intersection over all members seen, whatever the chain resets). */
    private final java.util.LinkedHashMap<String, EnumSet<Category>> groupFamilies = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, EnumSet<Category>> eldest) { return size() > MAX_GROUP_FAMILIES; }
    };
    private long chainEpoch = Long.MIN_VALUE;
    private Reason lastReset = Reason.NO_EVIDENCE;
    private long resets, positiveSeen, duplicatesIgnored;

    public SetbackPolicy(SessionKey session) { this.session = Objects.requireNonNull(session); }
    public SessionKey session() { return session; }

    /** Any observation that is not new positive evidence (VALID, UNCERTAIN incl. START_UNAVAILABLE, a proof-rejected INVALID): the chain resets. */
    public void onNonEvidence(Reason why) { reset(why); }
    /** A Phase 9 DUPLICATE: neither extends nor ends the chain (Phase 9 continuity semantics; one physical transition counts once). */
    public void onDuplicate() { duplicatesIgnored++; }
    /** A world-epoch change or any discontinuity the owner observes. */
    public void onEpochChange() { reset(Reason.WORLD_EPOCH_MISMATCH); }
    /** The owner applied (or began applying) a correction: evidence before it never combines with evidence after it. */
    public void onCorrection() { reset(Reason.RECOVERY); }

    /**
     * One POSITIVE_EVENT from Phase 9: {@code e} is the event, {@code snapshot} the ledger projection right after it was ingested. The event's
     * group is identified in the snapshot (the newest group whose sequence range contains the event); an event whose group cannot be
     * identified, or a snapshot flagged {@code correlationIncomplete}, resets the chain - disjointness is never assumed.
     */
    public void onPositiveEvent(EvidenceEvent e, EvidenceSnapshot snapshot) {
        Objects.requireNonNull(e); Objects.requireNonNull(snapshot); positiveSeen++;
        if (!e.session().equals(session) || !snapshot.session().equals(session)) { reset(Reason.SESSION_MISMATCH); return; }
        if (snapshot.correlationIncomplete()) { reset(Reason.CORRELATION_INCOMPLETE); return; }
        if (chainEpoch != Long.MIN_VALUE && e.worldEpoch() != chainEpoch) reset(Reason.WORLD_EPOCH_MISMATCH);
        String groupId = null;
        for (EvidenceSnapshot.GroupSummary g : snapshot.groups()) if (g.firstSequence() <= e.startSequence() && g.lastSequence() >= e.endSequence() && g.categories().containsAll(e.categories())) groupId = g.id();
        if (groupId == null) { reset(Reason.CORRELATION_UNRESOLVED); return; }
        chainEpoch = e.worldEpoch();
        EnumSet<Category> fam = groupFamilies.get(groupId);
        if (fam == null) groupFamilies.put(groupId, EnumSet.copyOf(e.categories())); else fam.retainAll(e.categories());
        Entry existing = null; for (Entry c : chain) if (c.groupId.equals(groupId)) existing = c;
        if (existing != null) {
            existing.absorb(e);
            if (existing != chain.peekLast()) reset(Reason.CORRELATION_UNRESOLVED); // a group re-entered out of order: never reorder the chain
            return;
        }
        chain.addLast(new Entry(groupId, e));
        while (chain.size() > MAX_CHAIN) chain.removeFirst();
    }

    /**
     * The verdict at the snapshot's time: groups that left the active window no longer count (evidence staleness - mitigation never
     * resurrects expired evidence); session and epoch must match.
     */
    public Verdict verdict(EvidenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (!snapshot.session().equals(session)) return notEligible(Reason.SESSION_MISMATCH, List.of());
        if (snapshot.correlationIncomplete()) { reset(Reason.CORRELATION_INCOMPLETE); return notEligible(Reason.CORRELATION_INCOMPLETE, List.of()); }
        if (!chain.isEmpty() && snapshot.worldEpoch() != chainEpoch) { reset(Reason.WORLD_EPOCH_MISMATCH); return notEligible(Reason.WORLD_EPOCH_MISMATCH, List.of()); }
        long cutoff = snapshot.latestNanos() - snapshot.windowNanos();
        boolean expired = false;
        while (!chain.isEmpty() && chain.peekFirst().lastNanos < cutoff) { chain.removeFirst(); expired = true; }
        List<ChainGroup> view = new ArrayList<>(); for (Entry c : chain) view.add(c.view());
        if (chain.isEmpty()) return notEligible(expired ? Reason.EVIDENCE_EXPIRED : Reason.NO_EVIDENCE, view);
        if (chain.size() < REQUIRED_GROUPS) {
            java.util.Set<Category> lastFamily = view.get(view.size() - 1).family(); // diagnostic only: the newest group's family
            return new Verdict(false, expired ? Reason.EVIDENCE_EXPIRED : Reason.INSUFFICIENT_EVIDENCE, chain.size(), lastFamily.isEmpty() ? null : lastFamily.iterator().next(), view, chain.peekFirst().firstSeq, chain.peekLast().lastSeq, VERSION);
        }
        // the most recent REQUIRED_GROUPS consecutive groups decide; their common family is the verdict's family (lowest ordinal: deterministic)
        List<ChainGroup> decisive = view.subList(view.size() - REQUIRED_GROUPS, view.size());
        EnumSet<Category> f = EnumSet.allOf(Category.class); for (ChainGroup g : decisive) f.retainAll(g.family());
        if (f.isEmpty()) return notEligible(Reason.INSUFFICIENT_EVIDENCE, view);
        return new Verdict(true, Reason.POLICY_MET, REQUIRED_GROUPS, f.iterator().next(), decisive, decisive.get(0).firstSequence(), decisive.get(decisive.size() - 1).lastSequence(), VERSION);
    }

    private Verdict notEligible(Reason r, List<ChainGroup> view) { return new Verdict(false, r, view.size(), null, view, view.isEmpty() ? -1 : view.get(0).firstSequence(), view.isEmpty() ? -1 : view.get(view.size() - 1).lastSequence(), VERSION); }
    private void reset(Reason why) { if (!chain.isEmpty()) resets++; chain.clear(); lastReset = why; }

    public int chainLength() { return chain.size(); }
    public Reason lastReset() { return lastReset; }
    public long resets() { return resets; }
    public long positiveSeen() { return positiveSeen; }
    public long duplicatesIgnored() { return duplicatesIgnored; }
}
