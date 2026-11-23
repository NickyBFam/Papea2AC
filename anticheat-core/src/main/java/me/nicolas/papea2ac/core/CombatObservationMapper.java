package me.nicolas.papea2ac.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.nicolas.papea2ac.combat.CombatObservation;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.EntityObservation;

/**
 * Phase 1 observations -> the Phase 11 domain vocabularies (the DAG-mandated translation: combat and world never see protocol types).
 *
 * <p>Clientbound entity packets sent to THIS observer become world {@link EntityObservation}s in wire order (bundle children keep their order under
 * the bundle's sequence, like the world and context mappers); the observer's own entity is excluded (it is the attacker, owned by the player
 * timeline). Each mapped packet is one the 26.2 client uses to change an entity's network state (CombatProfile cites the client handler):
 * AddEntity (spawn), MoveEntity Pos/Rot/PosRot (delta decode), EntityPositionSync (codec base + target), TeleportEntity (target only; relative
 * components), RotateHead, RemoveEntities, SetEntityData (pose and shared flags only), SetPassengers (vehicle relation), and the new-minecart lerp
 * steps (MoveMinecart; not reconstructed, so explicitly UNSUPPORTED). Serverbound ATTACK and INTERACT become {@link CombatObservation}s.
 */
public final class CombatObservationMapper {
    private CombatObservationMapper() {}

    public static List<EntityObservation> entity(Observation o, int selfEntityId, CombatProfile profile) {
        if (!(o.event() instanceof Observation.Outbound out)) return List.of();
        List<EntityObservation> events = new ArrayList<>(1);
        outbound(out.payload(), selfEntityId, profile, events);
        return events;
    }
    private static void outbound(PacketPayload p, int self, CombatProfile profile, List<EntityObservation> out) {
        switch (p) {
            case PacketPayload.Bundle b -> { for (PacketPayload child : b.children()) outbound(child, self, profile, out); }
            case SemanticPayload.EntitySpawn s -> { if (s.entityId() != self) out.add(new EntityObservation.Spawned(s.entityId(), s.uuid(), s.type(), s.position().x(), s.position().y(), s.position().z(), s.rotation().yaw(), s.rotation().pitch(), s.headYaw())); }
            case SemanticPayload.EntityDelta d -> { if (d.entityId() != self) out.add(new EntityObservation.Moved(d.entityId(), d.dx(), d.dy(), d.dz(), d.hasPosition(), d.rotation().yaw(), d.rotation().pitch(), d.hasRotation())); }
            case SemanticPayload.EntityPositionSync s -> { if (s.entityId() != self) out.add(new EntityObservation.PositionSynced(s.entityId(), s.position().x(), s.position().y(), s.position().z(), s.rotation().yaw(), s.rotation().pitch())); }
            case SemanticPayload.EntityPosition t -> { if (t.entityId() != self) out.add(new EntityObservation.Teleported(t.entityId(), t.position().x(), t.position().y(), t.position().z(), t.rotation().yaw(), t.rotation().pitch(), mask(t.relative()))); }
            // P11-F9: a pre-schema-3 sync-or-absolute-teleport: whether the codec base moved is unknowable, so no later delta can be decoded exactly
            case SemanticPayload.LegacyEntityPosition l -> { if (l.entityId() != self) out.add(new EntityObservation.UnsupportedMotion(l.entityId(), "LEGACY_SYNC_OR_TELEPORT")); }
            case SemanticPayload.HeadRotation h -> { if (h.entityId() != self) out.add(new EntityObservation.HeadRotated(h.entityId(), h.yaw())); }
            case SemanticPayload.RemoveEntities r -> { List<Integer> ids = new ArrayList<>(r.ids().size()); for (int id : r.ids()) if (id != self) ids.add(id); if (!ids.isEmpty()) out.add(new EntityObservation.Removed(ids)); }
            case SemanticPayload.EntitySyncedData d -> { if (d.entityId() != self) data(d, profile, out); }
            case PacketPayload.Passengers ps -> out.add(new EntityObservation.Passengers(ps.vehicleId(), ps.passengers().stream().filter(id -> id != self).toList()));
            case SemanticPayload.MinecartMove m -> { if (m.entityId() != self) out.add(new EntityObservation.UnsupportedMotion(m.entityId(), "MINECART_LERP_STEPS")); }
            default -> { }
        }
    }
    private static int mask(java.util.Set<SemanticPayload.Relative> r) {
        int m = 0;
        if (r.contains(SemanticPayload.Relative.X)) m |= EntityObservation.Teleported.REL_X;
        if (r.contains(SemanticPayload.Relative.Y)) m |= EntityObservation.Teleported.REL_Y;
        if (r.contains(SemanticPayload.Relative.Z)) m |= EntityObservation.Teleported.REL_Z;
        if (r.contains(SemanticPayload.Relative.Y_ROT)) m |= EntityObservation.Teleported.REL_YAW;
        if (r.contains(SemanticPayload.Relative.X_ROT)) m |= EntityObservation.Teleported.REL_PITCH;
        return m;
    }
    /** Only the pose (profile id, POSE kind) and the shared flags (profile id, BYTE kind) are kept; a pose field recorded OPAQUE (older capture) is UNKNOWN, never STANDING. */
    private static void data(SemanticPayload.EntitySyncedData d, CombatProfile profile, List<EntityObservation> out) {
        String pose = null; boolean opaque = false; int flags = -1;
        for (SemanticPayload.EntityDataField f : d.fields()) {
            if (f.id() == profile.poseDataId()) { if (f.kind().equals("POSE")) { pose = f.value(); opaque = false; } else { pose = null; opaque = true; } }
            else if (f.id() == profile.sharedFlagsDataId() && f.kind().equals("BYTE")) { try { flags = Byte.parseByte(f.value()) & 0xFF; } catch (NumberFormatException e) { flags = -1; } }
        }
        if (pose != null || opaque || flags >= 0) out.add(new EntityObservation.DataUpdated(d.entityId(), pose, opaque, flags));
    }

    /** A serverbound ATTACK or INTERACT attempt; everything else (including every other entity action) is empty. */
    public static Optional<CombatObservation> combat(Observation o) {
        if (!(o.event() instanceof Observation.Attempt a)) return Optional.empty();
        return switch (a.payload()) {
            case SemanticPayload.Attack at -> Optional.of(new CombatObservation.Attack(o.sequence(), a.id(), a.ingressOrdinal(), at.entityId(), o.capturedNanos()));
            case SemanticPayload.Interact in -> Optional.of(new CombatObservation.Interaction(o.sequence(), a.id(), a.ingressOrdinal(), in.entityId(), o.capturedNanos(), in.hand().name(),
                in.location().x(), in.location().y(), in.location().z(), in.secondary()));
            default -> Optional.empty();
        };
    }
}
