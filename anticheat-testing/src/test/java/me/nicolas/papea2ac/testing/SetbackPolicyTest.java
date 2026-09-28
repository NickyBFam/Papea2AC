package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.mitigation.SetbackPolicy;
import me.nicolas.papea2ac.model.SessionKey;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 Decision 1, policy {@value SetbackPolicy#VERSION}: 4 independent Phase 9 correlation groups, consecutive, same session, same world
 * epoch, same family, all active in the 30 s window; VALID / UNCERTAIN (incl. START_UNAVAILABLE and proof-rejected) / epoch change reset;
 * duplicates never count; a group counts once; magnitude never shortens the requirement. Fed through a REAL Phase 9 ledger exactly as the actor
 * feeds it (ingest -> outcome -> policy), so the grouping is Phase 9's, not a re-implementation.
 */
class SetbackPolicyTest {
    static final long S = 1_000_000_000L;
    static final List<InvalidCode> H = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), V = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.VERTICAL_EXCESS, InvalidCode.IMPOSSIBLE_ASCENT),
        HU = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS, InvalidCode.UNEXPLAINED_DISPLACEMENT);

    /** The owner's feeding rule (mirrors MitigationActor.onEvidence). */
    static final class Harness {
        final EvidenceLedger ledger; final SetbackPolicy policy; final SessionKey key;
        Harness(SessionKey key, EvidenceBounds bounds) { this.key = key; ledger = new EvidenceLedger(key, bounds); policy = new SetbackPolicy(key); }
        Harness() { this(SESSION, EvidenceBounds.DEFAULT); }
        EvidenceLedger.Outcome feed(ValidationRecord r) {
            EvidenceLedger.Outcome o = ledger.ingest(r);
            switch (o) {
                case POSITIVE_EVENT -> policy.onPositiveEvent(ledger.lastEvent().orElseThrow(), ledger.snapshot());
                case DUPLICATE -> policy.onDuplicate();
                default -> policy.onNonEvidence(Reason.INSUFFICIENT_EVIDENCE);
            }
            return o;
        }
        SetbackPolicy.Verdict verdict() { return policy.verdict(ledger.snapshot()); }
        SetbackPolicy.Verdict verdictAt(long nanos) { return policy.verdict(ledger.snapshot(nanos)); }
    }
    /** An independent group: an INVALID whose observation range does not touch the previous one's (the chain re-anchored in between). */
    static ValidationRecord group(int i, List<InvalidCode> codes, long nanos) { return invalid(SESSION, 0, 100L * i, 100L * i + 10, nanos, codes, 0.2, 0.0); }

    @Test void oneTwoOrThreeGroupsAreNeverEligibleAndFourAre() {
        Harness h = new Harness();
        for (int i = 1; i <= 3; i++) { assertEquals(EvidenceLedger.Outcome.POSITIVE_EVENT, h.feed(group(i, H, i * S))); var v = h.verdict(); assertFalse(v.eligible(), i + " group(s): " + v); assertEquals(i, v.groups()); assertEquals(Reason.INSUFFICIENT_EVIDENCE, v.reason()); }
        h.feed(group(4, H, 4 * S));
        var v = h.verdict();
        assertTrue(v.eligible(), v.toString()); assertEquals(Reason.POLICY_MET, v.reason()); assertEquals(4, v.groups()); assertEquals(Category.HORIZONTAL_EXCESS, v.family());
        assertEquals(4, new LinkedHashSet<>(v.groupIds()).size(), "4 distinct Phase 9 groups"); assertEquals(SetbackPolicy.VERSION, v.policyVersion());
        assertEquals(100L, v.firstSequence()); assertEquals(410L, v.lastSequence());
    }
    @Test void aConsecutiveRunOfTouchingInvalidsIsOneGroupAndNeverEligible() {
        Harness h = new Harness();
        for (int i = 0; i < 12; i++) h.feed(invalid(SESSION, 0, 10L * i, 10L * i + 10, (i + 1) * S / 10, H, 0.3, 0.0)); // each transition starts where the previous ended
        var v = h.verdict(); assertFalse(v.eligible(), v.toString()); assertEquals(1, v.groups(), "Phase 9 merges touching ranges: one causal family");
        assertEquals(12, h.ledger.snapshot().activeEvents());
    }
    // the four approved continuity patterns (I = complete INVALID of an independent group, U = uncertainty gap, V = validated transition)
    @Test void patternIIIIIsEligibleOnlyWithFourConsecutiveGroups() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, H, 3 * S));
        assertFalse(h.verdict().eligible(), "I I I");
        h.feed(group(4, H, 4 * S)); assertTrue(h.verdict().eligible(), "I I I I");
    }
    @Test void patternIUIResetsTheChain() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S));
        h.feed(uncertain(250, 260, 2 * S + 1, UncertainCode.WORLD_INCOMPLETE));
        h.feed(group(3, H, 3 * S)); h.feed(group(4, H, 4 * S)); h.feed(group(5, H, 5 * S));
        var v = h.verdict(); assertFalse(v.eligible(), "I I U I I I: only 3 groups after the gap: " + v); assertEquals(3, v.groups());
        h.feed(group(6, H, 6 * S)); assertTrue(h.verdict().eligible(), "four consecutive groups after the gap");
    }
    @Test void patternIVIResetsTheChain() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, H, 3 * S));
        h.feed(valid(SESSION, 0, 350, 360, 3 * S + 1));
        h.feed(group(4, H, 4 * S));
        var v = h.verdict(); assertFalse(v.eligible(), "I I I V I: " + v); assertEquals(1, v.groups());
    }
    @Test void patternIIUIResetsTheChain() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S));
        h.feed(uncertain(250, 260, 2 * S + 1, UncertainCode.START_UNAVAILABLE));
        h.feed(group(3, H, 3 * S));
        var v = h.verdict(); assertFalse(v.eligible(), "I I U I"); assertEquals(1, v.groups());
    }
    @Test void anUnanchoredTransmissionAndAProofRejectedInvalidResetTheChain() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, H, 3 * S));
        h.policy.onNonEvidence(Reason.INSUFFICIENT_EVIDENCE); // what the actor does for an UNCERTAIN [START_UNAVAILABLE] that never reaches Phase 9
        h.feed(group(4, H, 4 * S)); assertFalse(h.verdict().eligible());
        Harness r = new Harness();
        r.feed(group(1, H, S)); r.feed(group(2, H, 2 * S)); r.feed(group(3, H, 3 * S));
        ValidationRecord incomplete = new ValidationRecord(SESSION, 0, 350, 360, 3 * S + 1, EvidenceVocabulary.Classification.INVALID, ValidationRecord.Completeness.ALL, List.of(UncertainCode.SEARCH_LIMIT), H,
            proof(1), new ValidationRecord.ResidualSummary(true, 0.5, 0, 0.3274, 0.42, 0, EvidenceVocabulary.BoundKind.ENVELOPE, 0.2, 0), new ValidationRecord.WorkSummary(1, 1, 0, 0, 1, 144), PROV, 0);
        assertEquals(EvidenceLedger.Outcome.PROOF_REJECTED, r.feed(incomplete));
        r.feed(group(4, H, 4 * S)); assertFalse(r.verdict().eligible(), "a proof-rejected INVALID is an incomplete observation: it resets");
    }
    @Test void duplicatesNeverCountAndAGroupCountsOnceWhateverItsMembers() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); ValidationRecord third = group(3, H, 3 * S); h.feed(third);
        for (int i = 0; i < 5; i++) assertEquals(EvidenceLedger.Outcome.DUPLICATE, h.feed(third));
        assertFalse(h.verdict().eligible(), "re-delivery of one transition is one group"); assertEquals(3, h.verdict().groups());
        // more members in the same group (touching ranges) still count as one group
        h.feed(invalid(SESSION, 0, 310, 320, 3 * S + 2, H, 0.9, 0.0)); h.feed(invalid(SESSION, 0, 320, 330, 3 * S + 3, H, 1.7, 0.0));
        assertEquals(3, h.verdict().groups()); assertFalse(h.verdict().eligible());
    }
    @Test void differentFamiliesNeverCombine() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, V, 3 * S)); h.feed(group(4, H, 4 * S));
        var v = h.verdict(); assertFalse(v.eligible(), "H H V H: " + v);
        Harness g = new Harness();
        g.feed(group(1, V, S)); g.feed(group(2, V, 2 * S)); g.feed(group(3, V, 3 * S)); g.feed(group(4, V, 4 * S));
        assertTrue(g.verdict().eligible()); assertEquals(Category.VERTICAL_EXCESS, g.verdict().family(), "lowest-ordinal common family (VERTICAL_EXCESS < IMPOSSIBLE_ASCENT)");
    }
    @Test void aSharedFamilyCombinesAndTheChainFamilyIsTheIntersection() {
        Harness h = new Harness();
        h.feed(group(1, HU, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, HU, 3 * S)); h.feed(group(4, H, 4 * S));
        var v = h.verdict(); assertTrue(v.eligible(), v.toString()); assertEquals(Category.HORIZONTAL_EXCESS, v.family());
    }
    @Test void magnitudeNeverShortensTheRequirement() {
        Harness h = new Harness();
        for (int i = 1; i <= 3; i++) h.feed(invalid(SESSION, 0, 100L * i, 100L * i + 10, i * S, H, 25.0, 0.0));
        assertFalse(h.verdict().eligible(), "three enormous excesses are still three groups");
    }
    @Test void evidenceThatLeftTheWindowNeverCounts() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 12 * S)); h.feed(group(3, H, 23 * S)); h.feed(group(4, H, 34 * S)); // the first is 33 s old at the fourth
        var v = h.verdict(); assertFalse(v.eligible(), "only 3 groups are inside the 30 s window: " + v); assertEquals(3, v.groups());
        Harness k = new Harness();
        for (int i = 1; i <= 4; i++) k.feed(group(i, H, i * S));
        assertTrue(k.verdict().eligible());
        var aged = k.verdictAt(40 * S); assertFalse(aged.eligible(), "mitigation never resurrects expired evidence"); assertEquals(Reason.EVIDENCE_EXPIRED, aged.reason());
    }
    @Test void aWorldEpochChangeResetsAndEvidenceNeverCrossesIt() {
        Harness h = new Harness();
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, H, 3 * S));
        h.feed(invalid(SESSION, 1, 400, 410, 4 * S, H, 0.2, 0.0));
        var v = h.verdict(); assertFalse(v.eligible(), v.toString()); assertEquals(1, v.groups(), "only the new epoch's group");
    }
    @Test void anotherSessionsEvidenceIsRefused() {
        Harness h = new Harness(); SessionKey other = new SessionKey(new UUID(9, 2), 1);
        EvidenceLedger foreign = new EvidenceLedger(other);
        foreign.ingest(invalid(other, 0, 100, 110, S, H, 0.2, 0.0));
        h.policy.onPositiveEvent(foreign.lastEvent().orElseThrow(), foreign.snapshot());
        assertEquals(Reason.SESSION_MISMATCH, h.policy.lastReset()); assertEquals(Reason.SESSION_MISMATCH, h.policy.verdict(foreign.snapshot()).reason());
    }
    @Test void anIncompleteCorrelationForbidsCounting() {
        Harness h = new Harness(SESSION, new EvidenceBounds(256, 2, 512, 30 * S, 16, 32)); // only two groups retained: the third evicts one
        h.feed(group(1, H, S)); h.feed(group(2, H, 2 * S)); h.feed(group(3, H, 3 * S)); h.feed(group(4, H, 4 * S));
        assertTrue(h.ledger.snapshot().correlationIncomplete());
        var v = h.verdict(); assertFalse(v.eligible(), v.toString()); assertEquals(Reason.CORRELATION_INCOMPLETE, v.reason());
    }
    @Test void theSameStreamAlwaysYieldsTheSameVerdict() {
        List<ValidationRecord> stream = List.of(group(1, H, S), uncertain(150, 160, S + 1, UncertainCode.SEARCH_LIMIT), group(2, HU, 2 * S), group(3, H, 3 * S), group(4, HU, 4 * S), group(5, H, 5 * S));
        Harness a = new Harness(), b = new Harness();
        for (ValidationRecord r : stream) { a.feed(r); b.feed(r); }
        assertEquals(a.verdict(), b.verdict()); assertTrue(a.verdict().eligible());
    }
}
