package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.BoundKind;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Rejection;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import org.junit.jupiter.api.Test;
/**
 * The Phase 9 proof gate: only a complete Phase 8 INVALID with an exhaustive proof becomes positive evidence. Every other classification and
 * every corrupted proof variant (no proof, zero hypotheses, zero enumeration, candidates remaining, a false completeness dimension, an
 * uncertainty reason, a legal candidate, a witness, a missing NO_LEGAL_INPUT, an incomplete timing hypothesis) is refused with its typed
 * rejection; the enum value alone is never trusted. Families come one-to-one from the typed reasons; the digest is a pure function of the
 * record's identity and proof content.
 */
class EvidenceEventTest {
    static ValidationRecord with(ValidationRecord r, Classification c, ValidationRecord.Completeness comp, List<UncertainCode> unc, List<InvalidCode> inv, ValidationRecord.ProofSummary proof, ValidationRecord.WorkSummary work, int witnesses) {
        return new ValidationRecord(r.session(), r.worldEpoch(), r.startSequence(), r.endSequence(), r.nanos(), c, comp, unc, inv, proof, r.residual(), work, r.provenance(), witnesses);
    }
    @Test void aCompleteInvalidBecomesOneEventWithTypedFamiliesAndMagnitude() {
        ValidationRecord r = invalid(100, 102, 1_000_000_000L);
        assertTrue(EvidenceEvent.check(r).isEmpty());
        EvidenceEvent e = EvidenceEvent.from(r).orElseThrow();
        assertEquals(64, e.id().length()); assertEquals(List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), e.invalidReasons());
        assertEquals(java.util.Set.of(Category.HORIZONTAL_EXCESS), e.categories()); assertEquals(0.1726, e.magnitude().horizontalExcess()); assertEquals(BoundKind.ENVELOPE, e.magnitude().bound()); assertFalse(e.magnitude().collisionPenetration());
        assertEquals(EvidenceEvent.digest(r), e.id(), "the id is the record digest");
        String d = e.describe().toLowerCase(java.util.Locale.ROOT); for (String w : new String[] { "cheat", "hack", "ban", "kick", "setback", "punish", "vl" }) assertFalse(d.contains(w), d);
    }
    @Test void validAndUncertainNeverBecomeEvidence() {
        assertEquals(Optional.of(Rejection.NOT_INVALID), EvidenceEvent.check(valid(1, 2, 0))); assertTrue(EvidenceEvent.from(valid(1, 2, 0)).isEmpty());
        for (UncertainCode c : UncertainCode.values()) { ValidationRecord u = uncertain(1, 2, 0, c); assertEquals(Optional.of(Rejection.NOT_INVALID), EvidenceEvent.check(u), c.name()); assertTrue(EvidenceEvent.from(u).isEmpty(), c.name()); }
    }
    @Test void everyCorruptedProofVariantIsRejectedWithItsTypedReason() {
        ValidationRecord ok = invalid(100, 102, 0);
        ValidationRecord.WorkSummary w = ok.work(); ValidationRecord.ProofSummary p = ok.proof();
        assertEquals(Optional.of(Rejection.PROOF_MISSING), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), ValidationRecord.ProofSummary.NONE, w, 0)));
        assertEquals(Optional.of(Rejection.PROOF_NOT_EXHAUSTIVE), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), new ValidationRecord.ProofSummary(true, 0, 1, 144, 144, 100, 44, 0, "o", "m", "c", "t", 7, List.of()), w, 0)));
        assertEquals(Optional.of(Rejection.PROOF_NOT_EXHAUSTIVE), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), new ValidationRecord.ProofSummary(true, 1, 1, 144, 0, 0, 0, 0, "o", "m", "c", "t", 7, List.of()), w, 0)), "nothing enumerated");
        assertEquals(Optional.of(Rejection.CANDIDATES_REMAINING), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), new ValidationRecord.ProofSummary(true, 1, 1, 144, 144, 100, 43, 1, "o", "m", "c", "t", 7, List.of()), w, 0)));
        // each of the 13 completeness dimensions individually
        for (int i = 0; i < 13; i++) {
            boolean[] f = new boolean[13]; java.util.Arrays.fill(f, true); f[i] = false;
            ValidationRecord.Completeness c = new ValidationRecord.Completeness(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11], f[12]);
            assertEquals(Optional.of(Rejection.COMPLETENESS_MISSING), EvidenceEvent.check(with(ok, Classification.INVALID, c, List.of(), ok.invalidReasons(), p, w, 0)), "dimension " + i);
        }
        assertEquals(Optional.of(Rejection.UNCERTAINTY_PRESENT), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(UncertainCode.SEARCH_LIMIT), ok.invalidReasons(), p, w, 0)));
        assertEquals(Optional.of(Rejection.LEGAL_CANDIDATE_PRESENT), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), p, new ValidationRecord.WorkSummary(1, 1, 0, 1, 1, 144), 0)));
        assertEquals(Optional.of(Rejection.LEGAL_CANDIDATE_PRESENT), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), p, new ValidationRecord.WorkSummary(1, 1, 1, 0, 1, 144), 0)), "an explaining hypothesis");
        assertEquals(Optional.of(Rejection.WITNESS_PRESENT), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), p, w, 1)));
        assertEquals(Optional.of(Rejection.NO_LEGAL_INPUT_MISSING), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), List.of(InvalidCode.HORIZONTAL_EXCESS), p, w, 0)));
        assertEquals(Optional.of(Rejection.PROOF_NOT_EXHAUSTIVE), EvidenceEvent.check(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), p, new ValidationRecord.WorkSummary(2, 1, 0, 0, 1, 144), 0)), "an incomplete timing hypothesis");
        for (ValidationRecord bad : List.of(with(ok, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), ValidationRecord.ProofSummary.NONE, w, 0), with(ok, Classification.INVALID, ok.completeness(), List.of(UncertainCode.SEARCH_LIMIT), ok.invalidReasons(), p, w, 0)))
            assertTrue(EvidenceEvent.from(bad).isEmpty());
    }
    @Test void familiesAreTypedFromTheReasonsAndNeverRenamed() {
        assertEquals(java.util.Set.of(Category.GENERAL_MOVEMENT_IMPOSSIBILITY), EvidenceEvent.from(invalid(SESSION, 0, 1, 2, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)).orElseThrow().categories(), "NO_LEGAL_INPUT alone is general impossibility, not Speed");
        var e = EvidenceEvent.from(invalid(SESSION, 0, 1, 2, 0, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.VERTICAL_EXCESS, InvalidCode.IMPOSSIBLE_DESCENT, InvalidCode.COLLISION_PENETRATION), 0, -0.5)).orElseThrow();
        assertEquals(java.util.Set.of(Category.VERTICAL_EXCESS, Category.IMPOSSIBLE_DESCENT, Category.COLLISION_PENETRATION), e.categories()); assertTrue(e.magnitude().collisionPenetration()); assertEquals(-0.5, e.magnitude().verticalExcess());
        assertEquals(java.util.Set.of(Category.UNEXPLAINED_DISPLACEMENT), EvidenceEvent.from(invalid(SESSION, 0, 1, 2, 0, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.UNEXPLAINED_DISPLACEMENT), 0, 0)).orElseThrow().categories());
    }
    @Test void theDigestIsDeterministicIgnoresTimeAndDistinguishesIdentityAndProof() {
        ValidationRecord a = invalid(100, 102, 1_000L), b = invalid(100, 102, 999_999_999L);
        assertEquals(EvidenceEvent.digest(a), EvidenceEvent.digest(b), "the monotonic time is not part of the identity");
        assertNotEquals(EvidenceEvent.digest(a), EvidenceEvent.digest(invalid(100, 103, 1_000L)), "another transition");
        assertNotEquals(EvidenceEvent.digest(a), EvidenceEvent.digest(invalid(SESSION, 1, 100, 102, 1_000L, a.invalidReasons(), 0.1726, 0)), "another world epoch");
        assertNotEquals(EvidenceEvent.digest(a), EvidenceEvent.digest(invalid(new me.nicolas.papea2ac.model.SessionKey(SESSION.player(), 2), 0, 100, 102, 1_000L, a.invalidReasons(), 0.1726, 0)), "another session");
        assertNotEquals(EvidenceEvent.digest(a), EvidenceEvent.digest(invalid(SESSION, 0, 100, 102, 1_000L, a.invalidReasons(), 0.1727, 0)), "another proven excess");
        assertEquals(EvidenceEvent.digest(a), EvidenceEvent.digest(invalid(100, 102, 1_000L)), "reproducible across constructions");
    }
}
