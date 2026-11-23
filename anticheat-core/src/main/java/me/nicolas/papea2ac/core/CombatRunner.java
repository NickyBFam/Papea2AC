package me.nicolas.papea2ac.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import me.nicolas.papea2ac.combat.AttackContext;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.combat.CombatObservation;
import me.nicolas.papea2ac.combat.CombatProvenance;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.EntityHistory;
import me.nicolas.papea2ac.world.EntityLookup;
import me.nicolas.papea2ac.world.EntityObservation;
import me.nicolas.papea2ac.world.ObserverWorld;

/**
 * Per-session Phase 11 composition on the session shard: feeds the observer {@link EntityHistory} (world-owned) and the {@link CombatEngine} in the
 * transport's ordered sequence, with the world's clientbound coverage truth (the same break reasons, the same compromise report, sequence gaps),
 * and publishes an immutable diagnostics snapshot for other threads. Any internal failure is contained: it is counted and taints entity coverage
 * (never an exact answer after an error), and the session and the shard keep running. It never touches evidence or mitigation.
 */
public final class CombatRunner {
    /** Immutable values only (the lookups are immutable records): text is rendered on the reader's thread, never on the shard. */
    public record Diagnostics(EntityHistory.Status entities, List<EntityLookup> liveEntities, int moreLive, CombatEngine.Status combat, long internalErrors, String lastError) {
        public Diagnostics { liveEntities = List.copyOf(liveEntities); Objects.requireNonNull(lastError); }
    }
    private record EntitySnapshot(EntityHistory.Status status, List<EntityLookup> live, int more) {}
    public static final int LIVE_LINES = 16;
    /**
     * P11-F8: the entity snapshot and the nearest-32 deep-slot rebalance run once per this many server ticks (1 s), not every tick: publishing and
     * rendering per tick cost ~15 us and ~61 KB of garbage per session per tick. Attack contexts are read from the engine's own published status,
     * so they are never stale; the entity snapshot says which sequence it describes.
     */
    public static final int SNAPSHOT_TICKS = 20;

    private final SessionKey key; private final CombatProfile profile; private final EntityHistory entities; private final CombatEngine engine; private final PlayerTimeline timeline;
    private final Supplier<TimingLedger.View> timing; private final Supplier<ValidationStatus> validation; private final LongAdder engineErrors; private final CombatEngine.Metrics metrics;
    private volatile long errors; private volatile String lastError = ""; private volatile EntitySnapshot snapshot; private volatile boolean closed; private long ticks;

