package me.nicolas.papea2ac.combat;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TimelineLookup;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.EntityHistory;
import me.nicolas.papea2ac.world.EntityLookup;

/**
 * The per-session combat observation engine (Phase 11, descriptive). Owner-confined to the session shard; it consumes observations in the transport's
 * ordered sequence and resolves each attack's target against the observer {@link EntityHistory} at that exact position, so "target update N, attack
 * N+1" and "attack N, target update N+1" resolve differently no matter how close their callbacks ran. Bounded: the last {@link #MAX_ATTACKS} attack
 * contexts and {@link #MAX_INTERACTIONS} interactions (PERFORMANCE.md "64 pending/player"); nothing grows with session age.
 *
 * <p>It never produces evidence, a setback or any verdict; it depends on neither evidence nor mitigation.
 */
public final class CombatEngine {
    public static final int MAX_ATTACKS = 64, MAX_INTERACTIONS = 64, STATUS_LINES = 12;

    /** A non-attack entity interaction with its target resolution; never an attack. */
    public record InteractionRecord(SessionKey session, long worldEpoch, CombatObservation.Interaction observation, EntityLookup target) {
        public InteractionRecord { Objects.requireNonNull(session); Objects.requireNonNull(observation); Objects.requireNonNull(target); }
        public String describe() { return "I" + session.epoch() + ":" + observation.sequence() + " target#" + observation.targetId() + " " + observation.hand() + (observation.secondaryAction() ? " sneaking" : "") + " -> " + target.describe(); }
    }
    /** Fixed-cardinality engine-wide counters (no UUID, entity id, name, chunk or session label). */
    public static final class Metrics {
        public final LongAdder attacks = new LongAdder(), interactions = new LongAdder(), targetFound = new LongAdder(), targetIncomplete = new LongAdder(), contextsComplete = new LongAdder(),
            contextsIncomplete = new LongAdder(), rejectedOutOfOrder = new LongAdder(), internalErrors = new LongAdder();
        /** Entity lifecycle totals of CLOSED sessions (the core folds each session's entity-history counters in when it closes); live sessions report their own. */
        public final LongAdder entityGenerations = new LongAdder(), entityRemovals = new LongAdder(), entityReplacements = new LongAdder(), entityEpochResets = new LongAdder(), closedSessions = new LongAdder();
        public final EnumMap<EntityLookup.Status, LongAdder> byTargetStatus = new EnumMap<>(EntityLookup.Status.class);
        public Metrics() { for (EntityLookup.Status s : EntityLookup.Status.values()) byTargetStatus.put(s, new LongAdder()); }
    }
    /** Immutable published summary (safe from any thread): the recent contexts are immutable values, rendered only when a reader asks. */
    public record Status(SessionKey session, long attacks, long interactions, long targetFound, long targetNotFound, long complete, long incomplete, long rejected,
                         List<AttackContext> recentAttacks, List<InteractionRecord> recentInteractions, AttackContext lastAttack) {
        public Status { recentAttacks = List.copyOf(recentAttacks); recentInteractions = List.copyOf(recentInteractions); }
    }

    private final SessionKey session; private final CombatProfile profile; private final Metrics metrics;
    private final ArrayDeque<AttackContext> attacks = new ArrayDeque<>(); private final ArrayDeque<InteractionRecord> interactions = new ArrayDeque<>();
    private long lastSequence, attackCount, interactionCount, found, notFound, complete, incomplete, rejected;
    private Thread owner; private volatile Status status;

    public CombatEngine(SessionKey session, CombatProfile profile, Metrics metrics) {
        this.session = Objects.requireNonNull(session); this.profile = Objects.requireNonNull(profile); this.metrics = Objects.requireNonNull(metrics); publish(null);
    }
    private void owner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("combat engine ownership"); }

    /**
     * One ATTACK observation at its ordered position. {@code attackerBefore}: the Phase 2 timeline's latest record strictly before the attack;
     * {@code timing}: the Phase 7 ledger view as of the attack. Returns the context, or null for input out of transport order (counted, never applied).
     */
    public AttackContext onAttack(CombatObservation.Attack a, EntityHistory history, TimelineLookup attackerBefore, CombatProvenance.MovementValidation validation, TimingLedger.View timing) {
        owner();
        // P11-F6: a refused (out-of-order) observation republishes the status, so the rejected count is never stale
        if (a.sequence() <= lastSequence) { rejected++; metrics.rejectedOutOfOrder.increment(); publish(status.lastAttack()); return null; }
        lastSequence = a.sequence();
        EntityLookup target = history.lookupId(session, history.worldEpoch(), a.targetId(), a.sequence());
        CombatProvenance.Attacker attacker = CombatProvenance.Attacker.of(attackerBefore, validation == null ? CombatProvenance.MovementValidation.UNAVAILABLE : validation);
        CombatProvenance.Timing t = CombatProvenance.Timing.of(timing, a.sequence(), a.capturedNanos());
        AttackContext c = new AttackContext(session, history.worldEpoch(), a, target, attacker, t, CombatCompleteness.of(target, attacker, t), profile.id());
        attacks.addLast(c); while (attacks.size() > MAX_ATTACKS) attacks.pollFirst();
        attackCount++; metrics.attacks.increment(); metrics.byTargetStatus.get(target.status()).increment();
        if (target.found()) { found++; metrics.targetFound.increment(); } else { notFound++; metrics.targetIncomplete.increment(); }
        if (c.completeness().status() == CombatCompleteness.Status.COMPLETE) { complete++; metrics.contextsComplete.increment(); } else { incomplete++; metrics.contextsIncomplete.increment(); }
        publish(c);
        return c;
    }
    /** One non-attack entity interaction; resolved like an attack's target, recorded separately, never counted as an attack. */
    public InteractionRecord onInteraction(CombatObservation.Interaction i, EntityHistory history) {
        owner();
        if (i.sequence() <= lastSequence) { rejected++; metrics.rejectedOutOfOrder.increment(); publish(status.lastAttack()); return null; }
        lastSequence = i.sequence();
        InteractionRecord r = new InteractionRecord(session, history.worldEpoch(), i, history.lookupId(session, history.worldEpoch(), i.targetId(), i.sequence()));
        interactions.addLast(r); while (interactions.size() > MAX_INTERACTIONS) interactions.pollFirst();
        interactionCount++; metrics.interactions.increment();
        publish(status.lastAttack());
        return r;
    }
    /** Owner thread: the retained contexts, oldest first (replay export, tests). */
    public List<AttackContext> attacks() { owner(); return List.copyOf(attacks); }
    public List<InteractionRecord> interactions() { owner(); return List.copyOf(interactions); }
    public Status status() { return status; }
    public SessionKey session() { return session; }
    private void publish(AttackContext last) {
        List<AttackContext> ra = new ArrayList<>(STATUS_LINES); List<InteractionRecord> ri = new ArrayList<>(STATUS_LINES);
        int skip = Math.max(0, attacks.size() - STATUS_LINES); int k = 0; for (AttackContext c : attacks) if (k++ >= skip) ra.add(c);
        skip = Math.max(0, interactions.size() - STATUS_LINES); k = 0; for (InteractionRecord r : interactions) if (k++ >= skip) ri.add(r);
        status = new Status(session, attackCount, interactionCount, found, notFound, complete, incomplete, rejected, ra, ri, last);
    }
}
