package me.nicolas.papea2ac.world;
/**
 * First-class coverage state of one observer chunk. Not a boolean: the reason state is missing matters for
 * diagnostics and later uncertainty (unload = the protocol told us the client discarded it; evicted = Papea2AC
 * dropped its own model for memory; both make current lookups unavailable, with different provenance).
 * Only a qualifying baseline event moves a chunk back to COMPLETE - nothing heals accidentally.
 */
public enum WorldCoverage {
    /** A full chunk baseline was observed with retained content; every section cell is known. */
    COMPLETE,
    /** Some cells are known (block updates, or a baseline for some sections) but no complete baseline covers the chunk. */
    PARTIAL,
    /** A full baseline was observed but its section content was not retained (over the inline cap, decode fault, or budget) - the chunk is known to have been sent, its blocks are not. */
    CONTENT_NOT_RETAINED,
    /** The protocol told the client to discard this chunk; current state is unavailable by design. */
    UNLOADED,
    /** Papea2AC dropped its model of this chunk for memory; the client may still hold it. */
    EVICTED,
    /** Nothing was ever observed for this chunk in this world epoch. */
    UNKNOWN,
    /** The chunk was observed while Phase 1 transport coverage was already broken; its content cannot be trusted as the client's view. */
    TRANSPORT_INCOMPLETE
}
