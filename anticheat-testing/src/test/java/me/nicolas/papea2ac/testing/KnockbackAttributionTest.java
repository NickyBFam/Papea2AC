package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.VelocityTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.VelocityRunner;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/** Phase 13 attribution through the live runner: one outcome per influence, settled once, fixed-label metrics, never evidence. */
class KnockbackAttributionTest {
    static final Vec3d KB = new Vec3d(0.4, 0.36, 0.0);
    static VelocityRunner run(TimingProfile p, List<MovementInput> in, List<Knock> knocks, VelocityRunner.Metrics m) {
        PhysicsScene ps = new PhysicsScene(); Harness h = new Harness(ps, p);
        VelocityRunner v = new VelocityRunner(KEY, m); h.runner.attachVelocity(v);
        h.anchor(1, 8.5, SURFACE, 8.5);
        List<MovementInput> all = new ArrayList<>(in); all.addAll(idle(10));
        simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), all, knocks, h.rec, false); h.feed();
        return v;
    }
    @Test void anHonestKnockbackSettlesAsAppliedOnce() {
        VelocityRunner.Metrics m = new VelocityRunner.Metrics();
        VelocityRunner v = run(TimingProfile.PAPER_26_2_FENCED, walk(25, false), List.of(Knock.honest(KB, 5, 6, 7)), m);
        VelocityRunner.Status st = v.status();
        assertEquals(1, st.tracked()); assertEquals(1, st.applied()); assertEquals(0, st.excluded());
        assertTrue(st.influences().get(0).contains("APPLIED"), st.influences().toString());
        assertEquals(1, m.influences.sum()); assertEquals(1, m.applied.sum());
    }
    @Test void aCancelledFencedKnockbackIsExcludedUnderTheFence() {
        VelocityRunner.Metrics m = new VelocityRunner.Metrics();
        VelocityRunner v = run(TimingProfile.PAPER_26_2_FENCED, walk(25, false), List.of(Knock.cancelled(KB, 5, 6)), m);
        VelocityRunner.Status st = v.status();
        assertEquals(1, st.excluded(), st.recent().toString()); assertEquals(1, st.fencedExcluded());
        assertTrue(st.influences().get(0).contains("FENCED_APPLICATION_EXCLUDED") && st.influences().get(0).contains("fence marker@"), st.influences().toString());
    }
    @Test void underTheFrozenProfileTheSameCancellationStaysPendingThenUnresolved() {
        VelocityRunner v = run(TimingProfile.PAPER_26_2, walk(25, false), List.of(Knock.cancelled(KB, 5, 6)), new VelocityRunner.Metrics());
        assertEquals(0, v.status().excluded(), v.status().recent().toString());
        assertFalse(v.status().influences().get(0).contains("APPLIED"), v.status().influences().toString());
    }
}
