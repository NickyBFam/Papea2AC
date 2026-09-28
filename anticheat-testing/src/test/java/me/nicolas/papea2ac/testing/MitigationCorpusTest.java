package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.model.*;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 corpus gates: the synthetic mitigation corpus matches every exact expectation; the whole Phase 8/9 legitimate corpus produces zero
 * policy eligibility, zero intents and zero setbacks; every complete INVALID of the impossible corpus is ONE Phase 9 event and therefore never a
 * setback (a single event is not the policy); and the final Phase 9 live result (22 183 transitions: 22 108 VALID, 75 UNCERTAIN, 0 INVALID)
 * replayed at the count level produces zero setbacks.
 */
class MitigationCorpusTest {
    @Test void theSyntheticCorpusMatchesEveryExactExpectation() {
        List<MitigationCorpus.Result> results = MitigationCorpus.synthetic();
        List<String> mismatches = new ArrayList<>(); for (var r : results) if (!r.matches()) mismatches.add(r.name() + ": expected " + r.expectedOutcome() + " " + r.expectedReason() + " got " + r.actualOutcome() + " " + r.actualReason());
        assertEquals(List.of(), mismatches);
        assertTrue(results.size() >= 38, "every section-52 case is present: " + results.size());
        Set<String> names = new HashSet<>(); for (var r : results) assertTrue(names.add(r.name()), "unique case " + r.name());
        // the corpus is deterministic: a second run is identical
        assertEquals(results.stream().map(MitigationCorpus.Result::json).toList(), MitigationCorpus.synthetic().stream().map(MitigationCorpus.Result::json).toList());
    }
    @Test void theLegitimateCorpusProducesZeroSetbacks() throws Exception {
        MitigationCorpus.Sink sink = new MitigationCorpus.Sink("legitimate");
        ValidationCorpus.Tally.SINK.set(sink);
        try {
            ValidationCorpus.fuzzCorpus(90_000, 2_000); ValidationCorpus.velocityCorpus(7_000, 1_500, true); ValidationCorpus.velocityCorpus(9_000, 200, false); ValidationCorpus.teleportCorpus(30_000, 500);
            List<String> failures = new ArrayList<>(); ValidationCorpus.nativeCorpus(ValidationCorpusTest.NATIVE, failures); assertTrue(failures.isEmpty(), failures.toString());
        } finally { ValidationCorpus.Tally.SINK.remove(); }
        assertEquals(32_451, sink.consumed, sink.json().toString()); assertEquals(0, sink.positive);
        assertEquals(0, sink.eligible, "ZERO policy eligibility: " + sink.json()); assertEquals(0, sink.proposed, "ZERO intents: " + sink.json());
    }
    @Test void aSingleControlledInvalidIsNeverASetback() {
        MitigationCorpus.Sink sink = new MitigationCorpus.Sink("impossible");
        ValidationCorpus.Tally.SINK.set(sink);
        try { ValidationCorpus.impossibleCorpus(7_000, 250); } finally { ValidationCorpus.Tally.SINK.remove(); }
        assertTrue(sink.positive >= 3_000, sink.json().toString());
        assertEquals(0, sink.eligible, "one complete INVALID -> one event -> never the policy: " + sink.json()); assertEquals(0, sink.proposed);
    }
    @Test void theFinalPhase9LiveResultReplayedAtCountLevelProducesZeroSetbacks() {
        // the exact live packet stream was not recorded (docs/PHASE_10_IMPLEMENTATION.md REAL-CLIENT); its complete Phase 9 outcome counts were:
        // 22 183 transitions = 22 108 VALID + 75 UNCERTAIN, 0 INVALID, 0 positive events - replayed here in a deterministic interleaving with anchors
        MitigationActorTest.H0 h = new MitigationActorTest.H0(); h.mode = EffectMode.ENFORCE; long t = MitigationCorpus.T0; long seq = 0; int valid = 0, uncertain = 0;
        for (int i = 0; i < 22_183; i++) {
            t += 50_000_000L; long a = seq; seq += 10;
            if (i % 296 == 17 && uncertain < 75) { h.feed(uncertain(h.key, 0, a, seq, t, List.of(UncertainCode.WORLD_INCOMPLETE), new ValidationRecord.Completeness(true, false, false, true, true, true, true, true, true, true, true, true, true))); uncertain++; }
            else { h.anchor(seq, t, MitigationCorpus.ANCHOR); h.feed(valid(h.key, 0, a, seq, t)); valid++; }
        }
        assertEquals(22_108, valid); assertEquals(75, uncertain);
        assertEquals(0, h.metrics.eligible.sum()); assertEquals(0, h.metrics.proposed.sum()); assertTrue(h.captured.isEmpty() && h.queued.isEmpty());
    }
}
