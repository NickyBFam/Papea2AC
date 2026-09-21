package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static me.nicolas.papea2ac.testing.ValidationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.MovementValidation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;

/**
 * The Phase 8 classification core over the Phase 3/4/5 test scene: VALID (unique, ambiguous, external velocity, rotation-only,
 * teleport application point), UNCERTAIN with its named blocking dimension (world, attribute, effect, entities, search limit,
 * fluid, discontinuity, frontier), and INVALID only under complete knowledge with an exhaustion proof and physical diagnostics.
 * Every expected value is known by construction (the hidden truth) or by an independent physical argument stated at the case.
 */
class MovementValidatorTest {
    static final float YAW = 0f;

    @Test void aOneTickWalkIsValidWithAWitnessThatReproducesTheObservation() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Validated v = single(ps, ps.env(), start, MovementInput.forward(YAW, false), null);
        MovementValidationResult r = v.result();
        assertEquals(Classification.VALID, r.classification(), describe(v));
        assertTrue(r.validReasons().contains(ValidReason.LEGAL_INPUT_FOUND)); assertTrue(r.uncertainReasons().isEmpty() && r.invalidReasons().isEmpty());
        assertTrue(r.completeness().all(), "a complete transition: " + r.completeness().missing());
        assertFalse(r.witnesses().isEmpty()); assertEquals(v.target().position(), new Vector3(r.witnesses().get(0).predicted().position().x(), r.witnesses().get(0).predicted().position().y(), r.witnesses().get(0).predicted().position().z()), "the witness reproduces the observation");
        assertTrue(r.nextFrontierComplete()); assertFalse(r.next().isEmpty());
        assertEquals(MovementValidationResult.Proof.NONE, r.proof());
    }
    @Test void aRestReminderIsValidWithAmbiguityAndNothingIsChosen() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 20; i++) inputs.add(MovementInput.idle(YAW));
        Truth truth = simulate(ps, ps.env(), start, inputs, List.of(), new Recorder(), true);
        List<Validated> chain = validateChain(ps, ps.env(), truth);
        assertEquals(1, chain.size(), describe(chain));
        MovementValidationResult r = chain.get(0).result();
        assertEquals(Classification.VALID, r.classification(), describe(chain));
        assertTrue(r.counts().legalHistories() > 1, "idle with/without item use, jump key in mid-air ... several raw histories: " + describe(chain));
        assertTrue(r.counts().continuationNodes() >= 1);
    }
    @Test void aVelocityAppliedAtAHiddenTickIsValidThroughTheExternalMotionBranch() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < 6; i++) inputs.add(MovementInput.idle(YAW));
        Truth truth = simulate(ps, ps.env(), start, inputs, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.3, 0.4, 0.1), 3, 1)), new Recorder(), true);
        List<Validated> chain = validateChain(ps, ps.env(), truth);
        assertFalse(chain.isEmpty());
        MovementValidationResult first = chain.get(0).result();
        assertEquals(Classification.VALID, first.classification(), describe(chain));
        assertTrue(first.validReasons().contains(ValidReason.EXTERNAL_MOTION_EXPLAINS) && first.validReasons().contains(ValidReason.LEGAL_TIMING_BRANCH_FOUND), describe(chain));
        assertTrue(first.counts().timingHypotheses() > 1, "apply@j / later branches kept: " + describe(chain));
        for (Validated v : chain) assertNotEquals(Classification.INVALID, v.result().classification(), describe(chain));
        for (Validated v : chain) if (v.truthAfter() != null) assertTrue(v.result().next().nodes().stream().anyMatch(n -> n.state().equals(v.truthAfter())), "the true state is carried: " + describe(chain));
    }
    @Test void aRotationOnlyPacketIsValidatedLikeAnyTransmission() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        List<MovementInput> inputs = List.of(MovementInput.idle(YAW), MovementInput.idle(37f), MovementInput.forward(37f, false));
        Truth truth = simulate(ps, ps.env(), start, inputs, List.of(), new Recorder(), true);
        List<Validated> chain = validateChain(ps, ps.env(), truth);
        assertEquals(2, chain.size(), describe(chain));
        assertFalse(chain.get(0).target().hasPosition(), "first transmission is rotation-only");
        for (Validated v : chain) assertEquals(Classification.VALID, v.result().classification(), describe(chain));
    }
    @Test void aCorrectionApplicationPointIsValidAsATeleportAndReanchorsTheChain() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd();
        rec.advance(TICK_NANOS / 8); rec.teleport(9, 12.5, SURFACE, 12.5, YAW, 0);
        rec.advance(TICK_NANOS / 4); rec.confirm(9); rec.advance(100_000L); rec.movement(12.5, SURFACE, 12.5, YAW, true);
        PhysicsState post = start.withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        Truth truth = simulate(ps, ps.env(), post, List.of(MovementInput.forward(YAW, false), MovementInput.forward(YAW, false)), List.of(), rec, true, false);
        // the chain starts from the pre-teleport rest; the generator's truth start is the post state, so drive the chain by hand from the rest
        Truth fromRest = new Truth(start, truth.ticks(), truth.applicationTick(), rec);
        List<Validated> chain = validateChain(ps, ps.env(), fromRest, validator(ps), TimingBudget.DEFAULT, null);
        assertTrue(chain.size() >= 2, describe(chain));
        MovementValidationResult tp = chain.get(0).result();
        assertEquals(Classification.VALID, tp.classification(), describe(chain)); assertTrue(tp.validReasons().contains(ValidReason.TELEPORT_APPLICATION_POINT), describe(chain));
        assertTrue(tp.witnesses().get(0).teleportApplication()); assertTrue(tp.nextFrontierComplete(), "an absolute correction re-anchors the chain");
        for (int i = 1; i < chain.size(); i++) assertEquals(Classification.VALID, chain.get(i).result().classification(), describe(chain));
    }
    @Test void anUnobservedChunkInReachIsUncertainWorldIncompleteNeverInvalid() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Truth truth = simulate(ps, ps.env(), start, List.of(MovementInput.forward(YAW, false)), List.of(), new Recorder(), true);
        // the validating world never observed chunk (0,0): the same transition, the same impossible observation, but the collision is incomplete
        PhysicsScene partial = new PhysicsScene(new CollisionTestSupport.Scene(1, new me.nicolas.papea2ac.world.WorldBudget())); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) if (cx != 0 || cz != 0) partial.scene.baseline(cx, cz);
        Validated v = validateChain(ps, partial.env(), truth, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0); // an observation no legal input reaches - but the world is incomplete
        assertEquals(Classification.UNCERTAIN, v.result().classification(), describe(v));
        assertTrue(v.result().uncertainReasons().contains(UncertainReason.WORLD_INCOMPLETE), describe(v)); assertFalse(v.result().completeness().world());
        assertTrue(v.result().invalidReasons().isEmpty()); assertEquals(MovementValidationResult.Proof.NONE, v.result().proof());
    }
    @Test void anUnknownAttributeOrEffectIsUncertainNotDefaulted() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsEnvironment attrUnknown = ps.env().withAttributes(PhysicsEnvironment.Attributes.ALL_UNKNOWN);
        Truth truth = simulate(ps, ps.env(), start, List.of(MovementInput.forward(YAW, false)), List.of(), new Recorder(), true);
        Validated v = validateChain(ps, attrUnknown, truth, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0);
        assertEquals(Classification.UNCERTAIN, v.result().classification(), describe(v)); assertTrue(v.result().uncertainReasons().contains(UncertainReason.ATTRIBUTE_UNKNOWN), describe(v)); assertFalse(v.result().completeness().attributes());
        PhysicsEnvironment effUnknown = ps.env().withEffects(PhysicsEnvironment.Effects.UNKNOWN);
        Validated e = validateChain(ps, effUnknown, truth, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0);
        assertEquals(Classification.UNCERTAIN, e.result().classification(), describe(e)); assertTrue(e.result().uncertainReasons().contains(UncertainReason.EFFECT_UNKNOWN), describe(e));
        PhysicsEnvironment entUnknown = ps.env().withEntities(PhysicsEnvironment.EntityNeighborhood.UNKNOWN);
        Validated n = validateChain(ps, entUnknown, truth, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0);
        assertEquals(Classification.UNCERTAIN, n.result().classification(), describe(n)); assertTrue(n.result().uncertainReasons().contains(UncertainReason.ENTITY_NEIGHBORHOOD_UNKNOWN), describe(n));
        // the same unknown context with a legal observation is still VALID: a witness needs no completeness
        Validated ok = validateChain(ps, attrUnknown, truth, validator(ps), TimingBudget.DEFAULT, null).get(0);
        assertNotEquals(Classification.INVALID, ok.result().classification(), describe(ok));
    }
    @Test void aSearchLimitIsUncertainEvenWhenNothingWasFound() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Truth truth = simulate(ps, ps.env(), start, List.of(MovementInput.forward(YAW, false)), List.of(), new Recorder(), true);
        MovementValidator v = validator(ps);
        List<Validated> chain = validateChain(ps, ps.env(), truth, v, TimingBudget.DEFAULT, shift(1.5, 0, 0));
        // the same impossible observation with a Phase 6 node budget the two-node frontier cannot fit: the reconstructor's own SEARCH_LIMIT gate
        List<TimingReconstruction.Node> two = List.of(new TimingReconstruction.Node(start, Set.of(), "a"), new TimingReconstruction.Node(start.withVelocity(new Vec3d(0, -0.0784 - 1e-9, 0)), Set.of(), "b"));
        MovementValidationResult r = v.validate(new MovementValidationRequest(chain.get(0).request().timing(), new TimingReconstruction.Frontier(two), ps.env(), new ReconstructionRequest.SearchBudget(1, 4096, 3, 256, 64, 4096), true, true));
        assertEquals(Classification.UNCERTAIN, r.classification(), String.join("\n", r.describe()));
        assertTrue(r.uncertainReasons().contains(UncertainReason.SEARCH_LIMIT), String.join("\n", r.describe())); assertFalse(r.completeness().search());
        // a reconstruction budget of one physics evaluation on a case the envelope cannot prune (a legal walk with the true observation shifted a hair beyond the send threshold ball is still pruned; use a silent-closure rest reminder instead)
        List<MovementInput> idle = new ArrayList<>(); for (int k = 0; k < 20; k++) idle.add(MovementInput.idle(YAW));
        Truth rest = simulate(ps, ps.env(), start, idle, List.of(), new Recorder(), true);
        Validated starvedRest = validateChain(ps, ps.env(), rest, v, new TimingBudget(3, 16, 8, 64, 16, 1, 64), shift(1.5, 0, 0)).get(0);
        assertEquals(Classification.UNCERTAIN, starvedRest.result().classification(), describe(starvedRest)); assertTrue(starvedRest.result().uncertainReasons().contains(UncertainReason.SEARCH_LIMIT), describe(starvedRest));
        // a frontier larger than Phase 6's node cap: SEARCH_LIMIT from the reconstructor itself
        List<TimingReconstruction.Node> many = new ArrayList<>(); for (int i = 0; i < 300; i++) many.add(new TimingReconstruction.Node(start.withVelocity(new Vec3d(0, -0.0784 - i * 1e-9, 0)), Set.of(), "n" + i));
        MovementValidationResult big = v.validate(new MovementValidationRequest(chain.get(0).request().timing(), new TimingReconstruction.Frontier(many), ps.env(), ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        assertEquals(Classification.UNCERTAIN, big.classification(), String.join("\n", big.describe())); assertTrue(big.uncertainReasons().contains(UncertainReason.SEARCH_LIMIT), String.join("\n", big.describe()));
    }
    @Test void fluidContactIsUncertainUnsupportedFluid() {
        PhysicsScene ps = new PhysicsScene(); for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) ps.set(x, (int) SURFACE, z, WATER);
        PhysicsState start = PhysicsState.resting(8.5, SURFACE, 8.5, me.nicolas.papea2ac.collision.PlayerBox.Pose.STANDING, false);
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(8.5, SURFACE + 0.3, 8.5, YAW, false);
        TimingRequest tr = request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), YAW, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, t.nanos());
        MovementValidationResult r = validator(ps).validate(MovementValidationRequest.of(tr, TimingReconstruction.Frontier.of(start), ps.env()));
        assertEquals(Classification.UNCERTAIN, r.classification(), String.join("\n", r.describe()));
        assertTrue(r.uncertainReasons().contains(UncertainReason.UNSUPPORTED_FLUID), String.join("\n", r.describe())); assertFalse(r.completeness().mechanicSupported());
    }
    @Test void aWorldEpochMismatchOrDiscontinuityIsNeverAnOrdinaryTransition() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2); rec.discontinuity(1); rec.advance(TICK_NANOS / 2); TimingEvent.MovementObserved t = rec.movement(8.5, SURFACE, 8.5, YAW, true);
        TimingRequest tr = request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), YAW, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, t.nanos());
        MovementValidationResult r = validator(ps).validate(MovementValidationRequest.of(tr, TimingReconstruction.Frontier.of(start), ps.env()));
        assertEquals(Classification.UNCERTAIN, r.classification(), String.join("\n", r.describe())); assertTrue(r.uncertainReasons().contains(UncertainReason.DISCONTINUITY), String.join("\n", r.describe()));
        // an environment of another epoch than the request
        Truth truth = simulate(ps, ps.env(), start, List.of(MovementInput.forward(YAW, false)), List.of(), new Recorder(), true);
        Validated v = validateChain(ps, ps.env().withView(ps.scene.world.currentView(), 7), truth, validator(ps), TimingBudget.DEFAULT, null).get(0);
        assertEquals(Classification.UNCERTAIN, v.result().classification(), describe(v)); assertTrue(v.result().uncertainReasons().contains(UncertainReason.DISCONTINUITY), describe(v));
    }
    @Test void anIncompleteFrontierForbidsInvalid() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Truth truth = simulate(ps, ps.env(), start, List.of(MovementInput.forward(YAW, false)), List.of(), new Recorder(), true);
        Validated v = validateChain(ps, ps.env(), truth, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0);
        assertEquals(Classification.INVALID, v.result().classification(), describe(v));
        MovementValidationResult r = validator(ps).validate(v.request().withFrontierComplete(false));
        assertEquals(Classification.UNCERTAIN, r.classification(), String.join("\n", r.describe())); assertTrue(r.uncertainReasons().contains(UncertainReason.FRONTIER_INCOMPLETE));
        MovementValidationResult empty = validator(ps).validate(new MovementValidationRequest(v.request().timing(), new TimingReconstruction.Frontier(List.of()), ps.env(), ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        assertEquals(Classification.UNCERTAIN, empty.classification()); assertTrue(empty.uncertainReasons().contains(UncertainReason.START_UNAVAILABLE));
        MovementValidationResult notRetained = validator(ps).validate(v.request().withContentRetained(false));
        assertEquals(Classification.UNCERTAIN, notRetained.classification()); assertTrue(notRetained.uncertainReasons().contains(UncertainReason.CONTENT_NOT_RETAINED));
    }
    /** Independent argument: from rest, the largest one-tick horizontal displacement of any legal input is a sprint-jump start (~0.13 with the 0.98 pipeline); 1.5 blocks is beyond every candidate and the M3 envelope. */
    @Test void aHorizontalDisplacementBeyondEveryLegalInputIsInvalidWithAnExhaustionProofAndHorizontalExcess() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Validated v = single(ps, ps.env(), start, MovementInput.forward(YAW, false), shift(1.5, 0, 0));
        MovementValidationResult r = v.result();
        assertEquals(Classification.INVALID, r.classification(), describe(v));
        assertTrue(r.completeness().all()); assertTrue(r.uncertainReasons().isEmpty()); assertEquals(0, r.counts().legalCandidates());
        assertTrue(r.invalidReasons().contains(InvalidReason.NO_LEGAL_INPUT) && r.invalidReasons().contains(InvalidReason.HORIZONTAL_EXCESS), describe(v));
        assertFalse(r.invalidReasons().contains(InvalidReason.VERTICAL_EXCESS));
        assertTrue(r.proof().hypothesesExhausted() >= 1 && r.proof().rawEnumerated() > 0 && r.proof().candidatesRemaining() == 0 && r.proof().excludedExact() + r.proof().excludedEnvelope() > 0, describe(v));
        assertTrue(r.residual().present() && r.residual().horizontalExcess() > 1.0 && r.residual().maxLegalHorizontal() < 0.5, describe(v));
        assertNotEquals(MovementValidationResult.ReachBound.NONE, r.residual().bound());
        assertTrue(r.next().isEmpty(), "no continuation from an unexplained transition");
        for (String l : r.describe()) { String lo = l.toLowerCase(Locale.ROOT); assertFalse(lo.contains("cheat") || lo.contains("hack") || lo.contains("intent"), l); }
    }
    /** Independent argument: a jump from rest rises 0.42 in its first tick (jump strength attribute); +1.0 in one tick has no legal source. */
    @Test void anAscentBeyondAnyJumpIsInvalidWithImpossibleAscent() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Validated v = single(ps, ps.env(), start, MovementInput.forward(YAW, false), m -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), SURFACE + 1.0, m.position().z()), true, m.rotation(), false));
        MovementValidationResult r = v.result();
        assertEquals(Classification.INVALID, r.classification(), describe(v));
        assertTrue(r.invalidReasons().contains(InvalidReason.IMPOSSIBLE_ASCENT) && r.invalidReasons().contains(InvalidReason.VERTICAL_EXCESS), describe(v));
        assertTrue(r.residual().maxLegalRise() < 0.5 && r.residual().maxLegalRise() > 0.4, "the legal rise bound is the jump: " + describe(v));
    }
    /** Independent argument: a standing player's box cannot occupy the stone floor; the observation one block down puts the feet inside it. */
    @Test void aPositionInsideSolidGeometryIsInvalidWithCollisionPenetration() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Validated v = single(ps, ps.env(), start, MovementInput.forward(YAW, false), m -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), SURFACE - 0.5, m.position().z()), true, m.rotation(), false));
        MovementValidationResult r = v.result();
        assertEquals(Classification.INVALID, r.classification(), describe(v));
        assertTrue(r.invalidReasons().contains(InvalidReason.COLLISION_PENETRATION) && r.invalidReasons().contains(InvalidReason.IMPOSSIBLE_DESCENT), describe(v));
    }
    @Test void theResultVocabularyAndBoundsHold() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        for (Validated v : List.of(single(ps, ps.env(), start, MovementInput.forward(YAW, false), null), single(ps, ps.env(), start, MovementInput.forward(YAW, false), shift(1.5, 0, 0)))) {
            MovementValidationResult r = v.result();
            assertTrue(r.witnesses().size() <= MovementValidationResult.MAX_WITNESSES && r.notes().size() <= MovementValidationResult.MAX_NOTES && r.residual().nearest().size() <= 4);
            assertTrue(Double.isFinite(r.residual().observedHorizontal()) && Double.isFinite(r.residual().horizontalExcess()));
            assertTrue(r.work().classifierOperations() > 0 && r.work().classifierOperations() < 10_000);
            assertEquals(r.classification() == Classification.INVALID, r.proof() != MovementValidationResult.Proof.NONE);
        }
        // the record refuses an unsound INVALID
        assertThrows(IllegalArgumentException.class, () -> new MovementValidationResult(Classification.INVALID, 1, 2, 0, new MovementValidationResult.Completeness(false, true, true, true, true, true, true, true, true, true, true, true, true), List.of(), List.of(), List.of(InvalidReason.NO_LEGAL_INPUT), new MovementValidationResult.Counts(1, 1, 1, 0, 1, 0, 0, 1, 0), List.of(), MovementValidationResult.Proof.NONE, MovementValidationResult.Residual.NONE, new MovementValidationResult.Work(0, 0, 0, 0, 0, 0), new MovementValidationResult.Provenance(0, 0, 1, 2, "t", "m", "o", "c"), new TimingReconstruction.Frontier(List.of()), false, List.of()));
    }
}
