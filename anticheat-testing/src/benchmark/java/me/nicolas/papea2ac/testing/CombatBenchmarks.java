package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.player.TimelineLookup;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Phase 11 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc). Descriptive, one host, never a capacity claim.
 *
 * <p>ENTITY kernels (session shard, per clientbound entity packet): relative move of a deep-history entity; absolute teleport; spawn + removal
 * (one pair per op: a spawn alone cannot run at steady state without filling the bounded registry); id reuse (a spawn over a live id, which ends
 * the old incarnation); a move in a FULL bounded registry (256 summaries, 32 x 96-revision rings). LOOKUP kernels: current and historical target
 * lookup (40-entity registry). ATTACK kernels (per serverbound ATTACK): context construction with a known and with an unknown target. MIXED: 8
 * moves + 1 attack + 1 lookup, with a spawn/remove every 16th op. NO-COMBAT path through the core CombatRunner: a serverbound movement
 * observation, and a server tick with 40 tracked entities (amortized: every 20th tick refreshes the entity snapshot, P11-F8); the nearest-32
 * deep-slot rebalance of a full registry (run once per second).
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class CombatBenchmarks {
    static final CombatProfile P = CombatProfile.PAPER_26_2;
    static final SessionKey KEY = new SessionKey(new UUID(0xC0B7L, 0xBE7CL), 1);
    static final long TICK = 50_000_000L, MS = 1_000_000L;

    /** A standalone history + engine with an independent sequence / time cursor. */
    static final class F {
        final EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20); final CombatEngine e = new CombatEngine(KEY, P, new CombatEngine.Metrics()); long seq, nanos = 1_000_000_000L;
        void apply(EntityObservation o, long dt) { seq++; nanos += dt; h.apply(KEY, o, seq, 0, nanos); }
        void spawn(int id, double x) { apply(new EntityObservation.Spawned(id, new UUID(id, id), "minecraft:zombie", x, 64, 0, 0f, 0f, 0f), MS); }
        AttackContext attack(int id) { seq++; nanos += MS; h.observeSequence(KEY, seq, nanos); return e.onAttack(new CombatObservation.Attack(seq, seq, seq, id, nanos), h, new TimelineLookup.NeverExisted(seq), null, null); }
        static F registry(int n) { F f = new F(); for (int i = 0; i < n; i++) f.spawn(100 + i, i); for (int t = 0; t < 60; t++) for (int i = 0; i < n; i++) f.apply(move(100 + i), i == 0 ? TICK : 0); return f; }
    }
    static EntityObservation.Moved move(int id) { return new EntityObservation.Moved(id, (short) 37, (short) 0, (short) -11, true, 12f, 3f, true); }

    private F moveF, teleportF, cycleF, reuseF, fullF, lookupF, knownF, unknownF, mixedF;
    private int moveId, cycleId, reuseId, fullId, knownId, mixedOp;
    private long historicalSeq;
    private CombatRunner runner; private long runnerSeq, runnerNanos = 1_000_000_000L, serverTick; private PacketPayload movement;

    @Setup public void setup() {
        moveF = F.registry(40); teleportF = F.registry(40); cycleF = F.registry(40); reuseF = F.registry(40); lookupF = F.registry(40); knownF = F.registry(40); unknownF = F.registry(40); mixedF = F.registry(40);
        fullF = new F(); for (int i = 0; i < EntityHistory.MAX_RETAINED; i++) fullF.spawn(10 + i, i);
        for (int r = 0; r < EntityHistory.MAX_REVISIONS; r++) for (int i = 0; i < EntityHistory.MAX_DEEP; i++) fullF.apply(move(10 + i), MS / 8);
        historicalSeq = lookupF.seq - 400;
        check(lookupF.h.lookupId(KEY, 0, 105, lookupF.seq + 1).found(), "current lookup FOUND");
        check(lookupF.h.lookupId(KEY, 0, 105, historicalSeq).found(), "historical lookup FOUND (inside the ring)");
        check(knownF.attack(105).target().found(), "known-target attack"); check(unknownF.attack(9999).target().status() == EntityLookup.Status.UNKNOWN, "unknown-target attack");
        check(fullF.h.status().live() == EntityHistory.MAX_RETAINED && fullF.h.status().deep() == EntityHistory.MAX_DEEP, "full bounded registry");
        var w = new ObserverWorld(KEY, new WorldBudget(), new BlockStateDictionary()); var t = new PlayerTimeline(KEY);
        runner = new CombatRunner(KEY, P, w, t, () -> null, () -> null, new CombatEngine.Metrics());
        for (int i = 0; i < 40; i++) outbound(new SemanticPayload.EntitySpawn(100 + i, new UUID(1, i), "minecraft:zombie", new PacketPayload.Position(i, 64, 0), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), 0f, 0));
        movement = new PacketPayload.Movement(new PacketPayload.Position(1.5, 64, 2.5), new PacketPayload.Rotation(90f, 10f), true, false);
        check(runner.entities().status().live() == 40, "runner tracks 40 entities");
    }
    static void check(boolean ok, String what) { if (!ok) throw new IllegalStateException("benchmark precondition: " + what); }
    private void outbound(PacketPayload p) { runnerSeq++; runnerNanos += MS; runner.apply(new Observation(KEY, runnerSeq, runnerNanos, 0, new Observation.Outbound(runnerSeq, p)), runnerNanos, 1, 0, null, false); }

    @Benchmark public long relativeMoveUpdate() { moveF.apply(move(100 + (moveId = (moveId + 1) & 31)), MS); return moveF.seq; }
    @Benchmark public long teleportUpdate() { teleportF.apply(new EntityObservation.Teleported(100 + (moveId = (moveId + 1) & 31), moveId, 70, -4, 0f, 0f, 0), MS); return teleportF.seq; }
    @Benchmark public long spawnThenDespawn() { int id = 5000 + (cycleId = (cycleId + 1) & 63); cycleF.spawn(id, 1); cycleF.apply(new EntityObservation.Removed(List.of(id)), MS); return cycleF.seq; }
    @Benchmark public long idReuseSpawnOverLiveId() { reuseF.spawn(100 + (reuseId = (reuseId + 1) % 40), 3); return reuseF.seq; }
    @Benchmark public long moveInFullBoundedRegistry() { fullF.apply(move(10 + (fullId = (fullId + 1) & 31)), MS / 8); return fullF.seq; }
    private double origin;
    @Benchmark public long deepSlotRebalanceFullRegistry() { fullF.h.rebalance(origin = (origin + 17) % 256, 64, 0); return fullF.h.status().promotions(); }
    @Benchmark public EntityLookup currentTargetLookup() { return lookupF.h.lookupId(KEY, 0, 105, lookupF.seq + 1); }
    @Benchmark public EntityLookup historicalTargetLookup() { return lookupF.h.lookupId(KEY, 0, 105, historicalSeq); }
    @Benchmark public AttackContext attackIngestKnownTarget() { return knownF.attack(100 + (knownId = (knownId + 1) % 40)); }
    @Benchmark public AttackContext attackIngestUnknownTarget() { return unknownF.attack(9999); }
    @Benchmark public Object mixedCombatStream() {
        int op = mixedOp++; for (int i = 0; i < 8; i++) mixedF.apply(move(100 + ((op * 8 + i) % 40)), i == 0 ? TICK / 4 : 0);
        if ((op & 15) == 0) { mixedF.spawn(7000, 0); mixedF.apply(new EntityObservation.Removed(List.of(7000)), 0); }
        AttackContext c = mixedF.attack(100 + op % 40); return mixedF.h.lookupId(KEY, 0, 100 + (op + 7) % 40, mixedF.seq + 1).found() ? c : null;
    }
    @Benchmark public long noCombatMovementObservation() { runnerSeq++; runnerNanos += MS; runner.apply(new Observation(KEY, runnerSeq, runnerNanos, 0, new Observation.Attempt(runnerSeq, runnerSeq, movement)), runnerNanos, 1, 0, null, false); return runnerSeq; }
    @Benchmark public long noCombatServerTickWith40Entities() { runnerSeq++; runnerNanos += MS; runner.apply(new Observation(KEY, runnerSeq, runnerNanos, 0, new Observation.Tick(++serverTick)), runnerNanos, 1, 0, null, false); return runnerSeq; }
}
