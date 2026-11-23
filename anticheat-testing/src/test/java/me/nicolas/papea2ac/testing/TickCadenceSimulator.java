package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.player.TickCadenceEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
/**
 * Phase 14 independent oracle: a discrete-event model of the pinned vanilla 26.2 client's tick scheduling, a two-way network and the
 * server's capture taps, producing the {@link TickCadenceEvent} stream the server would capture. The client side is transcribed from the client
 * bytecode (see {@code ClientCadenceFactsTest}), NOT from the validator: {@code DeltaTracker.Timer.advanceGameTime} in float arithmetic
 * ({@code deltaTicks = (float)(ms - lastMs) / mspt}, {@code residual += deltaTicks}, {@code i = (int) residual}, {@code residual -= i}),
 * {@code Util.getMillis} truncation of the client's own monotonic clock (which may drift), {@code Minecraft.runTick} running
 * {@code processQueuedPackets} after {@code advanceGameTime} and before {@code min(10, i)} ticks, game-thread handlers for ping (pong),
 * position/rotation corrections (one movement packet each) and ticking state ({@code max(50, mspt)} while running normally, 50 when frozen),
 * one optional movement packet and one tick end per tick, and one FIFO for every game-thread write. The network delivers each direction in
 * order with base delay, jitter and stall intervals; the server's event loop may pause (captures and encoder taps wait for its end).
 *
 * <p>Cheat knobs (ground truth for the detection side): a timer factor (ticks credited faster than real time), ignoring the ten-tick frame
 * cap, suppressing tick ends, holding pongs, answering pings from the network thread. Everything is deterministic for a seed.
 */
final class TickCadenceSimulator {
    static final long MS = 1_000_000L, S = 1_000_000_000L;
    record Interval(long start, long end) {}
    /** One scenario. Times are real (absolute) nanoseconds; the client clock runs {@code driftPpm} faster than real time. */
    static final class Config {
        long seed = 1, durationNanos = 60 * S;
        long driftPpm = 0;
        long frameMinNanos = 4 * MS, frameMaxNanos = 17 * MS; double hitchProbability = 0.002; long hitchMinNanos = 100 * MS, hitchMaxNanos = 1500 * MS;
        List<Interval> clientFreezes = new ArrayList<>();
        long tickCostMinNanos = 100_000, tickCostMaxNanos = 2 * MS; long procMaxNanos = 500_000; double moveProbability = 0.8;
        long upBaseNanos = 20 * MS, upJitterNanos = 5 * MS, downBaseNanos = 20 * MS, downJitterNanos = 5 * MS;
        List<Interval> upStalls = new ArrayList<>(), downStalls = new ArrayList<>(), serverPauses = new ArrayList<>();
        long pingPeriodNanos = S; long pingPeriodJitterNanos = 0; double inducedPerSecond = 0.2; double pingWriteFailProbability = 0; double pluginPingCollisionProbability = 0;
        List<long[]> tickRateChanges = new ArrayList<>(); // {timeNanos, rateMilli, frozen 0/1}
        /** Combat-time marker cadence (Phase 12 change to the Phase 7 scheduler): inside these intervals (the player attacked in the last 3 s) one
         *  marker per server tick that saw nearby entity movement ({@code combatMarkerProbability}), at least 50 ms after the previous marker,
         *  at most 16 pending (approximated from the path's base round trip); the 1 Hz periodic marker continues throughout. */
        List<Interval> combat = new ArrayList<>(); double combatMarkerProbability = 0.9;
        // cheat knobs
        double timerFactor = 1.0; long timerStartNanos = 0; boolean ignoreFrameCap; boolean suppressTickEnds; long pongHoldNanos; boolean pongFromNetworkThread;
        boolean cheating() { return timerFactor > 1.0 || ignoreFrameCap; }
        Config copy() { Config c = new Config(); c.seed = seed; c.durationNanos = durationNanos; c.driftPpm = driftPpm; c.frameMinNanos = frameMinNanos; c.frameMaxNanos = frameMaxNanos; c.hitchProbability = hitchProbability; c.hitchMinNanos = hitchMinNanos; c.hitchMaxNanos = hitchMaxNanos;
            c.clientFreezes = new ArrayList<>(clientFreezes); c.tickCostMinNanos = tickCostMinNanos; c.tickCostMaxNanos = tickCostMaxNanos; c.procMaxNanos = procMaxNanos; c.moveProbability = moveProbability; c.upBaseNanos = upBaseNanos; c.upJitterNanos = upJitterNanos; c.downBaseNanos = downBaseNanos; c.downJitterNanos = downJitterNanos;
            c.upStalls = new ArrayList<>(upStalls); c.downStalls = new ArrayList<>(downStalls); c.serverPauses = new ArrayList<>(serverPauses); c.pingPeriodNanos = pingPeriodNanos; c.pingPeriodJitterNanos = pingPeriodJitterNanos; c.inducedPerSecond = inducedPerSecond; c.pingWriteFailProbability = pingWriteFailProbability; c.pluginPingCollisionProbability = pluginPingCollisionProbability;
            c.tickRateChanges = new ArrayList<>(tickRateChanges); c.combat = new ArrayList<>(combat); c.combatMarkerProbability = combatMarkerProbability; c.timerFactor = timerFactor; c.timerStartNanos = timerStartNanos; c.ignoreFrameCap = ignoreFrameCap; c.suppressTickEnds = suppressTickEnds; c.pongHoldNanos = pongHoldNanos; c.pongFromNetworkThread = pongFromNetworkThread; return c; }
    }
    /** A server-side capture: the event (sequence assigned after the merge) and its capture time. */
    record Capture(long nanos, int order, Kind kind, long id, long wire, TickCadenceEvent.Induced induced, float rate, boolean frozen) {}
    enum Kind { TICK_END, MOVEMENT, PONG, PING, WRITE_FAILED, INDUCED, TICK_RATE }
    /** The result: the captured stream in capture order and ground truth about the client. */
    record Trace(List<TickCadenceEvent> events, long ticksExecuted, long frames, long cappedTicksDropped, long realStart, long realEnd) {}

