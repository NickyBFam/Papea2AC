package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingMemoryMain.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.core.MovementValidationRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.simulation.*;
/**
 * Empirical memory harness for Phase 8 (same methodology as the Phase 2-7 harnesses: forced GC, used-heap diff, 3 trials, median;
 * estimates on one host, never a supported-capacity claim) plus thread-allocation measurements. Measures the validator (stateless),
 * one live runner empty / anchored with its bounded recent ring full, the player-owned context ledger empty / at every bound,
 * retained MovementValidationResult values (VALID with witnesses, INVALID with proof) if a consumer kept 100, and per-result
 * allocation for the classifier alone versus validation end to end (timing + Phase 6 + Phase 5 + classifier) per family
 * (brief sections 62, 64).
 */
public final class ValidationMemoryMain {
    private ValidationMemoryMain() {}
    public static void main(String[] args) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("phase", "8 - Movement Validation"); out.put("method", "forced GC + used-heap diff, 3 trials, median; thread-allocation counters for per-result garbage; estimates on one host, not capacity");
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        TimingReconstruction timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator)); MovementValidator validator = new MovementValidator(timing);
        SessionKey key = new SessionKey(new UUID(9, 8), 1);
        out.put("validatorBytes", Math.round(measure(() -> new MovementValidator(timing))));
        out.put("contextLedgerEmptyBytes", Math.round(measure(() -> new MovementContextLedger(key))));
        out.put("contextLedgerAtBoundsBytes", Math.round(measure(() -> {
            MovementContextLedger l = new MovementContextLedger(key); long seq = 1;
            for (int i = 0; i < MovementContextLedger.MAX_ATTRIBUTES + 8; i++) l.apply(key, new ContextEvent.AttributeUpdated(seq++, "minecraft:attr_" + i, 0.1, List.of(new ContextEvent.ModifierValue("minecraft:mod_" + i, 0.2, "ADD_VALUE"))));
            for (int i = 0; i < MovementContextLedger.MAX_EFFECTS + 8; i++) l.apply(key, new ContextEvent.EffectAdded(seq++, "minecraft:effect_" + i, 1));
            for (int i = 0; i < MovementContextLedger.MAX_ENTITIES + 64; i++) l.apply(key, new ContextEvent.EntitySpawned(seq++, 1000 + i, i % 3 == 0 ? "minecraft:player" : "minecraft:cow", 8.5 + i, SURFACE, 8.5));
            l.apply(key, new ContextEvent.BorderInitialized(seq++, 0, 0, 5.9999968E7, 5.9999968E7, 0));
            return l;
        })));
        out.put("contextLedgerBounds", "attributes <= " + MovementContextLedger.MAX_ATTRIBUTES + ", effects <= " + MovementContextLedger.MAX_EFFECTS + ", tracked entities <= " + MovementContextLedger.MAX_ENTITIES + " (beyond the bound the neighbourhood is UNKNOWN, never dropped silently)");
        out.put("runnerEmptyBytes", Math.round(measure(() -> new MovementValidationRunnerTest.Harness(ps).runner)));
        out.put("runnerAnchoredRecentFullBytes", Math.round(measure(() -> {
            MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps); h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
            MovementValidationRunnerTest.correction(h, 1, 12.5, SURFACE, 12.5, 0f); h.feed();
            PhysicsState post = settled(ps, 8.5, SURFACE, 8.5).withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
            List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 2 * MovementValidationRunner.RECENT; i++) in.add(MovementInput.forward((i / 24) % 2 == 0 ? 0f : 180f, false)); // back and forth inside the observed chunks
            simulate(ps, ps.env(), post, in, List.of(), h.rec, true, false); h.feed();
            if (h.status().valid() < MovementValidationRunner.RECENT) throw new IllegalStateException("harness did not validate: " + h.status());
            return h.runner;
        })));
        out.put("runnerBounds", "event slice <= " + MovementValidationRunner.MAX_SLICE + " events, recent ring " + MovementValidationRunner.RECENT + " lines, injected ring " + MovementValidationRunner.RECENT + " lines, one last result, frontier <= TimingBudget.DEFAULT.maxFrontierNodes() nodes; no per-transmission history, no UUID-labelled metric");
        // per-result allocation: classifier alone versus end to end
        Map<String, MovementValidationRequest> families = new LinkedHashMap<>();
        TimingReconstruction.Frontier f = TimingReconstruction.Frontier.of(rest);
        families.put("valid_one_tick", new MovementValidationRequest(oneTick(rest, 0), f, env, ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        families.put("valid_velocity_2_branches", new MovementValidationRequest(oneTick(rest, 1), f, env, ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        families.put("invalid_horizontal_excess", new MovementValidationRequest(shifted(rest, 0.5), f, env, ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        families.put("uncertain_world_incomplete", new MovementValidationRequest(oneTick(rest, 0), f, new PhysicsScene(new CollisionTestSupport.Scene(1, new me.nicolas.papea2ac.world.WorldBudget())).env(), ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        families.put("two_tick_gap", new MovementValidationRequest(gap(rest, 2), f, env, ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        families.put("three_tick_gap", new MovementValidationRequest(gap(rest, 3), f, env, ReconstructionRequest.SearchBudget.DEFAULT, true, true));
        Map<String, Object> perResult = new LinkedHashMap<>();
        for (var e : families.entrySet()) {
            MovementValidationRequest req = e.getValue(); TimingReconstruction.Result tr = timing.run(req.timing(), req.frontier(), req.environment(), req.reconstructionBudget());
            long classifierOnly = alloc(() -> validator.classify(req, tr)); long endToEnd = alloc(() -> validator.validate(req));
            MovementValidationResult r = validator.validate(req);
            Map<String, Object> m = new LinkedHashMap<>(); m.put("classifierOnlyBytes", classifierOnly); m.put("endToEndBytes", endToEnd); m.put("classification", r.classification().name()); m.put("timingHypotheses", r.counts().timingHypotheses()); m.put("reconstructions", r.counts().reconstructions()); m.put("evaluations", r.work().evaluations()); m.put("witnesses", r.witnesses().size()); m.put("proofHypothesisIds", r.proof().hypothesisIds().size()); m.put("retainedResultBytes", Math.round(measure(() -> validator.validate(req))));
            perResult.put(e.getKey(), m);
        }
        out.put("perResultAllocationBytes", perResult);
        MovementValidationRequest keepValid = families.get("valid_velocity_2_branches"), keepInvalid = families.get("invalid_horizontal_excess");
        out.put("retainedBytesPer100ValidResults", Math.round(measure(() -> { List<Object> l = new ArrayList<>(); for (int i = 0; i < 100; i++) l.add(validator.validate(keepValid)); return l; })));
        out.put("retainedBytesPer100InvalidResults", Math.round(measure(() -> { List<Object> l = new ArrayList<>(); for (int i = 0; i < 100; i++) l.add(validator.validate(keepInvalid)); return l; })));
        out.put("perSessionRetained", "one MovementContextLedger + one MovementValidationRunner (above; the runner keeps the frontier, the last result and two bounded rings of description lines) - the validator is stateless; requests and results are values the caller owns and drops");
        Path p = Path.of(args[0]); Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(out));
    }
    /** One forward tick whose observed position is shifted {@code dx} beyond the truth: no legal input. */
    static TimingRequest shifted(PhysicsState rest, double dx) {
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd();
        PhysicsResult pr = new PhysicsScene().simulator.simulate(rest, MovementInput.forward(0f, false), new PhysicsScene().env());
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(pr.next().position().x() + dx, pr.next().position().y(), pr.next().position().z(), 0f, pr.next().onGround());
        return request(rec, 100, 1_000_000_000L, new me.nicolas.papea2ac.model.Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), me.nicolas.papea2ac.version.TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1);
    }
}
