package me.nicolas.papea2ac.replay;

import java.util.*;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Owner-confined ROLLING prehistory for decision capsules (REPLAY_SYSTEM "Capture lifecycle": "Share q=min(256 KiB, 48 MiB/activeSessions)
 * lazily. Prefer at most three available seconds of prehistory ... these are opportunities, not guarantees"). Unlike the Phase 1 diagnostic
 * prefix ({@link LiveCapture}), it never goes permanently incomplete: frames are kept in segments, each opened by a coherent
 * {@link TransportCapsule.Checkpoint} supplied by the owner at a regular interval, or at once when the newest segment reaches a quarter of the share; the oldest segment is dropped as soon as the remaining
 * segments still cover the prehistory horizon, or when the byte budget or the capsule frame limit is exceeded. A sequence gap inside a segment
 * breaks only that segment. {@link #promote} closes the capsule for a decision point: the oldest intact segment's checkpoint plus every later
 * frame, contiguous through the decision point - or empty with the reason, never a fabricated checkpoint.
 *
 * <p>Frames carry the owner's session-relative clock (the same origin as the checkpoint), so a promoted capsule is valid as it stands.
 */
public final class RollingCapture {
    public static final long PREHISTORY_NANOS = 3_000_000_000L, CHECKPOINT_INTERVAL_NANOS = 250_000_000L;
    /** A segment reaching budget/4 makes the next checkpoint due at once, so at least three segments fit the share (a segment exceeds the quarter by at most one frame). */
    public static final int MAX_FRAMES = 4096, SEGMENTS_PER_SHARE = 4;
    /** A promotion result: the capsule, or the reason none is available. */
    public record Promotion(Optional<TransportCapsule> capsule, String reason, int frames, long chargedBytes, long spanNanos) {}

    private static final class Segment {
        final TransportCapsule.Checkpoint checkpoint; final long openedAt; final List<TransportCapsule.Frame> frames = new ArrayList<>(); long bytes = 256, nextSequence; boolean intact = true;
        Segment(TransportCapsule.Checkpoint cp, long openedAt) { checkpoint = cp; this.openedAt = openedAt; nextSequence = cp.startSequence(); }
    }
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private final long budget; private long bytes; private int frames; private long lastCheckpointAt = Long.MIN_VALUE; private long dropped, gaps, overflows;

    /** {@code budgetBytes}: the session's reserved rolling share (at most 256 KiB). */
    public RollingCapture(long budgetBytes) { if (budgetBytes < 4096) throw new IllegalArgumentException("rolling budget"); this.budget = budgetBytes; }

    public boolean checkpointDue(long nowNanos) { return lastCheckpointAt == Long.MIN_VALUE || nowNanos - lastCheckpointAt >= CHECKPOINT_INTERVAL_NANOS; }
    /** Opens a new segment at the coherent boundary {@code cp} (its start sequence is the next input's sequence). */
    public void checkpoint(TransportCapsule.Checkpoint cp, long nowNanos) {
        Segment s = new Segment(Objects.requireNonNull(cp), nowNanos); segments.addLast(s); bytes += s.bytes; frames++; lastCheckpointAt = nowNanos; retain(nowNanos);
    }
    /** Appends one frame to the newest segment (dropped when no segment is open yet). */
    public void append(TransportCapsule.Frame frame, long nowNanos) {
        Segment s = segments.peekLast(); if (s == null || !s.intact) return;
        if (frame instanceof TransportCapsule.Input in) { if (in.sequence() != s.nextSequence) { s.intact = false; gaps++; lastCheckpointAt = Long.MIN_VALUE; return; } s.nextSequence++; }
        int b = frame.chargedBytes(); s.frames.add(frame); s.bytes += b; bytes += b; frames++;
        if (s.bytes >= budget / SEGMENTS_PER_SHARE) lastCheckpointAt = Long.MIN_VALUE; // size-triggered checkpoint: a bulk burst (chunk churn) never grows one segment past the share
        retain(nowNanos);
    }
    private void retain(long now) {
        while (segments.size() > 1) {
            Segment second = segmentAt(1);
            boolean horizonCovered = second.openedAt <= now - PREHISTORY_NANOS;
            if (horizonCovered || bytes > budget || frames > MAX_FRAMES || !segments.peekFirst().intact) drop(); else break;
        }
        Segment only = segments.peekFirst();
        if (only != null && segments.size() == 1 && (bytes > budget || frames > MAX_FRAMES)) { drop(); overflows++; } // one segment alone exceeds the share: nothing intact until the next checkpoint
    }
    private Segment segmentAt(int i) { Iterator<Segment> it = segments.iterator(); Segment s = null; for (int k = 0; k <= i; k++) s = it.next(); return s; }
    private void drop() { Segment s = segments.removeFirst(); bytes -= s.bytes; frames -= 1 + s.frames.size(); dropped++; }

    /** The newest segment stops accepting frames (e.g. a frame could not be exported): it stays out of every capsule; a checkpoint is due at once and starts afresh. */
    public void breakSegment() { Segment s = segments.peekLast(); if (s != null && s.intact) { s.intact = false; gaps++; } lastCheckpointAt = Long.MIN_VALUE; }
    /**
     * Closes the decision capsule through the newest frame (owner thread, bounded by the budget): from the OLDEST segment of the newest run of
     * intact, sequence-contiguous segments (each checkpoint starting exactly where the previous segment ended). Never throws, never bridges a gap.
     */
    public Promotion promote(String profile, String configurationDigest, String revision, long epoch) {
        if (segments.isEmpty()) return new Promotion(Optional.empty(), "NO_CHECKPOINT_YET", 0, 0, 0);
        List<Segment> all = new ArrayList<>(segments);
        if (!all.get(all.size() - 1).intact) return new Promotion(Optional.empty(), "NEWEST_SEGMENT_BROKEN", 0, 0, 0);
        int start = all.size() - 1;
        while (start > 0 && all.get(start - 1).intact && all.get(start).checkpoint.startSequence() == all.get(start - 1).nextSequence) start--;
        Segment first = all.get(start);
        List<TransportCapsule.Frame> out = new ArrayList<>(frames); out.add(first.checkpoint); long charged = 512 + 256; long lastTime = first.checkpoint.elapsedNanos();
        for (int i = start; i < all.size(); i++) for (TransportCapsule.Frame f : all.get(i).frames) { out.add(f); charged += f.chargedBytes(); lastTime = Math.max(lastTime, f.elapsedNanos()); }
        if (out.size() > MAX_FRAMES || charged > TransportCapsule.MAX_BYTES) return new Promotion(Optional.empty(), "CAPSULE_LIMIT", out.size(), charged, 0);
        TransportCapsule c;
        try { c = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, profile, configurationDigest, revision, epoch, false, out, true, ""); }
        catch (IllegalArgumentException e) { return new Promotion(Optional.empty(), "CAPSULE_INVALID:" + e.getMessage(), out.size(), charged, 0); }
        return new Promotion(Optional.of(c), "", out.size(), charged, lastTime - first.checkpoint.elapsedNanos());
    }
    public long bytes() { return bytes; }
    public int frames() { return frames; }
    public int segments() { return segments.size(); }
    public long dropped() { return dropped; }
    public long gaps() { return gaps; }
    public long overflows() { return overflows; }
}
