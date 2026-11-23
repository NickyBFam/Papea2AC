package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.checks.rotation.ClientTickCondition;
import me.nicolas.papea2ac.checks.rotation.QuantizationFit;
import me.nicolas.papea2ac.checks.rotation.RotationAnalysis;
import me.nicolas.papea2ac.checks.rotation.RotationAnalysisConfig;
import me.nicolas.papea2ac.checks.rotation.RotationAnalyzer;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.player.RotationHistory;
import me.nicolas.papea2ac.version.RotationProfile;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import static org.junit.jupiter.api.Assertions.*;
/** Phase 15: exact kinematics, bounded history, quantization fit and the legitimate/adversarial rotation corpora. */
class RotationAnalysisTest {
    private static final RotationAnalysisConfig C = RotationAnalysisConfig.DEFAULT;

    /** Runs a stream and collects every window the analyzer emits. */
    private static List<RotationAnalysis> windows(RotationCorpus.Stream s) {
        var h = new RotationHistory();
        var a = new RotationAnalyzer(RotationCorpus.kinematics(), C, true);
        List<RotationAnalysis> out = new ArrayList<>();
        RotationCorpus.replay(s, h, () -> a.offer(h.snapshot()).ifPresent(out::add));
        return out;
    }
    private static long count(List<RotationAnalysis> w, QuantizationFit.Status st) { return w.stream().filter(x -> x.fit().status() == st).count(); }

    // ------------------------------------------------------------------ profile and exact arithmetic

    @Test void kinematicsIsAnExactCopyOfTheVersionProfile() {
        var p = RotationProfile.NATIVE_26_2; var k = RotationCorpus.kinematics();
        assertEquals(Double.doubleToRawLongBits(0.6000000238418579d), Double.doubleToRawLongBits(p.sensitivityScale()), "0.6F widened, as in the bytecode");
        assertEquals(Double.doubleToRawLongBits(0.20000000298023224d), Double.doubleToRawLongBits(p.sensitivityOffset()));
        assertEquals(8.0d, p.unscopedMultiplier());
        assertEquals(Float.floatToRawIntBits(0.15F), Float.floatToRawIntBits(p.perCountScale()));
        assertEquals(p.profileId(), k.profileId());
        assertEquals(Float.floatToRawIntBits(p.perCountScale()), Float.floatToRawIntBits(k.perCountScale()));
        assertFalse(p.yawWrapped(), "the client never wraps yaw");
    }

    @Test void stepArithmeticFollowsTheClientTypesAndOrder() {
        var k = RotationCorpus.kinematics();
        // default sensitivity 0.5: f = 0.5000000149..., k = f^3 * 8 = 1.00000008940..., (float) -> 1.0000001F, * 0.15F
        double mult = k.multiplier(0.5, false);
        assertEquals(1.0000000894069698d, mult, 1e-15);
        assertEquals(Float.floatToRawIntBits(1.0000001F * 0.15F), Float.floatToRawIntBits(k.unit(0.5, false)));
        assertEquals(Float.floatToRawIntBits((float) (3 * mult) * 0.15F), Float.floatToRawIntBits(k.frameStep(3, mult)));
        int differing = 0;
        for (int n = 1; n <= 1000; n++) if (k.frameStep(n, mult) != n * k.unit(0.5, false)) differing++;
        assertTrue(differing > 0, "n counts are not always n units in float: the fit needs a bound");
        // scoping drops the 8x factor
        assertEquals(k.multiplier(0.5, false) / 8, k.multiplier(0.5, true), 1e-15);
        assertTrue(k.minUnit() < k.unit(0, false) && k.unit(1, false) == k.maxUnit());
        // pitch: (p + step) % 360 then clamp, twice; yaw is never wrapped
        assertEquals(90f, k.turnPitch(89.9f, 5f));
        assertEquals(-90f, k.turnPitch(-89.9f, -5f));
        assertEquals(400f + 0.15f, k.turnYaw(400f, 0.15f));
        assertEquals(0.5, k.sensitivityForUnit(k.unit(0.5, false), false), 1e-6);
        assertEquals(0.2, k.sensitivityForUnit(k.unit(0.2, true), true), 1e-6);
    }

