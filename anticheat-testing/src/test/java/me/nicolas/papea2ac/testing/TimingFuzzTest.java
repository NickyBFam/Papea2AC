package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 7 fuzz (brief section 59): random bounded scenarios - world (flat / course / ice), random legal inputs with idle runs (silent
 * ticks, long enough to exercise the silent closure), 0-2 influences with hidden application ticks and network leads, an optional
 * mid-trace absolute correction applied after 0-2 client ticks, yaw changes (rotation-only packets), and marker/answer traffic with
 * random RTT jitter. The hidden truth (every per-tick client state) must be retained in the union frontier at every transmission.
 * Statistics of the run are written to the artifact by TimingFixtureMain; here they are asserted and printed with the seed.
 */
class TimingFuzzTest {
    record Result(int cases, int steps, Map<String, Integer> statuses, int influences, int teleports, int closures, long evaluations, int maxHypotheses, int lost) {}

    /** One generated scenario: the world, the true start, the recorder holding every event, and the hidden truth (state per transmission); null when the seed produced an unsimulable case. */
    record Scenario(long seed, ReconstructionRoundTripTest.World world, PhysicsState start, Recorder rec, List<PhysicsState> truthStates, List<TimingEvent.MovementObserved> truthTx, List<Influence> influences, boolean teleport, List<MovementInput> firstInputs, boolean simulable) {
        PhysicsState truthFor(TimingEvent.MovementObserved target) { int idx = truthTx.indexOf(target); return idx >= 0 ? truthStates.get(idx) : null; }
    }
    static Scenario generate(long seed) {
        List<ReconstructionRoundTripTest.World> worlds = ReconstructionRoundTripTest.worlds();
        SplittableRandom r = new SplittableRandom(seed);
        ReconstructionRoundTripTest.World w = worlds.get(r.nextInt(worlds.size()));
        PhysicsState start; try { start = settled(w.ps(), w.x0() + r.nextInt(5) - 2 + 0.5, SURFACE, w.z0() + r.nextInt(5) - 2 + 0.5); } catch (AssertionError e) { return new Scenario(seed, null, null, null, List.of(), List.of(), List.of(), false, List.of(), false); }
        float yaw = ReconstructionRoundTripTest.randomYaw(r);
        // segment 1
        int n1 = 2 + r.nextInt(8); List<MovementInput> in1 = new ArrayList<>(); int idleRun = 0;
        for (int i = 0; i < n1; i++) { if (idleRun > 0) { in1.add(MovementInput.idle(yaw)); idleRun--; continue; } if (r.nextInt(6) == 0) idleRun = 1 + r.nextInt(6); if (r.nextInt(7) == 0) yaw = ReconstructionRoundTripTest.randomYaw(r); in1.add(r.nextInt(4) == 0 ? MovementInput.idle(yaw) : ReconstructionRoundTripTest.randomInput(r, yaw)); }
        List<Influence> infl = new ArrayList<>(); int count = r.nextInt(3); int lastApp = 0, lastEmit = 0;
        for (int i = 0; i < count; i++) { int app = Math.min(n1, lastApp + 1 + r.nextInt(3)); if (app <= lastApp) break; int lead = Math.min(r.nextInt(3), app - 1); int emit = app - lead; if (emit < lastEmit) { lead = app - lastEmit; emit = lastEmit; } boolean set = r.nextBoolean(); infl.add(new Influence(set ? TimingEvent.Operation.SET : TimingEvent.Operation.ADD, new Vec3d((r.nextInt(61) - 30) / 100.0, 0.2 + r.nextInt(30) / 100.0, (r.nextInt(61) - 30) / 100.0), app, lead)); lastApp = app; lastEmit = emit; }
        Recorder rec = new Recorder();
        // marker traffic with jitter
        for (int i = 0; i < 3; i++) { rec.marker(TimingEvent.MarkerKind.PING, i); rec.advance((10 + r.nextInt(40)) * 1_000_000L); rec.ack(TimingEvent.MarkerKind.PING, i); }
        Truth t1; try { t1 = simulate(w.ps(), w.env(), start, in1, infl, rec, true); } catch (AssertionError e) { return new Scenario(seed, null, null, null, List.of(), List.of(), List.of(), false, List.of(), false); }
        List<PhysicsState> truthStates = new ArrayList<>(); List<TimingEvent.MovementObserved> truthTx = new ArrayList<>();
        for (TruthTick tt : t1.ticks()) { truthStates.add(tt.after()); truthTx.add(tt.transmission()); }
        // optional correction: sent, client keeps ticking `lead` ticks (segment 2 pre), then applies, then segment 3
        boolean teleport = r.nextInt(3) == 0; PhysicsState post = null; TimingEvent.MovementObserved applied = null;
        if (teleport) {
            PhysicsState s = t1.ticks().get(t1.ticks().size() - 1).after();
            double tx = w.x0() + 4.5 + r.nextInt(3), tz = w.z0() + 4.5 + r.nextInt(3); long id = 50 + r.nextInt(9);
            rec.advance(TICK_NANOS / 8); rec.teleport(id, tx, SURFACE, tz, yaw, 0);
            int lead = r.nextInt(3);
            if (lead > 0) { List<MovementInput> pre = new ArrayList<>(); for (int i = 0; i < lead; i++) pre.add(r.nextInt(3) == 0 ? MovementInput.idle(yaw) : ReconstructionRoundTripTest.randomInput(r, yaw));
                Truth tp; try { tp = simulate(w.ps(), w.env(), s, pre, List.of(), rec, true, false); } catch (AssertionError e) { return new Scenario(seed, null, null, null, List.of(), List.of(), List.of(), true, List.of(), false); }
                for (TruthTick tt : tp.ticks()) { truthStates.add(tt.after()); truthTx.add(tt.transmission()); } s = tp.ticks().get(tp.ticks().size() - 1).after(); }
            rec.advance(TICK_NANOS / 4); rec.confirm(id); rec.advance(100_000L); applied = rec.movement(tx, SURFACE, tz, yaw, s.onGround());
            post = s.withPosition(new Vec3d(tx, SURFACE, tz)).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE);
            truthStates.add(post); truthTx.add(applied);
            int n3 = 2 + r.nextInt(5); List<MovementInput> in3 = new ArrayList<>(); for (int i = 0; i < n3; i++) in3.add(r.nextInt(3) == 0 ? MovementInput.idle(yaw) : ReconstructionRoundTripTest.randomInput(r, yaw));
            Truth t3; try { t3 = simulate(w.ps(), w.env(), post, in3, List.of(), rec, true, false); } catch (AssertionError e) { return new Scenario(seed, null, null, null, List.of(), List.of(), List.of(), true, List.of(), false); }
            for (TruthTick tt : t3.ticks()) { truthStates.add(tt.after()); truthTx.add(tt.transmission()); }
        }
        return new Scenario(seed, w, start, rec, truthStates, truthTx, infl, teleport, in1, true);
    }
    static Result fuzz(long seed0, int cases, StringBuilder failures) {
        int done = 0, steps = 0, influences = 0, teleports = 0, closures = 0, maxHyp = 0, lost = 0; long evaluations = 0; Map<String, Integer> statuses = new TreeMap<>();
        for (int c = 0; c < cases; c++) {
            long seed = seed0 + c;
            Scenario sc = generate(seed);
            if (sc.teleport()) teleports++;
            if (!sc.simulable()) continue;
            ReconstructionRoundTripTest.World w = sc.world(); PhysicsState start = sc.start(); Recorder rec = sc.rec(); List<PhysicsState> truthStates = sc.truthStates(); List<TimingEvent.MovementObserved> truthTx = sc.truthTx(); List<Influence> infl = sc.influences(); List<MovementInput> in1 = sc.firstInputs();
            // chain
            TimingReconstruction timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(w.ps().simulator));
            TimingReconstruction.Frontier frontier = TimingReconstruction.Frontier.of(start);
            long anchorSeq = 100, anchorNanos = 1_000_000_000L; boolean inTick = true; Vector3 lastSent = new Vector3(start.position().x(), start.position().y(), start.position().z()); float lastYaw = in1.get(0).yaw();
            Map<String, TimingEvent.ExternalMotionSent> sent = new LinkedHashMap<>(); int from = 0; boolean caseLost = false;
            for (int i = 0; i < rec.events.size() && !caseLost; i++) {
                TimingEvent e = rec.events.get(i);
                if (e instanceof TimingEvent.ExternalMotionSent m) sent.put(m.id(), m);
                if (!(e instanceof TimingEvent.MovementObserved target)) continue;
                TimingRequest req = new TimingRequest(new ArrayList<>(rec.events.subList(from, i + 1)), anchorSeq, anchorNanos, inTick, lastSent, lastYaw, target, new ArrayList<>(sent.values()), rec.ledger.view().teleports(), rec.ledger.view().rtt(), true, false, 0, rec.nanos + 1, TimingProfile.PAPER_26_2, TimingBudget.DEFAULT);
                from = i + 1;
                TimingReconstruction.Result res = timing.run(req, frontier, w.env(), ReconstructionRequest.SearchBudget.DEFAULT);
                steps++; evaluations += res.work().evaluations(); maxHyp = Math.max(maxHyp, res.work().hypotheses()); statuses.merge(res.status().name(), 1, Integer::sum);
                for (var reso : res.resolutions()) for (var h : reso.hypotheses()) if (h.closureTicks() > 0) { closures++; break; }
                int idx = truthTx.indexOf(target); final PhysicsState truth = idx >= 0 ? truthStates.get(idx) : null;
                if (truth != null && res.next().nodes().stream().noneMatch(nd -> nd.state().equals(truth))) { lost++; caseLost = true; failures.append("seed ").append(seed).append(" world ").append(w.name()).append(": truth lost at seq ").append(target.sequence()).append(' ').append(String.join(" | ", res.describe())).append('\n'); }
                if (res.next().isEmpty()) break;
                frontier = res.next(); anchorSeq = target.sequence(); anchorNanos = target.nanos(); inTick = res.targetInTick();
                if (target.hasPosition()) lastSent = target.position(); if (target.hasRotation()) lastYaw = target.rotation().yaw();
            }
            done++; influences += infl.size();
            if (failures.length() > 30_000) break;
        }
        return new Result(done, steps, statuses, influences, teleports, closures, evaluations, maxHyp, lost);
    }

    @Test void thousandsOfRandomTimingScenariosRetainTheHiddenTruth() {
        StringBuilder failures = new StringBuilder();
        Result r = fuzz(90_000, 2_000, failures);
        System.out.println("timing fuzz: " + r);
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(r.cases() >= 1_200, "cases " + r.cases()); assertTrue(r.closures() > 20, "silent closures exercised: " + r.closures()); assertTrue(r.teleports() > 200, "teleports exercised: " + r.teleports());
        assertEquals(0, r.lost());
    }
}
