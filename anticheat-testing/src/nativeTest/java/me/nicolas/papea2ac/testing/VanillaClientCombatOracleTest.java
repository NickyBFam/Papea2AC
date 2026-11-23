package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import io.netty.buffer.Unpooled;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.combat.CombatObservation;
import me.nicolas.papea2ac.core.CombatObservationMapper;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;
import org.junit.jupiter.api.*;

/**
 * Phase 11 vanilla-CLIENT differential oracle (independent review thread, brief section 50).
 *
 * <p>{@link NativeCombatDifferentialTest} compares the model against the pinned PAPER jar's natives. This test closes the loop on the other end:
 * the pinned vanilla 26.2 CLIENT jar (sha256 {@value #CLIENT_SHA256}). The client's own classes - its VecDeltaCodec, PositionMoveRotation, Relative,
 * Mth, Pose and the entity / combat packet classes with their STREAM_CODECs - are loaded child-first from the client jar into an isolated class
 * loader (everything else resolves to the Paper classpath), so every comparison below is against client bytecode, never a stand-in:
 * <ul>
 * <li><b>tracker chains</b>: a ServerEntity-style tracker (server VecDeltaCodec encode, short-range check, periodic / overflow EntityPositionSync)
 *     sends real Paper packets; each is (a) captured through the real normalizer and Phase 11 mapper into {@link EntityHistory} and (b) encoded with
 *     the SERVER codec, decoded with the CLIENT codec and applied the way {@code ClientPacketListener.handleMoveEntity / handleEntityPositionSync}
 *     apply it (client VecDeltaCodec decode, setBase). Model target and codec base must equal the client's bit for bit;</li>
 * <li><b>rotation bytes</b>: every packed yaw/pitch byte through Rot / PosRot / AddEntity / RotateHead, wire round trip, client getters vs the
 *     captured values, and vs what the client entity finally holds ({@code Entity.setXRot}: {@code clamp(x % 360, -90, 90)});</li>
 * <li><b>teleport / sync wire</b>: TeleportEntity and EntityPositionSync with random values and relative sets: client-decoded fields equal the
 *     captured payload; absolute teleports through the client's {@code calculateAbsolute} equal the model's EXACT value;</li>
 * <li><b>serverbound combat wire</b>: client-built ATTACK and INTERACT, client codec encode, Paper codec decode, normalizer, combat mapper;</li>
 * <li><b>spear STAB</b>: the 26.2 piercing-weapon attack is {@code ServerboundPlayerActionPacket(STAB, BlockPos.ZERO, DOWN)}: no entity id.</li>
 * </ul>
 * Case counts are printed with the {@code [phase11-oracle]} prefix. Skipped (not failed) when the client jar is absent.
 */
class VanillaClientCombatOracleTest {
    static final String CLIENT_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    static final String GAME = "net.minecraft.network.protocol.game.";
    static final SessionKey KEY = new SessionKey(new UUID(0x11L, 0x0AL), 1);
    static final CombatProfile P = CombatProfile.PAPER_26_2;
    static final int SELF = 999_999;
    /** Client classes loaded child-first (with their nested classes). */
    static final List<String> CLIENT_FIRST = List.of(GAME + "VecDeltaCodec", "net.minecraft.world.entity.PositionMoveRotation", "net.minecraft.world.entity.Relative",
        "net.minecraft.util.Mth", "net.minecraft.world.entity.Pose", GAME + "ClientboundMoveEntityPacket", GAME + "ClientboundAddEntityPacket", GAME + "ClientboundRotateHeadPacket",
        GAME + "ClientboundTeleportEntityPacket", GAME + "ClientboundEntityPositionSyncPacket", GAME + "ClientboundRemoveEntitiesPacket", GAME + "ClientboundSetPassengersPacket",
        GAME + "ServerboundAttackPacket", GAME + "ServerboundInteractPacket", GAME + "ServerboundPlayerActionPacket");

    static ClassLoader client; static Path clientJar;
    static Class<?> vec3; static Constructor<?> vecNew; static Field vx, vy, vz;
    static Object registries; static Constructor<?> registryBuf; static Method encode, decode;