    // ------------------------------------------------------------------ bounded history

    @Test void historyIsBoundedSegmentedAndOrdered() {
        var h = new RotationHistory();
        for (int i = 1; i <= 100; i++) h.observe(i, new Rotation(i, 0));
        var s = h.snapshot();
        assertEquals(RotationHistory.CAPACITY, s.size());
        assertEquals(100 - RotationHistory.CAPACITY, s.overwritten());
        assertEquals(37, s.sequences()[0]); assertEquals(100, s.sequences()[s.size() - 1]);
        h.breakSegment(RotationHistory.Break.TELEPORT_ACCEPTED);
        h.observe(101, new Rotation(5, 5));
        h.observe(50, new Rotation(6, 6)); // out of order: never linked to its predecessor
        s = h.snapshot();
        int n = s.size();
        assertNotEquals(s.segments()[n - 3], s.segments()[n - 2]);
        assertNotEquals(s.segments()[n - 2], s.segments()[n - 1]);
        assertEquals(RotationHistory.Break.OUT_OF_ORDER, s.lastBreak());
    }

    @Test void deltasNeverCrossASegmentBreak() {
        var w = windows(RotationCorpus.teleportMidway(7, 0.5, 400));
        assertFalse(w.isEmpty());
        assertTrue(w.stream().anyMatch(x -> x.uncertainty().contains(RotationAnalysis.Uncertainty.SEGMENT_BREAK_IN_WINDOW)));
        // the teleport jump (to 123.456 / -12.5) is never a delta: no window reports a jump that large unless the synthetic flick made it
        for (var x : w) if (x.uncertainty().contains(RotationAnalysis.Uncertainty.SEGMENT_BREAK_IN_WINDOW)) assertTrue(x.features().samples() < RotationHistory.CAPACITY);
    }

    // ------------------------------------------------------------------ legitimate corpus

    @Test void legitimateRawInputFitsItsOwnSensitivityAcrossTheRange() {
        var k = RotationCorpus.kinematics();
        double[] grid = {0.0, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0};
        int windows = 0, fits = 0, belowFloor = 0;
        java.util.EnumMap<QuantizationFit.Status, Integer> statuses = new java.util.EnumMap<>(QuantizationFit.Status.class);
        for (double s : grid) for (long seed = 1; seed <= 6; seed++) {
            var w = windows(RotationCorpus.legitimate("raw", seed * 31 + (long) (s * 1000), RotationCorpus.ClientOptions.raw(s), 600));
            for (var x : w) {
                statuses.merge(x.fit().status(), 1, Integer::sum);
                if (x.fit().status() == QuantizationFit.Status.INSUFFICIENT_SAMPLES) continue;
                windows++;
                // soundness: a legitimate unit inside the searched domain is never reported absent; below the floor it was not examined
                if (k.unit(s, false) < x.fit().resolutionFloor()) { belowFloor++; continue; }
                assertNotEquals(QuantizationFit.Status.NO_FIT, x.fit().status(), "legitimate raw input must never be NO_FIT: s=" + s + " seed=" + seed + " " + x);
                if (x.fit().status() == QuantizationFit.Status.FIT) {
                    fits++;
                    assertEquals(k.unit(s, false), x.fit().unit(), k.unit(s, false) * 1e-4, "fitted unit is the player's unit");
                    assertTrue(x.confounders().isEmpty());
                    assertNotEquals(RotationAnalysis.UnitContinuity.CHANGED, x.unitContinuity(), "the unit is stable while sensitivity is");
                }
            }
        }
        System.out.println("[phase15] legitimate raw corpus: windows=" + windows + " fits=" + fits + " belowFloor=" + belowFloor + " statuses=" + statuses);
        assertTrue(windows > 200, "corpus too small: " + windows);
        assertTrue(fits >= (windows - belowFloor) * 0.5, "the fit must cover most in-domain windows: " + fits + "/" + (windows - belowFloor));
    }

