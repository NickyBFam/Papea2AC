package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
/**
 * Phase 7 per-session timing ledger (player-owned mutable ledger, ARCHITECTURE.md A3 "mutable ledgers in player"): the ordered
 * timing facts of one session - movement transmissions, client tick ends, markers and their answers, write outcomes, position
 * corrections and confirmations, external-motion influences, server ticks, discontinuities - plus the bounded RTT estimator and
 * marker ledger. It never decides application: it records, bounds and publishes; the simulation module's TimingResolver turns a
 * slice of these events into bounded hypotheses.
 *
 * <p>Ownership mirrors PlayerTimeline: {@link #apply} and the history readers are owner-thread-only (the session's shard);
 * {@link #view()} is safe from any thread and reads the last published immutable {@link View}. The published view carries the
 * counters always and the diagnostic event capture only while {@link #publishEvents(boolean)} is on (a diagnostic/qualification
 * concern: copying that list on every packet is not a production cost). Bounds: the event ring (2048 records, 256 KiB,
 * the timeline's three-second horizon), the diagnostic capture ({@value #PUBLISHED_EVENTS} events, only while armed),
 * {@value #MAX_PENDING_TELEPORTS} pending corrections, MarkerLedger's caps, one RttEstimator window. A reconnect gets a brand-new ledger from a brand-new SessionKey: nothing here survives a session.
 */
public final class TimingLedger {
    public static final int MAX_PENDING_TELEPORTS = 8, PUBLISHED_EVENTS = 4096, RING_COUNT = 2048, RING_BYTES = 256 * 1024;
    public static final long HORIZON_NANOS = PlayerTimeline.HORIZON_NANOS;

