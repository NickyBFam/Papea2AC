package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.*;
import me.nicolas.papea2ac.world.*;

/**
 * Thread 8 (cross-phase performance) Phase 12 kernels: the per-session cost the reach runner adds on top of the Phase 11 combat runner, fed in the
 * same order as PacketEngine (context, timeline, world, timing ledger, combat runner, reach runner). {@code reach=false} is the Phase 11 baseline
 * in the same fork layout, so each pair is a direct A/B. Same methodology as the phase benchmarks; descriptive, one host, no capacity claim.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
public class CrossPhaseReachBenchmarks {
    static final long TICK = 50_000_000L, MS = 1_000_000L;
    static final int SELF = 42;

    static class Rig {
        final SessionKey key = new SessionKey(new UUID(0x12AL, 0x8L), 1);
        ObserverWorld world; PlayerTimeline timeline; TimingLedger timing; MovementContextLedger context; CombatRunner combat; ReachRunner reach; boolean withReach;
        long seq, nanos = 1_000_000_000L, ticks, wire = 1000, attempt; int pingId = 0x0100_0000;
        void build(int n, boolean withReach) {
            this.withReach = withReach;
            world = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary()); timeline = new PlayerTimeline(key); timing = new TimingLedger(key); context = new MovementContextLedger(key);
            combat = new CombatRunner(key, CombatProfile.PAPER_26_2, world, timeline, timing::view, () -> null, new CombatEngine.Metrics());
            reach = new ReachRunner(key, EntityGeometryTable.paper26_2(), MovementProfile.paper26_2(), CombatProfile.PAPER_26_2, new ReachRunner.Metrics());
            outbound(new SemanticPayload.JoinGame(SELF, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false, -64, 384), 0);
            for (int i = 0; i < n; i++) outbound(CrossPhaseSupport.spawn(100 + i, (i % 16) * 0.9, 3 + (i / 16) * 0.9), 0);
            for (int t = 0; t < 60; t++) { clientTick(0f); for (int i = 0; i < n; i++) outbound(CrossPhaseSupport.move(100 + i), 0); serverTick(); }
            marker();
        }
        void apply(Observation.Event e, long dt) {
            seq++; nanos += dt; Observation o = new Observation(key, seq, nanos, 0, e);
            for (ContextEvent ce : ContextObservationMapper.map(o, SELF)) context.apply(key, ce);
            PlayerObservationMapper.map(o, SELF).ifPresent(po -> timeline.append(key, po, seq, nanos));
            if (e instanceof Observation.Outbound out) for (WorldObservation w : WorldObservationMapper.map(o)) world.apply(key, w, seq, nanos, null); else world.observeSequence(key, seq, nanos);
            TimingObservationMapper.map(o, SELF, world.worldEpoch()).ifPresent(te -> timing.apply(key, te));
            combat.apply(o, nanos, SELF, world.worldEpoch(), null, false);
            if (withReach) reach.apply(o, combat, timing, context.view(), null, world, SELF);
        }
        void outbound(PacketPayload p, long dt) { apply(new Observation.Outbound(++wire, p), dt); }
        void inbound(PacketPayload p, long dt) { long a = ++attempt; apply(new Observation.Attempt(a, a, p), dt); }
        void clientTick(float yaw) {
            inbound(new PacketPayload.Movement(new PacketPayload.Position(0.5, 64, 0.5), new PacketPayload.Rotation(yaw, 0f), true, false), MS);
            inbound(new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0), 0);
        }
        void serverTick() { apply(new Observation.Tick(++ticks), TICK); }
        /** one combat-time marker: the clientbound PING and the client's PONG (20 per second per fighting player under D12-2) */
        void marker() { int id = ++pingId; outbound(new PacketPayload.Control(PacketPayload.ControlKind.PING, id), 0); inbound(new PacketPayload.Control(PacketPayload.ControlKind.PONG, id), 20 * MS); }
    }

    /** No-combat serverbound movement (+ its tick end) while {@code entities} are in view. */
    @State(Scope.Thread) public static class Idle extends Rig {
        @Param({ "0", "40", "256" }) public int entities; @Param({ "false", "true" }) public boolean withReach;
        @Setup public void setup() { build(entities, withReach); }
    }
    @Benchmark public long noCombatClientTick(Idle s) { s.clientTick(0f); return s.seq; }

    /** One clientbound relative move with {@code entities} in view, every entity moving once per tick. */
    @State(Scope.Thread) public static class Crowd extends Rig {
        @Param({ "40", "256" }) public int entities; @Param({ "false", "true" }) public boolean withReach; int next;
        @Setup public void setup() { build(entities, withReach); }
    }
    @Benchmark public long entityMove(Crowd s) { int i = s.next; s.next = (i + 1) % s.entities; s.outbound(CrossPhaseSupport.move(100 + i), i == 0 ? TICK : 0); return s.seq; }

    /** Projectile churn with 200 others in view: spawn, 3 moves, removal (the reach runner's lifetime extents see every one). */
    @State(Scope.Thread) public static class Churn extends Rig {
        @Param({ "false", "true" }) public boolean withReach; int nextId = 1_000_000;
        @Setup public void setup() { build(200, withReach); }
    }
    @Benchmark public long projectileChurn(Churn s) {
        int id = s.nextId++; s.outbound(CrossPhaseSupport.spawn(id, 0.5, 2.5), MS);
        for (int k = 0; k < 3; k++) s.outbound(CrossPhaseSupport.move(id), TICK);
        s.outbound(new SemanticPayload.RemoveEntities(List.of(id)), MS); return s.seq;
    }

    /** Combat-time marker cost on the shard: one PING observation plus its PONG, 40 entities in view. */
    @State(Scope.Thread) public static class Markers extends Rig {
        @Param({ "false", "true" }) public boolean withReach;
        @Setup public void setup() { build(40, withReach); }
    }
    @Benchmark public long markerPingPong(Markers s) { s.marker(); return s.seq; }

    /** A fighting client tick: ATTACK on a target in a 40-entity view, then the tick's movement and tick end (the decision runs at the tick end). */
    @State(Scope.Thread) public static class Fight extends Rig {
        @Param({ "false", "true" }) public boolean withReach; int target;
        @Setup public void setup() { build(40, withReach); }
    }
    @Benchmark public long attackTick(Fight s) { s.target = (s.target + 1) % 40; s.inbound(new SemanticPayload.Attack(100 + s.target), MS); s.clientTick(0f); s.marker(); return s.seq; } // a fighting tick carries one combat-time marker (D12-2)
    /** Autoclicker burst: 64 ATTACKs (the pending bound) inside one client tick, all decided at its tick end. Reported per burst. */
    @Benchmark public long attackBurst64(Fight s) { for (int k = 0; k < 64; k++) { s.target = (s.target + 1) % 40; s.inbound(new SemanticPayload.Attack(100 + s.target), 0); } s.clientTick(0f); s.serverTick(); return s.seq; }
}
