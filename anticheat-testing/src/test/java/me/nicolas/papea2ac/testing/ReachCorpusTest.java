package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 12 attack corpus gate: legitimate classes are never INVALID; the corpus is deterministic; adversarial shares are reported. */
class ReachCorpusTest {
    @Test void legitimateAttacksAreNeverInvalidAndTheCorpusIsDeterministic() {
        Map<String, ReachCorpus.ClassResult> a = ReachCorpus.run(0x12_C0A1L, 120), b = ReachCorpus.run(0x12_C0A1L, 120);
        assertEquals(a, b, "deterministic");
        for (var r : a.values()) {
            System.out.println("[phase12-corpus] " + r.name() + " attacks=" + r.attacks() + " valid=" + r.valid() + " uncertain=" + r.uncertain() + " invalid=" + r.invalid() + " miss=" + r.missClasses() + " reasons=" + r.reasons());
            if (r.legitimate()) { assertEquals(0, r.invalid(), "a legitimate attack was INVALID in " + r.name()); assertTrue(r.attacks() > 100, r.name() + " exercised: " + r.attacks()); }
        }
        assertTrue(a.get("reach-6.0").invalid() > 0, "a blatant reach is excluded at least sometimes");
    }
}
