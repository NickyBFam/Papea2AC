package me.nicolas.papea2ac.testing;

import java.lang.reflect.*;
import java.util.*;
import me.nicolas.papea2ac.collision.CollisionShape;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.BlockStateKey;

/** Reflection handles onto the pinned server's shape machinery, shared by the Phase 4 native differential tests. Test-only; production never touches these classes. */
final class NativeCollisionSupport {
    static Class<?> blockGetter, blockPosC, ctxC, stateBase, voxelShape, aabbC, shapesC, axisC, boolOpC;
    static Object emptyGetter, emptyCtx, registry, blockRegistry, andOp; static Object[] axes;
    static Method getCollisionShape, toAabbs, getStateDefinition, getProperties, getPossibleStates, propName, getValue, valueName, getKey, byId, size, createShape, joinIsNotEmpty, move, collide, collideMany, intersects, getCoords, isFull, xSize, ySize, zSize;
    static Field discreteField; static Field[] aabbFields; static Constructor<?> posCtor, aabbCtor, eccCtor; static Object emptyStack;
    // vanilla (client) algorithm handles: Paper replaces VoxelShape.collide / Shapes.joinIsNotEmpty with Moonrise re-implementations, but the vanilla
    // collideX and the private index-merger join are still in the jar and are what the native client runs.
    static Method collideX, axisCycleBetween, createIndexMerger, joinPrivate, mergerSize, vsMin, vsMax, opApply; static Object axisX, blockSingleton;
    private static boolean ready;

