package me.nicolas.papea2ac.testing;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Phase 12 controlled, version-pinned extraction of vanilla block OUTLINE shapes (the shapes the client's attack pick clips against:
 * {@code ClipContext.Block.OUTLINE} -> {@code BlockStateBase.getShape(BlockGetter, BlockPos, CollisionContext)}; R7). Runs ONLY on the nativeTest
 * classpath against the pinned Paper 26.2 build 124 jar, never in production. For every state of {@code Block.BLOCK_STATE_REGISTRY} it records the
 * resting outline (at the origin, empty context) as a canonical box list and classifies the block:
 * <ul>
 * <li>STATIC — the outline is the same at five positions, under every probe context and against a getter that throws on access;</li>
 * <li>OFFSET maxH maxV — the outline moves with the per-position offset ({@code hasOffsetFunction}); the recorded shape is the un-offset one and
 *     consumers use the envelope widened by the maximum offsets;</li>
 * <li>CONTEXT — the outline varies with the entity context (held item: light, scaffolding …): consumers treat it as unknown;</li>
 * <li>LEVEL — the outline reads the level (or varies by position without an offset function): unknown.</li>
 * </ul>
 * Usage: {@code OutlineShapeExtractMain <table-out> <summary-json-out>}
 */
public final class OutlineShapeExtractMain {
    static final String PROFILE = "paper-26.2-124";
    static final class Marker extends RuntimeException { @java.io.Serial private static final long serialVersionUID = 1L; Marker() { super(null, null, false, false); } }

