package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.RttEstimator;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/** Seeded deterministic properties of the timing layer (brief section 58). Seeds are in every failure message. */
class TimingPropertyTest {
    static final long SEED = Long.getLong("timing.seed", 0x71A1E5L);

    /** Same trace, same request → identical hypotheses, statuses and work (no hash order, no clock, no randomness). */
    @Test void theSameTraceResolvesIdentically() {
        SplittableRandom r = new SplittableRandom(SEED);
        for (int c = 0; c < 200; c++) {
            Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, an = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
            int k = 1 + r.nextInt(4); int infl = r.nextInt(3);
            for (int i = 0; i < infl; i++) { rec.advance(r.nextInt(5) * 1_000_000L); if (r.nextBoolean()) rec.velocity(0.1, 0.4, 0); else rec.explosion(0.1, 0.2, 0); }
            for (int i = 1; i < k; i++) { rec.advance(TICK_NANOS); if (r.nextInt(4) == 0) rec.rotationOnly(r.nextInt(360) - 180, true); rec.tickEnd(); }
            rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(8.6, SURFACE, 8.5, 0f, true);
            TimingRequest req = request(rec, anchor, an, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1);
            TimingResolution a = new TimingResolver().resolve(req), b = new TimingResolver().resolve(req);
            assertEquals(a.status(), b.status(), "seed " + SEED + " case " + c); assertEquals(a.work(), b.work(), "seed " + SEED + " case " + c);
            assertEquals(a.hypotheses().stream().map(TimingHypothesis::id).toList(), b.hypotheses().stream().map(TimingHypothesis::id).toList(), "seed " + SEED + " case " + c);
            for (int i = 0; i < a.hypotheses().size(); i++) assertTrue(a.hypotheses().get(i).equivalentTo(b.hypotheses().get(i)));
        }
    }
    /** Adding uncertainty (a coverage break, a lost tick-end capability, extra pending influences) never removes a hypothesis the exact account contained. */
    @Test void addingUncertaintyNeverShrinksTheLegalHypothesisSet() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        for (int c = 0; c < 200; c++) {
            Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, an = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
            int k = 1 + r.nextInt(3); boolean withInfluence = r.nextBoolean(); TimingEvent.ExternalMotionSent v = null;
            if (withInfluence) { rec.advance(1_000_000L); v = rec.velocity(0.2, 0.4, 0); }
            for (int i = 1; i < k; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); }
            rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(8.7, SURFACE, 8.5, 0f, true);
            TimingRequest exact = request(rec, anchor, an, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1);
            TimingResolution a = new TimingResolver().resolve(exact);
            // degraded coverage: the bounded fallback must still contain every exact hypothesis (same horizon, same application choice)
            TimingRequest degraded = new TimingRequest(exact.events(), exact.anchorSequence(), exact.anchorNanos(), true, exact.lastSentPosition(), exact.lastYaw(), exact.target(), exact.pendingInfluences(), exact.teleports(), exact.rtt(), false, false, 0, exact.nowNanos(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
            TimingResolution b = new TimingResolver().resolve(degraded);
            assertFalse(b.complete(), "seed " + SEED + " case " + c);
            for (TimingHypothesis h : a.hypotheses()) assertTrue(b.hypotheses().stream().anyMatch(o -> o.horizon() == h.horizon() && o.branch().motionsPerTick().equals(h.branch().motionsPerTick()) && o.pendingInfluences().equals(h.pendingInfluences())), "seed " + SEED + " case " + c + ": exact hypothesis " + h.id() + " missing from the degraded set " + b.describe());
            // an extra pending influence adds branches, never removes the branch set of the original influence
            if (withInfluence) {
                Recorder rec2 = new Recorder(); TimingEvent.ExternalMotionSent extra = rec2.explosion(0.05, 0.1, 0);
                TimingResolution cWith = new TimingResolver().resolve(exact.withPendingInfluences(List.of(extra)));
                Set<String> placements = new TreeSet<>(); for (TimingHypothesis h : cWith.hypotheses()) placements.add(placement(h, v.id()));
                for (TimingHypothesis h : a.hypotheses()) assertTrue(placements.contains(placement(h, v.id())), "seed " + SEED + " case " + c + ": placement " + placement(h, v.id()) + " lost");
            }
        }
    }
    static String placement(TimingHypothesis h, String id) { for (int t = 0; t < h.branch().motionsPerTick().size(); t++) for (var m : h.branch().motionsPerTick().get(t)) if (m.sourceId().equals(id)) return "t" + (t + 1); return h.pendingInfluences().contains(id) ? "later" : "absent"; }
    /** A timeout / expiry never creates certainty: expired influences are reported, the hypotheses stay, complete=false. */
    @Test void timeoutsNeverCreateCertainty() {
        Recorder rec = new Recorder(); rec.tickEnd(); TimingEvent.ExternalMotionSent v = rec.velocity(0.3, 0.4, 0);
        rec.advance(TimingProfile.PAPER_26_2.applicationHorizonNanos() + 1); long anchor = rec.sequence, an = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved t = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = new TimingResolver().resolve(request(rec, anchor, an, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(v), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1));
        assertEquals(TimingResolution.Status.EXPIRED, r.status()); assertFalse(r.complete()); assertEquals(1, r.hypotheses().size());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("not non-application")));
        // marker expiry likewise: no sample, no certainty
        Recorder m = new Recorder(); m.marker(TimingEvent.MarkerKind.PING, 1); m.advance(me.nicolas.papea2ac.player.MarkerLedger.EXPIRY_NANOS + 1); m.tickEnd();
        assertEquals(1, m.ledger.view().markers().expired()); assertFalse(m.ledger.view().rtt().hasSamples());
    }
    /** A search limit never claims completeness, and every retained structure is bounded by the budget. */
    @Test void searchLimitsNeverClaimCompleteAndStayBounded() {
        Recorder rec = new Recorder(); rec.tickEnd(); long anchor = rec.sequence, an = rec.nanos; rec.advance(1_000_000L); rec.tickEnd();
        for (int i = 0; i < 4; i++) rec.velocity(0.1 * (i + 1), 0.4, 0);
        for (int i = 1; i < 3; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); }
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(9.0, SURFACE + 0.4, 8.5, 0f, false);
        for (int cap : new int[] { 1, 2, 5, 16 }) {
            TimingBudget b = new TimingBudget(3, cap, 8, 64, 16, 16_384, 64);
            TimingResolution r = new TimingResolver().resolve(request(rec, anchor, an, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, b, rec.nanos + 1));
            assertEquals(TimingResolution.Status.SEARCH_LIMIT, r.status(), "cap " + cap); assertFalse(r.complete()); assertTrue(r.hypotheses().size() <= cap);
        }
        // the adapter's frontier cap
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5);
        TimingBudget tiny = new TimingBudget(3, 16, 8, 64, 16, 16_384, 1);
        Recorder r2 = new Recorder(); r2.advance(1_000_000L); r2.tickEnd(); r2.advance(TICK_NANOS); TimingEvent.MovementObserved idle = r2.movement(8.5, SURFACE, 8.5, 0f, true);
        TimingReconstruction.Result res = new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator)).run(request(r2, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), 0f, idle, List.of(), TimingProfile.PAPER_26_2, tiny, r2.nanos + 1), TimingReconstruction.Frontier.of(s), ps.env(), ReconstructionRequest.SearchBudget.DEFAULT);
        assertEquals(TimingReconstruction.Status.SEARCH_LIMIT, res.status()); assertFalse(res.complete()); assertEquals(1, res.next().nodes().size()); assertTrue(res.work().frontierDropped() > 0);
    }
    /** Monotonic timestamps and large values: events must be ordered by sequence; nanos near Long.MAX_VALUE do not overflow the estimator or the resolver. */
    @Test void largeMonotonicTimestampsAndOrderingAreSafe() {
        assertThrows(IllegalArgumentException.class, () -> new TimingRequest(List.of(new TimingEvent.ClientTickEnd(5, 1), new TimingEvent.ClientTickEnd(4, 2)), 1, 0, true, Vector3.ZERO, 0f, new TimingEvent.MovementObserved(6, 3, true, Vector3.ZERO, true, new me.nicolas.papea2ac.model.Rotation(0, 0), true), List.of(), List.of(), RttEstimator.Summary.EMPTY, true, false, 0, 10, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT));
        Recorder rec = new Recorder(); rec.nanos = Long.MAX_VALUE - 10_000_000_000L; rec.tickEnd(); long anchor = rec.sequence, an = rec.nanos; rec.advance(1_000_000L); rec.tickEnd(); rec.marker(TimingEvent.MarkerKind.PING, 3); rec.advance(20_000_000L); rec.ack(TimingEvent.MarkerKind.PING, 3); rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved t = rec.movement(8.6, SURFACE, 8.5, 0f, true);
        TimingResolution r = new TimingResolver().resolve(request(rec, anchor, an, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1));
        assertEquals(TimingResolution.Status.RESOLVED_UNIQUE, r.status()); assertTrue(r.evidence().rttPresent()); assertEquals(20_000_000L, rec.ledger.view().rtt().lastNanos());
    }
    /** The true synthetic application tick is never excluded over random single-tick intervals (a focused variant of the round trip: one influence, exact alignment). */
    @Test void theActualApplicationTickIsNeverExcluded() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); SplittableRandom r = new SplittableRandom(SEED + 7); int cases = 0;
        for (int c = 0; c < 300; c++) {
            PhysicsState start = settled(ps, 8.5 + r.nextInt(3), SURFACE, 8.5 + r.nextInt(3)); float yaw = ReconstructionRoundTripTest.randomYaw(r);
            int n = 3 + r.nextInt(4), app = 1 + r.nextInt(n); int lead = Math.min(r.nextInt(3), app - 1);
            List<MovementInput> inputs = new ArrayList<>(); for (int i = 0; i < n; i++) inputs.add(ReconstructionRoundTripTest.randomInput(r, yaw));
            Truth truth; try { truth = simulate(ps, env, start, inputs, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.2, 0.42, -0.1), app, lead)), new Recorder(), true); } catch (AssertionError e) { continue; }
            cases++;
            for (ChainStep s : chain(ps, env, truth, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT)) if (s.truthAfter() != null) assertTrue(s.truthRetained(), "seed " + (SEED + 7) + " case " + c + "\n" + describe(List.of(s)));
        }
        assertTrue(cases > 200, "cases " + cases);
    }
}
