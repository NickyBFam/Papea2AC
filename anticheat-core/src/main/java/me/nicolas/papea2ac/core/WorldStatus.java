package me.nicolas.papea2ac.core;
/**
 * Bounded, primitive-only observer-world diagnostic for one session (core-owned so platform-paper, which may not
 * depend on anticheat-world, can display it). Summary of the published WorldSnapshot only - never chunk or block
 * content. Every value describes what was OBSERVED as sent to this client, not server truth.
 */
public record WorldStatus(long sessionEpoch, long worldEpoch, boolean hasDimension, String dimensionId, int minY, int height,
                          long revision, long lastSequence, int chunkCount, int completeChunks, int partialChunks, int contentNotRetainedChunks,
                          int transportIncompleteChunks, long evictedChunks, long unloadedChunks, long privateBytes, long historyBytes, int historyEntries,
                          long historyEvictions, boolean transportBroken, String transportBreakReason, long transportBreaks, long lastBreakSequence, long blockUpdates, String lastBlockUpdate, java.util.List<String> recentBlockUpdates) {}
