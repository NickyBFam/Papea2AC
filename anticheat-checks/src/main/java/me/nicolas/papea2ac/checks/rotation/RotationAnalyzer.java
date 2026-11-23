package me.nicolas.papea2ac.checks.rotation;
import me.nicolas.papea2ac.player.RotationSeries;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
/**
 * Phase 15: turns the bounded rotation history into feature windows. One instance per session, owned by the session actor.
 *
 * <p>Deterministic: the result depends only on the series values, the kinematics and the configuration (no clock, no randomness, no
 * hash-ordered collection). Bounded: one evaluation reads at most {@code RotationHistory.CAPACITY} samples and runs one bounded
 * {@link QuantizationFit}; evaluation happens once per {@code evaluationStride} new observations. Retained state is two scalars.
 */
public final class RotationAnalyzer {
    private final RotationKinematics kinematics;
    private final RotationAnalysisConfig config;
    private final boolean profileVerified;
    private long lastEvaluatedAccepted;
    private double establishedUnit; // 0 while no window of this session has fitted

    public RotationAnalyzer(RotationKinematics kinematics, RotationAnalysisConfig config, boolean profileVerified) {
        this.kinematics = Objects.requireNonNull(kinematics);
        this.config = Objects.requireNonNull(config);
        this.profileVerified = profileVerified;
    }

    /** Offline/nominal convenience: {@link #offer(RotationSeries, ClientTickCondition)} at the default 20 Hz. Production passes the real condition. */
    public Optional<RotationAnalysis> offer(RotationSeries series) {
        return offer(series, ClientTickCondition.NOMINAL);
    }

    /** Evaluates a window when enough new observations arrived since the previous evaluation. */
    public Optional<RotationAnalysis> offer(RotationSeries series, ClientTickCondition tick) {
        if (series.accepted() - lastEvaluatedAccepted < config.evaluationStride()) return Optional.empty();
        lastEvaluatedAccepted = series.accepted();
        return Optional.of(analyze(series, tick));
    }

    /** Offline/nominal convenience: {@link #analyze(RotationSeries, ClientTickCondition)} at the default 20 Hz. */
    public RotationAnalysis analyze(RotationSeries series) {
        return analyze(series, ClientTickCondition.NOMINAL);
    }

