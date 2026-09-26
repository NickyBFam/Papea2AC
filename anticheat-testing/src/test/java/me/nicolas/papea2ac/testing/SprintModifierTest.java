package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static me.nicolas.papea2ac.testing.ValidationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.physics.PhysicsState.SprintModifier;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import org.junit.jupiter.api.Test;

/**
 * P9-F1 regression (found by the Phase 9 real-client qualification, originating in the Phase 5 attribute model): the server syncs its own
 * transient {@code minecraft:sprinting} MOVEMENT_SPEED modifier to the client in {@code ClientboundUpdateAttributes}; the client replaces
 * its modifier set with that snapshot and afterwards only removes / adds the modifier at its own sprint transitions. The tick on which a
 * sprinting player stops sprinting but keeps moving (sprint -> strafe, the live seq 235287->235357) therefore runs at plain walking speed
 * while the last synced snapshot still carries the modifier. Before the fix the snapshot's copy made every candidate of that tick 30% too
 * fast (the live residual: nearest {@code -A----} outside by exactly 0.3 x 0.098 = 0.02940, nearest {@code -A-C--} by
 * 0.098 - 0.098 x 1.3 x 0.3 = 0.05978) and a legitimate player produced a complete INVALID and one positive evidence event.
 */
class SprintModifierTest {
    static final float YAW = 0f;
    static final MovementInput STRAFE = new MovementInput(0, 1, false, false, false, false, YAW), STRAFE_SNEAK = new MovementInput(0, 1, false, true, false, false, YAW);
    /** The scene environment with the server's sprint modifier synced into the MOVEMENT_SPEED snapshot (what the client holds while the server sees it sprinting). */
    static PhysicsEnvironment withSyncedSprint(PhysicsScene ps) {
        PhysicsEnvironment env = ps.env();
        PhysicsEnvironment.Attribute speed = env.attributes().get(MovementProfile.ATTR_MOVEMENT_SPEED);
        List<MovementProfile.Modifier> mods = new ArrayList<>(speed.modifiers()); mods.add(PROFILE.sprintModifier());
        return env.withAttributes(env.attributes().with(MovementProfile.ATTR_MOVEMENT_SPEED, PhysicsEnvironment.Attribute.known(speed.base(), PhysicsEnvironment.Attribute.Provenance.SERVER_SENT, mods)));
    }
    static double h(Vec3d v) { return Math.sqrt(v.x() * v.x() + v.z() * v.z()); }

