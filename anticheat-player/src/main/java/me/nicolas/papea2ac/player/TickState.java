package me.nicolas.papea2ac.player;
/**
 * Minimal fixed-counter tick tracking. Distinct server-tick, logical-step and capture-sequence clocks are
 * kept as separate fields per PLAYER_TIMELINE.md Decision L1: they are never conflated to make replay
 * "line up", and captureSequence (not serverTick) is the sole deterministic ordering key elsewhere.
 */
public record TickState(long lastServerTick, boolean hasServerTick, long logicalStep) {
    public TickState {
        if (logicalStep < 0) throw new IllegalArgumentException("logicalStep");
        if (hasServerTick && lastServerTick < 0) throw new IllegalArgumentException("lastServerTick");
    }
    public static final TickState UNKNOWN = new TickState(0, false, 0);
    public TickState observe(long serverTick) { return new TickState(serverTick, true, logicalStep + 1); }
}