    /**
     * Evaluates the newest segment of the series now. Updates the session's established unit when the window fits.
     * The tick condition scales the frames bound; an unresolved condition, or a ticking-state change inside the window, leaves the
     * per-delta error unbounded, so no quantization fit is attempted (PRECISION_LIMITED plus the tick uncertainty).
     */
    public RotationAnalysis analyze(RotationSeries series, ClientTickCondition tick) {
        Objects.requireNonNull(tick);
        int n = series.size();
        float[] yaw = series.yaws(), pitch = series.pitches();
        int[] seg = series.segments();
        var uncertainty = EnumSet.noneOf(RotationAnalysis.Uncertainty.class);
        if (!profileVerified) uncertainty.add(RotationAnalysis.Uncertainty.PROFILE_UNVERIFIED);
        if (n == 0) {
            var empty = new RotationAnalysis.Features(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            uncertainty.add(RotationAnalysis.Uncertainty.INSUFFICIENT_SAMPLES);
            return new RotationAnalysis(-1, -1, series.currentSegment(), empty,
                    new QuantizationFit(QuantizationFit.Status.INSUFFICIENT_SAMPLES, 0, 0, 0, 0, 0, 0, 0),
                    RotationAnalysis.UnitContinuity.NOT_APPLICABLE, uncertainty, RotationAnalysis.allConfounders());
        }
        int start = n - 1;
        while (start > 0 && seg[start - 1] == seg[n - 1]) start--;
        if (start > 0) uncertainty.add(RotationAnalysis.Uncertainty.SEGMENT_BREAK_IN_WINDOW);

        int pairs = n - 1 - start;
        float[] fitDeltas = new float[2 * Math.max(pairs, 0)];
        float[] fitMagnitudes = new float[fitDeltas.length];
        int fitCount = 0, nonZero = 0, large = 0, repeats = 0, yawOnly = 0, outside = 0, atClamp = 0;
        double sumYaw = 0, sumPitch = 0;
        float maxYaw = 0, maxPitch = 0, maxYawValue = 0;
        int previousYawBits = 0, previousPitchBits = 0;
        boolean havePrevious = false, clampExcluded = false;
        for (int i = start; i < n; i++) {
            if (!kinematics.pitchInProfile(pitch[i])) outside++;
            else if (pitch[i] == kinematics.pitchMin() || pitch[i] == kinematics.pitchMax()) atClamp++;
            maxYawValue = Math.max(maxYawValue, Math.abs(yaw[i]));
            if (i == start) continue;
            float dy = yaw[i] - yaw[i - 1]; // the client's own float subtraction (LocalPlayer.sendPosition)
            float dp = pitch[i] - pitch[i - 1];
            if (dy == 0 && dp == 0) continue;
            nonZero++;
            float ay = Math.abs(dy), ap = Math.abs(dp);
            sumYaw += ay; sumPitch += ap;
            maxYaw = Math.max(maxYaw, ay); maxPitch = Math.max(maxPitch, ap);
            if (ay >= config.largeDeltaDegrees()) large++;
            if (dp == 0) yawOnly++;
            int yb = Float.floatToRawIntBits(dy), pb = Float.floatToRawIntBits(dp);
            if (havePrevious && yb == previousYawBits && pb == previousPitchBits) repeats++;
            previousYawBits = yb; previousPitchBits = pb; havePrevious = true;
            if (ay > 0) { fitDeltas[fitCount] = ay; fitMagnitudes[fitCount] = Math.max(Math.abs(yaw[i]), Math.abs(yaw[i - 1])); fitCount++; }
            if (ap > 0) {
                boolean clamped = !interior(pitch[i]) || !interior(pitch[i - 1]);
                if (clamped) clampExcluded = true;
                else { fitDeltas[fitCount] = ap; fitMagnitudes[fitCount] = Math.max(Math.abs(pitch[i]), Math.abs(pitch[i - 1])); fitCount++; }
            }
        }
        if (clampExcluded) uncertainty.add(RotationAnalysis.Uncertainty.PITCH_CLAMP_EXCLUDED);
        var features = new RotationAnalysis.Features(n - start, pairs, nonZero, nonZero == 0 ? 0 : sumYaw / nonZero, maxYaw,
                nonZero == 0 ? 0 : sumPitch / nonZero, maxPitch, large, repeats, yawOnly, outside, atClamp, maxYawValue);

        boolean tickUnresolved = !tick.resolved();
        boolean tickChanged = !tickUnresolved && tick.stableSinceSequence() > 0 && series.sequences()[start] < tick.stableSinceSequence();
        if (tickUnresolved) uncertainty.add(RotationAnalysis.Uncertainty.TICK_RATE_UNRESOLVED);
        if (tickChanged) uncertainty.add(RotationAnalysis.Uncertainty.TICK_RATE_CHANGED_IN_WINDOW);
        QuantizationFit fit = tickUnresolved || tickChanged
                ? new QuantizationFit(nonZero < config.minSamples() ? QuantizationFit.Status.INSUFFICIENT_SAMPLES : QuantizationFit.Status.PRECISION_LIMITED,
                        0, 0, Double.POSITIVE_INFINITY, fitCount, 0, 0, 0)
                : QuantizationFit.fit(fitDeltas, fitMagnitudes, fitCount, kinematics.minUnit(), kinematics.maxUnit(),
                        tick.framesBound(config.framesPerTickBound()), config);
        switch (fit.status()) {
            case INSUFFICIENT_SAMPLES -> uncertainty.add(RotationAnalysis.Uncertainty.INSUFFICIENT_SAMPLES);
            case PRECISION_LIMITED -> uncertainty.add(RotationAnalysis.Uncertainty.PRECISION_LIMITED);
            case FIT -> { if (fit.resolvable() < fit.samples()) uncertainty.add(RotationAnalysis.Uncertainty.PRECISION_LIMITED); }
            case SEARCH_INCOMPLETE -> uncertainty.add(RotationAnalysis.Uncertainty.SEARCH_INCOMPLETE);
            case NO_FIT -> { if (fit.resolutionFloor() > kinematics.minUnit()) uncertainty.add(RotationAnalysis.Uncertainty.PRECISION_LIMITED); }
        }
        RotationAnalysis.UnitContinuity continuity;
        if (fit.status() != QuantizationFit.Status.FIT) continuity = RotationAnalysis.UnitContinuity.NOT_APPLICABLE;
        else {
            if (establishedUnit == 0) continuity = RotationAnalysis.UnitContinuity.NO_PREVIOUS;
            else continuity = Math.abs(fit.unit() - establishedUnit) <= establishedUnit * 1e-3
                    ? RotationAnalysis.UnitContinuity.SAME : RotationAnalysis.UnitContinuity.CHANGED;
            establishedUnit = fit.unit();
        }
        var confounders = fit.status() == QuantizationFit.Status.FIT ? EnumSet.noneOf(RotationAnalysis.Confounder.class) : RotationAnalysis.allConfounders();
        return new RotationAnalysis(series.sequences()[start], series.sequences()[n - 1], seg[n - 1], features, fit, continuity, uncertainty, confounders);
    }

    /** Diagnostic: the sensitivity implied by a fitted window, under the non-scoping hypothesis. */
    public double impliedSensitivity(RotationAnalysis analysis) {
        return analysis.fit().status() == QuantizationFit.Status.FIT ? kinematics.sensitivityForUnit(analysis.fit().unit(), false) : Double.NaN;
    }

    private boolean interior(float p) {
        return p > kinematics.pitchMin() && p < kinematics.pitchMax();
    }
}
