package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;

/**
 * Phase 6 qualification distribution (§48): over the 88 pinned native trajectories (every tick, declared input hidden) and
 * a deterministic random corpus on the flat / course / ice worlds, records raw legal candidates, physics evaluations
 * (simulated classes after the envelope prefilter), matching states and histories, and the unique / equivalent-inputs /
 * distinct-states percentages per movement category. Ambiguity is data for Phase 8, never a defect. Writes
 * {@code candidate-distribution.json} and {@code native-reconstruction-results.json}.
 */
public final class ReconstructionDistributionMain {
    private ReconstructionDistributionMain() {}
    static final class Bucket {
        int ticks, unique, equivalent, distinct, incomplete; long raw, evaluations, states, histories, excludedEnvelope, excludedExact; int maxStates, maxHistories;
        void add(ReconstructionResult r) {
            ticks++; raw += r.counts().rawEnumerated(); evaluations += r.work().evaluations(); states += r.counts().matchingStates(); histories += r.counts().matchingHistories(); excludedEnvelope += r.counts().excludedEnvelope(); excludedExact += r.counts().excludedExact();
            maxStates = Math.max(maxStates, r.counts().matchingStates()); maxHistories = Math.max(maxHistories, r.counts().matchingHistories());
            if (!r.complete()) incomplete++; else switch (r.ambiguity()) { case UNIQUE -> unique++; case EQUIVALENT_INPUTS -> equivalent++; case DISTINCT_STATES -> distinct++; case NONE -> { } }
        }
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticks", ticks); m.put("uniquePercent", pct(unique)); m.put("equivalentInputsPercent", pct(equivalent)); m.put("distinctStatesPercent", pct(distinct)); m.put("incompletePercent", pct(incomplete));
            m.put("meanRawCandidates", ticks == 0 ? 0 : (double) raw / ticks); m.put("meanPhysicsEvaluations", ticks == 0 ? 0 : (double) evaluations / ticks); m.put("meanMatchingStates", ticks == 0 ? 0 : (double) states / ticks); m.put("meanMatchingHistories", ticks == 0 ? 0 : (double) histories / ticks);
            m.put("maxMatchingStates", maxStates); m.put("maxMatchingHistories", maxHistories); m.put("envelopeExcludedShare", raw == 0 ? 0 : (double) excludedEnvelope / raw); m.put("exactExcludedShare", raw == 0 ? 0 : (double) excludedExact / raw);
            return m;
        }
        double pct(int n) { return ticks == 0 ? 0 : Math.round(10000.0 * n / ticks) / 100.0; }
    }
    static String category(PhysicsState start, MovementInput in) {
        if (!start.onGround()) return in.anyMove() ? "airborne_with_keys" : "airborne_no_keys";
        if (in.jump()) return "ground_jump";
        if (!in.anyMove()) return "ground_still";
        return in.sprinting() ? "ground_sprint" : in.shift() ? "ground_sneak" : in.usingItem() ? "ground_using_item" : "ground_walk";
    }
    public static void main(String[] args) throws Exception {
        Path artifacts = Path.of(args[0]); Files.createDirectories(artifacts);
        // ---- native trajectories
        NativeReconstructionGate.Summary native_ = NativeReconstructionGate.run(artifacts.resolve("physics-fixtures/native-trajectories.json"));
        Files.writeString(artifacts.resolve("native-reconstruction-results.json"), MiniJson.write(NativeReconstructionGate.json(native_)) + "\n", StandardCharsets.UTF_8);
        Map<String, Bucket> nativeByGroup = new TreeMap<>(); Bucket nativeAll = new Bucket();
        Map<String, Bucket> byCategory = new TreeMap<>();
        for (PhysicsScenarios.Scenario sc : PhysicsScenarios.all()) {
            if (sc.fluidDeferred()) continue;
            ScenarioWorld w = new ScenarioWorld(sc); PhysicsEnvironment env = w.environment(); List<PhysicsResult> chain = w.runChained(); InputReconstructor rec = new InputReconstructor(w.simulator);
            PhysicsState start = sc.start().state();
            for (int t = 0; t < chain.size(); t++) {
                PhysicsResult r = chain.get(t); if (r.next() == null || !r.exact()) break; MovementInput declared = sc.input(sc.ticks().get(t));
                ReconstructionResult res = rec.reconstruct(ReconstructionRequest.of(start, observe(r.next(), declared.yaw(), t + 1), env));
                nativeAll.add(res); nativeByGroup.computeIfAbsent(sc.group(), k -> new Bucket()).add(res); byCategory.computeIfAbsent("native:" + category(start, declared), k -> new Bucket()).add(res);
                start = r.next();
            }
        }
        // ---- random corpus (same worlds and generator as the round-trip gate)
        SplittableRandom rnd = new SplittableRandom(0x6A6A6A6AL); List<ReconstructionRoundTripTest.World> worlds = ReconstructionRoundTripTest.worlds();
        Bucket fuzzAll = new Bucket(); Map<String, Bucket> fuzzByWorld = new TreeMap<>();
        for (int c = 0; c < 1500; c++) {
            ReconstructionRoundTripTest.World w = worlds.get(rnd.nextInt(worlds.size()));
            PhysicsState s; try { s = settled(w.ps(), w.x0() + rnd.nextInt(5) - 2, SURFACE, w.z0() + rnd.nextInt(5) - 2); } catch (AssertionError e) { continue; }
            int walk = rnd.nextInt(25); float yaw = ReconstructionRoundTripTest.randomYaw(rnd);
            for (int i = 0; i < walk; i++) { if (rnd.nextInt(6) == 0) yaw = ReconstructionRoundTripTest.randomYaw(rnd); PhysicsResult pr = w.ps().step(s, ReconstructionRoundTripTest.randomInput(rnd, yaw), w.env()); if (pr.next() == null || !pr.exact()) break; s = pr.next(); }
            if (rnd.nextInt(6) == 0) yaw = ReconstructionRoundTripTest.randomYaw(rnd);
            MovementInput in = ReconstructionRoundTripTest.randomInput(rnd, yaw); PhysicsResult pr = w.ps().step(s, in, w.env()); if (pr.next() == null || !pr.exact()) continue;
            ReconstructionResult res = new InputReconstructor(w.ps().simulator).reconstruct(ReconstructionRequest.of(s, observe(pr.next(), yaw, 1000 + c), w.env()));
            fuzzAll.add(res); fuzzByWorld.computeIfAbsent(w.name(), k -> new Bucket()).add(res); byCategory.computeIfAbsent("random:" + category(s, in), k -> new Bucket()).add(res);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("phase", "6 - Input Reconstruction"); root.put("note", "single-tick reconstructions with the declared input hidden; ambiguity is data for Phase 8, not a defect; percentages are of complete searches plus incomplete");
        root.put("rawDomain", InputDomain.RAW_SIZE); root.put("contextFreeClasses", InputDomain.CLASS_COUNT);
        root.put("nativeTrajectories", nativeAll.json()); Map<String, Object> g = new LinkedHashMap<>(); for (var e : nativeByGroup.entrySet()) g.put(e.getKey(), e.getValue().json()); root.put("nativeByGroup", g);
        root.put("randomCorpus", fuzzAll.json()); Map<String, Object> fw = new LinkedHashMap<>(); for (var e : fuzzByWorld.entrySet()) fw.put(e.getKey(), e.getValue().json()); root.put("randomByWorld", fw);
        Map<String, Object> cat = new LinkedHashMap<>(); for (var e : byCategory.entrySet()) cat.put(e.getKey(), e.getValue().json()); root.put("byCategory", cat);
        Files.writeString(artifacts.resolve("candidate-distribution.json"), MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("candidate distribution: native ticks=" + nativeAll.ticks + " unique%=" + nativeAll.pct(nativeAll.unique) + " random ticks=" + fuzzAll.ticks + " unique%=" + fuzzAll.pct(fuzzAll.unique) + " -> " + artifacts);
        if (!native_.failures().isEmpty()) { System.err.println("NATIVE GATE FAILURES: " + native_.failures()); System.exit(1); }
    }
}
