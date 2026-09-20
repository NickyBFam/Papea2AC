package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.STONE;
import static me.nicolas.papea2ac.testing.WorldTestSupport.AIR;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.physics.PhysicsEnvironment.*;
import me.nicolas.papea2ac.physics.PhysicsResult.Reason.Code;
import me.nicolas.papea2ac.physics.PhysicsResult.Status;
import me.nicolas.papea2ac.version.MovementProfile;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 core matrix (PHASE_5_IMPLEMENTATION.md "ACCEPTANCE MATRIX"): one focused test per movement rule, uncertainty
 * class and bound, on the Phase 3/4 baseline world (surface y = 70). Exact vanilla numbers are asserted where the rule
 * fixes them (0.098 first-step, 0.42 jump, -0.0784 resting fall, 1.2522 apex...); the bit-level truth for whole
 * trajectories is the native differential and its pinned trajectory digests ({@link PhysicsOracleFixtureTest}).
 */
class PhysicsCoreMatrixTest {
    static final float YAW = 0f;
    static final MovementInput IDLE = MovementInput.idle(YAW), W = MovementInput.forward(YAW, false), W_SPRINT = MovementInput.forward(YAW, true),
        JUMP = new MovementInput(0, 0, true, false, false, false, YAW), W_JUMP = new MovementInput(1, 0, true, false, false, false, YAW), W_SHIFT = new MovementInput(1, 0, false, true, false, false, YAW);

