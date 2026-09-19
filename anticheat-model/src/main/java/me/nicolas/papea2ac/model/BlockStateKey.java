package me.nicolas.papea2ac.model;
import java.util.*;
/**
 * Platform-independent, version-safe block-state identity: canonical namespaced block key plus a sorted property
 * map (WORLD_MODEL.md: "BlockStateKey = canonical namespaced type + canonical property set"; runtime palette ids
 * are never persisted as identities). Two wire encodings of the same logical state - different native numeric
 * ids, different property order - produce equal keys. Property VALUES are kept verbatim so later phases can
 * derive shape/friction/fluid/climbable behaviour from them; this type itself derives nothing.
 */
public record BlockStateKey(String block, SortedMap<String, String> properties) {
    public BlockStateKey {
        Objects.requireNonNull(block); Objects.requireNonNull(properties);
        if (block.isEmpty() || block.length() > 256 || properties.size() > 32) throw new IllegalArgumentException("block state key");
        for (var e : properties.entrySet()) if (e.getKey().isEmpty() || e.getKey().length() > 64 || e.getValue().length() > 64) throw new IllegalArgumentException("block state property");
        properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
    }
    public static BlockStateKey of(String block) { return new BlockStateKey(block, new TreeMap<>()); }
    /** Canonical archival form, e.g. {@code minecraft:oak_stairs[facing=north,half=bottom]}; deterministic because properties are sorted. */
    public String canonical() {
        if (properties.isEmpty()) return block;
        var sb = new StringBuilder(block).append('[');
        boolean first = true;
        for (var e : properties.entrySet()) { if (!first) sb.append(','); first = false; sb.append(e.getKey()).append('=').append(e.getValue()); }
        return sb.append(']').toString();
    }
    /** Parses the canonical form; the inverse of canonical() for replay/fixtures. */
    public static BlockStateKey parse(String canonical) {
        int open = canonical.indexOf('[');
        if (open < 0) return of(canonical);
        if (!canonical.endsWith("]")) throw new IllegalArgumentException("canonical block state");
        var props = new TreeMap<String, String>();
        String body = canonical.substring(open + 1, canonical.length() - 1);
        if (!body.isEmpty()) for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("canonical block state property");
            props.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return new BlockStateKey(canonical.substring(0, open), props);
    }
}
