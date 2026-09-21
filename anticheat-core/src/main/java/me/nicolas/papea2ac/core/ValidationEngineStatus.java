package me.nicolas.papea2ac.core;
/** Engine-wide Phase 8 counters (fixed labels, no UUIDs; brief 58): requests and classifications, the UNCERTAIN families, the Phase 7/6 work consumed, anchoring, contained failures, and controlled injections. */
public record ValidationEngineStatus(long requests, long valid, long uncertain, long invalid, long uncertainSearchLimit, long uncertainUnsupported, long uncertainWorldIncomplete, long uncertainExternalMotion, long uncertainUnanchored, long uncertainOther,
                                     long timingBranches, long reconstructions, long evaluations, long anchors, long anchorsLost, long internalErrors, long injectedRequests) {}
