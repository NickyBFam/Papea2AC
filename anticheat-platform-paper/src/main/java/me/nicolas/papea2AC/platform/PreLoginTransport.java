package me.nicolas.papea2AC.platform;
import java.util.*;
import io.netty.channel.Channel;
import me.nicolas.papea2ac.core.PacketEngine;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.PacketPayload;

/**
 * Pre-player connection state, owned exclusively by the channel's own Netty event-loop thread
 * (installation and every mutation happen there; {@link #boundSession} is the sole field read from
 * elsewhere and is volatile for that reason). Never keyed or trusted by client-supplied data:
 * {@link #claimedIdentity} is an observation, not a session key.
 */
final class PreLoginTransport {
    private static final int JOURNAL_CAPACITY = 256, JOURNAL_BYTE_CAPACITY = 256 * 1024;
    enum Direction { INBOUND, OUTBOUND }
    record Entry(Direction direction, long ordinal, long capturedNanos, PacketPayload payload) {}
    final TransportSessionId id;
    final Channel channel;
    private TransportPhase phase = TransportPhase.ACCEPTED;
    private final List<Entry> journal = new ArrayList<>();
    private long journalBytes;
    private boolean truncated;
    volatile UUID claimedIdentity;
    volatile UUID establishedIdentity;
    volatile int clientProtocol = -1;
    /** Null until bind(); once set, both early handlers forward directly to it instead of journaling. */
    volatile PacketEngine.Session boundSession;
    String inboundHandlerName, outboundHandlerName;
    private final java.util.concurrent.atomic.AtomicBoolean globalAdmissionReleased = new java.util.concurrent.atomic.AtomicBoolean();
    PreLoginTransport(TransportSessionId id, Channel channel) { this.id = id; this.channel = channel; }
    /** True only the first time this is called for a given instance; lets both the bind-success path and the
     * later channelInactive path race to release this connection's one global admission slot without double-releasing it. */
    boolean releaseGlobalAdmissionOnce() { return globalAdmissionReleased.compareAndSet(false, true); }
    TransportPhase phase() { return phase; }
    /** Illegal transitions are rejected, never silently applied; caller decides fallback. */
    boolean advance(TransportPhase next) {
        if (!phase.canAdvanceTo(next)) return false;
        phase = next;
        return true;
    }
    /** Bounded: overflow marks the prefix incomplete rather than growing without limit or rejecting traffic. */
    void record(Direction direction, long ordinal, long capturedNanos, PacketPayload payload) {
        if (truncated) return;
        int charged = payload.chargedBytes() + 64;
        if (journal.size() >= JOURNAL_CAPACITY || journalBytes + charged > JOURNAL_BYTE_CAPACITY) { truncated = true; return; }
        journal.add(new Entry(direction, ordinal, capturedNanos, payload));
        journalBytes += charged;
    }
    /** Global (aggregate, cross-connection) pressure denied this entry; same visible effect as a local overflow. */
    void truncateForGlobalPressure() { truncated = true; }
    boolean truncated() { return truncated; }
    long journalBytes() { return journalBytes; }
    List<Entry> journalSnapshot() { return List.copyOf(journal); }
    void clear() { journal.clear(); journalBytes = 0; }
}
