package me.nicolas.papea2ac.player;
/**
 * Phase 15: immutable chronological snapshot of a {@link RotationHistory} (oldest first). The arrays are private copies made by
 * {@link RotationHistory#snapshot()} and are never exposed for mutation; accessors return them for read-only iteration by the analyzer.
 * {@code lastBreak} is null while no segment has ended.
 */
public record RotationSeries(long[] sequences, float[] yaws, float[] pitches, int[] segments, int currentSegment,
                             long accepted, long overwritten, long breaks, RotationHistory.Break lastBreak) {
    public RotationSeries {
        int n = sequences.length;
        if (yaws.length != n || pitches.length != n || segments.length != n) throw new IllegalArgumentException("ragged series");
        if (accepted < n || overwritten < 0 || breaks < 0) throw new IllegalArgumentException("counters");
    }
    public int size() { return sequences.length; }
}
