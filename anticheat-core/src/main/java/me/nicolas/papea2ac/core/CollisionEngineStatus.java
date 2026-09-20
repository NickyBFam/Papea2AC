package me.nicolas.papea2ac.core;
/** Primitive-only engine-wide collision status (profile identity, shape-table size and the fixed-shape counters) for platform-paper, which has no anticheat-collision edge. */
public record CollisionEngineStatus(String profile, String tableDigest, int tableStates, int tableShapes, int tableBlocks, long queries, long complete, long incomplete, long unavailable, long rejected,
                                    long truncated, long cellsVisited, long hits, long boxesEmitted, long shapeCacheHits, long shapeCacheMisses, long unsupportedStates, long contextRequired,
                                    long dynamicCells, long worldUnknownCells, long historicalMisses) {}
