package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.evidence.EvidenceSnapshot;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.ActionType;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.DecisionOutcome;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.IntentState;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Terminal;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.world.WorldView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
/**
 * One session's mitigation actor (MITIGATION.md G1: "A worker emits an immutable MitigationIntent; only the Paper main-thread executor applies
 * game changes"). Owned by the session's shard thread (asserted). It consumes the ordered Phase 9 outcomes (live chain and the controlled side
 * chain), the admitted safe anchors, the timing facts that matter to a correction (teleport sent/confirmed, external motion, epoch changes)
 * and the main thread's ordered outcome records; it decides, in a fixed order, whether a setback is permitted and drives the one active
 * intent through the MITIGATION.md state machine. No clock is read: every time comes from an observation, so a replay of the same stream
 * yields the same decisions, targets and action ids.
 *
 * <p>Decision order (first failure wins; every outcome carries one typed reason):
 * <ol>
 * <li>the evidence policy ({@link SetbackPolicy}) - NONE / NOT_ELIGIBLE;</li>
 * <li>identity: session, world epoch; the session loop breaker; one active correction; recovery; the 2 s cooldown - SUPPRESSED;</li>
 * <li>current state: transport health, unresolved correction, unresolved external motion, unsupported locomotion - SUPPRESSED;</li>
 * <li>target: the most recent admitted anchor of the same session and epoch (for the live chain, certified before the first qualifying
 *     group); none - SUPPRESSED(NO_SAFE_ANCHOR); older than 2 s or farther than 8 blocks - RESYNC_REQUIRED (no fallback anchor, no
 *     invented position);</li>
 * <li>target safety now: a block dispute or an incomplete entity neighbourhood at the target or the current position; the standing box
 *     re-certified against the CURRENT observer world (block-change race) - SUPPRESSED;</li>
 * <li>idempotence: an action id already among the predecessors - SUPPRESSED(DUPLICATE_ACTION);</li>
 * <li>effect mode: OBSERVE_ONLY / DISABLED - SUPPRESSED (the would-be target and action id are recorded, nothing is applied); ENFORCE -
 *     PROPOSED, then WAITING_CAPTURE (a durable replay receipt is required), QUEUED, EXECUTING on the main thread ...</li>
 * </ol>
 *
 * <p>Feedback-loop prevention: proposing resets both evidence chains; while an intent is active or recovering every Phase 9 outcome is a reset,
 * never a contribution (the anticheat's own correction never becomes evidence, eligibility or another setback).
 */
public final class MitigationActor {
    /** Mirrors core's runtime effect mode (mitigation does not depend on core). */
    public enum EffectMode { DISABLED, OBSERVE_ONLY, ENFORCE }
    /** Requests the durable replay capsule through the decision point; the receipt returns via {@link #onCaptureReceipt}. */
    public interface CapturePort { boolean request(MitigationIntent intent); }
    /** The bounded hand-off to the Paper main-thread executor; {@code false} = saturated (rejected without blocking). */
    public interface IntentSink { boolean offer(MitigationIntent intent); }
    /** MITIGATION G2 at proposal time ("count proposed actionable decisions before capture/queue delay"): {@code false} = the global guard refuses (tripped). */
    public interface ProposalGate { boolean propose(SessionKey session, long nanos); }
    /** What the main thread reports back through the session's ordered control stream. */
    public enum PlatformOutcome { STARTED, SENT, REJECTED }

