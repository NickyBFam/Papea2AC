package me.nicolas.papea2ac.core;
/** Bounded engine-wide Phase 7 metrics (no per-player labels): marker traffic, RTT sample quality, acknowledgement hygiene, tracer activity. */
public record TimingEngineStatus(String timingProfile, boolean markerPortPresent, long markersIssued, long markerSendFailures, long markersAnswered, long rttSamples, long rttOutliers, long rttRejected, long lateOrUnknownAcks,
                                 long expiredMarkers, long staleTeleportConfirms, long velocityIgnoredUnknownSelf, int activeTraces, long tracerSteps, long tracerExplained, long tracerUnexplained) {}
