package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.mitigation.AnchorAdmission;
import me.nicolas.papea2ac.mitigation.MitigationActor;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.model.HealthLatch;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.simulation.MovementValidation;
import me.nicolas.papea2ac.simulation.MovementValidationRequest;
import me.nicolas.papea2ac.simulation.MovementValidationResult;
import me.nicolas.papea2ac.simulation.TimingReconstruction;
import me.nicolas.papea2ac.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
/**
 * Phase 10 core composition for one session (the session's shard thread; like {@link EvidenceRunner}): extracts the safe-anchor facts of
 * every completed live Phase 8 result, builds the current-state facts a mitigation decision needs, and routes the Phase 9 outcomes, the
 * timing facts (corrections sent/confirmed, external motion, epoch changes, tick ends) and the main thread's ordered outcome records to the
 * session's {@link MitigationActor}. It never throws into the validation pipeline (failures are contained and counted) and it never
 * applies anything: an intent leaves only through the engine's bounded main-thread queue.
 */
public final class MitigationRunner {
    private final SessionKey key; private final MitigationActor actor; private final HealthLatch health; private final Supplier<MitigationActor.EffectMode> mode; private final Supplier<String> worldKey;
    private final MitigationActor.Metrics metrics;
    private BlockDisputeLedger disputes;
    private volatile MitigationActor.Status status;

    public MitigationRunner(SessionKey key, MitigationActor actor, HealthLatch health, Supplier<MitigationActor.EffectMode> mode, Supplier<String> worldKey, MitigationActor.Metrics metrics) {
        this.key = Objects.requireNonNull(key); this.actor = Objects.requireNonNull(actor); this.health = Objects.requireNonNull(health); this.mode = Objects.requireNonNull(mode); this.worldKey = Objects.requireNonNull(worldKey); this.metrics = Objects.requireNonNull(metrics);
        status = actor.status();
    }
    void attachDisputes(BlockDisputeLedger d) { this.disputes = d; }
    public MitigationActor.Status status() { return status; }
    MitigationActor actor() { return actor; }

    /** A completed LIVE result: anchor admission first (it describes the state after this transition), then the Phase 9 outcome. */
    void onLive(MovementValidationResult r, EvidenceLedger.Outcome outcome, EvidenceRunner evidence, TimingEvent.MovementObserved m, MovementValidationRequest req,
                TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch, int pendingInfluences, float yaw, float pitch, Vector3 position) {
        try {
            actor.onAnchorFacts(facts(r, m, req, timing, context, worldEpoch, yaw, pitch), worldEpoch, view);
            if (outcome != null) actor.onEvidence(outcome, evidence.lastEvent(false).filter(e -> outcome == EvidenceLedger.Outcome.POSITIVE_EVENT), evidence.snapshot(false), false, current(m, timing, context, view, worldEpoch, pendingInfluences, position));
        } catch (RuntimeException ex) { metrics.internalErrors.increment(); }
        publish();
    }
    /** A completed SIDE-chain result (controlled qualification): only the side policy is fed; targets still come from the LIVE anchors. */
    void onSide(EvidenceLedger.Outcome outcome, EvidenceRunner evidence, TimingEvent.MovementObserved liveTarget, TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch, int pendingInfluences, Vector3 position) {
        try { if (outcome != null) actor.onEvidence(outcome, evidence.lastEvent(true).filter(e -> outcome == EvidenceLedger.Outcome.POSITIVE_EVENT), evidence.snapshot(true), true, current(liveTarget, timing, context, view, worldEpoch, pendingInfluences, position)); }
        catch (RuntimeException ex) { metrics.internalErrors.increment(); }
        publish();
    }
    void onUnanchored(long nanos) { try { actor.onUnanchored(nanos); } catch (RuntimeException ex) { metrics.internalErrors.increment(); } publish(); }
    void onTick(long nanos) { try { actor.onTick(nanos); } catch (RuntimeException ex) { metrics.internalErrors.increment(); } publish(); }
    /** The timing facts that concern a correction in flight. */
    void onTiming(TimingEvent e) {
        try {
            switch (e) {
                case TimingEvent.TeleportSent t -> actor.onTeleportSent(t.teleportId(), t.position(), t.relativeMask(), t.nanos());
                case TimingEvent.TeleportConfirmed c -> actor.onTeleportConfirmed(c.teleportId(), c.nanos());
                case TimingEvent.ExternalMotionSent x -> actor.onExternalMotion(x.nanos());
                case TimingEvent.Discontinuity d -> actor.onEpochChange(d.newWorldEpoch(), d.nanos());
                default -> { }
            }
        } catch (RuntimeException ex) { metrics.internalErrors.increment(); }
        publish();
    }
    /** The main thread's ordered outcome record ({@code value} = "{shortId}:S" started, ":X" sent, ":R:{reason ordinal}" rejected). */
    void onPlatformRecord(String value, long nanos) {
        try {
            int c = value.indexOf(':'); if (c != 16) return;
            String id = value.substring(0, 16), rest = value.substring(17);
            if (rest.equals("S")) actor.onPlatformOutcome(id, MitigationActor.PlatformOutcome.STARTED, null, nanos);
            else if (rest.equals("X")) actor.onPlatformOutcome(id, MitigationActor.PlatformOutcome.SENT, null, nanos);
            else if (rest.startsWith("R:")) { int ord = Integer.parseInt(rest.substring(2)); Reason r = ord >= 0 && ord < Reason.values().length ? Reason.values()[ord] : Reason.INTERNAL_ERROR; actor.onPlatformOutcome(id, MitigationActor.PlatformOutcome.REJECTED, r, nanos); }
        } catch (RuntimeException ex) { metrics.internalErrors.increment(); }
        publish();
    }
    void onCaptureReceipt(String actionId, boolean durable, String receipt, long nanos) { try { actor.onCaptureReceipt(actionId, durable, receipt, nanos); } catch (RuntimeException ex) { metrics.internalErrors.increment(); } publish(); }
    /** An ordered capture record naming the active intent by its short id (the receipt identity is the durable capsule the capture service promoted). */
    void onCaptureReceiptShort(String shortId, boolean durable, long nanos) {
        try { actor.activeIntent().filter(i -> i.shortId().equals(shortId)).ifPresent(i -> actor.onCaptureReceipt(i.actionId(), durable, durable ? "capsule:" + shortId : "failed", nanos)); }
        catch (RuntimeException ex) { metrics.internalErrors.increment(); }
        publish();
    }
    void onSessionEnd(boolean shutdown, long nanos) { try { actor.onSessionEnd(shutdown, nanos); } catch (RuntimeException ex) { metrics.internalErrors.increment(); } publish(); }

