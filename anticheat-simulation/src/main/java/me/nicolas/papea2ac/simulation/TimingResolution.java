package me.nicolas.papea2ac.simulation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
/**
 * Immutable result of {@link TimingResolver#resolve}: a status, the bounded hypothesis list (never one guessed instant), whether
 * the enumeration is complete within the supported model, the fences/evidence used, work accounting and provenance.
 * {@code complete=false} means a legitimate account may lie outside the enumerated set (budget, horizon, degraded coverage);
 * it is never turned into certainty.
 */
public record TimingResolution(Status status, List<TimingHypothesis> hypotheses, boolean complete, Evidence evidence, Work work, Provenance provenance, List<String> notes) {
    /** Severity-ordered: the first that applies wins when several do. */
    public enum Status {
        SESSION_DISCONTINUITY, WORLD_DISCONTINUITY, TELEPORT_UNRESOLVED, UNSUPPORTED, UNSUPPORTED_HORIZON, SEARCH_LIMIT, TIMING_GAP, EXTERNAL_MOTION_UNRESOLVED, EXPIRED, NO_ACK_EVIDENCE, INSUFFICIENT_RTT,
        RESOLVED_AMBIGUOUS, RESOLVED_UNIQUE;
        public boolean resolved() { return this == RESOLVED_UNIQUE || this == RESOLVED_AMBIGUOUS; }
    }
    /** What the resolution rested on. */
    public record Evidence(int clientTickEnds, int rotationOnlyPackets, int markerAnswers, int confirmsConsulted, boolean tickEndCapable, boolean rttPresent, boolean rttStale, long rttLowNanos, long rttHighNanos, int influencesConsidered, int influencesExpired) {}
    public record Work(int alignmentsEnumerated, int applicationCombinations, int hypothesesEmitted, int hypothesesDeduplicated, int fencesConsulted) {}
    public record Provenance(long anchorSequence, long targetSequence, long worldEpoch, String timingProfile) {}
    public TimingResolution {
        Objects.requireNonNull(status); hypotheses = List.copyOf(hypotheses); Objects.requireNonNull(evidence); Objects.requireNonNull(work); Objects.requireNonNull(provenance); notes = List.copyOf(notes);
        if (status == Status.RESOLVED_UNIQUE && hypotheses.size() != 1) throw new IllegalArgumentException("RESOLVED_UNIQUE needs exactly one hypothesis");
        if (status == Status.RESOLVED_AMBIGUOUS && hypotheses.size() < 2) throw new IllegalArgumentException("RESOLVED_AMBIGUOUS needs several hypotheses");
    }
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("timing " + status + " complete=" + complete + " hypotheses=" + hypotheses.size() + " (dedup " + work.hypothesesDeduplicated() + ") tickEnds=" + evidence.clientTickEnds() + " rotOnly=" + evidence.rotationOnlyPackets()
            + " rtt=" + (evidence.rttPresent() ? evidence.rttLowNanos() / 1_000_000 + ".." + evidence.rttHighNanos() / 1_000_000 + "ms" + (evidence.rttStale() ? " (stale)" : "") : "none") + " influences=" + evidence.influencesConsidered() + " seq=" + provenance.anchorSequence() + "->" + provenance.targetSequence());
        for (TimingHypothesis h : hypotheses) out.add("  " + h.describe());
        for (String n : notes) out.add("  note: " + n);
        return out;
    }
}
