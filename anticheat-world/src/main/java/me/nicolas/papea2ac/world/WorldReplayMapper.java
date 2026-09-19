package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.schema.WorldCapsule;
import java.util.*;
/**
 * anticheat-world's local ReplayMapper (REPLAY_SYSTEM.md R2): ObserverWorld state <-> WorldCapsule. Exports a
 * coherent state checkpoint (WORLD_MODEL.md: "World's local ReplayMapper exports canonical data and restores only
 * after dictionary/closure validation"); run-local interned ids are translated to canonical keys on the way out
 * and re-interned on the way in, so a capsule restored in another run yields equal lookups even though the
 * integer ids differ. Coverage, notes, partial known-masks and tombstone provenance are carried verbatim: replay
 * never increases knowledge. The revision history ring is not archival and is not exported.
 */
public final class WorldReplayMapper {
    private WorldReplayMapper() {}

    public static WorldCapsule export(ObserverWorld world, BlockStateDictionary dictionary) {
        var chunks = new ArrayList<WorldCapsule.Chunk>();
        for (var chunk : world.chunksView().values()) {
            var sections = new ArrayList<WorldCapsule.Section>();
            for (int i = 0; i < chunk.sectionCount(); i++) {
                var s = chunk.section(i); if (s == null) continue;
                var palette = new ArrayList<String>(s.paletteSize());
                for (int p = 0; p < s.paletteSize(); p++) { var key = dictionary.key(s.paletteId(p)); palette.add(key == null ? "papea2ac:unresolved" : key.canonical()); }
                var packed = new ArrayList<Long>(s.packedLength()); for (int w = 0; w < s.packedLength(); w++) packed.add(s.packedWord(w));
                var mask = new ArrayList<Long>(); if (!s.complete()) for (int w = 0; w < 64; w++) mask.add(s.knownWord(w));
                sections.add(new WorldCapsule.Section(i, s.bitsPerEntry(), palette, packed, mask));
            }
            var entities = new ArrayList<WorldCapsule.BlockEntity>();
            for (var be : chunk.blockEntities().values()) entities.add(new WorldCapsule.BlockEntity(be.packedPos(), be.type(), be.encodedBytes(), be.sequence()));
            entities.sort(Comparator.comparingLong(WorldCapsule.BlockEntity::packedPos));
            chunks.add(new WorldCapsule.Chunk(chunk.key().x(), chunk.key().z(), chunk.coverage().name(), chunk.coverageNote(), chunk.baselineSequence(), chunk.createdSequence(),
                chunk.revision(), chunk.sequence(), chunk.sectionCount(), sections, entities));
        }
        chunks.sort(Comparator.comparingInt(WorldCapsule.Chunk::x).thenComparingInt(WorldCapsule.Chunk::z)); // deterministic order regardless of HashMap iteration
        var tombstones = new ArrayList<WorldCapsule.Tombstone>();
        for (var e : world.tombstonesView().entrySet()) tombstones.add(new WorldCapsule.Tombstone(e.getKey().x(), e.getKey().z(), e.getValue().reason().name(), e.getValue().sequence(), e.getValue().revision(), e.getValue().createdSequence()));
        tombstones.sort(Comparator.comparingInt(WorldCapsule.Tombstone::x).thenComparingInt(WorldCapsule.Tombstone::z));
        var dim = world.dimension();
        return new WorldCapsule(1, world.key().epoch(), world.worldEpoch(), dim.isPresent(), dim.map(DimensionProfile::dimensionId).orElse(""), dim.map(DimensionProfile::minY).orElse(0),
            dim.map(DimensionProfile::height).orElse(0), world.revision(), world.lastSequence(), world.transportBroken(), world.transportBreakReason(), chunks, tombstones);
    }

    /** Builds a fresh ObserverWorld holding exactly the projected state. Throws IllegalArgumentException on any closure/validation failure (nothing partial is installed). */
    public static ObserverWorld restore(WorldCapsule capsule, SessionKey key, WorldBudget budget, BlockStateDictionary dictionary) {
        if (key.epoch() != capsule.sessionEpoch()) throw new IllegalArgumentException("session epoch mismatch");
        var restoredChunks = new LinkedHashMap<ChunkKey, ChunkSnapshot>();
        for (var c : capsule.chunks()) {
            var sections = new SectionSnapshot[c.sectionCount()];
            for (var s : c.sections()) {
                if (sections[s.index()] != null) throw new IllegalArgumentException("duplicate section");
                int[] palette = new int[s.palette().size()];
                for (int p = 0; p < palette.length; p++) { palette[p] = dictionary.intern(BlockStateKey.parse(s.palette().get(p))); if (palette[p] < 0) throw new IllegalArgumentException("dictionary full"); }
                long[] packed = new long[s.packed().size()]; for (int w = 0; w < packed.length; w++) packed[w] = s.packed().get(w);
                long[] mask = null; if (!s.knownMask().isEmpty()) { mask = new long[64]; for (int w = 0; w < 64; w++) mask[w] = s.knownMask().get(w); }
                sections[s.index()] = SectionSnapshot.fromParts(palette, s.bits(), packed, mask);
            }
            var entities = new HashMap<Long, ChunkSnapshot.BlockEntitySummary>();
            for (var be : c.blockEntities()) entities.put(be.packedPos(), new ChunkSnapshot.BlockEntitySummary(be.packedPos(), be.type(), be.encodedBytes(), be.sequence()));
            var ck = new ChunkKey(c.x(), c.z());
            restoredChunks.put(ck, new ChunkSnapshot(ck, sections, WorldCoverage.valueOf(c.coverage()), c.baselineSequence(), c.createdSequence(), c.revision(), c.sequence(), c.note(), Map.copyOf(entities)));
        }
        var tombstones = new LinkedHashMap<ChunkKey, ObserverWorld.Tombstone>();
        for (var t : capsule.tombstones()) tombstones.put(new ChunkKey(t.x(), t.z()), new ObserverWorld.Tombstone(WorldCoverage.valueOf(t.reason()), t.sequence(), t.revision(), t.createdSequence()));
        var world = new ObserverWorld(key, budget, dictionary);
        world.restore(capsule.worldEpoch(), capsule.hasDimension() ? new DimensionProfile(capsule.dimensionId(), capsule.minY(), capsule.height()) : null,
            capsule.revision(), capsule.lastSequence(), capsule.transportBroken(), capsule.transportBreakReason(), restoredChunks, tombstones);
        return world;
    }
}
