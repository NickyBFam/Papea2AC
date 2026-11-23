package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 13 corpus gates over {@link VelocityCorpus}, both timing profiles:
 * <ul>
 * <li>every legitimate class: ZERO false INVALID (the release gate), under the frozen and the fenced profile;</li>
 * <li>fenced profile, strong cheat classes (cancel, horizontal 0, vertical 0, reverse, delay past the fence): at least 80 % of scenarios
 *     produce an INVALID (TESTING.md Phase 8 adversarial gate, applied to classes whose impossibility is proven by construction);</li>
 * <li>the frozen profile's cancel class is reported (the gap the fence closes), never gated upward.</li>
 * </ul>
 * With {@code P13_WRITE_ARTIFACTS} set it writes {@code docs/phase13-artifacts/velocity-corpus-results.json}.
 */
class VelocityCorpusTest {
    static final int PER_CLASS = 40; static final long SEED = 1300;

    static Map<String, VelocityCorpus.ClassTally> run(TimingProfile profile) { return run(profile, false); }
    static Map<String, VelocityCorpus.ClassTally> run(TimingProfile profile, boolean combatMarkers) { return run(profile, combatMarkers, Double.NaN); }
    static Map<String, VelocityCorpus.ClassTally> run(TimingProfile profile, boolean combatMarkers, double melee) {
        PhysicsScene ps = new PhysicsScene();
        Map<String, VelocityCorpus.ClassTally> tallies = new LinkedHashMap<>();
        for (VelocityCorpus.Scenario s : VelocityCorpus.scenarios(SEED, PER_CLASS)) {
            VelocityCorpus.Outcome o = VelocityCorpus.run(ps, s, profile, combatMarkers, melee);
            String cls = s.cls().startsWith("L-latency") ? "L-latency" : s.cls();
            VelocityCorpus.ClassTally t = tallies.computeIfAbsent(cls, k -> new VelocityCorpus.ClassTally(k, s.kind()));
            if (o != null) t.add(o);
        }
        return tallies;
    }
    static String json(String profile, Map<String, VelocityCorpus.ClassTally> t) {
        StringBuilder sb = new StringBuilder("    \"" + profile + "\": {\n"); int i = 0;
        for (VelocityCorpus.ClassTally c : t.values()) {
            sb.append(String.format(Locale.ROOT, "      \"%s\": {\"kind\": \"%s\", \"scenarios\": %d, \"scenariosWithInvalid\": %d, \"detectionRate\": %.3f, \"transitions\": %d, \"valid\": %d, \"uncertain\": %d, \"invalid\": %d, \"examples\": [%s]}",
                c.cls, c.kind, c.scenarios, c.scenariosWithInvalid, c.detectionRate(), c.transitions, c.valid, c.uncertain, c.invalid,
                String.join(", ", c.examples.stream().map(e -> "\"" + e.replace("\\", "\\\\").replace("\"", "'") + "\"").toList())));
            sb.append(++i < t.size() ? ",\n" : "\n");
        }
        return sb.append("    }").toString();
    }

    @Test void legitimateKnockbackIsNeverInvalidAndFencedCheatsAreDetected() throws Exception {
        Map<String, VelocityCorpus.ClassTally> frozen = run(TimingProfile.PAPER_26_2), fenced = run(TimingProfile.PAPER_26_2_FENCED), combat = run(TimingProfile.PAPER_26_2_FENCED, true), frozenCombat = run(TimingProfile.PAPER_26_2, true), melee = run(TimingProfile.PAPER_26_2_FENCED, true, 2.5), meleeTouching = run(TimingProfile.PAPER_26_2_FENCED, true, 0.4);
        List<String> failures = new ArrayList<>();
        for (var profile : List.of(Map.entry("frozen", frozen), Map.entry("fenced", fenced), Map.entry("fenced+combat-markers", combat), Map.entry("frozen+combat-markers", frozenCombat), Map.entry("fenced+combat+melee-2.5", melee), Map.entry("fenced+combat+melee-0.4", meleeTouching)))
            for (VelocityCorpus.ClassTally c : profile.getValue().values()) {
                if (c.scenarios < PER_CLASS / 2) failures.add(profile.getKey() + " " + c.cls + ": only " + c.scenarios + " certified scenarios");
                if (c.kind == VelocityCorpus.Kind.LEGITIMATE && c.invalid > 0) failures.add(profile.getKey() + " " + c.cls + ": FALSE INVALID " + c.examples);
            }
        for (String strong : List.of("C-cancel", "C-horizontal-0", "C-vertical-0", "C-reverse", "C-delay-past-fence")) {
            for (var p : List.of(Map.entry("fenced", fenced), Map.entry("fenced+combat-markers", combat))) { VelocityCorpus.ClassTally c = p.getValue().get(strong);
            if (c.detectionRate() < 0.8) failures.add(p.getKey() + " " + strong + ": detection " + c.detectionRate() + " < 0.8 (" + c.scenariosWithInvalid + "/" + c.scenarios + ")"); }
        }
        if (System.getenv("P13_WRITE_ARTIFACTS") != null) {
            Path out = TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase13-artifacts/velocity-corpus-results.json"); Files.createDirectories(out.getParent());
            Files.writeString(out, "{\n  \"seed\": " + SEED + ", \"perClass\": " + PER_CLASS + ",\n  \"profiles\": {\n" + json("paper-26.2-124-timing", frozen) + ",\n" + json("paper-26.2-124-timing-fenced", fenced) + ",\n" + json("paper-26.2-124-timing-fenced+phase12-combat-markers", combat) + ",\n" + json("paper-26.2-124-timing+phase12-combat-markers", frozenCombat) + ",\n" + json("fenced+combat-markers+melee-attacker-2.5-blocks", melee) + ",\n" + json("fenced+combat-markers+melee-attacker-0.4-blocks", meleeTouching) + "\n  },\n  \"failures\": " + failures.size() + "\n}\n");
        }
        assertEquals(List.of(), failures);
    }
}
