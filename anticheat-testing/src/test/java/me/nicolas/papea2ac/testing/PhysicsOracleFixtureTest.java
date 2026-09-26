package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import me.nicolas.papea2ac.physics.PhysicsResult;
import org.junit.jupiter.api.Test;

/**
 * Offline replay of the pinned NATIVE trajectories (docs/phase9-artifacts/physics-fixtures/native-trajectories.json - the P9-F3 re-pin; docs/phase6-artifacts holds the P6-F1 pin; docs/phase5-artifacts holds the frozen pre-P6-F1 record,
 * recorded by the pinned server through {@code physicsNativeDifferential}): every catalogued walking scenario, run
 * chained on the simulator from its start state, must reproduce the server's trajectory digest bit for bit, and the
 * fluid scenarios must be refused as UNSUPPORTED_FLUID. TESTING.md: "Expected physics values must cite an independent
 * source or controlled measurement; never derive both expected and actual through the same simulator" - the expected
 * side here is the server's own movement code. A changed digest is a reviewed event, never a re-pin.
 */
class PhysicsOracleFixtureTest {
    static final Path FIXTURES = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase9-artifacts/physics-fixtures/native-trajectories.json");

    @Test void everyCatalogScenarioReproducesThePinnedNativeTrajectory() throws Exception {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(Files.readString(FIXTURES, StandardCharsets.UTF_8)));
        Map<String, Object> pinned = MiniJson.obj(root.get("scenarios"));
        int replayed = 0, deferred = 0; List<String> failures = new ArrayList<>();
        for (PhysicsScenarios.Scenario sc : PhysicsScenarios.all()) {
            ScenarioWorld w = new ScenarioWorld(sc);
            if (sc.fluidDeferred()) {
                PhysicsResult r = w.simulator.simulate(sc.start().state(), sc.input(sc.ticks().get(0)), w.environment());
                if (r.status() != PhysicsResult.Status.UNSUPPORTED_FLUID) failures.add(sc.id() + ": expected UNSUPPORTED_FLUID, got " + r.summary());
                deferred++; continue;
            }
            Map<String, Object> f = MiniJson.obj(pinned.get(sc.id()));
            assertNotNull(f, "no pinned native trajectory for " + sc.id() + " - run physicsNativeDifferential");
            List<PhysicsResult> run = w.runChained();
            for (PhysicsResult r : run) if (!r.exact()) { failures.add(sc.id() + ": non-exact tick " + r.summary()); break; }
            if (run.size() != MiniJson.integer(f.get("ticks"))) failures.add(sc.id() + ": " + run.size() + " ticks simulated, " + f.get("ticks") + " pinned");
            String digest = TrajectoryDigest.ofResults(run);
            if (!digest.equals(f.get("digest"))) failures.add(sc.id() + ": trajectory digest " + digest + " != pinned " + f.get("digest") + " (last simulated " + run.get(run.size() - 1).next().position() + ", pinned last " + f.get("last") + ")");
            replayed++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(replayed >= 80, "replayed " + replayed); assertEquals(3, deferred);
    }
    @Test void replayIsDeterministicAcrossRuns() {
        PhysicsScenarios.Scenario sc = PhysicsScenarios.all().stream().filter(s -> s.id().equals("mixed_course_random_100")).findFirst().orElseThrow();
        String a = TrajectoryDigest.ofResults(new ScenarioWorld(sc).runChained()), b = TrajectoryDigest.ofResults(new ScenarioWorld(sc).runChained());
        assertEquals(a, b);
    }
}
