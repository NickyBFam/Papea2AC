package me.nicolas.papea2ac.model;
import java.util.concurrent.atomic.AtomicReference;
/** Transport infrastructure, never mutable gameplay state. Sticky until a new session. */
public final class HealthLatch {
    public enum Reason { HEALTHY, OVERLOADED, LOST_SERVER_OBSERVATION, MALFORMED,
        PIPELINE_UNCOVERED, OUTCOME_UNKNOWN, EXPIRED, CANCELLED, TRANSFORMED,
        SHARD_FAILED, CLOSED, WORK_QUOTA, INTERNAL_FAILURE }
    public record State(long generation, Reason reason, long firstMissingSeq) {
        public boolean healthy() { return reason == Reason.HEALTHY; }
    }
    private final AtomicReference<State> state;
    public HealthLatch() { this(0); }
    /** Seeds a fresh latch at a non-zero generation baseline, for resuming replay from a mid-session checkpoint. */
    public HealthLatch(long initialGeneration) {
        if (initialGeneration < 0) throw new IllegalArgumentException("generation");
        state = new AtomicReference<>(new State(initialGeneration, Reason.HEALTHY, 0));
    }
    public State get() { return state.get(); }
    /** Linearization point for a generation-bound transition. A later barrier invalidates its use. */
    public boolean authorize(long expectedGeneration) {
        State current = state.get();
        return current.healthy() && current.generation() == expectedGeneration
            && state.compareAndSet(current, current);
    }
    public void invalidate(Reason reason, long sequence) {
        if (reason == Reason.HEALTHY) throw new IllegalArgumentException("cannot heal");
        state.updateAndGet(s -> s.healthy() ? new State(s.generation()+1, reason, sequence) : s);
    }
}
