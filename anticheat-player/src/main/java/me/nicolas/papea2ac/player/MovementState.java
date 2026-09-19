package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Vector3;
import java.util.Objects;
/**
 * The claimed/observed movement fact history (256-attempt/256 KiB/500 ms provisional-to-certified
 * journal for movement is already owned by Phase 1's TruthActor in anticheat-core; see PACKET_PIPELINE.md
 * Decision P2). This component intentionally does not duplicate that certification ledger: it records only
 * the latest observed claim and latest correlated outcome, i.e. "what the Timeline currently believes",
 * not a second provisional journal.
 */
public record MovementState(Vector3 position, boolean onGround, boolean horizontalCollision,
                             PlayerObservation.ApplyResult lastOutcome, long observedAtSequence) {
    public MovementState {
        Objects.requireNonNull(position); Objects.requireNonNull(lastOutcome);
        if (observedAtSequence < 0) throw new IllegalArgumentException("observedAtSequence");
    }
    public static final MovementState UNKNOWN = new MovementState(Vector3.ZERO, false, false, PlayerObservation.ApplyResult.UNKNOWN, 0);
}
