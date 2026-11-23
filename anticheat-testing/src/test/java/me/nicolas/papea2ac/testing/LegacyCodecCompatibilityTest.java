package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.CapsuleCodec;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * P3-C1: compatibility against the REAL Phase 2 baseline codec, not a re-implementation. The baseline sources of
 * PacketPayload/SemanticPayload/PayloadCodec/SemanticCodec/PayloadDigest/TransportCapsule/CapsuleCodec are taken from
 * git at the Phase 2 baseline commit, compiled at test time and loaded in an isolated class loader, so
 * OLD FORMAT -> NEW DECODER uses the genuine old encoder and NEW FORMAT -> OLD DECODER uses the genuine old decoder.
 * Requires git (the project is a git checkout) and a JDK compiler (the test JVM is the toolchain JDK).
 */
class LegacyCodecCompatibilityTest {
    static final String PHASE2_BASELINE = "f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24";
    static final List<String> SOURCES = List.of(
        "anticheat-protocol/src/main/java/me/nicolas/papea2ac/protocol/PacketPayload.java",
        "anticheat-protocol/src/main/java/me/nicolas/papea2ac/protocol/SemanticPayload.java",
        "anticheat-protocol/src/main/java/me/nicolas/papea2ac/protocol/PayloadCodec.java",
        "anticheat-protocol/src/main/java/me/nicolas/papea2ac/protocol/SemanticCodec.java",
        "anticheat-protocol/src/main/java/me/nicolas/papea2ac/protocol/PayloadDigest.java",
        "anticheat-replay-schema/src/main/java/me/nicolas/papea2ac/schema/TransportCapsule.java",
        "anticheat-replay/src/main/java/me/nicolas/papea2ac/replay/CapsuleCodec.java");
    static URLClassLoader legacy; static Path dir;

