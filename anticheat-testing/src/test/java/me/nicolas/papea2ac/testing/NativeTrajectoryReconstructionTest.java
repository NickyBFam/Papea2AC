package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The hard Phase 6 gate (§50): over every tick of every pinned native walking trajectory, the declared input is never excluded. */
class NativeTrajectoryReconstructionTest {
    static final Path FIXTURES = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase9-artifacts/physics-fixtures/native-trajectories.json");
    @Test void declaredInputIsNeverExcludedOnAnyNativeTick() throws Exception {
        NativeReconstructionGate.Summary s = NativeReconstructionGate.run(FIXTURES);
        System.out.println("native reconstruction: scenarios=" + s.scenarios() + " ticks=" + s.ticksChecked() + " included=" + s.declaredIncluded() + " witness=" + s.declaredWitness() + " unique=" + s.unique() + " equivalentInputs=" + s.equivalentInputs() + " distinctStates=" + s.distinctStates() + " incomplete=" + s.incomplete() + " evalOn=" + s.evaluationsOn() + " evalOff=" + s.evaluationsOff());
        assertEquals(88, s.scenarios(), "every non-fluid catalog scenario replayed against its pinned digest");
        assertTrue(s.failures().isEmpty(), String.join("\n", s.failures()));
        assertEquals(s.ticksChecked(), s.declaredIncluded(), "false exclusions");
        assertEquals(0, s.pruningDisagreements());
        assertTrue(s.ticksChecked() >= 3105, "ticks checked " + s.ticksChecked());
    }
}
