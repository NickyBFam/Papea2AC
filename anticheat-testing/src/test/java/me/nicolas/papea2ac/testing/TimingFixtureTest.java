package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
/** Deterministic replay of the pinned timing fixtures (docs/phase9-artifacts/timing-fixtures/timing-fixtures.json - the P9-F3 re-pin; docs/phase7-artifacts keeps the Phase 7 pin): identical statuses, hypothesis ids, truth retention and digest per case. */
class TimingFixtureTest {
    static final Path FIXTURES = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase9-artifacts/timing-fixtures/timing-fixtures.json");

    @Test void everyFixtureCaseReproducesItsPinnedSummary() throws Exception {
        assertTrue(Files.exists(FIXTURES), "run gradlew.bat timingFixtures first");
        Map<String, Object> pinned = MiniJson.obj(MiniJson.obj(MiniJson.parse(Files.readString(FIXTURES, StandardCharsets.UTF_8))).get("cases"));
        List<String> failures = new ArrayList<>(); int n = 0, truthClaimed = 0, truthRetained = 0;
        for (TimingFixtures.Case c : TimingFixtures.all()) {
            Map<String, Object> p = MiniJson.obj(pinned.get(c.id())); assertNotNull(p, "no pinned summary for " + c.id());
            Map<String, Object> now = TimingFixtures.summary(c.build().get()); n++;
            for (String k : new String[] { "digest", "truthClaimed", "truthRetained", "events" }) if (!String.valueOf(p.get(k)).equals(String.valueOf(now.get(k)))) failures.add(c.id() + "." + k + ": now " + now.get(k) + " pinned " + p.get(k));
            List<Object> ps = MiniJson.arr(p.get("steps")), ns = MiniJson.arr(now.get("steps"));
            if (ps.size() != ns.size()) failures.add(c.id() + ".steps: " + ns.size() + " vs " + ps.size());
            else for (int i = 0; i < ps.size(); i++) for (String k : new String[] { "status", "timing", "hypotheses", "explaining", "complete" }) if (!String.valueOf(MiniJson.obj(ps.get(i)).get(k)).equals(String.valueOf(MiniJson.obj(ns.get(i)).get(k)))) failures.add(c.id() + ".steps[" + i + "]." + k + ": now " + MiniJson.obj(ns.get(i)).get(k) + " pinned " + MiniJson.obj(ps.get(i)).get(k));
            truthClaimed += MiniJson.integer(now.get("truthClaimed")); truthRetained += MiniJson.integer(now.get("truthRetained"));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(TimingFixtures.all().size(), n); assertEquals(truthClaimed, truthRetained, "every claimed truth retained");
        assertTrue(truthClaimed > 40, "truth claimed " + truthClaimed);
    }
    @Test void theSameCaseSummarizesIdenticallyTwice() throws Exception {
        TimingFixtures.Case c = TimingFixtures.all().get(7);
        assertEquals(TimingFixtures.summary(c.build().get()).get("digest"), TimingFixtures.summary(c.build().get()).get("digest"));
    }
}
