package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.BlockLookup.Status;

/**
 * Equivalence gate for the Phase 4 forward addition to the Phase 3 world module: the section-granular views must
 * answer exactly what the cell-level Phase 3 lookups answer, for the current state, for every historical sequence
 * and for the published snapshot, across a seeded random script of baselines, updates, unloads, breaks and a respawn.
 * The Phase 3 semantics are the oracle; the views add no knowledge and lose none.
 */
class WorldViewTest {
    static BlockLookup viaView(WorldView v, BlockPos p) {
        if (v.availability() != Status.KNOWN) return BlockLookup.miss(v.availability(), WorldCoverage.UNKNOWN);
        int idx = v.dimension().sectionIndexOf(p.y());
        var s = v.section(p.x() >> 4, p.z() >> 4, idx);
        if (s.status() != Status.KNOWN) return BlockLookup.miss(s.status(), s.coverage());
        int id = s.section().stateIdAt(p.sectionIndex());
        return id < 0 ? BlockLookup.miss(Status.UNKNOWN_CELL, s.coverage()) : BlockLookup.known(id, v.dictionary().key(id), s.revision(), s.coverage());
    }
    static void assertSame(BlockLookup expected, BlockLookup actual, String where) {
        assertEquals(expected.status(), actual.status(), where); assertEquals(expected.stateId(), actual.stateId(), where); assertEquals(expected.state(), actual.state(), where);
        assertEquals(expected.chunkCoverage(), actual.chunkCoverage(), where); if (expected.known()) assertEquals(expected.revision(), actual.revision(), where);
    }
    @Test void currentHistoricalAndPublishedViewsAgreeWithTheCellLevelLookups() {
        long seed = 0x77_0F_4C; var rnd = new Random(seed); var s = new Scene(); var cells = new ArrayList<BlockPos>();
        for (int i = 0; i < 300; i++) cells.add(new BlockPos(rnd.nextInt(-40, 41), rnd.nextInt(-70, 330), rnd.nextInt(-40, 41)));
        var states = List.of(STONE, DIRT, AIR, SLAB_TOP, FENCE_POST, STAIRS);
        var sequences = new ArrayList<Long>(); sequences.add(s.seq);
        for (int step = 0; step < 220; step++) {
            int k = rnd.nextInt(100);
            if (k < 20) sequences.add(s.baseline(rnd.nextInt(-3, 3), rnd.nextInt(-3, 3)));
            else if (k < 70) { var c = cells.get(rnd.nextInt(cells.size())); if (c.y() >= -64 && c.y() < 320) sequences.add(s.set(c.x(), c.y(), c.z(), states.get(rnd.nextInt(states.size())))); }
            else if (k < 80) sequences.add(s.apply(new WorldObservation.ChunkUnloaded(rnd.nextInt(-3, 3), rnd.nextInt(-3, 3))));
            else if (k < 85) sequences.add(s.apply(new WorldObservation.CoverageBreak("SEQUENCE_GAP:" + step)));
            else if (k < 88) sequences.add(s.apply(unretained(rnd.nextInt(-3, 3), rnd.nextInt(-3, 3), "OVER_INLINE_CAP")));
            else if (k < 90 && step > 150) sequences.add(s.apply(NETHER));
            else sequences.add(s.mark());
            // current view == blockAt, for every probe cell
            var cur = s.world.currentView(); assertEquals(Status.KNOWN, cur.availability());
            for (var c : cells) assertSame(s.world.blockAt(c), viaView(cur, c), "seed=" + seed + " step=" + step + " cur " + c);
            // every retained historical sequence == blockAtSequence
            for (long q : sequences) { var hv = s.world.viewAtSequence(q); for (int j = 0; j < 40; j++) { var c = cells.get(rnd.nextInt(cells.size())); assertSame(s.world.blockAtSequence(c, q), viaView(hv, c), "seed=" + seed + " step=" + step + " seq=" + q + " " + c); } }
            assertEquals(Status.FUTURE_SEQUENCE, s.world.viewAtSequence(s.seq + 1).availability());
        }
        // published snapshot view == snapshot.blockAt (cross-thread projection)
        s.world.publishNow(); var snap = s.world.snapshot(); var sv = snap.view(s.dictionary);
        for (var c : cells) assertSame(snap.blockAt(c, s.dictionary), viaView(sv, c), "snapshot " + c);
        assertEquals(snap.worldEpoch(), sv.worldEpoch()); assertEquals(snap.revision(), sv.revision()); assertEquals(snap.lastSequence(), sv.sequence());
    }
    @Test void viewsReportAvailabilityBeforeAnySectionLookup() {
        var key = new SessionKey(new UUID(0x4C, 5), 5); var dict = new BlockStateDictionary(); var w = new ObserverWorld(key, new WorldBudget(), dict);
        assertEquals(Status.DIMENSION_UNKNOWN, w.currentView().availability()); assertNull(w.currentView().dimension());
        assertEquals(Status.DIMENSION_UNKNOWN, w.currentView().section(0, 0, 4).status());
        var s = new Scene(); long b = s.baseline(0, 0);
        assertEquals(Status.KNOWN, s.world.viewAtSequence(b).availability()); assertEquals(Status.FUTURE_SEQUENCE, s.world.viewAtSequence(b + 1).availability()); assertEquals(Status.WRONG_WORLD_EPOCH, s.world.viewAtSequence(0).availability());
        assertEquals(Status.OUTSIDE_DIMENSION, s.world.currentView().section(0, 0, 24).status()); assertEquals(Status.OUTSIDE_DIMENSION, s.world.currentView().section(0, 0, -1).status());
        assertEquals(Status.UNKNOWN_CHUNK, s.world.currentView().section(7, 7, 4).status());
        var known = s.world.currentView().section(0, 0, 8); assertEquals(Status.KNOWN, known.status()); assertNotNull(known.section()); assertEquals(WorldCoverage.COMPLETE, known.coverage());
        assertThrows(IllegalArgumentException.class, () -> new WorldView.SectionLookup(Status.KNOWN, null, WorldCoverage.COMPLETE, 1));
    }
}
