package me.nicolas.papea2ac.checks.rotation;
import java.util.Arrays;
/**
 * Phase 15: does a window of per-packet rotation deltas look like whole-count multiples of one sensitivity unit?
 *
 * <p>Legitimate raw-input turning moves both axes in whole cursor counts of the same unit (see {@link RotationKinematics}); each packet delta is
 * a float sum of per-frame steps, so it equals {@code n * unit} only up to a bounded rounding error. The bound for a delta {@code a} whose
 * endpoints have magnitude at most {@code Y}, under at most {@code F} turning frames, is
 * {@code F * ulp(Y) + (a + F * u) * 2^-22 + ulp(a)}: one accumulator rounding per frame (with headroom for intermediate overshoot), two float
 * roundings per frame step, and the packet subtraction. Large unwrapped yaw makes {@code ulp(Y)} large.
 *
 * <p>Resolution floor: a unit {@code u} can only be told apart from its neighbours when deltas' bounds are at most
 * {@code u / resolvabilityFactor} (chance-fit control: a random delta lands inside such a window with probability at most
 * {@code 2 / resolvabilityFactor}). The floor is {@code resolvabilityFactor} times the {@code minSamples}-th smallest bound; units below it are
 * not examined, and the result says so ({@link #resolutionFloor}). The searched domain is {@code [max(minUnit, floor), maxUnit]}.
 *
 * <p>Search: a candidate is {@code seed / m} for one of the smallest precise deltas ({@code seed}: bound no larger than the
 * {@code minSamples}-th smallest bound) and {@code m = 1..maxDivisor}, largest unit first.
 * Each candidate is an interval {@code [u - e, u + e]} narrowed by intersection as deltas are placed (smallest first, up to four passes): a
 * delta {@code a} placed at {@code N} counts constrains the unit to {@code [(a - B)/N, (a + B)/N]}, and is placeable only while
 * {@code B + N * e <= u / resolvabilityFactor}. The first candidate with at least {@code minSamples} placed deltas, at least three distinct
 * whole counts among the explained ones (a single repeated delta is a multiple of each of its divisors), and at least {@code fitFraction}
 * explained wins. A failure is NO_FIT for the searched domain only when every unit of it is reachable as
 * {@code seed / m} and every candidate could be tested (placed at least {@code minSamples} deltas or was refuted); otherwise SEARCH_INCOMPLETE.
 *
 * <p>A fit is a feature, never a verdict: GCD-aware automation can produce perfect fits, and smooth camera, fractional input, controllers,
 * sensitivity changes and scoping transitions legitimately produce no fit. Cost: at most {@code candidateSources * maxDivisor} candidates,
 * each at most four passes over the window's deltas, abandoned as soon as it can no longer reach fitFraction.
 */
