package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
import me.nicolas.papea2ac.simulation.MovementValidation.InvalidReason;
import me.nicolas.papea2ac.simulation.MovementValidation.UncertainReason;
import me.nicolas.papea2ac.simulation.MovementValidation.ValidReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
/**
 * The immutable answer of {@link MovementValidator} for ONE observed transition (MOVEMENT_SIMULATION.md "SimulationResult":
 * classification, completeness certificate, witnesses or exclusion summary, error summaries, work, provenance). Invariants
 * enforced by the constructor, not by convention:
 * <ul>
 * <li>{@code INVALID} requires {@link Completeness#all()} to hold, zero legal candidates, zero uncertainty reasons and a proof;</li>
 * <li>{@code VALID} requires at least one witness;</li>
 * <li>{@code UNCERTAIN} requires at least one uncertainty reason;</li>
 * <li>every double is finite; every list is bounded.</li>
 * </ul>
 * Nothing here is a level, a score, a probability or a motive.
 */
public record MovementValidationResult(Classification classification, long startSequence, long endSequence, long worldEpoch, Completeness completeness,
                                       List<ValidReason> validReasons, List<UncertainReason> uncertainReasons, List<InvalidReason> invalidReasons,
                                       Counts counts, List<Witness> witnesses, Proof proof, Residual residual, Work work, Provenance provenance,
                                       TimingReconstruction.Frontier next, boolean nextFrontierComplete, List<String> notes) {
    public static final int MAX_WITNESSES = 4, MAX_NOTES = 16;

    /**
     * The completeness certificate: every dimension INVALID depends on (MOVEMENT_SIMULATION.md: "INVALID requires all relevant
     * flags true"). Each false is a separate, named loss - they are never merged into one flag.
     */
    public record Completeness(boolean timing, boolean reconstruction, boolean world, boolean attributes, boolean effects, boolean equipment, boolean entities, boolean border,
                               boolean externalMotion, boolean mechanicSupported, boolean search, boolean observation, boolean frontier) {
        public boolean all() { return timing && reconstruction && world && attributes && effects && equipment && entities && border && externalMotion && mechanicSupported && search && observation && frontier; }
        public static final Completeness ALL = new Completeness(true, true, true, true, true, true, true, true, true, true, true, true, true);
        public List<String> missing() {
            List<String> m = new ArrayList<>();
            if (!timing) m.add("timing"); if (!reconstruction) m.add("reconstruction"); if (!world) m.add("world"); if (!attributes) m.add("attributes"); if (!effects) m.add("effects"); if (!equipment) m.add("equipment"); if (!entities) m.add("entities"); if (!border) m.add("border");
            if (!externalMotion) m.add("externalMotion"); if (!mechanicSupported) m.add("mechanicSupported"); if (!search) m.add("search"); if (!observation) m.add("observation"); if (!frontier) m.add("frontier");
            return m;
        }
    }
    /** Exact counts of what was consumed (never truncated). */
    public record Counts(int timingResolutions, int timingHypotheses, int completeTimingHypotheses, int explainingHypotheses, int reconstructions, int legalCandidates, int legalHistories, int startNodes, int continuationNodes) {}
    /**
     * A bounded LegitimateWitness (EVIDENCE_MODEL.md): the timing hypothesis, the external-motion branch, the canonical input
     * history (one of {@code historyCount} equivalent ones - uniqueness is never claimed), the predicted state and how it met the
     * observation. Enough to replay; never the claim that these keys were pressed.
     */
    public record Witness(String hypothesisId, String branchId, int candidateId, List<MovementInput> input, int historyCount, PhysicsState predicted, TickObservation.Residual residual, List<String> appliedInfluences, boolean teleportApplication) {
        public Witness { Objects.requireNonNull(hypothesisId); Objects.requireNonNull(branchId); input = List.copyOf(input); Objects.requireNonNull(residual); appliedInfluences = List.copyOf(appliedInfluences); }
    }
    /** Nearest exact prediction (excluded) - diagnostic residual carrier (dx, dy, dz = prediction - observation reference). */
    public record NearMiss(String hypothesisId, String branchId, List<MovementInput> input, double dx, double dy, double dz, double outsideDistance) {
        public NearMiss { Objects.requireNonNull(hypothesisId); Objects.requireNonNull(branchId); input = List.copyOf(input); if (!(Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz) && Double.isFinite(outsideDistance))) throw new IllegalArgumentException("non-finite residual"); }
    }
    /**
     * The bounded exclusion proof of an INVALID result (MOVEMENT_SIMULATION.md M2 certificate): which hypotheses were exhausted,
     * the domain sizes, the observation compatibility model and the world provenance the exhaustion is valid for.
     */
    public record Proof(int hypothesesExhausted, int reconstructions, long evaluations, int rawEnumerated, int excludedExact, int excludedEnvelope, int candidatesRemaining, int uncertaintyReasons,
                        String observationProfile, String movementProfile, String collisionProfile, String timingProfile, long worldEpoch, long worldRevision, List<String> hypothesisIds) {
        public Proof { observationProfile = Objects.requireNonNull(observationProfile); movementProfile = Objects.requireNonNull(movementProfile); collisionProfile = Objects.requireNonNull(collisionProfile); timingProfile = Objects.requireNonNull(timingProfile); hypothesisIds = List.copyOf(hypothesisIds); if (hypothesisIds.size() > 32) throw new IllegalArgumentException("hypothesis ids"); }
        public static final Proof NONE = new Proof(0, 0, 0, 0, 0, 0, 0, 0, "-", "-", "-", "-", 0, 0, List.of());
    }
    /** Which bound the legal-reach numbers come from: NONE (no sound bound is claimed), SIMULATED_COMPLETE (every admitted input was simulated exactly), ENVELOPE (the sound outer bound of a single-tick request: M3 envelopes united with the exact predictions of envelope-less inputs). */
    public enum ReachBound { NONE, SIMULATED_COMPLETE, ENVELOPE }
    /**
     * Diagnostic residual summary (§37 of the brief): the observed displacement measured from the nearest chain start, the legal
     * reach of the exhausted set (its sound bound kind named), and the nearest excluded predictions. Derived only from work the
     * search already did; it never overrides the completeness logic and is never a threshold.
     */
    public record Residual(boolean present, double observedHorizontal, double observedRise, double maxLegalHorizontal, double maxLegalRise, double minLegalRise, ReachBound bound, double horizontalExcess, double verticalExcess, List<NearMiss> nearest) {
        public Residual { nearest = List.copyOf(nearest); if (nearest.size() > ReconstructionResult.Diagnostics.MAX_NEAREST) throw new IllegalArgumentException("nearest"); for (double d : new double[] { observedHorizontal, observedRise, maxLegalHorizontal, maxLegalRise, minLegalRise, horizontalExcess, verticalExcess }) if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite residual"); Objects.requireNonNull(bound); }
        public static final Residual NONE = new Residual(false, 0, 0, 0, 0, 0, ReachBound.NONE, 0, 0, List.of());
    }
    /** Bounded work: the Phase 7/6/5 work consumed plus the classifier's own bookkeeping operations (no clock: replay-deterministic). */
    public record Work(int timingResolutions, int hypotheses, int reconstructions, long evaluations, long physicsQueries, int classifierOperations) {}
    /** What the answer is valid for. */
    public record Provenance(long worldEpoch, long worldRevision, long anchorSequence, long targetSequence, String timingProfile, String movementProfile, String observationProfile, String collisionProfile) {
        public Provenance { Objects.requireNonNull(timingProfile); Objects.requireNonNull(movementProfile); Objects.requireNonNull(observationProfile); Objects.requireNonNull(collisionProfile); }
    }

    public MovementValidationResult {
        Objects.requireNonNull(classification); Objects.requireNonNull(completeness); validReasons = List.copyOf(validReasons); uncertainReasons = List.copyOf(uncertainReasons); invalidReasons = List.copyOf(invalidReasons);
        Objects.requireNonNull(counts); witnesses = List.copyOf(witnesses); Objects.requireNonNull(proof); Objects.requireNonNull(residual); Objects.requireNonNull(work); Objects.requireNonNull(provenance); Objects.requireNonNull(next); notes = List.copyOf(notes);
        if (witnesses.size() > MAX_WITNESSES) throw new IllegalArgumentException("witnesses");
        if (notes.size() > MAX_NOTES) throw new IllegalArgumentException("notes");
        switch (classification) {
            case VALID -> { if (witnesses.isEmpty() || validReasons.isEmpty()) throw new IllegalArgumentException("VALID needs a witness and a reason"); if (!invalidReasons.isEmpty()) throw new IllegalArgumentException("VALID with invalid reasons"); }
            case UNCERTAIN -> { if (uncertainReasons.isEmpty()) throw new IllegalArgumentException("UNCERTAIN needs its blocking reason"); if (!invalidReasons.isEmpty() || !validReasons.isEmpty()) throw new IllegalArgumentException("UNCERTAIN with verdict reasons"); }
            case INVALID -> {
                if (!completeness.all()) throw new IllegalArgumentException("INVALID without completeness: " + completeness.missing());
                if (counts.legalCandidates() != 0 || !witnesses.isEmpty()) throw new IllegalArgumentException("INVALID with a legal candidate");
                if (!uncertainReasons.isEmpty()) throw new IllegalArgumentException("INVALID with uncertainty");
                if (!invalidReasons.contains(InvalidReason.NO_LEGAL_INPUT) || proof == Proof.NONE || proof.hypothesesExhausted() == 0 || proof.candidatesRemaining() != 0) throw new IllegalArgumentException("INVALID needs an exhaustion proof");
            }
        }
    }
    public boolean valid() { return classification == Classification.VALID; }
    public boolean invalid() { return classification == Classification.INVALID; }
    public boolean uncertain() { return classification == Classification.UNCERTAIN; }

    /** Bounded human-readable lines (diagnostic; the same vocabulary as the record, nothing more). */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("validation " + classification + " seq=" + startSequence + "->" + endSequence + " worldEpoch=" + worldEpoch + " timingHypotheses=" + counts.timingHypotheses() + " (complete " + counts.completeTimingHypotheses() + ") explaining=" + counts.explainingHypotheses() + " reconstructions=" + counts.reconstructions() + " legal=" + counts.legalCandidates() + "/" + counts.legalHistories() + " frontier " + counts.startNodes() + "->" + counts.continuationNodes() + (nextFrontierComplete ? "" : " (incomplete)"));
        out.add("  completeness " + (completeness.all() ? "ALL" : "missing " + completeness.missing()) + (validReasons.isEmpty() ? "" : " valid=" + validReasons) + (uncertainReasons.isEmpty() ? "" : " uncertain=" + uncertainReasons) + (invalidReasons.isEmpty() ? "" : " invalid=" + invalidReasons));
        for (Witness w : witnesses) { StringBuilder sb = new StringBuilder("  witness ").append(w.hypothesisId()).append(' ').append(w.teleportApplication() ? "correction application" : InputDomain.describeHistory(w.input())).append(w.historyCount() > 1 ? " (+" + (w.historyCount() - 1) + " equivalent)" : ""); if (w.predicted() != null) sb.append(String.format(Locale.ROOT, " pos=(%.6f,%.6f,%.6f) ground=%s", w.predicted().position().x(), w.predicted().position().y(), w.predicted().position().z(), w.predicted().onGround())); if (!w.appliedInfluences().isEmpty()) sb.append(" applied=").append(w.appliedInfluences()); out.add(sb.toString()); }
        if (residual.present()) out.add(String.format(Locale.ROOT, "  residual observed h=%.6f rise=%.6f | legal h<=%.6f rise in [%.6f, %.6f] (%s) excess h=%.6f v=%.6f", residual.observedHorizontal(), residual.observedRise(), residual.maxLegalHorizontal(), residual.minLegalRise(), residual.maxLegalRise(), residual.bound(), residual.horizontalExcess(), residual.verticalExcess()));
        for (NearMiss n : residual.nearest()) out.add(String.format(Locale.ROOT, "  nearest %s %s d=(%.3e,%.3e,%.3e) outside=%.3e", n.hypothesisId(), InputDomain.describeHistory(n.input()), n.dx(), n.dy(), n.dz(), n.outsideDistance()));
        if (proof != Proof.NONE) out.add("  proof: hypotheses=" + proof.hypothesesExhausted() + " " + proof.hypothesisIds() + " reconstructions=" + proof.reconstructions() + " evaluations=" + proof.evaluations() + " enumerated=" + proof.rawEnumerated() + " excludedExact=" + proof.excludedExact() + " excludedEnvelope=" + proof.excludedEnvelope() + " remaining=" + proof.candidatesRemaining() + " model=" + proof.observationProfile() + "/" + proof.movementProfile() + "/" + proof.collisionProfile() + "/" + proof.timingProfile() + " world=" + proof.worldEpoch() + "@" + proof.worldRevision());
        for (String n : notes) out.add("  note: " + n);
        return out;
    }
}
