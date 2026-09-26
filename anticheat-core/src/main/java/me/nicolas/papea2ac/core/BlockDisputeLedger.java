package me.nicolas.papea2ac.core;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.protocol.SemanticPayload;

/**
 * P9-F2: the cells whose state the client and the observer world may legitimately disagree about, and for how long.
 *
 * <p>A block state is not one fact shared by both sides. Two bounded windows exist in vanilla 26.2:
 * <ul>
 * <li><b>client prediction</b> - {@code ServerboundUseItemOnPacket} / {@code ServerboundPlayerActionPacket} carry a prediction sequence; the
 *     client applies its own predicted block state IMMEDIATELY. The server acknowledges with {@code ClientboundBlockChangedAckPacket} from the
 *     interaction handler, but the authoritative state reaches this observer only with the chunk broadcast later in the same server tick
 *     ({@code ChunkHolder.broadcastChanges}). The ack therefore does NOT settle the cell - only the applied block change does; until then the
 *     client's world is ahead of ours and its movement obeys a shape we do not have;</li>
 * <li><b>in-flight server change</b> - a block changed by anything else ({@code ClientboundBlockUpdate} / section update) reaches the client
 *     one transport delay later, so for that delay the client still moves against the OLD shape while our world already holds the new one.</li>
 * </ul>
 *
 * Either way the cell's shape is genuinely UNKNOWN for that window: a transition whose reach touches such a cell cannot be proven impossible.
 * This ledger is the bounded record of those cells; {@link #disputed} answers the validation runner, which marks the WORLD dimension of the
 * transition incomplete (never a tolerance, never a suppressed result: an explained transition stays VALID, an unexplained one is UNCERTAIN).
 *
 * <p>Shard-owned like every other per-session ledger. Bounded: at most {@link #MAX_CELLS} cells and {@link #MAX_PENDING} pending predictions;
 * an overflow is recorded as a whole-world dispute until it drains, which is conservative in the only safe direction.
 */
public final class BlockDisputeLedger {
    /** Bounded by construction; a busy builder touches a handful of cells per second, a redstone farm cannot grow this. */
    public static final int MAX_CELLS = 256, MAX_PENDING = 64;
    /** How long after an applied change the client may still be moving against the previous shape, on top of the measured round trip. */
    public static final long TRANSPORT_MARGIN_NANOS = 100_000_000L;
    /** The horizon after which a predicted cell is dropped although no authoritative change ever arrived (the interaction changed nothing, or the update was lost). */
    public static final long PREDICTION_HORIZON_NANOS = 3_000_000_000L;

    /** Engine-wide counters, fixed labels. */
    public static final class Metrics {
        public final LongAdder predictions = new LongAdder(), acknowledged = new LongAdder(), changes = new LongAdder(), disputedTransitions = new LongAdder(), overflows = new LongAdder();
    }
    private record Cell(int x, int y, int z) {}
    /** One disputed cell: the monotonic time until which the disagreement can persist, and whether the client predicted it itself. */
    private static final class Dispute { long untilNanos; boolean prediction; int sequence; Dispute(long untilNanos, boolean prediction, int sequence) { this.untilNanos = untilNanos; this.prediction = prediction; this.sequence = sequence; } }

    private final SessionKey key; private final Metrics metrics;
    private final Map<Cell, Dispute> cells = new LinkedHashMap<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private long everythingDisputedUntilNanos = Long.MIN_VALUE;
    private int lastAck = Integer.MIN_VALUE;
    private Thread owner;

