package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Vector3;
import java.util.Objects;
/** PLAYER_STATE.md Decision S2's taxonomy. Player owns this ledger/vocabulary; version/physics own operation and application semantics in later phases. */
public record ExternalMotionInfluence(Mechanism mechanism, String source, Vector3 vector, long correlationId, long observedAtSequence) {
    public ExternalMotionInfluence {
        Objects.requireNonNull(mechanism); Objects.requireNonNull(source); Objects.requireNonNull(vector);
        if (source.length() > 64) throw new IllegalArgumentException("source");
        if (correlationId < 0 || observedAtSequence < 1) throw new IllegalArgumentException("influence ids");
    }
    public enum Mechanism { VELOCITY_DELTA, VELOCITY_REPLACE, MODE_ACCELERATION, KINEMATIC_DISPLACEMENT, CONTACT_PUSH, DECLARED_UNKNOWN_MOTION }
    public int chargedBytes() { return 96 + source.length(); }
}
