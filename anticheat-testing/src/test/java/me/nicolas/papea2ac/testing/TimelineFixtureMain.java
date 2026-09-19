package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import me.nicolas.papea2ac.player.TimelineReplayMapper;

/** Writes docs/phase2-artifacts/timeline-fixtures.json: the pinned per-fixture replay digests TimelineFixtureDeterminismTest asserts against. */
public final class TimelineFixtureMain {
    private TimelineFixtureMain() {}
    public static void main(String[] args) throws IOException {
        var json = new StringBuilder("{\n  \"canonicalization\": \"SHA-256 over one line per replayed record: captureSequence|kind|worldEpoch|uncertaintyReasons|transportDegradeReason|resultingState (record toString)\",\n  \"fixtures\": {\n");
        var fixtures = TimelineFixtures.all();
        for (int i = 0; i < fixtures.size(); i++) {
            var f = fixtures.get(i);
            var replay = TimelineReplayMapper.replay(TimelineFixtures.capsule(f));
            json.append("    \"").append(f.name()).append("\": {\"steps\": ").append(f.steps().size()).append(", \"sessionEpoch\": ").append(f.sessionEpoch())
                .append(", \"finalWorldEpoch\": ").append(replay.finalState().worldEpoch()).append(", \"digest\": \"").append(TimelineFixtures.digest(replay)).append("\"}")
                .append(i + 1 < fixtures.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString());
        System.out.print(json);
    }
}
