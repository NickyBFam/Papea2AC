package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.player.TimingEvent;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 adversarial QA, P11-F27 delta (snapshot 4). Attacks on the attacker-slowdown branch: a hostile client controls how many ATTACK packets
 * it sends and to which id, so the branch must never become a switch that turns an impossible movement into a non-INVALID one; and a legitimate
 * slowdown the client applies WITHOUT an ATTACK packet (spear charge) must not stay a false INVALID.
 */
class Phase11QaAttackerSlowdownAttackTest {
    /** A speed cheat: {@code step} blocks of +x per tick on flat ground (the legal sprint is ~0.28), {@code spoofed} ATTACK packets on a non-existent id before each tick's movement. */
    static AttackerSlowdownTest.Harness speedCheat(double step, int spoofed) {
        var h = new AttackerSlowdownTest.Harness(new PhysicsScene()); var rec = h.rec; double x = 8.5;
        for (int i = 1; i <= 8; i++) {
            rec.advance(TICK_NANOS / 4);
            for (int a = 0; a < spoofed; a++) { rec.advance(10_000L); rec.add(new TimingEvent.LocalAttack(rec.nextSeq(), rec.nanos, 999_999)); }
            rec.advance(TICK_NANOS / 4);
            x += step; rec.movement(x, SURFACE, 8.5, 0f, true);
            rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2);
        }
        h.feed();
        return h;
    }
    @Test void theControlSpeedCheatIsInvalid() {
        var h = speedCheat(0.8, 0);
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
    /** One spoofed ATTACK per tick: the slowdown branch only slows, so the cheat must stay INVALID. */
    @Test void oneSpoofedAttackPerTickDoesNotHideASpeedCheat() {
        var h = speedCheat(0.8, 1);
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
    /**
     * Eight spoofed ATTACK packets per tick (nine slowdown variants > MAX_ATTACK_VARIANTS): the resolver marks the account incomplete, so the
     * validator answers TIMING_INCOMPLETE / UNCERTAIN. An ATTACK on a non-existent id costs the cheat nothing.
     */
    @Test void attackSpamDoesNotHideASpeedCheat() {
        var h = speedCheat(0.8, 8);
        assertTrue(h.status().invalid() >= 1, "8 spoofed ATTACK packets per tick turned an impossible speed into no INVALID:\n" + h.dump());
    }
    /**
     * Spear charge: the pinned 26.2 client runs KineticWeapon.damageEntities from ItemStack.onUseTick on its own player (no client guard), which
     * calls Player.stabAttack -> hurtOrSimulate -> causeExtraKnockback: the same (0.6, 1.0, 0.6) slowdown and setSprinting(false), and NO serverbound
     * ATTACK packet is sent. The sprinting charge into a boat / minecart / item frame / painting is therefore the P11-F27 path without its signal.
     */
    @Test void aSpearChargeHitSlowsTheClientWithoutAnAttackPacketAndMustNotBeInvalid() {
        for (String type : List.of("minecraft:painting", "minecraft:item_frame")) {
            var h = new AttackerSlowdownTest.Harness(new PhysicsScene(), type);
            AttackerSlowdownTest.play(h, AttackerSlowdownTest.moves(8, 12), Set.of(), Set.of(9));
            assertEquals(0, h.status().invalid(), "legitimate spear-charge slowdown on " + type + " is INVALID:\n" + h.dump());
        }
    }
}
