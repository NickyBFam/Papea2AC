package me.nicolas.papea2ac.api;
/** Redacted published observation status. Packet certification is not physics qualification. */
public record CoverageSummary(boolean admitted, boolean captureAvailable, boolean certificationAvailable,
    String status, long captured, long certified, int pending, int queueDepth, int queueCapacity) {}
