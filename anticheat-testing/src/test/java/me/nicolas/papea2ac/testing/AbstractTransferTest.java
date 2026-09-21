package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import org.junit.jupiter.api.Test;

/**
 * M3 containment (TESTING.md TC12, MOVEMENT_SIMULATION.md M3 "Tests pair ... random concrete states sampled inside envelopes,
 * assert successor containment, adversarial edge-contact/rounding cases and no envelope-only witness"): for random boxes
 * around reachable states, every sampled concrete state's Phase 5 successor under every raw input lies inside
 * {@code T#(box, input)} and inside {@code T#(box, all inputs)}, or the transfer is TOP. Sampling is a regression check,
 * not a proof; the per-operator monotonicity argument is documented in {@link AbstractTransfer} / {@link Interval}.
 */
class AbstractTransferTest {
    static final long SEED = Long.getLong("reconstruction.seed", 0x7A7A7A7AL);
    static final MovementObservationProfile OBS = MovementObservationProfile.PAPER_26_2;

    static AbstractState.Box around(SplittableRandom r, PhysicsState s, double dp, double dv) {
        Interval x = spread(r, s.position().x(), dp), z = spread(r, s.position().z(), dp);
        Interval y = s.onGround() ? Interval.point(s.position().y()) : spread(r, s.position().y(), dp);
        Interval vx = spread(r, s.velocity().x(), dv), vy = spread(r, s.velocity().y(), dv), vz = spread(r, s.velocity().z(), dv);
        int nj = s.noJumpDelay(); int lo = Math.max(0, nj - r.nextInt(2)), hi = Math.min(10, nj + r.nextInt(2));
        return new AbstractState.Box(x, y, z, vx, vy, vz, s.onGround() ? AbstractState.GROUND_ON : AbstractState.GROUND_OFF, AbstractState.poseBit(s.pose()), lo, hi);
    }
    static Interval spread(SplittableRandom r, double c, double d) { double a = c - r.nextDouble() * d, b = c + r.nextDouble() * d; return new Interval(Math.min(a, b), Math.max(a, b)); }
    static PhysicsState sample(SplittableRandom r, AbstractState.Box b, PhysicsState template) {
        double x = pick(r, b.x()), y = pick(r, b.y()), z = pick(r, b.z()), vx = pick(r, b.vx()), vy = pick(r, b.vy()), vz = pick(r, b.vz());
        int nj = b.noJumpDelayLo() + (b.noJumpDelayHi() > b.noJumpDelayLo() ? r.nextInt(b.noJumpDelayHi() - b.noJumpDelayLo() + 1) : 0);
        return new PhysicsState(new Vec3d(x, y, z), new Vec3d(vx, vy, vz), template.pose(), b.mayBeOnGround(), false, false, false, template.fallDistance(), Vec3d.ZERO, false, template.support(), nj, PhysicsState.Locomotion.WALKING);
    }
    static double pick(SplittableRandom r, Interval i) { if (i.isPoint()) return i.lo(); int k = r.nextInt(6); return k == 0 ? i.lo() : k == 1 ? i.hi() : i.lo() + r.nextDouble() * (i.hi() - i.lo()); }

