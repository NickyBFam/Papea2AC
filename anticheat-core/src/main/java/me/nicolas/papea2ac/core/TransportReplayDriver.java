package me.nicolas.papea2ac.core;

import java.io.*;
import java.security.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.TransportCapability;
import me.nicolas.papea2ac.protocol.PayloadDigest;
import me.nicolas.papea2ac.protocol.TransportReplayMapper;
import me.nicolas.papea2ac.schema.*;

/** Re-executes packet truth fixtures. It never qualifies the recorded server or executes effects. */
public final class TransportReplayDriver implements ReplayDriverPort {
    public static final String PROFILE = "transport-ieee-java25-v1";
    @Override public List<String> replay(TransportCapsule capsule) throws IOException {
        if (!capsule.complete()) throw new IOException("incomplete transport capsule");
        if (!PROFILE.equals(capsule.profile())) throw new IOException("INCOMPATIBLE_NUMERIC_ENVIRONMENT");
        var key = new SessionKey(new UUID(0, 1), capsule.epoch());
        boolean checkpointed = !capsule.frames().isEmpty() && capsule.frames().get(0) instanceof TransportCapsule.Checkpoint;
        var checkpoint = checkpointed ? (TransportCapsule.Checkpoint) capsule.frames().get(0) : null;
        var health = checkpoint == null ? new HealthLatch() : new HealthLatch(checkpoint.startGeneration());
        var capability = capsule.qualifiedFixture() ? new TransportCapability(true, true, true, true) : TransportCapability.observeOnly();
        var actor = new TruthActor(key, health, capability, new PacketMetrics(), 256);
        if (checkpoint != null)
            actor.seed(checkpoint.startSequence() - 1, checkpoint.lastIngressOrdinal(), checkpoint.certifiedIngressOrdinal(),
                    checkpoint.lastProcessingOrdinal(), checkpoint.lastServerRevision());
        var outputs = new ArrayList<String>(capsule.frames().size());
        try {
            for (var frame : capsule.frames()) {
                switch (frame) {
                    case TransportCapsule.Checkpoint ignored -> { /* already applied above, before the replay loop begins */ }
                    case TransportCapsule.Input input -> actor.process(TransportReplayMapper.restore(input, key), input.elapsedNanos());
                    case TransportCapsule.Clock clock -> actor.tick(clock.elapsedNanos());
                    case TransportCapsule.Close close -> actor.close();
                    case TransportCapsule.Barrier barrier -> {
                        health.invalidate(HealthLatch.Reason.valueOf(barrier.reason()), barrier.firstMissingSequence());
                        actor.tick(barrier.elapsedNanos());
                    }
                }
                outputs.add(actor.replayStateDigest());
            }
            return List.copyOf(outputs);
        } catch (IllegalArgumentException e) { throw new IOException("invalid replay control", e); }
        finally { actor.close(); }
    }
    public static String digest(TruthActor.Snapshot snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeLong(snapshot.processedSequence()); out.writeLong(snapshot.certifiedIngressOrdinal());
            out.writeLong(snapshot.promotingSequence()); out.writeInt(snapshot.pending()); out.writeInt(snapshot.journalBytes());
            out.writeBoolean(snapshot.certificationAvailable()); out.writeUTF(snapshot.reason().name());
            out.writeUTF(snapshot.acceptedServerProjection() == null ? "" : PayloadDigest.of(snapshot.acceptedServerProjection()));
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
