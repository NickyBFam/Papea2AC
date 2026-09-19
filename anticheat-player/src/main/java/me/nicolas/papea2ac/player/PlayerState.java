package me.nicolas.papea2ac.player;
import java.util.Objects;
/**
 * PLAYER_STATE.md Decision S1: an immutable directory of small components, not a monolithic mutable object.
 * Unchanged components are the SAME reference across successive states (structural sharing), so recording
 * one full PlayerState reference per Timeline record costs little more than a few pointers when most
 * components did not change on that observation - see PHASE_2_IMPLEMENTATION.md's snapshot-strategy section.
 *
 * World epoch increments only on dimension/respawn (PLAYER_TIMELINE.md). Connection epoch is not tracked
 * here: it is SessionKey.epoch() one level up, since a reconnect already produces a brand-new PlayerTimeline
 * instance rather than a mutation of this one. Validation epoch is reserved (always 0): Phase 2 does not
 * perform movement validation, so nothing yet increments it; a later phase owns that decision.
 */
public record PlayerState(MovementState movement, RotationState rotation, TeleportState teleport,
                           VelocityState velocity, TickState tick, long worldEpoch, long validationEpoch, long revision) {
    public PlayerState {
        Objects.requireNonNull(movement); Objects.requireNonNull(rotation); Objects.requireNonNull(teleport);
        Objects.requireNonNull(velocity); Objects.requireNonNull(tick);
        if (worldEpoch < 0 || validationEpoch < 0 || revision < 0) throw new IllegalArgumentException("epoch/revision");
    }
    public static final PlayerState INITIAL = new PlayerState(MovementState.UNKNOWN, RotationState.UNKNOWN, TeleportState.EMPTY, VelocityState.EMPTY, TickState.UNKNOWN, 0, 0, 0);
}
