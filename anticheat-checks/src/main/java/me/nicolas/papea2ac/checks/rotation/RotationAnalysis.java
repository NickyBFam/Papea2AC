package me.nicolas.papea2ac.checks.rotation;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
/**
 * Phase 15 output: one bounded rotation feature window (EVIDENCE_MODEL kind BehavioralFeatureWindow). It describes; it never decides.
 *
 * <p>There is no INVALID here and no score. Rotation analysis cannot label a legal ray impossible (COMBAT_MODEL), and a statistical window
 * needs minimum samples, calibration, confounder handling and held-out validation before any policy may weigh it (EVIDENCE_MODEL). Until a
 * reviewed calibration exists, {@link #calibrated()} is false and the violation policy must give this record zero punitive weight.
 *
 * @param windowStartSequence capture sequence of the oldest sample used
 * @param windowEndSequence   capture sequence of the newest sample used
 * @param segment             segment of the newest sample; only deltas inside one segment are used
 * @param features            descriptive statistics of the window
 * @param fit                 sensitivity-quantization fit of the pooled yaw/pitch deltas
 * @param unitContinuity      the fitted unit compared with the last fitted unit of this session
 * @param uncertainty         why the window describes less than it could
 * @param confounders         legitimate mechanisms that explain the observed shape at least as well as automation does
 */
public record RotationAnalysis(long windowStartSequence, long windowEndSequence, int segment, Features features, QuantizationFit fit,
                               UnitContinuity unitContinuity, Set<Uncertainty> uncertainty, Set<Confounder> confounders) {
    public RotationAnalysis {
        Objects.requireNonNull(features); Objects.requireNonNull(fit); Objects.requireNonNull(unitContinuity);
        // EnumSet copies: declaration-ordered iteration, so replay digests never depend on a per-JVM hash salt
        var u = EnumSet.noneOf(Uncertainty.class); u.addAll(uncertainty); uncertainty = Collections.unmodifiableSet(u);
        var k = EnumSet.noneOf(Confounder.class); k.addAll(confounders); confounders = Collections.unmodifiableSet(k);
    }

    /** No calibration population, held-out validation or reviewed policy exists yet for rotation windows. */
    public boolean calibrated() { return false; }

    public enum Uncertainty {
        /** Fewer non-zero deltas than the fit needs. */
        INSUFFICIENT_SAMPLES,
        /** The window spans a segment break; only the newest segment was used. */
        SEGMENT_BREAK_IN_WINDOW,
        /** Some or all deltas were too imprecise (unwrapped yaw magnitude, tiny units) to resolve whole counts. */
        PRECISION_LIMITED,
        /** No unit was found, but the search could not reach or test every legitimate unit (large deltas, too few distinct counts). */
        SEARCH_INCOMPLETE,
        /** Pitch deltas touching the clamp were excluded from the fit (the clamp truncates whole counts). */
        PITCH_CLAMP_EXCLUDED,
        /** The client tick period is unknown (Phase 14 signal incomplete): the frames bound, hence the error bound, is unbounded. */
        TICK_RATE_UNRESOLVED,
        /** The server ticking state changed inside the window: deltas may straddle two tick periods. */
        TICK_RATE_CHANGED_IN_WINDOW,
        /** The protocol profile of this client is not the qualified native profile; the kinematics may not apply. */
        PROFILE_UNVERIFIED
    }

    /** Legitimate reasons a native player's deltas are not whole multiples of one unit. Listed whenever the fit is not FIT. */
    public enum Confounder {
        SMOOTH_CAMERA, NON_RAW_OR_FRACTIONAL_INPUT, SENSITIVITY_CHANGED, SCOPING_TRANSITION, CONTROLLER_OR_INPUT_MOD, NON_NATIVE_CLIENT
    }

    public enum UnitContinuity { NO_PREVIOUS, SAME, CHANGED, NOT_APPLICABLE }

    /**
     * @param samples              observations in the used segment
     * @param deltas               consecutive pairs in the used segment
     * @param nonZeroDeltas        pairs whose yaw or pitch changed
     * @param meanAbsYaw           mean |delta yaw| over non-zero pairs, degrees
     * @param maxAbsYaw            max |delta yaw|, degrees
     * @param meanAbsPitch         mean |delta pitch| over non-zero pairs, degrees
     * @param maxAbsPitch          max |delta pitch|, degrees
     * @param largeYawDeltas       pairs with |delta yaw| at or above the configured diagnostic edge
     * @param exactRepeats         consecutive non-zero pairs whose (delta yaw, delta pitch) float bits repeat exactly
     * @param yawOnlyDeltas        non-zero pairs with pitch delta exactly zero
     * @param pitchOutsideProfile  samples whose pitch the native client cannot produce (outside the clamp range)
     * @param pitchAtClamp         samples exactly at a clamp bound
     * @param maxAbsYawValue       largest |yaw| value seen (drives float precision)
     */
    public record Features(int samples, int deltas, int nonZeroDeltas, double meanAbsYaw, float maxAbsYaw, double meanAbsPitch, float maxAbsPitch,
                           int largeYawDeltas, int exactRepeats, int yawOnlyDeltas, int pitchOutsideProfile, int pitchAtClamp, float maxAbsYawValue) {}

    static Set<Confounder> allConfounders() { return EnumSet.allOf(Confounder.class); }
}
