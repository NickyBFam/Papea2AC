package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import org.junit.jupiter.api.Test;
/** Offline reproductions of two Phase 8 real-client observations through the live runner: a walk from honey onto soul sand (live: one UNCERTAIN / SEARCH_LIMIT step at that boundary, not reproduced here - every step VALID) and a rest one block from water (live: no rest anchor for two minutes; here the rest fixed point anchors). Both stay in the suite as regression guards. */
class ValidationLiveReproTest {
    @Test void walkingFromHoneyOntoSoulSandStaysValidThroughTheLiveRunner() {
        PhysicsScene ps = new PhysicsScene();
        for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) ps.set(x, 69, z, HONEY);
        for (int x = 11; x <= 20; x++) for (int z = 6; z <= 10; z++) ps.set(x, 69, z, SOUL_SAND);
        MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps); h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        double y = 69.0 + 15.0 / 16.0; // honey is 15/16 high
        MovementValidationRunnerTest.correction(h, 1, 8.5, y, 8.5, -90f); h.feed();
        assertTrue(h.status().anchored(), h.status().anchorKind());
        PhysicsState rest = settled(ps, 8.5, y, 8.5).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 25; i++) in.add(MovementInput.idle(-90f)); for (int i = 0; i < 40; i++) in.add(MovementInput.forward(-90f, i > 20));
        simulate(ps, ps.env(), rest, in, List.of(), h.rec, true, false); h.feed();
        ValidationStatus st = h.status();
        assertEquals(0, st.invalid(), String.join("\n", st.recent()));
        assertEquals(st.transitions(), st.valid(), String.join("\n", st.notable()));
    }
    @Test void aRestOneBlockFromWaterAnchorsTheChain() {
        PhysicsScene ps = new PhysicsScene();
        for (int x = 7; x <= 9; x++) for (int z = 10; z <= 12; z++) ps.set(x, 69, z, WATER);
        MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps); h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        h.rec.tickEnd(); h.rec.advance(TICK_NANOS); h.rec.movement(8.5, SURFACE, 9.06, -90f, true);
        PhysicsState rest = settled(ps, 8.5, SURFACE, 9.06);
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 20; i++) idle.add(MovementInput.idle(-90f));
        simulate(ps, ps.env(), rest, idle, List.of(), h.rec, true); h.feed();
        assertTrue(h.status().anchored(), h.status().anchorKind()); assertTrue(h.status().anchorKind().startsWith("rest"), h.status().anchorKind());
    }
}