    /** A correction the client has not yet confirmed (or confirmed: {@code confirmedSequence} > 0). Superseded = a later correction was confirmed first. */
    public record PendingTeleport(TimingEvent.TeleportSent sent, long confirmedSequence, long confirmedNanos, boolean superseded, boolean writeFailed) {
        public boolean confirmed() { return confirmedSequence > 0; }
        public long teleportId() { return sent.teleportId(); }
    }
    public record Counters(long events, long movements, long clientTickEnds, long markersSent, long markersAcknowledged, long staleTeleportConfirms, long teleportsSent, long teleportsConfirmed,
                           long externalMotions, long failedWrites, long discontinuities, long coverageBreaks, long evictions) {
        public static final Counters ZERO = new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    public record View(SessionKey key, long lastSequence, long lastNanos, long worldEpoch, boolean closed, boolean tickEndCapable, long lastClientTickEndSequence,
                       RttEstimator.Summary rtt, MarkerLedger.Summary markers, Counters counters, List<PendingTeleport> teleports, List<TimingEvent> events, boolean eventsPublished) {
        public View { teleports = List.copyOf(teleports); events = List.copyOf(events); }
    }

    private final SessionKey key;
    private final ObservationRing<TimingEvent> ring = new ObservationRing<>(RING_COUNT, RING_BYTES, HORIZON_NANOS, TimingEvent::chargedBytes);
    private final RttEstimator rtt = new RttEstimator();
    private final MarkerLedger markers = new MarkerLedger();
    private final ArrayList<PendingTeleport> teleports = new ArrayList<>();
    private Thread owner;
    private long lastSequence, lastNanos, worldEpoch, lastClientTickEndSequence;
    private long events, movements, clientTickEnds, markersSent, markersAcknowledged, staleTeleportConfirms, teleportsSent, teleportsConfirmed, externalMotions, failedWrites, discontinuities, coverageBreaks;
    private boolean closed;
    private volatile boolean publishEvents;
    /** Diagnostic capture (P7-F5): while publishing, every non-tick event since the switch was turned on is retained here, independent of the ring's
     *  three-second horizon, up to {@value #PUBLISHED_EVENTS} (a published list of that size means capped); a human-paced qualification is far longer than the ring. Owner-thread only;
     *  the switch itself is a generation the owner observes on its next apply, so the on/off caller never touches the list. */
    private final ArrayList<TimingEvent> diagnostic = new ArrayList<>();
    private volatile int publishGeneration;
    private int seenGeneration;
    private volatile View view;

    public TimingLedger(SessionKey key) { this.key = Objects.requireNonNull(key); publish(); }

    private void assertOwner() {
        if (owner == null) owner = Thread.currentThread();
        else if (owner != Thread.currentThread()) throw new IllegalStateException("timing ledger ownership");
    }

    /** Applies one event in capture order; a stale-session, closed, or non-monotonic event is a no-op (returns false), never a partial mutation. */
    public boolean apply(SessionKey observedFor, TimingEvent event) {
        assertOwner();
        Objects.requireNonNull(event);
        if (!key.equals(observedFor) || closed) return false;
        if (event.sequence() <= lastSequence) return false;
        // An outbound write outcome is timing evidence only for a marker or a correction this ledger is tracking; every other write (entity updates,
        // chunks, sounds: hundreds per second on a real session) is not an event here - it churned the ring and filled the diagnostic capture in
        // seconds (P7-F6, real-client qualification).
        if (event instanceof TimingEvent.WriteResolved w && !markers.tracksWire(w.wireOrdinal()) && !tracksTeleportWire(w.wireOrdinal())) return true;
        lastSequence = event.sequence(); lastNanos = event.nanos(); events++;
        switch (event) {
            case TimingEvent.MovementObserved m -> movements++;
            case TimingEvent.ClientTickEnd t -> { clientTickEnds++; lastClientTickEndSequence = t.sequence(); }
            case TimingEvent.MarkerSent s -> { markers.sent(s.kind(), s.id(), s.sequence(), s.nanos(), s.wireOrdinal()); markersSent++; }
            case TimingEvent.MarkerAcknowledged a -> {
                MarkerLedger.Answer answer = markers.acknowledged(a.kind(), a.id(), a.nanos());
                if (answer.outcome() == MarkerLedger.Answer.Outcome.RESOLVED || answer.outcome() == MarkerLedger.Answer.Outcome.RESOLVED_WRITE_UNKNOWN) {
                    markersAcknowledged++;
                    answer.rttNanos().ifPresent(sample -> rtt.accept(sample, a.nanos()));
                }
            }
            case TimingEvent.WriteResolved w -> {
                markers.writeResolved(w.wireOrdinal(), w.success());
                if (!w.success()) { failedWrites++; for (int i = 0; i < teleports.size(); i++) if (teleports.get(i).sent().wireOrdinal() == w.wireOrdinal()) teleports.set(i, new PendingTeleport(teleports.get(i).sent(), 0, 0, false, true)); }
            }
            case TimingEvent.TeleportSent t -> {
                teleportsSent++;
                if (teleports.size() >= MAX_PENDING_TELEPORTS) teleports.remove(0);
                teleports.add(new PendingTeleport(t, 0, 0, false, false));
            }
            case TimingEvent.TeleportConfirmed c -> {
                int index = -1;
                for (int i = 0; i < teleports.size(); i++) if (!teleports.get(i).confirmed() && !teleports.get(i).superseded() && teleports.get(i).teleportId() == c.teleportId()) { index = i; break; } // a superseded correction's confirm is stale: the client confirmed a later one first
                if (index < 0) staleTeleportConfirms++;
                else {
                    teleportsConfirmed++;
                    PendingTeleport p = teleports.get(index);
                    teleports.set(index, new PendingTeleport(p.sent(), c.sequence(), c.nanos(), false, p.writeFailed()));
                    // an older, still unconfirmed correction can no longer be confirmed in order: superseded (the client never applied it, or its confirm was lost with a failed write)
                    for (int i = 0; i < index; i++) { PendingTeleport o = teleports.get(i); if (!o.confirmed() && !o.superseded()) teleports.set(i, new PendingTeleport(o.sent(), 0, 0, true, o.writeFailed())); }
                    // confirmed corrections stay listed (a resolver looking at an earlier interval needs them); the list is bounded by MAX_PENDING_TELEPORTS on insertion
                }
            }
            case TimingEvent.ExternalMotionSent e -> externalMotions++;
            case TimingEvent.ServerTick t -> { }
            case TimingEvent.Discontinuity d -> { discontinuities++; worldEpoch = d.newWorldEpoch(); }
            case TimingEvent.CoverageBreak b -> coverageBreaks++;
        }
        ring.append(event, event.nanos());
        markers.expire(event.nanos());
        if (seenGeneration != publishGeneration) { seenGeneration = publishGeneration; diagnostic.clear(); }
        if (publishEvents && !(event instanceof TimingEvent.ServerTick) && diagnostic.size() < PUBLISHED_EVENTS) diagnostic.add(event);
        publish();
        return true;
    }
    private boolean tracksTeleportWire(long wireOrdinal) { for (PendingTeleport t : teleports) if (t.sent().wireOrdinal() == wireOrdinal) return true; return false; }
    /** Ages out history and pending markers by monotonic time even without new events. */
    public void expire(long nowNanos) { assertOwner(); ring.expire(nowNanos); markers.expire(nowNanos); publish(); }
    public void close() { assertOwner(); closed = true; publish(); }
    /** Diagnostic/qualification switch: while on, every published view carries the recent event list (bounded by PUBLISHED_EVENTS). */
    public void publishEvents(boolean on) { publishEvents = on; publishGeneration++; }
    /** Owner-thread history: every retained event, oldest first (bounded by the ring). */
    public List<TimingEvent> events() { assertOwner(); return ring.snapshot(); }
    /** Owner-thread history: the retained events with sequence in [from, to], oldest first. */
    public List<TimingEvent> events(long from, long to) {
        assertOwner(); List<TimingEvent> all = ring.snapshot(); List<TimingEvent> out = new ArrayList<>();
        for (TimingEvent e : all) if (e.sequence() >= from && e.sequence() <= to) out.add(e);
        return out;
    }
    public RttEstimator.Summary rtt() { return view.rtt(); }
    /** Safe from any thread. */
    public View view() { return view; }
    public SessionKey key() { return key; }

    private void publish() {
        List<TimingEvent> published = List.of();
        if (publishEvents) published = diagnostic;
        view = new View(key, lastSequence, lastNanos, worldEpoch, closed, clientTickEnds > 0, lastClientTickEndSequence, rtt.summary(), markers.summary(),
            new Counters(events, movements, clientTickEnds, markersSent, markersAcknowledged, staleTeleportConfirms, teleportsSent, teleportsConfirmed, externalMotions, failedWrites, discontinuities, coverageBreaks, ring.evictions()),
            teleports, published, publishEvents);
    }
}
