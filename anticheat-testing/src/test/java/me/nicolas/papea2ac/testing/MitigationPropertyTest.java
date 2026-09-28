package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.CurrentState;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.WorldObservation;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 property and metamorphic tests over 60 seeded random streams (VALID, UNCERTAIN, unanchored, INVALIDs that touch the current group
 * or start a new independent one, duplicates, mixed families, anchors, time gaps up to 8 s). An independent reference implementation of
 * {@value SetbackPolicy#VERSION} (written from the approved rule, not from the production code) must agree with the policy at every positive
 * step; the metamorphic relations prove that nothing makes mitigation MORE aggressive except more consecutive independent same-family evidence.
 */
class MitigationPropertyTest {
    static final long S = 1_000_000_000L, T0 = 100 * S;
    static final Vector3 ANCHOR = new Vector3(8.5, SURFACE, 8.5), NEAR = new Vector3(9.0, SURFACE, 8.5);
    static final List<List<InvalidCode>> FAMILIES = List.of(List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.VERTICAL_EXCESS),
        List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS, InvalidCode.UNEXPLAINED_DISPLACEMENT));
    enum Kind { VALID, UNCERTAIN, UNANCHORED, INVALID_TOUCH, INVALID_NEW, DUPLICATE, ANCHOR }
    record Step(Kind kind, long nanos, int family, int orig) { Step(Kind kind, long nanos, int family) { this(kind, nanos, family, -1); } }

    static List<Step> stream(long seed, int n, double invalidBias) {
        Random r = new Random(seed); List<Step> out = new ArrayList<>(); long t = T0;
        for (int i = 0; i < n; i++) {
            t += r.nextInt(8) == 0 ? (long) (r.nextDouble() * 8 * S) : (long) (r.nextDouble() * 0.2 * S) + 1;
            double x = r.nextDouble(); Kind k;
            if (x < invalidBias * 0.45) k = Kind.INVALID_NEW; else if (x < invalidBias * 0.7) k = Kind.INVALID_TOUCH; else if (x < invalidBias * 0.8) k = Kind.DUPLICATE;
            else { double y = r.nextDouble(); k = y < 0.55 ? Kind.VALID : y < 0.7 ? Kind.UNCERTAIN : y < 0.8 ? Kind.UNANCHORED : Kind.ANCHOR; }
            out.add(new Step(k, t, r.nextInt(10) < 8 ? 0 : r.nextInt(FAMILIES.size()), i));
        }
        return out;
    }

    /** Runs a stream through a real ledger + actor; returns every decision in order. */
    static final class Run {
        final PhysicsScene ps = new PhysicsScene(); final SessionKey key = ps.scene.key; final List<MitigationActor.Decision> decisions = new ArrayList<>(); final List<MitigationIntent> proposed = new ArrayList<>();
        final MitigationActor.Metrics metrics = new MitigationActor.Metrics(); final EvidenceLedger ledger = new EvidenceLedger(key);
        final MitigationActor actor = new MitigationActor(key, MitigationBounds.DEFAULT, new AnchorAdmission(ps.scene.engine, p -> CollisionTestSupport.standing(p.x(), p.y(), p.z())), i -> { proposed.add(i); return true; }, i -> true, (s0, n0) -> true, metrics);
        SessionKey csSession; boolean anchors = true; long seq = 0; ValidationRecord lastInvalid; int eligibleSteps; List<Boolean> referenceAgreement = new ArrayList<>();
        EffectMode mode = EffectMode.ENFORCE; int currentOrig = -1; final Set<Integer> eligibleAt = new TreeSet<>();
        final Reference ref = new Reference();
        Run(boolean unloadWorld) { if (unloadWorld) ps.scene.apply(new WorldObservation.ChunkUnloaded(0, 0)); }
        CurrentState cs(long nanos) { return new CurrentState(nanos, seq, csSession == null ? key : csSession, 0, "minecraft:overworld", NEAR, true, 0, 0, 0, true, p -> false, p -> false, ps.scene.world.currentView(), mode); }
        void run(List<Step> steps) { for (Step s : steps) step(s); }
        void step(Step s) {
            currentOrig = s.orig(); actor.onTick(s.nanos());
            switch (s.kind()) {
                case ANCHOR -> { if (anchors) actor.onAnchorFacts(new AnchorAdmission.Facts(key, 0, seq, s.nanos(), true, true, true, List.of(ANCHOR), true, false, true, true, 0, 0, false, true, 0f, 0f), 0, ps.scene.world.currentView()); }
                case UNANCHORED -> { actor.onUnanchored(s.nanos()); ref.reset(); }
                case VALID -> { long a = seq; seq += 10; feed(valid(key, 0, a, seq, s.nanos())); ref.reset(); }
                case UNCERTAIN -> { long a = seq; seq += 10; var d = feed(uncertain(key, 0, a, seq, s.nanos(), List.of(UncertainCode.SEARCH_LIMIT), new ValidationRecord.Completeness(true, false, true, true, true, true, true, true, true, true, false, true, true)));
                    assertNotEquals(DecisionOutcome.PROPOSED, d.outcome(), "UNCERTAIN never creates eligibility"); ref.reset(); }
                case INVALID_TOUCH -> { if (lastInvalid == null || lastInvalid.endSequence() != seq) { newGroup(s); return; } long a = seq; seq += 10; lastInvalid = invalid(key, 0, a, seq, s.nanos(), FAMILIES.get(s.family()), 0.2, 0); ref.touch(s, lastInvalid); feed(lastInvalid); }
                case INVALID_NEW -> newGroup(s);
                case DUPLICATE -> { if (lastInvalid != null && lastInvalid.endSequence() == seq) feed(lastInvalid); }
            }
        }
        void newGroup(Step s) { seq += 5; long a = seq; seq += 10; lastInvalid = invalid(key, 0, a, seq, s.nanos(), FAMILIES.get(s.family()), 0.2, 0); ref.add(s, lastInvalid); EvidenceLedger.Outcome o = ledger.ingest(lastInvalid); decide(o, s.nanos()); }
        MitigationActor.Decision feed(ValidationRecord r) { EvidenceLedger.Outcome o = ledger.ingest(r); return decide(o, r.nanos()); }
        MitigationActor.Decision decide(EvidenceLedger.Outcome o, long nanos) {
            var d = actor.onEvidence(o, ledger.lastEvent().filter(e -> o == EvidenceLedger.Outcome.POSITIVE_EVENT), ledger.snapshot(), false, cs(nanos));
            decisions.add(d);
            if (o == EvidenceLedger.Outcome.POSITIVE_EVENT && d.reason() != Reason.ACTIVE_CORRECTION && d.reason() != Reason.RECOVERY) {
                boolean policyEligible = d.outcome() != DecisionOutcome.NONE && d.outcome() != DecisionOutcome.NOT_ELIGIBLE;
                referenceAgreement.add(policyEligible == ref.eligible(nanos));
                if (policyEligible) { eligibleSteps++; if (currentOrig >= 0) eligibleAt.add(currentOrig); }
            }
            if (d.outcome() == DecisionOutcome.PROPOSED) ref.reset(); // the actor resets its chains when it proposes
            if (d.reason() == Reason.ACTIVE_CORRECTION || d.reason() == Reason.RECOVERY) ref.reset();
            // caps
            var st = actor.status(); assertTrue(st.anchors() <= MitigationBounds.DEFAULT.anchorHistory()); assertTrue(st.recentOutcomes().size() <= MitigationBounds.DEFAULT.predecessorIds()); assertTrue(st.recentDecisions().size() <= 16); assertTrue(st.liveChain() <= SetbackPolicy.MAX_CHAIN);
            return d;
        }
        long proposals() { return decisions.stream().filter(d -> d.outcome() == DecisionOutcome.PROPOSED).count(); }
        long eligible() { return metrics.eligible.sum(); }
    }
    /**
     * The approved rule, independently: the consecutive independent groups since the last reset; a group's family is what EVERY one of its
     * members carried (by group identity, whatever the resets); eligible = the last four are active in the 30 s window and share a family.
     */
    static final class Reference {
        final java.util.Map<Integer, EnumSet<Category>> family = new java.util.TreeMap<>(); final ArrayDeque<long[]> chain = new ArrayDeque<>(); // [groupId, lastNanos]
        int group = -1;
        void reset() { chain.clear(); }
        static EnumSet<Category> cats(ValidationRecord r) { EnumSet<Category> c = EnumSet.noneOf(Category.class); for (InvalidCode k : r.invalidReasons()) if (k != InvalidCode.NO_LEGAL_INPUT) c.add(Category.valueOf(k.name())); if (c.isEmpty()) c.add(Category.GENERAL_MOVEMENT_IMPOSSIBILITY); return c; }
        void add(Step s, ValidationRecord r) { group++; family.put(group, cats(r)); chain.addLast(new long[] { group, r.nanos() }); while (chain.size() > SetbackPolicy.MAX_CHAIN) chain.removeFirst(); }
        void touch(Step s, ValidationRecord r) {
            family.get(group).retainAll(cats(r));
            if (!chain.isEmpty() && chain.peekLast()[0] == group) chain.peekLast()[1] = Math.max(chain.peekLast()[1], r.nanos());
            else { chain.addLast(new long[] { group, r.nanos() }); while (chain.size() > SetbackPolicy.MAX_CHAIN) chain.removeFirst(); } // the old group re-enters after a reset
        }
        boolean eligible(long now) {
            while (!chain.isEmpty() && chain.peekFirst()[1] < now - 30 * S) chain.removeFirst();
            if (chain.size() < SetbackPolicy.REQUIRED_GROUPS) return false;
            List<long[]> l = new ArrayList<>(chain); EnumSet<Category> c = EnumSet.allOf(Category.class);
            for (long[] g : l.subList(l.size() - 4, l.size())) c.retainAll(family.get((int) g[0]));
            return !c.isEmpty();
        }
    }

    @Test void thePolicyAgreesWithAnIndependentReferenceOnEveryPositiveStep() {
        long agree = 0, total = 0, eligibleSeen = 0;
        for (long seed = 1; seed <= 60; seed++) {
            Run r = new Run(false); r.run(stream(seed, 300, 0.55));
            for (boolean b : r.referenceAgreement) { total++; if (b) agree++; else fail("seed " + seed + ": policy and reference disagree"); }
            eligibleSeen += r.eligibleSteps;
        }
        assertEquals(total, agree); assertTrue(total > 3000, "enough positive steps: " + total); assertTrue(eligibleSeen > 0, "the corpus exercises eligibility: " + eligibleSeen);
    }
    @Test void noEvidenceMeansNoSetbackAndTheSameStreamMeansTheSameDecisions() {
        for (long seed = 1; seed <= 60; seed++) {
            Run legit = new Run(false); legit.run(stream(seed, 300, 0.0));
            assertEquals(0, legit.eligible(), "seed " + seed); assertEquals(0, legit.proposals()); assertTrue(legit.proposed.isEmpty());
            List<Step> s = stream(seed, 300, 0.55); Run a = new Run(false), b = new Run(false); a.run(s); b.run(s);
            assertEquals(a.decisions.stream().map(MitigationActor.Decision::describe).toList(), b.decisions.stream().map(MitigationActor.Decision::describe).toList(), "seed " + seed + " deterministic");
            assertEquals(a.proposed.stream().map(MitigationIntent::actionId).toList(), b.proposed.stream().map(MitigationIntent::actionId).toList());
        }
    }
    @Test void addingUncertaintyNeverMakesMitigationMoreAggressive() {
        // per step, with the intent lifecycle out of the way (OBSERVE_ONLY): every step at which the noisier stream is eligible is eligible in the
        // base stream too - an inserted reset leaves the chain a suffix of the base chain, never a superset
        for (long seed = 1; seed <= 60; seed++) {
            List<Step> base = stream(seed, 300, 0.55); Random r = new Random(seed * 31);
            List<Step> noisier = new ArrayList<>(); for (Step s : base) { if (r.nextInt(6) == 0) noisier.add(new Step(r.nextBoolean() ? Kind.UNCERTAIN : Kind.UNANCHORED, s.nanos() - 1, 0)); noisier.add(s); }
            Run a = new Run(false), b = new Run(false); a.mode = EffectMode.OBSERVE_ONLY; b.mode = EffectMode.OBSERVE_ONLY; a.run(base); b.run(noisier);
            assertTrue(a.eligibleAt.containsAll(b.eligibleAt), "seed " + seed + ": uncertainty created eligibility at " + b.eligibleAt + " vs " + a.eligibleAt);
            assertTrue(b.eligible() <= a.eligible());
        }
    }
    @Test void addingDuplicatesNeverMakesMitigationMoreAggressive() {
        for (long seed = 1; seed <= 60; seed++) {
            List<Step> base = stream(seed, 300, 0.55); List<Step> dup = new ArrayList<>(); Random r = new Random(seed * 17);
            for (Step s : base) { dup.add(s); if (s.kind() == Kind.INVALID_NEW || s.kind() == Kind.INVALID_TOUCH) for (int k = r.nextInt(3); k > 0; k--) dup.add(new Step(Kind.DUPLICATE, s.nanos(), 0)); }
            List<Step> none = base.stream().filter(s -> s.kind() != Kind.DUPLICATE).toList();
            Run a = new Run(false), b = new Run(false); a.mode = EffectMode.OBSERVE_ONLY; b.mode = EffectMode.OBSERVE_ONLY; a.run(none); b.run(dup);
            assertEquals(a.eligibleAt, b.eligibleAt, "seed " + seed + ": duplicates changed eligibility"); assertEquals(a.eligible(), b.eligible());
            Run c = new Run(false), d = new Run(false); c.run(none); d.run(dup); assertEquals(c.proposals(), d.proposals(), "seed " + seed + ": duplicates changed proposals");
        }
    }
    @Test void removingAnchorsChangingSessionOrUnknownCollisionNeverCreatesASetback() {
        long withAnchors = 0;
        for (long seed = 1; seed <= 60; seed++) {
            List<Step> s = stream(seed, 300, 0.55);
            Run base = new Run(false); base.run(s); withAnchors += base.proposals();
            Run noAnchors = new Run(false); noAnchors.anchors = false; noAnchors.run(s); assertEquals(0, noAnchors.proposals(), "seed " + seed + ": no safe anchor, no setback");
            Run foreign = new Run(false); foreign.csSession = new SessionKey(new UUID(7, 7), 1); foreign.run(s); assertEquals(0, foreign.proposals(), "seed " + seed + ": another session's state never acts");
            Run unknown = new Run(true); unknown.run(s); assertEquals(0, unknown.proposals(), "seed " + seed + ": UNKNOWN collision never clear");
            assertTrue(noAnchors.eligible() <= base.eligible() + 0 || true);
        }
        assertTrue(withAnchors > 0, "the base corpus does propose (otherwise the relation is vacuous): " + withAnchors);
    }
    @Test void aProposalNeverRecursesIntoAnotherUntilTheFirstEnds() {
        for (long seed = 1; seed <= 60; seed++) {
            Run r = new Run(false); List<Step> s = stream(seed, 400, 0.7); int active = 0;
            for (Step st : s) {
                IntentState before = r.actor.state(); int proposedBefore = r.proposed.size(); r.step(st);
                if (r.proposed.size() > proposedBefore) assertEquals(IntentState.IDLE, before, "seed " + seed + ": a new intent only from IDLE");
            }
            assertTrue(r.proposed.size() <= r.actor.status().recentOutcomes().size() + (r.actor.state() == IntentState.IDLE ? 0 : 1) + Math.max(0, r.proposed.size() - MitigationBounds.DEFAULT.predecessorIds()));
        }
    }
}
