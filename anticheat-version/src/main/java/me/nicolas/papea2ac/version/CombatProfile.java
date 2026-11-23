package me.nicolas.papea2ac.version;
import java.util.List;
import java.util.Objects;
/**
 * Version-scoped combat / entity-network facts for the pinned Paper 26.2 build 124 / vanilla 26.2 client pair (VERSION_SUPPORT.md: numeric and
 * quantization rules, entity poses and interaction semantics belong to the version profile, each with provenance). Established from the pinned
 * jars' bytecode and re-verified by the native lane against the real classes ({@code NativeCombatDifferentialTest}).
 *
 * <ul>
 * <li><b>Attack / interaction packets.</b> 26.2 has a dedicated {@code ServerboundAttackPacket(int entityId)} (the VarInt entity id and nothing
 *     else: no hand, no sneak flag, no hit vector) and a separate {@code ServerboundInteractPacket(entityId, hand, Vec3 location,
 *     usingSecondaryAction)} whose location is always present and uses {@code Vec3.LP_STREAM_CODEC}. The older INTERACT / INTERACT_AT split does
 *     not exist on this protocol ({@link #attackPacketIsDistinct}).</li>
 * <li><b>Relative moves.</b> {@code ClientboundMoveEntityPacket} carries short deltas decoded by {@code VecDeltaCodec.decode(long,long,long)}: all
 *     three zero returns the base; otherwise each non-zero axis is {@code (Math.round(base * 4096.0) + delta) / 4096.0} and each zero axis keeps
 *     the base component bit-for-bit ({@link #decodeAxis}). The client ({@code ClientPacketListener.handleMoveEntity}) decodes from the entity's
 *     {@code VecDeltaCodec} base, then sets the base to the result and interpolates to it.</li>
 * <li><b>Codec base.</b> Set by spawn ({@code Entity.recreateFromPacket -> syncPacketPositionCodec}), by position sync
 *     ({@code handleEntityPositionSync -> getPositionCodec().setBase}) and by relative moves. A teleport ({@code handleTeleportEntity ->
 *     setValuesFromPositionPacket}) does NOT touch the base, and resolves relative components against the entity's CURRENT client position,
 *     which depends on interpolation progress ({@link #teleportUpdatesCodecBase} false, {@link #relativeTeleportUsesClientPosition} true). The
 *     server ({@code ServerEntity}) never sends a teleport packet from its tracker: it resynchronises with position sync, which sets the base on
 *     both sides.</li>
 * <li><b>Updates for an unknown id</b> are dropped by the client ({@code getEntity(...) == null -> return}); a spawn on an id that already exists
 *     replaces the old entity ({@code ClientLevel.addEntity}).</li>
 * <li><b>Synced data.</b> {@code Entity.DATA_SHARED_FLAGS_ID} is id 0 (BYTE) and {@code Entity.DATA_POSE} is id 6 (POSE): the static
 *     initialiser defines SHARED_FLAGS, AIR_SUPPLY, CUSTOM_NAME, CUSTOM_NAME_VISIBLE, SILENT, NO_GRAVITY, POSE, TICKS_FROZEN in that order.</li>
 * <li><b>Interpolation.</b> The client interpolates to the network target ({@code moveOrInterpolateTo}); reconstructing the rendered
 *     interpolation progress needs a qualified client application guarantee that does not exist yet (LATENCY_COMPENSATION.md). Phase 11 records
 *     the network target (the interpolation endpoint) only ({@link #interpolationProgressModelled} false).</li>
 * </ul>
 */
public record CombatProfile(String id, boolean attackPacketIsDistinct, double deltaScale, boolean teleportUpdatesCodecBase, boolean relativeTeleportUsesClientPosition,
                            int sharedFlagsDataId, int poseDataId, List<String> poses, boolean interpolationProgressModelled) {
    public CombatProfile { Objects.requireNonNull(id); poses = List.copyOf(poses); if (deltaScale <= 0 || sharedFlagsDataId < 0 || poseDataId < 0) throw new IllegalArgumentException("combat profile"); }
    /** Pose constants of the vanilla entity Pose enum in declaration order (pinned by the native lane against the real enum). */
    public static final List<String> POSES_26_2 = List.of("STANDING", "FALL_FLYING", "SLEEPING", "SWIMMING", "SPIN_ATTACK", "CROUCHING", "LONG_JUMPING", "DYING",
        "CROAKING", "USING_TONGUE", "SITTING", "ROARING", "SNIFFING", "EMERGING", "DIGGING", "SLIDING", "SHOOTING", "INHALING");
    public static final CombatProfile PAPER_26_2 = new CombatProfile("paper-26.2-124-combat", true, 4096.0, false, true, 0, 6, POSES_26_2, false);

    /** {@code VecDeltaCodec.decode} for one axis: a zero delta keeps the base component bit-for-bit, otherwise {@code (round(base * scale) + delta) / scale}. */
    public double decodeAxis(double base, long delta) { return delta == 0 ? base : (double) (Math.round(base * deltaScale) + delta) / deltaScale; }
    public boolean knownPose(String pose) { return poses.contains(pose); }
}
