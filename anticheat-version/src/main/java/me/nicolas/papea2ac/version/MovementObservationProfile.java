package me.nicolas.papea2ac.version;

/**
 * Version-scoped description of what the vanilla client SERIALIZES about its own movement, i.e. the quantization the
 * reconstruction may rely on (INPUT_RECONSTRUCTION.md / MOVEMENT_SIMULATION.md M2 step 1: "Form observation set O from
 * exact present fields and version-established quantization/numerical intervals. Do not inflate O with an arbitrary
 * speed/reach tolerance"). Phase 6 forward addition to the version module (additive; no Phase 1-5 semantics changed).
 *
 * <p>Facts for the pinned 26.2 client / Paper 26.2 build 124 protocol:
 * <ul>
 * <li>{@code ServerboundMovePlayerPacket.Pos / PosRot} carry the feet position as three IEEE doubles and the rotation as two
 *     floats - no fixed-point quantization: a transmitted position is compared EXACTLY (numeric equality) with a prediction;</li>
 * <li>the client sends a position only when the squared distance to the LAST TRANSMITTED position exceeds
 *     {@link #positionSendThresholdSqr} ({@code LocalPlayer.sendPosition}: {@code dx*dx + dy*dy + dz*dz > Mth.square(2.0E-4)})
 *     or every {@link #positionReminderTicks} ticks; a tick that produced a packet WITHOUT position (rotation-only, status-only)
 *     or no packet at all therefore constrains the true feet position to the closed ball
 *     {@code |p - lastSent|^2 <= positionSendThresholdSqr} around the last transmitted position - the "observation interval"
 *     of a silent tick, derived from the protocol, never a tolerance;</li>
 * <li>rotation is sent when yaw or pitch changed versus the last transmitted rotation ({@code d != 0} on floats): a packet
 *     without rotation means the yaw used by that tick equals the last transmitted yaw exactly.</li>
 * </ul>
 * The threshold lives in client-only code (the client {@code LocalPlayer} class) that is not part of the server jar
 * the controlled extraction reads; it is recorded here from the client's publicly documented behaviour and checked against the
 * real client in the Phase 6 qualification (consecutive transmitted positions in a friction-decay tail are separated by
 * more than the threshold from the previous transmission). A different client build needs its own profile entry.
 */
public record MovementObservationProfile(String id, double positionSendThresholdSqr, int positionReminderTicks) {
    public MovementObservationProfile {
        if (id == null || id.isEmpty() || id.length() > 64) throw new IllegalArgumentException("id");
        if (!(positionSendThresholdSqr > 0.0) || !Double.isFinite(positionSendThresholdSqr) || positionReminderTicks <= 0) throw new IllegalArgumentException("profile");
    }
    /** The client evaluates {@code Mth.square(2.0E-4)} in double arithmetic; the product is kept as the client computes it. */
    public static final double PAPER_26_2_POSITION_SEND_THRESHOLD_SQR = 2.0E-4 * 2.0E-4;
    public static final MovementObservationProfile PAPER_26_2 = new MovementObservationProfile("paper-26.2-124-movement-observation", PAPER_26_2_POSITION_SEND_THRESHOLD_SQR, 20);
    /** Exactly the client's silence condition: {@code true} when a client at {@code (x, y, z)} whose last transmitted position is {@code (lx, ly, lz)} would NOT send a position this tick (ignoring the reminder). */
    public boolean withinSendThreshold(double x, double y, double z, double lx, double ly, double lz) {
        double d0 = x - lx, d1 = y - ly, d2 = z - lz;
        return !(d0 * d0 + d1 * d1 + d2 * d2 > positionSendThresholdSqr);
    }
}
