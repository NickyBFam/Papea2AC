package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TickCadenceEvent;
import me.nicolas.papea2ac.player.TickCadenceLedger;
import me.nicolas.papea2ac.simulation.TickCadenceResult;
import me.nicolas.papea2ac.simulation.TickCadenceValidator;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** Phase 14 test helpers: a session key, and the replay of a simulated stream through the real ledger and validator. */
final class TickCadenceTestSupport {
    static final long MS = 1_000_000L, S = 1_000_000_000L;
    static final SessionKey KEY = new SessionKey(new UUID(14, 14), 1);
    private TickCadenceTestSupport() {}

    /** What the engine concluded about one simulated stream. */
    record Replay(List<TickCadenceResult> results, long valid, long uncertain, long invalid, long firstInvalidNanos, long worstLegitMargin, TickCadenceResult firstInvalid, TickCadenceLedger.View view) {
        boolean anyInvalid() { return invalid > 0; }
    }
    static Replay replay(TickCadenceSimulator.Trace trace, TickCadenceProfile profile) { return replay(trace.events(), trace.realStart(), profile); }
    static Replay replay(List<TickCadenceEvent> events, long origin, TickCadenceProfile profile) {
        TickCadenceLedger ledger = new TickCadenceLedger(KEY, profile.maxWindowNanos());
        TickCadenceValidator validator = new TickCadenceValidator();
        List<TickCadenceResult> results = new ArrayList<>();
        long valid = 0, uncertain = 0, invalid = 0, firstInvalid = -1, margin = Long.MAX_VALUE; TickCadenceResult first = null;
        for (TickCadenceEvent e : events) {
            Optional<TickCadenceLedger.Anchor> a = ledger.apply(KEY, e);
            if (a.isEmpty()) continue;
            TickCadenceResult r = validator.evaluate(KEY, ledger.anchors(), profile, true);
            results.add(r);
            switch (r.classification()) {
                case VALID -> { valid++; margin = Math.min(margin, -r.worst().excess()); }
                case UNCERTAIN -> uncertain++;
                case INVALID -> { invalid++; if (first == null) { first = r; firstInvalid = r.targetNanos() - origin; } }
            }
        }
        return new Replay(results, valid, uncertain, invalid, firstInvalid, margin, first, ledger.view());
    }
    /** Random combat episodes (3-20 s, the 3 s attack window extended by repeated attacks) covering a large share of the scenario. */
    static void combat(TickCadenceSimulator.Config c, java.util.SplittableRandom r) {
        long t = r.nextLong(5 * S);
        while (t < c.durationNanos) { long len = (3 + r.nextInt(18)) * S; c.combat.add(new TickCadenceSimulator.Interval(t, t + len)); t += len + r.nextLong(10 * S); }
        c.combatMarkerProbability = 0.5 + r.nextDouble() / 2;
    }
    /** A legitimate scenario drawn from wide ranges of frame pacing, hitches, freezes, network delay/jitter/stalls, server pauses, drift, marker cadence and server packets. */
    static TickCadenceSimulator.Config legit(long seed) {
        java.util.SplittableRandom r = new java.util.SplittableRandom(seed * 0x9E3779B97F4A7C15L + 7);
        TickCadenceSimulator.Config c = new TickCadenceSimulator.Config();
        c.seed = seed; c.durationNanos = (40 + r.nextInt(80)) * S;
        c.driftPpm = r.nextLong(1001) - 500; // within the physical range (the tolerance is 2 500 ppm)
        switch (r.nextInt(5)) {
            case 0 -> { c.frameMinNanos = MS / 5; c.frameMaxNanos = MS; } // 1000-5000 fps
            case 1 -> { c.frameMinNanos = 4 * MS; c.frameMaxNanos = 17 * MS; } // 60-250 fps
            case 2 -> { c.frameMinNanos = 30 * MS; c.frameMaxNanos = 70 * MS; } // ~20 fps
            case 3 -> { c.frameMinNanos = 200 * MS; c.frameMaxNanos = 600 * MS; } // 2-5 fps (ticks bunch up)
            default -> { c.frameMinNanos = 45 * MS; c.frameMaxNanos = 55 * MS; } // frame period near the tick period (aliasing)
        }
        c.hitchProbability = r.nextDouble() * 0.01; c.hitchMinNanos = 100 * MS; c.hitchMaxNanos = (200 + r.nextInt(3000)) * MS;
        if (r.nextInt(3) == 0) { long at = r.nextLong(c.durationNanos / 2); c.clientFreezes.add(new TickCadenceSimulator.Interval(at, at + (500 + r.nextInt(8000)) * MS)); }
        c.tickCostMinNanos = 50_000; c.tickCostMaxNanos = (1 + r.nextInt(20)) * MS; c.procMaxNanos = r.nextInt(3) == 0 ? 20 * MS : 500_000; c.moveProbability = r.nextDouble();
        c.upBaseNanos = r.nextLong(400 * MS + 1); c.downBaseNanos = r.nextLong(400 * MS + 1); c.upJitterNanos = r.nextLong(300 * MS + 1); c.downJitterNanos = r.nextLong(300 * MS + 1);
        for (int i = r.nextInt(3); i > 0; i--) { long at = r.nextLong(c.durationNanos); c.upStalls.add(new TickCadenceSimulator.Interval(at, at + r.nextLong(10 * S))); }
        for (int i = r.nextInt(3); i > 0; i--) { long at = r.nextLong(c.durationNanos); c.downStalls.add(new TickCadenceSimulator.Interval(at, at + r.nextLong(10 * S))); }
        for (int i = r.nextInt(3); i > 0; i--) { long at = r.nextLong(c.durationNanos); c.serverPauses.add(new TickCadenceSimulator.Interval(at, at + r.nextLong(2 * S))); }
        c.pingPeriodNanos = r.nextInt(3) == 0 ? 50 * MS : S; c.pingPeriodJitterNanos = r.nextInt(2) == 0 ? 0 : 100 * MS;
        if (c.pingPeriodNanos == S && r.nextInt(2) == 0) combat(c, r); // the combat-time marker cadence (Phase 12 scheduler)
        c.inducedPerSecond = r.nextDouble() * 2; c.pingWriteFailProbability = r.nextInt(4) == 0 ? 0.05 : 0; c.pluginPingCollisionProbability = r.nextInt(4) == 0 ? 0.1 : 0;
        if (r.nextInt(3) == 0) { c.tickRateChanges.add(new long[] { r.nextLong(c.durationNanos / 2), new long[] { 5_000, 10_000, 40_000, 100_000 }[r.nextInt(4)], r.nextInt(2) }); c.tickRateChanges.add(new long[] { c.durationNanos / 2 + r.nextLong(c.durationNanos / 2), 20_000, 0 }); }
        return c;
    }
}
