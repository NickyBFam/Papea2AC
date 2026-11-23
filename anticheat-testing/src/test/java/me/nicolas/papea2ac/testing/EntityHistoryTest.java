package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.CombatTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 observer entity history: lifecycle, incarnations and id reuse, the exact 26.2 network numeric path (codec base vs target), teleport
 * and position-sync semantics, first-class unknown, eviction honesty, coverage taint, epoch and session isolation, bounds and ordering.
 */
class EntityHistoryTest {
    @Test void spawnLookupMoveAttackRemove() {
        Rig r = new Rig(); long s0 = r.spawn(7, 10.5, 64, -3.25);
        var l = r.now(7); assertEquals(Status.FOUND, l.status()); assertEquals(10.5, l.revision().x()); assertEquals("minecraft:zombie", l.type()); assertEquals(1, l.key().incarnation());
        assertEquals(Status.PRE_SPAWN, r.at(7, s0).status(), "before (and at) the spawn observation the entity does not exist yet");
        long m = r.move(7, 4096, 0, -2048);
        assertEquals(11.5, r.now(7).revision().x()); assertEquals(-3.75, r.now(7).revision().z());
        assertEquals(10.5, r.at(7, m).revision().x(), "the state before the move sequence is the pre-move state");
        long rm = r.remove(7);
        assertEquals(Status.FOUND, r.at(7, rm).status(), "the removal observation itself: still alive just before it");
        var after = r.now(7); assertEquals(Status.DESPAWNED, after.status()); assertEquals("REMOVED", after.detail());
    }
    @Test void relativeMovesFollowTheVecDeltaCodecExactly() {
        Rig r = new Rig(); r.spawn(1, 0.123456789, 70.00001, -5.5);
        double bx = 0.123456789, by = 70.00001, bz = -5.5;
        int[][] deltas = { { 1, 0, 0 }, { -32768, 5, 32767 }, { 0, 0, 0 }, { 7, -7, 0 }, { 32767, 32767, -32768 }, { 0, 1, 0 } };
        for (int[] d : deltas) {
            r.move(1, d[0], d[1], d[2]);
            if (d[0] != 0 || d[1] != 0 || d[2] != 0) { bx = refDecode(bx, d[0]); by = refDecode(by, d[1]); bz = refDecode(bz, d[2]); }
            var v = r.now(1).revision();
            assertEquals(Double.doubleToRawLongBits(bx), Double.doubleToRawLongBits(v.x()), "x bit-exact"); assertEquals(Double.doubleToRawLongBits(by), Double.doubleToRawLongBits(v.y()));
            assertEquals(Double.doubleToRawLongBits(bz), Double.doubleToRawLongBits(v.z())); assertEquals(v.x(), v.baseX()); assertEquals(EntityRevision.Status.EXACT, v.positionStatus());
        }
        // a zero delta on one axis keeps the base component bit-for-bit (no snap to the 1/4096 grid)
        Rig z = new Rig(); z.spawn(2, 1.0 / 3.0, 0.1, 0.2); z.move(2, 4096, 0, 0);
        assertEquals(Double.doubleToRawLongBits(0.1), Double.doubleToRawLongBits(z.now(2).revision().y()));
    }
    @Test void absoluteTeleportSetsTheTargetButNotTheCodecBase() {
        Rig r = new Rig(); r.spawn(3, 0, 64, 0);
        r.teleport(3, 100, 70, 100, 0);
        var t = r.now(3).revision(); assertEquals(100, t.x()); assertEquals(EntityRevision.Status.EXACT, t.positionStatus()); assertEquals(0, t.baseX(), "handleTeleportEntity leaves the codec base");
        r.move(3, 4096, 0, 0); // decodes from the OLD base (0,64,0), as the 26.2 client does
        assertEquals(1.0, r.now(3).revision().x()); assertEquals(64, r.now(3).revision().y());
    }
    @Test void positionSyncSetsBothTheBaseAndTheTarget() {
        Rig r = new Rig(); r.spawn(4, 0, 64, 0); r.sync(4, 50.25, 65, -9);
        r.move(4, 4096, 0, 0);
        assertEquals(51.25, r.now(4).revision().x()); assertEquals(51.25, r.now(4).revision().baseX());
    }
    @Test void relativeTeleportComponentsAreInterpolationDependentNeverExact() {
        Rig r = new Rig(); r.spawn(5, 0, 64, 0);
        r.teleport(5, 1, 0, 0, EntityObservation.Teleported.REL_X);
        assertEquals(EntityRevision.Status.INTERPOLATION_DEPENDENT, r.now(5).revision().positionStatus());
        r.move(5, 4096, 0, 0); // a later relative move decodes from the exact base: exact again
        assertEquals(EntityRevision.Status.EXACT, r.now(5).revision().positionStatus()); assertEquals(1.0, r.now(5).revision().x());
        r.teleport(5, 90, 0, 0, EntityObservation.Teleported.REL_YAW);
        var v = r.now(5).revision(); assertEquals(EntityRevision.Status.EXACT, v.positionStatus()); assertEquals(EntityRevision.Status.INTERPOLATION_DEPENDENT, v.rotationStatus());
    }
    @Test void rotationOnlyAndMoveRotationPackets() {
        Rig r = new Rig(); r.spawn(6, 1, 64, 1);
        r.rot(6, 180f, -45f); var v = r.now(6).revision();
        assertEquals(1, v.x()); assertEquals(180f, v.yaw()); assertEquals(-45f, v.pitch()); assertEquals("ROT", v.cause());
        r.moveRot(6, 4096, 0, 0, 90f, 10f); v = r.now(6).revision(); assertEquals(2, v.x()); assertEquals(90f, v.yaw()); assertEquals("MOVE_ROT", v.cause());
    }
    @Test void anUpdateBeforeAnyBaselineIsNeverFabricated() {
        Rig r = new Rig(); r.move(9, 4096, 0, 0); r.teleport(9, 5, 5, 5, 0);
        assertEquals(Status.UNKNOWN, r.now(9).status(), "no position zero, no current server position");
        assertEquals(2, r.h.status().updatesForUnknown());
        r.spawn(9, 1, 2, 3); assertEquals(1, r.now(9).revision().x(), "a qualifying spawn establishes the baseline");
    }
    @Test void idReuseCreatesADistinctIncarnationAndNeverLinksBack() {
        Rig r = new Rig(); r.spawn(37, 0, 64, 0); var a = r.now(37).key();
        long beforeRemove = r.move(37, 4096, 0, 0); long rm = r.remove(37); long gapSeq = r.silent(); r.spawn(37, 500, 64, 500);
        var b = r.now(37).key();
        assertNotEquals(a, b); assertEquals(37, b.networkId()); assertTrue(b.incarnation() > a.incarnation());
        assertEquals(Status.ID_REUSED, r.h.lookupKey(a, r.seq + 1).status(), "an old incarnation never resolves to the reuse");
        assertEquals(Status.FOUND, r.h.lookupKey(a, rm).status()); assertEquals(1.0, r.h.lookupKey(a, rm).revision().x());
        assertEquals(Status.DESPAWNED, r.at(37, gapSeq + 1).status(), "between removal and respawn the id is free");
        assertEquals(500, r.now(37).revision().x());
        // a spawn on a live id (no removal) replaces it: a new incarnation, the old one ended
        r.spawn(37, 9, 9, 9); var c = r.now(37).key(); assertTrue(c.incarnation() > b.incarnation());
        assertEquals(Status.ID_REUSED, r.h.lookupKey(b, r.seq + 1).status()); assertEquals(1, r.h.status().replacements());
    }
    @Test void worldEpochChangeEndsEverythingAndNothingCrossesIt() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0); var old = r.now(1).key(); long oldEpoch = r.h.worldEpoch();
        r.epoch(oldEpoch + 1);
        assertEquals(Status.UNKNOWN, r.now(1).status(), "the new epoch starts clean");
        assertEquals(Status.WORLD_EPOCH_MISMATCH, r.h.lookupKey(old, r.seq + 1).status());
        assertEquals(Status.WORLD_EPOCH_MISMATCH, r.h.lookupId(KEY, oldEpoch, 1, r.seq + 1).status());
        r.spawn(1, 5, 5, 5); assertEquals(oldEpoch + 1, r.now(1).key().worldEpoch());
    }
    @Test void sessionIsolationAndClose() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        assertEquals(Status.SESSION_MISMATCH, r.h.lookupId(OTHER, r.h.worldEpoch(), 1, r.seq + 1).status());
        assertFalse(r.h.apply(OTHER, new EntityObservation.Removed(List.of(1)), r.seq + 1, 0, r.nanos), "observations for another session are refused");
        r.h.close(); assertEquals(Status.SESSION_MISMATCH, r.now(1).status());
    }
    @Test void aCoverageGapTaintsLiveEntitiesAndMakesUnknownIdsIncomplete() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0); long before = r.seq; r.gap(); r.move(1, 4096, 0, 0);
        assertEquals(Status.INCOMPLETE_COVERAGE, r.now(1).status(), "a lost observation may have been this entity's update");
        assertEquals(Status.FOUND, r.at(1, before + 1).status(), "the state before the gap stays queryable");
        assertEquals(Status.INCOMPLETE_COVERAGE, r.now(42).status(), "a spawn may have been lost: unknown is not absent");
        r.spawn(50, 1, 1, 1); assertEquals(Status.FOUND, r.now(50).status(), "a spawn observed after the break is a fresh, complete baseline");
        Rig b = new Rig(); b.spawn(2, 0, 0, 0); b.breakCoverage("OVERLOADED"); assertEquals(Status.INCOMPLETE_COVERAGE, b.now(2).status());
    }
    @Test void outOfOrderAndDuplicateObservationsAreRejected() {
        Rig r = new Rig(); long s = r.spawn(1, 0, 64, 0);
        assertFalse(r.h.apply(KEY, new EntityObservation.Spawned(1, new UUID(1, 1), "minecraft:zombie", 0, 64, 0, 0f, 0f, 0f), s, 0, r.nanos), "a replayed duplicate observation");
        assertFalse(r.h.apply(KEY, new EntityObservation.Removed(List.of(1)), s - 1 < 1 ? 1 : s, 0, r.nanos));
        assertEquals(1, r.h.status().generations(), "no impossible extra generation");
        long rm = r.remove(1); assertFalse(r.h.apply(KEY, new EntityObservation.Removed(List.of(1)), rm, 0, r.nanos), "a duplicate removal observation");
        r.remove(1); assertEquals(1, r.h.status().removals(), "removing an already removed id changes nothing"); assertEquals(1, r.h.status().updatesForUnknown());
    }
    @Test void orderingDecidesWhichStateAnAttackSees() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        long move = r.move(1, 4096, 0, 0);          // target update N
        assertEquals(1.0, r.at(1, move + 1).revision().x(), "attack at N+1 sees the update");
        assertEquals(0.0, r.at(1, move).revision().x(), "attack at N (before the update) does not");
    }
    @Test void poseIsRawAndUnknownIsNeverStanding() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        assertEquals("STANDING", r.now(1).revision().pose(), "the synced default at spawn: pairing sends only non-default values");
        r.pose(1, "CROUCHING"); assertEquals("CROUCHING", r.now(1).revision().pose()); assertEquals(1, r.now(1).revision().metadataRevision());
        r.apply(new EntityObservation.DataUpdated(1, null, true, -1)); assertNull(r.now(1).revision().pose(), "an OPAQUE pose field is UNKNOWN");
        r.pose(1, "NOT_A_26_2_POSE"); assertNull(r.now(1).revision().pose(), "a pose the profile does not know is UNKNOWN");
        r.apply(new EntityObservation.DataUpdated(1, null, false, 0x02)); assertEquals(2, r.now(1).revision().sharedFlags());
    }
    @Test void passengersAndUnsupportedMotion() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0); r.spawn(2, 0, 64, 0);
        r.apply(new EntityObservation.Passengers(1, List.of(2))); assertEquals(1, r.now(2).revision().vehicleId());
        r.apply(new EntityObservation.Passengers(1, List.of())); assertEquals(-1, r.now(2).revision().vehicleId());
        r.apply(new EntityObservation.UnsupportedMotion(1, "MINECART_LERP_STEPS")); assertEquals(Status.UNSUPPORTED, r.now(1).status());
        r.sync(1, 3, 64, 3); assertEquals(Status.UNSUPPORTED, r.now(1).status(), "position sync after unsupported motion does not claim exactness of the unsupported mechanism");
    }
    @Test void futureSequencesAreRefused() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        assertEquals(Status.FUTURE_SEQUENCE, r.at(1, r.seq + 2).status());
    }
    @Test void summaryEntitiesKeepOnlyTheirCurrentStateAndSayEvicted() {
        Rig r = new Rig();
        for (int i = 0; i < EntityHistory.MAX_DEEP; i++) r.spawn(100 + i, i, 64, 0);
        long s = r.spawn(500, 0, 64, 0); long m = r.move(500, 4096, 0, 0);
        assertEquals(Status.FOUND, r.now(500).status());
        assertEquals(Status.EVICTED, r.at(500, m).status(), "a summary-only entity never pretends to know its older state");
        r.h.rebalance(0, 64, 0); // entity 500 sits at x=1: nearer than most deep entities, so it is promoted from here on
        long m2 = r.move(500, 4096, 0, 0); assertEquals(1.0, r.at(500, m2).revision().x());
    }
    @Test void deepHistoryIsBoundedAndHonest() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        long first = 0; for (int i = 0; i < EntityHistory.MAX_REVISIONS + 20; i++) { long s = r.move(1, 1, 0, 0); if (i == 0) first = s; }
        assertEquals(Status.EVICTED, r.at(1, first).status(), "revisions beyond 96 aged out: EVICTED, never the nearest retained");
        assertEquals(Status.FOUND, r.now(1).status());
        // horizon: revisions older than three seconds go (the newest older one stays as the floor)
        Rig t = new Rig(); t.spawn(1, 0, 64, 0); long early = t.move(1, 1, 0, 0); for (int i = 0; i < 80; i++) t.move(1, 1, 0, 0); // 80 ticks = 4 s
        assertEquals(Status.EVICTED, t.at(1, early).status());
    }
    @Test void capacityIsNeverSilentlyExceeded() {
        Rig r = new Rig();
        for (int i = 0; i < EntityHistory.MAX_RETAINED; i++) r.spawn(i, i, 64, 0);
        long refused = r.spawn(9999, 0, 64, 0);
        assertEquals(EntityHistory.MAX_RETAINED, r.h.status().live()); assertEquals(1, r.h.status().capacityRefusals());
        assertEquals(Status.INCOMPLETE_COVERAGE, r.at(9999, refused + 1).status(), "the refused entity is unprovable, not absent");
        assertEquals(Status.FOUND, r.now(0).status(), "no live entity was silently dropped to make room");
        // ended incarnations are forgotten first, and their lookups say EVICTED
        Rig e = new Rig(); e.spawn(1, 0, 0, 0); long gone = e.remove(1);
        for (int i = 0; i < EntityHistory.MAX_RETAINED; i++) e.spawn(10 + i, 0, 0, 0);
        assertEquals(Status.EVICTED, e.at(1, gone + 1).status(), "the forgotten removal is not turned into UNKNOWN");
    }
    @Test void aTightByteTierShedsDeepHistoryBeforeRefusing() {
        Rig r = new Rig(KEY, 64 * 1024);
        for (int i = 0; i < 20; i++) r.spawn(i, i, 64, 0);
        for (int k = 0; k < 90; k++) for (int i = 0; i < 20; i++) r.move(i, 1, 0, 0);
        assertTrue(r.h.bytes() <= 64 * 1024, "bytes " + r.h.bytes()); assertEquals(20, r.h.status().live(), "summaries survive; only optional depth was shed");
        assertTrue(r.h.status().historyEvictions() > 0);
    }
    /** Brief section 13: QUERY_LIMIT is first class - a range query never silently truncates, never pads an evicted or tainted range, and says which. */
    @Test void rangeQueriesAreTypedNeverSilentlyTruncatedOrPadded() {
        Rig r = new Rig(); long spawn = r.spawn(7, 0, 64, 0); for (int i = 0; i < 10; i++) r.move(7, 4096, 0, 0);
        var key = r.now(7).key();
        var all = r.h.revisions(key, 0, r.seq + 1, 32);
        assertEquals(Status.FOUND, all.status()); assertEquals(11, all.revisions().size()); assertEquals(spawn, all.revisions().getFirst().sequence());
        var capped = r.h.revisions(key, 0, r.seq + 1, 4);
        assertEquals(Status.QUERY_LIMIT, capped.status()); assertEquals(4, capped.revisions().size(), "the first 4, flagged partial"); assertFalse(capped.complete());
        assertEquals(1, r.h.status().queryLimits());
        var none = r.h.revisions(key, r.seq + 1, r.seq + 1, 4); assertEquals(Status.FOUND, none.status()); assertTrue(none.revisions().isEmpty(), "an empty FOUND range is a proven absence of revisions");
        assertEquals(Status.FUTURE_SEQUENCE, r.h.revisions(key, 0, r.seq + 5, 4).status());
        assertEquals(Status.SESSION_MISMATCH, r.h.revisions(new EntityKey(OTHER, key.worldEpoch(), 7, key.incarnation()), 0, r.seq + 1, 4).status());
        assertEquals(Status.UNKNOWN, r.h.revisions(new EntityKey(KEY, key.worldEpoch(), 7, 99), 0, r.seq + 1, 4).status());
        for (int i = 0; i < EntityHistory.MAX_REVISIONS + 10; i++) r.move(7, 1, 0, 0);
        var aged = r.h.revisions(key, 0, r.seq + 1, EntityHistory.MAX_RANGE_REVISIONS);
        assertEquals(Status.EVICTED, aged.status(), "older revisions of the range aged out: partial, not complete");
        assertTrue(aged.revisions().size() <= EntityHistory.MAX_REVISIONS && aged.revisions().getFirst().sequence() > spawn, "only the retained (3 s horizon / 96 ring) tail");
        r.gap(); r.silent();
        var tainted = r.h.revisions(key, 0, r.seq + 1, EntityHistory.MAX_RANGE_REVISIONS);
        assertEquals(Status.EVICTED, tainted.status(), "eviction is reported first");
        var tail = r.h.revisions(key, tainted.revisions().getFirst().sequence(), r.seq + 1, EntityHistory.MAX_RANGE_REVISIONS);
        assertEquals(Status.INCOMPLETE_COVERAGE, tail.status(), "a coverage break inside the range"); assertTrue(tail.revisions().stream().allMatch(v -> v.sequence() < r.h.status().coverageBreakSequence()));
        assertThrows(IllegalArgumentException.class, () -> r.h.revisions(key, 0, 1, EntityHistory.MAX_RANGE_REVISIONS + 1));
    }
}
