package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.CapsuleCodec;
import me.nicolas.papea2ac.schema.TransportCapsule;
import me.nicolas.papea2ac.world.*;

/**
 * P3-C1 schema contract, git-free: pinned byte fixtures produced by the REAL Phase 2 encoder (proven identical by
 * LegacyCodecCompatibilityTest) decode under the current decoder; the encoder writes the current schema (3 since P11-F2) at both layers;
 * unknown future schemas fail clearly; the migration preserves replay hashes; legacy records without a dimension
 * range never become a guessed range in the world model.
 */
class CapsuleSchemaCompatibilityTest {
    // Real payload-schema-1 bytes (Phase 2 baseline f24824f9 encoder), pinned. Regenerate ONLY by re-running LegacyCodecCompatibilityTest against the baseline.
    static final String LEGACY_JOIN_GAME_HEX = "010b3b000000050000136d696e6563726166743a6f766572776f726c64000000140000000a0000000a0008535552564956414c0100";
    static final String LEGACY_RESPAWN_HEX = "010b4a00146d696e6563726166743a7468655f6e657468657201";
    static final String LEGACY_CHUNK_BASELINE_HEX = "010b4f00000003fffffffc00003039000000020000000401";
    static final String LEGACY_BLOCK_CHANGE_HEX = "010b120000000100000046ffffffef000f6d696e6563726166743a77617465720000000100056c6576656c000130";
    /** A whole schema-1 container (Phase 2 CapsuleCodec) holding one Outbound legacy JoinGame and a Close frame. */
    static final String LEGACY_CAPSULE_HEX = "5041433200000001000e7068617365322d6669787475726500403030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303000286632343832346639663565303039623833353139356639643562376566376435636234636562323400000000000000010001000000000002000000e201000000000000000a000000000000000500000000000000010000000000000000000000000000000000084f5554424f554e4400074e4554574f524b00000000000000000000000000000000ffffffffffffffff0004504c4159ffffffff00403030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303003000000000000000100000035010b3b000000050000136d696e6563726166743a6f766572776f726c64000000140000000a0000000a0008535552564956414c0100a36c41d3b423ae5acb02f2d72f364561c84a7dd0cbf425f4a7906213ed4264e7000000090400000000000000146fbca3b925d0c3d7c20ceaeaab400c724dfce4e1459b275082df5a7697015cd69e6259ab241d6d4a72b3c498dd414347cad7a0a39c86146bee60af7b2e789738";
    static byte[] hex(String s) { return HexFormat.of().parseHex(s); }

