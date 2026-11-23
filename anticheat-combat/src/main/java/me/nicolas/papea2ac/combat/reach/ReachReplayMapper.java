package me.nicolas.papea2ac.combat.reach;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.schema.ReachCapsule;

/**
 * Reach-local ReplayMapper (COMBAT_MODEL.md; REPLAY_SYSTEM.md R2): retained {@link ReachValidation}s -> {@link ReachCapsule}, its canonical
 * bytes (fixed field order, raw IEEE bits) and SHA-256. Replaying the same transport observations twice must give byte-identical projections.
 */
public final class ReachReplayMapper {
    private ReachReplayMapper() {}

    public static ReachCapsule export(long sessionEpoch, String profileId, List<ReachValidation> results) {
        List<ReachCapsule.Result> out = new ArrayList<>(results.size());
        for (ReachValidation r : results) {
            List<String> classes = new ArrayList<>(); for (var c : r.missClasses()) classes.add(c.name());
            List<String> reasons = new ArrayList<>(); for (var x : r.reasons()) reasons.add(x.name());
            List<Double> witness = r.witness() == null ? List.of() : List.of(r.witness().eyeX(), r.witness().eyeY(), r.witness().eyeZ(), (double) r.witness().yaw(), (double) r.witness().pitch(),
                r.witness().positionsMinX(), r.witness().positionsMinY(), r.witness().positionsMinZ(), r.witness().positionsMaxX(), r.witness().positionsMaxY(), r.witness().positionsMaxZ(),
                r.witness().maxEntryDistance(), r.witness().range());
            List<Double> cert = r.certificate() == null ? List.of() : List.of((double) r.certificate().scenes(), r.certificate().minDistance(), r.certificate().minResidual());
            ApplicationFrontier f = r.frontier();
            out.add(new ReachCapsule.Result(r.sequence(), r.attackId(), r.status().name(), classes, reasons, r.eyeSource().name(), f.status().name(), f.markerSequence(), f.pongSequence(),
                f.ticksSincePong(), r.targetType() == null ? "" : r.targetType(), r.targetNetworkId(), r.targetIncarnation(), r.scenes(), r.workUnits(), witness, cert, r.entityRange(), r.scale(), r.notes()));
        }
        return new ReachCapsule(ReachCapsule.SCHEMA, sessionEpoch, profileId, out);
    }

    public static byte[] encode(ReachCapsule c) {
        try (var bytes = new ByteArrayOutputStream(); var o = new DataOutputStream(bytes)) {
            o.writeInt(c.schema()); o.writeLong(c.sessionEpoch()); o.writeUTF(c.profileId()); o.writeInt(c.results().size());
            for (var r : c.results()) {
                o.writeLong(r.sequence()); o.writeUTF(r.attackId()); o.writeUTF(r.status()); strings(o, r.missClasses()); strings(o, r.reasons()); o.writeUTF(r.eyeSource()); o.writeUTF(r.window());
                o.writeLong(r.markerSequence()); o.writeLong(r.pongSequence()); o.writeInt(r.ticksSincePong()); o.writeUTF(r.targetType()); o.writeInt(r.targetNetworkId()); o.writeLong(r.targetIncarnation());
                o.writeInt(r.scenes()); o.writeInt(r.workUnits()); doubles(o, r.witness()); doubles(o, r.certificate());
                o.writeLong(Double.doubleToRawLongBits(r.entityRange())); o.writeLong(Double.doubleToRawLongBits(r.scale())); strings(o, r.notes());
            }
            o.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void strings(DataOutputStream o, List<String> s) throws IOException { o.writeInt(s.size()); for (String x : s) o.writeUTF(x); }
    private static void doubles(DataOutputStream o, List<Double> d) throws IOException { o.writeInt(d.size()); for (double x : d) o.writeLong(Double.doubleToRawLongBits(x)); }

    public static String digest(ReachCapsule c) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(c))); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
