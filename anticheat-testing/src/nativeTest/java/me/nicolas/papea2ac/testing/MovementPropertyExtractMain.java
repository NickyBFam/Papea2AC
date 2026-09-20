package me.nicolas.papea2ac.testing;

import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5 controlled, version-pinned extraction of the vanilla MOVEMENT PROPERTY definitions (WORLD_MODEL W3: "version
 * provides pure definitions"; NUMERICAL_POLICY NUM1: "If table-based, serialize/hash table bits generated independently
 * from permitted version observations"). Runs ONLY on the nativeTest classpath against the pinned Paper 26.2 build 124
 * jar, never in production. Four artifacts, all consumed by anticheat-version as data:
 * <ol>
 * <li>{@code block-movement-<profile>.tsv}: per block {@code getFriction/getSpeedFactor/getJumpFactor/getBounceRestitution}
 *     (float bits), tag membership (climbable, fences, walls, fence_gates, suppresses_bounce, can_glide_through - read from the jar's own
 *     data-pack JSON, the same files the client and server load), the FenceGateBlock instanceof marker, the {@code isAir} flag, and the DECLARING
 *     CLASS of every movement-relevant override ({@code entityInside, stepOn, fallOn, getEntityInsideCollisionShape} and
 *     the four property getters) so the physics module can classify behaviour per class and report anything it does not
 *     model as UNSUPPORTED instead of ignoring it; plus every block state whose fluid state is non-empty (type, amount,
 *     source) addressed by the same mixed-radix state index the shape table uses.</li>
 * <li>{@code movement-profile-<profile>.tsv}: the player's attribute supplier ({@code Player.createAttributes()}: id,
 *     default, min, max), {@code LivingEntity.SPEED_MODIFIER_SPRINTING}, every registered mob effect with its attribute
 *     templates (id, amount, operation), and the sine table digest.</li>
 * <li>{@code mth-sin-<profile>.bin}: the 65536 float bits of {@code Mth.SIN} (big-endian), the client's movement basis.</li>
 * <li>a JSON summary for the artifacts directory.</li>
 * </ol>
 * Usage: {@code MovementPropertyExtractMain <block-table-out> <profile-out> <sin-out> <summary-json-out>}
 */
public final class MovementPropertyExtractMain {
    static final String PROFILE = "paper-26.2-124";

