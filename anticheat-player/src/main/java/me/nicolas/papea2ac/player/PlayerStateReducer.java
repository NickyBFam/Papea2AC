package me.nicolas.papea2ac.player;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
/**
 * Pure, deterministic, platform-independent state reducer. No Bukkit/Paper/NMS import, no I/O, no randomness,
 * no wall-clock read: every input is an explicit parameter, so it is fully testable without starting Paper
 * (TESTING.md's independent-oracle philosophy) and safe to run identically during replay.
 *
 * Known, deliberate limitation: ExternalMotionInfluence entries are never resolved/consumed here (no
 * application-acceptance decision, per this phase's explicit exclusion of movement simulation/AntiKnockback
 * logic). A session accumulating more than VelocityState.ACTIVE_BOUND simultaneous unresolved influences will
 * therefore report overflow=true from that point on, until a later phase (simulation/physics) introduces the
 * resolution mechanism LATENCY_COMPENSATION.md's M3 automaton describes. This is documented, expected
 * behavior, not a bug.
 */
public final class PlayerStateReducer {
    private PlayerStateReducer() {}
    public record Transition(PlayerState next, TimelineRecord.Kind kind, List<String> uncertaintyReasons, boolean discontinuity) {
        public Transition { Objects.requireNonNull(next); Objects.requireNonNull(kind); uncertaintyReasons = List.copyOf(uncertaintyReasons); }
    }
    public static Transition apply(PlayerState previous, PlayerObservation observation, long captureSequence) {
        Objects.requireNonNull(previous); Objects.requireNonNull(observation);
        if (captureSequence < 1) throw new IllegalArgumentException("captureSequence");
        return switch (observation) {
            case PlayerObservation.MovementClaim m -> {
                // Structural sharing: a component this claim's protocol variant did not carry stays the SAME reference.
                var movement = m.position()
                        .map(position -> new MovementState(position, m.onGround(), m.horizontalCollision(), previous.movement().lastOutcome(), captureSequence))
                        .orElse(previous.movement());
                var rotation = m.rotation()
                        .map(value -> new RotationState(value, captureSequence))
                        .orElse(previous.rotation());
                var next = new PlayerState(movement, rotation, previous.teleport(), previous.velocity(), previous.tick(),
                        previous.worldEpoch(), previous.validationEpoch(), previous.revision() + 1);
                yield new Transition(next, TimelineRecord.Kind.MOVEMENT, List.of(), false);
            }
            case PlayerObservation.MovementOutcome mo -> {
                var previousMovement = previous.movement();
                var movement = new MovementState(mo.finalPosition(), previousMovement.onGround(), previousMovement.horizontalCollision(), mo.result(), captureSequence);
                var next = new PlayerState(movement, previous.rotation(), previous.teleport(), previous.velocity(), previous.tick(),
                        previous.worldEpoch(), previous.validationEpoch(), previous.revision() + 1);
                boolean uncertain = mo.result() == PlayerObservation.ApplyResult.REJECTED
                        || mo.result() == PlayerObservation.ApplyResult.FAILED
                        || mo.result() == PlayerObservation.ApplyResult.UNKNOWN;
                yield new Transition(next, TimelineRecord.Kind.MOVEMENT_OUTCOME,
                        uncertain ? List.of("MOVEMENT_OUTCOME_" + mo.result()) : List.of(), false);
            }
            case PlayerObservation.TeleportSent ts -> {
                var predecessors = new ArrayList<>(previous.teleport().diagnosticPredecessors());
                previous.teleport().active().ifPresent(old -> predecessors.add(0, old));
                while (predecessors.size() > TeleportState.DIAGNOSTIC_BOUND) predecessors.remove(predecessors.size() - 1);
                var pending = new TeleportState.PendingTeleport(ts.teleportId(), ts.position(), ts.rotation(), false);
                var teleport = new TeleportState(Optional.of(pending), predecessors);
                var next = new PlayerState(previous.movement(), previous.rotation(), teleport, previous.velocity(), previous.tick(),
                        previous.worldEpoch(), previous.validationEpoch(), previous.revision() + 1);
                yield new Transition(next, TimelineRecord.Kind.TELEPORT_SENT, List.of(), false);
            }
            case PlayerObservation.TeleportAcknowledged ta -> {
                var active = previous.teleport().active();
                if (active.isEmpty() || active.get().teleportId() != ta.teleportId()) {
                    // Unknown/stale ID never advances any frontier (LATENCY_COMPENSATION.md): record the fact, change nothing.
                    yield new Transition(previous, TimelineRecord.Kind.TELEPORT_ACKNOWLEDGED, List.of("UNKNOWN_TELEPORT_ID"), false);
                }
                var acknowledged = new TeleportState.PendingTeleport(active.get().teleportId(), active.get().position(), active.get().rotation(), true);
                var teleport = new TeleportState(Optional.of(acknowledged), previous.teleport().diagnosticPredecessors());
                var next = new PlayerState(previous.movement(), previous.rotation(), teleport, previous.velocity(), previous.tick(),
                        previous.worldEpoch(), previous.validationEpoch(), previous.revision() + 1);
                yield new Transition(next, TimelineRecord.Kind.TELEPORT_ACKNOWLEDGED, List.of(), false);
            }
            case PlayerObservation.ExternalMotionObserved em -> {
                var influence = new ExternalMotionInfluence(em.mechanism(), em.source(), em.vector(), em.correlationId(), captureSequence);
                var diagnostics = new ArrayList<>(previous.velocity().diagnostics());
                diagnostics.add(0, influence);
                while (diagnostics.size() > VelocityState.DIAGNOSTIC_BOUND) diagnostics.remove(diagnostics.size() - 1);
                var active = new ArrayList<>(previous.velocity().active());
                boolean overflow;
                if (active.size() >= VelocityState.ACTIVE_BOUND) { overflow = true; } else { active.add(influence); overflow = false; }
                var velocity = new VelocityState(active, diagnostics, overflow);
                var next = new PlayerState(previous.movement(), previous.rotation(), previous.teleport(), velocity, previous.tick(),
                        previous.worldEpoch(), previous.validationEpoch(), previous.revision() + 1);
                yield new Transition(next, TimelineRecord.Kind.EXTERNAL_MOTION,
                        overflow ? List.of("UNCERTAIN_APPLICATION_OVERFLOW") : List.of(), false);
            }
            case PlayerObservation.Discontinuity d -> {
                var next = new PlayerState(previous.movement(), previous.rotation(), previous.teleport(), previous.velocity(), previous.tick(),
                        previous.worldEpoch() + 1, previous.validationEpoch(), previous.revision() + 1);
                yield new Transition(next, TimelineRecord.Kind.DISCONTINUITY, List.of(), true);
            }
            case PlayerObservation.TickObserved t -> {
                var tick = previous.tick().observe(t.serverTick());
                var next = new PlayerState(previous.movement(), previous.rotation(), previous.teleport(), previous.velocity(), tick,
                        previous.worldEpoch(), previous.validationEpoch(), previous.revision() + 1);
                yield new Transition(next, TimelineRecord.Kind.TICK, List.of(), false);
            }
            case PlayerObservation.CoverageGap g ->
                new Transition(previous, TimelineRecord.Kind.COVERAGE_GAP, List.of(g.reason()), false);
            case PlayerObservation.SessionClosed ignored ->
                new Transition(previous, TimelineRecord.Kind.SESSION_CLOSED, List.of(), false);
        };
    }
}
