package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
/**
 * Phase 14 per-session cadence ledger (player-owned mutable ledger, ARCHITECTURE.md A3): cumulative counts of the client's tick ends and
 * movement packets, the server's outstanding ping markers, and one ANCHOR per answered ping. An anchor freezes, at the position of the pong
 * in the capture stream, the cumulative inbound counts and the outbound context of its ping (when the ping left the encoder tap, how many
 * movement-inducing packets had been sent before it). Two anchors bracket a window: the client ticks whose tick ends lie between the two
 * pongs were executed after the client processed the first ping and before it sent the second pong. The ledger records and bounds; it never
 * decides - {@code TickCadenceValidator} (simulation) turns anchors into a bounded classification.
 *
 * <p>Correlation is deliberately conservative: a pong resolves the OLDEST outstanding ping with its id, whoever sent the ping (a plugin's
 * ping with a colliding id therefore makes the window start earlier, never later); an unknown or duplicate pong creates nothing. A ping
 * whose write failed is forgotten (the client never saw it). Pending pings expire only by capacity: a late pong is still a valid fence.
 *
 * <p>Ownership mirrors {@link TimingLedger}: {@link #apply} and {@link #anchors()} are owner-thread-only (the session's shard); {@link #view()}
 * is safe from any thread. Bounds: {@value #MAX_PENDING_PINGS} pending pings; anchors are kept densely for the newest
 * {@code recentAnchors}, then thinned to at least {@code sparseSpacingNanos} apart, never older than {@code maxWindowNanos} before the newest
 * answer and never more than {@code maxAnchors}. A coverage break starts a new segment and drops every older anchor.
 *
 * <p>The ledger also publishes the client's TICK-RATE SIGNAL ({@link TickRateSignal}): which server ticking states (rate, frozen) the client
 * may currently be applying. The client applies a ticking-state packet on its game thread in wire order, and answers a ping on the same
 * thread in the same order, so a change is SETTLED (proven applied) at the first pong whose ping left after it on the wire; between the
 * change's encoder tap and that pong both the previous and the new state are possible (TRANSITION). Before any ticking-state packet in the
 * session, or after a coverage break, the state is UNOBSERVED; more than {@value #MAX_PENDING_RATE_CHANGES} unsettled changes is OVERFLOW.
 */
public final class TickCadenceLedger {
    public static final int MAX_PENDING_PINGS = 64, DEFAULT_RECENT_ANCHORS = 48, DEFAULT_MAX_ANCHORS = 352;
    public static final long DEFAULT_SPARSE_SPACING_NANOS = 2_000_000_000L;
    public static final int MAX_PENDING_RATE_CHANGES = 8;

    /** One server ticking state as the client receives it ({@code ClientboundTickingStatePacket}: rate, frozen). */
    public record RateState(float rate, boolean frozen) {
        public static final RateState NOMINAL = new RateState(20.0f, false);
        public RateState { if (!Float.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("rate"); }
    }
    /**
     * The ticking states the client may be applying at the ledger's current position. {@code candidates} are in application order (the
     * oldest possible first, the newest sent last); exactly one when SETTLED. {@code complete} is true when the list provably contains the
     * client's state (SETTLED or TRANSITION from an observed state); UNOBSERVED and OVERFLOW are incomplete: a consumer must not assume a
     * rate, only the client facts that hold for every rate (a vanilla client never ticks faster than 20 Hz).
     * {@code changeSequence} is the capture sequence of the newest change, {@code settledSequence} the pong that settled the current state.
     */
    public record TickRateSignal(Status status, List<RateState> candidates, boolean complete, long changeSequence, long settledSequence) {
        public enum Status { UNOBSERVED, SETTLED, TRANSITION, OVERFLOW }
        public static final TickRateSignal UNOBSERVED = new TickRateSignal(Status.UNOBSERVED, List.of(), false, 0, 0);
        public TickRateSignal {
            Objects.requireNonNull(status); candidates = List.copyOf(candidates);
            if (status == Status.SETTLED && (candidates.size() != 1 || !complete)) throw new IllegalArgumentException("settled needs exactly one state");
            if ((status == Status.UNOBSERVED || status == Status.OVERFLOW) && complete) throw new IllegalArgumentException("incomplete status");
        }
    }

