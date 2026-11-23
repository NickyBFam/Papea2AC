package me.nicolas.papea2ac.core;
/** Bounded engine-wide Phase 14 metrics (no per-player labels): anchors, classification counts, distinct exclusion runs, fence hygiene, contained errors. */
public record TickCadenceEngineStatus(String cadenceProfile, long sessions, long anchors, long valid, long uncertain, long invalid, long exclusionRuns, long unknownAnswers, long internalErrors) {
    public static TickCadenceEngineStatus of(String profile, TickCadenceRunner.Metrics m) {
        return new TickCadenceEngineStatus(profile, m.sessionsCreated.sum(), m.anchors.sum(), m.valid.sum(), m.uncertain.sum(), m.invalid.sum(), m.exclusionRuns.sum(), m.unknownAnswers.sum(), m.internalErrors.sum());
    }
}
