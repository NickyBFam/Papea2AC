package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.collision.CollisionResult.Certainty;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.BlockShapeTable;
import me.nicolas.papea2ac.world.*;

/** Seeded property tests (task §52); every failure message carries the seed. */
class CollisionPropertyTest {
    static final long SEED = 0xC0DEC0DEL;
    static Aabb randomBox(Random r, double cx, double cy, double cz, double maxSize) {
        double x0 = cx + r.nextDouble(-8, 8), y0 = cy + r.nextDouble(-8, 8), z0 = cz + r.nextDouble(-8, 8);
        return new Aabb(x0, y0, z0, x0 + r.nextDouble(0, maxSize), y0 + r.nextDouble(0, maxSize), z0 + r.nextDouble(0, maxSize));
    }
    @Test void everyTableShapeHasOrderedBoxesInsideTheProfileEnvelopeAndAConsistentGrid() {
        var t = PROFILE.table(); var r = new BlockShapeResolver(PROFILE, new BlockStateDictionary(), new CollisionMetrics());
        for (int i = 0; i < t.shapeCount(); i++) {
            var s = r.shape(i);
            for (int b = 0; b < s.boxCount(); b++) { assertTrue(s.minX(b) <= s.maxX(b) && s.minY(b) <= s.maxY(b) && s.minZ(b) <= s.maxZ(b), "shape " + i); assertTrue(s.minX(b) >= -1 && s.maxX(b) <= 2 && s.minY(b) >= -1 && s.maxY(b) <= 2 && s.minZ(b) >= -1 && s.maxZ(b) <= 2, "shape " + i + " leaves the [-1,2] block-relative envelope"); }
            if (s.isEmpty()) continue;
            // grid consistency: every box is a union of filled grid cells and every filled cell lies in some box
            for (int x = 0; x < s.gridSize(0); x++) for (int y = 0; y < s.gridSize(1); y++) for (int z = 0; z < s.gridSize(2); z++) {
                double cx = (s.gridCoord(0, x) + s.gridCoord(0, x + 1)) / 2, cy = (s.gridCoord(1, y) + s.gridCoord(1, y + 1)) / 2, cz = (s.gridCoord(2, z) + s.gridCoord(2, z + 1)) / 2;
                boolean inBox = false; for (int b = 0; b < s.boxCount(); b++) if (s.minX(b) <= cx && cx < s.maxX(b) && s.minY(b) <= cy && cy < s.maxY(b) && s.minZ(b) <= cz && cz < s.maxZ(b)) inBox = true;
                assertEquals(inBox, s.gridFull(x, y, z), "shape " + i + " cell " + x + "," + y + "," + z);
            }
            assertEquals(s.large(), t.large(i));
        }
    }
    @Test void identicalStatesResolveIdenticallyAndFullCubeAcceptanceEqualsStrictAabbIntersection() {
        var r = new Random(SEED); var dict = new BlockStateDictionary(); var resolver = new BlockShapeResolver(PROFILE, dict, new CollisionMetrics());
        var keys = new ArrayList<BlockStateKey>(); for (var b : PROFILE.table().blocks()) { var props = new TreeMap<String, String>(); for (int i = 0; i < b.propertyCount(); i++) props.put(b.property(i), b.values(i).get(r.nextInt(b.values(i).size()))); keys.add(new BlockStateKey(b.name(), props)); }
        for (var k : keys) { var a = resolver.plan(k); var b = resolver.plan(k); assertEquals(a.resting(), b.resting(), k.canonical()); int id = dict.intern(k); assertSame(resolver.entry(id), resolver.entry(id)); assertEquals(a.resting(), resolver.entry(id).resting(), k.canonical()); }
        for (int i = 0; i < 20000; i++) {
            int cx = r.nextInt(-3, 3), cy = r.nextInt(-3, 3), cz = r.nextInt(-3, 3); var q = randomBox(r, 0, 0, 0, 3);
            assertEquals(q.intersects(cx, cy, cz, cx + 1, cy + 1, cz + 1), CollisionEngine.intersects(CollisionShape.FULL_CUBE, cx, cy, cz, q), "seed=" + SEED + " i=" + i);
        }
    }
    @Test void unknownNeverBecomesClearAndTraversalVisitsEachRelevantCellExactlyOnceInAFixedOrder() {
        var r = new Random(SEED); var s = new Scene(); s.baseline(0, 0); s.baseline(1, 0); s.baseline(0, 1); s.baseline(1, 1);
        var states = List.of(STONE, AIR, SLAB_TOP, FENCE_POST, STAIRS, CAULDRON, SCAFFOLDING, SHULKER, BOGUS);
        for (int i = 0; i < 200; i++) s.set(r.nextInt(0, 32), r.nextInt(69, 76), r.nextInt(0, 32), states.get(r.nextInt(states.size())));
        for (int i = 0; i < 1500; i++) {
            var q = randomBox(r, 16, 71, 16, 4); if (q.maxX() - q.minX() > 8) continue;
            CollisionResult a = s.query(q), b = s.query(q);
            assertEquals(a, b, "seed=" + SEED + " i=" + i + " determinism");
            assertEquals(expectedCells(q), a.cellsVisited(), "seed=" + SEED + " i=" + i + " visits every padded cell but the corners exactly once");
            int sum = 0; for (CellStatus cs : CellStatus.values()) sum += a.count(cs); assertEquals(a.cellsVisited(), sum, "every visited cell has exactly one status");
            boolean anyUnknown = false; for (CellStatus cs : CellStatus.values()) if (a.count(cs) > 0 && !cs.exact() && cs != CellStatus.OUTSIDE_DIMENSION) anyUnknown = true;
            if (anyUnknown) { assertEquals(Certainty.INCOMPLETE, a.certainty(), "seed=" + SEED + " i=" + i); assertFalse(a.definitelyClear()); } else assertEquals(Certainty.COMPLETE, a.certainty(), "seed=" + SEED + " i=" + i);
            var seen = new HashSet<Long>(); for (var h : a.hits()) { long key = (((long) h.x() & 0xFFFFF) << 40) | (((long) h.y() & 0xFFFFF) << 20) | ((long) h.z() & 0xFFFFF); if (h.definite()) assertTrue(seen.add(key), "seed=" + SEED + " i=" + i + " each cell contributes at most one definite hit"); }
            for (int k = 1; k < a.hits().size(); k++) { var p = a.hits().get(k - 1); var h = a.hits().get(k); assertTrue(p.z() < h.z() || (p.z() == h.z() && (p.y() < h.y() || (p.y() == h.y() && p.x() <= h.x()))), "seed=" + SEED + " i=" + i + " order"); }
            for (var h : a.hits()) assertTrue(CollisionEngine.intersects(h.shape(), h.originX(), h.originY(), h.originZ(), q), "seed=" + SEED + " i=" + i + " every hit really intersects the query");
        }
    }
    @Test void historicalQueriesNeverSeeTheFutureAndWorldEpochMismatchNeverSucceeds() {
        var r = new Random(SEED); var s = new Scene(); s.baseline(0, 0);
        var earliest = new HashMap<Long, Long>(); var cells = new ArrayList<long[]>(); var sequences = new ArrayList<Long>();
        for (int i = 0; i < 120; i++) { int x = r.nextInt(2, 14), y = r.nextInt(70, 74), z = r.nextInt(2, 14); long q = s.set(x, y, z, STONE); long key = (x << 20) | (y << 10) | z; earliest.putIfAbsent(key, q); cells.add(new long[] { key, x, y, z }); sequences.add(q); if (r.nextInt(3) == 0) sequences.add(s.mark()); }
        for (long q : sequences) for (int t = 0; t < 30; t++) {
            var p = cells.get(r.nextInt(cells.size())); var box = new Aabb(p[1] + 0.2, p[2] + 0.2, p[3] + 0.2, p[1] + 0.8, p[2] + 0.8, p[3] + 0.8);
            var res = s.queryAt(q, box);
            if (res.certainty() == Certainty.UNAVAILABLE) { assertEquals(CellStatus.HISTORY_NOT_RETAINED, res.unavailableReason(), "seed=" + SEED); continue; }
            assertEquals(q, res.sequence()); assertEquals(Certainty.COMPLETE, res.certainty(), "seed=" + SEED + " seq=" + q);
            boolean expectedVisible = earliest.get(p[0]) <= q;
            assertEquals(expectedVisible, anyHitAt(res, p), "seed=" + SEED + " seq=" + q + " cell first placed at " + earliest.get(p[0]) + ": a historical query never reads a later revision");
            assertEquals(Certainty.UNAVAILABLE, s.engine.query(s.world.viewAtSequence(q), box, ShapeContext.UNKNOWN, s.world.worldEpoch() + 1 + r.nextInt(3)).certainty(), "seed=" + SEED + " epoch mismatch");
        }
    }
    static boolean anyHitAt(CollisionResult r, long[] p) { for (var h : r.hits()) if (h.x() == p[1] && h.y() == p[2] && h.z() == p[3]) return true; return false; }
    @Test void oversizedQueriesAreAlwaysRejectedAndNeverIterateWhileInBoundQueriesNeverExceedTheCellBudget() {
        var r = new Random(SEED); var s = new Scene(); s.baseline(0, 0);
        for (int i = 0; i < 2000; i++) {
            double x0 = r.nextDouble(-50, 50), y0 = r.nextDouble(-70, 330), z0 = r.nextDouble(-50, 50);
            var q = new Aabb(x0, y0, z0, x0 + r.nextDouble(0, 40), y0 + r.nextDouble(0, 40), z0 + r.nextDouble(0, 40));
            var res = s.query(q); long cells = expectedCells(q) + 8;
            if (cells > 4096) { assertEquals(Certainty.REJECTED, res.certainty(), "seed=" + SEED + " i=" + i + " cells=" + cells); assertEquals(0, res.cellsVisited()); assertEquals(0, res.hitCount()); }
            else { assertNotEquals(Certainty.REJECTED, res.certainty(), "seed=" + SEED + " i=" + i + " cells=" + cells); assertTrue(res.cellsVisited() <= 4096); assertEquals(cells - 8, res.cellsVisited()); }
        }
    }
    @Test void tableClassificationInvariantsHoldForEveryBlock() {
        var t = PROFILE.table(); int dynamic = 0;
        for (var b : t.blocks()) {
            for (int st = 0; st < b.stateCount(); st++) assertTrue(b.shapeIndex(st) >= 0 && b.shapeIndex(st) < t.shapeCount(), b.name());
            if (b.kind() == BlockShapeTable.Kind.CONTEXT) for (int st = 0; st < b.stateCount(); st++) for (int v = 0; v < 6; v++) assertTrue(b.contextVariant(st, v) >= 0 && b.contextVariant(st, v) < t.shapeCount());
            if (b.kind() == BlockShapeTable.Kind.OFFSET_XZ) assertTrue(b.maxHorizontalOffset() > 0 && b.maxHorizontalOffset() <= 0.25f);
            if (b.kind() != BlockShapeTable.Kind.STATIC) dynamic++;
        }
        assertEquals(23, dynamic, "3 offset + 1 context + 1 entity-context + 18 block-entity blocks");
    }
}
