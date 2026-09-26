package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import java.util.Objects;
/**
 * One transition to validate (§31 of the Phase 8 brief): the Phase 7 timing request for the interval (events, anchor, target,
 * pending influences, corrections, RTT window, profile, budget), the supported-state frontier the interval starts from (every
 * concrete client state the chain still admits after the anchor's tick, with each state's own remaining pending influences),
 * the physics environment the interval is simulated in, Phase 6's per-request budget, and two chain facts the caller owns:
 * <ul>
 * <li>{@code frontierComplete} - whether the frontier is known to contain every legal continuation of the history so far
 *     (false as soon as any earlier step of the chain was incomplete or the start was not a proven fixed point: an INVALID
 *     built on a possibly-missing state would be unsound, so it is forbidden until the chain re-anchors);</li>
 * <li>{@code contentRetained} - whether the timeline/world history the request rests on was fully retained (a CONTENT_NOT_RETAINED
 *     world region or an evicted history is a named uncertainty, never proof).</li>
 * </ul>
 * Every field is caller-supplied; nothing is read from a live server or a clock.
 */
public record MovementValidationRequest(TimingRequest timing, TimingReconstruction.Frontier frontier, PhysicsEnvironment environment, ReconstructionRequest.SearchBudget reconstructionBudget, boolean frontierComplete, boolean contentRetained, boolean worldDisputed) {
    /** The Phase 8 field set: the world of the transition is not in dispute (P9-F2). */
    public MovementValidationRequest(TimingRequest timing, TimingReconstruction.Frontier frontier, PhysicsEnvironment environment, ReconstructionRequest.SearchBudget reconstructionBudget, boolean frontierComplete, boolean contentRetained) {
        this(timing, frontier, environment, reconstructionBudget, frontierComplete, contentRetained, false);
    }
    public MovementValidationRequest { Objects.requireNonNull(timing); Objects.requireNonNull(frontier); Objects.requireNonNull(environment); Objects.requireNonNull(reconstructionBudget); }
    public static MovementValidationRequest of(TimingRequest timing, TimingReconstruction.Frontier frontier, PhysicsEnvironment environment) { return new MovementValidationRequest(timing, frontier, environment, ReconstructionRequest.SearchBudget.DEFAULT, true, true); }
    public MovementValidationRequest withFrontierComplete(boolean c) { return new MovementValidationRequest(timing, frontier, environment, reconstructionBudget, c, contentRetained, worldDisputed); }
    public MovementValidationRequest withContentRetained(boolean c) { return new MovementValidationRequest(timing, frontier, environment, reconstructionBudget, frontierComplete, c, worldDisputed); }
    /** P9-F2: whether a cell within the transition's reach is in dispute between the client and this observer (see {@code BlockDisputeLedger}). */
    public MovementValidationRequest withWorldDisputed(boolean d) { return new MovementValidationRequest(timing, frontier, environment, reconstructionBudget, frontierComplete, contentRetained, d); }
    public MovementValidationRequest withBudget(ReconstructionRequest.SearchBudget b) { return new MovementValidationRequest(timing, frontier, environment, b, frontierComplete, contentRetained, worldDisputed); }
}
