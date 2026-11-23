package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.checks.rotation.QuantizationFit;
import me.nicolas.papea2ac.checks.rotation.RotationAnalysis;
import me.nicolas.papea2ac.checks.rotation.RotationAnalysisConfig;
import me.nicolas.papea2ac.checks.rotation.RotationAnalyzer;
import me.nicolas.papea2ac.player.RotationHistory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
/**
 * Phase 15 corpus report and cost measurement. Writes a deterministic JSON summary (statuses and feature counts per scenario; the timing
 * section is measured and therefore not deterministic) to the path in args[0], or prints it.
 */
public final class RotationCorpusReportMain {
    private RotationCorpusReportMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, List<RotationCorpus.Stream>> scenarios = new LinkedHashMap<>();
        double[] grid = {0.0, 0.1, 0.25, 0.5, 0.75, 1.0};
        List<RotationCorpus.Stream> raw = new ArrayList<>(), smooth = new ArrayList<>(), fractional = new ArrayList<>(), scoping = new ArrayList<>(),
                largeYaw = new ArrayList<>(), change = new ArrayList<>(), teleport = new ArrayList<>(), clamp = new ArrayList<>();
        for (double s : grid) for (long seed = 1; seed <= 5; seed++) {
            long sd = seed * 1000 + (long) (s * 100);
            raw.add(RotationCorpus.legitimate("raw", sd, RotationCorpus.ClientOptions.raw(s), 1200));
            smooth.add(RotationCorpus.legitimate("smooth", sd, RotationCorpus.ClientOptions.raw(s).withSmooth(), 1200));
            fractional.add(RotationCorpus.legitimate("fractional", sd, RotationCorpus.ClientOptions.raw(s).withFractional(), 1200));
            scoping.add(RotationCorpus.legitimate("scoping", sd, RotationCorpus.ClientOptions.raw(s).withScoping(), 1200));
            largeYaw.add(RotationCorpus.legitimate("large-yaw", sd, RotationCorpus.ClientOptions.raw(s).withStart(20_000f, 0), 1200));
            clamp.add(RotationCorpus.legitimate("pitch-clamp", sd, RotationCorpus.ClientOptions.raw(s).withPitchBias(3).withStart(0, 80), 1200));
            change.add(RotationCorpus.sensitivityChange(sd, s, 1 - s, 1200));
            teleport.add(RotationCorpus.teleportMidway(sd, s, 1200));
        }
        scenarios.put("legit-raw", raw); scenarios.put("legit-smooth-camera", smooth); scenarios.put("legit-fractional-input", fractional);
        scenarios.put("legit-scoping", scoping); scenarios.put("legit-large-yaw-20000", largeYaw); scenarios.put("legit-pitch-clamp", clamp);
        scenarios.put("legit-sensitivity-change", change); scenarios.put("legit-teleport", teleport);
        for (var kind : RotationCorpus.Adversary.values()) {
            List<RotationCorpus.Stream> l = new ArrayList<>();
            for (double s : grid) for (long seed = 1; seed <= 5; seed++) l.add(RotationCorpus.adversarial(kind, seed * 1000 + (long) (s * 100), s, 1200));
            scenarios.put("adv-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-'), l);
        }

        var json = new StringBuilder("{\n  \"profile\": \"").append(RotationCorpus.kinematics().profileId()).append("\",\n  \"config\": \"")
                .append(RotationAnalysisConfig.DEFAULT).append("\",\n  \"scenarios\": {\n");
        int si = 0;
        for (var e : scenarios.entrySet()) {
            var statuses = new EnumMap<QuantizationFit.Status, Integer>(QuantizationFit.Status.class);
            var uncertain = new EnumMap<RotationAnalysis.Uncertainty, Integer>(RotationAnalysis.Uncertainty.class);
            int windows = 0, changed = 0, repeats = 0, large = 0, outside = 0, unitOk = 0;
            for (var s : e.getValue()) for (var w : windows(s)) {
                windows++;
                statuses.merge(w.fit().status(), 1, Integer::sum);
                for (var u : w.uncertainty()) uncertain.merge(u, 1, Integer::sum);
                if (w.unitContinuity() == RotationAnalysis.UnitContinuity.CHANGED) changed++;
                if (w.features().exactRepeats() > 0) repeats++;
                if (w.features().largeYawDeltas() > 0) large++;
                if (w.features().pitchOutsideProfile() > 0) outside++;
                if (w.fit().status() == QuantizationFit.Status.FIT) {
                    var k = RotationCorpus.kinematics();
                    double expected = e.getKey().equals("legit-scoping") ? k.unit(s.sensitivity(), true) : k.unit(s.sensitivity(), false);
                    double alternative = e.getKey().equals("legit-sensitivity-change") ? k.unit(1 - s.sensitivity(), false) : expected;
                    if (Math.abs(w.fit().unit() - expected) <= expected * 1e-4 || Math.abs(w.fit().unit() - alternative) <= alternative * 1e-4) unitOk++;
                }
            }
            json.append(String.format(Locale.ROOT, "    \"%s\": {\"streams\": %d, \"windows\": %d, \"status\": %s, \"uncertainty\": %s, \"fitUnitMatchesTrue\": %d, "
                            + "\"unitChanged\": %d, \"windowsWithExactRepeats\": %d, \"windowsWithLargeYaw\": %d, \"windowsWithPitchOutsideProfile\": %d}%s\n",
                    e.getKey(), e.getValue().size(), windows, map(statuses), map(uncertain), unitOk, changed, repeats, large, outside, ++si < scenarios.size() ? "," : ""));
        }
        json.append("  },\n  \"timing\": ").append(timing()).append("\n}\n");
        if (args.length > 0) { Files.writeString(Path.of(args[0]), json.toString()); System.out.println("wrote " + args[0]); }
        else System.out.print(json);
    }

