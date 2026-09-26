package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import me.nicolas.papea2ac.evidence.EvidenceBounds;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.evidence.EvidenceLedger.Outcome;
import me.nicolas.papea2ac.evidence.EvidenceSnapshot;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.model.SessionKey;
import org.junit.jupiter.api.Test;
/**
 * Deterministic property and metamorphic tests of the evidence engine over seeded random record streams (the seed is in every message):
 * VALID and UNCERTAIN never create positive evidence; a complete INVALID creates at most one event per transition; duplicate ingestion is
 * idempotent; the same stream yields an identical snapshot and identical digests; adding VALID or UNCERTAIN records cannot retroactively
 * create evidence; session replacement isolates state; world-epoch segmentation is obeyed; expired events leave the active window
 * deterministically; caps are never exceeded; missing proofs and internal-error records never create evidence; the snapshot is immutable.
 */
class EvidencePropertyTest {
    static final int SEEDS = 60;
    /** A random stream of records for one session: I/V/U with random reasons, gaps, epochs and the odd duplicate / corrupted INVALID. */
    static List<ValidationRecord> stream(long seed, SessionKey s, int n) {
        SplittableRandom r = new SplittableRandom(seed); List<ValidationRecord> out = new ArrayList<>(); long seq = 100, nanos = 0; long epoch = 0;
        for (int i = 0; i < n; i++) {
            int k = r.nextInt(100); long end = seq + 1 + r.nextInt(3); nanos += TICK * (1 + r.nextInt(8));
            if (r.nextInt(50) == 0) epoch++;
            ValidationRecord rec;
            if (k < 45) rec = valid(s, epoch, seq, end, nanos);
            else if (k < 75) rec = uncertain(s, epoch, seq, end, nanos, List.of(UncertainCode.values()[r.nextInt(UncertainCode.values().length)]), new ValidationRecord.Completeness(false, true, true, true, true, true, true, true, true, true, true, true, true));
            else if (k < 95) rec = invalid(s, epoch, seq, end, nanos, r.nextBoolean() ? List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS) : List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.VERTICAL_EXCESS, InvalidCode.IMPOSSIBLE_ASCENT), r.nextDouble(), r.nextDouble());
            else { ValidationRecord ok = invalid(s, epoch, seq, end, nanos, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0); rec = new ValidationRecord(s, epoch, seq, end, nanos, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), ValidationRecord.ProofSummary.NONE, ok.residual(), ok.work(), ok.provenance(), 0); } // corrupted: no proof
            out.add(rec); if (r.nextInt(10) == 0) out.add(rec); // an occasional duplicate delivery
            seq = end;
        }
        return out;
    }
    static EvidenceLedger run(SessionKey s, List<ValidationRecord> recs, EvidenceBounds b) { EvidenceLedger l = new EvidenceLedger(s, b); for (ValidationRecord r : recs) l.ingest(r); return l; }

    @Test void validAndUncertainNeverCreateEvidenceAndInvalidCreatesAtMostOnePerTransition() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            SessionKey s = new SessionKey(new UUID(9, seed), 1); List<ValidationRecord> recs = stream(seed, s, 300); EvidenceLedger l = new EvidenceLedger(s);
            java.util.Set<String> ids = new java.util.HashSet<>(); int events = 0, completeInvalid = 0;
            for (ValidationRecord r : recs) {
                Outcome o = l.ingest(r);
                if (r.valid() || r.uncertain()) assertTrue(o == Outcome.LEGITIMATE_WITNESS || o == Outcome.UNRESOLVED, "seed " + seed + " " + r.classification() + " -> " + o);
                if (o == Outcome.POSITIVE_EVENT) { events++; assertTrue(EvidenceEvent.check(r).isEmpty(), "seed " + seed); assertTrue(ids.add(EvidenceEvent.digest(r)), "seed " + seed + ": one event per identity"); }
                if (r.invalid() && EvidenceEvent.check(r).isEmpty() && ids.add(EvidenceEvent.digest(r) + "#c")) completeInvalid++;
                if (r.invalid() && EvidenceEvent.check(r).isPresent()) assertEquals(Outcome.PROOF_REJECTED, o, "seed " + seed + ": a corrupted proof never creates evidence");
            }
            assertEquals(completeInvalid, events, "seed " + seed + ": exactly one event per distinct complete INVALID transition");
            EvidenceSnapshot snap = l.snapshot(); assertEquals(events, snap.totalEvents() + snap.evictedEvents(), "seed " + seed); assertTrue(snap.totalEvents() <= EvidenceBounds.DEFAULT.maxEvents());
        }
    }
    @Test void theSameStreamYieldsAnIdenticalSnapshotAndIdenticalDigestsAndDuplicatesAreIdempotent() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            SessionKey s = new SessionKey(new UUID(9, seed), 1); List<ValidationRecord> recs = stream(seed, s, 200);
            EvidenceLedger a = run(s, recs, EvidenceBounds.DEFAULT), b = run(s, recs, EvidenceBounds.DEFAULT);
            assertEquals(a.snapshot(), b.snapshot(), "seed " + seed); assertEquals(a.events(), b.events(), "seed " + seed);
            List<ValidationRecord> twice = new ArrayList<>(); for (ValidationRecord r : recs) { twice.add(r); twice.add(r); }
            EvidenceLedger c = run(s, twice, EvidenceBounds.DEFAULT);
            assertEquals(a.snapshot().totalEvents(), c.snapshot().totalEvents(), "seed " + seed + ": duplicate ingestion adds no event"); assertEquals(a.events(), c.events(), "seed " + seed);
            assertEquals(a.snapshot().currentRun(), c.snapshot().currentRun(), "seed " + seed + ": a duplicate does not extend the run");
        }
    }
    @Test void addingValidOrUncertainNeverCreatesEvidenceRetroactivelyAndNeverRemovesHistory() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            SessionKey s = new SessionKey(new UUID(9, seed), 1); List<ValidationRecord> recs = stream(seed, s, 150); EvidenceLedger l = run(s, recs, EvidenceBounds.DEFAULT);
            EvidenceSnapshot before = l.snapshot(); long seq = recs.get(recs.size() - 1).endSequence(); long nanos = before.latestNanos() + TICK; long epoch = before.worldEpoch();
            SplittableRandom r = new SplittableRandom(seed);
            for (int i = 0; i < 40; i++) { seq += 2; nanos += TICK; l.ingest(r.nextBoolean() ? valid(s, epoch, seq - 2, seq, nanos) : uncertain(s, epoch, seq - 2, seq, nanos, List.of(UncertainCode.CLIMBABLE_CONTACT), new ValidationRecord.Completeness(true, true, true, true, true, true, true, true, true, false, true, true, true))); }
            EvidenceSnapshot after = l.snapshot();
            assertEquals(before.totalEvents(), after.totalEvents(), "seed " + seed); assertEquals(before.evictedEvents(), after.evictedEvents(), "seed " + seed); assertEquals(0, after.currentRun(), "seed " + seed); assertEquals(before.longestRun(), after.longestRun(), "seed " + seed);
            assertTrue(after.activeEvents() <= before.activeEvents(), "seed " + seed + ": aging can only expire");
        }
    }
    @Test void sessionReplacementIsolatesStateAndEpochSegmentationIsObeyed() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            SessionKey a = new SessionKey(new UUID(9, seed), 1), b = new SessionKey(new UUID(9, seed), 2);
            EvidenceLedger la = run(a, stream(seed, a, 120), EvidenceBounds.DEFAULT); EvidenceLedger lb = new EvidenceLedger(b);
            assertTrue(lb.snapshot().empty(), "seed " + seed); for (ValidationRecord r : stream(seed, a, 5)) assertEquals(Outcome.SESSION_MISMATCH, lb.ingest(r), "seed " + seed);
            assertTrue(lb.snapshot().empty()); assertFalse(la.snapshot().equals(lb.snapshot()) || la.snapshot().totalEvents() == 0 && la.snapshot().validObserved() == 0, "seed " + seed);
            // epoch: every active event carries the current epoch; the run never spans an epoch change
            EvidenceLedger l = new EvidenceLedger(a); long seq = 0, nanos = 0; int run = 0; long epoch = 0; SplittableRandom r = new SplittableRandom(seed * 31);
            for (int i = 0; i < 200; i++) { seq += 2; nanos += TICK; boolean change = r.nextInt(15) == 0; if (change) epoch++; Outcome o = l.ingest(invalid(a, epoch, seq - 2, seq, nanos, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)); if (change) run = 0; if (o == Outcome.POSITIVE_EVENT) run++; assertEquals(run, l.snapshot().currentRun(), "seed " + seed + " step " + i); }
            EvidenceSnapshot s = l.snapshot(); assertEquals(epoch, s.worldEpoch()); for (EvidenceEvent e : l.events()) if (e.worldEpoch() != epoch) assertTrue(s.expiredEvents() > 0);
            assertEquals(s.activeEvents(), (int) l.events().stream().filter(e -> e.worldEpoch() == s.worldEpoch() && s.latestNanos() - e.nanos() <= s.windowNanos()).count(), "seed " + seed);
        }
    }
    @Test void expiryIsDeterministicAndCapsAreNeverExceeded() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            SessionKey s = new SessionKey(new UUID(9, seed), 1); EvidenceBounds b = new EvidenceBounds(16, 6, 32, 2_000_000_000L, 4, 5);
            EvidenceLedger l = new EvidenceLedger(s, b); SplittableRandom r = new SplittableRandom(seed); long seq = 0, nanos = 0;
            for (int i = 0; i < 400; i++) {
                seq += 1 + r.nextInt(4); nanos += TICK * (1 + r.nextInt(30)); l.ingest(invalid(s, 0, seq - 1, seq, nanos, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), 0.1, 0));
                EvidenceSnapshot snap = l.snapshot();
                assertTrue(snap.totalEvents() <= b.maxEvents() && snap.totalGroups() <= b.maxGroups() && snap.supportingIds().size() <= b.supportingIds() && snap.groups().size() <= b.supportingIds(), "seed " + seed + " step " + i);
                final long now = nanos; int activeExpected = 0; for (EvidenceEvent e : l.events()) if (now - e.nanos() <= b.windowNanos()) activeExpected++;
                assertEquals(activeExpected, snap.activeEvents(), "seed " + seed + " step " + i);
            }
            EvidenceSnapshot far = l.snapshot(nanos + 10 * b.windowNanos()); assertEquals(0, far.activeEvents(), "seed " + seed); assertEquals(l.snapshot().totalEvents(), far.totalEvents(), "seed " + seed + ": expiry removes nothing from history");
            assertEquals(far, l.snapshot(nanos + 10 * b.windowNanos()), "seed " + seed + ": deterministic");
        }
    }
    @Test void anInternalErrorClassOrAMissingProofNeverCreatesEvidenceAndTheSnapshotIsImmutable() {
        SessionKey s = new SessionKey(new UUID(9, 77), 1); EvidenceLedger l = new EvidenceLedger(s);
        assertEquals(Outcome.UNRESOLVED, l.ingest(uncertain(s, 0, 1, 3, 0, List.of(UncertainCode.INTERNAL_ERROR), new ValidationRecord.Completeness(false, false, true, true, true, true, true, true, true, true, true, true, true))));
        ValidationRecord ok = invalid(s, 0, 3, 5, TICK, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0);
        assertEquals(Outcome.PROOF_REJECTED, l.ingest(new ValidationRecord(s, 0, 3, 5, TICK, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), ValidationRecord.ProofSummary.NONE, ok.residual(), ok.work(), ok.provenance(), 0)));
        EvidenceSnapshot snap = l.snapshot(); assertTrue(snap.empty()); assertEquals(1, snap.proofRejected()); assertEquals(1, snap.uncertainObserved());
        assertThrows(UnsupportedOperationException.class, () -> snap.gapReasons().clear()); assertThrows(UnsupportedOperationException.class, () -> snap.activeByCategory().add(null));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceBounds(0, 1, 1, 1, 1, 1)); assertThrows(IllegalArgumentException.class, () -> new EvidenceBounds(8, 1, 4, 1, 1, 1));
    }
}
