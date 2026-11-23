package me.nicolas.papea2ac.world;
import java.util.Objects;
import me.nicolas.papea2ac.model.SessionKey;
/**
 * Identity of one observed entity incarnation (WORLD_MODEL.md: "EntityState contains entity network ID and incarnation"). A network id is
 * reused by the server after removal; the incarnation is a per-session, strictly increasing counter assigned at each observed spawn, so
 * {@code spawn 37 as A, remove A, spawn 37 as B} gives two different keys and no lookup for A can ever resolve to B. Keys never cross a
 * session or a world epoch.
 */
public record EntityKey(SessionKey session, long worldEpoch, int networkId, long incarnation) {
    public EntityKey { Objects.requireNonNull(session); if (worldEpoch < 0 || networkId < 0 || incarnation < 1) throw new IllegalArgumentException("entity key"); }
    public String describe() { return "#" + networkId + "/i" + incarnation + "@e" + worldEpoch; }
}
