package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.BlockStateKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
/**
 * Run-local interning of canonical block-state identities to small integers (WORLD_MODEL.md W3: "BlockStateId is
 * an interned integer for hot lookups... IDs may vary by run; canonical digests translate them"). Append-only and
 * thread-safe: an id, once assigned, never changes or is reused, so immutable sections can carry ids safely across
 * threads. Bounded: beyond {@link #CAPACITY} distinct states intern() returns {@link #UNRESOLVED} and the caller
 * must degrade coverage explicitly - the dictionary never grows without limit under adversarial state churn.
 * Ids are never persisted as identities; replay/fixtures always carry the canonical key.
 */
public final class BlockStateDictionary {
    public static final int CAPACITY = 65536;
    public static final int UNRESOLVED = -1;
    private final ConcurrentHashMap<BlockStateKey, Integer> ids = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<BlockStateKey> keys = new AtomicReferenceArray<>(CAPACITY);
    private volatile int size;

    public int intern(BlockStateKey key) {
        Integer existing = ids.get(key);
        if (existing != null) return existing;
        synchronized (this) {
            existing = ids.get(key);
            if (existing != null) return existing;
            if (size >= CAPACITY) return UNRESOLVED;
            int id = size;
            keys.set(id, key);
            ids.put(key, id);
            size = id + 1;
            return id;
        }
    }
    /** Null for an id this dictionary never issued (including UNRESOLVED). */
    public BlockStateKey key(int id) { return id < 0 || id >= size ? null : keys.get(id); }
    public int size() { return size; }
}
