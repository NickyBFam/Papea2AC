package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
/**
 * Phase 13 native oracle (P13-N1 / P13-F2): the client-side application facts Phase 13 relies on, read from the PINNED vanilla 26.2 CLIENT
 * jar with {@code java.lang.classfile} (no client code is copied, run or redistributed; the jar stays in the owner's launcher directory).
 * The test is skipped when the jar is absent; when present its SHA-256 must be the pinned one, so a different client can never pass.
 * With the environment variable {@code P13_WRITE_ARTIFACTS} set it writes {@code docs/phase13-artifacts/client-application-facts.json}.
 *
 * <ul>
 * <li>N1a {@code ClientCommonPacketListenerImpl.handlePing}: {@code ensureRunningOnSameThread} before the pong is constructed and sent (game thread).</li>
 * <li>N1b {@code handleKeepAlive}: no thread hop (network thread; never a fence).</li>
 * <li>N1c {@code ClientPacketListener.handleSetEntityMotion / handleExplosion / handleBundlePacket}: {@code ensureRunningOnSameThread} first;
 *     {@code Entity.lerpMotion} is exactly {@code setDeltaMovement}; no class between {@code LocalPlayer} and {@code Entity} overrides it.</li>
 * <li>N1d {@code PacketProcessor}: one concurrent FIFO queue, drained by polling until empty; its only client caller is {@code Minecraft.runTick},
 *     before the frame's {@code tick()} calls.</li>
 * <li>F2a {@code Minecraft.tick}: {@code handleKeybinds} (attacks) precedes {@code ClientLevel.tickEntities} (the local player's movement) and
 *     the tick-end packet; {@code MultiPlayerGameMode.attack} sends the ATTACK packet before {@code Player.attack}.</li>
 * <li>F2b {@code Player.causeExtraKnockback}: {@code multiply(0.6, 1.0, 0.6)} then {@code setSprinting(false)}; {@code hurtClient} is declared
 *     true-capable exactly by RemotePlayer, VehicleEntity, ShulkerBullet, BlockAttachedEntity, ItemFrame, EndCrystal, ExperienceOrb and ItemEntity (base {@code Entity}: false).</li>
 * </ul>
 */
class ClientApplicationFactsTest {
    static final String PINNED_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    static Path clientJar() {
        String p = System.getenv("P13_CLIENT_JAR");
        if (p != null) return Path.of(p);
        String appData = System.getenv("APPDATA");
        return appData == null ? Path.of("missing-client.jar") : Path.of(appData, ".minecraft", "versions", "26.2", "26.2.jar");
    }

