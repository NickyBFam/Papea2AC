package me.nicolas.papea2ac.testing;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Controlled, version-pinned extraction of vanilla block collision geometry (task §14): runs ONLY on the nativeTest
 * classpath against the pinned Paper 26.2 build 124 jar, never in production. For every block state in
 * {@code Block.BLOCK_STATE_REGISTRY} it records {@code BlockStateBase.getCollisionShape(EmptyBlockGetter.INSTANCE,
 * BlockPos.ZERO, CollisionContext.empty())} as a canonical AABB list, classifies each block as STATIC (the shape is a
 * pure function of the canonical state) or DYNAMIC (position offset / entity context / level-and-block-entity
 * dependent), and verifies the STATIC claim empirically: the same shape at five positions, under six entity contexts
 * and against a BlockGetter that throws on any access. The result is a deterministic text table consumed by
 * anticheat-version (production loads a resource; no reflection at query time).
 *
 * Usage: {@code CollisionShapeExtractMain <table-out> <summary-json-out>}
 */
public final class CollisionShapeExtractMain {
    static final String PROFILE = "paper-26.2-124";
    static final class Marker extends RuntimeException { @java.io.Serial private static final long serialVersionUID = 1L; Marker() { super(null, null, false, false); } }

    public static void main(String[] args) throws Exception {
        Path tableOut = Path.of(args[0]), summaryOut = Path.of(args[1]);
        NativePacketTest.bootstrap();
        ClassLoader cl = CollisionShapeExtractMain.class.getClassLoader();
        Class<?> blockGetter = Class.forName("net.minecraft.world.level.BlockGetter"), blockPosC = Class.forName("net.minecraft.core.BlockPos"),
            ctxC = Class.forName("net.minecraft.world.phys.shapes.CollisionContext"), stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase"),
            behaviour = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour"), propertyC = Class.forName("net.minecraft.world.level.block.state.properties.Property"),
            voxelShape = Class.forName("net.minecraft.world.phys.shapes.VoxelShape"), aabbC = Class.forName("net.minecraft.world.phys.AABB");
        Object emptyGetter = Class.forName("net.minecraft.world.level.EmptyBlockGetter").getField("INSTANCE").get(null);
        Object emptyCtx = ctxC.getMethod("empty").invoke(null);
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
        Method getKey = Class.forName("net.minecraft.core.Registry").getMethod("getKey", Object.class);
        Method getCollisionShape = stateBase.getMethod("getCollisionShape", blockGetter, blockPosC, ctxC);
        Method toAabbs = voxelShape.getMethod("toAabbs");
        Class<?> axisC = Class.forName("net.minecraft.core.Direction$Axis"); Object[] axisValues = axisC.getEnumConstants();
        Method getCoords = voxelShape.getDeclaredMethod("getCoords", axisC); getCoords.setAccessible(true);
        Field discrete = voxelShape.getDeclaredField("shape"); discrete.setAccessible(true);
        Class<?> dvsC = Class.forName("net.minecraft.world.phys.shapes.DiscreteVoxelShape");
        Method isFull = dvsC.getMethod("isFull", int.class, int.class, int.class), xSize = dvsC.getMethod("getXSize"), ySize = dvsC.getMethod("getYSize"), zSize = dvsC.getMethod("getZSize");
        GridReader grid = new GridReader(getCoords, axisValues, discrete, isFull, xSize, ySize, zSize);
        Method getStateDefinition = Class.forName("net.minecraft.world.level.block.Block").getMethod("getStateDefinition");
        Class<?> stateDef = Class.forName("net.minecraft.world.level.block.state.StateDefinition");
        Method getProperties = stateDef.getMethod("getProperties"), getPossibleStates = stateDef.getMethod("getPossibleStates");
        Method propName = propertyC.getMethod("getName"), propValues = propertyC.getMethod("getPossibleValues"), valueName = propertyC.getMethod("getName", Comparable.class);
        Method getValue = stateBase.getMethod("getValue", propertyC);
        Method hasOffset = stateBase.getMethod("hasOffsetFunction"), getOffset = stateBase.getMethod("getOffset", blockPosC);
        Method maxH = behaviour.getDeclaredMethod("getMaxHorizontalOffset"), maxV = behaviour.getDeclaredMethod("getMaxVerticalOffset"); maxH.setAccessible(true); maxV.setAccessible(true);
        Field dynamicField = behaviour.getDeclaredField("dynamicShape"); dynamicField.setAccessible(true);
        Field hasCollisionField = behaviour.getField("hasCollision");
        Field[] aabbFields = { aabbC.getField("minX"), aabbC.getField("minY"), aabbC.getField("minZ"), aabbC.getField("maxX"), aabbC.getField("maxY"), aabbC.getField("maxZ") };
        Constructor<?> posCtor = blockPosC.getConstructor(int.class, int.class, int.class);
        Object zero = posCtor.newInstance(0, 0, 0);
        Object[] positions = { zero, posCtor.newInstance(1, 0, 0), posCtor.newInstance(5, 3, -7), posCtor.newInstance(-13, 64, 29), posCtor.newInstance(100003, -40, -77777) };
        // Entity contexts: entity bottom relative to the block's own Y (1.0 above the top face, 0.5 inside, -0.5 below) x descending. Entity itself is null (no Level exists here).
        Constructor<?> ecc = Class.forName("net.minecraft.world.phys.shapes.EntityCollisionContext").getDeclaredConstructor(boolean.class, boolean.class, double.class,
            Class.forName("net.minecraft.world.item.ItemStack"), boolean.class, Class.forName("net.minecraft.world.entity.Entity")); ecc.setAccessible(true);
        Object emptyStack = Class.forName("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);
        double[] bottoms = { 1.0, 0.5, -0.5 }; boolean[] descending = { false, true };
        Object[] contexts = new Object[6]; String[] contextNames = new String[6];
        for (int b = 0; b < 3; b++) for (int d = 0; d < 2; d++) { contexts[b * 2 + d] = ecc.newInstance(descending[d], false, bottoms[b], emptyStack, false, null); contextNames[b * 2 + d] = "bottom=" + bottoms[b] + ",descending=" + descending[d]; }
        Object throwingGetter = Proxy.newProxyInstance(cl, new Class<?>[] { blockGetter }, (p, m, a) -> {
            switch (m.getName()) { case "toString": return "throwing"; case "hashCode": return 1; case "equals": return p == a[0]; default: throw new Marker(); }
        });

        List<String> shapeLines = new ArrayList<>(); Map<String, Integer> shapeIndex = new LinkedHashMap<>();
        shapeIndex.put("", 0); shapeLines.add("S 0"); shapeLines.add("G 0 0 0 0"); // index 0 = empty shape (no grid)
        ShapeRegistry reg = new ShapeRegistry(shapeIndex, shapeLines, toAabbs, aabbFields, grid);
        StringBuilder blocks = new StringBuilder();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> dynamicBlocks = new ArrayList<>(), levelBlocks = new ArrayList<>(), positionBlocks = new ArrayList<>(), contextBlocks = new ArrayList<>(), disagreements = new ArrayList<>(), noCollisionBlocks = new ArrayList<>();
        int blockCount = 0, stateCount = 0, fullStates = 0, emptyStates = 0, partialStates = 0;
        Map<String, Integer> classificationCounts = new TreeMap<>();
        List<String> names = new ArrayList<>();
        Map<String, Object> byName = new HashMap<>();
        for (Object block : (Iterable<?>) registry) { String name = getKey.invoke(registry, block).toString(); names.add(name); byName.put(name, block); }
        Collections.sort(names); // LC_ALL=C order: deterministic table regardless of registry iteration
        for (String name : names) {
            Object block = byName.get(name);
            Object def = getStateDefinition.invoke(block);
            List<?> props = new ArrayList<>((Collection<?>) getProperties.invoke(def));
            List<?> states = (List<?>) getPossibleStates.invoke(def);
            boolean dynamic = dynamicField.getBoolean(block), hasCollision = hasCollisionField.getBoolean(block);
            // property axes, in StateDefinition order (name-sorted TreeMap in vanilla); verify the mixed-radix state index below
            StringBuilder axes = new StringBuilder(); int[] radix = new int[props.size()]; List<List<String>> valueNames = new ArrayList<>();
            for (int i = 0; i < props.size(); i++) {
                Object p = props.get(i); List<?> values = (List<?>) propValues.invoke(p); List<String> vn = new ArrayList<>();
                for (Object v : values) vn.add((String) valueName.invoke(p, v));
                valueNames.add(vn); radix[i] = values.size();
                axes.append(' ').append(propName.invoke(p)).append('=').append(String.join(",", vn));
            }
            int[] indices = new int[states.size()];
            String classification = dynamic ? "DYNAMIC" : "STATIC"; String detail = "";
            boolean anyLevel = false, anyPos = false, anyCtx = false; String firstReason = null;
            for (int s = 0; s < states.size(); s++) {
                Object state = states.get(s);
                // mixed-radix check: canonical index from property values must equal the registry order position
                int idx = 0;
                for (int i = 0; i < props.size(); i++) { Object p = props.get(i); String vn = (String) valueName.invoke(p, getValue.invoke(state, p)); idx = idx * radix[i] + valueNames.get(i).indexOf(vn); }
                if (idx != s) throw new IllegalStateException("state order mismatch for " + name + " at " + s + " (computed " + idx + ")");
                String canon, originCanon; Object restingShape = null;
                try {
                    restingShape = getCollisionShape.invoke(state, emptyGetter, zero, emptyCtx);
                    originCanon = canonical(toAabbs.invoke(restingShape), aabbFields); // the shape AT the origin: the reference for the variation probes below
                    if ((boolean) hasOffset.invoke(state)) {
                        // record the base (un-offset) shape: undo the origin offset, which is exactly +-maxHorizontalOffset (seed 0) and therefore recoverable without rounding
                        Object off = getOffset.invoke(state, zero); Class<?> vec = off.getClass();
                        double ox = vec.getField("x").getDouble(off), oy = vec.getField("y").getDouble(off), oz = vec.getField("z").getDouble(off);
                        float mh = (float) maxH.invoke(block);
                        if (Math.abs(ox) != (double) mh || Math.abs(oz) != (double) mh || (oy != 0.0 && oy != -(double) (float) maxV.invoke(block))) throw new IllegalStateException("origin offset of " + name + " is not exactly recoverable: " + ox + "," + oy + "," + oz);
                        restingShape = voxelShape.getMethod("move", double.class, double.class, double.class).invoke(restingShape, -ox, -oy, -oz);
                    }
                    canon = canonical(toAabbs.invoke(restingShape), aabbFields);
                }
                catch (InvocationTargetException e) { canon = ""; originCanon = ""; anyLevel = true; firstReason = firstReason == null ? "resting shape needs level: " + e.getCause() : firstReason; }
                int si = reg.register(restingShape);
                indices[s] = si; stateCount++;
                if (canon.isEmpty()) emptyStates++; else if (canon.equals("0.0 0.0 0.0 1.0 1.0 1.0")) fullStates++; else partialStates++;
                // empirical checks (every state, even dynamic ones - the summary explains each dynamic family by what actually varied)
                try { getCollisionShape.invoke(state, throwingGetter, zero, emptyCtx); } catch (InvocationTargetException e) { if (e.getCause() instanceof Marker) anyLevel = true; else throw e; }
                for (int pi = 1; pi < positions.length; pi++) {
                    String c2; try { c2 = canonical(toAabbs.invoke(getCollisionShape.invoke(state, emptyGetter, positions[pi], emptyCtx)), aabbFields); } catch (InvocationTargetException e) { c2 = "<level>"; }
                    if (!c2.equals(originCanon)) anyPos = true;
                }
                for (int ci = 0; ci < contexts.length; ci++) {
                    String c2; try { c2 = canonical(toAabbs.invoke(getCollisionShape.invoke(state, emptyGetter, zero, contexts[ci])), aabbFields); } catch (InvocationTargetException e) { c2 = "<level>"; }
                    if (!c2.equals(originCanon)) anyCtx = true;
                }
            }
            if (!hasCollision) noCollisionBlocks.add(name);
            if (anyLevel) levelBlocks.add(name); if (anyPos) positionBlocks.add(name); if (anyCtx) contextBlocks.add(name);
            if (dynamic) {
                dynamicBlocks.add(name);
                List<String> kinds = new ArrayList<>();
                if (anyLevel) kinds.add("LEVEL"); if (anyPos) kinds.add("POSITION"); if (anyCtx) kinds.add("CONTEXT");
                boolean offsets = (boolean) hasOffset.invoke(states.get(0));
                if (offsets) {
                    // XZ vs XYZ: the vertical component is identically zero for XZ; probe positions until it is not
                    boolean xyz = false; Field vecY = Class.forName("net.minecraft.world.phys.Vec3").getField("y");
                    for (int px = -40; px <= 40 && !xyz; px += 7) for (int pz = -40; pz <= 40 && !xyz; pz += 5) if (vecY.getDouble(getOffset.invoke(states.get(0), posCtor.newInstance(px, 0, pz))) != 0.0) xyz = true;
                    kinds.add("OFFSET_" + (xyz ? "XYZ" : "XZ") + "(maxH=" + maxH.invoke(block) + ",maxV=" + maxV.invoke(block) + ")");
                }
                if (kinds.isEmpty()) kinds.add("FLAG_ONLY");
                detail = String.join("+", kinds);
                // context variants for CONTEXT blocks: the shape under each of the six entity contexts, per state, so the resolver needs no per-block geometry code
                if (anyCtx && !anyLevel) {
                    StringBuilder cv = new StringBuilder();
                    for (int s = 0; s < states.size(); s++) {
                        for (int ci = 0; ci < contexts.length; ci++) {
                            int si = reg.register(getCollisionShape.invoke(states.get(s), emptyGetter, zero, contexts[ci]));
                            cv.append(ci == 0 && s == 0 ? "" : " ").append(si);
                        }
                    }
                    detail += "\nC " + cv;
                }
            } else if (anyLevel || anyPos || anyCtx) {
                disagreements.add(name + " varies (" + (anyLevel ? "level " : "") + (anyPos ? "position " : "") + (anyCtx ? "context" : "") + ") without the dynamicShape flag" + (firstReason != null ? " - " + firstReason : ""));
            }
            classificationCounts.merge(classification + (detail.isEmpty() ? "" : ":" + detail.split("\n")[0]), 1, Integer::sum);
            blocks.append("B ").append(name).append(' ').append(states.size()).append(' ').append(classification).append(detail.isEmpty() ? "" : " " + detail.split("\n")[0]).append(axes).append('\n');
            if (detail.contains("\nC ")) blocks.append(detail.substring(detail.indexOf("\nC ") + 1)).append('\n');
            blocks.append("I ").append(runLength(indices)).append('\n');
            blockCount++;
        }
        // Named extra shapes the resolver needs beyond the resting shape (entity-dependent powder snow), and the avatar pose boxes.
        StringBuilder extras = new StringBuilder();
        Field falling = Class.forName("net.minecraft.world.level.block.PowderSnowBlock").getDeclaredField("FALLING_COLLISION_SHAPE"); falling.setAccessible(true);
        int fallingIdx = reg.register(falling.get(null));
        extras.append("X powder_snow_falling ").append(fallingIdx).append('\n');
        Object fullBlock = Class.forName("net.minecraft.world.phys.shapes.Shapes").getMethod("block").invoke(null);
        extras.append("X full_cube ").append(reg.register(fullBlock)).append('\n');
        Class<?> avatar = Class.forName("net.minecraft.world.entity.Avatar");
        Field posesF = avatar.getDeclaredField("POSES"), standingF = avatar.getDeclaredField("STANDING_DIMENSIONS"); posesF.setAccessible(true); standingF.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Object, Object> poses = (Map<Object, Object>) posesF.get(null); Object standing = standingF.get(null);
        Class<?> dims = Class.forName("net.minecraft.world.entity.EntityDimensions"); Method width = dims.getMethod("width"), height = dims.getMethod("height");
        Map<String, Object> poseDims = new TreeMap<>();
        for (Object pose : Class.forName("net.minecraft.world.entity.Pose").getEnumConstants()) { Object dm = poses.getOrDefault(pose, standing); poseDims.put(pose.toString(), dm); }
        for (var e : poseDims.entrySet()) extras.append("P ").append(e.getKey()).append(' ').append(width.invoke(e.getValue())).append(' ').append(height.invoke(e.getValue())).append('\n');
        List<String> poseSummary = new ArrayList<>();
        for (var e : poseDims.entrySet()) poseSummary.add(e.getKey() + "=" + width.invoke(e.getValue()) + "x" + height.invoke(e.getValue()));
        summary.put("avatarPoseDimensions", poseSummary);
        StringBuilder table = new StringBuilder();
        table.append("# papea2ac collision shape table\n");
        table.append("# profile ").append(PROFILE).append('\n');
        table.append("# source BlockStateBase.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()) of the pinned Paper 26.2 build 124 server (Mojang mappings), every state of Block.BLOCK_STATE_REGISTRY\n");
        table.append("# format S <shape> [minX minY minZ maxX maxY maxZ]* | G <shape> <nx> <ny> <nz> [x coords (nx+1)] [y coords] [z coords] [filled cells as hex, index (x*ny+y)*nz+z, MSB first] | B <block> <states> STATIC|DYNAMIC [kinds] [prop=v1,v2,..]* | C <6 context-variant shape indices per state> | I <shape index per state in state order, runs as index*count> | X <named shape> <index> | P <avatar pose> <width> <height>\n");
        table.append("# blocks ").append(blockCount).append(" states ").append(stateCount).append(" shapes ").append(shapeIndex.size()).append('\n');
        for (String s : shapeLines) table.append(s).append('\n');
        table.append(blocks); table.append(extras);
        byte[] bytes = table.toString().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(tableOut.getParent()); Files.write(tableOut, bytes);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        summary.put("profile", PROFILE); summary.put("blocks", blockCount); summary.put("states", stateCount); summary.put("uniqueShapes", shapeIndex.size());
        summary.put("statesEmpty", emptyStates); summary.put("statesFullCube", fullStates); summary.put("statesPartial", partialStates);
        summary.put("dynamicBlocks", dynamicBlocks); summary.put("levelDependent", levelBlocks); summary.put("positionDependent", positionBlocks); summary.put("contextDependent", contextBlocks);
        summary.put("variesWithoutDynamicFlag", disagreements); summary.put("noCollisionBlocks", noCollisionBlocks.size()); summary.put("classificationCounts", classificationCounts);
        summary.put("tableSha256", digest); summary.put("tableBytes", bytes.length);
        Files.createDirectories(summaryOut.getParent()); Files.writeString(summaryOut, json(summary, 0) + "\n");
        System.out.println("blocks=" + blockCount + " states=" + stateCount + " shapes=" + shapeIndex.size() + " dynamic=" + dynamicBlocks + " level=" + levelBlocks + " position=" + positionBlocks + " context=" + contextBlocks + " disagreements=" + disagreements + " sha256=" + digest);
    }

    /** Exact vanilla voxel grid of a VoxelShape: per-axis coordinate lists and the DiscreteVoxelShape's filled cells. Two shapes with equal box lists but different internal grids are different shapes (they clip differently when penetrated). */
    static final class GridReader {
        final Method getCoords, isFull, xSize, ySize, zSize; final Object[] axes; final Field discrete;
        GridReader(Method getCoords, Object[] axes, Field discrete, Method isFull, Method xSize, Method ySize, Method zSize) { this.getCoords = getCoords; this.axes = axes; this.discrete = discrete; this.isFull = isFull; this.xSize = xSize; this.ySize = ySize; this.zSize = zSize; }
        String describe(Object voxelShape) throws Exception {
            Object dvs = discrete.get(voxelShape);
            int nx = (int) xSize.invoke(dvs), ny = (int) ySize.invoke(dvs), nz = (int) zSize.invoke(dvs);
            StringBuilder sb = new StringBuilder().append(nx).append(' ').append(ny).append(' ').append(nz);
            for (int a = 0; a < 3; a++) { Object list = getCoords.invoke(voxelShape, axes[a]); int n = (int) list.getClass().getMethod("size").invoke(list); Method get = list.getClass().getMethod("getDouble", int.class); get.setAccessible(true);
                int expect = (a == 0 ? nx : a == 1 ? ny : nz) + 1; if (n != expect) throw new IllegalStateException("grid coordinate count " + n + " != " + expect);
                for (int i = 0; i < n; i++) sb.append(' ').append(Double.toString((double) get.invoke(list, i))); }
            int bits = nx * ny * nz; StringBuilder hex = new StringBuilder(); int nibble = 0, count = 0;
            for (int x = 0; x < nx; x++) for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++) { nibble = (nibble << 1) | ((boolean) isFull.invoke(dvs, x, y, z) ? 1 : 0); if (++count == 4) { hex.append(Character.forDigit(nibble, 16)); nibble = 0; count = 0; } }
            if (count > 0) hex.append(Character.forDigit(nibble << (4 - count), 16));
            if (bits > 0) sb.append(' ').append(hex);
            return sb.toString();
        }
    }
    /** Shape interning keyed by (canonical boxes + exact grid); writes the S and G lines. */
    static final class ShapeRegistry {
        final Map<String, Integer> index; final List<String> lines; final Method toAabbs; final Field[] fields; final GridReader grid;
        ShapeRegistry(Map<String, Integer> index, List<String> lines, Method toAabbs, Field[] fields, GridReader grid) { this.index = index; this.lines = lines; this.toAabbs = toAabbs; this.fields = fields; this.grid = grid; }
        int register(Object voxelShape) throws Exception {
            if (voxelShape == null) return 0;
            String boxes = canonical(toAabbs.invoke(voxelShape), fields);
            if (boxes.isEmpty()) return 0;
            String g = grid.describe(voxelShape); String key = boxes + " | " + g;
            Integer si = index.get(key);
            if (si == null) { si = index.size(); index.put(key, si); lines.add("S " + si + " " + boxes); lines.add("G " + si + " " + g); }
            return si;
        }
    }
    /** Canonical AABB list: each box as six Double.toString values, boxes sorted lexicographically, space separated. */
    static String canonical(Object aabbs, Field[] f) throws Exception {
        List<String> boxes = new ArrayList<>();
        for (Object box : (List<?>) aabbs) { StringBuilder b = new StringBuilder(); for (int i = 0; i < 6; i++) { if (i > 0) b.append(' '); b.append(Double.toString(f[i].getDouble(box))); } boxes.add(b.toString()); }
        Collections.sort(boxes);
        return String.join(" ", boxes);
    }
    static String runLength(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; ) { int j = i; while (j < v.length && v[j] == v[i]) j++; if (sb.length() > 0) sb.append(' '); sb.append(v[i]); if (j - i > 1) sb.append('*').append(j - i); i = j; }
        return sb.toString();
    }
    @SuppressWarnings("unchecked")
    static String json(Object o, int depth) {
        String pad = "  ".repeat(depth + 1), end = "  ".repeat(depth);
        if (o instanceof Map<?, ?> m) { StringBuilder sb = new StringBuilder("{\n"); boolean first = true; for (var e : m.entrySet()) { if (!first) sb.append(",\n"); first = false; sb.append(pad).append('"').append(e.getKey()).append("\": ").append(json(e.getValue(), depth + 1)); } return sb.append('\n').append(end).append('}').toString(); }
        if (o instanceof List<?> l) { if (l.isEmpty()) return "[]"; StringBuilder sb = new StringBuilder("[\n"); boolean first = true; for (Object e : l) { if (!first) sb.append(",\n"); first = false; sb.append(pad).append(json(e, depth + 1)); } return sb.append('\n').append(end).append(']').toString(); }
        if (o instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
        return String.valueOf(o);
    }
}
