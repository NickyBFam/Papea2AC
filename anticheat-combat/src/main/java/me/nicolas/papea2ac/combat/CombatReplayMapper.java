package me.nicolas.papea2ac.combat;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.schema.CombatCapsule;
import me.nicolas.papea2ac.world.EntityLookup;
import me.nicolas.papea2ac.world.EntityRevision;

/**
 * Combat-local ReplayMapper (COMBAT_MODEL.md; REPLAY_SYSTEM.md R2): the retained attack contexts / interactions -> {@link CombatCapsule}, its canonical
 * bytes and SHA-256. Replaying the same transport observations twice must give byte-identical projections (same generations, target states,
 * attack observations, completeness and contexts).
 */
public final class CombatReplayMapper {
    private CombatReplayMapper() {}
    public static CombatCapsule export(CombatEngine engine, String profileId) {
        List<CombatCapsule.Attack> attacks = new ArrayList<>(); List<CombatCapsule.Interaction> interactions = new ArrayList<>();
        for (AttackContext c : engine.attacks()) {
            var a = c.attacker(); var o = c.observation(); var t = c.timing();
            List<String> reasons = new ArrayList<>(); for (var r : c.completeness().reasons()) reasons.add(r.name());
            attacks.add(new CombatCapsule.Attack(o.sequence(), o.attemptId(), o.ingressOrdinal(), o.targetId(), c.worldEpoch(), target(c.target(), o.targetId()),
                a.status().name(), a.recordSequence(), a.positionSequence(), a.claimedPosition() == null ? 0 : a.claimedPosition().x(), a.claimedPosition() == null ? 0 : a.claimedPosition().y(),
                a.claimedPosition() == null ? 0 : a.claimedPosition().z(), a.rotationKnown(), a.yaw(), a.pitch(), a.rotationSequence(), a.validation().lastClassification(),
                a.validation().lastEndSequence(), a.validation().anchored(), t.status().name(), t.lastTickEndSequence(), t.rttSamples(), t.rttStale(), c.completeness().status().name(), reasons));
        }
        for (CombatEngine.InteractionRecord r : engine.interactions()) {
            var o = r.observation();
            interactions.add(new CombatCapsule.Interaction(o.sequence(), o.attemptId(), o.ingressOrdinal(), o.targetId(), r.worldEpoch(), o.hand(), o.x(), o.y(), o.z(), o.secondaryAction(), target(r.target(), o.targetId())));
        }
        return new CombatCapsule(CombatCapsule.SCHEMA, engine.session().epoch(), profileId, attacks, interactions);
    }
    private static CombatCapsule.Target target(EntityLookup l, int id) {
        EntityRevision v = l.revision();
        return new CombatCapsule.Target(l.status().name(), id, l.key() == null ? -1 : l.key().incarnation(), l.type() == null ? "" : l.type(), v == null ? -1 : v.sequence(),
            v == null ? 0 : v.x(), v == null ? 0 : v.y(), v == null ? 0 : v.z(), v == null ? "" : v.positionStatus().name(), v == null ? 0 : v.yaw(), v == null ? 0 : v.pitch(),
            v == null ? "" : v.rotationStatus().name(), v == null || v.pose() == null ? "" : v.pose(), v == null ? -1 : v.vehicleId(), l.detail());
    }
    public static byte[] encode(CombatCapsule c) {
        try (var bytes = new ByteArrayOutputStream(); var o = new DataOutputStream(bytes)) {
            o.writeInt(c.schema()); o.writeLong(c.sessionEpoch()); o.writeUTF(c.profileId()); o.writeInt(c.attacks().size());
            for (var a : c.attacks()) {
                o.writeLong(a.sequence()); o.writeLong(a.attemptId()); o.writeLong(a.ingressOrdinal()); o.writeInt(a.targetId()); o.writeLong(a.worldEpoch()); target(o, a.target());
                o.writeUTF(a.attackerStatus()); o.writeLong(a.attackerRecordSequence()); o.writeLong(a.attackerPositionSequence()); o.writeLong(Double.doubleToRawLongBits(a.attackerX()));
                o.writeLong(Double.doubleToRawLongBits(a.attackerY())); o.writeLong(Double.doubleToRawLongBits(a.attackerZ())); o.writeBoolean(a.attackerRotationKnown());
                o.writeInt(Float.floatToRawIntBits(a.attackerYaw())); o.writeInt(Float.floatToRawIntBits(a.attackerPitch())); o.writeLong(a.attackerRotationSequence()); o.writeUTF(a.validationClassification());
                o.writeLong(a.validationEndSequence()); o.writeBoolean(a.validationAnchored()); o.writeUTF(a.timingStatus()); o.writeLong(a.lastTickEndSequence()); o.writeLong(a.rttSamples());
                o.writeBoolean(a.rttStale()); o.writeUTF(a.completeness()); o.writeInt(a.reasons().size()); for (String r : a.reasons()) o.writeUTF(r);
            }
            o.writeInt(c.interactions().size());
            for (var i : c.interactions()) {
                o.writeLong(i.sequence()); o.writeLong(i.attemptId()); o.writeLong(i.ingressOrdinal()); o.writeInt(i.targetId()); o.writeLong(i.worldEpoch()); o.writeUTF(i.hand());
                o.writeLong(Double.doubleToRawLongBits(i.x())); o.writeLong(Double.doubleToRawLongBits(i.y())); o.writeLong(Double.doubleToRawLongBits(i.z())); o.writeBoolean(i.secondaryAction()); target(o, i.target());
            }
            o.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void target(DataOutputStream o, CombatCapsule.Target t) throws IOException {
        o.writeUTF(t.status()); o.writeInt(t.networkId()); o.writeLong(t.incarnation()); o.writeUTF(t.type()); o.writeLong(t.revisionSequence()); o.writeLong(Double.doubleToRawLongBits(t.x()));
        o.writeLong(Double.doubleToRawLongBits(t.y())); o.writeLong(Double.doubleToRawLongBits(t.z())); o.writeUTF(t.positionStatus()); o.writeInt(Float.floatToRawIntBits(t.yaw()));
        o.writeInt(Float.floatToRawIntBits(t.pitch())); o.writeUTF(t.rotationStatus()); o.writeUTF(t.pose()); o.writeInt(t.vehicleId()); o.writeUTF(t.detail());
    }
    public static String digest(CombatCapsule c) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(c))); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
