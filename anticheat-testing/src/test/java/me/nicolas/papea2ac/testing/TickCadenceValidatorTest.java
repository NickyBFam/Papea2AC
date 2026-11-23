package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TickCadenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import me.nicolas.papea2ac.player.TickCadenceLedger;
import me.nicolas.papea2ac.simulation.TickCadenceResult;
import me.nicolas.papea2ac.simulation.TickCadenceValidator;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import org.junit.jupiter.api.Test;
/** Phase 14 profile arithmetic and validator classification on hand-built anchors: exact boundaries, every uncertain path, both estimators. */
class TickCadenceValidatorTest {
    static final TickCadenceProfile P = TickCadenceProfile.PAPER_26_2;
    static final TickCadenceValidator V = new TickCadenceValidator();
    static TickCadenceLedger.Anchor anchor(int segment, long pingSeq, long pingNanos, long wire, long induced, long answerSeq, long answerNanos, long tickEnds, long movements) {
        return new TickCadenceLedger.Anchor(segment, pingSeq, pingSeq, pingNanos, wire, induced, answerSeq, answerNanos, tickEnds, movements);
    }

    @Test void theProfileBoundIsExactIntegerArithmetic() {
        assertEquals(22, P.maxTicks(1)); // any positive span already allows the frame slack
        assertEquals(200 + 22, P.maxTicks(10 * S - 1_000_000)); // 9.999 s x 1.0025 / 50 ms = 200.47 -> 200
        assertEquals(200 + 22, P.maxTicks(10 * S)); // 200.5
        assertEquals(1203 + 22, P.maxTicks(60 * S)); // 60 x 20 x 1.0025 = 1203
        assertEquals(12030 + 22, P.maxTicks(P.maxWindowNanos())); // 600 s
        assertEquals(-1, P.maxTicks(0)); assertEquals(-1, P.maxTicks(-5)); assertEquals(-1, P.maxTicks(P.maxWindowNanos() + 1));
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceProfile("x", 50_000_000L, 10, 20, 2_500, S, true, true), "slack below 2 x cap + 1");
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceProfile("x", 50_000_000L, 10, 22, 1_000_000, S, true, true));
        assertThrows(ArithmeticException.class, () -> new TickCadenceProfile("x", 50_000_000L, 10, 22, 2_500, Long.MAX_VALUE / 2, true, true));
    }

    @Test void excessZeroIsValidAndExcessOneIsAnExclusionWithItsProof() {
        long span = 10 * S; long max = P.maxTicks(span);
        var a = anchor(0, 10, 0, 5, 0, 11, 30 * MS, 0, 0);
        var atBound = anchor(0, 100, span - 40 * MS, 50, 0, 101, span, max, 0);
        TickCadenceResult ok = V.evaluate(KEY, List.of(a, atBound), P, true);
        assertTrue(ok.valid(), ok.describe()); assertEquals(0, ok.worst().excess()); assertEquals(1, ok.windowsEvaluated()); assertEquals(span, ok.worst().windowNanos());
        var over = anchor(0, 100, span - 40 * MS, 50, 0, 101, span, max + 1, 0);
        TickCadenceResult bad = V.evaluate(KEY, List.of(a, over), P, true);
        assertTrue(bad.invalid(), bad.describe()); assertEquals(1, bad.worst().excess()); assertEquals(List.of(TickCadenceResult.InvalidCode.TICK_END_EXCESS), bad.invalidReasons());
        assertEquals(11, bad.worst().fromAnswerSequence()); assertEquals(101, bad.worst().toAnswerSequence()); assertEquals(TickCadenceResult.Source.TICK_END, bad.worst().source());
    }

    @Test void theWindowStartsAtTheEarlierPingNotItsPong() {
        // pong A came back 3 s after its ping (a stall): the window is measured from the ping, so the 3 s count in the client's favour
        var a = anchor(0, 10, 0, 5, 0, 11, 3 * S, 0, 0);
        var b = anchor(0, 20, 9 * S, 9, 0, 21, 10 * S, P.maxTicks(10 * S), 0);
        assertTrue(V.evaluate(KEY, List.of(a, b), P, true).valid());
    }

    @Test void theWorstOfAllRetainedWindowsDecidesAndEveryWindowIsEvaluated() {
        List<TickCadenceLedger.Anchor> anchors = new ArrayList<>();
        long ticks = 0;
        for (int i = 0; i < 30; i++) { anchors.add(anchor(0, 10 + 10L * i, i * S, 1 + i, 0, 11 + 10L * i, i * S + 40 * MS, ticks, 0)); ticks += 20; }
        // a 1.5x client over the last 20 s: 30 ticks per second
        for (int i = 30; i < 50; i++) { anchors.add(anchor(0, 10 + 10L * i, i * S, 1 + i, 0, 11 + 10L * i, i * S + 40 * MS, ticks, 0)); ticks += 30; }
        TickCadenceResult r = V.evaluate(KEY, anchors, P, true);
        assertTrue(r.invalid(), r.describe()); assertEquals(49, r.windowsEvaluated());
        // 1170 ticks at the target; from anchor j <= 30 the window holds 1170 - 20j ticks against floor((49.04 - j) x 20.05) + 22: excess 167 for j in 26..30
        assertEquals(167, r.worst().excess(), r.worst().describe());
        assertEquals(271, r.worst().fromAnswerSequence(), "the first window reaching the largest excess is reported (strictly larger replaces): " + r.worst().describe());
    }

    @Test void theMovementEstimatorSubtractsTheInducedPacketsBetweenThePings() {
        long span = 10 * S, max = P.maxTicks(span);
        var a = anchor(0, 10, 0, 5, 3, 11, 30 * MS, 0, 0);
        var b = anchor(0, 100, span - 40 * MS, 50, 3 + 7, 101, span, 0, max + 7); // 7 induced movements: exactly at the bound
        TickCadenceResult r = V.evaluate(KEY, List.of(a, b), P, true);
        assertTrue(r.valid(), r.describe()); assertEquals(TickCadenceResult.Source.MOVEMENT, r.worst().source()); assertEquals(7, r.worst().induced());
        assertEquals(max, r.movementBeyondTickEnds(), "every movement-proven tick lacks its tick end");
        var b2 = anchor(0, 100, span - 40 * MS, 50, 3 + 7, 101, span, 0, max + 8);
        TickCadenceResult r2 = V.evaluate(KEY, List.of(a, b2), P, true);
        assertTrue(r2.invalid()); assertEquals(List.of(TickCadenceResult.InvalidCode.MOVEMENT_EXCESS), r2.invalidReasons());
        // pings answered out of wire order (no vanilla client does this): the movement count is not used, only the tick ends
        var reordered = anchor(0, 100, span - 40 * MS, 4, 0, 101, span, 0, 10 * max);
        TickCadenceResult r3 = V.evaluate(KEY, List.of(a, reordered), P, true);
        assertTrue(r3.valid(), r3.describe()); assertEquals(0, r3.worst().induced()); assertEquals(0, r3.worst().executedAtLeast());
    }

    @Test void uncertainPathsNeverExclude() {
        var only = anchor(0, 10, 0, 5, 0, 11, 30 * MS, 0, 0);
        TickCadenceResult first = V.evaluate(KEY, List.of(only), P, true);
        assertTrue(first.uncertain()); assertEquals(List.of(TickCadenceResult.UncertainCode.NO_PRIOR_ANCHOR), first.uncertainReasons());
        var far = anchor(0, 100, P.maxWindowNanos() + 5 * S, 50, 0, 101, P.maxWindowNanos() + 6 * S, 1_000_000, 0);
        TickCadenceResult out = V.evaluate(KEY, List.of(only, far), P, true);
        assertTrue(out.uncertain()); assertEquals(List.of(TickCadenceResult.UncertainCode.WINDOW_OUT_OF_RANGE), out.uncertainReasons()); assertEquals(1, out.windowsSkipped());
        var other = anchor(1, 100, 5 * S, 50, 0, 101, 6 * S, 1_000_000, 0);
        TickCadenceResult seg = V.evaluate(KEY, List.of(only, other), P, true);
        assertTrue(seg.uncertain(), "a different segment is never paired");
        var huge = anchor(0, 100, 9 * S, 50, 0, 101, 10 * S, 1_000_000, 0);
        TickCadenceResult unsupported = V.evaluate(KEY, List.of(only, huge), P, false);
        assertTrue(unsupported.uncertain()); assertEquals(List.of(TickCadenceResult.UncertainCode.PROFILE_UNSUPPORTED), unsupported.uncertainReasons());
        assertTrue(unsupported.worst().excess() > 0, "the window is still measured for diagnostics");
        assertThrows(IllegalArgumentException.class, () -> V.evaluate(KEY, List.of(), P, true));
    }

    @Test void resultInvariantsAreStructural() {
        var w = new TickCadenceResult.Window(1, 2, 3, S, 10, 0, 0, 10, TickCadenceResult.Source.TICK_END, 42, -32);
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceResult(KEY, 0, 3, S, TickCadenceResult.Classification.INVALID, List.of(), List.of(TickCadenceResult.InvalidCode.TICK_END_EXCESS), w, 1, 0, 0, "p"));
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceResult(KEY, 0, 3, S, TickCadenceResult.Classification.VALID, List.of(), List.of(), null, 0, 0, 0, "p"));
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceResult(KEY, 0, 3, S, TickCadenceResult.Classification.UNCERTAIN, List.of(), List.of(), w, 1, 0, 0, "p"));
        assertThrows(IllegalArgumentException.class, () -> new TickCadenceResult.Window(1, 2, 3, S, 10, 0, 0, 10, TickCadenceResult.Source.TICK_END, 42, 0), "excess must equal executed - max");
    }
}
