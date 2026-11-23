package me.nicolas.papea2ac.combat;

import java.util.Objects;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.world.EntityLookup;

/**
 * The immutable descriptive context of one observed ATTACK packet (COMBAT_MODEL.md "AttackContext records attack sequence, attacker session/epochs
 * and movement/rotation provenance, target ID/incarnation ... coverage"), built once, at the attack's position in the session's ordered stream, from
 * the observer entity history, the player timeline and the timing ledger. It is what Phase 12 consumes. It contains no reach, hitbox, ray, rotation,
 * cadence or cheat verdict, and it never claims the server accepted the attack, that anything was hit or that damage followed.
 *
 * <p>Identity: {@code session} + {@code observation.sequence()} (the transport's ordered capture sequence), unique and deterministic per session;
 * {@link #id()} renders it without any wall-clock component.
 */
public record AttackContext(SessionKey session, long worldEpoch, CombatObservation.Attack observation, EntityLookup target, CombatProvenance.Attacker attacker,
                            CombatProvenance.Timing timing, CombatCompleteness completeness, String profileId) {
    public AttackContext {
        Objects.requireNonNull(session); Objects.requireNonNull(observation); Objects.requireNonNull(target); Objects.requireNonNull(attacker); Objects.requireNonNull(timing);
        Objects.requireNonNull(completeness); Objects.requireNonNull(profileId); if (worldEpoch < 0) throw new IllegalArgumentException("world epoch");
    }
    public String id() { return "A" + session.epoch() + ":" + observation.sequence(); }
    public String describe() {
        return id() + " target#" + observation.targetId() + " -> " + target.describe() + " | attacker " + attacker.status()
            + (attacker.claimedPosition() == null ? "" : String.format(java.util.Locale.ROOT, " claim(%.5f,%.5f,%.5f)@%d rot%s", attacker.claimedPosition().x(), attacker.claimedPosition().y(), attacker.claimedPosition().z(), attacker.positionSequence(),
                attacker.rotationKnown() ? String.format(java.util.Locale.ROOT, "(%.2f,%.2f)@%d", attacker.yaw(), attacker.pitch(), attacker.rotationSequence()) : "=UNKNOWN"))
            + " validation=" + attacker.validation().lastClassification() + "@" + attacker.validation().lastEndSequence() + (attacker.validation().anchored() ? "" : "(unanchored)")
            + " | timing " + timing.status() + (timing.lastTickEndSequence() > 0 ? "@tickEnd" + timing.lastTickEndSequence() : "") + (timing.rttSamples() > 0 ? String.format(java.util.Locale.ROOT, " rtt~%.1fms j%.1fms%s", timing.rttSmoothedNanos() / 1e6, timing.rttJitterNanos() / 1e6, timing.rttStale() ? " STALE" : "") : " rtt=none")
            + " | " + completeness.describe();
    }
}
