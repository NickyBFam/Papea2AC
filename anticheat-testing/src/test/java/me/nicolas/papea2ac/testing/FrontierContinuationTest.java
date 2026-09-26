package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static me.nicolas.papea2ac.testing.ValidationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
import org.junit.jupiter.api.Test;

/**
 * Frontier-continuation safety (P9-F3 §30, independent of any single defect): a VALID transition does NOT prove one hidden
 * {@link PhysicsState}. Whenever several legal candidates reproduce the same observation, every candidate whose state differs in a field
 * that can change a later tick must survive into the next transition; only states that are bit-identical (and carry the same pending
 * influences) may merge. If this ever regresses, a legitimate player whose true hidden state was dropped becomes INVALID one tick later -
 * exactly the shape of the P9-F3 live failure - so the property is pinned here rather than inferred from the defect that motivated it.
 */
class FrontierContinuationTest {
    static final float YAW = 0f;

    @Test void observationallyEquivalentCandidatesWithDifferentHiddenStatesAllSurvive() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        // a rest reminder: standing and crouching reproduce the same position, and the jump key in mid-air is invisible - several hidden states
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 20; i++) idle.add(MovementInput.idle(YAW));
        Truth truth = simulate(ps, ps.env(), start, idle, List.of(), new Recorder(), true);
        List<Validated> chain = validateChain(ps, ps.env(), truth);
        MovementValidationResult r = chain.get(0).result();
        assertEquals(Classification.VALID, r.classification(), describe(chain));
        assertTrue(r.counts().legalHistories() > 1, "several raw histories explain the reminder: " + describe(chain));
        // every continuation is distinct as a full state; none was collapsed onto another
        Set<PhysicsState> states = new LinkedHashSet<>();
        for (TimingReconstruction.Node n : r.next().nodes()) states.add(n.state());
        assertEquals(r.next().nodes().size(), states.size(), "continuation nodes must be pairwise distinct states");
        assertTrue(r.next().nodes().size() >= 1);
        // and the true hidden state is among them
        assertTrue(r.next().nodes().stream().anyMatch(n -> n.state().equals(chain.get(0).truthAfter())), describe(chain));
    }
    @Test void statesDifferingOnlyInAFutureRelevantFieldAreNotMerged() {
        // the dedup identity is the whole PhysicsState plus the pending influences: two states that agree on position but differ in any
        // simulation-relevant field are different keys, so a union cannot silently keep one of them
        PhysicsScene ps = new PhysicsScene(); PhysicsState a = settled(ps, 8.5, SURFACE, 8.5);
        List<PhysicsState> variants = List.of(
            a.withVelocity(a.velocity().add(0.01, 0, 0)),
            a.withPose(me.nicolas.papea2ac.collision.PlayerBox.Pose.CROUCHING),
            a.withOnGround(!a.onGround()),
            a.withFallDistance(a.fallDistance() + 1.0),
            a.withStuck(new Vec3d(0.5, 0.5, 0.5)),
            a.withSupport(PhysicsState.Support.at(1, 2, 3)),
            a.withSprintModifier(PhysicsState.SprintModifier.STUCK_ON),
            a.withLocomotion(PhysicsState.Locomotion.ELYTRA));
        Set<PhysicsState> set = new LinkedHashSet<>(); set.add(a); set.addAll(variants);
        assertEquals(variants.size() + 1, set.size(), "every future-relevant field must take part in the identity");
        for (PhysicsState v : variants) assertNotEquals(a, v);
    }
    @Test void aFrontierCapNeverSilentlyKeepsOneStateWhileClaimingCompleteness() {
        // the budget that bounds the continuation union must report incompleteness when it drops a legal state; a capped frontier that
        // still claimed completeness would let a later transition be proven INVALID against a state set that is not the real union
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 20; i++) idle.add(MovementInput.idle(YAW));
        Truth truth = simulate(ps, ps.env(), start, idle, List.of(), new Recorder(), true);
        TimingBudget tiny = new TimingBudget(3, 16, 8, 64, 16, 16384L, 1); // maxFrontierNodes = 1: any second continuation must be reported as a drop
        List<Validated> chain = validateChain(ps, ps.env(), truth, validator(ps), tiny, null);
        MovementValidationResult r = chain.get(0).result();
        if (r.next().nodes().size() < 2) {
            assertTrue(r.classification() != Classification.INVALID, "a capped frontier must never yield INVALID: " + describe(chain));
            assertTrue(!r.nextFrontierComplete() || r.counts().continuationNodes() == r.next().nodes().size(),
                "a frontier that dropped a continuation must be reported incomplete: " + describe(chain));
        }
    }
}
