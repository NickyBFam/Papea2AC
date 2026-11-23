package me.nicolas.papea2ac.world;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.schema.EntityCapsule;

/**
 * anticheat-world's entity ReplayMapper (REPLAY_SYSTEM.md R2): EntityHistory state -> the immutable {@link EntityCapsule} projection, plus its canonical
 * byte form (fixed field order, doubles/floats as raw IEEE bits, incarnations in order) and SHA-256. Two histories fed the same ordered observations
 * project to identical bytes; any divergence in generations, revisions, exactness or honesty markers changes the digest. State is rebuilt by replaying
 * the transport observations; this projection is the comparison target, not a second source of truth.
 */
public final class EntityReplayMapper {
    private EntityReplayMapper() {}
    public static EntityCapsule export(EntityHistory h) {
        List<EntityCapsule.Entity> entities = new ArrayList<>();
        for (EntityHistory.Rec r : h.retained()) {
            List<EntityCapsule.Revision> revs = new ArrayList<>();
            if (r.deep != null) for (EntityRevision v : r.deep) revs.add(dto(v)); else revs.add(dto(r.current));
            entities.add(new EntityCapsule.Entity(r.key.networkId(), r.key.incarnation(), r.type, r.uuid.getMostSignificantBits(), r.uuid.getLeastSignificantBits(), r.spawnSequence,
                r.endSequence, r.endCause == null ? "" : r.endCause.name(), r.taintSequence, r.taintReason, r.deep != null, revs));
        }
        return new EntityCapsule(EntityCapsule.SCHEMA, h.session().epoch(), h.worldEpoch(), h.epochStartSequence(), h.lastSequence(), h.nextIncarnation(), h.evictionFloorSequence(),
            h.coverageBreakAt(), h.coverageBreakText(), h.capacityOverflowAt(), entities);
    }
    private static EntityCapsule.Revision dto(EntityRevision v) {
        return new EntityCapsule.Revision(v.sequence(), v.subIndex(), v.cause(), v.x(), v.y(), v.z(), v.positionStatus().name(), v.baseX(), v.baseY(), v.baseZ(), v.yaw(), v.pitch(),
            v.rotationStatus().name(), v.headYaw(), v.pose() == null ? "" : v.pose(), v.poseOpaque(), v.sharedFlags(), v.metadataRevision(), v.vehicleId());
    }
    public static byte[] encode(EntityCapsule c) {
        try (var bytes = new ByteArrayOutputStream(); var o = new DataOutputStream(bytes)) {
            o.writeInt(c.schema()); o.writeLong(c.sessionEpoch()); o.writeLong(c.worldEpoch()); o.writeLong(c.epochStartSequence()); o.writeLong(c.lastSequence()); o.writeLong(c.nextIncarnation());
            o.writeLong(c.evictionFloor()); o.writeLong(c.coverageBreakSequence()); o.writeUTF(c.coverageBreakReason()); o.writeLong(c.capacityOverflowSequence()); o.writeInt(c.entities().size());
            for (var e : c.entities()) {
                o.writeInt(e.networkId()); o.writeLong(e.incarnation()); o.writeUTF(e.type()); o.writeLong(e.uuidMost()); o.writeLong(e.uuidLeast()); o.writeLong(e.spawnSequence()); o.writeLong(e.endSequence());
                o.writeUTF(e.endCause()); o.writeLong(e.taintSequence()); o.writeUTF(e.taintReason()); o.writeBoolean(e.deep()); o.writeInt(e.revisions().size());
                for (var v : e.revisions()) {
                    o.writeLong(v.sequence()); o.writeInt(v.subIndex()); o.writeUTF(v.cause()); o.writeLong(Double.doubleToRawLongBits(v.x())); o.writeLong(Double.doubleToRawLongBits(v.y())); o.writeLong(Double.doubleToRawLongBits(v.z()));
                    o.writeUTF(v.positionStatus()); o.writeLong(Double.doubleToRawLongBits(v.baseX())); o.writeLong(Double.doubleToRawLongBits(v.baseY())); o.writeLong(Double.doubleToRawLongBits(v.baseZ()));
                    o.writeInt(Float.floatToRawIntBits(v.yaw())); o.writeInt(Float.floatToRawIntBits(v.pitch())); o.writeUTF(v.rotationStatus()); o.writeInt(Float.floatToRawIntBits(v.headYaw()));
                    o.writeUTF(v.pose()); o.writeBoolean(v.poseOpaque()); o.writeInt(v.sharedFlags()); o.writeLong(v.metadataRevision()); o.writeInt(v.vehicleId());
                }
            }
            o.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static String digest(EntityCapsule c) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(c))); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
