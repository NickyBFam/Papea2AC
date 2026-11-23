package me.nicolas.papea2ac.version;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * The pinned target's movement rules as pure data (MOVEMENT_SIMULATION.md "Step specification": "The selected ruleset
 * defines exact operation order ..."; NUMERICAL_POLICY.md NUM1: "Qualified concrete physics reproduces the selected
 * client's numerical algorithm, including casts, lookup/indexing and operation order"). Every constant names the
 * vanilla 26.2 source it reproduces with the exact Java type the client uses (a {@code float} literal stays a float;
 * where the client widens a float literal to double the widened value is spelled out). None is a tolerance invented
 * here; none compounds per tick. The forward simulator in anticheat-physics reads these and defines no constant of its own.
 *
 * Data members come from the controlled nativeTest extraction ({@code MovementPropertyExtractMain}) of the pinned jar:
 * the block movement table, the player's attribute supplier, the client-local sprint modifier, every mob effect's
 * attribute templates, and the 65536-entry {@code Mth.SIN} float table (the client's yaw basis; the resource bits are
 * authoritative, the generating formula is documentation). Loading failure surfaces at composition, never partially.
 */
public final class MovementProfile {
    // ---- input (LocalPlayer / KeyboardInput / LivingEntity.applyInput), see PHASE_5_IMPLEMENTATION.md "MOVEMENT INPUT"
    /** {@code KeyboardInput.calculateImpulse}: opposing keys cancel, otherwise +1 / -1 (float). */
    public static final float KEY_IMPULSE = 1.0f;
    /** {@code LocalPlayer.aiStep}: move impulses are multiplied by {@code 0.2F} while using an item (and not riding). */
    public static final float USE_ITEM_INPUT_FACTOR = 0.2f;
    /**
     * The {@code 0.98F} input factor. Server-side {@code LivingEntity.applyInput} is {@code xxa *= 0.98F; zza *= 0.98F};
     * the local client overrides it and applies the same factor INSIDE its own input pipeline (see
     * {@link #VEC2_NORMALIZE_EPSILON}): once, before the square-movement rescale. P6-F1.
     */
    public static final float INPUT_FRICTION = 0.98f;
    /**
     * P6-F1 (real-client qualification, Phase 6): the local client's key vector is processed in FLOAT before the shared
     * double-precision {@code Entity.getInputVector}: {@code Vec2(strafe, forward).normalized()} (float length via
     * {@code Mth.sqrt}, a length below {@code 1.0E-4F} normalizes to ZERO, per-component division), then {@code scale(0.98F)},
     * {@code scale(0.2F)} while using an item, {@code scale(sneakingSpeed)} while moving slowly, then the square-movement
     * rescale: {@code u = v.scale(1.0F / |v|)}, {@code |v| * distanceToUnitSquare(u)} capped at {@code 1.0F}, where
     * {@code distanceToUnitSquare(u) = Mth.sqrt(1 + (min(|u.x|,|u.y|) / max(|u.x|,|u.y|))^2)}. Straight keys are
     * bit-identical to the per-axis impulse the Phase 5 model used; on a diagonal the result is {@code 0.7071067F} per
     * component (one float ulp BELOW {@code 1/sqrt(2)}, length {@code 1 - 1.014E-7}), which the double normalization no
     * longer touches - measured on the pinned client as a {@code 1.0E-8} block/tick shortfall against the Phase 5 model.
     * Client-only code: not observable by the server-side oracle, established by controlled real-client measurement.
     */
    public static final float VEC2_NORMALIZE_EPSILON = 1.0E-4f;
    /** The square-movement cap: the rescaled key vector never exceeds unit length. */
    public static final float SQUARE_MOVEMENT_CAP = 1.0f;
    /** {@code Entity.getInputVector}: an input with {@code lengthSqr < 1.0E-7} contributes nothing. */
    public static final double INPUT_LENGTH_SQR_EPSILON = 1.0E-7;
    /** {@code Vec3.normalize}: a length below {@code 1.0E-4F} (widened) normalizes to ZERO. */
    public static final double NORMALIZE_EPSILON = 9.999999747378752E-6;
    /** {@code Entity.moveRelative / jumpFromGround}: degrees to radians as the client does it, {@code yaw * 0.017453292F} (float multiply, then widened). */
    public static final float DEG_TO_RAD = 0.017453292f;
    /** {@code Mth.sin(double)}: index = {@code (int)((long)(d * 10430.378350470453) & 65535L)}; cos adds 16384.0 before the cast. */
    public static final double SIN_SCALE = 10430.378350470453, COS_OFFSET = 16384.0;
    public static final long SIN_MASK = 65535L;
    // ---- aiStep
    /** {@code LivingEntity.aiStep}: a velocity component below {@code 0.003} in magnitude is zeroed before movement (non-players per axis). */
    public static final double VELOCITY_ZERO_THRESHOLD = 0.003;
    /** {@code LivingEntity.aiStep}, players: the horizontal pair is zeroed together when {@code horizontalDistanceSqr < 9.0E-6}. */
    public static final double PLAYER_HORIZONTAL_ZERO_THRESHOLD_SQR = 9.0E-6;
    /** {@code LivingEntity.aiStep}: ticks a jump is suppressed after {@code jumpFromGround}. */
    public static final int JUMP_DELAY_TICKS = 10;
    // ---- travelInAir / friction
    /** {@code LivingEntity.getFrictionInfluencedSpeed}: on ground with friction > 0.6 the speed is {@code getSpeed() * (0.21600002F / (f*f*f))} (float). */
    public static final float GROUND_SPEED_NUMERATOR = 0.21600002f;
    /** The friction value at which the numerator formula switches (compared as double: {@code (double)f > 0.6}). */
    public static final double GROUND_SPEED_FRICTION_THRESHOLD = 0.6;
    /** {@code LivingEntity.travelInAir}: base horizontal air drag {@code 0.91F} (multiplied by the ground friction when on ground) and vertical drag {@code 0.98F}. */
    public static final float AIR_FRICTION = 0.91f, VERTICAL_DRAG = 0.98f;
    /** {@code Player.getFlyingSpeed} (not flying): {@code 0.025999999F} when sprinting, else {@code 0.02F}. */
    public static final float AIR_SPRINT_SPEED = 0.025999999f, AIR_SPEED = 0.02f;
    /** {@code LivingEntity.travelInAir}, levitation: {@code dy += (0.05 * (amp + 1) - dy) * 0.2}. */
    public static final double LEVITATION_TARGET_PER_LEVEL = 0.05, LEVITATION_RATE = 0.2;
    /** {@code LivingEntity.getEffectiveGravity}: slow falling caps gravity at {@code 0.01} while not rising. */
    public static final double SLOW_FALLING_GRAVITY = 0.01;
    /** {@code LivingEntity.computeModifiedFriction(f, mod) = Mth.clamp(1 - (1 - f) * mod, 0, 1)} (float). */
    public static float computeModifiedFriction(float friction, float modifier) { float v = 1.0f - (1.0f - friction) * modifier; return v < 0.0f ? 0.0f : Math.min(v, 1.0f); }
    // ---- jump
    /** {@code LivingEntity.jumpFromGround}: no jump when the power is {@code <= 1.0E-5F}. */
    public static final float JUMP_POWER_EPSILON = 1.0E-5f;
    /** {@code LivingEntity.getJumpBoostPower}: {@code 0.1F * (amp + 1)}. */
    public static final float JUMP_BOOST_PER_LEVEL = 0.1f;
    /** {@code LivingEntity.jumpFromGround}: sprint-jump horizontal impulse {@code (-sin(yaw) * 0.2, 0, cos(yaw) * 0.2)}. */
    public static final double SPRINT_JUMP_IMPULSE = 0.2;
    /** P11-F27, {@code Player.causeExtraKnockback} (pinned 26.2, ldc2_w 0.6d twice): the attacker's own motion is multiplied by (0.6, 1.0, 0.6) when its knockback is positive. */
    public static final double ATTACKER_SLOWDOWN_HORIZONTAL = 0.6;
    // ---- climbing
    /** {@code LivingEntity.handleOnClimbable}: horizontal clamp {@code +-0.15F} (widened), vertical floor {@code -0.15F}, and the climb-up speed {@code 0.2} on horizontal collision / jumping. */
    public static final double CLIMB_LIMIT = 0.15000000596046448, CLIMB_UP_SPEED = 0.2;
    // ---- Entity.move / collide / step
    /** {@code Entity.move}: stuck multipliers and collision results below {@code lengthSqr 1.0E-7} are treated as zero. */
    public static final double MOVE_EPSILON_SQR = 1.0E-7;
    /** {@code Entity.collide}: the step candidate box is shrunk by {@code 1.0E-5F} at the bottom when the entity did not collide downward this move. */
    public static final double STEP_BOTTOM_EPSILON = 9.999999747378752E-6;
    /** {@code Mth.equal(double, double)}: {@code |a - b| < 1.0E-5F} (widened), the horizontal-collision test in {@code Entity.move}. */
    public static final double MTH_EQUAL_EPSILON = 9.999999747378752E-6;
    /** {@code Entity.move}: a collided movement of {@code lengthSqr >= 1.0} with a non-zero fall distance triggers the fall-damage-resetting point-ray clip ({@code FALLDAMAGE_RESETTING} blocks and water), reproduced by the physics module. */
    public static final double FALL_RESET_RAY_LENGTH_SQR = 1.0;
    /** {@code Entity.checkSupportingBlock}: the support probe box spans {@code minY - 1.0E-6 .. minY}. */
    public static final double SUPPORT_PROBE_DEPTH = 1.0E-6;
    /** {@code Entity.getOnPos} offsets: {@code getBlockPosBelowThatAffectsMyMovement = 0.500001F}, {@code getOnPosLegacy = 0.2F}, {@code getOnPos = 1.0E-5F}; fences/walls/gates keep the supporting pos for offsets {@code <= 0.5}. */
    public static final float ON_POS_MOVEMENT_OFFSET = 0.500001f, ON_POS_LEGACY_OFFSET = 0.2f, ON_POS_OFFSET = 1.0E-5f;
    public static final double ON_POS_KEEP_SUPPORT_LIMIT = 0.5;
    /** {@code Player.maybeBackOffFromEdge}: sneaking back-off shrinks the horizontal movement in {@code 0.05} steps while the entity could fall at least {@code maxUpStep}. */
    public static final double EDGE_BACKOFF_STEP = 0.05;
    /** {@code Player.canFallAtLeast}: the probe box is the body inset by {@code 1.0E-7} horizontally, extended {@code dist + 1.0E-7} below the feet. */
    public static final double EDGE_PROBE_INSET = 1.0E-7;
    /** {@code Player.canPlayerFitWithinBlocksAndEntitiesWhen}: the pose box is deflated by {@code 1.0E-7} before the no-collision test. */
    public static final double POSE_FIT_DEFLATE = 1.0E-7;
    /** {@code Player.tick}: X/Z are clamped to {@code +-2.9999999E7} at the end of every tick. */
    public static final double COORDINATE_CLAMP = 2.9999999E7;
    // ---- block effects
    /** {@code Entity.checkInsideBlocks}: the destination body box is deflated by {@code 1.0E-5F} (widened) before block-cell sampling; a movement longer than {@code 0.99999} (squared compare) uses the swept traversal for the "inside" flag. */
    public static final double INSIDE_SAMPLE_DEFLATE = 9.999999747378752E-6, INSIDE_LONG_MOVE = 0.99999;
    /** {@code BlockGetter.forEachBlockIntersectedBetween}: movements shorter than {@code 1.0E-5F} (squared) sample only the destination box. */
    public static final double INSIDE_TINY_MOVE_SQR = (double) (1.0E-5f * 1.0E-5f);
    /** {@code Entity.applyEffectsFromBlocks}: a new movement segment is recorded when the position moved by more than {@code 1.0E-5} (squared {@code 9.999999439624929E-11}). */
    public static final double MOVEMENT_SEGMENT_EPSILON_SQR = 9.999999439624929E-11;
    /** {@code AABB.collidedAlongVector}: candidate boxes are inflated by the half body size minus {@code 1.0E-7}; ray clips use {@code 1.0E-7} face tolerances. */
    public static final double COLLIDED_ALONG_EPSILON = 1.0E-7;
    /** {@code SlimeBlock.stepOn}: horizontal velocity multiplied by {@code 0.4 + |dy| * 0.2} when {@code |dy| < 0.1} and the entity is not stepping carefully. */
    public static final double SLIME_STEP_DY_LIMIT = 0.1, SLIME_STEP_BASE = 0.4, SLIME_STEP_SLOPE = 0.2;
    /** {@code WebBlock.entityInside}: {@code makeStuckInBlock(0.25, 0.05F, 0.25)}; with Weaving {@code (0.5, 0.25, 0.5)}. */
    public static final double WEB_STUCK_XZ = 0.25, WEB_STUCK_Y = 0.05000000074505806, WEB_WEAVING_XZ = 0.5, WEB_WEAVING_Y = 0.25;
    /** {@code SweetBerryBushBlock.entityInside}: {@code makeStuckInBlock(0.8F, 0.75, 0.8F)}. */
    public static final double BERRY_STUCK_XZ = 0.800000011920929, BERRY_STUCK_Y = 0.75;
    /** {@code PowderSnowBlock.entityInside}: {@code makeStuckInBlock(0.9F, 1.5, 0.9F)}. */
    public static final double POWDER_SNOW_STUCK_XZ = 0.8999999761581421, POWDER_SNOW_STUCK_Y = 1.5;
    /** {@code HoneyBlock}: sliding when not on ground, {@code y <= blockY + 0.9375 - 1.0E-7}, old dy {@code < -0.08}, horizontally within {@code 0.4375 + width/2 + 1.0E-7} of the block centre; slide sets old dy to {@code -0.05} (scaling x/z by {@code -0.05/oldDy} below {@code -0.13}); old dy = {@code dy / 0.98F + 0.08}, new dy = {@code (v - 0.08) * 0.98F}. */
    public static final double HONEY_TOP = 0.9375, HONEY_SLIDE_DY = -0.08, HONEY_FAST_DY = -0.13, HONEY_TARGET_DY = -0.05, HONEY_REACH = 0.4375, HONEY_DRAG = 0.9800000190734863, HONEY_GRAVITY = 0.08;
    /** {@code Entity.push(Entity)}: player-player pushing ignores separations below {@code 0.01F} (widened) and pushes {@code 0.05F} (widened) scaled by {@code min(1, 1/d)}. */
    public static final double PUSH_MIN_SEPARATION = 0.009999999776482582, PUSH_STRENGTH = 0.05000000074505806;
    /** {@code Entity.getBlockBounciness}: non-living entities take {@code 0.8F} of the block restitution; living entities the full value. */
    public static final float NON_LIVING_BOUNCE_FACTOR = 0.8f;
    /** {@code Entity.getFluidInteractionBox}: the body box deflated by {@code 0.001} decides fluid contact. */
    public static final double FLUID_BOX_DEFLATE = 0.001;
    // ---- attribute ids the movement code reads
    public static final String ATTR_MOVEMENT_SPEED = "minecraft:movement_speed", ATTR_GRAVITY = "minecraft:gravity", ATTR_JUMP_STRENGTH = "minecraft:jump_strength", ATTR_STEP_HEIGHT = "minecraft:step_height",
        ATTR_SNEAKING_SPEED = "minecraft:sneaking_speed", ATTR_MOVEMENT_EFFICIENCY = "minecraft:movement_efficiency", ATTR_AIR_DRAG_MODIFIER = "minecraft:air_drag_modifier", ATTR_FRICTION_MODIFIER = "minecraft:friction_modifier",
        ATTR_BOUNCINESS = "minecraft:bounciness", ATTR_WATER_MOVEMENT_EFFICIENCY = "minecraft:water_movement_efficiency", ATTR_SCALE = "minecraft:scale";
    public static final List<String> MOVEMENT_ATTRIBUTES = List.of(ATTR_MOVEMENT_SPEED, ATTR_GRAVITY, ATTR_JUMP_STRENGTH, ATTR_STEP_HEIGHT, ATTR_SNEAKING_SPEED, ATTR_MOVEMENT_EFFICIENCY, ATTR_AIR_DRAG_MODIFIER, ATTR_FRICTION_MODIFIER, ATTR_BOUNCINESS, ATTR_SCALE);
    public static final String EFFECT_SPEED = "minecraft:speed", EFFECT_SLOWNESS = "minecraft:slowness", EFFECT_JUMP_BOOST = "minecraft:jump_boost", EFFECT_LEVITATION = "minecraft:levitation",
        EFFECT_SLOW_FALLING = "minecraft:slow_falling", EFFECT_WEAVING = "minecraft:weaving", EFFECT_DOLPHINS_GRACE = "minecraft:dolphins_grace";
    /** Effects whose only movement influence is an attribute template in the table are handled generically; these are read by name in the step. */
    public static final Set<String> DIRECTLY_MODELLED_EFFECTS = Set.of(EFFECT_JUMP_BOOST, EFFECT_LEVITATION, EFFECT_SLOW_FALLING, EFFECT_WEAVING);
    /** Effects the profile knows to touch a movement mode Phase 5 does not simulate (fluids): their presence is UNSUPPORTED only inside that mode. */
    public static final Set<String> FLUID_MODE_EFFECTS = Set.of(EFFECT_DOLPHINS_GRACE);

