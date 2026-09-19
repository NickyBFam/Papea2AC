package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.BlockLookup.Status;

/** Section 53 core matrix for the observer world (categories noted per test). Paper-free. */
class ObserverWorldTest {
    static final SessionKey KEY = new SessionKey(new UUID(60, 1), 1);
    WorldBudget budget; BlockStateDictionary dict; ObserverWorld w; long seq;
    @BeforeEach void setUp() { budget = new WorldBudget(); dict = new BlockStateDictionary(); w = new ObserverWorld(KEY, budget, dict); seq = 0; }
    boolean apply(WorldObservation o) { return w.apply(KEY, o, ++seq, seq * 1_000_000L, null); }
    boolean applyAt(WorldObservation o, long nanos) { return w.apply(KEY, o, ++seq, nanos, null); }
    BlockLookup at(int x, int y, int z) { return w.blockAt(new BlockPos(x, y, z)); }

    // 1
    @Test void emptyObserverWorldKnowsNothingAndSaysSo() {
        assertEquals(Status.DIMENSION_UNKNOWN, at(0, 64, 0).status(), "no JoinGame yet: absolute Y cannot even be placed");
        apply(OVERWORLD);
        assertEquals(Status.UNKNOWN_CHUNK, at(0, 64, 0).status()); assertEquals(WorldCoverage.UNKNOWN, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals(0, w.chunkCount()); assertEquals(0, w.snapshot().chunkCount());
    }
    // 2, 3
    @Test void fullChunkBaselineMakesEveryCellKnown() {
        apply(OVERWORLD); apply(baseline(0, 0));
        assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals(STONE, at(3, -64, 3).state()); assertEquals(STONE, at(3, 63, 3).state()); assertEquals(STONE, at(0, 64 + 3, 0).state());
        assertEquals(DIRT, at(0, 64 + 4, 0).state()); assertEquals(AIR, at(0, 64 + 6, 0).state()); assertEquals(AIR, at(15, 319, 15).state());
        assertTrue(at(0, 0, 0).known()); assertEquals(WorldCoverage.COMPLETE, at(0, 0, 0).chunkCoverage());
    }
    // 4, 5, 20
    @Test void unknownChunkSectionAndInvalidYAreDistinctResults() {
        apply(OVERWORLD); apply(baseline(0, 0));
        assertEquals(Status.UNKNOWN_CHUNK, at(16, 0, 0).status());
        assertEquals(Status.OUTSIDE_DIMENSION, at(0, 320, 0).status()); assertEquals(Status.OUTSIDE_DIMENSION, at(0, -65, 0).status());
        apply(new WorldObservation.BlockChanged(new BlockPos(20, 10, 20), STONE));
        assertEquals(Status.UNKNOWN_SECTION, at(20, 100, 20).status(), "a partial chunk's untouched section has no data");
    }
    // 6, 7
    @Test void singleBlockUpdateWithoutBaselineCreatesAPartialChunkNotAFullOne() {
        apply(OVERWORLD);
        apply(new WorldObservation.BlockChanged(new BlockPos(5, 70, 5), STONE));
        assertEquals(WorldCoverage.PARTIAL, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals(STONE, at(5, 70, 5).state());
        assertEquals(Status.UNKNOWN_CELL, at(6, 70, 5).status(), "the neighbour was never observed - not air");
        assertEquals(Status.UNKNOWN_CELL, at(5, 71, 5).status());
        assertEquals("NO_BASELINE", w.chunk(new ChunkKey(0, 0)).orElseThrow().coverageNote());
    }
    // 7 (with baseline), 9
    @Test void singleBlockUpdateOnACompleteChunkKeepsCompletenessAndLeavesThePriorRevisionImmutable() {
        apply(OVERWORLD); apply(baseline(0, 0));
        var before = w.chunk(new ChunkKey(0, 0)).orElseThrow(); var beforeSection = before.section(8); long revBefore = w.revision();
        apply(new WorldObservation.BlockChanged(new BlockPos(1, 64 + 6, 1), STONE));
        assertEquals(STONE, at(1, 70, 1).state()); assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals(AIR.canonical(), dict.key(beforeSection.stateIdAt(index(1, 6, 1))).canonical(), "the previous section snapshot is unchanged");
        assertSame(beforeSection, before.section(8)); assertEquals(revBefore + 1, w.revision());
        assertSame(before.section(0), w.chunk(new ChunkKey(0, 0)).orElseThrow().section(0), "untouched sections are structurally shared, not copied");
    }
    // 8
    @Test void multiBlockUpdateAppliesAtomicallyAsOneRevision() {
        apply(OVERWORLD); apply(baseline(0, 0)); long rev = w.revision();
        var changes = new ArrayList<WorldObservation.BlockChange>();
        for (int i = 0; i < 10; i++) changes.add(new WorldObservation.BlockChange(new BlockPos(i, 64 + 6, 0), DIRT));
        apply(new WorldObservation.SectionBlocksChanged(0, 4, 0, changes));
        for (int i = 0; i < 10; i++) assertEquals(DIRT, at(i, 70, 0).state());
        assertEquals(rev + 1, w.revision(), "one packet = one revision");
        // a batch with a position outside its own section is rejected whole
        var bad = List.of(new WorldObservation.BlockChange(new BlockPos(0, 70, 0), STONE), new WorldObservation.BlockChange(new BlockPos(0, 90, 0), STONE));
        apply(new WorldObservation.SectionBlocksChanged(0, 4, 0, bad));
        assertEquals(DIRT, at(0, 70, 0).state(), "nothing from a rejected batch is applied");
    }
    // 10
    @Test void fullBaselineReplacementReplacesEverythingDeterministically() {
        apply(OVERWORLD); apply(baseline(0, 0));
        apply(new WorldObservation.BlockChanged(new BlockPos(1, 300, 1), STONE));
        assertEquals(STONE, at(1, 300, 1).state());
        var fresh = new ArrayList<WorldObservation.SectionContent>(); for (int i = 0; i < OVERWORLD_SECTIONS; i++) fresh.add(uniformSection(i < 12 ? DIRT : AIR));
        apply(baseline(0, 0, fresh));
        assertEquals(AIR, at(1, 300, 1).state(), "the stale private update did not survive the new authoritative baseline");
        assertEquals(DIRT, at(0, 0, 0).state()); assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals(0, w.chunk(new ChunkKey(0, 0)).orElseThrow().blockEntities().size(), "block entities are not merged from the stale chunk");
    }
    // 11, 12, 13
    @Test void unloadMakesCurrentStateUnavailableWithUnloadProvenanceAndReObservationStaysPartial() {
        apply(OVERWORLD); apply(baseline(0, 0));
        apply(new WorldObservation.ChunkUnloaded(0, 0));
        assertEquals(Status.UNLOADED, at(0, 0, 0).status()); assertEquals(WorldCoverage.UNLOADED, w.coverageOf(new ChunkKey(0, 0))); assertEquals(0, w.chunkCount());
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 0, 0), DIRT));
        assertEquals(WorldCoverage.PARTIAL, w.coverageOf(new ChunkKey(0, 0)), "a block update after unload does not resurrect the old baseline");
        assertEquals("AFTER_UNLOADED", w.chunk(new ChunkKey(0, 0)).orElseThrow().coverageNote());
        assertEquals(Status.UNKNOWN_CELL, at(1, 0, 0).status(), "the previously known neighbour is NOT brought back from the unloaded chunk");
        apply(baseline(0, 0));
        assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)), "only a new full baseline restores completeness");
    }
    // 12, 14, 32
    @Test void evictionIsDistinctFromUnloadAndReObservationAfterEvictionStaysPartial() {
        // Tiny private tier (2600 B): a baseline chunk costs ~288 B of private pointers, so ten of them overflow and the farthest from the view center is evicted.
        budget = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, 2600); w = new ObserverWorld(KEY, budget, dict); seq = 0;
        apply(OVERWORLD); apply(new WorldObservation.ViewCenter(0, 0));
        for (int x = 0; x < 10; x++) apply(baseline(x, 0));
        assertTrue(w.evictedChunks() > 0, "the tier cannot hold ten chunks' private pointers");
        var evicted = new ChunkKey(9, 0);
        assertEquals(WorldCoverage.EVICTED, w.coverageOf(evicted), "the farthest chunk from the view center goes first");
        assertEquals(Status.EVICTED, w.blockAt(new BlockPos(144, 0, 0)).status());
        assertNotEquals(WorldCoverage.UNLOADED, w.coverageOf(evicted), "eviction never masquerades as a protocol unload");
        assertEquals(0, w.tombstone(evicted).map(t -> t.reason() == WorldCoverage.UNLOADED ? 1 : 0).orElse(1));
        // Re-observation after eviction: one block update makes it PARTIAL (never COMPLETE again without a baseline). Move the view so it is the nearest chunk and survives.
        apply(new WorldObservation.ViewCenter(9, 0));
        apply(new WorldObservation.BlockChanged(new BlockPos(144, 0, 0), DIRT));
        assertEquals(WorldCoverage.PARTIAL, w.coverageOf(evicted)); assertEquals("AFTER_EVICTED", w.chunk(evicted).orElseThrow().coverageNote());
        assertEquals(DIRT, w.blockAt(new BlockPos(144, 0, 0)).state()); assertEquals(Status.UNKNOWN_CELL, w.blockAt(new BlockPos(145, 0, 0)).status(), "the old baseline content is gone for good");
    }
    // 15, 16
    @Test void negativeChunkCoordinatesAndBoundariesResolveToTheRightChunk() {
        apply(OVERWORLD); apply(baseline(-1, -1)); apply(baseline(0, 0));
        assertEquals(STONE, at(-1, 0, -1).state()); assertEquals(STONE, at(-16, 0, -16).state()); assertEquals(Status.UNKNOWN_CHUNK, at(-17, 0, -17).status());
        assertEquals(STONE, at(0, 0, 0).state()); assertEquals(STONE, at(15, 0, 15).state()); assertEquals(Status.UNKNOWN_CHUNK, at(16, 0, 16).status());
        apply(new WorldObservation.BlockChanged(new BlockPos(-1, 100, -1), DIRT));
        assertEquals(DIRT, at(-1, 100, -1).state()); assertEquals(Status.UNKNOWN_CHUNK, at(-17, 100, -1).status());
    }
    // 17, 18, 19
    @Test void negativeYAndDimensionExtremesMapToTheCorrectSections() {
        apply(OVERWORLD); apply(baseline(0, 0));
        assertEquals(STONE, at(0, -64, 0).state(), "dimension minimum"); assertEquals(STONE, at(0, -1, 0).state()); assertEquals(AIR, at(0, 319, 0).state(), "dimension maximum");
        apply(new WorldObservation.BlockChanged(new BlockPos(0, -64, 0), DIRT)); assertEquals(DIRT, at(0, -64, 0).state()); assertEquals(STONE, at(0, -63, 0).state());
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 319, 0), DIRT)); assertEquals(DIRT, at(0, 319, 0).state()); assertEquals(AIR, at(0, 318, 0).state());
    }
    // 24
    @Test void blockEntityObservationsAreBoundedSummariesOnTheChunk() {
        apply(OVERWORLD); apply(baseline(0, 0));
        apply(new WorldObservation.BlockEntityObserved(new BlockPos(1, 70, 1), "minecraft:chest", 120));
        var chunk = w.chunk(new ChunkKey(0, 0)).orElseThrow();
        assertEquals(1, chunk.blockEntities().size()); assertEquals("minecraft:chest", chunk.blockEntities().get(new BlockPos(1, 70, 1).packed()).type());
        for (int i = 0; i < 300; i++) apply(new WorldObservation.BlockEntityObserved(new BlockPos(i & 15, 70, (i >> 4) & 15), "minecraft:sign", 10));
        assertTrue(w.chunk(new ChunkKey(0, 0)).orElseThrow().blockEntities().size() <= ChunkSnapshot.MAX_BLOCK_ENTITIES);
    }
    // 26
    @Test void dimensionTransitionIsAnExplicitDiscontinuityNothingCarriesOver() {
        apply(OVERWORLD); apply(baseline(0, 0)); long epoch = w.worldEpoch();
        apply(NETHER);
        assertEquals(epoch + 1, w.worldEpoch()); assertEquals(0, w.chunkCount());
        assertEquals(Status.UNKNOWN_CHUNK, at(0, 10, 0).status(), "old-dimension coordinates never resolve against the new dimension");
        assertEquals(Status.OUTSIDE_DIMENSION, at(0, -1, 0).status(), "the nether has no negative Y");
        assertEquals(Status.WRONG_WORLD_EPOCH, w.blockAtSequence(new BlockPos(0, 10, 0), 2).status(), "history before the epoch change is not retained across it");
    }
    // 27, 28, 29
    @Test void transportCoverageBreakMarksEveryChunkUntrustworthyUntilANewBaseline() {
        apply(OVERWORLD); apply(baseline(0, 0)); apply(baseline(1, 0));
        apply(new WorldObservation.CoverageBreak("OVERLOADED"));
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, w.coverageOf(new ChunkKey(0, 0))); assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, w.coverageOf(new ChunkKey(1, 0)));
        assertEquals(Status.TRANSPORT_INCOMPLETE, at(0, 0, 0).status(), "a possibly-missed update means the cell is no longer the client's known view");
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT));
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, w.coverageOf(new ChunkKey(0, 0)), "later valid packets do not heal coverage");
        apply(baseline(0, 0));
        assertEquals(WorldCoverage.COMPLETE, w.coverageOf(new ChunkKey(0, 0)), "a full baseline received after the break is a fresh authoritative view of THIS chunk: it recovers (P3-F8) - the only way coverage ever heals");
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, w.coverageOf(new ChunkKey(1, 0)), "a chunk never re-sent stays incomplete for the session");
        assertTrue(w.transportBroken(), "the session records that a break happened (diagnostic)"); assertEquals(1, w.transportBreaks());
    }
    @Test void degradedTransportReasonOnApplyBreaksCoverageTheSameWay() {
        apply(OVERWORLD); apply(baseline(0, 0));
        w.apply(KEY, new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT), ++seq, 0, 0, "PIPELINE_UNCOVERED");
        assertTrue(w.transportBroken()); assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, w.coverageOf(new ChunkKey(0, 0)));
    }
    @Test void prefixIncompleteBaselineIsExplicitNotAirFilled() {
        apply(OVERWORLD); apply(unretained(0, 0, "OVER_INLINE_CAP:70000"));
        assertEquals(WorldCoverage.CONTENT_NOT_RETAINED, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals(Status.CONTENT_NOT_RETAINED, at(0, 0, 0).status());
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT));
        assertEquals(DIRT, at(0, 70, 0).state(), "the one observed cell is known");
        assertEquals(Status.UNKNOWN_CELL, at(1, 70, 0).status(), "its neighbour in the now-partial section is unknown, not air");
        assertEquals(WorldCoverage.CONTENT_NOT_RETAINED, at(1, 70, 0).chunkCoverage());
        assertEquals(Status.CONTENT_NOT_RETAINED, at(1, 100, 0).status(), "an untouched section still reports the chunk-level reason");
    }
    // 30
    @Test void historicalExactLookupReturnsTheStateAsOfThatSequence() {
        apply(OVERWORLD); apply(baseline(0, 0)); long afterBaseline = seq;
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT)); long afterFirst = seq;
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), STONE));
        assertEquals(AIR, w.blockAtSequence(new BlockPos(0, 70, 0), afterBaseline).state());
        assertEquals(DIRT, w.blockAtSequence(new BlockPos(0, 70, 0), afterFirst).state());
        assertEquals(STONE, w.blockAtSequence(new BlockPos(0, 70, 0), seq).state());
        assertEquals(STONE, w.blockAtSequence(new BlockPos(0, 0, 0), afterBaseline).state(), "an untouched cell reads from the current chunk");
        assertEquals(Status.FUTURE_SEQUENCE, w.blockAtSequence(new BlockPos(0, 70, 0), seq + 1).status());
        assertEquals(Status.UNKNOWN_CHUNK, w.blockAtSequence(new BlockPos(0, 70, 0), 1).status(), "before the baseline the chunk did not exist for this observer");
    }
    @Test void historyAcrossUnloadIsAnsweredFromTheRetainedRevision() {
        apply(OVERWORLD); apply(baseline(0, 0)); long before = seq;
        apply(new WorldObservation.ChunkUnloaded(0, 0));
        assertEquals(STONE, w.blockAtSequence(new BlockPos(0, 0, 0), before).state());
        assertEquals(Status.UNLOADED, w.blockAtSequence(new BlockPos(0, 0, 0), seq).status());
    }
    // 31
    @Test void evictedHistoryIsReportedNotSilentlyApproximated() {
        apply(OVERWORLD); apply(baseline(0, 0)); long afterBaseline = seq;
        for (int i = 0; i < ObserverWorld.HISTORY_MAX_ENTRIES + 10; i++) apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), (i & 1) == 0 ? DIRT : STONE));
        assertTrue(w.historyEvictions() > 0);
        assertEquals(Status.HISTORY_NOT_RETAINED, w.blockAtSequence(new BlockPos(0, 70, 0), afterBaseline).status());
        assertTrue(w.blockAtSequence(new BlockPos(0, 70, 0), seq).known(), "the present is always answerable");
    }
    @Test void historyAlsoExpiresByTheThreeSecondHorizon() {
        applyAt(OVERWORLD, 0); applyAt(baseline(0, 0), 0); long afterBaseline = seq;
        applyAt(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT), 1_000_000_000L);
        assertEquals(1, w.historySize());
        applyAt(new WorldObservation.TickObserved(1), 1_000_000_000L + ObserverWorld.HISTORY_HORIZON_NANOS);
        assertEquals(0, w.historySize()); assertEquals(Status.HISTORY_NOT_RETAINED, w.blockAtSequence(new BlockPos(0, 70, 0), afterBaseline).status());
    }
    // 32, 33
    @Test void sessionReplacementIsStructuralAndLateOldSessionEventsAreRejected() {
        apply(OVERWORLD); apply(baseline(0, 0));
        var replacement = new ObserverWorld(new SessionKey(KEY.player(), 2), budget, dict);
        assertEquals(0, replacement.snapshot().chunkCount(), "a new session starts from nothing");
        assertFalse(replacement.apply(KEY, baseline(0, 0), 1, 0, null), "an old-epoch key never mutates the replacement");
        assertEquals(0, replacement.snapshot().chunkCount());
        assertFalse(w.apply(KEY, baseline(1, 1), 1, 0, null), "and a stale (non-monotonic) sequence is a no-op on the old world");
    }
    // 34
    @Test void sharedBudgetExhaustionDegradesCoverageWithoutFabricatingState() {
        var tiny = new WorldBudget(1024, WorldBudget.PRIVATE_FLOOR_BYTES); w = new ObserverWorld(KEY, tiny, dict); seq = 0;
        apply(OVERWORLD); apply(baseline(0, 0));
        assertEquals(WorldCoverage.CONTENT_NOT_RETAINED, w.coverageOf(new ChunkKey(0, 0)));
        assertEquals("SHARED_BUDGET", w.chunk(new ChunkKey(0, 0)).orElseThrow().coverageNote());
        assertEquals(Status.CONTENT_NOT_RETAINED, at(0, 0, 0).status()); assertEquals(1, tiny.budgetRejections.get()); assertEquals(0, tiny.sharedBytes());
    }
    // 35, 40
    @Test void closeReleasesEverythingAndGlobalAccountingReturnsToBaseline() {
        apply(OVERWORLD); apply(baseline(0, 0)); apply(baseline(1, 0)); apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT));
        assertTrue(budget.sharedBytes() > 0); assertEquals(1, budget.observerWorlds.get()); assertEquals(2, budget.chunks.get());
        var other = new ObserverWorld(new SessionKey(new UUID(60, 2), 1), budget, dict);
        other.apply(other.key(), OVERWORLD, 1, 0, null); other.apply(other.key(), baseline(0, 0), 2, 0, null);
        long sharedWithTwo = budget.sharedBytes(); assertTrue(budget.sharedHits.get() > 0, "identical baseline sections are shared, charged once");
        assertEquals(3, budget.sharedSections(), "both observers, three chunks, but only three distinct section contents exist (stone, surface, air)");
        apply(new WorldObservation.SessionClosed());
        assertTrue(w.closed()); assertEquals(1, budget.observerWorlds.get()); assertEquals(1, budget.chunks.get());
        assertEquals(sharedWithTwo, budget.sharedBytes(), "the other observer still references every distinct section, so nothing is freed yet");
        other.close();
        assertEquals(0, budget.observerWorlds.get()); assertEquals(0, budget.chunks.get()); assertEquals(0, budget.sharedBytes()); assertEquals(0, budget.sharedSections()); assertEquals(0, budget.sections.get());
        assertFalse(apply(baseline(2, 2)), "a closed world accepts nothing");
    }
    // 36
    @Test void revisionsAdvanceDeterministicallyAndOnlyOnMaterialChange() {
        apply(OVERWORLD); long r0 = w.revision();
        apply(baseline(0, 0)); assertEquals(r0 + 1, w.revision());
        apply(new WorldObservation.ViewCenter(0, 0)); assertEquals(r0 + 1, w.revision(), "view tracking is not a world-state revision");
        apply(new WorldObservation.TickObserved(5)); assertEquals(r0 + 1, w.revision());
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT)); assertEquals(r0 + 2, w.revision());
        assertEquals(r0 + 2, w.chunk(new ChunkKey(0, 0)).orElseThrow().revision());
    }
    @Test void ownershipIsAsserted() throws Exception {
        apply(OVERWORLD);
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> assertThrows(IllegalStateException.class, () -> w.apply(KEY, baseline(0, 0), 99, 0, null))).get();
            assertNotNull(pool.submit(() -> w.snapshot()).get(), "the published snapshot is readable from any thread");
        } finally { pool.shutdownNow(); }
    }
    @Test void publishedSnapshotIsImmutableAndAnswersLookupsCrossThread() throws Exception {
        apply(OVERWORLD); apply(baseline(0, 0));
        var snap = w.snapshot();
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 70, 0), DIRT)); // updates publish lazily
        assertEquals(AIR, snap.blockAt(new BlockPos(0, 70, 0), dict).state(), "an already-published snapshot never changes");
        apply(new WorldObservation.TickObserved(1));
        assertEquals(DIRT, w.snapshot().blockAt(new BlockPos(0, 70, 0), dict).state());
        var pool = Executors.newSingleThreadExecutor();
        try { assertEquals(DIRT, pool.submit(() -> w.snapshot().blockAt(new BlockPos(0, 70, 0), dict).state()).get()); } finally { pool.shutdownNow(); }
    }
    @Test void blockEventsAreRecordedBoundedNeverSimulated() {
        apply(OVERWORLD);
        for (int i = 0; i < 300; i++) apply(new WorldObservation.BlockEventObserved(new BlockPos(i, 64, 0), "minecraft:piston", 0, 1));
        assertEquals(ObserverWorld.BLOCK_EVENT_MAX, w.blockEvents().size());
    }
    /** P3 property-test finding: releasing a chunk's shared refs BEFORE settling its private charge let a section that dropped to zero refs be subtracted as private, driving privateBytes negative. */
    @Test void evictingTheSoleHolderOfASharedSectionNeverDrivesThePrivateChargeNegative() {
        var tiny = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, 2600); w = new ObserverWorld(KEY, tiny, dict); seq = 0;
        apply(OVERWORLD); apply(new WorldObservation.ViewCenter(0, 0));
        var unique = new ArrayList<WorldObservation.SectionContent>(); for (int i = 0; i < OVERWORLD_SECTIONS; i++) { final int k = i; unique.add(section(idx -> (idx + k) % 97 == 0 ? DIRT : STONE)); }
        apply(baseline(9, 0, unique)); // content held by no other chunk: eviction drops its sections to zero refs
        for (int x = 0; x < 12; x++) apply(baseline(x, 0));
        assertTrue(w.evictedChunks() > 0); assertEquals(WorldCoverage.EVICTED, w.coverageOf(new ChunkKey(9, 0)));
        assertTrue(w.privateBytes() >= 0, "private charge went negative: " + w.privateBytes());
        long recomputed = 0; for (var c : w.snapshot().chunks().values()) recomputed += c.privateChargedBytes(s -> !tiny.isShared(s));
        assertEquals(recomputed, w.privateBytes());
        w.close(); assertEquals(0, w.privateBytes()); assertEquals(0, tiny.sharedBytes());
    }
    /** Section 44: a world-silent session sequence (movement claim, outcome) still advances the "observed through" bound, so lookups at it are answerable and history stays exact. */
    @Test void observeSequenceAdvancesTheObservedThroughBoundWithoutARevision() {
        apply(OVERWORLD); apply(baseline(0, 0)); long rev = w.revision();
        assertTrue(w.observeSequence(KEY, 3, 0)); assertEquals(3, w.lastSequence()); assertEquals(rev, w.revision(), "no revision for a marker");
        assertFalse(w.observeSequence(new SessionKey(KEY.player(), 2), 4, 0), "stale/foreign session"); assertFalse(w.observeSequence(KEY, 3, 0), "non-monotonic");
        assertEquals(STONE, w.blockAt(new BlockPos(0, 0, 0)).state()); assertEquals(Status.KNOWN, w.blockAtSequence(new BlockPos(1, 64, 1), 3).status());
        seq = 3; apply(new WorldObservation.BlockChanged(new BlockPos(1, 64, 1), DIRT)); // sequence 4
        assertTrue(w.observeSequence(KEY, 5, 0));
        assertEquals(STONE, w.blockAtSequence(new BlockPos(1, 64, 1), 3).state(), "through the marker sequence: before the update");
        assertEquals(DIRT, w.blockAtSequence(new BlockPos(1, 64, 1), 5).state(), "through a later marker: after the update");
        assertEquals(Status.FUTURE_SEQUENCE, w.blockAtSequence(new BlockPos(1, 64, 1), 6).status());
        assertTrue(w.apply(KEY, new WorldObservation.TickObserved(1), 6, 0, null), "apply after a marker continues the same sequence space");
    }
    /** Item 40 (shutdown cleanup, supervisor path): a world whose owning shard died is reclaimed only once that thread is proven terminated; a live owner is never reclaimed from another thread. */
    @Test void supervisorReclaimsAWorldOnlyAfterItsOwnerThreadTerminated() throws Exception {
        var shared = new WorldBudget(); var d = new BlockStateDictionary();
        var holder = new ObserverWorld[1];
        var owner = new Thread(() -> { var w = new ObserverWorld(KEY, shared, d); w.apply(KEY, OVERWORLD, 1, 0, null); w.apply(KEY, baseline(0, 0), 2, 0, null); holder[0] = w; });
        owner.start(); owner.join();
        assertTrue(shared.sharedBytes() > 0); assertEquals(1, shared.observerWorlds.get());
        holder[0].retireAfterOwnerTermination();
        assertTrue(holder[0].closed()); assertEquals(0, shared.sharedBytes()); assertEquals(0, shared.observerWorlds.get()); assertEquals(0, shared.chunks.get());
        // a live owner: reclamation from another thread is refused
        var live = new ObserverWorld(new SessionKey(KEY.player(), 9), shared, d); live.apply(new SessionKey(KEY.player(), 9), OVERWORLD, 1, 0, null);
        var other = new Thread(() -> assertThrows(IllegalStateException.class, live::retireAfterOwnerTermination)); other.start(); other.join();
        assertFalse(live.closed()); live.close();
    }
    /** P3-F3: Phase 1 re-degrades a broken session on every tick; a repeated break must be idempotent - no revision inflation, one counted break. */
    @Test void repeatedCoverageBreaksAreIdempotent() {
        apply(OVERWORLD); apply(baseline(0, 0));
        apply(new WorldObservation.CoverageBreak("OVERLOADED")); long rev = w.revision(); long breaks = budget.coverageBreaks.get();
        for (int i = 0; i < 200; i++) apply(new WorldObservation.CoverageBreak("OVERLOADED"));
        for (int i = 0; i < 50; i++) w.apply(KEY, new WorldObservation.TickObserved(i), ++seq, 0, "OVERLOADED");
        assertEquals(rev, w.revision(), "no revision when a break changes no chunk"); assertEquals(breaks + 200, budget.coverageBreaks.get(), "explicit break events are counted; the sticky per-observation reason is state, not an event");
        assertEquals(201, w.transportBreaks()); assertTrue(w.transportBroken()); assertEquals("OVERLOADED", w.snapshot().transportBreakReason());
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, w.coverageOf(new ChunkKey(0, 0)));
    }
    /** Bounded block-update diagnostic (real-client qualification evidence for place/break): a count and the last update summary, never a dump. */
    @Test void blockUpdateDiagnosticCountsAppliedUpdatesAndNamesTheLastOne() {
        apply(OVERWORLD); apply(baseline(0, 0));
        assertEquals(0, w.snapshot().blockUpdates()); assertEquals("", w.snapshot().lastBlockUpdate());
        apply(new WorldObservation.BlockChanged(new BlockPos(3, 70, 3), STAIRS)); w.publishNow();
        assertEquals(1, w.snapshot().blockUpdates()); assertEquals(STAIRS.canonical() + " @ 3,70,3 seq 3", w.snapshot().lastBlockUpdate());
        var multi = new ArrayList<WorldObservation.BlockChange>(); for (int i = 0; i < 5; i++) multi.add(new WorldObservation.BlockChange(new BlockPos(i, 66, 7), WATER));
        apply(new WorldObservation.SectionBlocksChanged(0, 4, 0, multi)); w.publishNow();
        assertEquals(6, w.snapshot().blockUpdates()); assertEquals(WATER.canonical() + " @ 4,66,7 seq 4 (batch of 5)", w.snapshot().lastBlockUpdate());
        assertEquals(2, w.snapshot().recentBlockUpdates().size()); assertEquals(w.snapshot().lastBlockUpdate(), w.snapshot().recentBlockUpdates().getLast());
        for (int i = 0; i < 20; i++) apply(new WorldObservation.BlockChanged(new BlockPos(i & 15, 70, 1), DIRT)); w.publishNow();
        assertEquals(ObserverWorld.RECENT_BLOCK_UPDATES, w.snapshot().recentBlockUpdates().size(), "bounded ring"); assertTrue(w.snapshot().recentBlockUpdates().getFirst().contains("@ 12,70,1"), "oldest retained is the 13th of 20");
        apply(new WorldObservation.BlockChanged(new BlockPos(0, 400, 0), DIRT)); w.publishNow(); // outside the dimension (y max 319): not applied, not counted
        assertEquals(26, w.snapshot().blockUpdates());
    }
}
