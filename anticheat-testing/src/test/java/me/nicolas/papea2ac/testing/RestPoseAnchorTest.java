package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.simulation.*;
import org.junit.jupiter.api.Test;

/**
 * P9-F3 regression (found by the Phase 9 real-client qualification, capture 5; originating component: Phase 8 rest anchoring).
 *
 * <p>The live failure: the owner rested while holding the sneak key, then walked west under a {@code minecraft:oak_slab[type=top]}
 * whose collision box is y 68.5..69.0. A crouching player's box is 1.5 high (67.0..68.5) and passes under it; a standing player's box is
 * 1.8 high (67.0..68.8) and does not. The chain's only frontier node carried {@code pose=STANDING}, so the model clipped the movement
 * against the slab at exactly x = -26.0 and declared the observed position impossible: a complete
 * {@code INVALID [NO_LEGAL_INPUT, UNEXPLAINED_DISPLACEMENT]} on a legitimate player, which Phase 9 faithfully turned into one event.
 * The capture proved the model's input pipeline was exact - the impulse the observation required was bit-identical to the model's own
 * processed input vector - and that the whole discrepancy was the collision clip (0.001993 in x, z identical).
 *
 * <p>Root cause: {@code restFixedPoint} probed every pose with a shift-RELEASED idle input, so the crouching fixed point converged to
 * STANDING and deduplicated away ("rest (1 pose state)"). A resting client may be sneaking; its pose is an input fact, not a default.
 * The fix probes each pose with the input that keeps it, so the crouching resting state stays in the anchor union. That is a superset of
 * the previous start states: it can only remove impossibility claims, never create one.
 */
class RestPoseAnchorTest {
    static final BlockStateKey TOP_SLAB = BlockStateKey.parse("minecraft:oak_slab[type=top,waterlogged=false]");
    static final float WEST = 90f; // yaw 90 faces -x
    /** Standing fits at rest (box minX 8.01 > the slab face at x=8.0) but one sneak step (0.0294) takes the box under the slab. */
    static final double REST_X = 8.31;

    /** The live geometry: floor at SURFACE, a top slab two cells west at head height, leaving a 1.5-high gap a crouching player fits through. */
    static PhysicsScene scene() {
        PhysicsScene ps = new PhysicsScene();
        for (int x = 4; x <= 7; x++) ps.set(x, (int) SURFACE + 1, 8, TOP_SLAB);
        return ps;
    }

    @Test void aRestingClientMayBeSneakingAndTheCrouchingFixedPointIsAnAnchorState() {
        PhysicsScene ps = scene();
        PhysicsEnvironment env = ps.env();
        PhysicsState standing = MovementValidator.restFixedPoint(ps.simulator, env, REST_X, SURFACE, 8.5, PlayerBox.Pose.STANDING, WEST);
        PhysicsState crouching = MovementValidator.restFixedPoint(ps.simulator, env, REST_X, SURFACE, 8.5, PlayerBox.Pose.CROUCHING, WEST);
        assertNotNull(standing); assertEquals(PlayerBox.Pose.STANDING, standing.pose());
        assertNotNull(crouching, "a client resting while sneaking is a fixed point too");
        assertEquals(PlayerBox.Pose.CROUCHING, crouching.pose(), "the crouching rest must not collapse to STANDING");
        assertNotEquals(standing, crouching, "the two resting states are distinct hidden states and must both reach the frontier");
        assertEquals("fixed point", MovementValidator.restFixedPointReason(ps.simulator, env, REST_X, SURFACE, 8.5, PlayerBox.Pose.CROUCHING, WEST));
    }

