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
 * P11-F27 (attacker slowdown; found by the Phase 13 thread as P13-F2, fixed forward in Phase 11). The pinned 26.2 client runs {@code Player.attack} locally ({@code MultiPlayerGameMode.attack} sends
 * the ATTACK packet first, from {@code Minecraft.handleKeybinds}, before {@code ClientLevel.tickEntities} moves the local player). When the
 * target's {@code hurtClient} is true (RemotePlayer, VehicleEntity, ShulkerBullet, BlockAttachedEntity, ItemFrame) and the knockback is positive
 * (sprint hit, or knockback attribute/enchantment), {@code causeExtraKnockback} runs {@code setDeltaMovement(getDeltaMovement().multiply(0.6,
 * 1.0, 0.6))} then {@code setSprinting(false)} before that tick's movement. The truth below applies it by exact arithmetic (a SET of the slowed
 * motion), independent of the model under test.
 */
class AttackerSlowdownTest {
    static final SessionKey KEY = new SessionKey(new UUID(13, 2), 1);
    static final int TARGET_ID = 4242;
    static final class Harness {
        final PhysicsScene ps; final TimingLedger timing = new TimingLedger(KEY); final MovementContextLedger context = new MovementContextLedger(KEY); final MovementValidationRunner.Metrics metrics = new MovementValidationRunner.Metrics(); final MovementValidationRunner runner; final Recorder rec; int fed;
        Harness(PhysicsScene ps) { this(ps, null); }
        /** {@code targetType}: the attacked entity, shown to the client 1 block beside the start (no other player anywhere in the scene). */
        Harness(PhysicsScene ps, String targetType) {
            this.ps = ps;
            runner = new MovementValidationRunner(KEY, new MovementValidator(new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator))), ps.simulator, ps.scene.engine, ps.behaviors, TimingProfile.PAPER_26_2, metrics);
            rec = new Recorder(timing); rec.nanos = 5_000_000_000L;
            context.apply(KEY, new ContextEvent.BorderInitialized(0, 0, 0, 5.9999968E7, 5.9999968E7, 0));
            context.apply(KEY, new ContextEvent.GameModeChanged(1, "survival"));
            if (targetType != null) context.apply(KEY, new ContextEvent.EntitySpawned(2, TARGET_ID, targetType, 9.5, SURFACE, 8.5));
            rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 8); rec.teleport(1, 8.5, SURFACE, 8.5, 0f, 0); rec.advance(TICK_NANOS / 4); rec.confirm(1); rec.advance(100_000L); rec.movement(8.5, SURFACE, 8.5, 0f, false);
            feed();
        }
        void feed() { for (; fed < rec.events.size(); fed++) runner.onEvent(rec.events.get(fed), timing.view(), context.view(), ps.scene.world.currentView(), ps.scene.world.worldEpoch()); }
        ValidationStatus status() { return runner.status(); }
        String dump() { ValidationStatus st = status(); return "transitions=" + st.transitions() + " valid=" + st.valid() + " uncertain=" + st.uncertain() + " invalid=" + st.invalid() + "\n" + String.join("\n", st.recent()) + "\n" + String.join("\n", st.notable()); }
    }
    /** The synthetic client after the anchor: {@code attackTicks} record an ATTACK in the gap before that tick's movement, {@code slowdownTicks} apply the slowdown. */
    static void play(Harness h, List<MovementInput> inputs, Set<Integer> attackTicks, Set<Integer> slowdownTicks) { play(h, inputs, attackTicks, slowdownTicks, -1, null); }
    /** {@code knockTick} / {@code knock}: a server velocity (SET) sent just before that tick and drained in it (Minecraft.runTick: processQueuedPackets before tick()), so the slowdown applies to the post-knockback motion. */
    static void play(Harness h, List<MovementInput> inputs, Set<Integer> attackTicks, Set<Integer> slowdownTicks, int knockTick, Vec3d knock) {
        PhysicsScene ps = h.ps; PhysicsEnvironment env = ps.env(); Recorder rec = h.rec;
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.5).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        double lx = 8.5, ly = SURFACE, lz = 8.5; float lastYaw = inputs.get(0).yaw(); int sinceSend = 0;
        for (int i = 1; i <= inputs.size(); i++) {
            if (i == knockTick) rec.velocity(knock.x(), knock.y(), knock.z());
            rec.advance(TICK_NANOS / 4);
            if (attackTicks.contains(i)) { rec.advance(100_000L); rec.add(new TimingEvent.LocalAttack(rec.nextSeq(), rec.nanos, 4242)); }
            rec.advance(TICK_NANOS / 4);
            MovementInput in = inputs.get(i - 1);
            Vec3d drained = i == knockTick ? knock : s.velocity();
            List<PhysicsEnvironment.ExternalMotion> applied = slowdownTicks.contains(i) ? List.of(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, new Vec3d(drained.x() * 0.6, drained.y(), drained.z() * 0.6), "attack" + i))
                : i == knockTick ? List.of(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, knock, "knock" + i)) : List.of();
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
    static List<MovementInput> moves(int sprint, int walk) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < sprint; i++) l.add(MovementInput.forward(0f, true)); for (int i = 0; i < walk; i++) l.add(MovementInput.forward(0f, false)); return l; }
    static List<MovementInput> idle(int n) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(MovementInput.idle(0f)); return l; }

    @Test void theUnrecordedSlowdownIsTheFalseInvalidAndTheControlIsValid() {
        PhysicsScene ps = new PhysicsScene();
        Harness control = new Harness(ps); play(control, moves(8, 12), Set.of(), Set.of());
        assertEquals(0, control.status().invalid(), control.dump()); assertEquals(control.status().transitions(), control.status().valid(), control.dump());
        // what the frozen chain saw before the fix: the slowdown without any attack signal is outside every branch
        Harness hidden = new Harness(ps); play(hidden, moves(8, 12), Set.of(), Set.of(9));
        assertTrue(hidden.status().invalid() >= 1, "no branch explains an unannounced slowdown: " + hidden.dump());
    }
    @Test void aRecordedSprintHitIsValidWithTheSlowdownBranch() {
        Harness h = new Harness(new PhysicsScene()); play(h, moves(8, 12), Set.of(9), Set.of(9));
        assertEquals(0, h.status().invalid(), h.dump()); assertEquals(h.status().transitions(), h.status().valid(), h.dump());
    }
    @Test void aRecordedAttackWithoutSlowdownIsValidWithTheOtherBranch() {
        Harness h = new Harness(new PhysicsScene()); play(h, moves(8, 12), Set.of(9), Set.of());
        assertEquals(0, h.status().invalid(), h.dump()); assertEquals(h.status().transitions(), h.status().valid(), h.dump());
    }
    @Test void attacksWhileStandingNeverProduceInvalid() {
        Harness h = new Harness(new PhysicsScene()); play(h, idle(40), Set.of(5, 12), Set.of(5, 12));
        assertEquals(0, h.status().invalid(), h.dump());
    }
    @Test void theInboundAttackPayloadBecomesALocalAttackWithItsOwnClocks() {
        var o = new me.nicolas.papea2ac.protocol.Observation(KEY, 21, 777, 0, new me.nicolas.papea2ac.protocol.Observation.Attempt(21, 21, new me.nicolas.papea2ac.protocol.SemanticPayload.Attack(99)));
        var e = (TimingEvent.LocalAttack) me.nicolas.papea2ac.core.TimingObservationMapper.map(o, 42, 0).orElseThrow();
        assertEquals(21, e.sequence()); assertEquals(777, e.nanos()); assertEquals(99, e.targetEntityId());
    }

    // ---- P11-F27 regressions by target class. The client's hurtClient is true for a painting / item frame (BlockAttachedEntity, ItemFrame) and a
    // boat / minecart (VehicleEntity), so a sprint or knockback hit on them slows the attacker. Nobody else is in the scene (no nearby player).
    static final List<String> SLOWING_TARGETS = List.of("minecraft:painting", "minecraft:item_frame", "minecraft:oak_boat", "minecraft:minecart");
    /** Paintings and item frames are not pushers: before P11-F27 a sprint hit on them was a COMPLETE false INVALID (the live path). */
    static boolean nonPusher(String type) { return type.equals("minecraft:painting") || type.equals("minecraft:item_frame"); }

    @Test void aSprintHitOnEachSlowingTargetWithNoPlayerNearbyIsNeverInvalid() {
        for (String type : SLOWING_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type); play(h, moves(8, 12), Set.of(9), Set.of(9));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump());
            if (nonPusher(type)) assertEquals(h.status().transitions(), h.status().valid(), "explained exactly, not by fabrication: " + type + "\n" + h.dump());
            else assertTrue(h.status().valid() + h.status().uncertain() == h.status().transitions(), "a vehicle collider nearby degrades to UNCERTAIN, never INVALID: " + type + "\n" + h.dump());
        }
    }
    @Test void aKnockbackItemHitWhileWalkingOnEachSlowingTargetIsNeverInvalid() {
        // positive knockback without sprinting (ATTACK_KNOCKBACK / knockback enchantment): the motion still drops to 60%
        List<MovementInput> walk = new ArrayList<>(); for (int i = 0; i < 20; i++) walk.add(MovementInput.forward(0f, false));
        for (String type : SLOWING_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type); play(h, walk, Set.of(9), Set.of(9));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump());
            if (nonPusher(type)) assertEquals(h.status().transitions(), h.status().valid(), type + "\n" + h.dump());
        }
    }
    @Test void aServerKnockbackDrainedInTheAttackTickIsNeverInvalid() {
        // external knockback (a server SET velocity drained in the same tick) followed by the attack's slowdown of the post-knockback motion
        for (String type : SLOWING_TARGETS) {
            Harness h = new Harness(new PhysicsScene(), type); play(h, moves(8, 14), Set.of(9), Set.of(9), 9, new Vec3d(0.3, 0.36, -0.2));
            assertEquals(0, h.status().invalid(), type + "\n" + h.dump());
        }
    }
    @Test void withoutTheAttackSignalTheSameSlowdownStaysUnexplainedOnAPaintingSoTheRegressionHasTeeth() {
        // the pre-fix situation: no ATTACK observed, the slowdown happens anyway: no branch explains it (the frozen model's behaviour)
        Harness h = new Harness(new PhysicsScene(), "minecraft:painting"); play(h, moves(8, 12), Set.of(), Set.of(9));
        assertTrue(h.status().invalid() >= 1, h.dump());
    }
}
