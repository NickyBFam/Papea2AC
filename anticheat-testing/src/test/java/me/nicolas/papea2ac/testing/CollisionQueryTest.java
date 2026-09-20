package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.collision.CollisionResult.Certainty;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/**
 * World-level collision queries against observer worlds (task §49 items 1-9 and 30-45): coverage semantics inherited
 * from Phase 3 (UNKNOWN != AIR, UNLOADED != EVICTED, CONTENT_NOT_RETAINED, epochs), the vanilla broad phase (padded
 * range, ring rule), bounds, determinism, immutability, isolation and cleanup. Layout: baseline chunk surface is dirt at
 * y=68..69, air from y=70.
 */
class CollisionQueryTest {
    @Test void emptyWorldQueryIsUnknownNeverClear() {
        var s = new Scene(); var r = s.query(standing(4.5, 70, 4.5));
        assertEquals(Certainty.INCOMPLETE, r.certainty()); assertFalse(r.definitelyClear()); assertFalse(r.definitelyObstructed()); assertEquals(0, r.hitCount());
        assertEquals(r.cellsVisited(), r.count(CellStatus.UNKNOWN_CHUNK), "every visited cell is UNKNOWN_CHUNK");
        assertEquals(expectedCells(standing(4.5, 70, 4.5)), r.cellsVisited());
    }
    @Test void standingOnDirtIsCompleteAndClearWhileOverlappingItIsObstructed() {
        var s = new Scene(); s.baseline(0, 0);
        var onTop = s.query(standing(4.5, 70, 4.5));
        assertEquals(Certainty.COMPLETE, onTop.certainty()); assertTrue(onTop.definitelyClear(), "feet exactly on the top face: face contact is not intersection"); assertEquals(0, onTop.hitCount());
        assertEquals(expectedCells(standing(4.5, 70, 4.5)), onTop.cellsVisited()); assertTrue(onTop.count(CellStatus.EXACT) > 0 && onTop.count(CellStatus.EXACT_EMPTY) > 0);
        var sunk = s.query(standing(4.5, 69.5, 4.5));
        assertEquals(Certainty.COMPLETE, sunk.certainty()); assertTrue(sunk.definitelyObstructed()); assertFalse(sunk.definitelyClear());
        assertEquals(1, sunk.hitCount(), "the 0.6-wide box centred in the cell touches one dirt cube"); for (var h : sunk.hits()) { assertEquals(69, h.y()); assertSame(CollisionShape.FULL_CUBE, h.shape()); assertTrue(h.definite()); assertEquals(CellStatus.EXACT, h.status()); }
        var corner = s.query(standing(5.0, 69.5, 5.0)); assertEquals(4, corner.hitCount(), "over the cell corner it touches four dirt cubes");
        var inAir = s.query(standing(4.5, 75, 4.5)); assertTrue(inAir.definitelyClear()); assertEquals(0, inAir.count(CellStatus.EXACT), "only air cells around"); assertTrue(inAir.count(CellStatus.EXACT_EMPTY) > 0);
        var deep = s.query(new Aabb(4.2, 40, 4.2, 4.8, 41.8, 4.8)); assertEquals(Certainty.COMPLETE, deep.certainty()); assertEquals(2, deep.hitCount(), "fully inside stone: the two cubes of the column overlap the 0.6x1.8x0.6 box");
    }
    @Test void exactFaceTouchOverlapAndTinyOffsets() {
        var s = new Scene(); s.baseline(0, 0);
        assertEquals(0, s.query(new Aabb(4, 70, 4, 5, 71, 5)).hitCount(), "resting exactly on the dirt top");
        assertEquals(0, s.query(new Aabb(5, 68, 4, 6, 69, 5)).hitCount() > 0 ? 0 : 1, "a unit box inside the dirt layer at y=68 overlaps dirt");
        assertEquals(1, s.query(new Aabb(4.2, 70 - 1.0E-9, 4.2, 4.8, 71, 4.8)).hitCount(), "1e-9 below the surface: strict overlap with one dirt cube");
        assertEquals(0, s.query(new Aabb(4.2, 70 + 1.0E-9, 4.2, 4.8, 71, 4.8)).hitCount());
        assertEquals(1, s.query(new Aabb(4.2, 69.99999995, 4.2, 4.8, 71, 4.8)).hitCount(), "5e-8 overlap with a full cube counts (strict AABB rule for Shapes.block())");
        s.set(4, 70, 4, SLAB_BOTTOM);
        assertEquals(0, s.query(new Aabb(4.2, 70.5, 4.2, 4.8, 72, 4.8)).hitCount(), "resting on a slab top");
        assertEquals(0, s.query(new Aabb(4.2, 70.5 - 5.0E-8, 4.2, 4.8, 72, 4.8)).hitCount(), "5e-8 into a non-full shape is merged away by the voxel join (Shapes.EPSILON)");
        assertEquals(1, s.query(new Aabb(4.2, 70.5 - 2.0E-7, 4.2, 4.8, 72, 4.8)).hitCount(), "2e-7 into the slab is a hit");
    }
    @Test void negativeCoordinatesChunkBoundarySectionBoundaryAndNegativeY() {
        var s = new Scene(); s.baseline(0, 0); s.baseline(-1, 0); s.baseline(-1, -1); s.baseline(0, -1);
        var neg = s.query(standing(-3.0, 69.5, -3.0)); assertEquals(Certainty.COMPLETE, neg.certainty()); assertEquals(4, neg.hitCount()); for (var h : neg.hits()) { assertTrue(h.x() < 0 && h.z() < 0); assertTrue(h.x() == -3 || h.x() == -4); }
        var straddle = s.query(new Aabb(-0.3, 69.5, -0.3, 0.3, 71.3, 0.3)); assertEquals(Certainty.COMPLETE, straddle.certainty());
        var cells = new HashSet<String>(); for (var h : straddle.hits()) cells.add(h.x() + "," + h.z()); assertEquals(Set.of("-1,-1", "-1,0", "0,-1", "0,0"), cells, "hits from all four chunks around the origin");
        s.set(0, 79, 0, STONE); s.set(0, 80, 0, STONE);
        var section = s.query(new Aabb(0.2, 79.2, 0.2, 0.8, 80.8, 0.8)); assertEquals(2, section.hitCount(), "cells on both sides of the y=80 section boundary"); assertEquals(Certainty.COMPLETE, section.certainty());
        var negY = s.query(new Aabb(4.2, -60.5, 4.2, 4.8, -59, 4.8)); assertEquals(Certainty.COMPLETE, negY.certainty()); assertTrue(negY.definitelyObstructed(), "stone at negative Y (section 0 of the overworld)");
        for (var h : negY.hits()) assertTrue(h.y() < -58);
    }
    @Test void partialChunkKnownCellsAreExactAndUnknownCellsStayUnknown() {
        var s = new Scene(); s.set(4, 70, 4, STONE); // no baseline: a PARTIAL chunk with exactly one known cell
        var r = s.query(new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8));
        assertEquals(Certainty.INCOMPLETE, r.certainty()); assertTrue(r.definitelyObstructed(), "the observed stone is real geometry"); assertEquals(1, r.hitCount());
        assertEquals(1, r.count(CellStatus.EXACT)); assertTrue(r.count(CellStatus.UNKNOWN_CELL) > 0, "the other cells of the touched section were never observed"); assertTrue(r.count(CellStatus.UNKNOWN_SECTION) + r.count(CellStatus.UNKNOWN_CELL) > 0);
        assertFalse(r.definitelyClear());
        var elsewhere = s.query(standing(4.5, 100, 4.5)); assertEquals(Certainty.INCOMPLETE, elsewhere.certainty()); assertTrue(elsewhere.count(CellStatus.UNKNOWN_SECTION) > 0, "an untouched section of a partial chunk is UNKNOWN_SECTION");
    }
    @Test void unknownNeighbourChunkMakesTheQueryIncompleteEvenWhenTheKnownPartIsClear() {
        var s = new Scene(); s.baseline(0, 0);
        var edge = s.query(standing(15.5, 70, 8.5)); // the padded range reaches x=16 (chunk 1, never observed)
        assertEquals(Certainty.INCOMPLETE, edge.certainty()); assertEquals(0, edge.hitCount()); assertFalse(edge.definitelyClear(), "a fence in the unknown neighbour could reach into the box: never claim clear");
        assertTrue(edge.count(CellStatus.UNKNOWN_CHUNK) > 0);
        assertTrue(s.query(standing(13.5, 70, 8.5)).definitelyClear(), "one block further in, every visited cell is known");
    }
    @Test void unloadedAndEvictedAndContentNotRetainedAreDistinctAndNeverAir() {
        var s = new Scene(); s.baseline(0, 0); s.baseline(1, 0);
        assertTrue(s.query(standing(20.5, 70, 8.5)).definitelyClear());
        s.apply(new WorldObservation.ChunkUnloaded(1, 0));
        var unloaded = s.query(standing(20.5, 70, 8.5)); assertEquals(Certainty.INCOMPLETE, unloaded.certainty()); assertEquals(unloaded.cellsVisited(), unloaded.count(CellStatus.UNLOADED)); assertEquals(0, unloaded.count(CellStatus.EVICTED));
        s.apply(unretained(2, 0, "OVER_INLINE_CAP"));
        var notRetained = s.query(standing(36.5, 70, 8.5)); assertEquals(Certainty.INCOMPLETE, notRetained.certainty()); assertEquals(notRetained.cellsVisited(), notRetained.count(CellStatus.CONTENT_NOT_RETAINED), "content dropped for resource safety is not air");
        // eviction: a floor-tier world with a distant view center drops far chunks EVICTED
        var tight = new Scene(2, new WorldBudget(96L << 20, WorldBudget.PRIVATE_FLOOR_BYTES)); tight.baseline(0, 0); tight.apply(new WorldObservation.ViewCenter(0, 0));
        var evicted = new ChunkKey(210, 0); tight.baseline(210, 0); for (int sec = 0; sec < 24; sec++) tight.set(210 * 16 + (sec & 15), -64 + sec * 16 + 3, 3, SLAB_TOP); // far chunk with 24 private copy-on-write sections
        assertTrue(tight.query(standing(210 * 16 + 8.5, 70, 8.5)).definitelyClear());
        for (int i = 1; i < 100 && tight.world.coverageOf(evicted) != WorldCoverage.EVICTED; i++) { tight.baseline(i, 0); for (int sec = 0; sec < 24; sec++) tight.set(i * 16 + (sec & 15), -64 + sec * 16 + 3, 3, sec % 2 == 0 ? SLAB_TOP : FENCE_POST); } // nearer chunks fill the tier; the farthest goes first
        assertEquals(WorldCoverage.EVICTED, tight.world.coverageOf(evicted), "the private tier evicted the farthest chunk"); var ev = tight.query(standing(evicted.x() * 16 + 8.5, 70, 8.5));
        assertEquals(Certainty.INCOMPLETE, ev.certainty()); assertTrue(ev.count(CellStatus.EVICTED) > 0); assertEquals(0, ev.count(CellStatus.UNLOADED), "eviction is not an unload");
    }
    @Test void wrongWorldEpochAndDimensionChangeAreExplicitFailures() {
        var s = new Scene(); s.baseline(0, 0);
        var wrong = s.engine.query(s.world.currentView(), standing(4.5, 70, 4.5), ShapeContext.UNKNOWN, s.world.worldEpoch() + 1);
        assertEquals(Certainty.UNAVAILABLE, wrong.certainty()); assertEquals(CellStatus.WRONG_WORLD_EPOCH, wrong.unavailableReason()); assertEquals(0, wrong.cellsVisited()); assertFalse(wrong.definitelyClear());
        long before = s.world.worldEpoch(); var oldView = s.world.currentView();
        s.apply(NETHER); // respawn into another dimension: new epoch, old chunks gone
        assertEquals(before + 1, s.world.worldEpoch());
        var old = s.engine.query(oldView, standing(4.5, 70, 4.5), ShapeContext.UNKNOWN, before); // a view captured before the change still carries the old epoch...
        assertNotEquals(Certainty.COMPLETE, old.certainty() == Certainty.COMPLETE && old.count(CellStatus.UNKNOWN_CHUNK) == 0 ? Certainty.COMPLETE : Certainty.INCOMPLETE, "...but its chunks were released by the respawn: nothing resolves from it");
        var fresh = s.query(standing(4.5, 70, 4.5)); assertEquals(Certainty.INCOMPLETE, fresh.certainty()); assertEquals(fresh.cellsVisited(), fresh.count(CellStatus.UNKNOWN_CHUNK), "the overworld chunk does not exist in the nether epoch: coordinates alone never resolve");
        var stale = s.engine.query(s.world.currentView(), standing(4.5, 70, 4.5), ShapeContext.UNKNOWN, before); assertEquals(Certainty.UNAVAILABLE, stale.certainty()); assertEquals(CellStatus.WRONG_WORLD_EPOCH, stale.unavailableReason());
    }
    @Test void dimensionUnknownFutureSequenceAndHistoryNotRetainedAreUnavailable() {
        var key = new SessionKey(new UUID(0x4C, 9), 9); var dict = new BlockStateDictionary(); var world = new ObserverWorld(key, new WorldBudget(), dict); var engine = new CollisionEngine(PROFILE, dict);
        var noDim = engine.query(world.currentView(), standing(0.5, 70, 0.5), ShapeContext.UNKNOWN, 0); assertEquals(Certainty.UNAVAILABLE, noDim.certainty()); assertEquals(CellStatus.DIMENSION_UNKNOWN, noDim.unavailableReason());
        var s = new Scene(); long b = s.baseline(0, 0);
        var future = s.queryAt(b + 5, standing(4.5, 70, 4.5)); assertEquals(Certainty.UNAVAILABLE, future.certainty()); assertEquals(CellStatus.FUTURE_SEQUENCE, future.unavailableReason());
        var beforeEpoch = s.queryAt(0, standing(4.5, 70, 4.5)); assertEquals(Certainty.UNAVAILABLE, beforeEpoch.certainty()); assertEquals(CellStatus.WRONG_WORLD_EPOCH, beforeEpoch.unavailableReason());
        assertTrue(s.engine.metrics().snapshot().historicalMisses() >= 2);
    }
    @Test void historicalQueryReturnsTheGeometryObservedThroughThatSequenceOnly() {
        var s = new Scene(); s.baseline(0, 0);
        long a = s.mark(); long c = s.set(4, 70, 4, STONE); long d = s.mark();
        var atA = s.queryAt(a, new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)); assertEquals(Certainty.COMPLETE, atA.certainty()); assertTrue(atA.definitelyClear(), "before C the cell was air");
        var atC = s.queryAt(c, new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)); assertTrue(atC.definitelyObstructed(), "sequence semantics are inclusive: at C the stone is there");
        var atD = s.queryAt(d, new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)); assertTrue(atD.definitelyObstructed()); assertEquals(Certainty.COMPLETE, atD.certainty());
        assertEquals(a, atA.sequence()); assertEquals(d, atD.sequence()); assertEquals(s.world.worldEpoch(), atA.worldEpoch());
        long e = s.set(4, 70, 4, AIR);
        assertTrue(s.queryAt(d, new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)).definitelyObstructed(), "removing the block later never changes the past");
        assertTrue(s.queryAt(e, new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)).definitelyClear());
    }
    @Test void playerWorldTemporalCoherenceBeforeAndAfterABlockChange() {
        // world baseline at A, player state B, block change C, player state D (the Phase 3 coherence script, now with geometry)
        var s = new Scene(); long a = s.baseline(0, 0); long b = s.mark(); long c = s.set(5, 70, 4, STAIRS); long d = s.mark();
        var boxB = standing(5.5, 70, 4.5);
        var atB = s.queryAt(b, boxB); assertTrue(atB.definitelyClear(), "B sees pre-C geometry: no stairs yet");
        var atD = s.queryAt(d, boxB); assertTrue(atD.definitelyObstructed(), "D sees post-C geometry"); assertEquals(1, atD.hitCount()); assertEquals(CellStatus.EXACT, atD.hits().get(0).status());
        assertTrue(a < b && b < c && c < d);
        assertEquals(atD.hits(), s.query(boxB).hits(), "the current view agrees with the latest historical view");
    }
    @Test void oversizedAndOutOfRangeQueriesAreRejectedBeforeAnyWork() {
        var s = new Scene(); s.baseline(0, 0);
        var huge = s.query(new Aabb(0, 60, 0, 40, 80, 40)); assertEquals(Certainty.REJECTED, huge.certainty()); assertEquals(0, huge.cellsVisited()); assertEquals(0, huge.hitCount());
        var thinButLong = s.query(new Aabb(0, 70, 0, 0.1, 70.1, 5000)); assertEquals(Certainty.REJECTED, thinButLong.certainty());
        var flat = s.query(new Aabb(0, 70, 0, 61, 70.1, 61)); assertEquals(Certainty.REJECTED, flat.certainty(), "63*3*63 padded cells > 4096");
        var limit = s.query(new Aabb(0.5, 70.5, 0.5, 12.5, 82.5, 12.5)); assertNotEquals(Certainty.REJECTED, limit.certainty(), "15*15*15 padded = 3375 cells fits"); assertEquals(3375 - 8, limit.cellsVisited());
        var exact = s.query(new Aabb(0, 70, 0, 12.5, 82.5, 12.5)); assertNotEquals(Certainty.REJECTED, exact.certainty(), "16*16*16 padded = exactly 4096 fits"); assertEquals(4096 - 8, exact.cellsVisited());
        assertEquals(Certainty.REJECTED, s.query(new Aabb(0, 70, 0, 13.5, 82.5, 12.5)).certainty(), "17*16*16 does not");
        var far = s.query(new Aabb(3.3E7, 70, 0, 3.3E7 + 1, 71, 1)); assertEquals(Certainty.REJECTED, far.certainty(), "beyond the protocol coordinate space");
        assertEquals(5, s.engine.metrics().snapshot().rejected());
        assertThrows(IllegalArgumentException.class, () -> new Aabb(Double.NaN, 0, 0, 1, 1, 1), "NaN never reaches the engine: the value type refuses it");
    }
    @Test void manyHitQueryIsTruncatedExplicitlyAtTheBoxBudget() {
        var s = new Scene(); s.baseline(0, 0);
        for (int x = 1; x <= 12; x++) for (int y = 71; y <= 82; y++) for (int z = 1; z <= 12; z++) s.set(x, y, z, CAULDRON); // 1728 cells x 15 boxes = 25920 boxes > 16384
        var r = s.query(new Aabb(1, 71, 1, 13, 83, 13));
        assertTrue(r.truncated()); assertEquals(Certainty.INCOMPLETE, r.certainty()); assertTrue(r.boxCount() > 16384 && r.boxCount() <= 16384 + 15); assertTrue(r.definitelyObstructed());
        assertEquals(1, s.engine.metrics().snapshot().truncated());
        var small = s.query(new Aabb(1.2, 71.2, 1.2, 1.8, 72.8, 1.8)); assertFalse(small.truncated()); assertEquals(Certainty.COMPLETE, small.certainty()); assertEquals(2 * 15, small.boxCount());
    }
    @Test void malformedOrUnknownObservedStatesAreUnsupportedNotAirNotCrash() {
        var s = new Scene(); s.baseline(0, 0); s.set(4, 70, 4, BOGUS); s.set(5, 70, 4, MALFORMED);
        var r = s.query(new Aabb(4.2, 70.2, 4.2, 5.8, 71, 4.8));
        assertEquals(Certainty.INCOMPLETE, r.certainty()); assertEquals(2, r.count(CellStatus.UNSUPPORTED_BLOCK_STATE)); assertEquals(0, r.hitCount(), "no geometry is invented for an unsupported state"); assertFalse(r.definitelyClear());
        assertEquals(2, s.engine.metrics().snapshot().unsupportedStates());
        assertTrue(s.query(standing(10.5, 70, 10.5)).definitelyClear(), "other cells are unaffected");
    }
    @Test void contextDependentAndDynamicCellsAreEnvelopesUntilContextIsKnown() {
        var s = new Scene(); s.baseline(0, 0); s.set(4, 70, 4, SCAFFOLDING); s.set(6, 70, 4, SHULKER); s.set(8, 70, 4, MOVING_PISTON);
        var scaff = s.query(new Aabb(4.2, 70.9, 4.2, 4.8, 72, 4.8)); assertEquals(Certainty.INCOMPLETE, scaff.certainty()); assertEquals(1, scaff.count(CellStatus.CONTEXT_REQUIRED));
        assertFalse(scaff.definitelyObstructed()); assertFalse(scaff.definitelyClear()); assertEquals(1, scaff.hitCount()); assertFalse(scaff.hits().get(0).definite(), "possible-only: the stable plate exists for a body above that is not descending");
        var scaffKnown = s.query(new Aabb(4.2, 70.999995, 4.2, 4.8, 72, 4.8), ShapeContext.body(70.999995)); // within isAbove's 1e-5 of the top: the plate is there and the body overlaps it by 5e-6
        assertEquals(Certainty.COMPLETE, scaffKnown.certainty()); assertTrue(scaffKnown.definitelyObstructed()); assertEquals(CellStatus.EXACT_CONTEXT, scaffKnown.hits().get(0).status());
        var scaffDesc = s.query(new Aabb(4.2, 70.999995, 4.2, 4.8, 72, 4.8), ShapeContext.of(70.999995, true, 0, false)); assertTrue(scaffDesc.definitelyClear(), "sneaking: the plate gives way");
        var scaffInside = s.query(new Aabb(4.2, 70.9, 4.2, 4.8, 72, 4.8), ShapeContext.body(70.9)); assertTrue(scaffInside.definitelyClear(), "a body below the top face (by more than 1e-5) is inside the scaffolding: no plate (vanilla lets it fall through)");
        var shulker = s.query(new Aabb(6.2, 71.2, 6.2 - 2, 6.8, 72, 6.8 - 2)); // above the box: only the lid could reach
        assertEquals(1, shulker.count(CellStatus.DYNAMIC_BLOCK_ENTITY)); assertFalse(shulker.definitelyObstructed()); assertFalse(shulker.definitelyClear()); assertEquals(1, shulker.hitCount()); assertFalse(shulker.hits().get(0).definite());
        var shulkerBody = s.query(new Aabb(6.2, 70.5, 6.2 - 2, 6.8, 72, 6.8 - 2)); assertTrue(shulkerBody.definitelyObstructed(), "the closed body is certain"); assertEquals(2, shulkerBody.hitCount());
        var piston = s.query(new Aabb(9.2, 70.2, 4.2, 9.8, 71.8, 4.8)); // the cell east of the moving piston: reachable by the moving block
        assertEquals(1, piston.count(CellStatus.DYNAMIC_BLOCK_ENTITY), "the moving piston in the neighbouring cell is visited from the face ring"); assertFalse(piston.definitelyClear()); assertFalse(piston.definitelyObstructed());
    }
    @Test void resultsAreImmutableDeterministicAndOrdered() {
        var s = new Scene(); s.baseline(0, 0); s.set(4, 70, 4, FENCE_POST); s.set(5, 70, 5, SLAB_TOP);
        var box = new Aabb(3.5, 69.5, 3.5, 5.5, 72, 5.5);
        CollisionResult r1 = s.query(box), r2 = s.query(box);
        assertEquals(r1, r2); assertEquals(r1.hashCode(), r2.hashCode()); assertEquals(r1.hits(), r2.hits()); assertEquals(r1.summary(), r2.summary());
        assertThrows(UnsupportedOperationException.class, () -> r1.hits().add(r1.hits().get(0)));
        int[] counts = r1.statusCounts(); counts[0] = 999; assertNotEquals(999, r1.statusCounts()[0], "defensive copy");
        // deterministic traversal order: x fastest, then y, then z (vanilla Cursor3D)
        var hits = r1.hits(); for (int i = 1; i < hits.size(); i++) { var p = hits.get(i - 1); var h = hits.get(i); assertTrue(p.z() < h.z() || (p.z() == h.z() && (p.y() < h.y() || (p.y() == h.y() && p.x() <= h.x()))), "order at " + i); }
        assertEquals(expectedCells(box), r1.cellsVisited());
    }
    @Test void sessionsAreIsolatedAndCleanupLeavesNoSharedState() {
        var budget = new WorldBudget(); var a = new Scene(1, budget); var b = new Scene(2, budget);
        a.baseline(0, 0); b.baseline(0, 0); a.set(4, 70, 4, STONE);
        assertTrue(a.query(new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)).definitelyObstructed()); assertTrue(b.query(new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)).definitelyClear(), "another session never sees this session's observation");
        long shared = budget.sharedBytes(); assertTrue(shared > 0); a.world.close();
        assertEquals(shared, budget.sharedBytes(), "the baseline sections are still referenced by the other session (shared once, charged once)"); assertEquals(1, budget.observerWorlds.get());
        var closedQuery = a.engine.query(a.world.currentView(), standing(4.5, 70, 4.5), ShapeContext.UNKNOWN, a.world.worldEpoch());
        assertEquals(Certainty.INCOMPLETE, closedQuery.certainty()); assertEquals(closedQuery.cellsVisited(), closedQuery.count(CellStatus.UNKNOWN_CHUNK), "a closed world has no chunks: unknown, not air");
        b.world.close(); assertEquals(0, budget.sharedBytes()); assertEquals(0, budget.observerWorlds.get());
    }
    @Test void cellsAboveOrBelowTheDimensionAreExactlyEmptyAndTheQueryStaysComplete() {
        var s = new Scene(); s.baseline(0, 0);
        var above = s.query(new Aabb(4.2, 318.5, 4.2, 4.8, 320.3, 4.8)); assertEquals(Certainty.COMPLETE, above.certainty()); assertTrue(above.count(CellStatus.OUTSIDE_DIMENSION) > 0); assertTrue(above.definitelyClear());
        var below = s.query(new Aabb(4.2, -65, 4.2, 4.8, -63.5, 4.8)); assertEquals(Certainty.COMPLETE, below.certainty()); assertTrue(below.definitelyObstructed(), "bedrock-level stone at y=-64"); assertTrue(below.count(CellStatus.OUTSIDE_DIMENSION) > 0);
    }
    @Test void metricsCountEveryOutcomeWithFixedKeys() {
        var s = new Scene(); s.baseline(0, 0);
        s.query(standing(4.5, 70, 4.5)); s.query(standing(4.5, 69.5, 4.5)); s.query(standing(200.5, 70, 4.5)); s.query(new Aabb(0, 60, 0, 40, 80, 40));
        var m = s.engine.metrics().snapshot();
        assertEquals(4, m.queries()); assertEquals(2, m.complete()); assertEquals(1, m.incomplete()); assertEquals(1, m.rejected()); assertEquals(0, m.unavailable()); assertTrue(m.cellsVisited() > 0); assertEquals(1, m.hits()); assertEquals(1, m.boxesEmitted()); assertTrue(m.worldUnknownCells() > 0); assertTrue(m.shapeCacheHits() > m.shapeCacheMisses());
    }
}
