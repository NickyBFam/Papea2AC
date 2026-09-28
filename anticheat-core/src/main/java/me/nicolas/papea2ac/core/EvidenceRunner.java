package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.evidence.EvidenceSnapshot;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.simulation.MovementValidationResult;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
/**
 * Phase 9 evidence for ONE session, owned by the session's shard thread like the validation runner that feeds it (THREADING_MODEL T1).
 * Every completed live Phase 8 result is projected once ({@link ValidationEvidenceMapper}) and ingested into the LIVE ledger; results of the
 * controlled side-chain injection go to a separate SIDE ledger (the qualification instrument: it demonstrates event creation and
 * idempotency without ever touching the live evidence). Failures are contained: an exception in the projection or the ledger is counted
 * and never becomes evidence, never reaches the validation path. {@link #status()} publishes an immutable projection (the live snapshot,
 * the side snapshot and bounded recent lines); no consequence of any kind is drawn here (Phase 10 owns interpretation).
 */
public final class EvidenceRunner {
    public static final int RECENT = 16;
    /** Engine-wide bounded counters (fixed labels, no UUIDs). */
    public static final class Metrics {
        public final LongAdder consumed = new LongAdder(), legitimateWitness = new LongAdder(), unresolved = new LongAdder(), positiveEvents = new LongAdder(), duplicates = new LongAdder(), proofRejected = new LongAdder(), outOfOrder = new LongAdder(),
            sessionMismatch = new LongAdder(), internalErrors = new LongAdder(), sideConsumed = new LongAdder(), sidePositiveEvents = new LongAdder(), sideDuplicates = new LongAdder(), sessionsCreated = new LongAdder();
    }
    private final SessionKey key; private final Metrics metrics; private final EvidenceLedger live, side;
    private final ArrayDeque<String> recent = new ArrayDeque<>(), sideRecent = new ArrayDeque<>();
    private ValidationRecord lastSideRecord;
    private volatile EvidenceStatus status;

    public EvidenceRunner(SessionKey key, Metrics metrics) { this.key = Objects.requireNonNull(key); this.metrics = Objects.requireNonNull(metrics); live = new EvidenceLedger(key); side = new EvidenceLedger(key); metrics.sessionsCreated.increment(); publish(); }
    public EvidenceStatus status() { return status; }
    EvidenceLedger liveLedger() { return live; }

    /** Consumes one completed Phase 8 result (live chain, or the side chain when {@code sideChain}). Never throws. */
    public EvidenceLedger.Outcome onResult(MovementValidationResult r, long nanos, boolean sideChain) {
        try {
            ValidationRecord rec = ValidationEvidenceMapper.record(key, r, nanos);
            return ingest(rec, sideChain);
        } catch (RuntimeException ex) {
            metrics.internalErrors.increment();
            remember(sideChain ? sideRecent : recent, "seq=" + r.endSequence() + " evidence INTERNAL_ERROR " + ex.getClass().getSimpleName() + " (no evidence created)");
            publish(); return null;
        }
    }
    private EvidenceLedger.Outcome ingest(ValidationRecord rec, boolean sideChain) {
        EvidenceLedger ledger = sideChain ? side : live;
        EvidenceLedger.Outcome out = ledger.ingest(rec);
        if (sideChain) { lastSideRecord = rec; metrics.sideConsumed.increment(); if (out == EvidenceLedger.Outcome.POSITIVE_EVENT) metrics.sidePositiveEvents.increment(); if (out == EvidenceLedger.Outcome.DUPLICATE) metrics.sideDuplicates.increment(); }
        else {
            metrics.consumed.increment();
            switch (out) {
                case LEGITIMATE_WITNESS -> metrics.legitimateWitness.increment();
                case UNRESOLVED -> metrics.unresolved.increment();
                case POSITIVE_EVENT -> metrics.positiveEvents.increment();
                case DUPLICATE -> metrics.duplicates.increment();
                case PROOF_REJECTED -> metrics.proofRejected.increment();
                case OUT_OF_ORDER -> metrics.outOfOrder.increment();
                case SESSION_MISMATCH -> metrics.sessionMismatch.increment();
            }
        }
        String line = "seq=" + rec.startSequence() + "->" + rec.endSequence() + " " + rec.classification() + " -> " + out + (out == EvidenceLedger.Outcome.POSITIVE_EVENT ? " " + ledger.lastEvent().map(EvidenceEvent::describe).orElse("") : out == EvidenceLedger.Outcome.PROOF_REJECTED ? " " + ledger.lastRejection().map(Enum::name).orElse("") : out == EvidenceLedger.Outcome.UNRESOLVED ? " " + rec.uncertainReasons() : "");
        remember(sideChain ? sideRecent : recent, line);
        publish(); return out;
    }
    /** Qualification only (platform thread): asks the shard to re-deliver the last side-chain record to the side ledger at the next tick end (must be a DUPLICATE, never a second event). */
    public String requestReingestLastSide() { if (lastSideRecord == null) return "REFUSED: no side-chain result to re-deliver yet"; reingestRequested = true; return "SCHEDULED: the last side-chain record (seq=" + lastSideRecord.startSequence() + "->" + lastSideRecord.endSequence() + ") is re-delivered to the side ledger at the next tick end; expect DUPLICATE"; }
    private volatile boolean reingestRequested;
    /** Shard thread, at a tick end: performs a requested re-delivery and ages the published snapshot to {@code nanos} (expiry is deterministic from the records' own monotonic times). */
    public void advance(long nanos) {
        try {
            if (reingestRequested && lastSideRecord != null) { reingestRequested = false; EvidenceLedger.Outcome out = ingest(lastSideRecord, true); remember(sideRecent, "re-delivery of seq=" + lastSideRecord.startSequence() + "->" + lastSideRecord.endSequence() + " -> " + out); }
            status = status(live.snapshot(nanos), side.snapshot(nanos));
        } catch (RuntimeException ex) { metrics.internalErrors.increment(); }
    }
    private void remember(ArrayDeque<String> ring, String line) { ring.addLast(line); while (ring.size() > RECENT) ring.removeFirst(); }
    private void publish() { status = status(live.snapshot(), side.snapshot()); }
    private EvidenceStatus status(EvidenceSnapshot l, EvidenceSnapshot s) { return new EvidenceStatus(key.epoch(), l.worldEpoch(), l.activeEvents(), l.totalEvents(), l.currentRun(), l.longestRun(), l.validObserved(), l.uncertainObserved(), l.invalidObserved(), l.proofRejected(), l.duplicates(), l.describe(), s.describe(), List.copyOf(recent), List.copyOf(sideRecent)); }
    public EvidenceSnapshot liveSnapshot() { return live.snapshot(); }
    /** Phase 10: the snapshot of one chain right after an ingest and that chain's newest event (the mitigation policy input; owner shard). */
    public EvidenceSnapshot snapshot(boolean sideChain) { return sideChain ? side.snapshot() : live.snapshot(); }
    public java.util.Optional<EvidenceEvent> lastEvent(boolean sideChain) { return sideChain ? side.lastEvent() : live.lastEvent(); }
}
