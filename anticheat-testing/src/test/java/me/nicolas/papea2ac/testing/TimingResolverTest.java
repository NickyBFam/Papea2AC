package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/** The core timing matrix (brief section 60) against synthetic traces: alignment, gaps, influences, corrections, discontinuities, bounds, determinism. */
class TimingResolverTest {
    static final TimingResolver RESOLVER = new TimingResolver();
    static final Vector3 P0 = new Vector3(8.5, SURFACE, 8.5);

    /** k ticks with tick ends, then a position transmission; returns the request for the last transmission. */
    static TimingRequest walk(Recorder rec, int k, TimingProfile profile) {
        long anchor = rec.sequence, anchorNanos = rec.nanos;
        rec.advance(1_000_000L); rec.tickEnd(); // the anchor's own tick end
        for (int i = 1; i < k; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); }
        rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        return request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), profile, TimingBudget.DEFAULT, rec.nanos + 1);
    }

    @Test void tickEndCountIsTheExactHorizon() {
        for (int k = 1; k <= 3; k++) {
            Recorder rec = new Recorder(); rec.tickEnd(); // capability proven earlier in the session
            TimingResolution r = RESOLVER.resolve(walk(rec, k, TimingProfile.PAPER_26_2));
            assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status(), r.describe().toString());
            assertEquals(k, r.hypotheses().get(0).horizon()); assertEquals(TimingHypothesis.Alignment.TICK_END_EXACT, r.hypotheses().get(0).alignment());
            assertTrue(r.complete());
            for (int t = 0; t < k - 1; t++) assertInstanceOf(TickObservation.WithinSendThreshold.class, r.hypotheses().get(0).ticks().get(t).position(), "silent intermediate tick");
            assertInstanceOf(TickObservation.Exact.class, r.hypotheses().get(0).ticks().get(k - 1).position());
        }
    }
    /** More than three ticks: the leading silent ticks go to the exact silent closure, the last three to the observation list; beyond the closure bound it is UNSUPPORTED_HORIZON, never a truncation. */
    @Test void moreThanThreeTicksUsesTheSilentClosureAndBeyondItIsUnsupported() {
        Recorder rec = new Recorder(); rec.tickEnd();
        TimingResolution r = RESOLVER.resolve(walk(rec, 5, TimingProfile.PAPER_26_2));
        assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status(), r.describe().toString()); assertTrue(r.complete());
        assertEquals(3, r.hypotheses().get(0).horizon()); assertEquals(2, r.hypotheses().get(0).closureTicks());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("5 client ticks elapsed")), r.notes().toString());
        Recorder far = new Recorder(); far.tickEnd();
        TimingResolution u = RESOLVER.resolve(walk(far, 3 + TimingResolver.MAX_CLOSURE_TICKS + 1, TimingProfile.PAPER_26_2));
        assertEquals(TimingResolution.Status.UNSUPPORTED_HORIZON, u.status()); assertTrue(u.hypotheses().isEmpty()); assertFalse(u.complete());
    }
    @Test void noTickEndCapabilityGivesTheBoundedFallbackWithCompletenessFalse() {
        Recorder rec = new Recorder(); // never a tick end
        long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.TIMING_GAP, r.status()); assertFalse(r.complete()); assertEquals(3, r.hypotheses().size());
        assertEquals(List.of(1, 2, 3), r.hypotheses().stream().map(TimingHypothesis::horizon).toList());
        assertTrue(r.hypotheses().stream().allMatch(h -> h.alignment() == TimingHypothesis.Alignment.BOUNDED_FALLBACK));
    }
    @Test void twoTransmissionsWithoutATickEndBetweenIsATimingGap() {
        Recorder rec = new Recorder(); rec.tickEnd();
        long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L);
        TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.TIMING_GAP, r.status()); assertFalse(r.complete()); assertEquals(3, r.hypotheses().size());
    }
    @Test void degradedCoverageNeverTrustsTheCounts() {
        Recorder rec = new Recorder(); rec.tickEnd(); rec.coverageBreak("INGRESS_FULL");
        TimingResolution r = RESOLVER.resolve(walk(rec, 2, TimingProfile.PAPER_26_2));
        assertEquals(TimingResolution.Status.TIMING_GAP, r.status()); assertFalse(r.complete()); assertEquals(3, r.hypotheses().size());
    }
    @Test void aWorldDiscontinuityInsideTheIntervalIsNeverCrossed() {
        Recorder rec = new Recorder(); rec.tickEnd();
        long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(TICK_NANOS); rec.discontinuity(1); rec.tickEnd(); rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved target = rec.movement(100.5, 80, 100.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.WORLD_DISCONTINUITY, r.status()); assertTrue(r.hypotheses().isEmpty());
    }
    @Test void rotationOnlyPacketsGiveTheirTickTheYaw() {
        Recorder rec = new Recorder(); rec.tickEnd();
        long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); // anchor tick end
        rec.advance(TICK_NANOS); rec.rotationOnly(45f, true); rec.tickEnd();   // tick 1: yaw 45, silent
        rec.advance(TICK_NANOS); rec.tickEnd();                                // tick 2: silent, yaw carries
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 90f, true); // tick 3
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status()); List<TickObservation> t = r.hypotheses().get(0).ticks();
        assertEquals(3, t.size()); assertEquals(45f, t.get(0).yaw()); assertEquals(45f, t.get(1).yaw()); assertEquals(90f, t.get(2).yaw()); assertEquals(1, r.evidence().rotationOnlyPackets());
    }
    /** An influence sent AFTER a tick's end was captured cannot have applied to that tick: the earliest application tick moves. */
    @Test void aTickWhoseEndWasCapturedBeforeTheInfluenceLeftIsExcludedFromApplication() {
        Recorder rec = new Recorder(); rec.tickEnd();
        long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); // anchor tick end
        rec.advance(TICK_NANOS); rec.tickEnd();                  // tick 1 ended before the velocity was sent
        rec.advance(10_000_000L); TimingEvent.ExternalMotionSent v = rec.velocity(0.3, 0.4, 0.0);
        rec.advance(TICK_NANOS); rec.tickEnd();                  // tick 2
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.9, SURFACE + 0.3, 8.5, 0f, false); // tick 3
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.RESOLVED_AMBIGUOUS, r.status(), r.describe().toString());
        Set<String> ids = new TreeSet<>(); for (TimingHypothesis h : r.hypotheses()) ids.add(h.id());
        assertEquals(Set.of("h3-" + v.id() + "@2", "h3-" + v.id() + "@3", "h3-" + v.id() + "@later"), ids, "application at tick 1 excluded, ticks 2/3 and 'later' kept");
        for (TimingHypothesis h : r.hypotheses()) if (h.id().endsWith("@2")) assertEquals(1, h.branch().motionsPerTick().get(1).size());
    }
    @Test void orderedInfluencesApplyInWireOrderAndTheProductIsBounded() {
        Recorder rec = new Recorder(); rec.tickEnd();
        long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); rec.advance(1_000_000L);
        TimingEvent.ExternalMotionSent a = rec.velocity(0.1, 0.4, 0), b = rec.explosion(0.2, 0.3, 0);
        for (int i = 1; i < 3; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); }
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.9, SURFACE + 0.3, 8.5, 0f, false);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        // a at tick i, b at tick j >= i, plus b later (a placed), plus both later: 6 + 3 + 1 = 10 hypotheses, all ordered
        assertEquals(10, r.hypotheses().size(), r.describe().toString());
        for (TimingHypothesis h : r.hypotheses()) {
            int ta = tickOf(h, a.id()), tb = tickOf(h, b.id());
            if (ta == 0) assertEquals(0, tb, "b cannot apply before a: " + h.id()); else if (tb != 0) assertTrue(ta <= tb, h.id());
        }
        assertEquals(TimingResolution.Status.RESOLVED_AMBIGUOUS, r.status()); assertTrue(r.complete());
        // three influences over three ticks exceed the 16-hypothesis cap: SEARCH_LIMIT, complete=false, never a silent truncation
        Recorder rec2 = new Recorder(); rec2.tickEnd(); long an2 = rec2.sequence, nn2 = rec2.nanos; rec2.advance(1_000_000L); rec2.tickEnd(); rec2.advance(1_000_000L); rec2.velocity(0.1, 0.4, 0); rec2.velocity(0.2, 0.4, 0); rec2.explosion(0.1, 0.2, 0);
        for (int i = 1; i < 3; i++) { rec2.advance(TICK_NANOS); rec2.tickEnd(); }
        rec2.advance(TICK_NANOS); TimingEvent.MovementObserved t2 = rec2.movement(8.9, SURFACE + 0.3, 8.5, 0f, false);
        TimingResolution r2 = RESOLVER.resolve(request(rec2, an2, nn2, P0, 0f, t2, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec2.nanos));
        assertEquals(TimingResolution.Status.SEARCH_LIMIT, r2.status()); assertFalse(r2.complete()); assertEquals(16, r2.hypotheses().size());
    }
    static int tickOf(TimingHypothesis h, String id) { for (int t = 0; t < h.branch().motionsPerTick().size(); t++) for (var m : h.branch().motionsPerTick().get(t)) if (m.sourceId().equals(id)) return t + 1; return 0; }
    @Test void anInfluencePastTheApplicationHorizonIsExpiredNotDenied() {
        Recorder rec = new Recorder(); rec.tickEnd();
        TimingEvent.ExternalMotionSent old = rec.velocity(0.3, 0.4, 0);
        rec.advance(4_000_000_000L); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(old), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.EXPIRED, r.status()); assertFalse(r.complete()); assertEquals(1, r.hypotheses().size());
        assertEquals(Set.of(old.id()), r.hypotheses().get(0).expiredInfluences()); assertTrue(r.hypotheses().get(0).appliedInfluences().isEmpty());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("not non-application")), r.notes().toString());
    }
    @Test void anUnresolvedVectorMakesTheIntervalExternalMotionUnresolved() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); rec.unresolvedMotion(); rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.EXTERNAL_MOTION_UNRESOLVED, r.status()); assertEquals(2, r.hypotheses().size());
    }
    @Test void moreThanEightUnresolvedInfluencesIsOverflowNotAGuess() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
        for (int i = 0; i < 9; i++) rec.velocity(0.1 * i, 0.4, 0);
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.UNSUPPORTED, r.status()); assertFalse(r.complete()); assertTrue(r.notes().get(0).contains("UNCERTAIN_APPLICATION_OVERFLOW"));
    }
    @Test void aConfirmedCorrectionMakesTheNextPositionItsApplicationPoint() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); // anchor tick end
        rec.advance(TICK_NANOS); rec.tickEnd(); rec.advance(TICK_NANOS); rec.tickEnd();     // two pre-application ticks
        rec.advance(1_000_000L); rec.teleport(7, 20.5, SURFACE, 20.5, 0f, 0);
        rec.advance(30_000_000L); rec.confirm(7); rec.advance(100_000L);
        TimingEvent.MovementObserved target = rec.movement(20.5, SURFACE, 20.5, 0f, false);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status()); TimingHypothesis h = r.hypotheses().get(0);
        assertEquals(TimingHypothesis.TeleportRelation.APPLICATION_POINT, h.teleport()); assertEquals(0, h.horizon()); assertEquals(2, h.preTicks()); assertEquals(7, h.correction().teleportId());
    }
    @Test void movementBeforeTheConfirmIsPreApplicationOrdinaryMovement() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
        rec.advance(1_000_000L); rec.teleport(8, 20.5, SURFACE, 20.5, 0f, 0);
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status()); assertEquals(TimingHypothesis.TeleportRelation.PRE_APPLICATION, r.hypotheses().get(0).teleport()); assertEquals(1, r.hypotheses().get(0).horizon());
    }
    @Test void aStaleConfirmResolvesNothingAndSupersessionIsRecorded() {
        Recorder rec = new Recorder(); rec.tickEnd();
        rec.teleport(1, 1.5, SURFACE, 1.5, 0f, 0); rec.teleport(2, 2.5, SURFACE, 2.5, 0f, 0);
        rec.confirm(9); // unknown id
        assertEquals(1, rec.ledger.view().counters().staleTeleportConfirms());
        rec.confirm(2); // the newer one confirmed first: the older can no longer be confirmed in order
        var teleports = rec.ledger.view().teleports();
        assertTrue(teleports.stream().anyMatch(t -> t.teleportId() == 2 && t.confirmed()));
        assertTrue(teleports.stream().noneMatch(t -> t.teleportId() == 1 && !t.superseded() && !t.confirmed()), teleports.toString());
        rec.confirm(1); // late: the superseded one is not "pending unconfirmed" any more
        assertEquals(2, rec.ledger.view().counters().staleTeleportConfirms());
    }
    @Test void aRelativeCorrectionAfterManySilentTicksUsesTheClosureAndBeyondItIsUnresolved() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
        for (int i = 0; i < 5; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); }
        rec.teleport(3, 1, 0, 0, 0f, TimingEvent.RELATIVE_X); rec.advance(1_000_000L); rec.confirm(3); rec.advance(100_000L);
        TimingEvent.MovementObserved target = rec.movement(9.5, SURFACE, 8.5, 0f, true);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status()); assertEquals(5, r.hypotheses().get(0).preTicks()); assertTrue(r.notes().stream().anyMatch(n -> n.contains("exact silent closure")));
        Recorder far = new Recorder(); far.tickEnd(); long a2 = far.sequence, n2 = far.nanos; far.advance(1_000_000L); far.tickEnd();
        for (int i = 0; i < 30; i++) { far.advance(TICK_NANOS); far.tickEnd(); }
        far.teleport(4, 1, 0, 0, 0f, TimingEvent.RELATIVE_X); far.advance(1_000_000L); far.confirm(4); far.advance(100_000L);
        TimingEvent.MovementObserved t2 = far.movement(9.5, SURFACE, 8.5, 0f, true);
        TimingResolution u = RESOLVER.resolve(request(far, a2, n2, P0, 0f, t2, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, far.nanos));
        assertEquals(TimingResolution.Status.TELEPORT_UNRESOLVED, u.status()); assertFalse(u.complete());
    }
    @Test void equivalentHypothesesDeduplicateAndTheSameTraceResolvesIdentically() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); rec.velocity(0.2, 0.4, 0);
        rec.advance(TICK_NANOS); rec.tickEnd(); rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.7, SURFACE + 0.2, 8.5, 0f, false);
        TimingRequest req = request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos);
        TimingResolution a = RESOLVER.resolve(req), b = RESOLVER.resolve(req);
        assertEquals(a.hypotheses().stream().map(TimingHypothesis::id).toList(), b.hypotheses().stream().map(TimingHypothesis::id).toList());
        assertEquals(a.status(), b.status()); assertEquals(a.work(), b.work());
        for (int i = 0; i < a.hypotheses().size(); i++) for (int j = i + 1; j < a.hypotheses().size(); j++) assertFalse(a.hypotheses().get(i).equivalentTo(a.hypotheses().get(j)), "no two emitted hypotheses are equivalent");
    }
    @Test void markerAnswersNeverNarrowApplicationUnderTheProfile() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, anchorNanos = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
        TimingEvent.ExternalMotionSent v = rec.velocity(0.2, 0.4, 0); rec.marker(TimingEvent.MarkerKind.PING, 5); rec.advance(3_000_000L); rec.ack(TimingEvent.MarkerKind.PING, 5);
        rec.advance(TICK_NANOS); rec.tickEnd(); rec.advance(TICK_NANOS); rec.tickEnd(); rec.advance(TICK_NANOS); TimingEvent.MovementObserved target = rec.movement(8.7, SURFACE + 0.2, 8.5, 0f, false);
        TimingResolution r = RESOLVER.resolve(request(rec, anchor, anchorNanos, P0, 0f, target, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos));
        assertTrue(r.hypotheses().stream().anyMatch(h -> h.pendingInfluences().contains(v.id())), "'not yet applied' survives despite the pong (receipt is not application)");
        assertEquals(1, r.evidence().markerAnswers()); assertTrue(r.notes().stream().anyMatch(n -> n.contains("pongBoundsApplication")));
        assertTrue(r.evidence().rttPresent(), "the pong produced an RTT sample");
    }
    @Test void statusesNeverUseVerdictVocabulary() {
        for (TimingResolution.Status s : TimingResolution.Status.values()) assertFalse(s.name().equals("VALID") || s.name().equals("INVALID") || s.name().contains("CHEAT") || s.name().contains("SUSPICIOUS"));
        for (TimingReconstruction.Status s : TimingReconstruction.Status.values()) assertFalse(s.name().equals("VALID") || s.name().equals("INVALID") || s.name().contains("CHEAT") || s.name().contains("SUSPICIOUS"));
    }
}
