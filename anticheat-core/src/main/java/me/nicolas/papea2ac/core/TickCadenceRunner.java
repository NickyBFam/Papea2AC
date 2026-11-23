package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TickCadenceEvent;
import me.nicolas.papea2ac.player.TickCadenceLedger;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.simulation.TickCadenceResult;
import me.nicolas.papea2ac.simulation.TickCadenceValidator;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
/**
 * Phase 14 cadence for ONE session, owned by the session's shard thread (THREADING_MODEL T1) like the timing ledger it sits beside. Every
 * observation is mapped once ({@link TickCadenceObservationMapper}) into the session's {@link TickCadenceLedger}; every answered ping becomes an
 * anchor and is classified at once by the pure {@link TickCadenceValidator} against the retained anchors. The runner keeps counts, the largest
 * excess, the number of distinct exclusion runs and a bounded ring of lines, and publishes an immutable {@link TickCadenceStatus}. It draws no
 * consequence: no evidence, no mitigation (Phase 14 preparation; the evidence projection is a separate, reviewed integration step).
 * Failures are contained: an exception is counted and never reaches the caller.
 */
public final class TickCadenceRunner {
    public static final int RECENT = 16, WINDOWS = 32;
    /** Engine-wide bounded counters (fixed labels, no UUIDs). */
    public static final class Metrics {
        public final LongAdder anchors = new LongAdder(), valid = new LongAdder(), uncertain = new LongAdder(), invalid = new LongAdder(), exclusionRuns = new LongAdder(), unknownAnswers = new LongAdder(),
            internalErrors = new LongAdder(), sessionsCreated = new LongAdder();
    }
    private final SessionKey key; private final TickCadenceProfile profile; private final boolean profileSupported; private final Metrics metrics;
    private final TickCadenceLedger ledger; private final TickCadenceValidator validator = new TickCadenceValidator();
    private final ArrayDeque<String> recent = new ArrayDeque<>(), windows = new ArrayDeque<>();
    private long valid, uncertain, invalid, runs, largestExcess = Long.MIN_VALUE, beyond;
    private String largestWindow = "-", last = "-";
    private boolean inRun;
    private TickCadenceResult lastResult;
    private volatile TickCadenceStatus status;

    public TickCadenceRunner(SessionKey key, TickCadenceProfile profile, boolean profileSupported, Metrics metrics) {
        this.key = Objects.requireNonNull(key); this.profile = Objects.requireNonNull(profile); this.profileSupported = profileSupported; this.metrics = Objects.requireNonNull(metrics);
        ledger = new TickCadenceLedger(key, profile.maxWindowNanos()); metrics.sessionsCreated.increment(); publish();
    }
    public TickCadenceStatus status() { return status; }
    public Optional<TickCadenceResult> lastResult() { return Optional.ofNullable(lastResult); }
    /** Thread-safe: the client tick-rate signal at the last applied observation (Phase 14 contract for Phase 15). */
    public TickCadenceLedger.TickRateSignal tickRateSignal() { return ledger.tickRateSignal(); }
    TickCadenceLedger ledger() { return ledger; }

    /** Shard thread: consumes one observation in capture order. Never throws. */
    public void onObservation(Observation o) {
        try {
            if (!o.session().equals(key)) return;
            List<TickCadenceEvent> events = TickCadenceObservationMapper.map(o);
            for (TickCadenceEvent e : events) apply(e);
        } catch (RuntimeException ex) { metrics.internalErrors.increment(); remember("seq=" + o.sequence() + " cadence INTERNAL_ERROR " + ex.getClass().getSimpleName()); publish(); }
    }
    /** Shard thread: a loss-class transport coverage break latched at {@code sequence}; no window crosses it. */
    public void onCoverageBreak(long sequence, long nanos, String reason) {
        try { apply(new TickCadenceEvent.CoverageBreak(sequence, nanos, reason)); inRun = false; remember("seq=" + sequence + " coverage break (" + reason + "): new segment, no window crosses it"); publish(); }
        catch (RuntimeException ex) { metrics.internalErrors.increment(); }
    }
    public void close() { ledger.close(); publish(); }

    private void apply(TickCadenceEvent e) {
        long unknownBefore = ledger.view().counters().unknownAnswers();
        Optional<TickCadenceLedger.Anchor> anchor = ledger.apply(key, e);
        if (ledger.view().counters().unknownAnswers() > unknownBefore) metrics.unknownAnswers.increment();
        if (anchor.isEmpty()) { if (!(e instanceof TickCadenceEvent.TickEnd) && !(e instanceof TickCadenceEvent.Movement)) publish(); return; }
        metrics.anchors.increment();
        TickCadenceResult r = validator.evaluate(key, ledger.anchors(), profile, profileSupported);
        lastResult = r; last = r.describe(); beyond = Math.max(beyond, r.movementBeyondTickEnds());
        windows.addLast(last); while (windows.size() > WINDOWS) windows.removeFirst();
        switch (r.classification()) {
            case VALID -> { valid++; metrics.valid.increment(); inRun = false; }
            case UNCERTAIN -> { uncertain++; metrics.uncertain.increment(); }
            case INVALID -> {
                invalid++; metrics.invalid.increment();
                if (!inRun) { inRun = true; runs++; metrics.exclusionRuns.increment(); remember(r.describe()); }
            }
        }
        if (r.worst() != null && r.worst().excess() > largestExcess) { largestExcess = r.worst().excess(); largestWindow = r.worst().describe(); }
        if (r.classification() == TickCadenceResult.Classification.UNCERTAIN && uncertain <= 2) remember(r.describe());
        publish();
    }
    static String describe(TickCadenceLedger.TickRateSignal s) {
        StringBuilder sb = new StringBuilder(48).append(s.status());
        for (TickCadenceLedger.RateState r : s.candidates()) sb.append(' ').append(r.rate()).append(r.frozen() ? "/frozen" : "");
        return sb.toString();
    }
    private void remember(String line) { recent.addLast(line); while (recent.size() > RECENT) recent.removeFirst(); }
    private void publish() {
        TickCadenceLedger.View v = ledger.view(); TickCadenceLedger.Counters c = v.counters();
        status = new TickCadenceStatus(key.epoch(), v.segment(), c.tickEnds(), c.movements(), c.induced(), c.pingsSent(), c.pingsAnswered(), c.unknownAnswers(), c.failedPings(), v.pendingPings(), v.anchors(),
            c.anchorsThinned(), c.anchorsExpired(), c.coverageBreaks(), describe(v.tickRate()), c.tickRateChanges(), valid, uncertain, invalid, runs, largestExcess == Long.MIN_VALUE ? 0 : largestExcess, largestWindow, beyond, last, List.copyOf(recent), List.copyOf(windows));
    }
}
