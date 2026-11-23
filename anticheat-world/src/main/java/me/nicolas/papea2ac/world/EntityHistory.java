package me.nicolas.papea2ac.world;

import java.util.*;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.version.CombatProfile;

/**
 * The observer's entity history (COMBAT_MODEL.md: "TargetHistory belongs to the observer WorldView"; WORLD_MODEL.md EntityState): the network
 * state of every entity the observer's client was SENT, reconstructed from clientbound packets only, with incarnation identity, bounded per-entity
 * history and first-class unknown. One instance per session, owner-confined (the session shard); no live server state ever enters.
 *
 * <p><b>State.</b> Per incarnation: the client's {@code VecDeltaCodec} base and the network target (CombatProfile: spawn and position sync set both;
 * a relative move decodes from the base and sets both; an absolute teleport sets only the target; a relative teleport component makes the target
 * INTERPOLATION_DEPENDENT), rotation, head yaw, pose (the synced default STANDING at spawn, as the client has it, because pairing
 * sends only non-default synced values), shared flags and the vehicle relation.
 *
 * <p><b>Bounds</b> (WORLD_MODEL / PERFORMANCE "256 summaries, 32 deep histories, 96 revisions/history; tier byte cap"; three-second target
 * horizon). At most 256 retained incarnations (live plus recently ended). At most 32 "deep" live incarnations keep up to 96 revisions within the
 * horizon (the newest revision older than the horizon is kept as the floor); every other incarnation keeps only its current revision. Deep slots
 * go to the entities nearest the observer ({@link #rebalance}; ties to the lower incarnation). Bytes are charged to the observer world's private
 * tier; deep history is shed first, and a spawn that cannot be stored even as a summary is refused and marks the epoch capacity-incomplete.
 *
 * <p><b>Honesty.</b> A revision that aged out of a history answers EVICTED for the sequences it covered, never the nearest retained revision; once an
 * ended incarnation has been forgotten (capacity), an id lookup that can no longer be proven answers EVICTED, never UNKNOWN or PRE_SPAWN (the
 * eviction floor records the highest evicted sequence for diagnostics). A clientbound
 * coverage break (a sequence gap, a loss-class latch reason, a compromised capture position) taints every live incarnation from that sequence on and
 * makes unknown ids INCOMPLETE_COVERAGE. A world epoch change ends every incarnation and discards the epoch's structures; nothing crosses it.
 */
public final class EntityHistory {
    public static final int MAX_RETAINED = 256, MAX_DEEP = 32, MAX_REVISIONS = 96;
    public static final long HORIZON_NANOS = 3_000_000_000L;
    public static final int SUMMARY_BYTES = 256;
    public enum EndCause { REMOVED, REPLACED_BY_SPAWN, WORLD_EPOCH_END, SESSION_END }

    static final class Rec {
        final EntityKey key; final String type; final UUID uuid; final long spawnSequence;
        EntityRevision current; ArrayDeque<EntityRevision> deep; // null = summary only
        long endSequence = -1; EndCause endCause;
        long taintSequence = -1; String taintReason = "";
        Rec(EntityKey key, String type, UUID uuid, long spawnSequence, EntityRevision first) { this.key = key; this.type = type; this.uuid = uuid; this.spawnSequence = spawnSequence; current = first; }
        boolean live() { return endSequence < 0; }
        int bytes() { return SUMMARY_BYTES + (deep == null ? 0 : deep.size() * EntityRevision.CHARGED_BYTES); }
    }

    private final SessionKey session; private final CombatProfile profile; private final ObserverWorld tier; private final int standaloneLimit;
    private Thread owner;
    private long worldEpoch, epochStartSequence = 1, lastSequence, nextIncarnation = 1; private int lastSubIndex = -1;
    private final HashMap<Integer, Rec> live = new HashMap<>();
    private final ArrayDeque<Rec> ended = new ArrayDeque<>();
    private final HashMap<Integer, List<Rec>> byId = new HashMap<>();
    private final HashMap<Long, Rec> byIncarnation = new HashMap<>();
    private long evictionFloor = -1, coverageBreakSequence = -1, capacityOverflowSequence = -1, forgottenMaxEnd = -1; private String coverageBreakReason = "";
    private long bytes; private int deepCount; private boolean closed;
    // fixed-cardinality counters
    private long generations, removals, replacements, epochResets, historyEvictions, capacityRefusals, coverageBreaks, updatesForUnknown, promotions, demotions, relativeTeleports, unsupportedMotions, rangeQueries, queryLimits;

