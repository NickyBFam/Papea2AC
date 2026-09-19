package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.*;
import java.util.*;

/**
 * One player's OBSERVED world (WORLD_MODEL.md W1): what server->client traffic told this client, reduced in
 * Phase 1 sequence order. Never a query of the live server world. Owned by the session's actor thread
 * (single-writer asserted like TruthActor/PlayerTimeline); one instance per PacketEngine.Session, so a reconnect
 * starts a brand-new observer world and old snapshots can never become the replacement's current state.
 *
 * State: dimension profile (from JoinGame/Respawn; absent = capture began mid-session), world epoch (bumped on
 * respawn/dimension change, which drops every chunk of the old epoch), current chunks, tombstones (UNLOADED /
 * EVICTED provenance), a bounded revision history of replaced sections, and private-byte accounting against the
 * budget's tier. Complete baseline sections live in the shared content-addressed store; copy-on-write sections
 * produced by block updates are private to this observer.
 *
 * Coverage never heals accidentally: PARTIAL/EVICTED/TRANSPORT_INCOMPLETE chunks only become COMPLETE again
 * through a new full baseline with retained content while transport is healthy.
 *
 * Threading: apply()/queries are owner-thread-only; {@link #snapshot()} returns the last published immutable
 * WorldSnapshot (published on tick/structural changes, PLAYER_STATE.md's bounded cadence) for other threads.
 */
public final class ObserverWorld {
    public static final int HISTORY_MAX_ENTRIES = 4096, TOMBSTONE_MAX = 4096, BLOCK_EVENT_MAX = 256;
    public static final long HISTORY_HORIZON_NANOS = 3_000_000_000L;

    /** One replaced section: {@code before} is the section as it was until {@code sequence} (null = did not exist). */
    public record SectionRevision(ChunkKey chunk, int sectionIndex, long sequence, long revision, SectionSnapshot before, long nanos, boolean beforeShared) {}
    public record Tombstone(WorldCoverage reason, long sequence, long revision, long createdSequence) {}
    public record BlockEvent(BlockPos position, String block, int action, int parameter, long sequence) {}

