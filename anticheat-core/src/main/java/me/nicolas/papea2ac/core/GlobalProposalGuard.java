package me.nicolas.papea2ac.core;

import java.util.*;

/**
 * MITIGATION G2 at proposal time: "Count proposed actionable decisions before capture/queue delay and before execution, not only completed
 * setbacks". Shards call {@link #propose} when an ENFORCE decision is about to propose an intent (before its capsule is captured). The
 * thresholds are the same as the main-thread arbiter ({@link RuntimeControl#admitSynthetic}): within a 10 s window, suspend once distinct
 * actionable sessions reach max(3, ceil(0.20 * N)), N being the eligible sessions at window start; separately, suspend once 3 distinct
 * sessions propose within 1 s, regardless of N. The distinct-session window is bounded by the admitted capacity.
 *
 * <p>Tripping is the out-of-band restrictive latch ("bounded control queues have an out-of-band restrictive latch"). From then on every
 * proposal and every main-thread admission is refused. The main thread applies the restriction to the runtime mode at its next opportunity
 * ({@link PacketEngine#applyPendingRestriction}), and only an explicit re-enable resets the latch. Thread-safe, allocation-bounded, no
 * clock of its own: times are the callers' System.nanoTime() stamps.
 */
public final class GlobalProposalGuard {
    public static final long WINDOW_NANOS = 10_000_000_000L, BURST_NANOS = 1_000_000_000L;
    public static final int BURST_SESSIONS = 3, MIN_WINDOW_SESSIONS = 3;
    private final int capacity; private final Map<UUID, Long> recent = new HashMap<>();
    private long windowStart = Long.MIN_VALUE; private int windowEligible; private volatile boolean tripped; private long proposals, refusals, trips; private String lastTrip = "";

    public GlobalProposalGuard(int capacity) { if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("capacity"); this.capacity = capacity; }

    /** True when the proposal may proceed to capture; false = refused (the guard is, or has just become, tripped). */
    public synchronized boolean propose(UUID session, int eligibleSessions, long nowNanos) {
        Objects.requireNonNull(session);
        if (tripped) { refusals++; return false; }
        if (windowStart == Long.MIN_VALUE || nowNanos - windowStart > WINDOW_NANOS) { windowStart = nowNanos; windowEligible = Math.max(1, Math.min(capacity, eligibleSessions)); recent.clear(); }
        if (recent.size() >= capacity && !recent.containsKey(session)) return trip("WINDOW_CAPACITY");
        recent.put(session, nowNanos); proposals++;
        int burst = 0; for (long t : recent.values()) if (nowNanos - t <= BURST_NANOS) burst++;
        int windowLimit = Math.max(MIN_WINDOW_SESSIONS, (int) Math.ceil(0.20 * windowEligible));
        if (recent.size() >= windowLimit) return trip("WINDOW distinct=" + recent.size() + " limit=" + windowLimit + " eligible=" + windowEligible);
        if (burst >= BURST_SESSIONS) return trip("BURST distinct=" + burst + " within 1s");
        return true;
    }
    private boolean trip(String why) { tripped = true; trips++; refusals++; lastTrip = why; return false; }
    public boolean tripped() { return tripped; }
    /** Only an explicit (re-)enable resets the latch and the window. */
    public synchronized void reset() { tripped = false; recent.clear(); windowStart = Long.MIN_VALUE; }
    public synchronized String describe() { return "proposals=" + proposals + " refusals=" + refusals + " trips=" + trips + (tripped ? " TRIPPED(" + lastTrip + ")" : "") + " windowSessions=" + recent.size(); }
}
