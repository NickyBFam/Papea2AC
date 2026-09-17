package me.nicolas.papea2ac.core;

/**
 * Bounded, fixed-shape engine-wide diagnostic snapshot. Every field is an aggregate or a global mode -
 * never a per-player payload or an unbounded collection - so exporting this is always safe regardless of
 * session count. Per-session detail belongs to the session-owned ordered snapshot path (PacketEngine.Session
 * / GuardStatus), read individually where an operator has selected one player, never dumped in bulk here.
 * Consistency model: this reads several independently-updated aggregate fields without a single global
 * lock, so it may reflect values from slightly different instants (e.g. activeSessions and queueOccupancy
 * a few nanoseconds apart); it never fabricates an impossible per-session combination because it carries
 * no per-session data at all, only sums/counts that are individually always internally consistent.
 */
public record MonitoringSnapshot(boolean captureEnabled, boolean effectsSuspended, String effectsMode,
        int activeSessions, int certifiedSessions, int overloadedSessions,
        int queueOccupancyTotal, int queueCapacityTotal, int journalBytesTotal,
        long replayRollingBytesUsed, long replayPromotionBytesUsed, long replayIoBytesUsed,
        String writerHealth, long writerWritten, long writerRejected, long writerFailed,
        long unknownOutcomes, long overloadEvents, long checkpointsCreated, long recordingPauses) {
}
