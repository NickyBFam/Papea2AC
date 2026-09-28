package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.mitigation.MitigationBounds;
import me.nicolas.papea2ac.mitigation.MitigationIntent;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.model.SafeAnchorValidationPort;
import java.util.Objects;
import java.util.Optional;
/**
 * The main-thread precondition order of MITIGATION.md G1 ("Main thread checks identity, online state, current world, death/respawn/vehicle
 * transition, current health latch, pending teleports, permissions/exemptions and action expiry before any API call"), as a pure function of
 * the facts the platform gathered right now. The platform executor collects the facts (Paper API reads only) and calls this; the first failing
 * check is the typed rejection. A {@code CLEAR} destination is required: UNKNOWN is never treated as clear.
 */
public final class MitigationPreconditions {
    private MitigationPreconditions() {}
    /** What the main thread observed for one intent immediately before deciding to apply it. */
    public record PlatformFacts(boolean currentSession, boolean online, long nowNanos, String worldKey, boolean dead, boolean vehicleOrGliding, boolean supportedGameMode,
                                int unresolvedTeleports, double distanceToTarget, boolean destinationLoaded, SafeAnchorValidationPort.ClearanceResult clearance) {
        public PlatformFacts { Objects.requireNonNull(worldKey); Objects.requireNonNull(clearance); }
    }
    public static Optional<Reason> check(MitigationIntent it, PlatformFacts f, MitigationBounds bounds) {
        Objects.requireNonNull(it); Objects.requireNonNull(f); Objects.requireNonNull(bounds);
        if (!f.currentSession()) return Optional.of(Reason.SESSION_ENDED);
        if (!f.online()) return Optional.of(Reason.PLAYER_OFFLINE);
        if (f.nowNanos() > it.deadlineNanos()) return Optional.of(Reason.DEADLINE_EXPIRED);
        if (!f.worldKey().equals(it.worldKey())) return Optional.of(Reason.WORLD_CHANGED);
        if (f.dead()) return Optional.of(Reason.DEATH_OR_RESPAWN);
        if (f.vehicleOrGliding()) return Optional.of(Reason.VEHICLE_OR_GLIDING);
        if (!f.supportedGameMode()) return Optional.of(Reason.UNSUPPORTED_LOCOMOTION);
        if (f.unresolvedTeleports() > 0) return Optional.of(Reason.UNRESOLVED_TELEPORT);
        if (!(f.distanceToTarget() <= bounds.maxCorrectionDistance())) return Optional.of(Reason.ANCHOR_TOO_FAR);
        if (!f.destinationLoaded()) return Optional.of(Reason.DESTINATION_UNLOADED);
        if (f.clearance() == SafeAnchorValidationPort.ClearanceResult.OBSTRUCTED) return Optional.of(Reason.COLLISION_OBSTRUCTED);
        if (f.clearance() != SafeAnchorValidationPort.ClearanceResult.CLEAR) return Optional.of(Reason.COLLISION_UNKNOWN);
        return Optional.empty();
    }
}
