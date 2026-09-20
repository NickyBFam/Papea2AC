package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.regex.Pattern;
import me.nicolas.papea2ac.collision.CollisionResult.Certainty;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.world.WorldBudget;

/** Fixture determinism: every collision fixture renders identically twice in-process (different dictionary ids, different budget) AND matches the digest pinned in docs/phase4-artifacts/collision-fixtures.json. */
class CollisionFixtureDeterminismTest {
    @Test void everyFixtureRendersIdenticallyOnRepeatedRunsWithPerturbedRunLocalIds() {
        for (var f : CollisionFixtures.all()) {
            var a = new CollisionTestSupport.Scene(1, new WorldBudget()); var b = new CollisionTestSupport.Scene(7, new WorldBudget());
            for (int i = 0; i < 5; i++) b.dictionary.intern(BlockStateKey.of("minecraft:perturb_" + i)); // different run-local ids for every real state
            assertEquals(CollisionFixtures.render(f, CollisionFixtures.run(f, a)), CollisionFixtures.render(f, CollisionFixtures.run(f, b)), f.name());
        }
    }
    @Test void everyFixtureMatchesItsPinnedDigest() throws Exception {
        String pinned = Files.readString(TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase4-artifacts/collision-fixtures.json"));
        assertTrue(pinned.contains("\"tableSha256\": \"" + CollisionTestSupport.PROFILE.table().digest() + "\""), "fixtures were pinned against this exact shape table");
        for (var f : CollisionFixtures.all()) {
            var m = Pattern.compile("\"" + f.name() + "\": \\{[^}]*\"digest\": \"([0-9a-f]{64})\"").matcher(pinned);
            assertTrue(m.find(), "no pinned digest for fixture " + f.name());
            assertEquals(m.group(1), CollisionFixtures.digest(CollisionFixtures.render(f, CollisionFixtures.run(f, new CollisionTestSupport.Scene()))), "fixture " + f.name() + " produces a different result than the pinned artifact - a reviewed regeneration is required, never a silent update");
        }
    }
    @Test void fixtureOutcomesAreTheSpecifiedOnes() {
        var byName = new java.util.HashMap<String, java.util.List<me.nicolas.papea2ac.collision.CollisionResult>>();
        for (var f : CollisionFixtures.all()) byName.put(f.name(), CollisionFixtures.run(f, new CollisionTestSupport.Scene()));
        assertTrue(byName.get("simpleFullCube").get(0).definitelyClear()); assertTrue(byName.get("simpleFullCube").get(1).definitelyObstructed()); assertEquals(4, byName.get("simpleFullCube").get(2).hitCount());
        for (var r : byName.get("complexShapes")) assertEquals(Certainty.COMPLETE, r.certainty()); assertTrue(byName.get("complexShapes").get(3).definitelyObstructed(), "a fence post reaches 1.5 high into the box above");
        assertEquals(Certainty.INCOMPLETE, byName.get("contextAndOffsetShapes").get(0).certainty()); assertEquals(Certainty.INCOMPLETE, byName.get("contextAndOffsetShapes").get(1).certainty(), "the shulker/piston envelopes stay dynamic even with a body context");
        assertFalse(byName.get("unknownWorld").get(0).definitelyClear()); assertTrue(byName.get("unknownWorld").get(0).definitelyObstructed()); assertFalse(byName.get("unknownWorld").get(1).definitelyClear());
        assertEquals(Certainty.INCOMPLETE, byName.get("coverageStates").get(0).certainty()); assertEquals(Certainty.INCOMPLETE, byName.get("coverageStates").get(1).certainty()); assertEquals(Certainty.INCOMPLETE, byName.get("coverageStates").get(2).certainty()); assertEquals(Certainty.INCOMPLETE, byName.get("coverageStates").get(3).certainty());
        assertTrue(byName.get("historicalState").get(0).definitelyClear()); assertTrue(byName.get("historicalState").get(1).definitelyObstructed()); assertTrue(byName.get("historicalState").get(2).definitelyClear()); assertEquals(Certainty.UNAVAILABLE, byName.get("historicalState").get(3).certainty());
        assertEquals(Certainty.UNAVAILABLE, byName.get("worldEpochMismatch").get(0).certainty()); assertEquals(Certainty.INCOMPLETE, byName.get("worldEpochMismatch").get(1).certainty());
        assertEquals(Certainty.REJECTED, byName.get("boundsAndRejection").get(0).certainty()); assertEquals(Certainty.REJECTED, byName.get("boundsAndRejection").get(1).certainty()); assertTrue(byName.get("boundsAndRejection").get(2).definitelyClear());
    }
}
