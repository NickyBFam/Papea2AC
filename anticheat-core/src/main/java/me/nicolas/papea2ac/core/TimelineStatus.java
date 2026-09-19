package me.nicolas.papea2ac.core;

/**
 * Bounded, primitive-only Player Timeline diagnostic for one session (core-owned so platform-paper, which may
 * not depend on anticheat-player, can display it). This is the published Snapshot's summary only - never the
 * history itself, never a mutable component reference. Values are OBSERVED facts: teleportSent/teleportAcknowledged
 * describe a correction the server sent and a confirm packet the client returned, not client application; the
 * position/rotation are the latest claimed/observed values, not validated truth.
 */
public record TimelineStatus(long sessionEpoch, long lastCommittedSequence, int retainedRecords, int retainedChargedBytes,
                             long evictions, boolean lastAppendHadCoverageIssue, long worldEpoch, long revision,
                             boolean hasPosition, double x, double y, double z, boolean hasRotation, float yaw, float pitch, boolean onGround,
                             boolean teleportSent, long teleportId, boolean teleportAcknowledged,
                             int activeExternalMotionInfluences, boolean externalMotionOverflow, boolean hasServerTick, long lastServerTick) {}