    @BeforeAll static void compileBaseline() throws Exception {
        Path root = TimelineFixtureDeterminismTest.repoRoot(); dir = Files.createTempDirectory("papea2ac-legacy-codec");
        var files = new ArrayList<Path>();
        for (String source : SOURCES) {
            var p = new ProcessBuilder("git", "-C", root.toString(), "show", PHASE2_BASELINE + ":" + source).redirectErrorStream(true).start();
            byte[] content = p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor(), "git show failed for " + source + ": " + new String(content));
            Path out = dir.resolve("src").resolve(source); Files.createDirectories(out.getParent()); Files.write(out, content); files.add(out);
        }
        Path classes = Files.createDirectories(dir.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler, "JDK compiler required");
        var fm = compiler.getStandardFileManager(null, null, null);
        var units = fm.getJavaFileObjectsFromPaths(files);
        boolean ok = compiler.getTask(null, fm, null, List.of("-d", classes.toString(), "-proc:none", "-Xlint:none"), null, units).call();
        assertTrue(ok, "Phase 2 baseline codec sources must compile standalone");
        legacy = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    @AfterAll static void cleanUp() throws Exception {
        if (legacy != null) legacy.close();
        try (var walk = Files.walk(dir)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } // best effort
    }
    static Class<?> l(String name) throws ClassNotFoundException { return Class.forName(name, true, legacy); }
    static Object newRecord(Class<?> type, Object... args) throws Exception {
        for (var c : type.getDeclaredConstructors()) if (c.getParameterCount() == args.length) { c.setAccessible(true); return c.newInstance(args); }
        throw new AssertionError("no " + args.length + "-arg constructor on " + type);
    }
    static Object call(Class<?> type, String method, Class<?>[] sig, Object... args) throws Throwable {
        try { var m = type.getDeclaredMethod(method, sig); m.setAccessible(true); return m.invoke(null, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static byte[] legacyEncode(Object payload) throws Throwable { return (byte[]) call(l("me.nicolas.papea2ac.protocol.PayloadCodec"), "encode", new Class<?>[]{l("me.nicolas.papea2ac.protocol.PacketPayload")}, payload); }
    static Object legacyDecode(byte[] bytes) throws Throwable { return call(l("me.nicolas.papea2ac.protocol.PayloadCodec"), "decode", new Class<?>[]{byte[].class}, (Object) bytes); }
    static String legacyDigest(Object payload) throws Throwable { return (String) call(l("me.nicolas.papea2ac.protocol.PayloadDigest"), "of", new Class<?>[]{l("me.nicolas.papea2ac.protocol.PacketPayload")}, payload); }

    // --- legacy record builders (Phase 2 shapes) ---
    Object legacyJoinGame() throws Exception { return newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$JoinGame"), 5, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false); }
    Object legacyRespawn() throws Exception { return newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$Respawn"), "minecraft:the_nether", (byte) 1); }
    Object legacyChunkBaseline() throws Exception { return newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$ChunkBaseline"), 3, -4, 12345, 2, 4, true); }
    Object legacyBlockChange() throws Exception {
        var prop = newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$Property"), "level", "0");
        var state = newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$BlockState"), "minecraft:water", List.of(prop));
        var pos = newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$BlockPosition"), 1, 70, -17);
        return newRecord(l("me.nicolas.papea2ac.protocol.SemanticPayload$BlockChange"), pos, state);
    }
    Object legacyMovement() throws Exception {
        var pos = newRecord(l("me.nicolas.papea2ac.protocol.PacketPayload$Position"), 1.0, 2.0, 3.0);
        return newRecord(l("me.nicolas.papea2ac.protocol.PacketPayload$Movement"), pos, null, true, false);
    }
    static final SemanticPayload.BlockChange NEW_BLOCK_CHANGE = new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(1, 70, -17), new SemanticPayload.BlockState("minecraft:water", List.of(new SemanticPayload.Property("level", "0"))));

    @Test void oldFormatFromTheRealPhase2EncoderDecodesUnderTheNewDecoder() throws Throwable {
        byte[] join = legacyEncode(legacyJoinGame()), respawn = legacyEncode(legacyRespawn()), chunk = legacyEncode(legacyChunkBaseline()), block = legacyEncode(legacyBlockChange()), move = legacyEncode(legacyMovement());
        assertEquals(1, join[0], "Phase 2 wrote payload schema 1");
        var j = assertInstanceOf(SemanticPayload.JoinGame.class, PayloadCodec.decode(join));
        assertEquals(5, j.playerId()); assertEquals("minecraft:overworld", j.dimension()); assertEquals("SURVIVAL", j.gameType()); assertTrue(j.onlineMode());
        assertFalse(j.hasDimensionRange(), "a legacy JoinGame carries NO range - it must not be invented"); assertEquals(0, j.dimensionMinY()); assertEquals(0, j.dimensionHeight());
        var r = assertInstanceOf(SemanticPayload.Respawn.class, PayloadCodec.decode(respawn));
        assertEquals("minecraft:the_nether", r.dimension()); assertEquals((byte) 1, r.keepFlags()); assertFalse(r.hasDimensionRange());
        var c = assertInstanceOf(SemanticPayload.ChunkBaseline.class, PayloadCodec.decode(chunk));
        assertEquals(3, c.chunkX()); assertEquals(-4, c.chunkZ()); assertEquals(12345, c.encodedBlockBytes()); assertEquals(2, c.blockEntityCount()); assertEquals(4, c.heightmapCount()); assertTrue(c.hasLight());
        assertFalse(c.contentRetained()); assertTrue(c.sections().isEmpty()); assertEquals("NOT_DECODED", c.contentNote());
        assertEquals(NEW_BLOCK_CHANGE, PayloadCodec.decode(block), "an unchanged tag decodes to the identical record");
        assertEquals(new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false), PayloadCodec.decode(move));
        // the pinned hex fixtures used by the git-free test are exactly these real Phase 2 bytes
        assertEquals(CapsuleSchemaCompatibilityTest.LEGACY_JOIN_GAME_HEX, HexFormat.of().formatHex(join));
        assertEquals(CapsuleSchemaCompatibilityTest.LEGACY_RESPAWN_HEX, HexFormat.of().formatHex(respawn));
        assertEquals(CapsuleSchemaCompatibilityTest.LEGACY_CHUNK_BASELINE_HEX, HexFormat.of().formatHex(chunk));
        assertEquals(CapsuleSchemaCompatibilityTest.LEGACY_BLOCK_CHANGE_HEX, HexFormat.of().formatHex(block));
    }

    @Test void aWholeSchema1CapsuleFromTheRealPhase2ContainerEncoderDecodesUnderTheNewDecoder() throws Throwable {
        Class<?> tc = l("me.nicolas.papea2ac.schema.TransportCapsule");
        Object payload = newRecord(l("me.nicolas.papea2ac.schema.TransportCapsule$Payload"), (Object) legacyEncode(legacyJoinGame()));
        Object context = newRecord(l("me.nicolas.papea2ac.schema.TransportCapsule$Context"), 0L, "OUTBOUND", "NETWORK", 0L, 0L, -1L, "PLAY", -1, "0".repeat(64));
        Object outbound = newRecord(l("me.nicolas.papea2ac.schema.TransportCapsule$Outbound"), 1L, payload);
        Object input = newRecord(l("me.nicolas.papea2ac.schema.TransportCapsule$Input"), 10L, 5L, 1L, 0L, context, outbound);
        Object close = newRecord(l("me.nicolas.papea2ac.schema.TransportCapsule$Close"), 20L);
        Object capsule = newRecord(tc, 1, "phase2-fixture", "0".repeat(64), PHASE2_BASELINE, 1L, false, List.of(input, close), true);
        byte[] bytes = (byte[]) call(l("me.nicolas.papea2ac.replay.CapsuleCodec"), "encode", new Class<?>[]{tc}, capsule);
        var decoded = CapsuleCodec.decode(bytes);
        assertEquals(1, decoded.schema(), "legacy container schema is preserved, not rewritten"); assertEquals(2, decoded.frames().size());
        var restored = TransportReplayMapper.restore((TransportCapsule.Input) decoded.frames().getFirst(), new me.nicolas.papea2ac.model.SessionKey(new UUID(1, 1), 1));
        var join = assertInstanceOf(SemanticPayload.JoinGame.class, ((Observation.Outbound) restored.event()).payload());
        assertFalse(join.hasDimensionRange());
        assertEquals(CapsuleSchemaCompatibilityTest.LEGACY_CAPSULE_HEX, HexFormat.of().formatHex(bytes), "pinned legacy capsule fixture is the real Phase 2 output");
    }

    @Test void newFormatIsRejectedClearlyByThePhase2DecoderNeverMisinterpreted() throws Throwable {
        var join = new SemanticPayload.JoinGame(5, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false, -64, 384);
        for (PacketPayload p : List.of(join, new SemanticPayload.Respawn("minecraft:the_nether", (byte) 1, 0, 256), NEW_BLOCK_CHANGE, new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false))) {
            byte[] bytes = PayloadCodec.encode(p); assertEquals(PayloadCodec.SCHEMA, bytes[0], "the current encoder writes payload schema " + PayloadCodec.SCHEMA + " (2 since Phase 3, 3 since P11-F2)");
            var e = assertThrows(IOException.class, () -> legacyDecode(bytes), p.getClass().getSimpleName());
            assertEquals("payload schema", e.getMessage(), "the Phase 2 decoder stops at the schema byte - it never reads a schema-2 body");
        }
        var current = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, "phase3", "0".repeat(64), "x", 1, false, List.of(new TransportCapsule.Close(1)), true);
        byte[] container = CapsuleCodec.encode(current);
        var e = assertThrows(IOException.class, () -> call(l("me.nicolas.papea2ac.replay.CapsuleCodec"), "decode", new Class<?>[]{byte[].class}, (Object) container));
        assertEquals("capsule schema", e.getMessage(), "the Phase 2 container decoder rejects schema 2 at the header");
    }

    @Test void digestsOfLayoutUnchangedPayloadsArePreservedAcrossTheSchemaBump() throws Throwable {
        assertEquals(legacyDigest(legacyBlockChange()), PayloadDigest.of(NEW_BLOCK_CHANGE), "same semantic content, same digest as a Phase 2 build computed");
        assertEquals(legacyDigest(legacyMovement()), PayloadDigest.of(new PacketPayload.Movement(new PacketPayload.Position(1, 2, 3), null, true, false)));
        var newJoin = new SemanticPayload.JoinGame(5, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false, -64, 384);
        assertNotEquals(legacyDigest(legacyJoinGame()), PayloadDigest.of(newJoin), "a JoinGame that now carries the range is different content and digests differently - documented");
    }
}
