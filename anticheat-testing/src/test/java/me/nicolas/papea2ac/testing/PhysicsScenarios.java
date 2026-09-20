package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.physics.*;
import java.util.*;

/**
 * The Phase 5 scenario catalog shared by the unit matrix, the replay fixtures and the native differential (TESTING.md
 * "Physics: All supported movement modes and transitions ... Native-client captures with known environment/input").
 * Every scenario is explicit: a void arena with placed blocks, an initial vanilla state, explicit attributes/effects
 * and an explicit per-tick key sequence. The same description drives the oracle plugin (through JSON) and the simulator
 * (through {@link ScenarioWorld}); nothing is inferred on either side.
 *
 * Arena: a stone platform at y = 63 covering x, z in [-12, 12] (feet rest at y = 64.0). The oracle world is a void
 * superflat, so every non-air cell the movement code can see is in {@code blocks}.
 */
final class PhysicsScenarios {
    private PhysicsScenarios() {}
    static final int PLATFORM_Y = 63, PLATFORM_HALF = 12;
    static final double FLOOR = 64.0, REST_VY = -0.0784000015258789;
    static final String STONE = "minecraft:stone", AIR = "minecraft:air";

    record Block(int x, int y, int z, String state) {}
    record Tick(int f, int s, boolean jump, boolean shift, boolean sprint, boolean use, float yaw) {
        static Tick idle() { return new Tick(0, 0, false, false, false, false, 0f); }
        static Tick fwd(float yaw) { return new Tick(1, 0, false, false, false, false, yaw); }
        static Tick sprint(float yaw) { return new Tick(1, 0, false, false, true, false, yaw); }
    }
    record Start(double x, double y, double z, double vx, double vy, double vz, float yaw, boolean onGround, PlayerBox.Pose pose, double fallDistance, Vec3d stuck, boolean inPowderSnow, int noJumpDelay, int[] support, boolean sprinting, boolean shift) {
        /** Resting on the platform at (x, z) with the vanilla resting velocity and the platform block recorded as support. */
        static Start resting(double x, double z, float yaw) { return new Start(x, FLOOR, z, 0.0, REST_VY, 0.0, yaw, true, PlayerBox.Pose.STANDING, 0.0, Vec3d.ZERO, false, 0, new int[] { (int) Math.floor(x), PLATFORM_Y, (int) Math.floor(z) }, false, false); }
        static Start airborne(double x, double y, double z, double vx, double vy, double vz, float yaw) { return new Start(x, y, z, vx, vy, vz, yaw, false, PlayerBox.Pose.STANDING, 0.0, Vec3d.ZERO, false, 0, null, false, false); }
        Start withPose(PlayerBox.Pose p) { return new Start(x, y, z, vx, vy, vz, yaw, onGround, p, fallDistance, stuck, inPowderSnow, noJumpDelay, support, sprinting, shift); }
        Start withSprinting(boolean s) { return new Start(x, y, z, vx, vy, vz, yaw, onGround, pose, fallDistance, stuck, inPowderSnow, noJumpDelay, support, s, shift); }
        Start withVelocity(double vx, double vy, double vz) { return new Start(x, y, z, vx, vy, vz, yaw, onGround, pose, fallDistance, stuck, inPowderSnow, noJumpDelay, support, sprinting, shift); }
        PhysicsState state() { return new PhysicsState(new Vec3d(x, y, z), new Vec3d(vx, vy, vz), pose, onGround, false, false, false, fallDistance, stuck, inPowderSnow, support == null ? PhysicsState.Support.NONE : PhysicsState.Support.at(support[0], support[1], support[2]), noJumpDelay, PhysicsState.Locomotion.WALKING); }
    }
    record Effect(String id, int amplifier) {}
    record Scenario(String id, String group, List<Block> blocks, Start start, Map<String, Double> attributes, List<Effect> effects, boolean boots, List<Tick> ticks, boolean fluidDeferred) {
        Scenario { blocks = List.copyOf(blocks); attributes = Map.copyOf(attributes); effects = List.copyOf(effects); ticks = List.copyOf(ticks); }
        MovementInput input(Tick t) { return new MovementInput(t.f, t.s, t.jump, t.shift, t.sprint, t.use, t.yaw); }
    }

