package me.nicolas.papea2ac.core;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.TransportReplayMapper;
import me.nicolas.papea2ac.replay.DurableCapsuleWriter;
import me.nicolas.papea2ac.replay.ReplayQuota;
import me.nicolas.papea2ac.replay.RollingCapture;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * One session's Phase 10 decision-capsule source (shard-owned): the capsule prehistory is the replay ROLLING share
 * (REPLAY_SYSTEM "q=min(256 KiB, 48 MiB/activeSessions)"), not the timeline ring. It is session-count dependent, and TC25 measures that
 * dependence. The share is reserved lazily at the first observation and released on {@link #close}. Every observation is exported in
 * sequence, administrative ones included, with a coherent checkpoint every {@link RollingCapture#CHECKPOINT_INTERVAL_NANOS}.
 * {@link #request} promotes the prehistory through the decision point and submits it for durable storage. {@link PacketEngine} and the
 * TC25 harness run this same code.
 */
public final class DecisionCapture implements AutoCloseable {
    /** The coherent boundary facts recorded in each checkpoint (the actor snapshot at the checkpoint). */
    public record Cursor(long lastIngressOrdinal, long certifiedIngressOrdinal, long lastProcessingOrdinal, long lastServerRevision) {
        public static final Cursor ZERO = new Cursor(0, 0, 0, 0);
    }
    private final ReplayQuota quota; private final long owner, origin;
    private RollingCapture rolling; private ReplayQuota.Lease lease; private boolean refused;
    private volatile RollingCapture.Promotion lastPromotion;

    /** {@code originNanos}: the session-relative capsule clock origin, earlier than any capture time the session can observe. */
    public DecisionCapture(ReplayQuota quota, long owner, long originNanos) { this.quota = Objects.requireNonNull(quota); this.owner = owner; this.origin = originNanos; }

    /** Shard thread. Reserves the fair rolling share on first use, inserts a checkpoint when one is due and appends the observation. */
    public void observe(Observation o, long generation, boolean captureEnabled, String effectsMode, Supplier<Cursor> cursor, int activeSessions, long now) {
        if (rolling == null) {
            if (refused) return;
            int n = Math.max(1, activeSessions); long fair = Math.min(256L << 10, (48L << 20) / n);
            var l = quota.reserve(ReplayQuota.Pool.ROLLING, owner, fair, n);
            if (l.isEmpty()) { refused = true; return; }
            lease = l.get(); rolling = new RollingCapture(lease.bytes());
        }
        try {
            if (rolling.checkpointDue(now)) { Cursor c = cursor.get();
                rolling.checkpoint(new TransportCapsule.Checkpoint(Math.max(0, now - origin), o.sequence(), generation, 1, o.context().protocolState().name(), captureEnabled, effectsMode,
                    c.lastIngressOrdinal(), c.certifiedIngressOrdinal(), c.lastProcessingOrdinal(), c.lastServerRevision()), now); }
            rolling.append(TransportReplayMapper.export(o, origin, now), now);
        } catch (IOException | IllegalArgumentException e) { rolling.breakSegment(); } // a frame that cannot be exported breaks the segment until the next checkpoint: never a gap inside a capsule
    }
    /**
     * Shard thread: closes the capsule through the newest observation and submits it. The result is false, and no receipt follows, when no
     * rolling share was granted, no intact capsule is available, or the writer refuses the job (queue or IO pool saturated). The reason is kept
     * in {@link #lastPromotion}.
     */
    public boolean request(DurableCapsuleWriter writer, String id, String profile, String configurationDigest, long epoch, Consumer<DurableCapsuleWriter.Receipt> receipt) {
        if (writer == null || rolling == null) { lastPromotion = new RollingCapture.Promotion(java.util.Optional.empty(), refused ? "ROLLING_SHARE_REFUSED" : "NO_ROLLING_CAPTURE", 0, 0, 0); return false; }
        var p = rolling.promote(profile, configurationDigest, "phase10-decision", epoch); lastPromotion = p;
        return p.capsule().isPresent() && writer.submit(id, p.capsule().get(), receipt);
    }
    public RollingCapture.Promotion lastPromotion() { return lastPromotion; }
    public RollingCapture rolling() { return rolling; }
    public boolean refused() { return refused; }
    @Override public void close() { if (lease != null) { lease.close(); lease = null; } rolling = null; }
}