    @Test void invertedAxesKeepTheSameUnit() {
        var k = RotationCorpus.kinematics();
        var w = windows(RotationCorpus.legitimate("invert", 11, RotationCorpus.ClientOptions.raw(0.4).withInvert(), 600));
        assertTrue(count(w, QuantizationFit.Status.FIT) > 0);
        for (var x : w) if (x.fit().status() == QuantizationFit.Status.FIT) assertEquals(k.unit(0.4, false), x.fit().unit(), k.unit(0.4, false) * 1e-4);
    }

    @Test void scopingFitsTheScopedUnit() {
        var k = RotationCorpus.kinematics();
        var w = windows(RotationCorpus.legitimate("scoping", 12, RotationCorpus.ClientOptions.raw(0.8).withScoping(), 600));
        assertEquals(0, count(w, QuantizationFit.Status.NO_FIT));
        for (var x : w) if (x.fit().status() == QuantizationFit.Status.FIT) assertEquals(k.unit(0.8, true), x.fit().unit(), k.unit(0.8, true) * 1e-4);
    }

    @Test void pitchClampIsExcludedNotMisfit() {
        var w = windows(RotationCorpus.legitimate("clamp", 13, RotationCorpus.ClientOptions.raw(0.5).withPitchBias(3).withStart(0, 80), 600));
        assertTrue(w.stream().anyMatch(x -> x.uncertainty().contains(RotationAnalysis.Uncertainty.PITCH_CLAMP_EXCLUDED)));
        assertEquals(0, count(w, QuantizationFit.Status.NO_FIT));
        assertTrue(w.stream().allMatch(x -> x.features().pitchOutsideProfile() == 0), "the native client never leaves the clamp range");
    }

    /** Uncapped frame rates put more turning frames (float roundings) into one packet than the configured bound assumes. */
    @Test void highFrameRateBeyondTheFramesBoundIsMeasured() {
        var k = RotationCorpus.kinematics();
        int noFit = 0, windows = 0;
        for (double s : new double[]{0.0, 0.1, 0.5, 1.0}) for (long seed = 1; seed <= 4; seed++) {
            for (var x : windows(RotationCorpus.legitimate("fps", seed * 7 + (long) (s * 100), RotationCorpus.ClientOptions.raw(s).withFrames(60).withStart(0, 0), 600))) {
                if (x.fit().status() == QuantizationFit.Status.INSUFFICIENT_SAMPLES || k.unit(s, false) < x.fit().resolutionFloor()) continue;
                windows++;
                if (x.fit().status() == QuantizationFit.Status.NO_FIT) noFit++;
            }
        }
        System.out.println("[phase15] 60 frames per tick (~1200 FPS), frames bound " + C.framesPerTickBound() + ": windows=" + windows + " NO_FIT=" + noFit);
        assertEquals(0, noFit, "legitimate high-FPS input must not be reported as NO_FIT");
    }

    // ------------------------------------------------------------------ Phase 14 tick-rate contract (PHASE14_TIMER_CONTRACT_READY v1)

    @Test void framesBoundScalesWithTheClientTickPeriod() {
        assertEquals(20, ClientTickCondition.NOMINAL.framesBound(20));
        assertEquals(80, new ClientTickCondition(true, 200_000_000L, 5).framesBound(20), "/tick rate 5");
        assertEquals(400, new ClientTickCondition(true, 1_000_000_000L, 5).framesBound(20), "/tick rate 1 (the client floors the rate at 1)");
        assertThrows(IllegalArgumentException.class, () -> new ClientTickCondition(true, 49_999_999L, 0), "the client never ticks faster than 20 Hz");
    }

