package me.nicolas.papea2ac.core;

/**
 * Engine-wide configured/effective limits and current usage, so degradation caused by capacity can be
 * explained rather than guessed at. Fixed shape, no per-player fields - see MonitoringSnapshot for the
 * bounded per-session diagnostic that is deliberately kept separate from this global rollup.
 */
public record BudgetStatus(int configuredCapacity, int activeSessions, long globalBudgetBytes, long reservedBytes,
        long replayRollingBytesUsed, long replayRollingBytesLimit, long replayPromotionBytesUsed,
        long replayIoBytesUsed, int writerQueueCapacity, long writerWritten, long writerRejected, long writerFailed) {
}
