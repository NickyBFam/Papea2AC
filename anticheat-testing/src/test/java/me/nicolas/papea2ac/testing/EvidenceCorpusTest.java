package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
/**
 * The Phase 9 corpus gates over the Phase 8 corpora, projected through core's mapper into one evidence ledger per case: the whole legitimate
 * corpus (fuzz, velocity with/without tick ends, teleport, native) creates ZERO positive evidence and zero proof rejections (the two
 * SEARCH_LIMIT UNCERTAINs included); every complete INVALID of the impossible corpus creates exactly one deduplicated event with the typed
 * family of its reasons; delivering every record twice yields the same event count.
 */
class EvidenceCorpusTest {
    @Test void theLegitimateCorporaCreateZeroPositiveEvidence() throws Exception {
        EvidenceCorpus.Sink sink = new EvidenceCorpus.Sink("legitimate", false);
        ValidationCorpus.Tally.SINK.set(sink);
        try {
            ValidationCorpus.fuzzCorpus(90_000, 2_000); ValidationCorpus.velocityCorpus(7_000, 1_500, true); ValidationCorpus.velocityCorpus(9_000, 200, false); ValidationCorpus.teleportCorpus(30_000, 500);
            List<String> failures = new ArrayList<>(); ValidationCorpus.nativeCorpus(ValidationCorpusTest.NATIVE, failures); assertTrue(failures.isEmpty(), failures.toString());
        } finally { ValidationCorpus.Tally.SINK.remove(); }
        // 32 451 since the P9-F3 re-pin of the native trajectories (the sneak scenarios now follow the pinned client's crouch timing; 32 314 before)
        assertEquals(32_451, sink.consumed, sink.json().toString()); assertEquals(0, sink.invalid); assertEquals(2, sink.uncertain);
        assertEquals(0, sink.events, "ZERO positive evidence from the legitimate corpus: " + sink.json()); assertEquals(0, sink.rejected); assertEquals(0, sink.duplicates);
        assertEquals(Map.of("SEARCH_LIMIT", 2), sink.gaps);
    }
    @Test void everyCompleteInvalidOfTheImpossibleCorpusCreatesExactlyOneDeduplicatedEvent() {
        EvidenceCorpus.Sink sink = new EvidenceCorpus.Sink("impossible", true);
        ValidationCorpus.Tally.SINK.set(sink);
        Map<String, ValidationCorpus.Tally> tallies;
        try { tallies = ValidationCorpus.impossibleCorpus(7_000, 250); } finally { ValidationCorpus.Tally.SINK.remove(); }
        int invalid = 0; for (ValidationCorpus.Tally t : tallies.values()) invalid += t.invalid;
        assertEquals(invalid, sink.invalid); assertEquals(invalid, sink.events, "one event per complete INVALID: " + sink.json()); assertEquals(0, sink.rejected); assertEquals(0, sink.duplicates);
        assertTrue(sink.events >= 3_000, sink.json().toString());
        assertTrue(sink.eventsByCategory.containsKey("HORIZONTAL_EXCESS") && sink.eventsByCategory.containsKey("IMPOSSIBLE_ASCENT") && sink.eventsByCategory.containsKey("COLLISION_PENETRATION") && sink.eventsByCategory.containsKey("UNEXPLAINED_DISPLACEMENT"), sink.eventsByCategory.toString());
        assertFalse(sink.eventsByCategory.containsKey("GENERAL_MOVEMENT_IMPOSSIBILITY"), "every Phase 8 INVALID of the corpus carries a typed axis reason");
        assertTrue(sink.maxH > 0.3 && sink.maxV >= 1.0, "magnitudes are the proven excesses: h=" + sink.maxH + " v=" + sink.maxV);
        sink.duplicatePass();
        assertEquals(sink.events, sink.duplicatePassEvents, "delivering every record twice creates no extra event"); assertEquals(sink.events, sink.duplicatePassDuplicates, "every second delivery is reported DUPLICATE");
    }
}
