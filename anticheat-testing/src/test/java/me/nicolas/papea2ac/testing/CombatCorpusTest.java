package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.regex.Pattern;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import me.nicolas.papea2ac.protocol.Observation;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 attack corpus (brief section 54) and replay fixtures (section 55) through the production core composition ({@link CombatCorpus}): every
 * fixture's descriptive result equals its specification-derived expectation; no output carries verdict vocabulary; each pinned replay trace, fed
 * twice into fresh runners and once more through an encoded schema-3 transport capsule, reproduces the live state digest exactly.
 */
class CombatCorpusTest {
    static final Pattern VERDICT = Pattern.compile("(?i)\\b(cheat|hack|killaura|aura|reach|hitbox|violation|flag(ged)?|suspicious|illegal|invalid|verdict|score|ban|kick)\\b");

    @Test void everyFixtureHasItsExpectedDescriptiveResult() {
        var results = CombatCorpus.run();
        assertTrue(results.size() >= 21, "the brief's 21 cases, plus the lifecycle cases");
        List<String> mismatches = new ArrayList<>();
        for (var r : results) if (!r.matches()) mismatches.add(r.name() + "\n   expected " + r.expected() + "\n   observed " + r.observed());
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
        for (var r : results) {
            assertFalse(VERDICT.matcher(r.observed()).find(), "descriptive only: " + r.observed());
            for (String c : r.contexts()) assertFalse(VERDICT.matcher(c).find(), "descriptive only: " + c);
        }
    }
    @Test void theCorpusIsDeterministic() {
        var a = CombatCorpus.run(); var b = CombatCorpus.run();
        for (int i = 0; i < a.size(); i++) { assertEquals(a.get(i).observed(), b.get(i).observed()); assertEquals(a.get(i).digest(), b.get(i).digest(), a.get(i).name()); assertEquals(a.get(i).contexts(), b.get(i).contexts()); }
    }
    @Test void pinnedTracesReplayTwiceAndThroughTheCapsuleIdentically() throws Exception {
        Map<String, CombatCorpus.Result> byName = new HashMap<>(); for (var r : CombatCorpus.run()) byName.put(r.name(), r);
        boolean sawSync = false;
        for (var e : CombatCorpus.REPLAY_TRACES.entrySet()) {
            var r = byName.get(e.getValue().getFirst()); assertNotNull(r, e.getKey());
            String first = CombatCorpus.replay(r.key(), r.log()), second = CombatCorpus.replay(r.key(), r.log());
            assertEquals(first, second, e.getKey() + ": replaying twice"); assertEquals(r.digest(), first, e.getKey() + ": replay equals the live state");
            List<Observation> restored = CombatCorpus.throughCapsule(r.key(), r.log());
            assertEquals(r.log(), restored, e.getKey() + ": capsule round trip preserves every observation");
            assertEquals(first, CombatCorpus.replay(r.key(), restored), e.getKey() + ": capsule replay");
            for (var o : restored) if (o.event() instanceof Observation.Outbound out && out.payload() instanceof SemanticPayload.EntityPositionSync) sawSync = true;
        }
        assertTrue(sawSync, "a schema-3 capsule carrying EntityPositionSync (tag 80) round-tripped");
    }
}
