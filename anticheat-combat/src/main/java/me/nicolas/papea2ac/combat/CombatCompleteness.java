package me.nicolas.papea2ac.combat;

import java.util.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.EntityLookup;
import me.nicolas.papea2ac.world.EntityRevision;

/**
 * Why an attack context is (in)complete for a later geometric consumer (COMBAT_MODEL.md: "Untracked or evicted targets are UNKNOWN"; unknown is first
 * class). {@code reasons} are the gaps specific to THIS attack; {@code standing} are the limitations that apply to every attack on the current
 * transport and model (production transport is CAPTURE-ONLY; the rendered interpolation state is not modelled; no client application frontier is
 * claimed). A context is COMPLETE only when {@code reasons} is empty; it never becomes a verdict either way.
 */
public record CombatCompleteness(Status status, Set<Reason> reasons, Set<Standing> standing) {
    public enum Status { COMPLETE, INCOMPLETE }
    public enum Reason {
        TARGET_UNKNOWN, TARGET_PRE_SPAWN, TARGET_DESPAWNED, TARGET_ID_REUSED, TARGET_EVICTED, TARGET_EPOCH_MISMATCH, TARGET_SESSION_MISMATCH, TARGET_FUTURE_SEQUENCE,
        TARGET_COVERAGE, TARGET_UNSUPPORTED_MOTION, TARGET_QUERY_LIMIT, TARGET_POSITION_INTERPOLATION_DEPENDENT, TARGET_ROTATION_INTERPOLATION_DEPENDENT, TARGET_POSE_UNKNOWN, TARGET_MOUNTED,
        TARGET_VEHICLE_DEPENDENT, TARGET_PLAYER_INFO_UNTRACKED,
        ATTACKER_HISTORY_UNAVAILABLE, ATTACKER_ROTATION_UNKNOWN, ATTACKER_MOVEMENT_UNVALIDATED,
        TIMING_UNAVAILABLE, TIMING_TICK_END_UNAVAILABLE, TIMING_RTT_UNKNOWN_OR_STALE
    }
    public enum Standing { SERVER_OUTCOME_UNSUPPORTED, INTERPOLATION_PROGRESS_NOT_MODELLED, CLIENT_APPLICATION_FRONTIER_UNCLAIMED }
    public static final Set<Standing> CAPTURE_ONLY = Collections.unmodifiableSet(EnumSet.allOf(Standing.class));
    public CombatCompleteness {
        Objects.requireNonNull(status); reasons = Collections.unmodifiableSet(reasons.isEmpty() ? EnumSet.noneOf(Reason.class) : EnumSet.copyOf(reasons));
        standing = Collections.unmodifiableSet(standing.isEmpty() ? EnumSet.noneOf(Standing.class) : EnumSet.copyOf(standing));
        if ((status == Status.COMPLETE) != reasons.isEmpty()) throw new IllegalArgumentException("COMPLETE exactly when no reason");
    }
    public static CombatCompleteness of(EntityLookup target, CombatProvenance.Attacker attacker, CombatProvenance.Timing timing) {
        EnumSet<Reason> r = EnumSet.noneOf(Reason.class);
        switch (target.status()) {
            case FOUND -> {
                EntityRevision v = target.revision();
                if (v.positionStatus() == EntityRevision.Status.INTERPOLATION_DEPENDENT) r.add(Reason.TARGET_POSITION_INTERPOLATION_DEPENDENT);
                if (v.rotationStatus() == EntityRevision.Status.INTERPOLATION_DEPENDENT) r.add(Reason.TARGET_ROTATION_INTERPOLATION_DEPENDENT);
                // P11-F12 / P11-F13: the client places the entity from a vehicle relation (or has not been re-synced since one ended)
                if (v.positionStatus() == EntityRevision.Status.VEHICLE_DEPENDENT || v.rotationStatus() == EntityRevision.Status.VEHICLE_DEPENDENT) r.add(Reason.TARGET_VEHICLE_DEPENDENT);
                if (!v.poseKnown()) r.add(Reason.TARGET_POSE_UNKNOWN);
                // P11-F20: the client drops a player spawn without a prior PlayerInfo entry; PlayerInfo is not captured, so that precondition is unobserved
                if (CombatProfile.PLAYER_TYPE.equals(target.type())) r.add(Reason.TARGET_PLAYER_INFO_UNTRACKED);
                if (v.vehicleId() >= 0) r.add(Reason.TARGET_MOUNTED); // vehicle passengers are capability-gated (COMBAT_MODEL): not silently unmounted geometry
            }
            case UNKNOWN -> r.add(Reason.TARGET_UNKNOWN);
            case PRE_SPAWN -> r.add(Reason.TARGET_PRE_SPAWN);
            case DESPAWNED -> r.add(Reason.TARGET_DESPAWNED);
            case ID_REUSED -> r.add(Reason.TARGET_ID_REUSED);
            case EVICTED -> r.add(Reason.TARGET_EVICTED);
            case WORLD_EPOCH_MISMATCH -> r.add(Reason.TARGET_EPOCH_MISMATCH);
            case SESSION_MISMATCH -> r.add(Reason.TARGET_SESSION_MISMATCH);
            case FUTURE_SEQUENCE -> r.add(Reason.TARGET_FUTURE_SEQUENCE);
            case INCOMPLETE_COVERAGE -> r.add(Reason.TARGET_COVERAGE);
            case UNSUPPORTED -> r.add(Reason.TARGET_UNSUPPORTED_MOTION);
            case QUERY_LIMIT -> r.add(Reason.TARGET_QUERY_LIMIT); // single-sequence lookups never answer it; kept total for the vocabulary
        }
        if (attacker.status() != CombatProvenance.Attacker.Status.FOUND) r.add(Reason.ATTACKER_HISTORY_UNAVAILABLE);
        else {
            if (!attacker.rotationKnown()) r.add(Reason.ATTACKER_ROTATION_UNKNOWN);
            if (!attacker.movementValidatedThrough()) r.add(Reason.ATTACKER_MOVEMENT_UNVALIDATED);
        }
        switch (timing.status()) {
            case UNAVAILABLE -> r.add(Reason.TIMING_UNAVAILABLE);
            case NO_TICK_END_YET, TICK_END_UNSUPPORTED -> r.add(Reason.TIMING_TICK_END_UNAVAILABLE);
            case TICK_ALIGNED -> { }
        }
        if (timing.status() != CombatProvenance.Timing.Status.UNAVAILABLE && timing.rttStale()) r.add(Reason.TIMING_RTT_UNKNOWN_OR_STALE);
        return new CombatCompleteness(r.isEmpty() ? Status.COMPLETE : Status.INCOMPLETE, r, CAPTURE_ONLY);
    }
    public String describe() { return status + (reasons.isEmpty() ? "" : " " + reasons) + " standing=" + standing; }
}
