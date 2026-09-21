package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
/**
 * Deterministic timing fixtures (brief section 61): each case is an ordered synthetic trace (send events, movement observations,
 * tick ends, marker answers, external motion, corrections - no wall clock) over the Phase 3/4/5 test scene, plus the hidden truth
 * where one exists. The pinned summary is the resolver's statuses/hypothesis ids per transmission, the reconstruction statuses,
 * whether the truth survived, and a SHA-256 over the ordered hypothesis descriptions and continuation states. A changed digest
 * is a reviewed event, never a silent re-pin.
 */
final class TimingFixtures {
    private TimingFixtures() {}
    record Case(String id, String description, java.util.function.Supplier<Built> build) {}
    /** A built case: the recorder (events), the start state and per-transmission truth (null when no truth is claimed). */
    record Built(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, Recorder rec, Map<Long, PhysicsState> truthBySequence, float yaw) {}

    static List<Case> all() {
        List<Case> out = new ArrayList<>();
        out.add(new Case("stable_rtt_walk", "three 1-tick walking transmissions with 20 ms marker answers", () -> walk(0, 20, 0, false)));
        out.add(new Case("rtt_jitter", "walking with 10-80 ms jittered marker answers", () -> walk(1, 45, 35, false)));
        out.add(new Case("rtt_outlier", "walking with one 900 ms answer among 20 ms answers", () -> walk(2, 20, 0, true)));
        out.add(new Case("velocity_immediate", "velocity SET applied at the next tick", () -> velocity(3, 1, 0)));
        out.add(new Case("velocity_delayed_1", "velocity SET applied one tick late (network lead 1)", () -> velocity(4, 2, 1)));
        out.add(new Case("velocity_delayed_2", "velocity SET applied two ticks late", () -> velocity(5, 3, 2)));
        out.add(new Case("explosion_impulse", "explosion ADD applied at the next tick", () -> explosion(6)));
        out.add(new Case("multiple_velocities", "two velocities in wire order, applied at consecutive ticks", () -> two(7)));
        out.add(new Case("silent_client_velocity", "a standing client receives a velocity after two silent ticks", () -> silentVelocity(8)));
        out.add(new Case("teleport_immediate_confirm", "correction applied before the next tick", () -> teleport(9, 0)));
        out.add(new Case("teleport_delayed_confirm", "correction applied after two pre-application ticks", () -> teleport(10, 2)));
        out.add(new Case("teleport_supersession", "two corrections, the older confirmed first, then the newer", () -> supersession(11)));
        out.add(new Case("silent_closure_reminder", "a standing client's 20-tick position reminder then a move (silent closure)", () -> reminder(12)));
        out.add(new Case("rotation_only_ticks", "rotation-only packets inside a 3-tick interval", () -> rotation(13)));
        out.add(new Case("no_tick_end_fallback", "a session without tick ends: bounded fallback", () -> noTickEnds(14)));
        out.add(new Case("world_discontinuity", "a respawn boundary inside the interval", () -> discontinuity(15)));
        out.add(new Case("unresolved_motion", "a declared-unknown influence in scope", () -> unresolved(16)));
        out.add(new Case("influence_overflow", "nine simultaneously unresolved influences", () -> overflow(17)));
        out.add(new Case("expired_influence", "an influence older than the application horizon", () -> expired(18)));
        return out;
    }
    // ---- builders
    static Built scene() { PhysicsScene ps = new PhysicsScene(); return new Built(ps, ps.env(), settled(ps, 8.5, SURFACE, 8.5), new Recorder(), new LinkedHashMap<>(), 30f); }
    static Built walk(int seed, int rttMs, int jitterMs, boolean outlier) {
        Built b = scene(); Recorder rec = b.rec(); SplittableRandom r = new SplittableRandom(seed);
        for (int i = 0; i < 6; i++) { rec.marker(TimingEvent.MarkerKind.PING, i); rec.advance((rttMs + (jitterMs > 0 ? r.nextInt(jitterMs) : 0) + (outlier && i == 3 ? 880 : 0)) * 1_000_000L); rec.ack(TimingEvent.MarkerKind.PING, i); rec.advance(300_000_000L); }
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 3; i++) in.add(MovementInput.forward(b.yaw(), i == 2));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(), rec, true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built velocity(int seed, int app, int lead) {
        Built b = scene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 5; i++) in.add(MovementInput.forward(b.yaw(), false));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.2, 0.4, -0.1), app, lead)), b.rec(), true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built explosion(int seed) {
        Built b = scene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 4; i++) in.add(MovementInput.idle(b.yaw()));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(new Influence(TimingEvent.Operation.ADD, new Vec3d(0.3, 0.5, 0.1), 2, 0)), b.rec(), true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built two(int seed) {
        Built b = scene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 5; i++) in.add(MovementInput.forward(b.yaw(), false));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.1, 0.4, 0.0), 2, 0), new Influence(TimingEvent.Operation.SET, new Vec3d(-0.2, 0.42, 0.1), 3, 0)), b.rec(), true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built silentVelocity(int seed) {
        Built b = scene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 6; i++) in.add(MovementInput.idle(b.yaw()));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(new Influence(TimingEvent.Operation.SET, new Vec3d(0.25, 0.4, 0.05), 3, 1)), b.rec(), true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built teleport(int seed, int lead) {
        Built b = scene(); Recorder rec = b.rec(); rec.advance(1_000_000L); rec.tickEnd();
        PhysicsState s = b.start(); rec.advance(TICK_NANOS / 8); rec.teleport(7, 12.5, SURFACE, 12.5, b.yaw(), 0);
        if (lead > 0) { List<MovementInput> pre = new ArrayList<>(); for (int i = 0; i < lead; i++) pre.add(MovementInput.forward(b.yaw(), false)); Truth tp = simulate(b.ps(), b.env(), s, pre, List.of(), rec, true, false); for (TruthTick tt : tp.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after()); s = tp.ticks().get(tp.ticks().size() - 1).after(); }
        rec.advance(TICK_NANOS / 4); rec.confirm(7); rec.advance(100_000L); TimingEvent.MovementObserved applied = rec.movement(12.5, SURFACE, 12.5, b.yaw(), s.onGround());
        PhysicsState post = s.withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE); b.truthBySequence().put(applied.sequence(), post);
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 3; i++) in.add(MovementInput.forward(b.yaw(), false));
        Truth t = simulate(b.ps(), b.env(), post, in, List.of(), rec, true, false);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built supersession(int seed) {
        Built b = scene(); Recorder rec = b.rec(); rec.advance(1_000_000L); rec.tickEnd();
        rec.teleport(1, 12.5, SURFACE, 12.5, b.yaw(), 0); rec.teleport(2, 14.5, SURFACE, 14.5, b.yaw(), 0);
        rec.advance(2_000_000L); rec.confirm(1); rec.advance(100_000L); TimingEvent.MovementObserved a = rec.movement(12.5, SURFACE, 12.5, b.yaw(), true);
        b.truthBySequence().put(a.sequence(), b.start().withPosition(new Vec3d(12.5, SURFACE, 12.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE));
        rec.advance(1_000_000L); rec.confirm(2); rec.advance(100_000L); TimingEvent.MovementObserved c = rec.movement(14.5, SURFACE, 14.5, b.yaw(), true);
        PhysicsState post = b.start().withPosition(new Vec3d(14.5, SURFACE, 14.5)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE); b.truthBySequence().put(c.sequence(), post);
        List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 2; i++) in.add(MovementInput.forward(b.yaw(), false));
        Truth t = simulate(b.ps(), b.env(), post, in, List.of(), rec, true, false);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built reminder(int seed) {
        Built b = scene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 22; i++) in.add(MovementInput.idle(b.yaw())); in.add(MovementInput.forward(b.yaw(), false));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(), b.rec(), true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built rotation(int seed) {
        Built b = scene(); List<MovementInput> in = List.of(MovementInput.idle(30f), MovementInput.idle(60f), MovementInput.forward(90f, false));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(), b.rec(), true);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built noTickEnds(int seed) {
        Built b = scene(); List<MovementInput> in = new ArrayList<>(); for (int i = 0; i < 3; i++) in.add(MovementInput.forward(b.yaw(), false));
        Truth t = simulate(b.ps(), b.env(), b.start(), in, List.of(), b.rec(), false);
        for (TruthTick tt : t.ticks()) if (tt.transmission() != null) b.truthBySequence().put(tt.transmission().sequence(), tt.after());
        return b;
    }
    static Built discontinuity(int seed) {
        Built b = scene(); Recorder rec = b.rec(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS); rec.discontinuity(1); rec.tickEnd(); rec.advance(TICK_NANOS); rec.movement(100.5, 80, 100.5, b.yaw(), true);
        return b;
    }
    static Built unresolved(int seed) {
        Built b = scene(); Recorder rec = b.rec(); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(1_000_000L); rec.unresolvedMotion(); rec.advance(TICK_NANOS);
        PhysicsResult pr = b.ps().simulator.simulate(b.start(), MovementInput.forward(b.yaw(), false), b.env()); TimingEvent.MovementObserved m = rec.movement(pr.next().position().x(), pr.next().position().y(), pr.next().position().z(), b.yaw(), pr.next().onGround());
        return b;
    }
    static Built overflow(int seed) {
        Built b = scene(); Recorder rec = b.rec(); rec.advance(1_000_000L); rec.tickEnd(); for (int i = 0; i < 9; i++) rec.velocity(0.1 * i, 0.4, 0); rec.advance(TICK_NANOS); rec.movement(8.6, SURFACE, 8.5, b.yaw(), true);
        return b;
    }
    static Built expired(int seed) {
        Built b = scene(); Recorder rec = b.rec(); rec.velocity(0.3, 0.4, 0); rec.advance(4_000_000_000L); rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS);
        PhysicsResult pr = b.ps().simulator.simulate(b.start(), MovementInput.forward(b.yaw(), false), b.env()); rec.movement(pr.next().position().x(), pr.next().position().y(), pr.next().position().z(), b.yaw(), pr.next().onGround());
        return b;
    }

    /** Runs the chain over a built case and summarizes it (statuses, hypothesis ids, truth retention, digest). */
    static Map<String, Object> summary(Built b) throws Exception {
        TimingReconstruction timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(b.ps().simulator));
        Recorder rec = b.rec();
        TimingReconstruction.Frontier frontier = TimingReconstruction.Frontier.of(b.start());
        long anchorSeq = 100, anchorNanos = 1_000_000_000L; boolean inTick = true; Vector3 lastSent = new Vector3(b.start().position().x(), b.start().position().y(), b.start().position().z()); float lastYaw = b.yaw();
        Map<String, TimingEvent.ExternalMotionSent> sent = new LinkedHashMap<>(); int from = 0;
        List<Object> steps = new ArrayList<>(); MessageDigest md = MessageDigest.getInstance("SHA-256"); int retained = 0, claimed = 0; long evaluations = 0;
        for (int i = 0; i < rec.events.size(); i++) {
            TimingEvent e = rec.events.get(i);
            if (e instanceof TimingEvent.ExternalMotionSent m) sent.put(m.id(), m);
            if (!(e instanceof TimingEvent.MovementObserved target)) continue;
            TimingRequest req = new TimingRequest(new ArrayList<>(rec.events.subList(from, i + 1)), anchorSeq, anchorNanos, inTick, lastSent, lastYaw, target, new ArrayList<>(sent.values()), rec.ledger.view().teleports(), rec.ledger.view().rtt(), rec.ledger.view().tickEndCapable(), false, rec.ledger.view().worldEpoch(), rec.nanos + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
            from = i + 1;
            TimingReconstruction.Result res = timing.run(req, frontier, b.env(), ReconstructionRequest.SearchBudget.DEFAULT);
            evaluations += res.work().evaluations();
            Map<String, Object> st = new LinkedHashMap<>(); st.put("sequence", target.sequence()); st.put("status", res.status().name()); st.put("complete", res.complete());
            List<String> ids = new ArrayList<>(); for (var reso : res.resolutions()) { st.put("timing", reso.status().name()); for (var h : reso.hypotheses()) ids.add(h.id()); } st.put("hypotheses", ids);
            List<String> explaining = new ArrayList<>(); for (var s : res.explaining()) explaining.add(s.hypothesis().id()); st.put("explaining", explaining); st.put("frontier", res.next().nodes().size());
            PhysicsState truth = b.truthBySequence().get(target.sequence());
            if (truth != null) { claimed++; boolean ok = res.next().nodes().stream().anyMatch(n -> n.state().equals(truth)); if (ok) retained++; st.put("truthRetained", ok); }
            for (var reso : res.resolutions()) for (var h : reso.hypotheses()) md.update(h.describe().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (var n : res.next().nodes()) md.update(n.state().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            steps.add(st);
            if (res.next().isEmpty()) break;
            frontier = res.next(); anchorSeq = target.sequence(); anchorNanos = target.nanos(); inTick = res.targetInTick();
            if (target.hasPosition()) lastSent = target.position(); if (target.hasRotation()) lastYaw = target.rotation().yaw();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", rec.events.size()); out.put("steps", steps); out.put("truthClaimed", claimed); out.put("truthRetained", retained); out.put("evaluations", evaluations);
        var rtt = rec.ledger.view().rtt(); out.put("rttSamples", rtt.samples()); out.put("rttOutliers", rtt.outliers()); out.put("rttSmoothedNanos", rtt.smoothedNanos()); out.put("rttJitterNanos", rtt.jitterNanos());
        StringBuilder hex = new StringBuilder(); for (byte x : md.digest()) hex.append(String.format("%02x", x)); out.put("digest", hex.toString());
        return out;
    }
}