    private final SessionKey key;
    private final WorldBudget budget;
    private final BlockStateDictionary dictionary;
    private Thread owner;
    private DimensionProfile dimension;
    private long worldEpoch, epochStartSequence = 1, revision, lastSequence, firstSequence = -1;
    private int lastSubIndex = -1;
    private final HashMap<ChunkKey, ChunkSnapshot> chunks = new HashMap<>();
    private final LinkedHashMap<ChunkKey, Tombstone> tombstones = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ChunkKey, Tombstone> e) { return size() > TOMBSTONE_MAX; }
    };
    private final ArrayDeque<SectionRevision> history = new ArrayDeque<>();
    private long historyBytes, historyEvictions, historyEvictedMaxSequence = -1;
    private final ArrayDeque<BlockEvent> blockEvents = new ArrayDeque<>();
    private long privateBytes;
    /** transportBroken = at least one transport break happened in this session (diagnostic); coverage itself is per chunk (P3-F8). */
    private boolean transportBroken, closed;
    private String transportBreakReason = ""; private long transportBreaks, lastBreakSequence;
    private ChunkKey viewCenter; private int viewRadius = -1;
    private long unknownLookups, evictedChunks, unloadedChunks, budgetRejections, droppedUpdates;
    /** Bounded diagnostic: how many block updates (single + batched cells) were applied and the last one (canonical state @ x,y,z seq) - a summary line, never a dump. */
    private long blockUpdates;
    public static final int RECENT_BLOCK_UPDATES = 8;
    // Ring of the last updates as primitives (packed position, dictionary id, sequence, batch size): nothing is formatted on the hot path; publish() renders the strings.
    private final long[] recentPos = new long[RECENT_BLOCK_UPDATES], recentSeq = new long[RECENT_BLOCK_UPDATES]; private final int[] recentState = new int[RECENT_BLOCK_UPDATES], recentBatch = new int[RECENT_BLOCK_UPDATES];
    private int recentHead, recentCount;
    private void noteBlockUpdate(BlockPos pos, int stateId, long sequence, int batch) {
        int i = recentHead; recentPos[i] = pos.packed(); recentState[i] = stateId; recentSeq[i] = sequence; recentBatch[i] = batch; recentHead = (i + 1) % RECENT_BLOCK_UPDATES; if (recentCount < RECENT_BLOCK_UPDATES) recentCount++;
    }
    private List<String> renderRecent() {
        if (recentCount == 0) return List.of();
        var out = new ArrayList<String>(recentCount);
        for (int k = 0; k < recentCount; k++) {
            int i = (recentHead - recentCount + k + RECENT_BLOCK_UPDATES) % RECENT_BLOCK_UPDATES; var pos = BlockPos.unpack(recentPos[i]); var key = dictionary.key(recentState[i]);
            out.add((key == null ? "papea2ac:unresolved" : key.canonical()) + " @ " + pos.x() + "," + pos.y() + "," + pos.z() + " seq " + recentSeq[i] + (recentBatch[i] > 1 ? " (batch of " + recentBatch[i] + ")" : ""));
        }
        return out;
    }
    private volatile WorldSnapshot published;

    public ObserverWorld(SessionKey key, WorldBudget budget, BlockStateDictionary dictionary) {
        this.key = Objects.requireNonNull(key); this.budget = Objects.requireNonNull(budget); this.dictionary = Objects.requireNonNull(dictionary);
        budget.observerWorlds.incrementAndGet();
        publish();
    }
    private void assertOwner() {
        if (owner == null) owner = Thread.currentThread();
        else if (owner != Thread.currentThread()) throw new IllegalStateException("observer world ownership");
    }
    public SessionKey key() { return key; }
    public long worldEpoch() { return worldEpoch; }
    public Optional<DimensionProfile> dimension() { return Optional.ofNullable(dimension); }
    public long revision() { return revision; }
    public long lastSequence() { return lastSequence; }
    public long privateBytes() { return privateBytes; }
    public boolean transportBroken() { return transportBroken; }
    public long transportBreaks() { return transportBreaks; }
    public long lastBreakSequence() { return lastBreakSequence; }

    // ------------------------------------------------------------------ apply

    public boolean apply(SessionKey observedFor, WorldObservation observation, long sequence, long nanos, String transportDegradeReason) { return apply(observedFor, observation, sequence, 0, nanos, transportDegradeReason); }
    /**
     * Returns false (a no-op) for a stale session key, a closed world, or a non-monotonic (sequence, subIndex) pair.
     * subIndex orders several observations carried by ONE Phase 1 observation (a bundle's children) without
     * inventing a second sequence space. transportDegradeReason: Phase 1's sticky health reason, null when healthy.
     */
    public boolean apply(SessionKey observedFor, WorldObservation observation, long sequence, int subIndex, long nanos, String transportDegradeReason) {
        assertOwner();
        Objects.requireNonNull(observation);
        if (!key.equals(observedFor) || closed) return false;
        if (sequence < 1 || subIndex < 0 || sequence < lastSequence || (sequence == lastSequence && subIndex <= lastSubIndex)) return false;
        noteGap(sequence, nanos);
        lastSequence = sequence; lastSubIndex = subIndex; if (firstSequence < 0) firstSequence = sequence;
        // A NEW sticky degrade reason riding on an ordinary observation is a break; an explicit CoverageBreak observation is the event itself (never counted twice).
        if (transportDegradeReason != null && !(observation instanceof WorldObservation.CoverageBreak) && !transportDegradeReason.equals(transportBreakReason)) breakCoverage(transportDegradeReason, sequence, nanos);
        expireHistory(nanos);
        switch (observation) {
            case WorldObservation.DimensionEntered d -> enterDimension(d, sequence, nanos);
            case WorldObservation.ChunkBaselineObserved b -> baseline(b, sequence, nanos);
            case WorldObservation.BlockChanged c -> blockChange(c.position(), c.state(), sequence, nanos);
            case WorldObservation.SectionBlocksChanged s -> sectionChange(s, sequence, nanos);
            case WorldObservation.ChunkUnloaded u -> unload(new ChunkKey(u.chunkX(), u.chunkZ()), sequence, nanos);
            case WorldObservation.BlockEntityObserved be -> blockEntity(be, sequence);
            case WorldObservation.ViewCenter v -> viewCenter = new ChunkKey(v.chunkX(), v.chunkZ());
            case WorldObservation.ViewRadius v -> viewRadius = v.radius();
            case WorldObservation.BlockEventObserved e -> { if (blockEvents.size() >= BLOCK_EVENT_MAX) blockEvents.removeFirst(); blockEvents.addLast(new BlockEvent(e.position(), e.block(), e.action(), e.parameter(), sequence)); }
            case WorldObservation.CoverageBreak b -> breakCoverage(b.reason(), sequence, nanos);
            case WorldObservation.TickObserved ignored -> publish();
            case WorldObservation.SessionClosed ignored -> close();
        }
        enforcePrivateBudget(sequence, nanos);
        if (!(observation instanceof WorldObservation.BlockChanged) && !(observation instanceof WorldObservation.SectionBlocksChanged) && !(observation instanceof WorldObservation.BlockEventObserved)) publish();
        return true;
    }

    /**
     * Session-sequence marker for an ordered observation that carried NO world semantics (a movement claim, an outcome,
     * a control change). Advances the "observed through" bound without a revision, so a historical lookup at any
     * session sequence up to the last one the shard delivered is answerable - Phase 2 records and Phase 3 lookups
     * then share one sequence space (section 44), instead of a player record at S facing FUTURE_SEQUENCE from a world
     * that merely had nothing to change at S. Same stale-key/ordering rules as apply(); never publishes.
     */
    public boolean observeSequence(SessionKey observedFor, long sequence, long nanos) {
        assertOwner();
        if (!key.equals(observedFor) || closed) return false;
        if (sequence < 1 || sequence <= lastSequence) return false;
        noteGap(sequence, nanos);
        lastSequence = sequence; lastSubIndex = -1; if (firstSequence < 0) firstSequence = sequence;
        expireHistory(nanos);
        return true;
    }

    private void enterDimension(WorldObservation.DimensionEntered d, long sequence, long nanos) {
        if (d.respawn()) {
            // Explicit discontinuity: nothing from the previous epoch may resolve against the new dimension's chunks.
            for (var chunk : chunks.values()) releaseChunk(chunk);
            chunks.clear(); tombstones.clear(); dropHistory();
            worldEpoch++; epochStartSequence = sequence;
        }
        dimension = d.hasRange() ? new DimensionProfile(d.dimensionId(), d.minY(), d.height()) : null; // no range observed = no profile (never a guessed one)
        revision++; budget.revisions.incrementAndGet();
    }

    private void baseline(WorldObservation.ChunkBaselineObserved b, long sequence, long nanos) {
        var ck = new ChunkKey(b.chunkX(), b.chunkZ());
        int sectionCount = dimension != null ? dimension.sectionCount() : b.sections().size();
        ChunkSnapshot previous = chunks.get(ck);
        long created = previous != null ? previous.createdSequence() : sequence;
        revision++; budget.revisions.incrementAndGet();
        var sections = new SectionSnapshot[sectionCount];
        WorldCoverage coverage; String note;
        if (!b.contentRetained()) { coverage = WorldCoverage.CONTENT_NOT_RETAINED; note = b.note(); }
        else if (dimension != null && b.sections().size() != sectionCount) { coverage = WorldCoverage.CONTENT_NOT_RETAINED; note = "SECTION_COUNT_MISMATCH:" + b.sections().size() + "/" + sectionCount; }
        else {
            coverage = WorldCoverage.COMPLETE; note = ""; // a full baseline received after a break is a fresh authoritative view of THIS chunk: coverage recovers per chunk, only through this qualifying observation (P3-F8)
            var acquired = new ArrayList<SectionSnapshot>(sections.length);
            for (int i = 0; i < b.sections().size(); i++) {
                var content = b.sections().get(i);
                int[] palette = new int[content.palette().size()];
                boolean resolved = true;
                for (int p = 0; p < palette.length; p++) { palette[p] = dictionary.intern(content.palette().get(p)); if (palette[p] < 0) resolved = false; }
                SectionSnapshot canonical = null;
                if (resolved) {
                    try { canonical = budget.acquire(SectionSnapshot.fromPalette(palette, content.bitsPerEntry(), content.copyPacked())); }
                    catch (IllegalArgumentException malformed) { canonical = null; note = "MALFORMED_SECTION"; }
                } else note = "DICTIONARY_FULL";
                if (canonical == null) { for (var s : acquired) budget.release(s); acquired.clear(); coverage = WorldCoverage.CONTENT_NOT_RETAINED; if (note.isEmpty()) note = "SHARED_BUDGET"; budgetRejections++; break; }
                acquired.add(canonical);
            }
            if (coverage != WorldCoverage.CONTENT_NOT_RETAINED) for (int i = 0; i < acquired.size(); i++) sections[i] = acquired.get(i);
        }
        if (previous != null) {
            for (int i = 0; i < previous.sectionCount(); i++) if (previous.section(i) != null) pushHistory(new SectionRevision(ck, i, sequence, revision, previous.section(i), nanos, budget.isShared(previous.section(i))));
            privateBytes -= previous.privateChargedBytes(this::isPrivate);
            budget.chunks.decrementAndGet(); adjustCoverageCounters(previous.coverage(), -1); budget.sections.addAndGet(-previous.knownSections());
        }
        var chunk = ChunkSnapshot.baseline(ck, sections, coverage, created, revision, sequence, note);
        chunks.put(ck, chunk); tombstones.remove(ck);
        privateBytes += chunk.privateChargedBytes(this::isPrivate);
        budget.chunks.incrementAndGet(); adjustCoverageCounters(coverage, +1); budget.sections.addAndGet(chunk.knownSections());
    }

    private void blockChange(BlockPos pos, BlockStateKey state, long sequence, long nanos) {
        if (dimension == null) { droppedUpdates++; return; } // cannot place absolute Y; lookups already report DIMENSION_UNKNOWN
        int index = dimension.sectionIndexOf(pos.y());
        if (index < 0) return;
        int stateId = dictionary.intern(state);
        if (stateId < 0) { breakCoverage("DICTIONARY_FULL", sequence, nanos); return; }
        var ck = pos.chunk();
        ChunkSnapshot chunk = chunkForUpdate(ck, sequence);
        SectionSnapshot before = chunk.section(index);
        SectionSnapshot base = before != null ? before : SectionSnapshot.unknown();
        SectionSnapshot after = base.with(pos.sectionIndex(), stateId);
        commitSection(chunk, index, before, after, sequence, nanos);
        blockUpdates++; noteBlockUpdate(pos, stateId, sequence, 1);
    }

    private void sectionChange(WorldObservation.SectionBlocksChanged s, long sequence, long nanos) {
        if (dimension == null) { droppedUpdates++; return; }
        int index = dimension.sectionIndexOfSectionY(s.sectionY());
        if (index < 0) return;
        var ck = new ChunkKey(s.sectionX(), s.sectionZ());
        // Validate the whole batch before touching anything: an update is atomic per packet.
        if (s.changes().isEmpty()) return; // nothing observed: never commit an empty placeholder section
        int[] ids = new int[s.changes().size()];
        for (int i = 0; i < ids.length; i++) {
            var c = s.changes().get(i);
            if (!c.position().chunk().equals(ck) || c.position().sectionY() != s.sectionY()) return;
            ids[i] = dictionary.intern(c.state());
            if (ids[i] < 0) { breakCoverage("DICTIONARY_FULL", sequence, nanos); return; }
        }
        ChunkSnapshot chunk = chunkForUpdate(ck, sequence);
        SectionSnapshot before = chunk.section(index);
        int[] indices = new int[ids.length]; for (int i = 0; i < ids.length; i++) indices[i] = s.changes().get(i).position().sectionIndex();
        SectionSnapshot after = (before != null ? before : SectionSnapshot.unknown()).withAll(indices, ids); // one copy for the whole batch
        commitSection(chunk, index, before, after, sequence, nanos);
        blockUpdates += ids.length; noteBlockUpdate(s.changes().get(ids.length - 1).position(), ids[ids.length - 1], sequence, ids.length);
    }

    /** The chunk an update applies to: the existing one, or a fresh PARTIAL chunk. A tombstoned (unloaded/evicted) chunk is NOT resurrected as complete - it comes back PARTIAL until a new baseline. */
    private ChunkSnapshot chunkForUpdate(ChunkKey ck, long sequence) {
        ChunkSnapshot chunk = chunks.get(ck);
        if (chunk != null) return chunk;
        revision++; budget.revisions.incrementAndGet();
        Tombstone t = tombstones.remove(ck);
        String note = t == null ? "NO_BASELINE" : "AFTER_" + t.reason();
        chunk = ChunkSnapshot.empty(ck, dimension.sectionCount(), WorldCoverage.PARTIAL, revision, sequence, note);
        chunks.put(ck, chunk); privateBytes += chunk.privateChargedBytes(this::isPrivate);
        budget.chunks.incrementAndGet(); adjustCoverageCounters(chunk.coverage(), +1);
        return chunk;
    }

    private void commitSection(ChunkSnapshot chunk, int index, SectionSnapshot before, SectionSnapshot after, long sequence, long nanos) {
        revision++; budget.revisions.incrementAndGet();
        WorldCoverage coverage = chunk.coverage(); // updates never heal and never break: a TRANSPORT_INCOMPLETE chunk stays so until a full baseline
        boolean beforeShared = before != null && budget.isShared(before);
        pushHistory(new SectionRevision(chunk.key(), index, sequence, revision, before, nanos, beforeShared)); // history now owns before's shared ref (if any)
        if (before != null && !beforeShared) privateBytes -= before.chargedBytes();
        privateBytes += after.chargedBytes(); if (before == null) budget.sections.incrementAndGet();
        var updated = chunk.withSection(index, after, coverage, revision, sequence);
        if (coverage != chunk.coverage()) { adjustCoverageCounters(chunk.coverage(), -1); adjustCoverageCounters(coverage, +1); }
        chunks.put(chunk.key(), updated);
    }

    private void unload(ChunkKey ck, long sequence, long nanos) {
        ChunkSnapshot chunk = chunks.remove(ck);
        revision++; budget.revisions.incrementAndGet();
        if (chunk != null) {
            for (int i = 0; i < chunk.sectionCount(); i++) if (chunk.section(i) != null) pushHistory(new SectionRevision(ck, i, sequence, revision, chunk.section(i), nanos, budget.isShared(chunk.section(i))));
            privateBytes -= chunk.privateChargedBytes(this::isPrivate);
            budget.chunks.decrementAndGet(); adjustCoverageCounters(chunk.coverage(), -1); budget.sections.addAndGet(-chunk.knownSections());
        }
        tombstones.put(ck, new Tombstone(WorldCoverage.UNLOADED, sequence, revision, chunk != null ? chunk.createdSequence() : sequence)); unloadedChunks++; budget.unloadedChunks.incrementAndGet();
    }

    private void blockEntity(WorldObservation.BlockEntityObserved be, long sequence) {
        if (dimension == null || !dimension.containsY(be.position().y())) return;
        var ck = be.position().chunk();
        ChunkSnapshot chunk = chunkForUpdate(ck, sequence);
        revision++; budget.revisions.incrementAndGet();
        var updated = chunk.withBlockEntity(new ChunkSnapshot.BlockEntitySummary(be.position().packed(), be.type(), be.encodedBytes(), sequence), revision, sequence);
        privateBytes += updated.privateChargedBytes(this::isPrivate) - chunk.privateChargedBytes(this::isPrivate);
        chunks.put(ck, updated);
    }

    /**
     * The ingress consumes a sequence number for every capture, including one it had to drop (queue full), so a
     * dropped clientbound observation is visible here as a gap in the session sequence. The world detects it itself
     * because Phase 1's HealthLatch keeps only the FIRST degrade reason (in a real session: EXPIRED within ~0.5 s) and
     * would never report a later loss (P3-F3, real-client qualification).
     */
    private void noteGap(long sequence, long nanos) {
        if (lastSequence > 0 && sequence > lastSequence + 1) { breakCoverage("SEQUENCE_GAP:" + (lastSequence + 1), sequence, nanos); publish(); } // structural: publish even on the world-silent path
    }
    /**
     * Transport break EVENT (a sequence gap, an ingress overload, a lost server observation ...): any update to a chunk held at
     * this moment may have been missed, so every currently held chunk stops being a trustworthy client view UNTIL a new full
     * baseline of that chunk arrives (baseline() then restores COMPLETE for that chunk only). Nothing else heals: block updates
     * keep a TRANSPORT_INCOMPLETE chunk incomplete, and chunks never re-sent stay incomplete for the session. This is per-chunk
     * coverage, not a sticky session state (P3-F8, real-client qualification: the sticky form refused qualifying baselines).
     * Idempotent: a break that changes no chunk (all already incomplete) costs no revision (P3-F3).
     */
    private void breakCoverage(String reason, long sequence, long nanos) {
        transportBroken = true; transportBreakReason = reason; transportBreaks++; lastBreakSequence = sequence; budget.coverageBreaks.incrementAndGet();
        boolean changed = false; long rev = revision + 1;
        for (var e : new ArrayList<>(chunks.entrySet())) {
            var c = e.getValue();
            if (c.coverage() != WorldCoverage.TRANSPORT_INCOMPLETE) { adjustCoverageCounters(c.coverage(), -1); adjustCoverageCounters(WorldCoverage.TRANSPORT_INCOMPLETE, +1); chunks.put(e.getKey(), c.withCoverage(WorldCoverage.TRANSPORT_INCOMPLETE, reason, rev, sequence)); changed = true; }
        }
        if (changed) { revision = rev; budget.revisions.incrementAndGet(); }
    }

    // ------------------------------------------------------------------ budget / history

    private boolean isPrivate(SectionSnapshot s) { return !budget.isShared(s); }
    private void pushHistory(SectionRevision r) {
        history.addLast(r);
        if (r.before() != null && !r.beforeShared()) historyBytes += r.before().chargedBytes();
        while (history.size() > HISTORY_MAX_ENTRIES) evictOldestHistory();
    }
    private void evictOldestHistory() {
        var r = history.removeFirst();
        if (r.before() != null) { if (r.beforeShared()) budget.release(r.before()); else historyBytes -= r.before().chargedBytes(); }
        historyEvictions++; budget.historyEvictions.incrementAndGet(); historyEvictedMaxSequence = Math.max(historyEvictedMaxSequence, r.sequence());
    }
    private void expireHistory(long nanos) { while (!history.isEmpty() && nanos - history.peekFirst().nanos() >= HISTORY_HORIZON_NANOS) evictOldestHistory(); }
    private void dropHistory() { while (!history.isEmpty()) evictOldestHistory(); }
    private void releaseChunk(ChunkSnapshot chunk) {
        // Charge is settled BEFORE the shared refs go: a release that drops a section to zero refs makes isPrivate() true for it, and it must not be subtracted as private (P3 property-test finding).
        privateBytes -= chunk.privateChargedBytes(this::isPrivate);
        for (int i = 0; i < chunk.sectionCount(); i++) { var s = chunk.section(i); if (s != null && budget.isShared(s)) budget.release(s); }
        budget.chunks.decrementAndGet(); adjustCoverageCounters(chunk.coverage(), -1); budget.sections.addAndGet(-chunk.knownSections());
    }
    /** Tier enforcement: drop history first (optional forensic depth), then the farthest chunks from the view center, tombstoning them EVICTED (never UNLOADED - provenance differs). */
    private void enforcePrivateBudget(long sequence, long nanos) {
        int limit = budget.privateLimit();
        while (privateBytes + historyBytes > limit && !history.isEmpty()) evictOldestHistory();
        while (privateBytes + historyBytes > limit && !chunks.isEmpty()) {
            ChunkKey farthest = null; int best = -1;
            for (var ck : chunks.keySet()) { int d = viewCenter == null ? 0 : ck.distanceTo(viewCenter); if (d > best) { best = d; farthest = ck; } }
            var chunk = chunks.remove(farthest);
            revision++; budget.revisions.incrementAndGet();
            releaseChunk(chunk);
            tombstones.put(farthest, new Tombstone(WorldCoverage.EVICTED, sequence, revision, chunk.createdSequence())); evictedChunks++; budget.evictedChunks.incrementAndGet();
        }
    }
    private void adjustCoverageCounters(WorldCoverage c, int delta) {
        if (c == WorldCoverage.COMPLETE) budget.completeChunks.addAndGet(delta);
        else if (c == WorldCoverage.PARTIAL) budget.partialChunks.addAndGet(delta);
    }

    /** Releases every shared reference and all private charge; global accounting returns to its pre-session baseline. */
    public void close() {
        assertOwner();
        if (closed) return;
        closed = true;
        for (var chunk : chunks.values()) releaseChunk(chunk);
        chunks.clear(); dropHistory(); tombstones.clear(); blockEvents.clear();
        budget.observerWorlds.decrementAndGet();
        publish();
    }
    public boolean closed() { return closed; }
    /** Supervisor path for a dead shard: releases everything once the owning thread is proven terminated (never while it might still be running). */
    public void retireAfterOwnerTermination() {
        if (owner != null && owner.isAlive()) throw new IllegalStateException("owner still alive");
        owner = Thread.currentThread();
        close();
    }

    // ------------------------------------------------------------------ queries (owner thread)

    public Optional<ChunkSnapshot> chunk(ChunkKey ck) { assertOwner(); return Optional.ofNullable(chunks.get(ck)); }
    public Optional<Tombstone> tombstone(ChunkKey ck) { assertOwner(); return Optional.ofNullable(tombstones.get(ck)); }
    public int chunkCount() { assertOwner(); return chunks.size(); }
    public int historySize() { assertOwner(); return history.size(); }
    public long historyEvictions() { assertOwner(); return historyEvictions; }
    public long evictedChunks() { assertOwner(); return evictedChunks; }
    public List<BlockEvent> blockEvents() { assertOwner(); return List.copyOf(blockEvents); }
    public WorldCoverage coverageOf(ChunkKey ck) {
        assertOwner();
        var c = chunks.get(ck); if (c != null) return c.coverage();
        var t = tombstones.get(ck); return t != null ? t.reason() : WorldCoverage.UNKNOWN;
    }

    public BlockLookup blockAt(BlockPos pos) { assertOwner(); return lookupCurrent(pos, chunks.get(pos.chunk()), tombstones.get(pos.chunk())); }

    private BlockLookup lookupCurrent(BlockPos pos, ChunkSnapshot chunk, Tombstone tomb) {
        if (dimension == null) return miss(BlockLookup.Status.DIMENSION_UNKNOWN, WorldCoverage.UNKNOWN);
        int index = dimension.sectionIndexOf(pos.y());
        if (index < 0) return miss(BlockLookup.Status.OUTSIDE_DIMENSION, chunk != null ? chunk.coverage() : WorldCoverage.UNKNOWN);
        if (chunk == null) return tomb == null ? miss(BlockLookup.Status.UNKNOWN_CHUNK, WorldCoverage.UNKNOWN)
                : miss(tomb.reason() == WorldCoverage.UNLOADED ? BlockLookup.Status.UNLOADED : BlockLookup.Status.EVICTED, tomb.reason());
        return lookupInChunk(chunk, index, pos.sectionIndex());
    }
    private BlockLookup lookupInChunk(ChunkSnapshot chunk, int sectionIndex, int cellIndex) {
        if (chunk.coverage() == WorldCoverage.TRANSPORT_INCOMPLETE) return miss(BlockLookup.Status.TRANSPORT_INCOMPLETE, chunk.coverage());
        var section = chunk.section(sectionIndex);
        if (section == null) return miss(chunk.coverage() == WorldCoverage.CONTENT_NOT_RETAINED ? BlockLookup.Status.CONTENT_NOT_RETAINED : BlockLookup.Status.UNKNOWN_SECTION, chunk.coverage());
        int id = section.stateIdAt(cellIndex);
        if (id < 0) return miss(BlockLookup.Status.UNKNOWN_CELL, chunk.coverage());
        return BlockLookup.known(id, dictionary.key(id), chunk.revision(), chunk.coverage());
    }
    private BlockLookup miss(BlockLookup.Status status, WorldCoverage coverage) { unknownLookups++; budget.unknownLookups.incrementAndGet(); return BlockLookup.miss(status, coverage); }

    /**
     * The block as this observer knew it right after processing {@code sequence}. Exact semantics, never nearest:
     * FUTURE_SEQUENCE beyond the last commit; WRONG_WORLD_EPOCH before the current epoch began (old-epoch history
     * is not retained); HISTORY_NOT_RETAINED when a revision that could have changed the answer was evicted.
     */
    public BlockLookup blockAtSequence(BlockPos pos, long sequence) {
        assertOwner();
        if (sequence > lastSequence) return miss(BlockLookup.Status.FUTURE_SEQUENCE, WorldCoverage.UNKNOWN);
        if (sequence < epochStartSequence || (firstSequence >= 0 && sequence < firstSequence)) return miss(BlockLookup.Status.WRONG_WORLD_EPOCH, WorldCoverage.UNKNOWN);
        if (dimension == null) return miss(BlockLookup.Status.DIMENSION_UNKNOWN, WorldCoverage.UNKNOWN);
        int index = dimension.sectionIndexOf(pos.y());
        if (index < 0) return miss(BlockLookup.Status.OUTSIDE_DIMENSION, WorldCoverage.UNKNOWN);
        var ck = pos.chunk();
        // Oldest revision of this section committed AFTER the requested sequence: its "before" is the state at that sequence.
        SectionRevision candidate = null;
        for (var r : history) if (r.chunk().equals(ck) && r.sectionIndex() == index && r.sequence() > sequence && (candidate == null || r.sequence() < candidate.sequence())) candidate = r;
        var chunk = chunks.get(ck); var tomb = tombstones.get(ck);
        long created = chunk != null ? chunk.createdSequence() : tomb != null ? tomb.createdSequence() : Long.MAX_VALUE;
        if (created > sequence) return miss(BlockLookup.Status.UNKNOWN_CHUNK, WorldCoverage.UNKNOWN); // the chunk did not exist for this observer yet
        if (candidate != null) {
            if (historyEvictedMaxSequence > sequence && historyEvictedMaxSequence < candidate.sequence()) return miss(BlockLookup.Status.HISTORY_NOT_RETAINED, WorldCoverage.UNKNOWN);
            if (candidate.before() == null) return miss(BlockLookup.Status.UNKNOWN_SECTION, WorldCoverage.PARTIAL);
            int id = candidate.before().stateIdAt(pos.sectionIndex());
            return id < 0 ? miss(BlockLookup.Status.UNKNOWN_CELL, WorldCoverage.PARTIAL) : BlockLookup.known(id, dictionary.key(id), candidate.revision() - 1, WorldCoverage.PARTIAL);
        }
        if (historyEvictedMaxSequence > sequence) return miss(BlockLookup.Status.HISTORY_NOT_RETAINED, WorldCoverage.UNKNOWN);
        if (chunk != null) {
            return lookupInChunk(chunk, index, pos.sectionIndex());
        }
        if (tomb != null && tomb.sequence() > sequence) return miss(BlockLookup.Status.HISTORY_NOT_RETAINED, tomb.reason());
        return lookupCurrent(pos, null, tomb);
    }

    // ------------------------------------------------------------------ replay projection hooks (WorldReplayMapper only)

    Map<ChunkKey, ChunkSnapshot> chunksView() { assertOwner(); return Collections.unmodifiableMap(chunks); }
    Map<ChunkKey, Tombstone> tombstonesView() { assertOwner(); return Collections.unmodifiableMap(tombstones); }
    String transportBreakReason() { return transportBreakReason; }
    long epochStartSequence() { return epochStartSequence; }
    /** Restore path: installs a projected state into a fresh world. Only valid before any apply(). Shared sections are (re)acquired so accounting stays exact. */
    void restore(long worldEpoch, DimensionProfile dimension, long revision, long lastSequence, boolean transportBroken, String reason, Map<ChunkKey, ChunkSnapshot> restoredChunks, Map<ChunkKey, Tombstone> restoredTombstones) {
        assertOwner();
        if (this.lastSequence > 0 || !chunks.isEmpty() || this.revision != 0) throw new IllegalStateException("restore into a used world");
        this.worldEpoch = worldEpoch; this.dimension = dimension; this.revision = revision; this.lastSequence = lastSequence; this.firstSequence = lastSequence > 0 ? 1 : -1;
        this.transportBroken = transportBroken; this.transportBreakReason = reason; this.epochStartSequence = 1;
        for (var e : restoredChunks.entrySet()) {
            var chunk = e.getValue(); var sections = new SectionSnapshot[chunk.sectionCount()];
            for (int i = 0; i < sections.length; i++) { var s = chunk.section(i); if (s == null) continue; sections[i] = s.complete() ? budget.acquire(s) : s; if (sections[i] == null) sections[i] = s; }
            var installed = new ChunkSnapshot(chunk.key(), sections, chunk.coverage(), chunk.baselineSequence(), chunk.createdSequence(), chunk.revision(), chunk.sequence(), chunk.coverageNote(), chunk.blockEntities());
            chunks.put(e.getKey(), installed); privateBytes += installed.privateChargedBytes(this::isPrivate);
            budget.chunks.incrementAndGet(); adjustCoverageCounters(installed.coverage(), +1); budget.sections.addAndGet(installed.knownSections());
        }
        tombstones.putAll(restoredTombstones);
        publish();
    }

    // ------------------------------------------------------------------ publication

    public record WorldSnapshot(long worldEpoch, DimensionProfile dimension, long revision, long lastSequence, int chunkCount, int completeChunks, int partialChunks,
                                int contentNotRetainedChunks, int transportIncompleteChunks, long evictedChunks, long unloadedChunks, long privateBytes, long historyBytes,
                                int historyEntries, long historyEvictions, boolean transportBroken, String transportBreakReason, long transportBreaks, long lastBreakSequence, long blockUpdates, String lastBlockUpdate, List<String> recentBlockUpdates, boolean closed, Map<ChunkKey, ChunkSnapshot> chunks) {
        /** Cross-thread block lookup against this immutable snapshot (no tombstone/history detail: those are owner-thread queries). */
        public BlockLookup blockAt(BlockPos pos, BlockStateDictionary dictionary) {
            if (dimension == null) return BlockLookup.miss(BlockLookup.Status.DIMENSION_UNKNOWN, WorldCoverage.UNKNOWN);
            int index = dimension.sectionIndexOf(pos.y());
            if (index < 0) return BlockLookup.miss(BlockLookup.Status.OUTSIDE_DIMENSION, WorldCoverage.UNKNOWN);
            var chunk = chunks.get(pos.chunk());
            if (chunk == null) return BlockLookup.miss(BlockLookup.Status.UNKNOWN_CHUNK, WorldCoverage.UNKNOWN);
            if (chunk.coverage() == WorldCoverage.TRANSPORT_INCOMPLETE) return BlockLookup.miss(BlockLookup.Status.TRANSPORT_INCOMPLETE, chunk.coverage());
            var section = chunk.section(index);
            if (section == null) return BlockLookup.miss(chunk.coverage() == WorldCoverage.CONTENT_NOT_RETAINED ? BlockLookup.Status.CONTENT_NOT_RETAINED : BlockLookup.Status.UNKNOWN_SECTION, chunk.coverage());
            int id = section.stateIdAt(pos.sectionIndex());
            return id < 0 ? BlockLookup.miss(BlockLookup.Status.UNKNOWN_CELL, chunk.coverage()) : BlockLookup.known(id, dictionary.key(id), chunk.revision(), chunk.coverage());
        }
    }
    private void publish() {
        var recent = renderRecent();
        int complete = 0, partial = 0, notRetained = 0, transport = 0;
        for (var c : chunks.values()) switch (c.coverage()) { case COMPLETE -> complete++; case PARTIAL -> partial++; case CONTENT_NOT_RETAINED -> notRetained++; case TRANSPORT_INCOMPLETE -> transport++; default -> {} }
        published = new WorldSnapshot(worldEpoch, dimension, revision, lastSequence, chunks.size(), complete, partial, notRetained, transport, evictedChunks, unloadedChunks,
            privateBytes, historyBytes, history.size(), historyEvictions, transportBroken, transportBreakReason, transportBreaks, lastBreakSequence, blockUpdates, recent.isEmpty() ? "" : recent.getLast(), recent, closed, Map.copyOf(chunks));
    }
    /** Safe from any thread: the last published immutable snapshot (bounded cadence: ticks and structural changes). */
    public WorldSnapshot snapshot() { return published; }
    /** Owner-thread: force publication (used at cadence points the caller controls, e.g. after a batch of updates). */
    public void publishNow() { assertOwner(); publish(); }
}
