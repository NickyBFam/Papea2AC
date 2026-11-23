package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.VelocityTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 13 central question (P13_VELOCITY_MATRIX section 1), answered offline through the real Phase 8 runner:
 *
 * <ol>
 * <li>Under the frozen Phase 7 profile ({@code PAPER_26_2}, no application bound) a client that CANCELS knockback is never contradicted:
 *     the "not yet applied" branch explains it until the influence expires (the hypothesis of thread 9, confirmed).</li>
 * <li>Under the Phase 13 fenced profile ({@code PAPER_26_2_FENCED}, P13-N1: the pong of a ping written after the velocity is sent from
 *     the client's packet drain, after the velocity was applied) the same cancellation has no explaining branch once the answer is in:
 *     INVALID with a complete certificate - for a moving and for a standing (silent) client.</li>
 * <li>Honest clients stay VALID under the fenced profile, including late processing, a stall without any answer, and repeated knockback.</li>
 * </ol>
 */
class KnockbackApplicationFenceTest {
    static final Vec3d KB = new Vec3d(0.4, 0.36, 0.0);

    private static Harness run(TimingProfile profile, List<MovementInput> inputs, List<Knock> knocks, int tailIdle) {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps, profile);
        h.anchor(1, 8.5, SURFACE, 8.5);
        List<MovementInput> all = new ArrayList<>(inputs); all.addAll(idle(tailIdle));
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), all, knocks, h.rec, false); h.feed();
        return h;
    }

    // ---------------------------------------------------------------- 1. the gap under the frozen profile
    @Test void aCancelledKnockbackIsNeverContradictedWithoutAnApplicationBound() {
        Harness standing = run(TimingProfile.PAPER_26_2, idle(30), List.of(Knock.cancelled(KB, 5, 6)), 0);
        assertEquals(0, standing.status().invalid(), "frozen profile: full cancellation while standing is explained by the not-applied branch\n" + standing.dump());
        Harness walking = run(TimingProfile.PAPER_26_2, walk(30, false), List.of(Knock.cancelled(KB, 5, 6)), 0);
        assertEquals(0, walking.status().invalid(), "frozen profile: full cancellation while walking is explained by the not-applied branch\n" + walking.dump());
    }

    // ---------------------------------------------------------------- 2. the fence closes it
    @Test void aCancelledKnockbackIsInvalidOnceTheFenceIsAnsweredWhileWalking() {
        Harness h = run(TimingProfile.PAPER_26_2_FENCED, walk(30, false), List.of(Knock.cancelled(KB, 5, 6)), 0);
        ValidationStatus st = h.status();
        assertTrue(st.invalid() >= 1, "fenced profile: the answered marker removes the not-applied branch\n" + h.dump());
        assertTrue(st.notable().stream().anyMatch(l -> l.contains("INVALID") && l.contains("NO_LEGAL_INPUT")), h.dump());
    }
    @Test void aCancelledKnockbackIsInvalidOnceTheFenceIsAnsweredWhileStanding() {
        Harness h = run(TimingProfile.PAPER_26_2_FENCED, idle(40), List.of(Knock.cancelled(KB, 5, 6)), 0);
        assertTrue(h.status().invalid() >= 1, "fenced profile: a silent client that ignored a fenced knockback has no explaining branch (branching closure)\n" + h.dump());
    }
    @Test void aHalvedKnockbackIsInvalidUnderBothProfiles() {
        for (TimingProfile p : List.of(TimingProfile.PAPER_26_2, TimingProfile.PAPER_26_2_FENCED)) {
            Harness h = run(p, walk(30, false), List.of(Knock.scaled(KB, 5, 5, 6, 0.5, 1.0)), 0);
            assertTrue(h.status().invalid() >= 1, p.id() + ": a scaled vector matches neither the applied nor the not-applied branch\n" + h.dump());
        }
    }

    // ---------------------------------------------------------------- 3. honest clients
    @Test void honestKnockbackStaysValidUnderTheFencedProfile() {
        for (boolean walking : new boolean[] { false, true }) for (int lag = 0; lag <= 4; lag++) for (int ackLag = 0; ackLag <= 3; ackLag++) {
            Knock k = Knock.honest(KB, 5, 5 + lag, 5 + lag + ackLag);
            Harness h = run(TimingProfile.PAPER_26_2_FENCED, walking ? walk(30, false) : idle(30), List.of(k), 10);
            assertEquals(0, h.status().invalid(), "honest " + (walking ? "walking" : "standing") + " lag=" + lag + " ackLag=" + ackLag + "\n" + h.dump());
        }
    }
    @Test void anHonestKnockbackDuringAStallWithoutAnyAnswerStaysValid() {
        Harness h = run(TimingProfile.PAPER_26_2_FENCED, walk(30, false), List.of(Knock.honest(KB, 5, 12, 0)), 10);
        assertEquals(0, h.status().invalid(), h.dump());
    }
    @Test void repeatedHonestKnockbackStaysValidUnderTheFencedProfile() {
        List<Knock> combo = List.of(Knock.honest(KB, 4, 5, 5), Knock.honest(new Vec3d(-0.3, 0.36, 0.2), 9, 10, 11), Knock.honest(new Vec3d(0.0, 0.42, -0.4), 12, 12, 14));
        Harness h = run(TimingProfile.PAPER_26_2_FENCED, walk(40, true), combo, 10);
        assertEquals(0, h.status().invalid(), h.dump());
    }
}
