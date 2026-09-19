package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;

/**
 * Deterministic seeded property/fuzz tests (no new external dependency - java.util.Random with a fixed seed
 * printed on failure, per the originating task's explicit "no new property-testing dependency is required
 * unless clearly justified" guidance). Every seed below is fixed so CI is reproducible; a failure's message
 * always names the seed and the exact step, so a counterexample is reproducible by rerunning that one seed.
 */
class PlayerTimelinePropertyTest {
    static final Vector3[] POSITIONS = { new Vector3(0, 0, 0), new Vector3(1, 64, -1), new Vector3(-500, 5, 500), new Vector3(0.5, 0.5, 0.5) };
    static final Rotation[] ROTATIONS = { new Rotation(0, 0), new Rotation(179.9f, -89f), new Rotation(-400f, 91f) };

    static PlayerObservation randomObservation(Random r, long teleportIdHint) {
        return switch (r.nextInt(7)) {
            case 0 -> new PlayerObservation.MovementClaim(
                    r.nextBoolean() ? Optional.of(POSITIONS[r.nextInt(POSITIONS.length)]) : Optional.empty(),
                    r.nextBoolean() ? Optional.of(ROTATIONS[r.nextInt(ROTATIONS.length)]) : Optional.empty(),
                    r.nextBoolean(), r.nextBoolean());
            case 1 -> new PlayerObservation.TeleportSent(teleportIdHint, POSITIONS[r.nextInt(POSITIONS.length)], ROTATIONS[r.nextInt(ROTATIONS.length)]);
            case 2 -> new PlayerObservation.TeleportAcknowledged(r.nextBoolean() ? teleportIdHint : teleportIdHint + 1); // sometimes a stale/unknown id
            case 3 -> new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.values()[r.nextInt(ExternalMotionInfluence.Mechanism.values().length)], "fuzz", POSITIONS[r.nextInt(POSITIONS.length)], r.nextInt(1000));
            case 4 -> new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.values()[r.nextInt(2)]);
            case 5 -> new PlayerObservation.TickObserved(r.nextInt(100000));
            default -> new PlayerObservation.CoverageGap("FUZZ_" + r.nextInt(4));
        };
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3, 4, 5})
    void sequenceRemainsMonotonicAndHistoryNeverExceedsBound(long seed) {
        var random = new Random(seed);
        var key = new SessionKey(new UUID(0, 1), 1);
        var timeline = new PlayerTimeline(key);
        long sequence = 0;
        for (int i = 0; i < 6000; i++) {
            sequence++;
            final long seq = sequence; final int step = i;
            timeline.append(key, randomObservation(random, seq), seq, seq);
            assertTrue(timeline.snapshot().lastCommittedSequence() <= seq, () -> "seed=" + seed + " step=" + step + ": sequence must never exceed what was offered");
            assertTrue(timeline.snapshot().recordCount() <= 4096, () -> "seed=" + seed + " step=" + step + ": history must never exceed the 4096 bound");
            assertTrue(timeline.snapshot().recordBytes() <= PlayerTimeline.FLOOR_BYTES, () -> "seed=" + seed + " step=" + step + ": byte budget must never be exceeded");
        }
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3, 4, 5})
    void currentStateAlwaysCorrespondsToTheLatestAcceptedObservation(long seed) {
        var random = new Random(seed);
        var key = new SessionKey(new UUID(0, 2), 1);
        var timeline = new PlayerTimeline(key);
        PlayerState expected = PlayerState.INITIAL;
        long sequence = 0;
        for (int i = 0; i < 500; i++) {
            sequence++;
            final long seq = sequence; final int step = i;
            var observation = randomObservation(random, seq);
            boolean accepted = timeline.append(key, observation, seq, seq);
            assertTrue(accepted, () -> "seed=" + seed + " step=" + step + ": a strictly increasing sequence must always be accepted");
            expected = PlayerStateReducer.apply(expected, observation, seq).next();
            final PlayerState expectedNow = expected;
            assertEquals(expectedNow, timeline.currentState(), () -> "seed=" + seed + " step=" + step + ": current state must equal independently re-derived reducer output");
        }
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3})
    void duplicateAndOutOfOrderInputNeverCorruptsState(long seed) {
        var random = new Random(seed);
        var key = new SessionKey(new UUID(0, 3), 1);
        var timeline = new PlayerTimeline(key);
        long sequence = 0;
        for (int i = 0; i < 500; i++) {
            sequence++;
            final long seq = sequence; final int step = i;
            timeline.append(key, randomObservation(random, seq), seq, seq);
            var beforeCorruption = timeline.currentState();
            long committedBefore = timeline.snapshot().lastCommittedSequence();
            // Inject a duplicate (same sequence again) and an out-of-order (older) sequence.
            assertFalse(timeline.append(key, randomObservation(random, seq), seq, seq), () -> "seed=" + seed + " step=" + step + ": duplicate sequence must be rejected");
            if (seq > 1) assertFalse(timeline.append(key, randomObservation(random, seq), seq - 1, seq), () -> "seed=" + seed + " step=" + step + ": out-of-order sequence must be rejected");
            assertSame(beforeCorruption, timeline.currentState(), () -> "seed=" + seed + " step=" + step + ": a rejected append must not mutate state at all");
            assertEquals(committedBefore, timeline.snapshot().lastCommittedSequence());
        }
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3})
    void theSameValidObservationSequenceProducesAnIdenticalTimelineEveryTime(long seed) {
        var key = new SessionKey(new UUID(0, 4), 1);
        List<PlayerObservation> script = new ArrayList<>();
        var recorder = new Random(seed);
        long sequence = 0;
        for (int i = 0; i < 300; i++) script.add(randomObservation(recorder, ++sequence));

        var a = new PlayerTimeline(key); var b = new PlayerTimeline(key);
        for (int i = 0; i < script.size(); i++) { a.append(key, script.get(i), i + 1, i + 1); b.append(key, script.get(i), i + 1, i + 1); }
        assertEquals(a.currentState(), b.currentState(), () -> "seed=" + seed + ": identical input must produce an identical final state");
        assertEquals(a.allRecords(), b.allRecords(), () -> "seed=" + seed + ": identical input must produce an identical retained record sequence");
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3})
    void oldSessionEventsNeverMutateAReplacementSessionsTimeline(long seed) {
        var random = new Random(seed);
        var uuid = new UUID(0, 5);
        var oldKey = new SessionKey(uuid, 1);
        var newKey = new SessionKey(uuid, 2);
        var oldTimeline = new PlayerTimeline(oldKey);
        var newTimeline = new PlayerTimeline(newKey);
        for (int i = 1; i <= 50; i++) newTimeline.append(newKey, randomObservation(random, i), i, i);
        var beforeInterference = newTimeline.currentState();
        // A late event still carrying the OLD epoch must never land on the new timeline via any key mismatch.
        for (int i = 1; i <= 20; i++) assertFalse(newTimeline.append(oldKey, randomObservation(random, i), 51 + i, 51 + i), () -> "seed=" + seed + ": an old-epoch key must never be accepted by the replacement timeline");
        assertSame(beforeInterference, newTimeline.currentState());
        assertNotSame(oldTimeline, newTimeline);
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3})
    void lookupStatusesRemainCorrectAfterArbitraryEviction(long seed) {
        var random = new Random(seed);
        var key = new SessionKey(new UUID(0, 6), 1);
        var timeline = new PlayerTimeline(key, PlayerTimeline.TARGET_BYTES);
        long sequence = 0;
        for (int i = 0; i < 5000; i++) { sequence++; timeline.append(key, randomObservation(random, sequence), sequence, sequence); }
        long last = timeline.snapshot().lastCommittedSequence();
        long oldestRetained = timeline.allRecords().isEmpty() ? last + 1 : timeline.allRecords().get(0).captureSequence();
        assertInstanceOf(TimelineLookup.NeverExisted.class, timeline.stateAtSequence(last + 1000), () -> "seed=" + seed + ": beyond the last committed sequence must be NeverExisted");
        if (oldestRetained > 1) assertInstanceOf(TimelineLookup.Evicted.class, timeline.stateAtSequence(oldestRetained - 1), () -> "seed=" + seed + ": just below the retained window (but within ever-committed range) must be Evicted");
        assertInstanceOf(TimelineLookup.Found.class, timeline.stateAtSequence(last), () -> "seed=" + seed + ": the most recent committed sequence must always be Found");
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 3})
    void worldEpochTransitionPreventsSilentCoordinateContinuityAcrossDiscontinuities(long seed) {
        var random = new Random(seed);
        var key = new SessionKey(new UUID(0, 7), 1);
        var timeline = new PlayerTimeline(key);
        long sequence = 0;
        long discontinuityAt = -1;
        for (int i = 0; i < 200; i++) {
            sequence++;
            var observation = randomObservation(random, sequence);
            timeline.append(key, observation, sequence, sequence);
            if (observation instanceof PlayerObservation.Discontinuity && discontinuityAt < 0) discontinuityAt = sequence;
        }
        if (discontinuityAt > 1) {
            // Everything strictly before the discontinuity's own record belongs to the pre-discontinuity
            // world epoch; the CURRENT epoch has already advanced, so this must never silently answer Found.
            var lookup = timeline.stateBefore(discontinuityAt);
            assertTrue(lookup instanceof TimelineLookup.WorldEpochCrossing || lookup instanceof TimelineLookup.Evicted || lookup instanceof TimelineLookup.PreCapture,
                    () -> "seed=" + seed + ": a lookup spanning a discontinuity must never silently return a Found record from the wrong epoch, got " + lookup);
        }
    }
}