    /** The core mapping from the Phase 14 contract types (TickCadenceLedger.TickRateSignal, TickCadenceProfile) to the rotation condition. */
    @Test void phase14SignalMapsToTheRotationCondition() {
        var p = me.nicolas.papea2ac.version.TickCadenceProfile.PAPER_26_2;
        java.util.function.BiFunction<Float, Boolean, me.nicolas.papea2ac.player.TickCadenceLedger.RateState> st = me.nicolas.papea2ac.player.TickCadenceLedger.RateState::new;
        java.util.function.Function<me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal, ClientTickCondition> map =
                s -> me.nicolas.papea2ac.core.RotationTickConditionMapper.map(s, p);
        assertEquals(ClientTickCondition.UNRESOLVED, map.apply(me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.UNOBSERVED));
        var settledNominal = new me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal(
                me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.Status.SETTLED, List.of(st.apply(20f, false)), true, 12, 14);
        assertEquals(new ClientTickCondition(true, 50_000_000L, 12), map.apply(settledNominal));
        var settledSlow = new me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal(
                me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.Status.SETTLED, List.of(st.apply(5f, false)), true, 30, 33);
        assertEquals(200_000_000L, map.apply(settledSlow).maxTickPeriodNanos(), "/tick rate 5");
        var frozenSlow = new me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal(
                me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.Status.SETTLED, List.of(st.apply(5f, true)), true, 40, 41);
        assertEquals(200_000_000L, map.apply(frozenSlow).maxTickPeriodNanos(), "frozen at rate 5: /tick step runs the 200 ms period");
        var frozenNominal = new me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal(
                me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.Status.SETTLED, List.of(st.apply(20f, true)), true, 40, 41);
        assertEquals(50_000_000L, map.apply(frozenNominal).maxTickPeriodNanos(), "frozen at 20: the client still ticks every 50 ms");
        var transition = new me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal(
                me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.Status.TRANSITION, List.of(st.apply(20f, false), st.apply(1f, false)), true, 50, 0);
        assertEquals(1_000_000_000L, map.apply(transition).maxTickPeriodNanos(), "the longest candidate period during a transition");
        var unknownPrevious = new me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal(
                me.nicolas.papea2ac.player.TickCadenceLedger.TickRateSignal.Status.TRANSITION, List.of(st.apply(20f, false)), false, 2, 0);
        assertEquals(ClientTickCondition.UNRESOLVED, map.apply(unknownPrevious), "join: previous state unknown until the first settle");
    }

    /** Under /tick rate 5 a client tick holds four times the frames; with the scaled bound legitimate input is never NO_FIT. */
    @Test void slowServerTickRateUsesTheScaledBound() {
        var k = RotationCorpus.kinematics();
        var slow = new ClientTickCondition(true, 200_000_000L, 0);
        int windows = 0, noFit = 0;
        for (double s : new double[]{0.0, 0.25, 0.5, 1.0}) for (long seed = 1; seed <= 4; seed++) {
            var stream = RotationCorpus.legitimate("tick5", seed * 13 + (long) (s * 100), RotationCorpus.ClientOptions.raw(s).withFrames(80).withStart(0, 0), 600);
            var h = new RotationHistory(); var a = new RotationAnalyzer(k, C, true);
            List<RotationAnalysis> w = new ArrayList<>();
            RotationCorpus.replay(stream, h, () -> a.offer(h.snapshot(), slow).ifPresent(w::add));
            for (var x : w) {
                if (x.fit().status() == QuantizationFit.Status.INSUFFICIENT_SAMPLES || k.unit(s, false) < x.fit().resolutionFloor()) continue;
                windows++;
                if (x.fit().status() == QuantizationFit.Status.NO_FIT) noFit++;
            }
        }
        System.out.println("[phase15] /tick rate 5 (80 frames per tick, scaled bound 80): windows=" + windows + " NO_FIT=" + noFit);
        assertTrue(windows > 50);
        assertEquals(0, noFit);
    }

