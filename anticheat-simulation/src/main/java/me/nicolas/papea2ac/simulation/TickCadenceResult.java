package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
/**
 * The Phase 14 classification of one cadence target (every window ending at the newest anchor). VALID means every evaluated window holds
 * at most the ticks a vanilla client could have executed in it (a legitimate explanation exists, not proof of an honest client).
 * INVALID means at least one complete window holds more ticks than any vanilla client could have executed in its real-time span: a
 * deterministic exclusion conditional on the profile's client facts. UNCERTAIN means no window could be evaluated with complete knowledge.
 *
 * <p>{@code worst} is the evaluated window with the largest excess (for VALID, the tightest margin; for INVALID, the proof). Every number is
 * exact integer arithmetic on capture-monotonic nanos and stream counts; nothing is a probability or a score.
 */
public record TickCadenceResult(SessionKey session, int segment, long targetSequence, long targetNanos, Classification classification, List<UncertainCode> uncertainReasons,
                            List<InvalidCode> invalidReasons, Window worst, int windowsEvaluated, int windowsSkipped, long movementBeyondTickEnds, String profile) {
    public enum Classification { VALID, UNCERTAIN, INVALID }
    /** Why no complete window was available. */
    public enum UncertainCode { NO_PRIOR_ANCHOR, PROFILE_UNSUPPORTED, WINDOW_OUT_OF_RANGE }
    /** What a complete INVALID window proves. TICK_END_EXCESS: more tick ends than possible ticks; MOVEMENT_EXCESS: more in-tick movement packets than possible ticks. */
    public enum InvalidCode { TICK_END_EXCESS, MOVEMENT_EXCESS }
    /** Which stream count gave the window's lower bound of executed ticks. */
    public enum Source { TICK_END, MOVEMENT }

    /**
     * One evaluated window between the pong of an earlier anchor and the pong of the target: {@code windowNanos} = target answer minus the
     * earlier ping's encoder-tap time; {@code executedAtLeast} = max(tick ends, movements - induced) between the two pongs; {@code maxTicks}
     * from the profile; {@code excess} = executedAtLeast - maxTicks (positive only on an exclusion).
     */
    public record Window(long fromPingSequence, long fromAnswerSequence, long toAnswerSequence, long windowNanos, long tickEnds, long movements, long induced, long executedAtLeast, Source source, long maxTicks, long excess) {
        public Window {
            Objects.requireNonNull(source);
            if (windowNanos <= 0 || tickEnds < 0 || movements < 0 || induced < 0 || executedAtLeast < 0 || maxTicks < 0 || excess != executedAtLeast - maxTicks) throw new IllegalArgumentException("window");
        }
        /** Descriptive only: the apparent tick rate over the window relative to 20 Hz (executedAtLeast x 50 ms / span). Never compared to anything. */
        public double apparentSpeed() { return executedAtLeast * 50_000_000.0 / windowNanos; }
        public String describe() {
            return String.format(Locale.ROOT, "pong %d->%d span %.3fs ticks>=%d (%s; tickEnds=%d moves=%d induced=%d) max=%d excess=%d apparent x%.3f",
                fromAnswerSequence, toAnswerSequence, windowNanos / 1e9, executedAtLeast, source, tickEnds, movements, induced, maxTicks, excess, apparentSpeed());
        }
    }

    public TickCadenceResult {
        Objects.requireNonNull(session); Objects.requireNonNull(classification); uncertainReasons = List.copyOf(uncertainReasons); invalidReasons = List.copyOf(invalidReasons); Objects.requireNonNull(profile);
        if (windowsEvaluated < 0 || windowsSkipped < 0 || movementBeyondTickEnds < 0) throw new IllegalArgumentException("counts");
        switch (classification) {
            case INVALID -> { if (worst == null || worst.excess() <= 0 || invalidReasons.isEmpty() || !uncertainReasons.isEmpty()) throw new IllegalArgumentException("an exclusion needs a complete window with positive excess"); }
            case VALID -> { if (worst == null || windowsEvaluated == 0 || worst.excess() > 0 || !invalidReasons.isEmpty() || !uncertainReasons.isEmpty()) throw new IllegalArgumentException("a witness needs evaluated windows within the bound"); }
            case UNCERTAIN -> { if (uncertainReasons.isEmpty() || !invalidReasons.isEmpty()) throw new IllegalArgumentException("uncertain needs a reason"); }
        }
    }
    public boolean valid() { return classification == Classification.VALID; }
    public boolean uncertain() { return classification == Classification.UNCERTAIN; }
    public boolean invalid() { return classification == Classification.INVALID; }
    public String describe() {
        StringBuilder sb = new StringBuilder(160).append("seq=").append(targetSequence).append(' ').append(classification);
        if (!uncertainReasons.isEmpty()) sb.append(' ').append(uncertainReasons);
        if (!invalidReasons.isEmpty()) sb.append(' ').append(invalidReasons);
        sb.append(" windows=").append(windowsEvaluated);
        if (windowsSkipped > 0) sb.append(" skipped=").append(windowsSkipped);
        if (worst != null) sb.append(classification == Classification.INVALID ? " | " : " | tightest ").append(worst.describe());
        if (movementBeyondTickEnds > 0) sb.append(" | movements beyond tick ends: ").append(movementBeyondTickEnds);
        return sb.toString();
    }
}
