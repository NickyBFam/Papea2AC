package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import java.util.List;
import java.util.Objects;

/**
 * Everything one reconstruction needs, supplied EXPLICITLY by the caller (MOVEMENT_SIMULATION.md "SimulationInput: Prior
 * supported-state frontier; observed fields; admissible step/input domains; rotation constraints; world view(s);
 * abilities/attributes/effects; external impulse ledger; collision/numeric profile; deterministic work budget"). Immutable.
 * <ul>
 * <li>{@link #frontier} - the supported start states: concrete {@link ConcreteStart} nodes (a settled rest, or the matching
 *     candidates of the previous reconstruction, each with its bounded history label) or {@link AbstractStart} boxes (M3
 *     envelopes, e.g. a start known only through a silent tick); at least one, at most {@link SearchBudget#maxNodes};</li>
 * <li>{@link #ticks} - the caller's tick-count/alignment HYPOTHESIS (§31: 1, 2 or 3 client ticks between the start and the
 *     last observation, MOVEMENT_SIMULATION.md M2 "at most three inferred steps"), each with its observation set and yaw.
 *     Tick alignment is never inferred from arrival time here (Phase 7 owns credibility; the caller issues one request per
 *     hypothesis);</li>
 * <li>{@link #environment} - the shared immutable Phase 5 environment (one world view per request, §14); its own
 *     {@code externalMotions} are ignored in favour of {@link #branches};</li>
 * <li>{@link #worldEpoch} / {@link #discontinuity} - the epoch the observations belong to and whether the caller knows a
 *     teleport / world discontinuity lies inside the transition (§28, §29: never crossed as ordinary movement);</li>
 * <li>{@link #constraints} / {@link #assumptions} / {@link #previousSprinting} - known input dimensions (§8) and explicitly
 *     opted-in client assumptions with their stateful prerequisite (§6, §7);</li>
 * <li>{@link #witnessProposal} - the client-reported input, tried FIRST as an untrusted witness (INPUT_RECONSTRUCTION.md
 *     "try reported input as the first concrete witness"); its yaw is replaced by each tick's observed yaw; may be null;</li>
 * <li>{@link #branches} - caller-provided external-motion application scenarios (§27; Phase 7 decides timing): each branch is
 *     evaluated separately and the result keeps them apart; empty = one branch with no external motion;</li>
 * <li>{@link #budget} - the deterministic work bounds (§41);</li>
 * <li>{@link #envelopePruning} - whether the M3 abstract lane may exclude whole nodes whose sound envelope cannot reach the
 *     remaining observations (proven-safe pruning only; TOP never prunes).</li>
 * </ul>
 */
