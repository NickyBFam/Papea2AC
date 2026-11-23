package me.nicolas.papea2ac.version;
import java.util.Objects;
/**
 * Version-scoped facts that bound how many client ticks a vanilla client can execute in an interval of real time (Phase 14, the client
 * tick cadence; LATENCY_COMPENSATION.md "Timer consumers use recorded monotonic deltas and batching allowances, with server-stall and
 * capture-gap information"). Every field is a fact of the pinned vanilla 26.2 client established from its bytecode (the client jar is
 * unobfuscated; {@code NativeClientCadenceFactsTest} pins each fact), or an explicitly reviewed tolerance. A consumer derives an UPPER
 * bound only: anything that makes a legitimate client tick less often (frame drops, stalls, a lower server tick rate, a frozen game) can
 * only lower the real count, never raise it.
 *
 * <ul>
 * <li>{@code minTickPeriodNanos}: the shortest client tick period. {@code Minecraft} builds its {@code DeltaTracker.Timer} with
 *     {@code 20.0f} ticks per second ({@code msPerTick = 1000 / 20 = 50.0f}) and a target provider {@code getTickTargetMillis(f)} that
 *     returns {@code max(f, tickRateManager.millisecondsPerTick())} while the level runs normally and {@code f} otherwise. A server tick
 *     rate above 20 therefore never speeds the client up; a lower rate or a frozen game only slows it down or leaves it at 50 ms.</li>
 * <li>{@code maxTicksPerFrame}: {@code Minecraft.runTick} executes {@code min(10, advanceGameTime(now))} ticks per frame, and
 *     {@code advanceGameTime} consumes the whole integer part of the accumulated tick residual: ticks owed beyond ten are dropped, never
 *     carried. A client can never be ahead of real time, and its backlog at any instant is below one tick plus one frame's catch-up.</li>
 * <li>{@code frameSlackTicks}: the catch-up a window can contain beyond its duration. Between two game-thread fences the client can run
 *     the capped catch-up of the frame that processed the first fence (credited from time before it) and of the next frame (credited
 *     from the start of the fence's frame, which may precede the request), plus the sub-tick residual; every later frame starts after
 *     the fence and is credited only with time inside the window: {@code 2 x 10 + 1}, and one more tick for
 *     the millisecond truncation of {@code Util.getMillis} and the float accumulation of the residual (at most a few hundredths of a tick
 *     over {@code maxWindowNanos} at any plausible frame rate). 22 in total.</li>
 * <li>{@code clockTolerancePpm}: how much faster the client's monotonic clock may run than the server's. Crystal oscillators drift by
 *     tens of ppm and the Linux kernel slews CLOCK_MONOTONIC by at most 500 ppm under NTP; 2 500 ppm (0.25 %) is five times that margin.
 *     It is a reviewed tolerance, not a measurement.</li>
 * <li>{@code maxWindowNanos}: the longest window a consumer evaluates (bounds anchor retention and keeps the exact arithmetic in a
 *     {@code long}).</li>
 * <li>{@code pingAnsweredOnGameThread}: {@code ClientCommonPacketListenerImpl.handlePing} calls
 *     {@code PacketUtils.ensureRunningOnSameThread}, so the pong is written by the client GAME thread in the same FIFO as the tick end
 *     ({@code Connection.send} from one thread preserves order through {@code eventLoop.execute}). A pong is therefore a game-thread
 *     fence: every tick end after it in the stream belongs to a tick executed after the client processed the ping. (The Phase 7 ACK
 *     inventory names the network thread; that is finding P14-F1. The keep-alive reply IS network-thread and is never a fence.)</li>
 * <li>{@code tickEndEveryTick}: {@code Minecraft.tick} sends {@code ServerboundClientTickEndPacket} at its end while a level and a
 *     connection exist and the game is not paused (multiplayer never pauses); it is the only caller of {@code tick()}.</li>
 * </ul>
 */
public record TickCadenceProfile(String id, long minTickPeriodNanos, int maxTicksPerFrame, int frameSlackTicks, long clockTolerancePpm, long maxWindowNanos,
                             boolean pingAnsweredOnGameThread, boolean tickEndEveryTick) {
    public static final long PPM = 1_000_000L;
    public TickCadenceProfile {
        Objects.requireNonNull(id);
        if (minTickPeriodNanos <= 0 || maxTicksPerFrame < 1 || frameSlackTicks < 2 * maxTicksPerFrame + 1 || clockTolerancePpm < 0 || clockTolerancePpm >= PPM || maxWindowNanos <= 0) throw new IllegalArgumentException("cadence profile");
        // the exact window arithmetic must stay inside a long: maxWindow x (1e6 + ppm) and minTickPeriod x 1e6
        Math.multiplyExact(maxWindowNanos, PPM + clockTolerancePpm); Math.multiplyExact(minTickPeriodNanos, PPM);
    }
    public static final TickCadenceProfile PAPER_26_2 = new TickCadenceProfile("paper-26.2-124-cadence", 50_000_000L, 10, 22, 2_500L, 600_000_000_000L, true, true);

    /** The client's tick period range for one server ticking state, in nanoseconds (both ends inclusive). */
    public record TickPeriod(long minNanos, long maxNanos) {
        public TickPeriod { if (minNanos <= 0 || maxNanos < minNanos) throw new IllegalArgumentException("tick period"); }
        public boolean fixed() { return minNanos == maxNanos; }
    }
    /**
     * The client tick period under the server ticking state {@code (rate, frozen)} (the tick-rate contract consumed by Phase 15). The client
     * stores {@code tickrate = max(rate, 1)} and {@code nanosecondsPerTick = (long) (1e9 / tickrate)} ({@code TickRateManager.setTickRate})
     * and ticks every {@code max(50 ms, nanosecondsPerTick)} while its level runs normally; frozen, it ticks every 50 ms, except during a
     * {@code /tick step} ({@code frozenTicksToRun > 0}), when it runs normally again - so a frozen state spans both periods. Values are exact
     * to the float precision of the client's {@code millisecondsPerTick} (a relative 6e-8).
     */
    public TickPeriod clientTickPeriod(float rate, boolean frozen) {
        if (!Float.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("rate");
        long running = Math.max(minTickPeriodNanos, (long) (1_000_000_000.0 / (double) Math.max(rate, 1.0f)));
        return frozen ? new TickPeriod(minTickPeriodNanos, running) : new TickPeriod(running, running);
    }

    /**
     * The most client ticks a vanilla client can execute strictly between two game-thread fences whose real-time span is at most
     * {@code windowNanos} (the server-clock interval from the first fence's request leaving the encoder tap to the second fence's answer
     * reaching the pre-process tap): {@code floor(window x (1 + tolerance) / minTickPeriod) + frameSlackTicks}. Exact integer arithmetic.
     *
     * @return the bound, or -1 when the window is outside {@code (0, maxWindowNanos]} (no bound is claimed)
     */
    public long maxTicks(long windowNanos) {
        if (windowNanos <= 0 || windowNanos > maxWindowNanos) return -1;
        return Math.multiplyExact(windowNanos, PPM + clockTolerancePpm) / Math.multiplyExact(minTickPeriodNanos, PPM) + frameSlackTicks;
    }
}