    private enum Down { PING, CORRECTION, ROTATION, TICK_RATE }
    private record Outbound(long tap, long wireSend, Down kind, long id, long wire, float rate, boolean frozen, boolean failed) {}
    private record Send(long time, int order, Kind kind, long id) {}

    static Trace run(Config c) {
        SplittableRandom rnd = new SplittableRandom(c.seed);
        long start = 1_000 * S + rnd.nextLong(S); // arbitrary monotonic origin (differences only)
        long end = start + c.durationNanos;
        List<Interval> freezes = shift(c.clientFreezes, start), upStalls = shift(c.upStalls, start), downStalls = shift(c.downStalls, start), pauses = shift(c.serverPauses, start);
        List<Capture> captures = new ArrayList<>();
        int[] order = {0};
        // ---- server outbound schedule (pings, induced packets, tick-rate changes), in wire order ----
        List<long[]> schedule = new ArrayList<>(); // {time, kind}
        List<Long> pingTimes = new ArrayList<>();
        for (long t = start + rnd.nextLong(c.pingPeriodNanos); t < end; t += c.pingPeriodNanos + (c.pingPeriodJitterNanos > 0 ? rnd.nextLong(c.pingPeriodJitterNanos) : 0)) pingTimes.add(t);
        if (!c.combat.isEmpty()) {
            long phase = rnd.nextLong(50 * MS);
            for (Interval i : shift(c.combat, start)) for (long t = i.start() + phase; t < Math.min(i.end(), end); t += 50 * MS) if (rnd.nextDouble() < c.combatMarkerProbability) pingTimes.add(t);
            pingTimes.sort(null);
            long rtt = c.upBaseNanos + c.downBaseNanos + c.upJitterNanos / 2 + c.downJitterNanos / 2 + 20 * MS;
            List<Long> kept = new ArrayList<>(); long last = Long.MIN_VALUE / 2;
            for (long t : pingTimes) {
                if (t - last < 50 * MS) continue; // at least 50 ms apart
                int pendingEstimate = 0; for (int k = kept.size() - 1; k >= 0 && t - kept.get(k) < rtt; k--) pendingEstimate++;
                if (pendingEstimate >= 16) continue; // at most 16 pending
                kept.add(t); last = t;
            }
            pingTimes = kept;
        }
        for (long t : pingTimes) schedule.add(new long[] { t, 0 });
        if (c.inducedPerSecond > 0) for (long t = start; t < end;) { t += (long) (-Math.log(1 - rnd.nextDouble()) / c.inducedPerSecond * S); if (t < end) schedule.add(new long[] { t, rnd.nextBoolean() ? 1 : 2 }); }
        for (long[] tr : c.tickRateChanges) schedule.add(new long[] { start + tr[0], 3, tr[1], tr[2] });
        schedule.sort(Comparator.comparingLong(a -> a[0]));
        List<Outbound> outbound = new ArrayList<>();
        long wire = 0, pingId = 0, lastTap = start, lastWire = start, downArrivalFloor = start;
        for (long[] s : schedule) {
            long tap = Math.max(lastTap, after(s[0], pauses)); lastTap = tap;
            long send = Math.max(lastWire, tap + rnd.nextLong(200_000)); lastWire = send;
            Down kind = switch ((int) s[1]) { case 0 -> Down.PING; case 1 -> Down.CORRECTION; case 2 -> Down.ROTATION; default -> Down.TICK_RATE; };
            long id = kind == Down.PING ? ++pingId : 0;
            if (kind == Down.PING && c.pluginPingCollisionProbability > 0 && rnd.nextDouble() < c.pluginPingCollisionProbability) {
                // another plugin's ping with the same id, written just before ours
                Outbound plugin = new Outbound(tap, send, Down.PING, id, ++wire, 0, false, false); outbound.add(plugin);
                captures.add(new Capture(tap, order[0]++, Kind.PING, id, plugin.wire(), null, 0, false));
                tap += 1000; send += 1000; lastTap = tap; lastWire = send;
            }
            boolean failed = kind == Down.PING && rnd.nextDouble() < c.pingWriteFailProbability;
            Outbound o = new Outbound(tap, send, kind, id, ++wire, s.length > 2 ? s[2] / 1000f : 0, s.length > 3 && s[3] != 0, failed);
            outbound.add(o);
            switch (kind) {
                case PING -> captures.add(new Capture(tap, order[0]++, Kind.PING, id, o.wire(), null, 0, false));
                case CORRECTION -> captures.add(new Capture(tap, order[0]++, Kind.INDUCED, 0, o.wire(), TickCadenceEvent.Induced.CORRECTION, 0, false));
                case ROTATION -> captures.add(new Capture(tap, order[0]++, Kind.INDUCED, 0, o.wire(), TickCadenceEvent.Induced.ROTATION, 0, false));
                case TICK_RATE -> captures.add(new Capture(tap, order[0]++, Kind.TICK_RATE, 0, o.wire(), null, o.rate(), o.frozen()));
            }
            if (failed) captures.add(new Capture(tap + 1000, order[0]++, Kind.WRITE_FAILED, 0, o.wire(), null, 0, false));
        }
        // downstream arrivals at the client, in wire order
        long[] arrival = new long[outbound.size()];
        for (int i = 0; i < outbound.size(); i++) {
            Outbound o = outbound.get(i);
            if (o.failed()) { arrival[i] = Long.MAX_VALUE; continue; }
            long a = o.wireSend() + c.downBaseNanos + (c.downJitterNanos > 0 ? rnd.nextLong(c.downJitterNanos) : 0);
            a = after(a, downStalls);
            a = Math.max(a, downArrivalFloor); downArrivalFloor = a; arrival[i] = a;
        }
        // ---- client game thread ----
        List<Send> sends = new ArrayList<>();
        int sendOrder = 0;
        long t = start + rnd.nextLong(20 * MS);
        long lastMs = clientMs(t, c.driftPpm); float residual = 0f; float msptF = 50.0f;
        int next = 0; long ticks = 0, frames = 0, dropped = 0;
        boolean frozen = false; float rate = 20.0f;
        while (t < end) {
            long frozenUntil = within(t, freezes);
            if (frozenUntil > t) t = frozenUntil; // the whole process is stopped (debugger, OS freeze): no frame starts
            frames++;
            long f = t;
            long ms = clientMs(f, c.driftPpm);
            float target = frozen ? 50.0f : Math.max(50.0f, msptF);
            float delta = (float) (ms - lastMs) / target;
            if (c.timerFactor > 1.0 && f >= start + c.timerStartNanos) delta *= (float) c.timerFactor;
            lastMs = ms; residual += delta; int owed = (int) residual; residual -= owed;
            long now = f + rnd.nextLong(c.procMaxNanos + 1);
            // processQueuedPackets: everything that arrived before this point, in arrival (= wire) order
            while (next < outbound.size() && arrival[next] <= now) {
                Outbound o = outbound.get(next);
                switch (o.kind()) {
                    case PING -> { if (!c.pongFromNetworkThread) sends.add(new Send(now + c.pongHoldNanos, sendOrder++, Kind.PONG, o.id())); }
                    case CORRECTION, ROTATION -> sends.add(new Send(now, sendOrder++, Kind.MOVEMENT, 0));
                    case TICK_RATE -> { rate = o.rate(); frozen = o.frozen(); msptF = 1000.0f / rate; }
                }
                now += 1000;
                next++;
            }
            int run = c.ignoreFrameCap ? owed : Math.min(10, owed); dropped += owed - run;
            for (int k = 0; k < run; k++) {
                now += c.tickCostMinNanos + rnd.nextLong(c.tickCostMaxNanos - c.tickCostMinNanos + 1);
                // a client suppressing its tick ends is modelled moving every tick (its only remaining per-tick packet)
                if (c.suppressTickEnds || rnd.nextDouble() < c.moveProbability) sends.add(new Send(now, sendOrder++, Kind.MOVEMENT, 0));
                if (!c.suppressTickEnds) sends.add(new Send(now + 1, sendOrder++, Kind.TICK_END, 0));
                ticks++;
            }
            long duration = rnd.nextDouble() < c.hitchProbability ? c.hitchMinNanos + rnd.nextLong(c.hitchMaxNanos - c.hitchMinNanos + 1) : c.frameMinNanos + rnd.nextLong(c.frameMaxNanos - c.frameMinNanos + 1);
            t = Math.max(now + 1000, f + duration);
        }
        // a modified client answering on receipt: the pong leaves at the arrival time, ahead of the game thread
        if (c.pongFromNetworkThread) for (int i = 0; i < outbound.size(); i++) if (outbound.get(i).kind() == Down.PING && arrival[i] != Long.MAX_VALUE && arrival[i] < end) sends.add(new Send(arrival[i], sendOrder++, Kind.PONG, outbound.get(i).id()));
        // ---- upstream: the client's single write FIFO in send order, delivered in order ----
        sends.sort(Comparator.comparingLong(Send::time).thenComparingInt(Send::order));
        long upFloor = start;
        for (Send s : sends) {
            long a = s.time() + c.upBaseNanos + (c.upJitterNanos > 0 ? rnd.nextLong(c.upJitterNanos) : 0);
            a = after(a, upStalls); a = Math.max(a, upFloor); upFloor = a;
            long cap = after(a, pauses);
            if (cap > end + 30 * S) break;
            captures.add(new Capture(cap, order[0]++, s.kind(), s.id(), 0, null, 0, false));
        }
        // ---- server capture order: by capture time; outbound captures precede inbound ones at equal times ----
        captures.sort(Comparator.comparingLong(Capture::nanos).thenComparingInt(Capture::order));
        List<TickCadenceEvent> events = new ArrayList<>(captures.size());
        long seq = 0, lastNanos = 0;
        for (Capture cp : captures) {
            long n = Math.max(lastNanos, cp.nanos()); lastNanos = n; seq++;
            events.add(switch (cp.kind()) {
                case TICK_END -> new TickCadenceEvent.TickEnd(seq, n);
                case MOVEMENT -> new TickCadenceEvent.Movement(seq, n);
                case PONG -> new TickCadenceEvent.PingAnswered(seq, n, cp.id());
                case PING -> new TickCadenceEvent.PingSent(seq, n, cp.id(), cp.wire());
                case WRITE_FAILED -> new TickCadenceEvent.WriteFailed(seq, n, cp.wire());
                case INDUCED -> new TickCadenceEvent.InducedSent(seq, n, cp.wire(), cp.induced());
                case TICK_RATE -> new TickCadenceEvent.TickRate(seq, n, cp.wire(), cp.rate(), cp.frozen());
            });
        }
        return new Trace(events, ticks, frames, dropped, start, end);
    }
    /** The client's Util.getMillis: its own monotonic clock (drifting) truncated to milliseconds. */
    static long clientMs(long realNanos, long driftPpm) { return (realNanos + Math.floorDiv(realNanos * driftPpm, 1_000_000L)) / MS; }
    /** The first instant at or after {@code t} outside every interval (a stalled path delivers at the stall's end). */
    static long after(long t, List<Interval> intervals) { long x = t; boolean moved = true; while (moved) { moved = false; for (Interval i : intervals) if (x >= i.start() && x < i.end()) { x = i.end(); moved = true; } } return x; }
    private static long within(long t, List<Interval> intervals) { return after(t, intervals); }
    private static List<Interval> shift(List<Interval> relative, long origin) { List<Interval> out = new ArrayList<>(relative.size()); for (Interval i : relative) out.add(new Interval(origin + i.start(), origin + i.end())); return out; }
}
