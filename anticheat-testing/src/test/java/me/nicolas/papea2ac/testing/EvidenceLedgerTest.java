package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.nicolas.papea2ac.evidence.EvidenceBounds;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.evidence.EvidenceLedger.Outcome;
import me.nicolas.papea2ac.evidence.EvidenceSnapshot;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.model.SessionKey;
import org.junit.jupiter.api.Test;
/**
 * The session evidence ledger: the ordering patterns of the Phase 9 brief (I I I, I V I, I U I, V I V, U I U, I I V V I) with their exact
 * continuity semantics, idempotent duplicates, out-of-order records, correlation groups (touching transitions = one causal family with a
 * maximum-member magnitude), the active window and expiry, world-epoch segmentation, session isolation, the bounds and their deterministic
 * eviction, and the snapshot's neutrality.
 */
class EvidenceLedgerTest {
    /** Feeds a pattern of I/V/U as consecutive transitions of 2 sequences each, one tick apart. */
    static EvidenceLedger feed(String pattern, UncertainCode u) {
        EvidenceLedger l = new EvidenceLedger(SESSION); long seq = 100, nanos = 1_000_000_000L;
        for (char c : pattern.toCharArray()) {
            ValidationRecord r = switch (c) { case 'I' -> invalid(seq, seq + 2, nanos); case 'V' -> valid(seq, seq + 2, nanos); case 'U' -> uncertain(seq, seq + 2, nanos, u); default -> throw new IllegalArgumentException(); };
            l.ingest(r); seq += 2; nanos += TICK;
        }
        return l;
    }
    @Test void orderingPatternsHaveExactContinuitySemantics() {
        EvidenceSnapshot iii = feed("III", null).snapshot();
        assertEquals(3, iii.activeEvents()); assertEquals(3, iii.currentRun()); assertEquals(3, iii.longestRun()); assertEquals(1, iii.activeGroups(), "three touching transitions are one causal family"); assertEquals(3, iii.groups().get(0).members());
        EvidenceSnapshot ivi = feed("IVI", null).snapshot();
        assertEquals(2, ivi.activeEvents()); assertEquals(1, ivi.currentRun(), "a VALID ends the run"); assertEquals(1, ivi.longestRun()); assertEquals(2, ivi.activeGroups(), "the VALID transition separates two families (the ranges do not touch)"); assertEquals(1, ivi.validObserved()); assertEquals(0, ivi.validSinceLastInvalid());
        EvidenceSnapshot iui = feed("IUI", UncertainCode.SEARCH_LIMIT).snapshot();
        assertEquals(2, iui.activeEvents()); assertEquals(1, iui.currentRun(), "an UNCERTAIN ends the run: nothing is bridged over a gap"); assertEquals(2, iui.activeGroups()); assertEquals(1, iui.uncertainObserved()); assertEquals(List.of(new EvidenceSnapshot.GapCount(UncertainCode.SEARCH_LIMIT, 1L)), iui.gapReasons());
        EvidenceSnapshot viv = feed("VIV", null).snapshot();
        assertEquals(1, viv.activeEvents()); assertEquals(0, viv.currentRun()); assertEquals(1, viv.longestRun()); assertEquals(1, viv.validSinceLastInvalid()); assertEquals(2, viv.validObserved()); assertEquals(Classification.VALID, viv.lastClassification());
        EvidenceSnapshot uiu = feed("UIU", UncertainCode.CLIMBABLE_CONTACT).snapshot();
        assertEquals(1, uiu.activeEvents()); assertEquals(0, uiu.currentRun()); assertEquals(1, uiu.uncertainSinceLastInvalid()); assertEquals(2, uiu.uncertainObserved()); assertEquals(List.of(new EvidenceSnapshot.GapCount(UncertainCode.CLIMBABLE_CONTACT, 2L)), uiu.gapReasons());
        EvidenceSnapshot iivvi = feed("IIVVI", null).snapshot();
        assertEquals(3, iivvi.activeEvents()); assertEquals(1, iivvi.currentRun()); assertEquals(2, iivvi.longestRun()); assertEquals(2, iivvi.activeGroups()); assertEquals(2, iivvi.groups().get(0).members()); assertEquals(1, iivvi.groups().get(1).members()); assertEquals(0, iivvi.validSinceLastInvalid());
        assertEquals(3, iivvi.totalEvents()); assertEquals(3, iivvi.invalidObserved()); assertEquals(2, iivvi.validObserved());
    }
    @Test void validAndUncertainNeverAddPositiveEvidenceAndAnEarlierEventIsNeverErasedByOneValid() {
        for (String p : new String[] { "VVVVVVVV", "VVUUVVUU", "UUUUUUUU" }) { EvidenceSnapshot s = feed(p, UncertainCode.WORLD_INCOMPLETE).snapshot(); assertEquals(0, s.activeEvents(), p); assertEquals(0, s.totalEvents(), p); assertTrue(s.empty(), p); }
        EvidenceSnapshot s = feed("IV", null).snapshot(); assertEquals(1, s.activeEvents(), "one VALID after an INVALID does not erase the event"); assertEquals(0, s.currentRun()); assertEquals(1, s.validSinceLastInvalid());
    }
    @Test void theSameTransitionDeliveredAgainIsADuplicateNotASecondEvent() {
        EvidenceLedger l = new EvidenceLedger(SESSION); ValidationRecord r = invalid(100, 102, 1_000L);
        assertEquals(Outcome.POSITIVE_EVENT, l.ingest(r)); assertEquals(Outcome.DUPLICATE, l.ingest(r)); assertEquals(Outcome.DUPLICATE, l.ingest(invalid(100, 102, 9_999L)), "another delivery time, same identity");
        EvidenceSnapshot s = l.snapshot(); assertEquals(1, s.activeEvents()); assertEquals(1, s.totalEvents()); assertEquals(2, s.duplicates()); assertEquals(1, s.currentRun(), "a duplicate does not extend the run"); assertEquals(3, s.invalidObserved());
        assertEquals(Outcome.POSITIVE_EVENT, l.ingest(invalid(102, 104, 2_000L))); assertEquals(Outcome.OUT_OF_ORDER, l.ingest(invalid(100, 102, 1_000L)), "a late re-delivery after a later record is out of order and ignored");
        assertEquals(2, l.snapshot().activeEvents()); assertEquals(1, l.snapshot().outOfOrder());
    }
    @Test void aRejectedInvalidIsAGapNeverEvidence() {
        EvidenceLedger l = new EvidenceLedger(SESSION); ValidationRecord ok = invalid(100, 102, 0);
        ValidationRecord noProof = new ValidationRecord(ok.session(), 0, 102, 104, TICK, Classification.INVALID, ok.completeness(), List.of(), ok.invalidReasons(), ValidationRecord.ProofSummary.NONE, ok.residual(), ok.work(), ok.provenance(), 0);
        ValidationRecord incomplete = new ValidationRecord(ok.session(), 0, 104, 106, 2 * TICK, Classification.INVALID, new ValidationRecord.Completeness(true, true, false, true, true, true, true, true, true, true, true, true, true), List.of(UncertainCode.WORLD_INCOMPLETE), ok.invalidReasons(), ok.proof(), ok.residual(), ok.work(), ok.provenance(), 0);
        assertEquals(Outcome.POSITIVE_EVENT, l.ingest(ok)); assertEquals(Outcome.PROOF_REJECTED, l.ingest(noProof)); assertEquals(Outcome.PROOF_REJECTED, l.ingest(incomplete));
        EvidenceSnapshot s = l.snapshot(); assertEquals(1, s.activeEvents()); assertEquals(2, s.proofRejected()); assertEquals(0, s.currentRun(), "a rejected INVALID ends the run like a gap"); assertEquals(List.of(new EvidenceSnapshot.GapCount(UncertainCode.WORLD_INCOMPLETE, 1L)), s.gapReasons());
    }
    @Test void touchingTransitionsFormOneGroupWithAMaximumMemberMagnitudeAndSeparatedOnesDoNot() {
        EvidenceLedger l = new EvidenceLedger(SESSION);
        l.ingest(invalid(SESSION, 0, 100, 102, 0, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), 0.10, 0));
        l.ingest(invalid(SESSION, 0, 102, 104, TICK, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), 0.25, 0));
        l.ingest(invalid(SESSION, 0, 104, 106, 2 * TICK, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.VERTICAL_EXCESS, InvalidCode.IMPOSSIBLE_ASCENT), 0, 0.58));
        l.ingest(invalid(SESSION, 0, 200, 202, 3 * TICK, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), 0.05, 0)); // a separate transition later
        EvidenceSnapshot s = l.snapshot();
        assertEquals(2, s.activeGroups()); EvidenceSnapshot.GroupSummary g = s.groups().get(0);
        assertEquals(3, g.members()); assertEquals(100, g.firstSequence()); assertEquals(106, g.lastSequence()); assertEquals(0.25, g.maxHorizontalExcess(), "maximum member, never a sum (0.35)"); assertEquals(0.58, g.maxVerticalExcess());
        assertTrue(g.categories().containsAll(List.of(Category.HORIZONTAL_EXCESS, Category.VERTICAL_EXCESS, Category.IMPOSSIBLE_ASCENT)));
        assertEquals(1, s.groups().get(1).members()); assertEquals(4, s.activeEvents());
        assertEquals(3, s.activeByCategory().stream().filter(c -> c.category() == Category.HORIZONTAL_EXCESS).findFirst().orElseThrow().count());
        var mh = s.magnitudes().stream().filter(m -> m.category() == Category.HORIZONTAL_EXCESS).findFirst().orElseThrow(); assertEquals(0.25, mh.maxHorizontalExcess()); assertEquals(3, mh.events());
    }
    @Test void theActiveWindowExpiresDeterministicallyFromTheLatestIngestedTimeAndTotalsRemain() {
        EvidenceBounds b = new EvidenceBounds(256, 128, 512, 1_000_000_000L, 16, 32); EvidenceLedger l = new EvidenceLedger(SESSION, b);
        l.ingest(invalid(100, 102, 0)); l.ingest(invalid(102, 104, 500_000_000L));
        assertEquals(2, l.snapshot().activeEvents());
        l.ingest(valid(104, 106, 1_200_000_000L)); // 1.2 s later: the first event (age 1.2 s) has left the 1 s window, the second (0.7 s) has not
        EvidenceSnapshot s = l.snapshot(); assertEquals(1, s.activeEvents()); assertEquals(2, s.totalEvents()); assertEquals(1, s.expiredEvents()); assertEquals(102, s.firstActiveSequence()); assertEquals(700_000_000L, s.newestEventAgeNanos());
        EvidenceSnapshot later = l.snapshot(3_000_000_000L); assertEquals(0, later.activeEvents(), "aging without a new record"); assertEquals(2, later.totalEvents()); assertEquals(0, later.activeGroups()); assertEquals(1, later.groups().size(), "the one (touching) group stays listed, expired");
        assertEquals(1, l.snapshot(100L).activeEvents(), "a snapshot never goes back in time before the latest ingest");
        assertEquals(1, s.groups().stream().filter(EvidenceSnapshot.GroupSummary::active).count());
    }
    @Test void aWorldEpochChangeSegmentsTheActiveStateButKeepsHistoryAndTotals() {
        EvidenceLedger l = new EvidenceLedger(SESSION);
        l.ingest(invalid(SESSION, 0, 100, 102, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)); l.ingest(invalid(SESSION, 0, 102, 104, TICK, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0));
        assertEquals(2, l.snapshot().currentRun());
        assertEquals(Outcome.POSITIVE_EVENT, l.ingest(invalid(SESSION, 1, 104, 106, 2 * TICK, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)));
        EvidenceSnapshot s = l.snapshot();
        assertEquals(1, s.epochSegments()); assertEquals(1, s.worldEpoch()); assertEquals(1, s.currentRun(), "no continuous run across the epoch boundary"); assertEquals(2, s.longestRun());
        assertEquals(1, s.activeEvents(), "only the new epoch's event is active"); assertEquals(3, s.totalEvents()); assertEquals(1, s.activeGroups(), "groups restart with the epoch"); assertEquals(2, s.expiredEvents());
        assertEquals(3, s.invalidObserved());
    }
    @Test void sessionsAreIsolatedAndForeignRecordsAreRefused() {
        SessionKey a = new SessionKey(new UUID(9, 2), 1), b = new SessionKey(new UUID(9, 2), 2); // the same player, a reconnect
        EvidenceLedger la = new EvidenceLedger(a), lb = new EvidenceLedger(b);
        assertEquals(Outcome.POSITIVE_EVENT, la.ingest(invalid(a, 0, 1, 3, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)));
        assertEquals(Outcome.SESSION_MISMATCH, lb.ingest(invalid(a, 0, 1, 3, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)), "session A's record never reaches session B");
        assertTrue(lb.snapshot().empty()); assertEquals(1, la.snapshot().activeEvents());
        assertNotEquals(EvidenceEvent.digest(invalid(a, 0, 1, 3, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)), EvidenceEvent.digest(invalid(b, 0, 1, 3, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)));
    }
    @Test void boundsAreEnforcedWithDeterministicEvictionAndCorrelationIncompleteness() {
        EvidenceBounds b = new EvidenceBounds(8, 4, 8, 3_600_000_000_000L, 3, 4); EvidenceLedger l = new EvidenceLedger(SESSION, b);
        long seq = 0; for (int i = 0; i < 20; i++) { l.ingest(invalid(seq, seq + 1, i * TICK)); seq += 10; } // non-touching transitions: one group each
        EvidenceSnapshot s = l.snapshot();
        assertEquals(8, s.totalEvents()); assertEquals(12, s.evictedEvents()); assertEquals(4, s.totalGroups()); assertEquals(16, s.evictedGroups()); assertTrue(s.correlationIncomplete(), "evicted groups forbid pretending disjointness"); assertEquals(3, s.supportingIds().size()); assertEquals(3, s.groups().size());
        assertEquals(191, s.lastActiveSequence()); assertEquals(120, s.firstActiveSequence(), "the oldest retained event is the 13th (0-based 12) at seq 120");
        assertEquals(20, s.invalidObserved()); assertEquals(20, s.currentRun(), "the run counts transitions, not retained events");
        // the dedup ring is bounded too: an evicted id delivered again is a new event only when its id fell out of the ring (bounded memory, documented)
        assertEquals(Outcome.DUPLICATE, l.ingest(invalid(190, 191, 19 * TICK)));
        for (UncertainCode c : UncertainCode.values()) l.ingest(uncertain(200 + c.ordinal() * 2, 201 + c.ordinal() * 2, 20 * TICK + c.ordinal(), c));
        assertEquals(UncertainCode.values().length, l.snapshot().gapReasons().size());
    }
    @Test void theSnapshotIsImmutableNeutralAndDescribable() {
        EvidenceLedger l = feed("IIUVI", UncertainCode.UNSUPPORTED_FLUID); EvidenceSnapshot s = l.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> s.supportingIds().add("x")); assertThrows(UnsupportedOperationException.class, () -> s.groups().clear());
        List<String> lines = s.describe(); assertTrue(lines.size() >= 3 && lines.size() <= 24, lines.toString());
        String all = String.join("\n", lines).toLowerCase(java.util.Locale.ROOT);
        for (String w : new String[] { "cheat", "hack", "ban", "kick", "setback", "punish", "probability", "confidence", "vl " }) assertFalse(all.contains(w), w);
        for (var m : EvidenceSnapshot.class.getRecordComponents()) { String n = m.getName().toLowerCase(java.util.Locale.ROOT); assertFalse(n.contains("cheat") || n.contains("ban") || n.contains("kick") || n.contains("setback") || n.contains("recommend") || n.contains("level"), n); }
        assertEquals(s, l.snapshot(), "the same state gives an equal snapshot");
        List<EvidenceEvent> evs = l.events(); assertEquals(3, evs.size()); List<String> ids = new ArrayList<>(); for (EvidenceEvent e : evs) ids.add(e.id()); assertEquals(3, new java.util.HashSet<>(ids).size());
    }
}
