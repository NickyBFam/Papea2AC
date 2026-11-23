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
 * Phase 11 fuzz (brief section 53): random spawn / relative move / rotation / teleport (absolute and relative) / sync / metadata / passengers /
 * removal / attack / interaction / epoch change / coverage loss streams, including hostile values (extreme deltas, large ids, 300 distinct ids against
 * the 256 capacity). After every step: no exception, no cross-generation or cross-session leak, no impossible exact lookup, bounds held; per seed the
 * whole run is deterministic.
 */
class CombatFuzzTest {
    static final int SEEDS = 40, STEPS = 1500;

    static String run(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed); Rig r = new Rig();
        for (int step = 0; step < STEPS; step++) {
            int id = rnd.nextInt(8) == 0 ? rnd.nextInt(300) : rnd.nextInt(10), op = rnd.nextInt(100);
            switch (op / 7) {
                case 0 -> r.spawn(id, rnd.nextDouble(-3e7, 3e7), rnd.nextDouble(-64, 320), rnd.nextDouble(-3e7, 3e7));
                case 1, 2 -> r.move(id, rnd.nextInt(-32768, 32768), rnd.nextInt(-32768, 32768), rnd.nextInt(-32768, 32768));
                case 3 -> r.rot(id, rnd.nextFloat() * 720 - 360, rnd.nextFloat() * 180 - 90);
                case 4 -> r.teleport(id, rnd.nextDouble(-100, 100), 64, 0, rnd.nextInt(32));
                case 5 -> r.sync(id, rnd.nextDouble(-1e6, 1e6), 64, 0);
                case 6 -> r.apply(new EntityObservation.DataUpdated(id, rnd.nextBoolean() ? "CROUCHING" : null, rnd.nextInt(5) == 0, rnd.nextInt(-1, 256)));
                case 7 -> r.apply(new EntityObservation.Passengers(id, List.of(rnd.nextInt(10), rnd.nextInt(10))));
                case 8 -> r.remove(id, rnd.nextInt(10));
                case 9 -> { var c = r.attack(id); check(r, c.target(), id); }
                case 10 -> { long s = r.next(); r.h.observeSequence(KEY, s, r.nanos); var i = r.engine.onInteraction(new CombatObservation.Interaction(s, s, s, id, r.nanos, "MAIN_HAND", 0, 0, 0, rnd.nextBoolean()), r.h); check(r, i.target(), id); }
                case 11 -> { if (rnd.nextInt(6) == 0) r.epoch(r.h.worldEpoch() + 1); else r.silent(); }
                case 12 -> { if (rnd.nextInt(8) == 0) { r.gap(); r.silent(); } else r.silent(); }
                default -> r.apply(new EntityObservation.HeadRotated(id, rnd.nextFloat() * 360));
            }
            if (step % 50 == 0) r.h.rebalance(rnd.nextDouble(-100, 100), 64, rnd.nextDouble(-100, 100));
            var st = r.h.status();
            assertTrue(st.live() + st.ended() <= EntityHistory.MAX_RETAINED && st.deep() <= EntityHistory.MAX_DEEP && st.bytes() >= 0 && st.bytes() <= TIER, "bounds at step " + step);
            assertTrue(r.engine.attacks().size() <= CombatEngine.MAX_ATTACKS && r.engine.interactions().size() <= CombatEngine.MAX_INTERACTIONS);
        }
        for (int id = 0; id < 300; id += 7) check(r, r.now(id), id);
        return EntityReplayMapper.digest(EntityReplayMapper.export(r.h)) + CombatReplayMapper.digest(CombatReplayMapper.export(r.engine, P.id())) + r.h.status();
    }
    static void check(Rig r, EntityLookup l, int id) {
        if (l.status() == Status.FOUND) {
            assertEquals(id, l.key().networkId(), "no cross-generation / cross-id leak"); assertEquals(KEY, l.key().session(), "no cross-session leak");
            assertEquals(r.h.worldEpoch(), l.key().worldEpoch(), "no cross-epoch leak");
            var v = l.revision(); assertTrue(Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()));
            if (r.h.status().coverageBreakSequence() >= 0 && l.queriedSequence() > r.h.status().coverageBreakSequence()) assertTrue(l.spawnSequence() > r.h.status().coverageBreakSequence(), "no exact answer across a coverage loss");
        } else assertNull(l.revision());
    }
    @Test void randomLifecycleStreamsKeepEveryInvariantAndAreDeterministic() {
        for (long seed = 1; seed <= SEEDS; seed++) assertEquals(run(seed), run(seed), "seed " + seed);
    }
    @Test void malformedValuesAreRejectedAtConstructionNeverInside() {
        assertThrows(IllegalArgumentException.class, () -> new EntityObservation.Spawned(-1, new UUID(0, 0), "minecraft:zombie", 0, 0, 0, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new EntityObservation.Spawned(1, new UUID(0, 0), "minecraft:zombie", Double.NaN, 0, 0, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new EntityObservation.Teleported(1, 0, 0, 0, 0f, 0f, 64));
        assertThrows(IllegalArgumentException.class, () -> new EntityObservation.DataUpdated(1, "", false, -1));
        assertThrows(IllegalArgumentException.class, () -> new CombatObservation.Attack(0, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CombatObservation.Interaction(1, 1, 1, 1, 0, "FOOT", 0, 0, 0, false));
        Rig r = new Rig(); r.spawn(1, 0, 64, 0); var c = r.attack(1); long behind = c.observation().sequence() - 1;
        assertNull(r.engine.onAttack(new CombatObservation.Attack(behind, 1, 1, 1, 0), r.h, new TimelineLookup.NeverExisted(behind), null, null), "an attack behind the engine's position is refused, not applied");
        assertEquals(1, r.engine.status().attacks()); assertEquals(1, r.engine.status().rejected());
    }
}
