package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Rotation;
import java.util.Objects;
/**
 * Phase 15: the bounded rotation feature window that PLAYER_STATE assigns to RotationState ("current value and bounded feature window").
 *
 * <p>Owned and mutated only by the session actor (single-threaded, no locks); consumers read immutable {@link RotationSeries} snapshots.
 * Holds the last {@link #CAPACITY} rotation-bearing observations as raw float bits, in capture order, each tagged with a segment number.
 * A segment break marks a rotation discontinuity the client did not produce by turning (server teleport/rotation change, respawn, world
 * change, vehicle transition, transport gap): no delta is ever computed across a break. Fixed memory: three primitive arrays of
 * {@code CAPACITY} entries; the oldest entry is overwritten, and the count of overwritten entries is reported, never hidden.
 */
public final class RotationHistory {
    public static final int CAPACITY = 64;
    /** Why a segment ended. Every reason is a legitimate non-mouse rotation source or an observation gap, never an accusation. */
    public enum Break { SERVER_ROTATION, TELEPORT_ACCEPTED, RESPAWN, WORLD_CHANGE, VEHICLE_TRANSITION, TRANSPORT_GAP, OUT_OF_ORDER }

    private final long[] sequences = new long[CAPACITY];
    private final int[] yawBits = new int[CAPACITY];
    private final int[] pitchBits = new int[CAPACITY];
    private final int[] segments = new int[CAPACITY];
    private int head; // index of the next write
    private int size;
    private int segment;
    private long accepted;
    private long overwritten;
    private long breaks;
    private long lastSequence = -1;
    private Break lastBreak;

    /** Appends a rotation-bearing observation at its capture sequence. A non-increasing sequence starts a new segment instead of linking. */
    public void observe(long captureSequence, Rotation rotation) {
        Objects.requireNonNull(rotation);
        if (captureSequence < 0) throw new IllegalArgumentException("captureSequence");
        if (captureSequence <= lastSequence) breakSegment(Break.OUT_OF_ORDER);
        sequences[head] = captureSequence;
        yawBits[head] = Float.floatToRawIntBits(rotation.yaw());
        pitchBits[head] = Float.floatToRawIntBits(rotation.pitch());
        segments[head] = segment;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) size++; else overwritten++;
        accepted++;
        lastSequence = captureSequence;
    }

    /** Ends the current segment; the next observation cannot be differenced against anything before it. */
    public void breakSegment(Break reason) {
        Objects.requireNonNull(reason);
        segment++;
        breaks++;
        lastBreak = reason;
    }

    public long accepted() { return accepted; }

    /** Immutable chronological copy (oldest first). Allocates O(CAPACITY); intended for window evaluation, not per packet. */
    public RotationSeries snapshot() {
        long[] seq = new long[size]; float[] yaw = new float[size]; float[] pitch = new float[size]; int[] seg = new int[size];
        int start = (head - size + CAPACITY) % CAPACITY;
        for (int i = 0; i < size; i++) {
            int j = (start + i) % CAPACITY;
            seq[i] = sequences[j]; yaw[i] = Float.intBitsToFloat(yawBits[j]); pitch[i] = Float.intBitsToFloat(pitchBits[j]); seg[i] = segments[j];
        }
        return new RotationSeries(seq, yaw, pitch, seg, segment, accepted, overwritten, breaks, lastBreak);
    }
}
