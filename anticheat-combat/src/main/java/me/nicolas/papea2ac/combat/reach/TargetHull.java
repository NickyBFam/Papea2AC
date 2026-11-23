package me.nicolas.papea2ac.combat.reach;

import java.util.Arrays;
import java.util.List;
import me.nicolas.papea2ac.combat.geometry.PickMath;

/**
 * A sound box of every position the observer's client can hold for a target at the attack's pick (PHASE_12 R4 / M5).
 *
 * <p>The client moves an interpolating entity, once per client tick, by {@code pos = lerp(1/k, pos, target)} with {@code k} the remaining
 * steps ({@code k <= steps}, reset to {@code steps} by each applied network update); a snapping entity takes each applied target exactly. Every
 * position is therefore a convex combination of (a) the position held when the window's first update was applied and (b) the network targets
 * applied since. Each tick multiplies the weight of (a) by at most {@code rho = (steps − 1) / steps}; so after {@code m} proven ticks the
 * position lies in the box of the window's targets, widened on each side by {@code rho^m} times the distance from that box to wherever (a) can
 * be. (a) itself is a convex combination of everything since the spawn snap, so it lies in the incarnation's LIFETIME box of targets.
 *
 * <p>Preconditions the caller enforces (else the target is UNSUPPORTED, never INVALID): no local displacement of the entity on the client
 * (vehicle / passenger relations, moving pistons, client-simulated movement), no far snap during interpolation, no INTERPOLATION_DEPENDENT /
 * VEHICLE_DEPENDENT / UNSUPPORTED revision in the window, and coverage complete since the spawn (the lifetime box saw every target).
 */
public final class TargetHull {
    private TargetHull() {}

    /** Absolute padding for the client's own rounding of the lerp chain (each step is {@code a + d*(b−a)}, a few ulps; the chain contracts). */
    static double roundingPad(double maxAbs) { return 64 * Math.ulp(Math.max(1.0, maxAbs)) + 1.0E-12; }

