package me.nicolas.papea2ac.protocol;

import java.io.*;
import java.util.*;

/**
 * Bounded semantic payload encoding. This never accepts native packet bytes.
 * <p>Payload schema byte: {@link #SCHEMA} = 3 is written; 1, 2 and 3 are decoded; anything else fails clearly.
 * Schema 3 differs from schema 2 ONLY by two new semantic tags: 80 (P11-F2, EntityPositionSync, previously conflated with the
 * teleport tag 16) and 81 (P11-F9, LegacyEntityPosition: how a DECODED schema-1/2 tag 16 with an empty relative set - a sync or an
 * absolute teleport, indistinguishable there - is re-encoded). Schema-1/2 bytes are unchanged; tags 80/81 are rejected inside them, and a
 * schema-1/2 tag 16 decodes as LegacyEntityPosition when its relative set is empty (EntityPosition otherwise). A schema-2 decoder
 * rejects schema-3 bytes at the schema byte.
 * Schema 2 (P3-C1) differs from schema 1 ONLY in three semantic tags - 59 JoinGame and 74 Respawn gained the
 * dimension vertical range, 79 ChunkBaseline gained inline section content - plus the per-payload limit
 * (64 KiB -> 128 KiB so a baseline carrying up to 40 KiB of packed section data still encodes). Every other tag is
 * byte-identical in both schemas. Schema-1 bytes of the three changed tags decode through their legacy layout
 * (see SemanticCodec.read); a schema-1 decoder cannot read schema-2 bytes and rejects them at the schema byte.
 */
public final class PayloadCodec {
    public static final int LIMIT = 128 * 1024;
    public static final int SCHEMA = 3, LEGACY_SCHEMA = 1;
    private PayloadCodec() {}
    public static byte[] encode(PacketPayload payload) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(SCHEMA);
            write(out, payload);
        }
        if (bytes.size() > LIMIT) throw new IOException("payload limit");
        return bytes.toByteArray();
    }
    public static PacketPayload decode(byte[] bytes) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("payload limit");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int schema = in.readUnsignedByte();
            if (schema < LEGACY_SCHEMA || schema > SCHEMA) throw new IOException("unsupported payload schema " + schema);
            PacketPayload payload = read(in, false, schema);
            if (in.available() != 0) throw new IOException("trailing payload bytes");
            return payload;
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid semantic payload", e);
        }
    }
    private static void vector(DataOutputStream out, PacketPayload.Position p) throws IOException {
        out.writeDouble(p.x()); out.writeDouble(p.y()); out.writeDouble(p.z());
    }
    private static PacketPayload.Position vector(DataInputStream in) throws IOException {
        return new PacketPayload.Position(in.readDouble(), in.readDouble(), in.readDouble());
    }
    private static void rotation(DataOutputStream out, PacketPayload.Rotation r) throws IOException {
        out.writeFloat(r.yaw()); out.writeFloat(r.pitch());
    }
    private static PacketPayload.Rotation rotation(DataInputStream in) throws IOException {
        return new PacketPayload.Rotation(in.readFloat(), in.readFloat());
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("noncanonical boolean");
        return value == 1;
    }
    private static int count(DataInputStream in, int limit) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > limit) throw new IOException("count limit");
        return value;
    }
    private static String text(DataInputStream in, int limit) throws IOException {
        // readUTF allocates at most 64 KiB, already inside the per-record byte limit.
        String value = in.readUTF();
        if (value.length() > limit) throw new IOException("text limit");
        return value;
    }
    private static void write(DataOutputStream out, PacketPayload payload) throws IOException {
        switch (payload) {
            case SemanticPayload p -> { out.writeByte(11); SemanticCodec.write(out,p); }
            case PacketPayload.Movement p -> {
                out.writeByte(1); out.writeBoolean(p.position() != null);
                if (p.position() != null) vector(out, p.position());
                out.writeBoolean(p.rotation() != null);
                if (p.rotation() != null) rotation(out, p.rotation());
                out.writeBoolean(p.onGround()); out.writeBoolean(p.horizontalCollision());
            }
            case PacketPayload.Control p -> { out.writeByte(2); out.writeUTF(p.kind().name()); out.writeLong(p.value()); }
            case PacketPayload.Uncovered p -> { out.writeByte(3); out.writeUTF(p.family().name()); out.writeUTF(p.reason()); }
            case PacketPayload.Bundle p -> {
                out.writeByte(4); out.writeInt(p.children().size());
                for (var child : p.children()) write(out, child);
            }
            case PacketPayload.Velocity p -> { out.writeByte(5); out.writeInt(p.entityId()); vector(out, p.vector()); }
            case PacketPayload.Explosion p -> {
                out.writeByte(6); vector(out, p.center()); out.writeFloat(p.radius()); out.writeInt(p.blockCount()); out.writeBoolean(p.playerKnockback().isPresent());
                if (p.playerKnockback().isPresent()) vector(out, p.playerKnockback().get());
            }
            case PacketPayload.Vehicle p -> { out.writeByte(7); vector(out, p.position()); rotation(out, p.rotation()); out.writeBoolean(p.onGround()); }
            case PacketPayload.Passengers p -> {
                out.writeByte(8); out.writeInt(p.vehicleId()); out.writeInt(p.passengers().size());
                for (int id : p.passengers()) out.writeInt(id);
            }
            case PacketPayload.Health p -> { out.writeByte(9); out.writeFloat(p.health()); out.writeInt(p.food()); out.writeFloat(p.saturation()); }
            case PacketPayload.TickRate p -> { out.writeByte(10); out.writeFloat(p.rate()); out.writeBoolean(p.frozen()); }
        }
    }
    private static PacketPayload read(DataInputStream in, boolean insideBundle, int schema) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 11 -> SemanticCodec.read(in, schema);
            case 1 -> new PacketPayload.Movement(bool(in) ? vector(in) : null, bool(in) ? rotation(in) : null, bool(in), bool(in));
            case 2 -> new PacketPayload.Control(PacketPayload.ControlKind.valueOf(text(in, 32)), in.readLong());
            case 3 -> new PacketPayload.Uncovered(PacketPayload.Family.valueOf(text(in, 32)), text(in, 128));
            case 4 -> {
                if (insideBundle) throw new IOException("nested bundle");
                int n = count(in, 256); var children = new ArrayList<PacketPayload>(n); int charged = 0;
                for (int i = 0; i < n; i++) {
                    PacketPayload child = read(in, true, schema); charged += child.chargedBytes();
                    if (charged > 65536) throw new IOException("bundle retained-byte limit");
                    children.add(child);
                }
                yield new PacketPayload.Bundle(children);
            }
            case 5 -> new PacketPayload.Velocity(in.readInt(), vector(in));
            case 6 -> {
                var center = vector(in); float radius = in.readFloat(); int blockCount = in.readInt();
                yield new PacketPayload.Explosion(center, radius, blockCount, bool(in) ? Optional.of(vector(in)) : Optional.empty());
            }
            case 7 -> new PacketPayload.Vehicle(vector(in), rotation(in), bool(in));
            case 8 -> {
                int id = in.readInt(), n = count(in, 256); var passengers = new ArrayList<Integer>(n);
                for (int i = 0; i < n; i++) passengers.add(in.readInt());
                yield new PacketPayload.Passengers(id, passengers);
            }
            case 9 -> new PacketPayload.Health(in.readFloat(), in.readInt(), in.readFloat());
            case 10 -> new PacketPayload.TickRate(in.readFloat(), bool(in));
            default -> throw new IOException("unknown payload tag");
        };
    }
}
