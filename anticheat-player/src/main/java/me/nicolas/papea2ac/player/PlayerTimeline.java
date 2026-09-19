package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.List;
import java.util.Objects;

/**
 * Actor-owned (single-writer-asserted, mirroring anticheat-core's TruthActor) Player Timeline: current
 * PlayerState plus a bounded, immutable-record history. One instance per Session; a reconnect gets a brand
 * new instance from a brand new SessionKey, so old mutable history is never reused across sessions
 * (session-replacement isolation is structural, not a runtime flag).
 *
 * Bound: PLAYER_STATE.md's Timeline row - 4096 records, 512 KiB floor or 1 MiB target, three-second target,
 * whichever binds first - enforced by reusing anticheat-player's own existing ObservationRing primitive
 * (its hardcoded ceilings of 4096/1_048_576/3_000_000_000ns already match this row exactly).
 *
 * Threading: append()/tick()/history-shaped queries (stateAtSequence, stateBefore, recentStates,
 * snapshotRange, coverageAt) are owner-thread-only (assertOwner(), same discipline as TruthActor), because
 * the backing ring is a plain, non-thread-safe structure - deep historical queries are for later-phase
 * consumers running on the SAME actor/shard (THREADING_MODEL.md), not arbitrary threads. currentState() and
 * snapshot() are safe from any thread: they read a published immutable Snapshot, never the ring itself.
 */
public final class PlayerTimeline {
    public static final int FLOOR_BYTES = 512 * 1024;
    public static final int TARGET_BYTES = 1024 * 1024;
    public static final long HORIZON_NANOS = 3_000_000_000L;

    public record Snapshot(PlayerState state, long lastCommittedSequence, int recordCount, int recordBytes, long evictions, boolean lastAppendHadCoverageIssue) {}

    private final SessionKey key;
    private final ObservationRing<TimelineRecord> ring;
    private Thread owner;
    private PlayerState current = PlayerState.INITIAL;
    private long lastCommittedSequence;
    /** The very first captureSequence ever appended to THIS instance, retained even after that record itself is evicted from the ring - the only way to tell "never sent to this timeline" (PreCapture) apart from "sent, then aged out" (Evicted). -1 means nothing has ever been appended. */
    private long firstEverAppendedSequence = -1;
    private boolean closed;
    private volatile Snapshot snapshot;

    public PlayerTimeline(SessionKey key) { this(key, FLOOR_BYTES); }

    /** byteBound must be one of the two PERFORMANCE.md tiers: the CORE_OBSERVATION floor or the QUALIFICATION target - never an arbitrary value in between chosen ad hoc. */
    public PlayerTimeline(SessionKey key, int byteBound) {
        this.key = Objects.requireNonNull(key);
        if (byteBound != FLOOR_BYTES && byteBound != TARGET_BYTES) throw new IllegalArgumentException("byteBound must be the floor or target tier");
        this.ring = new ObservationRing<>(4096, byteBound, HORIZON_NANOS, TimelineRecord::chargedBytes);
        publish(false);
    }

    private void assertOwner() {
        if (owner == null) owner = Thread.currentThread();
        else if (owner != Thread.currentThread()) throw new IllegalStateException("timeline ownership");
    }

    /** Append with Phase 1 transport health known to be HEALTHY at this instant. */
    public boolean append(SessionKey observedFor, PlayerObservation observation, long captureSequence, long monotonicNanos) {
        return append(observedFor, observation, captureSequence, monotonicNanos, null);
    }

    /**
     * Returns false for a stale-session or non-monotonic (duplicate/out-of-order) observation, which is a no-op,
     * never a partial mutation. transportDegradeReason is Phase 1's sticky HealthLatch reason if the session is
     * currently unhealthy (null when healthy): because that latch never heals within a session, EVERY record
     * appended while it is set carries the fact as an uncertainty reason - a session that was already degraded
     * before its first observation (e.g. pre-login truncation) is therefore never mistaken for a certain one, even
     * though no generation bump happens on any individual append.
     */
    public boolean append(SessionKey observedFor, PlayerObservation observation, long captureSequence, long monotonicNanos, String transportDegradeReason) {
        assertOwner();
        Objects.requireNonNull(observation);
        if (!key.equals(observedFor)) return false;
        if (closed) return false;
        if (captureSequence < 1 || captureSequence <= lastCommittedSequence) return false;
        var transition = PlayerStateReducer.apply(current, observation, captureSequence);
        current = transition.next();
        lastCommittedSequence = captureSequence;
        if (firstEverAppendedSequence < 0) firstEverAppendedSequence = captureSequence;
        var record = commit(transition, captureSequence, monotonicNanos, observation, transportDegradeReason == null ? "" : transportDegradeReason);
        ring.append(record, monotonicNanos);
        if (observation instanceof PlayerObservation.SessionClosed) closed = true;
        publish(!record.certain());
        return true;
    }

