package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.CombatTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.player.TimelineLookup;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 metamorphic relations (brief section 52) over ONE fixed generated observation list and its variants, compared point by point at identical
 * sequences: deleting a relevant packet or inserting a coverage gap can only keep or lower certainty (and never change an exact value); changing
 * session / epoch invalidates identity; remove + respawn never keeps the old generation; moving an update across an attack changes what the attack
 * sees; duplicates add neither certainty nor generations.
 */
class CombatMetamorphicTest {
    /** One generated observation (or a silent one); {@code seq} is fixed so variants stay aligned. */
    record Op(EntityObservation obs) {}

    static List<Op> generate(long seed) {
        SplittableRandom r = new SplittableRandom(seed); List<Op> ops = new ArrayList<>();
        for (int k = 0; k < 400; k++) {
            int id = r.nextInt(8), op = r.nextInt(100);
            if (op < 12) ops.add(new Op(new EntityObservation.Spawned(id, new UUID(id, k), "minecraft:zombie", r.nextInt(-100, 100) + r.nextDouble(), 64, r.nextDouble() * 9, 0f, 0f, 0f)));
            else if (op < 60) ops.add(new Op(new EntityObservation.Moved(id, (short) r.nextInt(-9000, 9000), (short) r.nextInt(-50, 50), (short) r.nextInt(-9000, 9000), true, 0f, 0f, false)));
            else if (op < 66) ops.add(new Op(new EntityObservation.PositionSynced(id, r.nextInt(-50, 50) + 0.5, 64, 0.5, 0f, 0f)));
            else if (op < 72) ops.add(new Op(new EntityObservation.Teleported(id, r.nextInt(-50, 50), 64, 3, 0f, 0f, r.nextInt(3) == 0 ? EntityObservation.Teleported.REL_X : 0)));
            else if (op < 78) ops.add(new Op(new EntityObservation.Removed(List.of(id))));
            else ops.add(new Op(null)); // a silent observation
        }
        return ops;
    }
    /** Applies the list; {@code lost} indices consume their sequence without being observed (a transport loss); {@code breakAt} indices become coverage breaks. */
    static Rig apply(List<Op> ops, Set<Integer> lost, Set<Integer> breakAt) {
        Rig r = new Rig();
        for (int i = 0; i < ops.size(); i++) {
            Op o = ops.get(i);
            if (lost.contains(i)) { r.gap(); continue; }
            if (breakAt.contains(i)) { r.breakCoverage("INSERTED_GAP"); continue; }
            if (o.obs() == null) r.silent(); else r.apply(o.obs());
        }
        r.silent(); // observe one more so a trailing loss is detected
        return r;
    }
    static int rank(EntityLookup l) {
        if (l.status() == Status.FOUND) return l.revision().positionStatus() == EntityRevision.Status.EXACT ? 3 : 2;
        return 0;
    }
    /** The unbounded truth of the ORIGINAL stream (the property test's independent reference), at the same sequences (op i has sequence i + 1). */
    static CombatPropertyTest.Ref truth(List<Op> ops) {
        var ref = new CombatPropertyTest.Ref();
        for (int i = 0; i < ops.size(); i++) {
            long seq = i + 1; var o = ops.get(i).obs();
            switch (o) {
                case null -> { }
                case EntityObservation.Spawned sp -> ref.spawn(sp.entityId(), seq, sp.x(), sp.y(), sp.z());
                case EntityObservation.Moved m -> { if (m.hasPosition()) ref.move(m.entityId(), seq, m.dx(), m.dy(), m.dz()); }
                case EntityObservation.PositionSynced ps -> ref.sync(ps.entityId(), seq, ps.x(), ps.y(), ps.z());
                case EntityObservation.Teleported t -> ref.teleport(t.entityId(), seq, t.x(), t.y(), t.z(), (t.relativeMask() & EntityObservation.Teleported.REL_X) != 0);
                case EntityObservation.Removed rm -> { for (int id : rm.ids()) ref.remove(id, seq); }
                default -> throw new AssertionError(o);
            }
        }
        return ref;
    }
    /**
     * Soundness under deletion/insertion: every exact answer of the variant equals the true state of the original stream, and the variant is never more
     * certain than the original except where the original answered EVICTED (bounded retention can legitimately differ when a variant lost a later update).
     */
    static void neverMoreCertain(Rig original, Rig variant, CombatPropertyTest.Ref ref, String what) {
        for (int id = 0; id < 8; id++) for (long s = 1; s <= original.seq + 1; s++) {
            var a = original.at(id, s); var b = variant.at(id, s);
            if (a.status() != Status.EVICTED) assertTrue(rank(b) <= rank(a), what + ": id " + id + " seq " + s + " variant " + b.describe() + " vs original " + a.describe());
            if (rank(b) == 3) {
                var t = ref.at(id, s);
                assertNotNull(t, what + ": variant exact where the original truth has no state: id " + id + " seq " + s + " " + b.describe());
                assertEquals(Double.doubleToRawLongBits(t.x()), Double.doubleToRawLongBits(b.revision().x()), what + ": an exact value differs from the truth at id " + id + " seq " + s);
            }
        }
    }
    @Test void deletingARelevantPacketCanOnlyPreserveOrReduceCertainty() {
        for (long seed = 1; seed <= 25; seed++) {
            List<Op> ops = generate(seed); Rig original = apply(ops, Set.of(), Set.of());
            var ref = truth(ops); SplittableRandom r = new SplittableRandom(seed * 7);
            for (int t = 0; t < 4; t++) { int idx = r.nextInt(ops.size()); neverMoreCertain(original, apply(ops, Set.of(idx), Set.of()), ref, "seed " + seed + " delete op " + idx); }
        }
    }
    @Test void insertingACoverageGapCannotMakeALookupMoreExact() {
        for (long seed = 1; seed <= 25; seed++) {
            List<Op> ops = generate(seed); Rig original = apply(ops, Set.of(), Set.of());
            var ref = truth(ops);
            for (int idx = 0; idx < ops.size(); idx++) if (ops.get(idx).obs() == null) { neverMoreCertain(original, apply(ops, Set.of(), Set.of(idx)), ref, "seed " + seed + " gap at " + idx); break; }
        }
    }
    @Test void changingSessionOrEpochInvalidatesPriorIdentity() {
        Rig r = apply(generate(5), Set.of(), Set.of()); for (int i = 0; i < 4; i++) r.spawn(50 + i, i, 64, i);
        List<EntityKey> keys = new ArrayList<>(); for (var e : r.h.liveEntities()) keys.add(e.key());
        for (var k : keys) { assertEquals(Status.FOUND, r.h.lookupKey(k, r.seq + 1).status()); assertEquals(Status.SESSION_MISMATCH, r.h.lookupKey(new EntityKey(OTHER, k.worldEpoch(), k.networkId(), k.incarnation()), r.seq + 1).status()); }
        r.epoch(r.h.worldEpoch() + 1);
        for (var k : keys) assertEquals(Status.WORLD_EPOCH_MISMATCH, r.h.lookupKey(k, r.seq + 1).status());
    }
    @Test void removeAndRespawnOfTheSameIdCannotPreserveTheOldGeneration() {
        Rig r = new Rig(); r.spawn(3, 0, 64, 0); var a = r.now(3).key(); r.remove(3); r.spawn(3, 0, 64, 0); var b = r.now(3).key();
        assertNotEquals(a.incarnation(), b.incarnation()); assertEquals(Status.ID_REUSED, r.h.lookupKey(a, r.seq + 1).status());
        assertEquals(r.now(3).revision().x(), 0.0); assertEquals(b, r.now(3).key(), "the id resolves only to the new generation");
    }
    @Test void shiftingAnUpdateAcrossAnAttackChangesWhatTheAttackSees() {
        Rig before = new Rig(); before.spawn(1, 0, 64, 0); before.move(1, 4096, 0, 0); var c1 = before.attack(1);
        Rig after = new Rig(); after.spawn(1, 0, 64, 0); var c2 = after.attack(1); after.move(1, 4096, 0, 0);
        assertEquals(1.0, c1.target().revision().x(), "update before the attack is in its context"); assertEquals(0.0, c2.target().revision().x(), "update after the attack is not");
        assertNotEquals(c1.target().revision().sequence(), c2.target().revision().sequence());
    }
    @Test void duplicateObservationsAddNeitherCertaintyNorGenerations() {
        List<Op> ops = generate(9); Rig once = apply(ops, Set.of(), Set.of());
        Rig twice = new Rig();
        for (Op o : ops) {
            if (o.obs() == null) { twice.silent(); continue; }
            long s = twice.apply(o.obs());
            assertFalse(twice.h.apply(KEY, o.obs(), s, 0, twice.nanos), "a re-delivered observation is rejected");
        }
        twice.silent();
        assertEquals(once.h.status().generations(), twice.h.status().generations());
        assertEquals(EntityReplayMapper.digest(EntityReplayMapper.export(once.h)), EntityReplayMapper.digest(EntityReplayMapper.export(twice.h)));
        long attacks = 0; Rig dup = new Rig(); dup.spawn(1, 0, 64, 0); var c = dup.attack(1);
        assertNull(dup.engine.onAttack(c.observation(), dup.h, new TimelineLookup.NeverExisted(c.observation().sequence()), null, null), "a duplicate attack observation adds nothing");
        assertEquals(1, dup.engine.status().attacks() - attacks);
    }
}
