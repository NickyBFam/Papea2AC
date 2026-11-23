package me.nicolas.papea2ac.checks.rotation;
/**
 * Phase 15 analysis bounds. These are measurement parameters, not detection thresholds: nothing here turns a feature into a verdict.
 *
 * @param framesPerTickBound  assumed maximum number of turning frames inside one movement packet interval; each frame can add one float
 *                            rounding of the (unwrapped) yaw accumulator, so this scales the per-delta error bound
 * @param minSamples          fewest resolvable non-zero deltas a quantization fit may be computed from
 * @param fitFraction         fraction of resolvable deltas a candidate unit must explain to be reported as the fitted unit
 * @param resolvabilityFactor a delta is resolvable for unit u only when its error bound is at most u / factor (chance-fit control: a random
 *                            delta lands inside the bound of some multiple with probability at most 2 / factor)
 * @param candidateSources    how many of the smallest distinct deltas seed unit candidates
 * @param maxDivisor          candidate units are seed / m for m = 1..maxDivisor
 * @param evaluationStride    a window is evaluated after this many new rotation observations
 * @param largeDeltaDegrees   diagnostic histogram edge for large single-packet turns
 */
public record RotationAnalysisConfig(int framesPerTickBound, int minSamples, double fitFraction, double resolvabilityFactor,
                                     int candidateSources, int maxDivisor, int evaluationStride, float largeDeltaDegrees) {
    public RotationAnalysisConfig {
        if (framesPerTickBound < 1 || minSamples < 2 || !(fitFraction > 0.5 && fitFraction <= 1) || !(resolvabilityFactor >= 4)
                || candidateSources < 1 || maxDivisor < 1 || evaluationStride < 1 || !(largeDeltaDegrees > 0))
            throw new IllegalArgumentException("rotation analysis config");
    }
    public static final RotationAnalysisConfig DEFAULT = new RotationAnalysisConfig(20, 16, 0.9d, 16.0d, 2, 128, 20, 30.0F);
}
