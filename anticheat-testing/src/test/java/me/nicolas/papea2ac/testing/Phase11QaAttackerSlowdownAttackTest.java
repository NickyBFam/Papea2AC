package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.player.TimingEvent;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 adversarial QA, P11-F27 delta (QA-09, QA-10; first written by the QA thread on snapshot 4, extended by the Lead for the snapshot-5
 * attack-gated design). A hostile client controls how many ATTACK packets it sends and to which id: they must never turn an impossible movement
 * into a non-INVALID one (QA-09), not even on a real eligible entity within reach (excess counts are folded into a sound envelope that still
 * excludes impossible speed). A legitimate slowdown the client applies WITHOUT an ATTACK packet (spear charge) must not be a false INVALID (QA-10).
 */
class Phase11QaAttackerSlowdownAttackTest {
    static final int NO_SUCH_ENTITY = 999_999;
    /** A speed cheat: {@code step} blocks of +x per tick on flat ground (the legal sprint is ~0.28), {@code spoofed} ATTACK packets on {@code target} before each tick's movement, the position sent every {@code ticksPerPacket} ticks. */
    static AttackerSlowdownTest.Harness speedCheat(AttackerSlowdownTest.Harness h, double step, int spoofed, int target, int ticksPerPacket) {
        var rec = h.rec; double x = 8.5;
        for (int i = 1; i <= 8 * ticksPerPacket; i++) {
            rec.advance(TICK_NANOS / 4);
            for (int a = 0; a < spoofed; a++) { rec.advance(10_000L); rec.add(new TimingEvent.LocalAttack(rec.nextSeq(), rec.nanos, target)); }
            rec.advance(TICK_NANOS / 4);
            x += step; if (i % ticksPerPacket == 0) rec.movement(x, SURFACE, 8.5, 0f, true);
            rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2);
        }
        h.feed();
        return h;
    }
    static AttackerSlowdownTest.Harness speedCheat(double step, int spoofed) { return speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene()), step, spoofed, NO_SUCH_ENTITY, 1); }

    @Test void theControlSpeedCheatIsInvalid() {
        assertTrue(speedCheat(0.8, 0).status().invalid() >= 1);
    }
    /** QA-09: 1, 8, 16 and 64 spoofed ATTACK packets per tick on an id the client does not track give no branch: the cheat stays INVALID. */
    @Test void attackSpamOnAnUnknownIdDoesNotHideASpeedCheat() {
        for (int n : List.of(1, 8, 16, 64)) {
            var h = speedCheat(0.8, n);
            assertTrue(h.status().invalid() >= 1, n + " spoofed ATTACK packets per tick turned an impossible speed into no INVALID:\n" + h.dump());
        }
    }
    /** QA-09: spam on a real eligible entity within reach, with a knockback source (every count possible): the folded envelope still excludes the speed. */
    @Test void attackSpamOnARealPaintingInReachDoesNotHideASpeedCheat() {
        for (int n : List.of(1, 8, 16, 64)) {
            var h = speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting").hands(false, true), 0.8, n, AttackerSlowdownTest.TARGET_ID, 1);
            assertTrue(h.status().invalid() >= 1, "spam on a reachable painting, n=" + n + ":\n" + h.dump());
        }
    }
    /** QA-09: spam on real entities that cannot slow the client: a mob (hurtClient false) and a painting far beyond any vanilla reach. */
    @Test void attackSpamOnEntitiesThatCannotSlowTheClientDoesNotHideASpeedCheat() {
        for (int n : List.of(8, 64)) {
            var mob = speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:zombie", 8.5, 40.5), 0.8, n, AttackerSlowdownTest.TARGET_ID, 1);
            assertTrue(mob.status().invalid() >= 1, "spam on a mob, n=" + n + ":\n" + mob.dump());
            var far = speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting", 8.5, 40.5), 0.8, n, AttackerSlowdownTest.TARGET_ID, 1);
            assertTrue(far.status().invalid() >= 1, "spam on a painting out of reach, n=" + n + ":\n" + far.dump());
        }
    }
    /** QA-09, multi-tick horizon: spam every tick with the position sent every 4 ticks (unknown id, and a reachable painting). */
    @Test void spamAcrossAMultiTickHorizonDoesNotHideASpeedCheat() {
        assertTrue(speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene()), 0.8, 8, NO_SUCH_ENTITY, 4).status().invalid() >= 1);
        var h = speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting").hands(false, true), 0.8, 8, AttackerSlowdownTest.TARGET_ID, 4);
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
    /**
     * QA-10, spear charge: the pinned 26.2 client runs KineticWeapon.damageEntities from ItemStack.onUseTick on its own player, which calls
     * Player.stabAttack -> hurtOrSimulate -> causeExtraKnockback: the same (0.6, 1.0, 0.6) slowdown and setSprinting(false), and NO serverbound ATTACK
     * packet is sent. While a kinetic weapon may be in use beside an eligible entity, the slowdown must never be INVALID.
     */
    @Test void aSpearChargeHitSlowsTheClientWithoutAnAttackPacketAndMustNotBeInvalid() {
        for (String type : List.of("minecraft:painting", "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:oak_boat", "minecraft:minecart", "minecraft:end_crystal", "minecraft:player")) {
            var h = new AttackerSlowdownTest.Harness(new PhysicsScene(), type).hands(true, false).using(true);
            AttackerSlowdownTest.play(h, AttackerSlowdownTest.moves(8, 12), AttackerSlowdownTest.NONE, AttackerSlowdownTest.at(9, 1));
            assertEquals(0, h.status().invalid(), "legitimate spear-charge slowdown on " + type + " is INVALID:\n" + h.dump());
        }
    }
    /** QA-10 with unknown hand items (nothing sent yet): a kinetic weapon may be held, so the charge branch applies. */
    @Test void aChargeWithUnknownHandItemsIsNeverInvalid() {
        var h = new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting").using(true);
        AttackerSlowdownTest.play(h, AttackerSlowdownTest.moves(8, 12), AttackerSlowdownTest.NONE, AttackerSlowdownTest.at(9, 1));
        assertEquals(0, h.status().invalid(), h.dump());
    }
    /** QA-10 controls: an item use without any kinetic item, a kinetic weapon held but not used, or a charge beside a mob only - no branch, still INVALID. */
    @Test void withoutAPossibleKineticChargeBesideAnEligibleEntityTheSlowdownStaysInvalid() {
        var notKinetic = new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting").hands(false, false).using(true);
        AttackerSlowdownTest.play(notKinetic, AttackerSlowdownTest.moves(8, 12), AttackerSlowdownTest.NONE, AttackerSlowdownTest.at(9, 1));
        assertTrue(notKinetic.status().invalid() >= 1, "eating or blocking cannot charge: " + notKinetic.dump());
        var notUsing = new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting").hands(true, false).using(false);
        AttackerSlowdownTest.play(notUsing, AttackerSlowdownTest.moves(8, 12), AttackerSlowdownTest.NONE, AttackerSlowdownTest.at(9, 1));
        assertTrue(notUsing.status().invalid() >= 1, notUsing.dump());
        var mob = new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:cow", 8.5, 20.5).hands(true, false).using(true);
        AttackerSlowdownTest.play(mob, AttackerSlowdownTest.moves(8, 12), AttackerSlowdownTest.NONE, AttackerSlowdownTest.at(9, 1));
        assertTrue(mob.status().invalid() >= 1, mob.dump());
    }
    /** QA-10 cannot be abused either: a speed cheat while "charging" a spear beside a painting stays INVALID (the charge branch only scales motion down). */
    @Test void aSpeedCheatWhileChargingBesideAPaintingStaysInvalid() {
        var h = speedCheat(new AttackerSlowdownTest.Harness(new PhysicsScene(), "minecraft:painting").hands(true, true).using(true), 0.8, 0, NO_SUCH_ENTITY, 1);
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
    /** The item facts reach the context from the server's inventory packets: a spear in the hotbar is kinetic, a knockback enchantment a knockback source. */
    @Test void theInventoryMapsToHandItemFacts() {
        var spear = new me.nicolas.papea2ac.protocol.SemanticPayload.Item(false, "minecraft:iron_spear", 1, 0, 250, false, List.of(), List.of("minecraft:kinetic_weapon", "minecraft:attribute_modifiers"));
        var stick = new me.nicolas.papea2ac.protocol.SemanticPayload.Item(false, "minecraft:stick", 1, 0, 0, false, List.of(new me.nicolas.papea2ac.protocol.SemanticPayload.Enchant("minecraft:knockback", 2)), List.of());
        var bread = new me.nicolas.papea2ac.protocol.SemanticPayload.Item(false, "minecraft:bread", 3, 0, 0, false, List.of(), List.of("minecraft:food"));
        List<me.nicolas.papea2ac.protocol.SemanticPayload.Item> items = new ArrayList<>(); for (int i = 0; i < 46; i++) items.add(me.nicolas.papea2ac.protocol.SemanticPayload.Item.EMPTY);
        items.set(36, spear); items.set(37, stick); items.set(45, bread);
        var o = new me.nicolas.papea2ac.protocol.Observation(AttackerSlowdownTest.KEY, 5, 5, 0, new me.nicolas.papea2ac.protocol.Observation.Outbound(1, new me.nicolas.papea2ac.protocol.SemanticPayload.ContainerContent(0, 1, items, me.nicolas.papea2ac.protocol.SemanticPayload.Item.EMPTY)));
        var events = me.nicolas.papea2ac.core.ContextObservationMapper.map(o, 7);
        assertEquals(10, events.size());
        assertEquals(new ContextEvent.HandItem(5, 0, true, true), events.get(0));
        assertEquals(new ContextEvent.HandItem(5, 1, false, true), events.get(1));
        assertEquals(new ContextEvent.HandItem(5, 2, false, false), events.get(2));
        assertEquals(new ContextEvent.HandItem(5, ContextEvent.HandItem.OFF_HAND, false, false), events.get(9));
        var use = new me.nicolas.papea2ac.protocol.Observation(AttackerSlowdownTest.KEY, 6, 6, 0, new me.nicolas.papea2ac.protocol.Observation.Attempt(6, 6, new me.nicolas.papea2ac.protocol.SemanticPayload.UseItem(me.nicolas.papea2ac.protocol.SemanticPayload.Hand.MAIN_HAND, 1, new me.nicolas.papea2ac.protocol.PacketPayload.Rotation(0f, 0f))));
        assertEquals(List.of(new ContextEvent.ItemUse(6, true, false)), me.nicolas.papea2ac.core.ContextObservationMapper.map(use, 7));
    }
}