    /** The full live shape through the production path: rest anchor -> context -> timing -> reconstruction -> validation. */
    static ValidationStatus run(PhysicsScene ps, boolean sneakWhileResting) {
        MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        MovementInput restInput = sneakWhileResting ? new MovementInput(0, 0, false, true, false, false, WEST) : MovementInput.idle(WEST);
        PhysicsState rest = settled(ps, REST_X, SURFACE, 8.5);
        if (sneakWhileResting) { // the client has been sneaking in place: its pose is CROUCHING when the reminder is sent
            for (int i = 0; i < 4; i++) { PhysicsResult r = ps.step(rest, restInput); rest = r.next(); }
            assertEquals(PlayerBox.Pose.CROUCHING, rest.pose());
        }
        h.rec.tickEnd(); h.rec.advance(TICK_NANOS); h.rec.movement(REST_X, SURFACE, 8.5, WEST, true);
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 21; i++) idle.add(restInput);
        simulate(ps, ps.env(), rest, idle, List.of(), h.rec, true); h.feed();
        assertTrue(h.status().anchored(), h.status().anchorKind());
        // now the client walks west, under the slab, still sneaking
        MovementInput walk = new MovementInput(1, 0, false, sneakWhileResting, false, false, WEST);
        PhysicsState after = rest;
        List<MovementInput> west = new ArrayList<>(); for (int i = 0; i < 6; i++) west.add(walk);
        simulate(ps, ps.env(), after, west, List.of(), h.rec, true, false); h.feed();
        return h.status();
    }

    @Test void aSneakingClientWalkingUnderATopSlabIsValidThroughTheLiveRunner() {
        ValidationStatus st = run(scene(), true);
        assertEquals(0, st.invalid(), "the live P9-F3 failure must not recur: " + String.join("\n", st.notable()));
        assertEquals(st.transitions(), st.valid(), String.join("\n", st.recent()));
        assertTrue(st.anchorKind().startsWith("rest"), st.anchorKind());
    }
    @Test void anOrdinaryStandingClientIsUnaffected() {
        ValidationStatus st = run(scene(), false);
        assertEquals(0, st.invalid(), String.join("\n", st.notable()));
        assertEquals(st.transitions(), st.valid(), String.join("\n", st.recent()));
    }
    /**
     * The live single-tick shape at the validation layer: the anchor frontier is built exactly as the runner builds it (the rest fixed point
     * of each pose, deduplicated), and one crouch-walk tick under the slab is validated against it. Pre-fix that frontier is STANDING-only,
     * the standing box is clipped at the slab (x = 8.300000011920929 instead of the observed 8.280599996605517) and the tick is INVALID.
     */
    @Test void theSingleCrouchWalkTickIsExplainedByTheAnchorFrontier() {
        PhysicsScene ps = scene(); PhysicsEnvironment env = ps.env();
        PhysicsState crouchRest = settled(ps, REST_X, SURFACE, 8.5).withPose(PlayerBox.Pose.CROUCHING);
        MovementInput walk = new MovementInput(1, 0, false, true, false, false, WEST);
        ValidationTestSupport.Validated v = ValidationTestSupport.single(ps, env, crouchRest, walk, null);
        assertEquals(8.280599996605517, v.target().position().x(), 1e-12, "the client's crouching step passes under the top slab");
        List<TimingReconstruction.Node> anchor = new ArrayList<>();
        for (PlayerBox.Pose pose : new PlayerBox.Pose[] { PlayerBox.Pose.STANDING, PlayerBox.Pose.CROUCHING, PlayerBox.Pose.SWIMMING }) {
            PhysicsState f = MovementValidator.restFixedPoint(ps.simulator, env, REST_X, SURFACE, 8.5, pose, WEST);
            if (f != null && anchor.stream().noneMatch(n -> n.state().equals(f))) anchor.add(new TimingReconstruction.Node(f, Set.of(), "rest/" + pose));
        }
        assertTrue(anchor.size() >= 2, "the anchor union must contain the sneaking rest: " + anchor.size());
        MovementValidationRequest q = v.request();
        MovementValidationRequest fromAnchor = new MovementValidationRequest(q.timing(), new TimingReconstruction.Frontier(anchor), q.environment(), q.reconstructionBudget(), q.frontierComplete(), q.contentRetained());
        MovementValidationResult r = ValidationTestSupport.validator(ps).validate(fromAnchor);
        assertNotEquals(MovementValidation.Classification.INVALID, r.classification(), "the live P9-F3 tick must not be provable impossible: " + String.join("\n", r.describe()));
        assertEquals(MovementValidation.Classification.VALID, r.classification(), String.join("\n", r.describe()));
        // and the standing-only frontier (the pre-fix anchor) is exactly what produced the live INVALID
        PhysicsState standOnly = MovementValidator.restFixedPoint(ps.simulator, env, REST_X, SURFACE, 8.5, PlayerBox.Pose.STANDING, WEST);
        MovementValidationRequest standing = new MovementValidationRequest(q.timing(), new TimingReconstruction.Frontier(List.of(new TimingReconstruction.Node(standOnly, Set.of(), "rest/standing"))), q.environment(), q.reconstructionBudget(), q.frontierComplete(), q.contentRetained());
        assertEquals(MovementValidation.Classification.INVALID, ValidationTestSupport.validator(ps).validate(standing).classification(), "the pre-fix anchor shape reproduces the live failure");
    }
    @Test void theAnchorNowCarriesBothRestingPoses() {
        PhysicsScene ps = scene();
        MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        PhysicsState rest = settled(ps, REST_X, SURFACE, 8.5);
        h.rec.tickEnd(); h.rec.advance(TICK_NANOS); h.rec.movement(REST_X, SURFACE, 8.5, WEST, true);
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 21; i++) idle.add(MovementInput.idle(WEST));
        simulate(ps, ps.env(), rest, idle, List.of(), h.rec, true); h.feed();
        assertTrue(h.status().anchorKind().startsWith("rest"), h.status().anchorKind());
        assertTrue(h.status().frontierNodes() >= 2, "the standing and the sneaking rest are both legal starts: " + h.status().anchorKind() + " nodes=" + h.status().frontierNodes());
    }
}
