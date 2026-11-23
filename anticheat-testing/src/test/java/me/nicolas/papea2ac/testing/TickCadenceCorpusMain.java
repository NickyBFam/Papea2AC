package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TickCadenceTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nicolas.papea2ac.version.TickCadenceProfile;
/**
 * Phase 14 offline qualification corpus (descriptive, deterministic): (1) a large legitimate corpus from {@link TickCadenceTestSupport#legit}
 * through the real ledger and validator - the false-exclusion count and the closest legitimate margin; (2) the detection latency of timer
 * factors under four network/frame profiles; (3) the effect of frame hitches on a slight timer. Writes
 * docs/phase14-artifacts/cadence-corpus-results.json. Args: [output path] [legit seeds].
 */
public final class TickCadenceCorpusMain {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/phase14-artifacts/cadence-corpus-results.json");
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        TickCadenceProfile p = TickCadenceProfile.PAPER_26_2;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("phase", "14 - Timer (client tick cadence), offline corpus"); root.put("profile", p.id());
        root.put("oracle", "TickCadenceSimulator: vanilla 26.2 client tick scheduling transcribed from the client bytecode (ClientCadenceFactsTest), two-way in-order network with jitter and stalls, server capture taps with event-loop pauses");
        // (1) legitimate corpus
        long invalid = 0, anchors = 0, windows = 0, worst = Long.MAX_VALUE, worstSeed = -1, simulatedNanos = 0, dropped = 0, ticks = 0, combatScenarios = 0, combatInvalid = 0;
        List<Long> invalidSeeds = new ArrayList<>();
        for (long seed = 1; seed <= seeds; seed++) {
            TickCadenceSimulator.Config c = legit(seed);
            TickCadenceSimulator.Trace t = TickCadenceSimulator.run(c);
            Replay r = replay(t, p);
            simulatedNanos += c.durationNanos; dropped += t.cappedTicksDropped(); ticks += t.ticksExecuted();
            anchors += r.results().size(); for (var res : r.results()) windows += res.windowsEvaluated();
            if (r.anyInvalid()) { invalid++; if (invalidSeeds.size() < 20) invalidSeeds.add(seed); }
            if (!c.combat.isEmpty()) { combatScenarios++; if (r.anyInvalid()) combatInvalid++; }
            if (r.worstLegitMargin() < worst) { worst = r.worstLegitMargin(); worstSeed = seed; }
        }
        Map<String, Object> legit = new LinkedHashMap<>();
        legit.put("scenarios", seeds); legit.put("simulatedHours", Math.round(simulatedNanos / 3.6e12 * 100) / 100.0); legit.put("clientTicks", ticks); legit.put("ticksDroppedByTheFrameCap", dropped);
        legit.put("combatMarkerScenarios", combatScenarios); legit.put("combatMarkerInvalid", combatInvalid); legit.put("anchors", anchors); legit.put("windowsEvaluated", windows); legit.put("invalid", invalid); legit.put("invalidSeeds", invalidSeeds);
        legit.put("closestMarginTicks", worst); legit.put("closestMarginSeed", worstSeed);
        legit.put("ranges", "40-120 s each; 1-5000 fps incl. 2-5 fps and frame period ~ tick period; hitches up to 3.2 s; process freezes up to 8.5 s; drift +-500 ppm; base delay 0-400 ms and jitter 0-300 ms per direction; up to 2 stalls of up to 10 s per direction; up to 2 server pauses of up to 2 s; markers 1 Hz or 20 Hz, +-100 ms jitter; 5 % failed ping writes; 10 % colliding plugin pings; 0-2 corrections/rotations per second; tick rate 5/10/40/100 or frozen then back to 20");
        root.put("legitimate", legit);
        // (2) detection latency
        Map<String, Object> detection = new LinkedHashMap<>();
        String[] profiles = { "lan", "internet-80ms", "jittery-250ms", "stall-9s", "internet-80ms-combat-markers" };
        double[] factors = { 1.02, 1.05, 1.1, 1.25, 1.5, 2.0, 3.0, 5.0 };
        for (String prof : profiles) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (double f : factors) {
                List<Double> first = new ArrayList<>(); int missed = 0;
                for (long seed = 1; seed <= 10; seed++) {
                    TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 1000 + seed; c.durationNanos = 300 * S; c.timerFactor = f; c.timerStartNanos = 30 * S; c.hitchProbability = 0.0002;
                    switch (prof) {
                        case "lan" -> { c.upBaseNanos = MS; c.downBaseNanos = MS; c.upJitterNanos = MS / 2; c.downJitterNanos = MS / 2; }
                        case "internet-80ms" -> { c.upBaseNanos = 40 * MS; c.downBaseNanos = 40 * MS; c.upJitterNanos = 10 * MS; c.downJitterNanos = 10 * MS; }
                        case "internet-80ms-combat-markers" -> { c.upBaseNanos = 40 * MS; c.downBaseNanos = 40 * MS; c.upJitterNanos = 10 * MS; c.downJitterNanos = 10 * MS; c.combat.add(new TickCadenceSimulator.Interval(25 * S, 300 * S)); }
                        case "jittery-250ms" -> { c.upBaseNanos = 100 * MS; c.downBaseNanos = 100 * MS; c.upJitterNanos = 150 * MS; c.downJitterNanos = 150 * MS; }
                        default -> { c.upBaseNanos = 50 * MS; c.downBaseNanos = 50 * MS; c.downStalls.add(new TickCadenceSimulator.Interval(60 * S, 69 * S)); c.upStalls.add(new TickCadenceSimulator.Interval(90 * S, 99 * S)); }
                    }
                    Replay r = replay(TickCadenceSimulator.run(c), p);
                    if (!r.anyInvalid()) missed++; else first.add(Math.round((r.firstInvalidNanos() - c.timerStartNanos) / 1e7) / 100.0);
                }
                first.sort(null);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("detected", 10 - missed); cell.put("of", 10);
                if (!first.isEmpty()) { cell.put("medianSeconds", first.get(first.size() / 2)); cell.put("maxSeconds", first.get(first.size() - 1)); }
                row.put("x" + f, cell);
            }
            detection.put(prof, row);
        }
        root.put("detectionLatencyAfterTimerStart", detection);
        // (3) hitches against a slight timer
        Map<String, Object> hitches = new LinkedHashMap<>();
        for (double h : new double[] { 0, 0.0005, 0.002, 0.01 }) {
            int detected = 0; long dropTotal = 0;
            for (long seed = 1; seed <= 10; seed++) {
                TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 2000 + seed; c.durationNanos = 300 * S; c.timerFactor = 1.05; c.hitchProbability = h;
                TickCadenceSimulator.Trace t = TickCadenceSimulator.run(c); dropTotal += t.cappedTicksDropped();
                if (replay(t, p).anyInvalid()) detected++;
            }
            Map<String, Object> cell = new LinkedHashMap<>(); cell.put("detectedOf10", detected); cell.put("meanTicksDroppedByCap", dropTotal / 10);
            hitches.put("hitchProbabilityPerFrame=" + h, cell);
        }
        root.put("timerX1.05UnderFrameHitches", hitches);
        root.put("cost", cost(p));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(root));
    }
    /** Descriptive single-host cost (not JMH, not a capacity claim): validator time and allocation per pong at full retention, ledger retained heap. */
    static Map<String, Object> cost(TickCadenceProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        // a full ledger: 20 Hz pongs for 700 s (dense + thinned + expired), ticks between
        me.nicolas.papea2ac.player.TickCadenceLedger l = new me.nicolas.papea2ac.player.TickCadenceLedger(KEY, p.maxWindowNanos());
        long seq = 0, t = 0;
        for (int i = 1; i <= 20 * 700; i++) { t += 50 * MS; l.apply(KEY, new me.nicolas.papea2ac.player.TickCadenceEvent.PingSent(++seq, t, i, i)); l.apply(KEY, new me.nicolas.papea2ac.player.TickCadenceEvent.TickEnd(++seq, t + MS)); l.apply(KEY, new me.nicolas.papea2ac.player.TickCadenceEvent.PingAnswered(++seq, t + 40 * MS, i)); }
        int anchors = l.anchors().size();
        me.nicolas.papea2ac.simulation.TickCadenceValidator v = new me.nicolas.papea2ac.simulation.TickCadenceValidator();
        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 20_000; i++) v.evaluate(KEY, l.anchors(), p, true); // warm-up
        int runs = 50_000; long a0 = mx.getCurrentThreadAllocatedBytes(), t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) v.evaluate(KEY, l.anchors(), p, true);
        long elapsed = System.nanoTime() - t0, alloc = mx.getCurrentThreadAllocatedBytes() - a0;
        m.put("anchorsAtFullRetention", anchors); m.put("validatorNanosPerPong", elapsed / runs); m.put("validatorBytesPerPong", alloc / runs);
        // per-event ledger cost (publishes an immutable view per event)
        long e0 = mx.getCurrentThreadAllocatedBytes(), s0 = System.nanoTime(); int events = 200_000;
        for (int i = 0; i < events; i++) l.apply(KEY, new me.nicolas.papea2ac.player.TickCadenceEvent.TickEnd(++seq, t + i));
        m.put("ledgerNanosPerTickEnd", (System.nanoTime() - s0) / events); m.put("ledgerBytesPerTickEnd", (mx.getCurrentThreadAllocatedBytes() - e0) / events);
        // retained heap of 200 full ledgers (forced GC heap difference, median of 3)
        List<Long> diffs = new ArrayList<>();
        for (int trial = 0; trial < 3; trial++) {
            List<me.nicolas.papea2ac.player.TickCadenceLedger> keep = new ArrayList<>();
            long before = used();
            for (int k = 0; k < 200; k++) {
                me.nicolas.papea2ac.player.TickCadenceLedger x = new me.nicolas.papea2ac.player.TickCadenceLedger(KEY, p.maxWindowNanos());
                long s = 0, tt = 0;
                for (int i = 1; i <= 20 * 700; i++) { tt += 50 * MS; x.apply(KEY, new me.nicolas.papea2ac.player.TickCadenceEvent.PingSent(++s, tt, i, i)); x.apply(KEY, new me.nicolas.papea2ac.player.TickCadenceEvent.PingAnswered(++s, tt + 40 * MS, i)); }
                keep.add(x);
            }
            diffs.add((used() - before) / keep.size()); keep.clear();
        }
        diffs.sort(null); m.put("retainedBytesPerFullLedger", diffs.get(1));
        m.put("note", "one host, descriptive; JMH and the end-to-end engine cost are integration work");
        return m;
    }
    static long used() { for (int i = 0; i < 4; i++) System.gc(); Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
}