    public static void main(String[] args) throws Exception {
        Path blockOut = Path.of(args[0]), profileOut = Path.of(args[1]), sinOut = Path.of(args[2]), summaryOut = Path.of(args[3]);
        NativePacketTest.bootstrap();
        ClassLoader cl = MovementPropertyExtractMain.class.getClassLoader();
        Class<?> blockC = Class.forName("net.minecraft.world.level.block.Block"), behaviour = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour"),
            stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase"), stateC = Class.forName("net.minecraft.world.level.block.state.BlockState"),
            levelC = Class.forName("net.minecraft.world.level.Level"), blockPosC = Class.forName("net.minecraft.core.BlockPos"), entityC = Class.forName("net.minecraft.world.entity.Entity"),
            applierC = Class.forName("net.minecraft.world.entity.InsideBlockEffectApplier"), blockGetter = Class.forName("net.minecraft.world.level.BlockGetter"),
            fenceGateC = Class.forName("net.minecraft.world.level.block.FenceGateBlock"), fluidStateC = Class.forName("net.minecraft.world.level.material.FluidState");
        Object blockRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
        Object fluidRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("FLUID").get(null);
        Method getKey = Class.forName("net.minecraft.core.Registry").getMethod("getKey", Object.class);
        Method getFriction = blockC.getMethod("getFriction"), getSpeedFactor = blockC.getMethod("getSpeedFactor"), getJumpFactor = blockC.getMethod("getJumpFactor"), getBounce = blockC.getMethod("getBounceRestitution");
        Method getStateDefinition = blockC.getMethod("getStateDefinition");
        Class<?> stateDef = Class.forName("net.minecraft.world.level.block.state.StateDefinition");
        Method getPossibleStates = stateDef.getMethod("getPossibleStates");
        Method getFluidState = stateBase.getMethod("getFluidState"), isAir = stateBase.getMethod("isAir"), defaultState = blockC.getMethod("defaultBlockState");
        Method fluidType = fluidStateC.getMethod("getType"), fluidAmount = fluidStateC.getMethod("getAmount"), fluidSource = fluidStateC.getMethod("isSource"), fluidEmpty = fluidStateC.getMethod("isEmpty"), fluidOwnHeight = fluidStateC.getMethod("getOwnHeight");
        // override signatures (26.2): entityInside(BlockState, Level, BlockPos, Entity, InsideBlockEffectApplier, boolean), stepOn(Level, BlockPos, BlockState, Entity), fallOn(Level, BlockState, BlockPos, Entity, double),
        // getEntityInsideCollisionShape(BlockState, BlockGetter, BlockPos, Entity)
        Map<String, Class<?>[]> overrides = new LinkedHashMap<>();
        overrides.put("entityInside", new Class<?>[] { stateC, levelC, blockPosC, entityC, applierC, boolean.class });
        overrides.put("stepOn", new Class<?>[] { levelC, blockPosC, stateC, entityC });
        overrides.put("fallOn", new Class<?>[] { levelC, stateC, blockPosC, entityC, double.class });
        overrides.put("getEntityInsideCollisionShape", new Class<?>[] { stateC, blockGetter, blockPosC, entityC });
        overrides.put("getFriction", new Class<?>[0]); overrides.put("getSpeedFactor", new Class<?>[0]); overrides.put("getJumpFactor", new Class<?>[0]); overrides.put("getBounceRestitution", new Class<?>[0]);
        for (var e : overrides.entrySet()) declaring(behaviour, blockC, e.getKey(), e.getValue()); // fail fast if a signature changed

        Map<String, Set<String>> tags = new LinkedHashMap<>();
        for (String tag : List.of("climbable", "fences", "walls", "fence_gates", "suppresses_bounce", "can_glide_through", "fall_damage_resetting")) tags.put(tag, resolveTag(cl, tag, new HashSet<>()));

        StringBuilder blocks = new StringBuilder();
        blocks.append("# Papea2AC block movement properties, profile ").append(PROFILE).append(", extracted from the pinned server jar by MovementPropertyExtractMain (nativeTest lane). Reviewed artifact; regenerate only under review.\n");
        blocks.append("# B <block> <friction> <speedFactor> <jumpFactor> <bounceRestitution> <tags|-> <entityInside> <stepOn> <fallOn> <entityInsideShape> <getFriction> <getSpeedFactor> <getJumpFactor> <getBounceRestitution>   (declaring class simple name of each override, BlockBehaviour/Block = not overridden)\n");
        blocks.append("# F <block> <stateIndex> <fluid> <amount> <source> <ownHeight>   (only states with a non-empty fluid state; stateIndex = registry order = mixed-radix index of the canonical key, as in the shape table)\n");
        int blockCount = 0, fluidStates = 0, stateCount = 0;
        Map<String, Integer> classCounts = new TreeMap<>(); Map<String, List<String>> classMembers = new TreeMap<>();
        List<String> names = new ArrayList<>(); Map<String, Object> byName = new HashMap<>();
        for (Object block : (Iterable<?>) blockRegistry) { String name = getKey.invoke(blockRegistry, block).toString(); names.add(name); byName.put(name, block); }
        Set<String> frictionValues = new TreeSet<>(), speedValues = new TreeSet<>(), jumpValues = new TreeSet<>(), bounceValues = new TreeSet<>();
        for (String name : names) { // registry order, like the shape table
            Object block = byName.get(name); blockCount++;
            float friction = (Float) getFriction.invoke(block), speed = (Float) getSpeedFactor.invoke(block), jump = (Float) getJumpFactor.invoke(block), bounce = (Float) getBounce.invoke(block);
            frictionValues.add(Float.toString(friction)); speedValues.add(Float.toString(speed)); jumpValues.add(Float.toString(jump)); bounceValues.add(Float.toString(bounce));
            List<String> tagList = new ArrayList<>();
            for (var t : tags.entrySet()) if (t.getValue().contains(name)) tagList.add(t.getKey());
            if (fenceGateC.isInstance(block)) tagList.add("fence_gate_block");
            if ((Boolean) isAir.invoke(defaultState.invoke(block))) tagList.add("air");
            blocks.append("B ").append(name).append(' ').append(Float.toString(friction)).append(' ').append(Float.toString(speed)).append(' ').append(Float.toString(jump)).append(' ').append(Float.toString(bounce))
                .append(' ').append(tagList.isEmpty() ? "-" : String.join(",", tagList));
            for (var e : overrides.entrySet()) {
                String dc = declaring(behaviour, block.getClass(), e.getKey(), e.getValue()).getSimpleName();
                blocks.append(' ').append(dc);
                if (!dc.equals("BlockBehaviour") && !dc.equals("Block")) { classCounts.merge(e.getKey() + ":" + dc, 1, Integer::sum); classMembers.computeIfAbsent(e.getKey() + ":" + dc, k -> new ArrayList<>()).add(name); }
            }
            blocks.append('\n');
            List<?> states = (List<?>) getPossibleStates.invoke(getStateDefinition.invoke(block));
            for (int i = 0; i < states.size(); i++) {
                stateCount++;
                Object fs = getFluidState.invoke(states.get(i));
                if ((Boolean) fluidEmpty.invoke(fs)) continue;
                fluidStates++;
                blocks.append("F ").append(name).append(' ').append(i).append(' ').append(getKey.invoke(fluidRegistry, fluidType.invoke(fs))).append(' ').append(fluidAmount.invoke(fs)).append(' ').append(fluidSource.invoke(fs)).append(' ').append(Float.toString((Float) fluidOwnHeight.invoke(fs))).append('\n');
            }
        }
        Files.createDirectories(blockOut.getParent());
        byte[] blockBytes = blocks.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(blockOut, blockBytes);

        // ---- movement profile: attributes, sprint modifier, effects, sine table digest
        StringBuilder prof = new StringBuilder();
        prof.append("# Papea2AC movement profile constants, profile ").append(PROFILE).append(", extracted from the pinned server jar by MovementPropertyExtractMain (nativeTest lane).\n");
        prof.append("# A <attribute> <playerDefault> <min> <max> <registryDefault>   (Player.createAttributes() supplier; RangedAttribute bounds used by sanitizeValue)\n");
        prof.append("# M <name> <modifierId> <amount> <operation>   (transient modifiers the client adds locally)\n");
        prof.append("# E <effect> <category> [<attribute> <modifierId> <amount> <operation>]...   (MobEffect.attributeModifiers templates; amount is per (amplifier+1))\n");
        prof.append("# T <name> <sha256> <length>   (table digests)\n");
        Class<?> playerC = Class.forName("net.minecraft.world.entity.player.Player");
        Object supplier = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeSupplier$Builder").getMethod("build").invoke(playerC.getMethod("createAttributes").invoke(null));
        Field instancesF = supplier.getClass().getDeclaredField("instances"); instancesF.setAccessible(true);
        Object attrRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ATTRIBUTE").get(null);
        Class<?> holderC = Class.forName("net.minecraft.core.Holder"); Method holderValue = holderC.getMethod("value");
        Class<?> attrInstC = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeInstance"); Method getBaseValue = attrInstC.getMethod("getBaseValue");
        Class<?> attrC = Class.forName("net.minecraft.world.entity.ai.attributes.Attribute"); Method getDefault = attrC.getMethod("getDefaultValue");
        Class<?> rangedC = Class.forName("net.minecraft.world.entity.ai.attributes.RangedAttribute"); Method getMin = rangedC.getMethod("getMinValue"), getMax = rangedC.getMethod("getMaxValue");
        @SuppressWarnings("unchecked") Map<Object, Object> instances = (Map<Object, Object>) instancesF.get(supplier);
        TreeMap<String, String> attrLines = new TreeMap<>();
        for (var e : instances.entrySet()) {
            Object attr = holderValue.invoke(e.getKey()); String id = getKey.invoke(attrRegistry, attr).toString();
            double base = (Double) getBaseValue.invoke(e.getValue()), def = (Double) getDefault.invoke(attr);
            String min = rangedC.isInstance(attr) ? Double.toString((Double) getMin.invoke(attr)) : "-", max = rangedC.isInstance(attr) ? Double.toString((Double) getMax.invoke(attr)) : "-";
            attrLines.put(id, "A " + id + ' ' + Double.toString(base) + ' ' + min + ' ' + max + ' ' + Double.toString(def) + '\n');
        }
        for (String l : attrLines.values()) prof.append(l);
        Class<?> livingC = Class.forName("net.minecraft.world.entity.LivingEntity");
        Field sprintF = livingC.getDeclaredField("SPEED_MODIFIER_SPRINTING"); sprintF.setAccessible(true); Object sprint = sprintF.get(null);
        Class<?> modC = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier"); Method modId = modC.getMethod("id"), modAmount = modC.getMethod("amount"), modOp = modC.getMethod("operation");
        prof.append("M sprinting ").append(modId.invoke(sprint)).append(' ').append(Double.toString((Double) modAmount.invoke(sprint))).append(' ').append(modOp.invoke(sprint)).append('\n');
        Object effectRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("MOB_EFFECT").get(null);
        Class<?> effectC = Class.forName("net.minecraft.world.effect.MobEffect"); Field templatesF = effectC.getField("attributeModifiers"); Method category = effectC.getMethod("getCategory");
        Class<?> templateC = Class.forName("net.minecraft.world.effect.MobEffect$AttributeTemplate"); Method tId = templateC.getMethod("id"), tAmount = templateC.getMethod("amount"), tOp = templateC.getMethod("operation");
        TreeMap<String, String> effectLines = new TreeMap<>(); int effectCount = 0, effectsWithModifiers = 0;
        for (Object effect : (Iterable<?>) effectRegistry) {
            effectCount++;
            String id = getKey.invoke(effectRegistry, effect).toString();
            StringBuilder line = new StringBuilder("E ").append(id).append(' ').append(category.invoke(effect));
            @SuppressWarnings("unchecked") Map<Object, Object> templates = (Map<Object, Object>) templatesF.get(effect);
            TreeMap<String, String> tl = new TreeMap<>();
            for (var t : templates.entrySet()) { String aid = getKey.invoke(attrRegistry, holderValue.invoke(t.getKey())).toString(); tl.put(aid, aid + ' ' + tId.invoke(t.getValue()) + ' ' + Double.toString((Double) tAmount.invoke(t.getValue())) + ' ' + tOp.invoke(t.getValue())); }
            if (!tl.isEmpty()) effectsWithModifiers++;
            for (String s : tl.values()) line.append(' ').append(s);
            effectLines.put(id, line.append('\n').toString());
        }
        for (String l : effectLines.values()) prof.append(l);
        // ---- sine table
        Field sinF = Class.forName("net.minecraft.util.Mth").getDeclaredField("SIN"); sinF.setAccessible(true); float[] sin = (float[]) sinF.get(null);
        ByteBuffer bb = ByteBuffer.allocate(sin.length * 4).order(ByteOrder.BIG_ENDIAN); for (float f : sin) bb.putFloat(f);
        byte[] sinBytes = bb.array(); Files.createDirectories(sinOut.getParent()); Files.write(sinOut, sinBytes);
        String sinSha = sha256(sinBytes);
        prof.append("T mth-sin ").append(sinSha).append(' ').append(sin.length).append('\n');
        // independent check of the generating formula (documentation only; the resource bits are authoritative)
        int formulaMismatches = 0; for (int i = 0; i < sin.length; i++) if (Float.floatToRawIntBits(sin[i]) != Float.floatToRawIntBits((float) Math.sin(i * Math.PI * 2.0 / 65536.0))) formulaMismatches++;
        Method mthSin = Class.forName("net.minecraft.util.Mth").getMethod("sin", double.class), mthCos = Class.forName("net.minecraft.util.Mth").getMethod("cos", double.class);
        // conformance vectors for the physics module's sine/cosine (yaw radians as the client computes them: yaw * 0.017453292F widened)
        StringBuilder vectors = new StringBuilder();
        float[] yaws = { 0f, 1f, -1f, 45f, 90f, 135f, 180f, -180f, 270f, 359.99f, 360f, 720f, -0.0f, 12.34567f, 1234567f, -98765.4f, 0.0001f, 89.99999f };
        for (float yaw : yaws) { double r = yaw * 0.017453292F; vectors.append("V ").append(Float.toString(yaw)).append(' ').append(Float.floatToRawIntBits((Float) mthSin.invoke(null, r))).append(' ').append(Float.floatToRawIntBits((Float) mthCos.invoke(null, r))).append('\n'); }
        prof.append("# V <yawDegrees> <sinBits> <cosBits>   (Mth.sin/cos of yaw * 0.017453292F, conformance vectors)\n").append(vectors);
        byte[] profBytes = prof.toString().getBytes(StandardCharsets.UTF_8); Files.write(profileOut, profBytes);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", PROFILE); summary.put("blocks", blockCount); summary.put("states", stateCount); summary.put("fluidStates", fluidStates);
        summary.put("frictionValues", new ArrayList<>(frictionValues)); summary.put("speedFactorValues", new ArrayList<>(speedValues)); summary.put("jumpFactorValues", new ArrayList<>(jumpValues)); summary.put("bounceRestitutionValues", new ArrayList<>(bounceValues));
        Map<String, Object> tagSizes = new LinkedHashMap<>(); for (var t : tags.entrySet()) tagSizes.put(t.getKey(), t.getValue().size()); summary.put("tagSizes", tagSizes);
        summary.put("overrideClasses", classCounts); summary.put("overrideMembers", classMembers);
        summary.put("attributes", attrLines.size()); summary.put("effects", effectCount); summary.put("effectsWithAttributeModifiers", effectsWithModifiers);
        summary.put("sinTableLength", sin.length); summary.put("sinTableSha256", sinSha); summary.put("sinFormulaMismatches", formulaMismatches);
        summary.put("blockTableSha256", sha256(blockBytes)); summary.put("blockTableBytes", blockBytes.length); summary.put("profileTableSha256", sha256(profBytes)); summary.put("profileTableBytes", profBytes.length);
        Files.createDirectories(summaryOut.getParent()); Files.writeString(summaryOut, json(summary, 0) + "\n");
        System.out.println("blocks=" + blockCount + " states=" + stateCount + " fluidStates=" + fluidStates + " effects=" + effectCount + " sinSha=" + sinSha + " formulaMismatches=" + formulaMismatches);
        System.out.println("blockTable sha256=" + sha256(blockBytes) + " profile sha256=" + sha256(profBytes));
    }

