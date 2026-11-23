package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.VelocityTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.core.PushExclusion;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 13 melee push exclusion. A melee attacker is always within the Phase 8 neighbourhood radius, so before Phase 13 every melee
 * knockback was UNCERTAIN (ENTITY_NEIGHBORHOOD_UNKNOWN). With a proof that no entity box can have touched the player during the transition,
 * the neighbourhood is empty and the fenced profile decides melee knockback like any other. Without the proof (or with an entity that may
 * touch the player) nothing changes.
 */
class MeleePushExclusionTest {
    static final Vec3d AWAY = new Vec3d(-0.4, 0.36, 0.0); // knockback away from an attacker standing at +x
    static final int ATTACKER = 77;
    /** The attacker's box when its admissible positions are exactly (x, SURFACE, 8.5): a 0.6 x 1.8 player box. */
    static PickMath.Box playerAt(double x) { return PickMath.makeBoundingBox(0.6f, 1.8f, x, SURFACE, 8.5); }

    static Harness melee(double attackerX, boolean withProof, List<Knock> knocks, List<MovementInput> in) {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps, TimingProfile.PAPER_26_2_FENCED);
        h.context.apply(KEY, new ContextEvent.EntitySpawned(2, ATTACKER, "minecraft:player", attackerX, SURFACE, 8.5));
        if (withProof) h.runner.attachNeighborhoodProof((req, ctx) -> PushExclusion.noContact(req, ctx, ApplicationFrontier.NONE, id -> id == ATTACKER ? playerAt(attackerX) : null));
        h.anchor(1, 8.5, SURFACE, 8.5);
        List<MovementInput> all = new ArrayList<>(in); all.addAll(idle(10));
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), all, knocks, h.rec, false); h.feed();
        return h;
    }

    @Test void withoutTheProofAMeleeKnockbackIsNeverDecided() {
        Harness cancel = melee(11.0, false, List.of(Knock.cancelled(AWAY, 5, 6)), walk(25, false));
        assertEquals(0, cancel.status().invalid(), cancel.dump());
        assertTrue(cancel.status().notable().stream().anyMatch(l -> l.contains("ENTITY_NEIGHBORHOOD_UNKNOWN")), cancel.dump());
    }
    @Test void withTheProofACancelledMeleeKnockbackIsInvalidAndAnHonestOneValid() {
        Harness cancel = melee(11.0, true, List.of(Knock.cancelled(AWAY, 5, 6)), idle(25));
        assertTrue(cancel.status().invalid() >= 1, cancel.dump());
        assertTrue(cancel.runner.neighborhoodProofs() > 0);
        for (int lag = 0; lag <= 3; lag++) {
            Harness honest = melee(11.0, true, List.of(Knock.honest(AWAY, 5, 5 + lag, 5 + lag + 1)), idle(25));
            assertEquals(0, honest.status().invalid(), "lag " + lag + "\n" + honest.dump());
            assertFalse(honest.status().notable().stream().anyMatch(l -> l.contains("ENTITY_NEIGHBORHOOD_UNKNOWN")), honest.dump());
        }
    }
    @Test void anAttackerThatMayTouchThePlayerKeepsItUncertain() {
        // 0.5 blocks apart: the boxes (grown by the margin) intersect, so a push is possible and nothing is excluded
        Harness h = melee(9.0, true, List.of(Knock.cancelled(AWAY, 5, 6)), idle(25));
        assertEquals(0, h.status().invalid(), h.dump());
        assertTrue(h.status().notable().stream().anyMatch(l -> l.contains("UNCERTAIN")), h.dump());
        // and an unknown bound (null) never proves anything
        PhysicsScene ps = new PhysicsScene(); Harness u = new Harness(ps, TimingProfile.PAPER_26_2_FENCED);
        u.context.apply(KEY, new ContextEvent.EntitySpawned(2, ATTACKER, "minecraft:player", 11.0, SURFACE, 8.5));
        u.runner.attachNeighborhoodProof((req, ctx) -> PushExclusion.noContact(req, ctx, ApplicationFrontier.NONE, id -> null));
        u.anchor(1, 8.5, SURFACE, 8.5);
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), idle(30), List.of(Knock.cancelled(AWAY, 5, 6)), u.rec, false); u.feed();
        assertEquals(0, u.status().invalid(), u.dump());
    }

    // ---------------------------------------------------------------- the shared Phase 12 bound (ReachValidator.possibleEntityBox) for a player target
    @Test void thePhase12EntityBoundCoversThePlayerBoxAndGrowsWithUnknowns() {
        EntityGeometryTable table = EntityGeometryTable.paper26_2();
        var lifetime = new PickMath.Box(3.5, 64, 0.5, 3.5, 64, 0.5);
        var proven = new ApplicationFrontier(ApplicationFrontier.Status.MARKER_PROVEN, 10, 12, 5);
        var known = new me.nicolas.papea2ac.combat.reach.ReachValidator.TargetInput(me.nicolas.papea2ac.world.EntityLookup.Status.FOUND, "minecraft:player", 70, 1, List.of(new double[]{3.5, 64, 0.5}), true, false, false, lifetime, proven, "STANDING", 1.0, null);
        PickMath.Box b = me.nicolas.papea2ac.combat.reach.ReachValidator.possibleEntityBox(known, table);
        assertNotNull(b); assertTrue(b.minX() <= 3.2 && b.maxX() >= 3.8 && b.maxY() >= 64.0 + 1.8f && b.maxX() - b.minX() < 0.62, "standing player 0.6 x 1.8 at scale 1: " + b);
        PickMath.Box local = PickMath.makeBoundingBox(0.6f, 1.8f, 0.5, 64, 0.5);
        assertFalse(new PickMath.Box(b.minX() - PushExclusion.MARGIN, b.minY(), b.minZ() - PushExclusion.MARGIN, b.maxX() + PushExclusion.MARGIN, b.maxY(), b.maxZ() + PushExclusion.MARGIN).intersects(local), "3 blocks apart: no contact");
        var unknownScale = new me.nicolas.papea2ac.combat.reach.ReachValidator.TargetInput(me.nicolas.papea2ac.world.EntityLookup.Status.FOUND, "minecraft:player", 70, 1, List.of(new double[]{3.5, 64, 0.5}), true, false, false, lifetime, proven, "STANDING", null, null);
        PickMath.Box big = me.nicolas.papea2ac.combat.reach.ReachValidator.possibleEntityBox(unknownScale, table);
        assertTrue(big.maxX() - big.minX() > 9.0, "an unknown scale admits the largest box (scale 16): " + big);
        assertNull(me.nicolas.papea2ac.combat.reach.ReachValidator.possibleEntityBox(new me.nicolas.papea2ac.combat.reach.ReachValidator.TargetInput(me.nicolas.papea2ac.world.EntityLookup.Status.FOUND, "minecraft:player", 70, 1, List.of(new double[]{3.5, 64, 0.5}), true, false, false, null, proven, "STANDING", 1.0, null), table), "an unknown lifetime proves nothing");
        assertNull(me.nicolas.papea2ac.combat.reach.ReachValidator.possibleEntityBox(new me.nicolas.papea2ac.combat.reach.ReachValidator.TargetInput(me.nicolas.papea2ac.world.EntityLookup.Status.FOUND, "minecraft:player", 70, 1, List.of(new double[]{3.5, 64, 0.5}), true, true, false, lifetime, proven, "STANDING", 1.0, null), table), "a mounted entity proves nothing");
    }
}
