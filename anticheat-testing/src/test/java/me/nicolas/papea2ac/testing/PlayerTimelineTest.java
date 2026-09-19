package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;

class PlayerTimelineTest {
    static final SessionKey KEY = new SessionKey(new UUID(3, 4), 1);
    static final Vector3 POS = new Vector3(1, 2, 3);
    static final Rotation ROT = new Rotation(0f, 0f);
    static PlayerObservation.MovementClaim move() { return new PlayerObservation.MovementClaim(Optional.of(POS), Optional.of(ROT), true, false); }

    @Test void appendAdvancesCurrentStateAndCommittedSequence() {
        var t = new PlayerTimeline(KEY);
        assertTrue(t.append(KEY, move(), 1, 0));
        assertEquals(POS, t.currentState().movement().position());
        assertEquals(1, t.snapshot().lastCommittedSequence());
    }

    @Test void staleSessionAppendIsRejectedAndNeverMutatesState() {
        var t = new PlayerTimeline(KEY);
        var before = t.currentState();
        assertFalse(t.append(new SessionKey(KEY.player(), KEY.epoch() + 1), move(), 1, 0));
        assertSame(before, t.currentState());
        assertEquals(0, t.snapshot().lastCommittedSequence());
    }

    @Test void nonMonotonicSequenceIsRejectedAsDuplicateOrOutOfOrder() {
        var t = new PlayerTimeline(KEY);
        assertTrue(t.append(KEY, move(), 5, 0));
        assertFalse(t.append(KEY, move(), 5, 0), "duplicate sequence must be a no-op");
        assertFalse(t.append(KEY, move(), 3, 0), "out-of-order (older) sequence must be a no-op");
        assertEquals(5, t.snapshot().lastCommittedSequence());
    }

    @Test void appendFromANonOwningThreadThrows() throws Exception {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        var pool = Executors.newSingleThreadExecutor();
        try { pool.submit(() -> assertThrows(IllegalStateException.class, () -> t.append(KEY, move(), 2, 0))).get(); }
        finally { pool.shutdownNow(); }
    }

