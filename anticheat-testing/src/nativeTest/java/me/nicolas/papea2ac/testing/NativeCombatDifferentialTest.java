package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.combat.CombatObservation;
import me.nicolas.papea2ac.core.CombatObservationMapper;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;
import org.junit.jupiter.api.*;

/**
 * Phase 11 native differential (brief section 50): the combat/entity model against the pinned Paper 26.2 build 124 natives - never stand-ins.
 *
 * <ul>
 * <li>VecDeltaCodec: randomized single decodes and whole move chains (decode then setBase, as ClientPacketListener.handleMoveEntity does) against
 * {@link CombatProfile#decodeAxis}, {@link EntityHistory} and the P11-F3-corrected {@link MovementContextLedger}, compared bit for bit;</li>
 * <li>PositionMoveRotation.calculateAbsolute (what handleTeleportEntity applies): absolute components are the packet value whatever the client's
 * position (EXACT), relative components depend on it (INTERPOLATION_DEPENDENT);</li>
 * <li>the Pose enum, DATA_SHARED_FLAGS_ID / DATA_POSE ids, and the attack / interact wire fields against {@link CombatProfile#PAPER_26_2};</li>
 * <li>native packets through the real normalizer and the Phase 11 mappers into the entity history.</li>
 * </ul>
 * Client-side handler facts that need the client jar (teleport does not reset the codec base; spawn / sync / moves do) are recorded, with the
 * extracted bytecode, in {@code docs/phase11-artifacts/native-differential-results.json}.
 */
class NativeCombatDifferentialTest {
    static final String GAME = "net.minecraft.network.protocol.game.";
    static final SessionKey KEY = new SessionKey(new UUID(0x11L, 0x50L), 1);
    static final CombatProfile P = CombatProfile.PAPER_26_2;
    static final int CODEC_CASES = 200_000, CHAINS = 2_000, CHAIN_LENGTH = 40, TELEPORT_CASES = 20_000;
    static Class<?> vec3, codec, pmr, relative;
    static Constructor<?> vecNew, pmrNew;
    static Method setBase, decode, calculateAbsolute;
    static Field vx, vy, vz;

