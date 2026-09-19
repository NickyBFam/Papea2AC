package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Rotation;
import java.util.Objects;
/** Rotation-bearing observations only, separate from MovementState per PLAYER_STATE.md's component table. */
public record RotationState(Rotation rotation, long observedAtSequence) {
    public RotationState { Objects.requireNonNull(rotation); if (observedAtSequence < 0) throw new IllegalArgumentException("observedAtSequence"); }
    public static final RotationState UNKNOWN = new RotationState(new Rotation(0f, 0f), 0);
}
