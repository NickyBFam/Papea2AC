package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
/**
 * Empirical memory harness for Phase 7 (same methodology as the Phase 2-6 harnesses: forced GC, used-heap diff, 3 trials, median;
 * estimates, never a supported-capacity claim) plus thread-allocation measurements. Measures: one empty TimingLedger (estimator +
 * marker ledger + empty ring), a ledger filled to its ring bound, a ledger with the marker/teleport caps full, retained
 * TimingResolution / TimingReconstruction.Result values if a consumer kept 100, and per-resolution allocation for timing only
 * versus timing + Phase 6 + Phase 5 (brief sections 49, 51, 69).
 */
public final class TimingMemoryMain {
    private TimingMemoryMain() {}
    private static void settle() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    private static long usedBytes() { var rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }
    private static double median(long[] v) { long[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }
    static double measure(java.util.function.Supplier<Object> s) { long[] d = new long[3]; for (int t = 0; t < 3; t++) { settle(); long b = usedBytes(); Object keep = s.get(); settle(); d[t] = usedBytes() - b; if (keep.hashCode() == 42) System.out.print(""); } return median(d); }
    static long alloc(Runnable r) { for (int i = 0; i < 200; i++) r.run(); var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean(); long id = Thread.currentThread().threadId(); long b = mx.getThreadAllocatedBytes(id); int n = 50; for (int i = 0; i < n; i++) r.run(); return (mx.getThreadAllocatedBytes(id) - b) / n; }

    public static void main(String[] args) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("phase", "7 - Latency / Acknowledgement / External-Motion Timing"); out.put("method", "forced GC + used-heap diff, 3 trials, median; thread-allocation counters for per-resolution garbage; estimates on one host, not capacity");
        SessionKey key = new SessionKey(new UUID(9, 9), 1);
        out.put("emptyLedgerBytes", Math.round(measure(() -> new TimingLedger(key))));
        out.put("ledgerAtRingBoundBytes", Math.round(measure(() -> { TimingLedger l = new TimingLedger(key); long seq = 1; for (int i = 0; i < 3000; i++) l.apply(key, new TimingEvent.ClientTickEnd(seq++, i * 50_000_000L)); return l; })));
        out.put("ledgerWithCapsFullBytes", Math.round(measure(() -> { TimingLedger l = new TimingLedger(key); long seq = 1; for (int i = 0; i < 40; i++) l.apply(key, new TimingEvent.MarkerSent(seq++, i, TimingEvent.MarkerKind.PING, i, 100 + i)); for (int i = 0; i < 10; i++) l.apply(key, new TimingEvent.TeleportSent(seq++, 1000 + i, i, 500 + i, Vector3.ZERO, new me.nicolas.papea2ac.model.Rotation(0, 0), Vector3.ZERO, 0)); for (int i = 0; i < 40; i++) l.apply(key, new TimingEvent.MarkerAcknowledged(seq++, 20_000_000L + i, TimingEvent.MarkerKind.PING, i)); return l; })));
        out.put("rttEstimatorBytes", Math.round(measure(() -> { RttEstimator e = new RttEstimator(); for (int i = 0; i < 40; i++) e.accept(20_000_000L, i); return e; })));
        out.put("bounds", "event ring 2048 records / 256 KiB / 3 s; 32 pending markers + 64 tombstones; 8 corrections; 32 RTT samples; diagnostic event capture <= 4096 events only while a trace is armed (P7-F5), cleared when it is cancelled; nothing keyed by UUID beyond the session map");
        // resolutions
        PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5);
        TimingResolver resolver = new TimingResolver(); TimingReconstruction timing = new TimingReconstruction(resolver, new InputReconstructor(ps.simulator));
        Map<String, TimingRequest> families = new LinkedHashMap<>();
        families.put("one_tick_no_influence", oneTick(rest, 0));
        families.put("one_tick_velocity_2_branches", oneTick(rest, 1));
        families.put("three_ticks_two_influences", threeTicks(rest, 2));
        families.put("two_tick_gap", gap(rest, 2)); families.put("three_tick_gap", gap(rest, 3));
        Map<String, Object> perResolution = new LinkedHashMap<>();
        for (var e : families.entrySet()) {
            TimingRequest req = e.getValue(); TimingReconstruction.Frontier f = TimingReconstruction.Frontier.of(rest);
            long timingOnly = alloc(() -> resolver.resolve(req)); long endToEnd = alloc(() -> timing.run(req, f, env, ReconstructionRequest.SearchBudget.DEFAULT));
            TimingReconstruction.Result r = timing.run(req, f, env, ReconstructionRequest.SearchBudget.DEFAULT);
            Map<String, Object> m = new LinkedHashMap<>(); m.put("timingOnlyBytes", timingOnly); m.put("timingPlusReconstructionBytes", endToEnd); m.put("hypotheses", r.work().hypotheses()); m.put("reconstructions", r.work().reconstructions()); m.put("evaluations", r.work().evaluations()); m.put("status", r.status().name()); perResolution.put(e.getKey(), m);
        }
        out.put("perResolutionAllocationBytes", perResolution);
        TimingRequest keep = families.get("one_tick_velocity_2_branches");
        out.put("retainedBytesPer100TimingResolutions", Math.round(measure(() -> { List<Object> l = new ArrayList<>(); for (int i = 0; i < 100; i++) l.add(resolver.resolve(keep)); return l; })));
        out.put("retainedBytesPer100EndToEndResults", Math.round(measure(() -> { List<Object> l = new ArrayList<>(); TimingReconstruction.Frontier f = TimingReconstruction.Frontier.of(rest); for (int i = 0; i < 100; i++) l.add(timing.run(keep, f, env, ReconstructionRequest.SearchBudget.DEFAULT)); return l; })));
        out.put("perSessionRetained", "one TimingLedger (above) - the resolver and the adapter are stateless; requests, hypotheses and results are values the caller owns and drops");
        Path p = Path.of(args[0]); Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(out));
    }
    static TimingRequest oneTick(PhysicsState rest, int influences) {
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); List<TimingEvent.ExternalMotionSent> sent = new ArrayList<>();
        for (int i = 0; i < influences; i++) sent.add(rec.velocity(0.2, 0.4, 0.1 * i));
        PhysicsResult pr = new PhysicsScene().simulator.simulate(rest, MovementInput.forward(0f, false), new PhysicsScene().env());
        rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(pr.next().position().x(), pr.next().position().y(), pr.next().position().z(), 0f, pr.next().onGround());
        return request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1);
    }
    static TimingRequest threeTicks(PhysicsState rest, int influences) {
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); for (int i = 0; i < influences; i++) rec.velocity(0.2 + 0.1 * i, 0.4, 0);
        for (int i = 1; i < 3; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); } rec.advance(TICK_NANOS); TimingEvent.MovementObserved t = rec.movement(8.9, SURFACE + 0.5, 8.6, 0f, false);
        return request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1);
    }
    static TimingRequest gap(PhysicsState rest, int k) {
        PhysicsScene ps = new PhysicsScene(); PhysicsState s = rest; for (int i = 0; i < k; i++) s = ps.simulator.simulate(s, i == k - 1 ? MovementInput.forward(0f, false) : MovementInput.idle(0f), ps.env()).next(); // k-1 silent ticks then a move
        Recorder rec = new Recorder(); rec.advance(1_000_000L); rec.tickEnd(); for (int i = 1; i < k; i++) { rec.advance(TICK_NANOS); rec.tickEnd(); } rec.advance(TICK_NANOS);
        TimingEvent.MovementObserved t = rec.movement(s.position().x(), s.position().y(), s.position().z(), 0f, s.onGround());
        return request(rec, 100, 1_000_000_000L, new Vector3(8.5, SURFACE, 8.5), 0f, t, List.of(), TimingProfile.PAPER_26_2, TimingBudget.DEFAULT, rec.nanos + 1);
    }
}
