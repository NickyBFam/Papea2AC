package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static me.nicolas.papea2ac.testing.ValidationTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.UnaryOperator;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
/**
 * Deterministic validation fixtures (brief 43, 80): each case is a synthetic ordered trace over the Phase 3/4/5 test scene with
 * an optional observation mutation and environment override, validated as a chain. The pinned summary is the classification,
 * reasons, completeness, counts and a SHA-256 over the ordered result descriptions. A changed digest is a reviewed event.
 */
final class ValidationFixtures {
    private ValidationFixtures() {}
    record Case(String id, String description, String expected, java.util.function.Supplier<List<Validated>> build) {}

    static List<Case> all() {
        List<Case> out = new ArrayList<>();
        out.add(new Case("valid_unique", "one-tick forward walk, unique legal input", "VALID", () -> { PhysicsScene ps = new PhysicsScene(); return List.of(single(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), MovementInput.forward(0f, false), null)); }));
        out.add(new Case("valid_ambiguous", "a standing client's 20-tick reminder: several equivalent legal inputs", "VALID", () -> { PhysicsScene ps = new PhysicsScene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 20; i++) in.add(MovementInput.idle(0f)); return validateChain(ps, ps.env(), simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), in, List.of(), new Recorder(), true)); }));
        out.add(new Case("valid_external_velocity", "velocity SET applied one tick after it left: the union carries apply@j and later", "VALID", () -> { PhysicsScene ps = new PhysicsScene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 5; i++) in.add(MovementInput.idle(0f)); return validateChain(ps, ps.env(), simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), in, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.3, 0.4, 0.1), 2, 1)), new Recorder(), true)); }));
        out.add(new Case("valid_teleport_application", "a correction's application point then walking from it", "VALID", () -> {
            PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
            Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 8); rec.teleport(9, 12.5, SURFACE, 12.5, 0f, 0); rec.advance(TICK_NANOS / 4); rec.confirm(9); rec.advance(100_000L); rec.movement(12.5, SURFACE, 12.5, 0f, true);
            PhysicsState post = start.withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
            Truth t = simulate(ps, ps.env(), post, List.of(MovementInput.forward(0f, false), MovementInput.forward(0f, false)), List.of(), rec, true, false);
            return validateChain(ps, ps.env(), new Truth(start, t.ticks(), t.applicationTick(), rec), validator(ps), TimingBudget.DEFAULT, null); }));
        out.add(new Case("uncertain_incomplete_world", "an impossible observation validated against a world whose chunk was never observed", "UNCERTAIN", () -> {
            PhysicsScene ps = new PhysicsScene(); Truth t = simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), List.of(MovementInput.forward(0f, false)), List.of(), new Recorder(), true);
            PhysicsScene partial = new PhysicsScene(new CollisionTestSupport.Scene(1, new me.nicolas.papea2ac.world.WorldBudget())); for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) if (cx != 0 || cz != 0) partial.scene.baseline(cx, cz);
            return validateChain(ps, partial.env(), t, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)); }));
        out.add(new Case("uncertain_unknown_attribute", "an impossible observation with unknown movement attributes", "UNCERTAIN", () -> { PhysicsScene ps = new PhysicsScene(); Truth t = simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), List.of(MovementInput.forward(0f, false)), List.of(), new Recorder(), true); return validateChain(ps, ps.env().withAttributes(PhysicsEnvironment.Attributes.ALL_UNKNOWN), t, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)); }));
        out.add(new Case("uncertain_search_limit", "an impossible observation with a Phase 6 node budget the frontier cannot fit", "UNCERTAIN", () -> {
            PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5); Truth t = simulate(ps, ps.env(), start, List.of(MovementInput.forward(0f, false)), List.of(), new Recorder(), true);
            Validated base = validateChain(ps, ps.env(), t, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0);
            List<TimingReconstruction.Node> two = List.of(new TimingReconstruction.Node(start, Set.of(), "a"), new TimingReconstruction.Node(start.withVelocity(new Vec3d(0, -0.0784 - 1e-9, 0)), Set.of(), "b"));
            MovementValidationRequest req = new MovementValidationRequest(base.request().timing(), new TimingReconstruction.Frontier(two), ps.env(), new ReconstructionRequest.SearchBudget(1, 4096, 3, 256, 64, 4096), true, true);
            return List.of(new Validated(base.target(), req, validator(ps).validate(req), null)); }));
        out.add(new Case("uncertain_unsupported_fluid", "a transition through water", "UNCERTAIN", () -> {
            PhysicsScene ps = new PhysicsScene(); for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) ps.set(x, (int) SURFACE, z, WATER);
            PhysicsState start = PhysicsState.resting(8.5, SURFACE, 8.5, me.nicolas.papea2ac.collision.PlayerBox.Pose.STANDING, false);
            Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS); TimingEvent.MovementObserved tx = rec.movement(8.5, SURFACE + 0.3, 8.5, 0f, false);
            TimingRequest tr = request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), 0f, tx, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, tx.nanos());
            MovementValidationRequest req = MovementValidationRequest.of(tr, TimingReconstruction.Frontier.of(start), ps.env()); return List.of(new Validated(tx, req, validator(ps).validate(req), null)); }));
        out.add(new Case("uncertain_climbable_contact", "an impossible observation next to a ladder: the exact model is not qualified for INVALID within reach of a climbable (P8-F2)", "UNCERTAIN", () -> {
            PhysicsScene ps = new PhysicsScene(); for (int y = 70; y <= 75; y++) ps.set(8, y, 10, WorldTestSupport.STONE); for (int y = 70; y <= 74; y++) ps.set(8, y, 9, CollisionTestSupport.LADDER);
            PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
            return List.of(single(ps, ps.env(), start, MovementInput.forward(0f, false), shift(1.5, 0, 0))); }));
        out.add(new Case("uncertain_teleport_discontinuity", "a respawn discontinuity inside the interval", "UNCERTAIN", () -> {
            PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
            Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2); rec.discontinuity(1); rec.advance(TICK_NANOS / 2); TimingEvent.MovementObserved tx = rec.movement(8.5, SURFACE, 8.5, 0f, true);
            TimingRequest tr = request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), 0f, tx, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, tx.nanos());
            MovementValidationRequest req = MovementValidationRequest.of(tr, TimingReconstruction.Frontier.of(start), ps.env()); return List.of(new Validated(tx, req, validator(ps).validate(req), null)); }));
        out.add(new Case("valid_pre_application_reminder", "a correction sent but not yet confirmed while the client stands: its reminder is ordinary pre-application movement (never bridged across the correction)", "VALID", () -> {
            PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5);
            Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 8); rec.teleport(9, 12.5, SURFACE, 12.5, 0f, 0);
            List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 30; i++) in.add(MovementInput.idle(0f));
            Truth t = simulate(ps, ps.env(), start, in, List.of(), rec, true, false);
            return validateChain(ps, ps.env(), t, validator(ps), TimingBudget.DEFAULT, null); }));
        out.add(new Case("invalid_horizontal_excess", "1.5 blocks in one tick from rest: beyond every legal input and the M3 envelope", "INVALID", () -> { PhysicsScene ps = new PhysicsScene(); return List.of(single(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), MovementInput.forward(0f, false), shift(1.5, 0, 0))); }));
        out.add(new Case("invalid_vertical_excess", "+1.0 in one tick from rest: no jump, effect or support explains the ascent", "INVALID", () -> { PhysicsScene ps = new PhysicsScene(); return List.of(single(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), MovementInput.forward(0f, false), m -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), SURFACE + 1.0, m.position().z()), true, m.rotation(), false))); }));
        out.add(new Case("invalid_collision_penetration", "the observed feet half a block inside the stone floor", "INVALID", () -> { PhysicsScene ps = new PhysicsScene(); return List.of(single(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), MovementInput.forward(0f, false), m -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), SURFACE - 0.5, m.position().z()), true, m.rotation(), false))); }));
        out.add(new Case("invalid_hover", "airborne after a jump, the observation holds the previous height", "INVALID", () -> {
            PhysicsScene ps = new PhysicsScene(); Truth t = simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), List.of(new MovementInput(0, 0, true, false, false, false, 0f), MovementInput.idle(0f), MovementInput.idle(0f)), List.of(), new Recorder(), true);
            TimingEvent.MovementObserved third = t.ticks().get(2).transmission(); TimingEvent.MovementObserved second = t.ticks().get(1).transmission();
            UnaryOperator<TimingEvent.MovementObserved> hover = m -> m == third ? new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), second.position().y(), m.position().z()), m.hasRotation(), m.rotation(), false) : m;
            return validateChain(ps, ps.env(), t, validator(ps), TimingBudget.DEFAULT, hover); }));
        out.add(new Case("world_epoch_mismatch", "the environment belongs to another world epoch than the observation", "UNCERTAIN", () -> { PhysicsScene ps = new PhysicsScene(); Truth t = simulate(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), List.of(MovementInput.forward(0f, false)), List.of(), new Recorder(), true); return validateChain(ps, ps.env().withView(ps.scene.world.currentView(), 7), t, validator(ps), TimingBudget.DEFAULT, null); }));
        out.add(new Case("frontier_incomplete", "an impossible observation on a chain whose earlier step was incomplete", "UNCERTAIN", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsState start = settled(ps, 8.5, SURFACE, 8.5); Truth t = simulate(ps, ps.env(), start, List.of(MovementInput.forward(0f, false)), List.of(), new Recorder(), true); Validated b = validateChain(ps, ps.env(), t, validator(ps), TimingBudget.DEFAULT, shift(1.5, 0, 0)).get(0); MovementValidationRequest req = b.request().withFrontierComplete(false); return List.of(new Validated(b.target(), req, validator(ps).validate(req), null)); }));
        return out;
    }

    static Map<String, Object> summary(List<Validated> chain) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        List<Object> steps = new ArrayList<>(); Map<String, Integer> classes = new TreeMap<>(); int invalidWithProof = 0;
        for (Validated v : chain) {
            MovementValidationResult r = v.result();
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("sequence", v.target().sequence()); st.put("classification", r.classification().name()); st.put("valid", r.validReasons().stream().map(Enum::name).toList()); st.put("uncertain", r.uncertainReasons().stream().map(Enum::name).toList()); st.put("invalid", r.invalidReasons().stream().map(Enum::name).toList());
            st.put("completeness", r.completeness().all() ? "ALL" : String.join(",", r.completeness().missing())); st.put("timingHypotheses", r.counts().timingHypotheses()); st.put("legal", r.counts().legalCandidates()); st.put("witnesses", r.witnesses().size()); st.put("continuation", r.counts().continuationNodes());
            if (r.invalid()) { invalidWithProof++; st.put("proof", r.proof().hypothesesExhausted() + " hypotheses, " + r.proof().rawEnumerated() + " enumerated, " + r.proof().excludedExact() + "+" + r.proof().excludedEnvelope() + " excluded, " + r.proof().candidatesRemaining() + " remaining"); st.put("residual", String.format(java.util.Locale.ROOT, "h=%.6f rise=%.6f excessH=%.6f excessV=%.6f %s", r.residual().observedHorizontal(), r.residual().observedRise(), r.residual().horizontalExcess(), r.residual().verticalExcess(), r.residual().bound())); }
            classes.merge(r.classification().name(), 1, Integer::sum);
            for (String l : r.describe()) md.update(l.getBytes(StandardCharsets.UTF_8));
            steps.add(st);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transmissions", chain.size()); out.put("classifications", classes); out.put("invalidWithProof", invalidWithProof); out.put("steps", steps); out.put("digest", HexFormat.of().formatHex(md.digest()));
        return out;
    }
}