    @Test void unresolvedTickRateNeverFitsOrMisfits() {
        var h = new RotationHistory();
        RotationCorpus.replay(RotationCorpus.legitimate("join", 3, RotationCorpus.ClientOptions.raw(0.5), 200), h, () -> {});
        var r = new RotationAnalyzer(RotationCorpus.kinematics(), C, true).analyze(h.snapshot(), ClientTickCondition.UNRESOLVED);
        assertEquals(QuantizationFit.Status.PRECISION_LIMITED, r.fit().status());
        assertTrue(r.uncertainty().contains(RotationAnalysis.Uncertainty.TICK_RATE_UNRESOLVED));
        assertTrue(r.features().nonZeroDeltas() > 0, "features other than the fit are still reported");
        assertEquals(RotationAnalysis.UnitContinuity.NOT_APPLICABLE, r.unitContinuity());
    }

    @Test void tickRateChangeInsideTheWindowSuspendsTheFit() {
        var h = new RotationHistory();
        RotationCorpus.replay(RotationCorpus.legitimate("change", 4, RotationCorpus.ClientOptions.raw(0.5), 200), h, () -> {});
        var s = h.snapshot();
        long mid = s.sequences()[s.size() / 2];
        var a = new RotationAnalyzer(RotationCorpus.kinematics(), C, true);
        var r = a.analyze(s, new ClientTickCondition(true, 50_000_000L, mid));
        assertTrue(r.uncertainty().contains(RotationAnalysis.Uncertainty.TICK_RATE_CHANGED_IN_WINDOW));
        assertNotEquals(QuantizationFit.Status.FIT, r.fit().status());
        assertNotEquals(QuantizationFit.Status.NO_FIT, r.fit().status());
        // once the window starts after the change, the fit resumes
        var after = a.analyze(s, new ClientTickCondition(true, 50_000_000L, s.sequences()[0]));
        assertEquals(QuantizationFit.Status.FIT, after.fit().status());
    }

    @Test void largeUnwrappedYawIsPrecisionLimitedNeverNoFit() {
        var w = windows(RotationCorpus.legitimate("large-yaw", 14, RotationCorpus.ClientOptions.raw(0.5).withStart(100_000f, 0), 600));
        assertEquals(0, count(w, QuantizationFit.Status.NO_FIT));
        assertTrue(w.stream().anyMatch(x -> x.uncertainty().contains(RotationAnalysis.Uncertainty.PRECISION_LIMITED)));
    }

    @Test void sensitivityChangeShowsAsUnitChangeNotAnomaly() {
        var k = RotationCorpus.kinematics();
        var w = windows(RotationCorpus.sensitivityChange(15, 0.3, 0.7, 1200));
        assertTrue(w.stream().anyMatch(x -> x.unitContinuity() == RotationAnalysis.UnitContinuity.CHANGED));
        var last = w.get(w.size() - 1);
        assertEquals(QuantizationFit.Status.FIT, last.fit().status());
        assertEquals(k.unit(0.7, false), last.fit().unit(), k.unit(0.7, false) * 1e-4);
        // a window straddling the change may legitimately fail to fit; when it does, the change is among its confounders
        for (var x : w) if (x.fit().status() == QuantizationFit.Status.NO_FIT) assertTrue(x.confounders().contains(RotationAnalysis.Confounder.SENSITIVITY_CHANGED));
    }

    /** Legitimate confounders: the quantization feature cannot tell these from automation, so it must say so. */
    @Test void smoothCameraAndFractionalInputAreReportedAsConfounded() {
        for (var o : List.of(RotationCorpus.ClientOptions.raw(0.5).withSmooth(), RotationCorpus.ClientOptions.raw(0.5).withFractional())) {
            var w = windows(RotationCorpus.legitimate("confounded", 16, o, 600));
            long noFit = count(w, QuantizationFit.Status.NO_FIT);
            assertTrue(noFit > 0, "confounded legitimate input does not fit: " + o);
            for (var x : w) if (x.fit().status() != QuantizationFit.Status.FIT) {
                assertTrue(x.confounders().contains(RotationAnalysis.Confounder.SMOOTH_CAMERA));
                assertTrue(x.confounders().contains(RotationAnalysis.Confounder.NON_RAW_OR_FRACTIONAL_INPUT));
                assertFalse(x.calibrated());
            }
        }
    }

