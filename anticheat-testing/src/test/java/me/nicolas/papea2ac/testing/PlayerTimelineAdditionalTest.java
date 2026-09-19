package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.ref.WeakReference;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;

/** Fills the remaining gaps identified by the Phase 2 test-matrix audit: empty timeline, on-ground field
 * fidelity, deep snapshot immutability, deterministic eviction order, invalid numeric input, and eviction
 * resource release. */
class PlayerTimelineAdditionalTest {
    static final SessionKey KEY = new SessionKey(new UUID(30, 31), 1);

    // 1. empty timeline
    @Test void emptyTimelineReportsInitialStateAndZeroesEverywhere() {
        var t = new PlayerTimeline(KEY);
        assertEquals(PlayerState.INITIAL, t.currentState());
        assertEquals(0, t.snapshot().lastCommittedSequence());
        assertEquals(0, t.snapshot().recordCount());
        assertEquals(0, t.snapshot().evictions());
        assertFalse(t.snapshot().lastAppendHadCoverageIssue());
        assertInstanceOf(TimelineLookup.NeverExisted.class, t.stateAtSequence(1));
        assertEquals(0, t.recentStates(10).size());
    }

    // 6. on-ground observation fidelity
    @Test void onGroundAndHorizontalCollisionFlagsAreCarriedExactly() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, new PlayerObservation.MovementClaim(Optional.of(new Vector3(0, 0, 0)), Optional.empty(), false, true), 1, 0);
        assertFalse(t.currentState().movement().onGround());
        assertTrue(t.currentState().movement().horizontalCollision());
    }

    // 10. immutable historical snapshots, beyond simple field immutability: a PlayerState retained by a
    // caller must be untouched by subsequent timeline mutation, including its component sub-objects.
    @Test void retainedHistoricalSnapshotIsUntouchedByLaterMutation() {
        var t = new PlayerTimeline(KEY);
        t.append(KEY, new PlayerObservation.MovementClaim(Optional.of(new Vector3(1, 1, 1)), Optional.of(new Rotation(0, 0)), true, false), 1, 0);
        var retained = t.currentState();
        var retainedMovement = retained.movement();
        for (long seq = 2; seq <= 50; seq++)
            t.append(KEY, new PlayerObservation.MovementClaim(Optional.of(new Vector3(seq, seq, seq)), Optional.of(new Rotation(seq, 0)), true, false), seq, seq);
        assertEquals(new Vector3(1, 1, 1), retained.movement().position(), "the retained PlayerState must not have been mutated in place");
        assertSame(retainedMovement, retained.movement(), "the component object itself must be untouched, not just equal by value");
        assertNotEquals(retained, t.currentState());
    }

    // 18. deterministic eviction: oldest-first, reproducible across two independently constructed timelines
    // fed the exact same input.
    @Test void evictionOrderIsDeterministicAndReproducible() {
        var a = new PlayerTimeline(KEY, PlayerTimeline.TARGET_BYTES);
        var b = new PlayerTimeline(KEY, PlayerTimeline.TARGET_BYTES);
        for (long seq = 1; seq <= 5000; seq++) {
            a.append(KEY, new PlayerObservation.TickObserved(seq), seq, seq);
            b.append(KEY, new PlayerObservation.TickObserved(seq), seq, seq);
        }
        assertEquals(a.snapshot().recordCount(), b.snapshot().recordCount());
        assertEquals(a.allRecords(), b.allRecords(), "the exact same input must evict the exact same records in the exact same order");
        // Oldest-first: the retained window's first record's sequence must be exactly (lastCommitted - count + 1).
        long expectedOldest = a.snapshot().lastCommittedSequence() - a.snapshot().recordCount() + 1;
        assertEquals(expectedOldest, a.allRecords().get(0).captureSequence());
    }

    // 30. invalid numeric input: reducer/timeline must remain structurally valid; nothing is silently coerced.
    @Test void nonFiniteCoordinatesAreRejectedAtConstructionNeverCoerced() {
        assertThrows(IllegalArgumentException.class, () -> new Vector3(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Vector3(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Vector3(Double.NEGATIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(0, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(Float.NEGATIVE_INFINITY, 0));
    }

    @Test void negativeZeroIsAFiniteValidCoordinateNotAnErrorAndIsPreservedExactly() {
        var v = new Vector3(-0.0, 0.0, 0.0);
        assertEquals(0, Double.compare(-0.0, v.x()), "negative zero must be preserved exactly, not normalized to positive zero");
        var t = new PlayerTimeline(KEY);
        assertTrue(t.append(KEY, new PlayerObservation.MovementClaim(Optional.of(v), Optional.empty(), true, false), 1, 0));
        assertEquals(0, Double.compare(-0.0, t.currentState().movement().position().x()));
    }

    @Test void absurdButFiniteCoordinatesAreAcceptedWithoutClamping() {
        // Phase 2 owns finiteness only; version-profile-defined range limits are a later phase's concern
        // (PACKET_PIPELINE.md: "position, angle and semantic limits come from the version profile, not
        // arbitrary clamps").
        var huge = new Vector3(1e300, -1e300, 1e300);
        var t = new PlayerTimeline(KEY);
        assertTrue(t.append(KEY, new PlayerObservation.MovementClaim(Optional.of(huge), Optional.empty(), true, false), 1, 0));
        assertEquals(huge, t.currentState().movement().position(), "no clamping - the exact claimed value is preserved");
    }

    // 35. memory/accounting release: an evicted record must become GC-eligible, not retained by a hidden reference.
    @Test void evictedRecordsBecomeEligibleForGarbageCollection() {
        var t = new PlayerTimeline(KEY, PlayerTimeline.TARGET_BYTES);
        WeakReference<TimelineRecord> firstRecordRef;
        {
            t.append(KEY, new PlayerObservation.TickObserved(1), 1, 0);
            firstRecordRef = new WeakReference<>(t.allRecords().get(0));
        }
        for (long seq = 2; seq <= 5000; seq++) t.append(KEY, new PlayerObservation.TickObserved(seq), seq, seq);
        assertTrue(t.snapshot().evictions() > 0, "the first record must have been evicted by now");
        for (int i = 0; i < 6 && firstRecordRef.get() != null; i++) { System.gc(); try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        assertNull(firstRecordRef.get(), "an evicted record must not be reachable through any hidden reference (index, cache, or current-state chain)");
    }
}
