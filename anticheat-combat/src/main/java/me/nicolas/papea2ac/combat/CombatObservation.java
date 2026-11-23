package me.nicolas.papea2ac.combat;
import java.util.Objects;
/**
 * An inbound combat-relevant packet the observer's client SENT, as the transport observed it (PACKET_PIPELINE.md observation matrix: "Attack, entity
 * interaction ... Combat/interaction"). Production transport is CAPTURE-ONLY: an observation says the packet arrived at the capture point, never
 * that the server accepted it, that a target was hit, that damage was applied or that the client saw a result.
 *
 * <p>26.2 wire forms (CombatProfile): {@link Attack} is {@code ServerboundAttackPacket(int entityId)} and nothing else; {@link Interaction} is
 * {@code ServerboundInteractPacket(entityId, hand, location, usingSecondaryAction)}. An interaction is never an attack.
 */
public sealed interface CombatObservation {
    long sequence(); long attemptId(); long ingressOrdinal(); int targetId(); long capturedNanos();
    private static void check(long sequence, long attemptId, long ingressOrdinal, int targetId) {
        if (sequence < 1 || attemptId < 1 || ingressOrdinal < 1) throw new IllegalArgumentException("combat observation identity");
        if (targetId < 0) throw new IllegalArgumentException("target id");
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
