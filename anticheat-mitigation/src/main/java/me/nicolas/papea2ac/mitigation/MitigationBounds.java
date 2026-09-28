package me.nicolas.papea2ac.mitigation;
/**
 * The operational limits of MITIGATION.md, verbatim. They bound staleness, player disruption and correction spam; none of them is a
 * detection threshold or says anything about how strong the evidence is (the evidence requirement is {@link SetbackPolicy}).
 *
 * <ul>
 * <li>maximum anchor age 2 s, maximum correction distance 8 blocks ("Exceeding either produces RESYNC_REQUIRED, no long-distance fallback");</li>
 * <li>initial intent deadline 500 ms from proposal; pending client confirmation times out after 5 s;</li>
 * <li>one active correction per player; eight predecessor ids retained to identify duplicates;</li>
 * <li>one correction sent per 2-second cooldown; three failed corrections within 30 s disable further setbacks for the session;</li>
 * <li>anchor history: a bounded ring (the anchor age bound makes older anchors useless; the ring holds more than 2 s at 20 transitions/s).</li>
 * </ul>
 */
public record MitigationBounds(long maxAnchorAgeNanos, double maxCorrectionDistance, long intentDeadlineNanos, long confirmTimeoutNanos, int predecessorIds,
                               long cooldownNanos, int failureLimit, long failureWindowNanos, int anchorHistory, long recoveryHorizonNanos) {
    public static final MitigationBounds DEFAULT = new MitigationBounds(2_000_000_000L, 8.0, 500_000_000L, 5_000_000_000L, 8, 2_000_000_000L, 3, 30_000_000_000L, 64, 5_000_000_000L);
    public MitigationBounds {
        if (maxAnchorAgeNanos <= 0 || !(maxCorrectionDistance > 0) || intentDeadlineNanos <= 0 || confirmTimeoutNanos <= 0 || predecessorIds < 1 || cooldownNanos < 0 || failureLimit < 1 || failureWindowNanos <= 0 || anchorHistory < 1 || recoveryHorizonNanos <= 0)
            throw new IllegalArgumentException("mitigation bounds");
    }
}
