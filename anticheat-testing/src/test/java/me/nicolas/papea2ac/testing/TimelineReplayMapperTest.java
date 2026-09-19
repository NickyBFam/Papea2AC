package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.schema.TimelineCapsule;

/**
 * Proves the replay projection boundary: TimelineReplayMapper exports the ORIGINAL causal PlayerObservation
 * (never the derived PlayerState) into a replay-schema DTO, and restoring + re-running the real
 * PlayerStateReducer against it reproduces the exact original TimelineRecord - genuine replay, not opaque
 * output storage. Also proves replay never increases trust: an incomplete capsule cannot decode as complete.
 */
class TimelineReplayMapperTest {
    static final Vector3 POS = new Vector3(1, 2, 3);
    static final Rotation ROT = new Rotation(9f, 1f);

    // --- state -> projection, for every sealed PlayerObservation variant ---

    @Test void movementWithBothFieldsProjectsBothPresent() {
        var dto = (TimelineCapsule.Movement) TimelineReplayMapper.export(new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false));
        assertTrue(dto.hasPosition()); assertTrue(dto.hasRotation());
        assertEquals(1, dto.x()); assertEquals(9f, dto.yaw());
    }

    @Test void movementWithOnlyRotationProjectsPositionAbsent() {
        var dto = (TimelineCapsule.Movement) TimelineReplayMapper.export(new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(ROT), true, false));
        assertFalse(dto.hasPosition());
        assertTrue(dto.hasRotation());
    }

    @Test void movementOutcomeProjectsResultAndPosition() {
        var dto = (TimelineCapsule.Outcome) TimelineReplayMapper.export(new PlayerObservation.MovementOutcome(PlayerObservation.ApplyResult.APPLIED, POS));
        assertEquals("APPLIED", dto.result());
    }

    @Test void teleportSentAndAcknowledgedProjectCorrectly() {
        var sentDto = (TimelineCapsule.TeleportSent) TimelineReplayMapper.export(new PlayerObservation.TeleportSent(7, POS, ROT));
        assertEquals(7, sentDto.teleportId());
        var ackDto = (TimelineCapsule.TeleportAcknowledged) TimelineReplayMapper.export(new PlayerObservation.TeleportAcknowledged(7));
        assertEquals(7, ackDto.teleportId());
    }

    @Test void externalMotionProjectsMechanismAsAStableName() {
        var dto = (TimelineCapsule.ExternalMotion) TimelineReplayMapper.export(new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA, "creeper", POS, 5));
        assertEquals("VELOCITY_DELTA", dto.mechanism());
        assertEquals("creeper", dto.source());
    }

    @Test void discontinuityCoverageGapTickAndSessionClosedProjectCorrectly() {
        assertEquals("RESPAWN", ((TimelineCapsule.Discontinuity) TimelineReplayMapper.export(new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN))).kind());
        assertEquals("PIPELINE_UNCOVERED", ((TimelineCapsule.CoverageGap) TimelineReplayMapper.export(new PlayerObservation.CoverageGap("PIPELINE_UNCOVERED"))).reason());
        assertEquals(42, ((TimelineCapsule.Tick) TimelineReplayMapper.export(new PlayerObservation.TickObserved(42))).serverTick());
        assertInstanceOf(TimelineCapsule.SessionClosed.class, TimelineReplayMapper.export(new PlayerObservation.SessionClosed()));
    }

    // --- projection encode/decode round trip ---

    @Test void everyVariantRoundTripsExactlyThroughExportAndRestore() {
        List<PlayerObservation> observations = List.of(
                new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false),
                new PlayerObservation.MovementClaim(Optional.of(POS), Optional.empty(), false, true),
                new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(ROT), true, false),
                new PlayerObservation.MovementOutcome(PlayerObservation.ApplyResult.TRANSFORMED_APPLIED, POS),
                new PlayerObservation.TeleportSent(3, POS, ROT),
                new PlayerObservation.TeleportAcknowledged(3),
                new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_REPLACE, "entity:9", POS, 1),
                new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.DIMENSION_CHANGE),
                new PlayerObservation.TickObserved(100),
                new PlayerObservation.CoverageGap("OVERLOADED"),
                new PlayerObservation.SessionClosed());
        for (var observation : observations) {
            var restored = TimelineReplayMapper.restore(TimelineReplayMapper.export(observation));
            assertEquals(observation, restored, () -> "round-trip mismatch for " + observation);
        }
    }

    @Test void repeatedEncodingOfTheSameObservationIsDeterministic() {
        var observation = new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false);
        assertEquals(TimelineReplayMapper.export(observation), TimelineReplayMapper.export(observation));
    }

    // --- full capsule replay: re-running the real reducer reproduces the original session exactly ---

    @Test void replayingACapsuleReproducesTheExactOriginalTimelineRecords() {
        var key = new SessionKey(new UUID(21, 22), 1);
        var timeline = new PlayerTimeline(key);
        timeline.append(key, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 1, 0);
        timeline.append(key, new PlayerObservation.TeleportSent(4, POS, ROT), 2, 0);
        timeline.append(key, new PlayerObservation.TeleportAcknowledged(4), 3, 0);
        timeline.append(key, new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA, "explosion", POS, 1), 4, 0);
        timeline.append(key, new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN), 5, 0);

        var original = timeline.allRecords();
        var capsule = TimelineReplayMapper.export(timeline, key.epoch());
        assertTrue(capsule.complete(), "no eviction occurred, so this capsule must be complete");
        var replay = TimelineReplayMapper.replay(capsule);

        assertEquals(original, replay.records(), "re-running the real reducer against restored inputs must reproduce the exact original records");
        assertEquals(timeline.currentState(), replay.finalState());
        assertTrue(replay.complete());
    }

    @Test void incompleteCoverageCanNeverDecodeAsCompleteCoverage() {
        assertThrows(IllegalArgumentException.class, () -> new TimelineCapsule(1, 1, List.of(), true, "some reason"),
                "TimelineCapsule must structurally refuse to claim completeness while carrying an incomplete reason");
    }

    @Test void evictedHistoryProducesAnHonestlyIncompleteCapsule() {
        var key = new SessionKey(new UUID(23, 24), 1);
        var timeline = new PlayerTimeline(key, PlayerTimeline.FLOOR_BYTES);
        for (long seq = 1; seq <= 5000; seq++) timeline.append(key, new PlayerObservation.TickObserved(seq), seq, seq);
        var capsule = TimelineReplayMapper.export(timeline, key.epoch());
        assertFalse(capsule.complete(), "a timeline that has evicted records cannot honestly export as complete");
        assertTrue(capsule.incompleteReason().contains("EVICTED"));
        // What IS retained must still replay deterministically, even though the prefix before it is gone.
        var replay = TimelineReplayMapper.replay(capsule);
        assertFalse(replay.complete());
        assertEquals(timeline.allRecords().size(), replay.records().size());
    }

    @Test void worldEpochDiscontinuityIsReproducedIdenticallyOnReplay() {
        var key = new SessionKey(new UUID(25, 26), 1);
        var timeline = new PlayerTimeline(key);
        timeline.append(key, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 1, 0);
        timeline.append(key, new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN), 2, 0);
        timeline.append(key, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 3, 0);

        var replay = TimelineReplayMapper.replay(TimelineReplayMapper.export(timeline, key.epoch()));
        assertEquals(1, timeline.currentState().worldEpoch());
        assertEquals(1, replay.finalState().worldEpoch());
        assertEquals(List.of(0L, 1L, 1L), replay.records().stream().map(TimelineRecord::worldEpoch).toList());
    }

    @Test void coverageGapUncertaintyIsPreservedNotSilentlyHealedByReplay() {
        var key = new SessionKey(new UUID(27, 28), 1);
        var timeline = new PlayerTimeline(key);
        timeline.append(key, new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false), 1, 0);
        timeline.append(key, new PlayerObservation.CoverageGap("PIPELINE_UNCOVERED"), 2, 0);

        var replay = TimelineReplayMapper.replay(TimelineReplayMapper.export(timeline, key.epoch()));
        var replayedGap = replay.records().get(1);
        assertFalse(replayedGap.certain());
        assertEquals(List.of("PIPELINE_UNCOVERED"), replayedGap.uncertaintyReasons());
    }
}
