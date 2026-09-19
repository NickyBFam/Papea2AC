package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.schema.TimelineCapsule;
import java.util.List;
import java.util.Optional;

/**
 * anticheat-player's local ReplayMapper (REPLAY_SYSTEM.md Decision R2): domain PlayerObservation <->
 * replay-schema TimelineCapsule. Exports the ORIGINAL causal observation from each retained TimelineRecord,
 * never the derived PlayerState/resultingState - so restore() + PlayerStateReducer.apply() reproduces the
 * exact same TimelineRecord deterministically, proving genuine replay rather than opaque snapshot storage.
 * No live PlayerTimeline/PlayerState object, no Paper/NMS type, ever crosses into the DTO.
 */
public final class TimelineReplayMapper {
    private TimelineReplayMapper() {}

    public static TimelineCapsule.Observation export(PlayerObservation observation) {
        return switch (observation) {
            case PlayerObservation.MovementClaim m -> new TimelineCapsule.Movement(
                    m.position().isPresent(), m.position().map(Vector3::x).orElse(0.0), m.position().map(Vector3::y).orElse(0.0), m.position().map(Vector3::z).orElse(0.0),
                    m.rotation().isPresent(), m.rotation().map(Rotation::yaw).orElse(0f), m.rotation().map(Rotation::pitch).orElse(0f),
                    m.onGround(), m.horizontalCollision());
            case PlayerObservation.MovementOutcome o -> new TimelineCapsule.Outcome(o.result().name(), o.finalPosition().x(), o.finalPosition().y(), o.finalPosition().z());
            case PlayerObservation.TeleportSent t -> new TimelineCapsule.TeleportSent(t.teleportId(), t.position().x(), t.position().y(), t.position().z(), t.rotation().yaw(), t.rotation().pitch());
            case PlayerObservation.TeleportAcknowledged t -> new TimelineCapsule.TeleportAcknowledged(t.teleportId());
            case PlayerObservation.ExternalMotionObserved e -> new TimelineCapsule.ExternalMotion(e.mechanism().name(), e.source(), e.vector().x(), e.vector().y(), e.vector().z(), e.correlationId());
            case PlayerObservation.Discontinuity d -> new TimelineCapsule.Discontinuity(d.kind().name());
            case PlayerObservation.TickObserved t -> new TimelineCapsule.Tick(t.serverTick());
            case PlayerObservation.CoverageGap g -> new TimelineCapsule.CoverageGap(g.reason());
            case PlayerObservation.SessionClosed ignored -> new TimelineCapsule.SessionClosed();
        };
    }

    public static PlayerObservation restore(TimelineCapsule.Observation dto) {
        return switch (dto) {
            case TimelineCapsule.Movement m -> new PlayerObservation.MovementClaim(
                    m.hasPosition() ? Optional.of(new Vector3(m.x(), m.y(), m.z())) : Optional.empty(),
                    m.hasRotation() ? Optional.of(new Rotation(m.yaw(), m.pitch())) : Optional.empty(),
                    m.onGround(), m.horizontalCollision());
            case TimelineCapsule.Outcome o -> new PlayerObservation.MovementOutcome(PlayerObservation.ApplyResult.valueOf(o.result()), new Vector3(o.x(), o.y(), o.z()));
            case TimelineCapsule.TeleportSent t -> new PlayerObservation.TeleportSent(t.teleportId(), new Vector3(t.x(), t.y(), t.z()), new Rotation(t.yaw(), t.pitch()));
            case TimelineCapsule.TeleportAcknowledged t -> new PlayerObservation.TeleportAcknowledged(t.teleportId());
            case TimelineCapsule.ExternalMotion e -> new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.valueOf(e.mechanism()), e.source(), new Vector3(e.x(), e.y(), e.z()), e.correlationId());
            case TimelineCapsule.Discontinuity d -> new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.valueOf(d.kind()));
            case TimelineCapsule.Tick t -> new PlayerObservation.TickObserved(t.serverTick());
            case TimelineCapsule.CoverageGap g -> new PlayerObservation.CoverageGap(g.reason());
            case TimelineCapsule.SessionClosed ignored -> new PlayerObservation.SessionClosed();
        };
    }

    /** Exports every record this owner-thread-only ring currently retains. complete=false with a reason must
     * never be silently upgraded to true by a later restore - TimelineCapsule's own compact constructor
     * rejects complete=true carrying a non-empty incompleteReason, so that mistake cannot compile past construction. */
    public static TimelineCapsule export(List<TimelineRecord> records, long sessionEpoch, boolean complete, String incompleteReason) {
        var entries = records.stream()
                .map(r -> new TimelineCapsule.Entry(r.captureSequence(), r.monotonicNanos(), export(r.source()), r.transportDegradeReason()))
                .toList();
        return new TimelineCapsule(1, sessionEpoch, entries, complete, incompleteReason);
    }

    /** Re-runs the real deterministic reducer against restored inputs, in order, from a fresh PlayerState -
     * a genuine replay, not a deserialization of stored output. Returns the final PlayerState plus the
     * ordered list of reproduced TimelineRecords, so a caller can assert bit-for-bit equality against what
     * was originally recorded. */
    public static ReplayResult replay(TimelineCapsule capsule) {
        var state = PlayerState.INITIAL;
        var records = new java.util.ArrayList<TimelineRecord>(capsule.entries().size());
        for (var entry : capsule.entries()) {
            var observation = restore(entry.observation());
            var transition = PlayerStateReducer.apply(state, observation, entry.captureSequence());
            state = transition.next();
            records.add(PlayerTimeline.commit(transition, entry.captureSequence(), entry.monotonicNanos(), observation, entry.transportDegradeReason()));
        }
        return new ReplayResult(state, List.copyOf(records), capsule.complete(), capsule.incompleteReason());
    }

    public record ReplayResult(PlayerState finalState, List<TimelineRecord> records, boolean complete, String incompleteReason) {}

    /** Convenience export straight from a live PlayerTimeline (owner-thread-only, matching PlayerTimeline's own
     * discipline): a timeline that has ever evicted a record cannot honestly claim a complete capsule, since
     * that earlier history is no longer retained to export - this is derived automatically, never left to a
     * caller to get wrong. */
    public static TimelineCapsule export(PlayerTimeline timeline, long sessionEpoch) {
        long evictions = timeline.snapshot().evictions();
        boolean complete = evictions == 0;
        return export(timeline.allRecords(), sessionEpoch, complete, complete ? "" : "PREFIX_EVICTED: " + evictions + " record(s) aged out before export");
    }
}