    public static void main(String[] args) throws Exception {
        try { run(args); } catch (Throwable t) { var w = new java.io.StringWriter(); t.printStackTrace(new java.io.PrintWriter(w)); Files.writeString(Path.of(args[1] + ".error.txt"), w.toString()); throw t; }
    }
    static void run(String[] args) throws Exception {
        Path tableOut = Path.of(args[0]), summaryOut = Path.of(args[1]);
        NativePacketTest.bootstrap();
        ClassLoader cl = OutlineShapeExtractMain.class.getClassLoader();
        Class<?> blockGetter = Class.forName("net.minecraft.world.level.BlockGetter"), blockPosC = Class.forName("net.minecraft.core.BlockPos"),
            ctxC = Class.forName("net.minecraft.world.phys.shapes.CollisionContext"), stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase"),
            behaviour = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour"), propertyC = Class.forName("net.minecraft.world.level.block.state.properties.Property"),
            voxelShape = Class.forName("net.minecraft.world.phys.shapes.VoxelShape"), aabbC = Class.forName("net.minecraft.world.phys.AABB");
        Object emptyGetter = Class.forName("net.minecraft.world.level.EmptyBlockGetter").getField("INSTANCE").get(null);
        Object emptyCtx = ctxC.getMethod("empty").invoke(null);
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
        Method getKey = Class.forName("net.minecraft.core.Registry").getMethod("getKey", Object.class);
        Method getShape = stateBase.getMethod("getShape", blockGetter, blockPosC, ctxC);
        Method toAabbs = voxelShape.getMethod("toAabbs"), move = voxelShape.getMethod("move", double.class, double.class, double.class);
        Method getStateDefinition = Class.forName("net.minecraft.world.level.block.Block").getMethod("getStateDefinition");
        Class<?> stateDef = Class.forName("net.minecraft.world.level.block.state.StateDefinition");
        Method getProperties = stateDef.getMethod("getProperties"), getPossibleStates = stateDef.getMethod("getPossibleStates");
        Method propName = propertyC.getMethod("getName"), propValues = propertyC.getMethod("getPossibleValues"), valueName = propertyC.getMethod("getName", Comparable.class);
        Method getValue = stateBase.getMethod("getValue", propertyC);
        Method hasOffset = stateBase.getMethod("hasOffsetFunction"), getOffset = stateBase.getMethod("getOffset", blockPosC);
        Method maxH = behaviour.getDeclaredMethod("getMaxHorizontalOffset"), maxV = behaviour.getDeclaredMethod("getMaxVerticalOffset"); maxH.setAccessible(true); maxV.setAccessible(true);
        Field[] aabbFields = { aabbC.getField("minX"), aabbC.getField("minY"), aabbC.getField("minZ"), aabbC.getField("maxX"), aabbC.getField("maxY"), aabbC.getField("maxZ") };
        Constructor<?> posCtor = blockPosC.getConstructor(int.class, int.class, int.class);
        Object zero = posCtor.newInstance(0, 0, 0);
        Object[] positions = { zero, posCtor.newInstance(1, 0, 0), posCtor.newInstance(5, 3, -7), posCtor.newInstance(-13, 64, 29), posCtor.newInstance(100003, -40, -77777) };
        // contexts: an entity above / inside / below, descending or not, holding nothing or an item whose holding changes outlines in vanilla
        Constructor<?> ecc = Class.forName("net.minecraft.world.phys.shapes.EntityCollisionContext").getDeclaredConstructor(boolean.class, boolean.class, double.class,
            Class.forName("net.minecraft.world.item.ItemStack"), boolean.class, Class.forName("net.minecraft.world.entity.Entity")); ecc.setAccessible(true);
        Class<?> itemStack = Class.forName("net.minecraft.world.item.ItemStack"), items = Class.forName("net.minecraft.world.item.Items"), itemLike = Class.forName("net.minecraft.world.level.ItemLike");
        List<Object> held = new ArrayList<>(List.of(itemStack.getField("EMPTY").get(null)));
        // ItemStacks of real items cannot be built without bound registry components here: two proxy contexts answer EVERY boolean query (isHoldingItem(any item), isDescending, isAbove ...) true / false instead, which is a superset of every held-item probe
        List<Object> contexts = new ArrayList<>();
        for (Object h : held) for (double bottom : new double[]{1.0, 0.5, -0.5}) for (boolean desc : new boolean[]{false, true}) contexts.add(ecc.newInstance(desc, false, bottom, h, false, null));
        for (boolean answer : new boolean[]{true, false}) contexts.add(Proxy.newProxyInstance(cl, new Class<?>[] { ctxC }, (p, m, a) -> switch (m.getName()) { case "toString" -> "proxy-" + answer; case "hashCode" -> answer ? 1 : 0; case "equals" -> p == a[0]; default -> m.getReturnType() == boolean.class ? (Object) answer : m.isDefault() ? java.lang.reflect.InvocationHandler.invokeDefault(p, m, a) : null; }));
        Object throwingGetter = Proxy.newProxyInstance(cl, new Class<?>[] { blockGetter }, (p, m, a) -> {
            switch (m.getName()) { case "toString": return "throwing"; case "hashCode": return 1; case "equals": return p == a[0]; default: throw new Marker(); }
        });

        Map<String, Integer> shapeIndex = new LinkedHashMap<>(); List<String> shapeLines = new ArrayList<>();
        shapeIndex.put("", 0); shapeLines.add("S 0");
        List<String> names = new ArrayList<>(); Map<String, Object> byName = new HashMap<>();
        for (Object block : (Iterable<?>) registry) { String name = getKey.invoke(registry, block).toString(); names.add(name); byName.put(name, block); }
        Collections.sort(names);
        StringBuilder blocks = new StringBuilder(); Map<String, Integer> kinds = new TreeMap<>(); List<String> contextBlocks = new ArrayList<>(), levelBlocks = new ArrayList<>();
        int stateCount = 0, empty = 0, full = 0;
        for (String name : names) {
            Object block = byName.get(name), def = getStateDefinition.invoke(block);
            List<?> props = new ArrayList<>((Collection<?>) getProperties.invoke(def)); List<?> states = (List<?>) getPossibleStates.invoke(def);
            StringBuilder axes = new StringBuilder(); int[] radix = new int[props.size()]; List<List<String>> valueNames = new ArrayList<>();
            for (int i = 0; i < props.size(); i++) {
                Object p = props.get(i); List<String> vn = new ArrayList<>(); for (Object v : (List<?>) propValues.invoke(p)) vn.add((String) valueName.invoke(p, v));
                valueNames.add(vn); radix[i] = vn.size(); axes.append(' ').append(propName.invoke(p)).append('=').append(String.join(",", vn));
            }
            boolean offsets = (boolean) hasOffset.invoke(states.get(0)), anyLevel = false, anyPos = false, anyCtx = false;
            int[] indices = new int[states.size()];
            for (int s = 0; s < states.size(); s++) {
                Object state = states.get(s);
                int idx = 0; for (int i = 0; i < props.size(); i++) { Object p = props.get(i); idx = idx * radix[i] + valueNames.get(i).indexOf((String) valueName.invoke(p, getValue.invoke(state, p))); }
                if (idx != s) throw new IllegalStateException("state order mismatch for " + name + " at " + s);
                String origin, canon;
                try {
                    Object shape = getShape.invoke(state, emptyGetter, zero, emptyCtx);
                    origin = canonical(toAabbs.invoke(shape), aabbFields);
                    if (offsets) { Object off = getOffset.invoke(state, zero); Class<?> vec = off.getClass(); shape = move.invoke(shape, -vec.getField("x").getDouble(off), -vec.getField("y").getDouble(off), -vec.getField("z").getDouble(off)); }
                    canon = canonical(toAabbs.invoke(shape), aabbFields);
                } catch (InvocationTargetException e) { anyLevel = true; origin = canon = ""; }
                try { getShape.invoke(state, throwingGetter, zero, emptyCtx); } catch (InvocationTargetException e) { if (e.getCause() instanceof Marker) anyLevel = true; else throw e; }
                if (!offsets) for (int pi = 1; pi < positions.length; pi++) {
                    String c2; try { c2 = canonical(toAabbs.invoke(getShape.invoke(state, emptyGetter, positions[pi], emptyCtx)), aabbFields); } catch (InvocationTargetException e) { c2 = "<level>"; }
                    if (!c2.equals(origin)) anyPos = true;
                }
                for (Object ctx : contexts) {
                    String c2; try { c2 = canonical(toAabbs.invoke(getShape.invoke(state, emptyGetter, zero, ctx)), aabbFields); } catch (InvocationTargetException e) { c2 = "<level>"; }
                    if (!c2.equals(origin)) anyCtx = true;
                }
                Integer si = shapeIndex.get(canon);
                if (si == null) { si = shapeIndex.size(); shapeIndex.put(canon, si); shapeLines.add("S " + si + (canon.isEmpty() ? "" : " " + canon)); }
                indices[s] = si; stateCount++;
                if (canon.isEmpty()) empty++; else if (canon.equals("0.0 0.0 0.0 1.0 1.0 1.0")) full++;
            }
            String kind = anyLevel || anyPos ? "LEVEL" : anyCtx ? "CONTEXT" : offsets ? "OFFSET " + maxH.invoke(block) + " " + maxV.invoke(block) : "STATIC";
            if (anyLevel || anyPos) levelBlocks.add(name); else if (anyCtx) contextBlocks.add(name);
            kinds.merge(kind.split(" ")[0], 1, Integer::sum);
            blocks.append("B ").append(name).append(' ').append(states.size()).append(' ').append(kind).append(axes).append('\n');
            blocks.append("I ").append(runLength(indices)).append('\n');
        }
        StringBuilder table = new StringBuilder("# papea2ac outline shape table\n# profile ").append(PROFILE).append('\n');
        table.append("# source BlockStateBase.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()) of the pinned Paper 26.2 build 124 server (Mojang mappings), every state of Block.BLOCK_STATE_REGISTRY; OFFSET shapes are recorded un-offset\n");
        table.append("# format S <shape> [minX minY minZ maxX maxY maxZ]* (Double.toString, exact) | B <block> <states> STATIC|OFFSET <maxH> <maxV>|CONTEXT|LEVEL [prop=v1,v2,..]* | I <shape index per state, runs as index*count>\n");
        for (String s : shapeLines) table.append(s).append('\n');
        table.append(blocks);
        byte[] bytes = table.toString().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(tableOut.getParent()); Files.write(tableOut, bytes);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", PROFILE); summary.put("blocks", names.size()); summary.put("states", stateCount); summary.put("uniqueShapes", shapeIndex.size());
        summary.put("statesEmpty", empty); summary.put("statesFullCube", full); summary.put("kinds", kinds); summary.put("contextBlocks", contextBlocks); summary.put("levelBlocks", levelBlocks);
        summary.put("tableSha256", EntityGeometryExtractMain.sha256(bytes)); summary.put("tableBytes", bytes.length);
        Files.createDirectories(summaryOut.getParent()); Files.writeString(summaryOut, EntityGeometryExtractMain.json(summary, 0) + "\n");
        System.out.println("outline blocks=" + names.size() + " states=" + stateCount + " shapes=" + shapeIndex.size() + " kinds=" + kinds + " context=" + contextBlocks + " level=" + levelBlocks);
    }

    static String canonical(Object aabbs, Field[] f) throws Exception {
        List<String> boxes = new ArrayList<>();
        for (Object a : (List<?>) aabbs) boxes.add(f[0].getDouble(a) + " " + f[1].getDouble(a) + " " + f[2].getDouble(a) + " " + f[3].getDouble(a) + " " + f[4].getDouble(a) + " " + f[5].getDouble(a));
        return String.join(" ", boxes);
    }
    static String runLength(int[] v) {
        StringBuilder s = new StringBuilder(); int i = 0;
        while (i < v.length) { int j = i; while (j < v.length && v[j] == v[i]) j++; if (s.length() > 0) s.append(' '); s.append(v[i]); if (j - i > 1) s.append('*').append(j - i); i = j; }
        return s.toString();
    }
}
