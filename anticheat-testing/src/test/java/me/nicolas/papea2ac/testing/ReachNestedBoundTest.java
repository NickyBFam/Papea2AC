package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.combat.reach.FrontierTracker;
import me.nicolas.papea2ac.combat.reach.TargetHull;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.version.OutlineShapeTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 backtrack refinement (every answered marker at once) and the outline table:
 * <ul>
 * <li>the prefix-weight linear program is solved exactly (brute force over every feasible vertex on small instances; never below a random
 *     feasible point);</li>
 * <li>the nested box is never wider than the single-newest-frontier box, and it narrows when older proofs exist;</li>
 * <li>the outline table resolves known states (full cube, bottom slab, offset plants) and refuses unknown ones.</li>
 * </ul>
 * (The nested box's soundness against an exact client simulation is in {@code ReachValidatorTest.theInterpolationHullContainsEveryClientPosition}.)
 */
class ReachNestedBoundTest {
    @Test void thePrefixWeightProgramIsSolvedExactly() {
        Random r = new Random(0x12_3001L);
        for (int c = 0; c < 20_000; c++) {
            int n = 1 + r.nextInt(6); double[] d = new double[n], caps = new double[n];
            for (int i = 0; i < n; i++) { d[i] = (r.nextDouble() - 0.5) * 4; caps[i] = r.nextInt(4) == 0 ? 1.0 : r.nextDouble(); }
            double lp = TargetHull.maxPrefixWeights(d, caps);
            // brute force: P_t takes the previous value or its effective cap (suffix minimum), monotone; enumerate all 2^n paths
            double[] eff = new double[n]; double m = 1.0; for (int t = n - 1; t >= 0; t--) { m = Math.min(m, caps[t]); eff[t] = m; }
            double best = 0.0;
            for (int mask = 0; mask < (1 << n); mask++) {
                double p = 0.0, v = 0.0; boolean ok = true;
                for (int t = 0; t < n; t++) { if ((mask & (1 << t)) != 0) { if (eff[t] < p) { ok = false; break; } p = eff[t]; } v += d[t] * p; }
                if (ok) best = Math.max(best, v);
            }
            assertEquals(best, lp, 1e-12, () -> "LP " + Arrays.toString(d) + " caps " + Arrays.toString(caps));
            for (int k = 0; k < 20; k++) { // random feasible points never exceed it
                double p = 0.0, v = 0.0; for (int t = 0; t < n; t++) { p = Math.min(eff[t], p + r.nextDouble() * (eff[t] - p)); v += d[t] * p; }
                assertTrue(v <= lp + 1e-12, "feasible point above the optimum");
            }
        }
    }

    @Test void theNestedBoxIsNeverWiderAndNarrowsWithOlderProofs() {
        Random r = new Random(0x12_3002L); int narrower = 0;
        for (int c = 0; c < 5_000; c++) {
            int n = 10 + r.nextInt(40); List<double[]> h = new ArrayList<>(); double x = 0, y = 64, z = 0;
            for (int i = 0; i < n; i++) { x += (r.nextDouble() - 0.3) * 0.3; z += (r.nextDouble() - 0.5) * 0.3; h.add(new double[]{x, y, z}); }
            PickMath.Box life = ReachValidatorTest.box(h);
            int newest = n - 1 - r.nextInt(3);
            PickMath.Box single = TargetHull.positions(h.subList(newest, n), life, 3, 1);
            List<TargetHull.Bound> bounds = new ArrayList<>(); for (int i = 1; i <= newest; i++) bounds.add(new TargetHull.Bound(i, Math.max(1, newest - i + 1)));
            PickMath.Box nested = TargetHull.nested(h, life, 3, bounds);
            double eps = 1e-9;
            assertTrue(nested.minX() >= single.minX() - eps && nested.maxX() <= single.maxX() + eps && nested.minZ() >= single.minZ() - eps && nested.maxZ() <= single.maxZ() + eps,
                () -> "nested " + nested + " wider than single " + single);
            if (nested.maxX() - nested.minX() < single.maxX() - single.minX() - 1e-6) narrower++;
        }
        assertTrue(narrower > 2_500, "older proofs narrow the box in most cases: " + narrower);
    }

    @Test void theFrontierIndexAnswersExactlyAsTheSliceScans() {
        // perf-to-phase12-01: ReachRunner builds one index per ring state instead of copying and rescanning the ring per attack; results must not change
        Random r = new Random(0x12_3003L);
        for (int c = 0; c < 2_000; c++) {
            List<TimingEvent> ev = new ArrayList<>(); long seq = 0; int n = 20 + r.nextInt(300);
            for (int i = 0; i < n; i++) {
                seq += 1 + r.nextInt(3); int id = r.nextInt(6); int k = r.nextInt(10);
                if (k < 2) ev.add(new TimingEvent.MarkerSent(seq, seq, r.nextInt(5) == 0 ? TimingEvent.MarkerKind.KEEPALIVE : TimingEvent.MarkerKind.PING, id, i + 1));
                else if (k < 4) ev.add(new TimingEvent.MarkerAcknowledged(seq, seq, r.nextInt(5) == 0 ? TimingEvent.MarkerKind.KEEPALIVE : TimingEvent.MarkerKind.PING, id));
                else ev.add(new TimingEvent.ClientTickEnd(seq, seq));
            }
            ApplicationFrontier.Index index = ApplicationFrontier.index(ev);
            for (int q = 0; q < 20; q++) {
                long attack = 1 + (long) (r.nextDouble() * (seq + 2)), from = r.nextInt(3) == 0 ? 0 : (long) (r.nextDouble() * attack), pos = (long) (r.nextDouble() * attack) - 1;
                List<TimingEvent> slice = new ArrayList<>(); for (TimingEvent e : ev) if (e.sequence() >= from && e.sequence() <= attack) slice.add(e);
                assertEquals(ApplicationFrontier.all(slice, attack), index.all(from, attack), "all");
                assertEquals(ApplicationFrontier.of(slice, attack), index.newest(from, attack), "of");
                int ticks = 0; for (TimingEvent e : slice) if (e instanceof TimingEvent.ClientTickEnd && e.sequence() > pos && e.sequence() < attack) ticks++;
                assertEquals(ticks, index.tickEndsBetween(from, pos, attack), "tick ends");
            }
        }
    }

    @Test void theIncrementalTrackerAnswersAsTheRingCopyThroughEvictions() {
        // the runner's mirror of the timing ring (perf-to-phase12-01): fed what the ledger accepted, queried with oldest = evictions, against the
        // index of a copy of the ring; streams long enough to evict by count (2048) and by the 3 s horizon
        Random r = new Random(0x12_3004L); long queries = 0;
        for (int c = 0; c < 40; c++) {
            SessionKey key = new SessionKey(new UUID(0x12EL, c), 1);
            TimingLedger ledger = new TimingLedger(key); FrontierTracker tracker = new FrontierTracker(); long mirrored = 0;
            long seq = 0, nanos = 1_000_000_000L, wire = 0; int n = 1_000 + r.nextInt(5_000);
            for (int i = 0; i < n; i++) {
                seq += 1 + r.nextInt(2); nanos += r.nextInt(20) == 0 ? 400_000_000L + r.nextInt(900_000_000) : r.nextInt(3_000_000);
                int id = r.nextInt(12), k = r.nextInt(12); TimingEvent e;
                if (k < 2) e = new TimingEvent.MarkerSent(seq, nanos, r.nextInt(6) == 0 ? TimingEvent.MarkerKind.KEEPALIVE : TimingEvent.MarkerKind.PING, id, ++wire);
                else if (k < 4) e = new TimingEvent.MarkerAcknowledged(seq, nanos, r.nextInt(6) == 0 ? TimingEvent.MarkerKind.KEEPALIVE : TimingEvent.MarkerKind.PING, id);
                else if (k < 8) e = new TimingEvent.ClientTickEnd(seq, nanos);
                else e = new TimingEvent.ServerTick(seq, nanos, i);
                if (r.nextInt(50) == 0) ledger.expire(nanos);
                ledger.apply(key, e);
                TimingLedger.Counters cnt = ledger.view().counters();
                if (cnt.events() == mirrored + 1) { tracker.accepted(e, mirrored); mirrored++; tracker.trim(cnt.evictions()); }
                assertEquals(mirrored, cnt.events());
                if (r.nextInt(25) == 0) {
                    List<TimingEvent> ring = ledger.events(); assertEquals(cnt.events() - ring.size(), cnt.evictions(), "ring = accepted events from the eviction count");
                    ApplicationFrontier.Index index = ApplicationFrontier.index(ring);
                    long attack = seq + 1 - r.nextInt(40), from = r.nextInt(3) == 0 ? 0 : Math.max(0, seq - r.nextInt(3_000)), pos = attack - 1 - r.nextInt(60);
                    assertEquals(index.all(from, attack), tracker.all(from, attack, cnt.evictions()), "all");
                    assertEquals(index.newest(from, attack), tracker.newest(from, attack, cnt.evictions()), "newest");
                    assertEquals(index.tickEndsBetween(from, pos, attack), tracker.tickEndsBetween(from, pos, attack, cnt.evictions()), "tick ends");
                    queries++;
                }
            }
        }
        assertTrue(queries > 5_000, "queries " + queries);
    }

    @Test void theOutlineTableResolvesKnownStatesAndRefusesUnknownOnes() {
        OutlineShapeTable t = OutlineShapeTable.paper26_2();
        assertTrue(t.blockCount() > 1000, "every block of the pinned registry");
        var stone = t.lookup(BlockStateKey.of("minecraft:stone"));
        assertEquals(OutlineShapeTable.Kind.STATIC, stone.kind()); assertArrayEquals(new double[]{0, 0, 0, 1, 1, 1}, stone.boxes(), 0.0);
        var air = t.lookup(BlockStateKey.of("minecraft:air")); assertTrue(air.empty(), "air has no outline");
        var slab = t.lookup(new BlockStateKey("minecraft:oak_slab", new TreeMap<>(Map.of("type", "bottom", "waterlogged", "false"))));
        assertEquals(OutlineShapeTable.Kind.STATIC, slab.kind()); assertArrayEquals(new double[]{0, 0, 0, 1, 0.5, 1}, slab.boxes(), 0.0);
        var grass = t.lookup(BlockStateKey.of("minecraft:short_grass"));
        assertEquals(OutlineShapeTable.Kind.OFFSET, grass.kind(), "short grass has an outline that moves with the per-position offset"); assertFalse(grass.empty());
        assertNull(t.lookup(BlockStateKey.of("minecraft:not_a_block")));
        assertNull(t.lookup(new BlockStateKey("minecraft:oak_slab", new TreeMap<>(Map.of("type", "sideways", "waterlogged", "false")))), "unknown value");
    }
}
