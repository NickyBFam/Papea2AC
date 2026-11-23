package me.nicolas.papea2ac.world;
/**
 * One immutable network-state revision of an observed entity: the state the observer's client was SENT as of {@code sequence} (in wire order,
 * {@code subIndex} within a bundle). It is the NETWORK target (the client's interpolation endpoint can differ: addDelta, far snap, rotation-only re-target), never a rendered position (CombatProfile:
 * interpolation progress is not modelled), and sending does not imply the client has applied it (the application frontier is not claimed).
 *
 * <p>{@code positionStatus} / {@code rotationStatus}: EXACT when the value follows bit-exactly from observed packets; INTERPOLATION_DEPENDENT after a
 * relative teleport component (resolved against PositionMoveRotation.of(entity): the interpolation target, else the rendered position); UNSUPPORTED after motion the model does not reconstruct;
 * VEHICLE_DEPENDENT while the client places the entity itself because of a vehicle relation (a rider is positioned by {@code positionRider}; a vehicle
 * carrying the observer is client-authoritative) and, once the relation ends, until the entity's next EntityPositionSync (P11-F12, P11-F13).
 * {@code nanos} is the observation's capture clock, the clock of Phase 7 timing and {@code CombatObservation.capturedNanos} (P11-F15). The codec base (the VecDeltaCodec origin of later relative moves) is always exact for a tracked incarnation. {@code pose} null = UNKNOWN (never
 * defaulted to standing); {@code sharedFlags} -1 = UNKNOWN; {@code vehicleId} -1 = no vehicle (the client's state for a fresh entity).
 */
public record EntityRevision(long sequence, int subIndex, long nanos, String cause,
                             double x, double y, double z, Status positionStatus,
                             double baseX, double baseY, double baseZ,
                             float yaw, float pitch, Status rotationStatus, float headYaw,
                             String pose, boolean poseOpaque, int sharedFlags, long metadataRevision, int vehicleId) {
    public enum Status { EXACT, INTERPOLATION_DEPENDENT, UNSUPPORTED, VEHICLE_DEPENDENT }
    public static final int CHARGED_BYTES = 160;
    public EntityRevision {
        if (sequence < 1 || subIndex < 0 || cause == null || positionStatus == null || rotationStatus == null || metadataRevision < 0 || vehicleId < -1) throw new IllegalArgumentException("entity revision");
    }
    public boolean poseKnown() { return pose != null; }
    public String describe() {
        return String.format(java.util.Locale.ROOT, "seq=%d %s pos=(%.5f,%.5f,%.5f)%s rot=(%.2f,%.2f)%s head=%.2f pose=%s flags=%s vehicle=%s",
            sequence, cause, x, y, z, positionStatus == Status.EXACT ? "" : "[" + positionStatus + "]", yaw, pitch, rotationStatus == Status.EXACT ? "" : "[" + rotationStatus + "]",
            headYaw, pose == null ? (poseOpaque ? "OPAQUE" : "UNKNOWN") : pose, sharedFlags < 0 ? "UNKNOWN" : Integer.toString(sharedFlags), vehicleId < 0 ? "none" : "#" + vehicleId);
    }
}
