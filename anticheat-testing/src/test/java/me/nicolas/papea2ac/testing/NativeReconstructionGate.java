package me.nicolas.papea2ac.testing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;

/**
 * Phase 6 no-false-exclusion gate over the Phase 5 NATIVE trajectories (§49-§50): for every tick of every pinned walking
 * scenario, the actual start state and the actual next state (the server's trajectory, reproduced bit for bit by the
 * digest-verified chain - the digest is re-checked here against the pinned native fixture before any tick is used) are
 * given to the reconstructor with the declared input HIDDEN; the declared input must be an EXACT witness of the result.
 * A single exclusion is a Phase 6 correctness blocker. Also records the unique/ambiguous distribution and the work per
 * tick, for the candidate-distribution artifact. Runs identically with and without the envelope prefilter and requires the
 * candidate sets to agree.
 */
final class NativeReconstructionGate {
    record TickRow(String scenario, int tick, MovementInput declared, ReconstructionResult on, ReconstructionResult off) {}
    record ScenarioRow(String id, String group, int ticks, int included, int unique, int equivalentInputs, int distinctStates, int incomplete, long evaluations, long envelopeTransfers, int maxHistories, String digest) {}
    record Summary(int scenarios, int ticksChecked, int declaredIncluded, int declaredWitness, int unique, int equivalentInputs, int distinctStates, int incomplete, int pruningDisagreements, long evaluationsOn, long evaluationsOff, List<ScenarioRow> rows, List<String> failures) {}

    static Summary run(Path pinnedFixture) throws Exception {
        Map<String, Object> pinned = MiniJson.obj(MiniJson.obj(MiniJson.parse(Files.readString(pinnedFixture, StandardCharsets.UTF_8))).get("scenarios"));
        List<ScenarioRow> rows = new ArrayList<>(); List<String> failures = new ArrayList<>();
        int scenarios = 0, ticks = 0, included = 0, witness = 0, unique = 0, equiv = 0, distinct = 0, incomplete = 0, disagreements = 0; long evOn = 0, evOff = 0;
        for (PhysicsScenarios.Scenario sc : PhysicsScenarios.all()) {
            if (sc.fluidDeferred()) continue;
            ScenarioWorld w = new ScenarioWorld(sc); PhysicsEnvironment env = w.environment();
            List<PhysicsResult> chain = w.runChained();
            String digest = TrajectoryDigest.ofResults(chain);
            Map<String, Object> f = MiniJson.obj(pinned.get(sc.id()));
            if (f == null || !digest.equals(f.get("digest"))) { failures.add(sc.id() + ": chain digest " + digest + " does not match the pinned native trajectory - the chain is not the server's trajectory"); continue; }
            InputReconstructor rec = new InputReconstructor(w.simulator);
            int sInc = 0, sUnique = 0, sEquiv = 0, sDistinct = 0, sIncomplete = 0, sMaxHist = 0; long sEv = 0, sEnv = 0;
            PhysicsState start = sc.start().state();
            for (int t = 0; t < chain.size(); t++) {
                PhysicsResult r = chain.get(t); MovementInput declared = sc.input(sc.ticks().get(t));
                if (r.next() == null || !r.exact()) break;
                TickObservation obs = ReconstructionTestSupport.observe(r.next(), declared.yaw(), t + 1);
                ReconstructionRequest req = ReconstructionRequest.of(start, obs, env);
                ReconstructionResult on = rec.reconstruct(req), off = rec.reconstruct(req.withEnvelopePruning(false));
                ticks++; sEv += on.work().evaluations(); sEnv += on.work().envelopeTransfers(); evOn += on.work().evaluations(); evOff += off.work().evaluations();
                boolean adm = on.admits(List.of(declared)), wit = on.witnesses(List.of(declared));
                if (adm) { included++; sInc++; } if (wit) witness++;
                if (!adm) failures.add(sc.id() + " tick " + (t + 1) + ": declared input " + InputDomain.describe(declared) + " EXCLUDED: " + String.join(" | ", on.describe(6)));
                else if (!wit && on.complete()) failures.add(sc.id() + " tick " + (t + 1) + ": declared input admitted but not a witness in a complete search: " + String.join(" | ", on.describe(6)));
                if (!ReconstructionRoundTripTest.candidateSet(on).equals(ReconstructionRoundTripTest.candidateSet(off))) { disagreements++; failures.add(sc.id() + " tick " + (t + 1) + ": envelope prefilter changed the candidate set"); }
                if (!on.complete()) { incomplete++; sIncomplete++; }
                else switch (on.ambiguity()) { case UNIQUE -> { unique++; sUnique++; } case EQUIVALENT_INPUTS -> { equiv++; sEquiv++; } case DISTINCT_STATES -> { distinct++; sDistinct++; } case NONE -> { } }
                for (ReconstructionResult.Candidate c : on.candidates()) sMaxHist = Math.max(sMaxHist, c.historyCount());
                start = r.next();
            }
            scenarios++;
            rows.add(new ScenarioRow(sc.id(), sc.group(), chain.size(), sInc, sUnique, sEquiv, sDistinct, sIncomplete, sEv, sEnv, sMaxHist, digest));
        }
        return new Summary(scenarios, ticks, included, witness, unique, equiv, distinct, incomplete, disagreements, evOn, evOff, rows, failures);
    }
    static Map<String, Object> json(Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", "6 - Input Reconstruction"); m.put("source", "pinned native trajectories (docs/phase6-artifacts/physics-fixtures/native-trajectories.json, the P6-F1 re-pin of the Phase 5 catalog), replayed by the digest-verified chain; declared input hidden per tick");
        m.put("scenarios", s.scenarios()); m.put("ticksChecked", s.ticksChecked()); m.put("declaredInputIncluded", s.declaredIncluded()); m.put("declaredInputExactWitness", s.declaredWitness()); m.put("falseExclusions", s.ticksChecked() - s.declaredIncluded());
        m.put("uniqueTicks", s.unique()); m.put("equivalentInputTicks", s.equivalentInputs()); m.put("distinctStateTicks", s.distinctStates()); m.put("incompleteTicks", s.incomplete()); m.put("pruningDisagreements", s.pruningDisagreements());
        m.put("physicsEvaluationsWithEnvelope", s.evaluationsOn()); m.put("physicsEvaluationsWithoutEnvelope", s.evaluationsOff());
        List<Object> rows = new ArrayList<>();
        for (ScenarioRow r : s.rows()) { Map<String, Object> o = new LinkedHashMap<>(); o.put("id", r.id()); o.put("group", r.group()); o.put("ticks", r.ticks()); o.put("declaredIncluded", r.included()); o.put("unique", r.unique()); o.put("equivalentInputs", r.equivalentInputs()); o.put("distinctStates", r.distinctStates()); o.put("incomplete", r.incomplete()); o.put("evaluations", r.evaluations()); o.put("envelopeTransfers", r.envelopeTransfers()); o.put("maxHistoriesPerNode", r.maxHistories()); o.put("chainDigest", r.digest()); rows.add(o); }
        m.put("perScenario", rows); m.put("failures", new ArrayList<Object>(s.failures()));
        return m;
    }
}