    /** {@code tier}: the observer world whose private byte tier this history shares (null in isolated tests: then {@code standaloneLimit} bytes). */
    public EntityHistory(SessionKey session, CombatProfile profile, ObserverWorld tier, int standaloneLimit) {
        this.session = Objects.requireNonNull(session); this.profile = Objects.requireNonNull(profile); this.tier = tier; this.standaloneLimit = standaloneLimit;
        if (tier == null && standaloneLimit < SUMMARY_BYTES) throw new IllegalArgumentException("standalone limit");
    }
    private void owner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("entity history ownership"); }

    // ---- ingestion -----------------------------------------------------------------------------------------------------------------------
    /** Applies one observation at {@code (sequence, subIndex)} in the session's wire order; false (and no change) for out-of-order or closed input. */
    public boolean apply(SessionKey observedFor, EntityObservation o, long sequence, int subIndex, long nanos) {
        owner();
        if (closed || !session.equals(observedFor) || sequence < 1 || subIndex < 0 || sequence < lastSequence || (sequence == lastSequence && subIndex <= lastSubIndex)) return false;
        noteGap(sequence); lastSequence = sequence; lastSubIndex = subIndex;
        switch (o) {
            case EntityObservation.Spawned s -> spawn(s, sequence, subIndex, nanos);
            case EntityObservation.Moved m -> update(m.entityId(), sequence, subIndex, nanos, r -> moved(r, m, sequence, subIndex, nanos));
            // an incarnation moved by an unreconstructed mechanism stays UNSUPPORTED: a later packet never claims exactness for that mechanism
            case EntityObservation.PositionSynced p -> update(p.entityId(), sequence, subIndex, nanos, r -> rev(r, sequence, subIndex, nanos, "SYNC", p.x(), p.y(), p.z(), sticky(r.positionStatus()), p.x(), p.y(), p.z(), p.yaw(), p.pitch(), sticky(r.rotationStatus()), r.headYaw(), r.pose(), r.poseOpaque(), r.sharedFlags(), r.metadataRevision(), r.vehicleId()));
            case EntityObservation.Teleported t -> update(t.entityId(), sequence, subIndex, nanos, r -> teleported(r, t, sequence, subIndex, nanos));
            case EntityObservation.HeadRotated h -> update(h.entityId(), sequence, subIndex, nanos, r -> rev(r, sequence, subIndex, nanos, "HEAD", r.x(), r.y(), r.z(), r.positionStatus(), r.baseX(), r.baseY(), r.baseZ(), r.yaw(), r.pitch(), r.rotationStatus(), h.headYaw(), r.pose(), r.poseOpaque(), r.sharedFlags(), r.metadataRevision(), r.vehicleId()));
            case EntityObservation.DataUpdated d -> update(d.entityId(), sequence, subIndex, nanos, r -> data(r, d, sequence, subIndex, nanos));
            case EntityObservation.Passengers p -> passengers(p, sequence, subIndex, nanos);
            case EntityObservation.UnsupportedMotion u -> update(u.entityId(), sequence, subIndex, nanos, r -> { unsupportedMotions++; return rev(r, sequence, subIndex, nanos, "UNSUPPORTED:" + u.kind(), r.x(), r.y(), r.z(), EntityRevision.Status.UNSUPPORTED, r.baseX(), r.baseY(), r.baseZ(), r.yaw(), r.pitch(), EntityRevision.Status.UNSUPPORTED, r.headYaw(), r.pose(), r.poseOpaque(), r.sharedFlags(), r.metadataRevision(), r.vehicleId()); });
            case EntityObservation.Removed rm -> { for (int id : rm.ids()) { Rec r = live.get(id); if (r == null) { updatesForUnknown++; continue; } end(r, sequence, EndCause.REMOVED); removals++; } }
            case EntityObservation.CoverageBreak b -> breakCoverage(b.reason(), sequence);
        }
        trimHorizon(nanos);
        return true;
    }
    /** An observation carrying no entity traffic still advances the observed-through bound (so a lost one is detected as a sequence gap). */
    public void observeSequence(SessionKey observedFor, long sequence, long nanos) {
        owner();
        if (closed || !session.equals(observedFor) || sequence < 1 || sequence <= lastSequence) return;
        noteGap(sequence); lastSequence = sequence; lastSubIndex = -1; trimHorizon(nanos);
    }
    /** The observer world entered a new world epoch at {@code sequence} (dimension change / respawn): every incarnation ends; nothing crosses. */
    public void enterEpoch(long newWorldEpoch, long sequence) {
        owner();
        if (closed || newWorldEpoch <= worldEpoch) return;
        for (Rec r : new ArrayList<>(live.values())) end(r, sequence, EndCause.WORLD_EPOCH_END);
        live.clear(); ended.clear(); byId.clear(); byIncarnation.clear(); bytes = 0; deepCount = 0;
        worldEpoch = newWorldEpoch; epochStartSequence = sequence; evictionFloor = -1; coverageBreakSequence = -1; capacityOverflowSequence = -1; forgottenMaxEnd = -1; coverageBreakReason = "";
        epochResets++;
    }
    public void close() { owner(); if (closed) return; for (Rec r : new ArrayList<>(live.values())) end(r, lastSequence, EndCause.SESSION_END); live.clear(); ended.clear(); byId.clear(); byIncarnation.clear(); bytes = 0; deepCount = 0; closed = true; }

    /** P11-F4: a lost observation taints from the FIRST MISSING sequence (lastSequence + 1), not the arriving one: the state just after it is already unprovable (found by CombatMetamorphicTest, seed 2). */
    private void noteGap(long sequence) { if (lastSequence > 0 && sequence > lastSequence + 1) breakCoverage("SEQUENCE_GAP:" + (lastSequence + 1), lastSequence + 1); }
    private void breakCoverage(String reason, long sequence) {
        coverageBreaks++;
        if (coverageBreakSequence < 0) { coverageBreakSequence = sequence; coverageBreakReason = reason.length() > 64 ? reason.substring(0, 64) : reason; }
        for (Rec r : live.values()) if (r.taintSequence < 0) { r.taintSequence = sequence; r.taintReason = coverageBreakReason; }
    }

    private void spawn(EntityObservation.Spawned s, long sequence, int subIndex, long nanos) {
        Rec previous = live.get(s.entityId());
        // ClientLevel.addEntity replaces an entity already registered under the id: the client builds a fresh entity, so this is a new incarnation.
        // A replayed duplicate OBSERVATION never reaches here (apply() rejects anything not strictly after the last (sequence, subIndex)).
        if (previous != null) { end(previous, sequence, EndCause.REPLACED_BY_SPAWN); replacements++; }
        if (!makeRoomForSummary(sequence)) { capacityRefusals++; if (capacityOverflowSequence < 0) capacityOverflowSequence = sequence; return; }
        EntityKey key = new EntityKey(session, worldEpoch, s.entityId(), nextIncarnation++);
        EntityRevision first = new EntityRevision(sequence, subIndex, nanos, "SPAWN", s.x(), s.y(), s.z(), EntityRevision.Status.EXACT, s.x(), s.y(), s.z(), s.yaw(), s.pitch(),
            EntityRevision.Status.EXACT, s.headYaw(), "STANDING", false, 0, 0, -1);
        Rec r = new Rec(key, s.type(), s.uuid(), sequence, first);
        live.put(s.entityId(), r); byId.computeIfAbsent(s.entityId(), k -> new ArrayList<>(2)).add(r); byIncarnation.put(key.incarnation(), r);
        bytes += SUMMARY_BYTES; generations++;
        if (deepCount < MAX_DEEP) promote(r);
    }
    private interface Step { EntityRevision next(EntityRevision current); }
    private void update(int entityId, long sequence, int subIndex, long nanos, Step step) {
        Rec r = live.get(entityId);
        if (r == null) { updatesForUnknown++; return; } // the client drops updates for entities it does not know; so does the model
        EntityRevision next = step.next(r.current);
        if (next == null) return;
        r.current = next;
        if (r.deep != null) { if (!admit(EntityRevision.CHARGED_BYTES, r)) { demote(r, sequence); return; } r.deep.addLast(next); bytes += EntityRevision.CHARGED_BYTES; while (r.deep.size() > MAX_REVISIONS) evictOldestRevision(r); }
    }
    private EntityRevision moved(EntityRevision r, EntityObservation.Moved m, long sequence, int subIndex, long nanos) {
        double bx = r.baseX(), by = r.baseY(), bz = r.baseZ(), x = r.x(), y = r.y(), z = r.z(); EntityRevision.Status ps = r.positionStatus();
        if (m.hasPosition()) {
            if (m.dx() != 0 || m.dy() != 0 || m.dz() != 0) { bx = profile.decodeAxis(r.baseX(), m.dx()); by = profile.decodeAxis(r.baseY(), m.dy()); bz = profile.decodeAxis(r.baseZ(), m.dz()); }
            x = bx; y = by; z = bz; ps = ps == EntityRevision.Status.UNSUPPORTED ? ps : EntityRevision.Status.EXACT; // handleMoveEntity: target = decode(base); base = target
        }
        float yaw = m.hasRotation() ? m.yaw() : r.yaw(), pitch = m.hasRotation() ? m.pitch() : r.pitch();
        EntityRevision.Status rs = m.hasRotation() && r.rotationStatus() != EntityRevision.Status.UNSUPPORTED ? EntityRevision.Status.EXACT : r.rotationStatus();
        return rev(r, sequence, subIndex, nanos, m.hasPosition() ? (m.hasRotation() ? "MOVE_ROT" : "MOVE") : "ROT", x, y, z, ps, bx, by, bz, yaw, pitch, rs, r.headYaw(), r.pose(), r.poseOpaque(), r.sharedFlags(), r.metadataRevision(), r.vehicleId());
    }
    private EntityRevision teleported(EntityRevision r, EntityObservation.Teleported t, long sequence, int subIndex, long nanos) {
        if (t.relativePosition() || t.relativeRotation()) relativeTeleports++;
        // handleTeleportEntity: absolute components are exact; a relative component resolves against the client's current (interpolated) state; the codec base is untouched
        EntityRevision.Status ps = r.positionStatus() == EntityRevision.Status.UNSUPPORTED ? EntityRevision.Status.UNSUPPORTED : t.relativePosition() ? EntityRevision.Status.INTERPOLATION_DEPENDENT : EntityRevision.Status.EXACT;
        EntityRevision.Status rs = r.rotationStatus() == EntityRevision.Status.UNSUPPORTED ? EntityRevision.Status.UNSUPPORTED : t.relativeRotation() ? EntityRevision.Status.INTERPOLATION_DEPENDENT : EntityRevision.Status.EXACT;
        // P11-F7: PositionMoveRotation.calculateAbsolute, bit for bit: (relative ? current : 0) + packet (so an absolute -0.0 lands on +0.0), pitch clamped to [-90, 90] (Mth.clamp)
        double x = ((t.relativeMask() & EntityObservation.Teleported.REL_X) != 0 ? r.x() : 0.0) + t.x(), y = ((t.relativeMask() & EntityObservation.Teleported.REL_Y) != 0 ? r.y() : 0.0) + t.y(),
            z = ((t.relativeMask() & EntityObservation.Teleported.REL_Z) != 0 ? r.z() : 0.0) + t.z();
        float yaw = ((t.relativeMask() & EntityObservation.Teleported.REL_YAW) != 0 ? r.yaw() : 0f) + t.yaw(), rawPitch = ((t.relativeMask() & EntityObservation.Teleported.REL_PITCH) != 0 ? r.pitch() : 0f) + t.pitch();
        float pitch = rawPitch < -90f ? -90f : Math.min(rawPitch, 90f);
        return rev(r, sequence, subIndex, nanos, t.relativeMask() == 0 ? "TELEPORT" : "TELEPORT_REL", x, y, z, ps, r.baseX(), r.baseY(), r.baseZ(), yaw, pitch, rs, r.headYaw(), r.pose(), r.poseOpaque(), r.sharedFlags(), r.metadataRevision(), r.vehicleId());
    }
    private EntityRevision data(EntityRevision r, EntityObservation.DataUpdated d, long sequence, int subIndex, long nanos) {
        if (d.pose() == null && !d.poseOpaque() && d.sharedFlags() < 0) return null; // nothing the combat model keeps changed: no revision
        String pose = r.pose(); boolean opaque = r.poseOpaque();
        if (d.pose() != null) { pose = profile.knownPose(d.pose()) ? d.pose() : null; opaque = !profile.knownPose(d.pose()); }
        else if (d.poseOpaque()) { pose = null; opaque = true; }
        int flags = d.sharedFlags() >= 0 ? d.sharedFlags() : r.sharedFlags();
        return rev(r, sequence, subIndex, nanos, "DATA", r.x(), r.y(), r.z(), r.positionStatus(), r.baseX(), r.baseY(), r.baseZ(), r.yaw(), r.pitch(), r.rotationStatus(), r.headYaw(), pose, opaque, flags, r.metadataRevision() + 1, r.vehicleId());
    }
    private void passengers(EntityObservation.Passengers p, long sequence, int subIndex, long nanos) {
        if (!live.containsKey(p.vehicleId())) { updatesForUnknown++; return; }
        Set<Integer> now = new HashSet<>(p.passengers());
        for (Rec r : new ArrayList<>(live.values())) {
            int id = r.key.networkId();
            boolean should = now.contains(id), was = r.current.vehicleId() == p.vehicleId();
            if (should != was) update(id, sequence, subIndex, nanos, c -> rev(c, sequence, subIndex, nanos, should ? "MOUNT" : "DISMOUNT", c.x(), c.y(), c.z(), c.positionStatus(), c.baseX(), c.baseY(), c.baseZ(), c.yaw(), c.pitch(), c.rotationStatus(), c.headYaw(), c.pose(), c.poseOpaque(), c.sharedFlags(), c.metadataRevision(), should ? p.vehicleId() : -1));
        }
    }
    private static EntityRevision.Status sticky(EntityRevision.Status s) { return s == EntityRevision.Status.UNSUPPORTED ? s : EntityRevision.Status.EXACT; }
    private static EntityRevision rev(EntityRevision r, long sequence, int subIndex, long nanos, String cause, double x, double y, double z, EntityRevision.Status ps, double bx, double by, double bz,
                                      float yaw, float pitch, EntityRevision.Status rs, float headYaw, String pose, boolean poseOpaque, int flags, long metadataRevision, int vehicleId) {
        return new EntityRevision(sequence, subIndex, nanos, cause, x, y, z, ps, bx, by, bz, yaw, pitch, rs, headYaw, pose, poseOpaque, flags, metadataRevision, vehicleId);
    }

    private void end(Rec r, long sequence, EndCause cause) {
        if (!r.live()) return;
        r.endSequence = sequence; r.endCause = cause;
        if (live.get(r.key.networkId()) == r) live.remove(r.key.networkId());
        // P11-F5: the ended incarnation keeps its deep history AND its slot (WORLD_MODEL: at most 32 deep histories in total) until the horizon leaves only its
        // final revision (trim releases it) or it is forgotten: a target that dies right after an attack stays queryable for a pending consumer
        ended.addLast(r);
        while (live.size() + ended.size() > MAX_RETAINED && !ended.isEmpty()) forget(ended.pollFirst());
    }
    private void forget(Rec r) {
        bytes -= SUMMARY_BYTES; historyEvictions++;
        if (r.deep != null) { bytes -= (long) r.deep.size() * EntityRevision.CHARGED_BYTES; r.deep = null; deepCount--; }
        evictionFloor = Math.max(evictionFloor, r.endSequence >= 0 ? r.endSequence : lastSequence);
        forgottenMaxEnd = Math.max(forgottenMaxEnd, r.endSequence >= 0 ? r.endSequence : lastSequence);
        var list = byId.get(r.key.networkId()); if (list != null) { list.remove(r); if (list.isEmpty()) byId.remove(r.key.networkId()); }
        byIncarnation.remove(r.key.incarnation());
    }
    private boolean makeRoomForSummary(long sequence) {
        while (live.size() + ended.size() >= MAX_RETAINED && !ended.isEmpty()) forget(ended.pollFirst());
        if (live.size() + ended.size() >= MAX_RETAINED) return false; // 256 live incarnations: refuse, never silently drop a live one
        return admit(SUMMARY_BYTES, null);
    }
    /** Tier admission: shed deep history (optional depth) first, oldest revision of the largest history, then retained ended incarnations; never another entity's live summary. */
    private boolean admit(int need, Rec requester) {
        while (usage() + need > limit()) {
            Rec victim = largestDeep();
            if (victim != null) { evictOldestRevision(victim); continue; }
            if (!ended.isEmpty()) { forget(ended.pollFirst()); continue; }
            return false;
        }
        return true;
    }
    private long usage() { return bytes + (tier == null ? 0 : tier.tierUsageBytes()); }
    private long limit() { return tier == null ? standaloneLimit : tier.tierLimitBytes(); }
    /** Called by the observer world when it needs private-tier room: drops one deep revision (optional depth goes before any chunk). False = nothing sheddable. */
    boolean shedDeepRevision() {
        Rec victim = largestDeep();
        if (victim == null) return false;
        evictOldestRevision(victim); return true;
    }
    public long bytes() { return bytes; }
    /** The largest sheddable deep history (live or ended); ties go to the first in iteration order (the live map, then end order), which is deterministic for a given observation history. */
    private Rec largestDeep() {
        Rec victim = null;
        for (Rec r : live.values()) if (r.deep != null && r.deep.size() > 1 && (victim == null || r.deep.size() > victim.deep.size())) victim = r;
        for (Rec r : ended) if (r.deep != null && r.deep.size() > 1 && (victim == null || r.deep.size() > victim.deep.size())) victim = r;
        return victim;
    }
    private void evictOldestRevision(Rec r) {
        EntityRevision gone = r.deep.pollFirst(); bytes -= EntityRevision.CHARGED_BYTES; historyEvictions++;
        evictionFloor = Math.max(evictionFloor, gone.sequence());
    }
    private void promote(Rec r) {
        if (r.deep != null || !admit(EntityRevision.CHARGED_BYTES, r)) return;
        r.deep = new ArrayDeque<>(); r.deep.addLast(r.current); bytes += EntityRevision.CHARGED_BYTES; deepCount++; promotions++;
    }
    private void demote(Rec r, long sequence) {
        if (r.deep == null) return;
        if (!r.deep.isEmpty()) evictionFloor = Math.max(evictionFloor, r.deep.peekLast().sequence());
        bytes -= (long) r.deep.size() * EntityRevision.CHARGED_BYTES; historyEvictions += Math.max(0, r.deep.size() - 1); r.deep = null; deepCount--; demotions++;
    }
    /** Horizon: a deep history keeps revisions newer than the horizon plus the newest older one (the state in effect at the horizon edge). */
    private void trimHorizon(long nanos) {
        long edge = nanos - HORIZON_NANOS;
        for (Rec r : live.values()) trim(r, edge);
        for (Rec r : ended) trim(r, edge);
    }
    private void trim(Rec r, long edge) {
        if (r.deep == null) return;
        while (r.deep.size() > 1) { Iterator<EntityRevision> it = r.deep.iterator(); it.next(); EntityRevision second = it.next(); if (second.nanos() <= edge) evictOldestRevision(r); else break; }
        if (!r.live() && r.deep.size() <= 1 && r.deep.peekFirst() != null && r.deep.peekFirst().nanos() <= edge) { bytes -= (long) r.deep.size() * EntityRevision.CHARGED_BYTES; r.deep = null; deepCount--; } // nothing left but the final revision: release the slot
    }
    /**
     * Deep-slot policy (WORLD_MODEL "deep history prioritizes at most 32 nearby entities"): the 32 live incarnations whose current network target is
     * nearest the observer (squared distance; ties to the lower incarnation). A promoted incarnation's history starts at its current revision; a
     * demoted one keeps only its current revision (the dropped span raises the eviction floor). The core calls it once per second of server ticks (P11-F8) with the
     * observer's current claimed position (retention priority only; never a verdict). Deterministic given the ordered inputs.
     */
    public void rebalance(double ox, double oy, double oz) {
        owner();
        if (closed || live.isEmpty() || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)) return;
        List<Rec> order = new ArrayList<>(live.values());
        order.sort(Comparator.<Rec>comparingDouble(r -> sq(r.current.x() - ox) + sq(r.current.y() - oy) + sq(r.current.z() - oz)).thenComparingLong(r -> r.key.incarnation()));
        for (int i = MAX_DEEP; i < order.size(); i++) demote(order.get(i), lastSequence);
        for (int i = 0; i < Math.min(MAX_DEEP, order.size()) && deepCount < MAX_DEEP; i++) promote(order.get(i)); // ended incarnations may still hold slots: never above 32 in total
    }
    private static double sq(double v) { return v * v; }

    // ---- queries ---------------------------------------------------------------------------------------------------------------------------
    /** The network state of whichever incarnation owned {@code networkId} just before {@code sequence} (owner thread). */
    public EntityLookup lookupId(SessionKey querySession, long queryEpoch, int networkId, long sequence) {
        owner();
        EntityLookup scope = scope(querySession, queryEpoch, sequence); if (scope != null) return scope;
        List<Rec> recs = byId.getOrDefault(networkId, List.of());
        Rec alive = null, endedBefore = null, later = null;
        for (Rec r : recs) {
            if (r.spawnSequence < sequence && (r.endSequence < 0 || sequence <= r.endSequence)) alive = r;
            else if (r.endSequence >= 0 && r.endSequence < sequence) { if (endedBefore == null || r.endSequence > endedBefore.endSequence) endedBefore = r; }
            else if (r.spawnSequence >= sequence) { if (later == null || r.spawnSequence < later.spawnSequence) later = r; }
        }
        if (alive != null) return resolve(alive, sequence);
        if (coverageBreakSequence >= 0 && coverageBreakSequence < sequence) return EntityLookup.miss(EntityLookup.Status.INCOMPLETE_COVERAGE, null, null, null, -1, -1, sequence, worldEpoch, "clientbound coverage break " + coverageBreakReason);
        if (capacityOverflowSequence >= 0 && capacityOverflowSequence < sequence) return EntityLookup.miss(EntityLookup.Status.INCOMPLETE_COVERAGE, null, null, null, -1, -1, sequence, worldEpoch, "entity capacity " + MAX_RETAINED + " reached at seq " + capacityOverflowSequence);
        // retained ended incarnations always ended after every forgotten one (forgotten oldest-first), so a retained DESPAWNED stays provable
        if (endedBefore != null) return EntityLookup.miss(EntityLookup.Status.DESPAWNED, endedBefore.key, endedBefore.type, endedBefore.uuid, endedBefore.spawnSequence, endedBefore.endSequence, sequence, worldEpoch, endedBefore.endCause.name());
        if (forgottenMaxEnd >= 0) return EntityLookup.miss(EntityLookup.Status.EVICTED, null, null, null, -1, -1, sequence, worldEpoch, "ended incarnations were evicted (capacity): absence unprovable");
        if (later != null) return EntityLookup.miss(EntityLookup.Status.PRE_SPAWN, later.key, later.type, later.uuid, later.spawnSequence, later.endSequence, sequence, worldEpoch, "spawned at seq " + later.spawnSequence);
        return EntityLookup.miss(EntityLookup.Status.UNKNOWN, null, null, null, -1, -1, sequence, worldEpoch, "never observed in epoch " + worldEpoch);
    }
    /** The network state of one incarnation just before {@code sequence}: an ended incarnation never resolves to a later reuse of its id (owner thread). */
    public EntityLookup lookupKey(EntityKey key, long sequence) {
        owner();
        EntityLookup scope = scope(key.session(), key.worldEpoch(), sequence); if (scope != null) return scope;
        Rec r = byIncarnation.get(key.incarnation());
        if (r == null || !r.key.equals(key)) {
            if (key.incarnation() < nextIncarnation) return EntityLookup.miss(EntityLookup.Status.EVICTED, key, null, null, -1, -1, sequence, worldEpoch, "incarnation no longer retained");
            return EntityLookup.miss(EntityLookup.Status.UNKNOWN, key, null, null, -1, -1, sequence, worldEpoch, "incarnation never issued");
        }
        if (sequence <= r.spawnSequence) return EntityLookup.miss(EntityLookup.Status.PRE_SPAWN, r.key, r.type, r.uuid, r.spawnSequence, r.endSequence, sequence, worldEpoch, "spawned at seq " + r.spawnSequence);
        if (r.endSequence >= 0 && sequence > r.endSequence) {
            for (Rec other : byId.getOrDefault(key.networkId(), List.of()))
                if (other != r && other.spawnSequence < sequence && (other.endSequence < 0 || sequence <= other.endSequence))
                    return EntityLookup.miss(EntityLookup.Status.ID_REUSED, r.key, r.type, r.uuid, r.spawnSequence, r.endSequence, sequence, worldEpoch, "id now incarnation " + other.key.incarnation());
            return EntityLookup.miss(EntityLookup.Status.DESPAWNED, r.key, r.type, r.uuid, r.spawnSequence, r.endSequence, sequence, worldEpoch, r.endCause.name());
        }
        return resolve(r, sequence);
    }
    /**
     * A typed range answer: {@code FOUND} = every revision of the incarnation with sequence in [from, to) is present, in order; any other status carries
     * the retained part only as an explicitly partial prefix - {@code EVICTED} (older revisions of the range aged out), {@code INCOMPLETE_COVERAGE}
     * (a coverage break inside the range; revisions before it), {@code QUERY_LIMIT} (more than {@code limit} matches; the first {@code limit}) - or
     * nothing (scope, unknown or evicted incarnation). An empty FOUND list means the incarnation provably had no revision in the range.
     */
    public record RevisionRange(EntityLookup.Status status, EntityKey key, long from, long to, List<EntityRevision> revisions, String detail) {
        public RevisionRange { Objects.requireNonNull(status); Objects.requireNonNull(key); revisions = List.copyOf(revisions); Objects.requireNonNull(detail); }
        public boolean complete() { return status == EntityLookup.Status.FOUND; }
    }
    public static final int MAX_RANGE_REVISIONS = MAX_REVISIONS;
    /**
     * The revisions of one incarnation with sequence in [from, to) - the network history a later consumer (Phase 12) may consider - at most {@code limit}
     * (1..{@value #MAX_RANGE_REVISIONS}) of them; see {@link RevisionRange} for the honesty contract (owner thread).
     */
    public RevisionRange revisions(EntityKey key, long from, long to, int limit) {
        owner();
        if (limit < 1 || limit > MAX_RANGE_REVISIONS || from < 0 || to < from) throw new IllegalArgumentException("range query");
        EntityLookup scope = scope(key.session(), key.worldEpoch(), to); if (scope != null) return new RevisionRange(scope.status(), key, from, to, List.of(), scope.detail());
        Rec r = byIncarnation.get(key.incarnation());
        if (r == null || !r.key.equals(key))
            return new RevisionRange(key.incarnation() < nextIncarnation ? EntityLookup.Status.EVICTED : EntityLookup.Status.UNKNOWN, key, from, to, List.of(), key.incarnation() < nextIncarnation ? "incarnation no longer retained" : "incarnation never issued");
        List<EntityRevision> out = new ArrayList<>(); boolean more = false;
        long cut = r.taintSequence >= 0 && r.taintSequence < to ? r.taintSequence : Long.MAX_VALUE; // nothing at or after a coverage break is claimed
        Iterable<EntityRevision> retained = r.deep != null ? r.deep : List.of(r.current);
        EntityRevision earliest = r.deep != null && !r.deep.isEmpty() ? r.deep.peekFirst() : r.current;
        for (EntityRevision v : retained) if (v.sequence() >= from && v.sequence() < to && v.sequence() < cut) { if (out.size() == limit) { more = true; break; } out.add(v); }
        rangeQueries++;
        // complete from 'from' only if the earliest retained revision is the spawn itself or not after 'from' (no evicted revision can fall inside the range)
        if (earliest.sequence() > from && earliest.sequence() != r.spawnSequence) return new RevisionRange(EntityLookup.Status.EVICTED, key, from, to, out, "revisions before seq " + earliest.sequence() + " aged out");
        if (cut != Long.MAX_VALUE) return new RevisionRange(EntityLookup.Status.INCOMPLETE_COVERAGE, key, from, to, out, "clientbound coverage break " + r.taintReason);
        if (more) { queryLimits++; return new RevisionRange(EntityLookup.Status.QUERY_LIMIT, key, from, to, out, "more than " + limit + " revisions in range"); }
        return new RevisionRange(EntityLookup.Status.FOUND, key, from, to, out, r.deep == null ? "summary" : "deep");
    }
    private EntityLookup scope(SessionKey querySession, long queryEpoch, long sequence) {
        if (!session.equals(querySession)) return EntityLookup.miss(EntityLookup.Status.SESSION_MISMATCH, null, null, null, -1, -1, sequence, worldEpoch, "other session");
        if (queryEpoch != worldEpoch || sequence <= epochStartSequence && epochStartSequence > 1) return EntityLookup.miss(EntityLookup.Status.WORLD_EPOCH_MISMATCH, null, null, null, -1, -1, sequence, worldEpoch, "query epoch " + queryEpoch + " vs " + worldEpoch);
        if (sequence > lastSequence + 1) return EntityLookup.miss(EntityLookup.Status.FUTURE_SEQUENCE, null, null, null, -1, -1, sequence, worldEpoch, "observed through seq " + lastSequence);
        if (closed) return EntityLookup.miss(EntityLookup.Status.SESSION_MISMATCH, null, null, null, -1, -1, sequence, worldEpoch, "session closed");
        return null;
    }
    private EntityLookup resolve(Rec r, long sequence) {
        if (r.taintSequence >= 0 && r.taintSequence < sequence) return EntityLookup.miss(EntityLookup.Status.INCOMPLETE_COVERAGE, r.key, r.type, r.uuid, r.spawnSequence, r.endSequence, sequence, worldEpoch, "clientbound coverage break " + r.taintReason);
        EntityRevision at = null;
        if (r.current.sequence() < sequence) at = r.current;
        else if (r.deep != null) { for (Iterator<EntityRevision> it = r.deep.descendingIterator(); it.hasNext();) { EntityRevision v = it.next(); if (v.sequence() < sequence) { at = v; break; } } }
        if (at == null) return EntityLookup.miss(EntityLookup.Status.EVICTED, r.key, r.type, r.uuid, r.spawnSequence, r.endSequence, sequence, worldEpoch, r.deep == null ? "summary keeps only the current revision" : "revision aged out of deep history");
        if (at.positionStatus() == EntityRevision.Status.UNSUPPORTED) return EntityLookup.miss(EntityLookup.Status.UNSUPPORTED, r.key, r.type, r.uuid, r.spawnSequence, r.endSequence, sequence, worldEpoch, at.cause());
        return new EntityLookup(EntityLookup.Status.FOUND, r.key, r.type, r.uuid, at, r.spawnSequence, r.endSequence, sequence, worldEpoch, r.deep == null ? "summary" : "deep");
    }

    // ---- published summary -----------------------------------------------------------------------------------------------------------------
    public record Status(long worldEpoch, long lastSequence, int live, int ended, int deep, long bytes, long generations, long removals, long replacements, long epochResets,
                         long historyEvictions, long capacityRefusals, long coverageBreaks, long updatesForUnknown, long promotions, long demotions, long relativeTeleports,
                         long unsupportedMotions, long evictionFloor, long coverageBreakSequence, String coverageBreakReason, long capacityOverflowSequence, long rangeQueries, long queryLimits) {}
    public Status status() {
        return new Status(worldEpoch, lastSequence, live.size(), ended.size(), deepCount, bytes, generations, removals, replacements, epochResets, historyEvictions, capacityRefusals,
            coverageBreaks, updatesForUnknown, promotions, demotions, relativeTeleports, unsupportedMotions, evictionFloor, coverageBreakSequence, coverageBreakReason, capacityOverflowSequence, rangeQueries, queryLimits);
    }
    public long worldEpoch() { return worldEpoch; }
    public long lastSequence() { return lastSequence; }
    public SessionKey session() { return session; }
    public CombatProfile profile() { return profile; }
    /** Live incarnations in network-id order (diagnostics / replay export; owner thread). */
    public List<EntityLookup> liveEntities() { return liveEntities(Integer.MAX_VALUE); }
    /** The first {@code limit} live incarnations in network-id order (a bounded diagnostics snapshot; owner thread). */
    public List<EntityLookup> liveEntities(int limit) {
        owner();
        List<Integer> ids = new ArrayList<>(live.keySet()); Collections.sort(ids); if (ids.size() > limit) ids = ids.subList(0, Math.max(0, limit));
        List<EntityLookup> out = new ArrayList<>(ids.size());
        for (int id : ids) { Rec r = live.get(id); out.add(new EntityLookup(EntityLookup.Status.FOUND, r.key, r.type, r.uuid, r.current, r.spawnSequence, r.endSequence, lastSequence + 1, worldEpoch, (r.deep == null ? "summary" : "deep " + r.deep.size()) + (r.taintSequence >= 0 ? " TAINTED@" + r.taintSequence : ""))); }
        return out;
    }
    /** Replay export view: every retained incarnation with its retained revisions, ordered by incarnation (owner thread). */
    List<Rec> retained() { owner(); List<Rec> all = new ArrayList<>(byIncarnation.values()); all.sort(Comparator.comparingLong(r -> r.key.incarnation())); return all; }
    long epochStartSequence() { return epochStartSequence; }
    long evictionFloorSequence() { return evictionFloor; }
    long coverageBreakAt() { return coverageBreakSequence; }
    String coverageBreakText() { return coverageBreakReason; }
    long capacityOverflowAt() { return capacityOverflowSequence; }
    long nextIncarnation() { return nextIncarnation; }
}
