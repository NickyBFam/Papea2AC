package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.physics.PhysicsEnvironment.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.ReconstructionResult.Status;
import me.nicolas.papea2ac.simulation.ReconstructionRequest.*;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 core matrix (PHASE_6_IMPLEMENTATION.md acceptance matrix): one focused test per contract rule - unique /
 * ambiguous / empty / incomplete outcomes, every gate, uncertainty propagation without defaults, budgets, external-motion
 * branches, multi-tick hypotheses, frontier continuation, witness ordering, vocabulary. Numbers come from the Phase 5
 * matrix (0.098 first step, 0.42 jump...).
 */
class InputReconstructorTest {
    // ---- outcomes
    @Test void holdingWFromRestIsUniquelyReconstructed() {
        PhysicsScene ps = new PhysicsScene(); ReconstructionResult r = reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), W);
        assertEquals(Status.EXACT_UNIQUE, r.status(), dump(r)); assertTrue(r.complete()); assertEquals(ReconstructionResult.Ambiguity.UNIQUE, r.ambiguity());
        assertEquals("W-----", keys(r.candidates().get(0).input())); assertEquals(1, r.counts().matchingHistories()); assertEquals(144, r.counts().rawEnumerated());
        assertEquals(ReconstructionResult.Match.EXACT_WITNESS, r.candidates().get(0).match()); assertEquals(PhysicsResult.Status.EXACT, r.candidates().get(0).physicsStatus());
        assertEquals(0.0, r.candidates().get(0).residual().outsideDistance()); assertTrue(r.candidates().get(0).residual().groundClaimAgrees());
    }
    @Test void standingStillIsAmbiguousBetweenPoseStatesAndEquivalentKeys() {
        PhysicsScene ps = new PhysicsScene(); ReconstructionResult r = reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), IDLE);
        assertEquals(Status.EXACT_AMBIGUOUS, r.status(), dump(r)); assertEquals(ReconstructionResult.Ambiguity.DISTINCT_STATES, r.ambiguity());
        assertEquals(2, r.counts().matchingStates(), "standing vs crouching pose share the position"); assertEquals(8, r.counts().matchingHistories(), "sprint flag and item use are unobservable without movement keys");
        assertTrue(r.admits(List.of(IDLE))); assertTrue(r.admits(List.of(new MovementInput(0, 0, false, false, true, true, 0f))));
        assertTrue(r.admits(List.of(new MovementInput(0, 0, false, true, false, false, 0f)))); assertFalse(r.admits(List.of(JUMP)), "a jump from the ground moves");
        for (ReconstructionResult.Candidate c : r.candidates()) assertTrue(c.historiesComplete());
    }
    @Test void jumpKeyInMidAirIsAnEquivalentInputNotAGuess() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState air = ps.step(settled(ps, 8.5, SURFACE, 8.5), JUMP).next();
        ReconstructionResult r = reconstruct(ps, air, W);
        assertEquals(Status.EXACT_AMBIGUOUS, r.status(), dump(r)); assertEquals(2, r.counts().matchingStates(), "jump held vs released differ only in noJumpDelay");
        assertTrue(r.admits(List.of(W))); assertTrue(r.admits(List.of(W_JUMP))); assertFalse(r.admits(List.of(W_SPRINT)), "air sprint speed is observable");
    }
    @Test void anUnreachableObservationIsNoModelledCandidateNotAVerdict() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env();
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(settled(ps, 8.5, SURFACE, 8.5), TickObservation.exact(9.5, 70.0, 8.5, 0f, TickObservation.GroundClaim.TRUE, 1), env));
        assertEquals(Status.NO_MODELLED_CANDIDATE, r.status()); assertTrue(r.complete()); assertEquals(0, r.candidates().size()); assertEquals(144, r.counts().excludedEnvelope() + r.counts().excludedExact());
        ReconstructionResult off = reconstructor(ps).reconstruct(ReconstructionRequest.of(settled(ps, 8.5, SURFACE, 8.5), TickObservation.exact(9.5, 70.0, 8.5, 0f, TickObservation.GroundClaim.TRUE, 1), env).withEnvelopePruning(false));
        assertEquals(Status.NO_MODELLED_CANDIDATE, off.status()); assertEquals(144, off.counts().excludedExact()); assertEquals(144, off.work().evaluations());
        String text = String.join("\n", r.describe(10)).toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("invalid", "cheat", "violation", "valid")) assertFalse(text.contains(forbidden), forbidden);
    }
    @Test void knownInputDimensionsShrinkTheEnumerationAndWitnessProposalGoesFirst() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        List<TickObservation> obs = observations(ps, env, rest, List.of(W_SPRINT));
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withConstraints(InputDomain.Constraints.NONE.withSprinting(true).withStrafe(0)));
        assertEquals(24, r.counts().rawCandidatesPerTick()); assertEquals(Status.EXACT_UNIQUE, r.status(), dump(r)); assertEquals("W---R-", keys(r.candidates().get(0).input()));
        ReconstructionResult w = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withWitness(W_SPRINT).withEnvelopePruning(false));
        assertTrue(w.candidates().get(0).witnessProposal(), "the reported input is tried first and flagged"); assertEquals(Status.EXACT_UNIQUE, w.status());
        ReconstructionResult lie = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withWitness(W));
        assertFalse(lie.candidates().get(0).witnessProposal(), "a reported input that does not explain the observation is simply not a witness (no verdict)"); assertEquals(Status.EXACT_UNIQUE, lie.status());
    }
    @Test void aWrongInputConstraintYieldsAnEmptyCompleteSet() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(W)), env).withConstraints(InputDomain.Constraints.NONE.withForward(-1)));
        assertEquals(Status.NO_MODELLED_CANDIDATE, r.status(), dump(r)); assertEquals(48, r.counts().rawCandidatesPerTick());
    }
    // ---- gates
    @Test void gatesRefuseWithoutSearching() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        List<TickObservation> obs = observations(ps, env, rest, List.of(W));
        InputReconstructor rec = reconstructor(ps);
        ReconstructionResult epoch = rec.reconstruct(ReconstructionRequest.of(rest, obs, env).withWorldEpoch(env.worldEpoch() + 1));
        assertEquals(Status.WORLD_EPOCH_MISMATCH, epoch.status()); assertFalse(epoch.complete()); assertFalse(epoch.coverage().epochAndContinuity()); assertEquals(0, epoch.work().evaluations());
        ReconstructionResult disc = rec.reconstruct(ReconstructionRequest.of(rest, obs, env).withDiscontinuity(true));
        assertEquals(Status.DISCONTINUITY, disc.status()); assertEquals(0, disc.work().evaluations());
        ReconstructionResult horizon = rec.reconstruct(ReconstructionRequest.of(rest, List.of(obs.get(0), obs.get(0), obs.get(0), obs.get(0)), env));
        assertEquals(Status.TIMING_UNRESOLVED, horizon.status()); assertFalse(horizon.coverage().timingResolved());
        ReconstructionResult yaw = rec.reconstruct(ReconstructionRequest.of(rest, TickObservation.yawUnknown(obs.get(0).position(), 1), env));
        assertEquals(Status.ROTATION_UNRESOLVED, yaw.status()); assertFalse(yaw.coverage().rotationResolved());
        ReconstructionResult nan = rec.reconstruct(ReconstructionRequest.of(rest.withVelocity(new Vec3d(Double.NaN, 0, 0)), obs, env));
        assertEquals(Status.NUMERIC_INVALID, nan.status());
        // a historical view that is not retained is UNAVAILABLE (no fallback to the present world)
        PhysicsEnvironment stale = env.withView(ps.scene.world.viewAtSequence(Long.MAX_VALUE / 2), env.worldEpoch());
        ReconstructionResult unavailable = rec.reconstruct(ReconstructionRequest.of(rest, obs, stale));
        assertEquals(Status.UNAVAILABLE_WORLD, unavailable.status(), unavailable.coverage().reasons().toString()); assertEquals(0, unavailable.work().evaluations());
    }
    // ---- uncertainty propagation: never a default, never an exclusion
    @Test void unknownAttributesEffectsAndEntitiesNeverDefaultAndNeverExclude() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        List<TickObservation> obs = observations(ps, env, rest, List.of(W));
        InputReconstructor rec = reconstructor(ps);
        ReconstructionResult attr = rec.reconstruct(ReconstructionRequest.of(rest, obs, env.withAttributes(Attributes.ALL_UNKNOWN)));
        assertEquals(Status.UNKNOWN_ATTRIBUTE, attr.status(), dump(attr)); assertFalse(attr.complete()); assertFalse(attr.coverage().environmentKnown()); assertEquals(144, attr.counts().undecided()); assertEquals(0, attr.counts().excludedExact() + attr.counts().excludedEnvelope());
        assertTrue(attr.admits(List.of(W))); assertTrue(attr.admits(List.of(JUMP)), "nothing can be excluded without the attributes"); assertEquals(1, attr.candidates().size(), "one undecided node carrying every history: " + dump(attr)); assertEquals(144, attr.candidates().get(0).historyCount());
        ReconstructionResult eff = rec.reconstruct(ReconstructionRequest.of(rest, obs, env.withEffects(Effects.UNKNOWN)));
        assertEquals(Status.UNKNOWN_EFFECT, eff.status(), dump(eff)); assertFalse(eff.complete()); assertTrue(eff.admits(List.of(W)));
        for (ReconstructionResult.Candidate c : eff.candidates()) assertEquals(ReconstructionResult.Match.POSSIBLE_INCOMPLETE, c.match());
        ReconstructionResult ent = rec.reconstruct(ReconstructionRequest.of(rest, obs, env.withEntities(EntityNeighborhood.UNKNOWN)));
        assertEquals(Status.UNKNOWN_ENTITY_NEIGHBORHOOD, ent.status(), dump(ent)); assertTrue(ent.admits(List.of(W))); assertTrue(ent.counts().undecided() > 0);
        assertEquals(144, ent.counts().undecided(), "every raw input is undecided - no exclusion under unknown pushing"); assertEquals(0, ent.counts().excludedExact() + ent.counts().excludedEnvelope());
        assertEquals(144, ent.candidates().stream().mapToInt(ReconstructionResult.Candidate::historyCount).sum(), "inside possibilities plus one merged outside-undecided node carry every history"); assertTrue(ent.admits(List.of(JUMP)));
        ReconstructionResult border = rec.reconstruct(ReconstructionRequest.of(rest, obs, env.withBorder(WorldBorder.UNKNOWN)));
        assertEquals(Status.UNKNOWN_WORLD_BORDER, border.status()); assertTrue(border.admits(List.of(W)));
        ReconstructionResult ext = rec.reconstruct(ReconstructionRequest.of(rest, obs, env).withBranches(List.of(ExternalMotionBranch.firstTick("velocity?", ExternalMotion.unresolved("clientbound-velocity-7")))));
        assertEquals(Status.UNRESOLVED_EXTERNAL_MOTION, ext.status(), dump(ext)); assertFalse(ext.complete()); assertTrue(ext.admits(List.of(W)));
    }
    @Test void anUnknownCellInTheSweptRegionMakesTheResultIncompleteWorldWithoutExcluding() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 30.5, SURFACE, 30.5); // the chunk at x,z >= 32 is unobserved
        PhysicsEnvironment env = ps.env(); List<TickObservation> obs = observations(ps, env, rest, List.of(S));
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env));
        assertEquals(Status.INCOMPLETE_WORLD, r.status(), dump(r)); assertFalse(r.complete()); assertFalse(r.coverage().worldComplete());
        assertTrue(r.witnesses(List.of(S)), "the exact witness is still found"); assertTrue(r.counts().undecided() > 0, "inputs reaching the unknown chunk are undecided, not excluded");
    }
    @Test void fluidContactIsUnsupportedFluidNotACandidateVerdict() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 8, WATER); PhysicsState rest = standingAt(8.5, SURFACE, 8.5);
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, TickObservation.exact(8.5, 70.0, 8.5, 0f, TickObservation.GroundClaim.TRUE, 1), ps.env()));
        assertEquals(Status.UNSUPPORTED_FLUID, r.status(), dump(r)); assertFalse(r.complete()); assertFalse(r.coverage().physicsSupported()); assertEquals(0, r.counts().excludedExact() + r.counts().excludedEnvelope());
    }
    @Test void unsupportedLocomotionAndPoseAreUnsupportedContext() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        TickObservation obs = observations(ps, env, rest, List.of(W)).get(0);
        assertEquals(Status.UNSUPPORTED_CONTEXT, reconstructor(ps).reconstruct(ReconstructionRequest.of(rest.withLocomotion(PhysicsState.Locomotion.ELYTRA), obs, env)).status());
        assertEquals(Status.UNSUPPORTED_CONTEXT, reconstructor(ps).reconstruct(ReconstructionRequest.of(rest.withPose(PlayerBox.Pose.FALL_FLYING), obs, env)).status());
    }
    // ---- budgets
    @Test void exhaustedBudgetsReportSearchLimitWithPartialEvidenceAndNoCompletenessClaim() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        List<TickObservation> obs = observations(ps, env, rest, List.of(IDLE));
        SearchBudget tiny = new SearchBudget(256, 3, 3, 8, 64, 4096);
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withBudget(tiny).withEnvelopePruning(false));
        assertEquals(Status.SEARCH_LIMIT, r.status(), dump(r)); assertFalse(r.complete()); assertFalse(r.coverage().searchComplete()); assertEquals(3, r.work().evaluations()); assertEquals(141, r.counts().witnessHistoriesUnexplored());
        SearchBudget oneNode = new SearchBudget(1, 4096, 3, 8, 64, 4096);
        ReconstructionResult n = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withBudget(oneNode));
        assertEquals(Status.SEARCH_LIMIT, n.status(), dump(n)); assertFalse(n.complete()); assertEquals(1, n.candidates().size(), "the surviving evidence is kept");
        SearchBudget noCells = new SearchBudget(256, 4096, 3, 8, 64, 1);
        ReconstructionResult cells = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withBudget(noCells));
        assertEquals(Status.EXACT_AMBIGUOUS, cells.status(), "an envelope over budget is TOP: the concrete lane still completes " + dump(cells)); assertEquals(144, cells.work().evaluations());
        assertThrows(IllegalArgumentException.class, () -> new SearchBudget(256, 4096, 4, 8, 64, 4096), "the three-step horizon is a spec limit");
    }
    // ---- external motion branches
    @Test void externalMotionBranchesAreReconstructedSeparatelyAndNeverChosen() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        ExternalMotion kb = new ExternalMotion(ExternalMotion.Operation.SET, new Vec3d(0.0, 0.4, 0.5), "velocity-3");
        PhysicsResult withKb = ps.step(rest, IDLE, env.withExternalMotions(List.of(kb)));
        TickObservation obs = observe(withKb.next(), 0f, 9);
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs, env).withBranches(List.of(ExternalMotionBranch.NONE, ExternalMotionBranch.firstTick("applied", kb))));
        assertEquals(2, r.branches().size());
        ReconstructionResult.BranchSummary none = r.branches().get(0), applied = r.branches().get(1);
        assertEquals(0, none.matchingStates(), "without the knockback nothing reaches the observation"); assertTrue(applied.matchingStates() > 0);
        assertTrue(r.candidates().stream().allMatch(c -> c.branchId().equals("applied")));
        assertTrue(r.complete()); assertTrue(r.admits(List.of(IDLE)));
    }
    // ---- multi-tick
    @Test void twoAndThreeTickHypothesesReconstructTheHistory() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        ReconstructionResult two = reconstruct(ps, rest, List.of(W, W_A)); assertEquals(Status.EXACT_UNIQUE, two.status(), dump(two)); assertEquals("W----->WA----", keys(two.candidates().get(0).input()));
        ReconstructionResult three = reconstruct(ps, rest, List.of(W, JUMP, W_SPRINT)); assertTrue(three.complete(), dump(three)); assertTrue(three.witnesses(List.of(W, JUMP, W_SPRINT)), dump(three));
        assertEquals(3, three.provenance().ticks());
    }
    @Test void anUnobservedMiddleTickIsSolvedByEnvelopePruningNotByDroppingStates() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        List<TickObservation> obs = observations(ps, env, rest, List.of(W, W));
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, List.of(TickObservation.unobserved(0f, 100), obs.get(1)), env));
        assertEquals(Status.EXACT_UNIQUE, r.status(), dump(r)); assertEquals("W----->W-----", keys(r.candidates().get(0).input())); assertTrue(r.counts().excludedEnvelope() > 100 && r.work().evaluations() < 300, "the sound one-tick lookahead excludes the tick-1 inputs that cannot reach the tick-2 observation: " + dump(r));
        ReconstructionResult off = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, List.of(TickObservation.unobserved(0f, 100), obs.get(1)), env).withEnvelopePruning(false));
        assertEquals(Status.SEARCH_LIMIT, off.status(), "without the sound envelope the Cartesian expansion exhausts the 4096-evaluation cap and says so"); assertFalse(off.complete());
    }
    @Test void silentTicksAreConstrainedByTheSendThresholdBall() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, TickObservation.silent(8.5, 70.0, 8.5, 0f, 5), env));
        assertEquals(Status.EXACT_AMBIGUOUS, r.status(), dump(r)); assertTrue(r.admits(List.of(IDLE))); assertFalse(r.admits(List.of(W)), "0.098 exceeds the 2.0E-4 send threshold: the client would have transmitted");
        assertFalse(r.admits(List.of(W_SHIFT)), "0.0294 too"); assertFalse(r.admits(List.of(new MovementInput(1, 0, false, true, false, true, 0f))), "sneak + item use: 0.00588 too");
    }
    @Test void aResultsWitnessesSeedTheNextReconstruction() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        List<TickObservation> obs = observations(ps, env, rest, List.of(JUMP, W));
        ReconstructionResult first = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs.get(0), env));
        assertTrue(first.complete()); List<Start> frontier = first.frontier(); assertEquals(first.counts().matchingStates(), frontier.size());
        ReconstructionResult second = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, obs.get(1), env).withFrontier(frontier));
        assertTrue(second.complete(), dump(second)); assertTrue(second.admits(List.of(W)), dump(second));
        for (ReconstructionResult.Candidate c : second.candidates()) assertTrue(c.startLabel().startsWith("c"), "provenance of the start node kept: " + c.startLabel());
    }
    @Test void groundClaimIsReportedNeverUsedAsAConstraint() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        PhysicsResult w = ps.step(rest, W, env);
        TickObservation lying = TickObservation.exact(w.next().position().x(), w.next().position().y(), w.next().position().z(), 0f, TickObservation.GroundClaim.FALSE, 1);
        ReconstructionResult r = reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, lying, env));
        assertEquals(Status.EXACT_UNIQUE, r.status()); assertTrue(r.candidates().get(0).residual().groundClaimPresent()); assertFalse(r.candidates().get(0).residual().groundClaimAgrees(), "disagreement is data for Phase 8, not an exclusion");
    }
    @Test void sameRequestSameResult() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsEnvironment env = ps.env();
        ReconstructionRequest req = ReconstructionRequest.of(rest, observations(ps, env, rest, List.of(JUMP, IDLE, W)), env);
        ReconstructionResult a = reconstructor(ps).reconstruct(req), b = new InputReconstructor(ps.simulator).reconstruct(req);
        assertEquals(dump(a), dump(b)); assertEquals(a.work(), b.work()); assertEquals(a.counts(), b.counts());
    }
}
