package me.nicolas.papea2ac.version;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Version-pinned vanilla block MOVEMENT properties (WORLD_MODEL.md W3: "version provides pure definitions, physics
 * applies them, collision owns geometry"; COLLISION_ENGINE.md: "Slime bounce, honey wall-slide, ice friction, soul sand
 * slowdown, bed bounce, web/berry drag, bubble columns and fluid flow need property fixtures in addition to shape
 * fixtures"). Loaded once from the generated, reviewable resource produced by the controlled nativeTest-only extraction
 * ({@code MovementPropertyExtractMain}) from the pinned Paper 26.2 build 124 server: for every registered block the four
 * {@code Block} property getters ({@code getFriction / getSpeedFactor / getJumpFactor / getBounceRestitution}), the tag
 * memberships the movement code tests ({@code CLIMBABLE, FENCES, WALLS, FENCE_GATES, SUPPRESSES_BOUNCE,
 * CAN_GLIDE_THROUGH}), the {@code FenceGateBlock} instanceof marker, and the declaring class of every movement-relevant
 * override ({@code entityInside, stepOn, fallOn, getEntityInsideCollisionShape}); plus every block state with a
 * non-empty fluid state (type, amount, source, own height).
 *
 * The override classes are classified HERE, in the version module, into the behaviour vocabulary the physics module
 * implements ({@link InsideEffect}, {@link StepOn}). A class the profile does not name is {@link InsideEffect#UNSUPPORTED}
 * / {@link StepOn#UNSUPPORTED}: physics then reports UNSUPPORTED_BLOCK_BEHAVIOUR instead of silently ignoring a
 * behaviour it cannot model, and the movement-property coverage inventory gate fails on any unexplained entry.
 * Immutable and safely publishable; one shared instance per profile ({@link #paper26_2()}).
 */
public final class BlockMovementTable {
    /** What a block's {@code entityInside} override does to a player's motion (vanilla 26.2 class inventory). */
    public enum InsideEffect {
        /** Not overridden: no inside effect. */ NONE,
        /** {@code WebBlock}: {@code makeStuckInBlock(0.25, 0.05F, 0.25)} (or (0.5, 0.25, 0.5) with the Weaving effect). */ WEB,
        /** {@code SweetBerryBushBlock}: {@code makeStuckInBlock(0.8F, 0.75, 0.8F)} for living entities other than foxes/bees. */ SWEET_BERRY_BUSH,
        /** {@code PowderSnowBlock}: {@code makeStuckInBlock(0.9F, 1.5, 0.9F)} when the entity's own block position is powder snow, plus the FREEZE inside effect ({@code isInPowderSnow = true}). */ POWDER_SNOW,
        /** {@code HoneyBlock}: wall slide ({@code isSlidingDown} / {@code doSlideMovement}). */ HONEY_SLIDE,
        /** {@code BubbleColumnBlock}: bubble column drag/lift - a fluid-family behaviour, explicitly deferred with fluids in Phase 5. */ BUBBLE_COLUMN,
        /** {@code EndPortalBlock / NetherPortalBlock / EndGatewayBlock}: dimension transport; never simulated (world-epoch change). */ PORTAL,
        /** Overridden, but the override never touches position, velocity, stuck multiplier or pose (damage, sounds, block state changes, redstone, item pickup). */ NON_MOTION,
        /** An override class this profile does not classify: physics must not guess. */ UNSUPPORTED
    }
    /** What a block's {@code stepOn} override does to a player's motion. */
    public enum StepOn {
        NONE,
        /** {@code SlimeBlock.stepOn}: horizontal velocity multiplied by {@code 0.4 + |dy| * 0.2} when {@code |dy| < 0.1} and not stepping carefully. */ SLIME_SLOWDOWN,
        /** Overridden without motion effect (magma damage, redstone ore light, sculk vibration, turtle egg trampling). */ NON_MOTION,
        UNSUPPORTED
    }
    private static final Map<String, InsideEffect> INSIDE_BY_CLASS = Map.ofEntries(
        Map.entry("BlockBehaviour", InsideEffect.NONE),
        Map.entry("WebBlock", InsideEffect.WEB), Map.entry("SweetBerryBushBlock", InsideEffect.SWEET_BERRY_BUSH), Map.entry("PowderSnowBlock", InsideEffect.POWDER_SNOW),
        Map.entry("HoneyBlock", InsideEffect.HONEY_SLIDE), Map.entry("BubbleColumnBlock", InsideEffect.BUBBLE_COLUMN),
        Map.entry("EndPortalBlock", InsideEffect.PORTAL), Map.entry("NetherPortalBlock", InsideEffect.PORTAL), Map.entry("EndGatewayBlock", InsideEffect.PORTAL),
        Map.entry("BaseFireBlock", InsideEffect.NON_MOTION), Map.entry("BasePressurePlateBlock", InsideEffect.NON_MOTION), Map.entry("BigDripleafBlock", InsideEffect.NON_MOTION),
        Map.entry("ButtonBlock", InsideEffect.NON_MOTION), Map.entry("CactusBlock", InsideEffect.NON_MOTION), Map.entry("CampfireBlock", InsideEffect.NON_MOTION),
        Map.entry("CropBlock", InsideEffect.NON_MOTION), Map.entry("DetectorRailBlock", InsideEffect.NON_MOTION), Map.entry("EyeblossomBlock", InsideEffect.NON_MOTION),
        Map.entry("FrogspawnBlock", InsideEffect.NON_MOTION), Map.entry("HopperBlock", InsideEffect.NON_MOTION), Map.entry("LavaCauldronBlock", InsideEffect.NON_MOTION),
        Map.entry("LayeredCauldronBlock", InsideEffect.NON_MOTION), Map.entry("LilyPadBlock", InsideEffect.NON_MOTION), Map.entry("PitcherCropBlock", InsideEffect.NON_MOTION),
        Map.entry("TripWireBlock", InsideEffect.NON_MOTION), Map.entry("WitherRoseBlock", InsideEffect.NON_MOTION));
    private static final Map<String, StepOn> STEP_BY_CLASS = Map.of(
        "Block", StepOn.NONE, "SlimeBlock", StepOn.SLIME_SLOWDOWN,
        "MagmaBlock", StepOn.NON_MOTION, "RedStoneOreBlock", StepOn.NON_MOTION, "SculkSensorBlock", StepOn.NON_MOTION, "SculkShriekerBlock", StepOn.NON_MOTION, "TurtleEggBlock", StepOn.NON_MOTION);
    /** {@code fallOn} overrides known to leave motion alone (fall damage, sounds, block changes); bounce itself is {@code getBounceRestitution}, not {@code fallOn}, in 26.2. */
    private static final Set<String> FALL_ON_NON_MOTION = Set.of("Block", "BedBlock", "FarmlandBlock", "HayBlock", "HoneyBlock", "PointedDripstoneBlock", "PowderSnowBlock", "SlimeBlock", "TurtleEggBlock");

    /** One block's movement properties; float fields hold the exact vanilla float bits. */
    public record Block(String name, float friction, float speedFactor, float jumpFactor, float bounceRestitution,
                        boolean air, boolean climbable, boolean fence, boolean wall, boolean fenceGateTag, boolean fenceGateBlock, boolean suppressesBounce, boolean canGlideThrough, boolean fallDamageResetting,
                        InsideEffect insideEffect, StepOn stepOn, boolean fallOnKnown, boolean insideShapeOverride,
                        String insideClass, String stepOnClass, String fallOnClass, String insideShapeClass) {
        public Block { Objects.requireNonNull(name); Objects.requireNonNull(insideEffect); Objects.requireNonNull(stepOn); }
        /** Everything the physics module needs about this block is classified (no UNSUPPORTED family). */
        public boolean fullyClassified() { return insideEffect != InsideEffect.UNSUPPORTED && stepOn != StepOn.UNSUPPORTED && fallOnKnown; }
        /** Vanilla {@code getOnPos}: fences, walls and fence gates keep the supporting block itself as the "block below" for offsets up to 0.5. */
        public boolean keepsSupportingPosForSmallOffset() { return fence || wall || fenceGateBlock; }
    }
    /** A block state's fluid state (vanilla {@code FluidState}): registry id, level amount (1-8), source flag, {@code getOwnHeight()} (= amount / 9f). */
    public record Fluid(String fluid, int amount, boolean source, float ownHeight) {
        public Fluid { Objects.requireNonNull(fluid); }
        public boolean water() { return fluid.equals("minecraft:water") || fluid.equals("minecraft:flowing_water"); }
        public boolean lava() { return fluid.equals("minecraft:lava") || fluid.equals("minecraft:flowing_lava"); }
        /** Vanilla {@code Fluid.isSame}: flowing and still variants are the same fluid. */
        public boolean sameFluid(Fluid other) { return other != null && (water() ? other.water() : lava() ? other.lava() : fluid.equals(other.fluid)); }
    }

    public static final String PAPER_26_2_RESOURCE = "/me/nicolas/papea2ac/version/block-movement-paper-26.2-124.tsv";
    private static final class Holder { static final BlockMovementTable TABLE = loadResource(PAPER_26_2_RESOURCE); }
    /** The pinned profile's table; loading failure surfaces as an ExceptionInInitializerError to the composition root. */
    public static BlockMovementTable paper26_2() { return Holder.TABLE; }

    private final String profile, digest; private final int bytes;
    private final Map<String, Block> blocks; private final Map<String, Fluid[]> fluids; private final int fluidStateCount;

    private BlockMovementTable(String profile, String digest, int bytes, Map<String, Block> blocks, Map<String, Fluid[]> fluids, int fluidStateCount) {
        this.profile = profile; this.digest = digest; this.bytes = bytes; this.blocks = blocks; this.fluids = fluids; this.fluidStateCount = fluidStateCount;
    }
    public String profile() { return profile; }
    /** SHA-256 of the exact resource bytes: the table identity recorded in evidence/replay metadata. */
    public String digest() { return digest; }
    public int resourceBytes() { return bytes; }
    public int blockCount() { return blocks.size(); }
    public int fluidStateCount() { return fluidStateCount; }
    /** Null = block unknown to the profile (UNSUPPORTED; never a default friction). */
    public Block block(String name) { return blocks.get(name); }
    public Collection<Block> blocks() { return blocks.values(); }
    /** Fluid state of a block's registry state index (the same mixed-radix index {@link BlockShapeTable.Block#stateIndex} yields); null = no fluid. */
    public Fluid fluid(String block, int stateIndex) { Fluid[] f = fluids.get(block); return f == null || stateIndex < 0 || stateIndex >= f.length ? null : f[stateIndex]; }
    /** True when some state of the block carries a fluid (water/lava blocks, waterloggable blocks, kelp, bubble columns...). */
    public boolean mayHaveFluid(String block) { return fluids.containsKey(block); }

    static BlockMovementTable loadResource(String resource) {
        try (InputStream in = BlockMovementTable.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("block movement table resource missing: " + resource);
            return parse(in.readAllBytes());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    /** Parses table bytes (tests and the memory harness measure a fresh parse); production uses {@link #paper26_2()}. */
    public static BlockMovementTable parse(byte[] bytes) {
        String digest;
        try { var sb = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", b)); digest = sb.toString(); } catch (Exception e) { throw new IllegalStateException(e); }
        Map<String, Block> blocks = new LinkedHashMap<>(); Map<String, List<int[]>> fluidIdx = new HashMap<>(); Map<String, List<Fluid>> fluidVals = new HashMap<>();
        String profile = null; int fluidStates = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) { if (profile == null && line.contains("profile ")) { int i = line.indexOf("profile ") + 8; int j = line.indexOf(',', i); profile = line.substring(i, j < 0 ? line.length() : j).trim(); } continue; }
            String[] f = line.split(" ");
            switch (f[0]) {
                case "B" -> {
                    if (f.length != 15) throw new IllegalStateException("bad block line: " + line);
                    Set<String> tags = f[6].equals("-") ? Set.of() : Set.of(f[6].split(","));
                    String insideClass = f[7], stepClass = f[8], fallClass = f[9], shapeClass = f[10];
                    InsideEffect inside = INSIDE_BY_CLASS.getOrDefault(insideClass, InsideEffect.UNSUPPORTED);
                    StepOn step = STEP_BY_CLASS.getOrDefault(stepClass, StepOn.UNSUPPORTED);
                    blocks.put(f[1], new Block(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]),
                        tags.contains("air"), tags.contains("climbable"), tags.contains("fences"), tags.contains("walls"), tags.contains("fence_gates"), tags.contains("fence_gate_block"), tags.contains("suppresses_bounce"), tags.contains("can_glide_through"), tags.contains("fall_damage_resetting"),
                        inside, step, FALL_ON_NON_MOTION.contains(fallClass), !shapeClass.equals("BlockBehaviour"), insideClass, stepClass, fallClass, shapeClass));
                }
                case "F" -> {
                    if (f.length != 7) throw new IllegalStateException("bad fluid line: " + line);
                    fluidIdx.computeIfAbsent(f[1], k -> new ArrayList<>()).add(new int[] { Integer.parseInt(f[2]) });
                    fluidVals.computeIfAbsent(f[1], k -> new ArrayList<>()).add(new Fluid(f[3], Integer.parseInt(f[4]), Boolean.parseBoolean(f[5]), Float.parseFloat(f[6])));
                    fluidStates++;
                }
                default -> throw new IllegalStateException("unknown record: " + line);
            }
        }
        if (profile == null) throw new IllegalStateException("profile header missing");
        Map<String, Fluid[]> fluids = new HashMap<>();
        for (var e : fluidIdx.entrySet()) {
            int max = 0; for (int[] i : e.getValue()) max = Math.max(max, i[0]);
            Fluid[] arr = new Fluid[max + 1]; List<Fluid> vals = fluidVals.get(e.getKey());
            for (int k = 0; k < vals.size(); k++) arr[e.getValue().get(k)[0]] = vals.get(k);
            fluids.put(e.getKey(), arr);
        }
        return new BlockMovementTable(profile, digest, bytes.length, Collections.unmodifiableMap(blocks), Collections.unmodifiableMap(fluids), fluidStates);
    }
}
