package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import me.nicolas.papea2ac.player.TimelineReplayMapper;

/** Fixture determinism: every fixture replays identically twice in-process AND matches the digest pinned in docs/phase2-artifacts/timeline-fixtures.json. */
class TimelineFixtureDeterminismTest {
    static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.getParent();
        assertNotNull(p, "repository root not found");
        return p;
    }

    @Test void everyFixtureReplaysIdenticallyOnRepeatedRuns() {
        for (var f : TimelineFixtures.all()) {
            var first = TimelineReplayMapper.replay(TimelineFixtures.capsule(f));
            var second = TimelineReplayMapper.replay(TimelineFixtures.capsule(f));
            assertEquals(first.records(), second.records(), f.name());
            assertEquals(first.finalState(), second.finalState(), f.name());
            assertEquals(TimelineFixtures.digest(first), TimelineFixtures.digest(second), f.name());
        }
    }

    @Test void everyFixtureMatchesItsPinnedDigest() throws Exception {
        String pinned = Files.readString(repoRoot().resolve("docs/phase2-artifacts/timeline-fixtures.json"));
        for (var f : TimelineFixtures.all()) {
            var m = Pattern.compile("\"" + f.name() + "\": \\{[^}]*\"digest\": \"([0-9a-f]{64})\"").matcher(pinned);
            assertTrue(m.find(), "no pinned digest for fixture " + f.name());
            assertEquals(m.group(1), TimelineFixtures.digest(TimelineReplayMapper.replay(TimelineFixtures.capsule(f))),
                    "fixture " + f.name() + " replays to a different timeline than the pinned artifact - a reviewed regeneration is required, never a silent update");
        }
    }

    @Test void theReconnectFixtureIsIndependentOfTheEarlierSessionsHistory() {
        var reconnect = TimelineFixtures.all().stream().filter(f -> f.name().equals("reconnectNewSession")).findFirst().orElseThrow();
        var replay = TimelineReplayMapper.replay(TimelineFixtures.capsule(reconnect));
        assertEquals(2, replay.records().size());
        assertEquals(0, replay.records().get(0).resultingState().teleport().diagnosticPredecessors().size(), "a new session starts from PlayerState.INITIAL, never from a previous session's components");
    }
}