    /** One answered ping: the fence pair (request at the encoder tap, answer at the pre-process tap) and the stream counts at the answer. */
    public record Anchor(int segment, long pingId, long pingSequence, long pingNanos, long pingWireOrdinal, long inducedBeforePing,
                         long answerSequence, long answerNanos, long tickEnds, long movements) {
        public Anchor { if (pingSequence < 1 || answerSequence <= pingSequence || pingWireOrdinal < 1 || inducedBeforePing < 0 || tickEnds < 0 || movements < 0) throw new IllegalArgumentException("anchor"); }
    }
    public record Counters(long events, long tickEnds, long movements, long induced, long pingsSent, long pingsAnswered, long unknownAnswers, long failedPings, long pingsEvictedForCapacity,
                           long anchorsThinned, long anchorsExpired, long coverageBreaks, long tickRateChanges, long rejected) {
        public static final Counters ZERO = new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    public record View(SessionKey key, boolean closed, int segment, long lastSequence, long lastNanos, int pendingPings, int anchors, long oldestAnchorPingNanos, long newestAnchorAnswerNanos,
                       TickRateSignal tickRate, Counters counters) {}

    private record PendingPing(long id, long sequence, long nanos, long wireOrdinal, long inducedBefore) {}
    private record PendingRate(RateState state, long sequence, long wireOrdinal) {}

    private final SessionKey key;
    private final long maxWindowNanos, sparseSpacingNanos;
    private final int recentAnchors, maxAnchors;
    private final ArrayDeque<PendingPing> pending = new ArrayDeque<>();
    private final ArrayList<Anchor> anchors = new ArrayList<>();
    private final List<Anchor> anchorsView = Collections.unmodifiableList(anchors);
    private Thread owner;
    private int segment;
    private long lastSequence, lastNanos, events, tickEnds, movements, induced, pingsSent, pingsAnswered, unknownAnswers, failedPings, evicted, thinned, expired, breaks, rateChanges, rejected;
    private final ArrayDeque<PendingRate> pendingRates = new ArrayDeque<>();
    private RateState settledRate; // null = unobserved
    private long settledRateSequence, rateChangeSequence;
    private boolean rateOverflow, closed;
    private TickRateSignal signal = TickRateSignal.UNOBSERVED;
    private volatile View view;

    public TickCadenceLedger(SessionKey key, long maxWindowNanos) { this(key, maxWindowNanos, DEFAULT_RECENT_ANCHORS, DEFAULT_SPARSE_SPACING_NANOS, DEFAULT_MAX_ANCHORS); }
    public TickCadenceLedger(SessionKey key, long maxWindowNanos, int recentAnchors, long sparseSpacingNanos, int maxAnchors) {
        this.key = Objects.requireNonNull(key);
        if (maxWindowNanos <= 0 || recentAnchors < 2 || sparseSpacingNanos <= 0 || maxAnchors < recentAnchors) throw new IllegalArgumentException("cadence bounds");
        this.maxWindowNanos = maxWindowNanos; this.recentAnchors = recentAnchors; this.sparseSpacingNanos = sparseSpacingNanos; this.maxAnchors = maxAnchors;
        publish();
    }

    private void assertOwner() {
        if (owner == null) owner = Thread.currentThread();
        else if (owner != Thread.currentThread()) throw new IllegalStateException("cadence ledger ownership");
    }

    /**
     * Applies one event in capture order. A stale-session, closed or out-of-order (decreasing sequence) event changes nothing (counted as rejected).
     * @return the anchor this event created (an answered ping), else empty
     */
    public Optional<Anchor> apply(SessionKey observedFor, TickCadenceEvent event) {
        assertOwner();
        Objects.requireNonNull(event);
        // one observation may yield several events (a bundle's children, a coverage break synthesized at the observation that latched it),
        // so they share its sequence: the order is non-decreasing, and the caller applies each observation once
        if (!key.equals(observedFor) || closed || event.sequence() < lastSequence) { rejected++; publish(); return Optional.empty(); }
        lastSequence = event.sequence(); lastNanos = event.nanos();
        events++;
        Anchor created = null;
        switch (event) {
            case TickCadenceEvent.TickEnd t -> tickEnds++;
            case TickCadenceEvent.Movement m -> movements++;
            case TickCadenceEvent.InducedSent i -> induced++;
            case TickCadenceEvent.PingSent p -> {
                if (pending.size() >= MAX_PENDING_PINGS) { pending.removeFirst(); evicted++; }
                pending.addLast(new PendingPing(p.id(), p.sequence(), p.nanos(), p.wireOrdinal(), induced)); pingsSent++;
            }
            case TickCadenceEvent.WriteFailed w -> {
                for (Iterator<PendingPing> it = pending.iterator(); it.hasNext();) if (it.next().wireOrdinal() == w.wireOrdinal()) { it.remove(); failedPings++; break; }
            }
            case TickCadenceEvent.PingAnswered a -> {
                PendingPing match = null;
                for (Iterator<PendingPing> it = pending.iterator(); it.hasNext();) { PendingPing p = it.next(); if (p.id() == a.id()) { match = p; it.remove(); break; } } // the OLDEST ping with this id
                if (match == null) unknownAnswers++;
                else {
                    pingsAnswered++; created = new Anchor(segment, match.id(), match.sequence(), match.nanos(), match.wireOrdinal(), match.inducedBefore(), a.sequence(), a.nanos(), tickEnds, movements); add(created);
                    settleRates(match.wireOrdinal(), a.sequence());
                }
            }
            case TickCadenceEvent.TickRate r -> {
                RateState s = new RateState(r.rate(), r.frozen());
                RateState newest = pendingRates.isEmpty() ? settledRate : pendingRates.peekLast().state();
                if (!s.equals(newest)) rateChanges++;
                rateChangeSequence = r.sequence();
                if (pendingRates.size() >= MAX_PENDING_RATE_CHANGES) { pendingRates.removeFirst(); rateOverflow = true; }
                pendingRates.addLast(new PendingRate(s, r.sequence(), r.wireOrdinal()));
                signal = computeSignal();
            }
            case TickCadenceEvent.CoverageBreak b -> {
                breaks++; segment++; expired += anchors.size(); anchors.clear();
                // a lost span may have hidden ticking-state packets: nothing known about the client's rate survives it
                settledRate = null; settledRateSequence = 0; pendingRates.clear(); rateOverflow = false; signal = TickRateSignal.UNOBSERVED;
            }
        }
        publish();
        return Optional.ofNullable(created);
    }

    /** Every change written before the answered ping (wire order) was applied by the client's game thread before it wrote the pong. */
    private void settleRates(long pingWire, long answerSequence) {
        boolean settledAny = false;
        while (!pendingRates.isEmpty() && pendingRates.peekFirst().wireOrdinal() < pingWire) { settledRate = pendingRates.removeFirst().state(); settledAny = true; }
        if (settledAny) { settledRateSequence = answerSequence; if (pendingRates.isEmpty()) rateOverflow = false; signal = computeSignal(); }
    }
    private TickRateSignal computeSignal() {
        if (settledRate == null && pendingRates.isEmpty()) return TickRateSignal.UNOBSERVED;
        List<RateState> candidates = new ArrayList<>(pendingRates.size() + 1);
        if (settledRate != null) candidates.add(settledRate);
        for (PendingRate p : pendingRates) if (candidates.isEmpty() || !candidates.get(candidates.size() - 1).equals(p.state())) candidates.add(p.state());
        if (rateOverflow) return new TickRateSignal(TickRateSignal.Status.OVERFLOW, candidates, false, rateChangeSequence, settledRateSequence);
        if (pendingRates.isEmpty()) return new TickRateSignal(TickRateSignal.Status.SETTLED, candidates, true, rateChangeSequence, settledRateSequence);
        // unsettled changes: the previous state (when known) or any of the newer ones; an unknown previous state keeps the signal incomplete
        if (settledRate == null) return new TickRateSignal(TickRateSignal.Status.TRANSITION, candidates, false, rateChangeSequence, settledRateSequence);
        if (candidates.size() == 1) return new TickRateSignal(TickRateSignal.Status.SETTLED, candidates, true, rateChangeSequence, settledRateSequence); // a re-send of the settled state
        return new TickRateSignal(TickRateSignal.Status.TRANSITION, candidates, true, rateChangeSequence, settledRateSequence);
    }
    /** Owner-thread or any thread: the client tick-rate signal at the ledger's current position (immutable). */
    public TickRateSignal tickRateSignal() { return view.tickRate(); }

    private void add(Anchor a) {
        anchors.add(a);
        // the anchor that just left the dense tail keeps its place only if it is far enough from the retained anchor before it
        int leaving = anchors.size() - 1 - recentAnchors;
        if (leaving >= 1 && anchors.get(leaving).answerNanos() - anchors.get(leaving - 1).answerNanos() < sparseSpacingNanos) { anchors.remove(leaving); thinned++; }
        while (!anchors.isEmpty() && a.answerNanos() - anchors.get(0).pingNanos() > maxWindowNanos) { anchors.remove(0); expired++; }
        while (anchors.size() > maxAnchors) { anchors.remove(0); expired++; }
    }

    /** Owner-thread history: the retained anchors of the current segment, oldest first (unmodifiable live view; do not retain across applies). */
    public List<Anchor> anchors() { assertOwner(); return anchorsView; }
    public void close() { assertOwner(); closed = true; publish(); }
    /** Safe from any thread. */
    public View view() { return view; }
    public SessionKey key() { return key; }

    private void publish() {
        view = new View(key, closed, segment, lastSequence, lastNanos, pending.size(), anchors.size(), anchors.isEmpty() ? 0 : anchors.get(0).pingNanos(), anchors.isEmpty() ? 0 : anchors.get(anchors.size() - 1).answerNanos(),
            signal, new Counters(events, tickEnds, movements, induced, pingsSent, pingsAnswered, unknownAnswers, failedPings, evicted, thinned, expired, breaks, rateChanges, rejected));
    }
}
