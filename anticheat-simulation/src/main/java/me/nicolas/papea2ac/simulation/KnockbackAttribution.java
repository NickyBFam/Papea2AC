package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * Phase 13 knockback attribution: what one Phase 8 transition says about each external-motion influence (velocity SET, explosion ADD) in
 * its scope. It derives nothing new and re-runs no physics or timing: it reads the classification, the continuation frontier and the
 * application fence the request carried (ROADMAP Phase 13: "shared simulation consumption ... no duplicate motion formula").
 *
 * <ul>
 * <li>{@code APPLIED} - the transition is VALID and no continuation keeps the influence pending: every legal account applied it.</li>
 * <li>{@code PENDING} - VALID, and some continuation still has it unapplied (no fence yet, or the fence lies after the target).</li>
 * <li>{@code EXCLUDED} - INVALID: the complete union of every application alternative (each tick, and "not yet" where no fence forbids
 *     it) has no legal input. With a fence this is the anti-knockback outcome (the client answered the fence but moved as if the
 *     influence never arrived, or arrived altered); without one it means no alternative fits (e.g. an altered vector). It is the SAME
 *     causal family as the movement INVALID it annotates (EVIDENCE_MODEL: movement and velocity mismatches from one simulation are one
 *     family), never a second strike.</li>
 * <li>{@code UNRESOLVED} - UNCERTAIN, or the influence expired: named reasons, zero weight.</li>
 * </ul>
 */
public final class KnockbackAttribution {
    private KnockbackAttribution() {}
    public enum Outcome { APPLIED, PENDING, EXCLUDED, UNRESOLVED }
    public enum Reason { FENCED_APPLICATION_EXCLUDED, ALL_APPLICATION_ALTERNATIVES_EXCLUDED, NO_FENCE_YET, FENCE_NOT_CLAIMED_BY_PROFILE, EXPIRED, UNCERTAIN_TRANSITION }
    /** The fence that bounded the influence in this transition (the answered marker), or absent. */
    public record Fence(long markerSequence, long markerWireOrdinal, long answerSequence, long answerNanos) {}
    public record Attribution(String influenceId, String source, TimingEvent.Operation operation, Vector3 vector, long sentSequence, long sentNanos, Outcome outcome, List<Reason> reasons,
                              Fence fence, boolean fenceClaimed, MovementValidation.Classification classification, long resultStart, long resultEnd) {
        public Attribution { Objects.requireNonNull(influenceId); Objects.requireNonNull(outcome); reasons = List.copyOf(reasons); Objects.requireNonNull(classification); }
        public String describe() {
            return influenceId + " " + source + " " + operation + String.format(java.util.Locale.ROOT, " (%.4f,%.4f,%.4f)", vector.x(), vector.y(), vector.z()) + " sent@" + sentSequence + " -> " + outcome + " " + reasons
                + (fence == null ? "" : " fence marker@" + fence.markerSequence() + " answered@" + fence.answerSequence()) + (fenceClaimed ? "" : " (fence not claimed)") + " in " + classification + " " + resultStart + "->" + resultEnd;
        }
    }

    /** The influences in scope of {@code req} (carried pending + sent inside the interval, by id), each attributed from {@code r}. */
    public static List<Attribution> attribute(TimingRequest req, MovementValidationResult r) {
        Objects.requireNonNull(req); Objects.requireNonNull(r);
        Map<String, TimingEvent.ExternalMotionSent> scope = new LinkedHashMap<>();
        for (TimingEvent.ExternalMotionSent e : req.pendingInfluences()) scope.putIfAbsent(e.id(), e);
        for (TimingEvent e : req.events()) if (e instanceof TimingEvent.ExternalMotionSent s && s.sequence() < req.target().sequence()) scope.putIfAbsent(s.id(), s);
        boolean claimed = req.profile().pongBoundsApplication();
        List<Attribution> out = new ArrayList<>(scope.size());
        for (TimingEvent.ExternalMotionSent e : scope.values()) {
            TimingLedger.AnsweredMarker m = TimingResolver.applicationFence(e, req);
            Fence fence = m == null ? null : new Fence(m.markerSequence(), m.markerWireOrdinal(), m.answerSequence(), m.answerNanos());
            boolean expired = req.nowNanos() - e.nanos() > req.profile().applicationHorizonNanos();
            Outcome outcome; List<Reason> reasons = new ArrayList<>();
            if (expired) { outcome = Outcome.UNRESOLVED; reasons.add(Reason.EXPIRED); }
            else switch (r.classification()) {
                case VALID -> {
                    boolean pending = false; for (TimingReconstruction.Node n : r.next().nodes()) if (n.pendingInfluences().contains(e.id())) { pending = true; break; }
                    outcome = pending ? Outcome.PENDING : Outcome.APPLIED;
                    if (pending) reasons.add(fence == null ? Reason.NO_FENCE_YET : claimed ? Reason.NO_FENCE_YET : Reason.FENCE_NOT_CLAIMED_BY_PROFILE);
                }
                case INVALID -> { outcome = Outcome.EXCLUDED; reasons.add(fence != null && claimed ? Reason.FENCED_APPLICATION_EXCLUDED : Reason.ALL_APPLICATION_ALTERNATIVES_EXCLUDED); }
                default -> { outcome = Outcome.UNRESOLVED; reasons.add(Reason.UNCERTAIN_TRANSITION); }
            }
            out.add(new Attribution(e.id(), e.source(), e.operation(), e.vector(), e.sequence(), e.nanos(), outcome, reasons, fence, claimed, r.classification(), r.startSequence(), r.endSequence()));
        }
        return out;
    }
}
