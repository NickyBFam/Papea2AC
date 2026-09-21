package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.PhysicsSimulator;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.simulation.MovementValidation;
import me.nicolas.papea2ac.simulation.MovementValidationRequest;
import me.nicolas.papea2ac.simulation.MovementValidationResult;
import me.nicolas.papea2ac.simulation.MovementValidator;
import me.nicolas.papea2ac.simulation.ReconstructionRequest;
import me.nicolas.papea2ac.simulation.TimingBudget;
import me.nicolas.papea2ac.simulation.TimingReconstruction;
import me.nicolas.papea2ac.simulation.TimingRequest;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.version.TimingProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.WorldView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
/**
 * Phase 8 live movement validation for ONE session, owned by the session's shard thread (THREADING_MODEL.md T1: no main-thread
 * pipeline, no blocking). It is fed the session's timing events after the TimingLedger applied them, keeps the chain state the
 * validator needs (the union frontier and its completeness, the anchor, the event slice since the anchor, the influences in scope),
 * builds the physics environment from the observer world and the player-owned context ledger, runs {@link MovementValidator}
 * once per transmission and publishes a bounded immutable {@link ValidationStatus}. Nothing is enforced, accumulated or scored.
 *
 * <p>Anchoring (the chain needs a supported exact start): a position correction's application point (the join correction and every
 * later absolute correction: position and velocity are server-set; ground flag both ways, pose standing/crouching/crawling, no jump
 * delay and no fall distance as sound over-approximations) or an observed rest (a 20-tick position reminder at the same position
 * with the ground claim, confirmed as the simulator's resting fixed point for each supported pose). Until anchored every
 * transmission is UNCERTAIN / START_UNAVAILABLE. A world change, a loss-class coverage break or a chain that lost every
 * continuation drops the anchor; the frontier-completeness flag travels with the chain (an INVALID after an incomplete step is
 * forbidden by the validator).
 *
 * <p>Controlled impossible-movement qualification (Phase 8 brief 54): {@link #inject} starts a SIDE chain from a copy of the live
 * chain state; at each following client tick end it synthesizes one transmission at the previous transmitted position plus the
 * requested per-tick offset and validates it exactly like a real one. The live chain, the ledgers and the timeline never see the
 * synthetic packets; results are reported in the status as injected. The side chain lives in its own sequence space (every real
 * sequence doubled, the synthetic transmission just before its tick end) so that it never collides with the live numbering; it only
 * keeps the events that stay meaningful next to synthetic positions (client tick ends and rotation-only transmissions; markers are
 * dropped, which only widens the timing union) and it ends as soon as the live session sends a correction or an influence, changes
 * world or breaks coverage. A failure inside the side chain ends the side chain and never touches the live chain.
 */
public final class MovementValidationRunner {
    public static final int MAX_SLICE = 1024, RECENT = 48, MAX_INJECTION_TICKS = 40;
    /** The physics-relevant entity radius (blocks) inside which the client-side push/collision neighbourhood is UNKNOWN: interpolated entity positions lag up to a few ticks, a sprinting player covers ~0.3/tick. */
    static final double ENTITY_RADIUS_H = 4.0, ENTITY_RADIUS_V = 4.0;

    /** Engine-wide bounded counters (fixed labels, no UUIDs). */
    public static final class Metrics {
        /** Optional diagnostic sink for every live INVALID (full description; set by the platform to its logger, P8-F7): a live INVALID is never lost to a bounded ring again. Never a verdict consumer. */
        public volatile java.util.function.Consumer<String> invalidSink;
        public final LongAdder requests = new LongAdder(), valid = new LongAdder(), uncertain = new LongAdder(), invalid = new LongAdder(), uncertainSearchLimit = new LongAdder(), uncertainUnsupported = new LongAdder(), uncertainWorldIncomplete = new LongAdder(),
            uncertainExternalMotion = new LongAdder(), uncertainUnanchored = new LongAdder(), uncertainOther = new LongAdder(), timingBranches = new LongAdder(), reconstructions = new LongAdder(), evaluations = new LongAdder(), anchors = new LongAdder(), anchorsLost = new LongAdder(), internalErrors = new LongAdder(), injectedRequests = new LongAdder();
    }