    @Test void everyConcreteSuccessorIsContainedInTheEnvelope() {
        SplittableRandom r = new SplittableRandom(SEED);
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env();
        AbstractTransfer t = new AbstractTransfer(ps.simulator, env, OBS, 4096);
        int boxes = 0, contained = 0, top = 0, skipped = 0;
        List<PhysicsState> seeds = new ArrayList<>();
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); seeds.add(rest);
        PhysicsState s = rest; for (int i = 0; i < 40; i++) { MovementInput in = InputDomain.fromOrdinal(r.nextInt(144), (float) (r.nextInt(360) - 180)); PhysicsResult pr = ps.step(s, in, env); if (!pr.exact()) break; s = pr.next(); seeds.add(s); }
        for (int c = 0; c < 240; c++) {
            PhysicsState base = seeds.get(r.nextInt(seeds.size()));
            AbstractState.Box box = around(r, base, r.nextInt(3) == 0 ? 0.3 : 0.02, r.nextInt(3) == 0 ? 0.3 : 0.01);
            float yaw = (float) (r.nextInt(720) * 0.5 - 180.0);
            List<MovementInput> all = InputDomain.enumerate(InputDomain.Constraints.NONE, yaw);
            AbstractState union = t.step(AbstractState.of(box), all, List.of());
            AbstractTransfer.Prepared pre = t.prepare(box, List.of());
            if (pre == null) { top++; assertTrue(union.top(), "prepare TOP but union not TOP"); continue; }
            boxes++;
            for (int k = 0; k < 12; k++) {
                PhysicsState cs = sample(r, box, base);
                for (MovementInput in : all) {
                    List<AbstractState.Box> per = t.apply(pre, in);
                    PhysicsResult pr = ps.step(cs, in, env);
                    if (pr.next() == null) { skipped++; continue; }
                    String where = "seed=" + SEED + " case=" + c + " sample=" + k + " input=" + InputDomain.describe(in) + " yaw=" + yaw + "\nstart=" + cs + "\nnext=" + pr.next() + " status=" + pr.status();
                    if (per == null) { top++; continue; }
                    if (!pr.exact()) { skipped++; continue; } // the concrete step left the pilot region (the region proof would have made the transfer TOP for a matching box; a sampled state exactly on the region edge can still step out)
                    boolean in1 = false; for (AbstractState.Box b : per) if (b.contains(pr.next())) { in1 = true; break; }
                    assertTrue(in1, where + "\nper-input envelope: " + per);
                    assertTrue(union.contains(pr.next()), where + "\nunion envelope: " + union.summary());
                    contained++;
                }
            }
        }
        System.out.println("containment: boxes=" + boxes + " contained=" + contained + " top=" + top + " skipped=" + skipped);
        assertTrue(contained > 50000, "too few containment checks: " + contained);
    }
    @Test void thresholdAndLandingEdgesAreUnionedNotChosen() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); AbstractTransfer t = new AbstractTransfer(ps.simulator, env, OBS, 4096);
        PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        // horizontal zeroing threshold straddled: |v| = 0.003 boundary
        AbstractState.Box b = new AbstractState.Box(Interval.point(8.5), Interval.point(70.0), Interval.point(8.5), new Interval(0.0029, 0.0031), Interval.point(-0.0784000015258789), Interval.ZERO, AbstractState.GROUND_ON, AbstractState.POSE_STANDING, 0, 0);
        AbstractTransfer.Prepared pre = t.prepare(b, List.of()); assertNotNull(pre, t.topReason());
        for (double vx : new double[] { 0.0029, 0.00299999, 0.003, 0.0031 }) {
            PhysicsState cs = rest.withVelocity(new Vec3d(vx, -0.0784000015258789, 0.0));
            PhysicsResult pr = ps.step(cs, IDLE, env); assertTrue(pr.exact());
            boolean ok = false; for (AbstractState.Box o : t.apply(pre, IDLE)) if (o.contains(pr.next())) ok = true;
            assertTrue(ok, "vx=" + vx + " -> " + pr.next());
        }
        // landing edge: airborne just above the floor with a small downward velocity that may or may not reach it
        AbstractState.Box air = new AbstractState.Box(Interval.point(8.5), new Interval(70.05, 70.12), Interval.point(8.5), Interval.ZERO, new Interval(-0.1, -0.03), Interval.ZERO, AbstractState.GROUND_OFF, AbstractState.POSE_STANDING, 0, 0);
        AbstractTransfer.Prepared preAir = t.prepare(air, List.of()); assertNotNull(preAir, t.topReason());
        List<AbstractState.Box> out = t.apply(preAir, IDLE);
        assertTrue(out.stream().anyMatch(AbstractState.Box::mayBeOnGround) && out.stream().anyMatch(AbstractState.Box::mayBeAirborne), "both landing cases retained: " + out);
        for (double y : new double[] { 70.05, 70.08, 70.12 }) for (double vy : new double[] { -0.1, -0.06, -0.03 }) {
            PhysicsState cs = new PhysicsState(new Vec3d(8.5, y, 8.5), new Vec3d(0, vy, 0), PlayerBox.Pose.STANDING, false, false, false, false, 0, Vec3d.ZERO, false, PhysicsState.Support.NONE, 0, PhysicsState.Locomotion.WALKING);
            PhysicsResult pr = ps.step(cs, IDLE, env); assertTrue(pr.exact(), pr.summary());
            boolean ok = false; for (AbstractState.Box o : out) if (o.contains(pr.next())) ok = true;
            assertTrue(ok, "y=" + y + " vy=" + vy + " -> " + pr.next() + " boxes " + out);
        }
    }
    @Test void outsideThePilotDomainTheTransferIsTopAndExcludesNothing() {
        PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 10, WorldTestSupport.STONE); ps.set(8, 71, 10, WorldTestSupport.STONE); // a wall in the swept region
        PhysicsEnvironment env = ps.env(); AbstractTransfer t = new AbstractTransfer(ps.simulator, env, OBS, 4096);
        AbstractState a = AbstractState.fromConcrete(settled(ps, 8.5, SURFACE, 8.5));
        AbstractState r = t.step(a, InputDomain.enumerate(InputDomain.Constraints.NONE, 0f), List.of());
        assertTrue(r.top(), r.summary()); assertFalse(r.topReason().isEmpty());
        assertTrue(r.mayMatch(new TickObservation.Exact(0, 0, 0), OBS), "TOP never excludes");
        // unknown attribute, unknown entities, unresolved external motion, fluid, unknown chunk: all TOP
        assertTrue(new AbstractTransfer(ps.simulator, env.withAttributes(PhysicsEnvironment.Attributes.ALL_UNKNOWN), OBS, 4096).step(a, List.of(IDLE), List.of()).top());
        assertTrue(new AbstractTransfer(ps.simulator, env.withEntities(PhysicsEnvironment.EntityNeighborhood.UNKNOWN), OBS, 4096).step(a, List.of(IDLE), List.of()).top());
        assertTrue(t.step(a, List.of(IDLE), List.of(PhysicsEnvironment.ExternalMotion.unresolved("v1"))).top());
        PhysicsScene water = new PhysicsScene(); water.set(9, 70, 8, WATER); assertTrue(new AbstractTransfer(water.simulator, water.env(), OBS, 4096).step(AbstractState.fromConcrete(settled(water, 8.5, SURFACE, 8.5)), List.of(IDLE), List.of()).top());
        PhysicsScene edge = new PhysicsScene(); assertTrue(new AbstractTransfer(edge.simulator, edge.env(), OBS, 4096).step(AbstractState.fromConcrete(settled(edge, 30.5, SURFACE, 30.5)), List.of(IDLE), List.of()).top(), "unknown chunk beyond the observed area");
    }
    @Test void abstractStartsYieldEnvelopePossibilitiesNeverWitnesses() {
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        PhysicsResult w = ps.step(rest, W, env);
        // the start position is only known inside a silent-tick ball: an abstract start box around the rest state
        AbstractState.Box box = new AbstractState.Box(new Interval(8.4999, 8.5001), Interval.point(70.0), new Interval(8.4999, 8.5001), Interval.ZERO, Interval.point(-0.0784000015258789), Interval.ZERO, AbstractState.GROUND_ON, AbstractState.POSE_STANDING, 0, 0);
        ReconstructionRequest req = ReconstructionRequest.of(rest, observe(w.next(), 0f, 7), env).withFrontier(List.of(new ReconstructionRequest.AbstractStart(AbstractState.of(box), "ball")));
        ReconstructionResult r = reconstructor(ps).reconstruct(req);
        assertEquals(ReconstructionResult.Status.ENVELOPE_ONLY, r.status(), dump(r)); assertTrue(r.complete());
        for (ReconstructionResult.Candidate c : r.candidates()) { assertEquals(ReconstructionResult.Match.ENVELOPE_POSSIBLE, c.match()); assertNull(c.predicted()); }
        assertTrue(r.admits(List.of(W)), dump(r)); assertFalse(r.witnesses(List.of(W)), "an envelope is never a witness");
        assertFalse(r.admits(List.of(W_SPRINT)), "a class whose envelope misses the observation is excluded: " + dump(r));
    }
}
