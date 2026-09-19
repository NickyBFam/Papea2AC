package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import me.nicolas.papea2ac.world.*;

/** Writes docs/phase3-artifacts/world-fixtures.json: the pinned per-fixture projection digests WorldFixtureDeterminismTest asserts against. */
public final class WorldFixtureMain {
    private WorldFixtureMain() {}
    public static void main(String[] args) throws IOException {
        var json = new StringBuilder("{\n  \"canonicalization\": \"SHA-256 over WorldReplayMapper.export(world).toString() (chunks and tombstones sorted by (x,z); canonical block-state keys, never run-local ids)\",\n  \"fixtures\": {\n");
        var fixtures = WorldFixtures.all();
        for (int i = 0; i < fixtures.size(); i++) {
            var f = fixtures.get(i); var dict = new BlockStateDictionary();
            var world = WorldFixtures.run(f, new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, f.privateTier()), dict);
            var snap = world.snapshot();
            json.append("    \"").append(f.name()).append("\": {\"steps\": ").append(f.steps().size()).append(", \"privateTier\": ").append(f.privateTier())
                .append(", \"chunks\": ").append(snap.chunkCount()).append(", \"complete\": ").append(snap.completeChunks()).append(", \"partial\": ").append(snap.partialChunks())
                .append(", \"evicted\": ").append(snap.evictedChunks()).append(", \"unloaded\": ").append(snap.unloadedChunks()).append(", \"worldEpoch\": ").append(snap.worldEpoch())
                .append(", \"transportBroken\": ").append(snap.transportBroken()).append(", \"digest\": \"").append(WorldFixtures.digest(world, dict)).append("\"}")
                .append(i + 1 < fixtures.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString());
        System.out.print(json);
    }
}
