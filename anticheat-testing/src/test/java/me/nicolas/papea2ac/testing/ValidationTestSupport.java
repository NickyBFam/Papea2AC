package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import java.util.*;
import java.util.function.UnaryOperator;
/**
 * Phase 8 test scaffolding on top of the Phase 7 generator: a validator over a scene, a chain runner that validates every
 * transmission of a hidden truth (Phase 7 -> 6 -> 5 -> 8) carrying the union frontier and its completeness exactly like the
 * live runner, and controlled mutations of an observed transmission (the observation is changed, the truth is not) for the
 * impossible-movement corpus. Expected classifications never come from the validator: legitimacy is known by construction,
 * impossibility by an independent argument stated at each case.
 */
final class ValidationTestSupport {
    private ValidationTestSupport() {}

    static MovementValidator validator(PhysicsScene ps) { return new MovementValidator(new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator))); }

    /** One validated transmission of a chain: the target, the request, the result and the hidden truth (null for rotation-only/silent targets without a truth tick). */
    record Validated(TimingEvent.MovementObserved target, MovementValidationRequest request, MovementValidationResult result, PhysicsState truthAfter) {}

    /**
     * Validates every transmission of {@code truth} from the true start (an exact, complete frontier), threading the union frontier,
     * {@code anchorInTick} and the frontier-completeness flag through the chain as the live runner does. {@code mutate} may replace a
     * target transmission (an observation mutation: the events list is rebuilt with the mutated packet); null keeps the truth.
     */
    static List<Validated> validateChain(PhysicsScene ps, PhysicsEnvironment env, Truth truth, MovementValidator v, TimingBudget budget, UnaryOperator<TimingEvent.MovementObserved> mutate) {
        return walk(env, truth.start(), truth.recorder(), truth.ticks().get(0).input().yaw(), m -> { for (TruthTick t : truth.ticks()) if (t.transmission() == m) return t.after(); return null; }, v, budget, mutate, Integer.MAX_VALUE);
    }
    /**
     * The generic chain walk: from {@code start} (an exact, complete frontier) over every transmission recorded in {@code rec},
     * threading the union frontier, {@code anchorInTick} and the frontier-completeness flag exactly like the live runner.
     * {@code mutate} may replace a target transmission (the observation changes, the truth does not); {@code stopAfter} bounds
     * the number of validated transmissions (the impossible corpus validates up to and including the mutated one).
     */
    static List<Validated> walk(PhysicsEnvironment env, PhysicsState start, Recorder rec, float firstYaw, java.util.function.Function<TimingEvent.MovementObserved, PhysicsState> truthFor, MovementValidator v, TimingBudget budget, UnaryOperator<TimingEvent.MovementObserved> mutate, int stopAfter) {
        TimingReconstruction.Frontier frontier = TimingReconstruction.Frontier.of(start); boolean frontierComplete = true;
        long anchorSeq = 100, anchorNanos = 1_000_000_000L; boolean anchorInTick = true; Vector3 lastSent = new Vector3(start.position().x(), start.position().y(), start.position().z()); float lastYaw = firstYaw;
        Map<String, TimingEvent.ExternalMotionSent> influences = new LinkedHashMap<>();
        List<Validated> out = new ArrayList<>(); int from = 0;
        for (int i = 0; i < rec.events.size() && out.size() < stopAfter; i++) {
            TimingEvent e = rec.events.get(i);
            if (e instanceof TimingEvent.ExternalMotionSent m) influences.put(m.id(), m);
            if (!(e instanceof TimingEvent.MovementObserved original)) continue;
            TimingEvent.MovementObserved target = mutate == null ? original : mutate.apply(original); if (target == null) target = original;
            List<TimingEvent> slice = new ArrayList<>(rec.events.subList(from, i + 1)); slice.set(slice.size() - 1, target); from = i + 1;
            TimingRequest tr = new TimingRequest(slice, anchorSeq, anchorNanos, anchorInTick, lastSent, lastYaw, target, new ArrayList<>(influences.values()), rec.ledger.view().teleports(), rec.ledger.view().rtt(), rec.ledger.view().tickEndCapable(), rec.ledger.view().counters().coverageBreaks() > 0, rec.ledger.view().worldEpoch(), target.nanos(), TimingProfile.PAPER_26_2, budget);
            MovementValidationRequest req = MovementValidationRequest.of(tr, frontier, env).withFrontierComplete(frontierComplete);
            MovementValidationResult res = v.validate(req);
            out.add(new Validated(target, req, res, truthFor.apply(original)));
            if (res.next().isEmpty()) break;
            frontier = res.next(); frontierComplete = res.nextFrontierComplete(); anchorSeq = target.sequence(); anchorNanos = target.nanos();
            anchorInTick = true; for (var w : res.witnesses()) if (w.teleportApplication()) anchorInTick = false;
            if (target.hasPosition()) lastSent = target.position(); if (target.hasRotation()) lastYaw = target.rotation().yaw();
        }
        return out;
    }
    static List<Validated> validateChain(PhysicsScene ps, PhysicsEnvironment env, Truth truth) { return validateChain(ps, env, truth, validator(ps), TimingBudget.DEFAULT, null); }

    /** A single-transition request: the start state, one tick of hidden input, the observed end (possibly mutated) - the simplest validation. */
    static Validated single(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, MovementInput input, UnaryOperator<TimingEvent.MovementObserved> mutate) {
        Truth truth = simulate(ps, env, start, List.of(input), List.of(), new Recorder(), true);
        List<Validated> chain = validateChain(ps, env, truth, validator(ps), TimingBudget.DEFAULT, mutate);
        if (chain.isEmpty()) throw new AssertionError("the tick produced no transmission (inside the send threshold)");
        return chain.get(0);
    }
    /** The observed position shifted by (dx, dy, dz) - the observation changes, nothing else. */
    static UnaryOperator<TimingEvent.MovementObserved> shift(double dx, double dy, double dz) { return m -> !m.hasPosition() ? m : new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x() + dx, m.position().y() + dy, m.position().z() + dz), m.hasRotation(), m.rotation(), m.onGround()); }
    /** The observed displacement from {@code from} scaled by {@code factor} (horizontal only). */
    static UnaryOperator<TimingEvent.MovementObserved> scaleHorizontal(Vector3 from, double factor) { return m -> !m.hasPosition() ? m : new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(from.x() + (m.position().x() - from.x()) * factor, m.position().y(), from.z() + (m.position().z() - from.z()) * factor), m.hasRotation(), m.rotation(), m.onGround()); }
    static String describe(List<Validated> chain) { StringBuilder sb = new StringBuilder(); for (Validated v : chain) { sb.append("seq=").append(v.target().sequence()).append('\n'); for (String l : v.result().describe()) sb.append("  ").append(l).append('\n'); } return sb.toString(); }
    static String describe(Validated v) { return String.join("\n", v.result().describe()); }
}