    @Test void closedTimelineRejectsFurtherAppends() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        assertTrue(t.append(KEY, new PlayerObservation.SessionClosed(), 2, 0));
        assertFalse(t.append(KEY, move(), 3, 0));
    }

    @Test void stateAtSequenceFindsAnExactCommittedRecord() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        t.append(KEY, new PlayerObservation.TickObserved(9), 2, 0);
        var lookup = t.stateAtSequence(1);
        assertInstanceOf(TimelineLookup.Found.class, lookup);
        assertEquals(TimelineRecord.Kind.MOVEMENT, ((TimelineLookup.Found) lookup).record().kind());
    }

    @Test void stateAtSequenceBeyondLastCommittedIsNeverExisted() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        assertInstanceOf(TimelineLookup.NeverExisted.class, t.stateAtSequence(50));
    }

    @Test void stateAtSequenceBeforeThisTimelinesOwnFirstEverAppendIsPreCapture() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 5, 0); // this timeline instance never saw sequences 1-4
        assertInstanceOf(TimelineLookup.PreCapture.class, t.stateAtSequence(2));
    }

    @Test void stateAtSequenceForAnAgedOutRecordIsEvicted() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        t.append(KEY, new PlayerObservation.TickObserved(1), 2, PlayerTimeline.HORIZON_NANOS + 1);
        assertInstanceOf(TimelineLookup.Evicted.class, t.stateAtSequence(1));
    }

    /** At the 1 MiB target tier, small TickObserved records (144 bytes charged each, well under the
     * 1 MiB/4096 = 256 bytes/record average the count bound implies) never reach the byte ceiling first, so
     * this isolates the pure 4096-record count bound (PERFORMANCE.md's Timeline row: "4096 records ...
     * whichever first"). */
    @Test void countBoundEvictsTheOldestPastFourThousandNinetySixWhenByteBudgetHasHeadroom() {
        var t = new PlayerTimeline(KEY, PlayerTimeline.TARGET_BYTES);
        for (long seq = 1; seq <= 4097; seq++) t.append(KEY, new PlayerObservation.TickObserved(seq), seq, seq);
        assertEquals(4096, t.snapshot().recordCount());
        assertTrue(t.snapshot().recordBytes() <= PlayerTimeline.TARGET_BYTES);
        assertInstanceOf(TimelineLookup.Evicted.class, t.stateAtSequence(1));
        assertInstanceOf(TimelineLookup.Found.class, t.stateAtSequence(4097));
        assertTrue(t.snapshot().evictions() >= 1);
    }

    /** At the 512 KiB floor tier, the SAME 144-byte-per-record TickObserved stream exhausts the byte budget
     * (524288/144 is well under 4096) before ever reaching the count ceiling - proving the byte bound can bind
     * first, exactly as PERFORMANCE.md's "whichever first" phrasing requires, not just the count bound alone. */
    @Test void byteBoundEvictsBeforeTheCountCeilingWhenRecordsAreLargeRelativeToTheFloorTier() {
        var t = new PlayerTimeline(KEY, PlayerTimeline.FLOOR_BYTES);
        for (long seq = 1; seq <= 4097; seq++) t.append(KEY, new PlayerObservation.TickObserved(seq), seq, seq);
        assertTrue(t.snapshot().recordCount() < 4096, "byte bound must have evicted before the count ceiling was ever reached");
        assertTrue(t.snapshot().recordBytes() <= PlayerTimeline.FLOOR_BYTES);
        assertInstanceOf(TimelineLookup.Found.class, t.stateAtSequence(4097));
    }

    /** A time gap alone (no count/byte pressure at all - only two records ever appended) evicts past the
     * three-second horizon, proving the time bound is independently enforced, not merely implied by count/byte
     * pressure. */
    @Test void timeBoundEvictsPurelyOnElapsedHorizonWithNoCountOrBytePressure() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, new PlayerObservation.TickObserved(1), 1, 0);
        t.append(KEY, new PlayerObservation.TickObserved(2), 2, PlayerTimeline.HORIZON_NANOS + 1);
        assertEquals(1, t.snapshot().recordCount(), "only the still-fresh second record should remain");
        assertInstanceOf(TimelineLookup.Evicted.class, t.stateAtSequence(1));
        assertInstanceOf(TimelineLookup.Found.class, t.stateAtSequence(2));
    }

    /** No bound may be exceeded regardless of which one binds first: even under adversarial append pressure at
     * the floor tier, count/byte ceilings must both hold simultaneously at every observed point. */
    @Test void noBoundIsEverExceededRegardlessOfWhichBindsFirst() {
        var t = new PlayerTimeline(KEY, PlayerTimeline.FLOOR_BYTES);
        for (long seq = 1; seq <= 5000; seq++) {
            t.append(KEY, new PlayerObservation.TickObserved(seq), seq, seq);
            assertTrue(t.snapshot().recordCount() <= 4096);
            assertTrue(t.snapshot().recordBytes() <= PlayerTimeline.FLOOR_BYTES);
        }
    }

    @Test void stateBeforeRefusesToSilentlyCrossAWorldEpochBoundary() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        t.append(KEY, new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN), 2, 0);
        var lookup = t.stateBefore(2);
        assertInstanceOf(TimelineLookup.WorldEpochCrossing.class, lookup);
    }

    @Test void stateBeforeWithinTheSameEpochFindsTheRecord() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        t.append(KEY, new PlayerObservation.TickObserved(1), 2, 0);
        assertInstanceOf(TimelineLookup.Found.class, t.stateBefore(2));
    }

    @Test void recentStatesIsBoundedByWhatIsActuallyRetained() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        t.append(KEY, new PlayerObservation.TickObserved(1), 2, 0);
        assertEquals(2, t.recentStates(10).size(), "cannot fabricate more records than were ever appended");
        assertEquals(1, t.recentStates(1).size());
    }

    @Test void snapshotRangeReturnsOnlyTheClosedIntervalRequested() {
        var t = new PlayerTimeline(KEY);
        for (long seq = 1; seq <= 5; seq++) t.append(KEY, new PlayerObservation.TickObserved(seq), seq, 0);
        var range = t.snapshotRange(2, 4);
        assertEquals(List.of(2L, 3L, 4L), range.stream().map(TimelineRecord::captureSequence).toList());
    }

    @Test void snapshotIsReadableWithoutOwnershipFromAnyThread() throws Exception {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, move(), 1, 0);
        var pool = Executors.newSingleThreadExecutor();
        try { assertEquals(1L, pool.submit(() -> t.snapshot().lastCommittedSequence()).get()); }
        finally { pool.shutdownNow(); }
    }
}
