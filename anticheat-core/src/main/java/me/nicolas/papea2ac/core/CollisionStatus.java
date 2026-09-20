package me.nicolas.papea2ac.core;
import java.util.List;
/**
 * Bounded, primitive-only collision diagnostic for one session (task §54), core-owned so platform-paper (which may
 * not depend on anticheat-collision) can display it. Computed from the PUBLISHED world snapshot and the PUBLISHED
 * timeline position (the observed claim, not validated truth). Two explicit poses are evaluated - nothing assumes
 * standing silently - and at most {@link #MAX_BOXES} boxes per pose are rendered. It is a geometry readout only:
 * no movement validity is derived or implied.
 */
public record CollisionStatus(long sessionEpoch, long worldEpoch, long worldRevision, long worldSequence, boolean hasPosition, double x, double y, double z,
                              String profile, List<PoseReport> poses) {
    public static final int MAX_BOXES = 8;
    public record PoseReport(String pose, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, String certainty, String unavailableReason,
                             int cellsVisited, int hits, int boxes, boolean truncated, boolean definitelyObstructed, boolean definitelyClear, String statusCounts, List<String> firstBoxes) {}
}
