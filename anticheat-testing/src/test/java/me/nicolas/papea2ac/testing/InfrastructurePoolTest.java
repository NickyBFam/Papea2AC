package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;

/**
 * P3-F7 conformance: per-session ingress ceilings (4 MiB arena, 4 MiB bulk) compose into ONE engine-wide bound - the
 * PERFORMANCE.md "other queues/bulk/worker scratch/control 32 MiB" partition - so aggregate in-flight exposure is
 * sessions x 192 KiB guaranteed slice + the pool, never sessions x 8 MiB. Exhaustion is explicit (shed / accounted
 * drop), never blocking; every charge is returned on poll, drop and close.
 */
class InfrastructurePoolTest {
    static final SemanticPayload.JoinGame JOIN = ObserverWorldIntegrationTest.JOIN;
    static Observation.Event small() { return new Observation.Outbound(1, new PacketPayload.Velocity(9, new PacketPayload.Position(0, 1, 0))); } // ~330 B record
    static Observation.Event heavyChunk() {
        var sections = new ArrayList<SemanticPayload.ChunkSection>(); for (int s = 0; s < 24; s++) { final int k = s; sections.add(ObserverWorldIntegrationTest.wire(WorldTestSupport.section(idx -> (idx + k) % 7 == 0 ? WorldTestSupport.DIRT : (idx & 1) == 0 ? WorldTestSupport.STONE : WorldTestSupport.AIR))); }
        return new Observation.Outbound(1, new SemanticPayload.ChunkBaseline(0, 0, 60000, 0, 4, true, true, sections, ""));
    }
    static ConnectionIngress ingress(InfrastructurePool pool, List<ConnectionIngress.Loss> losses, HealthLatch latch, int n) {
        return new ConnectionIngress(new SessionKey(new UUID(77, n), 1), new PacketTruthTest.Loop(), latch, 4096, e -> Observation.Context.synthetic(), losses::add, pool);
    }

    @Test void poolReservesOnlyTheArenaAboveTheGuaranteedSliceAndAllBulk() {
        var pool = new InfrastructurePool(InfrastructurePool.DEFAULT_BYTES); var losses = new ArrayList<ConnectionIngress.Loss>(); var latch = new HealthLatch();
        var in = ingress(pool, losses, latch, 1);
        int cost = new Observation(new SessionKey(new UUID(77, 1), 1), 1, 0, 0, small()).chargedBytes();
        int fitting = ConnectionIngress.GUARANTEED_ARENA_BYTES / cost;
        for (int i = 0; i < fitting; i++) assertTrue(in.capture(small(), 0));
        assertEquals(0, pool.used(), "everything inside the guaranteed slice is the session's own tier reservation");
        assertTrue(in.capture(small(), 0));
        assertEquals((long) (fitting + 1) * cost - ConnectionIngress.GUARANTEED_ARENA_BYTES, pool.used(), "only the part above the slice is pooled");
        assertEquals(pool.used(), in.pooledArenaBytes());
        int bulk = heavyChunk().chargedBytes() > 0 ? ((Observation.Outbound) heavyChunk()).payload().bulkBytes() : 0; assertTrue(bulk > 40_000);
        long before = pool.used(); assertTrue(in.capture(heavyChunk(), 0));
        assertTrue(pool.used() >= before + bulk, "bulk is pooled in full"); assertEquals(bulk, in.bulkBytes());
        for (int i = 0; i < fitting + 2; i++) assertNotNull(in.poll());
        assertNull(in.poll()); assertEquals(0, pool.used(), "every charge returned on poll"); assertEquals(0, in.pooledArenaBytes()); assertEquals(0, in.bulkBytes()); assertEquals(0, in.retainedBytes());
        assertTrue(losses.isEmpty()); assertTrue(latch.get().healthy());
    }

