package me.nicolas.papea2ac.core;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import me.nicolas.papea2ac.combat.AttackContext;
import me.nicolas.papea2ac.combat.CombatProvenance;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.combat.reach.FrontierTracker;
import me.nicolas.papea2ac.combat.reach.ReachValidation;
import me.nicolas.papea2ac.combat.reach.ReachValidator;
import me.nicolas.papea2ac.combat.reach.TargetHull;
import me.nicolas.papea2ac.model.BlockPos;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.version.OutlineShapeTable;
import me.nicolas.papea2ac.world.BlockLookup;
import me.nicolas.papea2ac.world.EntityHistory;
import me.nicolas.papea2ac.world.EntityKey;
import me.nicolas.papea2ac.world.EntityLookup;
import me.nicolas.papea2ac.world.EntityObservation;
import me.nicolas.papea2ac.world.EntityRevision;
import me.nicolas.papea2ac.world.ObserverWorld;

/**
 * Per-session Phase 12 composition on the session shard (reach / hitbox / historical ray). It runs right after {@link CombatRunner} applied an
 * observation, keeps the small ledgers the reach decision needs and the Phase 11 contract does not carry, holds each new attack PENDING until
 * its client tick closes (the same-step rotation, M3), then calls the pure {@link ReachValidator}. Owner-confined; publishes an immutable
 * status for other threads. Bounded: {@value #MAX_PENDING} pending attacks, {@value #MAX_RESULTS} results, {@value #MAX_TARGETS} target
 * extents / metadata entries; a pending attack older than the history horizon is UNCERTAIN(PENDING_EXPIRED).
 *
 * <p>Ledgers (all from clientbound / serverbound observations in session order, never live server state):
 * <ul>
 * <li>target LIFETIME extents: per incarnation, the box of every network target since its spawn snap (any non-EXACT revision, coverage break
 *     or eviction makes it unknown, never guessed);</li>
 * <li>target metadata: the {@code scale} attribute and the synced baby flag per network id, with the sequence of the last change (a change the
 *     client may not have applied yet makes the value unknown at that attack);</li>
 * <li>self: the vehicle the observer rides (SetPassengers), and whether any item with an {@code attack_range} / {@code piercing_weapon} /
 *     {@code kinetic_weapon} component was ever sent to this player (then the held-item pick path is UNKNOWN for the session).</li>
 * </ul>
 * It never touches evidence or mitigation (Phase 12 is observe-only; evidence wiring is a separate reviewed step).
 */
public final class ReachRunner {
    public static final int MAX_PENDING = 64, MAX_RESULTS = 64, MAX_TARGETS = 256, STATUS_LINES = 12;
    public static final long PENDING_HORIZON_NANOS = 3_000_000_000L;
    /** Without tick ends, an attack whose rotation never arrives resolves after this long with the previous rotation only. */
    public static final long UNALIGNED_RESOLVE_NANOS = 1_000_000_000L;
    static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    static final Set<String> ATTACK_RANGE_COMPONENTS = Set.of("minecraft:attack_range", "minecraft:piercing_weapon", "minecraft:kinetic_weapon");
    static final String ATTR_ENTITY_RANGE = "minecraft:entity_interaction_range", ATTR_BLOCK_RANGE = "minecraft:block_interaction_range", ATTR_SCALE = "minecraft:scale";
    static final List<String> ATTACKER_POSES = List.of("STANDING", "CROUCHING", "SWIMMING", "FALL_FLYING", "SPIN_ATTACK");

    /** Fixed-cardinality engine-wide counters (no UUID, entity id, name, chunk or session label). */
    public static final class Metrics {
        public final LongAdder attacks = new LongAdder(), valid = new LongAdder(), uncertain = new LongAdder(), invalid = new LongAdder(), expired = new LongAdder(), overflow = new LongAdder(), internalErrors = new LongAdder();
        public final EnumMap<ReachValidation.MissClass, LongAdder> byMissClass = new EnumMap<>(ReachValidation.MissClass.class);
        public final EnumMap<ReachValidation.Reason, LongAdder> byReason = new EnumMap<>(ReachValidation.Reason.class);
        public final EnumMap<ReachValidation.EyeSource, LongAdder> byEyeSource = new EnumMap<>(ReachValidation.EyeSource.class);
        public final LongAdder windowMarkerProven = new LongAdder(), windowHorizonOnly = new LongAdder();
        /** Times a session's frontier tracker was rebuilt from a copy of the timing ring (start, or after a mirror gap). */
        public final LongAdder frontierRebuilds = new LongAdder();
        public Metrics() {
            for (var c : ReachValidation.MissClass.values()) byMissClass.put(c, new LongAdder());
            for (var r : ReachValidation.Reason.values()) byReason.put(r, new LongAdder());
            for (var e : ReachValidation.EyeSource.values()) byEyeSource.put(e, new LongAdder());
        }
    }
    /** Immutable published summary (safe from any thread). */
    public record Status(SessionKey session, long attacks, long valid, long uncertain, long invalid, long expired, int pending, List<ReachValidation> recent, long internalErrors, String lastError) {
        public Status { recent = List.copyOf(recent); Objects.requireNonNull(lastError); }
    }