    private static final class Chain {
        TimingReconstruction.Frontier frontier; boolean complete; String anchorKind = "none"; long anchorSequence, anchorNanos; boolean anchorInTick = true;
        Vector3 lastSent; float lastYaw; boolean lastGround; long lastPositionSequence, tickEndsSincePosition;
        final List<TimingEvent> slice = new ArrayList<>(); final Map<String, TimingEvent.ExternalMotionSent> influences = new LinkedHashMap<>();
        int transitions, valid, uncertain, invalid;
        Chain copy() { Chain c = new Chain(); c.frontier = frontier; c.complete = complete; c.anchorKind = anchorKind; c.anchorSequence = anchorSequence; c.anchorNanos = anchorNanos; c.anchorInTick = anchorInTick; c.lastSent = lastSent; c.lastYaw = lastYaw; c.lastGround = lastGround; c.lastPositionSequence = lastPositionSequence; c.tickEndsSincePosition = tickEndsSincePosition; c.slice.addAll(slice); c.influences.putAll(influences); return c; }
        boolean anchored() { return frontier != null && !frontier.isEmpty(); }
    }
    private final SessionKey key; private final MovementValidator validator; private final PhysicsSimulator simulator; private final CollisionEngine collision; private final BlockBehaviorQuery behaviors; private final TimingProfile profile; private final Metrics metrics;
    private final Chain live = new Chain();
    private Chain side; private int sideTicksLeft, sideDone; private Vector3 sideStep; private final ArrayDeque<String> recent = new ArrayDeque<>(), injected = new ArrayDeque<>(), notable = new ArrayDeque<>();
    private MovementValidationResult last; private MovementContextLedger.View lastContext; private boolean notablePropagated; private String anchorDiagnosis = "";
    private Thread owner;
    private volatile ValidationStatus status;

