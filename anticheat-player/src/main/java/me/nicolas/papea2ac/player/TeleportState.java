package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.model.Rotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
/** One active correction, eight diagnostic predecessors (PLAYER_STATE.md's exact TeleportState bound). */
public record TeleportState(Optional<PendingTeleport> active, List<PendingTeleport> diagnosticPredecessors) {
    public static final int DIAGNOSTIC_BOUND = 8;
    public TeleportState {
        Objects.requireNonNull(active); Objects.requireNonNull(diagnosticPredecessors);
        if (diagnosticPredecessors.size() > DIAGNOSTIC_BOUND) throw new IllegalArgumentException("diagnostic predecessors bound");
        diagnosticPredecessors = List.copyOf(diagnosticPredecessors);
    }
    public static final TeleportState EMPTY = new TeleportState(Optional.empty(), List.of());
    /** Sent is not applied: acknowledged only records that a teleport-confirm control packet referenced this ID, never that client physics applied it. */
    public record PendingTeleport(long teleportId, Vector3 position, Rotation rotation, boolean acknowledged) {
        public PendingTeleport { Objects.requireNonNull(position); Objects.requireNonNull(rotation); if (teleportId < 0) throw new IllegalArgumentException("teleportId"); }
    }
}