    public CombatRunner(SessionKey key, CombatProfile profile, ObserverWorld world, PlayerTimeline timeline, Supplier<TimingLedger.View> timing, Supplier<ValidationStatus> validation, CombatEngine.Metrics metrics) {
        this.key = key; this.profile = profile; this.timeline = timeline; this.timing = timing; this.validation = validation; this.engineErrors = metrics.internalErrors; this.metrics = metrics;
        entities = new EntityHistory(key, profile, world, 0); if (world != null) world.attachEntityHistory(entities);
        engine = new CombatEngine(key, profile, metrics);
        snapshot = new EntitySnapshot(entities.status(), List.of(), 0); // constructed off-shard: nothing owner-confined is touched here
    }
    /**
     * One observation, after the observer world applied it. {@code breakReason}: a clientbound coverage break at this observation (the world's truth:
     * a loss-class latch reason or a compromised capture position), or null; {@code breakOnly}: the observation itself was not applied (the world
     * recorded only the break), so neither is it here.
     */
    public void apply(Observation o, long now, int selfEntityId, long worldEpoch, String breakReason, boolean breakOnly) {
        try {
            long seq = o.sequence();
            if (worldEpoch > entities.worldEpoch()) entities.enterEpoch(worldEpoch, seq);
            int base = 0;
            if (breakReason != null) { entities.apply(key, new EntityObservation.CoverageBreak(breakReason.length() > 64 ? breakReason.substring(0, 64) : breakReason), seq, 0, now); base = 1; if (breakOnly) { publish(); return; } }
            var events = CombatObservationMapper.entity(o, selfEntityId, profile);
            for (int i = 0; i < events.size(); i++) entities.apply(key, events.get(i), seq, base + i, now);
            if (events.isEmpty() && base == 0) entities.observeSequence(key, seq, now);
            if (o.event() instanceof Observation.Tick && ++ticks % SNAPSHOT_TICKS == 0) {
                var m = timeline.currentState().movement();
                if (m.observedAtSequence() > 0) entities.rebalance(m.position().x(), m.position().y(), m.position().z());
                publish();
            }
            var c = CombatObservationMapper.combat(o);
            if (c.isPresent()) {
                switch (c.get()) {
                    case CombatObservation.Attack a -> engine.onAttack(a, entities, timeline.stateBefore(seq), provenance(validation.get()), timing.get());
                    case CombatObservation.Interaction i -> engine.onInteraction(i, entities);
                }
            }
        } catch (RuntimeException e) {
            errors++; engineErrors.increment(); String message = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            lastError = message.length() > 120 ? message.substring(0, 120) : message;
            // taint after the failure at a sub-index past any real bundle child of this observation, so the break orders after whatever was applied
            try { entities.apply(key, new EntityObservation.CoverageBreak("COMBAT_INTERNAL_ERROR"), o.sequence(), 1_000_000, now); } catch (RuntimeException ignored) { }
            try { publish(); } catch (RuntimeException ignored) { }
        }
    }
    static CombatProvenance.MovementValidation provenance(ValidationStatus v) {
        if (v == null) return CombatProvenance.MovementValidation.UNAVAILABLE;
        String kind = v.anchorKind() == null ? "none" : v.anchorKind(); String cls = v.lastClassification() == null ? "NONE" : v.lastClassification();
        return new CombatProvenance.MovementValidation(v.anchored(), kind.length() > 64 ? kind.substring(0, 64) : kind, cls.length() > 32 ? cls.substring(0, 32) : cls, v.lastEndSequence());
    }
    /**
     * Ends every incarnation (shard thread), publishes the final snapshot, and folds this session's entity lifecycle counters into the engine-wide
     * totals exactly once ({@link #closed()} then excludes it from the live sum, so nothing is counted twice).
     */
    public void close() {
        if (closed) return;
        try { entities.close(); } catch (RuntimeException ignored) { }
        try { publish(); var e = snapshot.status(); metrics.entityGenerations.add(e.generations()); metrics.entityRemovals.add(e.removals()); metrics.entityReplacements.add(e.replacements());
            metrics.entityEpochResets.add(e.epochResets()); metrics.closedSessions.increment(); } catch (RuntimeException ignored) { }
        closed = true;
    }
    /** Any thread: the session was closed and its lifecycle counters are already in the engine-wide totals. */
    public boolean closed() { return closed; }
    public EntityHistory entities() { return entities; }
    public CombatEngine engine() { return engine; }
    /** Any thread: the last entity snapshot (see {@link #SNAPSHOT_TICKS}) with the engine's current published status. */
    public Diagnostics diagnostics() { EntitySnapshot s = snapshot; return new Diagnostics(s.status(), s.live(), s.more(), engine.status(), errors, lastError); }
    private void publish() {
        List<EntityLookup> all = entities.liveEntities(LIVE_LINES);
        snapshot = new EntitySnapshot(entities.status(), all, entities.status().live() - all.size());
    }
    /** Renders a published snapshot for the diagnostic command (caller's thread; the snapshot is immutable). Descriptive only: no verdict, label, score or recommendation. */
    public static List<String> lines(Diagnostics d, boolean withEntities) {
        var out = new ArrayList<String>(); var e = d.entities(); var c = d.combat();
        out.add("combat session=" + c.session().epoch() + " worldEpoch=" + e.worldEpoch() + " entitySnapshot@seq " + e.lastSequence() + " (refreshed each " + SNAPSHOT_TICKS + " ticks; network state as SENT to this client; interpolation progress not modelled; CAPTURE-ONLY: no server acceptance claimed)");
        out.add("  entities live=" + e.live() + "/" + EntityHistory.MAX_RETAINED + " ended=" + e.ended() + " deep=" + e.deep() + "/" + EntityHistory.MAX_DEEP + " bytes=" + e.bytes()
            + " generations=" + e.generations() + " removals=" + e.removals() + " replaced=" + e.replacements() + " epochResets=" + e.epochResets() + " evictions=" + e.historyEvictions()
            + " promotions=" + e.promotions() + "/" + e.demotions() + " unknownUpdates=" + e.updatesForUnknown() + " relTeleports=" + e.relativeTeleports() + " unsupported=" + e.unsupportedMotions() + " rangeQueries=" + e.rangeQueries() + " queryLimits=" + e.queryLimits());
        out.add("  honesty evictionFloor=" + e.evictionFloor() + (e.coverageBreakSequence() >= 0 ? " COVERAGE_BREAK@" + e.coverageBreakSequence() + " " + e.coverageBreakReason() : " coverage=intact")
            + (e.capacityOverflowSequence() >= 0 ? " CAPACITY@" + e.capacityOverflowSequence() + " refusals=" + e.capacityRefusals() : "") + (d.internalErrors() > 0 ? " internalErrors=" + d.internalErrors() + " last=" + d.lastError() : ""));
        out.add("  attacks=" + c.attacks() + " interactions=" + c.interactions() + " targetFound=" + c.targetFound() + " targetNotFound=" + c.targetNotFound() + " complete=" + c.complete()
            + " incomplete=" + c.incomplete() + " rejected=" + c.rejected());
        for (var a : c.recentAttacks()) out.add("    " + a.describe());
        for (var i : c.recentInteractions()) out.add("    " + i.describe());
        if (withEntities) { for (EntityLookup l : d.liveEntities()) out.add("  entity " + l.describe()); if (d.moreLive() > 0) out.add("  ... " + d.moreLive() + " more"); }
        return out;
    }
    /** The last attack context (diagnostics). */
    public AttackContext lastAttack() { return engine.status().lastAttack(); }
}
