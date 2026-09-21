package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.simulation.TickObservation;
import me.nicolas.papea2ac.simulation.TickObservation.*;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import org.junit.jupiter.api.Test;

/** §9-§11, §53: the observation contract and the protocol-derived quantization, tested at the client's own boundaries. */
class TickObservationTest {
    static final MovementObservationProfile P = MovementObservationProfile.PAPER_26_2;
    @Test void transmittedPositionsCompareExactlyAndSignedZeroIsTheSamePlace() {
        Exact e = new Exact(8.5, 70.0, 0.0);
        assertTrue(e.contains(new Vec3d(8.5, 70.0, 0.0), P)); assertTrue(e.contains(new Vec3d(8.5, 70.0, -0.0), P), "-0.0 is the same transmitted coordinate");
        assertFalse(e.contains(new Vec3d(Math.nextUp(8.5), 70.0, 0.0), P), "one ulp is a different double: the protocol carries full doubles, no tolerance");
        assertFalse(e.contains(new Vec3d(Double.NaN, 70.0, 0.0), P)); assertThrows(IllegalArgumentException.class, () -> new Exact(Double.NaN, 0, 0));
    }
    @Test void silentTicksConstrainToTheClientsSendThresholdBallExactly() {
        assertEquals(2.0E-4 * 2.0E-4, P.positionSendThresholdSqr(), 0.0, "Mth.square(2.0E-4) as the client computes it");
        WithinSendThreshold w = new WithinSendThreshold(100.0, 64.0, -20.0);
        // on the threshold along one axis the client computes (x - lastX) in double first: at x = 100 that rounds just above 2.0E-4, so the client WOULD send;
        // at the origin the difference is exact and d*d == threshold -> not greater -> silent -> inside
        assertFalse(w.contains(new Vec3d(100.0 + 2.0E-4, 64.0, -20.0), P), "the client's own rounding decides, not the nominal 2.0E-4");
        WithinSendThreshold o = new WithinSendThreshold(0.0, 0.0, 0.0); assertTrue(o.contains(new Vec3d(2.0E-4, 0.0, 0.0), P)); assertFalse(o.contains(new Vec3d(Math.nextUp(2.0E-4), 0.0, 0.0), P));
        // the smallest displacement the client WOULD transmit is excluded
        double dx = (100.0 + 2.1E-4) - 100.0; assertTrue(dx * dx > P.positionSendThresholdSqr()); assertFalse(w.contains(new Vec3d(100.0 + 2.1E-4, 64.0, -20.0), P));
        // diagonal: the SUM of squares decides, not a per-axis box
        assertTrue(w.contains(new Vec3d(100.0 + 1.4E-4, 64.0, -20.0 + 1.4E-4), P)); assertFalse(w.contains(new Vec3d(100.0 + 1.5E-4, 64.0, -20.0 + 1.5E-4), P));
        assertTrue(w.contains(new Vec3d(100.0, 64.0, -20.0), P)); assertFalse(w.contains(new Vec3d(Double.POSITIVE_INFINITY, 64.0, -20.0), P));
    }
    @Test void residualsAreDiagnosticFiniteAndRelativeToTheObservationSet() {
        TickObservation exact = TickObservation.exact(8.5, 70.0, 8.5, 0f, GroundClaim.TRUE, 1);
        Residual r = exact.residual(new Vec3d(8.5, 70.0, 8.598), false, P);
        assertEquals(0.0, r.dx()); assertEquals(0.098, r.dz(), 1e-12); assertFalse(r.inside()); assertEquals(0.098, r.outsideDistance(), 1e-12); assertTrue(r.groundClaimPresent()); assertFalse(r.groundClaimAgrees()); assertTrue(r.finite());
        TickObservation silent = TickObservation.silent(8.5, 70.0, 8.5, 0f, 2);
        Residual s = silent.residual(new Vec3d(8.5, 70.0, 8.5 + 1.0E-4), true, P);
        assertTrue(s.inside()); assertEquals(0.0, s.outsideDistance()); assertEquals(1.0E-4, s.dz(), 1e-15); assertFalse(s.groundClaimPresent()); assertTrue(s.groundClaimAgrees(), "no claim: nothing to disagree with");
        Residual far = silent.residual(new Vec3d(8.5, 70.0, 8.5 + 1.0E-3), true, P);
        assertFalse(far.inside()); assertEquals(1.0E-3 - 2.0E-4, far.outsideDistance(), 1e-12, "distance beyond the ball, not the point distance");
        assertTrue(TickObservation.unobserved(0f, 3).position().contains(new Vec3d(1, 2, 3), P));
        assertFalse(TickObservation.yawUnknown(Unobserved.INSTANCE, 4).yawKnown());
    }
}
