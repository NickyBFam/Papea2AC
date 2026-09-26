package me.nicolas.papea2ac.evidence;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.BoundKind;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.List;
import java.util.Objects;
/**
 * The immutable projection of one completed Phase 8 {@code MovementValidationResult} that the evidence engine consumes (the
 * "validation projection" of the approved DAG: evidence never reads the simulation types). Core maps every field name-for-name; the
 * record carries exactly what an EvidenceRecord needs (EVIDENCE_MODEL "Evidence schema"): the session and epochs, the originating
 * sequence range, the classification with its completeness dimensions and reasons, the exhaustion proof summary, the residual with its
 * bound provenance, the work performed and the engine/profile provenance. No candidate graph, no player object, no exception.
 *
 * <p>{@code nanos} is the monotonic capture time of the transition's target observation (never the wall clock); it orders nothing by
 * itself - sequences do - and it drives window age only.
 */
public record ValidationRecord(SessionKey session, long worldEpoch, long startSequence, long endSequence, long nanos, Classification classification,
                               Completeness completeness, List<UncertainCode> uncertainReasons, List<InvalidCode> invalidReasons, ProofSummary proof, ResidualSummary residual,
                               WorkSummary work, Provenance provenance, int witnesses) {
    public static final int MAX_HYPOTHESIS_IDS = 32;
    /** The 13 Phase 8 completeness dimensions, name for name. */
    public record Completeness(boolean timing, boolean reconstruction, boolean world, boolean attributes, boolean effects, boolean equipment, boolean entities, boolean border,
                               boolean externalMotion, boolean mechanicSupported, boolean search, boolean observation, boolean frontier) {
        public static final Completeness ALL = new Completeness(true, true, true, true, true, true, true, true, true, true, true, true, true);
        public boolean all() { return timing && reconstruction && world && attributes && effects && equipment && entities && border && externalMotion && mechanicSupported && search && observation && frontier; }
        public int missingCount() { int n = 0; for (boolean b : new boolean[] { timing, reconstruction, world, attributes, effects, equipment, entities, border, externalMotion, mechanicSupported, search, observation, frontier }) if (!b) n++; return n; }
    }
    /** The Phase 8 exhaustion proof summary ({@code Proof.NONE} maps to {@link #NONE}: no proof). */
    public record ProofSummary(boolean present, int hypothesesExhausted, int reconstructions, long evaluations, int rawEnumerated, int excludedExact, int excludedEnvelope, int candidatesRemaining,
                               String observationProfile, String movementProfile, String collisionProfile, String timingProfile, long worldRevision, List<String> hypothesisIds) {
        public static final ProofSummary NONE = new ProofSummary(false, 0, 0, 0, 0, 0, 0, 0, "-", "-", "-", "-", 0, List.of());
        public ProofSummary {
            Objects.requireNonNull(observationProfile); Objects.requireNonNull(movementProfile); Objects.requireNonNull(collisionProfile); Objects.requireNonNull(timingProfile); hypothesisIds = List.copyOf(hypothesisIds);
            if (hypothesisIds.size() > MAX_HYPOTHESIS_IDS) throw new IllegalArgumentException("hypothesis ids");
            if (hypothesesExhausted < 0 || reconstructions < 0 || evaluations < 0 || rawEnumerated < 0 || excludedExact < 0 || excludedEnvelope < 0 || candidatesRemaining < 0) throw new IllegalArgumentException("proof counts");
        }
    }
    /** The Phase 8 residual diagnostics (observed displacement from the nearest chain start, the legal extent and its bound kind, the excesses). Finite only. */
    public record ResidualSummary(boolean present, double observedHorizontal, double observedRise, double maxLegalHorizontal, double maxLegalRise, double minLegalRise, BoundKind bound, double horizontalExcess, double verticalExcess) {
        public static final ResidualSummary NONE = new ResidualSummary(false, 0, 0, 0, 0, 0, BoundKind.NONE, 0, 0);
        public ResidualSummary {
            Objects.requireNonNull(bound);
            for (double d : new double[] { observedHorizontal, observedRise, maxLegalHorizontal, maxLegalRise, minLegalRise, horizontalExcess, verticalExcess }) if (!Double.isFinite(d)) throw new IllegalArgumentException("residual not finite");
        }
    }
    /** What Phase 8 consumed for the transition (descriptive). */
    public record WorkSummary(int timingHypotheses, int completeTimingHypotheses, int explainingHypotheses, int legalCandidates, int reconstructions, long evaluations) {
        public WorkSummary { if (timingHypotheses < 0 || completeTimingHypotheses < 0 || explainingHypotheses < 0 || legalCandidates < 0 || reconstructions < 0 || evaluations < 0) throw new IllegalArgumentException("work counts"); }
    }
    /** Engine/profile provenance and the external-motion / server-correction context of the transition (the Phase 8 witness kinds, name for name). */
    public record Provenance(String timingProfile, String movementProfile, String observationProfile, String collisionProfile, long worldRevision, int appliedInfluences, boolean correctionApplication) {
        public Provenance { Objects.requireNonNull(timingProfile); Objects.requireNonNull(movementProfile); Objects.requireNonNull(observationProfile); Objects.requireNonNull(collisionProfile); if (appliedInfluences < 0) throw new IllegalArgumentException("influences"); }
    }
    public ValidationRecord {
        Objects.requireNonNull(session); Objects.requireNonNull(classification); Objects.requireNonNull(completeness); uncertainReasons = List.copyOf(uncertainReasons); invalidReasons = List.copyOf(invalidReasons);
        Objects.requireNonNull(proof); Objects.requireNonNull(residual); Objects.requireNonNull(work); Objects.requireNonNull(provenance);
        if (endSequence < startSequence) throw new IllegalArgumentException("sequence range");
        if (witnesses < 0) throw new IllegalArgumentException("witnesses");
        if (uncertainReasons.size() > UncertainCode.values().length || invalidReasons.size() > InvalidCode.values().length) throw new IllegalArgumentException("reasons");
    }
    public boolean valid() { return classification == Classification.VALID; }
    public boolean uncertain() { return classification == Classification.UNCERTAIN; }
    public boolean invalid() { return classification == Classification.INVALID; }
}
