package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/**
 * Compact, code-defined Phase 3 world fixtures (no chunk dumps): each is a scripted observation sequence whose
 * resulting observer world - exported through WorldReplayMapper - must be byte-for-byte identical on every run and
 * host. Pinned digests live in docs/phase3-artifacts/world-fixtures.json (regenerate deliberately with the
 * `worldFixtures` Gradle task; a changed digest is a reviewed event).
 */
final class WorldFixtures {
    private WorldFixtures() {}
    record Step(WorldObservation observation, String transportDegradeReason) { Step(WorldObservation o) { this(o, null); } }
    record Fixture(String name, long sessionEpoch, int privateTier, List<Step> steps) {}

    static List<Fixture> all() {
        var single = new WorldObservation.BlockChanged(new BlockPos(3, 70, 3), STAIRS);
        var multi = new ArrayList<WorldObservation.BlockChange>(); for (int i = 0; i < 16; i++) multi.add(new WorldObservation.BlockChange(new BlockPos(i, 66, 7), WATER));
        var tier = WorldBudget.PRIVATE_FLOOR_BYTES;
        var l = new ArrayList<Fixture>();
        l.add(new Fixture("fullChunkBaseline", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)))));
        l.add(new Fixture("negativeCoordinateChunk", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(-1, -1)), new Step(new WorldObservation.BlockChanged(new BlockPos(-1, -64, -16), DIRT)))));
        l.add(new Fixture("singleBlockUpdate", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(single))));
        l.add(new Fixture("multiBlockUpdate", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(new WorldObservation.SectionBlocksChanged(0, 4, 0, multi)))));
        l.add(new Fixture("unload", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(baseline(1, 0)), new Step(new WorldObservation.ChunkUnloaded(0, 0)))));
        l.add(new Fixture("reloadFullResend", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(single), new Step(new WorldObservation.ChunkUnloaded(0, 0)), new Step(baseline(0, 0)))));
        l.add(new Fixture("partialBaseline", 1, tier, List.of(new Step(OVERWORLD), new Step(single), new Step(new WorldObservation.BlockChanged(new BlockPos(3, 71, 3), STONE)))));
        var evicted = new ArrayList<Step>(); evicted.add(new Step(OVERWORLD)); evicted.add(new Step(new WorldObservation.ViewCenter(0, 0)));
        for (int x = 0; x < 12; x++) evicted.add(new Step(baseline(x, 0)));
        evicted.add(new Step(new WorldObservation.ViewCenter(11, 0))); evicted.add(new Step(new WorldObservation.BlockChanged(new BlockPos(176, 0, 0), DIRT)));
        l.add(new Fixture("evictedThenPartialUpdate", 1, 2600, evicted));
        var nether = new ArrayList<WorldObservation.SectionContent>(); for (int i = 0; i < 16; i++) nether.add(uniformSection(i < 5 ? STONE : AIR));
        l.add(new Fixture("dimensionTransition", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(NETHER), new Step(baseline(0, 0, nether)), new Step(new WorldObservation.BlockChanged(new BlockPos(0, 90, 0), DIRT)))));
        l.add(new Fixture("captureCoverageBreak", 1, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(new WorldObservation.CoverageBreak("PIPELINE_UNCOVERED")), new Step(baseline(1, 0), "PIPELINE_UNCOVERED"), new Step(single, "PIPELINE_UNCOVERED"))));
        l.add(new Fixture("contentNotRetainedBaseline", 1, tier, List.of(new Step(OVERWORLD), new Step(unretained(0, 0, "OVER_INLINE_CAP:70000")), new Step(single))));
        l.add(new Fixture("reconnectNewSession", 2, tier, List.of(new Step(OVERWORLD), new Step(baseline(0, 0)), new Step(new WorldObservation.SessionClosed()))));
        return l;
    }

    static ObserverWorld run(Fixture fixture, WorldBudget budget, BlockStateDictionary dictionary) {
        var key = new SessionKey(new UUID(99, 99), fixture.sessionEpoch());
        var world = new ObserverWorld(key, budget, dictionary);
        long seq = 0;
        for (var step : fixture.steps()) { seq++; world.apply(key, step.observation(), seq, seq * 1_000_000L, step.transportDegradeReason()); }
        if (!world.closed()) world.publishNow(); // block updates publish lazily (tick cadence); fixtures read the settled snapshot
        return world;
    }
    static ObserverWorld run(Fixture fixture) { return run(fixture, new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, fixture.privateTier()), new BlockStateDictionary()); }

    /** Canonical rendering: the sorted WorldCapsule projection (canonical keys, no run-local ids) - stable across runs and hosts. */
    static String digest(ObserverWorld world, BlockStateDictionary dictionary) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(WorldReplayMapper.export(world, dictionary).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
