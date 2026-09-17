package me.nicolas.papea2ac.schema;

import java.util.*;

/** Transport-only replay from a coherent empty session; no future domain state is implied. */
public record TransportCapsule(int schema, String profile, String configurationDigest,
        String sourceRevision, long epoch, boolean qualifiedFixture, List<Frame> frames, boolean complete,
        String incompleteReason) {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    /** Convenience for session-start (checkpoint-free) capsules; equivalent to the explicit-reason constructor with an empty reason. */
    public TransportCapsule(int schema, String profile, String configurationDigest, String sourceRevision,
            long epoch, boolean qualifiedFixture, List<Frame> frames, boolean complete) {
        this(schema, profile, configurationDigest, sourceRevision, epoch, qualifiedFixture, frames, complete, "");
    }
    public TransportCapsule {
        Objects.requireNonNull(frames);
        if (schema != 1 || profile == null || profile.isEmpty() || profile.length() > 128
                || configurationDigest == null || !configurationDigest.matches("[0-9a-f]{64}")
                || sourceRevision == null || sourceRevision.length() > 128 || epoch < 1 || frames.size() > 4096)
            throw new IllegalArgumentException("capsule header/limit");
        if (incompleteReason == null || incompleteReason.length() > 64) throw new IllegalArgumentException("incomplete reason");
        if (complete && !incompleteReason.isEmpty()) throw new IllegalArgumentException("complete capsule cannot carry a reason");
        long bytes = 512, lastTime = 0, lastSequence = 0;
        if (!frames.isEmpty() && frames.get(0) instanceof Checkpoint first) lastSequence = first.startSequence() - 1;
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame instanceof Checkpoint && i != 0) throw new IllegalArgumentException("checkpoint must open the capsule");
            bytes += frame.chargedBytes();
            if (frame.elapsedNanos() < lastTime) throw new IllegalArgumentException("clock regression");
            lastTime = frame.elapsedNanos();
            if (frame instanceof Input input && input.sequence() != ++lastSequence)
                throw new IllegalArgumentException("incomplete sequence prefix");
        }
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("capsule retained-byte limit");
        frames = List.copyOf(frames);
    }
    /** True only when every frame from the declared checkpoint boundary (or session start) through the last frame is present with no gap. */
    public boolean prefixComplete() { return complete; }
    public sealed interface Frame permits Checkpoint, Input, Barrier, Clock, Close {
        long elapsedNanos();
        int chargedBytes();
    }
    /** Coherent starting boundary for a capsule that may begin after session start (mid-session (re)enable). */
    public record Checkpoint(long elapsedNanos, long startSequence, long startGeneration, long recordingEpoch,
            String protocolState, boolean captureEnabled, String effectsMode, long lastIngressOrdinal,
            long certifiedIngressOrdinal, long lastProcessingOrdinal, long lastServerRevision) implements Frame {
        public Checkpoint {
            if (elapsedNanos < 0 || startSequence < 1 || startGeneration < 0 || recordingEpoch < 1
                    || lastIngressOrdinal < 0 || certifiedIngressOrdinal < 0 || lastProcessingOrdinal < 0 || lastServerRevision < 0)
                throw new IllegalArgumentException("checkpoint");
            if (protocolState == null || protocolState.isEmpty() || protocolState.length() > 32) throw new IllegalArgumentException("checkpoint protocol state");
            if (effectsMode == null || effectsMode.isEmpty() || effectsMode.length() > 32) throw new IllegalArgumentException("checkpoint effects mode");
        }
        public int chargedBytes() { return 256; }
    }
    public record Context(long worldEpoch, String direction, String sourceKind, long sourceEventId,
            long causalParentSequence, long serverTick, String protocolState, int clientProtocol, String rulesetHash) {
        public Context {
            for (String text : List.of(direction, sourceKind, protocolState))
                if (text.length() > 32) throw new IllegalArgumentException("context tag");
            if (rulesetHash == null || !rulesetHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("ruleset digest");
        }
    }
    public record Input(long elapsedNanos, long capturedNanos, long sequence, long generation,
            Context context, Event event) implements Frame {
        public Input {
            Objects.requireNonNull(context); Objects.requireNonNull(event);
            if (elapsedNanos < 0 || capturedNanos < 0 || capturedNanos > elapsedNanos || sequence < 1 || generation < 0)
                throw new IllegalArgumentException("input time/identity");
        }
        public int chargedBytes() { return 512 + event.chargedBytes(); }
    }
    public record Barrier(long elapsedNanos, String reason, long firstMissingSequence) implements Frame {
        public Barrier { if (elapsedNanos < 0 || reason == null || reason.length() > 64 || firstMissingSequence < 0) throw new IllegalArgumentException("barrier"); }
        public int chargedBytes() { return 256; }
    }
    public record Clock(long elapsedNanos) implements Frame {
        public Clock { if (elapsedNanos < 0) throw new IllegalArgumentException("clock"); }
        public int chargedBytes() { return 64; }
    }
    public record Close(long elapsedNanos) implements Frame {
        public Close { if (elapsedNanos < 0) throw new IllegalArgumentException("close"); }
        public int chargedBytes() { return 64; }
    }
    public sealed interface Event permits Attempt, Outcome, Outbound, WriteOutcome, Tick, ControlChange, CheckpointMarker { int chargedBytes(); }
    /** Schema-1 PayloadCodec bytes, never arbitrary native or Java-serialized objects. */
    public record Payload(byte[] bytes) {
        public Payload { if (bytes == null || bytes.length < 2 || bytes.length > 65536) throw new IllegalArgumentException("payload bytes"); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        public int length() { return bytes.length; }
    }
    public record Attempt(long id, long ingressOrdinal, Payload payload) implements Event {
        public Attempt { Objects.requireNonNull(payload); if (id < 1 || ingressOrdinal < 1) throw new IllegalArgumentException("attempt"); }
        public int chargedBytes() { return 96 + payload.length(); }
    }
    public record Outcome(long attemptId, long processingOrdinal, long serverRevision,
            String result, Payload finalPayload, String originalDigest) implements Event {
        public Outcome {
            if (attemptId < 1 || processingOrdinal < 1 || serverRevision < 0 || result == null || result.length() > 32
                    || originalDigest == null || !originalDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("outcome");
        }
        public int chargedBytes() { return 384 + (finalPayload == null ? 0 : finalPayload.length()); }
    }
    public record Outbound(long wireOrdinal, Payload payload) implements Event {
        public Outbound { Objects.requireNonNull(payload); if (wireOrdinal < 1) throw new IllegalArgumentException("wire"); }
        public int chargedBytes() { return 96 + payload.length(); }
    }
    public record WriteOutcome(long wireOrdinal, boolean success) implements Event {
        public WriteOutcome { if (wireOrdinal < 1) throw new IllegalArgumentException("wire"); }
        public int chargedBytes() { return 64; }
    }
    public record Tick(long serverTick) implements Event { public int chargedBytes() { return 64; } }
    public record ControlChange(String kind, String value) implements Event {
        public ControlChange {
            if (kind == null || kind.isEmpty() || kind.length() > 32) throw new IllegalArgumentException("control kind");
            if (value == null || value.isEmpty() || value.length() > 32) throw new IllegalArgumentException("control value");
        }
        public int chargedBytes() { return 64; }
    }
    public record CheckpointMarker(long requestId) implements Event {
        public CheckpointMarker { if (requestId < 1) throw new IllegalArgumentException("requestId"); }
        public int chargedBytes() { return 32; }
    }
}
