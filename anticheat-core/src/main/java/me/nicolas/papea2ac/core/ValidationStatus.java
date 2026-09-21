package me.nicolas.papea2ac.core;
import java.util.List;
/**
 * Bounded, immutable projection of one session's live movement validation (Phase 8 brief 57): whether the chain is anchored and
 * how, the chain's counts, the last result (its description lines are the validator's own bounded vocabulary), the recent results
 * the results of a controlled injection, every recent notable result (INVALID, UNCERTAIN, VALID under an incomplete context) with its full
 * description (bounded ring, so that one never scrolls out unnoticed) and a one-line summary of the movement context the chain saw last. Primitives and strings only for platform display. No level, no score, no punishment.
 */
public record ValidationStatus(long sessionEpoch, boolean anchored, String anchorKind, long anchorSequence, boolean frontierComplete, int frontierNodes, int transitions, int valid, int uncertain, int invalid,
                               String lastClassification, long lastStartSequence, long lastEndSequence, List<String> lastResult, List<String> recent, List<String> injected, List<String> notable, String context, int injectionTicksPending) {
    public ValidationStatus { lastResult = List.copyOf(lastResult); recent = List.copyOf(recent); injected = List.copyOf(injected); notable = List.copyOf(notable); java.util.Objects.requireNonNull(context); }
}
