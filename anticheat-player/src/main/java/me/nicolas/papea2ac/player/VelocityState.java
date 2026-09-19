package me.nicolas.papea2ac.player;
import java.util.List;
import java.util.Objects;
/**
 * PLAYER_STATE.md bound: 64 diagnostic records, eight active simultaneously unresolved influences; overflow
 * is visible coverage loss with zero violation weight (LATENCY_COMPENSATION.md Decision N2), never a silently
 * dropped record.
 */
public record VelocityState(List<ExternalMotionInfluence> active, List<ExternalMotionInfluence> diagnostics, boolean overflow) {
    public static final int ACTIVE_BOUND = 8;
    public static final int DIAGNOSTIC_BOUND = 64;
    public VelocityState {
        Objects.requireNonNull(active); Objects.requireNonNull(diagnostics);
        if (active.size() > ACTIVE_BOUND) throw new IllegalArgumentException("active influence bound");
        if (diagnostics.size() > DIAGNOSTIC_BOUND) throw new IllegalArgumentException("diagnostic influence bound");
        active = List.copyOf(active); diagnostics = List.copyOf(diagnostics);
    }
    public static final VelocityState EMPTY = new VelocityState(List.of(), List.of(), false);
}
