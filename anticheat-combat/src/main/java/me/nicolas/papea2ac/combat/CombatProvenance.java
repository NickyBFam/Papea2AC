package me.nicolas.papea2ac.combat;

import java.util.Objects;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.player.PlayerObservation;
import me.nicolas.papea2ac.player.RotationState;
import me.nicolas.papea2ac.player.RttEstimator;
import me.nicolas.papea2ac.player.TimelineLookup;
import me.nicolas.papea2ac.player.TimingLedger;

/**
 * Descriptive provenance attached to an attack: where the attacker's last claimed movement / rotation came from, and the timing context. None of
 * it is a tolerance, an allowance or a verdict (COMBAT_MODEL.md B2: eyes come only from certified trajectories, which is Phase 12's job; RTT is
 * not a reach allowance, LATENCY_COMPENSATION.md).
 */
public final class CombatProvenance {
    private CombatProvenance() {}

    /** Phase 8 movement-validation provenance the core supplies (combat does not depend on simulation). */
    public record MovementValidation(boolean anchored, String anchorKind, String lastClassification, long lastEndSequence) {
        public MovementValidation { Objects.requireNonNull(anchorKind); Objects.requireNonNull(lastClassification); if (anchorKind.length() > 64 || lastClassification.length() > 32) throw new IllegalArgumentException("validation provenance"); }
        public static final MovementValidation UNAVAILABLE = new MovementValidation(false, "none", "NONE", 0);
    }

    /**
     * The attacker's state as the Phase 2 timeline had it just before the attack (the latest record strictly before the attack sequence, never
     * crossing a world epoch, never a later aim). {@code status} mirrors the timeline lookup; a claimed position is a raw client claim.
     */
    public record Attacker(Status status, long recordSequence, Vector3 claimedPosition, long positionSequence, String positionOutcome,
                           float yaw, float pitch, boolean rotationKnown, long rotationSequence, MovementValidation validation) {
        public enum Status { FOUND, EVICTED, PRE_CAPTURE, NEVER_EXISTED, WORLD_EPOCH_CROSSING, INCOMPLETE_COVERAGE }
        public Attacker { Objects.requireNonNull(status); Objects.requireNonNull(positionOutcome); Objects.requireNonNull(validation); if ((status == Status.FOUND) != (claimedPosition != null)) throw new IllegalArgumentException("attacker position exactly when FOUND"); }
        /** The movement preceding the attack is covered by a validated live chain (VALID through it). Raw claims are never promoted to eyes here. */
        public boolean movementValidatedThrough() { return status == Status.FOUND && validation.anchored() && "VALID".equals(validation.lastClassification()) && validation.lastEndSequence() >= positionSequence; }
        public static Attacker of(TimelineLookup lookup, MovementValidation validation) {
            return switch (lookup) {
                case TimelineLookup.Found f -> {
                    var st = f.record().resultingState(); var m = st.movement(); RotationState r = st.rotation();
                    boolean rotationKnown = r.observedAtSequence() > 0 && r != RotationState.UNKNOWN;
                    yield new Attacker(Status.FOUND, f.record().captureSequence(), m.position(), m.observedAtSequence(), outcome(m.lastOutcome()),
                        rotationKnown ? r.rotation().yaw() : 0f, rotationKnown ? r.rotation().pitch() : 0f, rotationKnown, r.observedAtSequence(), validation);
                }
                case TimelineLookup.Evicted e -> miss(Status.EVICTED, validation);
                case TimelineLookup.PreCapture p -> miss(Status.PRE_CAPTURE, validation);
                case TimelineLookup.NeverExisted n -> miss(Status.NEVER_EXISTED, validation);
                case TimelineLookup.WorldEpochCrossing w -> miss(Status.WORLD_EPOCH_CROSSING, validation);
                case TimelineLookup.IncompleteCoverage i -> miss(Status.INCOMPLETE_COVERAGE, validation);
            };
        }
        private static String outcome(PlayerObservation.ApplyResult r) { return r == null ? "UNKNOWN" : r.name(); }
        private static Attacker miss(Status s, MovementValidation v) { return new Attacker(s, -1, null, -1, "UNKNOWN", 0f, 0f, false, -1, v); }
    }

    /**
     * Timing context (Phase 7, descriptive): whether the client sends tick ends on this session, the last client tick end observed before the attack,
     * and the RTT summary at that point. {@code rttStale}: the newest sample is older than the marker retention (LATENCY_COMPENSATION.md: ten seconds).
     */
    public record Timing(Status status, boolean tickEndCapable, long lastTickEndSequence, long rttSamples, long rttMinNanos, long rttMaxNanos, long rttSmoothedNanos,
                         long rttJitterNanos, long rttAgeNanos, boolean rttStale) {
        public enum Status { TICK_ALIGNED, NO_TICK_END_YET, TICK_END_UNSUPPORTED, UNAVAILABLE }
        public static final long RTT_STALE_NANOS = 10_000_000_000L;
        public Timing { Objects.requireNonNull(status); }
        public static final Timing UNAVAILABLE = new Timing(Status.UNAVAILABLE, false, -1, 0, 0, 0, 0, 0, -1, true);
        public static Timing of(TimingLedger.View view, long attackSequence, long attackNanos) {
            if (view == null) return UNAVAILABLE;
            RttEstimator.Summary r = view.rtt();
            long age = r == null || r.samples() == 0 || r.lastSampleAtNanos() == Long.MIN_VALUE ? -1 : attackNanos - r.lastSampleAtNanos();
            boolean stale = age < 0 || age > RTT_STALE_NANOS;
            long tickEnd = view.lastClientTickEndSequence() > 0 && view.lastClientTickEndSequence() < attackSequence ? view.lastClientTickEndSequence() : -1;
            Status s = !view.tickEndCapable() ? Status.TICK_END_UNSUPPORTED : tickEnd < 0 ? Status.NO_TICK_END_YET : Status.TICK_ALIGNED;
            return r == null ? new Timing(s, view.tickEndCapable(), tickEnd, 0, 0, 0, 0, 0, -1, true)
                : new Timing(s, view.tickEndCapable(), tickEnd, r.samples(), r.minNanos(), r.maxNanos(), r.smoothedNanos(), r.jitterNanos(), age, stale);
        }
    }
}
