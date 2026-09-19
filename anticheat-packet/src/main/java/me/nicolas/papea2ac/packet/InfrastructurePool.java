package me.nicolas.papea2ac.packet;
import java.util.concurrent.atomic.AtomicLong;
/**
 * PERFORMANCE.md reference partition G: "other queues/bulk/worker scratch/control 32 MiB ... partitions, not extra
 * memory outside B". One bounded, engine-wide pool that every session's in-flight ingress bytes ABOVE its guaranteed
 * tier slice ({@link ConnectionIngress#GUARANTEED_ARENA_BYTES}) and ALL bulk payload content are reserved from before
 * they are held. It is what makes the per-session ceilings (4 MiB arena, 4 MiB bulk) compose into one global bound:
 * worst-case aggregate in-flight exposure = sessions x guaranteed slice (inside each session's tier reservation)
 * + this pool, never sessions x 8 MiB. Exhaustion is explicit and never blocks: bulk is shed (record kept), a record
 * that cannot be admitted is an accounted drop (INGRESS_FULL -> latch + sequence gap). P3-F7 conformance.
 */
public final class InfrastructurePool {
    public static final long DEFAULT_BYTES = 32L * 1024 * 1024;
    private final long limit;
    private final AtomicLong used = new AtomicLong();
    public InfrastructurePool(long limitBytes) {
        if (limitBytes < 1 || limitBytes > (1L << 40)) throw new IllegalArgumentException("pool limit");
        this.limit = limitBytes;
    }
    /** Non-blocking reservation; false leaves the pool untouched. */
    public boolean reserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        if (bytes == 0) return true;
        long current;
        do { current = used.get(); if (current > limit - bytes) return false; } while (!used.compareAndSet(current, current + bytes));
        return true;
    }
    public void release(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        long after = used.addAndGet(-bytes);
        if (after < 0) { used.addAndGet(bytes); throw new IllegalStateException("infrastructure pool over-released"); }
    }
    public long used() { return used.get(); }
    public long limit() { return limit; }
}
