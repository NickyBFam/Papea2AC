package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static me.nicolas.papea2ac.testing.ValidationTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
/**
 * The Phase 8 corpora (brief 44-47, 63-68): the LEGITIMATE corpus (the Phase 7 fuzz, velocity and teleport generators with their
 * own seeds - thousands of hidden truths through Phase 7 -> 6 -> 5 -> 8), the NATIVE corpus (the pinned Phase 5 server
 * trajectories, digest-verified, replayed as a client would transmit them) and the IMPOSSIBLE corpus (controlled observation
 * mutations of legitimate transmissions: horizontal scale, vertical shift, hover, gravity removal, wall penetration, step
 * height). Every legitimate transition must be VALID or UNCERTAIN - never INVALID (the zero-false-INVALID gate); impossible
 * mutations record detection descriptively (INVALID where the model is complete, UNCERTAIN otherwise, VALID only when the
 * mutation lands inside another legal hypothesis).
 */
final class ValidationCorpus {
    private ValidationCorpus() {}

    /** Per-class tallies. */
    static final class Tally {
        final String name; int cases, transmissions, valid, uncertain, invalid, truthLost; long evaluations; final Map<String, Integer> uncertainReasons = new TreeMap<>(), invalidReasons = new TreeMap<>(); final List<String> invalidCases = new ArrayList<>(), lostCases = new ArrayList<>();
        Tally(String name) { this.name = name; }
        void add(String caseId, List<Validated> chain) {
            cases++;
            for (Validated v : chain) {
                transmissions++; evaluations += v.result().work().evaluations();
                switch (v.result().classification()) { case VALID -> valid++; case UNCERTAIN -> { uncertain++; for (var r : v.result().uncertainReasons()) uncertainReasons.merge(r.name(), 1, Integer::sum); } case INVALID -> { invalid++; for (var r : v.result().invalidReasons()) invalidReasons.merge(r.name(), 1, Integer::sum); if (invalidCases.size() < 50) invalidCases.add(caseId + "@" + v.target().sequence() + ": " + String.join(" | ", v.result().describe())); } }
                if (v.truthAfter() != null && !v.result().next().isEmpty() && v.result().next().nodes().stream().noneMatch(n -> n.state().equals(v.truthAfter()))) { truthLost++; if (lostCases.size() < 20) lostCases.add(caseId + "@" + v.target().sequence()); }
            }
        }
        Map<String, Object> json() { Map<String, Object> m = new LinkedHashMap<>(); m.put("cases", cases); m.put("transmissions", transmissions); m.put("VALID", valid); m.put("UNCERTAIN", uncertain); m.put("INVALID", invalid); m.put("validShare", transmissions == 0 ? 0 : Math.round(10000.0 * valid / transmissions) / 100.0); m.put("truthLost", truthLost); m.put("evaluations", evaluations); m.put("uncertainReasons", uncertainReasons); m.put("invalidReasons", invalidReasons); m.put("invalidCases", invalidCases); m.put("lostCases", lostCases); return m; }
        @Override public String toString() { return name + ": cases=" + cases + " transmissions=" + transmissions + " VALID=" + valid + " UNCERTAIN=" + uncertain + " INVALID=" + invalid + " truthLost=" + truthLost + " uncertain=" + uncertainReasons; }
    }

