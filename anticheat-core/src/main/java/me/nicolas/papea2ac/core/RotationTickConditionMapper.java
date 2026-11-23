package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.checks.rotation.ClientTickCondition;
import me.nicolas.papea2ac.player.TickCadenceLedger;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import java.util.Objects;
/**
 * Phase 15 composition: maps the Phase 14 tick-rate signal ({@code PHASE14_TIMER_CONTRACT_READY} v2, read through
 * {@code TickCadenceRunner.tickRateSignal()}) to the rotation analyzer's {@link ClientTickCondition}. Pure and stateless.
 *
 * <p>Incomplete signals (UNOBSERVED, OVERFLOW, or TRANSITION from an unknown previous state) map to UNRESOLVED: the contract's only
 * rate-independent fact (the client never ticks faster than 20 Hz) bounds no frame count. A complete signal maps to the longest client tick
 * period any candidate allows ({@code clientTickPeriod(rate, frozen).maxNanos()}; frozen covers {@code /tick step} through its upper end),
 * and to the newest change's capture sequence, so a window that starts before it is not fitted.
 */
public final class RotationTickConditionMapper {
    private RotationTickConditionMapper() {}

    public static ClientTickCondition map(TickCadenceLedger.TickRateSignal signal, TickCadenceProfile profile) {
        Objects.requireNonNull(signal); Objects.requireNonNull(profile);
        if (!signal.complete() || signal.candidates().isEmpty()) return ClientTickCondition.UNRESOLVED;
        long longest = ClientTickCondition.NOMINAL_TICK_NANOS;
        for (TickCadenceLedger.RateState state : signal.candidates())
            longest = Math.max(longest, profile.clientTickPeriod(state.rate(), state.frozen()).maxNanos());
        return new ClientTickCondition(true, longest, Math.max(0, signal.changeSequence()));
    }
}