    /**
     * @param window the network targets of the window, oldest first: the update in effect when the proven frontier was handled (or the
     *               oldest retained one), then every later update before the attack. Each element is {x, y, z}.
     * @param lifetime the box of every network target of the incarnation since its spawn (spawn position included)
     * @param steps the type's interpolation steps; 0 means the entity snaps (no interpolation)
     * @param provenTicks client ticks provably completed since the window's first update was applied (0 when nothing is proven)
     * @return the position box, outward-rounded
     */
    public static PickMath.Box positions(List<double[]> window, PickMath.Box lifetime, int steps, int provenTicks) {
        if (window.isEmpty()) throw new IllegalArgumentException("empty window");
        if (steps < 0 || provenTicks < 0) throw new IllegalArgumentException("steps / ticks");
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (double[] q : window) {
            minX = Math.min(minX, q[0]); minY = Math.min(minY, q[1]); minZ = Math.min(minZ, q[2]);
            maxX = Math.max(maxX, q[0]); maxY = Math.max(maxY, q[1]); maxZ = Math.max(maxZ, q[2]);
        }
        double w = steps == 0 ? 0.0 : weightBound(steps, provenTicks);
        if (w > 0.0) {

            // per axis: how far (a) may sit outside the window box, scaled by the remaining weight of (a); outward-rounded
            minX = Math.nextDown(minX - Math.nextUp(w * Math.max(0.0, minX - lifetime.minX())));
            minY = Math.nextDown(minY - Math.nextUp(w * Math.max(0.0, minY - lifetime.minY())));
            minZ = Math.nextDown(minZ - Math.nextUp(w * Math.max(0.0, minZ - lifetime.minZ())));
            maxX = Math.nextUp(maxX + Math.nextUp(w * Math.max(0.0, lifetime.maxX() - maxX)));
            maxY = Math.nextUp(maxY + Math.nextUp(w * Math.max(0.0, lifetime.maxY() - maxY)));
            maxZ = Math.nextUp(maxZ + Math.nextUp(w * Math.max(0.0, lifetime.maxZ() - maxZ)));
        }
        double pad = steps == 0 ? 0.0 : roundingPad(Math.max(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.max(Math.abs(minY), Math.abs(maxY)), Math.max(Math.abs(minZ), Math.abs(maxZ)))));
        return new PickMath.Box(minX - pad, minY - pad, minZ - pad, maxX + pad, maxY + pad, maxZ + pad);
    }

    /** One proven application point: every target up to and including {@code history[index]} was applied {@code ticks} or more client ticks before the attack. */
    public record Bound(int index, int ticks) {}

    /**
     * The admissible position box from EVERY answered marker at once (PHASE_12 R5, backtrack refinement). The client position is a convex
     * combination {@code Σ w_k q_k} of the targets since the spawn snap. For each proven point {@code (s, m)}: once {@code history[s]} is applied,
     * every later tick multiplies the total weight of the targets older than {@code history[s]} (and of anything older than {@code history[0]})
     * by at most {@code rho = (steps − 1)/steps}, so that weight is at most {@code c = rho^m}. Per axis, with the history split at the proven
     * indices into segments {@code S_0 (incl. everything older, bounded by the lifetime box) .. S_r} with extreme values {@code a_t}, the largest
     * attainable coordinate is {@code a_r + max Σ_t P_t (a_{t−1} − a_t)} over non-decreasing prefix weights {@code 0 ≤ P_t ≤ c_t}
     * ({@link #maxPrefixWeights}, exact); the minimum is symmetric. Using segment extremes per axis is itself a relaxation (an upper bound on
     * every convex combination). Outward-rounded, plus the lerp rounding pad.
     *
     * @param history the network targets sent before the attack's capture, oldest first (the retained revisions since the spawn, or since the
     *                oldest retained one: anything older is inside {@code lifetime})
     * @param bounds proven points (indices into {@code history}, any order); a bound whose target was not retained is simply not given (a
     *               dropped constraint only widens the box)
     */
    public static PickMath.Box nested(List<double[]> history, PickMath.Box lifetime, int steps, List<Bound> bounds) {
        if (history.isEmpty()) throw new IllegalArgumentException("empty history");
        List<Bound> bs = new java.util.ArrayList<>(bounds);
        bs.removeIf(b -> b.index() <= 0 || b.index() >= history.size()); // index 0: everything before it is "older than history[0]" already covered by S_0 alone
        bs.sort((x, y) -> Integer.compare(x.index(), y.index()));
        // de-duplicate indices, keeping the strongest (most ticks) proof for each
        List<Bound> uniq = new java.util.ArrayList<>();
        for (Bound b : bs) { if (!uniq.isEmpty() && uniq.get(uniq.size() - 1).index() == b.index()) { if (b.ticks() > uniq.get(uniq.size() - 1).ticks()) uniq.set(uniq.size() - 1, b); } else uniq.add(b); }
        int r = uniq.size();
        double[] caps = new double[r];
        for (int t = 0; t < r; t++) caps[t] = steps == 0 ? 0.0 : weightBound(steps, uniq.get(t).ticks());
        double[] lo = {lifetime.minX(), lifetime.minY(), lifetime.minZ()}, hi = {lifetime.maxX(), lifetime.maxY(), lifetime.maxZ()};
        double[] outLo = new double[3], outHi = new double[3]; Program program = new Program(caps);
        for (int axis = 0; axis < 3; axis++) {
            double[] a = new double[r + 1], b = new double[r + 1];
            Arrays.fill(a, Double.NEGATIVE_INFINITY); Arrays.fill(b, Double.POSITIVE_INFINITY);
            a[0] = hi[axis]; b[0] = lo[axis];
            int seg = 0;
            for (int k = 0; k < history.size(); k++) {
                while (seg < r && k >= uniq.get(seg).index()) seg++;
                double v = history.get(k)[axis];
                if (v > a[seg]) a[seg] = v; if (v < b[seg]) b[seg] = v;
            }
            for (int t = 1; t <= r; t++) { if (a[t] == Double.NEGATIVE_INFINITY) { a[t] = a[t - 1]; b[t] = b[t - 1]; } } // empty segment: no constraint gain
            double[] da = new double[r], db = new double[r];
            for (int t = 1; t <= r; t++) { da[t - 1] = a[t - 1] - a[t]; db[t - 1] = b[t] - b[t - 1]; }
            // max coordinate = a_r + max Σ P_t (a_{t-1} - a_t); min coordinate = b_r - max Σ P_t (b_t - b_{t-1}); both over the same monotone prefix weights
            outHi[axis] = Math.nextUp(a[r] + program.solve(da));
            outLo[axis] = Math.nextDown(b[r] - program.solve(db));
        }
        double pad = steps == 0 ? 0.0 : roundingPad(Math.max(Math.max(Math.abs(outLo[0]), Math.abs(outHi[0])), Math.max(Math.max(Math.abs(outLo[1]), Math.abs(outHi[1])), Math.max(Math.abs(outLo[2]), Math.abs(outHi[2])))));
        return new PickMath.Box(outLo[0] - pad, outLo[1] - pad, outLo[2] - pad, outHi[0] + pad, outHi[1] + pad, outHi[2] + pad);
    }

    /**
     * {@code max Σ_t d_t P_t} over {@code 0 ≤ P_1 ≤ … ≤ P_r} with {@code P_t ≤ caps_t} (and ≤ 1): the exact optimum of the prefix-weight linear
     * program. {@code P_t} is the total weight of everything older than the t-th proven point, so it is non-decreasing in t; each cap tightens
     * every earlier prefix too, so the effective cap is the suffix minimum. An optimal solution takes, at each t, either the previous value or the
     * effective cap, so a dynamic program over those candidate values is exact (r ≤ a few dozen). Never negative (P = 0 is feasible).
     */
    public static double maxPrefixWeights(double[] d, double[] caps) {
        int r = d.length; if (r == 0) return 0.0;
        Program p = new Program(caps);
        return p.solve(d);
    }

    /**
     * The caps-only part of the program (effective caps, sorted candidate values) and two reusable DP rows, so the six solves of one
     * {@link #nested} box share them (perf-to-phase12-01). The arithmetic of each solve is unchanged.
     */
    private static final class Program {
        final double[] eff, cand, best, next;
        Program(double[] caps) {
            int r = caps.length; eff = new double[r]; double m = 1.0;
            for (int t = r - 1; t >= 0; t--) { m = Math.min(m, caps[t]); eff[t] = m; }
            cand = new double[r + 1]; cand[0] = 0.0; for (int t = 0; t < r; t++) cand[t + 1] = eff[t];
            Arrays.sort(cand);
            best = new double[cand.length]; next = new double[cand.length];
        }
        double solve(double[] d) { return dp(d, eff, cand, best, next); }
    }

    private static double dp(double[] d, double[] eff, double[] cand, double[] best, double[] next) {
        int r = d.length; if (r == 0) return 0.0;
        Arrays.fill(best, Double.NEGATIVE_INFINITY); best[0] = 0.0; // P_0 = 0
        for (int t = 0; t < r; t++) {
            double run = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < cand.length; j++) {
                run = Math.max(run, best[j]); // any previous value <= cand[j]
                next[j] = cand[j] <= eff[t] && run != Double.NEGATIVE_INFINITY ? run + d[t] * cand[j] : Double.NEGATIVE_INFINITY;
            }
            double[] swap = best; best = next; next = swap; // two rows reused (perf-to-phase12-01): same arithmetic, no per-step allocation
        }
        double out = 0.0; for (double v : best) out = Math.max(out, v);
        return out;
    }

    /**
     * An upper bound on {@code ((steps − 1) / steps)^ticks}, by repeated multiplication rounded up (no transcendental call: NUMERICAL_POLICY).
     * One step (steps == 1) reaches the target in one tick: weight 0 after one tick.
     */
    public static double weightBound(int steps, int ticks) {
        if (steps <= 1) return ticks > 0 ? 0.0 : 1.0;
        double rho = Math.nextUp((double) (steps - 1) / (double) steps), w = 1.0;
        for (int i = 0; i < ticks; i++) { w = Math.nextUp(w * rho); if (w < 1.0E-300) return 1.0E-300; }
        return w;
    }
}
