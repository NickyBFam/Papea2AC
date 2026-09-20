package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.MovementProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 native differential of the PROFILE DATA and the arithmetic KERNELS against the pinned jar (NUMERICAL_POLICY:
 * "Reproduce qualified client lookup/algorithm, indexing, input conversion and output casts"): every block's four movement
 * properties, the player's attribute supplier, the sine table bits and {@code Mth.sin/cos} over random arguments,
 * {@code AttributeInstance.calculateValue} with several ADD_MULTIPLIED_TOTAL modifiers (the hash-order emulation),
 * {@code Entity.getInputVector}, {@code Direction.axisStepOrder}, static {@code AABB.clip} and the inside-block traversal
 * {@code BlockGetter.forEachBlockIntersectedBetween}. Bit-level comparisons, no tolerances. Registry-only bootstrap
 * (no Level), so nothing here touches entity state.
 */
class NativeMovementProfileTest {
    static final MovementProfile PROFILE = MovementProfile.paper26_2();
    static Class<?> mth, vec3C, entityC, aabbC, blockGetter, directionC, blockPosC; static Constructor<?> vec3Ctor; static Method mthSin, mthCos;

    @BeforeAll static void bootstrap() throws Exception {
        NativePacketTest.bootstrap();
        mth = Class.forName("net.minecraft.util.Mth"); vec3C = Class.forName("net.minecraft.world.phys.Vec3"); entityC = Class.forName("net.minecraft.world.entity.Entity"); aabbC = Class.forName("net.minecraft.world.phys.AABB");
        blockGetter = Class.forName("net.minecraft.world.level.BlockGetter"); directionC = Class.forName("net.minecraft.core.Direction"); blockPosC = Class.forName("net.minecraft.core.BlockPos");
        vec3Ctor = vec3C.getConstructor(double.class, double.class, double.class); mthSin = mth.getMethod("sin", double.class); mthCos = mth.getMethod("cos", double.class);
    }
    static Object vec3(Vec3d v) throws Exception { return vec3Ctor.newInstance(v.x(), v.y(), v.z()); }
    static Vec3d vec3d(Object v) throws Exception { return new Vec3d((Double) vec3C.getField("x").get(v), (Double) vec3C.getField("y").get(v), (Double) vec3C.getField("z").get(v)); }

