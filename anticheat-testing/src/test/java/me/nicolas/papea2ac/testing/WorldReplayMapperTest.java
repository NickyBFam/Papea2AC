package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.BlockLookup.Status;

/** Sections 50/51: the Phase 3 replay projection preserves coverage exactly and never increases knowledge. */
class WorldReplayMapperTest {
    static final SessionKey KEY = new SessionKey(new UUID(70, 1), 1);
    WorldBudget budget; BlockStateDictionary dict; ObserverWorld w; long seq;
    @BeforeEach void setUp() { budget = new WorldBudget(); dict = new BlockStateDictionary(); w = new ObserverWorld(KEY, budget, dict); seq = 0; }
    void apply(WorldObservation o) { w.apply(KEY, o, ++seq, 0, null); }

    @Test void exportRestoreExportIsIdenticalAndLookupsAgreeEvenWithADifferentDictionary() {
        apply(OVERWORLD); apply(baseline(0, 0)); apply(baseline(-3, 7));
        apply(new WorldObservation.BlockChanged(new BlockPos(1, 70, 1), STAIRS));
        apply(new WorldObservation.BlockChanged(new BlockPos(40, 10, 40), WATER)); // partial chunk (2,2)
        apply(new WorldObservation.BlockEntityObserved(new BlockPos(2, 70, 2), "minecraft:chest", 44));
        apply(new WorldObservation.ChunkUnloaded(-3, 7));
        var capsule = WorldReplayMapper.export(w, dict);
        // A different run: fresh dictionary (different integer ids), fresh budget.
        var otherDict = new BlockStateDictionary(); otherDict.intern(BlockStateKey.of("minecraft:shuffle_ids_first"));
        var restored = WorldReplayMapper.restore(capsule, KEY, new WorldBudget(), otherDict);
        assertEquals(capsule, WorldReplayMapper.export(restored, otherDict), "export -> restore -> export is byte-for-byte equal despite different ids");
        assertEquals(STAIRS, restored.blockAt(new BlockPos(1, 70, 1)).state()); assertEquals(WATER, restored.blockAt(new BlockPos(40, 10, 40)).state());
        assertEquals(STONE, restored.blockAt(new BlockPos(0, 0, 0)).state());
        assertEquals(WorldCoverage.COMPLETE, restored.coverageOf(new ChunkKey(0, 0))); assertEquals(WorldCoverage.PARTIAL, restored.coverageOf(new ChunkKey(2, 2)));
        assertEquals(WorldCoverage.UNLOADED, restored.coverageOf(new ChunkKey(-3, 7))); assertEquals(Status.UNLOADED, restored.blockAt(new BlockPos(-48, 0, 112)).status());
        assertEquals(1, restored.chunk(new ChunkKey(0, 0)).orElseThrow().blockEntities().size());
        assertEquals(w.revision(), restored.revision()); assertEquals(w.lastSequence(), restored.lastSequence()); assertEquals(w.worldEpoch(), restored.worldEpoch());
    }
    @Test void partialStaysPartialAndUnknownCellsNeverDecodeAsAir() {
        apply(OVERWORLD); apply(new WorldObservation.BlockChanged(new BlockPos(5, 70, 5), STONE));
        var restored = WorldReplayMapper.restore(WorldReplayMapper.export(w, dict), KEY, new WorldBudget(), new BlockStateDictionary());
        assertEquals(WorldCoverage.PARTIAL, restored.coverageOf(new ChunkKey(0, 0)));
        assertEquals(STONE, restored.blockAt(new BlockPos(5, 70, 5)).state());
        assertEquals(Status.UNKNOWN_CELL, restored.blockAt(new BlockPos(6, 70, 5)).status()); assertEquals(Status.UNKNOWN_SECTION, restored.blockAt(new BlockPos(5, 10, 5)).status());
    }
    @Test void contentNotRetainedTransportIncompleteAndEvictedAreCarriedVerbatim() {
        apply(OVERWORLD); apply(unretained(0, 0, "OVER_INLINE_CAP:99999")); apply(baseline(1, 0));
        var beforeBreak = WorldReplayMapper.restore(WorldReplayMapper.export(w, dict), KEY, new WorldBudget(), new BlockStateDictionary());
        assertEquals(WorldCoverage.CONTENT_NOT_RETAINED, beforeBreak.coverageOf(new ChunkKey(0, 0))); assertEquals("OVER_INLINE_CAP:99999", beforeBreak.chunk(new ChunkKey(0, 0)).orElseThrow().coverageNote());
        assertEquals(Status.CONTENT_NOT_RETAINED, beforeBreak.blockAt(new BlockPos(0, 0, 0)).status());
        apply(new WorldObservation.CoverageBreak("OVERLOADED"));
        var capsule = WorldReplayMapper.export(w, dict);
        assertTrue(capsule.transportBroken()); assertEquals("OVERLOADED", capsule.transportBreakReason());
        var restored = WorldReplayMapper.restore(capsule, KEY, new WorldBudget(), new BlockStateDictionary());
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, restored.coverageOf(new ChunkKey(0, 0)), "a transport break degrades every held chunk, whatever it was before");
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, restored.coverageOf(new ChunkKey(1, 0))); assertEquals(Status.TRANSPORT_INCOMPLETE, restored.blockAt(new BlockPos(16, 0, 0)).status());
        assertTrue(restored.transportBroken(), "a restored world stays broken; later packets cannot heal it");
    }
    @Test void evictedTombstonesSurviveAndAWorldEpochIsPreserved() {
        var tiny = new WorldBudget(WorldBudget.SHARED_PARTITION_BYTES, 2600); w = new ObserverWorld(KEY, tiny, dict); seq = 0;
        apply(OVERWORLD); apply(NETHER); apply(new WorldObservation.ViewCenter(0, 0)); for (int x = 0; x < 15; x++) apply(baseline(x, 0, nether()));
        assertTrue(w.evictedChunks() > 0);
        var restored = WorldReplayMapper.restore(WorldReplayMapper.export(w, dict), KEY, new WorldBudget(), new BlockStateDictionary());
        assertEquals(1, restored.worldEpoch()); assertEquals("minecraft:the_nether", restored.dimension().orElseThrow().dimensionId());
        assertEquals(WorldCoverage.EVICTED, restored.coverageOf(new ChunkKey(14, 0))); assertEquals(Status.EVICTED, restored.blockAt(new BlockPos(224, 0, 0)).status());
    }
    static List<WorldObservation.SectionContent> nether() { var l = new ArrayList<WorldObservation.SectionContent>(); for (int i = 0; i < 16; i++) l.add(uniformSection(i < 4 ? STONE : AIR)); return l; }
    @Test void restoreRejectsCorruptCapsulesWithoutInstallingAnything() {
        apply(OVERWORLD); apply(baseline(0, 0));
        var capsule = WorldReplayMapper.export(w, dict);
        assertThrows(IllegalArgumentException.class, () -> WorldReplayMapper.restore(capsule, new SessionKey(KEY.player(), 2), new WorldBudget(), new BlockStateDictionary()), "epoch mismatch");
        var chunk = capsule.chunks().getFirst(); var bad = chunk.sections().get(8);
        var corrupt = new me.nicolas.papea2ac.schema.WorldCapsule.Section(bad.index(), bad.bits(), bad.palette(), bad.packed().subList(0, bad.packed().size() - 1), bad.knownMask());
        var sections = new ArrayList<>(chunk.sections()); sections.set(8, corrupt);
        var corruptChunk = new me.nicolas.papea2ac.schema.WorldCapsule.Chunk(chunk.x(), chunk.z(), chunk.coverage(), chunk.note(), chunk.baselineSequence(), chunk.createdSequence(), chunk.revision(), chunk.sequence(), chunk.sectionCount(), sections, chunk.blockEntities());
        var corruptCapsule = new me.nicolas.papea2ac.schema.WorldCapsule(1, capsule.sessionEpoch(), capsule.worldEpoch(), capsule.hasDimension(), capsule.dimensionId(), capsule.minY(), capsule.height(), capsule.revision(), capsule.lastSequence(), capsule.transportBroken(), capsule.transportBreakReason(), List.of(corruptChunk), capsule.tombstones());
        var budget = new WorldBudget();
        assertThrows(IllegalArgumentException.class, () -> WorldReplayMapper.restore(corruptCapsule, KEY, budget, new BlockStateDictionary()));
        assertEquals(0, budget.sharedBytes(), "nothing was acquired for a rejected capsule"); assertEquals(0, budget.chunks.get());
    }
    @Test void exportIsDeterministicAcrossRepeatedCallsAndIndependentOfInsertionOrder() {
        apply(OVERWORLD); apply(baseline(3, 1)); apply(baseline(-1, -1)); apply(baseline(0, 0));
        assertEquals(WorldReplayMapper.export(w, dict), WorldReplayMapper.export(w, dict));
        var otherDict = new BlockStateDictionary(); var other = new ObserverWorld(KEY, new WorldBudget(), otherDict); long s = 0;
        other.apply(KEY, OVERWORLD, ++s, 0, null); other.apply(KEY, baseline(0, 0), ++s, 0, null); other.apply(KEY, baseline(-1, -1), ++s, 0, null); other.apply(KEY, baseline(3, 1), ++s, 0, null);
        var a = WorldReplayMapper.export(w, dict); var b = WorldReplayMapper.export(other, otherDict);
        assertEquals(a.chunks().stream().map(c -> c.x() + "," + c.z()).toList(), b.chunks().stream().map(c -> c.x() + "," + c.z()).toList(), "chunk order is canonical, not HashMap order");
    }
}
