package me.nicolas.papea2ac.testing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
/**
 * Writes the Phase 8 evidence artifacts under docs/phase8-artifacts: the deterministic validation fixtures
 * ({@code validation-fixtures/validation-fixtures.json}), the legitimate-corpus / false-positive results, the native and timing
 * corpus results, and the impossible-movement detection results. Summaries only (no raw traces). A changed fixture digest is a
 * reviewed event. Exits non-zero when the zero-false-INVALID gate fails.
 */
public final class ValidationFixtureMain {
    private ValidationFixtureMain() {}
    public static void main(String[] args) throws Exception {
        Path artifacts = Path.of(args[0]); Files.createDirectories(artifacts.resolve("validation-fixtures"));
        // ---- fixtures
        Map<String, Object> root = new LinkedHashMap<>(); root.put("phase", "8 - Movement Validation"); root.put("source", "ValidationFixtures catalog (anticheat-testing): synthetic ordered traces over the Phase 3/4/5 test scene with observation mutations / environment overrides, validated as chains through Phase 7 -> 6 -> 5 -> 8; summaries only");
        Map<String, Object> cases = new LinkedHashMap<>(); int mismatches = 0;
        for (ValidationFixtures.Case c : ValidationFixtures.all()) {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("description", c.description()); m.put("expected", c.expected());
            Map<String, Object> s = ValidationFixtures.summary(c.build().get()); m.putAll(s);
            @SuppressWarnings("unchecked") List<Object> steps = (List<Object>) s.get("steps"); Map<String, Object> last = MiniJson.obj(steps.get(steps.size() - 1));
            boolean ok = c.expected().equals(last.get("classification")); m.put("expectedMet", ok); if (!ok) mismatches++;
            cases.put(c.id(), m);
        }
        root.put("cases", cases);
        Files.writeString(artifacts.resolve("validation-fixtures").resolve("validation-fixtures.json"), MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("validation fixtures: " + cases.size() + " cases, expected mismatches " + mismatches);
        // ---- legitimate corpus (false-positive gate)
        ValidationCorpus.Tally fuzz = ValidationCorpus.fuzzCorpus(90_000, 2_000), vel = ValidationCorpus.velocityCorpus(7_000, 1_500, true), velNo = ValidationCorpus.velocityCorpus(9_000, 200, false), tp = ValidationCorpus.teleportCorpus(30_000, 500);
        List<String> nativeFailures = new ArrayList<>();
        ValidationCorpus.Tally nat = ValidationCorpus.nativeCorpus(artifacts.getParent().resolve("phase6-artifacts/physics-fixtures/native-trajectories.json"), nativeFailures);
        Map<String, Object> legit = new LinkedHashMap<>(); legit.put("phase", "8"); legit.put("gate", "every supported legitimate transition validates VALID or UNCERTAIN, never INVALID; >= 95% VALID per supported benign class (TESTING.md)");
        Map<String, Object> classes = new LinkedHashMap<>(); for (ValidationCorpus.Tally t : List.of(fuzz, vel, velNo, tp, nat)) classes.put(t.name, t.json()); legit.put("classes", classes);
        int cases2 = 0, tx = 0, valid = 0, unc = 0, inv = 0; for (ValidationCorpus.Tally t : List.of(fuzz, vel, velNo, tp, nat)) { cases2 += t.cases; tx += t.transmissions; valid += t.valid; unc += t.uncertain; inv += t.invalid; }
        legit.put("totals", Map.of("cases", cases2, "transmissions", tx, "VALID", valid, "UNCERTAIN", unc, "INVALID", inv)); legit.put("seeds", "fuzz 90000..91999, velocity 7000..8499 (tick ends) and 9000..9199 (no tick ends), teleport 30000..30499, native = the pinned Phase 5 trajectories");
        Files.writeString(artifacts.resolve("legitimate-corpus-results.json"), MiniJson.write(legit) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> fp = new LinkedHashMap<>(); fp.put("phase", "8"); fp.put("gate", "SUPPORTED LEGITIMATE CORPUS: INVALID must be 0"); fp.put("legitimateCases", cases2); fp.put("legitimateTransmissions", tx); fp.put("VALID", valid); fp.put("UNCERTAIN", unc); fp.put("INVALID", inv);
        List<String> invalidIds = new ArrayList<>(); for (ValidationCorpus.Tally t : List.of(fuzz, vel, velNo, tp, nat)) invalidIds.addAll(t.invalidCases); fp.put("invalidCaseIds", invalidIds); fp.put("nativeFailures", nativeFailures);
        Map<String, Object> unreasons = new TreeMap<>(); for (ValidationCorpus.Tally t : List.of(fuzz, vel, velNo, tp, nat)) t.uncertainReasons.forEach((k, v) -> unreasons.merge(k, v, (a, b) -> (Integer) a + (Integer) b)); fp.put("uncertainReasons", unreasons);
        Files.writeString(artifacts.resolve("false-positive-results.json"), MiniJson.write(fp) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> natJson = new LinkedHashMap<>(); natJson.put("phase", "8"); natJson.put("source", "pinned Phase 5 native trajectories (docs/phase6-artifacts/physics-fixtures/native-trajectories.json), digest-verified, transmitted as the client would and validated as chains"); natJson.putAll(nat.json()); natJson.put("failures", nativeFailures);
        Files.writeString(artifacts.resolve("native-validation-results.json"), MiniJson.write(natJson) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> timingJson = new LinkedHashMap<>(); timingJson.put("phase", "8"); timingJson.put("source", "the Phase 7 velocity / teleport / fuzz scenarios (their own seeds) validated as chains: the true branch prevents false INVALID"); timingJson.put("velocity", vel.json()); timingJson.put("velocityNoTickEnds", velNo.json()); timingJson.put("teleport", tp.json()); timingJson.put("fuzz", fuzz.json());
        Files.writeString(artifacts.resolve("timing-validation-results.json"), MiniJson.write(timingJson) + "\n", StandardCharsets.UTF_8);
        // ---- impossible corpus (detection sensitivity, descriptive)
        Map<String, ValidationCorpus.Tally> imp = ValidationCorpus.impossibleCorpus(7_000, 250);
        Map<String, Object> det = new LinkedHashMap<>(); det.put("phase", "8"); det.put("method", "controlled observation mutations of legitimate velocity-corpus transmissions (seeds 7000..7249, one transmission per case chosen by the seed): the chain is legitimate up to the mutated transmission, whose classification is recorded; no cheat client, no brand, no tuning score");
        det.put("observationModel", "exact double equality for transmitted positions (MovementObservationProfile.PAPER_26_2): any shift from every legal prediction, including one ulp, lies outside the reachable observation set in a complete context");
        Map<String, Object> fams = new LinkedHashMap<>(); int measured = 0, detected = 0, uncertain = 0, wrongValid = 0;
        for (ValidationCorpus.Mutation m : ValidationCorpus.mutations()) { ValidationCorpus.Tally t = imp.get(m.name()); Map<String, Object> f = new LinkedHashMap<>(); f.put("modification", m.modification()); f.putAll(t.json()); f.remove("invalidCases"); f.remove("lostCases"); fams.put(m.name(), f); measured += t.transmissions; detected += t.invalid; uncertain += t.uncertain; wrongValid += t.valid; }
        det.put("families", fams); det.put("totals", Map.of("generated", measured, "INVALID", detected, "UNCERTAIN", uncertain, "VALID", wrongValid));
        Files.writeString(artifacts.resolve("detection-results.json"), MiniJson.write(det) + "\n", StandardCharsets.UTF_8);
        Files.writeString(artifacts.resolve("invalid-corpus-results.json"), MiniJson.write(det) + "\n", StandardCharsets.UTF_8);
        System.out.println("legitimate: cases=" + cases2 + " transmissions=" + tx + " VALID=" + valid + " UNCERTAIN=" + unc + " INVALID=" + inv + " | impossible: generated=" + measured + " INVALID=" + detected + " UNCERTAIN=" + uncertain + " VALID=" + wrongValid);
        if (inv > 0 || mismatches > 0 || !nativeFailures.isEmpty()) { System.err.println("GATE FAILED: false INVALID=" + inv + " fixture mismatches=" + mismatches + " native=" + nativeFailures); System.exit(1); }
    }
}