    /** Declaring class of the most specific override of {@code name(params)} in {@code from}'s hierarchy (stops at BlockBehaviour). */
    static Class<?> declaring(Class<?> root, Class<?> from, String name, Class<?>[] params) throws NoSuchMethodException {
        for (Class<?> c = from; c != null && root.isAssignableFrom(c); c = c.getSuperclass()) {
            try { c.getDeclaredMethod(name, params); return c; } catch (NoSuchMethodException ignored) { /* keep walking */ }
        }
        throw new NoSuchMethodException(name + " not found from " + from.getName());
    }
    private static final Pattern TOKEN = Pattern.compile("\"(#?minecraft:[a-z0-9_/.-]+)\"");
    /** Resolves a vanilla block tag from the jar's data pack ({@code data/minecraft/tags/block/<tag>.json}), following {@code #tag} references. */
    static Set<String> resolveTag(ClassLoader cl, String tag, Set<String> visiting) throws IOException {
        if (!visiting.add(tag)) return Set.of();
        try (InputStream in = cl.getResourceAsStream("data/minecraft/tags/block/" + tag + ".json")) {
            if (in == null) throw new FileNotFoundException("tag " + tag);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> out = new LinkedHashSet<>();
            Matcher m = TOKEN.matcher(text);
            while (m.find()) { String tok = m.group(1); if (tok.startsWith("#")) out.addAll(resolveTag(cl, tok.substring("#minecraft:".length()), visiting)); else out.add(tok); }
            return out;
        }
    }
    static String sha256(byte[] bytes) throws Exception { var sb = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", b)); return sb.toString(); }
    @SuppressWarnings("unchecked")
    static String json(Object o, int indent) {
        String pad = "  ".repeat(indent + 1), padEnd = "  ".repeat(indent);
        if (o instanceof Map<?, ?> m) { var sb = new StringBuilder("{\n"); var it = m.entrySet().iterator(); while (it.hasNext()) { var e = it.next(); sb.append(pad).append('"').append(e.getKey()).append("\": ").append(json(e.getValue(), indent + 1)); if (it.hasNext()) sb.append(','); sb.append('\n'); } return sb.append(padEnd).append('}').toString(); }
        if (o instanceof List<?> l) { if (l.isEmpty()) return "[]"; var sb = new StringBuilder("[\n"); for (int i = 0; i < l.size(); i++) { sb.append(pad).append(json(l.get(i), indent + 1)); if (i + 1 < l.size()) sb.append(','); sb.append('\n'); } return sb.append(padEnd).append(']').toString(); }
        if (o instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return String.valueOf(o);
    }
}