    record Call(String owner, String name, String type) { public String toString() { return owner.substring(owner.lastIndexOf('/') + 1) + "." + name; } }
    static final class Jar {
        final Map<String, byte[]> classes = new HashMap<>();
        Jar(Path p) throws Exception { try (ZipFile z = new ZipFile(p.toFile())) { for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) { ZipEntry en = e.nextElement(); if (en.getName().startsWith("net/minecraft/") && en.getName().endsWith(".class")) classes.put(en.getName().substring(0, en.getName().length() - 6), z.getInputStream(en).readAllBytes()); } } }
        ClassModel model(String internal) { byte[] b = classes.get(internal); if (b == null) throw new AssertionError("class absent: " + internal); return ClassFile.of().parse(b); }
        MethodModel method(String internal, String name) { for (MethodModel m : model(internal).methods()) if (m.methodName().equalsString(name)) return m; throw new AssertionError(internal + "." + name + " absent"); }
        boolean declares(String internal, String name) { for (MethodModel m : model(internal).methods()) if (m.methodName().equalsString(name)) return true; return false; }
        /** The method's invoke instructions in code order. */
        static List<Call> calls(MethodModel m) {
            List<Call> out = new ArrayList<>();
            m.code().ifPresent(code -> { for (CodeElement e : code) if (e instanceof InvokeInstruction i) out.add(new Call(i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue())); });
            return out;
        }
        static List<Double> doubleConstants(MethodModel m) {
            List<Double> out = new ArrayList<>();
            m.code().ifPresent(code -> { for (CodeElement e : code) if (e instanceof ConstantInstruction.LoadConstantInstruction l && l.constantEntry() instanceof DoubleEntry d) out.add(d.doubleValue()); });
            return out;
        }
    }
    static int index(List<Call> calls, String owner, String name) { for (int i = 0; i < calls.size(); i++) if (calls.get(i).owner().endsWith(owner) && calls.get(i).name().equals(name)) return i; return -1; }

    @Test void thePinnedClientAppliesInfluencesAndAnswersPingsOnItsGameThreadInWireOrder() throws Exception {
        Path jarPath = clientJar();
        Assumptions.assumeTrue(Files.isRegularFile(jarPath), "pinned 26.2 client jar not present at " + jarPath + " (owner workstation only)");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jarPath)));
        assertEquals(PINNED_SHA256, sha, "a different client jar can never qualify the pinned facts");
        Jar jar = new Jar(jarPath);
        Map<String, Object> facts = new LinkedHashMap<>();
        String common = "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl", game = "net/minecraft/client/multiplayer/ClientPacketListener";

        // N1a / N1b
        List<Call> ping = Jar.calls(jar.method(common, "handlePing"));
        int ensure = index(ping, "PacketUtils", "ensureRunningOnSameThread"), pong = index(ping, "ServerboundPongPacket", "<init>"), send = index(ping, common, "send");
        assertTrue(ensure >= 0 && ensure < pong && pong < send, "handlePing hops to the game thread before building and sending the pong: " + ping);
        facts.put("N1a.handlePing", ping.toString());
        List<Call> keepAlive = Jar.calls(jar.method(common, "handleKeepAlive"));
        assertEquals(-1, index(keepAlive, "PacketUtils", "ensureRunningOnSameThread"), "keep-alive is answered without a thread hop: " + keepAlive);
        facts.put("N1b.handleKeepAlive", keepAlive.toString());

        // N1c
        for (String handler : List.of("handleSetEntityMotion", "handleExplosion", "handleBundlePacket", "handleMovePlayer")) {
            List<Call> c = Jar.calls(jar.method(game, handler));
            assertEquals(1, c.stream().filter(x -> x.name().equals("packetProcessor")).count(), handler + " reads the packet processor once: " + c);
            assertEquals(0, index(c.subList(1, c.size()), "PacketUtils", "ensureRunningOnSameThread"), handler + " hops to the game thread first: " + c);
            facts.put("N1c." + handler, c.toString());
        }
        assertTrue(index(Jar.calls(jar.method(game, "handleSetEntityMotion")), "Entity", "lerpMotion") >= 0);
        assertEquals(List.of(new Call("net/minecraft/world/entity/Entity", "setDeltaMovement", "(Lnet/minecraft/world/phys/Vec3;)V")), Jar.calls(jar.method("net/minecraft/world/entity/Entity", "lerpMotion")), "Entity.lerpMotion = setDeltaMovement");
        for (String c : List.of("net/minecraft/client/player/LocalPlayer", "net/minecraft/client/player/AbstractClientPlayer", "net/minecraft/world/entity/player/Player", "net/minecraft/world/entity/Avatar", "net/minecraft/world/entity/LivingEntity"))
            if (jar.classes.containsKey(c)) assertFalse(jar.declares(c, "lerpMotion"), c + " must not override lerpMotion");
        facts.put("N1c.lerpMotion", "Entity.lerpMotion -> setDeltaMovement; not overridden on the LocalPlayer chain");

        // N1d
        String pp = "net/minecraft/network/PacketProcessor";
        assertTrue(index(Jar.calls(jar.method(pp, "<init>")), "Queues", "newConcurrentLinkedQueue") >= 0, "one concurrent FIFO queue");
        List<Call> drain = Jar.calls(jar.method(pp, "processQueuedPackets"));
        assertTrue(index(drain, "Queue", "poll") >= 0 && index(drain, "Queue", "isEmpty") >= 0, "drained by polling until empty: " + drain);
        int callers = 0; String callerSite = "";
        for (var e : jar.classes.entrySet()) {
            if (!e.getKey().startsWith("net/minecraft/client/")) continue;
            for (MethodModel m : ClassFile.of().parse(e.getValue()).methods()) for (Call c : Jar.calls(m)) if (c.owner().equals(pp) && c.name().equals("processQueuedPackets")) { callers++; callerSite = e.getKey() + "." + m.methodName().stringValue(); }
        }
        assertEquals(1, callers, "exactly one client call site of processQueuedPackets"); assertEquals("net/minecraft/client/Minecraft.runTick", callerSite);
        List<Call> runTick = Jar.calls(jar.method("net/minecraft/client/Minecraft", "runTick"));
        int drainAt = index(runTick, pp, "processQueuedPackets"), tickAt = -1; for (int i = 0; i < runTick.size(); i++) if (runTick.get(i).owner().equals("net/minecraft/client/Minecraft") && runTick.get(i).name().equals("tick") && runTick.get(i).type().equals("()V")) { tickAt = i; break; }
        assertTrue(drainAt >= 0 && tickAt > drainAt, "the drain precedes the frame's client ticks: drain@" + drainAt + " tick@" + tickAt);
        facts.put("N1d.PacketProcessor", "ConcurrentLinkedQueue; processQueuedPackets polls until empty; sole client caller Minecraft.runTick, call #" + drainAt + " before tick() call #" + tickAt);

        // F2a
        List<Call> tick = Jar.calls(jar.method("net/minecraft/client/Minecraft", "tick"));
        int keybinds = index(tick, "Minecraft", "handleKeybinds"), entities = index(tick, "ClientLevel", "tickEntities");
        assertTrue(keybinds >= 0 && entities > keybinds, "attacks (handleKeybinds) precede the local player's movement (tickEntities): " + keybinds + " < " + entities);
        List<Call> attack = Jar.calls(jar.method("net/minecraft/client/multiplayer/MultiPlayerGameMode", "attack"));
        int attackPacket = index(attack, "ServerboundAttackPacket", "<init>"), localAttack = index(attack, "Player", "attack");
        assertTrue(attackPacket >= 0 && localAttack > attackPacket, "the ATTACK packet is sent before the local Player.attack: " + attack);
        facts.put("F2a.order", "Minecraft.tick: handleKeybinds call #" + keybinds + " < ClientLevel.tickEntities call #" + entities + "; MultiPlayerGameMode.attack: " + attack);

        // F2b
        MethodModel extra = jar.method("net/minecraft/world/entity/player/Player", "causeExtraKnockback");
        List<Double> d = Jar.doubleConstants(extra);
        assertEquals(2, d.stream().filter(x -> x == 0.6).count(), "multiply(0.6, 1.0, 0.6): " + d);
        List<Call> ec = Jar.calls(extra);
        int mul = index(ec, "Vec3", "multiply"), sprint = index(ec, "Player", "setSprinting");
        assertTrue(mul >= 0 && sprint > mul, "slowdown then setSprinting(false): " + ec);
        assertEquals(List.of(), Jar.calls(jar.method("net/minecraft/world/entity/Entity", "hurtClient")), "Entity.hurtClient is a constant (false)");
        TreeSet<String> overriders = new TreeSet<>();
        for (var e : jar.classes.entrySet()) for (MethodModel m : ClassFile.of().parse(e.getValue()).methods()) if (m.methodName().equalsString("hurtClient") && !e.getKey().equals("net/minecraft/world/entity/Entity")) overriders.add(e.getKey());
        assertEquals(new TreeSet<>(List.of("net/minecraft/client/player/RemotePlayer", "net/minecraft/world/entity/decoration/BlockAttachedEntity", "net/minecraft/world/entity/decoration/ItemFrame", "net/minecraft/world/entity/boss/enderdragon/EndCrystal", "net/minecraft/world/entity/ExperienceOrb", "net/minecraft/world/entity/item/ItemEntity",
            "net/minecraft/world/entity/projectile/ShulkerBullet", "net/minecraft/world/entity/vehicle/VehicleEntity")), overriders, "the hurtClient overriders (targets whose hit can slow the attacker)");
        facts.put("F2b.causeExtraKnockback", "doubles " + d + "; calls " + ec);
        facts.put("F2b.hurtClientOverriders", overriders.toString());

        if (System.getenv("P13_WRITE_ARTIFACTS") != null) {
            StringBuilder sb = new StringBuilder("{\n  \"clientJarSha256\": \"").append(sha).append("\",\n  \"reader\": \"java.lang.classfile (ClientApplicationFactsTest)\",\n  \"facts\": {\n");
            int i = 0; for (var e : facts.entrySet()) sb.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue().toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"').append(++i < facts.size() ? ",\n" : "\n");
            sb.append("  }\n}\n");
            Path out = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase13-artifacts/client-application-facts.json"); Files.createDirectories(out.getParent()); Files.writeString(out, sb.toString());
        }
    }
}
