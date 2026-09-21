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
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
import me.nicolas.papea2ac.simulation.MovementValidation.UncertainReason;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Completeness;
import org.junit.jupiter.api.Test;
/**
 * Pre-baseline INVALID safety contract (Phase 8 decision pass): the metamorphic cases the reviewer named that were not yet explicit
 * tests - INVALID + an incomplete branch -> UNCERTAIN, a world query limit -> UNCERTAIN, an unresolved external motion -> UNCERTAIN -
 * and the structural proof that the result constructor rejects INVALID when any single completeness dimension is false.
 */
class ValidationSafetyTest {
    static final float YAW = 0f;
    static Validated invalidCase(PhysicsScene ps) {
        PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Validated v = single(ps, ps.env(), start, MovementInput.forward(YAW, false), shift(1.5, 0, 0));
        assertEquals(Classification.INVALID, v.result().classification(), describe(v));
        return v;
    }
    @Test void invalidPlusAnIncompleteBranchIsUncertain() {
        PhysicsScene ps = new PhysicsScene(); Validated v = invalidCase(ps);
        // a second start whose step reaches an unobserved chunk (x >= 32 is never observed): its branch is incomplete, so nothing may be INVALID any more
        List<TimingReconstruction.Node> nodes = new ArrayList<>(v.request().frontier().nodes());
        nodes.add(new TimingReconstruction.Node(PhysicsState.resting(31.7, SURFACE, 8.5, me.nicolas.papea2ac.collision.PlayerBox.Pose.STANDING, true), Set.of(), "edge"));
        MovementValidationResult r = validator(ps).validate(MovementValidationRequest.of(v.request().timing(), new TimingReconstruction.Frontier(nodes), ps.env()));
        assertEquals(Classification.UNCERTAIN, r.classification(), String.join("\n", r.describe()));
        assertTrue(r.uncertainReasons().contains(UncertainReason.WORLD_INCOMPLETE), r.uncertainReasons().toString()); assertFalse(r.completeness().world() && r.completeness().reconstruction());
    }
    @Test void aWorldQueryLimitIsUncertainForLegitimateAndImpossibleObservationsAlike() {
        PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        // a silent tick then a walk: the silent closure must simulate (the envelope lane alone cannot settle a two-tick interval), so a starved
        // physics query budget is hit; the observation a millionth beyond the truth is INVALID with the full budget and UNCERTAIN when starved
        Truth truth = simulate(ps, ps.env(), start, List.of(MovementInput.idle(YAW), MovementInput.forward(YAW, false)), List.of(), new Recorder(), true);
        Validated inv = validateChain(ps, ps.env(), truth, validator(ps), TimingBudget.DEFAULT, shift(1e-6, 0, 0)).get(0);
        assertEquals(Classification.INVALID, inv.result().classification(), describe(inv)); assertTrue(inv.result().work().evaluations() > 0, describe(inv));
        PhysicsEnvironment starved = ps.env().withBudget(new PhysicsEnvironment.Budget(2, 100000, 512, 2048));
        Validated r = validateChain(ps, starved, truth, validator(ps), TimingBudget.DEFAULT, shift(1e-6, 0, 0)).get(0);
        assertEquals(Classification.UNCERTAIN, r.result().classification(), describe(r));
        assertTrue(r.result().uncertainReasons().contains(UncertainReason.QUERY_LIMIT) || r.result().uncertainReasons().contains(UncertainReason.SEARCH_LIMIT), r.result().uncertainReasons().toString()); assertFalse(r.result().completeness().search());
        Validated ok = validateChain(ps, starved, truth, validator(ps), TimingBudget.DEFAULT, null).get(0);
        assertNotEquals(Classification.INVALID, ok.result().classification(), describe(ok));
    }
    @Test void anUnresolvedExternalMotionInScopeIsUncertainNeverInvalid() {
        PhysicsScene ps = new PhysicsScene(); Validated inv = invalidCase(ps);
        TimingRequest t0 = inv.request().timing();
        long seq = t0.anchorSequence() + 1; List<TimingEvent> events = new ArrayList<>();
        TimingEvent.ExternalMotionSent unresolved = new TimingEvent.ExternalMotionSent(seq, t0.anchorNanos() + 1_000L, 9_000, TimingEvent.Operation.UNRESOLVED, Vector3.ZERO, "declared-unknown");
        events.add(unresolved);
        for (TimingEvent e : t0.events()) events.add(e instanceof TimingEvent.MovementObserved m ? m : e instanceof TimingEvent.ClientTickEnd c ? new TimingEvent.ClientTickEnd(c.sequence() + 1, c.nanos()) : e);
        // keep the sequences strictly increasing after the inserted influence
        List<TimingEvent> shifted = new ArrayList<>(); long last = seq;
        for (TimingEvent e : events) { long s = Math.max(e.sequence(), last + (e == unresolved ? 0 : 1)); last = s; shifted.add(e == unresolved ? e : reseq(e, s)); }
        TimingEvent.MovementObserved target = (TimingEvent.MovementObserved) shifted.get(shifted.size() - 1);
        List<TimingEvent.ExternalMotionSent> pending = new ArrayList<>(t0.pendingInfluences()); pending.add(unresolved);
        TimingRequest tr = new TimingRequest(shifted, t0.anchorSequence(), t0.anchorNanos(), t0.anchorInTick(), t0.lastSentPosition(), t0.lastYaw(), target, pending, t0.teleports(), t0.rtt(), t0.tickEndCapable(), t0.coverageDegraded(), t0.worldEpoch(), t0.nowNanos(), t0.profile(), t0.budget());
        MovementValidationResult r = validator(ps).validate(MovementValidationRequest.of(tr, inv.request().frontier(), ps.env()));
        assertNotEquals(Classification.INVALID, r.classification(), String.join("\n", r.describe()));
        assertTrue(r.uncertainReasons().contains(UncertainReason.EXTERNAL_MOTION_UNRESOLVED) || r.uncertainReasons().contains(UncertainReason.EXTERNAL_MOTION_OVERFLOW) || r.uncertainReasons().contains(UncertainReason.TIMING_INCOMPLETE), r.uncertainReasons().toString());
        assertFalse(r.completeness().externalMotion() && r.completeness().timing(), r.completeness().missing().toString());
    }
    /** P8-F2 (reproduced live: two INVALIDs on a legitimate ladder with residuals 5.6e-5 and ~3e-2): within reach of a climbable the model is not qualified for INVALID. */
    @Test void aClimbableWithinReachForbidsInvalidButKeepsLegitimateClimbsValid() {
        PhysicsScene ps = new PhysicsScene(); for (int y = 70; y <= 75; y++) ps.set(8, y, 10, WorldTestSupport.STONE); for (int y = 70; y <= 74; y++) ps.set(8, y, 9, CollisionTestSupport.LADDER);
        PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
        Validated near = single(ps, ps.env(), start, MovementInput.forward(YAW, false), shift(1.5, 0, 0));
        assertEquals(Classification.UNCERTAIN, near.result().classification(), describe(near)); assertTrue(near.result().uncertainReasons().contains(UncertainReason.CLIMBABLE_CONTACT), describe(near)); assertFalse(near.result().completeness().mechanicSupported());
        List<MovementInput> climb = new ArrayList<>(); for (int i = 0; i < 12; i++) climb.add(MovementInput.forward(YAW, false));
        Truth t = simulate(ps, ps.env(), start, climb, List.of(), new Recorder(), true);
        for (Validated v : validateChain(ps, ps.env(), t, validator(ps), TimingBudget.DEFAULT, null)) { assertEquals(Classification.VALID, v.result().classification(), describe(v)); assertFalse(v.result().completeness().mechanicSupported(), describe(v)); }
        PhysicsState far = settled(ps, 4.5, SURFACE, 8.5); // three blocks west of the ladder: out of reach, INVALID stands
        Validated away = single(ps, ps.env(), far, MovementInput.forward(YAW, false), shift(1.5, 0, 0));
        assertEquals(Classification.INVALID, away.result().classification(), describe(away));
    }
    static TimingEvent reseq(TimingEvent e, long s) {
        if (e instanceof TimingEvent.MovementObserved m) return new TimingEvent.MovementObserved(s, m.nanos(), m.hasPosition(), m.position(), m.hasRotation(), m.rotation(), m.onGround());
        if (e instanceof TimingEvent.ClientTickEnd c) return new TimingEvent.ClientTickEnd(s, c.nanos());
        return e;
    }
    /** Structural proof: for each of the 13 dimensions, an otherwise perfect INVALID result with that single dimension false is rejected by the constructor. */
    @Test void theConstructorRejectsInvalidWheneverAnySingleCompletenessDimensionIsFalse() {
        PhysicsScene ps = new PhysicsScene(); MovementValidationResult r = invalidCase(ps).result();
        assertTrue(r.completeness().all());
        for (int i = 0; i < 13; i++) {
            boolean[] f = new boolean[13]; Arrays.fill(f, true); f[i] = false;
            Completeness c = new Completeness(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11], f[12]);
            assertEquals(1, c.missing().size());
            final Completeness cc = c;
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new MovementValidationResult(Classification.INVALID, r.startSequence(), r.endSequence(), r.worldEpoch(), cc, r.validReasons(), r.uncertainReasons(), r.invalidReasons(), r.counts(), r.witnesses(), r.proof(), r.residual(), r.work(), r.provenance(), r.next(), false, r.notes()), c.missing().toString());
            assertTrue(ex.getMessage().contains(c.missing().get(0)), ex.getMessage());
        }
        // and INVALID with a legal candidate, with an uncertainty reason, or without an exhaustion proof is rejected too
        assertThrows(IllegalArgumentException.class, () -> new MovementValidationResult(Classification.INVALID, r.startSequence(), r.endSequence(), r.worldEpoch(), r.completeness(), r.validReasons(), List.of(UncertainReason.SEARCH_LIMIT), r.invalidReasons(), r.counts(), r.witnesses(), r.proof(), r.residual(), r.work(), r.provenance(), r.next(), false, r.notes()));
        assertThrows(IllegalArgumentException.class, () -> new MovementValidationResult(Classification.INVALID, r.startSequence(), r.endSequence(), r.worldEpoch(), r.completeness(), r.validReasons(), r.uncertainReasons(), r.invalidReasons(), r.counts(), r.witnesses(), MovementValidationResult.Proof.NONE, r.residual(), r.work(), r.provenance(), r.next(), false, r.notes()));
    }
}
