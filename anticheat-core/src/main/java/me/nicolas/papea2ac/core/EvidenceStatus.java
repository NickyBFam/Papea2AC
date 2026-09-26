package me.nicolas.papea2ac.core;
import java.util.List;
import java.util.Objects;
/**
 * Bounded, immutable projection of one session's Phase 9 evidence for platform display (primitives and strings only, like the other
 * status records): the live snapshot's headline numbers and description lines, the side-chain (qualification) snapshot lines and bounded
 * recent ingest lines. No level, no recommendation, no consequence.
 */
public record EvidenceStatus(long sessionEpoch, long worldEpoch, int activeEvents, int totalEvents, int currentRun, int longestRun, long validObserved, long uncertainObserved, long invalidObserved, long proofRejected, long duplicates,
                             List<String> live, List<String> side, List<String> recent, List<String> sideRecent) {
    public EvidenceStatus { live = List.copyOf(live); side = List.copyOf(side); recent = List.copyOf(recent); sideRecent = List.copyOf(sideRecent); Objects.requireNonNull(live); }
}
