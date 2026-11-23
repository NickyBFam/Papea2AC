package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.CombatTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 properties against an INDEPENDENT, UNBOUNDED reference model (full history, no eviction, its own delta decode): whatever the bounded
 * EntityHistory answers FOUND must equal the reference exactly; it may know less, never more and never differently. Plus the brief's section-51
 * properties: determinism, unknown never exact, gaps never improve, session/epoch invalidation, id reuse, pre-spawn, eviction, interaction vs attack,
 * and bounds.
 */
class CombatPropertyTest {
    static final int SEEDS = 60, OPS = 700;

    /** The reference: every incarnation with its complete revision list (seq, x, y, z, exact). */
    static final class Ref {
        record Rev(long seq, double x, double y, double z, double bx, double by, double bz, boolean exact) {}
        static final class Inc { final int id; final long inc, spawn; long end = -1; final List<Rev> revs = new ArrayList<>(); Inc(int id, long inc, long spawn) { this.id = id; this.inc = inc; this.spawn = spawn; } }
        final Map<Integer, Inc> live = new HashMap<>(); final List<Inc> all = new ArrayList<>(); long nextInc = 1, gapAt = -1, epoch = 0, epochStart = 1;
        void spawn(int id, long seq, double x, double y, double z) { Inc p = live.remove(id); if (p != null) p.end = seq; Inc i = new Inc(id, nextInc++, seq); i.revs.add(new Rev(seq, x, y, z, x, y, z, true)); live.put(id, i); all.add(i); }
        void move(int id, long seq, int dx, int dy, int dz) {
            Inc i = live.get(id); if (i == null) return; Rev r = i.revs.get(i.revs.size() - 1);
            double bx = r.bx(), by = r.by(), bz = r.bz(); if (dx != 0 || dy != 0 || dz != 0) { bx = refDecode(bx, dx); by = refDecode(by, dy); bz = refDecode(bz, dz); }
            i.revs.add(new Rev(seq, bx, by, bz, bx, by, bz, true));
        }
        void sync(int id, long seq, double x, double y, double z) { Inc i = live.get(id); if (i != null) i.revs.add(new Rev(seq, x, y, z, x, y, z, true)); }
        void teleport(int id, long seq, double x, double y, double z, boolean relX) { Inc i = live.get(id); if (i == null) return; Rev r = i.revs.get(i.revs.size() - 1); i.revs.add(new Rev(seq, relX ? r.x() + x : x, y, z, r.bx(), r.by(), r.bz(), !relX)); }
        void remove(int id, long seq) { Inc i = live.remove(id); if (i != null) i.end = seq; }
        void epoch(long e, long seq) { for (Inc i : live.values()) i.end = seq; live.clear(); all.clear(); epoch = e; epochStart = seq; gapAt = -1; }
        /** The reference answer: the revision in effect before seq for the id, or null (not alive / not provable). */
        Rev at(int id, long seq) {
            for (Inc i : all) if (i.id == id && i.spawn < seq && (i.end < 0 || seq <= i.end)) {
                if (gapAt >= 0 && gapAt < seq && i.spawn <= gapAt) return null; // an incarnation that lived through a lost observation is unprovable after it
                Rev best = null; for (Rev r : i.revs) if (r.seq() < seq) best = r; return best;
            }
            return null;
        }
    }
    /** One generated stream applied to both models; returns the rig. */
    static Rig run(long seed, Ref ref, boolean withGaps) {
        SplittableRandom rnd = new SplittableRandom(seed); Rig r = new Rig();
        for (int k = 0; k < OPS; k++) {
            int id = rnd.nextInt(12); int op = rnd.nextInt(100);
            if (op < 12) { double x = rnd.nextInt(-3000, 3000) + rnd.nextDouble(), y = 60 + rnd.nextDouble() * 10, z = rnd.nextDouble() * 50; long s = r.spawn(id, x, y, z); ref.spawn(id, s, x, y, z); }
            else if (op < 55) { int dx = rnd.nextInt(-40000, 40000), dy = rnd.nextInt(-200, 200), dz = rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(-9000, 9000); dx = Math.max(-32768, Math.min(32767, dx)); long s = r.move(id, dx, dy, dz); ref.move(id, s, dx, dy, dz); }
            else if (op < 62) { double x = rnd.nextInt(-500, 500) + 0.5, y = 64, z = 0.25; long s = r.sync(id, x, y, z); ref.sync(id, s, x, y, z); }
            else if (op < 69) { boolean rel = rnd.nextBoolean(); double x = rnd.nextInt(-50, 50), y = 70, z = 1; long s = r.teleport(id, x, y, z, rel ? EntityObservation.Teleported.REL_X : 0); ref.teleport(id, s, x, y, z, rel); }
            else if (op < 76) { long s = r.remove(id); ref.remove(id, s); }
            else if (op < 77) { long e = r.h.worldEpoch() + 1; r.epoch(e); ref.epoch(e, r.seq); }
            else if (op < 78 && withGaps && rnd.nextInt(4) == 0) { r.gap(); r.silent(); if (ref.gapAt < 0) ref.gapAt = r.seq; }
            else if (op < 90) r.rot(id, rnd.nextFloat() * 360, rnd.nextFloat() * 90 - 45);
            else r.silent();
        }
        return r;
    }
    @Test void theBoundedHistoryNeverKnowsMoreOrDifferentlyThanTheReference() {
        long found = 0, checked = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Ref ref = new Ref(); Rig r = run(seed, ref, true);
            SplittableRandom q = new SplittableRandom(seed * 31);
            for (int k = 0; k < 400; k++) {
                int id = q.nextInt(12); long seq = q.nextInt(4) == 0 ? 1 + q.nextLong(r.seq + 1) : Math.max(1, r.seq + 1 - q.nextLong(50)); // mostly within the 3 s horizon, some anywhere
                EntityLookup l = r.at(id, seq); Ref.Rev truth = seq > ref.epochStart ? ref.at(id, seq) : null; checked++;
                if (l.status() == Status.FOUND) {
                    found++;
                    assertNotNull(truth, "seed " + seed + " id " + id + " seq " + seq + ": bounded FOUND where the reference has nothing: " + l.describe());
                    assertEquals(id, l.key().networkId()); assertEquals(KEY, l.key().session()); assertEquals(r.h.worldEpoch(), l.key().worldEpoch());
                    if (l.revision().positionStatus() == EntityRevision.Status.EXACT) {
                        assertTrue(truth.exact(), "seed " + seed + ": EXACT where the reference is interpolation-dependent");
                        assertEquals(Double.doubleToRawLongBits(truth.x()), Double.doubleToRawLongBits(l.revision().x()), "seed " + seed + " seq " + seq);
                        assertEquals(Double.doubleToRawLongBits(truth.z()), Double.doubleToRawLongBits(l.revision().z()));
                    }
                }
            }
        }
        assertTrue(found > checked / 10, "the property exercised real FOUND answers: " + found + "/" + checked);
    }
    @Test void sameInputsGiveTheSameProjection() {
        for (long seed = 1; seed <= 20; seed++) assertEquals(EntityReplayMapper.digest(EntityReplayMapper.export(run(seed, new Ref(), true).h)), EntityReplayMapper.digest(EntityReplayMapper.export(run(seed, new Ref(), true).h)));
    }
    @Test void unknownEvictedPreSpawnAndGapsNeverResolveAsFound() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            Rig r = run(seed, new Ref(), true);
            for (int id = 0; id < 12; id++) for (long seq = 1; seq <= r.seq + 1; seq += 7) {
                var l = r.at(id, seq);
                if (l.status() != Status.FOUND) assertNull(l.revision(), "a non-FOUND answer never carries a position");
                if (r.h.status().coverageBreakSequence() >= 0 && seq > r.h.status().coverageBreakSequence() && l.found()) assertTrue(l.key().incarnation() > 0 && l.spawnSequence() > r.h.status().coverageBreakSequence(), "after a gap only post-gap spawns are exact");
            }
        }
    }
    @Test void boundsAreNeverExceeded() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            Rig r = run(seed, new Ref(), true); var st = r.h.status();
            assertTrue(st.live() + st.ended() <= EntityHistory.MAX_RETAINED); assertTrue(st.deep() <= EntityHistory.MAX_DEEP); assertTrue(st.bytes() >= 0 && st.bytes() <= TIER);
            for (var e : r.h.liveEntities()) assertTrue(r.h.revisions(e.key(), 0, r.seq + 1, EntityHistory.MAX_RANGE_REVISIONS).revisions().size() <= EntityHistory.MAX_REVISIONS);
        }
    }
    @Test void sessionAndEpochChangesInvalidateEveryOldIdentity() {
        Rig r = run(7, new Ref(), false); for (int i = 0; i < 5; i++) r.spawn(900 + i, i, 64, i); List<EntityKey> keys = new ArrayList<>(); for (var e : r.h.liveEntities()) keys.add(e.key());
        assertFalse(keys.isEmpty());
        long e = r.h.worldEpoch() + 1; r.epoch(e);
        for (var k : keys) assertEquals(Status.WORLD_EPOCH_MISMATCH, r.h.lookupKey(k, r.seq + 1).status());
        for (var k : keys) assertEquals(Status.SESSION_MISMATCH, r.h.lookupKey(new EntityKey(OTHER, e, k.networkId(), k.incarnation()), r.seq + 1).status());
    }
    @Test void reusedIdsAreDistinctGenerationsAndPreSpawnIsNeverExact() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            Rig r = run(seed, new Ref(), false); Set<Long> incarnations = new HashSet<>();
            for (var e : r.h.liveEntities()) assertTrue(incarnations.add(e.key().incarnation()), "one incarnation per live entity");
            for (var e : r.h.liveEntities()) assertNotEquals(Status.FOUND, r.h.lookupKey(e.key(), e.spawnSequence()).status(), "at its own spawn sequence an incarnation is PRE_SPAWN");
        }
    }
    @Test void anAttackObservationNeverImpliesAcceptanceAndAnInteractionNeverBecomesAnAttack() {
        Rig r = run(3, new Ref(), false);
        for (int i = 0; i < 12; i++) { var c = r.attack(i); assertTrue(c.completeness().standing().contains(CombatCompleteness.Standing.SERVER_OUTCOME_UNSUPPORTED)); }
        long attacks = r.engine.status().attacks();
        long s = r.next(); r.h.observeSequence(KEY, s, r.nanos); r.engine.onInteraction(new CombatObservation.Interaction(s, s, s, 1, r.nanos, "OFF_HAND", 0, 0, 0, false), r.h);
        assertEquals(attacks, r.engine.status().attacks());
    }
}
