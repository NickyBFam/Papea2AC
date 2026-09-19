package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.BlockPos;
import me.nicolas.papea2ac.model.BlockStateKey;
import java.util.*;
/**
 * World-module-owned input contract, fed by anticheat-core's WorldObservationMapper from Phase 1's ordered
 * Observation stream (the world module may not depend on protocol/packet - ARCHITECTURE.md DAG). Exactly the
 * clientbound families WORLD_MODEL.md assigns to the observer world: dimension entry, chunk baseline, block/section
 * updates, unload, block entities, view tracking, block events (pistons etc. recorded as observations only),
 * plus the coverage/lifecycle signals the model must record. Nothing here queries the live server world.
 */
public sealed interface WorldObservation {
    int chargedBytes();

    /** JoinGame or Respawn: the client's dimension identity and vertical range. respawn=true means a world-epoch discontinuity (respawn/dimension change); the initial JoinGame is not a discontinuity.
     *  (minY,height) = (0,0) means the range was NOT observed (a payload-schema-1 legacy record): the world then has no dimension profile and answers DIMENSION_UNKNOWN rather than guessing 0..255 or -64..320. */
    record DimensionEntered(String dimensionId, int minY, int height, boolean respawn) implements WorldObservation {
        public DimensionEntered { Objects.requireNonNull(dimensionId); if (minY != 0 || height != 0) new DimensionProfile(dimensionId, minY, height); }
        public boolean hasRange() { return height != 0; }
        public int chargedBytes() { return 64; }
    }
    /** Decoded section content: an indirect palette of canonical states plus the packed indices (wire layout). Read-only like protocol's ChunkSection. */
    final class SectionContent {
        private final int bits; private final List<BlockStateKey> palette; private final long[] packed; private final int nonEmptyBlockCount;
        public SectionContent(int nonEmptyBlockCount, List<BlockStateKey> palette, int bits, long[] packed) {
            Objects.requireNonNull(palette); Objects.requireNonNull(packed);
            if (palette.isEmpty() || palette.size() > SectionSnapshot.MAX_PALETTE || bits < 0 || bits > SectionSnapshot.MAX_BITS) throw new IllegalArgumentException("section content");
            if (bits == 0 ? (palette.size() != 1 || packed.length != 0) : (palette.size() > (1 << bits) || packed.length != SectionSnapshot.packedLongs(bits))) throw new IllegalArgumentException("section content storage");
            if (nonEmptyBlockCount < 0 || nonEmptyBlockCount > SectionSnapshot.BLOCKS) throw new IllegalArgumentException("section count");
            this.bits = bits; this.palette = List.copyOf(palette); this.packed = packed.clone(); this.nonEmptyBlockCount = nonEmptyBlockCount;
        }
        public int bitsPerEntry() { return bits; }
        public List<BlockStateKey> palette() { return palette; }
        public int packedLength() { return packed.length; }
        public long packedWord(int i) { return packed[i]; }
        public long[] copyPacked() { return packed.clone(); }
        public int nonEmptyBlockCount() { return nonEmptyBlockCount; }
        public int chargedBytes() { return 64 + 8 * packed.length + 16 * palette.size(); }
        @Override public boolean equals(Object o) { return o instanceof SectionContent s && bits == s.bits && nonEmptyBlockCount == s.nonEmptyBlockCount && palette.equals(s.palette) && Arrays.equals(packed, s.packed); }
        @Override public int hashCode() { return Objects.hash(bits, nonEmptyBlockCount, palette) * 31 + Arrays.hashCode(packed); }
    }
    /** A full chunk baseline. contentRetained=false: the chunk was sent but its sections were not carried (over cap / decode fault); sections is then empty. Sections are bottom-up from the dimension's minimum section. */
    record ChunkBaselineObserved(int chunkX, int chunkZ, boolean contentRetained, List<SectionContent> sections, int blockEntityCount, String note) implements WorldObservation {
        public ChunkBaselineObserved {
            Objects.requireNonNull(sections); Objects.requireNonNull(note);
            if (sections.size() > 256 || blockEntityCount < 0 || note.length() > 64) throw new IllegalArgumentException("chunk baseline");
            if (!contentRetained && !sections.isEmpty()) throw new IllegalArgumentException("unretained content cannot carry sections");
            sections = List.copyOf(sections);
        }
        public int chargedBytes() { int b = 96; for (var s : sections) b += s.chargedBytes(); return b; }
    }
    record BlockChanged(BlockPos position, BlockStateKey state) implements WorldObservation {
        public BlockChanged { Objects.requireNonNull(position); Objects.requireNonNull(state); }
        public int chargedBytes() { return 96; }
    }
    record BlockChange(BlockPos position, BlockStateKey state) { public BlockChange { Objects.requireNonNull(position); Objects.requireNonNull(state); } }
    /** One packet's logical batch for one section; applied atomically (all or nothing) in packet order. */
    record SectionBlocksChanged(int sectionX, int sectionY, int sectionZ, List<BlockChange> changes) implements WorldObservation {
        public SectionBlocksChanged { Objects.requireNonNull(changes); if (changes.size() > 4096) throw new IllegalArgumentException("section update count"); changes = List.copyOf(changes); }
        public int chargedBytes() { return 64 + 48 * changes.size(); }
    }
    record ChunkUnloaded(int chunkX, int chunkZ) implements WorldObservation { public int chargedBytes() { return 32; } }
    record BlockEntityObserved(BlockPos position, String type, int encodedBytes) implements WorldObservation {
        public BlockEntityObserved { Objects.requireNonNull(position); Objects.requireNonNull(type); if (type.length() > 128 || encodedBytes < 0) throw new IllegalArgumentException("block entity"); }
        public int chargedBytes() { return 64; }
    }
    record ViewCenter(int chunkX, int chunkZ) implements WorldObservation { public int chargedBytes() { return 32; } }
    record ViewRadius(int radius) implements WorldObservation {
        public ViewRadius { if (radius < 0 || radius > 32) throw new IllegalArgumentException("view radius"); }
        public int chargedBytes() { return 32; }
    }
    /** Block action event (piston extend/retract, note block, etc.): recorded as an observation for later temporal rules; never simulated here. */
    record BlockEventObserved(BlockPos position, String block, int action, int parameter) implements WorldObservation {
        public BlockEventObserved { Objects.requireNonNull(position); Objects.requireNonNull(block); if (block.length() > 128) throw new IllegalArgumentException("block event"); }
        public int chargedBytes() { return 64; }
    }
    /** Phase 1 transport health degraded at this point: every chunk touched from here on is TRANSPORT_INCOMPLETE until a new baseline. */
    record CoverageBreak(String reason) implements WorldObservation {
        public CoverageBreak { Objects.requireNonNull(reason); if (reason.length() > 64) throw new IllegalArgumentException("reason"); }
        public int chargedBytes() { return 32; }
    }
    record TickObserved(long serverTick) implements WorldObservation {
        public TickObserved { if (serverTick < 0) throw new IllegalArgumentException("serverTick"); }
        public int chargedBytes() { return 16; }
    }
    record SessionClosed() implements WorldObservation { public int chargedBytes() { return 16; } }
}
