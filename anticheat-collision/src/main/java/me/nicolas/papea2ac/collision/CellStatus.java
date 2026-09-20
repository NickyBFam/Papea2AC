package me.nicolas.papea2ac.collision;
/**
 * Per-cell outcome vocabulary of a collision query (task §9): the world-coverage reasons inherited unchanged from
 * Phase 3's {@code BlockLookup.Status}, plus the geometry-level reasons Phase 4 adds. Fixed enum = fixed metric
 * keys; a result carries one count per status, never a free-form label.
 *
 * Only the first four statuses contribute exact geometry; everything else makes the query INCOMPLETE and is reported,
 * never silently treated as air or as solid.
 */
public enum CellStatus {
    /** Shape is a pure function of the observed canonical state. */
    EXACT,
    /** Shape resolved from the observed state plus the deterministic per-position offset (bamboo, pointed dripstone, sulfur spike). */
    EXACT_OFFSET,
    /** Shape resolved from the observed state plus the supplied entity context (scaffolding, powder snow). */
    EXACT_CONTEXT,
    /** Observed state whose geometry cannot be anything (air, fluids, plants ...): exact and empty. */
    EXACT_EMPTY,
    /** Context-dependent block queried without an entity context: envelope only (lower empty / upper union). */
    CONTEXT_REQUIRED,
    /** Block-entity-driven geometry (shulker lid, moving piston): only the resting lower bound and a motion upper bound are knowable from packets. */
    DYNAMIC_BLOCK_ENTITY,
    /** Observed canonical state absent from the pinned profile's table (unknown block or property set): no geometry claimed. */
    UNSUPPORTED_BLOCK_STATE,
    // ---- world coverage (Phase 3 vocabulary, one-to-one) ----
    UNKNOWN_CELL, UNKNOWN_CHUNK, UNKNOWN_SECTION, UNLOADED, EVICTED, CONTENT_NOT_RETAINED, TRANSPORT_INCOMPLETE, OUTSIDE_DIMENSION, DIMENSION_UNKNOWN, WRONG_WORLD_EPOCH, HISTORY_NOT_RETAINED, FUTURE_SEQUENCE;

    public boolean exact() { return this == EXACT || this == EXACT_OFFSET || this == EXACT_CONTEXT || this == EXACT_EMPTY; }
    /** Geometry-level uncertainty (the world was known, the shape is not fully determined). */
    public boolean geometryUncertain() { return this == CONTEXT_REQUIRED || this == DYNAMIC_BLOCK_ENTITY || this == UNSUPPORTED_BLOCK_STATE; }
    public boolean worldUnknown() { return ordinal() >= UNKNOWN_CELL.ordinal(); }
}
