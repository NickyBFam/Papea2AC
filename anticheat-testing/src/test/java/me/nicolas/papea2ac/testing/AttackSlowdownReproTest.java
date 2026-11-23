package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.VelocityTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * P13-F2 reproduction (finding phase13-to-coordinator-02): the pinned 26.2 client runs {@code Player.attack} locally
 * ({@code MultiPlayerGameMode.attack} sends the ATTACK packet, then calls it). When {@code hurtOrSimulate} is true on the client
 * ({@code hurtClient}: a remote player, a boat or minecart, a painting or leash knot, an item frame, a shulker bullet) and the knockback is
 * positive (a sprint hit, or a knockback attribute/enchantment), {@code causeExtraKnockback} multiplies the ATTACKER's own motion by
 * (0.6, 1, 0.6) and stops its sprint, before that tick's movement. No Phase 5-8 model knows it: a legitimate sprint-punch on an item frame
 * with nobody around is a complete-context INVALID today.
 */
class AttackSlowdownReproTest {
    static List<MovementInput> sprintThenWalk(int sprint, int walk) { List<MovementInput> l = new ArrayList<>(walk(sprint, true)); l.addAll(walk(walk, false)); return l; }

    @Test void aLegitimateSprintHitOnAnItemFrameIsACompleteInvalidUnderThePhase8Model() {
        PhysicsScene ps = new PhysicsScene();
        Harness control = new Harness(ps, TimingProfile.PAPER_26_2); control.anchor(1, 8.5, SURFACE, 8.5);
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), sprintThenWalk(8, 12), List.of(), Set.of(), control.rec, false); control.feed();
        assertEquals(0, control.status().invalid(), "control (sprint stop without an attack) is legitimate and modelled\n" + control.dump());
        assertTrue(control.status().valid() >= 15, control.dump());

        Harness h = new Harness(ps, TimingProfile.PAPER_26_2); h.anchor(1, 8.5, SURFACE, 8.5);
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), sprintThenWalk(8, 12), List.of(), Set.of(9), h.rec, false); h.feed();
        assertTrue(h.status().invalid() >= 1, "the attacker slowdown is outside the model: a legitimate sprint hit becomes INVALID\n" + h.dump());
    }

    // ---------------------------------------------------------------- the Phase 13 model: the recorded ATTACK branches the slowdown
    private static Harness attacking(TimingProfile p, List<MovementInput> inputs, Set<Integer> attacks, Set<Integer> slowdowns, List<Knock> knocks) {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps, p); h.anchor(1, 8.5, SURFACE, 8.5);
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), inputs, knocks, attacks, slowdowns, h.rec, false); h.feed();
        return h;
    }
    @Test void aRecordedSprintHitIsExplainedByTheSlowdownBranch() {
        for (TimingProfile p : List.of(TimingProfile.PAPER_26_2, TimingProfile.PAPER_26_2_FENCED)) {
            Harness h = attacking(p, sprintThenWalk(8, 12), Set.of(9), Set.of(9), List.of());
            assertEquals(0, h.status().invalid(), p.id() + "\n" + h.dump());
            assertEquals(h.status().transitions(), h.status().valid(), "every transition explained and complete: " + h.dump());
        }
    }
    @Test void aRecordedAttackWithoutSlowdownIsExplainedByTheOtherBranch() {
        Harness h = attacking(TimingProfile.PAPER_26_2_FENCED, sprintThenWalk(8, 12), Set.of(9), Set.of(), List.of());
        assertEquals(0, h.status().invalid(), h.dump()); assertEquals(h.status().transitions(), h.status().valid(), h.dump());
    }
    @Test void twoAttacksInOneTickAndAKnockbackInTheSameTickStayValid() {
        // the drained knockback first, then the attack's slowdown on the post-drain motion (Minecraft.runTick: processQueuedPackets before tick())
        Harness h = attacking(TimingProfile.PAPER_26_2_FENCED, sprintThenWalk(8, 14), Set.of(9), Set.of(9), List.of(Knock.honest(new Vec3d(0.3, 0.36, -0.2), 9, 9, 9)));
        assertEquals(0, h.status().invalid(), h.dump()); assertEquals(h.status().transitions(), h.status().valid(), h.dump());
    }
    @Test void anAttackWhileStandingNeverProducesInvalid() {
        Harness h = attacking(TimingProfile.PAPER_26_2_FENCED, idle(40), Set.of(5, 12), Set.of(5, 12), List.of());
        assertEquals(0, h.status().invalid(), h.dump());
    }
    @Test void keepSprintIsNotDetectableYetBecauseBothBranchesStayOpen() {
        // a cheat that skips the slowdown after a recorded attack is explained by the no-slowdown branch (needs target/cooldown facts: documented limitation)
        Harness h = attacking(TimingProfile.PAPER_26_2_FENCED, walk(20, true), Set.of(9), Set.of(), List.of());
        assertEquals(0, h.status().invalid(), h.dump());
    }
}
