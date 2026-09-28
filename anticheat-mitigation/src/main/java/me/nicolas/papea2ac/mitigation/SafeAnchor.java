package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.AnchorSource;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import java.util.List;
import java.util.Objects;
/**
 * A previously supported, known-safe player state that a setback may return to (MITIGATION.md: "SafeAnchor is a previously supported
 * position/pose with server compatibility, collision clearance, world revision, trusted velocity context and age ... a model value from a
 * certified concrete trajectory and correlated accepted server state"). Immutable; carries its full provenance so every selected target
 * explains itself in diagnostics and replay.
 *
 * <ul>
 * <li>{@code session}, {@code worldEpoch}, {@code worldRevision} - where the anchor is valid (never across a session or epoch);</li>
 * <li>{@code sequence}, {@code nanos} - the observation that certified it (capture sequence, monotonic time: its age);</li>
 * <li>{@code position} - the one concrete position every continuation of the certified VALID transition agreed on (exact doubles);</li>
 * <li>{@code yaw}/{@code pitch} - the rotation observed with it (diagnostic: a setback preserves the player's CURRENT rotation);</li>
 * <li>{@code grounded} - every continuation was on the ground (the trusted velocity context: a resting-on-ground state);</li>
 * <li>{@code crouchingPossible} - some continuation was crouching (the standing box was nevertheless proven clear);</li>
 * <li>{@code source} and {@code basis} - why it was admitted (the admitted checks, in order).</li>
 * </ul>
 */
public record SafeAnchor(SessionKey session, long worldEpoch, long worldRevision, long sequence, long nanos, Vector3 position, float yaw, float pitch,
                         boolean grounded, boolean crouchingPossible, AnchorSource source, List<String> basis) {
    public SafeAnchor {
        Objects.requireNonNull(session); Objects.requireNonNull(position); Objects.requireNonNull(source); basis = List.copyOf(basis);
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z()) || !Float.isFinite(yaw) || !Float.isFinite(pitch) || worldEpoch < 0 || worldRevision < 0 || sequence < 0)
            throw new IllegalArgumentException("anchor state");
    }
    public long ageAt(long nowNanos) { return nowNanos - nanos; }
    public double distanceTo(Vector3 p) { double dx = p.x() - position.x(), dy = p.y() - position.y(), dz = p.z() - position.z(); return Math.sqrt(dx * dx + dy * dy + dz * dz); }
    public String describe() {
        return String.format(java.util.Locale.ROOT, "anchor seq=%d epoch=%d rev=%d pos=(%.6f,%.6f,%.6f) %s%s basis=%s", sequence, worldEpoch, worldRevision, position.x(), position.y(), position.z(), source, crouchingPossible ? " crouching-possible" : "", basis);
    }
}
