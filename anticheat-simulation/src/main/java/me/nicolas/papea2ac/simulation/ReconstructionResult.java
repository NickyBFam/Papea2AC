package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.physics.PhysicsResult;
import me.nicolas.papea2ac.physics.PhysicsState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The answer of one reconstruction (MOVEMENT_SIMULATION.md "SimulationResult: matching candidates/frontier; completeness
 * certificate; error summaries; work counts; referenced world/timeline revisions"). Never a boolean and never a verdict:
 * an empty candidate list with {@code complete = true} is {@link Status#NO_MODELLED_CANDIDATE} - "no candidate in the
 * currently modelled and qualified reconstruction domain explains this transition" - and nothing more (Phase 7 timing and
 * Phase 8 policy do not exist yet; INVALID is not a value of this type).
 *
 * <p>Input ambiguity, missing-world ambiguity and timing ambiguity are kept apart (INPUT_RECONSTRUCTION.md): the
 * {@link Coverage} record says which prerequisites held, {@link Ambiguity} classifies the surviving input set, and every
 * {@link Candidate} carries its own match kind and the physics status it was computed under.
 */
public record ReconstructionResult(Status status, boolean complete, Ambiguity ambiguity, Coverage coverage, List<Candidate> candidates, Counts counts, Work work,
                                   Provenance provenance, List<BranchSummary> branches, List<String> notes, Diagnostics diagnostics) {
    /** The Phase 6 shape (no diagnostics): kept for every existing caller; {@link Diagnostics#NONE} is attached. */
    public ReconstructionResult(Status status, boolean complete, Ambiguity ambiguity, Coverage coverage, List<Candidate> candidates, Counts counts, Work work, Provenance provenance, List<BranchSummary> branches, List<String> notes) {
        this(status, complete, ambiguity, coverage, candidates, counts, work, provenance, branches, notes, Diagnostics.NONE);
    }
    /**
     * Bounded diagnostics for the consumer of an EMPTY or thin candidate set (Phase 8 residual/excess reporting; never a matching
     * input, never a verdict): the nearest exact-but-excluded predictions of the LAST tick (at most {@value #MAX_NEAREST}, by
     * distance outside the observation set) and the reach of the evaluated set relative to each node's chain start. Nothing here
     * changes a status, a count or a candidate: it is derived from work the search already did.
     */
    public record Diagnostics(List<Nearest> nearest, Extent extent) {
        public static final int MAX_NEAREST = 4;
        public static final Diagnostics NONE = new Diagnostics(List.of(), Extent.NONE);
        public Diagnostics { nearest = List.copyOf(nearest); Objects.requireNonNull(extent); if (nearest.size() > MAX_NEAREST) throw new IllegalArgumentException("nearest"); }
    }
    /** An exact prediction of the last tick that lies OUTSIDE the observation set (an excluded input), with its residual. */
    public record Nearest(String branchId, String startLabel, List<MovementInput> input, PhysicsState start, PhysicsState predicted, TickObservation.Residual residual) {
        public Nearest { Objects.requireNonNull(branchId); Objects.requireNonNull(startLabel); input = List.copyOf(input); Objects.requireNonNull(start); Objects.requireNonNull(predicted); Objects.requireNonNull(residual); }
    }
    /**
     * Displacement reach of the evaluated set, each prediction measured from its own chain start ({@code horizontal} =
     * hypot(dx, dz), {@code rise} = dy). {@code simulated*} ranges over every EXACT concrete prediction of the last tick (inside or
     * excluded); {@code allInputsSimulated} says whether that is the whole admitted domain (false when the envelope lane pruned
     * inputs without a physics step). {@code envelope*} is the sound OUTER bound of the whole admitted domain - the union of every
     * input's M3 envelope and of every exact prediction of an input that had no envelope - available for single-tick requests over a
     * concrete frontier whose envelope lane ran with nothing undecided, unexplored or bounded ({@code envelopePresent}); it bounds
     * what any legal input could reach.
     */
    public record Extent(int predictions, double simulatedMaxHorizontal, double simulatedMaxRise, double simulatedMinRise, boolean allInputsSimulated,
                        boolean envelopePresent, double envelopeMaxHorizontal, double envelopeMaxRise, double envelopeMinRise) {
        public static final Extent NONE = new Extent(0, 0, 0, 0, false, false, 0, 0, 0);
        public boolean present() { return predictions > 0 || envelopePresent; }
    }
    /** Ordered by severity (first = most severe); the result status is the most severe applicable one. ENVELOPE_ONLY: the search was complete but every surviving candidate is an M3 envelope possibility (no concrete witness exists in the request - an abstract start). */
    public enum Status {
        NUMERIC_INVALID, WORLD_EPOCH_MISMATCH, DISCONTINUITY, UNAVAILABLE_WORLD, TIMING_UNRESOLVED, ROTATION_UNRESOLVED, SEARCH_LIMIT, QUERY_LIMIT,
        UNSUPPORTED_CONTEXT, UNSUPPORTED_FLUID, UNRESOLVED_EXTERNAL_MOTION, UNKNOWN_ATTRIBUTE, UNKNOWN_EFFECT, UNKNOWN_EQUIPMENT, UNKNOWN_ENTITY_NEIGHBORHOOD, UNKNOWN_WORLD_BORDER, INCOMPLETE_WORLD,
        NO_MODELLED_CANDIDATE, ENVELOPE_ONLY, EXACT_AMBIGUOUS, EXACT_UNIQUE;
        /** The statuses under which the candidate set is exhaustive over the modelled domain. */
        public boolean completeSearch() { return this == NO_MODELLED_CANDIDATE || this == ENVELOPE_ONLY || this == EXACT_AMBIGUOUS || this == EXACT_UNIQUE; }
        static Status of(PhysicsResult.Status s) {
            return switch (s) {
                case NUMERIC_INVALID -> NUMERIC_INVALID; case COORDINATE_LIMIT -> UNSUPPORTED_CONTEXT; case UNAVAILABLE_WORLD -> UNAVAILABLE_WORLD; case QUERY_LIMIT -> QUERY_LIMIT;
                case UNSUPPORTED_MODE, UNSUPPORTED_POSE, UNSUPPORTED_BORDER, UNSUPPORTED_BLOCK_BEHAVIOUR -> UNSUPPORTED_CONTEXT; case UNSUPPORTED_FLUID -> UNSUPPORTED_FLUID;
                case UNRESOLVED_EXTERNAL_MOTION -> UNRESOLVED_EXTERNAL_MOTION; case UNKNOWN_ATTRIBUTE -> UNKNOWN_ATTRIBUTE; case UNKNOWN_EFFECT -> UNKNOWN_EFFECT; case UNKNOWN_EQUIPMENT -> UNKNOWN_EQUIPMENT;
                case UNKNOWN_ENTITY_NEIGHBORHOOD -> UNKNOWN_ENTITY_NEIGHBORHOOD; case UNKNOWN_WORLD_BORDER -> UNKNOWN_WORLD_BORDER; case INCOMPLETE_WORLD -> INCOMPLETE_WORLD; case EXACT -> EXACT_UNIQUE;
            };
        }
    }
    /** Classification of the surviving input set (§24): never forced to uniqueness. */
    public enum Ambiguity {
        /** No candidate survived (see the status for whether the search was complete). */ NONE,
        /** Exactly one raw input history explains the observation. */ UNIQUE,
        /** Several raw input histories, all producing the SAME predicted state (bit-identical; e.g. idle with/without item use, jump key in mid-air): physically one outcome, raw keys indistinguishable. */ EQUIVALENT_INPUTS,
        /** Several surviving predicted states: different physics, all inside the observation set. */ DISTINCT_STATES
    }
    /** How a candidate relates to the observation. Envelope containment is never a witness (M3: "O intersects gamma(A) is possible, not proven valid"). */
    public enum Match { EXACT_WITNESS, POSSIBLE_INCOMPLETE, ENVELOPE_POSSIBLE }
    /**
     * One surviving reconstruction node (§22 provenance): the raw input history (per tick) that reached it - several histories
     * merge into one node when they produce a bit-identical complete simulation state (§21) - the predicted state, its match
     * kind, the residual against the last observation, the physics status/reasons of the last step, and the branch.
     * {@code histories} is bounded by the budget; {@code historyCount} is always the exact number.
     */
    public record Candidate(int id, String branchId, String startLabel, List<List<MovementInput>> histories, int historyCount, long[][] tickMasks, PhysicsState predicted, AbstractState envelope, Match match,
                            TickObservation.Residual residual, PhysicsResult.Status physicsStatus, List<PhysicsResult.Reason> physicsReasons, boolean witnessProposal) {
        public Candidate {
            branchId = Objects.requireNonNull(branchId); startLabel = Objects.requireNonNull(startLabel); histories = histories.stream().map(List::copyOf).toList(); physicsReasons = List.copyOf(physicsReasons);
            tickMasks = tickMasks.clone(); for (int i = 0; i < tickMasks.length; i++) tickMasks[i] = tickMasks[i].clone();
            if ((predicted == null) == (envelope == null)) throw new IllegalArgumentException("exactly one of predicted state / envelope");
            if (historyCount < histories.size() || histories.isEmpty()) throw new IllegalArgumentException("history count");
        }
        /** The canonical (first in enumeration order) input history. */
        public List<MovementInput> input() { return histories.get(0); }
        public boolean exact() { return match == Match.EXACT_WITNESS; }
        /** Whether every raw history of this node is materialized in {@link #histories} (then {@link #containsHistory} is exact). */
        public boolean historiesComplete() { return historyCount == histories.size(); }
        /**
         * Whether the given raw history (keys only, yaw ignored) is one of this node's histories. Exact when the node's histories are
         * all materialized (always the case for a single tick: at most 144 raw inputs); for a truncated multi-tick node the answer
         * falls back to the per-tick raw-ordinal masks, which are exact per tick but a product SUPERSET across ticks - "may be
         * contained". {@link #historiesComplete()} tells the two apart.
         */
        public boolean containsHistory(List<MovementInput> keys) {
            for (List<MovementInput> h : histories) if (sameKeys(h, keys)) return true;
            if (historiesComplete() || keys.size() != tickMasks.length) return false;
            for (int t = 0; t < keys.size(); t++) { int o = InputDomain.ordinal(keys.get(t)); if ((tickMasks[t][o >>> 6] & (1L << (o & 63))) == 0) return false; }
            return true;
        }
        /** The raw ordinals seen at tick {@code t} across every merged history (exact per tick). */
        public List<Integer> ordinalsAt(int t) { List<Integer> out = new ArrayList<>(); for (int o = 0; o < InputDomain.RAW_SIZE; o++) if ((tickMasks[t][o >>> 6] & (1L << (o & 63))) != 0) out.add(o); return out; }
        static boolean sameKeys(List<MovementInput> a, List<MovementInput> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) { MovementInput x = a.get(i), y = b.get(i); if (x.forward() != y.forward() || x.strafe() != y.strafe() || x.jump() != y.jump() || x.shift() != y.shift() || x.sprinting() != y.sprinting() || x.usingItem() != y.usingItem()) return false; }
            return true;
        }
    }
    /** Which prerequisites of a complete reconstruction held (each false is a separate, named coverage loss - never merged into one flag). */
    public record Coverage(boolean epochAndContinuity, boolean timingResolved, boolean rotationResolved, boolean environmentKnown, boolean physicsSupported, boolean worldComplete, boolean searchComplete, List<String> reasons) {
        public Coverage { reasons = List.copyOf(reasons); }
        public boolean all() { return epochAndContinuity && timingResolved && rotationResolved && environmentKnown && physicsSupported && worldComplete && searchComplete; }
    }
    /** Exact counts (never truncated). */
    public record Counts(int rawCandidatesPerTick, int rawEnumerated, int classesSimulated, int excludedExact, int excludedEnvelope, int undecided, int matchingStates, int matchingHistories, int witnessHistoriesUnexplored) {}
    /** Bounded work performed (deterministic for identical requests). */
    public record Work(int evaluations, int maxLiveNodes, int mergedHistories, int droppedHistories, long physicsQueries, long physicsCells, long physicsInsideCells, long physicsEntityOperations, int envelopeTransfers, int envelopeCellsScanned) {}
    /** What the answer is valid for (§22). */
    public record Provenance(long worldEpoch, long worldRevision, long worldSequence, long firstSequence, long lastSequence, int ticks, String movementProfile, String observationProfile, String collisionProfile) {}
    /** Per external-motion branch: how many candidates survived under it (§27: never chosen silently). */
    public record BranchSummary(String id, int matchingStates, int matchingHistories, boolean complete) {}

    public ReconstructionResult {
        Objects.requireNonNull(status); Objects.requireNonNull(ambiguity); Objects.requireNonNull(coverage); candidates = List.copyOf(candidates); Objects.requireNonNull(counts); Objects.requireNonNull(work); Objects.requireNonNull(provenance); branches = List.copyOf(branches); notes = List.copyOf(notes); Objects.requireNonNull(diagnostics);
        if (complete != status.completeSearch()) throw new IllegalArgumentException("completeness must agree with the status");
        if (status == Status.EXACT_UNIQUE && candidates.size() != 1) throw new IllegalArgumentException("EXACT_UNIQUE needs exactly one candidate");
        if (status == Status.NO_MODELLED_CANDIDATE && !candidates.isEmpty()) throw new IllegalArgumentException("NO_MODELLED_CANDIDATE with candidates");
        if (status.completeSearch()) for (Candidate c : candidates) if (c.match() != Match.EXACT_WITNESS && c.match() != Match.ENVELOPE_POSSIBLE) throw new IllegalArgumentException("complete search with an undecided candidate");
    }
    /** The exact-witness candidates whose predicted state can seed the next reconstruction (frontier continuation); envelope-only and undecided candidates are not supported starts. */
    public List<ReconstructionRequest.Start> frontier() {
        List<ReconstructionRequest.Start> out = new ArrayList<>();
        for (Candidate c : candidates) if (c.exact()) out.add(new ReconstructionRequest.ConcreteStart(c.predicted(), "c" + c.id()));
        return out;
    }
    /** Whether the raw key history is among the surviving candidates (exact witnesses or undecided/envelope possibilities). */
    public boolean admits(List<MovementInput> keys) { for (Candidate c : candidates) if (c.containsHistory(keys)) return true; return false; }
    /** Whether the raw key history is an EXACT witness. */
    public boolean witnesses(List<MovementInput> keys) { for (Candidate c : candidates) if (c.exact() && c.containsHistory(keys)) return true; return false; }
    public int matchingStates() { return counts.matchingStates(); }
    /** Bounded diagnostic lines (never more than ~{@code maxCandidates + 6} lines; never the words valid/invalid/cheating). */
    public List<String> describe(int maxCandidates) {
        List<String> out = new ArrayList<>();
        out.add("reconstruction " + status + " complete=" + complete + " ambiguity=" + ambiguity + " ticks=" + provenance.ticks() + " seq=" + provenance.firstSequence() + ".." + provenance.lastSequence() + " worldEpoch=" + provenance.worldEpoch() + " rev=" + provenance.worldRevision());
        out.add("counts: raw/tick=" + counts.rawCandidatesPerTick() + " enumerated=" + counts.rawEnumerated() + " simulated=" + counts.classesSimulated() + " excludedExact=" + counts.excludedExact() + " excludedEnvelope=" + counts.excludedEnvelope() + " undecided=" + counts.undecided() + " matchingStates=" + counts.matchingStates() + " matchingHistories=" + counts.matchingHistories());
        out.add("work: evaluations=" + work.evaluations() + " maxLiveNodes=" + work.maxLiveNodes() + " merged=" + work.mergedHistories() + " physics q/c/i/e=" + work.physicsQueries() + "/" + work.physicsCells() + "/" + work.physicsInsideCells() + "/" + work.physicsEntityOperations() + " envelopeTransfers=" + work.envelopeTransfers());
        if (!coverage.all()) out.add("coverage: epoch=" + coverage.epochAndContinuity() + " timing=" + coverage.timingResolved() + " rotation=" + coverage.rotationResolved() + " environment=" + coverage.environmentKnown() + " physics=" + coverage.physicsSupported() + " world=" + coverage.worldComplete() + " search=" + coverage.searchComplete() + " " + coverage.reasons());
        if (branches.size() > 1) for (BranchSummary b : branches) out.add("branch " + b.id() + ": states=" + b.matchingStates() + " histories=" + b.matchingHistories() + " complete=" + b.complete());
        int n = 0;
        for (Candidate c : candidates) {
            if (n++ == maxCandidates) { out.add("  ... " + (candidates.size() - maxCandidates) + " more candidates"); break; }
            StringBuilder sb = new StringBuilder("  c").append(c.id()).append(' ').append(c.match()).append(' ');
            for (int t = 0; t < c.input().size(); t++) sb.append(t > 0 ? ">" : "").append(InputDomain.describe(c.input().get(t)));
            if (c.historyCount() > 1) sb.append(" (+").append(c.historyCount() - 1).append(" equivalent)");
            if (c.predicted() != null) sb.append(String.format(Locale.ROOT, " pos=(%.6f,%.6f,%.6f) vel=(%.5f,%.5f,%.5f) ground=%s", c.predicted().position().x(), c.predicted().position().y(), c.predicted().position().z(), c.predicted().velocity().x(), c.predicted().velocity().y(), c.predicted().velocity().z(), c.predicted().onGround()));
            else sb.append(" envelope=").append(c.envelope().summary());
            if (c.residual() != null) sb.append(String.format(Locale.ROOT, " d=(%.2e,%.2e,%.2e)%s", c.residual().dx(), c.residual().dy(), c.residual().dz(), c.residual().groundClaimPresent() ? (c.residual().groundClaimAgrees() ? " groundClaim=agrees" : " groundClaim=differs") : ""));
            if (c.physicsStatus() != PhysicsResult.Status.EXACT) sb.append(' ').append(c.physicsStatus());
            if (c.witnessProposal()) sb.append(" [reported input]");
            out.add(sb.toString());
        }
        for (String n2 : notes) out.add("note: " + n2);
        return out;
    }
}
