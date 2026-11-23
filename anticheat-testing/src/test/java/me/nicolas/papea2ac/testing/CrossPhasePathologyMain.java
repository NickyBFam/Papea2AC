package me.nicolas.papea2ac.testing;
import java.util.*;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Thread 8 (cross-phase performance) pathology and boundedness harness for the Phase 11 entity history inside the observer world's private tier.
 * Descriptive, one host, wall-clock medians over repeated runs; never a capacity claim. Prints one JSON object.
 *
 * <ol>
 * <li>tierWorldFirst: the world private tier is filled by ordinary block updates first, then 256 entities are spawned: are spawns refused while
 *     chunks remain evictable?</li>
 * <li>tierEntitiesFirst: the reverse order: 256 entities with full deep histories, then the same world traffic.</li>
 * <li>shedCost: cost of world block updates that must make tier room, with and without a full deep entity registry attached.</li>
 * <li>churnBound: long spawn/move/remove/id-reuse/epoch churn; retained heap and structure sizes must stay flat.</li>
 * </ol>
 */
public final class CrossPhasePathologyMain {
    private CrossPhasePathologyMain() {}
    static final BlockStateKey STAIRS = ObserverWorldMemoryMain.STAIRS, DIRT = ObserverWorldMemoryMain.DIRT;

