package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
import org.junit.jupiter.api.Test;
/**
 * The Phase 8 gates over the corpora (brief 44-47, 63-70): ZERO false INVALID on every legitimate corpus (the hard acceptance
 * requirement), >= 95% VALID on each supported benign class (TESTING.md concrete coverage gate), the pinned NATIVE server
 * trajectories never INVALID, and detection sensitivity on controlled impossible mutations recorded descriptively - INVALID
 * only ever with a complete proof.
 */
class ValidationCorpusTest {
    static final Path NATIVE = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase6-artifacts/physics-fixtures/native-trajectories.json");

    @Test void theLegitimateFuzzCorpusHasZeroFalseInvalid() {
        ValidationCorpus.Tally t = ValidationCorpus.fuzzCorpus(90_000, 2_000);
        System.out.println("legitimate " + t);
        assertEquals(0, t.invalid, String.join("\n", t.invalidCases)); assertEquals(0, t.truthLost, String.join("\n", t.lostCases));
        assertTrue(t.cases >= 1_200 && t.transmissions >= 10_000, t.toString());
        assertTrue(t.valid >= 0.95 * t.transmissions, "supported benign class must be >= 95% VALID: " + t);
    }
    @Test void theLegitimateVelocityCorporaHaveZeroFalseInvalid() {
        ValidationCorpus.Tally a = ValidationCorpus.velocityCorpus(7_000, 1_500, true), b = ValidationCorpus.velocityCorpus(9_000, 200, false);
        System.out.println("legitimate " + a); System.out.println("legitimate " + b);
        assertEquals(0, a.invalid, String.join("\n", a.invalidCases)); assertEquals(0, b.invalid, String.join("\n", b.invalidCases));
        assertEquals(0, a.truthLost + b.truthLost);
        assertTrue(a.valid >= 0.95 * a.transmissions, a.toString());
        assertTrue(b.valid >= 0.95 * b.transmissions, "without tick ends the bounded fallback still finds the witness (completeness false, VALID nonetheless): " + b);
        for (var v : List.of(a, b)) assertTrue(v.transmissions > 1_000, v.toString());
    }
    @Test void theLegitimateTeleportCorpusHasZeroFalseInvalid() {
        ValidationCorpus.Tally t = ValidationCorpus.teleportCorpus(30_000, 500);
        System.out.println("legitimate " + t);
        assertEquals(0, t.invalid, String.join("\n", t.invalidCases)); assertEquals(0, t.truthLost, String.join("\n", t.lostCases));
        assertTrue(t.cases >= 400 && t.valid >= 0.95 * t.transmissions, t.toString());
    }
    @Test void theNativeServerTrajectoriesAreNeverInvalid() throws Exception {
        List<String> failures = new ArrayList<>();
        ValidationCorpus.Tally t = ValidationCorpus.nativeCorpus(NATIVE, failures);
        System.out.println("native " + t);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(0, t.invalid, String.join("\n", t.invalidCases));
        assertTrue(t.cases >= 60 && t.transmissions >= 1_000, t.toString());
        assertTrue(t.valid > 0.5 * t.transmissions, "the supported native scenarios validate: " + t);
    }
    @Test void controlledImpossibleMutationsAreDetectedOnlyWithACompleteProof() {
        Map<String, ValidationCorpus.Tally> tallies = ValidationCorpus.impossibleCorpus(7_000, 250);
        int measured = 0, invalid = 0;
        for (ValidationCorpus.Tally t : tallies.values()) { System.out.println(t); measured += t.transmissions; invalid += t.invalid; }
        assertTrue(measured > 500, "measured mutations " + measured);
        // strong, unambiguous mutations of a complete-context transmission are detected in the large majority (TESTING.md: >= 80% on a predeclared supported adversarial class)
        for (String strong : List.of("horizontal-x2.0", "horizontal-x1.5", "upward+1.0", "upward+0.5", "through-floor")) {
            ValidationCorpus.Tally t = tallies.get(strong);
            assertTrue(t.transmissions > 50, strong + " measured " + t.transmissions);
            assertTrue(t.invalid >= 0.8 * t.transmissions, strong + ": " + t);
        }
        assertTrue(invalid > 0.5 * measured, "overall detection " + invalid + "/" + measured);
        // never VALID: an exact-double observation model has no room for a shifted position to coincide with another legal prediction unless it really does
        for (ValidationCorpus.Tally t : tallies.values()) assertTrue(t.valid <= 0.02 * Math.max(1, t.transmissions) + 1, t.name + " erroneously VALID " + t.valid + "/" + t.transmissions);
    }
}