    // ---------------------------------------------------------------- legitimate
    static Tally fuzzCorpus(long seed0, int cases) {
        Tally t = new Tally("timing-fuzz");
        for (int c = 0; c < cases; c++) {
            TimingFuzzTest.Scenario sc = TimingFuzzTest.generate(seed0 + c); if (!sc.simulable()) continue;
            MovementValidator v = validator(sc.world().ps());
            t.add("fuzz-" + (seed0 + c), walk(sc.world().env(), sc.start(), sc.rec(), sc.firstInputs().get(0).yaw(), sc::truthFor, v, TimingBudget.DEFAULT, null, Integer.MAX_VALUE));
        }
        return t;
    }
    static Tally velocityCorpus(long seed0, int cases, boolean tickEnds) {
        Tally t = new Tally(tickEnds ? "velocity" : "velocity-no-tick-ends");
        for (int c = 0; c < cases; c++) {
            VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed0 + c, tickEnds); if (vc == null) continue;
            t.add("velocity-" + (seed0 + c), validateChain(vc.world().ps(), vc.world().env(), vc.truth(), validator(vc.world().ps()), TimingBudget.DEFAULT, null));
        }
        return t;
    }
    static Tally teleportCorpus(long seed0, int cases) {
        Tally t = new Tally("teleport"); PhysicsScene ps = new PhysicsScene(); MovementValidator v = validator(ps);
        for (int c = 0; c < cases; c++) {
            TeleportTimingRoundTripTest.TeleportScenario sc = TeleportTimingRoundTripTest.generate(seed0 + c, ps, ps.env()); if (sc == null) continue;
            t.add("teleport-" + (seed0 + c), walk(ps.env(), sc.start(), sc.rec(), sc.yaw(), sc::truthFor, v, TimingBudget.DEFAULT, null, Integer.MAX_VALUE));
        }
        return t;
    }

    // ---------------------------------------------------------------- native (Phase 5 server trajectories)
    /** The pinned native trajectories, transmitted as the client would (send threshold / 20-tick reminder / rotation on change, one tick end per tick), validated as a chain from the scenario start. */
    static Tally nativeCorpus(Path pinnedFixture, List<String> failures) throws Exception {
        Tally t = new Tally("native-trajectories");
        Map<String, Object> pinned = MiniJson.obj(MiniJson.obj(MiniJson.parse(Files.readString(pinnedFixture, StandardCharsets.UTF_8))).get("scenarios"));
        for (PhysicsScenarios.Scenario sc : PhysicsScenarios.all()) {
            if (sc.fluidDeferred()) continue;
            ScenarioWorld w = new ScenarioWorld(sc); PhysicsEnvironment env = w.environment();
            List<PhysicsResult> chain = w.runChained();
            String digest = TrajectoryDigest.ofResults(chain);
            Map<String, Object> f = MiniJson.obj(pinned.get(sc.id()));
            if (f == null || !digest.equals(f.get("digest"))) { failures.add(sc.id() + ": chain digest does not match the pinned native trajectory"); continue; }
            // client transmissions of the true trajectory
            Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd();
            PhysicsState start = sc.start().state(); double lx = start.position().x(), ly = start.position().y(), lz = start.position().z(); float lastYaw = sc.input(sc.ticks().get(0)).yaw(); int sinceSend = 0;
            Map<TimingEvent.MovementObserved, PhysicsState> truth = new IdentityHashMap<>();
            int usable = 0;
            for (int i = 0; i < chain.size(); i++) {
                PhysicsResult r = chain.get(i); if (r.next() == null || !r.exact()) break;
                MovementInput in = sc.input(sc.ticks().get(i)); PhysicsState next = r.next();
                rec.advance(TICK_NANOS / 2);
                boolean sendPosition = !OBS.withinSendThreshold(next.position().x(), next.position().y(), next.position().z(), lx, ly, lz) || ++sinceSend >= 20;
                boolean yawChanged = in.yaw() != lastYaw;
                TimingEvent.MovementObserved tx = null;
                if (sendPosition) { tx = rec.movement(next.position().x(), next.position().y(), next.position().z(), in.yaw(), next.onGround()); lx = next.position().x(); ly = next.position().y(); lz = next.position().z(); sinceSend = 0; lastYaw = in.yaw(); }
                else if (yawChanged) { tx = rec.rotationOnly(in.yaw(), next.onGround()); lastYaw = in.yaw(); }
                if (tx != null) truth.put(tx, next);
                rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 2); usable++;
            }
            if (usable == 0) continue;
            MovementValidator v = new MovementValidator(new TimingReconstruction(new TimingResolver(), new InputReconstructor(w.simulator)));
            t.add("native-" + sc.id(), walk(env, start, rec, sc.input(sc.ticks().get(0)).yaw(), truth::get, v, TimingBudget.DEFAULT, null, Integer.MAX_VALUE));
        }
        return t;
    }

    // ---------------------------------------------------------------- impossible (controlled mutations)
    /** One mutation family: a name, the exact modification applied, and the mutation of the chosen transmission. */
    record Mutation(String name, String modification, java.util.function.BiFunction<Vector3, TimingEvent.MovementObserved, TimingEvent.MovementObserved> apply) {}
    static List<Mutation> mutations() {
        List<Mutation> out = new ArrayList<>();
        for (double f : new double[] { 1.01, 1.05, 1.1, 1.2, 1.5, 2.0 }) out.add(new Mutation("horizontal-x" + f, "horizontal displacement from the previous transmitted position scaled by " + f, (prev, m) -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(prev.x() + (m.position().x() - prev.x()) * f, m.position().y(), prev.z() + (m.position().z() - prev.z()) * f), m.hasRotation(), m.rotation(), m.onGround())));
        for (double dy : new double[] { 0.05, 0.2, 0.5, 1.0 }) out.add(new Mutation("upward+" + dy, "observed y raised by " + dy, (prev, m) -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), m.position().y() + dy, m.position().z()), m.hasRotation(), m.rotation(), m.onGround())));
        out.add(new Mutation("hover", "observed y held at the previous transmitted y (an airborne target that should have fallen)", (prev, m) -> m.position().y() >= prev.y() || m.onGround() ? null : new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), prev.y(), m.position().z()), m.hasRotation(), m.rotation(), false)));
        out.add(new Mutation("no-gravity", "the vertical fall of the tick removed (dy set to 0 for a falling target)", (prev, m) -> m.position().y() >= prev.y() || m.onGround() ? null : new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), prev.y(), m.position().z()), m.hasRotation(), m.rotation(), m.onGround())));
        out.add(new Mutation("step+0.7", "observed y raised by 0.7 with a ground claim (a step no block supports)", (prev, m) -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), m.position().y() + 0.7, m.position().z()), m.hasRotation(), m.rotation(), true)));
        out.add(new Mutation("through-floor", "observed y lowered by 0.6 (feet inside the floor)", (prev, m) -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(m.position().x(), m.position().y() - 0.6, m.position().z()), m.hasRotation(), m.rotation(), false)));
        out.add(new Mutation("1-ulp", "observed x moved by one ulp (the smallest representable change)", (prev, m) -> new TimingEvent.MovementObserved(m.sequence(), m.nanos(), true, new Vector3(Math.nextUp(m.position().x()), m.position().y(), m.position().z()), m.hasRotation(), m.rotation(), m.onGround())));
        return out;
    }
    /** Mutates the k-th position transmission (k chosen by the seed) of each legitimate fuzz/velocity scenario and validates up to it. */
    static Map<String, Tally> impossibleCorpus(long seed0, int cases) {
        Map<String, Tally> tallies = new LinkedHashMap<>(); for (Mutation m : mutations()) tallies.put(m.name(), new Tally("impossible/" + m.name()));
        for (int c = 0; c < cases; c++) {
            long seed = seed0 + c; SplittableRandom r = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
            VelocityTimingRoundTripTest.VelocityCase vc = VelocityTimingRoundTripTest.generate(seed, true); if (vc == null) continue;
            PhysicsScene ps = vc.world().ps(); MovementValidator v = validator(ps);
            List<TimingEvent.MovementObserved> positions = new ArrayList<>(); for (TimingEvent e : vc.truth().recorder().events) if (e instanceof TimingEvent.MovementObserved mo && mo.hasPosition()) positions.add(mo);
            if (positions.isEmpty()) continue;
            int pick = r.nextInt(positions.size()); TimingEvent.MovementObserved chosen = positions.get(pick);
            Vector3 prev = pick == 0 ? new Vector3(vc.truth().start().position().x(), vc.truth().start().position().y(), vc.truth().start().position().z()) : positions.get(pick - 1).position();
            int stopAfter = 0; for (TimingEvent e : vc.truth().recorder().events) { if (e instanceof TimingEvent.MovementObserved mo) { stopAfter++; if (mo == chosen) break; } }
            for (Mutation m : mutations()) {
                UnaryOperator<TimingEvent.MovementObserved> mutate = mo -> mo == chosen ? m.apply().apply(prev, mo) : mo;
                if (m.apply().apply(prev, chosen) == null) continue; // not applicable to this transmission
                List<Validated> chain = validateChain(ps, vc.world().env(), vc.truth(), v, TimingBudget.DEFAULT, mutate);
                // the validated mutated transmission is the last one walked when the chain reached it
                List<Validated> upTo = new ArrayList<>(); for (Validated x : chain) { upTo.add(x); if (x.target().sequence() == chosen.sequence()) break; }
                if (upTo.isEmpty() || upTo.get(upTo.size() - 1).target().sequence() != chosen.sequence()) continue; // the chain broke before the mutation (an earlier UNCERTAIN/INVALID): not a measurement of this mutation
                tallies.get(m.name()).add("impossible-" + seed + "-" + m.name(), List.of(upTo.get(upTo.size() - 1)));
            }
        }
        return tallies;
    }
    static void write(Path out, Map<String, Object> json) throws Exception { Files.createDirectories(out.getParent()); Files.writeString(out, MiniJson.write(json) + "\n", StandardCharsets.UTF_8); }
}