    public MovementValidationRunner(SessionKey key, MovementValidator validator, PhysicsSimulator simulator, CollisionEngine collision, BlockBehaviorQuery behaviors, TimingProfile profile, Metrics metrics) {
        this.key = Objects.requireNonNull(key); this.validator = Objects.requireNonNull(validator); this.simulator = Objects.requireNonNull(simulator); this.collision = Objects.requireNonNull(collision); this.behaviors = Objects.requireNonNull(behaviors); this.profile = Objects.requireNonNull(profile); this.metrics = Objects.requireNonNull(metrics);
        publish();
    }
    private void assertOwner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("validation runner ownership"); }
    public ValidationStatus status() { return status; }

    /** Feeds one timing event (already applied to the timing ledger) with the session's current published ledger views and the observer world. Never throws: a failure is contained as UNCERTAIN / INTERNAL_ERROR and the chain re-anchors. */
    public void onEvent(TimingEvent e, TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch) {
        assertOwner();
        try { handle(e, timing, context, view, worldEpoch); }
        catch (RuntimeException ex) {
            metrics.internalErrors.increment(); dropAnchor("internal error " + ex.getClass().getSimpleName());
            String msg = ex.getMessage() == null ? "" : ex.getMessage(); if (msg.length() > 120) msg = msg.substring(0, 120);
            recent.addLast("seq=" + e.sequence() + " UNCERTAIN [INTERNAL_ERROR] " + ex.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg)); while (recent.size() > RECENT) recent.removeFirst();
            publish();
        }
    }
    private void handle(TimingEvent e, TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch) {
        switch (e) {
            case TimingEvent.Discontinuity d -> { dropAnchor("world change"); live.slice.clear(); live.influences.clear(); endSide("world change"); }
            case TimingEvent.CoverageBreak b -> { dropAnchor("coverage break " + b.reason()); live.slice.clear(); live.influences.clear(); endSide("coverage break"); }
            case TimingEvent.ServerTick t -> { return; /* recorded by the ledger; not an alignment source, nothing to publish */ }
            case TimingEvent.WriteResolved w -> { return; /* P8-F1: write outcomes are resolved by the ledger (teleport/velocity write failures reach the request through its pending-teleport and influence views), never part of the slice */ }
            default -> {
                live.slice.add(e); if (live.slice.size() > MAX_SLICE) { dropAnchor("event slice bound"); live.slice.clear(); live.influences.clear(); }
                if (e instanceof TimingEvent.ExternalMotionSent m) { live.influences.put(m.id(), m); endSide("live influence " + m.id()); }
                if (e instanceof TimingEvent.TeleportSent || e instanceof TimingEvent.TeleportConfirmed) endSide("live correction");
                if (e instanceof TimingEvent.ClientTickEnd end) { live.tickEndsSincePosition++; sideTick(end, timing, context, view, worldEpoch); }
                if (e instanceof TimingEvent.MovementObserved m) { if (side != null && !m.hasPosition() && m.hasRotation()) side.slice.add(new TimingEvent.MovementObserved(2 * m.sequence(), m.nanos(), false, m.position(), true, m.rotation(), m.onGround())); movement(m, timing, context, view, worldEpoch); }
            }
        }
        lastContext = context; publish();
    }
    private void dropAnchor(String why) { if (live.anchored()) metrics.anchorsLost.increment(); live.frontier = null; live.complete = false; live.anchorKind = "none (" + why + ")"; }

    private void movement(TimingEvent.MovementObserved m, TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch) {
        PhysicsEnvironment env = environment(context, view, worldEpoch, m.hasPosition() ? m.position() : live.lastSent);
        if (!live.anchored()) {
            anchor(m, timing, context, env);
            live.slice.clear(); if (live.anchored()) { metrics.anchors.increment(); live.influences.clear(); }
            else { metrics.requests.increment(); metrics.uncertain.increment(); metrics.uncertainUnanchored.increment(); remember("seq=" + m.sequence() + " UNCERTAIN [START_UNAVAILABLE] not anchored", null); anchorDiagnosis = anchorDiagnosis(m, context, env); }
            track(m); return;
        }

        if (live.lastSent == null) { track(m); return; }
        TimingRequest tr = new TimingRequest(new ArrayList<>(live.slice), live.anchorSequence, live.anchorNanos, live.anchorInTick, live.lastSent, live.lastYaw, m, new ArrayList<>(live.influences.values()), timing.teleports(), timing.rtt(), timing.tickEndCapable(), timing.counters().coverageBreaks() > 0, timing.worldEpoch(), m.nanos(), profile, TimingBudget.DEFAULT);
        MovementValidationRequest req = new MovementValidationRequest(tr, live.frontier, env, ReconstructionRequest.SearchBudget.DEFAULT, live.complete, true);
        MovementValidationResult r = context.locomotionSupported() ? validator.validate(req) : MovementValidator.refuse(req, MovementValidation.UncertainReason.UNSUPPORTED_LOCOMOTION, "flight/creative/spectator context: " + context.gameMode() + (context.flying() ? " flying" : context.canFly() ? " can fly" : ""));
        count(r, false); live.transitions++; switch (r.classification()) { case VALID -> live.valid++; case UNCERTAIN -> live.uncertain++; case INVALID -> live.invalid++; }
        last = r; remember(summary(m.sequence(), r), r);
        // chain
        if (r.next().isEmpty()) { dropAnchor(r.classification() + " without continuation"); live.slice.clear(); live.influences.clear(); }
        else {
            live.frontier = r.next(); live.complete = r.nextFrontierComplete(); live.anchorSequence = m.sequence(); live.anchorNanos = m.nanos(); live.anchorInTick = true; for (var w : r.witnesses()) if (w.teleportApplication()) live.anchorInTick = false; live.slice.clear();
            // P8-F4: an influence stays in scope only while some continuation still has it pending (applied or expired ones are retired; the ledger's horizon bounds the rest)
            Set<String> pending = new java.util.LinkedHashSet<>(); for (TimingReconstruction.Node n : live.frontier.nodes()) pending.addAll(n.pendingInfluences());
            live.influences.keySet().removeIf(id -> !pending.contains(id));
            live.influences.values().removeIf(inf -> m.nanos() - inf.nanos() > profile.applicationHorizonNanos());
            // an incomplete chain re-anchors at the next observed rest (an exact start again) instead of carrying the incompleteness until a correction
            if (!live.complete && live.influences.isEmpty() && restAnchor(m, context, env)) { metrics.anchors.increment(); remember("seq=" + m.sequence() + " rest re-anchor: the chain is complete again (" + live.anchorKind + ")", null); }
        }
        track(m);
    }
    /** Why the last unanchored transmission did not anchor (diagnostic only): the rest conditions and the fixed-point verdict per pose. */
    private String anchorDiagnosis(TimingEvent.MovementObserved m, MovementContextLedger.View context, PhysicsEnvironment env) {
        if (!m.hasPosition()) return "rest check: rotation-only transmission";
        StringBuilder sb = new StringBuilder("rest check: ground=").append(m.onGround()).append(" tickEndsSincePosition=").append(live.tickEndsSincePosition).append(" samePosition=").append(live.lastSent != null && m.position().equals(live.lastSent)).append(" locomotion=").append(context.locomotionSupported());
        if (m.onGround() && live.lastSent != null && m.position().equals(live.lastSent) && live.tickEndsSincePosition >= 19)
            for (PlayerBox.Pose pose : new PlayerBox.Pose[] { PlayerBox.Pose.STANDING, PlayerBox.Pose.CROUCHING, PlayerBox.Pose.SWIMMING }) sb.append(" | ").append(pose.name().toLowerCase(Locale.ROOT)).append(": ").append(MovementValidator.restFixedPointReason(simulator, env, m.position().x(), m.position().y(), m.position().z(), pose, m.hasRotation() ? m.rotation().yaw() : live.lastYaw));
        return sb.toString();
    }
    /** The rest condition and fixed point for an already anchored chain (see {@link #anchor}): the 20-tick reminder at the same position with the ground claim. */
    private boolean restAnchor(TimingEvent.MovementObserved m, MovementContextLedger.View context, PhysicsEnvironment env) {
        if (!m.hasPosition() || !m.onGround() || live.lastSent == null || live.tickEndsSincePosition < 19 || !context.locomotionSupported()) return false;
        if (m.position().x() != live.lastSent.x() || m.position().y() != live.lastSent.y() || m.position().z() != live.lastSent.z()) return false;
        float yaw = m.hasRotation() ? m.rotation().yaw() : live.lastYaw; // P8-F6
        List<TimingReconstruction.Node> nodes = new ArrayList<>();
        for (PlayerBox.Pose pose : new PlayerBox.Pose[] { PlayerBox.Pose.STANDING, PlayerBox.Pose.CROUCHING, PlayerBox.Pose.SWIMMING }) {
            PhysicsState s = MovementValidator.restFixedPoint(simulator, env, m.position().x(), m.position().y(), m.position().z(), pose, yaw);
            if (s != null && nodes.stream().noneMatch(n -> n.state().equals(s))) nodes.add(new TimingReconstruction.Node(s, Set.of(), "rest/" + pose.name().toLowerCase(Locale.ROOT)));
        }
        if (nodes.isEmpty()) return false;
        live.frontier = new TimingReconstruction.Frontier(nodes); live.complete = true; live.anchorKind = "rest (" + nodes.size() + " pose state" + (nodes.size() == 1 ? "" : "s") + ")"; live.anchorSequence = m.sequence(); live.anchorNanos = m.nanos(); live.anchorInTick = true;
        return true;
    }
    private void track(TimingEvent.MovementObserved m) {
        if (m.hasPosition()) { live.lastSent = m.position(); live.lastPositionSequence = m.sequence(); live.tickEndsSincePosition = 0; live.lastGround = m.onGround(); }
        if (m.hasRotation()) live.lastYaw = m.rotation().yaw();
    }
    /** Tries to anchor the chain at this transmission (correction application point first, then the rest fixed point). */
    private void anchor(TimingEvent.MovementObserved m, TimingLedger.View timing, MovementContextLedger.View context, PhysicsEnvironment env) {
        if (!m.hasPosition()) return;
        float yaw = m.hasRotation() ? m.rotation().yaw() : live.lastYaw; // P8-F6: the 20-tick reminder of a standing client is position-only (PosRot only when the view moved)
        // (1) the position echoed right after a confirmed absolute correction is the correction's application point
        for (TimingLedger.PendingTeleport pt : timing.teleports()) {
            if (!pt.confirmed() || pt.superseded() || pt.sent().relativeMask() != 0 || pt.confirmedSequence() > m.sequence() || pt.confirmedSequence() < live.lastPositionSequence) continue;
            Vector3 p = pt.sent().position(); if (p.x() != m.position().x() || p.y() != m.position().y() || p.z() != m.position().z()) continue;
            List<TimingReconstruction.Node> nodes = new ArrayList<>();
            Vec3d vel = new Vec3d(pt.sent().delta().x(), pt.sent().delta().y(), pt.sent().delta().z());
            for (PlayerBox.Pose pose : new PlayerBox.Pose[] { PlayerBox.Pose.STANDING, PlayerBox.Pose.CROUCHING, PlayerBox.Pose.SWIMMING }) for (boolean ground : new boolean[] { false, true }) {
                PhysicsState s = PhysicsState.resting(p.x(), p.y(), p.z(), pose, ground).withVelocity(vel);
                nodes.add(new TimingReconstruction.Node(s, Set.of(), "correction-" + pt.teleportId() + "/" + pose.name().toLowerCase(Locale.ROOT) + (ground ? "/ground" : "/air")));
            }
            live.frontier = new TimingReconstruction.Frontier(nodes); live.complete = true; live.anchorKind = "correction " + pt.teleportId(); live.anchorSequence = m.sequence(); live.anchorNanos = m.nanos(); live.anchorInTick = false; live.lastSent = m.position(); live.lastYaw = yaw;
            return;
        }
        // (2) an observed rest: the 20-tick reminder at the same position with the ground claim, a resting fixed point of the simulator
        if (!m.onGround() || live.lastSent == null || live.tickEndsSincePosition < 19) return;
        if (m.position().x() != live.lastSent.x() || m.position().y() != live.lastSent.y() || m.position().z() != live.lastSent.z()) return;
        if (!context.locomotionSupported()) return;
        List<TimingReconstruction.Node> nodes = new ArrayList<>();
        for (PlayerBox.Pose pose : new PlayerBox.Pose[] { PlayerBox.Pose.STANDING, PlayerBox.Pose.CROUCHING, PlayerBox.Pose.SWIMMING }) {
            PhysicsState s = MovementValidator.restFixedPoint(simulator, env, m.position().x(), m.position().y(), m.position().z(), pose, yaw);
            if (s != null && nodes.stream().noneMatch(n -> n.state().equals(s))) nodes.add(new TimingReconstruction.Node(s, Set.of(), "rest/" + pose.name().toLowerCase(Locale.ROOT)));
        }
        if (nodes.isEmpty()) return;
        live.frontier = new TimingReconstruction.Frontier(nodes); live.complete = true; live.anchorKind = "rest (" + nodes.size() + " pose state" + (nodes.size() == 1 ? "" : "s") + ")"; live.anchorSequence = m.sequence(); live.anchorNanos = m.nanos(); live.anchorInTick = true; live.lastSent = m.position(); live.lastYaw = yaw;
    }

    /** The physics environment as the client sees it, from the observer world and the context ledger; every unknown dimension is UNKNOWN, never a default. */
    PhysicsEnvironment environment(MovementContextLedger.View ctx, WorldView view, long worldEpoch, Vector3 around) {
        MovementProfile mp = simulator.profile();
        PhysicsEnvironment env = PhysicsEnvironment.of(view, worldEpoch, collision, behaviors, mp);
        if (!ctx.contextComplete()) env = env.withAttributes(PhysicsEnvironment.Attributes.ALL_UNKNOWN).withEffects(PhysicsEnvironment.Effects.UNKNOWN);
        else {
            PhysicsEnvironment.Attributes attrs = PhysicsEnvironment.Attributes.defaults(mp);
            for (Map.Entry<String, MovementContextLedger.AttributeValue> e : ctx.attributes().entrySet()) {
                if (mp.attribute(e.getKey()) == null) continue;
                List<MovementProfile.Modifier> mods = new ArrayList<>(); boolean ok = true;
                for (ContextEvent.ModifierValue mv : e.getValue().modifiers()) { try { mods.add(new MovementProfile.Modifier(mv.id(), mv.amount(), MovementProfile.Operation.valueOf(mv.operation()))); } catch (IllegalArgumentException ex) { ok = false; } }
                attrs = ok ? attrs.with(e.getKey(), PhysicsEnvironment.Attribute.known(e.getValue().base(), PhysicsEnvironment.Attribute.Provenance.SERVER_SENT, mods)) : attrs.with(e.getKey(), PhysicsEnvironment.Attribute.UNKNOWN);
            }
            List<PhysicsEnvironment.EffectInstance> effects = new ArrayList<>(); boolean effectsOk = true;
            for (Map.Entry<String, Integer> e : ctx.effects().entrySet()) { if (e.getValue() < 0 || e.getValue() > 255) { effectsOk = false; continue; } effects.add(new PhysicsEnvironment.EffectInstance(e.getKey(), e.getValue())); }
            env = env.withAttributes(attrs).withEffects(effectsOk ? new PhysicsEnvironment.Effects(PhysicsEnvironment.Knowledge.KNOWN, effects) : PhysicsEnvironment.Effects.UNKNOWN);
        }
        boolean entitiesKnown = ctx.entitiesComplete() && around != null && !ctx.anyRelevantEntityNear(around.x(), around.y(), around.z(), ENTITY_RADIUS_H, ENTITY_RADIUS_V);
        env = env.withEntities(entitiesKnown ? PhysicsEnvironment.EntityNeighborhood.NONE : PhysicsEnvironment.EntityNeighborhood.UNKNOWN);
        MovementContextLedger.Border b = ctx.border();
        env = env.withBorder(b.known() && !b.moving() ? new PhysicsEnvironment.WorldBorder(PhysicsEnvironment.Knowledge.KNOWN, b.minX(), b.minZ(), b.maxX(), b.maxZ()) : PhysicsEnvironment.WorldBorder.UNKNOWN);
        return env.withEquipment(PhysicsEnvironment.Equipment.UNKNOWN);
    }

    // ---------------------------------------------------------------- controlled injection (qualification only)
    /** Starts a side chain from the live chain: {@code ticks} synthetic transmissions, each {@code step} further than the previous one, one per following client tick end. */
    public synchronized String inject(Vector3 step, int ticks) {
        if (ticks < 1 || ticks > MAX_INJECTION_TICKS) return "REFUSED: 1.." + MAX_INJECTION_TICKS + " ticks";
        if (!live.anchored() || live.lastSent == null) return "REFUSED: the live chain is not anchored (" + live.anchorKind + ") - stand still for a second first";
        if (!live.influences.isEmpty()) return "REFUSED: an external motion is still pending on the live chain - wait until it settles";
        Chain c = live.copy(); c.slice.clear(); c.anchorSequence = 2 * live.anchorSequence;
        for (TimingEvent e : live.slice) { // the side sequence space: real sequences doubled; only tick ends and rotation-only transmissions carry over
            if (e instanceof TimingEvent.ClientTickEnd t) c.slice.add(new TimingEvent.ClientTickEnd(2 * t.sequence(), t.nanos()));
            else if (e instanceof TimingEvent.MovementObserved m && !m.hasPosition() && m.hasRotation()) c.slice.add(new TimingEvent.MovementObserved(2 * m.sequence(), m.nanos(), false, m.position(), true, m.rotation(), m.onGround()));
        }
        side = c; sideStep = step; sideTicksLeft = ticks; sideDone = 0; injected.clear();
        return "INJECTING " + ticks + " synthetic transmission(s) at +(" + step.x() + "," + step.y() + "," + step.z() + ") per tick from " + live.lastSent + " (side chain from anchor " + live.anchorKind + " seq=" + live.anchorSequence + "); the live chain and the timeline are untouched";
    }
    private void endSide(String why) { if (side == null) return; side = null; sideTicksLeft = 0; injected.addLast("side chain ended (" + why + ")"); while (injected.size() > RECENT) injected.removeFirst(); }
    private void sideTick(TimingEvent.ClientTickEnd end, TimingLedger.View timing, MovementContextLedger.View context, WorldView view, long worldEpoch) {
        Chain c = side; if (c == null || sideTicksLeft <= 0) return;
        try {
            Vector3 p = new Vector3(c.lastSent.x() + sideStep.x(), c.lastSent.y() + sideStep.y(), c.lastSent.z() + sideStep.z());
            TimingEvent.MovementObserved m = new TimingEvent.MovementObserved(2 * end.sequence() - 1, end.nanos() - 1, true, p, true, new Rotation(c.lastYaw, 0f), sideStep.y() == 0 && c.lastGround);
            c.slice.add(m);
            PhysicsEnvironment env = environment(context, view, worldEpoch, p);
            // no live correction or influence is in scope on the side chain (injection is refused while one is pending and the side chain ends when one arrives)
            TimingRequest tr = new TimingRequest(new ArrayList<>(c.slice), c.anchorSequence, c.anchorNanos, c.anchorInTick, c.lastSent, c.lastYaw, m, List.of(), List.of(), timing.rtt(), timing.tickEndCapable(), timing.counters().coverageBreaks() > 0, timing.worldEpoch(), m.nanos(), profile, TimingBudget.DEFAULT);
            MovementValidationRequest req = new MovementValidationRequest(tr, c.frontier, env, ReconstructionRequest.SearchBudget.DEFAULT, c.complete, true);
            MovementValidationResult r = context.locomotionSupported() ? validator.validate(req) : MovementValidator.refuse(req, MovementValidation.UncertainReason.UNSUPPORTED_LOCOMOTION, "flight/creative/spectator context");
            metrics.injectedRequests.increment(); sideTicksLeft--; sideDone++;
            injected.addLast("injected +" + (sideStep.x() != 0 || sideStep.z() != 0 ? String.format(Locale.ROOT, "h%.3f ", Math.sqrt(sideStep.x() * sideStep.x() + sideStep.z() * sideStep.z())) : "") + String.format(Locale.ROOT, "y%.3f", sideStep.y()) + " -> " + summary(end.sequence(), r)); while (injected.size() > RECENT) injected.removeFirst();
            if (r.next().isEmpty()) { endSide(r.classification() + " without continuation after " + sideDone + " transmission(s)"); return; }
            c.frontier = r.next(); c.complete = r.nextFrontierComplete(); c.anchorSequence = m.sequence(); c.anchorNanos = m.nanos(); c.anchorInTick = true; c.slice.clear(); c.slice.add(new TimingEvent.ClientTickEnd(2 * end.sequence(), end.nanos())); c.lastSent = p; c.lastGround = m.onGround();
            if (sideTicksLeft == 0) endSide("complete");
        } catch (RuntimeException ex) {
            metrics.internalErrors.increment(); String msg = ex.getMessage() == null ? "" : ex.getMessage(); if (msg.length() > 120) msg = msg.substring(0, 120);
            endSide("internal error " + ex.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg));
        }
    }

    // ---------------------------------------------------------------- bookkeeping
    private void count(MovementValidationResult r, boolean side) {
        metrics.requests.increment(); metrics.timingBranches.add(r.counts().timingHypotheses()); metrics.reconstructions.add(r.counts().reconstructions()); metrics.evaluations.add(r.work().evaluations());
        switch (r.classification()) {
            case VALID -> metrics.valid.increment();
            case INVALID -> metrics.invalid.increment();
            case UNCERTAIN -> {
                metrics.uncertain.increment();
                // one family per result, in this precedence: unsupported mechanic, incomplete world/context, unresolved external motion, then a pure search/query bound
                // (a search bound next to an incomplete world is a consequence of the incompleteness: the silent closure stops where the world stops), then no anchor
                var u = r.uncertainReasons();
                if (u.contains(MovementValidation.UncertainReason.UNSUPPORTED_FLUID) || u.contains(MovementValidation.UncertainReason.UNSUPPORTED_LOCOMOTION) || u.contains(MovementValidation.UncertainReason.UNSUPPORTED_CONTEXT) || u.contains(MovementValidation.UncertainReason.UNSUPPORTED_HORIZON) || u.contains(MovementValidation.UncertainReason.CLIMBABLE_CONTACT)) metrics.uncertainUnsupported.increment();
                else if (u.contains(MovementValidation.UncertainReason.WORLD_INCOMPLETE) || u.contains(MovementValidation.UncertainReason.CONTENT_NOT_RETAINED) || u.contains(MovementValidation.UncertainReason.ENTITY_NEIGHBORHOOD_UNKNOWN) || u.contains(MovementValidation.UncertainReason.ATTRIBUTE_UNKNOWN) || u.contains(MovementValidation.UncertainReason.EFFECT_UNKNOWN) || u.contains(MovementValidation.UncertainReason.WORLD_BORDER_UNKNOWN)) metrics.uncertainWorldIncomplete.increment();
                else if (u.contains(MovementValidation.UncertainReason.EXTERNAL_MOTION_UNRESOLVED) || u.contains(MovementValidation.UncertainReason.EXTERNAL_MOTION_EXPIRED) || u.contains(MovementValidation.UncertainReason.EXTERNAL_MOTION_OVERFLOW)) metrics.uncertainExternalMotion.increment();
                else if (u.contains(MovementValidation.UncertainReason.SEARCH_LIMIT) || u.contains(MovementValidation.UncertainReason.QUERY_LIMIT)) metrics.uncertainSearchLimit.increment();
                else if (u.contains(MovementValidation.UncertainReason.START_UNAVAILABLE)) metrics.uncertainUnanchored.increment();
                else metrics.uncertainOther.increment();
                if (u.contains(MovementValidation.UncertainReason.INTERNAL_ERROR)) metrics.internalErrors.increment();
            }
        }
    }
    private void remember(String line, MovementValidationResult r) {
        recent.addLast(line); while (recent.size() > RECENT) recent.removeFirst();
        if (r != null && r.classification() == MovementValidation.Classification.INVALID && metrics.invalidSink != null) { StringBuilder sb = new StringBuilder("live INVALID session=").append(key.epoch()).append(' ').append(line); for (String d : r.describe()) sb.append(System.lineSeparator()).append("  ").append(d); try { metrics.invalidSink.accept(sb.toString()); } catch (RuntimeException ignored) { /* diagnostics never fail validation */ } }
        // an INVALID, an UNCERTAIN (other than the unanchored repeat) or a VALID under an incomplete context never scrolls out of the diagnostic unnoticed (bounded ring)
        if (r != null && (r.classification() != MovementValidation.Classification.VALID || !r.completeness().all()) && !r.uncertainReasons().contains(MovementValidation.UncertainReason.START_UNAVAILABLE)) {
            boolean propagated = r.classification() == MovementValidation.Classification.VALID && r.completeness().missing().equals(List.of("frontier")); // inherited incompleteness only: keep the origin, record the propagation once per run
            if (propagated && notablePropagated) return;
            notablePropagated = propagated;
            notable.addLast(line); for (String d : r.describe()) notable.addLast("  " + d); while (notable.size() > 2 * RECENT) notable.removeFirst();
        }
    }
    static String summary(long seq, MovementValidationResult r) {
        StringBuilder sb = new StringBuilder("seq=").append(seq).append(' ').append(r.classification());
        if (!r.validReasons().isEmpty()) sb.append(' ').append(r.validReasons()); if (!r.uncertainReasons().isEmpty()) sb.append(' ').append(r.uncertainReasons()); if (!r.invalidReasons().isEmpty()) sb.append(' ').append(r.invalidReasons());
        sb.append(" hyp=").append(r.counts().timingHypotheses()).append(" legal=").append(r.counts().legalCandidates()).append(" recon=").append(r.counts().reconstructions()).append(" eval=").append(r.work().evaluations());
        if (!r.completeness().all()) sb.append(" missing=").append(r.completeness().missing());
        if (r.residual().present() && r.classification() != MovementValidation.Classification.VALID) sb.append(String.format(Locale.ROOT, " obs h=%.4f rise=%.4f legal h<=%.4f rise[%.4f,%.4f] excess h=%.4f v=%.4f", r.residual().observedHorizontal(), r.residual().observedRise(), r.residual().maxLegalHorizontal(), r.residual().minLegalRise(), r.residual().maxLegalRise(), r.residual().horizontalExcess(), r.residual().verticalExcess()));
        return sb.toString();
    }
    static String contextSummary(MovementContextLedger.View c) {
        if (c == null) return "context: none yet";
        return "context complete=" + c.contextComplete() + " mode=" + c.gameMode() + (c.flying() ? " flying" : "") + (c.canFly() ? " canFly" : "") + " attributes=" + c.attributes().size() + " effects=" + c.effects().size() + " border=" + (c.border().known() ? (c.border().moving() ? "moving" : "known") : "unknown")
            + " relevantEntities=" + c.relevantEntities().size() + "/" + c.trackedEntities() + (c.entitiesComplete() ? "" : " INCOMPLETE") + " breaks=" + c.breaks();
    }
    private void publish() {
        MovementValidationResult r = last; String ctxLine = contextSummary(lastContext) + (live.anchored() || anchorDiagnosis.isEmpty() ? "" : "\n" + anchorDiagnosis);
        status = new ValidationStatus(key.epoch(), live.anchored(), live.anchorKind, live.anchorSequence, live.complete, live.frontier == null ? 0 : live.frontier.nodes().size(), live.transitions, live.valid, live.uncertain, live.invalid,
            r == null ? "-" : r.classification().name(), r == null ? 0 : r.startSequence(), r == null ? 0 : r.endSequence(), r == null ? List.of() : r.describe(), List.copyOf(recent), List.copyOf(injected), List.copyOf(notable), ctxLine, side != null ? sideTicksLeft : 0);
    }
}