    private static final class Extent { final int networkId; double minX, minY, minZ, maxX, maxY, maxZ; boolean broken; Extent(int id, double x, double y, double z) { networkId = id; minX = maxX = x; minY = maxY = y; minZ = maxZ = z; }
        void add(double x, double y, double z) { minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z); }
        PickMath.Box box() { return new PickMath.Box(minX, minY, minZ, maxX, maxY, maxZ); } }
    private static final class Meta { Double scale = 1.0; long scaleChanged = -1; Boolean baby = false; long babyChanged = -1; }
    private record Pending(AttackContext ctx, long capturedNanos, ReachValidator.TargetInput target, ReachValidator.EyeInput eye, ReachValidator.AttackerInput attacker,
                           ReachValidator.View previous, ReachValidator.View[] sameStep, boolean tickAligned) {}

    private final SessionKey key; private final EntityGeometryTable table; private final MovementProfile trig; private final CombatProfile combatProfile; private final Metrics metrics; private final String profileId;
    private final LinkedHashMap<Long, Extent> extents = new LinkedHashMap<>(); private final LinkedHashMap<Integer, Meta> meta = new LinkedHashMap<>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>(); private final ArrayDeque<ReachValidation> results = new ArrayDeque<>();
    private int selfVehicle = -1; private boolean attackRangeItemSeen; private long worldEpoch = -1, lastBreak = -1; private AttackContext lastSeen;
    private final FrontierTracker tracker = new FrontierTracker(); private TimingLedger trackedLedger; private long mirrored; private boolean trackerValid, ringCopyOnly;
    private long attacks, valid, uncertain, invalid, expired, errors; private String lastError = ""; private Thread owner; private volatile Status status;

    public ReachRunner(SessionKey key, EntityGeometryTable table, MovementProfile trig, CombatProfile combatProfile, Metrics metrics) {
        this.key = Objects.requireNonNull(key); this.table = Objects.requireNonNull(table); this.trig = Objects.requireNonNull(trig); this.combatProfile = Objects.requireNonNull(combatProfile);
        this.metrics = Objects.requireNonNull(metrics); this.profileId = "paper-26.2-124-reach/" + table.id() + "@" + table.digest().substring(0, 12);
        publish();
    }
    private void owner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("reach runner ownership"); }
    public Status status() { return status; }
    public String profileId() { return profileId; }

    /**
     * One observation, after the world, the timeline, the timing ledger, the movement validation and the combat runner saw it (session order).
     * Never throws: a failure is counted and leaves the pending attacks to expire as UNCERTAIN.
     */
    public void apply(Observation o, CombatRunner combat, TimingLedger timing, MovementContextLedger.View context, MovementValidationRunner validation, ObserverWorld world, int selfEntityId) {
        owner();
        try {
            long seq = o.sequence(); EntityHistory entities = combat.entities();
            if (entities.worldEpoch() != worldEpoch) { worldEpoch = entities.worldEpoch(); extents.clear(); meta.clear(); selfVehicle = -1; }
            var te = TimingObservationMapper.map(o, selfEntityId, entities.worldEpoch());
            track(timing, te);
            // ---- ledgers from the entity observations this observation produced
            if (o.event() instanceof Observation.Outbound ob) {
                for (EntityObservation e : CombatObservationMapper.entity(o, selfEntityId, combatProfile)) ledger(e, entities, seq, selfEntityId);
                ledger(ob.payload(), entities, seq, selfEntityId);
            }
            // ---- a new attack context (the combat runner built it at this observation)
            AttackContext c = combat.lastAttack();
            if (c != null && c != lastSeen && c.observation().sequence() == seq) { lastSeen = c; onAttack(c, o.capturedNanos(), entities, timing, context, validation, world, selfEntityId); }
            // ---- the attack's client tick closes: the same-step rotation (M3), then the decision
            if (te.isPresent()) {
                TimingEvent ev = te.get();
                if (ev instanceof TimingEvent.MovementObserved m && m.hasRotation())
                    for (Pending p : pending) if (p.ctx().observation().sequence() < seq && p.sameStep()[0] == null) p.sameStep()[0] = new ReachValidator.View(m.rotation().yaw(), m.rotation().pitch());
                if (ev instanceof TimingEvent.ClientTickEnd) resolveThrough(seq, world, false);
            }
            if (o.event() instanceof Observation.Tick) {
                // a clientbound coverage break makes every lifetime extent unknown (an update may be missing); checked once per server tick
                long brk = entities.status().coverageBreakSequence();
                if (brk != lastBreak) { lastBreak = brk; if (brk >= 0) for (Extent x : extents.values()) x.broken = true; }
                expire(o.capturedNanos(), world);
            }
        } catch (RuntimeException e) {
            errors++; metrics.internalErrors.increment(); String m = e.getClass().getSimpleName() + ": " + e.getMessage(); lastError = m.length() > 120 ? m.substring(0, 120) : m;
        }
        publish();
    }

    // ---- ledgers ----------------------------------------------------------------------------------------------------------------------------
    private void ledger(EntityObservation e, EntityHistory entities, long seq, int self) {
        switch (e) {
            case EntityObservation.Spawned s -> {
                EntityLookup l = entities.lookupId(key, entities.worldEpoch(), s.entityId(), seq + 1);
                if (l.found()) { putExtent(l.key().incarnation(), new Extent(s.entityId(), s.x(), s.y(), s.z())); meta.remove(s.entityId()); putMeta(s.entityId()); }
            }
            case EntityObservation.Moved m -> extend(entities, m.entityId(), seq);
            case EntityObservation.PositionSynced m -> extend(entities, m.entityId(), seq);
            case EntityObservation.Teleported m -> extend(entities, m.entityId(), seq);
            case EntityObservation.Removed r -> { for (int id : r.ids()) { extents.values().removeIf(x -> x.networkId == id); meta.remove(id); if (id == selfVehicle) selfVehicle = -1; } }
            case EntityObservation.Passengers p -> { if (p.passengers().contains(self)) selfVehicle = p.vehicleId(); else if (p.vehicleId() == selfVehicle) selfVehicle = -1; }
            case EntityObservation.CoverageBreak b -> { for (Extent x : extents.values()) x.broken = true; meta.clear(); }
            case EntityObservation.UnsupportedMotion u -> { for (Extent x : extents.values()) if (x.networkId == u.entityId()) x.broken = true; }
            default -> { }
        }
    }
    private void extend(EntityHistory entities, int id, long seq) {
        EntityLookup l = entities.lookupId(key, entities.worldEpoch(), id, seq + 1);
        if (!l.found()) return;
        Extent x = extents.get(l.key().incarnation());
        if (x == null) return; // spawned before this ledger existed or evicted: the lifetime stays unknown
        EntityRevision r = l.revision();
        if (r.positionStatus() != EntityRevision.Status.EXACT) x.broken = true; else x.add(r.x(), r.y(), r.z());
    }
    private void putExtent(long incarnation, Extent x) { extents.put(incarnation, x); while (extents.size() > MAX_TARGETS) extents.remove(extents.keySet().iterator().next()); }
    private Meta putMeta(int id) { Meta m = meta.computeIfAbsent(id, k -> new Meta()); while (meta.size() > MAX_TARGETS) meta.remove(meta.keySet().iterator().next()); return m; }

    private void ledger(me.nicolas.papea2ac.protocol.PacketPayload p, EntityHistory entities, long seq, int self) {
        switch (p) {
            case SemanticPayload.Attributes a when a.entityId() != self -> {
                Meta m = meta.get(a.entityId()); if (m == null) return;
                for (SemanticPayload.Attribute v : a.values()) if (v.id().equals(ATTR_SCALE)) {
                    Double nv = attribute(v.base(), modifiers(v.modifiers()), "SCALE");
                    if (!Objects.equals(nv, m.scale)) { m.scale = nv; m.scaleChanged = seq; }
                }
            }
            case SemanticPayload.EntitySyncedData d when d.entityId() != self -> {
                Meta m = meta.get(d.entityId()); if (m == null) return;
                EntityLookup l = entities.lookupId(key, entities.worldEpoch(), d.entityId(), seq + 1);
                EntityGeometryTable.Type t = l.found() && l.type() != null ? table.type(l.type()) : null;
                if (t == null || t.dimensionClass() != EntityGeometryTable.DimensionClass.BABY) return;
                for (SemanticPayload.EntityDataField f : d.fields()) if (f.id() == t.babyDataId()) {
                    Boolean nv = f.kind().equals("BOOLEAN") && (f.value().equals("true") || f.value().equals("false")) ? Boolean.valueOf(f.value()) : null;
                    if (!Objects.equals(nv, m.baby)) { m.baby = nv; m.babyChanged = seq; }
                }
            }
            case SemanticPayload.ContainerContent cc -> { for (SemanticPayload.Item i : cc.items()) capable(i); capable(cc.carried()); }
            case SemanticPayload.ContainerSlot cs -> capable(cs.item());
            case SemanticPayload.CursorItem ci -> capable(ci.item());
            case SemanticPayload.PlayerInventorySlot ps -> capable(ps.item());
            default -> { }
        }
    }
    private void capable(SemanticPayload.Item i) { if (i != null && !i.empty()) for (String c : i.otherComponents()) if (ATTACK_RANGE_COMPONENTS.contains(c)) { attackRangeItemSeen = true; return; } }

    // ---- attack inputs ----------------------------------------------------------------------------------------------------------------------
    private void onAttack(AttackContext c, long nanos, EntityHistory entities, TimingLedger timing, MovementContextLedger.View context, MovementValidationRunner validation, ObserverWorld world, int self) {
        attacks++; metrics.attacks.increment();
        long seq = c.observation().sequence();
        long from = Math.max(0, seq - 1_000_000);
        ApplicationFrontier frontier; List<ApplicationFrontier> frontiers; int tickEnds;
        long posSeq = c.attacker().status() == CombatProvenance.Attacker.Status.FOUND ? c.attacker().positionSequence() : -1;
        if (frontierReady(timing)) {
            long oldest = timing.view().counters().evictions();
            frontier = tracker.newest(from, seq, oldest); frontiers = tracker.all(from, seq, oldest); tickEnds = tracker.tickEndsBetween(from, posSeq, seq, oldest);
        } else { // fallback: one pass over a copy of the ring (identical answers, the pre-tracker cost)
            ApplicationFrontier.Index index = ApplicationFrontier.index(timing.events());
            frontier = index.newest(from, seq); frontiers = index.all(from, seq); tickEnds = index.tickEndsBetween(from, posSeq, seq);
        }
        ReachValidator.TargetInput target = targetInput(c, entities, frontier, frontiers, seq, world);
        ReachValidator.EyeInput eye = eyeInput(c, tickEnds, validation);
        ReachValidator.AttackerInput attacker = attackerInput(context, frontier);
        CombatProvenance.Attacker a = c.attacker();
        ReachValidator.View previous = a.rotationKnown() ? new ReachValidator.View(a.yaw(), a.pitch()) : null;
        boolean aligned = c.timing().status() == CombatProvenance.Timing.Status.TICK_ALIGNED;
        if (pending.size() >= MAX_PENDING) { Pending old = pending.pollFirst(); metrics.overflow.increment(); finish(old, null, null, ReachValidation.Reason.SCENE_LIMIT); }
        pending.addLast(new Pending(c, nanos, target, eye, attacker, previous, new ReachValidator.View[1], aligned));
    }

    private ReachValidator.TargetInput targetInput(AttackContext c, EntityHistory entities, ApplicationFrontier frontier, List<ApplicationFrontier> frontiers, long seq, ObserverWorld world) {
        return inputFor(c.target(), c.observation().targetId(), entities, frontier, frontiers, seq, world);
    }

    /**
     * Phase 13 (push exclusion, finding phase13-to-phase12-02): the same target input for ANY entity of this session by network id, as the
     * client held it just before {@code seq}, with the window bounded by {@code frontier} (the newest marker proof before the caller's interval).
     * Owner thread only (reads this runner's ledgers).
     */
    public ReachValidator.TargetInput entityInput(int networkId, long seq, EntityHistory entities, ApplicationFrontier frontier, ObserverWorld world) {
        return entityInput(networkId, seq, entities, frontier, frontier.status() == ApplicationFrontier.Status.MARKER_PROVEN ? List.of(frontier) : List.of(), world);
    }

    /** As {@link #entityInput(int, long, EntityHistory, ApplicationFrontier, ObserverWorld)}, also bounded by every answered marker ({@link ApplicationFrontier#all}). */
    public ReachValidator.TargetInput entityInput(int networkId, long seq, EntityHistory entities, ApplicationFrontier frontier, List<ApplicationFrontier> frontiers, ObserverWorld world) {
        owner();
        return inputFor(entities.lookupId(key, entities.worldEpoch(), networkId, seq), networkId, entities, frontier, frontiers, seq, world);
    }

    private ReachValidator.TargetInput inputFor(EntityLookup l, int networkId, EntityHistory entities, ApplicationFrontier frontier, List<ApplicationFrontier> frontiers, long seq, ObserverWorld world) {
        if (!l.found()) return new ReachValidator.TargetInput(l.status(), l.type(), networkId, l.key() == null ? 0 : l.key().incarnation(), List.of(), false, false, false, null, frontier, null, null, null);
        EntityKey k = l.key();
        Extent ext = extents.get(k.incarnation());
        PickMath.Box lifetime = ext == null || ext.broken ? null : ext.box();
        ApplicationFrontier f = frontier; long from;
        if (frontier.status() == ApplicationFrontier.Status.MARKER_PROVEN) {
            EntityLookup at = entities.lookupKey(k, frontier.markerSequence());
            if (at.found()) from = at.revision().sequence();
            else if (at.status() == EntityLookup.Status.PRE_SPAWN) from = l.spawnSequence(); // spawned after the marker: the window starts at the spawn snap
            else { from = l.spawnSequence(); f = ApplicationFrontier.NONE; }
        } else from = l.spawnSequence();
        EntityHistory.RevisionRange range = entities.revisions(k, from, seq, EntityHistory.MAX_RANGE_REVISIONS);
        List<EntityRevision> revs = range.revisions();
        if (!range.complete()) { f = ApplicationFrontier.NONE; List<EntityRevision> partial = new ArrayList<>(revs); if (!partial.contains(l.revision())) partial.add(l.revision()); revs = partial; }
        if (revs.isEmpty()) revs = List.of(l.revision());
        List<double[]> window = new ArrayList<>(revs.size()); boolean exact = true, mounted = false; String pose = l.revision().pose(); boolean poseStable = true;
        for (EntityRevision r : revs) {
            window.add(new double[]{r.x(), r.y(), r.z()});
            if (r.positionStatus() != EntityRevision.Status.EXACT) exact = false;
            if (r.vehicleId() >= 0) mounted = true;
            if (!Objects.equals(r.pose(), pose)) poseStable = false;
        }
        boolean localDisplacement = k.networkId() == selfVehicle || pistonNear(world, window, from);
        Meta m = meta.get(k.networkId());
        long proven = f.status() == ApplicationFrontier.Status.MARKER_PROVEN ? f.markerSequence() : Long.MAX_VALUE;
        Double scale = m == null ? null : (m.scaleChanged >= 0 && m.scaleChanged >= proven ? null : m.scale);
        Boolean baby = m == null ? null : (m.babyChanged >= 0 && m.babyChanged >= proven ? null : m.baby);
        if (f.status() != ApplicationFrontier.Status.MARKER_PROVEN) { if (m != null && m.scaleChanged >= l.spawnSequence()) scale = null; if (m != null && m.babyChanged >= l.spawnSequence()) baby = null; }
        // R5 refinement: every answered marker at once. The history is every retained revision since the spawn (older ones are inside the lifetime
        // box); each marker binds the revision in effect when the client handled it.
        List<double[]> history = new ArrayList<>(); List<TargetHull.Bound> bounds = new ArrayList<>();
        if (lifetime != null) {
            EntityHistory.RevisionRange all = entities.revisions(k, l.spawnSequence(), seq, EntityHistory.MAX_RANGE_REVISIONS);
            if (all.status() != EntityLookup.Status.QUERY_LIMIT && !all.revisions().isEmpty()) {
                List<EntityRevision> hr = all.revisions();
                for (EntityRevision r : hr) history.add(new double[]{r.x(), r.y(), r.z()});
                for (ApplicationFrontier af : frontiers) {
                    // the last revision before the marker (revisions ascending by sequence): binary search, same index as a forward scan
                    int lo = 0, hi = hr.size(); while (lo < hi) { int mid = (lo + hi) >>> 1; if (hr.get(mid).sequence() < af.markerSequence()) lo = mid + 1; else hi = mid; }
                    int idx = lo - 1;
                    if (idx > 0) bounds.add(new TargetHull.Bound(idx, af.ticksSincePong()));
                }
            }
        }
        return new ReachValidator.TargetInput(EntityLookup.Status.FOUND, l.type(), k.networkId(), k.incarnation(), window, exact, mounted, localDisplacement, lifetime, f,
            poseStable ? pose : null, scale, baby, history, bounds);
    }

    static final Set<String> PISTON_BLOCKS = Set.of("minecraft:piston", "minecraft:sticky_piston", "minecraft:moving_piston", "minecraft:piston_head");
    /**
     * Pistons move entities on the client too (the local displacement {@code InterpolationHandler.addDelta} adds to the target, oracle-to-phase11-06 /
     * P11-F24). A piston block event within a few blocks of the window's positions since the window start makes the target UNSUPPORTED; when the
     * bounded block-event ring no longer reaches back to the window start, absence cannot be shown and the answer is "possible".
     */
    static boolean pistonNear(ObserverWorld world, List<double[]> window, long since) {
        if (world == null) return true;
        List<ObserverWorld.BlockEvent> events = world.blockEvents();
        if (events.size() >= ObserverWorld.BLOCK_EVENT_MAX && events.get(0).sequence() > since) return true;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (double[] p : window) { minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]); minZ = Math.min(minZ, p[2]); maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]); maxZ = Math.max(maxZ, p[2]); }
        for (ObserverWorld.BlockEvent e : events) {
            if (e.sequence() < since || !PISTON_BLOCKS.contains(e.block())) continue;
            BlockPos b = e.position();
            if (b.x() >= minX - 3 && b.x() <= maxX + 2 && b.y() >= minY - 3 && b.y() <= maxY + 4 && b.z() >= minZ - 3 && b.z() <= maxZ + 2) return true;
        }
        return false;
    }

    // ---- application frontier inputs, kept incrementally (perf-to-phase12-01) ------------------------------------------------------------------
    /*
     * The timing ledger appends every event it accepts to its ring (counters.events counts them, in order) and only ever evicts from the ring's
     * front (counters.evictions; every TimingEvent charges a small positive constant, so no append is refused). So the ring is exactly the
     * accepted events with acceptance ordinal >= evictions. The tracker mirrors the accepted events: this observation's event is the one accepted
     * iff the ledger's count moved by one. Anything else (a skipped observation, a mapping failure, another ledger) invalidates the mirror, and
     * the next attack rebuilds it from a copy of the ring; if the ring's size ever disagrees with the eviction count, the runner stops trusting
     * the mirror and uses the ring copy for every attack. Either way the answers are those of ApplicationFrontier over the ring.
     */
    private void track(TimingLedger timing, Optional<TimingEvent> te) {
        if (timing == null) { trackerValid = false; return; }
        TimingLedger.Counters k = timing.view().counters();
        if (trackedLedger != timing) { trackedLedger = timing; trackerValid = false; mirrored = k.events(); return; }
        long d = k.events() - mirrored;
        if (d == 0) return;
        if (d == 1 && te.isPresent() && trackerValid) { tracker.accepted(te.get(), mirrored); mirrored++; tracker.trim(k.evictions()); return; }
        trackerValid = false; mirrored = k.events();
    }

    /** True when the tracker answers for this ledger's ring (rebuilding it from a ring copy when needed); false: use the ring copy directly. */
    private boolean frontierReady(TimingLedger timing) {
        if (ringCopyOnly || trackedLedger != timing) return false;
        if (trackerValid) return true;
        List<TimingEvent> ring = timing.events(); TimingLedger.Counters k = timing.view().counters();
        long base = k.events() - ring.size();
        if (base != k.evictions()) { ringCopyOnly = true; return false; }
        tracker.clear(); for (int i = 0; i < ring.size(); i++) tracker.accepted(ring.get(i), base + i);
        mirrored = k.events(); trackerValid = true; metrics.frontierRebuilds.increment();
        return true;
    }

    private ReachValidator.EyeInput eyeInput(AttackContext c, int tickEnds, MovementValidationRunner validation) {
        CombatProvenance.Attacker a = c.attacker();
        if (a.status() != CombatProvenance.Attacker.Status.FOUND || a.claimedPosition() == null) return ReachValidator.EyeInput.UNAVAILABLE;
        double x = a.claimedPosition().x(), y = a.claimedPosition().y(), z = a.claimedPosition().z();
        // tick alignment: exactly one client tick end between the claim and the attack = the claim closed the tick before the attack's tick
        boolean aligned = c.timing().status() == CombatProvenance.Timing.Status.TICK_ALIGNED && tickEnds == 1;
        boolean conflict = "INVALID".equals(a.validation().lastClassification()) && a.validation().lastEndSequence() == a.positionSequence();
        List<String> poses = new ArrayList<>();
        if (aligned && a.movementValidatedThrough() && validation != null && a.validation().lastEndSequence() == a.positionSequence()) {
            List<ReachValidator.EyeCandidate> certified = new ArrayList<>();
            for (MovementValidationRunner.FrontierState s : validation.frontierStates())
                if (s.x() == x && s.y() == y && s.z() == z) certified.add(new ReachValidator.EyeCandidate(x, y, z, s.pose(), false, 0, 0, 0));
            if (!certified.isEmpty()) return new ReachValidator.EyeInput(ReachValidation.EyeSource.CERTIFIED_CONCRETE, certified, null, List.of(), conflict);
        }
        if (validation != null) for (MovementValidationRunner.FrontierState s : validation.frontierStates()) if (!poses.contains(s.pose())) poses.add(s.pose());
        if (poses.isEmpty()) poses.addAll(ATTACKER_POSES);
        // the claim is the true position of a legitimate client at its sending tick; an unsent tick lies within the 2e-4 send threshold of it
        double t = aligned ? 0.0 : 2.0E-4;
        PickMath.Box feet = new PickMath.Box(Math.nextDown(x - t), Math.nextDown(y - t), Math.nextDown(z - t), Math.nextUp(x + t), Math.nextUp(y + t), Math.nextUp(z + t));
        return new ReachValidator.EyeInput(ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY, List.of(), feet, poses, conflict);
    }

    private ReachValidator.AttackerInput attackerInput(MovementContextLedger.View context, ApplicationFrontier frontier) {
        if (context == null) return new ReachValidator.AttackerInput(null, 4.5, null, selfVehicle >= 0, ReachValidator.HeldItem.UNKNOWN, "unknown");
        long proven = frontier.status() == ApplicationFrontier.Status.MARKER_PROVEN ? frontier.markerSequence() : Long.MAX_VALUE;
        Double range = selfAttribute(context, ATTR_ENTITY_RANGE, "ENTITY_INTERACTION_RANGE", proven);
        Double block = selfAttribute(context, ATTR_BLOCK_RANGE, "BLOCK_INTERACTION_RANGE", proven);
        Double scale = selfAttribute(context, ATTR_SCALE, "SCALE", proven);
        String mode = context.gameMode();
        boolean survivalLike = mode.equals("survival") || mode.equals("adventure");
        ReachValidator.HeldItem held = survivalLike && !attackRangeItemSeen ? ReachValidator.HeldItem.CLASSIC : ReachValidator.HeldItem.UNKNOWN;
        return new ReachValidator.AttackerInput(range, block == null ? 4.5 : block, scale, selfVehicle >= 0, held, mode);
    }
    /**
     * The attacker's own attribute as the client holds it: the last synced snapshot (or the player default when none was sent), unknown when it
     * changed after the proven application frontier (the client may still hold the previous value).
     */
    private Double selfAttribute(MovementContextLedger.View context, String id, String tableName, long proven) {
        MovementContextLedger.AttributeValue v = context.attributes().get(id);
        if (v == null) return attribute(table.attribute(tableName).defaultValue(), List.of(), tableName);
        if (v.sequence() >= proven) return null;
        List<MovementProfile.Modifier> mods = new ArrayList<>(v.modifiers().size());
        for (ContextEvent.ModifierValue m : v.modifiers()) mods.add(new MovementProfile.Modifier(m.id(), m.amount(), MovementProfile.Operation.valueOf(m.operation())));
        return attribute(v.base(), mods, tableName);
    }
    private Double attribute(double base, List<MovementProfile.Modifier> mods, String tableName) {
        EntityGeometryTable.Attribute a = table.attribute(tableName);
        if (a == null) return null;
        try { return MovementProfile.calculateValue(new MovementProfile.AttributeDefinition(a.id(), a.defaultValue(), a.min(), a.max(), a.defaultValue()), base, mods); }
        catch (IllegalArgumentException e) { return null; }
    }
    private static List<MovementProfile.Modifier> modifiers(List<SemanticPayload.Modifier> in) {
        List<MovementProfile.Modifier> out = new ArrayList<>(in.size());
        for (SemanticPayload.Modifier m : in) { try { out.add(new MovementProfile.Modifier(m.id(), m.amount(), MovementProfile.Operation.valueOf(m.operation()))); } catch (IllegalArgumentException e) { return null; } }
        return out;
    }

    // ---- resolution ------------------------------------------------------------------------------------------------------------------------
    /** Resolves every pending attack captured before {@code seq} (their client tick ended at {@code seq}). */
    private void resolveThrough(long seq, ObserverWorld world, boolean expiredOnly) {
        while (!pending.isEmpty() && pending.peekFirst().ctx().observation().sequence() < seq) {
            Pending p = pending.pollFirst();
            List<ReachValidator.View> views = new ArrayList<>(2);
            if (p.sameStep()[0] != null) views.add(p.sameStep()[0]);
            else if (p.previous() != null) views.add(p.previous());
            finish(p, views, world, null);
        }
    }
    private void expire(long nowNanos, ObserverWorld world) {
        while (!pending.isEmpty()) {
            Pending p = pending.peekFirst();
            long age = nowNanos - p.capturedNanos();
            if (!p.tickAligned() && age >= UNALIGNED_RESOLVE_NANOS) {
                // no tick ends on this session: the rotation is the previous one or the first one sent after the attack (either may be the attack's tick)
                pending.pollFirst(); List<ReachValidator.View> views = new ArrayList<>(2);
                if (p.previous() != null) views.add(p.previous()); if (p.sameStep()[0] != null && !views.contains(p.sameStep()[0])) views.add(p.sameStep()[0]);
                finish(p, views, world, null); continue;
            }
            if (age < PENDING_HORIZON_NANOS) return;
            pending.pollFirst(); expired++; metrics.expired.increment(); finish(p, null, null, ReachValidation.Reason.PENDING_EXPIRED);
        }
    }
    private void finish(Pending p, List<ReachValidator.View> views, ObserverWorld world, ReachValidation.Reason forced) {
        ReachValidation r;
        long seq = p.ctx().observation().sequence();
        if (forced != null || views == null) {
            ReachValidator.Input in = new ReachValidator.Input(key, seq, p.ctx().id(), p.target(), p.eye(), List.of(), p.attacker(), (ReachValidator.CellOutlines) null, true);
            r = ReachValidator.validate(in, table, trig, profileId);
            if (forced != null) r = new ReachValidation(r.session(), r.sequence(), r.attackId(), ReachValidation.Status.UNCERTAIN, List.of(), append(r.reasons(), forced), r.eyeSource(), r.frontier(),
                r.targetType(), r.targetNetworkId(), r.targetIncarnation(), r.scenes(), r.workUnits(), null, null, r.entityRange(), r.scale(), r.profileId(), r.notes());
        } else {
            ReachValidator.CellOutlines cells = world == null ? null : (x, y, z) -> outline(world.blockAtSequence(new BlockPos(x, y, z), seq), x, y, z);
            // D12-1: the attacker's own claims may support an exclusion (never a VALID)
            r = ReachValidator.validate(new ReachValidator.Input(key, seq, p.ctx().id(), p.target(), p.eye(), views, p.attacker(), cells, true), table, trig, profileId);
        }
        record(r);
    }
    /**
     * The OUTLINE boxes of a cell in world coordinates for the witness block check (R7): air has none; a STATIC outline is translated to the
     * cell; an OFFSET outline is widened by the block's maximum offsets (an envelope, so a ray proven clear of it is clear of the real shape); a
     * CONTEXT / LEVEL outline, an unknown state or an unobserved cell is unknown (null).
     */
    static double[] outline(BlockLookup b, int x, int y, int z) {
        if (!b.known()) return null;
        if (AIR.contains(b.state().block())) return new double[0];
        OutlineShapeTable.Outline o = OUTLINES.lookup(b.state());
        if (o == null || o.kind() == OutlineShapeTable.Kind.CONTEXT || o.kind() == OutlineShapeTable.Kind.LEVEL) return null;
        double[] in = o.boxes(), out = new double[in.length];
        double h = o.kind() == OutlineShapeTable.Kind.OFFSET ? o.maxHorizontalOffset() : 0.0, v = o.kind() == OutlineShapeTable.Kind.OFFSET ? o.maxVerticalOffset() : 0.0;
        for (int i = 0; i + 5 < in.length; i += 6) {
            out[i] = Math.nextDown(x + in[i] - h); out[i + 1] = Math.nextDown(y + in[i + 1] - v); out[i + 2] = Math.nextDown(z + in[i + 2] - h);
            out[i + 3] = Math.nextUp(x + in[i + 3] + h); out[i + 4] = Math.nextUp(y + in[i + 4] + v); out[i + 5] = Math.nextUp(z + in[i + 5] + h);
        }
        return out;
    }
    static final OutlineShapeTable OUTLINES = OutlineShapeTable.paper26_2();

    private static List<ReachValidation.Reason> append(List<ReachValidation.Reason> in, ReachValidation.Reason r) { List<ReachValidation.Reason> out = new ArrayList<>(in); if (!out.contains(r)) out.add(r); Collections.sort(out); return out; }
    private void record(ReachValidation r) {
        results.addLast(r); while (results.size() > MAX_RESULTS) results.pollFirst();
        switch (r.status()) {
            case VALID -> { valid++; metrics.valid.increment(); }
            case UNCERTAIN -> { uncertain++; metrics.uncertain.increment(); }
            case INVALID -> { invalid++; metrics.invalid.increment(); }
        }
        for (var c : r.missClasses()) metrics.byMissClass.get(c).increment();
        for (var reason : r.reasons()) metrics.byReason.get(reason).increment();
        metrics.byEyeSource.get(r.eyeSource()).increment();
        if (r.frontier().status() == ApplicationFrontier.Status.MARKER_PROVEN) metrics.windowMarkerProven.increment(); else metrics.windowHorizonOnly.increment();
    }

    /** Owner thread: the retained results, oldest first (replay export, tests). */
    public List<ReachValidation> results() { owner(); return List.copyOf(results); }
    /** Owner thread: resolves every pending attack now (session close): each becomes UNCERTAIN(PENDING_EXPIRED). */
    public void close() { owner(); while (!pending.isEmpty()) { Pending p = pending.pollFirst(); expired++; metrics.expired.increment(); finish(p, null, null, ReachValidation.Reason.PENDING_EXPIRED); } publish(); }

    /**
     * Publishes only when something visible changed (perf-to-phase12-01 minor): every result is added by a decision (valid / uncertain /
     * invalid change) and every error message by an error, so equal counters and pending size mean an equal Status.
     */
    private void publish() {
        Status s = status;
        if (s != null && s.attacks() == attacks && s.valid() == valid && s.uncertain() == uncertain && s.invalid() == invalid && s.expired() == expired
            && s.pending() == pending.size() && s.internalErrors() == errors) return;
        List<ReachValidation> recent = new ArrayList<>(STATUS_LINES); int skip = Math.max(0, results.size() - STATUS_LINES), k = 0;
        for (ReachValidation r : results) if (k++ >= skip) recent.add(r);
        status = new Status(key, attacks, valid, uncertain, invalid, expired, pending.size(), recent, errors, lastError);
    }

    /** Renders a published status for the diagnostic command (caller's thread). Descriptive: statuses, miss classes and reasons only; no score, label or recommendation. */
    public static List<String> lines(Status s) {
        List<String> out = new ArrayList<>();
        out.add("reach session=" + s.session().epoch() + " attacks=" + s.attacks() + " valid=" + s.valid() + " uncertain=" + s.uncertain() + " invalid=" + s.invalid() + " expired=" + s.expired() + " pending=" + s.pending()
            + (s.internalErrors() > 0 ? " internalErrors=" + s.internalErrors() + " last=" + s.lastError() : "") + " (OBSERVE_ONLY: no evidence, no mitigation)");
        for (ReachValidation r : s.recent()) out.add("    " + r.describe());
        return out;
    }
}
