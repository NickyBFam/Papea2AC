package me.nicolas.papea2ac.checks.rotation;
/**
 * Phase 15 view of the client's tick period, derived by core from the Phase 14 tick-rate contract
 * ({@code PHASE14_TIMER_CONTRACT_READY} v2: {@code TickCadenceLedger.TickRateSignal} and {@code TickCadenceProfile.clientTickPeriod});
 * the mapping itself is {@code core.RotationTickConditionMapper}.
 *
 * <p>Why rotation needs it: the client turns once per rendered frame and sends once per client tick, so the number of float roundings in one
 * packet delta grows with the tick period. Under {@code /tick rate 5} a tick lasts 200 ms and holds four times the frames of a 50 ms tick.
 *
 * <p>Core mapping (integration): {@code resolved = signal.complete()}; {@code maxTickPeriodNanos} = the largest
 * {@code clientTickPeriod(rate, frozen).maxNanos()} over {@code signal.candidates()}; {@code stableSinceSequence = signal.changeSequence()}
 * (0 when no change was ever observed). An incomplete signal (UNOBSERVED, OVERFLOW, or TRANSITION from an unknown previous state) maps to
 * {@link #UNRESOLVED}: the contract's only rate-independent fact is that the client never ticks faster than 20 Hz, which bounds nothing here.
 *
 * @param resolved            the client's ticking state is provably among the candidates
 * @param maxTickPeriodNanos  the longest client tick period any candidate allows
 * @param stableSinceSequence capture sequence of the latest ticking-state change; a window starting before it may span two rates
 */
public record ClientTickCondition(boolean resolved, long maxTickPeriodNanos, long stableSinceSequence) {
    /** The vanilla period at the default 20 Hz with no change observed. */
    public static final long NOMINAL_TICK_NANOS = 50_000_000L;
    public static final ClientTickCondition NOMINAL = new ClientTickCondition(true, NOMINAL_TICK_NANOS, 0);
    public static final ClientTickCondition UNRESOLVED = new ClientTickCondition(false, NOMINAL_TICK_NANOS, 0);

    public ClientTickCondition {
        if (maxTickPeriodNanos < NOMINAL_TICK_NANOS || stableSinceSequence < 0) throw new IllegalArgumentException("tick condition");
    }

    /** Frames bound scaled to the tick period: {@code ceil(base * period / 50 ms)}. */
    public int framesBound(int baseFramesPerNominalTick) {
        long scaled = Math.ceilDiv((long) baseFramesPerNominalTick * maxTickPeriodNanos, NOMINAL_TICK_NANOS);
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }
}