    @Test void everyBlockMovementPropertyMatchesTheJar() throws Exception {
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
        Method getKey = Class.forName("net.minecraft.core.Registry").getMethod("getKey", Object.class);
        Class<?> blockC = Class.forName("net.minecraft.world.level.block.Block");
        Method friction = blockC.getMethod("getFriction"), speed = blockC.getMethod("getSpeedFactor"), jump = blockC.getMethod("getJumpFactor"), bounce = blockC.getMethod("getBounceRestitution");
        BlockMovementTable table = PROFILE.blocks(); int n = 0;
        for (Object block : (Iterable<?>) registry) {
            String name = getKey.invoke(registry, block).toString(); BlockMovementTable.Block b = table.block(name); assertNotNull(b, name); n++;
            assertEquals(Float.floatToRawIntBits((Float) friction.invoke(block)), Float.floatToRawIntBits(b.friction()), name + " friction");
            assertEquals(Float.floatToRawIntBits((Float) speed.invoke(block)), Float.floatToRawIntBits(b.speedFactor()), name + " speed");
            assertEquals(Float.floatToRawIntBits((Float) jump.invoke(block)), Float.floatToRawIntBits(b.jumpFactor()), name + " jump");
            assertEquals(Float.floatToRawIntBits((Float) bounce.invoke(block)), Float.floatToRawIntBits(b.bounceRestitution()), name + " bounce");
            assertTrue(b.fullyClassified(), name + " has an unclassified movement override: " + b.insideClass() + "/" + b.stepOnClass() + "/" + b.fallOnClass());
        }
        assertEquals(table.blockCount(), n);
    }
    @Test void playerAttributeSupplierMatchesTheJar() throws Exception {
        Object supplier = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeSupplier$Builder").getMethod("build").invoke(Class.forName("net.minecraft.world.entity.player.Player").getMethod("createAttributes").invoke(null));
        Method getBase = supplier.getClass().getMethod("getBaseValue", Class.forName("net.minecraft.core.Holder"));
        Object attrRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ATTRIBUTE").get(null);
        Method get = Class.forName("net.minecraft.core.Registry").getMethod("get", Class.forName("net.minecraft.resources.Identifier"));
        Method parse = Class.forName("net.minecraft.resources.Identifier").getMethod("parse", String.class);
        for (String id : MovementProfile.MOVEMENT_ATTRIBUTES) {
            Object holder = ((Optional<?>) get.invoke(attrRegistry, parse.invoke(null, id))).orElseThrow();
            assertEquals(Double.doubleToRawLongBits((Double) getBase.invoke(supplier, holder)), Double.doubleToRawLongBits(PROFILE.attribute(id).playerDefault()), id);
        }
        Field sprintF = Class.forName("net.minecraft.world.entity.LivingEntity").getDeclaredField("SPEED_MODIFIER_SPRINTING"); sprintF.setAccessible(true); Object sprint = sprintF.get(null);
        assertEquals(PROFILE.sprintModifier().amount(), (Double) sprint.getClass().getMethod("amount").invoke(sprint)); assertEquals(PROFILE.sprintModifier().id(), sprint.getClass().getMethod("id").invoke(sprint).toString());
    }
    @Test void sineTableBitsAndLookupsMatchTheJar() throws Exception {
        Field sinF = mth.getDeclaredField("SIN"); sinF.setAccessible(true); float[] sin = (float[]) sinF.get(null);
        assertEquals(65536, sin.length); assertEquals(PROFILE.sineTableLength(), sin.length);
        for (int i = 0; i < sin.length; i++) assertEquals(Float.floatToRawIntBits(sin[i]), Float.floatToRawIntBits(PROFILE.sin(i / MovementProfile.SIN_SCALE + 1e-12)), "table index " + i);
        Random r = new Random(0x5EED);
        for (int i = 0; i < 50000; i++) {
            double d; switch (i % 4) { case 0 -> d = (r.nextFloat() * 720f - 360f) * MovementProfile.DEG_TO_RAD; case 1 -> d = r.nextDouble() * 1e6 - 5e5; case 2 -> d = (float) (r.nextGaussian() * 3); default -> d = r.nextInt(1 << 20) * 0.017453292f; }
            assertEquals(Float.floatToRawIntBits((Float) mthSin.invoke(null, d)), Float.floatToRawIntBits(PROFILE.sin(d)), "sin " + d);
            assertEquals(Float.floatToRawIntBits((Float) mthCos.invoke(null, d)), Float.floatToRawIntBits(PROFILE.cos(d)), "cos " + d);
        }
    }
    @Test void attributeCalculationMatchesAnAttributeInstanceWithSeveralTotalMultipliers() throws Exception {
        Class<?> instC = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeInstance"), modC = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier"), opC = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier$Operation");
        Class<?> idC = Class.forName("net.minecraft.resources.Identifier"); Method parse = idC.getMethod("parse", String.class);
        Object attrRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ATTRIBUTE").get(null);
        Object holder = ((Optional<?>) Class.forName("net.minecraft.core.Registry").getMethod("get", idC).invoke(attrRegistry, parse.invoke(null, MovementProfile.ATTR_MOVEMENT_SPEED))).orElseThrow();
        Constructor<?> instCtor = instC.getConstructor(Class.forName("net.minecraft.core.Holder"), java.util.function.Consumer.class);
        Constructor<?> modCtor = modC.getConstructor(idC, double.class, opC);
        Method setBase = instC.getMethod("setBaseValue", double.class), add = instC.getMethod("addTransientModifier", modC), getValue = instC.getMethod("getValue");
        Object[] ops = opC.getEnumConstants();
        String[] ids = { "minecraft:sprinting", "minecraft:effect.speed", "minecraft:effect.slowness", "minecraft:a", "minecraft:b", "minecraft:c", "papea2ac:x", "papea2ac:y", "minecraft:armor.chest", "minecraft:enchantment.swift_sneak", "minecraft:base_attack_speed", "minecraft:z1", "minecraft:z2", "minecraft:z3" };
        Random r = new Random(11); int cases = 0;
        for (int c = 0; c < 4000; c++) {
            int n = 1 + r.nextInt(6); List<MovementProfile.Modifier> mods = new ArrayList<>(); List<String> used = new ArrayList<>();
            Object inst = instCtor.newInstance(holder, (java.util.function.Consumer<Object>) o -> { }); double base = 0.05 + r.nextDouble() * 0.2; setBase.invoke(inst, base);
            for (int k = 0; k < n; k++) {
                String id = ids[r.nextInt(ids.length)]; if (used.contains(id)) continue; used.add(id);
                int op = r.nextInt(3); double amount = (r.nextDouble() - 0.3) * (op == 0 ? 0.1 : 0.6);
                mods.add(new MovementProfile.Modifier(id, amount, MovementProfile.Operation.values()[op]));
                add.invoke(inst, modCtor.newInstance(parse.invoke(null, id), amount, ops[op]));
            }
            double expected = (Double) getValue.invoke(inst), actual = MovementProfile.calculateValue(PROFILE.attribute(MovementProfile.ATTR_MOVEMENT_SPEED), base, mods);
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), "case " + c + " " + mods); cases++;
        }
        assertTrue(cases > 3000);
    }
    @Test void inputVectorMatchesEntityGetInputVector() throws Exception {
        Method m = entityC.getDeclaredMethod("getInputVector", vec3C, float.class, float.class); m.setAccessible(true);
        Random r = new Random(7); int cases = 0;
        float[] speeds = { 0.1f, 0.13f, 0.02f, 0.025999999f, 0.098f, 0.3f, 1.0f, 0.0f, 0.13f * 0.3f };
        for (int i = 0; i < 20000; i++) {
            double x = switch (r.nextInt(5)) { case 0 -> 0.98; case 1 -> -0.98; case 2 -> 0.0; case 3 -> 0.98 * 0.3f * 0.98f; default -> (r.nextDouble() - 0.5) * 3; };
            double z = switch (r.nextInt(5)) { case 0 -> 0.98; case 1 -> -0.98; case 2 -> 0.0; case 3 -> 0.98 * 0.2f * 0.98f; default -> (r.nextDouble() - 0.5) * 3; };
            float speed = speeds[r.nextInt(speeds.length)], yaw = r.nextInt(3) == 0 ? r.nextInt(720) - 360 : r.nextFloat() * 100000f - 50000f;
            Vec3d expected = vec3d(m.invoke(null, vec3(new Vec3d(x, 0.0, z)), speed, yaw)), actual = PhysicsKernels.inputVector(PROFILE, x, 0.0, z, speed, yaw);
            assertEquals(expected, actual, "input " + x + "," + z + " speed " + speed + " yaw " + yaw); cases++;
        }
        assertEquals(20000, cases);
    }
    @Test void axisStepOrderMatchesDirection() throws Exception {
        Method order = directionC.getMethod("axisStepOrder", vec3C); Random r = new Random(3);
        for (int i = 0; i < 5000; i++) {
            Vec3d v = new Vec3d((r.nextDouble() - 0.5) * (i % 7 == 0 ? 0 : 2), (r.nextDouble() - 0.5), i % 5 == 0 ? (r.nextDouble() - 0.5) * 2 : v0(r));
            List<?> expected = (List<?>) order.invoke(null, vec3(v)); int[] actual = v.axisStepOrder();
            for (int k = 0; k < 3; k++) assertEquals(expected.get(k).toString().toLowerCase(Locale.ROOT).charAt(0) - 'x', actual[k], v.toString());
        }
    }
    private static double v0(Random r) { return r.nextInt(2) == 0 ? 0.0 : -0.0; }
    @Test void boxClipMatchesAabbClip() throws Exception {
        Method clip = aabbC.getMethod("clip", double.class, double.class, double.class, double.class, double.class, double.class, vec3C, vec3C); Random r = new Random(5); int hits = 0;
        for (int i = 0; i < 20000; i++) {
            double x0 = r.nextInt(5) - 2, y0 = r.nextInt(5) - 2, z0 = r.nextInt(5) - 2;
            Vec3d from = new Vec3d(r.nextDouble() * 8 - 4, r.nextDouble() * 8 - 4, r.nextDouble() * 8 - 4), to = from.add((r.nextDouble() - 0.5) * 6, (r.nextDouble() - 0.5) * 6, (r.nextDouble() - 0.5) * 6);
            if (i % 3 == 0) to = new Vec3d(to.x(), from.y(), to.z());
            Optional<?> expected = (Optional<?>) clip.invoke(null, x0, y0, z0, x0 + 1, y0 + 1, z0 + 1, vec3(from), vec3(to));
            Vec3d actual = PhysicsKernels.clipBox(x0, y0, z0, x0 + 1, y0 + 1, z0 + 1, from, to);
            if (expected.isPresent()) { assertEquals(vec3d(expected.get()), actual, "clip " + from + "->" + to); hits++; } else assertNull(actual, "miss " + from + "->" + to);
        }
        assertTrue(hits > 100, "hits " + hits);
    }
    @Test void insideBlockTraversalMatchesForEachBlockIntersectedBetween() throws Exception {
        Class<?> visitorC = Class.forName("net.minecraft.world.level.BlockGetter$BlockStepVisitor");
        Method m = blockGetter.getMethod("forEachBlockIntersectedBetween", vec3C, vec3C, aabbC, visitorC);
        Constructor<?> aabbCtor = aabbC.getConstructor(double.class, double.class, double.class, double.class, double.class, double.class);
        Method px = blockPosC.getMethod("getX"), py = blockPosC.getMethod("getY"), pz = blockPosC.getMethod("getZ");
        Random r = new Random(9); int cases = 0, cellsTotal = 0;
        for (int i = 0; i < 6000; i++) {
            Vec3d from = new Vec3d(r.nextDouble() * 6 - 3, r.nextDouble() * 6 - 3, r.nextDouble() * 6 - 3);
            double scale = switch (i % 6) { case 0 -> 0.0; case 1 -> 1e-6; case 2 -> 0.3; case 3 -> 1.5; case 4 -> 4.0; default -> 0.05; };
            Vec3d to = from.add((r.nextDouble() - 0.5) * scale, (r.nextDouble() - 0.5) * scale, (r.nextDouble() - 0.5) * scale);
            if (i % 11 == 0) to = new Vec3d(from.x(), to.y(), from.z()); // pure vertical (falls)
            if (i % 13 == 0) to = from.add(0, 0, (r.nextDouble() - 0.5) * scale); // single axis
            float w = r.nextInt(3) == 0 ? 0.3f : 0.6f, h = r.nextInt(3) == 0 ? 0.6f : 1.8f;
            double half = (double) (w / 2f);
            Aabb box = new Aabb(to.x() - half, to.y(), to.z() - half, to.x() + half, to.y() + (double) h, to.z() + half).inflate(-9.999999747378752E-6);
            List<String> expected = new ArrayList<>(), actual = new ArrayList<>();
            Object visitor = Proxy.newProxyInstance(visitorC.getClassLoader(), new Class<?>[] { visitorC }, (p, method, a) -> {
                if (method.getName().equals("visit")) { expected.add(px.invoke(a[0]) + "," + py.invoke(a[0]) + "," + pz.invoke(a[0]) + "@" + a[1]); return true; }
                return method.getName().equals("toString") ? "v" : method.getName().equals("hashCode") ? 1 : Boolean.valueOf(p == a[0]);
            });
            m.invoke(null, vec3(from), vec3(to), aabbCtor.newInstance(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()), visitor);
            PhysicsKernels.traverseBlocks(from, to, box, (x, y, z, step) -> { actual.add(x + "," + y + "," + z + "@" + step); return true; });
            assertEquals(expected, actual, "traversal " + from + " -> " + to + " box " + box); cases++; cellsTotal += expected.size();
        }
        assertEquals(6000, cases); assertTrue(cellsTotal > 20000);
    }
}
