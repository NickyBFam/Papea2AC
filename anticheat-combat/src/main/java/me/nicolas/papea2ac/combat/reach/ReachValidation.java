package me.nicolas.papea2ac.combat.reach;

import java.util.List;
import java.util.Objects;
import me.nicolas.papea2ac.model.SessionKey;

/**
 * The Phase 12 result for one observed ATTACK (COMBAT_MODEL.md "CombatValidation contains status, target incarnation, minimum/maximum residual
 * intersection information, selected witness or exclusion certificate, scene IDs, numeric tolerances, relevant attribute values and
 * uncertainty"). Immutable; a value, never a live reference.
 *
 * <ul>
 * <li>{@code VALID}: a robust witness exists — a certified eye and the same-step view for which the vanilla 26.2 pick returns the target
 *     wherever the target can be on the client (every admissible position), within range and before any block. {@link #witness} is set.</li>
 * <li>{@code INVALID}: every admissible scene is covered and fails; {@link #certificate} names the miss class of each scene family. Only a
 *     complete set of dimensions (target, eye, view, range, world where used) can produce it.</li>
 * <li>{@code UNCERTAIN}: neither; {@link #reasons} names every dimension that prevented a decision (never empty).</li>
 * </ul>
 *
 * Neither VALID nor INVALID claims the server accepted the attack, that anything was hit or that damage followed (SERVER_OUTCOME_UNSUPPORTED).
 */
public record ReachValidation(SessionKey session, long sequence, String attackId, Status status, List<MissClass> missClasses, List<Reason> reasons,
                              EyeSource eyeSource, ApplicationFrontier frontier, String targetType, int targetNetworkId, long targetIncarnation,
                              int scenes, int workUnits, Witness witness, Certificate certificate, double entityRange, double scale, String profileId,
                              List<String> notes) {
    public enum Status { VALID, UNCERTAIN, INVALID }
    /** Why a scene's pick does not return the target (R9). */
    public enum MissClass { REACH, ANGLE, OCCLUSION, INVALID_TARGET }
    /** COMBAT_MODEL B2: where the attacker eye came from. */
    public enum EyeSource { CERTIFIED_CONCRETE, ABSTRACT_EXCLUSION_ONLY, UNAVAILABLE }
    /** Every reason a decision was not reached (UNCERTAIN) or a dimension was weakened. Fixed vocabulary; never a score. */
    public enum Reason {
        TARGET_NOT_FOUND, TARGET_TYPE_UNKNOWN, TARGET_DIMENSIONS_UNSUPPORTED, TARGET_INTERPOLATION_UNSUPPORTED, TARGET_POSITION_NOT_EXACT,
        TARGET_MOUNTED, TARGET_LIFETIME_INCOMPLETE, TARGET_SCALE_UNKNOWN, TARGET_POSE_UNKNOWN, TARGET_PICKABILITY_UNKNOWN, TARGET_WINDOW_EMPTY,
        EYE_UNAVAILABLE, EYE_ABSTRACT_ONLY, ATTACKER_FRONTIER_CONFLICT, ATTACKER_POSE_UNKNOWN, ATTACKER_PASSENGER, ATTACKER_SCALE_UNKNOWN,
        ROTATION_UNKNOWN, RANGE_UNKNOWN, HELD_ITEM_UNKNOWN, ATTACK_RANGE_ITEM_UNSUPPORTED, GAME_MODE_UNSUPPORTED,
        BLOCKS_UNRESOLVED, SCENE_LIMIT, WORK_LIMIT, PENDING_EXPIRED, BORDERLINE
    }
    /**
     * The robust witness: the eye (certified state) and view used, the admissible target position box it holds for, and an upper bound on the
     * distance at which the ray enters every admissible box (always below the range).
     */
    public record Witness(int scene, double eyeX, double eyeY, double eyeZ, float yaw, float pitch, double positionsMinX, double positionsMinY, double positionsMinZ,
                          double positionsMaxX, double positionsMaxY, double positionsMaxZ, double maxEntryDistance, double range, boolean interposedEntitiesNotEvaluated) {}
    /**
     * The exclusion certificate: the scene count, the miss class of the scene families, and the smallest distance at which any eye could reach
     * the hull of every admissible box ({@code NaN} when no ray meets it). {@code minResidual = minDistance − range} for REACH certificates.
     */
    public record Certificate(int scenes, List<MissClass> classes, double minDistance, double minResidual) {
        public Certificate { classes = List.copyOf(classes); }
    }

    public ReachValidation {
        Objects.requireNonNull(session); Objects.requireNonNull(attackId); Objects.requireNonNull(status); Objects.requireNonNull(eyeSource); Objects.requireNonNull(frontier);
        Objects.requireNonNull(profileId);
        missClasses = List.copyOf(missClasses); reasons = List.copyOf(reasons); notes = List.copyOf(notes);
        if (status == Status.VALID && witness == null) throw new IllegalArgumentException("VALID needs a witness");
        if (status == Status.INVALID && (certificate == null || missClasses.isEmpty() || witness != null)) throw new IllegalArgumentException("INVALID needs a certificate and no witness");
        if (status == Status.UNCERTAIN && reasons.isEmpty()) throw new IllegalArgumentException("UNCERTAIN needs a reason");
        if (status == Status.INVALID && eyeSource == EyeSource.UNAVAILABLE && !missClasses.equals(List.of(MissClass.INVALID_TARGET))) throw new IllegalArgumentException("a geometric INVALID needs an eye set");
        if (status == Status.VALID && eyeSource != EyeSource.CERTIFIED_CONCRETE) throw new IllegalArgumentException("VALID needs a certified concrete eye (COMBAT_MODEL B2)");
        if (notes.size() > 16) throw new IllegalArgumentException("notes bound");
    }

    public String describe() {
        StringBuilder s = new StringBuilder(attackId).append(' ').append(status);
        if (!missClasses.isEmpty()) s.append(' ').append(missClasses);
        if (!reasons.isEmpty()) s.append(" reasons=").append(reasons);
        s.append(" eye=").append(eyeSource).append(" window=").append(frontier.status()).append(frontier.status() == ApplicationFrontier.Status.MARKER_PROVEN ? "(+" + frontier.ticksSincePong() + "t)" : "")
            .append(" target=").append(targetType == null ? "?" : targetType).append('#').append(targetNetworkId).append("/i").append(targetIncarnation)
            .append(" scenes=").append(scenes).append(" work=").append(workUnits);
        if (witness != null) s.append(String.format(java.util.Locale.ROOT, " witness(entry<=%.4f range=%.2f%s)", witness.maxEntryDistance(), witness.range(), witness.interposedEntitiesNotEvaluated() ? " others-not-evaluated" : ""));
        if (certificate != null) s.append(String.format(java.util.Locale.ROOT, " certificate(%s min=%.4f residual=%.4f)", certificate.classes(), certificate.minDistance(), certificate.minResidual()));
        return s.toString();
    }
}
