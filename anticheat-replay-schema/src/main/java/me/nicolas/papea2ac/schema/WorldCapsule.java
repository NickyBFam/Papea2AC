package me.nicolas.papea2ac.schema;
import java.util.List;
import java.util.Objects;
/**
 * Observer-world archival projection (REPLAY_SYSTEM.md R2: replay-schema owns the DTO; anticheat-world's
 * WorldReplayMapper is the only writer/reader). A coherent STATE checkpoint of one observer world: dimension,
 * world epoch, every retained chunk with its coverage and per-section palette (canonical state keys, never
 * run-local ids) + packed indices + known-cell mask, tombstones with provenance. Coverage travels explicitly so
 * a PARTIAL chunk can never decode as COMPLETE and an unknown cell can never decode as air. No live section
 * arrays, no interned ids, no Paper/NMS type crosses this file.
 */
public record WorldCapsule(int schema, long sessionEpoch, long worldEpoch, boolean hasDimension, String dimensionId, int minY, int height,
                           long revision, long lastSequence, boolean transportBroken, String transportBreakReason,
                           List<Chunk> chunks, List<Tombstone> tombstones) {
    public static final int MAX_CHUNKS = 8192, MAX_SECTIONS = 256, MAX_PALETTE = 4096, MAX_PACKED = 1024;
    public WorldCapsule {
        Objects.requireNonNull(dimensionId); Objects.requireNonNull(transportBreakReason); Objects.requireNonNull(chunks); Objects.requireNonNull(tombstones);
        if (schema != 1 || sessionEpoch < 1 || worldEpoch < 0 || revision < 0 || lastSequence < 0) throw new IllegalArgumentException("world capsule");
        if (chunks.size() > MAX_CHUNKS || tombstones.size() > MAX_CHUNKS || transportBreakReason.length() > 64 || dimensionId.length() > 256) throw new IllegalArgumentException("world capsule bounds");
        if (hasDimension && (minY % 16 != 0 || height % 16 != 0 || height < 16 || height > 4064)) throw new IllegalArgumentException("world capsule dimension");
        chunks = List.copyOf(chunks); tombstones = List.copyOf(tombstones);
    }
    public record Section(int index, int bits, List<String> palette, List<Long> packed, List<Long> knownMask) {
        public Section {
            Objects.requireNonNull(palette); Objects.requireNonNull(packed); Objects.requireNonNull(knownMask);
            if (index < 0 || index >= MAX_SECTIONS || bits < 0 || bits > 12 || palette.isEmpty() || palette.size() > MAX_PALETTE || packed.size() > MAX_PACKED) throw new IllegalArgumentException("section dto");
            if (!knownMask.isEmpty() && knownMask.size() != 64) throw new IllegalArgumentException("known mask dto");
            palette = List.copyOf(palette); packed = List.copyOf(packed); knownMask = List.copyOf(knownMask);
        }
    }
    public record BlockEntity(long packedPos, String type, int encodedBytes, long sequence) {
        public BlockEntity { Objects.requireNonNull(type); if (type.length() > 128 || encodedBytes < 0) throw new IllegalArgumentException("block entity dto"); }
    }
    public record Chunk(int x, int z, String coverage, String note, long baselineSequence, long createdSequence, long revision, long sequence, int sectionCount, List<Section> sections, List<BlockEntity> blockEntities) {
        public Chunk {
            Objects.requireNonNull(coverage); Objects.requireNonNull(note); Objects.requireNonNull(sections); Objects.requireNonNull(blockEntities);
            if (sectionCount < 0 || sectionCount > MAX_SECTIONS || sections.size() > sectionCount || blockEntities.size() > 256 || note.length() > 64) throw new IllegalArgumentException("chunk dto");
            sections = List.copyOf(sections); blockEntities = List.copyOf(blockEntities);
        }
    }
    public record Tombstone(int x, int z, String reason, long sequence, long revision, long createdSequence) { public Tombstone { Objects.requireNonNull(reason); } }
}
