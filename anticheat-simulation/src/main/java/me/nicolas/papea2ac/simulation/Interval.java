package me.nicolas.papea2ac.simulation;

/**
 * A closed double interval {@code [lo, hi]} for the M3 abstract lane (MOVEMENT_SIMULATION.md M3: "outward-rounded intervals
 * for position and velocity"; NUMERICAL_POLICY.md: "Outward rounding at every primitive/cast ... Use nextDown/nextUp or
 * stronger proven bounds; infinities mean an unbounded conservative result").
 *
 * <p>Soundness argument used throughout: IEEE-754 round-to-nearest is monotone, so for an elementary operation with one
 * interval operand and one fixed operand ({@code x + c}, {@code x * c} with {@code c > 0}, {@code x - c}, {@code max(c, x)}),
 * the concrete result of every {@code x} in {@code [lo, hi]} lies between the concrete results at {@code lo} and {@code hi} -
 * the endpoints computed with the SAME operation bound the set exactly, no widening needed. Where two intervals combine
 * ({@code x + y}) monotonicity in each argument gives {@code [fl(lo1 + lo2), fl(hi1 + hi2)]}. Only genuinely non-monotone
 * places (squares straddling zero, thresholds) widen through {@link #hull} / {@link #outward()}.
 */
public record Interval(double lo, double hi) {
    public Interval {
        if (Double.isNaN(lo) || Double.isNaN(hi) || lo > hi) throw new IllegalArgumentException("interval [" + lo + ", " + hi + "]");
    }
    public static final Interval ZERO = new Interval(0.0, 0.0), TOP = new Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    public static Interval point(double v) { return new Interval(v, v); }
    public boolean isPoint() { return lo == hi; }
    public boolean bounded() { return Double.isFinite(lo) && Double.isFinite(hi); }
    public boolean contains(double v) { return v >= lo && v <= hi; }
    public boolean intersects(Interval o) { return !(o.hi < lo || o.lo > hi); }
    public double width() { return hi - lo; }
    /** Smallest interval containing both. */
    public Interval hull(Interval o) { return new Interval(Math.min(lo, o.lo), Math.max(hi, o.hi)); }
    public Interval hull(double v) { return new Interval(Math.min(lo, v), Math.max(hi, v)); }
    /** One ulp outward on each side (for results of non-monotone or reassociated expressions). */
    public Interval outward() { return new Interval(Math.nextDown(lo), Math.nextUp(hi)); }
    /** {@code x + c} for every x in this (monotone in x: endpoints exact). */
    public Interval add(double c) { return new Interval(lo + c, hi + c); }
    /** {@code x + y} over both intervals (monotone in each argument). */
    public Interval add(Interval o) { return new Interval(lo + o.lo, hi + o.hi); }
    /** {@code x - c}. */
    public Interval sub(double c) { return new Interval(lo - c, hi - c); }
    /** {@code x * c} for a fixed c (monotone; sign of c decides the endpoint order). Exact endpoints. */
    public Interval mul(double c) { return c >= 0 ? new Interval(lo * c, hi * c) : new Interval(hi * c, lo * c); }
    /** {@code max(c, x)}. */
    public Interval maxWith(double c) { return new Interval(Math.max(c, lo), Math.max(c, hi)); }
    /** Sound outer bound of {@code x * x} over the interval (the square is not monotone across zero). */
    public Interval square() {
        double a = lo * lo, b = hi * hi;
        if (lo <= 0.0 && hi >= 0.0) return new Interval(0.0, Math.nextUp(Math.max(a, b)));
        return new Interval(Math.nextDown(Math.min(a, b)), Math.nextUp(Math.max(a, b)));
    }
    /** Whether every concrete x satisfies {@code |x| < threshold} (used for the vanilla zeroing tests); conservative: false when unsure. */
    public boolean allAbsBelow(double threshold) { return Math.max(Math.abs(lo), Math.abs(hi)) < threshold; }
    /** Whether no concrete x satisfies {@code |x| < threshold}; conservative: false when unsure. */
    public boolean noneAbsBelow(double threshold) { return lo >= threshold || hi <= -threshold; }
    @Override public String toString() { return isPoint() ? Double.toString(lo) : "[" + lo + ", " + hi + "]"; }
}