public record QuantizationFit(Status status, double unit, double unitUncertainty, double resolutionFloor, int samples, int resolvable,
                              int explained, double bestFraction) {
    public enum Status {
        /** A unit inside the searched domain explains at least fitFraction of its placed deltas. */
        FIT,
        /** No unit inside the searched domain [max(minUnit, resolutionFloor), maxUnit]; units below the floor were not examined. */
        NO_FIT,
        /** No unit found, but the search could not reach every unit of the domain (large smallest deltas, or no candidate could be tested). */
        SEARCH_INCOMPLETE,
        /** The resolution floor is above every unit the profile can produce (large unwrapped yaw, frames bound). */
        PRECISION_LIMITED,
        /** Fewer than minSamples non-zero deltas. */
        INSUFFICIENT_SAMPLES
    }

    private static final double REL = 0x1.0p-22;
    private static final int PASSES = 4;
    private static final int MIN_DISTINCT_COUNTS = 3;

    public static double errorBound(double absDelta, double endpointMagnitude, double unit, int frames) {
        return frames * (double) Math.ulp((float) endpointMagnitude) + (absDelta + frames * unit) * REL + Math.ulp((float) absDelta);
    }

    /**
     * @param absDeltas  non-negative per-packet delta magnitudes (zeros are ignored), pooled over both axes
     * @param magnitudes per delta, the larger endpoint magnitude of the angle it was taken from
     * @param count      number of valid entries
     * @param frames     turning frames bound for one packet interval (scaled by the client tick period, see ClientTickCondition)
     */
    public static QuantizationFit fit(float[] absDeltas, float[] magnitudes, int count, float minUnit, float maxUnit, int frames, RotationAnalysisConfig c) {
        int n = 0;
        long[] packed = new long[count]; // (delta bits, original index): a deterministic ascending sort that carries the magnitudes along
        float[] y = new float[count];
        for (int i = 0; i < count; i++) if (absDeltas[i] > 0) { y[n] = magnitudes[i]; packed[n] = ((long) Float.floatToRawIntBits(absDeltas[i]) << 32) | n; n++; }
        if (n < c.minSamples()) return failed(Status.INSUFFICIENT_SAMPLES, 0, n, 0);
        packed = Arrays.copyOf(packed, n);
        Arrays.sort(packed); // non-negative float bits order like their values
        float[] a = new float[n]; float[] mag = new float[n];
        for (int i = 0; i < n; i++) { a[i] = Float.intBitsToFloat((int) (packed[i] >>> 32)); mag[i] = y[(int) packed[i]]; }

        double[] bounds = new double[n];
        for (int i = 0; i < n; i++) bounds[i] = errorBound(a[i], mag[i], minUnit, frames);
        double[] sortedBounds = bounds.clone();
        Arrays.sort(sortedBounds);
        double floor = c.resolvabilityFactor() * sortedBounds[c.minSamples() - 1];
        double domainLow = Math.max(minUnit, floor);
        if (domainLow > maxUnit) return failed(Status.PRECISION_LIMITED, floor, n, 0);

        double lo = domainLow * (1 - 1e-4), hi = maxUnit * (1 + 1e-4);
        double[] cu = new double[c.candidateSources() * c.maxDivisor()], ce = new double[cu.length];
        int cn = 0, seeds = 0;
        boolean complete = false;
        float previous = -1;
        double seedBound = sortedBounds[c.minSamples() - 1]; // seeds come from the most precise deltas, so candidate intervals start narrow
        for (int i = 0; i < n && seeds < c.candidateSources(); i++) {
            if (a[i] == previous || bounds[i] > seedBound) continue;
            previous = a[i]; seeds++;
            if (a[i] <= c.maxDivisor() * domainLow) complete = true;
            for (int m = 1; m <= c.maxDivisor(); m++) {
                double u = (double) a[i] / m;
                if (u < lo || u > hi) continue;
                cu[cn] = u; ce[cn] = errorBound(a[i], mag[i], u, frames) / m; cn++;
            }
        }
        // largest unit first: packed (positive float bits, index) keys sort like the values; iterated from the end
        long[] order = new long[cn];
        for (int i = 0; i < cn; i++) order[i] = ((long) Float.floatToRawIntBits((float) cu[i]) << 32) | i;
        Arrays.sort(order);
        for (int i = 0, j = cn - 1; i < j; i++, j--) { long t = order[i]; order[i] = order[j]; order[j] = t; }

        boolean anyTested = false, anyUntested = false;
        double bestFraction = 0;
        boolean[] placed = new boolean[n];
        double previousTried = Double.NaN;
        int unexplainedLimit = (int) Math.floor((1 - c.fitFraction()) * n);
        for (long key : order) {
            int k = (int) key;
            if (!Double.isNaN(previousTried) && Math.abs(previousTried - cu[k]) <= cu[k] * 1e-7) continue; // near-duplicate candidate
            previousTried = cu[k];
            double low = cu[k] - ce[k], high = cu[k] + ce[k];
            Arrays.fill(placed, false);
            int resolvable = 0, explained = 0, unexplained = 0, distinct = 0;
            double firstCount = 0, secondCount = 0;
            boolean abandoned = false;
            for (int pass = 0; pass < PASSES && !abandoned; pass++) {
                int placedThisPass = 0;
                for (int i = 0; i < n; i++) {
                    if (placed[i]) continue;
                    double u = (low + high) / 2, e = (high - low) / 2;
                    double counts = Math.rint(a[i] / u);
                    double b = errorBound(a[i], mag[i], u, frames);
                    if (counts < 1 || b + counts * e > u / c.resolvabilityFactor()) continue; // not placeable (yet)
                    placed[i] = true; resolvable++; placedThisPass++;
                    double nl = (a[i] - b) / counts, nh = (a[i] + b) / counts;
                    if (nl <= high && nh >= low) {
                        low = Math.max(low, nl); high = Math.min(high, nh); explained++;
                        if (distinct == 0) { firstCount = counts; distinct = 1; }
                        else if (distinct == 1 && counts != firstCount) { secondCount = counts; distinct = 2; }
                        else if (distinct == 2 && counts != firstCount && counts != secondCount) distinct = 3;
                    }
                    else if (++unexplained > unexplainedLimit) { abandoned = true; break; }
                }
                if (placedThisPass == 0) break;
            }
            // untestable: too few placed deltas, or fewer than three distinct counts (one repeated delta is a multiple of any of its divisors)
            if (!abandoned && (resolvable < c.minSamples() || distinct < MIN_DISTINCT_COUNTS)) { anyUntested = true; continue; }
            anyTested = true;
            if (abandoned) continue;
            double fraction = (double) explained / resolvable;
            bestFraction = Math.max(bestFraction, fraction);
            if (fraction >= c.fitFraction()) return new QuantizationFit(Status.FIT, (low + high) / 2, (high - low) / 2, floor, n, resolvable, explained, fraction);
        }
        // NO_FIT claims every candidate of a complete domain was examined; one untestable candidate could have been the unit
        return failed(anyTested && !anyUntested && complete ? Status.NO_FIT : Status.SEARCH_INCOMPLETE, floor, n, bestFraction);
    }

    private static QuantizationFit failed(Status status, double floor, int samples, double bestFraction) {
        return new QuantizationFit(status, 0, 0, floor, samples, 0, 0, bestFraction);
    }
}