    private static List<Block> platform() {
        List<Block> b = new ArrayList<>();
        for (int x = -PLATFORM_HALF; x <= PLATFORM_HALF; x++) for (int z = -PLATFORM_HALF; z <= PLATFORM_HALF; z++) b.add(new Block(x, PLATFORM_Y, z, STONE));
        return b;
    }
    private static List<Block> platformWith(Block... extra) { List<Block> b = platform(); b.addAll(List.of(extra)); return b; }
    /** Platform whose top layer inside [x0,x1]x[z0,z1] is replaced by {@code state} (e.g. ice). */
    private static List<Block> platformSurface(String state, int x0, int z0, int x1, int z1) {
        List<Block> b = new ArrayList<>();
        for (int x = -PLATFORM_HALF; x <= PLATFORM_HALF; x++) for (int z = -PLATFORM_HALF; z <= PLATFORM_HALF; z++) b.add(new Block(x, PLATFORM_Y, z, x >= x0 && x <= x1 && z >= z0 && z <= z1 ? state : STONE));
        return b;
    }
    private static List<Tick> repeat(Tick t, int n) { List<Tick> l = new ArrayList<>(n); for (int i = 0; i < n; i++) l.add(t); return l; }
    private static List<Tick> concat(List<Tick> a, List<Tick> b) { List<Tick> l = new ArrayList<>(a); l.addAll(b); return l; }
    private static Scenario sc(String id, String group, List<Block> blocks, Start start, List<Tick> ticks) { return new Scenario(id, group, blocks, start, Map.of(), List.of(), false, ticks, false); }
    private static Scenario sc(String id, String group, List<Block> blocks, Start start, Map<String, Double> attrs, List<Effect> effects, boolean boots, List<Tick> ticks) { return new Scenario(id, group, blocks, start, attrs, effects, boots, ticks, false); }
    private static List<Block> wallZ(int z, int y0, int y1, int x0, int x1, String state) { List<Block> b = new ArrayList<>(); for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) b.add(new Block(x, y, z, state)); return b; }
    private static List<Block> plus(List<Block> a, List<Block> b) { List<Block> l = new ArrayList<>(a); l.addAll(b); return l; }

    /** The whole catalog, deterministic order. */
    static List<Scenario> all() {
        List<Scenario> s = new ArrayList<>();
        Start rest = Start.resting(0.5, 0.5, 0f);
        // ---- ground / air basics
        s.add(sc("idle_ground", "basic", platform(), rest, repeat(Tick.idle(), 20)));
        s.add(sc("walk_forward", "basic", platform(), rest, repeat(Tick.fwd(0f), 40)));
        s.add(sc("walk_backward", "basic", platform(), rest, repeat(new Tick(-1, 0, false, false, false, false, 0f), 30)));
        s.add(sc("strafe_left", "basic", platform(), rest, repeat(new Tick(0, 1, false, false, false, false, 0f), 30)));
        s.add(sc("strafe_right", "basic", platform(), rest, repeat(new Tick(0, -1, false, false, false, false, 0f), 30)));
        s.add(sc("diagonal_forward_left", "basic", platform(), rest, repeat(new Tick(1, 1, false, false, false, false, 0f), 30)));
        s.add(sc("diagonal_back_right", "basic", platform(), rest, repeat(new Tick(-1, -1, false, false, false, false, 0f), 30)));
        s.add(sc("sprint_forward", "basic", platform(), rest.withSprinting(true), repeat(Tick.sprint(0f), 40)));
        s.add(sc("walk_yaw_37", "basic", platform(), Start.resting(0.5, 0.5, 37.5f), repeat(Tick.fwd(37.5f), 30)));
        s.add(sc("walk_yaw_neg123", "basic", platform(), Start.resting(0.5, 0.5, -123.4f), repeat(Tick.fwd(-123.4f), 30)));
        s.add(sc("walk_yaw_90", "basic", platform(), Start.resting(0.5, 0.5, 90f), repeat(Tick.fwd(90f), 30)));
        s.add(sc("walk_yaw_180_strafe", "basic", platform(), Start.resting(0.5, 0.5, 180f), repeat(new Tick(1, -1, false, false, false, false, 180f), 30)));
        s.add(sc("use_item_walk", "basic", platform(), rest, repeat(new Tick(1, 0, false, false, false, true, 0f), 30)));
        s.add(sc("sneak_walk", "basic", platform(), rest, repeat(new Tick(1, 0, false, true, false, false, 0f), 30)));
        s.add(sc("sprint_stop_transition", "basic", platform(), rest.withSprinting(true), concat(repeat(Tick.sprint(0f), 15), repeat(Tick.idle(), 25))));
        // ---- jumps and falls
        s.add(sc("jump_stationary", "jump", platform(), rest, repeat(new Tick(0, 0, true, false, false, false, 0f), 30)));
        s.add(sc("jump_forward", "jump", platform(), rest, repeat(new Tick(1, 0, true, false, false, false, 0f), 30)));
        s.add(sc("sprint_jump", "jump", platform(), rest.withSprinting(true), repeat(new Tick(1, 0, true, false, true, false, 0f), 30)));
        s.add(sc("sprint_jump_yaw", "jump", platform(), Start.resting(0.5, 0.5, 45f).withSprinting(true), repeat(new Tick(1, 0, true, false, true, false, 45f), 30)));
        s.add(sc("jump_tap_then_release", "jump", platform(), rest, concat(concat(repeat(new Tick(0, 0, true, false, false, false, 0f), 1), repeat(Tick.idle(), 14)), repeat(new Tick(0, 0, true, false, false, false, 0f), 15))));
        s.add(sc("fall_20", "fall", platform(), Start.airborne(0.5, FLOOR + 20, 0.5, 0, 0, 0, 0f), repeat(Tick.idle(), 60)));
        s.add(sc("fall_100_ticks", "fall", platform(), Start.airborne(0.5, FLOOR + 250, 0.5, 0, 0, 0, 0f), repeat(Tick.idle(), 100)));
        s.add(sc("air_strafe", "fall", platform(), Start.airborne(0.5, FLOOR + 3, 0.5, 0, 0.3, 0, 0f), repeat(new Tick(1, 1, false, false, false, false, 0f), 40)));
        s.add(sc("air_sprint_forward", "fall", platform(), Start.airborne(0.5, FLOOR + 3, 0.5, 0, 0.3, 0.2, 0f).withSprinting(true), repeat(new Tick(1, 0, false, false, true, false, 0f), 40)));
        // ---- collisions
        s.add(sc("wall_forward", "collision", plus(platform(), wallZ(3, 64, 66, -2, 2, STONE)), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("wall_diagonal_corner", "collision", plus(plus(platform(), wallZ(3, 64, 66, -3, 3, STONE)), List.of(new Block(3, 64, 0, STONE), new Block(3, 65, 0, STONE), new Block(3, 64, 1, STONE), new Block(3, 65, 1, STONE), new Block(3, 64, 2, STONE), new Block(3, 65, 2, STONE))), rest, repeat(new Tick(1, -1, false, false, false, false, 0f), 30)));
        s.add(sc("ceiling_jump", "collision", plus(platform(), wallZ(0, 66, 66, -1, 1, STONE)), rest, repeat(new Tick(0, 0, true, false, false, false, 0f), 20)));
        s.add(sc("ceiling_slab_jump", "collision", platformWith(new Block(0, 66, 0, "minecraft:oak_slab[type=bottom,waterlogged=false]")), rest, repeat(new Tick(0, 0, true, false, false, false, 0f), 20)));
        s.add(sc("step_up_block", "step", platformWith(new Block(0, 64, 2, STONE), new Block(0, 64, 3, STONE), new Block(0, 64, 4, STONE)), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("step_up_slab", "step", platformWith(new Block(0, 64, 2, "minecraft:oak_slab[type=bottom,waterlogged=false]"), new Block(0, 64, 3, "minecraft:oak_slab[type=bottom,waterlogged=false]")), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("step_up_stairs", "step", platformWith(new Block(0, 64, 2, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"), new Block(0, 64, 3, STONE), new Block(0, 64, 4, STONE)), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("step_fail_two_high", "step", platformWith(new Block(0, 64, 2, STONE), new Block(0, 65, 2, STONE)), rest, repeat(Tick.fwd(0f), 20)));
        s.add(sc("fence_blocks", "step", platformWith(new Block(0, 64, 2, "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]"), new Block(-1, 64, 2, "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]"), new Block(1, 64, 2, "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]")), rest, repeat(Tick.fwd(0f), 20)));
        s.add(sc("wall_block_blocks", "step", platformWith(new Block(0, 64, 2, "minecraft:cobblestone_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=low]")), rest, repeat(Tick.fwd(0f), 20)));
        s.add(sc("step_while_sprinting", "step", platformWith(new Block(0, 64, 3, STONE), new Block(0, 64, 4, STONE), new Block(0, 64, 5, STONE)), rest.withSprinting(true), repeat(Tick.sprint(0f), 30)));
        s.add(sc("step_height_attribute_two_blocks", "step", platformWith(new Block(0, 64, 2, STONE), new Block(0, 65, 2, STONE), new Block(0, 64, 3, STONE), new Block(0, 65, 3, STONE)), rest, Map.of("minecraft:step_height", 1.25), List.of(), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("crouch_under_slab_ceiling", "pose", plus(platform(), wallZ(2, 65, 65, -1, 1, "minecraft:oak_slab[type=top,waterlogged=false]")), rest, repeat(new Tick(1, 0, false, true, false, false, 0f), 30)));
        s.add(sc("crawl_gap", "pose", plus(plus(platform(), wallZ(1, 65, 66, -1, 1, STONE)), wallZ(2, 65, 66, -1, 1, STONE)), new Start(0.5, FLOOR, 1.5, 0, REST_VY, 0, 0f, true, PlayerBox.Pose.SWIMMING, 0, Vec3d.ZERO, false, 0, new int[] { 0, PLATFORM_Y, 1 }, false, false), repeat(Tick.fwd(0f), 20)));
        s.add(sc("sneak_edge_backoff", "sneak", platform(), Start.resting(0.5, 11.5, 0f), repeat(new Tick(1, 0, false, true, false, false, 0f), 40)));
        s.add(sc("sneak_edge_diagonal", "sneak", platform(), Start.resting(11.2, 11.2, 45f), repeat(new Tick(1, 0, false, true, false, false, 45f), 30)));
        s.add(sc("walk_off_edge", "fall", platform(), Start.resting(0.5, 11.5, 0f), repeat(Tick.fwd(0f), 30)));
        // ---- special blocks
        s.add(sc("ice_walk", "friction", platformSurface("minecraft:ice", -12, -12, 12, 12), rest, concat(repeat(Tick.fwd(0f), 20), repeat(Tick.idle(), 20))));
        s.add(sc("blue_ice_sprint", "friction", platformSurface("minecraft:blue_ice", -12, -12, 12, 12), rest.withSprinting(true), concat(repeat(Tick.sprint(0f), 20), repeat(Tick.idle(), 20))));
        s.add(sc("packed_ice_strafe", "friction", platformSurface("minecraft:packed_ice", -12, -12, 12, 12), rest, repeat(new Tick(1, 1, false, false, false, false, 0f), 25)));
        s.add(sc("slime_walk", "friction", platformSurface("minecraft:slime_block", -12, -12, 12, 12), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("slime_bounce", "bounce", platformSurface("minecraft:slime_block", -12, -12, 12, 12), Start.airborne(0.5, FLOOR + 8, 0.5, 0, 0, 0, 0f), repeat(Tick.idle(), 60)));
        s.add(sc("slime_bounce_suppressed", "bounce", platformSurface("minecraft:slime_block", -12, -12, 12, 12), Start.airborne(0.5, FLOOR + 8, 0.5, 0, 0, 0, 0f), repeat(new Tick(0, 0, false, true, false, false, 0f), 40)));
        s.add(sc("bed_bounce", "bounce", platformWith(new Block(0, 64, 0, "minecraft:red_bed[facing=north,occupied=false,part=head]"), new Block(0, 64, 1, "minecraft:red_bed[facing=north,occupied=false,part=foot]")), Start.airborne(0.5, FLOOR + 8, 0.5, 0, 0, 0, 0f), repeat(Tick.idle(), 50)));
        s.add(sc("honey_walk", "factor", platformSurface("minecraft:honey_block", -12, -12, 12, 12), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("honey_jump", "factor", platformSurface("minecraft:honey_block", -12, -12, 12, 12), rest, repeat(new Tick(0, 0, true, false, false, false, 0f), 20)));
        s.add(sc("honey_wall_slide", "factor", plus(platform(), wallZ(1, 64, 70, -1, 1, "minecraft:honey_block")), Start.airborne(0.5, FLOOR + 6, 0.35, 0, 0, 0.05, 0f), repeat(Tick.fwd(0f), 40)));
        s.add(sc("soul_sand_walk", "factor", platformSurface("minecraft:soul_sand", -12, -12, 12, 12), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("soul_sand_sprint_jump", "factor", platformSurface("minecraft:soul_sand", -12, -12, 12, 12), rest.withSprinting(true), repeat(new Tick(1, 0, true, false, true, false, 0f), 30)));
        s.add(sc("cobweb_walk_in", "stuck", platformWith(new Block(0, 64, 2, "minecraft:cobweb"), new Block(0, 65, 2, "minecraft:cobweb")), rest, repeat(Tick.fwd(0f), 40)));
        s.add(sc("cobweb_fall_through", "stuck", platformWith(new Block(0, 66, 0, "minecraft:cobweb"), new Block(0, 67, 0, "minecraft:cobweb")), Start.airborne(0.5, FLOOR + 10, 0.5, 0, 0, 0, 0f), repeat(Tick.idle(), 80)));
        s.add(sc("berry_bush_walk", "stuck", platformWith(new Block(0, 64, 2, "minecraft:sweet_berry_bush[age=0]"), new Block(0, 64, 3, "minecraft:sweet_berry_bush[age=0]")), rest, repeat(Tick.fwd(0f), 40)));
        s.add(sc("powder_snow_fall_in", "stuck", platformWith(new Block(0, 64, 0, "minecraft:powder_snow"), new Block(0, 65, 0, "minecraft:powder_snow"), new Block(0, 66, 0, "minecraft:powder_snow")), Start.airborne(0.5, FLOOR + 5, 0.5, 0, 0, 0, 0f), repeat(Tick.idle(), 50)));
        s.add(sc("powder_snow_boots_walk", "stuck", plus(platform(), wallZ(2, 64, 64, -2, 2, "minecraft:powder_snow")), rest, Map.of(), List.of(), true, repeat(Tick.fwd(0f), 30)));
        s.add(sc("powder_snow_boots_walk_in_climb", "stuck", platformWith(new Block(0, 64, 2, "minecraft:powder_snow"), new Block(0, 65, 2, "minecraft:powder_snow"), new Block(0, 64, 3, "minecraft:powder_snow"), new Block(0, 65, 3, "minecraft:powder_snow")), rest, Map.of(), List.of(), true, repeat(new Tick(1, 0, true, false, false, false, 0f), 40)));
        s.add(sc("magma_walk", "factor", platformSurface("minecraft:magma_block", -12, -12, 12, 12), rest, repeat(Tick.fwd(0f), 20)));
        // ---- climbing
        List<Block> ladderWall = plus(wallZ(2, 64, 70, -1, 1, STONE), List.of(new Block(0, 64, 1, "minecraft:ladder[facing=north,waterlogged=false]"), new Block(0, 65, 1, "minecraft:ladder[facing=north,waterlogged=false]"), new Block(0, 66, 1, "minecraft:ladder[facing=north,waterlogged=false]"), new Block(0, 67, 1, "minecraft:ladder[facing=north,waterlogged=false]"), new Block(0, 68, 1, "minecraft:ladder[facing=north,waterlogged=false]")));
        s.add(sc("ladder_climb", "climb", plus(platform(), ladderWall), rest, repeat(Tick.fwd(0f), 40)));
        s.add(sc("ladder_hold_shift", "climb", plus(platform(), ladderWall), new Start(0.5, FLOOR + 2.5, 1.5, 0, 0, 0, 0f, false, PlayerBox.Pose.STANDING, 0, Vec3d.ZERO, false, 0, null, false, true), repeat(new Tick(0, 0, false, true, false, false, 0f), 20)));
        s.add(sc("ladder_slide_down", "climb", plus(platform(), ladderWall), Start.airborne(0.5, FLOOR + 3.5, 1.5, 0, 0, 0, 0f), repeat(Tick.idle(), 30)));
        s.add(sc("ladder_jump_up", "climb", plus(platform(), ladderWall), rest, repeat(new Tick(1, 0, true, false, false, false, 0f), 30)));
        s.add(sc("vine_climb", "climb", plus(plus(platform(), wallZ(2, 64, 70, -1, 1, STONE)), List.of(new Block(0, 64, 1, "minecraft:vine[east=false,north=false,south=true,up=false,west=false]"), new Block(0, 65, 1, "minecraft:vine[east=false,north=false,south=true,up=false,west=false]"), new Block(0, 66, 1, "minecraft:vine[east=false,north=false,south=true,up=false,west=false]"))), rest, repeat(Tick.fwd(0f), 30)));
        s.add(sc("trapdoor_ladder", "climb", plus(plus(platform(), wallZ(2, 64, 70, -1, 1, STONE)), List.of(new Block(0, 64, 1, "minecraft:ladder[facing=north,waterlogged=false]"), new Block(0, 65, 1, "minecraft:ladder[facing=north,waterlogged=false]"), new Block(0, 66, 1, "minecraft:oak_trapdoor[facing=north,half=top,open=true,powered=false,waterlogged=false]"))), rest, repeat(Tick.fwd(0f), 40)));
        s.add(sc("scaffolding_climb", "climb", platformWith(new Block(0, 64, 0, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]"), new Block(0, 65, 0, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]"), new Block(0, 66, 0, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]")), rest, repeat(new Tick(0, 0, true, false, false, false, 0f), 30)));
        s.add(sc("scaffolding_descend", "climb", platformWith(new Block(0, 64, 0, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]"), new Block(0, 65, 0, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]"), new Block(0, 66, 0, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]")), new Start(0.5, FLOOR + 3, 0.5, 0, 0, 0, 0f, true, PlayerBox.Pose.STANDING, 0, Vec3d.ZERO, false, 0, new int[] { 0, 66, 0 }, false, true), repeat(new Tick(0, 0, false, true, false, false, 0f), 30)));
        // ---- effects and attributes
        s.add(sc("speed_2_walk", "effect", platform(), rest, Map.of(), List.of(new Effect("minecraft:speed", 1)), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("speed_1_sprint", "effect", platform(), rest.withSprinting(true), Map.of(), List.of(new Effect("minecraft:speed", 0)), false, repeat(Tick.sprint(0f), 30)));
        s.add(sc("slowness_1_walk", "effect", platform(), rest, Map.of(), List.of(new Effect("minecraft:slowness", 0)), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("jump_boost_2", "effect", platform(), rest, Map.of(), List.of(new Effect("minecraft:jump_boost", 1)), false, repeat(new Tick(0, 0, true, false, false, false, 0f), 40)));
        s.add(sc("slow_falling", "effect", platform(), Start.airborne(0.5, FLOOR + 6, 0.5, 0, 0, 0, 0f), Map.of(), List.of(new Effect("minecraft:slow_falling", 0)), false, repeat(Tick.idle(), 60)));
        s.add(sc("levitation", "effect", platform(), rest, Map.of(), List.of(new Effect("minecraft:levitation", 0)), false, repeat(Tick.idle(), 40)));
        s.add(sc("weaving_cobweb", "effect", platformWith(new Block(0, 64, 2, "minecraft:cobweb"), new Block(0, 65, 2, "minecraft:cobweb")), rest, Map.of(), List.of(new Effect("minecraft:weaving", 0)), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("attr_speed_0_2", "attribute", platform(), rest, Map.of("minecraft:movement_speed", 0.2), List.of(), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("attr_gravity_0_04_jump", "attribute", platform(), rest, Map.of("minecraft:gravity", 0.04), List.of(), false, repeat(new Tick(0, 0, true, false, false, false, 0f), 40)));
        s.add(sc("attr_jump_strength_0_6", "attribute", platform(), rest, Map.of("minecraft:jump_strength", 0.6), List.of(), false, repeat(new Tick(0, 0, true, false, false, false, 0f), 40)));
        s.add(sc("attr_sneaking_speed_0_5", "attribute", platform(), rest, Map.of("minecraft:sneaking_speed", 0.5), List.of(), false, repeat(new Tick(1, 0, false, true, false, false, 0f), 30)));
        s.add(sc("attr_scale_0_5_walk_gap", "attribute", plus(plus(platform(), wallZ(2, 65, 66, -1, 1, STONE)), wallZ(3, 65, 66, -1, 1, STONE)), rest, Map.of("minecraft:scale", 0.5), List.of(), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("attr_bounciness_0_5_wall", "attribute", plus(platform(), wallZ(3, 64, 66, -2, 2, STONE)), rest, Map.of("minecraft:bounciness", 0.5), List.of(), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("attr_air_drag_modifier_0_5", "attribute", platform(), rest, Map.of("minecraft:air_drag_modifier", 0.5), List.of(), false, repeat(new Tick(1, 0, true, false, false, false, 0f), 30)));
        s.add(sc("attr_friction_modifier_0_5_ice", "attribute", platformSurface("minecraft:ice", -12, -12, 12, 12), rest, Map.of("minecraft:friction_modifier", 0.5), List.of(), false, repeat(Tick.fwd(0f), 30)));
        s.add(sc("attr_movement_efficiency_0_5_soul_sand", "attribute", platformSurface("minecraft:soul_sand", -12, -12, 12, 12), rest, Map.of("minecraft:movement_efficiency", 0.5), List.of(), false, repeat(Tick.fwd(0f), 30)));
        // ---- external velocity (already applied by the caller: the start carries it)
        s.add(sc("knockback_applied", "external", platform(), Start.airborne(0.5, FLOOR + 0.1, 0.5, 0.9, 0.4, 0.3, 0f), repeat(Tick.idle(), 40)));
        s.add(sc("knockback_into_wall", "external", plus(platform(), wallZ(3, 64, 68, -3, 3, STONE)), Start.airborne(0.5, FLOOR + 0.1, 0.5, 0.0, 0.5, 1.4, 0f), repeat(Tick.idle(), 30)));
        s.add(sc("explosion_launch", "external", platform(), Start.resting(0.5, 0.5, 0f).withVelocity(-1.8, 1.9, 0.7), repeat(Tick.fwd(0f), 80)));
        // ---- fluids: run natively for the record; the simulator reports UNSUPPORTED_FLUID (explicit deferral, not compared)
        s.add(new Scenario("water_deferred", "fluid", platformWith(new Block(0, 64, 0, "minecraft:water[level=0]"), new Block(0, 64, 1, "minecraft:water[level=0]")), rest, Map.of(), List.of(), false, repeat(Tick.fwd(0f), 10), true));
        s.add(new Scenario("lava_deferred", "fluid", platformWith(new Block(0, 64, 0, "minecraft:lava[level=0]")), rest, Map.of(), List.of(), false, repeat(Tick.idle(), 10), true));
        s.add(new Scenario("waterlogged_slab_deferred", "fluid", platformWith(new Block(0, 64, 0, "minecraft:oak_slab[type=bottom,waterlogged=true]")), Start.resting(0.5, 0.5, 0f), Map.of(), List.of(), false, repeat(Tick.idle(), 10), true));
        // ---- mixed course (deterministic pseudo-random keys)
        List<Block> course = platformWith(new Block(0, 64, 3, STONE), new Block(1, 64, 3, "minecraft:oak_slab[type=bottom,waterlogged=false]"), new Block(-1, 64, 4, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"), new Block(2, 64, 5, STONE), new Block(2, 65, 5, STONE),
            new Block(-3, 64, 2, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"), new Block(3, 64, -2, "minecraft:ice"), new Block(4, 64, -2, "minecraft:ice"), new Block(-2, 65, -3, STONE), new Block(-2, 66, -3, STONE), new Block(0, 64, -5, "minecraft:soul_sand"));
        List<Tick> random = new ArrayList<>(); long seed = 0x5EED5EEDL;
        for (int i = 0; i < 100; i++) { seed = seed * 6364136223846793005L + 1442695040888963407L; int r = (int) (seed >>> 33); random.add(new Tick((r % 3) - 1, ((r >> 2) % 3) - 1, (r >> 4 & 7) == 0, (r >> 7 & 15) == 0, (r >> 11 & 3) == 0, false, (float) ((r >> 13) % 360))); }
        s.add(sc("mixed_course_random_100", "mixed", course, rest, random));
        return s;
    }
}