    private static List<RotationAnalysis> windows(RotationCorpus.Stream s) {
        var h = new RotationHistory();
        var a = new RotationAnalyzer(RotationCorpus.kinematics(), RotationAnalysisConfig.DEFAULT, true);
        List<RotationAnalysis> out = new ArrayList<>();
        RotationCorpus.replay(s, h, () -> a.offer(h.snapshot()).ifPresent(out::add));
        return out;
    }

    private static String map(Map<? extends Enum<?>, Integer> m) {
        var b = new StringBuilder("{");
        for (var e : m.entrySet()) { if (b.length() > 1) b.append(", "); b.append('"').append(e.getKey().name()).append("\": ").append(e.getValue()); }
        return b.append('}').toString();
    }

    /** Wall-clock cost of one full-window evaluation (snapshot + analyze), legitimate and worst-case (no-fit) windows. Measured, not a gate. */
    private static String timing() {
        var legit = RotationCorpus.legitimate("t", 5, RotationCorpus.ClientOptions.raw(0.5), 200);
        var noise = RotationCorpus.adversarial(RotationCorpus.Adversary.SMOOTH_AIM_ASSIST, 5, 0.5, 200);
        return String.format(Locale.ROOT, "{\"legitFullWindowNanos\": %d, \"noFitFullWindowNanos\": %d, \"note\": \"median of 2000 evaluations after 20000 warm-up, one thread, JIT, not JMH\"}",
                measure(legit), measure(noise));
    }

    private static long measure(RotationCorpus.Stream s) {
        var h = new RotationHistory();
        RotationCorpus.replay(s, h, () -> {});
        var a = new RotationAnalyzer(RotationCorpus.kinematics(), RotationAnalysisConfig.DEFAULT, true);
        long sink = 0;
        for (int i = 0; i < 20_000; i++) sink += a.analyze(h.snapshot()).features().samples();
        long[] t = new long[2000];
        for (int i = 0; i < t.length; i++) { long t0 = System.nanoTime(); sink += a.analyze(h.snapshot()).features().samples(); t[i] = System.nanoTime() - t0; }
        java.util.Arrays.sort(t);
        if (sink == 42) System.out.print("");
        return t[t.length / 2];
    }
}
