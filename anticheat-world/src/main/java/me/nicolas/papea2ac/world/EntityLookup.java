package me.nicolas.papea2ac.world;
import java.util.Objects;
import java.util.UUID;
/**
 * Result of one entity history query (WORLD_MODEL.md query contract: KNOWN or UNKNOWN(reason); "Omission never proves absence"). Unknown is
 * first class: a missing or unprovable state is never turned into absence, never into the current state, and never into the nearest retained
 * revision.
 *
 * <ul>
 * <li>{@code FOUND}: {@code revision} is the network state in effect just before the queried sequence (exactness per field inside the revision).</li>
 * <li>{@code UNKNOWN}: no incarnation of this id was observed in this epoch, and nothing makes that unprovable.</li>
 * <li>{@code PRE_SPAWN}: the incarnation (or the only incarnation of the id) was spawned at or after the queried sequence.</li>
 * <li>{@code DESPAWNED}: the incarnation was removed (or replaced by a respawn / ended with the epoch) before the queried sequence and the id was free.</li>
 * <li>{@code ID_REUSED}: the queried incarnation had ended and the same network id belonged to a LATER incarnation at the queried sequence.</li>
 * <li>{@code EVICTED}: the needed state was retained once and has aged out (bounds, horizon, deep-history demotion or capacity eviction).</li>
 * <li>{@code WORLD_EPOCH_MISMATCH} / {@code SESSION_MISMATCH}: the query names another epoch / session; history never crosses them.</li>
 * <li>{@code FUTURE_SEQUENCE}: the queried sequence has not been observed yet.</li>
 * <li>{@code INCOMPLETE_COVERAGE}: a clientbound coverage break (or the 256-entity capacity) makes the answer unprovable ({@code detail} says which).</li>
 * <li>{@code UNSUPPORTED}: the entity moved by a mechanism the model does not reconstruct (e.g. new-minecart lerp steps).</li>
 * <li>{@code QUERY_LIMIT}: a bounded range query ({@link EntityHistory#revisions}) matched more revisions than its limit; the returned prefix is flagged
 *     partial, never silently truncated. A single-sequence lookup is bounded by construction and never answers QUERY_LIMIT.</li>
 * </ul>
 */
public record EntityLookup(Status status, EntityKey key, String type, UUID uuid, EntityRevision revision, long spawnSequence, long endSequence,
                           long queriedSequence, long worldEpoch, String detail) {
    public enum Status { FOUND, UNKNOWN, PRE_SPAWN, DESPAWNED, EVICTED, ID_REUSED, WORLD_EPOCH_MISMATCH, SESSION_MISMATCH, FUTURE_SEQUENCE, INCOMPLETE_COVERAGE, UNSUPPORTED, QUERY_LIMIT }
    public EntityLookup {
        Objects.requireNonNull(status); Objects.requireNonNull(detail);
        if ((status == Status.FOUND) != (revision != null)) throw new IllegalArgumentException("a revision exactly when FOUND");
        if (detail.length() > 96) throw new IllegalArgumentException("detail");
    }
    public boolean found() { return status == Status.FOUND; }
    public String describe() {
        return status + (key == null ? "" : " " + key.describe()) + (type == null ? "" : " " + type) + (revision == null ? "" : " " + revision.describe()) + (detail.isEmpty() ? "" : " (" + detail + ")");
    }
    static EntityLookup miss(Status status, EntityKey key, String type, UUID uuid, long spawn, long end, long queried, long epoch, String detail) {
        return new EntityLookup(status, key, type, uuid, null, spawn, end, queried, epoch, detail);
    }
}
