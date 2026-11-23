package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.MovementValidationRunner;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * P11-F27 (attacker slowdown; found by the Phase 13 thread as P13-F2, fixed forward in Phase 11). The pinned 26.2 client slows ITSELF when it
 * hits a {@code hurtClient}-capable entity with positive knockback ({@code Player.causeExtraKnockback}: motion (0.6, 1.0, 0.6), then
 * {@code setSprinting(false)}): after an ATTACK packet (sprint hit, knockback item) or with no packet at all during a spear charge
 * ({@code Player.stabAttack}). {@code hurtClient} is true for other players, boats, rafts and minecarts, paintings, leash knots, item frames, end
 * crystals and shulker bullets; never for a mob. The truth below applies the slowdown by exact arithmetic (a SET of the slowed motion),
 * independent of the model under test. The fix (attack-gated, owner decision): an ATTACK on a tracked eligible entity within reach, or a tick of a
 * possible kinetic-weapon use beside one, gives slowdown branches - concrete counts, larger counts folded into a sound envelope.
 */
class AttackerSlowdownTest {
    static final SessionKey KEY = new SessionKey(new UUID(13, 2), 1);
    static final int TARGET_ID = 4242;
    static final class Harness {
        final PhysicsScene ps; final TimingLedger timing = new TimingLedger(KEY); final MovementContextLedger context = new MovementContextLedger(KEY); final MovementValidationRunner.Metrics metrics = new MovementValidationRunner.Metrics(); final MovementValidationRunner runner; final Recorder rec; int fed; long contextSeq = 10;
        Harness(PhysicsScene ps) { this(ps, null); }
        /** {@code targetType}: the hit entity, shown to the client 1 block beside the start unless placed elsewhere (no other player anywhere in the scene). */
        Harness(PhysicsScene ps, String targetType) { this(ps, targetType, 9.5, 8.5); }
        Harness(PhysicsScene ps, String targetType, double targetX, double targetZ) {
            this.ps = ps;
            runner = new MovementValidationRunner(KEY, new MovementValidator(new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator))), ps.simulator, ps.scene.engine, ps.behaviors, TimingProfile.PAPER_26_2, metrics);
            rec = new Recorder(timing); rec.nanos = 5_000_000_000L;
            context.apply(KEY, new ContextEvent.BorderInitialized(0, 0, 0, 5.9999968E7, 5.9999968E7, 0));
            context.apply(KEY, new ContextEvent.GameModeChanged(1, "survival"));
            if (targetType != null) context.apply(KEY, new ContextEvent.EntitySpawned(2, TARGET_ID, targetType, targetX, SURFACE, targetZ));
            rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 8); rec.teleport(1, 8.5, SURFACE, 8.5, 0f, 0); rec.advance(TICK_NANOS / 4); rec.confirm(1); rec.advance(100_000L); rec.movement(8.5, SURFACE, 8.5, 0f, false);
            feed();
        }
        /** The hotbar / off-hand items as the server sent them: every hotbar slot {@code kinetic} / {@code knockback}, an empty off hand. */
        Harness hands(boolean kinetic, boolean knockback) {
            for (int i = 0; i < ContextEvent.HandItem.OFF_HAND; i++) context.apply(KEY, new ContextEvent.HandItem(contextSeq, i, kinetic, knockback));
            context.apply(KEY, new ContextEvent.HandItem(contextSeq++, ContextEvent.HandItem.OFF_HAND, false, false)); return this;
        }
        Harness using(boolean active) { context.apply(KEY, new ContextEvent.ItemUse(contextSeq++, active, false)); return this; }
        void feed() { for (; fed < rec.events.size(); fed++) runner.onEvent(rec.events.get(fed), timing.view(), context.view(), ps.scene.world.currentView(), ps.scene.world.worldEpoch()); }
        ValidationStatus status() { return runner.status(); }
        String dump() { ValidationStatus st = status(); return "transitions=" + st.transitions() + " valid=" + st.valid() + " uncertain=" + st.uncertain() + " invalid=" + st.invalid() + "\n" + String.join("\n", st.recent()) + "\n" + String.join("\n", st.notable()); }
    }
    /** The synthetic client after the anchor: {@code attacks.get(i)} ATTACK packets on the target before tick i's movement, {@code slowdowns.get(i)} slowdowns applied. */
    static void play(Harness h, List<MovementInput> inputs, Map<Integer, Integer> attacks, Map<Integer, Integer> slowdowns, int knockTick, Vec3d knock) {
        PhysicsScene ps = h.ps; PhysicsEnvironment env = ps.env(); Recorder rec = h.rec;
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        double lx = 8.5, ly = SURFACE, lz = 8.5; float lastYaw = inputs.get(0).yaw(); int sinceSend = 0;
        for (int i = 1; i <= inputs.size(); i++) {
            if (i == knockTick) rec.velocity(knock.x(), knock.y(), knock.z());
            rec.advance(TICK_NANOS / 4);
            for (int a = 0; a < attacks.getOrDefault(i, 0); a++) { rec.advance(10_000L); rec.add(new TimingEvent.LocalAttack(rec.nextSeq(), rec.nanos, TARGET_ID)); }
            rec.advance(TICK_NANOS / 4);
            MovementInput in = inputs.get(i - 1);
            Vec3d v = i == knockTick ? knock : s.velocity(); int n = slowdowns.getOrDefault(i, 0);
            for (int x = 0; x < n; x++) v = new Vec3d(v.x() * 0.6, v.y(), v.z() * 0.6);
            List<PhysicsEnvironment.ExternalMotion> applied = n > 0 || i == knockTick ? List.of(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, v, "truth" + i)) : List.of();
            PhysicsResult r = ps.simulator.simulate(s, in, applied.isEmpty() ? env : env.withExternalMotions(applied));
            if (!r.exact()) throw new AssertionError("tick " + i + " not exact: " + r.summary());
            PhysicsState next = r.next();
            boolean sendPosition = !OBS.withinSendThreshold(next.position().x(), next.position().y(), next.position().z(), lx, ly, lz) || ++sinceSend >= 20;
            if (sendPosition) { rec.movement(next.position().x(), next.position().y(), next.position().z(), in.yaw(), next.onGround()); lx = next.position().x(); ly = next.position().y(); lz = next.position().z(); sinceSend = 0; lastYaw = in.yaw(); }
            else if (in.yaw() != lastYaw) { rec.rotationOnly(in.yaw(), next.onGround()); lastYaw = in.yaw(); }
            rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2);
            s = next;
        }
        h.feed();
    }
    static void play(Harness h, List<MovementInput> inputs, Map<Integer, Integer> attacks, Map<Integer, Integer> slowdowns) { play(h, inputs, attacks, slowdowns, -1, null); }
    static Map<Integer, Integer> at(int tick, int n) { return Map.of(tick, n); }
    static final Map<Integer, Integer> NONE = Map.of();
    static List<MovementInput> moves(int sprint, int walk) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < sprint; i++) l.add(MovementInput.forward(0f, true)); for (int i = 0; i < walk; i++) l.add(MovementInput.forward(0f, false)); return l; }
    static List<MovementInput> walk(int n) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(MovementInput.forward(0f, false)); return l; }
    static List<MovementInput> idle(int n) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(MovementInput.idle(0f)); return l; }

    /** Every type whose client-side hurtClient can be true; the first six never push, so nothing else blocks an exact explanation beside them. */
    static final List<String> NON_PUSHING_TARGETS = List.of("minecraft:painting", "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:leash_knot", "minecraft:end_crystal", "minecraft:shulker_bullet");
    static final List<String> VEHICLE_AND_PLAYER_TARGETS = List.of("minecraft:oak_boat", "minecraft:bamboo_raft", "minecraft:minecart", "minecraft:hopper_minecart", "minecraft:player");

    @Test void theControlIsValidAndAnUnannouncedSlowdownIsInvalid() {
        PhysicsScene ps = new PhysicsScene();
        Harness control = new Harness(ps); play(control, moves(8, 12), NONE, NONE);
        assertEquals(0, control.status().invalid(), control.dump()); assertEquals(control.status().transitions(), control.status().valid(), control.dump());
        Harness hidden = new Harness(ps); play(hidden, moves(8, 12), NONE, at(9, 1));
        assertTrue(hidden.status().invalid() >= 1, "nothing explains a slowdown without a hit: " + hidden.dump());
    }
    @Test void aSprintHitOnEachNonPushingTargetIsExplainedExactly() {
        for (String type : NON_PUSHING_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type); play(h, moves(8, 12), at(9, 1), at(9, 1));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump()); assertEquals(h.status().transitions(), h.status().valid(), "explained by the slowdown branch: " + type + "\n" + h.dump());
        }
    }
    @Test void aSprintHitOnEachVehicleOrPlayerIsNeverInvalid() {
        for (String type : VEHICLE_AND_PLAYER_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type); play(h, moves(8, 12), at(9, 1), at(9, 1));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump());
        }
    }
    @Test void anAttackThatDidNotSlowIsExplainedByTheNoHitBranch() {
        Harness h = new Harness(new PhysicsScene(), "minecraft:painting"); play(h, moves(8, 12), at(9, 1), NONE);
        assertEquals(0, h.status().invalid(), h.dump()); assertEquals(h.status().transitions(), h.status().valid(), h.dump());
    }
    @Test void aKnockbackItemHitWhileWalkingIsNeverInvalid() {
        for (String type : NON_PUSHING_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type).hands(false, true); play(h, walk(20), at(9, 1), at(9, 1));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump());
        }
    }
    @Test void aServerKnockbackDrainedInTheHitTickIsNeverInvalid() {
        for (String type : NON_PUSHING_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type); play(h, moves(8, 14), at(9, 1), at(9, 1), 9, new Vec3d(0.3, 0.36, -0.2));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump());
        }
    }
    @Test void aSprintReacquiredInTheHitTickIsExplained() {
        // Phase 13's client-order check: the hit stops sprinting, then LocalPlayer.tick may start sprinting again before that tick's movement,
        // so the slowed motion is followed by a full sprint bonus in the same tick (the sprint flag stays an input dimension after the class reset)
        Harness h = new Harness(new PhysicsScene(), "minecraft:item_frame"); play(h, moves(14, 6), at(9, 1), at(9, 1));
        assertEquals(0, h.status().invalid(), h.dump()); assertEquals(h.status().transitions(), h.status().valid(), h.dump());
    }
    @Test void repeatedKnockbackHitsInOneTickAreNeverInvalidEvenBeyondTheVariantCap() {
        Harness two = new Harness(new PhysicsScene(), "minecraft:painting").hands(false, true); play(two, moves(8, 12), at(9, 3), at(9, 2));
        assertEquals(0, two.status().invalid(), two.dump()); assertEquals(two.status().transitions(), two.status().valid(), "two of three hits slowed: a concrete count\n" + two.dump());
        Harness many = new Harness(new PhysicsScene(), "minecraft:painting").hands(false, true); play(many, moves(8, 12), at(9, 20), at(9, 12));
        assertEquals(0, many.status().invalid(), "12 slowdowns lie in the folded envelope option: UNCERTAIN at worst\n" + many.dump());
    }
    @Test void withoutAKnockbackSourceASecondHitInTheTickCannotSlowAgain() {
        // no knockback item: the sprint bonus applies at most once per tick, so a second 0.6 in the same tick is outside every branch
        Harness h = new Harness(new PhysicsScene(), "minecraft:painting").hands(false, false); play(h, moves(8, 12), at(9, 2), at(9, 2));
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
    @Test void anUnannouncedSlowdownBesideAPaintingStaysInvalid() {
        // the painting is in reach but nothing hit it (no ATTACK, no kinetic use): no branch, the frozen behaviour
        Harness h = new Harness(new PhysicsScene(), "minecraft:painting"); play(h, moves(8, 12), NONE, at(9, 1));
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
    @Test void anAttackOnAMobOrAFarPaintingGivesNoBranch() {
        Harness mob = new Harness(new PhysicsScene(), "minecraft:zombie", 8.5, 20.5); play(mob, moves(8, 12), at(9, 1), at(9, 1));
        assertTrue(mob.status().invalid() >= 1, "a mob never slows the attacker: " + mob.dump()); assertTrue(mob.metrics.attacksWithoutSlowdown.sum() >= 1);
        Harness far = new Harness(new PhysicsScene(), "minecraft:painting", 40.5, 8.5); play(far, moves(8, 12), at(9, 1), at(9, 1));
        assertTrue(far.status().invalid() >= 1, "a painting out of reach cannot have been hit: " + far.dump());
    }
    @Test void theInboundAttackPayloadBecomesALocalAttackWithItsOwnClocks() {
        var o = new me.nicolas.papea2ac.protocol.Observation(KEY, 21, 777, 0, new me.nicolas.papea2ac.protocol.Observation.Attempt(21, 21, new me.nicolas.papea2ac.protocol.SemanticPayload.Attack(99)));
        var e = (TimingEvent.LocalAttack) me.nicolas.papea2ac.core.TimingObservationMapper.map(o, 42, 0).orElseThrow();
        assertEquals(21, e.sequence()); assertEquals(777, e.nanos()); assertEquals(99, e.targetEntityId());
    }
    @Test void aFoldedCountIsExactOnlyOnceItsHitsReachTheClientZeroingThreshold() {
        // "15 or more hits" on the largest velocity a packet can carry (3.9 per axis): 0.6^15 puts it under the zeroing threshold, so every larger count
        // is the same zeroed state and the step is exact; "2 or more" on a sprint is not decidable and stays an unresolved (never exact) step
        PhysicsScene ps = new PhysicsScene(); PhysicsState s0 = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult big = ps.simulator.simulate(s0.withVelocity(new Vec3d(3.9, 0.0, 3.9)), MovementInput.idle(0f), ps.env().withExternalMotions(List.of(PhysicsEnvironment.ExternalMotion.attackerSlowdownRange(15, 0.001, "t"))));
        assertTrue(big.exact(), big.summary());
        PhysicsResult small = ps.simulator.simulate(s0.withVelocity(new Vec3d(0.3, 0.0, 0.0)), MovementInput.forward(0f, true), ps.env().withExternalMotions(List.of(PhysicsEnvironment.ExternalMotion.attackerSlowdownRange(2, 0.36, "t"))));
        assertFalse(small.exact(), small.summary());
    }
}