    static final class Rig {
        final SessionKey key = new SessionKey(new UUID(0x7E8FL, System.nanoTime()), 1);
        final WorldBudget budget = new WorldBudget(); final ObserverWorld world = new ObserverWorld(key, budget, new BlockStateDictionary());
        static final PacketPayload IDLE = new PacketPayload.Movement(new PacketPayload.Position(0.5, 64, 0.5), new PacketPayload.Rotation(0f, 0f), true, false);
        final CombatRunner runner = new CombatRunner(key, CombatProfile.PAPER_26_2, world, new PlayerTimeline(key), () -> null, () -> null, new CombatEngine.Metrics());
        long seq, nanos = 1_000_000_000L; int radius;
        Rig(int radius) {
            this.radius = radius;
            world(ObserverWorldMemoryMain.OVERWORLD, 0); world(new WorldObservation.ViewCenter(0, 0), 0);
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) world(ObserverWorldMemoryMain.terrain(x, z, (x * 31 + z) * 7919), 0);
        }
        void world(WorldObservation o, long dt) { seq++; nanos += dt; world.apply(key, o, seq, nanos, null); runnerSilent(); }
        /** keeps the runner's observed-through bound in step with the world (as PacketEngine does for every observation) */
        void runnerSilent() { runner.apply(new Observation(key, seq, nanos, 0, new Observation.Attempt(seq, seq, IDLE)), nanos, 1, world.worldEpoch(), null, false); }
        void outbound(PacketPayload p, long dt) { seq++; nanos += dt; world.observeSequence(key, seq, nanos); runner.apply(new Observation(key, seq, nanos, 0, new Observation.Outbound(seq, p)), nanos, 1, world.worldEpoch(), null, false); }
        void updates(int count, Random r) {
            for (int u = 0; u < count; u++) {
                int cx = r.nextInt(2 * radius + 1) - radius, cz = r.nextInt(2 * radius + 1) - radius;
                world(new WorldObservation.BlockChanged(new BlockPos((cx << 4) + r.nextInt(16), 60 + r.nextInt(30), (cz << 4) + r.nextInt(16)), (u & 1) == 0 ? STAIRS : DIRT), 1_000_000L);
            }
        }
        void crowd(int n, int ticks) {
            for (int i = 0; i < n; i++) outbound(CrossPhaseSupport.spawn(100 + i, i % 16, i / 16), 0);
            for (int t = 0; t < ticks; t++) for (int i = 0; i < n; i++) outbound(CrossPhaseSupport.move(100 + i), i == 0 ? 50_000_000L : 0);
        }
        String state() {
            world.publishNow(); var e = runner.entities().status(); var w = world.snapshot();
            return "{\"privateBytes\": " + world.privateBytes() + ", \"historyBytes\": " + w.historyBytes() + ", \"entityBytes\": " + e.bytes() + ", \"tierLimit\": " + WorldBudget.PRIVATE_FLOOR_BYTES
                + ", \"live\": " + e.live() + ", \"deep\": " + e.deep() + ", \"capacityRefusals\": " + e.capacityRefusals() + ", \"capacityOverflowSeq\": " + e.capacityOverflowSequence()
                + ", \"entityHistoryEvictions\": " + e.historyEvictions() + ", \"demotions\": " + e.demotions() + ", \"worldChunks\": " + w.chunkCount() + ", \"evictedChunks\": " + budget.snapshot().evictedChunks() + "}";
        }
    }

    static long median(long[] v) { long[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }
    static void settle() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }
    static long used() { var rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }

    public static void main(String[] args) {
        var out = new StringBuilder("{\n");
        // 1 + 2: tier interaction, view 11x11 with ordinary block-update traffic (the Phase 3 mixedUpdatesHistory121 shape)
        { var r = new Rig(5); r.updates(3000, new Random(1)); String before = r.state(); r.crowd(256, 60);
          out.append("  \"tierWorldFirst\": {\"afterWorld\": ").append(before).append(", \"afterEntities\": ").append(r.state()).append("},\n"); }
        { var r = new Rig(5); r.crowd(256, 60); String before = r.state(); r.updates(3000, new Random(1));
          out.append("  \"tierEntitiesFirst\": {\"afterEntities\": ").append(before).append(", \"afterWorld\": ").append(r.state()).append("},\n"); }
        // 3: block-update cost under tier pressure, with and without a full deep entity registry sharing the tier
        for (int withEntities = 0; withEntities < 2; withEntities++) {
            long[] perOp = new long[7];
            for (int trial = 0; trial < perOp.length; trial++) {
                var r = new Rig(5); if (withEntities == 1) r.crowd(256, 60); r.updates(3000, new Random(2));
                var rnd = new Random(3); int n = 20_000; long t0 = System.nanoTime(); r.updates(n, rnd); perOp[trial] = (System.nanoTime() - t0) / n;
            }
            out.append("  \"shedCost").append(withEntities == 1 ? "WithFullEntityRegistry" : "WorldOnly").append("\": {\"blockUpdateNanosPerOpMedian\": ").append(median(perOp)).append(", \"trials\": ").append(Arrays.toString(perOp)).append("},\n");
        }
        // 4: long churn; ids reused from a small pool, periodic epoch changes; heap and sizes sampled
        { var r = new Rig(0); var rnd = new Random(4); long epoch = 0; List<String> samples = new ArrayList<>(); long[] heap = new long[6];
          for (int round = 0; round < 6; round++) {
              for (int op = 0; op < 400_000; op++) {
                  int id = 100 + rnd.nextInt(600);
                  switch (rnd.nextInt(6)) {
                      case 0 -> r.outbound(CrossPhaseSupport.spawn(id, rnd.nextInt(32), rnd.nextInt(32)), 1_000_000L);
                      case 1 -> r.outbound(new SemanticPayload.RemoveEntities(List.of(id)), 1_000_000L);
                      default -> r.outbound(CrossPhaseSupport.move(id), 2_000_000L);
                  }
                  if (op % 20 == 0) r.runnerSilent();
                  if (op == 399_999 && round % 2 == 1) { epoch++; r.world(new WorldObservation.DimensionEntered(epoch % 2 == 0 ? "minecraft:overworld" : "minecraft:the_nether", -64, 384, false), 0); }
              }
              settle(); heap[round] = used(); samples.add(r.state());
          }
          out.append("  \"churnBound\": {\"observationsPerRound\": 400000, \"retainedHeapBytesPerRound\": ").append(Arrays.toString(heap)).append(", \"states\": ").append(samples).append("}\n");
          if (r.runner.closed()) throw new AssertionError(); }
        out.append("}");
        System.out.println(out);
    }
}