    /** The single record-construction path shared by live append and TimelineReplayMapper.replay(), so a replayed
     * record is built by exactly the same code as the original - including the transport-reason merge. */
    static TimelineRecord commit(PlayerStateReducer.Transition transition, long captureSequence, long monotonicNanos,
                                 PlayerObservation observation, String transportDegradeReason) {
        var reasons = transition.uncertaintyReasons();
        if (!transportDegradeReason.isEmpty() && reasons.size() < TimelineRecord.MAX_UNCERTAINTY_REASONS) {
            var withTransport = new java.util.ArrayList<>(reasons);
            withTransport.add("TRANSPORT_" + transportDegradeReason);
            reasons = List.copyOf(withTransport);
        }
        var next = transition.next();
        return new TimelineRecord(captureSequence, transition.kind(), monotonicNanos,
                next.tick().hasServerTick(), next.tick().lastServerTick(), next.worldEpoch(), next, reasons, observation, transportDegradeReason);
    }

    /** Ages out records past the three-second horizon even without a new observation, so a stalled/idle session's history reflects real elapsed time, not just event count. */
    public void expire(long nowNanos) { assertOwner(); ring.expire(nowNanos); publish(snapshot.lastAppendHadCoverageIssue()); }

    private void publish(boolean coverageIssue) {
        snapshot = new Snapshot(current, lastCommittedSequence, ring.size(), ring.bytes(), ring.evictions(), coverageIssue);
    }

    /** Safe from any thread: reads the last published immutable snapshot, never the ring. */
    public Snapshot snapshot() { return snapshot; }
    public PlayerState currentState() { return snapshot.state(); }

    public TimelineLookup stateAtSequence(long sequence) {
        assertOwner();
        if (sequence < 1 || sequence > lastCommittedSequence) return new TimelineLookup.NeverExisted(sequence);
        if (sequence < firstEverAppendedSequence) return new TimelineLookup.PreCapture(sequence);
        var records = ring.snapshot();
        int index = binarySearch(records, sequence);
        if (index < 0) return new TimelineLookup.Evicted(sequence);
        return new TimelineLookup.Found(records.get(index));
    }

    /** Most recent record strictly before the given sequence, refusing to silently cross a world-epoch boundary (respawn/dimension change) that lies in between. */
    public TimelineLookup stateBefore(long sequence) {
        assertOwner();
        var records = ring.snapshot();
        int index = -1;
        for (int i = records.size() - 1; i >= 0; i--) if (records.get(i).captureSequence() < sequence) { index = i; break; }
        if (index < 0) return firstEverAppendedSequence < 0 || sequence <= firstEverAppendedSequence
                ? new TimelineLookup.PreCapture(sequence) : new TimelineLookup.Evicted(sequence);
        var before = records.get(index);
        long referenceEpoch = current.worldEpoch();
        if (before.worldEpoch() != referenceEpoch) return new TimelineLookup.WorldEpochCrossing(sequence, before.worldEpoch(), referenceEpoch);
        return new TimelineLookup.Found(before);
    }

    /** Bounded by the ring's own retained size; never allocates beyond what is actually retained. */
    public List<TimelineRecord> recentStates(int limit) {
        assertOwner();
        if (limit < 1) throw new IllegalArgumentException("limit");
        var records = ring.snapshot();
        int from = Math.max(0, records.size() - limit);
        return records.subList(from, records.size());
    }

    /** Closed range [fromSequence, toSequence], capped to what the ring actually retains - a caller cannot force an unbounded materialization by passing a huge range. */
    public List<TimelineRecord> snapshotRange(long fromSequence, long toSequence) {
        assertOwner();
        if (fromSequence > toSequence) throw new IllegalArgumentException("range");
        var records = ring.snapshot();
        int lo = 0; while (lo < records.size() && records.get(lo).captureSequence() < fromSequence) lo++;
        int hi = lo; while (hi < records.size() && records.get(hi).captureSequence() <= toSequence) hi++;
        return records.subList(lo, hi);
    }

    public TimelineLookup coverageAt(long sequence) { return stateAtSequence(sequence); }

    /** Every record this ring currently retains, oldest first - already bounded by the ring's own cap, never a second unbounded copy. For replay export (TimelineReplayMapper). */
    public List<TimelineRecord> allRecords() { assertOwner(); return ring.snapshot(); }

    private static int binarySearch(List<TimelineRecord> records, long sequence) {
        int lo = 0, hi = records.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long midSeq = records.get(mid).captureSequence();
            if (midSeq == sequence) return mid;
            if (midSeq < sequence) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }
}
