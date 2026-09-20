package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import me.nicolas.papea2ac.version.CollisionProfile;

/** Writes docs/phase4-artifacts/collision-fixtures.json: the pinned per-fixture result digests CollisionFixtureDeterminismTest asserts against. */
public final class CollisionFixtureMain {
    private CollisionFixtureMain() {}
    public static void main(String[] args) throws IOException {
        var json = new StringBuilder("{\n  \"canonicalization\": \"SHA-256 over the canonical rendering of every query result of the fixture (certainty, provenance, status counts, hits with world boxes and definite flag); canonical block-state keys, never run-local ids\",\n  \"profile\": \"")
            .append(CollisionProfile.PAPER_26_2.id()).append("\",\n  \"tableSha256\": \"").append(CollisionProfile.PAPER_26_2.table().digest()).append("\",\n  \"fixtures\": {\n");
        var fixtures = CollisionFixtures.all();
        for (int i = 0; i < fixtures.size(); i++) {
            var f = fixtures.get(i); var scene = new CollisionTestSupport.Scene(); var results = CollisionFixtures.run(f, scene);
            int hits = 0, complete = 0, incomplete = 0, unavailable = 0, rejected = 0;
            for (var r : results) { hits += r.hitCount(); switch (r.certainty()) { case COMPLETE -> complete++; case INCOMPLETE -> incomplete++; case UNAVAILABLE -> unavailable++; case REJECTED -> rejected++; } }
            json.append("    \"").append(f.name()).append("\": {\"queries\": ").append(f.queries().size()).append(", \"complete\": ").append(complete).append(", \"incomplete\": ").append(incomplete).append(", \"unavailable\": ").append(unavailable)
                .append(", \"rejected\": ").append(rejected).append(", \"hits\": ").append(hits).append(", \"digest\": \"").append(CollisionFixtures.digest(CollisionFixtures.render(f, results))).append("\"}").append(i + 1 < fixtures.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        Path out = Path.of(args[0]); Files.createDirectories(out.getParent()); Files.writeString(out, json.toString()); System.out.print(json);
    }
}