    /** The current facts a decision needs, supplied by core at evaluation time (predicates are deterministic queries over owner-thread state). */
    public record CurrentState(long nanos, long sequence, SessionKey session, long worldEpoch, String worldKey, Vector3 position, boolean transportHealthy, long transportGeneration,
                               int pendingTeleports, int pendingInfluences, boolean locomotionSupported, Predicate<Vector3> disputedNear, Predicate<Vector3> entitiesNear,
                               WorldView view, EffectMode mode) {
        public CurrentState { Objects.requireNonNull(session); Objects.requireNonNull(worldKey); Objects.requireNonNull(disputedNear); Objects.requireNonNull(entitiesNear); Objects.requireNonNull(view); Objects.requireNonNull(mode); }
    }
    /** One evaluation's immutable result (diagnostics and replay). */
    public record Decision(DecisionOutcome outcome, Reason reason, boolean sideChain, int chainGroups, String family, SafeAnchor target, String actionId, long nanos, long sequence) {
        public Decision { Objects.requireNonNull(outcome); Objects.requireNonNull(reason); }
        public String describe() {
            return String.format(Locale.ROOT, "%s %s %s chain=%d%s%s%s", outcome, reason, sideChain ? "side" : "live", chainGroups, family == null ? "" : " family=" + family,
                actionId == null ? "" : " action=" + actionId.substring(0, 16), target == null ? "" : " target seq=" + target.sequence() + String.format(Locale.ROOT, " (%.3f,%.3f,%.3f)", target.position().x(), target.position().y(), target.position().z()));
        }
    }
    /** One intent's terminal record. */
    public record Outcome(String actionId, Terminal terminal, Reason reason, IntentState reachedState, long nanos) {}
    /** Fixed-cardinality counters (no player identity in any label). */
    public static final class Metrics {
        public final LongAdder evaluations = new LongAdder(), none = new LongAdder(), notEligible = new LongAdder(), eligible = new LongAdder(), suppressed = new LongAdder(), resyncRequired = new LongAdder(), proposed = new LongAdder(),
            queued = new LongAdder(), sent = new LongAdder(), confirmed = new LongAdder(), recovered = new LongAdder(), expired = new LongAdder(), rejected = new LongAdder(), superseded = new LongAdder(),
            failures = new LongAdder(), duplicatesDropped = new LongAdder(), sessionsDisabled = new LongAdder(), anchorsAdmitted = new LongAdder(), anchorsRefused = new LongAdder(), internalErrors = new LongAdder();
        public final EnumMap<Reason, LongAdder> byReason = new EnumMap<>(Reason.class);
        { for (Reason r : Reason.values()) byReason.put(r, new LongAdder()); }
    }
    private static final class Active {
        MitigationIntent intent; IntentState state; long teleportId = -1, sentNanos, confirmedNanos, startedNanos;
        Active(MitigationIntent i) { intent = i; state = IntentState.PROPOSED; }
    }

    private final SessionKey session; private final MitigationBounds bounds; private final AnchorAdmission admission; private final CapturePort capture; private final IntentSink sink; private final ProposalGate gate; private final Metrics metrics;
    private final SetbackPolicy live, side;
    private final ArrayDeque<SafeAnchor> anchors = new ArrayDeque<>();
    private final ArrayDeque<String> predecessors = new ArrayDeque<>();
    private final ArrayDeque<Outcome> outcomes = new ArrayDeque<>();
    private final ArrayDeque<Decision> decisions = new ArrayDeque<>();
    private final ArrayDeque<Long> failureTimes = new ArrayDeque<>();
    private final ArrayDeque<long[]> recentTeleports = new ArrayDeque<>(); // [teleportId, x bits, y bits, z bits, nanos, relativeMask]
    private Active active; private boolean disabled; private long lastSentNanos = Long.MIN_VALUE, epoch = Long.MIN_VALUE; private Decision last; private Reason lastAnchorRefusal;
    private Thread owner;

