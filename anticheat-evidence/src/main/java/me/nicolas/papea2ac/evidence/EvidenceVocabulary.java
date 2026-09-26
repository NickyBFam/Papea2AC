package me.nicolas.papea2ac.evidence;
/**
 * The fixed Phase 9 registries (EVIDENCE_MODEL.md: "a fixed category registry; arbitrary client strings never become category/map
 * keys"). Every name mirrors the Phase 8 vocabulary of {@code anticheat-simulation} exactly, so that core's projection is a name-for-name
 * mapping and nothing is re-interpreted: a Phase 8 {@code NO_LEGAL_INPUT} stays {@code NO_LEGAL_INPUT}; it never becomes "Speed".
 * Evidence depends on {@code model} only (approved DAG), so the Phase 8 enums are mirrored here rather than imported.
 */
public final class EvidenceVocabulary {
    private EvidenceVocabulary() {}
    /** The Phase 8 classification of one transition. */
    public enum Classification { VALID, UNCERTAIN, INVALID }
    /** The Phase 8 invalid reasons (NO_LEGAL_INPUT always present on INVALID; the others are diagnostics from the exhausted set). */
    public enum InvalidCode { NO_LEGAL_INPUT, HORIZONTAL_EXCESS, VERTICAL_EXCESS, IMPOSSIBLE_ASCENT, IMPOSSIBLE_DESCENT, COLLISION_PENETRATION, UNEXPLAINED_DISPLACEMENT }
    /** The Phase 8 uncertain reasons (the blocking dimension of an UNCERTAIN result; preserved as a coverage-gap class, never as evidence). */
    public enum UncertainCode {
        WORLD_INCOMPLETE, CONTENT_NOT_RETAINED, ATTRIBUTE_UNKNOWN, EFFECT_UNKNOWN, EQUIPMENT_UNKNOWN, ENTITY_NEIGHBORHOOD_UNKNOWN, WORLD_BORDER_UNKNOWN,
        EXTERNAL_MOTION_UNRESOLVED, EXTERNAL_MOTION_EXPIRED, EXTERNAL_MOTION_OVERFLOW, TELEPORT_UNRESOLVED, DISCONTINUITY, TIMING_GAP, TIMING_INCOMPLETE, UNSUPPORTED_HORIZON, ROTATION_UNRESOLVED,
        UNSUPPORTED_FLUID, UNSUPPORTED_LOCOMOTION, UNSUPPORTED_CONTEXT, SEARCH_LIMIT, QUERY_LIMIT, CAPTURE_GAP, FRONTIER_INCOMPLETE, START_UNAVAILABLE, NUMERIC_INVALID, INTERNAL_ERROR, CLIMBABLE_CONTACT
    }
    /**
     * The physical evidence families (EVIDENCE_MODEL "DeterministicExclusion" categories for movement). Derived one-to-one from the
     * typed Phase 8 invalid reasons of a complete INVALID proof: a proof that carries only {@code NO_LEGAL_INPUT} (no axis attributable)
     * is {@code GENERAL_MOVEMENT_IMPOSSIBILITY}; nothing is guessed.
     */
    public enum Category { HORIZONTAL_EXCESS, VERTICAL_EXCESS, IMPOSSIBLE_ASCENT, IMPOSSIBLE_DESCENT, COLLISION_PENETRATION, UNEXPLAINED_DISPLACEMENT, GENERAL_MOVEMENT_IMPOSSIBILITY }
    /** Which bound the Phase 8 legal-extent numbers came from (mirrors {@code ReachBound}). */
    public enum BoundKind { NONE, SIMULATED_COMPLETE, ENVELOPE }
    /** Why an INVALID record was refused as evidence (the proof gate, EVIDENCE_MODEL: "rejects uncertain/incomplete records for punitive accumulation"). */
    public enum Rejection { NOT_INVALID, PROOF_MISSING, PROOF_NOT_EXHAUSTIVE, CANDIDATES_REMAINING, COMPLETENESS_MISSING, UNCERTAINTY_PRESENT, LEGAL_CANDIDATE_PRESENT, WITNESS_PRESENT, NO_LEGAL_INPUT_MISSING, NUMERIC_INVALID }
}
