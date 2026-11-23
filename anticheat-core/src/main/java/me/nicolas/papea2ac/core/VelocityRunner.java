package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.simulation.KnockbackAttribution;
import me.nicolas.papea2ac.simulation.MovementValidationResult;
import me.nicolas.papea2ac.simulation.TimingRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
/**
 * Phase 13 per-session knockback bookkeeping, owned by the session's shard thread like the validation runner that feeds it. For every
 * completed live transition it records {@link KnockbackAttribution} per influence in scope: the latest outcome per influence (an influence
 * settles at APPLIED or EXCLUDED; PENDING / UNRESOLVED may still change) in a bounded ring, fixed-label engine metrics and a published
 * immutable status. It judges nothing beyond what the transition's classification already says, feeds no evidence (the movement result
 * already did, as the one causal family) and enforces nothing.
 */
public final class VelocityRunner {
    public static final int MAX_INFLUENCES = 64, MAX_LINES = 48;
    /** Engine-wide bounded counters (fixed labels, no UUIDs). */
    public static final class Metrics {
        public final LongAdder influences = new LongAdder(), applied = new LongAdder(), excluded = new LongAdder(), fencedExcluded = new LongAdder(), unresolved = new LongAdder(), pendingObservations = new LongAdder();
    }
    /** Published per-session view: the bounded per-influence lines and counts. */
    public record Status(long sessionEpoch, int tracked, long applied, long excluded, long fencedExcluded, long unresolved, List<String> influences, List<String> recent) {
        public Status { influences = List.copyOf(influences); recent = List.copyOf(recent); }
    }
    private final SessionKey key; private final Metrics metrics;
    private final LinkedHashMap<String, KnockbackAttribution.Attribution> latest = new LinkedHashMap<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private long applied, excluded, fencedExcluded, unresolved;
    private Thread owner;
    private volatile Status status;

    public VelocityRunner(SessionKey key, Metrics metrics) { this.key = Objects.requireNonNull(key); this.metrics = Objects.requireNonNull(metrics); publish(); }
    private void assertOwner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("velocity runner ownership"); }
    public Status status() { return status; }

    /** One completed live transition and the request it answered. Never throws into the validation runner. */
    public void onResult(TimingRequest req, MovementValidationResult r) {
        assertOwner();
        try {
            for (KnockbackAttribution.Attribution a : KnockbackAttribution.attribute(req, r)) {
                KnockbackAttribution.Attribution before = latest.get(a.influenceId());
                if (before == null) { metrics.influences.increment(); if (latest.size() >= MAX_INFLUENCES) latest.remove(latest.keySet().iterator().next()); }
                else if (before.outcome() == KnockbackAttribution.Outcome.APPLIED || before.outcome() == KnockbackAttribution.Outcome.EXCLUDED) continue; // settled: later transitions do not rewrite it
                latest.put(a.influenceId(), a);
                switch (a.outcome()) {
                    case APPLIED -> { applied++; metrics.applied.increment(); line(a); }
                    case EXCLUDED -> { excluded++; metrics.excluded.increment(); if (a.reasons().contains(KnockbackAttribution.Reason.FENCED_APPLICATION_EXCLUDED)) { fencedExcluded++; metrics.fencedExcluded.increment(); } line(a); }
                    case UNRESOLVED -> { if (before == null || before.outcome() != KnockbackAttribution.Outcome.UNRESOLVED) { unresolved++; metrics.unresolved.increment(); line(a); } }
                    case PENDING -> metrics.pendingObservations.increment();
                }
            }
        } catch (RuntimeException ex) {
            line("attribution failed: " + ex.getClass().getSimpleName());
        }
        publish();
    }
    /** A world change or a new session epoch: influences of the old world cannot be attributed any more. */
    public void reset(String why) { assertOwner(); latest.clear(); line("reset (" + why + ")"); publish(); }
    private void line(KnockbackAttribution.Attribution a) { line(a.describe()); }
    private void line(String s) { recent.addLast(s.length() > 240 ? s.substring(0, 240) : s); while (recent.size() > MAX_LINES) recent.removeFirst(); }
    private void publish() {
        List<String> inf = new ArrayList<>(latest.size()); for (Map.Entry<String, KnockbackAttribution.Attribution> e : latest.entrySet()) inf.add(e.getValue().describe());
        status = new Status(key.epoch(), latest.size(), applied, excluded, fencedExcluded, unresolved, inf, new ArrayList<>(recent));
    }
}