    /** Child-first for {@link #CLIENT_FIRST} only; every other class (netty, codecs, registries, Vec3, Entity...) is the Paper classpath's. */
    static final class ClientFirstLoader extends ClassLoader {
        private final JarFile jar;
        ClientFirstLoader(JarFile jar, ClassLoader parent) { super("vanilla-26.2-client", parent); this.jar = jar; }
        static boolean clientFirst(String name) { for (String c : CLIENT_FIRST) if (name.equals(c) || name.startsWith(c + "$")) return true; return false; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!clientFirst(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    ZipEntry e = jar.getEntry(name.replace('.', '/') + ".class");
                    if (e == null) throw new ClassNotFoundException(name + " (not in the client jar)");
                    try (InputStream in = jar.getInputStream(e)) { byte[] b = in.readAllBytes(); c = defineClass(name, b, 0, b.length); }
                    catch (IOException x) { throw new ClassNotFoundException(name, x); }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    static Path locateClientJar() {
        List<Path> candidates = new ArrayList<>();
        String property = System.getProperty("papea2ac.clientJar"); if (property != null) candidates.add(Path.of(property));
        candidates.add(Path.of("..", ".phase1-runtime", "client", "26.2.jar")); candidates.add(Path.of(".phase1-runtime", "client", "26.2.jar"));
        String appdata = System.getenv("APPDATA"); if (appdata != null) candidates.add(Path.of(appdata, ".minecraft", "versions", "26.2", "26.2.jar"));
        for (Path p : candidates) if (Files.isRegularFile(p)) return p;
        return null;
    }

    @BeforeAll static void bootstrap() throws Exception {
        NativePacketTest.bootstrap();
        clientJar = locateClientJar();
        Assumptions.assumeTrue(clientJar != null, "pinned vanilla 26.2 client jar not found (set -Dpapea2ac.clientJar or copy it to .phase1-runtime/client/26.2.jar)");
        assertEquals(CLIENT_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(clientJar))), "the pinned client jar");
        client = new ClientFirstLoader(new JarFile(clientJar.toFile()), VanillaClientCombatOracleTest.class.getClassLoader());
        vec3 = Class.forName("net.minecraft.world.phys.Vec3"); vecNew = vec3.getConstructor(double.class, double.class, double.class);
        vx = vec3.getField("x"); vy = vec3.getField("y"); vz = vec3.getField("z");
        Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries"), access = Class.forName("net.minecraft.core.RegistryAccess");
        registries = access.getMethod("fromRegistryOfRegistries", Class.forName("net.minecraft.core.Registry")).invoke(null, builtIn.getField("REGISTRY").get(null));
        registryBuf = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf").getConstructor(io.netty.buffer.ByteBuf.class, access);
        encode = Class.forName("net.minecraft.network.codec.StreamEncoder").getMethod("encode", Object.class, Object.class);
        decode = Class.forName("net.minecraft.network.codec.StreamDecoder").getMethod("decode", Object.class);
        // the isolation is real: these are the client jar's classes, distinct from Paper's
        for (String c : List.of(GAME + "VecDeltaCodec", "net.minecraft.world.entity.PositionMoveRotation", "net.minecraft.util.Mth", GAME + "ClientboundMoveEntityPacket"))
            assertNotSame(Class.forName(c), client.loadClass(c), c + " must come from the client jar");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------------------
    static Class<?> cc(String name) throws ClassNotFoundException { return client.loadClass(name); }
    static Object vec(double x, double y, double z) throws Exception { return vecNew.newInstance(x, y, z); }
    static double[] xyz(Object v) throws Exception { return new double[]{vx.getDouble(v), vy.getDouble(v), vz.getDouble(v)}; }
    static long bits(double d) { return Double.doubleToRawLongBits(d); }
    static int fbits(float f) { return Float.floatToRawIntBits(f); }
    static Object call(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(method) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(target, args); }
        throw new NoSuchMethodException(target.getClass().getName() + "." + method);
    }
    static Object server(String cls, Class<?>[] types, Object... args) throws Exception { return Class.forName(cls).getConstructor(types).newInstance(args); }
    static Object codecOf(Class<?> packetClass) throws Exception { Field f = packetClass.getField("STREAM_CODEC"); return f.get(null); }
    /** Encodes {@code packet} with its own class's STREAM_CODEC and decodes the bytes with {@code into}'s STREAM_CODEC (the other side's class). */
    static Object wire(Object packet, Class<?> into) throws Exception {
        Object buf = registryBuf.newInstance(Unpooled.buffer(), registries);
        encode.invoke(codecOf(packet.getClass()), buf, packet);
        Object decoded = decode.invoke(codecOf(into), buf);
        assertEquals(0, ((io.netty.buffer.ByteBuf) buf).readableBytes(), "the other side consumes exactly the bytes written: " + into.getName());
        return decoded;
    }
    /** One captured server packet through the real normalizer and the Phase 11 mapper into the history at {@code seq}. */
    static void capture(EntityHistory h, Object serverPacket, long seq) throws Exception {
        PacketPayload p = PaperPacketNormalizer.normalize(serverPacket); assertFalse(p instanceof PacketPayload.Uncovered, serverPacket.getClass().getName());
        int sub = 0;
        for (EntityObservation e : CombatObservationMapper.entity(new Observation(KEY, seq, seq, 0, new Observation.Outbound(seq, p)), SELF, P)) assertTrue(h.apply(KEY, e, seq, sub++, seq));
        if (sub == 0) h.observeSequence(KEY, seq, seq);
    }
    static EntityRevision at(EntityHistory h, int id, long seq) {
        EntityLookup l = h.lookupId(KEY, h.worldEpoch(), id, seq); assertEquals(EntityLookup.Status.FOUND, l.status(), l.detail()); return l.revision();
    }
    /** {@code Entity.setXRot} in the 26.2 client: {@code Math.clamp(xRot % 360.0F, -90.0F, 90.0F)} (javap of the pinned client jar). */
    static float clientAppliedPitch(float x) { return Math.clamp(x % 360.0F, -90.0F, 90.0F); }