    // ---- ground / air basics
    @Test void restingPlayerKeepsPositionAndRestingVelocity() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult r = ps.step(s, IDLE);
        assertEquals(Status.EXACT, r.status()); assertEquals(new Vec3d(8.5, 70.0, 8.5), r.next().position()); assertEquals(-0.0784000015258789, r.next().velocity().y()); assertTrue(r.next().onGround());
        assertEquals(PhysicsState.Support.at(8, 69, 8), r.next().support());
    }
    @Test void firstWalkingTickMovesExactly0_098() {
        PhysicsScene ps = new PhysicsScene(); PhysicsResult r = ps.step(settled(ps, 8.5, SURFACE, 8.5), W);
        assertEquals(Status.EXACT, r.status()); assertEquals(8.5 + 0.09800000336766246, r.next().position().z()); assertEquals(0.1f, r.trace().speed()); assertEquals(0.6f, r.trace().friction());
    }
    @Test void walkingConvergesToVanillaTerminalSpeed() {
        PhysicsScene ps = new PhysicsScene(); List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), W, 60);
        double v = run.get(59).next().velocity().z(); assertTrue(Math.abs(v - 0.11785) < 1e-4, "post-friction velocity " + v); // 0.2158 per tick before friction
        assertTrue(Math.abs(run.get(59).next().position().z() - run.get(58).next().position().z() - 0.21585) < 1e-4);
    }
    @Test void sprintingIsThirtyPercentFaster() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        double walk = ps.step(s, W).trace().inputVector().z(), sprint = ps.step(s, W_SPRINT).trace().inputVector().z();
        assertEquals(walk * 1.3, sprint, 1e-8); assertEquals(0.13f, ps.step(s, W_SPRINT).trace().speed(), 1e-7f);
    }
    @Test void yawRotatesTheInputBasisThroughTheSineTable() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        Vec3d east = ps.step(s, MovementInput.forward(-90f, false)).trace().inputVector(), west = ps.step(s, MovementInput.forward(90f, false)).trace().inputVector();
        assertEquals(0.09800000336766246, east.x(), 1e-9); assertEquals(-0.09800000336766246, west.x(), 1e-9); assertEquals(0.0, Math.abs(east.z()), 1e-8);
    }
    @Test void diagonalInputIsNormalized() {
        PhysicsScene ps = new PhysicsScene(); Vec3d v = ps.step(settled(ps, 8.5, SURFACE, 8.5), new MovementInput(1, 1, false, false, false, false, YAW)).trace().inputVector();
        assertEquals(0.1, Math.sqrt(v.x() * v.x() + v.z() * v.z()), 1e-6, "a diagonal key pair is normalized to length 1 before the speed scale (the 0.98 input factor vanishes)"); assertTrue(v.x() > 0 && v.z() > 0); // strafe +1 = left; facing +z (yaw 0) the left hand points to +x
    }
    @Test void usingAnItemScalesInputByOneFifth() {
        PhysicsScene ps = new PhysicsScene(); Vec3d v = ps.step(settled(ps, 8.5, SURFACE, 8.5), new MovementInput(1, 0, false, false, false, true, YAW)).trace().inputVector();
        assertEquals(0.098 * 0.2, v.z(), 1e-6);
    }
    @Test void sneakingScalesInputBySneakingSpeedAndCrouches() {
        PhysicsScene ps = new PhysicsScene(); PhysicsResult r = ps.step(settled(ps, 8.5, SURFACE, 8.5), W_SHIFT);
        assertEquals(0.098 * 0.3, r.trace().inputVector().z(), 1e-6); assertTrue(r.trace().crouchingInput()); assertEquals(PlayerBox.Pose.CROUCHING, r.next().pose());
    }
    @Test void smallVelocitiesAreZeroedBeforeMovement() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5).withVelocity(new Vec3d(0.002, -0.0784, 0.002));
        PhysicsResult r = ps.step(s, IDLE); assertEquals(0.0, r.trace().velocityBeforeMove().x()); assertEquals(8.5, r.next().position().x());
    }
    // ---- gravity, jump, fall
    @Test void jumpImpulseIs0_42AndApexIs1_2522() {
        PhysicsScene ps = new PhysicsScene(); List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), JUMP, 14);
        assertEquals(SURFACE + (double) 0.42f, run.get(0).next().position().y()); assertEquals(((double) 0.42f - 0.08) * (double) 0.98f, run.get(0).next().velocity().y(), 0.0); assertTrue(run.get(0).trace().jumped());
        double apex = 0; for (PhysicsResult r : run) apex = Math.max(apex, r.next().position().y() - SURFACE); assertEquals(1.2522, apex, 1e-4);
        assertTrue(run.get(11).next().onGround(), "lands on tick 12"); assertEquals(0.0, run.get(11).next().fallDistance());
    }
    @Test void holdingJumpRejumpsOnlyAfterLandingAndTheTenTickDelay() {
        PhysicsScene ps = new PhysicsScene(); List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), JUMP, 14);
        assertEquals(10, run.get(0).next().noJumpDelay()); assertEquals(0, run.get(10).next().noJumpDelay()); assertFalse(run.get(1).trace().jumped()); assertTrue(run.get(12).trace().jumped());
    }
    @Test void sprintJumpAddsTheForwardImpulse() {
        PhysicsScene ps = new PhysicsScene(); PhysicsResult r = ps.step(settled(ps, 8.5, SURFACE, 8.5), new MovementInput(1, 0, true, false, true, false, YAW));
        assertTrue(r.trace().velocityBeforeMove().z() > 0.2, "sprint-jump impulse 0.2 plus input: " + r.trace().velocityBeforeMove());
    }
    @Test void jumpBoostRaisesTheJump() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_JUMP_BOOST, 1)));
        PhysicsResult r = ps.step(settled(ps, 8.5, SURFACE, 8.5), JUMP, env); assertEquals(Status.EXACT, r.status()); assertEquals(0.42f + 0.2f, r.next().position().y() - SURFACE, 1e-6);
    }
    @Test void airborneVelocityFollowsGravityAndDrag() {
        PhysicsScene ps = new PhysicsScene(); PhysicsResult r = ps.step(airborneAt(8.5, 80, 8.5).withVelocity(new Vec3d(0, -0.5, 0)), IDLE);
        assertEquals((-0.5 - 0.08) * 0.98f, r.next().velocity().y(), 1e-12); assertEquals(79.5, r.next().position().y()); assertFalse(r.next().onGround()); assertEquals(0.5f, r.next().fallDistance(), 1e-7);
    }
    @Test void slowFallingCapsGravityAndResetsFallDistance() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_SLOW_FALLING, 0)));
        PhysicsResult r = ps.step(airborneAt(8.5, 80, 8.5).withVelocity(new Vec3d(0, -0.5, 0)).withFallDistance(3), IDLE, env);
        assertEquals((-0.5 - 0.01) * 0.98f, r.next().velocity().y(), 1e-12); assertEquals(0.5f, r.next().fallDistance(), 1e-7, "fall distance restarts from zero each tick under slow falling");
    }
    @Test void levitationPullsVerticalVelocityTowardItsTarget() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_LEVITATION, 0)));
        PhysicsResult r = ps.step(settled(ps, 8.5, SURFACE, 8.5).withVelocity(Vec3d.ZERO), IDLE, env); assertEquals((0.05 - 0.0) * 0.2 * 0.98f, r.next().velocity().y(), 1e-12);
    }
    @Test void fallIsBoundedByTerminalVelocity() {
        PhysicsScene ps = new PhysicsScene(); List<PhysicsResult> run = ps.run(airborneAt(8.5, 1500, 8.5), IDLE, 300);
        assertTrue(run.get(299).next().velocity().y() > -3.92 && run.get(299).next().velocity().y() < -3.9, "terminal " + run.get(299).next().velocity().y()); assertTrue(run.get(299).next().fallDistance() > 900); assertTrue(run.get(299).next().position().y() > SURFACE);
    }
    // ---- collision, step, contact
    @Test void wallStopsHorizontalMotionAndFlagsIt() {
        PhysicsScene ps = new PhysicsScene(); for (int y = 70; y <= 72; y++) for (int x = 7; x <= 9; x++) ps.set(x, y, 10, STONE);
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), W, 20, ps.env(), false);
        PhysicsState last = run.get(19).next(); assertEquals(10.0 - (double) 0.3f, last.position().z(), 0.0); assertTrue(last.horizontalCollision()); assertEquals(0.0, Math.abs(last.velocity().z()));
    }
    @Test void ceilingStopsTheJump() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) ps.set(x, 72, z, STONE);
        PhysicsResult r = ps.step(settled(ps, 8.5, SURFACE, 8.5), JUMP); assertEquals(72.0 - (double) 1.8f, r.next().position().y()); assertTrue(r.next().verticalCollision()); assertFalse(r.next().verticalCollisionBelow()); assertTrue(r.next().velocity().y() <= 0.0);
    }
    @Test void aFullBlockIsNotSteppedWithTheDefaultStepHeight() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 10, STONE); ps.set(8, 70, 11, STONE);
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), W, 25, ps.env(), false);
        assertEquals(70.0, run.get(24).next().position().y(), "step height 0.6 < 1.0: blocked (vanilla players jump or auto-jump)"); assertTrue(run.get(24).next().horizontalCollision()); assertFalse(run.stream().anyMatch(r -> r.trace().steppedUp()));
        assertEquals(10.0 - (double) 0.3f, run.get(24).next().position().z());
    }
    @Test void stepsOntoSlabAndStairsButNotOverAFence() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 10, SLAB_BOTTOM);
        List<PhysicsResult> slab = ps.run(settled(ps, 8.5, SURFACE, 8.5), W, 9, ps.env(), false); assertEquals(70.5, slab.get(8).next().position().y()); assertTrue(slab.get(6).trace().steppedUp());
        PhysicsScene ps2 = new PhysicsScene(); ps2.set(8, 70, 10, BlockStateKey.parse("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")); ps2.set(8, 70, 11, STONE);
        assertEquals(71.0, ps2.run(settled(ps2, 8.5, SURFACE, 8.5), W, 14, ps2.env(), false).get(13).next().position().y());
        PhysicsScene ps3 = new PhysicsScene(); ps3.set(8, 70, 10, FENCE_POST);
        assertEquals(70.0, ps3.run(settled(ps3, 8.5, SURFACE, 8.5), W, 25, ps3.env(), false).get(24).next().position().y());
    }
    @Test void stepHeightAttributeAllowsHigherSteps() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 10, STONE); ps.set(8, 70, 11, STONE);
        PhysicsEnvironment env = ps.env().withAttributes(Attributes.defaults(PhysicsTestSupport.PROFILE).withBase(MovementProfile.ATTR_STEP_HEIGHT, 1.25));
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), W, 12, env, false);
        assertEquals(71.0, run.get(11).next().position().y()); assertTrue(run.stream().anyMatch(r -> r.trace().steppedUp()));
    }
    @Test void walkingOffAnEdgeStartsAFallAndClearsSupport() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 12; z < 16; z++) { ps.set(x, 69, z, AIR); ps.set(x, 68, z, AIR); }
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 10.5), W, 12, ps.env(), false);
        PhysicsState last = run.get(11).next(); assertFalse(last.onGround()); assertTrue(last.position().y() < SURFACE); assertFalse(last.support().present());
    }
    @Test void sneakingBacksOffTheEdge() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 12; z < 16; z++) { ps.set(x, 69, z, AIR); ps.set(x, 68, z, AIR); }
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 10.5), W_SHIFT, 40, ps.env(), false);
        PhysicsState last = run.get(39).next(); assertTrue(last.onGround()); assertEquals(SURFACE, last.position().y()); assertTrue(last.position().z() <= 12.3 + 1e-9, "stops with the box edge over the platform: " + last.position().z());
    }
    @Test void crouchesUnderALowCeilingAndCrawlsInAOneBlockGap() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 7; x <= 9; x++) ps.set(x, 71, 10, SLAB_TOP); ps.set(8, 72, 10, STONE);
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 9.0), W_SHIFT, 20, ps.env(), false);
        assertEquals(PlayerBox.Pose.CROUCHING, run.get(19).next().pose()); assertTrue(run.get(19).next().position().z() > 10.0);
        PhysicsScene ps2 = new PhysicsScene(); for (int x = 7; x <= 9; x++) { ps2.set(x, 71, 10, STONE); ps2.set(x, 72, 10, STONE); }
        PhysicsResult r = ps2.step(settled(ps2, 8.5, SURFACE, 10.5).withPose(PlayerBox.Pose.SWIMMING), W);
        assertEquals(PlayerBox.Pose.SWIMMING, r.next().pose(), "cannot stand or crouch: stays crawling"); assertEquals(0.098 * 0.3, r.trace().inputVector().z(), 1e-6, "crawling moves slowly");
    }
    // ---- friction, factors, bounce, stuck
    @Test void iceKeepsSlidingLongerThanStone() {
        PhysicsScene ice = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) ice.set(x, 69, z, ICE);
        PhysicsScene stone = new PhysicsScene();
        PhysicsState s1 = ice.run(settled(ice, 8.5, SURFACE, 2.5), W, 10, ice.env(), false).get(9).next(), s2 = stone.run(settled(stone, 8.5, SURFACE, 2.5), W, 10, stone.env(), false).get(9).next();
        PhysicsState a = ice.run(s1, IDLE, 10, ice.env(), false).get(9).next(), b = stone.run(s2, IDLE, 10, stone.env(), false).get(9).next();
        assertTrue(a.velocity().z() > 10 * b.velocity().z(), "ice " + a.velocity().z() + " vs stone " + b.velocity().z());
        assertEquals(0.98f, ice.step(s1, W).trace().friction()); assertEquals(0.6f, stone.step(s2, W).trace().friction());
        PhysicsScene blue = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) blue.set(x, 69, z, BLUE_ICE); assertEquals(0.989f, blue.step(settled(blue, 8.5, SURFACE, 8.5), W).trace().friction());
    }
    @Test void slimeSlowsWalkersAndBouncesFallers() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) ps.set(x, 69, z, SLIME);
        PhysicsResult walk = ps.step(settled(ps, 8.5, SURFACE, 8.5), W); assertTrue(walk.next().velocity().z() < 0.04, "stepOn slowdown: " + walk.next().velocity().z());
        List<PhysicsResult> fall = ps.run(airborneAt(8.5, 78, 8.5), IDLE, 40, ps.env(), false);
        boolean bounced = false; for (PhysicsResult r : fall) if (r.next().velocity().y() > 0.3) bounced = true; assertTrue(bounced, "bounce restitution 1.0");
        List<PhysicsResult> sneak = ps.run(airborneAt(8.5, 78, 8.5), new MovementInput(0, 0, false, true, false, false, YAW), 40, ps.env(), false);
        for (PhysicsResult r : sneak) assertTrue(r.next().velocity().y() <= 0.0, "shift suppresses the bounce");
    }
    @Test void bedsBounceWithRestitution0_75() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 8, BED_HEAD); ps.set(8, 70, 9, BED_FOOT);
        List<PhysicsResult> fall = ps.run(airborneAt(8.5, 78, 8.5), IDLE, 30, ps.env(), false);
        double best = 0; for (PhysicsResult r : fall) best = Math.max(best, r.next().velocity().y()); assertTrue(best > 0.4 && best < 0.9, "bed bounce " + best);
    }
    @Test void honeySlowsAndHalvesJumpsAndSlidesDownWalls() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) ps.set(x, 69, z, HONEY);
        assertEquals(0.09800000336766246 * (double) 0.4f * (double) (0.6f * 0.91f), ps.step(settled(ps, 8.5, SURFACE, 8.5), W).next().velocity().z(), 0.0, "speed factor 0.4 applies to the post-move velocity");
        PhysicsState onHoney = settled(ps, 8.5, 69.9375, 8.5); // honey is 15/16 high
        assertEquals((double) (0.42f * 0.5f), ps.step(onHoney, JUMP).next().position().y() - onHoney.position().y(), 1e-12, "jump factor 0.5");
        PhysicsScene wall = new PhysicsScene(); for (int y = 70; y <= 76; y++) for (int x = 7; x <= 9; x++) wall.set(x, y, 10, HONEY);
        List<PhysicsResult> slide = wall.run(airborneAt(8.5, 76, 9.35).withVelocity(new Vec3d(0, 0, 0.05)), W, 15, wall.env(), false);
        assertEquals((-0.05 - 0.08) * 0.9800000190734863, slide.get(14).next().velocity().y(), 1e-12, "HoneyBlock.doSlideMovement target");
    }
    @Test void soulSandAppliesTheSpeedFactorToTheNextTick() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) ps.set(x, 69, z, SOUL_SAND);
        PhysicsResult r = ps.step(settled(ps, 8.5, 69.875, 8.5), W); assertEquals(0.09800000336766246 * (double) 0.4f * (double) (0.6f * 0.91f), r.next().velocity().z(), 0.0, "soul sand is 0.875 high; the factor applies to the post-move velocity");
    }
    @Test void cobwebSetsTheStuckMultiplierAndTheNextMoveIsCrushed() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5); ps.set(8, 70, 8, COBWEB); ps.set(8, 71, 8, COBWEB);
        PhysicsResult r = ps.step(start, W); assertEquals(new Vec3d(0.25, 0.05000000074505806, 0.25), r.next().stuck()); assertEquals(0.0, r.next().fallDistance());
        PhysicsResult r2 = ps.step(r.next(), W); assertEquals((r.next().velocity().z() + r2.trace().inputVector().z()) * 0.25, r2.trace().movementRequested().z(), 1e-15, "the stuck multiplier crushes the next move");
        PhysicsResult out = ps.step(r.next().withPosition(new Vec3d(8.5, SURFACE, 12.5)).withSupport(PhysicsState.Support.at(8, 69, 12)), W); assertEquals(Vec3d.ZERO, out.next().stuck(), "consumed once when no web is touched again");
        PhysicsEnvironment weaving = ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_WEAVING, 0)));
        assertEquals(new Vec3d(0.5, 0.25, 0.5), ps.step(start, W, weaving).next().stuck());
    }
    @Test void berryBushAndPowderSnowStuckMultipliers() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5); ps.set(8, 70, 8, BERRY_BUSH);
        assertEquals(new Vec3d(0.800000011920929, 0.75, 0.800000011920929), ps.step(start, W).next().stuck());
        PhysicsScene snow = new PhysicsScene(); snow.set(8, 70, 8, POWDER_SNOW); snow.set(8, 71, 8, POWDER_SNOW);
        PhysicsResult r = snow.step(airborneAt(8.5, 71.5, 8.5).withVelocity(new Vec3d(0, -0.3, 0)), IDLE);
        assertEquals(Status.EXACT, r.status()); assertEquals(new Vec3d(0.8999999761581421, 1.5, 0.8999999761581421), r.next().stuck()); assertTrue(r.next().inPowderSnow());
    }
    @Test void powderSnowNeedsKnownBootsToBeWalkable() {
        PhysicsScene snow = new PhysicsScene(); snow.set(8, 70, 9, POWDER_SNOW);
        PhysicsScene top = new PhysicsScene(); top.set(8, 70, 8, POWDER_SNOW);
        PhysicsResult boots = top.step(airborneAt(8.5, 71.0, 8.5).withVelocity(new Vec3d(0, -0.0784, 0)), IDLE, top.env().withEquipment(Equipment.LEATHER_BOOTS)); assertEquals(Status.EXACT, boots.status()); assertTrue(boots.next().onGround(), "boots stand on powder snow from above: " + boots.summary()); assertEquals(71.0, boots.next().position().y());
        PhysicsResult none = top.step(airborneAt(8.5, 71.0, 8.5).withVelocity(new Vec3d(0, -0.0784, 0)), IDLE); assertEquals(Status.EXACT, none.status()); assertFalse(none.next().onGround(), "no boots: sinks in"); assertTrue(none.next().inPowderSnow());
        PhysicsResult side = snow.step(settled(snow, 8.5, SURFACE, 8.5), W, snow.env().withEquipment(Equipment.LEATHER_BOOTS)); assertEquals(Status.EXACT, side.status()); assertFalse(side.next().horizontalCollision(), "PowderSnowBlock.getCollisionShape: solid only when the entity is above it");
        PhysicsResult unknown = snow.step(settled(snow, 8.5, SURFACE, 8.5), W, snow.env().withEquipment(Equipment.UNKNOWN)); assertEquals(Status.UNKNOWN_EQUIPMENT, unknown.status());
    }
    @Test void laddersClimbAndScaffoldingDescends() {
        PhysicsScene ps = new PhysicsScene(); for (int y = 70; y <= 75; y++) ps.set(8, y, 10, STONE); for (int y = 70; y <= 74; y++) ps.set(8, y, 9, LADDER);
        List<PhysicsResult> run = ps.run(settled(ps, 8.5, SURFACE, 8.5), W, 20, ps.env(), false);
        assertTrue(run.get(19).next().position().y() > 71.0, "climbed: " + run.get(19).next().position().y()); assertEquals((0.2 - 0.08) * (double) 0.98f, run.get(19).next().velocity().y(), 0.0, "climb-up 0.2 then gravity and vertical drag");
        PhysicsResult hold = ps.step(run.get(19).next().withVelocity(new Vec3d(0, -0.3, 0)), new MovementInput(0, 0, false, true, false, false, YAW)); assertEquals(0.0, hold.trace().movementRequested().y(), "shift holds a descending player on the ladder");
        PhysicsResult slide = ps.step(run.get(19).next().withVelocity(new Vec3d(0, -0.3, 0)), IDLE); assertEquals(-0.15000000596046448, slide.trace().movementRequested().y(), "ladder descent is clamped to -0.15F");
        PhysicsScene sc = new PhysicsScene(); for (int y = 70; y <= 72; y++) sc.set(8, y, 8, SCAFFOLDING);
        PhysicsResult down = sc.step(new PhysicsState(new Vec3d(8.5, 73, 8.5), new Vec3d(0, -0.0784000015258789, 0), PlayerBox.Pose.STANDING, true, false, false, false, 0, Vec3d.ZERO, false, PhysicsState.Support.at(8, 72, 8), 0, PhysicsState.Locomotion.WALKING), new MovementInput(0, 0, false, true, false, false, YAW));
        assertEquals(Status.EXACT, down.status()); assertTrue(down.next().position().y() < 73.0, "sneaking through scaffolding: " + down.summary());
    }
    // ---- attributes, effects, external motion, entities, border
    @Test void attributesDriveSpeedGravityAndJump() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        assertEquals(0.196, ps.step(s, W, ps.env().withAttributes(Attributes.defaults(PhysicsTestSupport.PROFILE).withBase(MovementProfile.ATTR_MOVEMENT_SPEED, 0.2))).trace().inputVector().z(), 1e-6);
        assertEquals((0.42f - 0.04) * 0.98f, ps.step(s, JUMP, ps.env().withAttributes(Attributes.defaults(PhysicsTestSupport.PROFILE).withBase(MovementProfile.ATTR_GRAVITY, 0.04))).next().velocity().y(), 1e-12);
        assertEquals(0.6f, ps.step(s, JUMP, ps.env().withAttributes(Attributes.defaults(PhysicsTestSupport.PROFILE).withBase(MovementProfile.ATTR_JUMP_STRENGTH, 0.6))).next().position().y() - SURFACE, 1e-9);
    }
    @Test void speedAndSlownessEffectsChangeTheSpeedAttribute() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        assertEquals(0.1 * 1.4 * 0.98, ps.step(s, W, ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_SPEED, 1)))).trace().inputVector().z(), 1e-6);
        assertEquals(0.1 * 0.85 * 0.98, ps.step(s, W, ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_SLOWNESS, 0)))).trace().inputVector().z(), 1e-6);
        assertEquals(0.1 * 1.2 * 1.3 * 0.98, ps.step(s, W_SPRINT, ps.env().withEffects(Effects.of(new EffectInstance(MovementProfile.EFFECT_SPEED, 0)))).trace().inputVector().z(), 1e-6);
    }
    @Test void unknownAttributeAbortsAndUnknownEffectDegrades() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult r = ps.step(s, W, ps.env().withAttributes(Attributes.ALL_UNKNOWN)); assertEquals(Status.UNKNOWN_ATTRIBUTE, r.status()); assertNull(r.next());
        PhysicsResult e = ps.step(s, W, ps.env().withEffects(Effects.of(new EffectInstance("minecraft:not_an_effect", 0)))); assertEquals(Status.UNKNOWN_EFFECT, e.status()); assertNotNull(e.next());
        assertEquals(Status.UNKNOWN_EFFECT, ps.step(s, W, ps.env().withEffects(Effects.UNKNOWN)).status());
    }
    @Test void unresolvedExternalMotionIsNeverExact() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult r = ps.step(s, W, ps.env().withExternalMotions(List.of(ExternalMotion.unresolved("plugin-declared-unknown-motion#7"))));
        assertEquals(Status.UNRESOLVED_EXTERNAL_MOTION, r.status()); assertNotNull(r.next()); assertTrue(r.hasReason(Code.EXTERNAL_MOTION_UNRESOLVED));
    }
    @Test void externalMotionSetReplacesAndAddAdds() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = airborneAt(8.5, 80, 8.5).withVelocity(new Vec3d(0.1, 0, 0.1));
        PhysicsResult set = ps.step(s, IDLE, ps.env().withExternalMotions(List.of(new ExternalMotion(ExternalMotion.Operation.SET, new Vec3d(0.5, 0.2, 0), "velocity#1"))));
        assertEquals(new Vec3d(0.5, 0.2, 0), set.trace().velocityBeforeMove());
        PhysicsResult add = ps.step(s, IDLE, ps.env().withExternalMotions(List.of(new ExternalMotion(ExternalMotion.Operation.ADD, new Vec3d(0.5, 0.2, 0), "explosion#1"))));
        assertEquals(new Vec3d(0.6, 0.2, 0.1), add.trace().velocityBeforeMove()); assertEquals(Status.EXACT, add.status());
    }
    @Test void entityCollidersBlockAndUnknownNeighbourhoodDegrades() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult boat = ps.step(s, W, ps.env().withEntities(new EntityNeighborhood(Knowledge.KNOWN, List.of(new Aabb(7.5, 70, 8.85, 9.5, 70.6, 10.3)), List.of())));
        assertEquals(Status.EXACT, boat.status()); assertTrue(boat.next().horizontalCollision() || boat.trace().steppedUp());
        assertEquals(Status.UNKNOWN_ENTITY_NEIGHBORHOOD, ps.step(s, W, ps.env().withEntities(EntityNeighborhood.UNKNOWN)).status());
    }
    @Test void nearbyPlayersPushAndTwoPushersAreOrderAmbiguous() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        Pusher p1 = new Pusher(8.2, 8.5, new Aabb(7.9, 70, 8.2, 8.5, 71.8, 8.8), true);
        PhysicsResult one = ps.step(s, IDLE, ps.env().withEntities(new EntityNeighborhood(Knowledge.KNOWN, List.of(), List.of(p1))));
        assertEquals(Status.EXACT, one.status()); assertEquals(0.3 / Math.sqrt(0.3) * 0.05000000074505806, one.next().velocity().x(), 1e-12, "Entity.push: dx/sqrt(d) * min(1, 1/sqrt(d)) * 0.05F");
        PhysicsResult two = ps.step(s, IDLE, ps.env().withEntities(new EntityNeighborhood(Knowledge.KNOWN, List.of(), List.of(p1, new Pusher(8.5, 8.2, new Aabb(8.2, 70, 7.9, 8.8, 71.8, 8.5), true)))));
        assertEquals(Status.UNKNOWN_ENTITY_NEIGHBORHOOD, two.status()); assertTrue(two.hasReason(Code.PUSH_ORDER_AMBIGUOUS));
    }
    @Test void worldBorderUnknownDegradesAndProximityIsUnsupported() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        assertEquals(Status.UNKNOWN_WORLD_BORDER, ps.step(s, W, ps.env().withBorder(WorldBorder.UNKNOWN)).status());
        assertEquals(Status.UNSUPPORTED_BORDER, ps.step(s, W, ps.env().withBorder(WorldBorder.of(8.5, 8.5, 4.0))).status());
        assertEquals(Status.EXACT, ps.step(s, W, ps.env().withBorder(WorldBorder.of(0, 0, 100.0))).status());
    }
    // ---- world coverage, fluids, gates
    @Test void unknownCellsAreNeverAir() {
        CollisionTestSupport.Scene scene = new CollisionTestSupport.Scene(); scene.baseline(0, 0); // chunk (0,0) only: z >= 16 unknown
        PhysicsScene ps = new PhysicsScene(scene);
        PhysicsResult r = ps.step(standingAt(8.5, SURFACE, 15.6), W); assertEquals(Status.INCOMPLETE_WORLD, r.status()); assertNotNull(r.next()); assertTrue(r.hasReason(Code.COLLISION_INCOMPLETE) || r.hasReason(Code.CELL_UNKNOWN));
        PhysicsResult far = ps.step(standingAt(8.5, SURFACE, 40.5), W); assertNotEquals(Status.EXACT, far.status());
    }
    @Test void fluidsAreDetectedAndExplicitlyDeferred() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 69, 8, WATER); ps.set(8, 70, 8, WATER);
        PhysicsResult r = ps.step(standingAt(8.5, SURFACE, 8.5), W); assertEquals(Status.UNSUPPORTED_FLUID, r.status()); assertNull(r.next()); assertTrue(r.hasReason(Code.FLUID_CONTACT));
        PhysicsScene slab = new PhysicsScene(); slab.set(8, 70, 8, WATERLOGGED_SLAB);
        assertEquals(Status.UNSUPPORTED_FLUID, slab.step(standingAt(8.5, 70.5, 8.5), IDLE).status(), "waterlogged blocks carry water");
        PhysicsScene lava = new PhysicsScene(); lava.set(8, 70, 8, LAVA); assertEquals(Status.UNSUPPORTED_FLUID, lava.step(standingAt(8.5, SURFACE, 8.5), IDLE).status());
        PhysicsScene dry = new PhysicsScene(); dry.set(8, 70, 10, WATER); assertEquals(Status.EXACT, dry.step(settled(dry, 8.5, SURFACE, 8.5), IDLE).status(), "water two blocks away is not contact");
    }
    @Test void nonFiniteAndOutOfRangeInputsAreRejected() {
        PhysicsScene ps = new PhysicsScene();
        assertEquals(Status.NUMERIC_INVALID, ps.step(standingAt(8.5, SURFACE, 8.5).withVelocity(new Vec3d(Double.NaN, 0, 0)), IDLE).status());
        assertEquals(Status.NUMERIC_INVALID, ps.step(standingAt(Double.POSITIVE_INFINITY, SURFACE, 8.5), IDLE).status());
        assertEquals(Status.COORDINATE_LIMIT, ps.step(standingAt(3.3e7, SURFACE, 8.5), IDLE).status());
        assertThrows(IllegalArgumentException.class, () -> new MovementInput(2, 0, false, false, false, false, 0f));
        assertThrows(IllegalArgumentException.class, () -> new MovementInput(0, 0, false, false, false, false, Float.NaN));
    }
    @Test void unsupportedLocomotionAndPosesAreExplicit() {
        PhysicsScene ps = new PhysicsScene();
        assertEquals(Status.UNSUPPORTED_MODE, ps.step(standingAt(8.5, SURFACE, 8.5).withLocomotion(PhysicsState.Locomotion.ELYTRA), IDLE).status());
        assertEquals(Status.UNSUPPORTED_MODE, ps.step(standingAt(8.5, SURFACE, 8.5).withLocomotion(PhysicsState.Locomotion.CREATIVE_FLYING), IDLE).status());
        assertEquals(Status.UNSUPPORTED_POSE, ps.step(standingAt(8.5, SURFACE, 8.5).withPose(PlayerBox.Pose.FALL_FLYING), IDLE).status());
    }
    @Test void wrongWorldEpochIsUnavailable() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsEnvironment wrong = env.withView(env.view(), env.worldEpoch() + 1);
        PhysicsResult r = ps.step(standingAt(8.5, SURFACE, 8.5), IDLE, wrong); assertEquals(Status.UNAVAILABLE_WORLD, r.status()); assertNull(r.next());
    }
    @Test void queryBudgetsAreEnforcedAndNormalStepsAreCheap() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult r = ps.step(s, W); assertEquals(Status.EXACT, r.status()); assertTrue(r.work().queries() <= 8, "queries " + r.work().queries()); assertTrue(r.work().cells() <= 400, "cells " + r.work().cells()); assertTrue(r.work().insideCells() <= 8);
        PhysicsResult limited = ps.step(s, W, ps.env().withBudget(new Budget(2, 100000, 512, 2048))); assertEquals(Status.QUERY_LIMIT, limited.status()); assertTrue(limited.hasReason(Code.QUERY_BUDGET));
        PhysicsResult cells = ps.step(s, W, ps.env().withBudget(new Budget(64, 100, 512, 2048))); assertEquals(Status.QUERY_LIMIT, cells.status());
    }
    @Test void portalContactIsUnsupported() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 8, NETHER_PORTAL); ps.set(8, 71, 8, NETHER_PORTAL);
        PhysicsResult r = ps.step(standingAt(8.5, SURFACE, 8.5).withVelocity(new Vec3d(0, -0.0784, 0)), W); assertEquals(Status.UNSUPPORTED_BLOCK_BEHAVIOUR, r.status()); assertTrue(r.hasReason(Code.PORTAL_CONTACT)); assertNotNull(r.next());
    }
    @Test void resultsAreDeterministicAndInputsImmutable() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); PhysicsState copy = new PhysicsState(s.position(), s.velocity(), s.pose(), s.onGround(), s.horizontalCollision(), s.verticalCollision(), s.verticalCollisionBelow(), s.fallDistance(), s.stuck(), s.inPowderSnow(), s.support(), s.noJumpDelay(), s.locomotion());
        PhysicsResult a = ps.step(s, W_JUMP), b = ps.step(s, W_JUMP); assertEquals(a, b); assertEquals(copy, s);
        assertThrows(UnsupportedOperationException.class, () -> a.reasons().add(new PhysicsResult.Reason(Code.CELL_UNKNOWN, "x")));
    }
    @Test void historicalViewsCarryTheirSequenceIntoProvenance() {
        PhysicsScene ps = new PhysicsScene(); long seq = ps.scene.mark(); ps.set(8, 70, 9, STONE);
        PhysicsEnvironment old = ps.env().withView(ps.scene.world.viewAtSequence(seq), ps.scene.world.worldEpoch());
        PhysicsState s = settled(ps, 8.5, SURFACE, 8.65);
        PhysicsResult before = ps.step(s, W, old), after = ps.step(s, W);
        assertEquals(seq, before.provenance().worldSequence()); assertFalse(before.next().horizontalCollision()); assertTrue(after.next().horizontalCollision(), after.summary());
        assertEquals(PhysicsTestSupport.PROFILE.sineDigest(), after.provenance().sineDigest());
    }
    @Test void selfConsistentRestingStateIsAFixedPoint() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        for (int i = 0; i < 50; i++) { PhysicsResult r = ps.step(s, IDLE); assertEquals(Status.EXACT, r.status()); assertEquals(s, r.next(), "tick " + i); s = r.next(); }
    }
    @Test void largeCoordinatesStayFiniteAndClampedAtTheWorldLimit() {
        CollisionTestSupport.Scene scene = new CollisionTestSupport.Scene(); PhysicsScene ps = new PhysicsScene(scene);
        int cx = 29999984 >> 4; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) scene.baseline(cx + dx, dz);
        PhysicsResult r = ps.step(standingAt(29999984.5, SURFACE, 8.5).withVelocity(new Vec3d(0, -0.0784, 0)), MovementInput.forward(-90f, true));
        assertNotNull(r.next()); assertTrue(r.next().position().isFinite()); assertTrue(r.status() == Status.EXACT || r.status() == Status.UNSUPPORTED_BORDER, r.summary());
    }
}
