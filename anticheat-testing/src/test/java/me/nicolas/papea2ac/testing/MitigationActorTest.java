package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Consumer;
import me.nicolas.papea2ac.core.GlobalProposalGuard;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.CurrentState;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.WorldObservation;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 decision order and intent state machine (MitigationActor) against a REAL observer world and the REAL collision engine: every
 * suppression reason, the RESYNC_REQUIRED bounds, OBSERVE_ONLY vs ENFORCE, the durable-capture requirement, idempotence, the teleport /
 * velocity / world-change / block-change races, deadlines, confirm timeout, the loop breaker, the cooldown, recovery without self-feeding and
 * session end. Evidence arrives through a real Phase 9 ledger.
 */
class MitigationActorTest {
    static final long S = 1_000_000_000L, T0 = 100 * S;
    static final List<InvalidCode> H = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS);
    static final Vector3 ANCHOR = new Vector3(8.5, SURFACE, 8.5), NEAR = new Vector3(9.3, SURFACE, 8.5);

    static final class H0 {
        final PhysicsScene ps = new PhysicsScene(); final SessionKey key = ps.scene.key;
        final AnchorAdmission admission = new AnchorAdmission(ps.scene.engine, p -> CollisionTestSupport.standing(p.x(), p.y(), p.z()));
        final List<MitigationIntent> captured = new ArrayList<>(), queued = new ArrayList<>();
        boolean captureAvailable = true, sinkOpen = true, gateOpen = true; int proposals;
        MitigationActor.ProposalGate gateDelegate; // when set, the real global guard decides
        final MitigationActor.Metrics metrics = new MitigationActor.Metrics();
        final MitigationActor actor = new MitigationActor(key, MitigationBounds.DEFAULT, admission, i -> { captured.add(i); return captureAvailable; }, i -> { if (!sinkOpen) return false; queued.add(i); return true; }, (s, n) -> { proposals++; return gateDelegate != null ? gateDelegate.propose(s, n) : gateOpen; }, metrics);
        final EvidenceLedger ledger = new EvidenceLedger(key);
        EffectMode mode = EffectMode.OBSERVE_ONLY; Vector3 position = NEAR; boolean healthy = true, supported = true, dispute = false, entities = false; int teleports = 0, influences = 0; SessionKey csSession;
        Consumer<Vector3> entityProbe = p -> { };
        CurrentState cs(long nanos, long seq) {
            return new CurrentState(nanos, seq, csSession == null ? key : csSession, 0, "minecraft:overworld", position, healthy, 0, teleports, influences, supported, p -> dispute,
                p -> { entityProbe.accept(p); return entities; }, ps.scene.world.currentView(), mode);
        }
        AnchorAdmission.Facts facts(long seq, long nanos, Vector3 p) { return new AnchorAdmission.Facts(key, 0, seq, nanos, true, true, true, List.of(p, p), true, true, true, true, 0, 0, false, true, 90f, 10f); }
        void anchor(long seq, long nanos, Vector3 p) { actor.onAnchorFacts(facts(seq, nanos, p), 0, ps.scene.world.currentView()); }
        MitigationActor.Decision feed(ValidationRecord r) { EvidenceLedger.Outcome o = ledger.ingest(r); return actor.onEvidence(o, ledger.lastEvent().filter(e -> o == EvidenceLedger.Outcome.POSITIVE_EVENT), ledger.snapshot(), false, cs(r.nanos(), r.endSequence())); }
        ValidationRecord group(int i, long nanos) { return invalid(key, 0, 100L * i, 100L * i + 10, nanos, H, 0.2, 0.0); }
        /** A standard eligible setup: one anchor certified before the first group, then 4 independent groups within 0.4 s. */
        MitigationActor.Decision eligible() { anchor(50, T0, ANCHOR); MitigationActor.Decision d = null; for (int i = 1; i <= 4; i++) d = feed(group(i, T0 + i * S / 10)); return d; }
    }

    @Test void aLegitimateStreamNeverActs() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE;
        for (int i = 0; i < 300; i++) { h.anchor(10L * i, T0 + i * S / 20, ANCHOR); MitigationActor.Decision d = h.feed(valid(h.key, 0, 10L * i, 10L * i + 10, T0 + i * S / 20)); assertEquals(DecisionOutcome.NONE, d.outcome()); }
        assertTrue(h.captured.isEmpty() && h.queued.isEmpty()); assertEquals(0, h.metrics.proposed.sum()); assertEquals(0, h.metrics.eligible.sum());
        assertTrue(h.actor.anchors().size() <= MitigationBounds.DEFAULT.anchorHistory(), "bounded anchor history");
    }
    @Test void oneToThreeGroupsAreNotEligibleAndFourInObserveOnlyChangeNothing() {
        H0 h = new H0(); h.anchor(50, T0, ANCHOR);
        for (int i = 1; i <= 3; i++) { var d = h.feed(h.group(i, T0 + i * S / 10)); assertEquals(DecisionOutcome.NOT_ELIGIBLE, d.outcome(), d.describe()); assertEquals(Reason.INSUFFICIENT_EVIDENCE, d.reason()); }
        var d = h.feed(h.group(4, T0 + 4 * S / 10));
        assertEquals(DecisionOutcome.SUPPRESSED, d.outcome(), d.describe()); assertEquals(Reason.MODE_OBSERVE_ONLY, d.reason());
        assertNotNull(d.target()); assertEquals(50, d.target().sequence()); assertNotNull(d.actionId(), "the would-be action is recorded");
        assertTrue(h.captured.isEmpty() && h.queued.isEmpty(), "OBSERVE_ONLY: nothing is captured, queued or applied"); assertEquals(IntentState.IDLE, h.actor.state());
    }
    @Test void enforceProposesExactlyOnceAndNeedsADurableReceiptBeforeQueueing() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE;
        var d = h.eligible();
        assertEquals(DecisionOutcome.PROPOSED, d.outcome(), d.describe()); assertEquals(IntentState.WAITING_CAPTURE, h.actor.state());
        assertEquals(1, h.captured.size()); assertTrue(h.queued.isEmpty(), "nothing reaches the main thread before the durable receipt");
        MitigationIntent it = h.captured.get(0);
        assertEquals(SetbackPolicy.VERSION, it.policyVersion()); assertEquals(ActionType.SETBACK, it.actionType()); assertEquals(4, it.sourceGroupIds().size()); assertEquals(ANCHOR, it.anchor().position());
        assertEquals(it.proposedNanos() + MitigationBounds.DEFAULT.intentDeadlineNanos(), it.deadlineNanos());
        h.actor.onCaptureReceipt(it.actionId(), true, "capsule:" + it.shortId(), T0 + 5 * S / 10);
        assertEquals(IntentState.QUEUED, h.actor.state()); assertEquals(1, h.queued.size()); assertEquals("capsule:" + it.shortId(), h.queued.get(0).durableReceipt());
        h.actor.onCaptureReceipt(it.actionId(), true, "again", T0 + 5 * S / 10); assertEquals(1, h.queued.size(), "a duplicate receipt queues nothing");
        // more positive evidence while the correction is active is never a contribution
        for (int i = 5; i <= 9; i++) assertEquals(Reason.ACTIVE_CORRECTION, h.feed(h.group(i, T0 + i * S / 10)).reason());
        assertEquals(1, h.captured.size());
        // determinism: the same stream on a fresh actor yields the same action id
        H0 g = new H0(); g.mode = EffectMode.ENFORCE; g.eligible();
        assertEquals(it.actionId(), g.captured.get(0).actionId());
    }
    @Test void everySafetyPreconditionSuppressesWithItsOwnReason() {
        record Case(Reason expected, DecisionOutcome outcome, Consumer<H0> setup) {}
        List<Case> cases = List.of(
            new Case(Reason.TRANSPORT_UNHEALTHY, DecisionOutcome.SUPPRESSED, h -> h.healthy = false),
            new Case(Reason.UNRESOLVED_TELEPORT, DecisionOutcome.SUPPRESSED, h -> h.teleports = 1),
            new Case(Reason.UNRESOLVED_EXTERNAL_MOTION, DecisionOutcome.SUPPRESSED, h -> h.influences = 1),
            new Case(Reason.UNSUPPORTED_LOCOMOTION, DecisionOutcome.SUPPRESSED, h -> h.supported = false),
            new Case(Reason.BLOCK_DISPUTE, DecisionOutcome.SUPPRESSED, h -> h.dispute = true),
            new Case(Reason.ENTITY_STATE_INCOMPLETE, DecisionOutcome.SUPPRESSED, h -> h.entities = true),
            new Case(Reason.ANCHOR_TOO_FAR, DecisionOutcome.RESYNC_REQUIRED, h -> h.position = new Vector3(ANCHOR.x() + 9.0, SURFACE, ANCHOR.z())),
            new Case(Reason.MODE_DISABLED, DecisionOutcome.SUPPRESSED, h -> h.mode = EffectMode.DISABLED),
            new Case(Reason.MODE_OBSERVE_ONLY, DecisionOutcome.SUPPRESSED, h -> h.mode = EffectMode.OBSERVE_ONLY));
        for (Case c : cases) {
            H0 h = new H0(); h.mode = EffectMode.ENFORCE; h.anchor(50, T0, ANCHOR);
            for (int i = 1; i <= 3; i++) h.feed(h.group(i, T0 + i * S / 10));
            c.setup().accept(h);
            var d = h.feed(h.group(4, T0 + 4 * S / 10));
            assertEquals(c.outcome(), d.outcome(), c.expected() + ": " + d.describe()); assertEquals(c.expected(), d.reason(), d.describe());
            assertTrue(h.captured.isEmpty() && h.queued.isEmpty(), c.expected() + " must not propose");
        }
    }
    @Test void noAnchorOrAnOldAnchorNeverProducesACorrection() {
        H0 none = new H0(); none.mode = EffectMode.ENFORCE;
        MitigationActor.Decision d = null; for (int i = 1; i <= 4; i++) d = none.feed(none.group(i, T0 + i * S / 10));
        assertEquals(Reason.NO_SAFE_ANCHOR, d.reason()); assertEquals(DecisionOutcome.SUPPRESSED, d.outcome());
        H0 old = new H0(); old.mode = EffectMode.ENFORCE; old.anchor(50, T0 - 3 * S, ANCHOR);
        for (int i = 1; i <= 4; i++) d = old.feed(old.group(i, T0 + i * S / 10));
        assertEquals(DecisionOutcome.RESYNC_REQUIRED, d.outcome(), d.describe()); assertEquals(Reason.ANCHOR_TOO_OLD, d.reason());
        assertTrue(old.captured.isEmpty(), "no long-distance or long-ago fallback");
        // an anchor certified AFTER the first qualifying group is never the live target (it would not be a pre-evidence state)
        H0 late = new H0(); late.mode = EffectMode.ENFORCE;
        for (int i = 1; i <= 3; i++) late.feed(late.group(i, T0 + i * S / 10));
        late.anchor(350, T0 + 35 * S / 100, ANCHOR);
        assertEquals(Reason.NO_SAFE_ANCHOR, late.feed(late.group(4, T0 + 4 * S / 10)).reason());
    }
    @Test void theTargetIsRecertifiedAgainstTheCurrentWorld() {
        // block-change race: the anchor was clear when certified, a block now occupies it
        H0 h = new H0(); h.mode = EffectMode.ENFORCE; h.anchor(50, T0, ANCHOR);
        for (int i = 1; i <= 3; i++) h.feed(h.group(i, T0 + i * S / 10));
        h.ps.set(8, (int) SURFACE, 8, BlockStateKey.of("minecraft:stone"));
        var d = h.feed(h.group(4, T0 + 4 * S / 10)); assertEquals(Reason.COLLISION_OBSTRUCTED, d.reason(), d.describe()); assertTrue(h.captured.isEmpty());
        // the chunk is no longer known: UNKNOWN is never clear
        H0 u = new H0(); u.mode = EffectMode.ENFORCE; u.anchor(50, T0, ANCHOR);
        for (int i = 1; i <= 3; i++) u.feed(u.group(i, T0 + i * S / 10));
        u.ps.scene.apply(new WorldObservation.ChunkUnloaded(0, 0));
        d = u.feed(u.group(4, T0 + 4 * S / 10)); assertEquals(Reason.COLLISION_UNKNOWN, d.reason(), d.describe()); assertTrue(u.captured.isEmpty());
    }
    @Test void anchorAdmissionRefusesEveryUnsafeState() {
        H0 h = new H0(); var view = h.ps.scene.world.currentView();
        AnchorAdmission.Facts ok = h.facts(1, T0, ANCHOR);
        assertTrue(h.admission.admit(ok, h.key, 0, view).anchor().isPresent());
        record Case(Reason r, AnchorAdmission.Facts f) {}
        List<Case> cases = List.of(
            new Case(Reason.WORLD_INCOMPLETE, new AnchorAdmission.Facts(h.key, 0, 1, T0, false, true, true, List.of(ANCHOR), true, false, true, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.WORLD_INCOMPLETE, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, false, true, List.of(ANCHOR), true, false, true, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.NO_SAFE_ANCHOR, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR, NEAR), true, false, true, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.NO_SAFE_ANCHOR, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), false, false, true, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.NO_SAFE_ANCHOR, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, false, List.of(ANCHOR), true, false, true, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.NO_SAFE_ANCHOR, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, false, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.UNRESOLVED_TELEPORT, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, true, 1, 0, false, true, 0f, 0f)),
            new Case(Reason.UNRESOLVED_EXTERNAL_MOTION, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, true, 0, 1, false, true, 0f, 0f)),
            new Case(Reason.BLOCK_DISPUTE, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, true, 0, 0, true, true, 0f, 0f)),
            new Case(Reason.UNSUPPORTED_LOCOMOTION, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, false, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.UNSUPPORTED_LOCOMOTION, new AnchorAdmission.Facts(h.key, 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, true, 0, 0, false, false, 0f, 0f)),
            new Case(Reason.COLLISION_OBSTRUCTED, h.facts(1, T0, new Vector3(8.5, SURFACE - 1.0, 8.5))),
            new Case(Reason.COLLISION_UNKNOWN, h.facts(1, T0, new Vector3(200.5, SURFACE, 200.5))),
            new Case(Reason.SESSION_MISMATCH, new AnchorAdmission.Facts(new SessionKey(new UUID(1, 2), 1), 0, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, true, 0, 0, false, true, 0f, 0f)),
            new Case(Reason.WORLD_EPOCH_MISMATCH, new AnchorAdmission.Facts(h.key, 3, 1, T0, true, true, true, List.of(ANCHOR), true, false, true, true, 0, 0, false, true, 0f, 0f)));
        for (Case c : cases) { var r = h.admission.admit(c.f(), h.key, 0, view); assertTrue(r.anchor().isEmpty(), c.r() + " must refuse"); assertEquals(c.r(), r.refusal()); }
    }
    /** The full MITIGATION.md lifecycle: PROPOSED -> WAITING_CAPTURE -> QUEUED -> EXECUTING -> SENT_AWAITING_CONFIRM -> CONFIRMED -> RECOVERY -> IDLE. */
    @Test void theCorrectionLifecycleRecoversWithoutFeedingItself() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE; h.eligible();
        MitigationIntent it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "capsule", T0 + 5 * S / 10);
        h.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.STARTED, null, T0 + 6 * S / 10); assertEquals(IntentState.EXECUTING, h.actor.state());
        h.actor.onTeleportSent(77, ANCHOR, 0, T0 + 6 * S / 10); assertEquals(IntentState.SENT_AWAITING_CONFIRM, h.actor.state(), "our exact target, absolute coordinates");
        h.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.SENT, null, T0 + 6 * S / 10); assertEquals(IntentState.SENT_AWAITING_CONFIRM, h.actor.state(), "SENT after the capture is idempotent");
        h.actor.onTeleportConfirmed(76, T0 + 7 * S / 10); assertEquals(IntentState.SENT_AWAITING_CONFIRM, h.actor.state(), "a stale confirm never confirms a newer correction");
        h.actor.onTeleportConfirmed(77, T0 + 7 * S / 10); assertEquals(IntentState.RECOVERY, h.actor.state());
        // the client is still applying the correction: nothing it produces becomes evidence for another setback
        for (int i = 10; i <= 16; i++) assertEquals(Reason.RECOVERY, h.feed(h.group(i, T0 + 8 * S / 10 + i)).reason());
        h.feed(valid(h.key, 0, 1700, 1710, T0 + 9 * S / 10));
        assertEquals(IntentState.IDLE, h.actor.state()); assertEquals(Terminal.RECOVERED, h.actor.status().recentOutcomes().get(0).terminal());
        assertEquals(1, h.captured.size()); assertEquals(1, h.queued.size()); assertEquals(1, h.metrics.sent.sum()); assertEquals(1, h.metrics.recovered.sum());
        assertEquals(0, h.actor.status().liveChain(), "evidence before the correction never combines with evidence after it");
        // the 2 s cooldown: a new eligible chain right away is suppressed, not corrected
        h.anchor(1800, T0 + 95 * S / 100, ANCHOR);
        MitigationActor.Decision d = null; for (int i = 20; i <= 23; i++) d = h.feed(h.group(i, T0 + S + i * S / 100));
        assertEquals(Reason.COOLDOWN, d.reason(), d.describe()); assertEquals(1, h.captured.size());
    }
    @Test void racesBeforeExecutionCancelTheStaleIntent() {
        // teleport race: a foreign correction lands before ours executes
        H0 t = new H0(); t.mode = EffectMode.ENFORCE; t.eligible(); MitigationIntent a = t.captured.get(0); t.actor.onCaptureReceipt(a.actionId(), true, "c", T0 + 5 * S / 10);
        t.actor.onTeleportSent(5, new Vector3(100, 80, 100), 0, T0 + 55 * S / 100);
        assertEquals(IntentState.IDLE, t.actor.state()); assertEquals(Reason.SUPERSEDED_BY_FOREIGN_TELEPORT, t.actor.status().recentOutcomes().get(0).reason()); assertEquals(Terminal.SUPERSEDED, t.actor.status().recentOutcomes().get(0).terminal());
        // velocity race: legitimate external motion registered before application
        H0 v = new H0(); v.mode = EffectMode.ENFORCE; v.eligible(); MitigationIntent b = v.captured.get(0); v.actor.onCaptureReceipt(b.actionId(), true, "c", T0 + 5 * S / 10);
        v.actor.onExternalMotion(T0 + 55 * S / 100);
        assertEquals(IntentState.IDLE, v.actor.state()); assertEquals(Reason.UNRESOLVED_EXTERNAL_MOTION, v.actor.status().recentOutcomes().get(0).reason());
        // world change: the old world's correction is never applied in the new one; incompatible anchors are dropped
        H0 w = new H0(); w.mode = EffectMode.ENFORCE; w.eligible(); w.actor.onEpochChange(1, T0 + 55 * S / 100);
        assertEquals(IntentState.IDLE, w.actor.state()); assertEquals(Reason.WORLD_CHANGED, w.actor.status().recentOutcomes().get(0).reason()); assertTrue(w.actor.anchors().isEmpty());
        // session end: the intent terminates; nothing of the old session survives
        H0 e = new H0(); e.mode = EffectMode.ENFORCE; e.eligible(); e.actor.onSessionEnd(false, T0 + 55 * S / 100);
        assertEquals(IntentState.IDLE, e.actor.state()); assertEquals(Reason.SESSION_ENDED, e.actor.status().recentOutcomes().get(0).reason()); assertTrue(e.actor.anchors().isEmpty());
    }
    @Test void deadlinesAndTimeoutsEndEveryIntentExactlyOnce() {
        H0 c = new H0(); c.mode = EffectMode.ENFORCE; c.eligible(); c.actor.onTick(T0 + 4 * S / 10 + MitigationBounds.DEFAULT.intentDeadlineNanos() + 1);
        assertEquals(Reason.CAPTURE_DEADLINE_MISS, c.actor.status().recentOutcomes().get(0).reason(), "no durable receipt within 500 ms: the effect is skipped");
        H0 q = new H0(); q.mode = EffectMode.ENFORCE; q.eligible(); MitigationIntent it = q.captured.get(0); q.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100);
        q.actor.onTick(it.deadlineNanos() + 1); assertEquals(Reason.DEADLINE_EXPIRED, q.actor.status().recentOutcomes().get(0).reason());
        H0 u = new H0(); u.mode = EffectMode.ENFORCE; u.captureAvailable = false; u.eligible();
        assertEquals(Reason.CAPTURE_UNAVAILABLE, u.actor.status().recentOutcomes().get(0).reason()); assertTrue(u.queued.isEmpty());
        H0 s = new H0(); s.mode = EffectMode.ENFORCE; s.sinkOpen = false; s.eligible(); MitigationIntent x = s.captured.get(0); s.actor.onCaptureReceipt(x.actionId(), true, "c", T0 + 45 * S / 100);
        assertEquals(Reason.QUEUE_SATURATED, s.actor.status().recentOutcomes().get(0).reason());
        for (H0 h : List.of(c, q, u, s)) { assertEquals(IntentState.IDLE, h.actor.state()); assertEquals(1, h.actor.status().recentOutcomes().size(), "exactly one terminal outcome"); }
    }
    @Test void threeFailedCorrectionsWithin30SecondsDisableSetbacksForTheSession() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE; long t = T0; int seq = 0;
        for (int round = 0; round < 3; round++) {
            h.anchor(seq + 50, t, ANCHOR); for (int i = 1; i <= 4; i++) h.feed(invalid(h.key, 0, seq + 100L * i, seq + 100L * i + 10, t + i * S / 10, H, 0.2, 0));
            MitigationIntent it = h.captured.get(h.captured.size() - 1); h.actor.onCaptureReceipt(it.actionId(), true, "c", t + 45 * S / 100);
            h.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.STARTED, null, t + 46 * S / 100);
            h.actor.onTeleportSent(round, ANCHOR, 0, t + 46 * S / 100);
            h.actor.onTick(t + 46 * S / 100 + MitigationBounds.DEFAULT.confirmTimeoutNanos() + 1); // never confirmed
            assertEquals(Reason.CONFIRM_TIMEOUT, h.actor.status().recentOutcomes().get(h.actor.status().recentOutcomes().size() - 1).reason());
            t += 7 * S; seq += 1000;
        }
        assertTrue(h.actor.disabled()); assertEquals(1, h.metrics.sessionsDisabled.sum());
        h.anchor(seq + 50, t, ANCHOR); MitigationActor.Decision d = null; for (int i = 1; i <= 4; i++) d = h.feed(invalid(h.key, 0, seq + 100L * i, seq + 100L * i + 10, t + i * S / 10, H, 0.2, 0));
        assertEquals(Reason.SESSION_DISABLED, d.reason(), "a loop breaker never escalates, it stops"); assertEquals(3, h.captured.size());
    }
    @Test void reprocessingTheSameEvidenceNeverSchedulesASecondAction() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE; h.anchor(50, T0, ANCHOR);
        List<ValidationRecord> groups = new ArrayList<>(); for (int i = 1; i <= 4; i++) groups.add(h.group(i, T0 + i * S / 10));
        for (ValidationRecord r : groups) h.feed(r);
        MitigationIntent it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onTick(it.deadlineNanos() + 1); // expired, back to IDLE
        for (ValidationRecord r : groups) h.feed(r); // re-delivery: Phase 9 refuses older ranges as OUT_OF_ORDER and the newest as DUPLICATE - neither counts
        assertEquals(1, h.captured.size(), "idempotent: the same evidence is one action at most");
        assertEquals(4, h.ledger.snapshot().duplicates() + h.ledger.snapshot().outOfOrder()); assertEquals(1, h.ledger.snapshot().duplicates());
    }
    @Test void anInternalErrorNeverProducesACorrection() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE; h.anchor(50, T0, ANCHOR);
        for (int i = 1; i <= 3; i++) h.feed(h.group(i, T0 + i * S / 10));
        h.entityProbe = p -> { throw new IllegalStateException("probe failure"); };
        var d = h.feed(h.group(4, T0 + 4 * S / 10));
        assertEquals(Reason.INTERNAL_ERROR, d.reason()); assertTrue(h.captured.isEmpty()); assertEquals(1, h.metrics.internalErrors.sum());
    }
    @Test void theSideChainDecidesOnItsOwnEvidenceButTargetsLiveAnchors() {
        H0 h = new H0(); h.mode = EffectMode.OBSERVE_ONLY; h.anchor(50, T0, ANCHOR);
        EvidenceLedger side = new EvidenceLedger(h.key); MitigationActor.Decision d = null;
        for (int i = 1; i <= 4; i++) { EvidenceLedger.Outcome o = side.ingest(invalid(h.key, 0, 2000L * i, 2000L * i + 1, T0 + i * S / 10, H, 0.17, 0)); d = h.actor.onEvidence(o, side.lastEvent(), side.snapshot(), true, h.cs(T0 + i * S / 10, 2000L * i)); }
        assertTrue(d.sideChain()); assertEquals(Reason.MODE_OBSERVE_ONLY, d.reason()); assertEquals(50, d.target().sequence(), "the live chain's certified anchor");
        assertEquals(0, h.actor.status().liveChain(), "the live chain never sees side-chain evidence");
    }
    @Test void aRefusingGlobalGuardSuppressesBeforeAnyCapture() {
        H0 h = new H0(); h.mode = EffectMode.ENFORCE; h.gateOpen = false;
        var d = h.eligible();
        assertEquals(DecisionOutcome.SUPPRESSED, d.outcome(), d.describe()); assertEquals(Reason.GLOBAL_GUARD, d.reason());
        assertEquals(1, h.proposals, "counted once, before capture"); assertTrue(h.captured.isEmpty() && h.queued.isEmpty(), "no capsule, no intent"); assertEquals(IntentState.IDLE, h.actor.state());
        H0 o = new H0(); o.eligible(); assertEquals(0, o.proposals, "OBSERVE_ONLY never reaches the proposal guard");
    }
    /** MITIGATION G2: "Explicit correlated fault tests must show suspension before individual three-failure loop breakers fire." */
    @Test void aCorrelatedFaultSuspendsGloballyBeforeAnyLoopBreaker() {
        GlobalProposalGuard guard = new GlobalProposalGuard(1024); List<H0> sessions = new ArrayList<>();
        for (int k = 0; k < 6; k++) { H0 h = new H0(); h.mode = EffectMode.ENFORCE; UUID id = new UUID(0x6000L, k); h.gateDelegate = (s, n) -> guard.propose(id, 100, n); sessions.add(h); }
        List<MitigationActor.Decision> ds = new ArrayList<>(); for (H0 h : sessions) ds.add(h.eligible()); // the same instant for every session: a correlated fault
        assertEquals(DecisionOutcome.PROPOSED, ds.get(0).outcome()); assertEquals(DecisionOutcome.PROPOSED, ds.get(1).outcome());
        for (int k = 2; k < 6; k++) assertEquals(Reason.GLOBAL_GUARD, ds.get(k).reason(), "the third distinct session within 1 s trips the burst gate: " + ds.get(k).describe());
        assertTrue(guard.tripped(), guard.describe());
        assertEquals(2, sessions.stream().mapToInt(h -> h.captured.size()).sum(), "only the two admitted proposals ever request a capsule");
        for (H0 h : sessions) { assertFalse(h.actor.disabled(), "no per-session loop breaker was needed"); assertEquals(0, h.metrics.sessionsDisabled.sum()); assertEquals(0, h.actor.status().recentFailures()); }
        // latched: a later, unrelated session is refused too until an explicit re-enable
        H0 later = new H0(); later.mode = EffectMode.ENFORCE; later.gateDelegate = (s, n) -> guard.propose(new UUID(0x6000L, 99), 100, n + 60 * S);
        assertEquals(Reason.GLOBAL_GUARD, later.eligible().reason());
    }
}
