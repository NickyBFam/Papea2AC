package me.nicolas.papea2ac.core;
/** Primitive-only engine-wide input-reconstruction status (legal raw domain size, context-free class count, observation profile, solver caps) plus the diagnostic tracer counters, for platform-paper, which has no anticheat-simulation edge. */
public record ReconstructionEngineStatus(int rawDomain, int contextFreeClasses, String observationProfile, int maxNodes, int maxEvaluations, int maxTicks,
                                         long diagnosticRequests, long diagnosticDeclaredPresent, long diagnosticSamples, int activeTraces) {}
