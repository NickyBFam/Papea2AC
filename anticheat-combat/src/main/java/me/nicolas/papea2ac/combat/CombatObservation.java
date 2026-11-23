package me.nicolas.papea2ac.combat;
import java.util.Objects;
/**
 * An inbound combat-relevant packet the observer's client SENT, as the transport observed it (PACKET_PIPELINE.md observation matrix: "Attack, entity
 * interaction ... Combat/interaction"). Production transport is CAPTURE-ONLY: an observation says the packet arrived at the capture point, never
 * that the server accepted it, that a target was hit, that damage was applied or that the client saw a result.
 *
 * <p>26.2 wire forms (CombatProfile): {@link Attack} is {@code ServerboundAttackPacket(int entityId)} and nothing else; {@link Interaction} is
 * {@code ServerboundInteractPacket(entityId, hand, location, usingSecondaryAction)}, where the location is the hit point MINUS the target's
 * client-side position, LP-quantized ({@code Vec3.LP_STREAM_CODEC}) and server-decoded: an offset from the target, never a world position
 * (oracle-07). {@link PiercingAttack} is the spear's {@code ServerboundPlayerActionPacket(STAB)}: the client sends it INSTEAD of an attack packet
 * and it names no target (the server picks targets itself), so it never resolves one (P11-F19). An interaction is never an attack.
 */
public sealed interface CombatObservation {
    long sequence(); long attemptId(); long ingressOrdinal(); long capturedNanos();
    private static void check(long sequence, long attemptId, long ingressOrdinal, int targetId) {
        if (sequence < 1 || attemptId < 1 || ingressOrdinal < 1) throw new IllegalArgumentException("combat observation identity");
        if (targetId < 0) throw new IllegalArgumentException("target id");
    }
    record PiercingAttack(long sequence, long attemptId, long ingressOrdinal, long capturedNanos) implements CombatObservation {
        public PiercingAttack { check(sequence, attemptId, ingressOrdinal, 0); }
    }
    record Attack(long sequence, long attemptId, long ingressOrdinal, int targetId, long capturedNanos) implements CombatObservation {
        public Attack { check(sequence, attemptId, ingressOrdinal, targetId); }
    }
    record Interaction(long sequence, long attemptId, long ingressOrdinal, int targetId, long capturedNanos, String hand, double x, double y, double z, boolean secondaryAction) implements CombatObservation {
        public Interaction {
            check(sequence, attemptId, ingressOrdinal, targetId); Objects.requireNonNull(hand);
            if (!hand.equals("MAIN_HAND") && !hand.equals("OFF_HAND")) throw new IllegalArgumentException("hand");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("interaction location");
        }
    }
}
