package me.nicolas.papea2ac.testing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
/**
 * Writes the Phase 7 evidence artifacts under docs/phase7-artifacts: the deterministic timing fixtures
 * ({@code timing-fixtures/timing-fixtures.json}), the round-trip / velocity / teleport / fuzz gate results and the RTT model
 * results, all summarized (no raw packet dumps, no live objects). A changed fixture digest is a reviewed event.
 */
public final class TimingFixtureMain {
    private TimingFixtureMain() {}
    public static void main(String[] args) throws Exception {
        Path artifacts = Path.of(args[0]); Files.createDirectories(artifacts.resolve("timing-fixtures"));
        // ---- fixtures
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("phase", "7 - Latency / Acknowledgement / External-Motion Timing"); root.put("source", "TimingFixtures catalog (anticheat-testing): synthetic ordered traces over the Phase 3/4/5 test scene, walked through TimingResolver + TimingReconstruction; summaries only");
        Map<String, Object> cases = new LinkedHashMap<>();
        for (TimingFixtures.Case c : TimingFixtures.all()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("description", c.description()); m.putAll(TimingFixtures.summary(c.build().get())); cases.put(c.id(), m); }
        root.put("cases", cases);
        Files.writeString(artifacts.resolve("timing-fixtures").resolve("timing-fixtures.json"), MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("timing fixtures: " + cases.size() + " cases");
        // ---- velocity round trip
        StringBuilder failures = new StringBuilder();
        VelocityTimingRoundTripTest.Stats v = VelocityTimingRoundTripTest.run(7_000, 1_500, true, failures);
        VelocityTimingRoundTripTest.Stats vNoTick = VelocityTimingRoundTripTest.run(9_000, 200, false, failures);
        Map<String, Object> vel = new LinkedHashMap<>();
        vel.put("phase", "7"); vel.put("gate", "hidden application tick / input never excluded: the true client state is in the union frontier at every transmission and the true application tick is among the explaining hypotheses");
        vel.put("withTickEnds", stats(v)); vel.put("withoutTickEnds", stats(vNoTick)); vel.put("failures", failures.length() == 0 ? 0 : failures.toString().split("\n").length); vel.put("seeds", "7000.. and 9000.. (SplittableRandom, deterministic)");
        Files.writeString(artifacts.resolve("velocity-timing-results.json"), MiniJson.write(vel) + "\n", StandardCharsets.UTF_8);
        // ---- teleport round trip
        var ps = new PhysicsTestSupport.PhysicsScene(); int tcases = 0, tapp = 0, tpre = 0, tlost = 0; List<Long> lostSeeds = new ArrayList<>();
        for (long seed = 30_000; seed < 30_500; seed++) {
            TeleportTimingRoundTripTest.Case c = TeleportTimingRoundTripTest.run(seed, ps, ps.env()); if (c == null) continue; tcases++;
            for (TimingTestSupport.ChainStep s : c.chain()) {
                if (s.truthAfter() != null && !s.truthRetained()) { tlost++; lostSeeds.add(seed); break; }
                if (s.target() == c.applicationTx()) tapp++;
                else if (s.target().sequence() < c.applicationTx().sequence() && s.result().resolutions().stream().anyMatch(res -> res.hypotheses().stream().anyMatch(h -> h.teleport() == me.nicolas.papea2ac.simulation.TimingHypothesis.TeleportRelation.PRE_APPLICATION))) tpre++;
            }
        }
        Map<String, Object> tp = new LinkedHashMap<>(); tp.put("phase", "7"); tp.put("gate", "the confirm's position is the application point, pre-application movement stays ordinary movement, the post-correction state is retained, no physics bridges the discontinuity");
        tp.put("cases", tcases); tp.put("applicationPointsResolved", tapp); tp.put("preApplicationTransmissions", tpre); tp.put("truthLost", tlost); tp.put("lostSeeds", lostSeeds); tp.put("seeds", "30000..30499");
        Files.writeString(artifacts.resolve("teleport-timing-results.json"), MiniJson.write(tp) + "\n", StandardCharsets.UTF_8);
        // ---- fuzz
        StringBuilder ff = new StringBuilder(); TimingFuzzTest.Result f = TimingFuzzTest.fuzz(90_000, 2_000, ff);
        Map<String, Object> fz = new LinkedHashMap<>(); fz.put("phase", "7"); fz.put("cases", f.cases()); fz.put("transmissions", f.steps()); fz.put("statuses", f.statuses()); fz.put("influences", f.influences()); fz.put("teleports", f.teleports()); fz.put("silentClosures", f.closures()); fz.put("evaluations", f.evaluations()); fz.put("maxHypotheses", f.maxHypotheses()); fz.put("truthLost", f.lost()); fz.put("seeds", "90000..91999");
        Files.writeString(artifacts.resolve("timing-fuzz-results.json"), MiniJson.write(fz) + "\n", StandardCharsets.UTF_8);
        // ---- combined round-trip summary
        Map<String, Object> rt = new LinkedHashMap<>(); rt.put("phase", "7"); rt.put("velocityCases", v.cases() + vNoTick.cases()); rt.put("velocityTransmissions", v.steps() + vNoTick.steps()); rt.put("teleportCases", tcases); rt.put("fuzzCases", f.cases()); rt.put("fuzzTransmissions", f.steps());
        rt.put("truthExcluded", (failures.length() == 0 ? 0 : 1) + tlost + f.lost()); rt.put("propertyTests", "TimingPropertyTest (6): determinism, monotone uncertainty, timeouts never certainty, bounded search, large timestamps, actual application tick never excluded");
        Files.writeString(artifacts.resolve("timing-roundtrip-results.json"), MiniJson.write(rt) + "\n", StandardCharsets.UTF_8);
        // ---- RTT model
        Map<String, Object> rtt = new LinkedHashMap<>(); rtt.put("phase", "7"); rtt.put("estimator", "RttEstimator: 32-sample window, smoothed (alpha 1/8) + jitter (beta 1/4), outlier = > 3*smoothed + 4*jitter + 20 ms (kept in the window, not folded), re-seed after 3 consecutive outliers, rejects <= 0 and > 60 s, stale after 30 s");
        rtt.put("samplesFrom", List.of("PING/PONG markers (issued by core through the platform port at 1 Hz per session plus a bracket after each outbound influence/correction, at most one per 50 ms, 16 pending)", "KEEPALIVE (Paper 26.2 server cadence 1 s: one challenge per second, pending queue, 30 s limit - P7-F3)")); rtt.put("semantics", "capture-monotonic interval encoder tap -> pre-process tap: network round trip plus the client's network-thread handling; never a client tick, never halved into a one-way delay");
        rtt.put("scenarios", scenarios());
        Files.writeString(artifacts.resolve("rtt-model-results.json"), MiniJson.write(rtt) + "\n", StandardCharsets.UTF_8);
        System.out.println("timing artifacts -> " + artifacts + " velocity=" + v + " teleport cases=" + tcases + " lost=" + tlost + " fuzz=" + f);
        if (failures.length() > 0 || ff.length() > 0 || tlost > 0) { System.err.println(failures); System.err.println(ff); System.exit(1); }
    }
    static Map<String, Object> stats(VelocityTimingRoundTripTest.Stats s) { Map<String, Object> m = new LinkedHashMap<>(); m.put("cases", s.cases()); m.put("transmissions", s.steps()); m.put("resolvedUnique", s.uniqueSteps()); m.put("resolvedAmbiguous", s.ambiguousSteps()); m.put("influences", s.influences()); m.put("maxHypotheses", s.maxHypotheses()); m.put("evaluations", s.evaluations()); return m; }
    static List<Object> scenarios() {
        List<Object> out = new ArrayList<>(); long MS = 1_000_000L;
        for (String[] sc : new String[][] { { "stable_20ms", "20", "0", "0" }, { "jitter_20_60ms", "20", "40", "0" }, { "outlier_spike", "20", "0", "1" }, { "shift_20_to_400ms", "20", "0", "2" } }) {
            var e = new me.nicolas.papea2ac.player.RttEstimator(); SplittableRandom r = new SplittableRandom(1); long t = 0;
            for (int i = 0; i < 40; i++) { t += 1000 * MS; long base = Long.parseLong(sc[1]), jit = Long.parseLong(sc[2]); long sample = (base + (jit > 0 ? r.nextInt((int) jit) : 0)) * MS; if (sc[3].equals("1") && i == 20) sample = 5000 * MS; if (sc[3].equals("2") && i >= 30) sample = 400 * MS; e.accept(sample, t); }
            var s = e.summary(); Map<String, Object> m = new LinkedHashMap<>(); m.put("scenario", sc[0]); m.put("samples", s.samples()); m.put("smoothedMs", s.smoothedNanos() / 1e6); m.put("jitterMs", s.jitterNanos() / 1e6); m.put("minMs", s.minNanos() / 1e6); m.put("maxMs", s.maxNanos() / 1e6); m.put("outliers", s.outliers()); m.put("envelopeHighMs", s.envelopeHighNanos() / 1e6); out.add(m);
        }
        return out;
    }
}