    @Test void sessionsShareOneBoundAndExhaustionIsExplicitNeverBlocking() {
        var pool = new InfrastructurePool(1024 * 1024); // a small pool makes the aggregate bound observable
        var lossesA = new ArrayList<ConnectionIngress.Loss>(); var lossesB = new ArrayList<ConnectionIngress.Loss>(); var latchA = new HealthLatch(); var latchB = new HealthLatch();
        var a = ingress(pool, lossesA, latchA, 2); var b = ingress(pool, lossesB, latchB, 3);
        // bulk: A takes most of the pool, B's chunk content is SHED (record kept, no gap), never dropped
        int heavyBulk = ((Observation.Outbound) heavyChunk()).payload().bulkBytes();
        int n = 0; while (pool.used() + heavyBulk <= pool.limit() - heavyBulk) { assertTrue(a.capture(heavyChunk(), 0)); n++; }
        assertTrue(n >= 10); assertTrue(lossesA.isEmpty());
        long usedBefore = pool.used(); int accepted = 0; for (int i = 0; i < 20; i++) if (b.capture(heavyChunk(), 0)) accepted++;
        assertEquals(20, accepted, "records still flow"); assertTrue(lossesB.stream().anyMatch(l -> l == ConnectionIngress.Loss.BULK_SHED), "content shed at the shared bound");
        assertTrue(latchB.get().healthy(), "shedding is not a transport degrade"); assertTrue(pool.used() <= pool.limit());
        var shed = b.poll(); var baseline = (SemanticPayload.ChunkBaseline) ((Observation.Outbound) shed.event()).payload();
        assertEquals(1, shed.sequence(), "no sequence gap");
        // records: with the pool nearly full, arena bytes above B's guaranteed slice cannot be admitted -> accounted drop (INGRESS_FULL) + OVERLOADED latch, never a block
        int cost = new Observation(new SessionKey(new UUID(77, 3), 1), 1, 0, 0, small()).chargedBytes(); int fitting = ConnectionIngress.GUARANTEED_ARENA_BYTES / cost;
        for (int i = 0; i < fitting + 5; i++) b.capture(small(), 0);
        assertTrue(pool.used() <= pool.limit(), "the pool is a hard bound"); assertTrue(lossesB.contains(ConnectionIngress.Loss.INGRESS_FULL) || pool.used() < pool.limit());
        // release everything: the pool returns to zero
        while (a.poll() != null) {} while (b.poll() != null) {}
        assertEquals(0, pool.used()); assertEquals(0, a.retainedBytes() + b.retainedBytes());
        assertTrue(usedBefore > 0);
    }

    @Test void closingASessionReturnsEveryChargeAndTheArithmeticIsBounded() {
        var pool = new InfrastructurePool(InfrastructurePool.DEFAULT_BYTES); var losses = new ArrayList<ConnectionIngress.Loss>(); var loop = new PacketTruthTest.Loop();
        var in = new ConnectionIngress(new SessionKey(new UUID(77, 4), 1), loop, new HealthLatch(), 4096, e -> Observation.Context.synthetic(), losses::add, pool);
        for (int i = 0; i < 40; i++) assertTrue(in.capture(heavyChunk(), 0)); for (int i = 0; i < 2000; i++) assertTrue(in.capture(small(), 0));
        assertTrue(pool.used() > 1_000_000); assertTrue(in.retainedBytes() <= ConnectionIngress.INGRESS_ARENA_BYTES + ConnectionIngress.BULK_CEILING_BYTES + 65536, "per-session hard ceilings");
        in.close(); loop.drain(); assertTrue(in.releaseAfterClose());
        assertEquals(0, pool.used(), "close returns arena and bulk charges"); assertEquals(0, in.retainedBytes());
        assertThrows(IllegalArgumentException.class, () -> new InfrastructurePool(0)); assertThrows(IllegalStateException.class, () -> pool.release(1));
        assertFalse(pool.reserve(pool.limit() + 1)); assertTrue(pool.reserve(pool.limit())); assertFalse(pool.reserve(1)); pool.release(pool.limit()); assertEquals(0, pool.used());
    }
}
