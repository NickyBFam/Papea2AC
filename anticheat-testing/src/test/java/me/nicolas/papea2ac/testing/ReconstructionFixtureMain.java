package me.nicolas.papea2ac.testing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Regenerates docs/phase6-artifacts/reconstruction-fixtures/reconstruction-fixtures.json from the fixture catalog (a changed digest is a reviewed event). */
public final class ReconstructionFixtureMain {
    private ReconstructionFixtureMain() {}
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("phase", "6 - Input Reconstruction"); root.put("source", "ReconstructionFixtures catalog (anticheat-testing), reconstructor over the Phase 3/4/5 test scene; result summaries only, no live objects");
        Map<String, Object> cases = new LinkedHashMap<>();
        for (ReconstructionFixtures.Case c : ReconstructionFixtures.all()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("description", c.description()); m.putAll(ReconstructionFixtures.summary(c.run().get())); cases.put(c.id(), m); }
        root.put("cases", cases);
        Files.createDirectories(out.getParent());
        Files.writeString(out, MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("reconstruction fixtures: " + cases.size() + " cases -> " + out);
        if (args.length > 1) writeInputDomain(Path.of(args[1]));
    }
    /** docs/phase6-artifacts/input-domain.json: the machine-verifiable legal raw domain (144 tuples in canonical order, class representatives, the unverified opt-in client assumptions). */
    static void writeInputDomain(Path out) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("phase", "6 - Input Reconstruction"); root.put("profile", "paper-26.2-124 (MovementInput domain of the Phase 5 simulator)");
        root.put("rawSize", me.nicolas.papea2ac.simulation.InputDomain.RAW_SIZE); root.put("contextFreeClasses", me.nicolas.papea2ac.simulation.InputDomain.CLASS_COUNT);
        root.put("canonicalOrder", "lexicographic forward(-1,0,+1) > strafe(-1,0,+1) > jump(false,true) > shift(false,true) > sprinting(false,true) > usingItem(false,true); the reported input, if any, is tried first");
        root.put("legality", "no raw tuple is excluded by default: the server-side Input record accepts every seven-boolean pattern; client-only legality rules are UNVERIFIED and opt-in (see assumptions)");
        List<Object> tuples = new ArrayList<>();
        for (int o = 0; o < me.nicolas.papea2ac.simulation.InputDomain.RAW_SIZE; o++) {
            var in = me.nicolas.papea2ac.simulation.InputDomain.fromOrdinal(o, 0f); Map<String, Object> t = new LinkedHashMap<>();
            t.put("ordinal", o); t.put("keys", me.nicolas.papea2ac.simulation.InputDomain.describe(in)); t.put("forward", in.forward()); t.put("strafe", in.strafe()); t.put("jump", in.jump()); t.put("shift", in.shift()); t.put("sprinting", in.sprinting()); t.put("usingItem", in.usingItem());
            t.put("classRepresentative", me.nicolas.papea2ac.simulation.InputDomain.ordinal(me.nicolas.papea2ac.simulation.InputDomain.classRepresentative(in))); tuples.add(t);
        }
        root.put("tuples", tuples);
        List<Object> assumptions = new ArrayList<>();
        for (var a : me.nicolas.papea2ac.simulation.InputDomain.ClientAssumption.values()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", a.name()); m.put("source", a.source); m.put("verified", a.verified); m.put("appliedByDefault", false); int rejected = 0; for (var in : me.nicolas.papea2ac.simulation.InputDomain.enumerate(me.nicolas.papea2ac.simulation.InputDomain.Constraints.NONE, 0f)) if (a.rejects(in, Boolean.FALSE)) rejected++; m.put("tuplesRejectedIfOptedIn_previousSprintingFalse", rejected); assumptions.add(m); }
        root.put("clientAssumptions", assumptions);
        Files.writeString(out, MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("input domain -> " + out);
    }
}