public record ReconstructionRequest(List<Start> frontier, List<TickObservation> ticks, PhysicsEnvironment environment, long worldEpoch, boolean discontinuity,
                                    InputDomain.Constraints constraints, List<InputDomain.ClientAssumption> assumptions, Boolean previousSprinting, MovementInput witnessProposal,
                                    List<ExternalMotionBranch> branches, SearchBudget budget, boolean envelopePruning, MovementObservationProfile observationProfile) {
    public static final int MAX_LABEL = 48;
    public sealed interface Start permits ConcreteStart, AbstractStart { String label(); }
    /** A concrete supported start state and the bounded provenance label of how it was reached (e.g. the previous result's candidate id). */
    public record ConcreteStart(PhysicsState state, String label) implements Start {
        public ConcreteStart { Objects.requireNonNull(state); label = clip(label); }
        public static ConcreteStart of(PhysicsState state) { return new ConcreteStart(state, "start"); }
    }
    /** An M3 envelope start: every concrete state inside the region is a supported start (§12; the concrete lane cannot enumerate it, the abstract lane propagates it). */
    public record AbstractStart(AbstractState region, String label) implements Start {
        public AbstractStart { Objects.requireNonNull(region); label = clip(label); }
    }
    /** One external-motion application scenario: the motions the caller applies before each tick ({@code motionsPerTick.get(t)}, absent ticks = none). */
    public record ExternalMotionBranch(String id, List<List<PhysicsEnvironment.ExternalMotion>> motionsPerTick) {
        public ExternalMotionBranch { id = clip(id); motionsPerTick = motionsPerTick.stream().map(List::copyOf).toList(); }
        public static final ExternalMotionBranch NONE = new ExternalMotionBranch("none", List.of());
        public List<PhysicsEnvironment.ExternalMotion> at(int tick) { return tick < motionsPerTick.size() ? motionsPerTick.get(tick) : List.of(); }
        /** A single influence applied before the first tick. */
        public static ExternalMotionBranch firstTick(String id, PhysicsEnvironment.ExternalMotion... motions) { return new ExternalMotionBranch(id, List.of(List.of(motions))); }
    }
    /**
     * Deterministic work bounds (PERFORMANCE.md "Solver | 256 nodes, 4096 transfer/step evaluations, 3 inferred steps";
     * MOVEMENT_SIMULATION.md M2 "do not raise them to fund Cartesian enumeration"). Exceeding any yields SEARCH_LIMIT with
     * {@code complete = false}; the per-step physics budget is the environment's own.
     * {@code maxHistoriesPerNode} bounds the raw-input provenance kept per merged node (the count is always exact);
     * {@code maxCandidatesReported} bounds the materialized candidate list (the counts stay exact).
     */
    public record SearchBudget(int maxNodes, int maxEvaluations, int maxTicks, int maxHistoriesPerNode, int maxCandidatesReported, int maxEnvelopeCells) {
        public SearchBudget {
            if (maxNodes <= 0 || maxEvaluations <= 0 || maxTicks <= 0 || maxTicks > 3 || maxHistoriesPerNode <= 0 || maxCandidatesReported <= 0 || maxEnvelopeCells <= 0) throw new IllegalArgumentException("budget");
        }
        /** 256 nodes / 4096 evaluations / 3 ticks (PERFORMANCE.md), 256 materialized histories per node (every single-tick node is complete: at most 144), 64 reported candidates, 4096 envelope cells per scan. */
        public static final SearchBudget DEFAULT = new SearchBudget(256, 4096, 3, 256, 64, 4096);
    }
    public ReconstructionRequest {
        frontier = List.copyOf(frontier); ticks = List.copyOf(ticks); Objects.requireNonNull(environment); Objects.requireNonNull(constraints); assumptions = List.copyOf(assumptions);
        branches = branches.isEmpty() ? List.of(ExternalMotionBranch.NONE) : List.copyOf(branches); Objects.requireNonNull(budget); Objects.requireNonNull(observationProfile);
        if (frontier.isEmpty()) throw new IllegalArgumentException("empty frontier");
        if (ticks.isEmpty()) throw new IllegalArgumentException("no ticks");
        if (branches.size() > 8) throw new IllegalArgumentException("more than 8 external-motion branches (LATENCY_COMPENSATION.md N2)");
    }
    /** The common case: one concrete start, one or more observed ticks, no constraints, no external motion, default budget, envelope pruning on. */
    public static ReconstructionRequest of(PhysicsState start, List<TickObservation> ticks, PhysicsEnvironment env) {
        return new ReconstructionRequest(List.of(ConcreteStart.of(start)), ticks, env, env.worldEpoch(), false, InputDomain.Constraints.NONE, List.of(), null, null, List.of(), SearchBudget.DEFAULT, true, MovementObservationProfile.PAPER_26_2);
    }
    public static ReconstructionRequest of(PhysicsState start, TickObservation tick, PhysicsEnvironment env) { return of(start, List.of(tick), env); }
    public ReconstructionRequest withConstraints(InputDomain.Constraints c) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, discontinuity, c, assumptions, previousSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withWitness(MovementInput w) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, w, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withBranches(List<ExternalMotionBranch> b) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, b, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withBudget(SearchBudget b) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, branches, b, envelopePruning, observationProfile); }
    public ReconstructionRequest withFrontier(List<Start> f) { return new ReconstructionRequest(f, ticks, environment, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withTicks(List<TickObservation> t) { return new ReconstructionRequest(frontier, t, environment, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withEnvironment(PhysicsEnvironment e) { return new ReconstructionRequest(frontier, ticks, e, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withWorldEpoch(long epoch) { return new ReconstructionRequest(frontier, ticks, environment, epoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withDiscontinuity(boolean d) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, d, constraints, assumptions, previousSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    public ReconstructionRequest withEnvelopePruning(boolean p) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, discontinuity, constraints, assumptions, previousSprinting, witnessProposal, branches, budget, p, observationProfile); }
    public ReconstructionRequest withAssumptions(List<InputDomain.ClientAssumption> a, Boolean prevSprinting) { return new ReconstructionRequest(frontier, ticks, environment, worldEpoch, discontinuity, constraints, a, prevSprinting, witnessProposal, branches, budget, envelopePruning, observationProfile); }
    static String clip(String s) { Objects.requireNonNull(s); return s.length() > MAX_LABEL ? s.substring(0, MAX_LABEL) : s; }
}