    @Test void theSnapshotsCopyOfTheModifierNeverDrivesTheSpeedTheClassDoes() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsEnvironment plain = ps.env(), synced = withSyncedSprint(ps);
        assertTrue(ps.simulator.snapshotCarriesSprintModifier(synced)); assertFalse(ps.simulator.snapshotCarriesSprintModifier(plain));
        PhysicsResult walk = ps.step(start, STRAFE, plain);                                              // a client that never sprinted: 0.098 per tick
        PhysicsResult stop = ps.step(start, STRAFE, synced);                                             // the P9-F1 tick: FOLLOWS under a snapshot that carries the modifier, input not sprinting
        PhysicsResult stuck = ps.step(start.withSprintModifier(SprintModifier.STUCK_ON), STRAFE, synced); // the client the pre-fix model assumed everywhere: not sprinting, modifier present
        assertEquals(walk.next().position(), stop.next().position(), "the stop tick is a plain walking tick whatever the snapshot carries");
        assertEquals(walk.next().velocity(), stop.next().velocity()); assertEquals(SprintModifier.FOLLOWS, stop.next().sprintModifier());
        double impulse = h(walk.trace().inputVector()), stuckImpulse = h(stuck.trace().inputVector());
        assertEquals(0.098, impulse, 1e-6, "walk impulse 0.98 x 0.1 x 0.216/0.216");
        assertEquals(0.3 * impulse, stuckImpulse - impulse, 1e-6, "the live residual 0.02940 = one sprint modifier's worth of walking impulse");
        // a sneak-scaled tick is one that STARTS crouched (P9-F3, pinned client): the live `-A-C--` candidate came from a client already sneaking
        PhysicsState crouched = start.withPose(me.nicolas.papea2ac.collision.PlayerBox.Pose.CROUCHING);
        PhysicsResult stopSneak = ps.step(crouched, STRAFE_SNEAK, synced), stuckSneak = ps.step(crouched.withSprintModifier(SprintModifier.STUCK_ON), STRAFE_SNEAK, synced);
        assertEquals(impulse - 1.3 * 0.3 * impulse, impulse - h(stuckSneak.trace().inputVector()), 1e-6, "the live sneak residual 0.05978 = the modifier under the sneak factor");
        assertEquals(0.3 * impulse, h(stopSneak.trace().inputVector()), 1e-6);
        // the sprinting input under a plain snapshot adds the modifier (the client adds it locally at the start): identical to a synced snapshot
        assertEquals(ps.step(start, MovementInput.forward(YAW, true), plain).next().position(), ps.step(start, MovementInput.forward(YAW, true), synced).next().position());
    }
    @Test void theClassFollowsTheClientsTransitions() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        // FOLLOWS is absorbing: the modifier is exactly the sprint input, tick after tick
        PhysicsResult r1 = ps.step(s, MovementInput.forward(YAW, true), env), r2 = ps.step(r1.next(), MovementInput.forward(YAW, true), env), r3 = ps.step(r2.next(), MovementInput.forward(YAW, false), env);
        assertEquals(SprintModifier.FOLLOWS, r1.next().sprintModifier()); assertEquals(SprintModifier.FOLLOWS, r2.next().sprintModifier()); assertEquals(SprintModifier.FOLLOWS, r3.next().sprintModifier());
        assertEquals(r1.trace().speed(), r2.trace().speed()); assertTrue(r3.trace().speed() < r2.trace().speed());
        // STUCK_ON: present for every input until the client starts sprinting, then FOLLOWS
        PhysicsResult t1 = ps.step(s.withSprintModifier(SprintModifier.STUCK_ON), STRAFE, env); assertEquals(SprintModifier.STUCK_ON, t1.next().sprintModifier()); assertEquals(r1.trace().speed(), t1.trace().speed(), "30% faster without sprinting");
        PhysicsResult t2 = ps.step(t1.next(), MovementInput.forward(YAW, true), env); assertEquals(SprintModifier.FOLLOWS, t2.next().sprintModifier()); assertEquals(r1.trace().speed(), t2.trace().speed());
        PhysicsResult t3 = ps.step(t2.next(), STRAFE, env); assertEquals(r3.trace().speed(), t3.trace().speed(), "a stop after the start removes it again");
        // STUCK_OFF: absent for every input until the client stops sprinting, then FOLLOWS
        PhysicsResult u1 = ps.step(s.withSprintModifier(SprintModifier.STUCK_OFF), MovementInput.forward(YAW, true), env); assertEquals(SprintModifier.STUCK_OFF, u1.next().sprintModifier()); assertEquals(r3.trace().speed(), u1.trace().speed(), "sprinting without the modifier");
        PhysicsResult u2 = ps.step(u1.next(), MovementInput.forward(YAW, false), env); assertEquals(SprintModifier.FOLLOWS, u2.next().sprintModifier());
        PhysicsResult u3 = ps.step(u2.next(), MovementInput.forward(YAW, true), env); assertEquals(r1.trace().speed(), u3.trace().speed());
        assertEquals(Boolean.TRUE, s.sprintModifierPresent(true)); assertEquals(Boolean.FALSE, s.sprintModifierPresent(false)); assertEquals(Boolean.TRUE, t1.next().sprintModifierPresent(false)); assertEquals(Boolean.FALSE, u1.next().sprintModifierPresent(true));
        // MAYBE classes: one state while the members agree (no horizontal input, or an input on which both resolve alike), split exactly when they differ
        PhysicsState mOn = s.withSprintModifier(SprintModifier.MAYBE_STUCK_ON), mOff = s.withSprintModifier(SprintModifier.MAYBE_STUCK_OFF);
        assertEquals(List.of(mOn), mOn.sprintModifierCases(MovementInput.idle(YAW))); assertEquals(List.of(mOn), mOn.sprintModifierCases(MovementInput.forward(YAW, true)));
        assertEquals(List.of(s, s.withSprintModifier(SprintModifier.STUCK_ON)), mOn.sprintModifierCases(STRAFE));
        assertEquals(List.of(mOff), mOff.sprintModifierCases(STRAFE)); assertEquals(List.of(s, s.withSprintModifier(SprintModifier.STUCK_OFF)), mOff.sprintModifierCases(MovementInput.forward(YAW, true)));
        PhysicsResult m1 = ps.step(mOn, MovementInput.idle(YAW), env); assertTrue(m1.exact()); assertEquals(SprintModifier.MAYBE_STUCK_ON, m1.next().sprintModifier(), "idle keeps the question open");
        PhysicsResult m2 = ps.step(mOn, MovementInput.forward(YAW, true), env); assertTrue(m2.exact()); assertEquals(SprintModifier.FOLLOWS, m2.next().sprintModifier(), "a start settles both members"); assertEquals(r1.trace().speed(), m2.trace().speed());
        PhysicsResult m3 = ps.step(mOn, STRAFE, env); assertNull(m3.next(), "an unsplit MAYBE state with a disagreeing ground input is undecided, never a prediction: " + m3.summary()); assertEquals(PhysicsResult.Status.UNKNOWN_ATTRIBUTE, m3.status());
        PhysicsResult m4 = ps.step(mOn, new MovementInput(0, 0, false, false, true, false, YAW), env); assertEquals(SprintModifier.MAYBE_STUCK_ON, m4.next().sprintModifier(), "idle with the sprint key keeps the question open too: one state for both key values"); assertEquals(m1.next(), m4.next());
        PhysicsResult m5 = ps.step(mOff, STRAFE, env); assertTrue(m5.exact()); assertEquals(SprintModifier.FOLLOWS, m5.next().sprintModifier(), "a walking tick settles MAYBE_STUCK_OFF exactly"); assertEquals(r3.trace().speed(), m5.trace().speed());
        PhysicsState air = s.withOnGround(false).withPosition(s.position().add(0, 1.0, 0)).withSprintModifier(SprintModifier.MAYBE_STUCK_OFF);
        assertEquals(List.of(air), air.sprintModifierCases(MovementInput.forward(YAW, true)), "airborne the speed attribute is never read: no split");
        PhysicsResult m6 = ps.step(air, MovementInput.forward(YAW, true), env); assertTrue(m6.exact(), m6.summary()); assertEquals(SprintModifier.MAYBE_STUCK_OFF, m6.next().sprintModifier());
        assertEquals(SprintModifier.MAYBE_STUCK_ON, SprintModifier.FOLLOWS.afterSnapshot(true)); assertEquals(SprintModifier.MAYBE_STUCK_OFF, SprintModifier.FOLLOWS.afterSnapshot(false));
        assertEquals(SprintModifier.FOLLOWS, SprintModifier.STUCK_OFF.afterSnapshot(true)); assertEquals(SprintModifier.FOLLOWS, SprintModifier.STUCK_ON.afterSnapshot(false));
        assertEquals(SprintModifier.MAYBE_STUCK_ON, SprintModifier.STUCK_ON.afterSnapshot(true)); assertEquals(SprintModifier.MAYBE_STUCK_OFF, SprintModifier.STUCK_OFF.afterSnapshot(false));
        assertEquals(SprintModifier.MAYBE_STUCK_ON, SprintModifier.MAYBE_STUCK_OFF.afterSnapshot(true)); assertEquals(SprintModifier.MAYBE_STUCK_OFF, SprintModifier.MAYBE_STUCK_ON.afterSnapshot(false));
        assertEquals(s, s.withSprintModifier(SprintModifier.FOLLOWS)); assertNotEquals(s, s.withSprintModifier(SprintModifier.STUCK_ON));
        assertFalse(s.toString().contains("sprintModifier"), "the Phase 5 record form is unchanged for FOLLOWS (fixture digests): " + s); assertTrue(t1.next().toString().endsWith("sprintModifier=STUCK_ON]"), t1.next().toString());
    }
    @Test void theAbstractTransferCarriesTheClassAndStaysSound() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5).withVelocity(new Vec3d(0.0, -0.0784000015258789, 0.15));
        PhysicsEnvironment plain = ps.env(), synced = withSyncedSprint(ps);
        for (PhysicsEnvironment env : List.of(plain, synced)) for (MovementInput in : List.of(STRAFE, MovementInput.forward(YAW, true), MovementInput.forward(YAW, false), STRAFE_SNEAK)) {
            AbstractTransfer t = new AbstractTransfer(ps.simulator, env, MovementObservationProfile.PAPER_26_2, 4096);
            for (SprintModifier mode : List.of(SprintModifier.FOLLOWS, SprintModifier.STUCK_ON, SprintModifier.STUCK_OFF)) {
                PhysicsState s = start.withSprintModifier(mode);
                AbstractState.Box box = AbstractState.fromConcrete(s).boxes().get(0); assertTrue(box.mayBe(mode)); assertEquals(1, Integer.bitCount(box.sprintModes()));
                AbstractTransfer.Prepared pre = t.prepare(box, List.of()); assertNotNull(pre);
                List<AbstractState.Box> boxes = t.apply(pre, in); assertNotNull(boxes);
                PhysicsResult r = ps.step(s, in, env); assertTrue(r.exact(), r.summary());
                assertTrue(boxes.stream().anyMatch(b -> b.contains(r.next())), "abstract boxes must contain the concrete step from " + mode + " under " + (env == plain ? "plain" : "synced") + " snapshot with " + InputDomain.describe(in) + ": " + boxes);
                for (AbstractState.Box b : boxes) assertEquals(AbstractState.sprintBit(r.next().sprintModifier()), b.sprintModes(), "the successor class is exact per input");
            }
            // a box holding every class transfers each: the union contains all three concrete successors
            AbstractState.Box any = new AbstractState.Box(Interval.point(start.position().x()), Interval.point(start.position().y()), Interval.point(start.position().z()), Interval.point(0.0), Interval.point(-0.0784000015258789), Interval.point(0.15), AbstractState.GROUND_ON, AbstractState.POSE_STANDING, 0, 0, 7);
            List<AbstractState.Box> boxes = t.apply(t.prepare(any, List.of()), in);
            for (SprintModifier mode : List.of(SprintModifier.FOLLOWS, SprintModifier.STUCK_ON, SprintModifier.STUCK_OFF)) { PhysicsState n = ps.step(start.withSprintModifier(mode), in, env).next(); assertTrue(boxes.stream().anyMatch(b -> b.contains(n)), mode + " " + InputDomain.describe(in)); }
            // a MAYBE state is abstracted as the union of its members: the box holds both bits, and both member successors are covered
            AbstractState.Box maybe = AbstractState.fromConcrete(start.withSprintModifier(SprintModifier.MAYBE_STUCK_ON)).boxes().get(0); assertEquals(AbstractState.SPRINT_FOLLOWS | AbstractState.SPRINT_STUCK_ON, maybe.sprintModes());
            assertTrue(maybe.contains(start.withSprintModifier(SprintModifier.MAYBE_STUCK_ON))); assertTrue(maybe.contains(start)); assertFalse(AbstractState.fromConcrete(start).boxes().get(0).contains(start.withSprintModifier(SprintModifier.MAYBE_STUCK_ON)), "a FOLLOWS box does not cover an open question");
            List<AbstractState.Box> mb = t.apply(t.prepare(maybe, List.of()), in);
            for (PhysicsState c : start.withSprintModifier(SprintModifier.MAYBE_STUCK_ON).sprintModifierCases(in)) { PhysicsResult r = ps.step(c, in, env); assertTrue(r.exact(), r.summary()); assertTrue(mb.stream().anyMatch(b -> b.contains(r.next())), c.sprintModifier() + " " + InputDomain.describe(in)); }
        }
    }
    @Test void aSprintToStrafeTransitionUnderASprintingSnapshotIsValidAndTheStuckReadingIsTheLiveInvalid() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment synced = withSyncedSprint(ps);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 6; i++) inputs.add(MovementInput.forward(YAW, true)); inputs.add(STRAFE); inputs.add(STRAFE);
        Truth truth = simulate(ps, synced, start, inputs, List.of(), new Recorder(), true);
        List<Validated> chain = validateChain(ps, synced, truth);
        assertEquals(8, chain.size(), describe(chain));
        for (Validated v : chain) assertEquals(Classification.VALID, v.result().classification(), describe(chain));
        for (Validated v : chain) assertTrue(v.result().next().nodes().stream().anyMatch(n -> n.state().equals(v.truthAfter())), "the true state is carried: " + describe(chain));
        // the same strafe tick from the class the pre-fix model effectively assumed under a synced snapshot (modifier present while not sprinting): the live INVALID with its residual
        PhysicsState beforeStrafe = truth.ticks().get(5).after(); assertEquals(SprintModifier.FOLLOWS, beforeStrafe.sprintModifier());
        Truth strafeOnly = simulate(ps, synced, beforeStrafe, List.of(STRAFE), List.of(), new Recorder(), true);
        List<Validated> stuck = walk(synced, beforeStrafe.withSprintModifier(SprintModifier.STUCK_ON), strafeOnly.recorder(), YAW, m -> null, validator(ps), TimingBudget.DEFAULT, null, Integer.MAX_VALUE);
        assertEquals(1, stuck.size()); MovementValidationResult r = stuck.get(0).result();
        assertEquals(Classification.INVALID, r.classification(), describe(stuck));
        assertTrue(r.completeness().all()); assertEquals(0, r.proof().candidatesRemaining(), describe(stuck)); assertEquals(r.proof().rawEnumerated(), r.proof().excludedExact() + r.proof().excludedEnvelope(), describe(stuck));
        // the same tick from the open question (flag unknown after a snapshot that carries the modifier): the FOLLOWS member explains it, VALID, and the answer is settled
        List<Validated> maybe = walk(synced, beforeStrafe.withSprintModifier(SprintModifier.MAYBE_STUCK_ON), strafeOnly.recorder(), YAW, m -> null, validator(ps), TimingBudget.DEFAULT, null, Integer.MAX_VALUE);
        assertEquals(Classification.VALID, maybe.get(0).result().classification(), describe(maybe));
        assertTrue(maybe.get(0).result().next().nodes().stream().allMatch(n -> n.state().sprintModifier() == SprintModifier.FOLLOWS), describe(maybe));
        List<Validated> fixed = walk(synced, beforeStrafe, strafeOnly.recorder(), YAW, m -> null, validator(ps), TimingBudget.DEFAULT, null, Integer.MAX_VALUE);
        assertEquals(Classification.VALID, fixed.get(0).result().classification(), describe(fixed));
    }
}
