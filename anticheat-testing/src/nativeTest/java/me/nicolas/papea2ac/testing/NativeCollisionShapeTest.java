package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.NativeCollisionSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.version.*;
import me.nicolas.papea2ac.world.BlockStateDictionary;

/**
 * Native differential matrix (task §15/§50/§51): the production resolver (table resource + rules, no NMS) against the
 * pinned server's own {@code BlockStateBase.getCollisionShape} for EVERY block state of the registry, for every
 * scaffolding state under contexts around the exact predicate boundaries, for the offset blocks at hundreds of
 * positions, and for the avatar pose boxes. The oracle is vanilla, not our own arithmetic.
 */
class NativeCollisionShapeTest {
    static BlockShapeResolver resolver; static CollisionProfile profile;
    @BeforeAll static void setUp() throws Exception { NativeCollisionSupport.bootstrap(); profile = CollisionProfile.PAPER_26_2; resolver = new BlockShapeResolver(profile, new BlockStateDictionary(), new CollisionMetrics()); }

    @Test void everyRegistryStateRestingShapeAndGridMatchesNative() throws Exception {
        int n = stateCount(); assertEquals(profile.table().stateCount(), n, "table covers the whole registry");
        int checked = 0, nonStatic = 0;
        for (int id = 0; id < n; id++) {
            Object state = state(id); BlockStateKey key = keyOf(state);
            var plan = resolver.plan(key);
            assertTrue(plan.supported(), () -> "unsupported registry state " + key.canonical());
            CollisionShape expected;
            try { expected = fromNative(nativeShape(state, pos(0, 0, 0), emptyCtx)); }
            catch (java.lang.reflect.InvocationTargetException e) { assertEquals(BlockShapeTable.Kind.BLOCK_ENTITY, plan.kind(), key.canonical()); continue; } // needs a block entity: classified as such
            if (plan.kind() == BlockShapeTable.Kind.OFFSET_XZ) assertEquals(expected, resolver.resolve(plan, 0, 0, 0, ShapeContext.UNKNOWN).lower(), () -> "origin shape of " + key.canonical());
            else assertEquals(expected, plan.resting(), () -> "resting shape of " + key.canonical());
            if (plan.kind() != BlockShapeTable.Kind.STATIC) nonStatic++;
            else { var r = resolver.resolve(plan, 0, 0, 0, ShapeContext.UNKNOWN); assertTrue(r.exact()); assertSame(plan.resting(), r.lower()); }
            checked++;
        }
        assertEquals(n, checked, "every registry state has a native resting shape under EmptyBlockGetter (block-entity blocks return their no-entity shape there)");
        assertTrue(nonStatic > 0);
    }

    /** The vanilla client fast path (AABB.intersects instead of the voxel join) applies only to the Shapes.block() singleton; our isFullCube() is geometric, so every full-cube state must BE the singleton for the two to agree. */
    @Test void everyFullCubeStateUsesTheSingletonBlockShape() throws Exception {
        int fullCubes = 0; var offenders = new ArrayList<String>();
        for (int id = 0; id < stateCount(); id++) {
            Object state = state(id); var plan = resolver.plan(keyOf(state));
            if (plan.kind() != BlockShapeTable.Kind.STATIC || !plan.resting().isFullCube()) continue;
            fullCubes++; if (nativeShape(state, pos(0, 0, 0), emptyCtx) != blockSingleton) offenders.add(keyOf(state).canonical());
        }
        assertTrue(fullCubes > 3000, "full cube states: " + fullCubes);
        assertEquals(List.of(), offenders, "full-cube geometry that is not Shapes.block()");
    }

    @Test void scaffoldingContextRuleMatchesNativeAroundEveryPredicateBoundary() throws Exception {
        var table = profile.table(); var block = table.block("minecraft:scaffolding"); assertEquals(BlockShapeTable.Kind.CONTEXT, block.kind());
        int checked = 0;
        for (int id = 0; id < stateCount(); id++) {
            Object state = state(id); BlockStateKey key = keyOf(state); if (!key.block().equals("minecraft:scaffolding")) continue;
            var plan = resolver.plan(key);
            for (int y : new int[] { 0, 7, -33, 250 }) for (boolean desc : new boolean[] { false, true })
                for (double rel : new double[] { 1.5, 1.0, 1.0 - 9.0E-6, 1.0 - 1.1E-5, 0.999, 0.5, 1.0E-6, 0.0, -9.0E-6, -1.1E-5, -0.5, -2.0 }) {
                    double bottom = y + rel;
                    CollisionShape expected = fromNative(nativeShape(state, pos(3, y, -4), entityContext(desc, bottom)));
                    var r = resolver.resolve(plan, 3, y, -4, ShapeContext.of(bottom, desc, 0, false));
                    assertEquals(CellStatus.EXACT_CONTEXT, r.status()); assertEquals(expected, r.lower(), () -> key.canonical() + " bottom=" + bottom + " desc=" + desc); checked++;
                }
            var env = resolver.resolve(plan, 0, 0, 0, ShapeContext.UNKNOWN);
            assertEquals(CellStatus.CONTEXT_REQUIRED, env.status()); assertTrue(env.lower().isEmpty(), "no context: nothing is claimed present");
        }
        assertEquals(32 * 4 * 2 * 12, checked);
    }