    @BeforeAll static void bootstrap() throws Exception {
        NativePacketTest.bootstrap();
        vec3 = Class.forName("net.minecraft.world.phys.Vec3"); codec = Class.forName(GAME + "VecDeltaCodec");
        pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation"); relative = Class.forName("net.minecraft.world.entity.Relative");
        vecNew = vec3.getConstructor(double.class, double.class, double.class); pmrNew = pmr.getConstructor(vec3, vec3, float.class, float.class);
        setBase = codec.getMethod("setBase", vec3); decode = codec.getMethod("decode", long.class, long.class, long.class);
        calculateAbsolute = pmr.getMethod("calculateAbsolute", pmr, pmr, Set.class);
        vx = vec3.getField("x"); vy = vec3.getField("y"); vz = vec3.getField("z");
    }
    static Object vec(double x, double y, double z) throws Exception { return vecNew.newInstance(x, y, z); }
    static double[] xyz(Object v) throws Exception { return new double[]{vx.getDouble(v), vy.getDouble(v), vz.getDouble(v)}; }
    static long bits(double d) { return Double.doubleToRawLongBits(d); }
    /** A base the way the server produces them: grid-aligned, off-grid, large, negative zero, near the world border. */
    static double base(SplittableRandom r) {
        return switch (r.nextInt(6)) {
            case 0 -> r.nextLong(-1L << 36, 1L << 36) / 4096.0;
            case 1 -> r.nextDouble(-30_000_000, 30_000_000);
            case 2 -> r.nextDouble(-512, 512);
            case 3 -> -0.0;
            case 4 -> r.nextInt(-64, 320) + r.nextInt(16) / 16.0;
            default -> 29_999_984 - r.nextDouble(8);
        };
    }
    static short delta(SplittableRandom r) { return switch (r.nextInt(4)) { case 0 -> 0; case 1 -> (short) r.nextInt(-8, 9); default -> (short) r.nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1); }; }

    @Test void deltaDecodeMatchesTheNativeCodecBitForBit() throws Exception {
        SplittableRandom r = new SplittableRandom(0x11D1FFL); Object c = codec.getConstructor().newInstance(); int allZero = 0;
        for (int i = 0; i < CODEC_CASES; i++) {
            double bx = base(r), by = base(r), bz = base(r); short dx = delta(r), dy = delta(r), dz = delta(r);
            if (dx == 0 && dy == 0 && dz == 0) allZero++;
            setBase.invoke(c, vec(bx, by, bz)); double[] n = xyz(decode.invoke(c, (long) dx, (long) dy, (long) dz));
            assertEquals(bits(n[0]), bits(P.decodeAxis(bx, dx)), "x base " + bx + " delta " + dx); assertEquals(bits(n[1]), bits(P.decodeAxis(by, dy)), "y base " + by + " delta " + dy);
            assertEquals(bits(n[2]), bits(P.decodeAxis(bz, dz)), "z base " + bz + " delta " + dz);
        }
        assertTrue(allZero > 0, "the all-zero (return the base) branch was exercised");
        System.out.println("[phase11-native] codecCases=" + CODEC_CASES + " allZeroCases=" + allZero);
    }

    /** Whole chains: spawn / sync set the base and the position; each move decodes from the base and sets it (client order); a teleport moves the position only. */
    @Test void moveChainsMatchTheNativeCodecInTheHistoryAndTheMovementLedger() throws Exception {
        SplittableRandom r = new SplittableRandom(0x11C4A1L); long steps = 0, teleports = 0, syncs = 0;
        for (int chain = 0; chain < CHAINS; chain++) {
            EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20); MovementContextLedger l = new MovementContextLedger(KEY);
            Object c = codec.getConstructor().newInstance(); long seq = 1;
            double x = base(r), y = r.nextDouble(-64, 320), z = base(r);
            h.apply(KEY, new EntityObservation.Spawned(3, new UUID(3, chain), "minecraft:player", x, y, z, 0f, 0f, 0f), seq, 0, seq);
            l.apply(KEY, new ContextEvent.EntitySpawned(seq, 3, "minecraft:player", x, y, z)); seq++;
            setBase.invoke(c, vec(x, y, z)); double[] pos = {x, y, z}; boolean ledgerCertain = true;
            for (int k = 0; k < CHAIN_LENGTH; k++, seq++) {
                int op = r.nextInt(20);
                if (op == 0) { // absolute teleport: the client's position jumps, the codec base does not
                    double tx = base(r), ty = r.nextDouble(-64, 320), tz = base(r); teleports++;
                    h.apply(KEY, new EntityObservation.Teleported(3, tx, ty, tz, 0f, 0f, 0), seq, 0, seq); l.apply(KEY, new ContextEvent.EntityTeleported(seq, 3));
                    pos = xyz(pmr.getMethod("position").invoke(calculateAbsolute.invoke(null, pmrNew.newInstance(vec(pos[0], pos[1], pos[2]), vec(0, 0, 0), 0f, 0f), pmrNew.newInstance(vec(tx, ty, tz), vec(0, 0, 0), 0f, 0f), Set.of()))); ledgerCertain = false;
                } else if (op == 1) { // position sync: base and position
                    double sx = base(r), sy = r.nextDouble(-64, 320), sz = base(r); syncs++;
                    h.apply(KEY, new EntityObservation.PositionSynced(3, sx, sy, sz, 0f, 0f), seq, 0, seq); l.apply(KEY, new ContextEvent.EntityMovedTo(seq, 3, sx, sy, sz));
                    setBase.invoke(c, vec(sx, sy, sz)); pos = new double[]{sx, sy, sz}; ledgerCertain = true;
                } else {
                    short dx = delta(r), dy = delta(r), dz = delta(r); steps++;
                    h.apply(KEY, new EntityObservation.Moved(3, dx, dy, dz, true, 0f, 0f, false), seq, 0, seq); l.apply(KEY, new ContextEvent.EntityMovedBy(seq, 3, dx, dy, dz));
                    Object decoded = decode.invoke(c, (long) dx, (long) dy, (long) dz); setBase.invoke(c, decoded); pos = xyz(decoded);
                }
                EntityRevision v = h.lookupId(KEY, h.worldEpoch(), 3, seq + 1).revision(); // a lookup at S sees what was applied before S
                assertEquals(bits(pos[0]), bits(v.x()), "history x, chain " + chain + " step " + k); assertEquals(bits(pos[1]), bits(v.y())); assertEquals(bits(pos[2]), bits(v.z()));
                double[] b = xyz(codec.getMethod("getBase").invoke(c));
                assertEquals(bits(b[0]), bits(v.baseX()), "history codec base x"); assertEquals(bits(b[1]), bits(v.baseY())); assertEquals(bits(b[2]), bits(v.baseZ()));
                assertEquals(ledgerCertain, l.view().entitiesComplete(), "ledger certainty follows teleport / sync");
                if (ledgerCertain) { var e = l.view().relevantEntities().getFirst(); assertEquals(bits(pos[0]), bits(e.x()), "ledger x"); assertEquals(bits(pos[1]), bits(e.y())); assertEquals(bits(pos[2]), bits(e.z())); }
            }
        }
        System.out.println("[phase11-native] chains=" + CHAINS + " moveSteps=" + steps + " teleports=" + teleports + " syncs=" + syncs);
    }

    @Test void teleportApplicationSplitsExactFromInterpolationDependentComponents() throws Exception {
        SplittableRandom r = new SplittableRandom(0x11AB5L); Object relX = relative.getField("X").get(null), relYaw = relative.getField("Y_ROT").get(null);
        long absolute = 0, rel = 0;
        for (int i = 0; i < TELEPORT_CASES; i++) {
            Object change = pmrNew.newInstance(vec(base(r), r.nextDouble(-64, 320), base(r)), vec(0, 0, 0), r.nextFloat() * 360 - 180, r.nextFloat() * 180 - 90);
            Object here = pmrNew.newInstance(vec(base(r), r.nextDouble(-64, 320), base(r)), vec(0, 0, 0), r.nextFloat() * 360 - 180, r.nextFloat() * 180 - 90);
            Object elsewhere = pmrNew.newInstance(vec(base(r), r.nextDouble(-64, 320), base(r)), vec(0, 0, 0), r.nextFloat() * 360 - 180, r.nextFloat() * 180 - 90);
            double[] cp = xyz(pmr.getMethod("position").invoke(change));
            // absolute: identical result from any client position, equal to the packet value (the model's EXACT)
            double[] a1 = xyz(pmr.getMethod("position").invoke(calculateAbsolute.invoke(null, here, change, Set.of())));
            double[] a2 = xyz(pmr.getMethod("position").invoke(calculateAbsolute.invoke(null, elsewhere, change, Set.of())));
            for (int k = 0; k < 3; k++) { assertEquals(bits(0.0 + cp[k]), bits(a1[k]), "(0 + packet)"); assertEquals(bits(a1[k]), bits(a2[k]), "independent of the client position"); }
            EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20);
            h.apply(KEY, new EntityObservation.Spawned(4, new UUID(4, i), "minecraft:zombie", 0, 64, 0, 0f, 0f, 0f), 1, 0, 1);
            h.apply(KEY, new EntityObservation.Teleported(4, cp[0], cp[1], cp[2], 0f, 0f, 0), 2, 0, 2);
            var v = h.lookupId(KEY, h.worldEpoch(), 4, 3).revision();
            assertEquals(EntityRevision.Status.EXACT, v.positionStatus()); for (int k = 0; k < 3; k++) assertEquals(bits(a1[k]), bits(new double[]{v.x(), v.y(), v.z()}[k]), "model = native absolute"); absolute++;
            // relative x: the result moves with the client's (interpolated) position, which the server cannot see (the model's INTERPOLATION_DEPENDENT)
            double[] r1 = xyz(pmr.getMethod("position").invoke(calculateAbsolute.invoke(null, here, change, Set.of(relX, relYaw))));
            double[] r2 = xyz(pmr.getMethod("position").invoke(calculateAbsolute.invoke(null, elsewhere, change, Set.of(relX, relYaw))));
            double hx = xyz(pmr.getMethod("position").invoke(here))[0];
            assertEquals(bits(hx + cp[0]), bits(r1[0]), "relative component = client position + packet value"); assertEquals(bits(cp[1]), bits(r1[1]), "absolute components unchanged");
            if (bits(r1[0]) != bits(r2[0])) rel++;
            h.apply(KEY, new EntityObservation.Teleported(4, cp[0], cp[1], cp[2], 5f, 0f, EntityObservation.Teleported.REL_X | EntityObservation.Teleported.REL_YAW), 3, 0, 3);
            var w = h.lookupId(KEY, h.worldEpoch(), 4, 4).revision();
            assertEquals(EntityRevision.Status.INTERPOLATION_DEPENDENT, w.positionStatus()); assertEquals(EntityRevision.Status.INTERPOLATION_DEPENDENT, w.rotationStatus());
        }
        assertTrue(rel > TELEPORT_CASES * 0.9, "relative results depend on the client position (a pair drawn with equal positions, e.g. both -0.0, legitimately agrees)");
        System.out.println("[phase11-native] teleportCases=" + TELEPORT_CASES + " absoluteExact=" + absolute + " relativeClientDependent=" + rel);
    }

    @Test void profileConstantsMatchTheNativeEnumsAndDataIds() throws Exception {
        Class<?> pose = Class.forName("net.minecraft.world.entity.Pose"); List<String> names = new ArrayList<>();
        for (Object o : pose.getEnumConstants()) names.add(((Enum<?>) o).name());
        assertEquals(names, P.poses(), "pose names in ordinal (wire id) order");
        Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
        Field poseField = entity.getDeclaredField("DATA_POSE"), flags = entity.getDeclaredField("DATA_SHARED_FLAGS_ID"); poseField.setAccessible(true); flags.setAccessible(true);
        Method id = Class.forName("net.minecraft.network.syncher.EntityDataAccessor").getMethod("id");
        assertEquals(P.poseDataId(), (int) id.invoke(poseField.get(null))); assertEquals(P.sharedFlagsDataId(), (int) id.invoke(flags.get(null)));
        assertEquals(List.of("entityId"), Arrays.stream(Class.forName(GAME + "ServerboundAttackPacket").getRecordComponents()).map(RecordComponent::getName).toList(),
            "ATTACK carries the target network id only: no position, no hand");
        assertTrue(P.attackPacketIsDistinct());
        assertEquals(List.of("entityId", "hand", "location", "usingSecondaryAction"), Arrays.stream(Class.forName(GAME + "ServerboundInteractPacket").getRecordComponents()).map(RecordComponent::getName).toList());
        assertEquals(4096.0, P.deltaScale());
        Method enc = codec.getDeclaredMethod("encode", double.class); enc.setAccessible(true);
        assertEquals(4096L, (long) enc.invoke(null, 1.0), "the codec quantum is 1/4096 block");
    }

    @Test void nativePacketsFlowThroughTheNormalizerAndMappersIntoTheHistory() throws Exception {
        Object zombie = Class.forName("net.minecraft.world.entity.EntityTypes").getField("ZOMBIE").get(null);
        Object add = NativePacketTest.create(GAME + "ClientboundAddEntityPacket", new Class<?>[]{int.class, UUID.class, double.class, double.class, double.class, float.class, float.class,
            Class.forName("net.minecraft.world.entity.EntityType"), int.class, vec3, double.class}, 21, new UUID(7, 7), 10.3, 64.0, -5.7, 15f, 90f, zombie, 0, vec(0, 0, 0), 90.0);
        Object move = NativePacketTest.create(GAME + "ClientboundMoveEntityPacket$PosRot", new Class<?>[]{int.class, short.class, short.class, short.class, byte.class, byte.class, boolean.class},
            21, (short) 2048, (short) 0, (short) -4096, (byte) 64, (byte) 0, true);
        Object poseSerializer = Class.forName("net.minecraft.network.syncher.EntityDataSerializers").getField("POSE").get(null);
        Object byteSerializer = Class.forName("net.minecraft.network.syncher.EntityDataSerializers").getField("BYTE").get(null);
        Class<?> dataValue = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue"), serializer = Class.forName("net.minecraft.network.syncher.EntityDataSerializer");
        Object crouch = NativePacketTest.create(dataValue.getName(), new Class<?>[]{int.class, serializer, Object.class}, 6, poseSerializer, Class.forName("net.minecraft.world.entity.Pose").getField("CROUCHING").get(null));
        Object flagsValue = NativePacketTest.create(dataValue.getName(), new Class<?>[]{int.class, serializer, Object.class}, 0, byteSerializer, (byte) 0x02);
        Object data = NativePacketTest.create(GAME + "ClientboundSetEntityDataPacket", new Class<?>[]{int.class, List.class}, 21, List.of(flagsValue, crouch));
        Object attack = NativePacketTest.create(GAME + "ServerboundAttackPacket", new Class<?>[]{int.class}, 21);

        EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20); long seq = 1; Object c = codec.getConstructor().newInstance();
        for (Object packet : List.of(add, move, data)) {
            PacketPayload p = PaperPacketNormalizer.normalize(packet); assertFalse(p instanceof PacketPayload.Uncovered, packet.getClass().getName());
            var obs = new Observation(KEY, seq, seq, 0, new Observation.Outbound(seq, p));
            int sub = 0; for (EntityObservation e : CombatObservationMapper.entity(obs, 999, P)) assertTrue(h.apply(KEY, e, seq, sub++, seq));
            seq++;
        }
        setBase.invoke(c, vec(10.3, 64.0, -5.7)); double[] expected = xyz(decode.invoke(c, 2048L, 0L, -4096L));
        var now = h.lookupId(KEY, h.worldEpoch(), 21, seq);
        assertEquals(EntityLookup.Status.FOUND, now.status()); assertEquals("minecraft:zombie", now.type());
        assertEquals(bits(expected[0]), bits(now.revision().x())); assertEquals(bits(expected[2]), bits(now.revision().z())); assertEquals(EntityRevision.Status.EXACT, now.revision().positionStatus());
        assertEquals("CROUCHING", now.revision().pose(), "the native Pose value arrives by name (P11-F1)"); assertEquals(0x02, now.revision().sharedFlags());
        assertEquals("STANDING", h.lookupId(KEY, h.worldEpoch(), 21, 2).revision().pose(), "the synced default at spawn");
        var at = CombatObservationMapper.combat(new Observation(KEY, seq, seq, 0, new Observation.Attempt(seq, seq, PaperPacketNormalizer.normalize(attack))));
        assertEquals(21, ((CombatObservation.Attack) at.orElseThrow()).targetId());
        // rotation: the client applies MoveEntity.getYRot()/getXRot() (handleMoveEntity -> moveOrInterpolateTo(pos, yRot, xRot)); the history keeps exactly those
        Method yRot = move.getClass().getMethod("getYRot"), xRot = move.getClass().getMethod("getXRot");
        assertEquals(Float.floatToRawIntBits((float) yRot.invoke(move)), Float.floatToRawIntBits(now.revision().yaw()), "yaw as the client applies it");
        assertEquals(Float.floatToRawIntBits((float) xRot.invoke(move)), Float.floatToRawIntBits(now.revision().pitch()));
        // removal closes this incarnation; a native respawn of the same id is a new incarnation and the old key answers ID_REUSED
        var oldKey = now.key(); seq = apply(h, NativePacketTest.create(GAME + "ClientboundRemoveEntitiesPacket", new Class<?>[]{int[].class}, (Object) new int[]{21}), seq);
        assertEquals(EntityLookup.Status.DESPAWNED, h.lookupId(KEY, h.worldEpoch(), 21, seq).status());
        Object again = NativePacketTest.create(GAME + "ClientboundAddEntityPacket", new Class<?>[]{int.class, UUID.class, double.class, double.class, double.class, float.class, float.class,
            Class.forName("net.minecraft.world.entity.EntityType"), int.class, vec3, double.class}, 21, new UUID(8, 8), -4.0, 70.0, 9.5, 0f, 0f, Class.forName("net.minecraft.world.entity.EntityTypes").getField("SKELETON").get(null), 0, vec(0, 0, 0), 0.0);
        seq = apply(h, again, seq);
        var reused = h.lookupId(KEY, h.worldEpoch(), 21, seq);
        assertEquals("minecraft:skeleton", reused.type()); assertEquals(oldKey.incarnation() + 1, reused.key().incarnation()); assertEquals(new UUID(8, 8), reused.uuid(), "packet-observed identity preserved");
        assertEquals(EntityLookup.Status.ID_REUSED, h.lookupKey(oldKey, seq).status());
        // a native INTERACT is an interaction, never an attack
        Object interact = NativePacketTest.create(GAME + "ServerboundInteractPacket", new Class<?>[]{int.class, Class.forName("net.minecraft.world.InteractionHand"), vec3, boolean.class},
            21, Class.forName("net.minecraft.world.InteractionHand").getField("OFF_HAND").get(null), vec(0.25, 1.5, -0.125), true);
        var in = CombatObservationMapper.combat(new Observation(KEY, seq, seq, 0, new Observation.Attempt(seq + 1, seq + 1, PaperPacketNormalizer.normalize(interact)))).orElseThrow();
        assertInstanceOf(CombatObservation.Interaction.class, in); assertEquals("OFF_HAND", ((CombatObservation.Interaction) in).hand()); assertTrue(((CombatObservation.Interaction) in).secondaryAction());
    }
    /** One native clientbound packet through the normalizer and the mapper into the history at the next sequence; returns the sequence after it. */
    static long apply(EntityHistory h, Object packet, long seq) throws Exception {
        PacketPayload p = PaperPacketNormalizer.normalize(packet); assertFalse(p instanceof PacketPayload.Uncovered, packet.getClass().getName());
        int sub = 0; for (EntityObservation e : CombatObservationMapper.entity(new Observation(KEY, seq, seq, 0, new Observation.Outbound(seq, p)), 999, P)) assertTrue(h.apply(KEY, e, seq, sub++, seq));
        if (sub == 0) h.observeSequence(KEY, seq, seq);
        return seq + 1;
    }
}
