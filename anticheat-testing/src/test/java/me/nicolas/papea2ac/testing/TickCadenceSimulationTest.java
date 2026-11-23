package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TickCadenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import org.junit.jupiter.api.Test;
/**
 * Phase 14 soundness and sensitivity against the independent client model ({@link TickCadenceSimulator}, transcribed from the 26.2 client
 * bytecode). Soundness: no legitimate stream - whatever its frame pacing, hitches, freezes, drift within the physical range, network delay,
 * jitter and stalls in either direction, server pauses, marker cadence (1 Hz or 20 Hz), failed or colliding pings, server corrections or
 * tick-rate changes - is ever classified INVALID. Sensitivity: timer factors from 1.05 upwards are excluded within a bounded time, and a
 * client that suppresses its tick ends is excluded through its movement packets.
 */
class TickCadenceSimulationTest {
    static final TickCadenceProfile P = TickCadenceProfile.PAPER_26_2;

    @Test void noLegitimateStreamIsEverInvalid() {
        long windows = 0, anchors = 0, worstMargin = Long.MAX_VALUE; long worstSeed = -1;
        for (long seed = 1; seed <= 240; seed++) {
            final long s = seed;
            TickCadenceSimulator.Config c = legit(seed);
            TickCadenceSimulator.Trace t = TickCadenceSimulator.run(c);
            Replay r = replay(t, P);
            assertFalse(r.anyInvalid(), () -> "legitimate seed " + s + " was excluded: " + r.firstInvalid().describe());
            anchors += r.results().size();
            for (var res : r.results()) windows += res.windowsEvaluated();
            if (r.worstLegitMargin() < worstMargin) { worstMargin = r.worstLegitMargin(); worstSeed = seed; }
        }
        assertTrue(anchors > 5_000, "the corpus produced anchors: " + anchors);
        assertTrue(worstMargin >= 0, "worst legitimate margin " + worstMargin + " (seed " + worstSeed + ")");
        System.out.println("cadence legit corpus: anchors=" + anchors + " windows=" + windows + " worstMarginTicks=" + worstMargin + " (seed " + worstSeed + ")");
    }

    @Test void theVanillaFrameCapAndAFrozenProcessNeverProduceAnExcess() {
        TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 3; c.durationNanos = 90 * S;
        c.frameMinNanos = 450 * MS; c.frameMaxNanos = 2_000 * MS; c.hitchProbability = 0.2; c.hitchMaxNanos = 5 * S; // owed ticks far beyond ten per frame
        c.clientFreezes.add(new TickCadenceSimulator.Interval(20 * S, 29 * S));
        TickCadenceSimulator.Trace t = TickCadenceSimulator.run(c);
        assertTrue(t.cappedTicksDropped() > 100, "the scenario really exercises the ten-tick cap: " + t.cappedTicksDropped());
        assertFalse(replay(t, P).anyInvalid());
    }

    @Test void stallsInBothDirectionsAndServerPausesOnlyWidenTheWindows() {
        TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 5; c.durationNanos = 120 * S;
        c.downStalls.add(new TickCadenceSimulator.Interval(10 * S, 19 * S)); // the live P11-F10 case: 9 s server-to-client stall
        c.upStalls.add(new TickCadenceSimulator.Interval(40 * S, 50 * S)); // client-to-server stall: tick ends arrive as one burst
        c.serverPauses.add(new TickCadenceSimulator.Interval(70 * S, 72 * S)); // server event loop paused (GC)
        c.pingPeriodNanos = 50 * MS; // the proposed combat-time marker cadence
        Replay r = replay(TickCadenceSimulator.run(c), P);
        assertFalse(r.anyInvalid(), () -> r.firstInvalid().describe());
        assertTrue(r.valid() > 1_000);
    }

    @Test void theCombatTimeMarkerCadenceIsSoundAndDetectsTimersFaster() {
        // legitimate fighting players: markers every tick that sees entity movement during combat, 1 Hz otherwise, under stalls and jitter
        java.util.SplittableRandom r = new java.util.SplittableRandom(77);
        for (long seed = 1; seed <= 60; seed++) {
            TickCadenceSimulator.Config c = legit(500 + seed); c.pingPeriodNanos = S; c.combat.clear(); combat(c, r);
            final long s = seed; Replay rep = replay(TickCadenceSimulator.run(c), P);
            assertFalse(rep.anyInvalid(), () -> "combat-cadence legitimate seed " + s + ": " + rep.firstInvalid().describe());
        }
        // a 1.25x timer that only runs while fighting: detected within the fight
        TickCadenceSimulator.Config t = new TickCadenceSimulator.Config(); t.seed = 91; t.durationNanos = 90 * S; t.hitchProbability = 0; t.timerFactor = 1.25; t.timerStartNanos = 30 * S;
        t.combat.add(new TickCadenceSimulator.Interval(30 * S, 60 * S));
        Replay rep = replay(TickCadenceSimulator.run(t), P);
        assertTrue(rep.anyInvalid()); assertTrue(rep.firstInvalidNanos() - 30 * S < 10 * S, "a 1.25x timer started with the fight, first excluded " + (rep.firstInvalidNanos() - 30 * S) / 1e9 + " s into the fight");
        assertTrue(rep.results().size() > 400, "combat markers add anchors: " + rep.results().size());
    }

