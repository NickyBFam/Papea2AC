package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.world.*;

/**
 * Section 57 (version/registry): block-state identity in the world model is the platform-independent canonical
 * key (block id + sorted properties), never a native numeric state id or a run-local dictionary integer. The same
 * logical state reached through different wire/native representations - property order, palette slot, global vs
 * indirect palette, dictionary id assignment - resolves to one semantic identity. (The native global-id -> BlockState
 * path itself is proven against the pinned jar in NativeChunkSectionTest.)
 */
class BlockStateIdentityTest {
    @Test void propertyOrderOnTheWireDoesNotChangeIdentity() {
        var a = new SemanticPayload.BlockState("minecraft:oak_stairs", List.of(new SemanticPayload.Property("waterlogged", "false"), new SemanticPayload.Property("facing", "north"), new SemanticPayload.Property("shape", "straight"), new SemanticPayload.Property("half", "bottom")));
        var b = new SemanticPayload.BlockState("minecraft:oak_stairs", List.of(new SemanticPayload.Property("facing", "north"), new SemanticPayload.Property("half", "bottom"), new SemanticPayload.Property("shape", "straight"), new SemanticPayload.Property("waterlogged", "false")));
        assertEquals(a, b, "Phase 1 already sorts properties at construction");
        var ka = mapped(a); var kb = mapped(b);
        assertEquals(ka, kb); assertEquals(STAIRS, ka); assertEquals(STAIRS.canonical(), ka.canonical());
        assertEquals(STAIRS, BlockStateKey.parse(ka.canonical()), "canonical form round-trips");
        assertEquals(BlockStateKey.parse("minecraft:water[level=0]"), new BlockStateKey("minecraft:water", new TreeMap<>(Map.of("level", "0"))));
    }

    @Test void dictionaryIdsAreRunLocalAndNeverPartOfIdentity() {
        var d1 = new BlockStateDictionary(); var d2 = new BlockStateDictionary();
        d2.intern(BlockStateKey.of("minecraft:bedrock")); d2.intern(BlockStateKey.of("minecraft:sand"));
        int s1 = d1.intern(STONE), s2 = d2.intern(STONE);
        assertNotEquals(s1, s2, "different runs assign different integers");
        assertEquals(d1.key(s1), d2.key(s2), "...for the same identity");
        assertEquals(s1, d1.intern(STONE), "interning is idempotent within a run");
        assertEquals(s1, d1.intern(BlockStateKey.parse("minecraft:stone")), "and representation-independent");
    }

    @Test void paletteSlotAndBitWidthAreStorageDetailsNotIdentity() {
        // The same logical section content encoded three ways: [stone, dirt] at 4 bits, [dirt, stone] at 4 bits, and the same cells at 8 bits (an over-wide palette a different writer might emit).
        java.util.function.IntFunction<BlockStateKey> f = idx -> (idx & 1) == 0 ? STONE : DIRT;
        var narrow = section(f);
        var swapped = new WorldObservation.SectionContent(narrow.nonEmptyBlockCount(), List.of(DIRT, STONE), 4, invert(narrow.copyPacked(), 4));
        var wide = widen(narrow, 8);
        var key = new SessionKey(new UUID(57, 1), 1);
        var results = new ArrayList<List<BlockStateKey>>();
        for (var content : List.of(narrow, swapped, wide)) {
            var dict = new BlockStateDictionary(); if (content == wide) dict.intern(BlockStateKey.of("minecraft:perturb"));
            var w = world(key, new WorldBudget(), dict, 1);
            var sections = new ArrayList<WorldObservation.SectionContent>(); for (int i = 0; i < OVERWORLD_SECTIONS; i++) sections.add(i == 8 ? content : uniformSection(AIR));
            w.apply(key, baseline(0, 0, sections), 2, 2, null);
            var seen = new ArrayList<BlockStateKey>(); for (int i = 0; i < 64; i++) seen.add(w.blockAt(new BlockPos(i & 15, 64 + ((i >> 4) & 15), (i >> 8) & 15)).state());
            results.add(seen);
            assertEquals(WorldReplayMapper.export(w, dict).chunks().getFirst().sections().get(8).palette().size() <= 2, true, "projection carries canonical keys only");
        }
        assertEquals(results.get(0), results.get(1), "palette order is not identity"); assertEquals(results.get(0), results.get(2), "bit width is not identity");
        assertEquals(STONE, results.get(0).get(0)); assertEquals(DIRT, results.get(0).get(1));
    }

    @Test void theSameStateFromABaselineAndFromABlockUpdateIsOneIdentity() {
        var dict = new BlockStateDictionary(); var key = new SessionKey(new UUID(57, 2), 1);
        var w = world(key, new WorldBudget(), dict, 1);
        w.apply(key, baseline(0, 0), 2, 2, null);
        var fromBaseline = w.blockAt(new BlockPos(0, 0, 0)); // stone from the palette
        w.apply(key, new WorldObservation.BlockChanged(new BlockPos(0, 100, 0), BlockStateKey.parse("minecraft:stone")), 3, 3, null);
        var fromUpdate = w.blockAt(new BlockPos(0, 100, 0));
        assertEquals(fromBaseline.state(), fromUpdate.state()); assertEquals(fromBaseline.stateId(), fromUpdate.stateId(), "one dictionary id per identity within a run");
    }

    /** The key the real Phase 1 -> Phase 3 mapper produces for a clientbound block change carrying this state. */
    static BlockStateKey mapped(SemanticPayload.BlockState state) {
        var key = new SessionKey(new UUID(57, 0), 1);
        var obs = new Observation(key, 1, 0, 0, new Observation.Outbound(1, new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(0, 0, 0), state)));
        return ((WorldObservation.BlockChanged) me.nicolas.papea2ac.core.WorldObservationMapper.map(obs).getFirst()).state();
    }
    static long[] invert(long[] packed, int bits) { long[] out = packed.clone(); int perLong = 64 / bits; long mask = (1L << bits) - 1; for (int i = 0; i < 4096; i++) { int w = i / perLong, sh = (i % perLong) * bits; long v = (out[w] >>> sh) & mask; out[w] = (out[w] & ~(mask << sh)) | ((1 - v) << sh); } return out; }
    static WorldObservation.SectionContent widen(WorldObservation.SectionContent c, int bits) {
        int oldBits = c.bitsPerEntry(), oldPer = 64 / oldBits, per = 64 / bits; long oldMask = (1L << oldBits) - 1; long[] src = c.copyPacked(), out = new long[SectionSnapshot.packedLongs(bits)];
        for (int i = 0; i < 4096; i++) { long v = (src[i / oldPer] >>> ((i % oldPer) * oldBits)) & oldMask; out[i / per] |= v << ((i % per) * bits); }
        return new WorldObservation.SectionContent(c.nonEmptyBlockCount(), c.palette(), bits, out);
    }
}
