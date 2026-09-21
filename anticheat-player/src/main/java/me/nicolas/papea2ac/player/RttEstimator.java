package me.nicolas.papea2ac.player;
/**
 * Bounded round-trip estimator over protocol marker samples (LATENCY_COMPENSATION.md N1: "RTT is a diagnostic and horizon-sizing
 * input; RTT/2 is not an authoritative one-way delay"). A sample is the capture-monotonic interval between a marker's encoder
 * tap and its answer's pre-process tap: network round trip plus the client's network-thread handling, never a client tick.
 *
 * <p>State is a fixed 32-sample window plus two exponentially smoothed statistics (smoothed RTT, alpha 1/8; jitter as the
 * smoothed absolute deviation, beta 1/4 - the classic estimator pair, chosen for its bounded state, not for any claim of
 * exactness). Robustness rules, each tested: a non-positive, non-finite-in-range (> 60 s) sample is REJECTED and counted;
 * a sample above {@code 3 x smoothed + 4 x jitter + 20 ms} is an OUTLIER - retained in the window (so the window maximum and
 * the envelope's upper edge see it) but not folded into the smoothed pair, so one extreme sample cannot poison the estimate;
 * three consecutive outliers re-seed the smoothed pair at the new level, so a genuine path change is not ignored forever.
 * Nothing here is a "ping integer": consumers read the {@link Summary} (last, window minimum/maximum, smoothed, jitter, age,
 * counts) and the derived {@link Summary#envelope} window.
 */
public final class RttEstimator {
    public static final int WINDOW = 32;
    public static final long MAX_SAMPLE_NANOS = 60_000_000_000L;
    public static final long OUTLIER_SLACK_NANOS = 20_000_000L;
    public static final int RESEED_AFTER_CONSECUTIVE_OUTLIERS = 3;

    private final long[] window = new long[WINDOW];
    private int windowSize, windowNext;
    private long samples, rejected, outliers, consecutiveOutliers;
    private long last, lastAt = Long.MIN_VALUE, smoothed, jitter;

    /** Immutable published statistics; {@code ageNanos} is relative to the caller's monotonic "now" (negative = no sample yet). */
    public record Summary(long samples, long windowSamples, long rejected, long outliers, long lastNanos, long minNanos, long maxNanos, long smoothedNanos, long jitterNanos, long lastSampleAtNanos) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, Long.MIN_VALUE);
        public boolean hasSamples() { return windowSamples > 0; }
        public long ageNanos(long nowNanos) { return hasSamples() ? nowNanos - lastSampleAtNanos : -1; }
        /** A sample older than this is stale for horizon sizing (still evidence of order of magnitude, never of the current path). */
        public static final long STALE_AFTER_NANOS = 30_000_000_000L;
        public boolean stale(long nowNanos) { return !hasSamples() || ageNanos(nowNanos) > STALE_AFTER_NANOS; }
        /** The round-trip envelope this evidence supports: [window minimum, max(window maximum, smoothed + 4 x jitter)]. Not a one-way delay. */
        public long envelopeLowNanos() { return minNanos; }
        public long envelopeHighNanos() { return Math.max(maxNanos, smoothedNanos + 4 * jitterNanos); }
    }

    /** Records one round-trip sample measured at {@code atNanos}; returns false when the sample is rejected as malformed. */
    public boolean accept(long rttNanos, long atNanos) {
        if (rttNanos <= 0 || rttNanos > MAX_SAMPLE_NANOS) { rejected++; return false; }
        samples++; last = rttNanos; lastAt = atNanos;
        window[windowNext] = rttNanos; windowNext = (windowNext + 1) % WINDOW; if (windowSize < WINDOW) windowSize++;
        if (samples == 1) { smoothed = rttNanos; jitter = rttNanos >> 1; consecutiveOutliers = 0; return true; } // first-sample seed of the deviation estimate (RFC-6298 style), not a one-way-delay estimate
        long threshold = 3 * smoothed + 4 * jitter + OUTLIER_SLACK_NANOS;
        if (samples >= 4 && rttNanos > threshold) {
            outliers++; consecutiveOutliers++;
            if (consecutiveOutliers >= RESEED_AFTER_CONSECUTIVE_OUTLIERS) { smoothed = rttNanos; jitter = rttNanos >> 2; consecutiveOutliers = 0; }
            return true;
        }
        consecutiveOutliers = 0;
        long deviation = Math.abs(rttNanos - smoothed);
        jitter += (deviation - jitter) / 4;
        smoothed += (rttNanos - smoothed) / 8;
        return true;
    }

    public Summary summary() {
        if (windowSize == 0) return new Summary(samples, 0, rejected, outliers, 0, 0, 0, 0, 0, Long.MIN_VALUE);
        long min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < windowSize; i++) { min = Math.min(min, window[i]); max = Math.max(max, window[i]); }
        return new Summary(samples, windowSize, rejected, outliers, last, min, max, smoothed, jitter, lastAt);
    }
    public long samples() { return samples; }
}
