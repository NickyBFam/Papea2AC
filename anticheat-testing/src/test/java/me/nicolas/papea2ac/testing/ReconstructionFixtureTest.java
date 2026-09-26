package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Replay of the pinned Phase 6 reconstruction fixtures (§57): every catalog case must reproduce its pinned outcome summary and candidate-set digest. */
class ReconstructionFixtureTest {
    static final Path FIXTURES = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase9-artifacts/reconstruction-fixtures/reconstruction-fixtures.json");
    @Test void everyFixtureCaseReproducesItsPinnedSummary() throws Exception {
        assertTrue(Files.exists(FIXTURES), "run reconstructionFixtures first: " + FIXTURES);
        Map<String, Object> pinned = MiniJson.obj(MiniJson.obj(MiniJson.parse(Files.readString(FIXTURES, StandardCharsets.UTF_8))).get("cases"));
        List<String> failures = new ArrayList<>(); int checked = 0;
        for (ReconstructionFixtures.Case c : ReconstructionFixtures.all()) {
            Map<String, Object> p = MiniJson.obj(pinned.get(c.id()));
            if (p == null) { failures.add(c.id() + ": not pinned"); continue; }
            Map<String, Object> now = ReconstructionFixtures.summary(c.run().get());
            for (String k : List.of("status", "complete", "ambiguity", "matchingStates", "matchingHistories", "excludedExact", "excludedEnvelope", "undecided", "evaluations", "candidates", "candidateSetDigest"))
                if (!String.valueOf(now.get(k)).equals(String.valueOf(p.get(k)))) failures.add(c.id() + "." + k + ": now " + now.get(k) + " pinned " + p.get(k));
            checked++;
        }
        assertEquals(pinned.size(), checked, "every pinned case is still in the catalog");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(checked >= 20, "cases " + checked);
    }
}
