package me.nicolas.papea2ac.core;
/** Engine-wide Phase 9 counters (fixed labels, no UUIDs): results consumed and their outcomes, side-chain (qualification) counts, contained failures, sessions created. */
public record EvidenceEngineStatus(long consumed, long legitimateWitness, long unresolved, long positiveEvents, long duplicates, long proofRejected, long outOfOrder, long sessionMismatch, long internalErrors,
                                   long sideConsumed, long sidePositiveEvents, long sideDuplicates, long sessionsCreated) {}