    // ------------------------------------------------------------------------------------------------ fact extraction
    AnchorAdmission.Facts facts(MovementValidationResult r, TimingEvent.MovementObserved m, MovementValidationRequest req, TimingLedger.View timing, MovementContextLedger.View context, long worldEpoch, float yaw, float pitch) {
        List<Vector3> positions = new ArrayList<>(); boolean grounded = true, crouching = false, onFoot = true, finite = true; int pendingInf = 0;
        for (TimingReconstruction.Node n : r.next().nodes()) {
            PhysicsState s = n.state();
            positions.add(new Vector3(s.position().x(), s.position().y(), s.position().z()));
            grounded &= s.onGround(); crouching |= s.pose() == me.nicolas.papea2ac.collision.PlayerBox.Pose.CROUCHING;
            onFoot &= s.pose() == me.nicolas.papea2ac.collision.PlayerBox.Pose.STANDING || s.pose() == me.nicolas.papea2ac.collision.PlayerBox.Pose.CROUCHING;
            finite &= Double.isFinite(s.position().x()) && Double.isFinite(s.position().y()) && Double.isFinite(s.position().z()) && Double.isFinite(s.velocity().x()) && Double.isFinite(s.velocity().y()) && Double.isFinite(s.velocity().z());
            pendingInf += n.pendingInfluences().size();
        }
        return new AnchorAdmission.Facts(key, worldEpoch, m.sequence(), m.nanos(), r.classification() == MovementValidation.Classification.VALID, r.completeness().all(), r.nextFrontierComplete(),
            positions, grounded, crouching, onFoot, finite, unresolvedTeleports(timing), pendingInf, req.worldDisputed(), context.locomotionSupported(), yaw, pitch);
    }
    static int unresolvedTeleports(TimingLedger.View timing) { int n = 0; for (TimingLedger.PendingTeleport t : timing.teleports()) if (!t.confirmed() && !t.superseded()) n++; return n; }

    private MitigationActor.CurrentState current(TimingEvent.MovementObserved m, TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch, int pendingInfluences, Vector3 position) {
        HealthLatch.State h = health.get();
        final long nanos = m.nanos();
        return new MitigationActor.CurrentState(nanos, m.sequence(), key, worldEpoch, worldKey.get(), position, h.healthy(), h.generation(), unresolvedTeleports(timing), pendingInfluences, context.locomotionSupported(),
            p -> disputes != null && disputes.disputed((int) Math.floor(p.x() - 1.0), (int) Math.floor(p.y() - 1.0), (int) Math.floor(p.z() - 1.0), (int) Math.floor(p.x() + 1.0), (int) Math.floor(p.y() + 2.0), (int) Math.floor(p.z() + 1.0), nanos),
            p -> !context.entitiesComplete() || context.anyRelevantEntityNear(p.x(), p.y(), p.z(), MovementValidationRunner.ENTITY_RADIUS_H, MovementValidationRunner.ENTITY_RADIUS_V),
            view, mode.get());
    }
    private void publish() { status = actor.status(); }
}
