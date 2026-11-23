package me.nicolas.papea2ac.testing;

import java.util.*;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TimelineLookup;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;

/** Builders for Phase 11 tests: an isolated observer entity history (standalone byte tier) driven by explicit, strictly ordered sequences. */
final class CombatTestSupport {
    private CombatTestSupport() {}
    static final SessionKey KEY = new SessionKey(new UUID(0x11L, 1), 1), OTHER = new SessionKey(new UUID(0x11L, 2), 2);
    static final CombatProfile P = CombatProfile.PAPER_26_2;
    static final long TICK = 50_000_000L;
    static final int TIER = 2 << 20;

    /** A history plus a strictly increasing sequence/nanos cursor. */
    static final class Rig {
        final EntityHistory h; final SessionKey key; long seq = 0, nanos = 1_000_000_000L;
        final CombatEngine engine; final CombatEngine.Metrics metrics = new CombatEngine.Metrics();
        Rig() { this(KEY, TIER); }
        Rig(SessionKey key, int tier) { this.key = key; h = new EntityHistory(key, P, null, tier); engine = new CombatEngine(key, P, metrics); }
        long next() { seq++; nanos += TICK; return seq; }
        long apply(EntityObservation o) { long s = next(); if (!h.apply(key, o, s, 0, nanos)) throw new AssertionError("rejected " + o); return s; }
        long silent() { long s = next(); h.observeSequence(key, s, nanos); return s; }
        long spawn(int id, double x, double y, double z) { return apply(new EntityObservation.Spawned(id, new UUID(id, id), id >= 1000 ? "minecraft:player" : "minecraft:zombie", x, y, z, 10f, 5f, 10f)); }
        long move(int id, int dx, int dy, int dz) { return apply(new EntityObservation.Moved(id, (short) dx, (short) dy, (short) dz, true, 0f, 0f, false)); }
        long moveRot(int id, int dx, int dy, int dz, float yaw, float pitch) { return apply(new EntityObservation.Moved(id, (short) dx, (short) dy, (short) dz, true, yaw, pitch, true)); }
        long rot(int id, float yaw, float pitch) { return apply(new EntityObservation.Moved(id, (short) 0, (short) 0, (short) 0, false, yaw, pitch, true)); }
        long sync(int id, double x, double y, double z) { return apply(new EntityObservation.PositionSynced(id, x, y, z, 0f, 0f)); }
        long teleport(int id, double x, double y, double z, int mask) { return apply(new EntityObservation.Teleported(id, x, y, z, 0f, 0f, mask)); }
        long remove(int... ids) { List<Integer> l = new ArrayList<>(); for (int i : ids) l.add(i); return apply(new EntityObservation.Removed(l)); }
        long pose(int id, String pose) { return apply(new EntityObservation.DataUpdated(id, pose, false, -1)); }
        long gap() { seq++; return seq; } // a lost observation: the next apply sees a sequence gap
        long breakCoverage(String why) { return apply(new EntityObservation.CoverageBreak(why)); }
        /** The epoch-changing observation is itself observed (core: enterEpoch then apply/observe at the same sequence). */
        void epoch(long e) { long s = next(); h.enterEpoch(e, s); h.observeSequence(key, s, nanos); }
        EntityLookup at(int id, long seq) { return h.lookupId(key, h.worldEpoch(), id, seq); }
        EntityLookup now(int id) { return at(id, seq + 1); }
        AttackContext attack(int target) { long s = next(); h.observeSequence(key, s, nanos); return engine.onAttack(new CombatObservation.Attack(s, s, s, target, nanos), h, new TimelineLookup.NeverExisted(s), CombatProvenance.MovementValidation.UNAVAILABLE, null); }
    }
    /** VecDeltaCodec.decode for one axis, written independently of CombatProfile (Math.round(base * 4096), long add, divide). */
    static double refDecode(double base, long delta) { if (delta == 0) return base; long enc = Math.round(base * 4096.0); return (enc + delta) / 4096.0; }
}
