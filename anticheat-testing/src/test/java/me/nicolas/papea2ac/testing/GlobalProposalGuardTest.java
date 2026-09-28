package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.GlobalProposalGuard;
import org.junit.jupiter.api.Test;

/** MITIGATION G2 proposal-time counting: the burst gate, the 20 % window, distinctness, the latch, the window roll and concurrency. */
class GlobalProposalGuardTest {
    static final long S = 1_000_000_000L, T0 = 1_000 * S;
    static UUID u(int i) { return new UUID(0x62L, i); }

    @Test void theThirdDistinctSessionWithinOneSecondTripsRegardlessOfN() {
        GlobalProposalGuard g = new GlobalProposalGuard(1024);
        assertTrue(g.propose(u(1), 1000, T0)); assertTrue(g.propose(u(2), 1000, T0 + S / 2));
        assertFalse(g.propose(u(3), 1000, T0 + 9 * S / 10), "3 distinct sessions in 1 s"); assertTrue(g.tripped());
        GlobalProposalGuard spaced = new GlobalProposalGuard(1024);
        for (int i = 0; i < 6; i++) assertTrue(spaced.propose(u(i), 1000, T0 + i * 1_100_000_000L), "never 3 within 1 s: " + spaced.describe());
    }
    @Test void theWindowTripsAtTwentyPercentOfTheEligibleSessionsWithAFloorOfThree() {
        GlobalProposalGuard g = new GlobalProposalGuard(1024); // N=32: ceil(6.4)=7
        for (int i = 0; i < 6; i++) assertTrue(g.propose(u(i), 32, T0 + i * 1_500_000_000L));
        assertFalse(g.propose(u(6), 32, T0 + 9_500_000_000L), "7th distinct session in 10 s"); assertTrue(g.tripped());
        GlobalProposalGuard small = new GlobalProposalGuard(1024); // N=5: floor 3
        assertTrue(small.propose(u(1), 5, T0)); assertTrue(small.propose(u(2), 5, T0 + 2 * S)); assertFalse(small.propose(u(3), 5, T0 + 4 * S));
    }
    @Test void oneSessionProposingRepeatedlyIsOneDistinctSession() {
        GlobalProposalGuard g = new GlobalProposalGuard(1024);
        for (int i = 0; i < 1000; i++) assertTrue(g.propose(u(7), 3, T0 + i * S / 100));
        assertFalse(g.tripped());
    }
    @Test void theWindowRollsAfterTenSecondsButATripIsLatchedUntilReset() {
        GlobalProposalGuard g = new GlobalProposalGuard(1024);
        assertTrue(g.propose(u(1), 5, T0)); assertTrue(g.propose(u(2), 5, T0 + 2 * S));
        assertTrue(g.propose(u(3), 5, T0 + 10 * S + 1), "a new window starts counting afresh");
        assertTrue(g.propose(u(4), 5, T0 + 12 * S + 1)); assertFalse(g.propose(u(5), 5, T0 + 14 * S + 1)); assertTrue(g.tripped());
        assertFalse(g.propose(u(1), 5, T0 + 100 * S), "latched: no automatic timer re-enable");
        g.reset(); assertFalse(g.tripped()); assertTrue(g.propose(u(1), 5, T0 + 101 * S));
    }
    @Test void theDistinctSessionWindowIsBoundedByCapacity() {
        GlobalProposalGuard g = new GlobalProposalGuard(2);
        assertTrue(g.propose(u(1), 2000, T0)); assertTrue(g.propose(u(2), 2000, T0 + 2 * S));
        assertFalse(g.propose(u(3), 2000, T0 + 4 * S), "a third distinct session cannot be tracked: restrict, never forget"); assertTrue(g.tripped());
    }
    @Test void concurrentProposalsAreCountedExactly() throws Exception {
        GlobalProposalGuard g = new GlobalProposalGuard(1024); ExecutorService pool = Executors.newFixedThreadPool(8); List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) { int k = i; fs.add(pool.submit(() -> g.propose(u(k), 1000, T0))); }
        int admitted = 0; for (Future<Boolean> f : fs) if (f.get()) admitted++;
        pool.shutdown();
        assertEquals(2, admitted, "exactly two of eight simultaneous distinct sessions pass the burst gate"); assertTrue(g.tripped());
    }
}
