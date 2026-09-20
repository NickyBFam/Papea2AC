package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.*;
import me.nicolas.papea2ac.version.*;

/** Shape coverage gate (task §16/§67): the committed inventory equals the one regenerated from the loaded table, every block is classified, and there is no unexplained UNSUPPORTED collision-relevant state. */
class ShapeCoverageInventoryTest {
    @Test void committedInventoryMatchesTheLoadedTableAndHasZeroUnexplainedUnsupported() throws Exception {
        var t = CollisionProfile.PAPER_26_2.table(); var root = TimelineFixtureDeterminismTest.repoRoot();
        assertEquals(ShapeCoverageMain.tsv(t), Files.readString(root.resolve("docs/phase4-artifacts/shape-coverage.tsv")).replace("\r\n", "\n"), "regenerate with :anticheat-testing:shapeCoverage and review the diff");
        assertEquals(ShapeCoverageMain.json(t), Files.readString(root.resolve("docs/phase4-artifacts/shape-coverage.json")).replace("\r\n", "\n"));
        var counts = new EnumMap<ShapeCoverageMain.Classification, Integer>(ShapeCoverageMain.Classification.class); int states = 0;
        for (var b : t.blocks()) { counts.merge(ShapeCoverageMain.classify(t, b), 1, Integer::sum); states += b.stateCount(); }
        assertEquals(1196, t.blockCount()); assertEquals(32366, states);
        assertEquals(0, counts.getOrDefault(ShapeCoverageMain.Classification.UNSUPPORTED, 0), "no unexplained unsupported collision-relevant block");
        assertEquals(3, counts.get(ShapeCoverageMain.Classification.POSITION_DERIVED_EXACT)); assertEquals(1, counts.get(ShapeCoverageMain.Classification.CONTEXT_DERIVED_EXACT)); assertEquals(19, counts.get(ShapeCoverageMain.Classification.CONSERVATIVE_UNCERTAIN_BY_SPEC), "powder snow + 17 shulker boxes + moving piston");
        assertNull(counts.get(ShapeCoverageMain.Classification.NEIGHBOR_DERIVED_EXACT), "no neighbour-derived family exists in this profile (connections are state properties)");
        assertTrue(counts.get(ShapeCoverageMain.Classification.STATE_DERIVED_EXACT) > 150, "state-derived families: " + counts.get(ShapeCoverageMain.Classification.STATE_DERIVED_EXACT));
        assertTrue(counts.get(ShapeCoverageMain.Classification.FULL_CUBE) > 400 && counts.get(ShapeCoverageMain.Classification.EMPTY_NON_COLLIDING) > 200);
        int sum = 0; for (int c : counts.values()) sum += c; assertEquals(1196, sum, "every block classified exactly once");
    }
    @Test void everyRequiredFamilyOfTheTaskIsPresentInTheProfileAndNotAGenericCube() {
        var t = CollisionProfile.PAPER_26_2.table(); int full = t.namedShape("full_cube");
        for (String family : List.of("minecraft:oak_slab", "minecraft:oak_stairs", "minecraft:oak_fence", "minecraft:cobblestone_wall", "minecraft:oak_fence_gate", "minecraft:oak_door", "minecraft:oak_trapdoor", "minecraft:ladder", "minecraft:white_carpet", "minecraft:snow", "minecraft:red_bed", "minecraft:chest", "minecraft:anvil", "minecraft:cactus", "minecraft:cake", "minecraft:farmland", "minecraft:dirt_path", "minecraft:hopper", "minecraft:cauldron", "minecraft:scaffolding", "minecraft:powder_snow", "minecraft:shulker_box", "minecraft:piston", "minecraft:piston_head", "minecraft:moving_piston", "minecraft:glass_pane", "minecraft:iron_bars", "minecraft:soul_sand", "minecraft:honey_block", "minecraft:enchanting_table", "minecraft:end_portal_frame", "minecraft:bamboo", "minecraft:pointed_dripstone", "minecraft:lily_pad", "minecraft:composter", "minecraft:candle", "minecraft:lantern", "minecraft:iron_chain", "minecraft:bell", "minecraft:grindstone", "minecraft:stonecutter", "minecraft:lectern", "minecraft:campfire", "minecraft:brewing_stand", "minecraft:daylight_detector", "minecraft:sea_pickle", "minecraft:turtle_egg", "minecraft:big_dripleaf", "minecraft:azalea", "minecraft:sculk_sensor", "minecraft:mud", "minecraft:heavy_core", "minecraft:decorated_pot", "minecraft:sniffer_egg")) {
            var b = t.block(family); assertNotNull(b, family + " missing from the pinned profile");
            boolean allFull = true; for (int s = 0; s < b.stateCount(); s++) if (b.shapeIndex(s) != full) allFull = false;
            assertFalse(allFull && b.kind() == BlockShapeTable.Kind.STATIC, family + " must not be a generic full cube");
        }
    }
}
