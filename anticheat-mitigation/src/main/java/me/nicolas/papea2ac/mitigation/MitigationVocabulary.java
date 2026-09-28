package me.nicolas.papea2ac.mitigation;
/**
 * The fixed Phase 10 registries (MITIGATION.md vocabulary where it names things; typed reasons everywhere else - never a boolean alone).
 * Nothing here is a verdict about a player: these name what a mitigation decision observed and why an action was or was not permitted.
 */
public final class MitigationVocabulary {
    private MitigationVocabulary() {}

    /** The outcome class of one evaluation. {@code PROPOSED} is the only class that yields an intent; everything else changes nothing in the game. */
    public enum DecisionOutcome {
        /** Nothing to decide (no positive evidence in the current chain). */
        NONE,
        /** Evidence exists but does not meet the versioned policy (e.g. fewer than the required independent groups). */
        NOT_ELIGIBLE,
        /** The evidence policy is met but a safety precondition forbids any correction (typed reason). */
        SUPPRESSED,
        /** The policy is met but no admissible correction exists within the operational bounds (MITIGATION.md: anchor age / distance); recovery, never punishment. */
        RESYNC_REQUIRED,
        /** An intent was proposed; its later execution outcome is recorded separately. */
        PROPOSED
    }

    /** Why a decision did not propose, or why an intent ended where it did. One reason per decision; the first failing precondition in the documented order. */
    public enum Reason {
        // evidence policy
        POLICY_MET, NO_EVIDENCE, INSUFFICIENT_EVIDENCE, CORRELATION_INCOMPLETE, CORRELATION_UNRESOLVED, EVIDENCE_EXPIRED,
        // identity / epochs
        SESSION_MISMATCH, WORLD_EPOCH_MISMATCH, TRANSPORT_UNHEALTHY,
        // current-state safety (MITIGATION.md "Main thread checks ... pending teleports"; P9-F2 / P9-F5 / Phase 7)
        UNRESOLVED_TELEPORT, UNRESOLVED_EXTERNAL_MOTION, BLOCK_DISPUTE, ENTITY_STATE_INCOMPLETE, UNSUPPORTED_LOCOMOTION, WORLD_INCOMPLETE,
        // anchor / target
        NO_SAFE_ANCHOR, ANCHOR_TOO_OLD, ANCHOR_TOO_FAR, COLLISION_OBSTRUCTED, COLLISION_UNKNOWN,
        // intent lifecycle / rate limits
        ACTIVE_CORRECTION, RECOVERY, COOLDOWN, SESSION_DISABLED, DUPLICATE_ACTION,
        // effect admission (G1/G2)
        MODE_OBSERVE_ONLY, MODE_DISABLED, GLOBAL_GUARD, CAPTURE_UNAVAILABLE, CAPTURE_DEADLINE_MISS, DEADLINE_EXPIRED,
        PLAYER_OFFLINE, WORLD_CHANGED, DEATH_OR_RESPAWN, VEHICLE_OR_GLIDING, DESTINATION_UNLOADED, PLATFORM_CANCELLED, SEND_FAILED, CONFIRM_TIMEOUT,
        SUPERSEDED_BY_FOREIGN_TELEPORT, SESSION_ENDED, QUEUE_SATURATED, SHUTDOWN,
        INTERNAL_ERROR
    }

    /** MITIGATION.md state machine: IDLE -> PROPOSED -> WAITING_CAPTURE -> QUEUED -> EXECUTING -> SENT_AWAITING_CONFIRM -> CONFIRMED -> RECOVERY -> IDLE. */
    public enum IntentState { IDLE, PROPOSED, WAITING_CAPTURE, QUEUED, EXECUTING, SENT_AWAITING_CONFIRM, CONFIRMED, RECOVERY }

    /** The single terminal execution outcome every intent reaches (MITIGATION.md: "Every intent must reach exactly one terminal execution outcome"). */
    public enum Terminal {
        /** The correction was sent, confirmed by the client's teleport confirm, and a coherent baseline followed. */
        RECOVERED,
        /** Confirmed, but no coherent baseline was re-established before the recovery horizon; the chain resynchronises from the next anchor. */
        CONFIRMED_UNRECOVERED,
        EXPIRED, REJECTED, SUPERSEDED, RESYNC_REQUIRED
    }

    public enum ActionType { SETBACK }

    /** Why a state was admitted as a safe anchor (provenance; the only admitted source in Phase 10). */
    public enum AnchorSource { VALID_CONCRETE_GROUNDED }
}
