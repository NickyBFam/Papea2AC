package me.nicolas.papea2ac.testing;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.BlockShapeTable;
import me.nicolas.papea2ac.version.CollisionProfile;

/**
 * Phase 5 machine-verifiable MOVEMENT PROPERTY coverage inventory (PHASE_5_IMPLEMENTATION.md "MOVEMENT FACTORS" /
 * "ACCEPTANCE MATRIX"): every block of the pinned profile classified by the movement behaviour the simulator applies
 * to it, with every flag it carries, so that no block silently falls into "default friction". Categories (primary, in
 * priority order) and what the simulator does with them:
 * <ul>
 * <li>UNSUPPORTED - an override class the version table does not classify (must be zero: the gate);</li>
 * <li>TRANSPORT_UNSUPPORTED - portals/gateways: contact is reported UNSUPPORTED_BLOCK_BEHAVIOUR (dimension change);</li>
 * <li>FLUID_DEFERRED - water / lava / bubble column: contact is detected and reported UNSUPPORTED_FLUID (explicit Phase 5 deferral);</li>
 * <li>INSIDE_EFFECT - cobweb, sweet berry bush, powder snow, honey slide: implemented and natively differential-tested;</li>
 * <li>BOUNCE - restitution > 0 (slime, beds): implemented through {@code restituteMovementAfterCollisions};</li>
 * <li>STEP_ON_MOTION - slime {@code stepOn} slowdown: implemented;</li>
 * <li>FRICTION_SPECIAL / SPEED_FACTOR / JUMP_FACTOR - ice family, slime, soul sand, honey: implemented through the block getters;</li>
 * <li>CLIMBABLE - ladders, vines, scaffolding, ...: implemented ({@code handleOnClimbable});</li>
 * <li>CONTEXT_DEPENDENT - shapes that depend on the entity (powder snow, scaffolding): implemented through the Phase 4 context;</li>
 * <li>NON_MOTION_OVERRIDE - an {@code entityInside/stepOn/fallOn} override that never touches motion (damage, redstone, sounds);</li>
 * <li>DEFAULT - friction 0.6, factors 1, no overrides, no fluid: ordinary vanilla block.</li>
 * </ul>
 * Flags: WATERLOGGABLE (some states carry water: fluid contact detection applies to those states), plus each matched
 * category. Output: {@code movement-property-coverage.tsv} (one row per block) and {@code .json} (counts + explanations).
 * Usage: {@code MovementPropertyCoverageMain <artifacts-dir>}
 */