    public BlockDisputeLedger(SessionKey key, Metrics metrics) { this.key = Objects.requireNonNull(key); this.metrics = Objects.requireNonNull(metrics); }
    private void assertOwner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("block dispute ledger ownership"); }
    public SessionKey key() { return key; }

    /** A client block interaction: the targeted cell and (for a placement) the cell the new block would occupy are in dispute until the authoritative change arrives. */
    public void clientPrediction(SemanticPayload payload, long nanos) {
        assertOwner();
        int sequence; int x, y, z; String face;
        switch (payload) {
            case SemanticPayload.UseOn u -> { sequence = u.sequence(); x = u.block().x(); y = u.block().y(); z = u.block().z(); face = u.face().name(); }
            case SemanticPayload.Dig d -> { sequence = d.sequence(); x = d.block().x(); y = d.block().y(); z = d.block().z(); face = d.face().name(); }
            default -> { return; }
        }
        metrics.predictions.increment();
        long until = nanos + PREDICTION_HORIZON_NANOS;
        add(new Cell(x, y, z), until, true, sequence);
        switch (face) { // the placement target: the neighbour the client would fill
            case "UP" -> add(new Cell(x, y + 1, z), until, true, sequence);
            case "DOWN" -> add(new Cell(x, y - 1, z), until, true, sequence);
            case "NORTH" -> add(new Cell(x, y, z - 1), until, true, sequence);
            case "SOUTH" -> add(new Cell(x, y, z + 1), until, true, sequence);
            case "WEST" -> add(new Cell(x - 1, y, z), until, true, sequence);
            case "EAST" -> add(new Cell(x + 1, y, z), until, true, sequence);
            default -> { }
        }
        if (pending.size() >= MAX_PENDING) pending.removeFirst();
        pending.addLast(sequence);
    }
    /**
     * The server acknowledged a client prediction. This does NOT settle the cell: the acknowledgement is sent by the interaction handler
     * immediately, while the authoritative block change is broadcast later in the same server tick, so the observer world is still holding
     * the previous state when the ack arrives (this is exactly how the second live P9-F2 failure got through an ack-settled gate). Only an
     * applied block change - or the horizon - settles a predicted cell.
     */
    public void acknowledged(int sequence, long nanos) {
        assertOwner(); metrics.acknowledged.increment(); lastAck = Math.max(lastAck, sequence);
        while (!pending.isEmpty() && pending.peekFirst() <= sequence) pending.removeFirst();
    }
    /**
     * A block change this observer applied: it settles a client prediction for that cell (this is the authoritative state at last), and
     * opens the opposite window - the client is behind this change by at most one transport delay.
     */
    public void applied(int x, int y, int z, long nanos, long rttNanos) {
        assertOwner(); metrics.changes.increment();
        Cell c = new Cell(x, y, z);
        Dispute d = cells.get(c);
        if (d != null && d.prediction) cells.remove(c); // the prediction is answered; only the transport window below remains
        add(c, nanos + rttNanos + TRANSPORT_MARGIN_NANOS, false, 0);
    }
    /** A coverage break or world change: nothing about the world is settled until the horizon passes. */
    public void reset(long nanos) { assertOwner(); cells.clear(); pending.clear(); everythingDisputedUntilNanos = nanos + TRANSPORT_MARGIN_NANOS; }

    private void add(Cell c, long until, boolean prediction, int sequence) {
        Dispute d = cells.get(c);
        if (d != null) { d.untilNanos = Math.max(d.untilNanos, until); d.prediction |= prediction; if (prediction) d.sequence = Math.max(d.sequence, sequence); return; }
        if (cells.size() >= MAX_CELLS) { metrics.overflows.increment(); everythingDisputedUntilNanos = Math.max(everythingDisputedUntilNanos, until); return; }
        cells.put(c, new Dispute(until, prediction, sequence));
    }
    /** Drops settled entries (called at tick ends and before each query; purely monotonic-time driven). */
    public void expire(long nanos) { assertOwner(); cells.entrySet().removeIf(e -> e.getValue().untilNanos <= nanos); }

    /**
     * Whether any cell of the closed block region {@code [x0..x1] x [y0..y1] x [z0..z1]} is still in dispute at {@code nanos}.
     * The caller passes the transition's reach region; a hit means the WORLD dimension of that transition is incomplete.
     */
    public boolean disputed(int x0, int y0, int z0, int x1, int y1, int z1, long nanos) {
        assertOwner(); expire(nanos);
        if (nanos < everythingDisputedUntilNanos) { metrics.disputedTransitions.increment(); return true; }
        for (Map.Entry<Cell, Dispute> e : cells.entrySet()) {
            Cell c = e.getKey();
            if (c.x() >= x0 && c.x() <= x1 && c.y() >= y0 && c.y() <= y1 && c.z() >= z0 && c.z() <= z1) { metrics.disputedTransitions.increment(); return true; }
        }
        return false;
    }
    /** Bounded diagnostic line. */
    public String describe(long nanos) {
        return "blockDisputes cells=" + cells.size() + "/" + MAX_CELLS + " pendingPredictions=" + pending.size() + " lastAck=" + (lastAck == Integer.MIN_VALUE ? "-" : lastAck)
            + (nanos < everythingDisputedUntilNanos ? " WHOLE-WORLD-DISPUTED" : "");
    }
    public int cellCount() { return cells.size(); }
    public int pendingCount() { return pending.size(); }
}
