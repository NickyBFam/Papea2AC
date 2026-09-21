package me.nicolas.papea2ac.simulation;
/**
 * Hard bounds of one timing resolution and of the reconstruction it drives (Phase 7 adds a combinatorial dimension on top of
 * the M2 solver caps, so every product is capped explicitly; exhaustion is SEARCH_LIMIT with {@code complete=false}, never a
 * "best few" selection). {@code maxHorizon} is Phase 6's inferred-step cap (MOVEMENT_SIMULATION.md M2: three) and is not raised.
 */
public record TimingBudget(int maxHorizon, int maxHypotheses, int maxInfluences, int maxFencesConsulted, int maxReconstructions, long maxTotalEvaluations, int maxFrontierNodes) {
    public TimingBudget {
        if (maxHorizon < 1 || maxHorizon > 3 || maxHypotheses < 1 || maxInfluences < 1 || maxInfluences > 8 || maxFencesConsulted < 1 || maxReconstructions < 1 || maxTotalEvaluations < 1 || maxFrontierNodes < 1)
            throw new IllegalArgumentException("timing budget");
    }
    /** 3 ticks, 16 hypotheses, 8 simultaneously unresolved influences (LATENCY_COMPENSATION.md N2), 64 fences, 16 reconstructions, 16 384 physics evaluations, 64 frontier nodes. */
    public static final TimingBudget DEFAULT = new TimingBudget(3, 16, 8, 64, 16, 16_384, 64);
}