    // ------------------------------------------------------------------ adversarial corpus (measured, not assumed)

    @Test void adversarialShapesProduceTheirFeatures() {
        for (var kind : RotationCorpus.Adversary.values()) {
            var w = windows(RotationCorpus.adversarial(kind, 21, 0.5, 600));
            assertFalse(w.isEmpty(), kind.name());
            var last = w.get(w.size() - 1);
            assertFalse(last.calibrated(), "no rotation window carries punitive weight");
            switch (kind) {
                case SMOOTH_AIM_ASSIST, YAW_ONLY_ASSIST, RANDOM_NOISE -> assertTrue(count(w, QuantizationFit.Status.FIT) <= w.size() * 0.1, kind + " fits " + w);
                case GCD_AWARE_AIM_ASSIST -> assertTrue(count(w, QuantizationFit.Status.FIT) >= w.size() * 0.9,
                        "documented bypass: rounding to the unit defeats the quantization feature, other features must carry it");
                case SPIN -> { assertTrue(last.features().exactRepeats() > 10); assertTrue(last.features().largeYawDeltas() > 10);
                    assertEquals(0, count(w, QuantizationFit.Status.FIT), "one repeated delta is a multiple of each of its divisors: never a fit"); }
                case PITCH_OUT_OF_RANGE -> assertTrue(last.features().pitchOutsideProfile() > 0);
                case SNAP_AIM -> assertTrue(w.stream().anyMatch(x -> x.features().largeYawDeltas() > 0 || x.features().maxAbsYaw() > 10));
            }
            if (kind == RotationCorpus.Adversary.YAW_ONLY_ASSIST) assertEquals(last.features().nonZeroDeltas(), last.features().yawOnlyDeltas());
        }
    }

    // ------------------------------------------------------------------ determinism and robustness

    @Test void analysisIsDeterministic() {
        var s = RotationCorpus.legitimate("det", 99, RotationCorpus.ClientOptions.raw(0.37), 800);
        assertEquals(windows(s), windows(s));
        var adv = RotationCorpus.adversarial(RotationCorpus.Adversary.SMOOTH_AIM_ASSIST, 99, 0.37, 800);
        assertEquals(windows(adv), windows(adv));
    }

    @Test void arbitraryFiniteInputNeverThrowsAndStaysBounded() {
        var rnd = new SplittableRandom(4242);
        for (int run = 0; run < 200; run++) {
            var h = new RotationHistory();
            var a = new RotationAnalyzer(RotationCorpus.kinematics(), C, run % 2 == 0);
            long seq = 0;
            for (int i = 0; i < 300; i++) {
                if (rnd.nextInt(40) == 0) h.breakSegment(RotationHistory.Break.TRANSPORT_GAP);
                float y = switch (rnd.nextInt(4)) { case 0 -> Float.MAX_VALUE * (rnd.nextBoolean() ? 1 : -1); case 1 -> (float) rnd.nextDouble(-1e7, 1e7); case 2 -> -0.0f; default -> Float.MIN_VALUE; };
                float p = (float) rnd.nextDouble(-400, 400);
                seq += rnd.nextInt(5) == 0 ? -1 : 1 + rnd.nextInt(3);
                h.observe(Math.max(seq, 0), new Rotation(y, p));
                var r = a.analyze(h.snapshot());
                assertTrue(r.features().samples() <= RotationHistory.CAPACITY);
                if (run % 2 != 0) assertTrue(r.uncertainty().contains(RotationAnalysis.Uncertainty.PROFILE_UNVERIFIED));
            }
        }
    }

    @Test void emptyHistoryIsInsufficientNotAnError() {
        var r = new RotationAnalyzer(RotationCorpus.kinematics(), C, true).analyze(new RotationHistory().snapshot());
        assertEquals(QuantizationFit.Status.INSUFFICIENT_SAMPLES, r.fit().status());
        assertTrue(r.uncertainty().contains(RotationAnalysis.Uncertainty.INSUFFICIENT_SAMPLES));
    }
}
