package me.nicolas.papea2ac.player;
import java.util.List;
import java.util.Objects;
/**
 * One committed Timeline entry. captureSequence is the sole deterministic ordering key (PLAYER_TIMELINE.md
 * Decision L1); serverTick/monotonicNanos are kept as separate, never-conflated diagnostic fields, never a
 * reorder key. resultingState is the full PlayerState immediately after this observation was applied,
 * relying on PlayerState's structural sharing to keep the marginal cost of one record small. source is the
 * original causal PlayerObservation that produced this record - kept so replay can re-run the same
 * deterministic reducer against restored inputs (REPLAY_SYSTEM.md Decision R1), the same pattern
 * protocol.TransportReplayMapper already uses for Phase 1 (it exports Observations, not TruthActor's derived
 * Snapshot).
 *
 * This is a deliberately scoped subset of PLAYER_TIMELINE.md's full eventual TimelineRecord shape (which also
 * anticipates combat/ACK/wire-ordinal/causal-parent fields owned by later phases): Phase 2 records only what
 * MovementState/RotationState/TeleportState/VelocityState/TickState/discontinuity/coverage actually produce.
 */
public record TimelineRecord(long captureSequence, Kind kind, long monotonicNanos, boolean hasServerTick,
                              long serverTick, long worldEpoch, PlayerState resultingState, List<String> uncertaintyReasons,
                              PlayerObservation source, String transportDegradeReason) {
    public static final int MAX_UNCERTAINTY_REASONS = 4;
    /** transportDegradeReason: Phase 1's sticky HealthLatch reason at append time ("" when healthy). Kept explicitly, not parsed back out of uncertaintyReasons, so replay reproduces it exactly. */
    public TimelineRecord {
        Objects.requireNonNull(kind); Objects.requireNonNull(resultingState); Objects.requireNonNull(uncertaintyReasons);
        Objects.requireNonNull(source); Objects.requireNonNull(transportDegradeReason);
        if (transportDegradeReason.length() > 32) throw new IllegalArgumentException("transport reason");
        if (captureSequence < 1) throw new IllegalArgumentException("captureSequence");
        if (worldEpoch < 0) throw new IllegalArgumentException("worldEpoch");
        if (uncertaintyReasons.size() > MAX_UNCERTAINTY_REASONS) throw new IllegalArgumentException("uncertainty reasons bound");
        uncertaintyReasons = List.copyOf(uncertaintyReasons);
    }
    public enum Kind { MOVEMENT, MOVEMENT_OUTCOME, TELEPORT_SENT, TELEPORT_ACKNOWLEDGED, EXTERNAL_MOTION, DISCONTINUITY, TICK, COVERAGE_GAP, SESSION_CLOSED }
    public boolean certain() { return uncertaintyReasons.isEmpty(); }
    /** Conservative fixed per-record estimate: resultingState is mostly shared references (structural sharing), so its marginal retained cost is not the size of a fully materialized PlayerState. An estimate, not a measurement - see PERFORMANCE.md's own methodology note. */
    public static int chargedBytes(TimelineRecord record) { return 112 + 24 * record.uncertaintyReasons.size() + record.source.chargedBytes(); }
}
