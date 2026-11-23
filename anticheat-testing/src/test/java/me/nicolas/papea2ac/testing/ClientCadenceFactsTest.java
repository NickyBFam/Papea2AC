package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import org.junit.jupiter.api.*;
/**
 * Phase 14 client facts, pinned against the vanilla 26.2 CLIENT jar itself (unobfuscated since 26.x): every fact {@link TickCadenceProfile}
 * relies on is read from the bytecode with {@code java.lang.classfile}. The jar is the owner's licensed install, not a repository asset:
 * the test runs where it exists ({@code -Dclient.jar=...} or the default launcher path) and is skipped elsewhere; its SHA-256 must be the
 * pinned one. Writes docs/phase14-artifacts/client-cadence-facts.json.
 */
class ClientCadenceFactsTest {
    static final String PINNED_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    static final Map<String, Object> facts = new LinkedHashMap<>();
    static ZipFile jar;
    static Path jarPath;

    @BeforeAll static void open() throws Exception {
        String configured = System.getProperty("client.jar");
        jarPath = configured != null ? Path.of(configured) : Path.of(System.getenv().getOrDefault("APPDATA", "."), ".minecraft", "versions", "26.2", "26.2.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jarPath), "vanilla 26.2 client jar not present: " + jarPath);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jarPath)));
        assertEquals(PINNED_SHA256, sha, "the client jar is the pinned 26.2 build");
        facts.put("clientJarSha256", sha); facts.put("profile", TickCadenceProfile.PAPER_26_2.id());
        jar = new ZipFile(jarPath.toFile());
    }
    @AfterAll static void write() throws Exception {
        if (jar == null) return;
        jar.close();
        Path out = Path.of(System.getProperty("repo.root", ".")).resolve("docs/phase14-artifacts/client-cadence-facts.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MiniJson.write(facts) + "\n", StandardCharsets.UTF_8);
    }
    static ClassModel model(String internal) throws Exception {
        ZipEntry e = jar.getEntry(internal + ".class"); assertNotNull(e, internal);
        try (InputStream in = jar.getInputStream(e)) { return ClassFile.of().parse(in.readAllBytes()); }
    }
    static MethodModel method(ClassModel cm, String name) { return cm.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow(() -> new AssertionError(name)); }
    static List<CodeElement> code(ClassModel cm, String name) { return method(cm, name).code().orElseThrow().elementList(); }
    static int indexOfInvoke(List<CodeElement> code, String owner, String name) {
        for (int i = 0; i < code.size(); i++) if (code.get(i) instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(owner) && inv.name().equalsString(name)) return i;
        return -1;
    }
    static boolean invokes(List<CodeElement> code, String owner, String name) { return indexOfInvoke(code, owner, name) >= 0; }

    @Test void theClientTimerIsTwentyTicksPerSecondAndNeverFasterThanFiftyMilliseconds() throws Exception {
        ClassModel mc = model("net/minecraft/client/Minecraft");
        List<CodeElement> init = code(mc, "<init>");
        int timer = -1;
        for (int i = 0; i < init.size(); i++) if (init.get(i) instanceof NewObjectInstruction n && n.className().asInternalName().equals("net/minecraft/client/DeltaTracker$Timer")) { timer = i; break; }
        assertTrue(timer >= 0, "Minecraft builds its DeltaTracker.Timer");
        boolean twenty = false;
        for (int i = timer; i < Math.min(init.size(), timer + 6); i++) if (init.get(i) instanceof ConstantInstruction c && Float.valueOf(20.0f).equals(c.constantValue())) twenty = true;
        assertTrue(twenty, "constructed with 20.0f ticks per second (msPerTick = 50 ms)");
        List<CodeElement> target = code(mc, "getTickTargetMillis");
        assertTrue(invokes(target, "net/minecraft/world/TickRateManager", "runsNormally") && invokes(target, "net/minecraft/world/TickRateManager", "millisecondsPerTick") && invokes(target, "java/lang/Math", "max"),
            "the tick target is max(50 ms, server mspt) while running normally, 50 ms otherwise");
        facts.put("timer", "Minecraft.<init>: new DeltaTracker.Timer(20.0f, ...); getTickTargetMillis(f) = runsNormally ? max(f, TickRateManager.millisecondsPerTick()) : f");
    }

    @Test void aFrameRunsAtMostTenTicksAfterProcessingQueuedPacketsAndOwedTicksBeyondAreDropped() throws Exception {
        ClassModel mc = model("net/minecraft/client/Minecraft");
        List<CodeElement> run = code(mc, "runTick");
        int advance = indexOfInvoke(run, "net/minecraft/client/DeltaTracker$Timer", "advanceGameTime"), queued = indexOfInvoke(run, "net/minecraft/network/PacketProcessor", "processQueuedPackets"),
            min = indexOfInvoke(run, "java/lang/Math", "min"), tick = indexOfInvoke(run, "net/minecraft/client/Minecraft", "tick");
        assertTrue(advance >= 0 && queued > advance && min > queued && tick > min, "advanceGameTime, then processQueuedPackets, then min(10, owed) ticks: " + advance + "/" + queued + "/" + min + "/" + tick);
        boolean ten = false;
        for (int i = Math.max(0, min - 4); i < min; i++) if (run.get(i) instanceof ConstantInstruction c && Integer.valueOf(10).equals(c.constantValue())) ten = true;
        assertTrue(ten, "the per-frame cap is the constant 10");
        // tick() has exactly one caller: the capped loop of runTick
        int callers = 0;
        for (MethodModel m : mc.methods()) for (CodeElement e : m.code().map(CodeModel::elementList).orElse(List.of())) if (e instanceof InvokeInstruction inv && inv.owner().asInternalName().equals("net/minecraft/client/Minecraft") && inv.name().equalsString("tick") && inv.type().equalsString("()V")) callers++;
        assertEquals(1, callers, "Minecraft.tick() is called only from the capped loop");
        // advanceGameTime consumes the whole integer part of the residual (f2i then fsub): ticks beyond the cap are not carried
        List<CodeElement> adv = code(model("net/minecraft/client/DeltaTracker$Timer"), "advanceGameTime");
        boolean f2i = adv.stream().anyMatch(e -> e instanceof ConvertInstruction c && c.opcode() == Opcode.F2I), fsub = adv.stream().anyMatch(e -> e instanceof OperatorInstruction o && o.opcode() == Opcode.FSUB);
        assertTrue(f2i && fsub, "residual -= (int) residual");
        facts.put("frame", "Minecraft.runTick: advanceGameTime(Util.getMillis()) -> packetProcessor.processQueuedPackets() -> runAllTasks() -> for (j < min(10, owed)) tick(); DeltaTracker.Timer.advanceGameTime returns (int) residual and subtracts it (owed ticks beyond 10 are dropped); tick() has one caller");
    }

    @Test void theTickEndIsSentAtTheEndOfEveryTick() throws Exception {
        List<CodeElement> tick = code(model("net/minecraft/client/Minecraft"), "tick");
        boolean instance = tick.stream().anyMatch(e -> e instanceof FieldInstruction f && f.owner().asInternalName().equals("net/minecraft/network/protocol/game/ServerboundClientTickEndPacket") && f.name().equalsString("INSTANCE"));
        assertTrue(instance && invokes(tick, "net/minecraft/client/multiplayer/ClientPacketListener", "send"), "Minecraft.tick sends ServerboundClientTickEndPacket.INSTANCE");
        facts.put("tickEnd", "Minecraft.tick(): connection.send(ServerboundClientTickEndPacket.INSTANCE) when a connection exists and the game is not paused");
    }

    @Test void thePongIsAGameThreadFenceAndTheKeepAliveIsNot() throws Exception {
        ClassModel common = model("net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl");
        List<CodeElement> ping = code(common, "handlePing");
        int ensure = indexOfInvoke(ping, "net/minecraft/network/protocol/PacketUtils", "ensureRunningOnSameThread"), pong = -1;
        for (int i = 0; i < ping.size(); i++) if (ping.get(i) instanceof NewObjectInstruction n && n.className().asInternalName().equals("net/minecraft/network/protocol/common/ServerboundPongPacket")) pong = i;
        assertTrue(ensure >= 0 && pong > ensure, "handlePing marshals to the game thread before writing the pong (P14-F1)");
        assertFalse(invokes(code(common, "handleKeepAlive"), "net/minecraft/network/protocol/PacketUtils", "ensureRunningOnSameThread"), "the keep-alive reply is written from the network thread");
        ClassModel game = model("net/minecraft/client/multiplayer/ClientPacketListener");
        for (String h : List.of("handleMovePlayer", "handleRotatePlayer", "handleTeleportEntity", "handleTickingState", "handleSetEntityMotion", "handleExplosion"))
            assertTrue(invokes(code(game, h), "net/minecraft/network/protocol/PacketUtils", "ensureRunningOnSameThread"), h + " runs on the game thread");
        // one write FIFO per thread: Connection.sendPacket defers to eventLoop().execute when called off the event loop
        List<CodeElement> send = code(model("net/minecraft/network/Connection"), "sendPacket");
        assertTrue(invokes(send, "io/netty/channel/EventLoop", "inEventLoop") && invokes(send, "io/netty/channel/EventLoop", "execute"), "off-loop writes are queued in order on the event loop");
        facts.put("fences", "handlePing: PacketUtils.ensureRunningOnSameThread then send(new ServerboundPongPacket(id)) - the pong is a GAME-thread write; handleKeepAlive: no ensureRunningOnSameThread (network thread, never a fence); handleMovePlayer/RotatePlayer/TeleportEntity/TickingState/SetEntityMotion/Explosion: game thread; Connection.sendPacket: eventLoop.execute off-loop (one FIFO per writing thread)");
    }

    @Test void onlyTheTickAndThreeServerPromptedHandlersSendMovementPackets() throws Exception {
        Map<String, Integer> senders = new TreeMap<>();
        for (Enumeration<? extends ZipEntry> en = jar.entries(); en.hasMoreElements();) {
            ZipEntry e = en.nextElement();
            if (!e.getName().endsWith(".class") || !e.getName().startsWith("net/minecraft/") || e.getName().startsWith("net/minecraft/network/protocol/")) continue; // the packet classes decode themselves (read); senders are elsewhere
            byte[] bytes; try (InputStream in = jar.getInputStream(e)) { bytes = in.readAllBytes(); }
            if (!new String(bytes, StandardCharsets.ISO_8859_1).contains("ServerboundMovePlayerPacket$")) continue;
            ClassModel cm = ClassFile.of().parse(bytes);
            for (MethodModel m : cm.methods()) for (CodeElement c : m.code().map(CodeModel::elementList).orElse(List.of()))
                if (c instanceof NewObjectInstruction n && n.className().asInternalName().startsWith("net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$")) senders.merge(cm.thisClass().asInternalName() + "." + m.methodName().stringValue(), 1, Integer::sum);
        }
        assertEquals(Set.of("net/minecraft/client/player/LocalPlayer.tick", "net/minecraft/client/player/LocalPlayer.sendPosition", "net/minecraft/client/multiplayer/ClientPacketListener.handleMovePlayer",
            "net/minecraft/client/multiplayer/ClientPacketListener.handleRotatePlayer", "net/minecraft/client/multiplayer/ClientPacketListener.handleTeleportEntity"), senders.keySet(), senders.toString());
        facts.put("movementSenders", senders);
    }
}