    public MitigationActor(SessionKey session, MitigationBounds bounds, AnchorAdmission admission, CapturePort capture, IntentSink sink, ProposalGate gate, Metrics metrics) {
        this.session = Objects.requireNonNull(session); this.bounds = Objects.requireNonNull(bounds); this.admission = Objects.requireNonNull(admission); this.capture = Objects.requireNonNull(capture); this.sink = Objects.requireNonNull(sink); this.gate = Objects.requireNonNull(gate); this.metrics = Objects.requireNonNull(metrics);
        live = new SetbackPolicy(session); side = new SetbackPolicy(session);
    }
    private void owner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("mitigation actor ownership"); }
    public SessionKey session() { return session; }

    // ------------------------------------------------------------------------------------------------ anchors
    /** One completed live transition: admitted as a safe anchor or refused with a typed reason. */
    public void onAnchorFacts(AnchorAdmission.Facts f, long ownerEpoch, WorldView view) {
        owner(); epoch(ownerEpoch, f.nanos());
        AnchorAdmission.Result r = admission.admit(f, session, ownerEpoch, view);
        if (r.anchor().isPresent()) { anchors.addLast(r.anchor().get()); while (anchors.size() > bounds.anchorHistory()) anchors.removeFirst(); metrics.anchorsAdmitted.increment(); lastAnchorRefusal = null; }
        else { metrics.anchorsRefused.increment(); lastAnchorRefusal = r.refusal(); }
    }

    // ------------------------------------------------------------------------------------------------ evidence
    /**
     * One Phase 9 ingest outcome ({@code event} is the new event for POSITIVE_EVENT, else empty) with the ledger snapshot right after it.
     * Returns the decision taken (NONE unless positive evidence made the policy eligible).
     */
    public Decision onEvidence(EvidenceLedger.Outcome outcome, Optional<EvidenceEvent> event, EvidenceSnapshot snapshot, boolean sideChain, CurrentState cs) {
        owner(); Objects.requireNonNull(outcome); Objects.requireNonNull(snapshot); Objects.requireNonNull(cs);
        try {
            epoch(cs.worldEpoch(), cs.nanos());
            SetbackPolicy p = sideChain ? side : live;
            if (!sideChain && active != null && active.state == IntentState.RECOVERY && outcome == EvidenceLedger.Outcome.LEGITIMATE_WITNESS) finish(Terminal.RECOVERED, Reason.RECOVERY, cs.nanos());
            if (active != null) { p.onCorrection(); return record(new Decision(DecisionOutcome.SUPPRESSED, active.state == IntentState.RECOVERY ? Reason.RECOVERY : Reason.ACTIVE_CORRECTION, sideChain, 0, null, null, null, cs.nanos(), cs.sequence()), false); }
            switch (outcome) {
                case POSITIVE_EVENT -> p.onPositiveEvent(event.orElseThrow(), snapshot);
                case DUPLICATE -> p.onDuplicate();
                case LEGITIMATE_WITNESS, UNRESOLVED, PROOF_REJECTED, OUT_OF_ORDER -> p.onNonEvidence(Reason.INSUFFICIENT_EVIDENCE);
                case SESSION_MISMATCH -> p.onNonEvidence(Reason.SESSION_MISMATCH);
            }
            if (outcome != EvidenceLedger.Outcome.POSITIVE_EVENT) return quiet(p, snapshot, sideChain, cs);
            return decide(p.verdict(snapshot), sideChain, cs);
        } catch (RuntimeException ex) {
            metrics.internalErrors.increment(); live.onNonEvidence(Reason.INTERNAL_ERROR); side.onNonEvidence(Reason.INTERNAL_ERROR);
            return record(new Decision(DecisionOutcome.SUPPRESSED, Reason.INTERNAL_ERROR, sideChain, 0, null, null, null, cs.nanos(), cs.sequence()), true);
        }
    }
    /** An unanchored transmission (UNCERTAIN [START_UNAVAILABLE]) on the live chain: an UNCERTAIN observation, so the live chain resets. */
    public void onUnanchored(long nanos) { owner(); live.onNonEvidence(Reason.INSUFFICIENT_EVIDENCE); }

    private Decision quiet(SetbackPolicy p, EvidenceSnapshot snapshot, boolean sideChain, CurrentState cs) {
        metrics.evaluations.increment(); metrics.none.increment();
        return last = new Decision(DecisionOutcome.NONE, p.chainLength() == 0 ? Reason.NO_EVIDENCE : Reason.INSUFFICIENT_EVIDENCE, sideChain, p.chainLength(), null, null, null, cs.nanos(), cs.sequence());
    }

    private Decision decide(SetbackPolicy.Verdict v, boolean sideChain, CurrentState cs) {
        if (!v.eligible()) return record(new Decision(v.groups() == 0 ? DecisionOutcome.NONE : DecisionOutcome.NOT_ELIGIBLE, v.reason(), sideChain, v.groups(), v.family() == null ? null : v.family().name(), null, null, cs.nanos(), cs.sequence()), true);
        metrics.eligible.increment();
        String fam = v.family().name();
        if (!cs.session().equals(session)) return suppressed(Reason.SESSION_MISMATCH, v, sideChain, cs, null, null);
        if (cs.worldEpoch() != epoch) return suppressed(Reason.WORLD_EPOCH_MISMATCH, v, sideChain, cs, null, null);
        if (disabled) return suppressed(Reason.SESSION_DISABLED, v, sideChain, cs, null, null);
        if (lastSentNanos != Long.MIN_VALUE && cs.nanos() - lastSentNanos < bounds.cooldownNanos()) return suppressed(Reason.COOLDOWN, v, sideChain, cs, null, null);
        if (!cs.transportHealthy()) return suppressed(Reason.TRANSPORT_UNHEALTHY, v, sideChain, cs, null, null);
        if (cs.pendingTeleports() > 0) return suppressed(Reason.UNRESOLVED_TELEPORT, v, sideChain, cs, null, null);
        if (cs.pendingInfluences() > 0) return suppressed(Reason.UNRESOLVED_EXTERNAL_MOTION, v, sideChain, cs, null, null);
        if (!cs.locomotionSupported()) return suppressed(Reason.UNSUPPORTED_LOCOMOTION, v, sideChain, cs, null, null);
        SafeAnchor target = null;
        for (Iterator<SafeAnchor> it = anchors.descendingIterator(); it.hasNext();) { SafeAnchor a = it.next(); if (a.session().equals(session) && a.worldEpoch() == epoch && (sideChain || a.sequence() <= v.firstSequence())) { target = a; break; } }
        if (target == null) return suppressed(Reason.NO_SAFE_ANCHOR, v, sideChain, cs, null, null);
        String id = MitigationIntent.actionId(v.policyVersion(), session, epoch, v.groupIds(), sideChain, target.sequence());
        if (target.ageAt(cs.nanos()) > bounds.maxAnchorAgeNanos()) return resync(Reason.ANCHOR_TOO_OLD, v, sideChain, cs, target, id);
        if (cs.position() == null || target.distanceTo(cs.position()) > bounds.maxCorrectionDistance()) return resync(Reason.ANCHOR_TOO_FAR, v, sideChain, cs, target, id);
        if (cs.disputedNear().test(target.position()) || (cs.position() != null && cs.disputedNear().test(cs.position()))) return suppressed(Reason.BLOCK_DISPUTE, v, sideChain, cs, target, id);
        if (cs.entitiesNear().test(target.position()) || (cs.position() != null && cs.entitiesNear().test(cs.position()))) return suppressed(Reason.ENTITY_STATE_INCOMPLETE, v, sideChain, cs, target, id);
        AnchorAdmission.Clearance c = admission.clearance(cs.view(), target.position(), epoch);
        if (!c.clear()) return suppressed(c.reason() == Reason.COLLISION_OBSTRUCTED ? Reason.COLLISION_OBSTRUCTED : Reason.COLLISION_UNKNOWN, v, sideChain, cs, target, id);
        if (predecessors.contains(id)) { metrics.duplicatesDropped.increment(); return suppressed(Reason.DUPLICATE_ACTION, v, sideChain, cs, target, id); }
        if (cs.mode() == EffectMode.DISABLED) return suppressed(Reason.MODE_DISABLED, v, sideChain, cs, target, id);
        if (cs.mode() != EffectMode.ENFORCE) return suppressed(Reason.MODE_OBSERVE_ONLY, v, sideChain, cs, target, id);
        // G2 counts the proposal BEFORE capture/queue delay; a tripped global guard refuses it (no capsule, no intent)
        if (!gate.propose(session, cs.nanos())) return suppressed(Reason.GLOBAL_GUARD, v, sideChain, cs, target, id);
        // ENFORCE: propose, then require the durable capsule receipt before anything is queued for the main thread
        MitigationIntent intent = new MitigationIntent(id, ActionType.SETBACK, session, epoch, cs.worldKey(), cs.transportGeneration(), v.groupIds(), v.eventIds(), v.family(), v.policyVersion(), sideChain,
            target, cs.sequence(), cs.view().revision(), cs.nanos(), cs.nanos() + bounds.intentDeadlineNanos(), "PENDING");
        remember(id); live.onCorrection(); side.onCorrection();
        active = new Active(intent); metrics.proposed.increment();
        Decision d = record(new Decision(DecisionOutcome.PROPOSED, Reason.POLICY_MET, sideChain, v.groups(), fam, target, id, cs.nanos(), cs.sequence()), false);
        active.state = IntentState.WAITING_CAPTURE;
        if (!capture.request(intent)) finish(Terminal.REJECTED, Reason.CAPTURE_UNAVAILABLE, cs.nanos());
        return d;
    }
    private Decision suppressed(Reason r, SetbackPolicy.Verdict v, boolean sideChain, CurrentState cs, SafeAnchor target, String id) {
        metrics.suppressed.increment(); metrics.byReason.get(r).increment();
        return record(new Decision(DecisionOutcome.SUPPRESSED, r, sideChain, v.groups(), v.family() == null ? null : v.family().name(), target, id, cs.nanos(), cs.sequence()), false);
    }
    private Decision resync(Reason r, SetbackPolicy.Verdict v, boolean sideChain, CurrentState cs, SafeAnchor target, String id) {
        metrics.resyncRequired.increment(); metrics.byReason.get(r).increment();
        return record(new Decision(DecisionOutcome.RESYNC_REQUIRED, r, sideChain, v.groups(), v.family().name(), target, id, cs.nanos(), cs.sequence()), false);
    }
    private Decision record(Decision d, boolean count) {
        metrics.evaluations.increment();
        if (count) { if (d.outcome() == DecisionOutcome.NONE) metrics.none.increment(); else if (d.outcome() == DecisionOutcome.NOT_ELIGIBLE) metrics.notEligible.increment(); }
        last = d; decisions.addLast(d); while (decisions.size() > 16) decisions.removeFirst(); return d;
    }

    // ------------------------------------------------------------------------------------------------ intent lifecycle
    public void onCaptureReceipt(String actionId, boolean durable, String receipt, long nanos) {
        owner();
        if (active == null || !active.intent.actionId().equals(actionId) || active.state != IntentState.WAITING_CAPTURE) return; // stale or duplicate receipt: diagnostic only
        if (!durable) { finish(Terminal.REJECTED, Reason.CAPTURE_UNAVAILABLE, nanos); return; }
        if (nanos > active.intent.deadlineNanos()) { finish(Terminal.EXPIRED, Reason.CAPTURE_DEADLINE_MISS, nanos); return; }
        active.intent = active.intent.withReceipt(receipt); active.state = IntentState.QUEUED; metrics.queued.increment();
        if (!sink.offer(active.intent)) finish(Terminal.REJECTED, Reason.QUEUE_SATURATED, nanos);
    }
    /** The main thread's ordered outcome for the active intent (matched by the short id; anything else is stale and only diagnostic). */
    public void onPlatformOutcome(String shortId, PlatformOutcome o, Reason reason, long nanos) {
        owner();
        if (active == null || !active.intent.shortId().equals(shortId)) return;
        switch (o) {
            case STARTED -> { if (active.state == IntentState.QUEUED) { active.state = IntentState.EXECUTING; active.startedNanos = nanos; } }
            case SENT -> {
                if (active.state == IntentState.QUEUED) { active.state = IntentState.EXECUTING; active.startedNanos = nanos; }
                if (active.state == IntentState.EXECUTING) adoptTeleportIfSeen(nanos);
            }
            case REJECTED -> {
                boolean failure = reason == Reason.PLATFORM_CANCELLED || reason == Reason.SEND_FAILED;
                finish(failure ? Terminal.RESYNC_REQUIRED : Terminal.REJECTED, reason == null ? Reason.INTERNAL_ERROR : reason, nanos);
                if (failure) failure(nanos);
            }
        }
    }
    /** A captured outgoing correction: ours when it lands exactly on the intent's target with absolute coordinates, otherwise foreign. */
    public void onTeleportSent(long teleportId, Vector3 position, int relativeMask, long nanos) {
        owner();
        recentTeleports.addLast(new long[] { teleportId, Double.doubleToLongBits(position.x()), Double.doubleToLongBits(position.y()), Double.doubleToLongBits(position.z()), nanos, relativeMask });
        while (recentTeleports.size() > 4) recentTeleports.removeFirst();
        if (active == null) return;
        if ((active.state == IntentState.EXECUTING || active.state == IntentState.QUEUED) && ours(position, relativeMask)) { sent(teleportId, nanos); return; }
        // a foreign correction wins: before execution it supersedes the intent; after an already-started effect it reconciles a new baseline
        switch (active.state) {
            case PROPOSED, WAITING_CAPTURE, QUEUED -> finish(Terminal.SUPERSEDED, Reason.SUPERSEDED_BY_FOREIGN_TELEPORT, nanos);
            case SENT_AWAITING_CONFIRM, CONFIRMED, RECOVERY -> finish(Terminal.SUPERSEDED, Reason.SUPERSEDED_BY_FOREIGN_TELEPORT, nanos);
            default -> { }
        }
    }
    public void onTeleportConfirmed(long teleportId, long nanos) {
        owner();
        if (active == null || active.state != IntentState.SENT_AWAITING_CONFIRM || active.teleportId != teleportId) return; // stale/duplicate confirms never confirm a newer correction
        active.state = IntentState.CONFIRMED; active.confirmedNanos = nanos; metrics.confirmed.increment();
        active.state = IntentState.RECOVERY; live.onCorrection(); side.onCorrection();
    }
    /** A legitimate external motion was registered for this player: an intent not yet executing must not erase it. */
    public void onExternalMotion(long nanos) {
        owner();
        if (active != null && (active.state == IntentState.PROPOSED || active.state == IntentState.WAITING_CAPTURE || active.state == IntentState.QUEUED)) finish(Terminal.REJECTED, Reason.UNRESOLVED_EXTERNAL_MOTION, nanos);
    }
    /** Time passes (tick ends): deadlines and timeouts, all measured in observation time. */
    public void onTick(long nanos) {
        owner();
        if (active == null) return;
        switch (active.state) {
            case PROPOSED, QUEUED -> { if (nanos > active.intent.deadlineNanos()) finish(Terminal.EXPIRED, Reason.DEADLINE_EXPIRED, nanos); }
            case WAITING_CAPTURE -> { if (nanos > active.intent.deadlineNanos()) finish(Terminal.EXPIRED, Reason.CAPTURE_DEADLINE_MISS, nanos); }
            case EXECUTING -> { if (nanos - active.startedNanos > bounds.confirmTimeoutNanos()) { finish(Terminal.RESYNC_REQUIRED, Reason.SEND_FAILED, nanos); failure(nanos); } }
            case SENT_AWAITING_CONFIRM -> { if (nanos - active.sentNanos > bounds.confirmTimeoutNanos()) { finish(Terminal.RESYNC_REQUIRED, Reason.CONFIRM_TIMEOUT, nanos); failure(nanos); } }
            case RECOVERY -> { if (nanos - active.confirmedNanos > bounds.recoveryHorizonNanos()) finish(Terminal.CONFIRMED_UNRECOVERED, Reason.RECOVERY, nanos); }
            default -> { }
        }
    }
    /** World/dimension change: incompatible anchors are dropped, both chains reset, and any intent is superseded (never applied in the new world). */
    public void onEpochChange(long newEpoch, long nanos) { owner(); epoch(newEpoch, nanos); }
    /** The session ends (disconnect, replacement, shutdown): the active intent terminates; nothing of this session can affect a new one. */
    public void onSessionEnd(boolean shutdown, long nanos) {
        owner();
        if (active != null) finish(Terminal.REJECTED, shutdown ? Reason.SHUTDOWN : Reason.SESSION_ENDED, nanos);
        anchors.clear(); live.onNonEvidence(Reason.SESSION_MISMATCH); side.onNonEvidence(Reason.SESSION_MISMATCH);
    }

    private boolean ours(Vector3 p, int relativeMask) {
        Vector3 t = active.intent.anchor().position();
        return (relativeMask & 7) == 0 && Double.doubleToLongBits(p.x()) == Double.doubleToLongBits(t.x()) && Double.doubleToLongBits(p.y()) == Double.doubleToLongBits(t.y()) && Double.doubleToLongBits(p.z()) == Double.doubleToLongBits(t.z());
    }
    private void adoptTeleportIfSeen(long nanos) {
        for (Iterator<long[]> it = recentTeleports.descendingIterator(); it.hasNext();) {
            long[] t = it.next();
            if (t[4] >= active.startedNanos - 1 && ours(new Vector3(Double.longBitsToDouble(t[1]), Double.longBitsToDouble(t[2]), Double.longBitsToDouble(t[3])), (int) t[5])) { sent(t[0], t[4]); return; }
        }
    }
    private void sent(long teleportId, long nanos) {
        active.state = IntentState.SENT_AWAITING_CONFIRM; active.teleportId = teleportId; active.sentNanos = nanos; lastSentNanos = nanos; metrics.sent.increment();
        live.onCorrection(); side.onCorrection();
    }
    private void failure(long nanos) {
        metrics.failures.increment(); failureTimes.addLast(nanos);
        while (!failureTimes.isEmpty() && nanos - failureTimes.peekFirst() > bounds.failureWindowNanos()) failureTimes.removeFirst();
        if (failureTimes.size() >= bounds.failureLimit() && !disabled) { disabled = true; metrics.sessionsDisabled.increment(); }
    }
    private void finish(Terminal t, Reason r, long nanos) {
        if (active == null) return;
        switch (t) { case RECOVERED -> metrics.recovered.increment(); case EXPIRED -> metrics.expired.increment(); case REJECTED -> metrics.rejected.increment(); case SUPERSEDED -> metrics.superseded.increment(); default -> { } }
        metrics.byReason.get(r).increment();
        outcomes.addLast(new Outcome(active.intent.actionId(), t, r, active.state, nanos)); while (outcomes.size() > bounds.predecessorIds()) outcomes.removeFirst();
        active = null;
    }
    private void remember(String id) { predecessors.addLast(id); while (predecessors.size() > bounds.predecessorIds()) predecessors.removeFirst(); }
    private void epoch(long e, long nanos) {
        if (epoch == Long.MIN_VALUE) { epoch = e; return; }
        if (e == epoch) return;
        epoch = e; anchors.removeIf(a -> a.worldEpoch() != e); live.onEpochChange(); side.onEpochChange();
        if (active != null) finish(Terminal.SUPERSEDED, Reason.WORLD_CHANGED, nanos);
    }

    // ------------------------------------------------------------------------------------------------ status
    public record Status(SessionKey session, long worldEpoch, int liveChain, int sideChain, String policy, int anchors, SafeAnchor latestAnchor, Reason lastAnchorRefusal,
                         String activeActionId, IntentState activeState, boolean disabled, long lastSentNanos, int recentFailures, Decision lastDecision, List<Decision> recentDecisions, List<Outcome> recentOutcomes) {
        public Status { recentDecisions = List.copyOf(recentDecisions); recentOutcomes = List.copyOf(recentOutcomes); }
    }
    public Status status() {
        return new Status(session, epoch == Long.MIN_VALUE ? -1 : epoch, live.chainLength(), side.chainLength(), SetbackPolicy.VERSION, anchors.size(), anchors.peekLast(), lastAnchorRefusal,
            active == null ? null : active.intent.actionId(), active == null ? IntentState.IDLE : active.state, disabled, lastSentNanos, failureTimes.size(), last, new ArrayList<>(decisions), new ArrayList<>(outcomes));
    }
    public IntentState state() { return active == null ? IntentState.IDLE : active.state; }
    public Optional<MitigationIntent> activeIntent() { return Optional.ofNullable(active == null ? null : active.intent); }
    public List<SafeAnchor> anchors() { return List.copyOf(anchors); }
    public boolean disabled() { return disabled; }
}
