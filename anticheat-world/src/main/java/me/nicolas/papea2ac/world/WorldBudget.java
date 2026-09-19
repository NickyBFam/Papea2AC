package me.nicolas.papea2ac.world;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
/**
 * Global observer-world accounting (PERFORMANCE.md: shared world content 96 MiB reference partition, charged once;
 * private per-session tier 768 KiB floor / 2 MiB target). Two mechanisms:
 *
 * 1. A content-addressed SHARED SECTION STORE: identical complete baseline sections (the same chunk sent to many
 *    players is byte-identical) are stored once and reference-counted. Shared sections are immutable and
 *    content-equal by construction, so sharing can never erase player-specific coverage/revision semantics - those
 *    live in each observer's ChunkSnapshot, which only references the shared section. This is the "shared
 *    immutable baseline + per-observer delta" hybrid WORLD_MODEL.md W2 describes.
 * 2. Per-observer PRIVATE charge (section pointers, chunk headers, copy-on-write sections created by block
 *    updates, revision history), capped per tier and enforced by ObserverWorld's eviction.
 *
 * When the shared store is full, acquire() returns null and the caller records CONTENT_NOT_RETAINED for that
 * section - traffic is never blocked and no block state is fabricated. Counters are fixed-shape, no per-player labels.
 */
public final class WorldBudget {
    public static final long SHARED_PARTITION_BYTES = 96L << 20;
    public static final int PRIVATE_FLOOR_BYTES = 768 * 1024, PRIVATE_TARGET_BYTES = 2 * 1024 * 1024;

    private static final class Entry { final SectionSnapshot section; final AtomicInteger refs = new AtomicInteger(); Entry(SectionSnapshot s) { section = s; } }
    private final ConcurrentHashMap<SectionSnapshot, Entry> shared = new ConcurrentHashMap<>();
    private final long sharedLimit; private final int privateLimit;
    private final AtomicLong sharedBytes = new AtomicLong();
    // fixed-shape global counters
    public final AtomicLong observerWorlds = new AtomicLong(), chunks = new AtomicLong(), completeChunks = new AtomicLong(), partialChunks = new AtomicLong(),
        evictedChunks = new AtomicLong(), unloadedChunks = new AtomicLong(), sections = new AtomicLong(), revisions = new AtomicLong(), historyEvictions = new AtomicLong(),
        unknownLookups = new AtomicLong(), coverageBreaks = new AtomicLong(), budgetRejections = new AtomicLong(), privateBytes = new AtomicLong(), sharedHits = new AtomicLong();

    public WorldBudget() { this(SHARED_PARTITION_BYTES, PRIVATE_FLOOR_BYTES); }
    public WorldBudget(long sharedLimit, int privateLimit) {
        if (sharedLimit < 1 || (privateLimit != PRIVATE_FLOOR_BYTES && privateLimit != PRIVATE_TARGET_BYTES && privateLimit < 1)) throw new IllegalArgumentException("budget");
        this.sharedLimit = sharedLimit; this.privateLimit = privateLimit;
    }
    public int privateLimit() { return privateLimit; }
    public long sharedLimit() { return sharedLimit; }
    public long sharedBytes() { return sharedBytes.get(); }
    public int sharedSections() { return shared.size(); }

    /** Canonical shared instance for content-equal complete sections, or null when the partition cannot admit it. Increments the reference count. */
    public SectionSnapshot acquire(SectionSnapshot candidate) {
        if (!candidate.complete()) throw new IllegalArgumentException("only complete sections are shared");
        while (true) {
            Entry existing = shared.get(candidate);
            if (existing != null) {
                int refs = existing.refs.get();
                if (refs > 0 && existing.refs.compareAndSet(refs, refs + 1)) { sharedHits.incrementAndGet(); return existing.section; }
                if (refs <= 0) { shared.remove(candidate, existing); continue; } // being released concurrently; retry
                continue;
            }
            int bytes = candidate.chargedBytes();
            long before = sharedBytes.get();
            if (before + bytes > sharedLimit) { budgetRejections.incrementAndGet(); return null; }
            if (!sharedBytes.compareAndSet(before, before + bytes)) continue;
            Entry fresh = new Entry(candidate); fresh.refs.set(1);
            Entry raced = shared.putIfAbsent(candidate, fresh);
            if (raced == null) return candidate;
            sharedBytes.addAndGet(-bytes); // someone else inserted first; use theirs
        }
    }
    public boolean isShared(SectionSnapshot section) { Entry e = shared.get(section); return e != null && e.section == section; }
    /** Decrements the reference count; the last release frees the partition bytes. Idempotence is the caller's responsibility (ObserverWorld tracks exactly which references it holds). */
    public void release(SectionSnapshot section) {
        Entry e = shared.get(section);
        if (e == null || e.section != section) return;
        int left = e.refs.decrementAndGet();
        if (left == 0 && shared.remove(section, e)) sharedBytes.addAndGet(-section.chargedBytes());
    }
    public record Snapshot(long observerWorlds, long chunks, long completeChunks, long partialChunks, long evictedChunks, long unloadedChunks, long sections,
                           long revisions, long historyEvictions, long unknownLookups, long coverageBreaks, long budgetRejections, long privateBytes,
                           long sharedBytes, int sharedSections, long sharedHits, long sharedLimit, int privateLimit) {}
    public Snapshot snapshot() {
        return new Snapshot(observerWorlds.get(), chunks.get(), completeChunks.get(), partialChunks.get(), evictedChunks.get(), unloadedChunks.get(), sections.get(),
            revisions.get(), historyEvictions.get(), unknownLookups.get(), coverageBreaks.get(), budgetRejections.get(), privateBytes.get(),
            sharedBytes.get(), shared.size(), sharedHits.get(), sharedLimit, privateLimit);
    }
}