    @Test void positionOffsetBlocksMatchNativeAtManyPositions() throws Exception {
        var rnd = new Random(0x0FF5E7); int checked = 0;
        for (int id = 0; id < stateCount(); id++) {
            Object state = state(id); BlockStateKey key = keyOf(state);
            if (!(key.block().equals("minecraft:bamboo") || key.block().equals("minecraft:pointed_dripstone") || key.block().equals("minecraft:sulfur_spike"))) continue;
            var plan = resolver.plan(key); assertEquals(BlockShapeTable.Kind.OFFSET_XZ, plan.kind());
            for (int i = 0; i < 60; i++) {
                final int x = i == 0 ? 0 : i == 1 ? -1 : i == 2 ? Integer.MAX_VALUE / 2 : rnd.nextInt(-3_000_000, 3_000_000), y = rnd.nextInt(-64, 320), z = i == 0 ? 0 : i == 1 ? -1 : i == 2 ? Integer.MIN_VALUE / 2 : rnd.nextInt(-3_000_000, 3_000_000);
                CollisionShape expected = fromNative(nativeShape(state, pos(x, y, z), emptyCtx));
                var r = resolver.resolve(plan, x, y, z, ShapeContext.UNKNOWN);
                assertEquals(CellStatus.EXACT_OFFSET, r.status()); assertEquals(expected, r.lower(), () -> key.canonical() + " @" + x + "," + y + "," + z); checked++;
            }
        }
        assertEquals((12 + 20 + 20) * 60, checked);
    }

    @Test void powderSnowNullEntityPathAndEmptyContextAreEmptyLikeNative() throws Exception {
        for (int id = 0; id < stateCount(); id++) {
            Object state = state(id); BlockStateKey key = keyOf(state); if (!key.block().equals("minecraft:powder_snow")) continue;
            var plan = resolver.plan(key); assertEquals(BlockShapeTable.Kind.ENTITY_CONTEXT, plan.kind());
            assertTrue(fromNative(nativeShape(state, pos(0, 0, 0), emptyCtx)).isEmpty());
            assertTrue(fromNative(nativeShape(state, pos(0, 0, 0), entityContext(false, 1.0))).isEmpty(), "no entity => empty");
            var env = resolver.resolve(plan, 0, 0, 0, ShapeContext.UNKNOWN);
            assertEquals(CellStatus.CONTEXT_REQUIRED, env.status()); assertTrue(env.lower().isEmpty()); assertTrue(env.upper().isFullCube());
            // the entity-attribute branches cannot be exercised without a Level-backed entity here: the falling shape is the extracted native constant
            assertEquals(resolver.shape(profile.table().namedShape("powder_snow_falling")), resolver.resolve(plan, 0, 0, 0, ShapeContext.of(1.0, false, 3.0, false)).lower());
            assertTrue(resolver.resolve(plan, 0, 5, 0, ShapeContext.of(6.0, false, 0, true)).lower().isFullCube(), "boots + above + not descending -> full cube");
            assertTrue(resolver.resolve(plan, 0, 5, 0, ShapeContext.of(6.0, true, 0, true)).lower().isEmpty(), "descending -> empty");
            assertTrue(resolver.resolve(plan, 0, 5, 0, ShapeContext.of(5.5, false, 0, true)).lower().isEmpty(), "inside the block -> empty");
        }
    }

    @Test void avatarPoseBoxesMatchNativeMakeBoundingBox() throws Exception {
        Class<?> avatar = Class.forName("net.minecraft.world.entity.Avatar");
        var posesF = avatar.getDeclaredField("POSES"); posesF.setAccessible(true); var standingF = avatar.getDeclaredField("STANDING_DIMENSIONS"); standingF.setAccessible(true);
        Map<?, ?> poses = (Map<?, ?>) posesF.get(null); Object standing = standingF.get(null);
        Class<?> poseC = Class.forName("net.minecraft.world.entity.Pose"); var make = Class.forName("net.minecraft.world.entity.EntityDimensions").getMethod("makeBoundingBox", double.class, double.class, double.class);
        var rnd = new Random(42);
        for (PlayerBox.Pose pose : PlayerBox.Pose.values()) {
            Object nativePose = null; for (Object c : poseC.getEnumConstants()) if (c.toString().equals(pose.name())) nativePose = c;
            assertNotNull(nativePose, pose.name()); Object dims = poses.containsKey(nativePose) ? poses.get(nativePose) : standing;
            for (int i = 0; i < 200; i++) {
                double x = rnd.nextDouble(-3.0E7, 3.0E7), y = rnd.nextDouble(-2048, 2048), z = rnd.nextDouble(-3.0E7, 3.0E7);
                if (i == 0) { x = 0.5; y = 64; z = -0.5; }
                Object nb = make.invoke(dims, x, y, z);
                Aabb expected = new Aabb(aabbFields[0].getDouble(nb), aabbFields[1].getDouble(nb), aabbFields[2].getDouble(nb), aabbFields[3].getDouble(nb), aabbFields[4].getDouble(nb), aabbFields[5].getDouble(nb));
                assertEquals(expected, PlayerBox.of(profile, pose, x, y, z), pose + " @" + x + "," + y + "," + z);
            }
        }
    }
}
