package me.nicolas.papea2ac.core;
/** Primitive-only engine-wide physics status (profile identity: movement profile, block movement table and sine table digests, table sizes) plus the diagnostic-step counters, for platform-paper, which has no anticheat-physics edge. */
public record PhysicsEngineStatus(String profile, String profileDigest, String sineDigest, String blockTableDigest, int blocks, int fluidStates, int effects, int attributes,
                                  long diagnosticSteps, long diagnosticExact, int activeTraces) {}
