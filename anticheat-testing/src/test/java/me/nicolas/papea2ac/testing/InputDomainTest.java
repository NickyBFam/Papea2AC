package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.simulation.InputDomain;
import me.nicolas.papea2ac.simulation.InputDomain.ClientAssumption;
import me.nicolas.papea2ac.simulation.InputDomain.Constraints;
import org.junit.jupiter.api.Test;

/** §5-§8: the legal raw domain is machine-verifiable, canonically ordered, constrained only by caller knowledge, and never shrunk by an unverified client assumption on its own. */
class InputDomainTest {
    @Test void theRawDomainIsExactlyThe144PhysicsTuplesInCanonicalOrder() {
        List<MovementInput> all = InputDomain.enumerate(Constraints.NONE, 0f);
        assertEquals(InputDomain.RAW_SIZE, all.size()); assertEquals(144, all.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            MovementInput in = all.get(i);
            assertEquals(i, InputDomain.ordinal(in), "canonical rank"); assertEquals(in, InputDomain.fromOrdinal(i, 0f)); assertTrue(seen.add(InputDomain.describe(in)), "duplicate " + InputDomain.describe(in));
            if (i > 0) { MovementInput p = all.get(i - 1); assertTrue(lex(p) < lex(in), "order " + InputDomain.describe(p) + " < " + InputDomain.describe(in)); }
        }
        assertEquals("SD----", InputDomain.describe(all.get(0))); assertEquals("WAJCRU", InputDomain.describe(all.get(143)));
        assertThrows(IllegalArgumentException.class, () -> new MovementInput(2, 0, false, false, false, false, 0f), "no analog values");
    }
    static int lex(MovementInput in) { return InputDomain.ordinal(in); }
    @Test void constraintsEnumerateOnlyTheUnknownDimensions() {
        Constraints c = Constraints.NONE.withSprinting(true).withStrafe(1);
        List<MovementInput> l = InputDomain.enumerate(c, 30f);
        assertEquals(3 * 2 * 2 * 2, l.size()); assertEquals(c.admittedCount(), l.size());
        for (MovementInput in : l) { assertTrue(in.sprinting()); assertEquals(1, in.strafe()); assertEquals(30f, in.yaw()); }
        assertEquals(1, InputDomain.enumerate(Constraints.exactly(new MovementInput(1, 0, true, false, true, false, 0f)), 0f).size());
        assertThrows(IllegalArgumentException.class, () -> new Constraints(5, 0, 0, 0, 0, 0));
    }
    @Test void theOnlyContextFreeEquivalenceIsItemUseWithoutMovementKeys() {
        int classes = 0; Set<MovementInput> reps = new HashSet<>();
        for (MovementInput in : InputDomain.enumerate(Constraints.NONE, 0f)) if (reps.add(InputDomain.classRepresentative(in))) classes++;
        assertEquals(InputDomain.CLASS_COUNT, classes); assertEquals(136, classes);
        assertEquals(InputDomain.classRepresentative(new MovementInput(0, 0, true, true, true, true, 0f)), InputDomain.classRepresentative(new MovementInput(0, 0, true, true, true, false, 0f)));
        assertNotEquals(InputDomain.classRepresentative(new MovementInput(1, 0, false, false, false, true, 0f)), InputDomain.classRepresentative(new MovementInput(1, 0, false, false, false, false, 0f)), "item use scales a non-zero impulse");
    }
    @Test void clientAssumptionsAreUnverifiedOptInAndNeverAppliedByDefault() {
        for (ClientAssumption a : ClientAssumption.values()) { assertFalse(a.verified, a + " must stay marked unverified: client-only code is not in the pinned server jar"); assertTrue(a.source.contains("client-only")); }
        assertEquals(144, InputDomain.enumerate(Constraints.NONE, 0f, List.of(), null).size());
        List<MovementInput> l = InputDomain.enumerate(Constraints.NONE, 0f, List.of(ClientAssumption.SPRINT_START_REQUIRES_FORWARD), Boolean.FALSE);
        assertEquals(144 - 2 * 3 * 2 * 2 * 2 + 0, l.size(), "sprint starting this tick needs forward=+1: the 48 sprint tuples with forward in {-1, 0} are rejected under the opted-in assumption");
        assertEquals(144, InputDomain.enumerate(Constraints.NONE, 0f, List.of(ClientAssumption.SPRINT_START_REQUIRES_FORWARD), Boolean.TRUE).size(), "a continuing sprint is not constrained");
        assertEquals(144, InputDomain.enumerate(Constraints.NONE, 0f, List.of(ClientAssumption.SPRINT_START_REQUIRES_FORWARD), null).size(), "unknown prerequisite never rejects");
        assertEquals(144 - 36, InputDomain.enumerate(Constraints.NONE, 0f, List.of(ClientAssumption.SPRINT_EXCLUDES_ITEM_USE), null).size());
    }
}
