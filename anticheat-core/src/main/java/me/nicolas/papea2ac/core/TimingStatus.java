package me.nicolas.papea2ac.core;
import java.util.List;
/** Primitive-only projection of one session's published TimingLedger view for platform-paper (which may not depend on anticheat-player). */
public record TimingStatus(long sessionEpoch, long worldEpoch, boolean tickEndCapable, long clientTickEnds, long movements, long influences, long teleportsSent, long teleportsConfirmed, long staleTeleportConfirms, long evictions,
                           boolean rttPresent, long rttLastNanos, long rttMinNanos, long rttSmoothedNanos, long rttJitterNanos, long rttMaxNanos, long rttSamples, long rttOutliers, long rttRejected, long rttAgeNanos,
                           long markersSent, long markersAcknowledged, int markersPending, long markersExpired, long markersLateOrUnknown, long markerFailedWrites, List<String> corrections) {
    public TimingStatus { corrections = List.copyOf(corrections); }
}