    // ---- 1. tracker chains: Paper encoder -> wire -> client decoder vs the model -------------------------------------------------------------
    @Test void trackerChainsDecodeOnTheClientExactlyAsTheModelHasThem() throws Exception {
        final int ENTITIES = 400, TICKS = 150;
        Class<?> serverCodec = Class.forName(GAME + "VecDeltaCodec"), clientCodec = cc(GAME + "VecDeltaCodec");
        Method sEncX = serverCodec.getMethod("encodeX", vec3), sEncY = serverCodec.getMethod("encodeY", vec3), sEncZ = serverCodec.getMethod("encodeZ", vec3), sSet = serverCodec.getMethod("setBase", vec3);
        Method cDecode = clientCodec.getMethod("decode", long.class, long.class, long.class), cSet = clientCodec.getMethod("setBase", vec3), cBase = clientCodec.getMethod("getBase");
        Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation"); Constructor<?> pmrNew = pmr.getConstructor(vec3, vec3, float.class, float.class);
        Class<?> clientPos = cc(GAME + "ClientboundMoveEntityPacket$Pos"), clientSync = cc(GAME + "ClientboundEntityPositionSyncPacket"), clientAdd = cc(GAME + "ClientboundAddEntityPacket");
        Object zombie = Class.forName("net.minecraft.world.entity.EntityTypes").getField("ZOMBIE").get(null);
        SplittableRandom r = new SplittableRandom(0x0A11CE11L);
        long moves = 0, syncs = 0, overflowSyncs = 0, wirePackets = 0; double maxQuantError = 0;
        for (int e = 0; e < ENTITIES; e++) {
            EntityHistory h = new EntityHistory(KEY, P, null, 4 << 20); int id = 100 + e; long seq = 1;
            double x = r.nextDouble(-30_000_000, 30_000_000) * (r.nextInt(4) == 0 ? 1 : 1e-5), y = r.nextDouble(-64, 320), z = r.nextDouble(-512, 512);
            // spawn (pairing): the client's recreateFromPacket sets codec base and position to the packet position; the server's codec base is the same value
            Object add = server(GAME + "ClientboundAddEntityPacket", new Class<?>[]{int.class, UUID.class, double.class, double.class, double.class, float.class, float.class,
                Class.forName("net.minecraft.world.entity.EntityType"), int.class, vec3, double.class}, id, new UUID(e, 1), x, y, z, 0f, 0f, zombie, 0, vec(0, 0, 0), 0.0);
            capture(h, add, seq); Object addOnClient = wire(add, clientAdd); wirePackets++;
            Object sc = serverCodec.getConstructor().newInstance(), c = clientCodec.getConstructor().newInstance();
            sSet.invoke(sc, vec(x, y, z));
            cSet.invoke(c, vec((double) call(addOnClient, "getX"), (double) call(addOnClient, "getY"), (double) call(addOnClient, "getZ")));
            double[] target = xyz(cBase.invoke(c)); seq++;
            double vxs = r.nextDouble(-0.6, 0.6), vys = r.nextDouble(-0.5, 0.5), vzs = r.nextDouble(-0.6, 0.6);
            for (int t = 1; t <= TICKS; t++, seq++) {
                if (r.nextInt(10) == 0) { vxs = r.nextDouble(-0.6, 0.6); vys = r.nextDouble(-0.5, 0.5); vzs = r.nextDouble(-0.6, 0.6); }
                double jump = r.nextInt(200) == 0 ? r.nextDouble(-40, 40) : 0; // a knockback / teleport-sized move overflows the short delta range
                x += vxs + jump; y += vys; z += vzs + r.nextDouble(-1e-3, 1e-3);
                Object now = vec(x, y, z);
                long dx = (long) sEncX.invoke(sc, now), dy = (long) sEncY.invoke(sc, now), dz = (long) sEncZ.invoke(sc, now);
                boolean overflow = dx < Short.MIN_VALUE || dx > Short.MAX_VALUE || dy < Short.MIN_VALUE || dy > Short.MAX_VALUE || dz < Short.MIN_VALUE || dz > Short.MAX_VALUE;
                Object packet;
                if (overflow || t % 60 == 0) { // ServerEntity.sendChanges: EntityPositionSync on overflow / periodic resync; base = position on both sides
                    packet = server(GAME + "ClientboundEntityPositionSyncPacket", new Class<?>[]{int.class, pmr, boolean.class}, id, pmrNew.newInstance(now, vec(0, 0, 0), 0f, 0f), false);
                    Object onClient = wire(packet, clientSync);
                    Object p = call(call(onClient, "values"), "position"); cSet.invoke(c, p); target = xyz(p);
                    syncs++; if (overflow) overflowSyncs++;
                } else {
                    packet = server(GAME + "ClientboundMoveEntityPacket$Pos", new Class<?>[]{int.class, short.class, short.class, short.class, boolean.class}, id, (short) dx, (short) dy, (short) dz, false);
                    Object onClient = wire(packet, clientPos);
                    Object decoded = cDecode.invoke(c, (long) (short) call(onClient, "getXa"), (long) (short) call(onClient, "getYa"), (long) (short) call(onClient, "getZa"));
                    cSet.invoke(c, decoded); target = xyz(decoded); moves++;
                }
                sSet.invoke(sc, now); wirePackets++;
                capture(h, packet, seq);
                EntityRevision v = at(h, id, seq + 1);
                double[] base = xyz(cBase.invoke(c));
                assertEquals(bits(target[0]), bits(v.x()), "client target x, entity " + e + " tick " + t); assertEquals(bits(target[1]), bits(v.y())); assertEquals(bits(target[2]), bits(v.z()));
                assertEquals(bits(base[0]), bits(v.baseX()), "client codec base x"); assertEquals(bits(base[1]), bits(v.baseY())); assertEquals(bits(base[2]), bits(v.baseZ()));
                assertEquals(EntityRevision.Status.EXACT, v.positionStatus());
                maxQuantError = Math.max(maxQuantError, Math.max(Math.abs(target[0] - x), Math.max(Math.abs(target[1] - y), Math.abs(target[2] - z))));
            }
        }
        // descriptive: the network target differs from the server's true position by the delta quantization only (half a 1/4096 step, plus the
        // rounding of large coordinates); Phase 12 must treat the target as quantized, never as the server position
        assertTrue(maxQuantError <= 0.5 / 4096 + 1e-6, "quantization bound " + maxQuantError);
        System.out.println("[phase11-oracle] trackerChains entities=" + ENTITIES + " ticks=" + TICKS + " moves=" + moves + " syncs=" + syncs + " overflowSyncs=" + overflowSyncs
            + " wirePackets=" + wirePackets + " maxNetworkTargetVsServerError=" + maxQuantError + " mismatches=0");
    }

