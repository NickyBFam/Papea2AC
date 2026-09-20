package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.MovementProfile;
import org.junit.jupiter.api.Test;

/** Gate: the movement property inventory has zero unexplained gaps, every effect/attribute the simulator reads exists in the profile, and the committed inventory matches the code. */
class MovementPropertyCoverageTest {
    @Test void everyBlockIsClassifiedAndNothingIsUnsupported() {
        List<MovementPropertyCoverageMain.Row> rows = MovementPropertyCoverageMain.inventory();
        assertEquals(BlockMovementTable.paper26_2().blockCount(), rows.size());
        List<String> gaps = rows.stream().filter(r -> r.primary().equals("UNSUPPORTED")).map(MovementPropertyCoverageMain.Row::block).toList();
        assertTrue(gaps.isEmpty(), "unexplained movement gaps: " + gaps);
        Map<String, Long> counts = new HashMap<>(); for (var r : rows) counts.merge(r.primary(), 1L, Long::sum);
        assertEquals(3L, counts.get("FLUID_DEFERRED"), "water, lava, bubble column"); assertEquals(3L, counts.get("TRANSPORT_UNSUPPORTED"), "nether portal, end portal, end gateway");
        assertEquals(4L, counts.get("INSIDE_EFFECT"), "cobweb, sweet berry bush, powder snow, honey");
        assertTrue(counts.get("BOUNCE") >= 17, "slime + 16 beds"); assertTrue(counts.get("CLIMBABLE") >= 7); assertTrue(counts.get("DEFAULT") > 900);
        for (var r : rows) if (r.primary().equals("FRICTION_SPECIAL")) assertTrue(r.block().contains("ice") || r.block().equals("minecraft:slime_block"), r.block());
    }
    @Test void committedInventoryMatchesTheCode() throws Exception {
        Path tsv = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase5-artifacts/movement-property-coverage.tsv");
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        List<MovementPropertyCoverageMain.Row> rows = MovementPropertyCoverageMain.inventory();
        assertEquals(rows.size() + 1, lines.size());
        for (int i = 0; i < rows.size(); i++) { String[] f = lines.get(i + 1).split("\t"); assertEquals(rows.get(i).block(), f[0]); assertEquals(rows.get(i).primary(), f[1], f[0]); assertEquals(String.join(",", rows.get(i).flags()), f[2], f[0]); }
    }
    @Test void profileCarriesEveryEffectAndAttributeTheStepReads() {
        MovementProfile p = MovementProfile.paper26_2();
        for (String id : MovementProfile.MOVEMENT_ATTRIBUTES) assertNotNull(p.attribute(id), id);
        for (String id : List.of(MovementProfile.EFFECT_SPEED, MovementProfile.EFFECT_SLOWNESS, MovementProfile.EFFECT_JUMP_BOOST, MovementProfile.EFFECT_LEVITATION, MovementProfile.EFFECT_SLOW_FALLING, MovementProfile.EFFECT_WEAVING, MovementProfile.EFFECT_DOLPHINS_GRACE)) assertNotNull(p.effect(id), id);
        assertEquals(0.20000000298023224, p.effect(MovementProfile.EFFECT_SPEED).templates().get(0).amount()); assertEquals(-0.15000000596046448, p.effect(MovementProfile.EFFECT_SLOWNESS).templates().get(0).amount());
        assertEquals(40, p.effects().size()); assertEquals("minecraft:sprinting", p.sprintModifier().id()); assertEquals(MovementProfile.Operation.ADD_MULTIPLIED_TOTAL, p.sprintModifier().operation());
        assertEquals(0.10000000149011612, p.attribute(MovementProfile.ATTR_MOVEMENT_SPEED).playerDefault()); assertEquals(0.41999998688697815, p.attribute(MovementProfile.ATTR_JUMP_STRENGTH).playerDefault()); assertEquals(0.6, p.attribute(MovementProfile.ATTR_STEP_HEIGHT).playerDefault());
    }
}
