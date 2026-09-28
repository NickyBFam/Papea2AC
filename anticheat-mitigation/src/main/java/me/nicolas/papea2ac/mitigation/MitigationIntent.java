package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.ActionType;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.model.SessionKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
/**
 * The immutable, conditional, expiring, idempotent mitigation intent (MITIGATION.md G1: "Intent includes unique action ID, session and all
 * epochs, transport health generation, source evidence/decision IDs, durable replay receipt, proposed safe anchor, permissible current-state
 * revision/range, world identity, deadline in monotonic/server observations, reason, policy version and action type"). A worker emits it;
 * only the Paper main-thread executor applies it, after re-checking every precondition. It is never a punishment.
 *
 * <p>{@link #actionId} is a SHA-256 digest of the policy version, session, world epoch, the Phase 9 group ids that met the policy, the chain
 * (live or side) and the anchor's sequence: the same evidence and anchor always produce the same id, so re-processing never schedules a
 * second identical action (eight predecessor ids are retained to recognise it).
 */
public record MitigationIntent(String actionId, ActionType actionType, SessionKey session, long worldEpoch, String worldKey, long transportGeneration,
                               List<String> sourceGroupIds, List<String> sourceEventIds, Category family, String policyVersion, boolean sideChain,
                               SafeAnchor anchor, long decisionSequence, long decisionWorldRevision, long proposedNanos, long deadlineNanos, String durableReceipt) {
    public MitigationIntent {
        Objects.requireNonNull(actionId); Objects.requireNonNull(actionType); Objects.requireNonNull(session); Objects.requireNonNull(worldKey); Objects.requireNonNull(family); Objects.requireNonNull(policyVersion); Objects.requireNonNull(anchor); Objects.requireNonNull(durableReceipt);
        sourceGroupIds = List.copyOf(sourceGroupIds); sourceEventIds = List.copyOf(sourceEventIds);
        if (actionId.length() != 64 || deadlineNanos <= proposedNanos || !anchor.session().equals(session) || anchor.worldEpoch() != worldEpoch) throw new IllegalArgumentException("intent");
    }
    /** The 16-hex short id carried by ordered control records (capsule-safe length). */
    public String shortId() { return actionId.substring(0, 16); }
    public MitigationIntent withReceipt(String receipt) { return new MitigationIntent(actionId, actionType, session, worldEpoch, worldKey, transportGeneration, sourceGroupIds, sourceEventIds, family, policyVersion, sideChain, anchor, decisionSequence, decisionWorldRevision, proposedNanos, deadlineNanos, receipt); }

    public static String actionId(String policyVersion, SessionKey session, long worldEpoch, List<String> groupIds, boolean sideChain, long anchorSequence) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(policyVersion).append('|').append(session.player()).append('|').append(session.epoch()).append('|').append(worldEpoch).append('|').append(sideChain ? "side" : "live").append('|').append(anchorSequence);
            for (String g : groupIds) sb.append('|').append(g);
            return HexFormat.of().formatHex(d.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