    // ---- 2. rotation bytes ----------------------------------------------------------------------------------------------------------------
    @Test void everyRotationByteDecodesOnTheClientAsCaptured() throws Exception {
        Class<?> clientRot = cc(GAME + "ClientboundMoveEntityPacket$Rot"), clientPosRot = cc(GAME + "ClientboundMoveEntityPacket$PosRot"), clientHead = cc(GAME + "ClientboundRotateHeadPacket");
        Class<?> clientAdd = cc(GAME + "ClientboundAddEntityPacket");
        Object zombie = Class.forName("net.minecraft.world.entity.EntityTypes").getField("ZOMBIE").get(null);
        long cases = 0, clampedPitchBytes = 0, modelPitchCases = 0;
        for (int yb = -128; yb < 128; yb++) for (int pb = -128; pb < 128; pb++) {
            Object rot = server(GAME + "ClientboundMoveEntityPacket$Rot", new Class<?>[]{int.class, byte.class, byte.class, boolean.class}, 5, (byte) yb, (byte) pb, true);
            Object onClient = wire(rot, clientRot);
            PacketPayload captured = PaperPacketNormalizer.normalize(rot);
            var d = (SemanticPayload.EntityDelta) captured;
            assertEquals(fbits((float) call(onClient, "getYRot")), fbits(d.rotation().yaw()), "yaw byte " + yb); assertEquals(fbits((float) call(onClient, "getXRot")), fbits(d.rotation().pitch()), "pitch byte " + pb);
            assertTrue((boolean) call(onClient, "hasRotation")); assertFalse((boolean) call(onClient, "hasPosition"));
            if (fbits(clientAppliedPitch(d.rotation().pitch())) != fbits(d.rotation().pitch())) clampedPitchBytes++;
            if (yb == 0) { // oracle-05 / P11-F23: the model holds the pitch the client entity holds after Entity.setXRot, for every pitch byte
                EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20);
                h.apply(KEY, new EntityObservation.Spawned(5, new UUID(5, pb & 0xFF), "minecraft:zombie", 0, 64, 0, 0f, d.rotation().pitch(), 0f), 1, 0, 1);
                assertEquals(fbits(clientAppliedPitch(d.rotation().pitch())), fbits(at(h, 5, 2).pitch()), "spawn pitch byte " + pb);
                capture(h, rot, 2);
                EntityRevision v = at(h, 5, 3);
                assertEquals(fbits(clientAppliedPitch(d.rotation().pitch())), fbits(v.pitch()), "Rot pitch byte " + pb); assertEquals(fbits(d.rotation().yaw()), fbits(v.yaw()), "yaw raw (setYRot)");
                modelPitchCases += 2;
            }
            cases++;
        }
        clampedPitchBytes /= 256; // per pitch byte
        for (int b = -128; b < 128; b++) {
            Object posRot = server(GAME + "ClientboundMoveEntityPacket$PosRot", new Class<?>[]{int.class, short.class, short.class, short.class, byte.class, byte.class, boolean.class}, 6, (short) b, (short) -b, (short) (b * 255), (byte) b, (byte) -b, false);
            Object pr = wire(posRot, clientPosRot); var d = (SemanticPayload.EntityDelta) PaperPacketNormalizer.normalize(posRot);
            assertEquals(d.dx(), (short) call(pr, "getXa")); assertEquals(d.dy(), (short) call(pr, "getYa")); assertEquals(d.dz(), (short) call(pr, "getZa"));
            assertEquals(fbits(d.rotation().yaw()), fbits((float) call(pr, "getYRot"))); assertEquals(fbits(d.rotation().pitch()), fbits((float) call(pr, "getXRot")));
            Object add = server(GAME + "ClientboundAddEntityPacket", new Class<?>[]{int.class, UUID.class, double.class, double.class, double.class, float.class, float.class,
                Class.forName("net.minecraft.world.entity.EntityType"), int.class, vec3, double.class}, 7, new UUID(7, b), 1.0, 2.0, 3.0, b * 360f / 256f, b * 360f / 256f, zombie, 0, vec(0, 0, 0), (double) (b * 360f / 256f));
            Object ad = wire(add, clientAdd); var s = (SemanticPayload.EntitySpawn) PaperPacketNormalizer.normalize(add);
            assertEquals(fbits(s.rotation().yaw()), fbits((float) call(ad, "getYRot"))); assertEquals(fbits(s.rotation().pitch()), fbits((float) call(ad, "getXRot")));
            assertEquals(fbits(s.headYaw()), fbits((float) call(ad, "getYHeadRot")));
            // the public RotateHead constructor needs a live Entity: build the Paper packet from its wire form (VarInt id, packed head-yaw byte) instead
            Object raw = registryBuf.newInstance(Unpooled.buffer(), registries); call(raw, "writeVarInt", 9); call(raw, "writeByte", b);
            Object head = decode.invoke(codecOf(Class.forName(GAME + "ClientboundRotateHeadPacket")), raw);
            Object hd = wire(head, clientHead); var hr = (SemanticPayload.HeadRotation) PaperPacketNormalizer.normalize(head);
            assertEquals(fbits(hr.yaw()), fbits((float) call(hd, "getYHeadRot")));
            cases += 3;
        }
        System.out.println("[phase11-oracle] rotationBytes cases=" + cases + " mismatches=0 pitchBytesTheClientClamps=" + clampedPitchBytes
            + " modelPitchEqualsClientHeldPitch=" + modelPitchCases + " (Entity.setXRot clamps; unreachable from a vanilla tracker, whose pitch is already within [-90, 90])");
    }

