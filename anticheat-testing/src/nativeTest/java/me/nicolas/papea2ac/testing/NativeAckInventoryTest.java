package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import me.nicolas.papea2AC.platform.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.*;

/**
 * Phase 7 ACK inventory of the pinned play protocol (Paper 26.2 build 124), established against the jar itself and written to
 * docs/phase7-artifacts/ack-inventory.json (LATENCY_COMPENSATION.md N1: protocol id, mappings and semantics are release-gated
 * artifacts, not assumptions hidden in code). Shapes and constructors by reflection, wire ids from the pinned inventory,
 * handler semantics from the bytecode ({@code java.lang.classfile}): {@code handlePong} is a no-op, keep-alives leave every
 * second with a pending queue and a 30 s limit (P7-F3: the profile said 15 s), a teleport confirm is accepted only when its id
 * equals {@code awaitingTeleport} and the server re-sends an unconfirmed teleport after 20 ticks, {@code handleClientTickEnd}
 * runs on the main thread. Finally the live path: each ACK packet written through a real PaperCapture pipeline lands in the
 * session's TimingLedger as the event the mapper promises.
 */
class NativeAckInventoryTest {
    static final String COMMON = "net.minecraft.network.protocol.common.", GAME = "net.minecraft.network.protocol.game.";
    static final Map<String, Object> inventory = new LinkedHashMap<>();
    @BeforeAll static void bootstrap() throws Exception { NativePacketTest.bootstrap(); }
    @AfterAll static void write() throws Exception {
        Path out = Path.of(System.getProperty("repo.root")).resolve("docs/phase7-artifacts/ack-inventory.json");
        Files.createDirectories(out.getParent());
        Map<String, Object> root = new LinkedHashMap<>(); root.put("phase", "7 - Latency / Acknowledgement / External-Motion Timing"); root.put("pinned", "paper-26.2 build 124 (.phase1-runtime/versions/26.2/paper-26.2.jar), vanilla 26.2 client"); root.put("profile", TimingProfile.PAPER_26_2.id());
        root.put("legacyTransactions", "none: the 26.2 play protocol has no window-confirmation/transaction packet; ping/pong (int id) and keep-alive (long id) are the request/response pairs; teleport confirm and client tick end are the game-thread fences");
        root.putAll(inventory);
        Files.writeString(out, json(root) + "\n", StandardCharsets.UTF_8);
    }
    static ClassModel model(String name) throws Exception {
        try (var in = Class.forName(name).getResourceAsStream("/" + name.replace('.', '/') + ".class")) { return ClassFile.of().parse(in.readAllBytes()); }
    }
    static MethodModel method(ClassModel cm, String name) { return cm.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow(() -> new AssertionError(name)); }
    static List<CodeElement> code(ClassModel cm, String name) { return method(cm, name).code().orElseThrow().elementList(); }
    static Map<String, Object> packet(String cls, String flow, String field, String fieldType, String ctor) throws Exception {
        Class<?> c = Class.forName(cls); Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", cls); m.put("flow", flow);
        if (field != null) { Field f = c.getDeclaredField(field); assertEquals(fieldType, f.getType().getName(), cls + "." + field); m.put("idField", field + ":" + fieldType); }
        m.put("constructor", ctor);
        var entries = PaperPacketInventory.inspect(); String simple = c.getSimpleName();
        Object type = c.getMethod("type").invoke(instance(c));
        String typeId = type.getClass().getMethod("id").invoke(type).toString(); m.put("type", typeId);
        entries.stream().filter(e -> e.state().equals("PLAY") && e.flow().equals(flow) && e.type().equals(typeId)).findFirst().ifPresent(e -> { m.put("wireId", e.wireId()); m.put("wireIdHex", "0x" + Integer.toHexString(e.wireId())); });
        assertTrue(m.containsKey("wireId"), "play wire id for " + typeId + " (" + simple + ")");
        return m;
    }
    static Object instance(Class<?> c) throws Exception {
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (!Modifier.isPublic(k.getModifiers())) continue;
            Class<?>[] p = k.getParameterTypes(); Object[] a = new Object[p.length]; boolean ok = true;
            for (int i = 0; i < p.length; i++) { if (p[i] == int.class) a[i] = 1; else if (p[i] == long.class) a[i] = 1L; else if (p[i] == float.class) a[i] = 0f; else if (p[i] == double.class) a[i] = 0d; else if (p[i] == boolean.class) a[i] = false; else if (p[i] == Set.class) a[i] = Set.of(); else if (p[i].getSimpleName().equals("PositionMoveRotation")) a[i] = pmr(0, 0, 0, 0, 0, 0, 0f, 0f); else ok = false; }
            if (ok) return k.newInstance(a);
        }
        throw new AssertionError("no constructible shape for " + c);
    }
    static Object vec3(double x, double y, double z) throws Exception { return Class.forName("net.minecraft.world.phys.Vec3").getConstructor(double.class, double.class, double.class).newInstance(x, y, z); }
    static Object pmr(double x, double y, double z, double dx, double dy, double dz, float yaw, float pitch) throws Exception {
        Class<?> v = Class.forName("net.minecraft.world.phys.Vec3");
        return Class.forName("net.minecraft.world.entity.PositionMoveRotation").getConstructor(v, v, float.class, float.class).newInstance(vec3(x, y, z), vec3(dx, dy, dz), yaw, pitch);
    }

    @Test void ackPacketShapesAndWireIdsArePinned() throws Exception {
        Map<String, Object> packets = new LinkedHashMap<>();
        packets.put("ping", packet(COMMON + "ClientboundPingPacket", "CLIENTBOUND", "id", "int", "ClientboundPingPacket(int) - the Phase 7 marker (PaperMarkerPort)"));
        packets.put("pong", packet(COMMON + "ServerboundPongPacket", "SERVERBOUND", "id", "int", "ServerboundPongPacket(int) - answered by the client's network thread; server handler is a no-op"));
        packets.put("keepAlive", packet(COMMON + "ClientboundKeepAlivePacket", "CLIENTBOUND", "id", "long", "ClientboundKeepAlivePacket(long) - server-issued challenge"));
        packets.put("keepAliveResponse", packet(COMMON + "ServerboundKeepAlivePacket", "SERVERBOUND", "id", "long", "ServerboundKeepAlivePacket(long) - answered by the client's network thread"));
        packets.put("clientTickEnd", packet(GAME + "ServerboundClientTickEndPacket", "SERVERBOUND", null, null, "ServerboundClientTickEndPacket() - record, no payload, once per client tick"));
        packets.put("acceptTeleportation", packet(GAME + "ServerboundAcceptTeleportationPacket", "SERVERBOUND", "id", "int", "ServerboundAcceptTeleportationPacket(int) - the client's game-thread confirm"));
        packets.put("playerPosition", packet(GAME + "ClientboundPlayerPositionPacket", "CLIENTBOUND", "id", "int", "ClientboundPlayerPositionPacket(int, PositionMoveRotation, Set<Relative>) - the correction"));
        inventory.put("packets", packets);
        assertEquals(TimingEvent.RELATIVE_X | TimingEvent.RELATIVE_Y | TimingEvent.RELATIVE_Z | TimingEvent.RELATIVE_Y_ROT | TimingEvent.RELATIVE_X_ROT | TimingEvent.RELATIVE_DELTA_X | TimingEvent.RELATIVE_DELTA_Y | TimingEvent.RELATIVE_DELTA_Z | TimingEvent.RELATIVE_ROTATE_DELTA, 0x1FF);
        Class<?> relative = Class.forName("net.minecraft.world.entity.Relative"); List<String> names = new ArrayList<>();
        for (Field f : relative.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers()) && f.getType() == relative) names.add(f.getName());
        assertEquals(List.of("X", "Y", "Z", "Y_ROT", "X_ROT", "DELTA_X", "DELTA_Y", "DELTA_Z", "ROTATE_DELTA"), names, "the nine relative flags the mapper's mask covers");
        inventory.put("relativeFlags", names);
    }
    @Test void serverHandlerSemanticsAreEstablishedFromTheBytecode() throws Exception {
        Map<String, Object> sem = new LinkedHashMap<>();
        ClassModel common = model("net.minecraft.server.network.ServerCommonPacketListenerImpl");
        // handlePong: a single return
        List<CodeElement> pong = code(common, "handlePong");
        long pongInstructions = pong.stream().filter(e -> e instanceof Instruction).count();
        assertEquals(1, pongInstructions, "handlePong is a no-op (one return)"); assertInstanceOf(ReturnInstruction.class, pong.stream().filter(e -> e instanceof Instruction).findFirst().orElseThrow());
        sem.put("handlePong", "no-op (single return): a pong is receipt/RTT evidence only; the server neither validates nor acts on it");
        // keepConnectionAlive: one keep-alive per second (nanoTime challenge, pending queue), kick after KEEPALIVE_LIMIT ms
        List<CodeElement> keep = code(common, "keepConnectionAlive");
        boolean secondsToNanos = false, pendingQueue = false, limitCompared = false;
        for (int i = 0; i < keep.size(); i++) {
            if (keep.get(i) instanceof FieldInstruction f && f.owner().name().equalsString("java/util/concurrent/TimeUnit") && f.name().equalsString("SECONDS")) {
                for (int j = i + 1; j < Math.min(i + 4, keep.size()); j++) if (keep.get(j) instanceof ConstantInstruction c && Long.valueOf(1L).equals(c.constantValue())) for (int k = j + 1; k < Math.min(j + 3, keep.size()); k++) if (keep.get(k) instanceof InvokeInstruction inv && inv.name().equalsString("toNanos")) secondsToNanos = true;
            }
            if (keep.get(i) instanceof FieldInstruction f && f.name().equalsString("pendingKeepAlives")) pendingQueue = true;
            if (keep.get(i) instanceof FieldInstruction f && f.name().equalsString("KEEPALIVE_LIMIT")) limitCompared = true;
            if (keep.get(i) instanceof InvokeInstruction inv && inv.owner().name().equalsString("net/minecraft/network/protocol/common/ClientboundKeepAlivePacket") && inv.name().equalsString("<init>")) sem.put("keepAliveConstructedIn", "keepConnectionAlive");
        }
        assertTrue(secondsToNanos, "keep-alive cadence: nanoTime - lastKeepAliveTx >= TimeUnit.SECONDS.toNanos(1)"); assertTrue(pendingQueue, "pending keep-alive queue"); assertTrue(limitCompared, "KEEPALIVE_LIMIT timeout");
        long limitDefaultSeconds = -1; String limitProperty = null;
        List<CodeElement> clinit = code(common, "<clinit>");
        for (int i = 0; i < clinit.size(); i++) if (clinit.get(i) instanceof ConstantInstruction c && "paper.playerconnection.keepalive".equals(c.constantValue())) { limitProperty = "paper.playerconnection.keepalive"; for (int j = i + 1; j < Math.min(i + 3, clinit.size()); j++) if (clinit.get(j) instanceof ConstantInstruction d && d.constantValue() instanceof Long l) limitDefaultSeconds = l; }
        assertEquals(30, limitDefaultSeconds, "KEEPALIVE_LIMIT default"); assertNotNull(limitProperty);
        assertEquals(15000, Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl").getField("LATENCY_CHECK_INTERVAL").get(null), "the vanilla constant still exists but Paper's cadence is 1 s");
        sem.put("keepAliveCadence", "one ClientboundKeepAlivePacket per second per connection (System.nanoTime based challenge id, io.papermc.paper.util.KeepAlive.pendingKeepAlives queue); LATENCY_CHECK_INTERVAL=15000 exists but does not drive the cadence (P7-F3)");
        sem.put("keepAliveTimeout", "KEEPALIVE_LIMIT = Long.getLong(\"paper.playerconnection.keepalive\", 30) * 1000 ms: kicked when the oldest pending keep-alive is older than that");
        sem.put("keepAliveResponse", "handleKeepAlive matches the response id against the head of the pending queue (challengeId) and feeds Paper's ping calculators; Papea2AC correlates the same long id in its MarkerLedger and never reads the server's latency field");
        // ServerGamePacketListenerImpl: teleport confirm, re-teleport, tick end
        ClassModel game = model("net.minecraft.server.network.ServerGamePacketListenerImpl");
        List<CodeElement> accept = code(game, "handleAcceptTeleportPacket");
        boolean sameThread = accept.stream().anyMatch(e -> e instanceof InvokeInstruction inv && inv.name().equalsString("ensureRunningOnSameThread"));
        boolean comparesAwaiting = false; for (int i = 0; i < accept.size(); i++) if (accept.get(i) instanceof FieldInstruction f && f.name().equalsString("awaitingTeleport")) for (int j = i + 1; j < Math.min(i + 3, accept.size()); j++) if (accept.get(j) instanceof BranchInstruction b && b.opcode() == Opcode.IF_ICMPNE) comparesAwaiting = true;
        assertTrue(sameThread && comparesAwaiting, "confirm handled on the main thread and accepted only when id == awaitingTeleport");
        sem.put("teleportConfirm", "handleAcceptTeleportPacket: main thread (ensureRunningOnSameThread); accepted only when packet id == awaitingTeleport (a stale/superseded id is ignored); the server then snaps the player to awaitingPositionFromClient");
        List<CodeElement> update = code(game, "updateAwaitingTeleport");
        boolean twenty = update.stream().anyMatch(e -> e instanceof ConstantInstruction c && Integer.valueOf(20).equals(c.constantValue()));
        assertTrue(twenty, "re-teleport after 20 ticks");
        sem.put("teleportResend", "updateAwaitingTeleport: while awaitingPositionFromClient is set, an unconfirmed teleport is re-sent (same id) when tickCount - awaitingTeleportTime > 20; the id increments per internalTeleport, so a superseding correction carries a new id");
        List<CodeElement> tickEnd = code(game, "handleClientTickEnd");
        assertTrue(tickEnd.stream().anyMatch(e -> e instanceof InvokeInstruction inv && inv.name().equalsString("ensureRunningOnSameThread")), "tick end on the main thread");
        sem.put("clientTickEnd", "handleClientTickEnd: main thread; fires Paper's ClientTickEndEvent; the packet is the client's once-per-tick fence used for alignment (TICK_END_EXACT)");
        assertTrue(game.fields().stream().anyMatch(f -> f.fieldName().equalsString("awaitingTeleport") && f.fieldType().equalsString("I")));
        assertTrue(game.fields().stream().anyMatch(f -> f.fieldName().equalsString("awaitingTeleportTime") && f.fieldType().equalsString("I")));
        inventory.put("serverSemantics", sem);
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("pong", "vanilla 26.2 client answers ClientboundPingPacket from the network thread (ClientCommonPacketListener.handlePing -> send(ServerboundPongPacket)): receipt evidence, no tick alignment");
        client.put("keepAlive", "answered from the network thread likewise");
        client.put("teleportConfirm", "handleMovePlayer runs on the game thread: applies the correction, sends ServerboundAcceptTeleportationPacket then the PosRot at the applied position (TimingProfile.teleportConfirmIsGameThreadFence)");
        client.put("clientTickEnd", "sent once at the end of every client tick while in a level (TimingProfile.clientTickEndPerTick; verified per session by presence and in the real-client qualification)");
        inventory.put("clientSemantics", client);
        assertFalse(TimingProfile.PAPER_26_2.pongBoundsApplication(), "pong never bounds an application frontier (N1)");
        assertEquals(1_000_000_000L, TimingProfile.PAPER_26_2.keepAliveIntervalNanos(), "profile cadence matches the jar (P7-F3)");
    }
    @Test void everyAckPacketReachesTheSessionsTimingLedgerThroughTheRealCapture() throws Exception {
        PacketEngine engine = new PacketEngine(1, 1L << 30, e -> fail(e)); EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter()); channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        PaperCapture adapter = new PaperCapture(engine); var session = adapter.attach(new UUID(77, 1), channel); channel.runPendingTasks();
        try {
            Class<?> relative = Class.forName("net.minecraft.world.entity.Relative");
            Object relX = relative.getField("X").get(null);
            channel.pipeline().writeAndFlush(NativePacketTest.create(COMMON + "ClientboundPingPacket", new Class<?>[] { int.class }, 0x01000005)); channel.runPendingTasks();
            await(session, v -> v.markers().sent() == 1 && v.markers().pending() == 1);
            channel.writeInbound(NativePacketTest.create(COMMON + "ServerboundPongPacket", new Class<?>[] { int.class }, 0x01000005)); channel.runPendingTasks();
            await(session, v -> v.markers().acknowledged() == 1 && v.rtt().samples() == 1);
            channel.pipeline().writeAndFlush(NativePacketTest.create(COMMON + "ClientboundKeepAlivePacket", new Class<?>[] { long.class }, 123456789L)); channel.runPendingTasks();
            channel.writeInbound(NativePacketTest.create(COMMON + "ServerboundKeepAlivePacket", new Class<?>[] { long.class }, 123456789L)); channel.runPendingTasks();
            await(session, v -> v.markers().sent() == 2 && v.markers().acknowledged() == 2 && v.rtt().samples() == 2);
            channel.writeInbound(NativePacketTest.create(GAME + "ServerboundClientTickEndPacket", new Class<?>[0])); channel.runPendingTasks();
            await(session, v -> v.counters().clientTickEnds() == 1 && v.tickEndCapable());
            channel.pipeline().writeAndFlush(NativePacketTest.create(GAME + "ClientboundPlayerPositionPacket", new Class<?>[] { int.class, Class.forName("net.minecraft.world.entity.PositionMoveRotation"), Set.class }, 3, pmr(1.5, 70, 2.5, 0, 0, 0, 90f, 0f), Set.of(relX))); channel.runPendingTasks();
            await(session, v -> v.counters().teleportsSent() == 1);
            TimingLedger.PendingTeleport pt = session.timing.view().teleports().get(0);
            assertEquals(3, pt.teleportId()); assertFalse(pt.confirmed()); assertTrue(pt.sent().relative(TimingEvent.RELATIVE_X)); assertFalse(pt.sent().relative(TimingEvent.RELATIVE_Y)); assertEquals(70.0, pt.sent().position().y()); assertEquals(90f, pt.sent().rotation().yaw());
            channel.writeInbound(NativePacketTest.create(GAME + "ServerboundAcceptTeleportationPacket", new Class<?>[] { int.class }, 99)); channel.runPendingTasks(); // a stale/unknown id
            await(session, v -> v.counters().staleTeleportConfirms() == 1);
            assertFalse(session.timing.view().teleports().get(0).confirmed());
            channel.writeInbound(NativePacketTest.create(GAME + "ServerboundAcceptTeleportationPacket", new Class<?>[] { int.class }, 3)); channel.runPendingTasks();
            await(session, v -> v.counters().teleportsConfirmed() == 1);
            assertTrue(session.timing.view().teleports().get(0).confirmed());
            // a velocity for an entity that is not the session's own (self unknown here): never an influence
            channel.pipeline().writeAndFlush(NativePacketTest.create(GAME + "ClientboundSetEntityMotionPacket", new Class<?>[] { int.class, Class.forName("net.minecraft.world.phys.Vec3") }, 7, vec3(0.1, 0.2, 0.3))); channel.runPendingTasks();
            long deadline = System.nanoTime() + 3_000_000_000L; while (engine.timingEngineStatus().velocityIgnoredUnknownSelf() < 1 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, engine.timingEngineStatus().velocityIgnoredUnknownSelf());
            assertEquals(0, session.timing.view().counters().externalMotions(), "self entity unknown: velocity not attributed (P7-F1)");
            Map<String, Object> live = new LinkedHashMap<>(); live.put("path", "EmbeddedChannel -> PaperCapture -> PacketEngine shard -> TimingObservationMapper -> TimingLedger");
            live.put("ping/pong", "MarkerSent(PING) then MarkerAcknowledged(PING): 1 RTT sample"); live.put("keepAlive", "MarkerSent(KEEPALIVE) then MarkerAcknowledged(KEEPALIVE): 1 RTT sample"); live.put("clientTickEnd", "ClientTickEnd: tickEndCapable=true"); live.put("playerPosition", "TeleportSent(id, relative mask) then TeleportConfirmed(id); a foreign confirm id is counted stale and confirms nothing"); live.put("velocityUnknownSelf", "not attributed, counted");
            inventory.put("liveCapture", live);
        } finally { adapter.detach(channel, session); channel.runPendingTasks(); channel.finishAndReleaseAll(); engine.close(); }
    }
    static void await(PacketEngine.Session session, java.util.function.Predicate<TimingLedger.View> c) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!c.test(session.timing.view()) && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(c.test(session.timing.view()), "timing ledger did not observe the expected event: " + session.timing.view().counters() + " " + session.timing.view().markers());
    }
    static String json(Object o) {
        StringBuilder sb = new StringBuilder(); json(o, sb, 0); return sb.toString();
    }
    private static void json(Object o, StringBuilder sb, int indent) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) { sb.append('"'); for (char ch : s.toCharArray()) { if (ch == '"' || ch == '\\') sb.append('\\').append(ch); else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); } sb.append('"'); }
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map<?, ?> m) { sb.append("{\n"); int i = 0; for (var e : m.entrySet()) { sb.append("  ".repeat(indent + 1)); json(String.valueOf(e.getKey()), sb, indent + 1); sb.append(": "); json(e.getValue(), sb, indent + 1); if (++i < m.size()) sb.append(','); sb.append('\n'); } sb.append("  ".repeat(indent)).append('}'); }
        else if (o instanceof Collection<?> c) { sb.append("[\n"); int i = 0; for (Object e : c) { sb.append("  ".repeat(indent + 1)); json(e, sb, indent + 1); if (++i < c.size()) sb.append(','); sb.append('\n'); } sb.append("  ".repeat(indent)).append(']'); }
        else json(String.valueOf(o), sb, indent);
    }
}
