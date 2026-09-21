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
import org.junit.jupiter.api.Test;
/**
 * Deterministic properties and metamorphic relations of the classification (brief 61-62), over random legitimate transitions
 * (the velocity generator) and their controlled mutations. Seeds are printed on failure.
 * <ul>
 * <li>determinism: the same request classifies identically;</li>
 * <li>a complete legal witness exists -> never INVALID; the witness reproduces the observation;</li>
 * <li>INVALID requires zero legal candidates, all completeness dimensions and a proof; UNCERTAIN carries a reason;</li>
 * <li>metamorphic: an added legal timing branch or input candidate never turns VALID into INVALID (VALID + candidate = VALID);
 *     an added uncertainty never turns UNCERTAIN into INVALID; INVALID + a legal candidate -> VALID; INVALID + an incomplete
 *     branch -> UNCERTAIN; search exhaustion never produces INVALID;</li>
 * <li>no NaN/Infinity in any result; bounded witness/proof/note sizes; a world-epoch mismatch is never VALID as ordinary movement.</li>
 * </ul>
 */
class ValidationPropertyTest {
    static final int CASES = 120;

    static List<Validated> legit(long seed) {
        VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed, true); if (vc == null) return List.of();
        return validateChain(vc.world().ps(), vc.world().env(), vc.truth(), validator(vc.world().ps()), TimingBudget.DEFAULT, null);
    }
    static void finite(MovementValidationResult r) {
        var res = r.residual(); for (double d : new double[] { res.observedHorizontal(), res.observedRise(), res.maxLegalHorizontal(), res.maxLegalRise(), res.minLegalRise(), res.horizontalExcess(), res.verticalExcess() }) assertTrue(Double.isFinite(d));
        for (var n : res.nearest()) assertTrue(Double.isFinite(n.dx()) && Double.isFinite(n.dy()) && Double.isFinite(n.dz()) && Double.isFinite(n.outsideDistance()));
        for (var w : r.witnesses()) if (w.predicted() != null) assertTrue(w.predicted().finite());
        assertTrue(r.witnesses().size() <= MovementValidationResult.MAX_WITNESSES && r.notes().size() <= MovementValidationResult.MAX_NOTES && r.proof().hypothesisIds().size() <= 32 && r.residual().nearest().size() <= 4);
    }

    @Test void theSameRequestClassifiesIdenticallyAndEveryResultIsFiniteAndBounded() {
        int checked = 0;
        for (long seed = 7_000; seed < 7_000 + CASES; seed++) {
            for (Validated v : legit(seed)) {
                MovementValidationResult again = validator(VelocityTimingRoundTripTest.generate(seed, true).world().ps()).validate(v.request());
                assertEquals(v.result().classification(), again.classification(), "seed " + seed);
                assertEquals(v.result().describe(), again.describe(), "seed " + seed);
                finite(v.result()); checked++;
            }
        }
        assertTrue(checked > 500, "checked " + checked);
    }
    @Test void aCompleteLegalWitnessIsNeverInvalidAndReproducesTheObservation() {
        int witnesses = 0;
        for (long seed = 7_000; seed < 7_000 + CASES; seed++) for (Validated v : legit(seed)) {
            MovementValidationResult r = v.result();
            assertNotEquals(Classification.INVALID, r.classification(), "seed " + seed + "\n" + describe(v));
            if (r.classification() == Classification.VALID) {
                assertFalse(r.witnesses().isEmpty());
                for (var w : r.witnesses()) { if (w.teleportApplication() || w.predicted() == null) continue; witnesses++;
                    if (v.target().hasPosition()) assertEquals(v.target().position(), new Vector3(w.predicted().position().x(), w.predicted().position().y(), w.predicted().position().z()), "seed " + seed + ": the witness reproduces the transmitted position");
                    else assertTrue(w.residual().inside(), "seed " + seed + ": a rotation-only/silent witness lies inside the send-threshold ball"); }
            }
        }
        assertTrue(witnesses > 500, "witnesses " + witnesses);
    }
    @Test void invalidRequiresExhaustionAndUncertainCarriesItsReason() {
        int invalid = 0;
        for (long seed = 7_000; seed < 7_000 + 60; seed++) {
            VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed, true); if (vc == null) continue;
            for (Validated v : validateChain(vc.world().ps(), vc.world().env(), vc.truth(), validator(vc.world().ps()), TimingBudget.DEFAULT, shift(0, 0.9, 0))) {
                MovementValidationResult r = v.result(); finite(r);
                switch (r.classification()) {
                    case INVALID -> { invalid++; assertTrue(r.completeness().all() && r.counts().legalCandidates() == 0 && r.uncertainReasons().isEmpty() && r.proof().hypothesesExhausted() > 0 && r.proof().candidatesRemaining() == 0 && r.invalidReasons().contains(InvalidReason.NO_LEGAL_INPUT), "seed " + seed + "\n" + describe(v)); }
                    case UNCERTAIN -> assertFalse(r.uncertainReasons().isEmpty(), "seed " + seed);
                    case VALID -> assertFalse(r.witnesses().isEmpty());
                }
                break; // the first (mutated) transmission decides; later ones start from an empty frontier
            }
        }
        assertTrue(invalid > 30, "invalid " + invalid);
    }
    /** INVALID + an incomplete branch -> UNCERTAIN; UNCERTAIN + more uncertainty -> UNCERTAIN; the frontier flag, the content flag and an unknown context each block INVALID. */
    @Test void addingUncertaintyNeverProducesInvalid() {
        int checked = 0;
        for (long seed = 7_000; seed < 7_000 + 60; seed++) {
            VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed, true); if (vc == null) continue;
            PhysicsScene ps = vc.world().ps(); MovementValidator v = validator(ps);
            List<Validated> chain = validateChain(ps, vc.world().env(), vc.truth(), v, TimingBudget.DEFAULT, shift(0, 0.9, 0));
            if (chain.isEmpty() || chain.get(0).result().classification() != Classification.INVALID) continue;
            MovementValidationRequest base = chain.get(0).request();
            for (MovementValidationRequest more : List.of(base.withFrontierComplete(false), base.withContentRetained(false),
                    new MovementValidationRequest(base.timing(), base.frontier(), base.environment().withAttributes(PhysicsEnvironment.Attributes.ALL_UNKNOWN), base.reconstructionBudget(), true, true),
                    new MovementValidationRequest(base.timing(), base.frontier(), base.environment().withEntities(PhysicsEnvironment.EntityNeighborhood.UNKNOWN), base.reconstructionBudget(), true, true),
                    new MovementValidationRequest(base.timing(), base.frontier(), base.environment().withBorder(PhysicsEnvironment.WorldBorder.UNKNOWN), base.reconstructionBudget(), true, true),
                    new MovementValidationRequest(base.timing(), base.frontier(), base.environment().withEffects(PhysicsEnvironment.Effects.UNKNOWN), base.reconstructionBudget(), true, true))) {
                // (unknown EQUIPMENT is deliberately not in this list: boots matter only where powder snow is touched, so the physics never consults them here and an unknown irrelevant dimension does not block exhaustion)
                MovementValidationResult r = v.validate(more);
                assertNotEquals(Classification.INVALID, r.classification(), "seed " + seed + " added uncertainty must not stay INVALID: " + String.join("\n", r.describe()));
                if (r.classification() == Classification.UNCERTAIN) assertFalse(r.uncertainReasons().isEmpty());
                // more uncertainty on top of an uncertain request stays uncertain (or VALID), never INVALID
                MovementValidationResult again = v.validate(more.withContentRetained(false).withFrontierComplete(false));
                assertNotEquals(Classification.INVALID, again.classification());
                checked++;
            }
        }
        assertTrue(checked > 100, "checked " + checked);
    }
    /** INVALID + a legal candidate -> VALID; VALID + an additional legal branch (a wider frontier) -> VALID. The added candidate is sound by construction: the observation is the exact one-tick successor of another resting start in the same world under the same input. */
    @Test void addingALegalCandidateTurnsInvalidIntoValidAndKeepsValidValid() {
        int flipped = 0, kept = 0;
        for (long seed = 7_000; seed < 7_000 + 120; seed++) {
            VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed, true); if (vc == null) continue;
            if (!vc.truth().ticks().get(0).applied().isEmpty() || !vc.truth().ticks().get(0).transmittedPosition()) continue; // a plain first tick with a transmitted position
            PhysicsScene ps = vc.world().ps(); PhysicsEnvironment env = vc.world().env(); MovementValidator v = validator(ps);
            List<Validated> chain = validateChain(ps, env, vc.truth(), v, TimingBudget.DEFAULT, null);
            if (chain.isEmpty()) continue;
            Validated first = chain.get(0); if (first.result().classification() != Classification.VALID) continue;
            PhysicsState other; try { other = settled(ps, vc.truth().start().position().x() + 0.75, SURFACE, vc.truth().start().position().z()); } catch (AssertionError e) { continue; }
            PhysicsResult pr = ps.step(other, vc.truth().ticks().get(0).input(), env); if (!pr.exact()) continue;
            Vector3 obs = new Vector3(pr.next().position().x(), pr.next().position().y(), pr.next().position().z());
            TimingEvent.MovementObserved mutated = new TimingEvent.MovementObserved(first.target().sequence(), first.target().nanos(), true, obs, first.target().hasRotation(), first.target().rotation(), pr.next().onGround());
            List<TimingEvent> events = new ArrayList<>(first.request().timing().events()); events.set(events.size() - 1, mutated);
            TimingRequest t0 = first.request().timing();
            TimingRequest tr = new TimingRequest(events, t0.anchorSequence(), t0.anchorNanos(), t0.anchorInTick(), t0.lastSentPosition(), t0.lastYaw(), mutated, t0.pendingInfluences(), t0.teleports(), t0.rtt(), t0.tickEndCapable(), t0.coverageDegraded(), t0.worldEpoch(), t0.nowNanos(), t0.profile(), t0.budget());
            MovementValidationResult inv = v.validate(MovementValidationRequest.of(tr, first.request().frontier(), env));
            if (inv.classification() != Classification.INVALID) continue; // the other start's successor happens to be reachable from the true start (or the context is incomplete): not a metamorphic pair
            List<TimingReconstruction.Node> nodes = new ArrayList<>(first.request().frontier().nodes()); nodes.add(new TimingReconstruction.Node(other, first.request().frontier().nodes().get(0).pendingInfluences(), "other"));
            MovementValidationResult plus = v.validate(MovementValidationRequest.of(tr, new TimingReconstruction.Frontier(nodes), env));
            assertEquals(Classification.VALID, plus.classification(), "seed " + seed + ": INVALID + a legal candidate must be VALID:\n" + String.join("\n", plus.describe())); flipped++;
            // VALID + a wider frontier stays VALID
            List<TimingReconstruction.Node> wider = new ArrayList<>(first.request().frontier().nodes()); wider.add(new TimingReconstruction.Node(other, first.request().frontier().nodes().get(0).pendingInfluences(), "extra"));
            MovementValidationResult still = v.validate(MovementValidationRequest.of(first.request().timing(), new TimingReconstruction.Frontier(wider), env));
            assertEquals(Classification.VALID, still.classification(), "seed " + seed); kept++;
        }
        assertTrue(flipped > 10 && kept > 10, "flipped " + flipped + " kept " + kept);
    }
    @Test void searchExhaustionNeverProducesInvalidAndAWorldEpochMismatchIsNeverValid() {
        int checked = 0;
        for (long seed = 7_000; seed < 7_000 + 40; seed++) {
            VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed, true); if (vc == null) continue;
            PhysicsScene ps = vc.world().ps(); MovementValidator v = validator(ps);
            for (Validated x : validateChain(ps, vc.world().env(), vc.truth(), v, TimingBudget.DEFAULT, shift(0.6, 0, 0))) {
                MovementValidationRequest base = x.request();
                List<TimingReconstruction.Node> two = new ArrayList<>(base.frontier().nodes()); two.add(new TimingReconstruction.Node(two.get(0).state().withVelocity(new Vec3d(0, two.get(0).state().velocity().y() - 1e-9, 0)), two.get(0).pendingInfluences(), "twin"));
                MovementValidationResult starved = v.validate(new MovementValidationRequest(base.timing(), new TimingReconstruction.Frontier(two), base.environment(), new ReconstructionRequest.SearchBudget(1, 4096, 3, 256, 64, 4096), true, true));
                assertTrue(starved.uncertainReasons().contains(UncertainReason.SEARCH_LIMIT), "seed " + seed + "\n" + String.join("\n", starved.describe()));
                assertNotEquals(Classification.INVALID, starved.classification(), "seed " + seed + "\n" + String.join("\n", starved.describe()));
                MovementValidationResult epoch = v.validate(new MovementValidationRequest(base.timing(), base.frontier(), base.environment().withView(base.environment().view(), base.environment().worldEpoch() + 1), base.reconstructionBudget(), true, true));
                assertNotEquals(Classification.VALID, epoch.classification(), "seed " + seed); assertTrue(epoch.uncertainReasons().contains(UncertainReason.DISCONTINUITY) || epoch.uncertainReasons().contains(UncertainReason.WORLD_INCOMPLETE), "seed " + seed + " " + epoch.uncertainReasons());
                checked++; break;
            }
        }
        assertTrue(checked > 20, "checked " + checked);
    }
}
