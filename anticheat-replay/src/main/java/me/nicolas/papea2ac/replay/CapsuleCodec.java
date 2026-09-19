package me.nicolas.papea2ac.replay;

import java.io.*;
import java.security.*;
import java.util.*;
import me.nicolas.papea2ac.schema.TransportCapsule;
import me.nicolas.papea2ac.schema.TransportCapsule.*;

/** Uncompressed, bounded local fixture format. Hashes prove integrity, not provenance. */
public final class CapsuleCodec {
    private CapsuleCodec() {}
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static byte[] encode(TransportCapsule capsule) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x50414332); out.writeInt(capsule.schema());
            out.writeUTF(capsule.profile()); out.writeUTF(capsule.configurationDigest()); out.writeUTF(capsule.sourceRevision());
            out.writeLong(capsule.epoch()); out.writeBoolean(capsule.qualifiedFixture()); out.writeBoolean(capsule.complete());
            out.writeUTF(capsule.incompleteReason());
            out.writeInt(capsule.frames().size());
            for (Frame frame : capsule.frames()) {
                var body = new ByteArrayOutputStream();
                try (var record = new DataOutputStream(body)) { write(record, frame); }
                byte[] encoded = body.toByteArray();
                out.writeInt(encoded.length); out.write(encoded); out.write(hash(encoded));
            }
            out.write(hash(bytes.toByteArray()));
        }
        if (bytes.size() > TransportCapsule.MAX_BYTES) throw new IOException("capsule byte limit");
        return bytes.toByteArray();
    }
    public static TransportCapsule decode(byte[] bytes) throws IOException {
        if (bytes.length < 40 || bytes.length > TransportCapsule.MAX_BYTES) throw new IOException("capsule byte limit");
        int bodyLength = bytes.length - 32;
        if (!MessageDigest.isEqual(hash(Arrays.copyOf(bytes, bodyLength)), Arrays.copyOfRange(bytes, bodyLength, bytes.length)))
            throw new IOException("capsule checksum");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (in.readInt() != 0x50414332) throw new IOException("capsule magic");
            int schema = in.readInt();
            if (schema < TransportCapsule.LEGACY_SCHEMA || schema > TransportCapsule.CURRENT_SCHEMA) throw new IOException("unsupported capsule schema " + schema);
            String profile = text(in, 128), configuration = text(in, 64), source = text(in, 128);
            long epoch = in.readLong(); boolean qualified = bool(in), complete = bool(in);
            String incompleteReason = text(in, 64);
            int count = size(in, 4096); var frames = new ArrayList<Frame>(count); long charged = 512;
            for (int i = 0; i < count; i++) {
                int length = size(in, Payload.MAX_BYTES + 4096);
                if (length > in.available() - 32) throw new IOException("truncated frame");
                byte[] record = in.readNBytes(length), digest = in.readNBytes(32);
                if (!MessageDigest.isEqual(hash(record), digest)) throw new IOException("frame checksum");
                try (var input = new DataInputStream(new ByteArrayInputStream(record))) {
                    Frame frame = read(input); charged += frame.chargedBytes();
                    if (charged > TransportCapsule.MAX_BYTES) throw new IOException("retained-byte limit");
                    if (input.available() != 0) throw new IOException("trailing frame bytes");
                    frames.add(frame);
                }
            }
            if (in.available() != 0) throw new IOException("trailing capsule bytes");
            return new TransportCapsule(schema, profile, configuration, source, epoch, qualified, frames, complete, incompleteReason);
        } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("invalid capsule", e); }
    }
    private static int size(DataInputStream in, int limit) throws IOException {
        int n = in.readInt(); if (n < 0 || n > limit) throw new IOException("count/length limit"); return n;
    }
    private static String text(DataInputStream in, int limit) throws IOException {
        String text = in.readUTF(); if (text.length() > limit) throw new IOException("text limit"); return text;
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte(); if (n > 1) throw new IOException("boolean"); return n == 1;
    }
    private static void payload(DataOutputStream out, Payload p) throws IOException {
        out.writeInt(p.length()); out.write(p.bytes());
    }
    private static Payload payload(DataInputStream in) throws IOException {
        int n = size(in, Payload.MAX_BYTES); if (n > in.available()) throw new IOException("truncated payload");
        return new Payload(in.readNBytes(n));
    }
    private static void write(DataOutputStream out, Frame frame) throws IOException {
        switch (frame) {
            case Checkpoint f -> {
                out.writeByte(5); out.writeLong(f.elapsedNanos());
                out.writeLong(f.startSequence()); out.writeLong(f.startGeneration()); out.writeLong(f.recordingEpoch());
                out.writeUTF(f.protocolState()); out.writeBoolean(f.captureEnabled()); out.writeUTF(f.effectsMode());
                out.writeLong(f.lastIngressOrdinal()); out.writeLong(f.certifiedIngressOrdinal());
                out.writeLong(f.lastProcessingOrdinal()); out.writeLong(f.lastServerRevision());
            }
            case Input f -> {
                out.writeByte(1); out.writeLong(f.elapsedNanos()); out.writeLong(f.capturedNanos());
                out.writeLong(f.sequence()); out.writeLong(f.generation());
                Context c = f.context();
                out.writeLong(c.worldEpoch()); out.writeUTF(c.direction()); out.writeUTF(c.sourceKind());
                out.writeLong(c.sourceEventId()); out.writeLong(c.causalParentSequence()); out.writeLong(c.serverTick());
                out.writeUTF(c.protocolState()); out.writeInt(c.clientProtocol()); out.writeUTF(c.rulesetHash());
                event(out, f.event());
            }
            case Barrier f -> { out.writeByte(2); out.writeLong(f.elapsedNanos()); out.writeUTF(f.reason()); out.writeLong(f.firstMissingSequence()); }
            case Clock f -> { out.writeByte(3); out.writeLong(f.elapsedNanos()); }
            case Close f -> { out.writeByte(4); out.writeLong(f.elapsedNanos()); }
        }
    }
    private static Frame read(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte(); long time = in.readLong();
        return switch (tag) {
            case 1 -> {
                long captured = in.readLong(), sequence = in.readLong(), generation = in.readLong();
                Context c = new Context(in.readLong(), text(in, 32), text(in, 32), in.readLong(), in.readLong(),
                        in.readLong(), text(in, 32), in.readInt(), text(in, 64));
                yield new Input(time, captured, sequence, generation, c, event(in));
            }
            case 2 -> new Barrier(time, text(in, 64), in.readLong());
            case 3 -> new Clock(time);
            case 4 -> new Close(time);
            case 5 -> new Checkpoint(time, in.readLong(), in.readLong(), in.readLong(),
                    text(in, 32), bool(in), text(in, 32), in.readLong(), in.readLong(), in.readLong(), in.readLong());
            default -> throw new IOException("unknown frame tag");
        };
    }
    private static void event(DataOutputStream out, Event event) throws IOException {
        switch (event) {
            case Attempt e -> { out.writeByte(1); out.writeLong(e.id()); out.writeLong(e.ingressOrdinal()); payload(out, e.payload()); }
            case Outcome e -> {
                out.writeByte(2); out.writeLong(e.attemptId()); out.writeLong(e.processingOrdinal()); out.writeLong(e.serverRevision());
                out.writeUTF(e.result()); out.writeUTF(e.originalDigest()); out.writeBoolean(e.finalPayload() != null);
                if (e.finalPayload() != null) payload(out, e.finalPayload());
            }
            case Outbound e -> { out.writeByte(3); out.writeLong(e.wireOrdinal()); payload(out, e.payload()); }
            case WriteOutcome e -> { out.writeByte(4); out.writeLong(e.wireOrdinal()); out.writeBoolean(e.success()); }
            case Tick e -> { out.writeByte(5); out.writeLong(e.serverTick()); }
            case ControlChange e -> { out.writeByte(6); out.writeUTF(e.kind()); out.writeUTF(e.value()); }
            case CheckpointMarker e -> { out.writeByte(7); out.writeLong(e.requestId()); }
        }
    }
    private static Event event(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 1 -> new Attempt(in.readLong(), in.readLong(), payload(in));
            case 2 -> {
                long id = in.readLong(), order = in.readLong(), revision = in.readLong();
                String result = text(in, 32), digest = text(in, 64);
                yield new Outcome(id, order, revision, result, bool(in) ? payload(in) : null, digest);
            }
            case 3 -> new Outbound(in.readLong(), payload(in));
            case 4 -> new WriteOutcome(in.readLong(), bool(in));
            case 5 -> new Tick(in.readLong());
            case 6 -> new ControlChange(text(in, 32), text(in, 32));
            case 7 -> new CheckpointMarker(in.readLong());
            default -> throw new IOException("unknown event tag");
        };
    }
}
