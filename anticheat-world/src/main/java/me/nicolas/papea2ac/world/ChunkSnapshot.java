package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.ChunkKey;
import java.util.*;
/**
 * Immutable observer chunk: a fixed array of section snapshots (null = never observed section), coverage, the
 * sequence of the baseline that established it (-1 when there was none), the revision/sequence of its last
 * change, and a bounded block-entity summary. Structural sharing: {@link #withSection} copies only the section
 * reference array (a few dozen pointers) and replaces one entry; the untouched sections are the same immutable
 * objects as before. A historical ChunkSnapshot therefore stays exactly what it was.
 */
public final class ChunkSnapshot {
    public static final int MAX_BLOCK_ENTITIES = 256;
    public record BlockEntitySummary(long packedPos, String type, int encodedBytes, long sequence) {
        public BlockEntitySummary { Objects.requireNonNull(type); if (type.length() > 128 || encodedBytes < 0) throw new IllegalArgumentException("block entity summary"); }
    }
    private final ChunkKey key;
    private final SectionSnapshot[] sections;
    private final WorldCoverage coverage;
    private final long baselineSequence, createdSequence, revision, sequence;
    private final String coverageNote;
    private final Map<Long, BlockEntitySummary> blockEntities;

    ChunkSnapshot(ChunkKey key, SectionSnapshot[] sections, WorldCoverage coverage, long baselineSequence, long createdSequence, long revision, long sequence, String coverageNote, Map<Long, BlockEntitySummary> blockEntities) {
        this.key = Objects.requireNonNull(key); this.sections = sections; this.coverage = Objects.requireNonNull(coverage);
        this.baselineSequence = baselineSequence; this.createdSequence = createdSequence; this.revision = revision; this.sequence = sequence;
        this.coverageNote = Objects.requireNonNull(coverageNote); this.blockEntities = blockEntities;
    }
    public static ChunkSnapshot empty(ChunkKey key, int sectionCount, WorldCoverage coverage, long revision, long sequence, String note) {
        return new ChunkSnapshot(key, new SectionSnapshot[sectionCount], coverage, -1, sequence, revision, sequence, note, Map.of());
    }
    public ChunkKey key() { return key; }
    public int sectionCount() { return sections.length; }
    /** Null when that section was never observed. */
    public SectionSnapshot section(int index) { return index < 0 || index >= sections.length ? null : sections[index]; }
    public WorldCoverage coverage() { return coverage; }
    public String coverageNote() { return coverageNote; }
    public long baselineSequence() { return baselineSequence; }
    /** Sequence at which this observer first held any state for this chunk (in the current epoch). */
    public long createdSequence() { return createdSequence; }
    public long revision() { return revision; }
    public long sequence() { return sequence; }
    public Map<Long, BlockEntitySummary> blockEntities() { return blockEntities; }
    public int knownSections() { int n = 0; for (var s : sections) if (s != null) n++; return n; }

    public ChunkSnapshot withSection(int index, SectionSnapshot section, WorldCoverage coverage, long revision, long sequence) {
        SectionSnapshot[] copy = sections.clone(); copy[index] = section;
        return new ChunkSnapshot(key, copy, coverage, baselineSequence, createdSequence, revision, sequence, coverageNote, blockEntities);
    }
    public ChunkSnapshot withCoverage(WorldCoverage coverage, String note, long revision, long sequence) {
        return new ChunkSnapshot(key, sections, coverage, baselineSequence, createdSequence, revision, sequence, note, blockEntities);
    }
    public ChunkSnapshot withBlockEntity(BlockEntitySummary summary, long revision, long sequence) {
        var copy = new HashMap<>(blockEntities);
        if (copy.size() >= MAX_BLOCK_ENTITIES && !copy.containsKey(summary.packedPos())) return this; // bounded: a 257th entity is not tracked
        copy.put(summary.packedPos(), summary);
        return new ChunkSnapshot(key, sections, coverage, baselineSequence, createdSequence, revision, sequence, coverageNote, Map.copyOf(copy));
    }
    /** A complete baseline REPLACES everything: sections, coverage, block entities. Nothing stale is merged in. */
    static ChunkSnapshot baseline(ChunkKey key, SectionSnapshot[] sections, WorldCoverage coverage, long createdSequence, long revision, long sequence, String note) {
        return new ChunkSnapshot(key, sections, coverage, sequence, createdSequence, revision, sequence, note, Map.of());
    }
    /** Private (observer-charged) bytes: pointers, header, block entities. Shared section content is charged once globally, by the store. */
    public int privateChargedBytes(java.util.function.Predicate<SectionSnapshot> isPrivate) {
        int bytes = 96 + 8 * sections.length + 64 * blockEntities.size();
        for (var s : sections) if (s != null && isPrivate.test(s)) bytes += s.chargedBytes();
        return bytes;
    }
}