    @Test void reducedRaisedOrFrozenServerTickRatesNeverProduceAnExcess() {
        for (long[] change : new long[][] { { 5_000, 0 }, { 10_000, 0 }, { 40_000, 0 }, { 100_000, 0 }, { 20_000, 1 } }) {
            TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 11 + change[0]; c.durationNanos = 60 * S;
            c.tickRateChanges.add(new long[] { 10 * S, change[0], change[1] }); c.tickRateChanges.add(new long[] { 40 * S, 20_000, 0 });
            assertFalse(replay(TickCadenceSimulator.run(c), P).anyInvalid(), "tick rate " + change[0] / 1000 + " frozen=" + change[1]);
        }
    }

    @Test void timerFactorsAreExcludedWithinBoundedTime() {
        long[] bound = { 0 };
        for (double factor : new double[] { 1.05, 1.1, 1.25, 1.5, 2.0, 3.0 }) {
            TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 21; c.durationNanos = 120 * S; c.timerFactor = factor; c.hitchProbability = 0; // hitches drop owed ticks and delay detection (measured separately)
            Replay r = replay(TickCadenceSimulator.run(c), P);
            assertTrue(r.anyInvalid(), "timer x" + factor + " not excluded in 120 s");
            long expectedWithin = (long) ((P.frameSlackTicks() + 3) / ((factor - 1.0 - P.clockTolerancePpm() / 1e6) * 20) * S) + 3 * S; // slack ticks at the excess rate, plus anchors
            assertTrue(r.firstInvalidNanos() <= expectedWithin, "timer x" + factor + " first excluded after " + r.firstInvalidNanos() / 1e9 + " s (expected within " + expectedWithin / 1e9 + " s)");
            bound[0] = Math.max(bound[0], r.firstInvalidNanos());
        }
    }

    @Test void aTimerThatSuppressesItsTickEndsIsExcludedThroughItsMovements() {
        TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 31; c.durationNanos = 90 * S; c.timerFactor = 1.5; c.suppressTickEnds = true; c.inducedPerSecond = 1;
        Replay r = replay(TickCadenceSimulator.run(c), P);
        assertTrue(r.anyInvalid());
        assertEquals(me.nicolas.papea2ac.simulation.TickCadenceResult.InvalidCode.MOVEMENT_EXCESS, r.firstInvalid().invalidReasons().get(0));
        // the same suppression without a timer stays VALID: the movement estimator is sound on its own
        c.timerFactor = 1.0; assertFalse(replay(TickCadenceSimulator.run(c), P).anyInvalid());
    }

    @Test void heldOrNetworkThreadPongsDoNotHideATimerForLong() {
        TickCadenceSimulator.Config held = new TickCadenceSimulator.Config(); held.seed = 41; held.durationNanos = 120 * S; held.timerFactor = 1.5; held.pongHoldNanos = 3 * S;
        assertTrue(replay(TickCadenceSimulator.run(held), P).anyInvalid(), "holding pongs 3 s only widens each window by 3 s");
        TickCadenceSimulator.Config early = new TickCadenceSimulator.Config(); early.seed = 42; early.durationNanos = 120 * S; early.timerFactor = 1.5; early.pongFromNetworkThread = true;
        assertTrue(replay(TickCadenceSimulator.run(early), P).anyInvalid(), "answering on the network thread only tightens the windows");
        TickCadenceSimulator.Config earlyLegit = early.copy(); earlyLegit.timerFactor = 1.0;
        // a network-thread pong is not a vanilla behaviour: it can make the earlier fence late by at most the client's queueing delay; the model shows no excess for an honest timer
        assertFalse(replay(TickCadenceSimulator.run(earlyLegit), P).anyInvalid());
    }

    @Test void catchingUpBeyondTheFrameCapAfterAFreezeIsExcludedButAShortOneIsInsideTheSlack() {
        // a vanilla client drops owed ticks beyond ten per frame; a client that runs all of them after a freeze executes, after the pongs it answers
        // on resuming, more ticks than the window since those pings allows ("balance" catch-up). A 2 s freeze banks 40 ticks: excluded.
        TickCadenceSimulator.Config c = new TickCadenceSimulator.Config(); c.seed = 51; c.durationNanos = 60 * S; c.hitchProbability = 0; c.ignoreFrameCap = true; c.clientFreezes.add(new TickCadenceSimulator.Interval(20 * S, 22 * S));
        Replay r = replay(TickCadenceSimulator.run(c), P);
        assertTrue(r.anyInvalid(), "a 40-tick catch-up burst beyond the vanilla cap");
        // a 0.5 s freeze banks 10 ticks: exactly what a vanilla frame may also run, inside the slack
        TickCadenceSimulator.Config shortFreeze = c.copy(); shortFreeze.clientFreezes.clear(); shortFreeze.clientFreezes.add(new TickCadenceSimulator.Interval(20 * S, 20 * S + 500 * MS));
        assertFalse(replay(TickCadenceSimulator.run(shortFreeze), P).anyInvalid());
        // and the same 2 s freeze on a vanilla client (cap kept) is never excluded
        TickCadenceSimulator.Config vanilla = c.copy(); vanilla.ignoreFrameCap = false;
        assertFalse(replay(TickCadenceSimulator.run(vanilla), P).anyInvalid());
    }
}