    // ---- 3. teleport / position-sync wire and the client's calculateAbsolute ---------------------------------------------------------------
    @Test void teleportAndSyncDecodeOnTheClientAsCapturedAndAbsoluteTeleportsAreExact() throws Exception {
        final int CASES = 20_000;
        Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation"); Constructor<?> pmrNew = pmr.getConstructor(vec3, vec3, float.class, float.class);
        Class<?> relative = Class.forName("net.minecraft.world.entity.Relative");
        Class<?> clientTeleport = cc(GAME + "ClientboundTeleportEntityPacket"), clientSync = cc(GAME + "ClientboundEntityPositionSyncPacket");
        Class<?> cPmr = cc("net.minecraft.world.entity.PositionMoveRotation"); Constructor<?> cPmrNew = cPmr.getConstructor(vec3, vec3, float.class, float.class);
        Method cAbs = cPmr.getMethod("calculateAbsolute", cPmr, cPmr, Set.class);
        Object[] serverRel = relative.getEnumConstants();
        SplittableRandom r = new SplittableRandom(0x7E1E9047L); long absolute = 0, relativeCases = 0, syncs = 0;
        for (int i = 0; i < CASES; i++) {
            double x = r.nextBoolean() ? r.nextDouble(-3e7, 3e7) : (r.nextInt(8) == 0 ? -0.0 : r.nextDouble(-64, 64)), y = r.nextDouble(-64, 320), z = r.nextDouble(-512, 512);
            float yaw = r.nextFloat() * 720 - 360, pitch = r.nextFloat() * 400 - 200; // includes out-of-range pitch: the client clamps it
            Set<Object> rel = new HashSet<>(); if (r.nextInt(3) == 0) for (Object o : serverRel) if (r.nextInt(3) == 0) rel.add(o);
            Object change = pmrNew.newInstance(vec(x, y, z), vec(0, 0, 0), yaw, pitch);
            Object teleport = server(GAME + "ClientboundTeleportEntityPacket", new Class<?>[]{int.class, pmr, Set.class, boolean.class}, 8, change, rel, false);
            Object onClient = wire(teleport, clientTeleport);
            var cap = (SemanticPayload.EntityPosition) PaperPacketNormalizer.normalize(teleport);
            Object cChange = call(onClient, "change"); double[] cp = xyz(call(cChange, "position"));
            assertEquals(bits(cap.position().x()), bits(cp[0])); assertEquals(bits(cap.position().y()), bits(cp[1])); assertEquals(bits(cap.position().z()), bits(cp[2]));
            assertEquals(fbits(cap.rotation().yaw()), fbits((float) call(cChange, "yRot"))); assertEquals(fbits(cap.rotation().pitch()), fbits((float) call(cChange, "xRot")));
            Set<String> clientRelNames = new TreeSet<>(); for (Object o : (Set<?>) call(onClient, "relatives")) clientRelNames.add(((Enum<?>) o).name());
            Set<String> capturedRelNames = new TreeSet<>(); for (var o : cap.relative()) capturedRelNames.add(o.name());
            assertEquals(clientRelNames, capturedRelNames, "relative flags");
            // the model against the CLIENT's calculateAbsolute, from an arbitrary current client state
            EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20);
            h.apply(KEY, new EntityObservation.Spawned(8, new UUID(8, i), "minecraft:zombie", r.nextDouble(-100, 100), 64, r.nextDouble(-100, 100), r.nextFloat() * 360 - 180, r.nextFloat() * 180 - 90, 0f), 1, 0, 1);
            capture(h, teleport, 2);
            EntityRevision v = at(h, 8, 3);
            Object current = cPmrNew.newInstance(vec(v.baseX(), v.baseY(), v.baseZ()), vec(0, 0, 0), r.nextFloat() * 360 - 180, r.nextFloat() * 180 - 90);
            Set<Object> clientRel = new HashSet<>(); for (Object o : (Set<?>) call(onClient, "relatives")) clientRel.add(o);
            Object applied = cAbs.invoke(null, current, cChange, clientRel); double[] ap = xyz(call(applied, "position"));
            boolean relPos = clientRelNames.contains("X") || clientRelNames.contains("Y") || clientRelNames.contains("Z"), relRot = clientRelNames.contains("Y_ROT") || clientRelNames.contains("X_ROT");
            if (!relPos) { assertEquals(EntityRevision.Status.EXACT, v.positionStatus()); assertEquals(bits(ap[0]), bits(v.x())); assertEquals(bits(ap[1]), bits(v.y())); assertEquals(bits(ap[2]), bits(v.z())); absolute++; }
            else { assertEquals(EntityRevision.Status.INTERPOLATION_DEPENDENT, v.positionStatus()); relativeCases++; }
            if (!relRot) {
                assertEquals(fbits((float) call(applied, "yRot")), fbits(v.yaw()), "teleport yaw");
                assertEquals(fbits(clientAppliedPitch((float) call(applied, "xRot"))), fbits(v.pitch()), "teleport pitch as the client entity finally holds it (calculateAbsolute, then setXRot)");
            }
            // position sync: the client decodes the same values; the model's base = target = packet position
            Object sync = server(GAME + "ClientboundEntityPositionSyncPacket", new Class<?>[]{int.class, pmr, boolean.class}, 8, change, r.nextBoolean());
            Object sc = wire(sync, clientSync); var sp = (SemanticPayload.EntityPositionSync) PaperPacketNormalizer.normalize(sync);
            double[] sv = xyz(call(call(sc, "values"), "position"));
            assertEquals(bits(sp.position().x()), bits(sv[0])); assertEquals(bits(sp.position().y()), bits(sv[1])); assertEquals(bits(sp.position().z()), bits(sv[2]));
            assertEquals(fbits(sp.rotation().yaw()), fbits((float) call(call(sc, "values"), "yRot"))); assertEquals(fbits(sp.rotation().pitch()), fbits((float) call(call(sc, "values"), "xRot")));
            capture(h, sync, 3); EntityRevision sr = at(h, 8, 4); // sync: base = target = packet position; pitch as the client entity holds it (P11-F23)
            assertEquals(bits(sv[0]), bits(sr.x())); assertEquals(bits(sv[0]), bits(sr.baseX())); assertEquals(fbits(clientAppliedPitch(sp.rotation().pitch())), fbits(sr.pitch()), "sync pitch " + sp.rotation().pitch());
            syncs++;
        }
        System.out.println("[phase11-oracle] teleportWire cases=" + CASES + " absoluteExactVsClientCalculateAbsolute=" + absolute + " relativeInterpolationDependent=" + relativeCases
            + " positionSyncWire=" + syncs + " mismatches=0");
    }

    // ---- 4. serverbound ATTACK / INTERACT: client codec -> Paper codec -> normalizer -> combat mapper --------------------------------------
    @Test void clientAttackAndInteractReachTheCombatMapperAsSent() throws Exception {
        final int CASES = 20_000;
        Class<?> clientAttack = cc(GAME + "ServerboundAttackPacket"), clientInteract = cc(GAME + "ServerboundInteractPacket");
        Class<?> serverAttack = Class.forName(GAME + "ServerboundAttackPacket"), serverInteract = Class.forName(GAME + "ServerboundInteractPacket");
        Class<?> hand = Class.forName("net.minecraft.world.InteractionHand");
        SplittableRandom r = new SplittableRandom(0xA77AC4L); long attacks = 0, interactions = 0, lossyLocations = 0; double maxLocationError = 0;
        for (int i = 0; i < CASES; i++) {
            int id = r.nextInt(0, Integer.MAX_VALUE);
            Object attack = clientAttack.getConstructor(int.class).newInstance(id);
            Object onServer = wire(attack, serverAttack);
            var obs = CombatObservationMapper.combat(new Observation(KEY, 10, 10, 0, new Observation.Attempt(10, 10, PaperPacketNormalizer.normalize(onServer)))).orElseThrow();
            assertInstanceOf(CombatObservation.Attack.class, obs); assertEquals(id, ((CombatObservation.Attack) obs).targetId()); attacks++;
            // INTERACT: the client sends hitLocation - entity position (MultiPlayerGameMode.interact), an entity-RELATIVE offset, encoded with Vec3.LP_STREAM_CODEC
            double ox = r.nextDouble(-1.5, 1.5), oy = r.nextDouble(0, 3), oz = r.nextDouble(-1.5, 1.5);
            Object h = hand.getEnumConstants()[r.nextInt(2)]; boolean secondary = r.nextBoolean();
            Object interact = clientInteract.getConstructor(int.class, hand, vec3, boolean.class).newInstance(id, h, vec(ox, oy, oz), secondary);
            Object onServerInteract = wire(interact, serverInteract);
            var in = (CombatObservation.Interaction) CombatObservationMapper.combat(new Observation(KEY, 11, 11, 0, new Observation.Attempt(11, 11, PaperPacketNormalizer.normalize(onServerInteract)))).orElseThrow();
            assertEquals(id, in.targetId()); assertEquals(((Enum<?>) h).name(), in.hand()); assertEquals(secondary, in.secondaryAction());
            double[] sent = xyz(call(interact, "location")), decodedOnServer = xyz(call(onServerInteract, "location"));
            assertEquals(bits(decodedOnServer[0]), bits(in.x())); assertEquals(bits(decodedOnServer[1]), bits(in.y())); assertEquals(bits(decodedOnServer[2]), bits(in.z()));
            double err = Math.max(Math.abs(sent[0] - in.x()), Math.max(Math.abs(sent[1] - in.y()), Math.abs(sent[2] - in.z())));
            if (err != 0) lossyLocations++; maxLocationError = Math.max(maxLocationError, err);
            interactions++;
        }
        System.out.println("[phase11-oracle] serverboundCombatWire attacks=" + attacks + " interactions=" + interactions + " mismatches=0 interactLocationLossyCases=" + lossyLocations
            + " maxInteractLocationError=" + maxLocationError + " (Vec3.LP_STREAM_CODEC; the captured location is the server-decoded entity-relative offset)");
    }

    // ---- 5. version facts the model relies on, read from the client jar ---------------------------------------------------------------------
    @Test void clientPoseEnumAndStabActionFacts() throws Exception {
        List<String> clientPoses = new ArrayList<>(); for (Object o : cc("net.minecraft.world.entity.Pose").getEnumConstants()) clientPoses.add(((Enum<?>) o).name());
        assertEquals(P.poses(), clientPoses, "client Pose ordinal order = CombatProfile");
        List<String> actions = new ArrayList<>(); for (Object o : cc(GAME + "ServerboundPlayerActionPacket$Action").getEnumConstants()) actions.add(((Enum<?>) o).name());
        assertTrue(actions.contains("STAB"), "26.2 has the piercing-weapon STAB action");
        // what the capture makes of the client's spear attack (MultiPlayerGameMode.piercingAttack): STAB, BlockPos.ZERO, Direction.DOWN, sequence 0
        Class<?> action = Class.forName(GAME + "ServerboundPlayerActionPacket$Action"), blockPos = Class.forName("net.minecraft.core.BlockPos"), direction = Class.forName("net.minecraft.core.Direction");
        Object stab = Class.forName(GAME + "ServerboundPlayerActionPacket").getConstructor(action, blockPos, direction)
            .newInstance(action.getField("STAB").get(null), blockPos.getField("ZERO").get(null), direction.getField("DOWN").get(null));
        PacketPayload p = PaperPacketNormalizer.normalize(stab);
        var mapped = CombatObservationMapper.combat(new Observation(KEY, 12, 12, 0, new Observation.Attempt(12, 12, p)));
        // oracle-01 / P11-F19: a target-less piercing attack, never an Attack and never a target
        assertInstanceOf(CombatObservation.PiercingAttack.class, mapped.orElseThrow(), "STAB is the spear's attack");
        assertEquals(CombatProfile.PIERCING_ATTACK_ACTION, "STAB"); assertTrue(actions.contains(CombatProfile.PIERCING_ATTACK_ACTION));
        // the other player actions stay non-combat
        for (String other : List.of("RELEASE_USE_ITEM", "DROP_ITEM", "SWAP_ITEM_WITH_OFFHAND", "START_DESTROY_BLOCK")) {
            Object packet = Class.forName(GAME + "ServerboundPlayerActionPacket").getConstructor(action, blockPos, direction)
                .newInstance(action.getField(other).get(null), blockPos.getField("ZERO").get(null), direction.getField("DOWN").get(null));
            assertTrue(CombatObservationMapper.combat(new Observation(KEY, 13, 13, 0, new Observation.Attempt(13, 13, PaperPacketNormalizer.normalize(packet)))).isEmpty(), other);
        }
        System.out.println("[phase11-oracle] clientPoses=" + clientPoses.size() + " playerActions=" + actions + " stabNormalizedAs=" + p + " stabCombatObservation=" + mapped);
    }

    // ---- 6. client-local pose changes (oracle-04 / P11-F22) ------------------------------------------------------------------------------------
    @Test void clientLocalPoseChangesNeverLeaveAKnownStalePose() throws Exception {
        Class<?> types = Class.forName("net.minecraft.world.entity.EntityTypes"), entityType = Class.forName("net.minecraft.world.entity.EntityType");
        Class<?>[] addSig = {int.class, UUID.class, double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class};
        Class<?> clientAdd = cc(GAME + "ClientboundAddEntityPacket");
        // Warden.recreateFromPacket: data 1 -> EMERGING on the client; data 0 -> the synced default
        for (int data : new int[]{0, 1}) {
            Object add = server(GAME + "ClientboundAddEntityPacket", addSig, 40, new UUID(40, data), 1.0, 64.0, 1.0, 0f, 0f, types.getField("WARDEN").get(null), data, vec(0, 0, 0), 0.0);
            assertEquals(data, (int) call(wire(add, clientAdd), "getData"), "the client reads the spawn data");
            EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20); capture(h, add, 1);
            EntityRevision v = at(h, 40, 2);
            if (data == 1) assertFalse(v.poseKnown(), "a Warden spawned with data 1 is EMERGING on the client: never STANDING");
            else assertEquals("STANDING", v.pose());
        }
        // LivingEntity.handleEntityEvent(3) -> die() -> setPose(DYING) on the client before the server's pose data
        EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20);
        capture(h, server(GAME + "ClientboundAddEntityPacket", addSig, 41, new UUID(41, 0), 1.0, 64.0, 1.0, 0f, 0f, types.getField("ZOMBIE").get(null), 0, vec(0, 0, 0), 0.0), 1);
        assertEquals("STANDING", at(h, 41, 2).pose());
        int sub = 0; for (EntityObservation e : CombatObservationMapper.entity(new Observation(KEY, 2, 2, 0, new Observation.Outbound(2, new SemanticPayload.EntitySimpleEvent(41, (byte) 3))), SELF, P)) assertTrue(h.apply(KEY, e, 2, sub++, 2));
        assertEquals(1, sub, "the death event is mapped");
        assertFalse(at(h, 41, 3).poseKnown(), "after EntityEvent(3) the client holds DYING: the model must not still claim STANDING");
        System.out.println("[phase11-oracle] clientLocalPose wardenSpawns=2 deathEvents=1 mismatches=0");
    }
}