public final class MovementPropertyCoverageMain {
    static final List<String> ORDER = List.of("UNSUPPORTED", "TRANSPORT_UNSUPPORTED", "FLUID_DEFERRED", "INSIDE_EFFECT", "BOUNCE", "STEP_ON_MOTION", "FRICTION_SPECIAL", "SPEED_FACTOR", "JUMP_FACTOR", "CLIMBABLE", "CONTEXT_DEPENDENT", "NON_MOTION_OVERRIDE", "DEFAULT");
    record Row(String block, String primary, List<String> flags, float friction, float speed, float jump, float bounce, String insideEffect, String stepOn, String note) {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir);
        List<Row> rows = inventory();
        StringBuilder tsv = new StringBuilder("block\tprimary\tflags\tfriction\tspeedFactor\tjumpFactor\tbounceRestitution\tentityInside\tstepOn\tnote\n");
        for (Row r : rows) tsv.append(r.block).append('\t').append(r.primary).append('\t').append(String.join(",", r.flags)).append('\t').append(r.friction).append('\t').append(r.speed).append('\t').append(r.jump).append('\t').append(r.bounce).append('\t').append(r.insideEffect).append('\t').append(r.stepOn).append('\t').append(r.note).append('\n');
        Files.writeString(dir.resolve("movement-property-coverage.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("profile", BlockMovementTable.paper26_2().profile()); json.put("blockTableSha256", BlockMovementTable.paper26_2().digest()); json.put("blocks", rows.size());
        Map<String, Object> counts = new LinkedHashMap<>(); for (String c : ORDER) counts.put(c, rows.stream().filter(r -> r.primary.equals(c)).count()); json.put("primaryCounts", counts);
        Map<String, Object> flagCounts = new TreeMap<>(); for (Row r : rows) for (String f : r.flags) flagCounts.merge(f, 1L, (a, b) -> (Long) a + (Long) b); json.put("flagCounts", flagCounts);
        json.put("unexplainedGaps", counts.get("UNSUPPORTED"));
        json.put("explicitDeferrals", Map.of("FLUID_DEFERRED", "water, lava and bubble columns: fluid contact is detected from observed fluid states (waterlogged states included) and reported UNSUPPORTED_FLUID; fluid locomotion, flow pushing and bubble drag are Phase 5 deferrals (PHASE_5_IMPLEMENTATION.md FLUIDS)",
            "TRANSPORT_UNSUPPORTED", "nether portal, end portal, end gateway: contact is reported UNSUPPORTED_BLOCK_BEHAVIOUR; a dimension change is a world-epoch discontinuity, never simulated"));
        Files.writeString(dir.resolve("movement-property-coverage.json"), MiniJson.write(json) + "\n", StandardCharsets.UTF_8);
        System.out.println("movement property coverage: " + counts);
    }
    static List<Row> inventory() {
        BlockMovementTable table = BlockMovementTable.paper26_2(); BlockShapeTable shapes = CollisionProfile.PAPER_26_2.table();
        List<Row> rows = new ArrayList<>();
        for (BlockMovementTable.Block b : table.blocks()) {
            List<String> flags = new ArrayList<>(); String note = "";
            if (!b.fullyClassified()) { flags.add("UNSUPPORTED"); note = "unclassified override: " + b.insideClass() + "/" + b.stepOnClass() + "/" + b.fallOnClass(); }
            if (b.insideEffect() == BlockMovementTable.InsideEffect.PORTAL) flags.add("TRANSPORT_UNSUPPORTED");
            if (b.name().equals("minecraft:water") || b.name().equals("minecraft:lava") || b.insideEffect() == BlockMovementTable.InsideEffect.BUBBLE_COLUMN) flags.add("FLUID_DEFERRED");
            if (b.insideEffect() == BlockMovementTable.InsideEffect.WEB || b.insideEffect() == BlockMovementTable.InsideEffect.SWEET_BERRY_BUSH || b.insideEffect() == BlockMovementTable.InsideEffect.POWDER_SNOW || b.insideEffect() == BlockMovementTable.InsideEffect.HONEY_SLIDE) flags.add("INSIDE_EFFECT");
            if (b.bounceRestitution() > 0f) flags.add("BOUNCE");
            if (b.stepOn() == BlockMovementTable.StepOn.SLIME_SLOWDOWN) flags.add("STEP_ON_MOTION");
            if (b.friction() != 0.6f) flags.add("FRICTION_SPECIAL");
            if (b.speedFactor() != 1.0f) flags.add("SPEED_FACTOR");
            if (b.jumpFactor() != 1.0f) flags.add("JUMP_FACTOR");
            if (b.climbable()) flags.add("CLIMBABLE");
            BlockShapeTable.Block sb = shapes.block(b.name());
            if (sb != null && (sb.kind() == BlockShapeTable.Kind.CONTEXT || sb.kind() == BlockShapeTable.Kind.ENTITY_CONTEXT)) flags.add("CONTEXT_DEPENDENT");
            if (b.insideEffect() == BlockMovementTable.InsideEffect.NON_MOTION || b.stepOn() == BlockMovementTable.StepOn.NON_MOTION || !b.fallOnClass().equals("Block")) flags.add("NON_MOTION_OVERRIDE");
            if (table.mayHaveFluid(b.name()) && !flags.contains("FLUID_DEFERRED")) flags.add("WATERLOGGABLE");
            String primary = "DEFAULT"; for (String c : ORDER) if (flags.contains(c)) { primary = c; break; }
            if (primary.equals("DEFAULT")) flags.add("DEFAULT");
            rows.add(new Row(b.name(), primary, flags, b.friction(), b.speedFactor(), b.jumpFactor(), b.bounceRestitution(), b.insideEffect().name(), b.stepOn().name(), note));
        }
        rows.sort(Comparator.comparing(Row::block));
        return rows;
    }
}
