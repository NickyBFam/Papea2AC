package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;

/** Pure reducer tests: no Paper, no threads, no I/O - deterministic transition tables only (TESTING.md's independent-oracle layer). */
class PlayerStateReducerTest {
    static final Vector3 POS = new Vector3(1, 2, 3);
    static final Rotation ROT = new Rotation(10f, 5f);

    @Test void movementClaimUpdatesBothComponentsWhenBothPresent() {
        var t = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 1);
        assertEquals(POS, t.next().movement().position());
        assertEquals(ROT, t.next().rotation().rotation());
        assertEquals(TimelineRecord.Kind.MOVEMENT, t.kind());
        assertTrue(t.uncertaintyReasons().isEmpty());
        assertFalse(t.discontinuity());
    }

    @Test void positionOnlyClaimLeavesRotationComponentAsTheSameReference() {
        var seeded = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(ROT), true, false), 1).next();
        var t = PlayerStateReducer.apply(seeded, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.empty(), true, false), 2);
        assertSame(seeded.rotation(), t.next().rotation(), "unclaimed component must be the same reference (structural sharing)");
        assertEquals(POS, t.next().movement().position());
    }

    @Test void rotationOnlyClaimLeavesMovementComponentAsTheSameReference() {
        var seeded = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.empty(), true, false), 1).next();
        var t = PlayerStateReducer.apply(seeded, new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(ROT), true, false), 2);
        assertSame(seeded.movement(), t.next().movement());
        assertEquals(ROT, t.next().rotation().rotation());
    }

    @Test void unrelatedComponentsAreAlwaysStructurallySharedAcrossAnyTransition() {
        var seeded = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 1).next();
        var t = PlayerStateReducer.apply(seeded, new PlayerObservation.TickObserved(5), 2);
        assertSame(seeded.movement(), t.next().movement());
        assertSame(seeded.rotation(), t.next().rotation());
        assertSame(seeded.teleport(), t.next().teleport());
        assertSame(seeded.velocity(), t.next().velocity());
    }

    @Test void rejectedMovementOutcomeIsUncertainButStillRecordsFinalPosition() {
        var t = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementOutcome(PlayerObservation.ApplyResult.REJECTED, POS), 1);
        assertEquals(List.of("MOVEMENT_OUTCOME_REJECTED"), t.uncertaintyReasons());
        assertEquals(PlayerObservation.ApplyResult.REJECTED, t.next().movement().lastOutcome());
    }

    @Test void appliedMovementOutcomeIsCertain() {
        var t = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementOutcome(PlayerObservation.ApplyResult.APPLIED, POS), 1);
        assertTrue(t.uncertaintyReasons().isEmpty());
    }

    @Test void teleportSentThenAcknowledgedMatchingIdAdvances() {
        var sent = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.TeleportSent(7, POS, ROT), 1).next();
        assertEquals(7L, sent.teleport().active().orElseThrow().teleportId());
        assertFalse(sent.teleport().active().orElseThrow().acknowledged());
        var acked = PlayerStateReducer.apply(sent, new PlayerObservation.TeleportAcknowledged(7), 2);
        assertTrue(acked.next().teleport().active().orElseThrow().acknowledged());
        assertTrue(acked.uncertaintyReasons().isEmpty());
    }

    @Test void teleportAcknowledgedWithUnknownIdNeverAdvancesAnyFrontier() {
        var sent = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.TeleportSent(7, POS, ROT), 1).next();
        var t = PlayerStateReducer.apply(sent, new PlayerObservation.TeleportAcknowledged(999), 2);
        assertSame(sent, t.next(), "stale/unknown teleport ack must be a complete no-op");
        assertEquals(List.of("UNKNOWN_TELEPORT_ID"), t.uncertaintyReasons());
    }

    @Test void teleportSentMovesPreviousActiveIntoDiagnosticPredecessorsBoundedAtEight() {
        var state = PlayerState.INITIAL;
        for (int i = 1; i <= 10; i++) state = PlayerStateReducer.apply(state, new PlayerObservation.TeleportSent(i, POS, ROT), i).next();
        assertEquals(10L, state.teleport().active().orElseThrow().teleportId());
        assertEquals(TeleportState.DIAGNOSTIC_BOUND, state.teleport().diagnosticPredecessors().size());
        assertEquals(9L, state.teleport().diagnosticPredecessors().get(0).teleportId(), "most recent predecessor first");
    }

    @Test void externalMotionOverflowsAfterEightSimultaneousActiveInfluencesAndStaysVisible() {
        var state = PlayerState.INITIAL;
        PlayerStateReducer.Transition last = null;
        for (int i = 1; i <= 9; i++) {
            last = PlayerStateReducer.apply(state, new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA, "creeper", POS, i), i);
            state = last.next();
        }
        assertEquals(VelocityState.ACTIVE_BOUND, state.velocity().active().size());
        assertTrue(state.velocity().overflow());
        assertEquals(List.of("UNCERTAIN_APPLICATION_OVERFLOW"), last.uncertaintyReasons());
        assertEquals(9, state.velocity().diagnostics().size(), "diagnostics keep visibility even for the overflowed influence");
    }

    @Test void discontinuityIncrementsWorldEpochAndFlagsDiscontinuity() {
        var t = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN), 1);
        assertEquals(1, t.next().worldEpoch());
        assertTrue(t.discontinuity());
    }

    @Test void coverageGapNeverMutatesState() {
        var seeded = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 1).next();
        var t = PlayerStateReducer.apply(seeded, new PlayerObservation.CoverageGap("PIPELINE_UNCOVERED"), 2);
        assertSame(seeded, t.next());
        assertEquals(List.of("PIPELINE_UNCOVERED"), t.uncertaintyReasons());
    }

    @Test void reducerIsPureAndDeterministicForTheSameInputs() {
        var a = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 5);
        var b = PlayerStateReducer.apply(PlayerState.INITIAL, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 5);
        assertEquals(a.next(), b.next());
    }
}
