package me.nicolas.papea2ac.simulation;
/**
 * The Phase 8 vocabulary (ARCHITECTURE.md A2: "Validation returns VALID, UNCERTAIN or INVALID plus completeness and reasons";
 * MOVEMENT_SIMULATION.md M1/M2; EVIDENCE_MODEL.md kinds LegitimateWitness / UnresolvedValidation / DeterministicExclusion).
 * Three classifications and three typed reason families, nothing else: no level, no score, no intent.
 */
public final class MovementValidation {
    private MovementValidation() {}

    /** The outcome of validating one observed transition. */
    public enum Classification {
        /** At least one complete, supported, legal hypothesis (timing + external motion + input + physics + world) explains the observation. Not proof of the pressed keys and not proof of honesty. */
        VALID,
        /** No legal explanation was found AND at least one relevant branch is incomplete or unsupported: the system cannot soundly decide. */
        UNCERTAIN,
        /** The complete supported legal hypothesis union is empty: every relevant branch was exhausted with complete knowledge and zero legal candidate remains. Says nothing about motive. */
        INVALID
    }

    /** Why a transition is VALID (which kind of explanation was found). */
    public enum ValidReason {
        /** A legal input history under a complete timing hypothesis reproduces the observation exactly. */ LEGAL_INPUT_FOUND,
        /** More than one timing hypothesis was enumerated and at least one of them admits a legal input (timing ambiguity kept, never chosen). */ LEGAL_TIMING_BRANCH_FOUND,
        /** The explaining hypothesis applies an external influence (velocity SET / explosion ADD) at an admissible tick. */ EXTERNAL_MOTION_EXPLAINS,
        /** The observation is the application point of a confirmed position correction (no physics step; ordinary movement resumes from it). */ TELEPORT_APPLICATION_POINT
    }

    /** Why a transition is UNCERTAIN: the blocking knowledge/coverage/search dimension (EVIDENCE_MODEL: UnresolvedValidation keeps its reason). */
    public enum UncertainReason {
        WORLD_INCOMPLETE, CONTENT_NOT_RETAINED, ATTRIBUTE_UNKNOWN, EFFECT_UNKNOWN, EQUIPMENT_UNKNOWN, ENTITY_NEIGHBORHOOD_UNKNOWN, WORLD_BORDER_UNKNOWN,
        EXTERNAL_MOTION_UNRESOLVED, EXTERNAL_MOTION_EXPIRED, EXTERNAL_MOTION_OVERFLOW, TELEPORT_UNRESOLVED, DISCONTINUITY, TIMING_GAP, TIMING_INCOMPLETE, UNSUPPORTED_HORIZON, ROTATION_UNRESOLVED,
        UNSUPPORTED_FLUID, UNSUPPORTED_LOCOMOTION, UNSUPPORTED_CONTEXT, SEARCH_LIMIT, QUERY_LIMIT, CAPTURE_GAP, FRONTIER_INCOMPLETE, START_UNAVAILABLE, NUMERIC_INVALID, INTERNAL_ERROR,
        /** A climbable block (ladder, vine, scaffolding, ...) within reach of the transition: the exact model is not qualified for INVALID there (P8-F2: live residuals of 5e-5..3e-2 on a legitimate ladder), so an unexplained transition is UNCERTAIN, never INVALID; an explained one stays VALID. */
        CLIMBABLE_CONTACT
    }

    /** Diagnostic reasons attached to an INVALID result (why the union is empty, in physical terms; never a motive). */
    public enum InvalidReason {
        /** Always present: no legal input history under any complete hypothesis reproduces the observation. */ NO_LEGAL_INPUT,
        /** The observed horizontal displacement exceeds the horizontal reach of every legal hypothesis (from the simulated set / its sound envelope, never a constant). */ HORIZONTAL_EXCESS,
        /** The observed vertical displacement lies outside the vertical reach of every legal hypothesis. */ VERTICAL_EXCESS,
        /** The observation rises more than any legal hypothesis allows (no jump/effect/support explains the ascent). */ IMPOSSIBLE_ASCENT,
        /** The observation descends more than any legal hypothesis allows. */ IMPOSSIBLE_DESCENT,
        /** The observed position's smallest player box definitely intersects known solid geometry that no supported legal transition can produce. */ COLLISION_PENETRATION,
        /** The union is empty but no axis of excess can be soundly attributed (the displacement lies off every legal prediction without a bounding excess). */ UNEXPLAINED_DISPLACEMENT
    }
}
