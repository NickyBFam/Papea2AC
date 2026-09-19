package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.BlockLookup.Status;

/**
 * Section 54: deterministic seeded property/fuzz tests for the observer world (java.util.Random with fixed seeds,
 * same convention as PlayerTimelinePropertyTest - no new dependency). Every failure message names the seed and
 * the step so a counterexample is reproducible by rerunning that single seed.
 */
class ObserverWorldPropertyTest {
    static final BlockStateKey[] STATES = { AIR, STONE, DIRT, WATER, STAIRS, BlockStateKey.of("minecraft:sand"), BlockStateKey.parse("minecraft:oak_log[axis=y]") };
    static final SessionKey KEY = new SessionKey(new UUID(54, 1), 1);

    /** A random in-range overworld position inside the [-R..R] chunk window. */
    static BlockPos randomPos(Random r, int chunkRadius) {
        int cx = r.nextInt(2 * chunkRadius + 1) - chunkRadius, cz = r.nextInt(2 * chunkRadius + 1) - chunkRadius;
        return new BlockPos((cx << 4) + r.nextInt(16), -64 + r.nextInt(384), (cz << 4) + r.nextInt(16));
    }
    static WorldObservation randomObservation(Random r, int chunkRadius) {
        return switch (r.nextInt(10)) {
            case 0, 1 -> baseline(r.nextInt(2 * chunkRadius + 1) - chunkRadius, r.nextInt(2 * chunkRadius + 1) - chunkRadius);
            case 2, 3, 4 -> new WorldObservation.BlockChanged(randomPos(r, chunkRadius), STATES[r.nextInt(STATES.length)]);
            case 5 -> { var p = randomPos(r, chunkRadius); var list = new ArrayList<WorldObservation.BlockChange>(); int n = 1 + r.nextInt(12);
                for (int i = 0; i < n; i++) list.add(new WorldObservation.BlockChange(new BlockPos((p.x() & ~15) + r.nextInt(16), (p.y() & ~15) + r.nextInt(16), (p.z() & ~15) + r.nextInt(16)), STATES[r.nextInt(STATES.length)]));
                yield new WorldObservation.SectionBlocksChanged(p.chunk().x(), p.sectionY(), p.chunk().z(), list); }
            case 6 -> new WorldObservation.ChunkUnloaded(r.nextInt(2 * chunkRadius + 1) - chunkRadius, r.nextInt(2 * chunkRadius + 1) - chunkRadius);
            case 7 -> new WorldObservation.TickObserved(r.nextInt(1 << 20));
            case 8 -> new WorldObservation.ViewCenter(r.nextInt(2 * chunkRadius + 1) - chunkRadius, r.nextInt(2 * chunkRadius + 1) - chunkRadius);
            default -> new WorldObservation.BlockEntityObserved(randomPos(r, chunkRadius), "minecraft:chest", r.nextInt(200));
        };
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void chunkCoordinateConversionsRoundTripAndLocalsStayInRange(long seed) {
        var r = new Random(seed);
        for (int i = 0; i < 20_000; i++) {
            int x = r.nextInt(1 << 26) - (1 << 25), z = r.nextInt(1 << 26) - (1 << 25), y = r.nextInt(4096) - 2048;
            final int step = i;
            var pos = new BlockPos(x, y, z);
            assertEquals(pos, BlockPos.unpack(pos.packed()), () -> "seed=" + seed + " step=" + step + ": packed round-trip");
            var chunk = pos.chunk();
            assertEquals(Math.floorDiv(x, 16), chunk.x(), () -> "seed=" + seed + " step=" + step + ": chunk x is floor division");
            assertEquals(Math.floorDiv(z, 16), chunk.z(), () -> "seed=" + seed + " step=" + step + ": chunk z is floor division");
            assertEquals(Math.floorDiv(y, 16), pos.sectionY(), () -> "seed=" + seed + " step=" + step + ": section y is floor division");
            assertTrue(pos.localX() >= 0 && pos.localX() < 16 && pos.localZ() >= 0 && pos.localZ() < 16 && pos.localY() >= 0 && pos.localY() < 16, () -> "seed=" + seed + " step=" + step + ": locals 0..15");
            assertEquals((chunk.x() << 4) + pos.localX(), x); assertEquals((chunk.z() << 4) + pos.localZ(), z); assertEquals((pos.sectionY() << 4) + pos.localY(), y);
            assertEquals((pos.localY() << 8) | (pos.localZ() << 4) | pos.localX(), pos.sectionIndex());
            assertEquals(chunk, ChunkKey.ofBlock(x, z)); assertEquals(pos.localX(), ChunkKey.localOf(x));
        }
    }

    @ParameterizedTest @ValueSource(longs = {11, 12, 13, 14, 15})
    void revisionIsMonotonicHistoryStaysBoundedAndUnknownNeverBecomesKnownWithoutObservation(long seed) {
        var r = new Random(seed);
        var budget = new WorldBudget(); var dict = new BlockStateDictionary();
        var w = world(KEY, budget, dict, 1);
        var observedPositions = new HashSet<BlockPos>(); var baselined = new HashSet<ChunkKey>();
        long revision = 0, seq = 1;
        for (int i = 0; i < 4000; i++) {
            var o = randomObservation(r, 3); seq++;
            final int step = i; final long s = seq;
            w.apply(KEY, o, seq, seq * 1_000L, null);
            assertTrue(w.revision() >= revision, () -> "seed=" + seed + " step=" + step + ": revision monotonic");
            revision = w.revision();
            assertTrue(w.historySize() <= ObserverWorld.HISTORY_MAX_ENTRIES, () -> "seed=" + seed + " step=" + step + ": history bound");
            assertTrue(w.privateBytes() + w.snapshot().historyBytes() <= budget.privateLimit() || w.chunkCount() == 0, () -> "seed=" + seed + " step=" + step + ": private tier bound");
            assertEquals(s, w.lastSequence());
            switch (o) {
                case WorldObservation.ChunkBaselineObserved b -> baselined.add(new ChunkKey(b.chunkX(), b.chunkZ()));
                case WorldObservation.BlockChanged b -> observedPositions.add(b.position());
                case WorldObservation.SectionBlocksChanged sb -> sb.changes().forEach(c -> observedPositions.add(c.position()));
                case WorldObservation.ChunkUnloaded u -> baselined.remove(new ChunkKey(u.chunkX(), u.chunkZ()));
                default -> {}
            }
            // A probe cell no observation ever touched, in a chunk that was never baselined, must not be KNOWN.
            var probe = randomPos(r, 3);
            if (!baselined.contains(probe.chunk()) && !observedPositions.contains(probe)) {
                var lookup = w.blockAt(probe);
                assertNotEquals(Status.KNOWN, lookup.status(), () -> "seed=" + seed + " step=" + step + ": unknown became known without observation at " + probe);
                assertNull(lookup.state());
            }
        }
    }

    @ParameterizedTest @ValueSource(longs = {21, 22, 23, 24})
    void partialNeverBecomesCompleteWithoutAQualifyingBaseline(long seed) {
        var r = new Random(seed);
        var w = world(KEY, new WorldBudget(), new BlockStateDictionary(), 1);
        long seq = 1;
        for (int i = 0; i < 3000; i++) {
            seq++; final int step = i;
            // block updates only - no baseline ever arrives
            w.apply(KEY, r.nextBoolean() ? new WorldObservation.BlockChanged(randomPos(r, 2), STATES[r.nextInt(STATES.length)]) : new WorldObservation.TickObserved(i), seq, seq, null);
            for (var c : w.snapshot().chunks().values()) assertEquals(WorldCoverage.PARTIAL, c.coverage(), () -> "seed=" + seed + " step=" + step + ": " + c.key() + " completed without a baseline");
        }
        assertTrue(w.chunkCount() > 0);
        seq++; w.apply(KEY, baseline(0, 0), seq, seq, null);
        assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)), "only a full baseline completes a chunk");
    }

    @ParameterizedTest @ValueSource(longs = {31, 32, 33})
    void unloadRemovesCurrentAvailabilityAndEvictionFreesChargedMemory(long seed) {
        var r = new Random(seed);
        var budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, 3000); var dict = new BlockStateDictionary();
        var w = world(KEY, budget, dict, 1);
        long seq = 1; seq++; w.apply(KEY, new WorldObservation.ViewCenter(0, 0), seq, seq, null);
        for (int i = 0; i < 400; i++) {
            seq++; final int step = i;
            var o = randomObservation(r, 4);
            w.apply(KEY, o, seq, seq, null);
            if (o instanceof WorldObservation.ChunkUnloaded u) {
                var ck = new ChunkKey(u.chunkX(), u.chunkZ());
                assertTrue(w.chunk(ck).isEmpty(), () -> "seed=" + seed + " step=" + step + ": unloaded chunk still current");
                assertNotEquals(Status.KNOWN, w.blockAt(new BlockPos(ck.x() << 4, 0, ck.z() << 4)).status());
            }
            w.publishNow();
            assertTrue(w.privateBytes() + w.snapshot().historyBytes() <= 3000 || w.chunkCount() == 0, () -> "seed=" + seed + " step=" + step + ": private charge above the tier after eviction");
            long recomputed = 0; for (var c : w.snapshot().chunks().values()) recomputed += c.privateChargedBytes(sec -> !budget.isShared(sec));
            assertEquals(recomputed, w.privateBytes(), () -> "seed=" + seed + " step=" + step + ": private charge must equal what is actually retained");
        }
        long sharedBefore = budget.sharedBytes();
        w.close();
        assertEquals(0, w.privateBytes()); assertEquals(0, budget.sharedBytes(), "the only observer closed: shared store fully released (was " + sharedBefore + ")");
        assertEquals(0, budget.chunks.get());
    }

    @ParameterizedTest @ValueSource(longs = {41, 42, 43})
    void anOldSessionCannotMutateTheNewSessionsWorld(long seed) {
        var r = new Random(seed);
        var budget = new WorldBudget(); var dict = new BlockStateDictionary();
        var old = new SessionKey(KEY.player(), 1); var fresh = new SessionKey(KEY.player(), 2);
        var w = world(fresh, budget, dict, 1);
        long seq = 1;
        for (int i = 0; i < 1000; i++) {
            seq++; final int step = i;
            var before = w.snapshot(); w.publishNow(); var revBefore = w.revision(); var countBefore = w.chunkCount();
            var accepted = w.apply(old, randomObservation(r, 2), seq, seq, null);
            assertFalse(accepted, () -> "seed=" + seed + " step=" + step + ": stale-session observation accepted");
            assertEquals(revBefore, w.revision()); assertEquals(countBefore, w.chunkCount()); assertEquals(before.chunks(), w.snapshot().chunks());
        }
        assertEquals(0, w.chunkCount());
    }

    @ParameterizedTest @ValueSource(longs = {51, 52, 53, 54})
    void theSameObservationSequenceProducesTheSameWorldModel(long seed) {
        var script = new ArrayList<WorldObservation>(); var r = new Random(seed);
        for (int i = 0; i < 1500; i++) script.add(randomObservation(r, 3));
        var d1 = new BlockStateDictionary(); var d2 = new BlockStateDictionary(); d2.intern(BlockStateKey.of("minecraft:perturb_ids")); d2.intern(DIRT);
        var a = world(KEY, new WorldBudget(), d1, 1); var b = world(KEY, new WorldBudget(), d2, 1);
        long seq = 1;
        for (var o : script) { seq++; a.apply(KEY, o, seq, seq, null); b.apply(KEY, o, seq, seq, null); }
        a.publishNow(); b.publishNow();
        assertEquals(WorldReplayMapper.export(a, d1), WorldReplayMapper.export(b, d2), "seed=" + seed + ": identical script, identical projection regardless of dictionary ids");
        assertEquals(a.revision(), b.revision()); assertEquals(a.evictedChunks(), b.evictedChunks()); assertEquals(a.historySize(), b.historySize());
    }

    @ParameterizedTest @ValueSource(longs = {61, 62, 63, 64, 65})
    void randomBlockUpdatesPreserveEveryUnaffectedBlockState(long seed) {
        var r = new Random(seed);
        var dict = new BlockStateDictionary();
        var w = world(KEY, new WorldBudget(), dict, 1);
        long seq = 1; seq++; w.apply(KEY, baseline(0, 0), seq, seq, null);
        // reference model: only the cells we explicitly change
        var expected = new HashMap<BlockPos, BlockStateKey>();
        for (int i = 0; i < 2000; i++) {
            seq++; final int step = i;
            var p = new BlockPos(r.nextInt(16), -64 + r.nextInt(384), r.nextInt(16)); var s = STATES[r.nextInt(STATES.length)];
            if (r.nextInt(5) == 0) {
                var list = new ArrayList<WorldObservation.BlockChange>(); int n = 1 + r.nextInt(8);
                for (int k = 0; k < n; k++) { var q = new BlockPos(r.nextInt(16), (p.y() & ~15) + r.nextInt(16), r.nextInt(16)); var t = STATES[r.nextInt(STATES.length)]; list.add(new WorldObservation.BlockChange(q, t)); }
                w.apply(KEY, new WorldObservation.SectionBlocksChanged(0, p.sectionY(), 0, list), seq, seq, null);
                for (var c : list) expected.put(c.position(), c.state()); // later entries win, like the wire
            } else { w.apply(KEY, new WorldObservation.BlockChanged(p, s), seq, seq, null); expected.put(p, s); }
            // probe: 64 random cells - changed ones equal the reference, unchanged ones equal the baseline function
            for (int k = 0; k < 64; k++) {
                var q = new BlockPos(r.nextInt(16), -64 + r.nextInt(384), r.nextInt(16));
                var want = expected.getOrDefault(q, baselineStateAt(q));
                var got = w.blockAt(q);
                assertEquals(Status.KNOWN, got.status(), () -> "seed=" + seed + " step=" + step + ": " + q);
                assertEquals(want, got.state(), () -> "seed=" + seed + " step=" + step + ": unaffected/affected cell mismatch at " + q);
            }
        }
        assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)), "updates never downgrade a complete baseline");
    }
    /** The state WorldTestSupport.baseline() puts at a position (section 8 = y 64..79: 4 stone, 2 dirt, air above). */
    static BlockStateKey baselineStateAt(BlockPos p) {
        int section = (p.y() + 64) >> 4;
        if (section < 8) return STONE;
        if (section > 8) return AIR;
        int y = p.localY(); return y < 4 ? STONE : y < 6 ? DIRT : AIR;
    }
}
