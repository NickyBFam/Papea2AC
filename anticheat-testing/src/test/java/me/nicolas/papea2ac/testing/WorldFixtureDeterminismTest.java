package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import me.nicolas.papea2ac.world.*;

/** Fixture determinism: every world fixture replays identically twice in-process AND matches the digest pinned in docs/phase3-artifacts/world-fixtures.json. */
class WorldFixtureDeterminismTest {
    @Test void everyFixtureProducesTheSameWorldOnRepeatedRuns() {
        for (var f : WorldFixtures.all()) {
            var d1 = new BlockStateDictionary(); var d2 = new BlockStateDictionary(); d2.intern(me.nicolas.papea2ac.model.BlockStateKey.of("minecraft:perturb_ids"));
            var a = WorldFixtures.run(f, new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, f.privateTier()), d1);
            var b = WorldFixtures.run(f, new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, f.privateTier()), d2);
            assertEquals(WorldReplayMapper.export(a, d1), WorldReplayMapper.export(b, d2), f.name() + ": identical input, different dictionary ids, identical projection");
            assertEquals(WorldFixtures.digest(a, d1), WorldFixtures.digest(b, d2), f.name());
        }
    }
    @Test void everyFixtureMatchesItsPinnedDigest() throws Exception {
        String pinned = Files.readString(TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase3-artifacts/world-fixtures.json"));
        for (var f : WorldFixtures.all()) {
            var m = Pattern.compile("\"" + f.name() + "\": \\{[^}]*\"digest\": \"([0-9a-f]{64})\"").matcher(pinned);
            assertTrue(m.find(), "no pinned digest for fixture " + f.name());
            var dict = new BlockStateDictionary();
            assertEquals(m.group(1), WorldFixtures.digest(WorldFixtures.run(f, new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, f.privateTier()), dict), dict),
                "fixture " + f.name() + " produces a different world than the pinned artifact - a reviewed regeneration is required, never a silent update");
        }
    }
    @Test void fixtureCoverageOutcomesAreTheSpecifiedOnes() {
        var byName = new java.util.HashMap<String, ObserverWorld>();
        for (var f : WorldFixtures.all()) byName.put(f.name(), WorldFixtures.run(f));
        assertEquals(1, byName.get("partialBaseline").snapshot().partialChunks());
        assertEquals(1, byName.get("unload").snapshot().unloadedChunks()); assertEquals(1, byName.get("unload").snapshot().chunkCount());
        assertEquals(1, byName.get("reloadFullResend").snapshot().completeChunks());
        assertTrue(byName.get("evictedThenPartialUpdate").snapshot().evictedChunks() > 0); assertTrue(byName.get("evictedThenPartialUpdate").snapshot().partialChunks() >= 1);
        assertEquals(1, byName.get("dimensionTransition").snapshot().worldEpoch()); assertEquals(1, byName.get("dimensionTransition").snapshot().chunkCount());
        assertTrue(byName.get("captureCoverageBreak").snapshot().transportBroken()); assertEquals(1, byName.get("captureCoverageBreak").snapshot().transportIncompleteChunks()); assertEquals(1, byName.get("captureCoverageBreak").snapshot().completeChunks());
        assertEquals(1, byName.get("contentNotRetainedBaseline").snapshot().contentNotRetainedChunks());
        assertTrue(byName.get("reconnectNewSession").snapshot().closed());
    }
}