    @Test void theNewEncoderWritesTheCurrentSchemaAtBothLayersAndTheLimitsAgree() throws Exception {
        assertEquals(3, PayloadCodec.SCHEMA); assertEquals(3, TransportCapsule.CURRENT_SCHEMA); assertEquals(1, PayloadCodec.LEGACY_SCHEMA); assertEquals(1, TransportCapsule.LEGACY_SCHEMA);
        assertEquals(3, PayloadCodec.encode(new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false))[0]);
        var capsule = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, "p", "0".repeat(64), "x", 1, false, List.of(new TransportCapsule.Close(1)), true);
        byte[] bytes = CapsuleCodec.encode(capsule);
        assertEquals(3, new DataInputStream(new ByteArrayInputStream(bytes, 4, 4)).readInt(), "container schema int after the magic");
        assertEquals(3, CapsuleCodec.decode(bytes).schema());
        assertEquals(PayloadCodec.LIMIT, TransportCapsule.Payload.MAX_BYTES, "every payload the codec can produce must fit a capsule payload slot");
    }

    @Test void pinnedSchema1PayloadsDecodeUnderTheSchema2Decoder() throws Exception {
        var join = assertInstanceOf(SemanticPayload.JoinGame.class, PayloadCodec.decode(hex(LEGACY_JOIN_GAME_HEX)));
        assertEquals(5, join.playerId()); assertEquals("minecraft:overworld", join.dimension()); assertFalse(join.hasDimensionRange());
        var respawn = assertInstanceOf(SemanticPayload.Respawn.class, PayloadCodec.decode(hex(LEGACY_RESPAWN_HEX)));
        assertEquals("minecraft:the_nether", respawn.dimension()); assertFalse(respawn.hasDimensionRange());
        var chunk = assertInstanceOf(SemanticPayload.ChunkBaseline.class, PayloadCodec.decode(hex(LEGACY_CHUNK_BASELINE_HEX)));
        assertEquals(3, chunk.chunkX()); assertEquals(-4, chunk.chunkZ()); assertFalse(chunk.contentRetained()); assertEquals("NOT_DECODED", chunk.contentNote());
        assertEquals(LegacyCodecCompatibilityTest.NEW_BLOCK_CHANGE, PayloadCodec.decode(hex(LEGACY_BLOCK_CHANGE_HEX)));
        // re-encoding a legacy record under the current schema is a different byte stream (schema byte + explicit 0,0 range), decodable again
        byte[] reencoded = PayloadCodec.encode(join); assertEquals(PayloadCodec.SCHEMA, reencoded[0]); assertEquals(join, PayloadCodec.decode(reencoded));
    }

    @Test void pinnedSchema1CapsuleDecodesAndItsLegacyJoinGameLeavesTheWorldDimensionUnknown() throws Exception {
        var capsule = CapsuleCodec.decode(hex(LEGACY_CAPSULE_HEX));
        assertEquals(1, capsule.schema()); assertEquals(2, capsule.frames().size());
        var key = new SessionKey(new UUID(31, 1), 1);
        var observation = TransportReplayMapper.restore((TransportCapsule.Input) capsule.frames().getFirst(), key);
        var mapped = WorldObservationMapper.map(observation);
        var entered = assertInstanceOf(WorldObservation.DimensionEntered.class, mapped.getFirst());
        assertFalse(entered.hasRange());
        var world = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary());
        world.apply(key, entered, 1, 0, null);
        assertTrue(world.dimension().isEmpty(), "no range observed = no dimension profile, never a guessed one");
        assertEquals(BlockLookup.Status.DIMENSION_UNKNOWN, world.blockAt(new BlockPos(0, 64, 0)).status());
        // a legacy Respawn is still an epoch discontinuity even though its range is unknown
        world.apply(key, new WorldObservation.DimensionEntered("minecraft:the_nether", 0, 0, true), 2, 0, null);
        assertEquals(1, world.worldEpoch()); assertTrue(world.dimension().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new WorldObservation.DimensionEntered("x", 16, 0, false), "a half-specified range is still invalid");
    }

    @Test void unknownFutureSchemasFailClearlyAtBothLayers() throws Exception {
        var capsule = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, "p", "0".repeat(64), "x", 1, false, List.of(new TransportCapsule.Close(1)), true);
        byte[] bytes = CapsuleCodec.encode(capsule);
        byte[] future = bytes.clone(); future[7] = 4; // schema int 3 -> 4, then re-seal the trailing checksum
        byte[] body = Arrays.copyOf(future, future.length - 32); byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
        System.arraycopy(digest, 0, future, future.length - 32, 32);
        assertEquals("unsupported capsule schema 4", assertThrows(IOException.class, () -> CapsuleCodec.decode(future)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new TransportCapsule(4, "p", "0".repeat(64), "x", 1, false, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new TransportCapsule(0, "p", "0".repeat(64), "x", 1, false, List.of(), true));
        byte[] payload = PayloadCodec.encode(new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false)); payload[0] = 4;
        assertEquals("unsupported payload schema 4", assertThrows(IOException.class, () -> PayloadCodec.decode(payload)).getMessage());
    }

    @Test void theMigrationPreservesThePinnedPhase1ConformanceDigests() throws Exception {
        Path artifacts = Path.of(System.getProperty("repo.root"), "docs", "phase1-artifacts");
        var schema1 = CapsuleCodec.decode(Base64.getDecoder().decode(Files.readString(artifacts.resolve("transport-conformance.capsule.b64")).strip()));
        assertEquals(1, schema1.schema(), "the pinned Phase 1 artifact is a schema-1 capsule: OLD FORMAT -> NEW DECODER");
        var expected = Files.readAllLines(artifacts.resolve("transport-conformance.expected.txt"));
        assertEquals(expected, new TransportReplayDriver().replay(schema1));
        // the same frames re-sealed as a current-schema container replay to the identical digests
        var schema2 = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, schema1.profile(), schema1.configurationDigest(), schema1.sourceRevision(), schema1.epoch(), schema1.qualifiedFixture(), schema1.frames(), schema1.prefixComplete(), schema1.incompleteReason());
        var roundTrip = CapsuleCodec.decode(CapsuleCodec.encode(schema2)); assertEquals(TransportCapsule.CURRENT_SCHEMA, roundTrip.schema());
        assertEquals(expected, new TransportReplayDriver().replay(roundTrip), "migration preserves original hashes");
    }

    /** The reason the payload bound moved with the schema: a realistic maximal chunk baseline must fit a capsule payload, or recordings would go EXPORT_ENCODE_FAILURE on ordinary terrain. */
    @Test void aRealisticMaximalChunkBaselineFitsACapsulePayload() throws Exception {
        var sections = new ArrayList<SemanticPayload.ChunkSection>();
        for (int s = 0; s < 24; s++) {
            var palette = new ArrayList<SemanticPayload.BlockState>();
            for (int p = 0; p < 16; p++) palette.add(new SemanticPayload.BlockState("minecraft:polished_blackstone_brick_stairs", List.of(new SemanticPayload.Property("facing", "north"), new SemanticPayload.Property("half", "bottom"), new SemanticPayload.Property("shape", "inner_left"), new SemanticPayload.Property("waterlogged", "false"), new SemanticPayload.Property("p" + p, "v" + p))));
            sections.add(new SemanticPayload.ChunkSection(4096, 0, palette, 4, new long[256]));
        }
        var baseline = new SemanticPayload.ChunkBaseline(0, 0, 60000, 0, 4, true, true, sections, "");
        assertTrue(baseline.sectionBytes() > SemanticPayload.ChunkBaseline.INLINE_CONTENT_CAP, "deliberately above the normalizer's inline cap - the worst shape the codec could ever be asked to carry");
        byte[] bytes = PayloadCodec.encode(baseline);
        assertTrue(bytes.length <= TransportCapsule.Payload.MAX_BYTES, "encoded " + bytes.length);
        var key = new SessionKey(new UUID(31, 2), 1);
        var input = TransportReplayMapper.export(new Observation(key, 1, 0, 0, new Observation.Outbound(1, baseline)), 0, 0);
        assertEquals(baseline, ((Observation.Outbound) TransportReplayMapper.restore(input, key).event()).payload());
    }

    /** Schema 3: the ONLY changes are semantic tags 80 (P11-F2, EntityPositionSync) and 81 (P11-F9, LegacyEntityPosition). Schema-2 bytes of every other
     *  tag decode exactly as before; tags 80/81 are rejected inside schema-1/2 bytes (they cannot appear there legitimately); a schema-1/2 tag 16 with an
     *  EMPTY relative set - a sync or an absolute teleport, which that schema did not distinguish - decodes as the explicitly ambiguous
     *  LegacyEntityPosition and re-encodes (tag 81) without changing meaning; a schema-2 container still decodes; tags 80/81 round-trip under schema 3. */
    @Test void schema3AddsOnlyTheSyncAndLegacyPositionTags() throws Exception {
        var sync = new SemanticPayload.EntityPositionSync(9, new PacketPayload.Position(1.5, 64, -2.25), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(90f, 0f), true);
        byte[] bytes = PayloadCodec.encode(sync); assertEquals(3, bytes[0]); assertEquals(sync, PayloadCodec.decode(bytes));
        byte[] asSchema2 = bytes.clone(); asSchema2[0] = 2;
        assertEquals("semantic tag 80 requires payload schema 3", assertThrows(IOException.class, () -> PayloadCodec.decode(asSchema2)).getMessage());
        var absolute = new SemanticPayload.EntityPosition(9, new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(5f, 6f), Set.of(), true);
        byte[] absoluteBytes = PayloadCodec.encode(absolute); assertEquals(absolute, PayloadCodec.decode(absoluteBytes), "schema 3: an empty relative set is an absolute teleport");
        byte[] legacyBytes = absoluteBytes.clone(); legacyBytes[0] = 2;
        var legacy = new SemanticPayload.LegacyEntityPosition(9, new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(5f, 6f), true);
        assertEquals(legacy, PayloadCodec.decode(legacyBytes), "schema 2: sync or absolute teleport, kept ambiguous");
        legacyBytes[0] = 1; assertEquals(legacy, PayloadCodec.decode(legacyBytes), "schema 1 alike");
        byte[] reencoded = PayloadCodec.encode(legacy); assertEquals(3, reencoded[0]); assertEquals(legacy, PayloadCodec.decode(reencoded), "tag 81 keeps the ambiguity across a re-encode");
        byte[] reencodedAs2 = reencoded.clone(); reencodedAs2[0] = 2;
        assertEquals("semantic tag 81 requires payload schema 3", assertThrows(IOException.class, () -> PayloadCodec.decode(reencodedAs2)).getMessage());
        assertEquals(new SemanticPayload.EntityPosition(9, new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(5f, 6f), Set.of(), true).chargedBytes(),
            legacy.chargedBytes(), "charged exactly like the tag-16 record it was, so replayed journal accounting is unchanged");
        var teleport = new SemanticPayload.EntityPosition(9, new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), Set.of(SemanticPayload.Relative.X), false);
        for (PacketPayload p : List.of(teleport, new SemanticPayload.Attack(4), new SemanticPayload.EntityDelta(3, (short) 5, (short) -5, (short) 0, new PacketPayload.Rotation(0f, 0f), true, false, true),
                new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false))) {
            byte[] b = PayloadCodec.encode(p); b[0] = 2; assertEquals(p, PayloadCodec.decode(b), "a schema-2 stream of an unchanged tag decodes identically: " + p.getClass().getSimpleName());
        }
        var old = new TransportCapsule(2, "p", "0".repeat(64), "x", 1, false, List.of(new TransportCapsule.Close(1)), true);
        assertEquals(2, CapsuleCodec.decode(CapsuleCodec.encode(old)).schema(), "a schema-2 container still decodes");
    }
}
