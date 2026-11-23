package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Phase 11 entity-count kernels (contributed by the cross-phase performance review, P11-F26): how the Phase 11 per-session combat path scales with the number of entities the observer was sent.
 * Same methodology as the phase benchmarks (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc); descriptive, one host, no capacity claim.
 *
 * <p>Every kernel drives the real core {@link CombatRunner} (mapper + entity history + engine), so it measures what the shard pays per observation.
 * {@code entities} live entities are spawned and given 3 s of per-tick relative moves first, so the nearest 32 carry full deep histories.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
public class CombatEntityCountBenchmarks {
    static final SessionKey KEY = new SessionKey(new UUID(0x7E8FL, 0x8L), 1);
    static final long TICK = 50_000_000L;

    /** A CombatRunner on an empty observer world with {@code n} live, recently moving entities. */
    static class Rig {
        CombatRunner runner; long seq, nanos = 1_000_000_000L, ticks; int n;
        void build(int n) {
            this.n = n;
            var w = new ObserverWorld(KEY, new WorldBudget(), new BlockStateDictionary()); var t = new PlayerTimeline(KEY);
            runner = new CombatRunner(KEY, CombatProfile.PAPER_26_2, w, t, () -> null, () -> null, new CombatEngine.Metrics());
            for (int i = 0; i < n; i++) outbound(spawn(100 + i, i % 16, i / 16), 0);
            for (int tick = 0; tick < 60; tick++) { tick(); for (int i = 0; i < n; i++) outbound(move(100 + i), i == 0 ? TICK : 0); }
            if (runner.entities().status().live() != n) throw new IllegalStateException("rig: expected " + n + " live, got " + runner.entities().status());
        }
        void apply(Observation.Event e, long dt) { seq++; nanos += dt; runner.apply(new Observation(KEY, seq, nanos, 0, e), nanos, 1, 0, null, false); }
        void outbound(PacketPayload p, long dt) { apply(new Observation.Outbound(seq + 1, p), dt); }
        void tick() { apply(new Observation.Tick(++ticks), 0); }
    }
    static SemanticPayload.EntitySpawn spawn(int id, double x, double z) {
        return new SemanticPayload.EntitySpawn(id, new UUID(1, id), "minecraft:zombie", new PacketPayload.Position(x, 64, z), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), 0f, 0);
    }
    static SemanticPayload.EntityDelta move(int id) { return new SemanticPayload.EntityDelta(id, (short) 37, (short) 0, (short) -11, new PacketPayload.Rotation(12f, 3f), true, true, true); }

    /** No-combat path: a serverbound movement observation while the observer tracks {@code entities} entities (P11 spec 57: must not materially slow movement). */
    @State(Scope.Thread)
    public static class Idle extends Rig {
        @Param({ "0", "40", "256" }) public int entities;
        PacketPayload movement;
        @Setup public void setup() { build(entities); movement = new PacketPayload.Movement(new PacketPayload.Position(1.5, 64, 2.5), new PacketPayload.Rotation(90f, 10f), true, false); }
    }
    @Benchmark public long noCombatMovementVsEntityCount(Idle s) { s.apply(new Observation.Attempt(s.seq + 1, s.seq + 1, s.movement), 1_000_000L); return s.seq; }

    /** One clientbound relative move while {@code entities} entities each move once per 50 ms tick (the steady state of a crowded view). */
    @State(Scope.Thread)
    public static class Crowd extends Rig {
        @Param({ "1", "40", "256" }) public int entities;
        int next;
        @Setup public void setup() { build(entities); }
    }
    @Benchmark public long entityMoveVsEntityCount(Crowd s) { int i = s.next; s.next = (i + 1) % s.entities; s.outbound(move(100 + i), i == 0 ? TICK : 0); return s.seq; }

    /** SetPassengers mount/dismount toggling (boats, minecarts, horses) in a full 256-entity view. */
    @State(Scope.Thread)
    public static class Riders extends Rig {
        boolean mounted; PacketPayload mount, dismount;
        @Setup public void setup() { build(256); mount = new PacketPayload.Passengers(100, List.of(101)); dismount = new PacketPayload.Passengers(100, List.of()); }
    }
    @Benchmark public long passengersToggleFullView(Riders s) { s.mounted = !s.mounted; s.outbound(s.mounted ? s.mount : s.dismount, 1_000_000L); return s.seq; }

    /** Projectile churn (snowball / arrow spam visible to this observer): spawn, 3 moves, removal, ids never reused, 200 other live entities. */
    @State(Scope.Thread)
    public static class Churn extends Rig {
        int nextId = 1_000_000;
        @Setup public void setup() { build(200); }
    }
    @Benchmark public long projectileChurnCrowdedView(Churn s) {
        int id = s.nextId++; s.outbound(spawn(id, 0.5, 0.5), 1_000_000L);
        for (int k = 0; k < 3; k++) s.outbound(move(id), TICK);
        s.outbound(new SemanticPayload.RemoveEntities(List.of(id)), 1_000_000L); return s.seq;
    }
}