    static synchronized void bootstrap() throws Exception {
        if (ready) return;
        NativePacketTest.bootstrap();
        blockGetter = Class.forName("net.minecraft.world.level.BlockGetter"); blockPosC = Class.forName("net.minecraft.core.BlockPos"); ctxC = Class.forName("net.minecraft.world.phys.shapes.CollisionContext");
        stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase"); voxelShape = Class.forName("net.minecraft.world.phys.shapes.VoxelShape"); aabbC = Class.forName("net.minecraft.world.phys.AABB");
        shapesC = Class.forName("net.minecraft.world.phys.shapes.Shapes"); axisC = Class.forName("net.minecraft.core.Direction$Axis"); axes = axisC.getEnumConstants(); boolOpC = Class.forName("net.minecraft.world.phys.shapes.BooleanOp");
        emptyGetter = Class.forName("net.minecraft.world.level.EmptyBlockGetter").getField("INSTANCE").get(null); emptyCtx = ctxC.getMethod("empty").invoke(null);
        registry = Class.forName("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        blockRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
        andOp = boolOpC.getField("AND").get(null);
        getCollisionShape = stateBase.getMethod("getCollisionShape", blockGetter, blockPosC, ctxC); toAabbs = voxelShape.getMethod("toAabbs");
        getStateDefinition = Class.forName("net.minecraft.world.level.block.Block").getMethod("getStateDefinition");
        Class<?> stateDef = Class.forName("net.minecraft.world.level.block.state.StateDefinition"); getProperties = stateDef.getMethod("getProperties"); getPossibleStates = stateDef.getMethod("getPossibleStates");
        Class<?> propertyC = Class.forName("net.minecraft.world.level.block.state.properties.Property"); propName = propertyC.getMethod("getName"); valueName = propertyC.getMethod("getName", Comparable.class);
        getValue = stateBase.getMethod("getValue", propertyC); getKey = Class.forName("net.minecraft.core.Registry").getMethod("getKey", Object.class);
        byId = registry.getClass().getMethod("byId", int.class); size = registry.getClass().getMethod("size");
        createShape = shapesC.getMethod("create", aabbC); joinIsNotEmpty = shapesC.getMethod("joinIsNotEmpty", voxelShape, voxelShape, boolOpC);
        move = voxelShape.getMethod("move", double.class, double.class, double.class); collide = voxelShape.getMethod("collide", axisC, aabbC, double.class);
        collideMany = shapesC.getMethod("collide", axisC, aabbC, Iterable.class, double.class); intersects = aabbC.getMethod("intersects", aabbC);
        getCoords = voxelShape.getDeclaredMethod("getCoords", axisC); getCoords.setAccessible(true); discreteField = voxelShape.getDeclaredField("shape"); discreteField.setAccessible(true);
        Class<?> dvs = Class.forName("net.minecraft.world.phys.shapes.DiscreteVoxelShape"); isFull = dvs.getMethod("isFull", int.class, int.class, int.class); xSize = dvs.getMethod("getXSize"); ySize = dvs.getMethod("getYSize"); zSize = dvs.getMethod("getZSize");
        aabbFields = new Field[] { aabbC.getField("minX"), aabbC.getField("minY"), aabbC.getField("minZ"), aabbC.getField("maxX"), aabbC.getField("maxY"), aabbC.getField("maxZ") };
        posCtor = blockPosC.getConstructor(int.class, int.class, int.class); aabbCtor = aabbC.getConstructor(double.class, double.class, double.class, double.class, double.class, double.class);
        eccCtor = Class.forName("net.minecraft.world.phys.shapes.EntityCollisionContext").getDeclaredConstructor(boolean.class, boolean.class, double.class, Class.forName("net.minecraft.world.item.ItemStack"), boolean.class, Class.forName("net.minecraft.world.entity.Entity")); eccCtor.setAccessible(true);
        emptyStack = Class.forName("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);
        Class<?> axisCycle = Class.forName("net.minecraft.core.AxisCycle"); axisCycleBetween = axisCycle.getMethod("between", axisC, axisC); axisX = axes[0];
        collideX = voxelShape.getDeclaredMethod("collideX", axisCycle, aabbC, double.class); collideX.setAccessible(true);
        Class<?> doubleList = Class.forName("it.unimi.dsi.fastutil.doubles.DoubleList"); Class<?> merger = Class.forName("net.minecraft.world.phys.shapes.IndexMerger");
        createIndexMerger = shapesC.getDeclaredMethod("createIndexMerger", int.class, doubleList, doubleList, boolean.class, boolean.class); createIndexMerger.setAccessible(true);
        joinPrivate = shapesC.getDeclaredMethod("joinIsNotEmpty", merger, merger, merger, dvs, dvs, boolOpC); joinPrivate.setAccessible(true);
        mergerSize = merger.getMethod("size"); vsMin = voxelShape.getMethod("min", axisC); vsMax = voxelShape.getMethod("max", axisC); opApply = boolOpC.getMethod("apply", boolean.class, boolean.class);
        blockSingleton = shapesC.getMethod("block").invoke(null);
        ready = true;
    }
    static int stateCount() throws Exception { return (int) size.invoke(registry); }
    static Object state(int id) throws Exception { return byId.invoke(registry, id); }
    /** Canonical BlockStateKey of a native state: registry key + every property by name, exactly what the normalizer produces. */
    static BlockStateKey keyOf(Object state) throws Exception {
        Object block = stateBase.getMethod("getBlock").invoke(state);
        String name = getKey.invoke(blockRegistry, block).toString();
        var props = new TreeMap<String, String>();
        for (Object p : (Collection<?>) getProperties.invoke(getStateDefinition.invoke(block))) props.put((String) propName.invoke(p), (String) valueName.invoke(p, getValue.invoke(state, p)));
        return new BlockStateKey(name, props);
    }
    static Object pos(int x, int y, int z) throws Exception { return posCtor.newInstance(x, y, z); }
    static Object aabb(Aabb b) throws Exception { return aabbCtor.newInstance(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()); }
    static Object entityContext(boolean descending, double bottom) throws Exception { return eccCtor.newInstance(descending, false, bottom, emptyStack, false, null); }
    static Object nativeShape(Object state, Object pos, Object ctx) throws Exception { return getCollisionShape.invoke(state, emptyGetter, pos, ctx); }
    /** Our CollisionShape built from a native VoxelShape's boxes AND grid (the same reading the extractor performs). */
    static CollisionShape fromNative(Object vs) throws Exception {
        List<?> boxes = (List<?>) toAabbs.invoke(vs);
        if (boxes.isEmpty()) return CollisionShape.EMPTY;
        List<String> canon = new ArrayList<>(); for (Object b : boxes) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 6; i++) { if (i > 0) sb.append(' '); sb.append(aabbFields[i].getDouble(b)); } canon.add(sb.toString()); }
        Collections.sort(canon); double[] flat = new double[boxes.size() * 6]; int n = 0; for (String c : canon) for (String v : c.split(" ")) flat[n++] = Double.parseDouble(v);
        Object dvs = discreteField.get(vs); int nx = (int) xSize.invoke(dvs), ny = (int) ySize.invoke(dvs), nz = (int) zSize.invoke(dvs);
        double[][] coords = new double[3][];
        for (int a = 0; a < 3; a++) { Object list = getCoords.invoke(vs, axes[a]); int sz = (int) list.getClass().getMethod("size").invoke(list); Method get = list.getClass().getMethod("getDouble", int.class); get.setAccessible(true); coords[a] = new double[sz]; for (int i = 0; i < sz; i++) coords[a][i] = (double) get.invoke(list, i); }
        long[] filled = new long[(nx * ny * nz + 63) / 64];
        for (int x = 0; x < nx; x++) for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++) if ((boolean) isFull.invoke(dvs, x, y, z)) { int i = (x * ny + y) * nz + z; filled[i >>> 6] |= 1L << (i & 63); }
        return CollisionShape.of(flat, new me.nicolas.papea2ac.version.BlockShapeTable.Grid(coords[0], coords[1], coords[2], filled));
    }
    /** Paper/Moonrise server-side collide (informational: what the SERVER computes). */
    static double moonriseCollide(Object vs, int axis, Aabb box, double desired) throws Exception { return (double) collide.invoke(vs, axes[axis], aabb(box), desired); }
    /** Vanilla client {@code VoxelShape.collide}: isEmpty -> desired, else collideX(AxisCycle.between(axis, X), box, desired). */
    static double vanillaCollide(Object vs, int axis, Aabb box, double desired) throws Exception {
        if ((boolean) voxelShape.getMethod("isEmpty").invoke(vs)) return desired;
        return (double) collideX.invoke(vs, axisCycleBetween.invoke(null, axes[axis], axisX), aabb(box), desired);
    }
    /** Vanilla client {@code Shapes.collide}: for each shape, |d| < EPS -> 0, d = shape.collide(...). */
    static double vanillaCollideMany(List<Object> shapes, int axis, Aabb box, double desired) throws Exception {
        for (Object vs : shapes) { if (Math.abs(desired) < 1.0E-7) return 0.0; desired = vanillaCollide(vs, axis, box, desired); }
        return desired;
    }
    /** Vanilla client {@code Shapes.joinIsNotEmpty(s1, s2, AND)} reproduced from its untouched private pieces (createIndexMerger + the merged-index join). */
    static boolean vanillaJoinIsNotEmpty(Object s1, Object s2) throws Exception {
        boolean e1 = (boolean) voxelShape.getMethod("isEmpty").invoke(s1), e2 = (boolean) voxelShape.getMethod("isEmpty").invoke(s2);
        if (e1 || e2) return (boolean) opApply.invoke(andOp, !e1, !e2);
        if (s1 == s2) return true;
        boolean excludeUpper = (boolean) opApply.invoke(andOp, true, false), excludeLower = (boolean) opApply.invoke(andOp, false, true);
        for (Object axis : axes) {
            if ((double) vsMax.invoke(s1, axis) < (double) vsMin.invoke(s2, axis) - 1.0E-7) return excludeUpper || excludeLower;
            if ((double) vsMax.invoke(s2, axis) < (double) vsMin.invoke(s1, axis) - 1.0E-7) return excludeUpper || excludeLower;
        }
        Object mx = createIndexMerger.invoke(null, 1, getCoords.invoke(s1, axes[0]), getCoords.invoke(s2, axes[0]), excludeUpper, excludeLower);
        Object my = createIndexMerger.invoke(null, (int) mergerSize.invoke(mx) - 1, getCoords.invoke(s1, axes[1]), getCoords.invoke(s2, axes[1]), excludeUpper, excludeLower);
        Object mz = createIndexMerger.invoke(null, ((int) mergerSize.invoke(mx) - 1) * ((int) mergerSize.invoke(my) - 1), getCoords.invoke(s1, axes[2]), getCoords.invoke(s2, axes[2]), excludeUpper, excludeLower);
        return (boolean) joinPrivate.invoke(null, mx, my, mz, discreteField.get(s1), discreteField.get(s2), andOp);
    }
    /** Vanilla client {@code BlockCollisions} candidate acceptance for a shape already moved to its cell: the singleton block uses AABB.intersects, everything else the voxel join. */
    static boolean vanillaAccepts(Object unmovedShape, Object movedShape, Aabb cellBox, Aabb query) throws Exception {
        if (unmovedShape == blockSingleton) return (boolean) intersects.invoke(aabb(query), aabb(cellBox));
        return vanillaJoinIsNotEmpty(movedShape, createShape.invoke(null, aabb(query)));
    }
    static Object moved(Object vs, double x, double y, double z) throws Exception { return move.invoke(vs, x, y, z); }
    static boolean nativeIntersects(Object movedShape, Aabb box) throws Exception { return (boolean) joinIsNotEmpty.invoke(null, movedShape, createShape.invoke(null, aabb(box)), andOp); }
}
