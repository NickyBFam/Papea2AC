package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.WorldBudget;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5 test scaffolding on top of the Phase 4 {@link CollisionTestSupport.Scene}: the pinned movement profile, a
 * world-owned {@link BlockBehaviorQuery}, the simulator, and a fully-known default environment over the scene's current
 * view. The baseline surface is at y = 70 (dirt 68..69, stone below), so a standing player rests at y = 70.0.
 */
final class PhysicsTestSupport {
    private PhysicsTestSupport() {}
    static final MovementProfile PROFILE = MovementProfile.paper26_2();
    static final double SURFACE = 70.0;
    static final BlockStateKey ICE = BlockStateKey.of("minecraft:ice"), BLUE_ICE = BlockStateKey.of("minecraft:blue_ice"), SLIME = BlockStateKey.of("minecraft:slime_block"), HONEY = BlockStateKey.of("minecraft:honey_block"),
        SOUL_SAND = BlockStateKey.of("minecraft:soul_sand"), COBWEB = BlockStateKey.of("minecraft:cobweb"), BERRY_BUSH = BlockStateKey.parse("minecraft:sweet_berry_bush[age=0]"), WATER = BlockStateKey.parse("minecraft:water[level=0]"),
        LAVA = BlockStateKey.parse("minecraft:lava[level=0]"), BED_FOOT = BlockStateKey.parse("minecraft:red_bed[facing=north,occupied=false,part=foot]"), MAGMA = BlockStateKey.of("minecraft:magma_block"),
        NETHER_PORTAL = BlockStateKey.parse("minecraft:nether_portal[axis=x]"), VINE = BlockStateKey.parse("minecraft:vine[east=false,north=true,south=false,up=false,west=false]"),
        WATERLOGGED_SLAB = BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=true]");

    static final class PhysicsScene {
        final CollisionTestSupport.Scene scene; final BlockBehaviorQuery behaviors; final PhysicsSimulator simulator;
        /** Default: the 3x3 baseline chunks around the origin are observed (x, z in -16..31). */
        PhysicsScene() { this(new CollisionTestSupport.Scene(1, new WorldBudget())); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) scene.baseline(cx, cz); }
        PhysicsScene(CollisionTestSupport.Scene scene) {
            this.scene = scene;
            behaviors = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionTestSupport.PROFILE.table(), scene.dictionary);
            simulator = new PhysicsSimulator(PROFILE);
        }
        long set(int x, int y, int z, BlockStateKey state) { return scene.set(x, y, z, state); }
        /** Fully-known environment over the current view (default attributes, no effects/entities, default border, no boots). */
        PhysicsEnvironment env() { return PhysicsEnvironment.of(scene.world.currentView(), scene.world.worldEpoch(), scene.engine, behaviors, PROFILE); }
        PhysicsResult step(PhysicsState s, MovementInput in) { return simulator.simulate(s, in, env()); }
        PhysicsResult step(PhysicsState s, MovementInput in, PhysicsEnvironment env) { return simulator.simulate(s, in, env); }
        /** Runs {@code ticks} ticks of the same input from {@code start}; every step must be EXACT unless {@code allowInexact}. */
        List<PhysicsResult> run(PhysicsState start, MovementInput in, int ticks, PhysicsEnvironment env, boolean allowInexact) {
            List<PhysicsResult> out = new ArrayList<>(); PhysicsState s = start;
            for (int i = 0; i < ticks; i++) {
                PhysicsResult r = simulator.simulate(s, in, env);
                out.add(r);
                if (r.next() == null) break;
                if (!allowInexact && !r.exact()) throw new AssertionError("tick " + i + ": " + r.summary());
                s = r.next();
            }
            return out;
        }
        List<PhysicsResult> run(PhysicsState start, MovementInput in, int ticks) { return run(start, in, ticks, env(), false); }
    }
    static PhysicsState standingAt(double x, double y, double z) { return PhysicsState.resting(x, y, z, PlayerBox.Pose.STANDING, true); }
    static PhysicsState airborneAt(double x, double y, double z) { return PhysicsState.resting(x, y, z, PlayerBox.Pose.STANDING, false); }
    /** A vanilla-consistent resting state: two idle ticks from a zero-velocity stand give the resting velocity (-0.0784), the simulated onGround flag and the supporting block, exactly as a player who has stood still for a while carries them. */
    static PhysicsState settled(PhysicsScene ps, double x, double y, double z) {
        PhysicsState s = standingAt(x, y, z);
        for (int i = 0; i < 2; i++) { PhysicsResult r = ps.step(s, MovementInput.idle(0f)); if (!r.exact()) throw new AssertionError(r.summary()); s = r.next(); }
        if (!s.onGround()) throw new AssertionError("not resting on ground at " + x + "," + y + "," + z);
        return s;
    }
}