    /** Vanilla {@code AttributeModifier.Operation} (ordinal order preserved). */
    public enum Operation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }
    /** An attribute's registry definition and the player's supplier default: {@code playerDefault} is the base a fresh player has; {@code min/max} are the RangedAttribute sanitize bounds. */
    public record AttributeDefinition(String id, double playerDefault, double min, double max, double registryDefault) {
        public AttributeDefinition { Objects.requireNonNull(id); }
        /** Vanilla {@code RangedAttribute.sanitizeValue}: NaN -> min, else clamp. */
        public double sanitize(double v) { return Double.isNaN(v) ? min : v < min ? min : Math.min(v, max); }
    }
    public record Modifier(String id, double amount, Operation operation) { public Modifier { Objects.requireNonNull(id); Objects.requireNonNull(operation); } }
    /** One mob effect's attribute template: {@code create(amplifier)} yields a modifier of {@code amount * (amplifier + 1)}. */
    public record EffectTemplate(String attribute, String modifierId, double amount, Operation operation) {
        public Modifier create(int amplifier) { return new Modifier(modifierId, amount * (double) (amplifier + 1), operation); }
    }
    public record Effect(String id, String category, List<EffectTemplate> templates) { public Effect { templates = List.copyOf(templates); } }

    /**
     * Vanilla {@code AttributeInstance.calculateValue}: base + all ADD_VALUE, then {@code d1 = d; d1 += d * m} for each
     * ADD_MULTIPLIED_BASE, then {@code d1 *= 1 + m} for each ADD_MULTIPLIED_TOTAL, then sanitize. Modifier ORDER within
     * a class follows the client's per-operation sets (hash order); only ADD_MULTIPLIED_TOTAL is order-sensitive in
     * floating point, so callers pass modifiers in the order the client holds them (packet order, sprint last).
     */
    public static double calculateValue(AttributeDefinition def, double base, List<Modifier> modifiers) {
        if (modifiers.isEmpty()) return def.sanitize(base);
        int adds = 0, bases = 0, totals = 0;
        for (Modifier m : modifiers) { switch (m.operation) { case ADD_VALUE -> adds++; case ADD_MULTIPLIED_BASE -> bases++; case ADD_MULTIPLIED_TOTAL -> totals++; } }
        double d = base;
        if (adds == 1) { for (Modifier m : modifiers) if (m.operation == Operation.ADD_VALUE) d += m.amount; } else if (adds > 1) for (Modifier m : clientIterationOrder(modifiers, Operation.ADD_VALUE)) d += m.amount;
        double d1 = d;
        if (bases == 1) { for (Modifier m : modifiers) if (m.operation == Operation.ADD_MULTIPLIED_BASE) d1 += d * m.amount; } else if (bases > 1) for (Modifier m : clientIterationOrder(modifiers, Operation.ADD_MULTIPLIED_BASE)) d1 += d * m.amount;
        if (totals == 1) { for (Modifier m : modifiers) if (m.operation == Operation.ADD_MULTIPLIED_TOTAL) d1 *= 1.0 + m.amount; } else if (totals > 1) for (Modifier m : clientIterationOrder(modifiers, Operation.ADD_MULTIPLIED_TOTAL)) d1 *= 1.0 + m.amount;
        return def.sanitize(d1);
    }
    /**
     * The per-operation modifier set of a vanilla {@code AttributeInstance} is a fastutil {@code Object2ObjectOpenHashMap}
     * (default size: 32 slots, linear probing on {@code HashCommon.mix(Identifier.hashCode()) & 31}) whose value iteration
     * runs from slot 31 down to 0. Reproducing that order (insert in the client's insertion order = packet order, then
     * iterate descending) keeps the ADD_MULTIPLIED_TOTAL product bit-identical when several such modifiers coexist
     * (e.g. Speed + sprinting). A duplicate id replaces the earlier entry in place, like {@code put}.
     */
    public static List<Modifier> clientIterationOrder(List<Modifier> inserted, Operation operation) {
        Modifier[] table = new Modifier[32]; int count = 0;
        for (Modifier m : inserted) {
            if (m.operation != operation) continue;
            int pos = mix(identifierHash(m.id)) & 31;
            while (table[pos] != null && !table[pos].id.equals(m.id)) pos = (pos + 1) & 31;
            if (table[pos] == null) count++;
            table[pos] = m;
            if (count > 24) throw new IllegalArgumentException("more than 24 modifiers of one operation: table growth is not modelled");
        }
        if (count == 0) return List.of();
        List<Modifier> out = new ArrayList<>(count);
        for (int pos = 31; pos >= 0; pos--) if (table[pos] != null) out.add(table[pos]);
        return out;
    }
    /** {@code Identifier.hashCode() = 31 * namespace.hashCode() + path.hashCode()} for a {@code namespace:path} id (a bare path means the {@code minecraft} namespace). */
    static int identifierHash(String id) { int i = id.indexOf(':'); String ns = i < 0 ? "minecraft" : id.substring(0, i), path = i < 0 ? id : id.substring(i + 1); return 31 * ns.hashCode() + path.hashCode(); }
    /** fastutil {@code HashCommon.mix(int)}. */
    static int mix(int x) { int h = x * 0x9E3779B9; return h ^ (h >>> 16); }

    public static final String PAPER_26_2_RESOURCE = "/me/nicolas/papea2ac/version/movement-profile-paper-26.2-124.tsv", PAPER_26_2_SIN_RESOURCE = "/me/nicolas/papea2ac/version/mth-sin-paper-26.2-124.bin";
    private static final class Holder { static final MovementProfile PROFILE = load("paper-26.2-124-movement", PAPER_26_2_RESOURCE, PAPER_26_2_SIN_RESOURCE, BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2); }
    public static MovementProfile paper26_2() { return Holder.PROFILE; }

    private final String id, digest, sineDigest; private final float[] sin;
    private final Map<String, AttributeDefinition> attributes; private final Modifier sprintModifier; private final Map<String, Effect> effects;
    private final BlockMovementTable blocks; private final CollisionProfile collision; private final List<float[]> conformance;

    private MovementProfile(String id, String digest, String sineDigest, float[] sin, Map<String, AttributeDefinition> attributes, Modifier sprintModifier, Map<String, Effect> effects, BlockMovementTable blocks, CollisionProfile collision, List<float[]> conformance) {
        this.id = id; this.digest = digest; this.sineDigest = sineDigest; this.sin = sin; this.attributes = attributes; this.sprintModifier = sprintModifier; this.effects = effects; this.blocks = blocks; this.collision = collision; this.conformance = conformance;
    }
    public String id() { return id; }
    /** SHA-256 of the profile resource bytes. */
    public String digest() { return digest; }
    /** SHA-256 of the sine table resource bytes (NUMERICAL_POLICY: table bits/digest belong in capsule metadata). */
    public String sineDigest() { return sineDigest; }
    public BlockMovementTable blocks() { return blocks; }
    public CollisionProfile collision() { return collision; }
    public AttributeDefinition attribute(String id) { return attributes.get(id); }
    public Collection<AttributeDefinition> attributes() { return attributes.values(); }
    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}: the client adds it locally while sprinting. */
    public Modifier sprintModifier() { return sprintModifier; }
    public Effect effect(String id) { return effects.get(id); }
    public Collection<Effect> effects() { return effects.values(); }
    /** Conformance vectors extracted with the table: {yawDegrees, sinBits, cosBits} (float bits stored as float values of the ints). */
    public List<float[]> sineConformance() { return conformance; }
    /** Vanilla {@code Mth.sin(double)}: table lookup, never host Math. */
    public float sin(double d) { return sin[(int) ((long) (d * SIN_SCALE) & SIN_MASK)]; }
    /** Vanilla {@code Mth.cos(double)}. */
    public float cos(double d) { return sin[(int) ((long) (d * SIN_SCALE + COS_OFFSET) & SIN_MASK)]; }
    public int sineTableLength() { return sin.length; }

    static MovementProfile load(String id, String resource, String sinResource, BlockMovementTable blocks, CollisionProfile collision) {
        try (InputStream in = MovementProfile.class.getResourceAsStream(resource); InputStream sinIn = MovementProfile.class.getResourceAsStream(sinResource)) {
            if (in == null) throw new IllegalStateException("movement profile resource missing: " + resource);
            if (sinIn == null) throw new IllegalStateException("sine table resource missing: " + sinResource);
            return parse(id, in.readAllBytes(), sinIn.readAllBytes(), blocks, collision);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    /** Parses profile bytes (tests and the memory harness); production uses {@link #paper26_2()}. */
    public static MovementProfile parse(String id, byte[] bytes, byte[] sinBytes, BlockMovementTable blocks, CollisionProfile collision) {
        String digest = sha256(bytes), sinDigest = sha256(sinBytes);
        Map<String, AttributeDefinition> attributes = new LinkedHashMap<>(); Map<String, Effect> effects = new LinkedHashMap<>(); Modifier sprint = null; String declaredSin = null; int declaredLen = -1;
        List<float[]> conformance = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(" ");
            switch (f[0]) {
                case "A" -> attributes.put(f[1], new AttributeDefinition(f[1], Double.parseDouble(f[2]), f[3].equals("-") ? -Double.MAX_VALUE : Double.parseDouble(f[3]), f[4].equals("-") ? Double.MAX_VALUE : Double.parseDouble(f[4]), Double.parseDouble(f[5])));
                case "M" -> { if (f[1].equals("sprinting")) sprint = new Modifier(f[2], Double.parseDouble(f[3]), Operation.valueOf(f[4])); }
                case "E" -> { List<EffectTemplate> t = new ArrayList<>(); for (int i = 3; i + 3 < f.length; i += 4) t.add(new EffectTemplate(f[i], f[i + 1], Double.parseDouble(f[i + 2]), Operation.valueOf(f[i + 3]))); effects.put(f[1], new Effect(f[1], f[2], t)); }
                case "T" -> { if (f[1].equals("mth-sin")) { declaredSin = f[2]; declaredLen = Integer.parseInt(f[3]); } }
                case "V" -> conformance.add(new float[] { Float.parseFloat(f[1]), Float.intBitsToFloat(Integer.parseInt(f[2])), Float.intBitsToFloat(Integer.parseInt(f[3])) });
                default -> throw new IllegalStateException("unknown record: " + line);
            }
        }
        if (sprint == null) throw new IllegalStateException("sprint modifier missing");
        if (!sinDigest.equals(declaredSin) || sinBytes.length != declaredLen * 4 || declaredLen != 65536) throw new IllegalStateException("sine table does not match the profile declaration (INCOMPATIBLE_NUMERIC_ENVIRONMENT): " + sinDigest + " vs " + declaredSin);
        float[] sin = new float[declaredLen]; ByteBuffer bb = ByteBuffer.wrap(sinBytes).order(ByteOrder.BIG_ENDIAN); for (int i = 0; i < sin.length; i++) sin[i] = bb.getFloat();
        for (String a : MOVEMENT_ATTRIBUTES) if (!attributes.containsKey(a)) throw new IllegalStateException("movement attribute missing from profile: " + a);
        MovementProfile p = new MovementProfile(id, digest, sinDigest, sin, Collections.unmodifiableMap(attributes), sprint, Collections.unmodifiableMap(effects), Objects.requireNonNull(blocks), Objects.requireNonNull(collision), Collections.unmodifiableList(conformance));
        for (float[] v : conformance) { double r = v[0] * DEG_TO_RAD; if (Float.floatToRawIntBits(p.sin(r)) != Float.floatToRawIntBits(v[1]) || Float.floatToRawIntBits(p.cos(r)) != Float.floatToRawIntBits(v[2])) throw new IllegalStateException("sine conformance vector failed on this host (INCOMPATIBLE_NUMERIC_ENVIRONMENT): yaw " + v[0]); }
        return p;
    }
    static String sha256(byte[] bytes) {
        try { var sb = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", b)); return sb.toString(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
