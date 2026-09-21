package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
/** Deterministic replay of the pinned validation fixtures (docs/phase8-artifacts/validation-fixtures/validation-fixtures.json): identical classification, reasons, completeness, counts and digest per case; every case meets its declared expectation. */
class ValidationFixtureTest {
    static final Path FIXTURES = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase8-artifacts/validation-fixtures/validation-fixtures.json");

    @Test void everyFixtureCaseReproducesItsPinnedSummaryAndMeetsItsExpectation() throws Exception {
        assertTrue(Files.exists(FIXTURES), "run gradlew.bat validationFixtures first");
        Map<String, Object> pinned = MiniJson.obj(MiniJson.obj(MiniJson.parse(Files.readString(FIXTURES, StandardCharsets.UTF_8))).get("cases"));
        List<String> failures = new ArrayList<>(); int n = 0;
        for (ValidationFixtures.Case c : ValidationFixtures.all()) {
            Map<String, Object> p = MiniJson.obj(pinned.get(c.id())); assertNotNull(p, "no pinned summary for " + c.id());
            Map<String, Object> now = ValidationFixtures.summary(c.build().get()); n++;
            for (String k : new String[] { "digest", "transmissions", "invalidWithProof" }) if (!String.valueOf(p.get(k)).equals(String.valueOf(now.get(k)))) failures.add(c.id() + "." + k + ": now " + now.get(k) + " pinned " + p.get(k));
            List<Object> ps = MiniJson.arr(p.get("steps")), ns = MiniJson.arr(now.get("steps"));
            if (ps.size() != ns.size()) failures.add(c.id() + ".steps: " + ns.size() + " vs " + ps.size());
            else for (int i = 0; i < ps.size(); i++) for (String k : new String[] { "classification", "valid", "uncertain", "invalid", "completeness", "timingHypotheses", "legal" }) if (!String.valueOf(MiniJson.obj(ps.get(i)).get(k)).equals(String.valueOf(MiniJson.obj(ns.get(i)).get(k)))) failures.add(c.id() + ".steps[" + i + "]." + k + ": now " + MiniJson.obj(ns.get(i)).get(k) + " pinned " + MiniJson.obj(ps.get(i)).get(k));
            Map<String, Object> last = MiniJson.obj(ns.get(ns.size() - 1));
            if (!c.expected().equals(last.get("classification"))) failures.add(c.id() + ": expected " + c.expected() + " got " + last.get("classification"));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(ValidationFixtures.all().size(), n);
    }
    @Test void theSameCaseSummarizesIdenticallyTwice() throws Exception {
        ValidationFixtures.Case c = ValidationFixtures.all().get(2);
        assertEquals(ValidationFixtures.summary(c.build().get()).get("digest"), ValidationFixtures.summary(c.build().get()).get("digest"));
    }
}
