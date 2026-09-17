package me.nicolas.papea2ac.core;

/**
 * Fail-closed per-session guard states. An unknown or unqualified prerequisite is always represented
 * as false, never fabricated as a "green" state (see TransportCapability.certification()'s own
 * fail-closed composition, which this mirrors at the diagnostic layer).
 */
public record GuardStatus(boolean captureCapability, boolean certificationCapability, boolean effectsSuspended,
        boolean overloaded, boolean replayArmed, boolean replayDurable) {
    /** Structurally always false in Phase 1: capture-only, no production certification path exists here or anywhere else in this engine. */
    public boolean productionQualified() { return false; }
}
