package me.nicolas.papea2ac.world;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
/**
 * Observer-scoped entity observations (WORLD_MODEL.md: "Entity histories come from packets sent to the attacker, including relative movement
 * quantization, teleports, metadata and destroy/respawn"). Core maps each clientbound entity packet sent to THIS observer into one of these, in
 * wire order; no server-side entity state ever enters. Values are the ones the client decoded (rotations as the client's floats, positions as
 * the client's doubles, relative moves as the raw short deltas).
 */
public sealed interface EntityObservation {
    int chargedBytes();
    private static void id(int entityId) { if (entityId < 0) throw new IllegalArgumentException("entity id"); }
    private static void finite(double... values) { for (double v : values) if (!Double.isFinite(v)) throw new IllegalArgumentException("non-finite entity value"); }

    /** {@code ClientboundAddEntityPacket}: codec base and target both become the absolute position (the client's {@code recreateFromPacket}). */
    record Spawned(int entityId, UUID uuid, String type, double x, double y, double z, float yaw, float pitch, float headYaw) implements EntityObservation {
        public Spawned { id(entityId); Objects.requireNonNull(uuid); Objects.requireNonNull(type); if (type.isEmpty() || type.length() > 128) throw new IllegalArgumentException("entity type"); finite(x, y, z, yaw, pitch, headYaw); }
        public int chargedBytes() { return 128; }
    }
    /** {@code ClientboundMoveEntityPacket} Pos / Rot / PosRot: raw short deltas decoded from the codec base. */
    record Moved(int entityId, short dx, short dy, short dz, boolean hasPosition, float yaw, float pitch, boolean hasRotation) implements EntityObservation {
        public Moved { id(entityId); finite(yaw, pitch); }
        public int chargedBytes() { return 48; }
    }
    /** {@code ClientboundEntityPositionSyncPacket}: sets the codec base and the target (never relative). */
    record PositionSynced(int entityId, double x, double y, double z, float yaw, float pitch) implements EntityObservation {
        public PositionSynced { id(entityId); finite(x, y, z, yaw, pitch); }
        public int chargedBytes() { return 64; }
    }
    /** {@code ClientboundTeleportEntityPacket}: the target only (the codec base is unchanged); relative components resolve against PositionMoveRotation.of(entity) (interpolation target, else rendered position). */
    record Teleported(int entityId, double x, double y, double z, float yaw, float pitch, int relativeMask) implements EntityObservation {
        public static final int REL_X = 1, REL_Y = 2, REL_Z = 4, REL_YAW = 8, REL_PITCH = 16;
        public Teleported { id(entityId); finite(x, y, z, yaw, pitch); if ((relativeMask & ~31) != 0) throw new IllegalArgumentException("relative mask"); }
        public boolean relativePosition() { return (relativeMask & (REL_X | REL_Y | REL_Z)) != 0; }
        public boolean relativeRotation() { return (relativeMask & (REL_YAW | REL_PITCH)) != 0; }
        public int chargedBytes() { return 64; }
    }
    /** {@code ClientboundRotateHeadPacket}. */
    record HeadRotated(int entityId, float headYaw) implements EntityObservation {
        public HeadRotated { id(entityId); finite(headYaw); }
        public int chargedBytes() { return 24; }
    }
    /** {@code ClientboundRemoveEntitiesPacket}. */
    record Removed(List<Integer> ids) implements EntityObservation {
        public Removed { ids = List.copyOf(ids); if (ids.size() > 1024) throw new IllegalArgumentException("removal count"); ids.forEach(EntityObservation::id); }
        public int chargedBytes() { return 32 + 8 * ids.size(); }
    }
    /** {@code ClientboundSetEntityDataPacket}, reduced to the raw facts the combat model keeps: the pose (null = not in this packet), whether a pose field was
     *  present but not decodable (an older capture recorded it OPAQUE), and the shared-flags byte (-1 = not in this packet). */
    record DataUpdated(int entityId, String pose, boolean poseOpaque, int sharedFlags) implements EntityObservation {
        public DataUpdated { id(entityId); if (pose != null && (pose.isEmpty() || pose.length() > 32)) throw new IllegalArgumentException("pose"); if (sharedFlags < -1 || sharedFlags > 255) throw new IllegalArgumentException("shared flags"); }
        public int chargedBytes() { return 40; }
    }
    /**
     * {@code ClientboundSetPassengersPacket}: the vehicle's complete passenger list (replaces the previous one), the observer's own id removed.
     * {@code vehicleIsObserver}: the vehicle is the observer itself (the listed entities ride it); {@code carriesObserver}: the observer is among the
     * vehicle's passengers (P11-F13: neither relation is dropped). At most 256 passengers, the protocol bound of {@code PacketPayload.Passengers} (P11-F16).
     */
    record Passengers(int vehicleId, List<Integer> passengers, boolean vehicleIsObserver, boolean carriesObserver) implements EntityObservation {
        public static final int MAX_PASSENGERS = 256;
        public Passengers { id(vehicleId); passengers = List.copyOf(passengers); if (passengers.size() > MAX_PASSENGERS) throw new IllegalArgumentException("passenger count"); passengers.forEach(EntityObservation::id);
            if (vehicleIsObserver && carriesObserver) throw new IllegalArgumentException("observer rides itself"); }
        public Passengers(int vehicleId, List<Integer> passengers) { this(vehicleId, passengers, false, false); }
        public int chargedBytes() { return 32 + 8 * passengers.size(); }
    }
    /** P11-F22: the client changed the entity's pose without SetEntityData (death event 3; a Warden's EMERGING spawn): the pose is UNKNOWN until the next pose data. */
    record PoseUnknown(int entityId, String cause) implements EntityObservation {
        public PoseUnknown { id(entityId); Objects.requireNonNull(cause); if (cause.isEmpty() || cause.length() > 24) throw new IllegalArgumentException("cause"); }
        public int chargedBytes() { return 32; }
    }
    /** A packet the combat model deliberately does not reconstruct for this entity (e.g. the new-minecart lerp-step movement): the entity's position becomes UNSUPPORTED. */
    record UnsupportedMotion(int entityId, String kind) implements EntityObservation {
        public UnsupportedMotion { id(entityId); Objects.requireNonNull(kind); if (kind.isEmpty() || kind.length() > 32) throw new IllegalArgumentException("kind"); }
        public int chargedBytes() { return 32; }
    }
    /** A clientbound coverage break (the world model's truth: loss-class Phase 1 latch reasons, compromised capture position); sequence gaps are detected by the history itself. */
    record CoverageBreak(String reason) implements EntityObservation {
        public CoverageBreak { Objects.requireNonNull(reason); if (reason.isEmpty() || reason.length() > 64) throw new IllegalArgumentException("reason"); }
        public int chargedBytes() { return 32; }
    }
}
