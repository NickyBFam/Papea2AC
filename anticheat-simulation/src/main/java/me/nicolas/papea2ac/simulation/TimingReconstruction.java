package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.player.TimingEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/**
 * The Phase 7 -> Phase 6 adapter (INPUT_RECONSTRUCTION.md: timing hypotheses become reconstruction branches; MOVEMENT_SIMULATION.md
 * "One matching witness must not discard the remainder"): for a frontier of (physics state, still-pending influences) nodes and a
 * timing request template, it resolves the timing per pending-set group, reconstructs the legal inputs under EVERY hypothesis
 * (hypotheses with identical tick observations share one InputReconstructor call as branches), and returns the UNION of the
 * exact-witness continuations as the next frontier, tagged with each branch's remaining pending influences. Nothing is ranked
 * or dropped for residual size; work is capped (TimingBudget) and exhaustion is SEARCH_LIMIT with {@code complete=false}.
 * InputReconstructor is not duplicated: every physics question goes through it.
 */
public final class TimingReconstruction {
    /** One frontier node: a concrete client state right after the anchor's tick plus the influences not yet consumed on the way to it. */
    public record Node(PhysicsState state, Set<String> pendingInfluences, String label) {
        public Node { Objects.requireNonNull(state); pendingInfluences = Set.copyOf(pendingInfluences); Objects.requireNonNull(label); }
        public static Node of(PhysicsState state) { return new Node(state, Set.of(), "start"); }
    }
    public record Frontier(List<Node> nodes) {
        public Frontier { nodes = List.copyOf(nodes); }
        public static Frontier of(PhysicsState state) { return new Frontier(List.of(Node.of(state))); }
        public boolean isEmpty() { return nodes.isEmpty(); }
    }
    /** One hypothesis and what Phase 6 said under it. */
    public record Step(TimingHypothesis hypothesis, ReconstructionResult result, boolean explains) {}
    public enum Status { RESOLVED_UNIQUE, RESOLVED_AMBIGUOUS, NO_MODELLED_CANDIDATE, INCOMPLETE, WORLD_DISCONTINUITY, TELEPORT_UNRESOLVED, UNSUPPORTED_HORIZON, TIMING_GAP, SEARCH_LIMIT, EXTERNAL_MOTION_UNRESOLVED, EXPIRED, UNSUPPORTED }
    public record Work(int resolutions, int hypotheses, int deduplicated, int reconstructions, long evaluations, int frontierIn, int frontierOut, int frontierDropped) {}
    public record Result(Status status, List<TimingResolution> resolutions, List<Step> steps, Frontier next, boolean complete, Work work, List<String> notes) {
        public Result { Objects.requireNonNull(status); resolutions = List.copyOf(resolutions); steps = List.copyOf(steps); Objects.requireNonNull(next); Objects.requireNonNull(work); notes = List.copyOf(notes); }
        /** Whether the target of this step was sent from inside a client tick (false when it is a correction's application point, sent between ticks): the next request's {@code anchorInTick}. */
        public boolean targetInTick() { for (Step s : steps) if (s.explains() && s.hypothesis().teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT) return false; return true; }
        /** Hypotheses under which at least one legal input history explains the target (the union Phase 8 will interpret). */
        public List<Step> explaining() { List<Step> out = new ArrayList<>(); for (Step s : steps) if (s.explains()) out.add(s); return out; }
        public List<String> describe() {
            List<String> out = new ArrayList<>();
            out.add("timing+reconstruction " + status + " complete=" + complete + " resolutions=" + work.resolutions() + " hypotheses=" + work.hypotheses() + " explaining=" + explaining().size() + " reconstructions=" + work.reconstructions() + " evaluations=" + work.evaluations() + " frontier " + work.frontierIn() + "->" + work.frontierOut() + (work.frontierDropped() > 0 ? " (dropped " + work.frontierDropped() + ")" : ""));
            for (Step s : steps) out.add("  " + (s.explains() ? "EXPLAINS " : "no       ") + s.hypothesis().describe() + " -> " + s.result().status() + " states=" + s.result().counts().matchingStates() + " histories=" + s.result().counts().matchingHistories());
            for (String n : notes) out.add("  note: " + n);
            return out;
        }
    }

    private final TimingResolver resolver;
    private final InputReconstructor reconstructor;
    public TimingReconstruction(TimingResolver resolver, InputReconstructor reconstructor) { this.resolver = Objects.requireNonNull(resolver); this.reconstructor = Objects.requireNonNull(reconstructor); }

    public Result run(TimingRequest template, Frontier frontier, PhysicsEnvironment env, ReconstructionRequest.SearchBudget perRequest) {
        TimingBudget budget = template.budget();
        List<String> notes = new ArrayList<>(); List<TimingResolution> resolutions = new ArrayList<>(); List<Step> steps = new ArrayList<>();
        LinkedHashMap<NodeKey, Node> next = new LinkedHashMap<>();
        int hypotheses = 0, dedup = 0, reconstructions = 0, dropped = 0; long evaluations = 0; boolean complete = true, searchLimit = false;
        Status worst = null;
        // group the frontier by pending set: each group asks its own timing question
        LinkedHashMap<Set<String>, List<Node>> groups = new LinkedHashMap<>();
        for (Node n : frontier.nodes()) groups.computeIfAbsent(n.pendingInfluences(), k -> new ArrayList<>()).add(n);
        outer:
        for (Map.Entry<Set<String>, List<Node>> g : groups.entrySet()) {
            List<TimingEvent.ExternalMotionSent> pending = new ArrayList<>(); for (TimingEvent.ExternalMotionSent e : template.pendingInfluences()) if (g.getKey().contains(e.id())) pending.add(e);
            TimingResolution res = resolver.resolve(template.withPendingInfluences(pending));
            resolutions.add(res); hypotheses += res.hypotheses().size(); dedup += res.work().hypothesesDeduplicated();
            if (!res.complete()) complete = false;
            worst = worse(worst, map(res.status()));
            // hypotheses sharing one observation list run as branches of one reconstruction
            LinkedHashMap<List<TickObservation>, List<TimingHypothesis>> byTicks = new LinkedHashMap<>();
            for (TimingHypothesis h : res.hypotheses()) {
                if (h.teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT) { steps.add(teleportStep(h, template, g.getValue(), env, next, budget, notes)); continue; }
                byTicks.computeIfAbsent(h.ticks(), k -> new ArrayList<>()).add(h);
            }
            for (Map.Entry<List<TickObservation>, List<TimingHypothesis>> e : byTicks.entrySet()) for (int c0 = 0; c0 < e.getValue().size(); c0 += ReconstructionRequest.MAX_BRANCHES) {
                // at most MAX_BRANCHES (N2: eight) application scenarios per InputReconstructor call; more hypotheses = more calls, never a dropped branch
                List<TimingHypothesis> chunk = e.getValue().subList(c0, Math.min(e.getValue().size(), c0 + ReconstructionRequest.MAX_BRANCHES));
                if (reconstructions >= budget.maxReconstructions() || evaluations >= budget.maxTotalEvaluations()) { searchLimit = true; notes.add("reconstruction budget reached (" + reconstructions + " calls, " + evaluations + " evaluations): remaining hypotheses not evaluated"); break outer; }
                // leading silent ticks: the exact silent closure of the group's states (a superset fixed point, never a guess)
                List<Node> groupNodes = g.getValue(); int closureTicks = chunk.get(0).closureTicks();
                if (closureTicks > 0 && chunk.get(0).closure().branches()) {
                    // Phase 13: fenced influences may apply inside the closure - branch them tick by tick, then reconstruct each applied-set separately
                    TimingHypothesis.ClosurePlan plan = chunk.get(0).closure();
                    Closure cl = branchingClosure(groupNodes, closureTicks, plan, template, env, perRequest, budget, reconstructions, evaluations);
                    reconstructions = cl.reconstructions; evaluations = cl.evaluations;
                    if (cl.limited) { searchLimit = true; notes.add("branching silent closure hit a bound after " + cl.iterations + " tick(s): complete=false"); }
                    Set<String> planIds = new LinkedHashSet<>(); for (TimingHypothesis.ClosureInfluence ci : plan.influences()) planIds.add(ci.id());
                    LinkedHashMap<Set<String>, List<TimingHypothesis>> byApplied = new LinkedHashMap<>();
                    for (TimingHypothesis h : chunk) byApplied.computeIfAbsent(h.closure().applied(), k -> new ArrayList<>()).add(h);
                    for (Map.Entry<Set<String>, List<TimingHypothesis>> ap : byApplied.entrySet()) {
                        // the closure states in which exactly this prefix was applied (the plan ids still pending are the rest)
                        List<Node> starts = new ArrayList<>();
                        for (Node n : cl.nodes) { Set<String> stillPending = new LinkedHashSet<>(n.pendingInfluences()); stillPending.retainAll(planIds); Set<String> expect = new LinkedHashSet<>(planIds); expect.removeAll(ap.getKey()); if (stillPending.equals(expect)) starts.add(n); }
                        if (starts.isEmpty()) { for (TimingHypothesis h : ap.getValue()) steps.add(new Step(h, emptyResult(template, h), false)); continue; }
                        if (reconstructions >= budget.maxReconstructions() || evaluations >= budget.maxTotalEvaluations()) { searchLimit = true; notes.add("reconstruction budget reached after the branching closure: remaining hypotheses not evaluated"); break outer; }
                        List<ReconstructionRequest.Start> rs = new ArrayList<>(); for (Node n : starts) rs.add(new ReconstructionRequest.ConcreteStart(n.state(), n.label()));
                        List<ReconstructionRequest.ExternalMotionBranch> branches = new ArrayList<>(); for (TimingHypothesis h : ap.getValue()) branches.add(h.branch());
                        ReconstructionRequest rr = ReconstructionRequest.of(starts.get(0).state(), e.getKey(), env).withFrontier(rs).withBranches(branches).withWorldEpoch(template.worldEpoch()).withBudget(perRequest);
                        ReconstructionResult result = reconstructor.reconstruct(rr);
                        reconstructions++; evaluations += result.work().evaluations();
                        for (TimingHypothesis h : ap.getValue()) {
                            boolean explains = false;
                            for (ReconstructionResult.Candidate c : result.candidates()) {
                                if (!c.branchId().equals(h.branch().id()) || !c.exact()) continue;
                                explains = true;
                                if (next.size() < budget.maxFrontierNodes()) next.putIfAbsent(new NodeKey(c.predicted(), h.pendingInfluences()), new Node(c.predicted(), h.pendingInfluences(), h.id() + "/c" + c.id()));
                                else dropped++;
                            }
                            if (!result.complete()) complete = false;
                            steps.add(new Step(h, result, explains));
                        }
                    }
                    continue;
                }
                if (closureTicks > 0) {
                    Closure cl = silentClosure(groupNodes, closureTicks, template, env, perRequest, budget, reconstructions, evaluations);
                    reconstructions = cl.reconstructions; evaluations = cl.evaluations; groupNodes = cl.nodes;
                    if (cl.limited) { searchLimit = true; notes.add("silent closure hit a bound after " + cl.iterations + " tick(s): complete=false"); }
                    if (groupNodes.isEmpty()) { for (TimingHypothesis h : chunk) steps.add(new Step(h, emptyResult(template, h), false)); notes.add("no state survives the " + closureTicks + " silent tick(s) before the observed segment"); continue; }
                }
                List<ReconstructionRequest.Start> starts = new ArrayList<>(); for (Node n : groupNodes) starts.add(new ReconstructionRequest.ConcreteStart(n.state(), n.label()));
                List<ReconstructionRequest.ExternalMotionBranch> branches = new ArrayList<>(); for (TimingHypothesis h : chunk) branches.add(h.branch());
                ReconstructionRequest rr = ReconstructionRequest.of(groupNodes.get(0).state(), e.getKey(), env).withFrontier(starts).withBranches(branches).withWorldEpoch(template.worldEpoch()).withBudget(perRequest);
                ReconstructionResult result = reconstructor.reconstruct(rr);
                reconstructions++; evaluations += result.work().evaluations();
                for (TimingHypothesis h : chunk) {
                    boolean explains = false;
                    for (ReconstructionResult.Candidate c : result.candidates()) {
                        if (!c.branchId().equals(h.branch().id()) || !c.exact()) continue;
                        explains = true;
                        if (next.size() < budget.maxFrontierNodes()) next.putIfAbsent(new NodeKey(c.predicted(), h.pendingInfluences()), new Node(c.predicted(), h.pendingInfluences(), h.id() + "/c" + c.id()));
                        else dropped++;
                    }
                    if (!result.complete()) complete = false;
                    steps.add(new Step(h, result, explains));
                }
            }
        }
        if (dropped > 0) { complete = false; searchLimit = true; notes.add("frontier cap " + budget.maxFrontierNodes() + " reached: " + dropped + " continuation state(s) dropped, complete=false"); }
        int explaining = 0; for (Step s : steps) if (s.explains()) explaining++;
        Status status;
        if (searchLimit) status = Status.SEARCH_LIMIT;
        else if (worst != null && worst != Status.RESOLVED_UNIQUE && worst != Status.RESOLVED_AMBIGUOUS && steps.isEmpty()) status = worst;
        else if (explaining == 0) status = steps.isEmpty() ? (worst == null ? Status.UNSUPPORTED : worst) : Status.NO_MODELLED_CANDIDATE;
        else if (!complete) status = Status.INCOMPLETE;
        else status = explaining == 1 ? Status.RESOLVED_UNIQUE : Status.RESOLVED_AMBIGUOUS;
        if (status == Status.NO_MODELLED_CANDIDATE) notes.add("no enumerated timing hypothesis admits a legal input history: reconstruction data (coverage/timing/model), never a verdict");
        Work work = new Work(resolutions.size(), hypotheses, dedup, reconstructions, evaluations, frontier.nodes().size(), next.size(), dropped);
        return new Result(status, resolutions, steps, new Frontier(new ArrayList<>(next.values())), complete && !searchLimit, work, notes);
    }

    /** A correction's application point: no physics step; the continuation is the corrected state per pre-application state (absolute or relative axes as the client interprets them). */
    private Step teleportStep(TimingHypothesis h, TimingRequest template, List<Node> nodes, PhysicsEnvironment env, LinkedHashMap<NodeKey, Node> next, TimingBudget budget, List<String> notes) {
        TimingEvent.TeleportSent c = h.correction();
        List<PhysicsState> pre = new ArrayList<>();
        ReconstructionResult preResult = null;
        if (h.preTicks() == 0) { for (Node n : nodes) pre.add(n.state()); }
        else {
            // the pre-application states after preTicks silent ticks: the exact silent closure (superset fixed point), never an assumption of idleness
            Closure cl = silentClosure(nodes, h.preTicks(), template, env, ReconstructionRequest.SearchBudget.DEFAULT, budget, 0, 0);
            for (Node n : cl.nodes) pre.add(n.state());
            if (cl.limited) notes.add("pre-application silent closure of correction " + c.teleportId() + " hit a bound after " + cl.iterations + " tick(s)");
        }
        boolean rotateDelta = c.relative(TimingEvent.RELATIVE_ROTATE_DELTA);
        int matched = 0;
        for (PhysicsState s : pre) {
            double x = c.relative(TimingEvent.RELATIVE_X) ? s.position().x() + c.position().x() : c.position().x();
            double y = c.relative(TimingEvent.RELATIVE_Y) ? s.position().y() + c.position().y() : c.position().y();
            double z = c.relative(TimingEvent.RELATIVE_Z) ? s.position().z() + c.position().z() : c.position().z();
            double vx = c.relative(TimingEvent.RELATIVE_DELTA_X) ? s.velocity().x() + c.delta().x() : c.delta().x();
            double vy = c.relative(TimingEvent.RELATIVE_DELTA_Y) ? s.velocity().y() + c.delta().y() : c.delta().y();
            double vz = c.relative(TimingEvent.RELATIVE_DELTA_Z) ? s.velocity().z() + c.delta().z() : c.delta().z();
            Vector3 target = template.target().position();
            if (target.x() != x || target.y() != y || target.z() != z) continue; // the confirm's position echo must equal the applied correction
            matched++;
            PhysicsState base = s.withPosition(new Vec3d(x, y, z)).withVelocity(new Vec3d(vx, vy, vz)).withSupport(PhysicsState.Support.NONE);
            // the client keeps its ground flag across the correction; the next tick's collision recomputes it - both values are carried (sound, two nodes)
            for (boolean ground : new boolean[] { s.onGround(), !s.onGround() }) {
                if (next.size() >= budget.maxFrontierNodes()) break;
                PhysicsState st = base.withOnGround(ground);
                next.putIfAbsent(new NodeKey(st, h.pendingInfluences()), new Node(st, h.pendingInfluences(), h.id() + (ground ? "/ground" : "/air")));
            }
        }
        if (rotateDelta) notes.add("correction " + c.teleportId() + " uses ROTATE_DELTA: the delta rotation is not modelled (documented limitation), continuation velocity may be wrong");
        if (matched == 0) notes.add("the position transmitted after confirm " + c.teleportId() + " does not equal the applied correction for any pre-application state (relative axes " + c.relativeMask() + ")");
        ReconstructionResult placeholder = preResult != null ? preResult : null;
        return new Step(h, placeholder == null ? emptyResult(template, h) : placeholder, matched > 0);
    }
    private record Closure(List<Node> nodes, int iterations, int reconstructions, long evaluations, boolean limited) {}
    /**
     * Exact silent closure: iterates single WithinSendThreshold ticks (every legal input, Phase 6) from the given states, accumulating the
     * union U of reached states; stops early once an iteration adds no state to U (then every later silent tick stays inside U:
     * step(F) is monotone and F_{n+1} c U_n implies F_{n+2} c step(U_n) c U_{n+1} = U_n) or after {@code ticks} ticks. The union is a
     * SUPERSET of the exact set after {@code ticks} ticks - sound, never a guess of idleness. Bounded by the frontier cap and the
     * evaluation budget ({@code limited} = a bound cut the iteration: the caller marks the account incomplete).
     */
    private Closure silentClosure(List<Node> start, int ticks, TimingRequest template, PhysicsEnvironment env, ReconstructionRequest.SearchBudget perRequest, TimingBudget budget, int reconstructions, long evaluations) {
        Vector3 p = template.lastSentPosition();
        LinkedHashMap<NodeKey, Node> union = new LinkedHashMap<>(); for (Node n : start) union.putIfAbsent(new NodeKey(n.state(), n.pendingInfluences()), n);
        List<Node> frontier = new ArrayList<>(start); boolean limited = false; int it = 0;
        for (; it < ticks && !frontier.isEmpty(); it++) {
            if (reconstructions >= budget.maxReconstructions() || evaluations >= budget.maxTotalEvaluations()) { limited = true; break; }
            List<ReconstructionRequest.Start> starts = new ArrayList<>(); for (Node n : frontier) starts.add(new ReconstructionRequest.ConcreteStart(n.state(), n.label()));
            TickObservation silent = TickObservation.silent(p.x(), p.y(), p.z(), template.lastYaw(), template.anchorSequence() + it + 1);
            ReconstructionResult r = reconstructor.reconstruct(ReconstructionRequest.of(frontier.get(0).state(), silent, env).withFrontier(starts).withWorldEpoch(template.worldEpoch()).withBudget(perRequest));
            reconstructions++; evaluations += r.work().evaluations();
            if (!r.complete()) limited = true;
            List<Node> next = new ArrayList<>(); boolean grew = false;
            for (ReconstructionResult.Candidate c : r.candidates()) {
                if (!c.exact()) continue;
                Node n = new Node(c.predicted(), frontier.get(0).pendingInfluences(), "closure" + (it + 1) + "/c" + c.id());
                NodeKey key = new NodeKey(n.state(), n.pendingInfluences());
                if (union.size() >= budget.maxFrontierNodes() && !union.containsKey(key)) { limited = true; continue; }
                if (union.putIfAbsent(key, n) == null) grew = true;
                next.add(n);
            }
            frontier = next;
            if (!grew) { it++; break; }
        }
        return new Closure(new ArrayList<>(union.values()), it, reconstructions, evaluations, limited);
    }
    /**
     * Phase 13 branching silent closure (fenced influences inside a silent stretch): EXACT per-tick frontiers, never a union, from the first
     * silent tick to the plan's last decision tick. At closure tick c a node may apply the next not-yet-applied plan influences in wire order
     * (a prefix of them, each from its earliest tick) as branches of that tick; after tick c every node that still holds an influence whose
     * deadline is c is dropped (the fence proves it was applied by then). A node carries the plan influences it has NOT applied yet in its
     * pending set. Past the last decision tick nothing branches any more and the Phase 7 superset fixed point covers the remaining silent
     * ticks per pending class. Bounded by the frontier cap and the reconstruction/evaluation budgets ({@code limited} = cut: incomplete).
     */
    private Closure branchingClosure(List<Node> start, int ticks, TimingHypothesis.ClosurePlan plan, TimingRequest template, PhysicsEnvironment env, ReconstructionRequest.SearchBudget perRequest, TimingBudget budget, int reconstructions, long evaluations) {
        Vector3 p = template.lastSentPosition();
        List<TimingHypothesis.ClosureInfluence> inf = plan.influences();
        List<Node> frontier = new ArrayList<>();
        for (Node n : start) { Set<String> pend = new LinkedHashSet<>(n.pendingInfluences()); for (TimingHypothesis.ClosureInfluence ci : inf) pend.add(ci.id()); frontier.add(new Node(n.state(), pend, n.label())); }
        boolean limited = false; int it = 0; int last = plan.lastDecisionTick(ticks);
        for (; it < last && !frontier.isEmpty(); it++) {
            int c = it + 1;
            LinkedHashMap<NodeKey, Node> nextTick = new LinkedHashMap<>();
            // group by how many plan influences are already applied (a wire-order prefix): one reconstruction per class, its options as branches
            LinkedHashMap<Integer, List<Node>> byApplied = new LinkedHashMap<>();
            for (Node n : frontier) { int m = 0; while (m < inf.size() && !n.pendingInfluences().contains(inf.get(m).id())) m++; byApplied.computeIfAbsent(m, x -> new ArrayList<>()).add(n); }
            for (Map.Entry<Integer, List<Node>> g : byApplied.entrySet()) {
                if (reconstructions >= budget.maxReconstructions() || evaluations >= budget.maxTotalEvaluations()) { limited = true; break; }
                int m = g.getKey(), maxM = m; while (maxM < inf.size() && inf.get(maxM).earliest() <= c) maxM++;
                List<ReconstructionRequest.ExternalMotionBranch> branches = new ArrayList<>();
                for (int to = m; to <= maxM; to++) {
                    List<PhysicsEnvironment.ExternalMotion> motions = new ArrayList<>(); for (int j = m; j < to; j++) motions.add(inf.get(j).motion());
                    branches.add(new ReconstructionRequest.ExternalMotionBranch("closure" + c + "-apply" + to, List.of(motions)));
                }
                List<ReconstructionRequest.Start> starts = new ArrayList<>(); for (Node n : g.getValue()) starts.add(new ReconstructionRequest.ConcreteStart(n.state(), n.label()));
                TickObservation silent = TickObservation.silent(p.x(), p.y(), p.z(), template.lastYaw(), template.anchorSequence() + c);
                ReconstructionResult r = reconstructor.reconstruct(ReconstructionRequest.of(g.getValue().get(0).state(), silent, env).withFrontier(starts).withBranches(branches).withWorldEpoch(template.worldEpoch()).withBudget(perRequest));
                reconstructions++; evaluations += r.work().evaluations();
                if (!r.complete()) limited = true;
                Set<String> base = g.getValue().get(0).pendingInfluences();
                for (ReconstructionResult.Candidate cand : r.candidates()) {
                    if (!cand.exact()) continue;
                    int to = Integer.parseInt(cand.branchId().substring(cand.branchId().indexOf("-apply") + 6));
                    Set<String> pend = new LinkedHashSet<>(base); for (int j = m; j < to; j++) pend.remove(inf.get(j).id());
                    boolean overdue = false; for (TimingHypothesis.ClosureInfluence ci : inf) if (ci.deadline() > 0 && ci.deadline() <= c && pend.contains(ci.id())) overdue = true;
                    if (overdue) continue; // the fence proves it was applied by tick c
                    NodeKey key = new NodeKey(cand.predicted(), pend);
                    if (nextTick.size() >= budget.maxFrontierNodes() && !nextTick.containsKey(key)) { limited = true; continue; }
                    nextTick.putIfAbsent(key, new Node(cand.predicted(), pend, "closure" + c + "/c" + cand.id()));
                }
            }
            frontier = new ArrayList<>(nextTick.values());
            if (limited) { it++; break; }
        }
        if (limited || frontier.isEmpty() || it >= ticks) return new Closure(frontier, it, reconstructions, evaluations, limited);
        // nothing branches past the last decision tick: the Phase 7 superset fixed point per pending class covers the remaining silent ticks
        LinkedHashMap<Set<String>, List<Node>> classes = new LinkedHashMap<>(); for (Node n : frontier) classes.computeIfAbsent(n.pendingInfluences(), x -> new ArrayList<>()).add(n);
        List<Node> out = new ArrayList<>(); int iterations = it;
        for (List<Node> cls : classes.values()) {
            Closure rest = silentClosure(cls, ticks - it, template, env, perRequest, budget, reconstructions, evaluations);
            reconstructions = rest.reconstructions; evaluations = rest.evaluations; limited |= rest.limited; iterations = Math.max(iterations, it + rest.iterations);
            out.addAll(rest.nodes);
        }
        return new Closure(out, iterations, reconstructions, evaluations, limited);
    }
    /** A result value for a physics-free teleport step (no candidates, complete): keeps Step's shape uniform for consumers. */
    private ReconstructionResult emptyResult(TimingRequest template, TimingHypothesis h) {
        return new ReconstructionResult(ReconstructionResult.Status.NO_MODELLED_CANDIDATE, true, ReconstructionResult.Ambiguity.NONE,
            new ReconstructionResult.Coverage(true, true, true, true, true, true, true, List.of()), List.of(),
            new ReconstructionResult.Counts(0, 0, 0, 0, 0, 0, 0, 0, 0), new ReconstructionResult.Work(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new ReconstructionResult.Provenance(template.worldEpoch(), 0, 0, template.anchorSequence(), template.target().sequence(), 0, "teleport-application", "-", "-"), List.of(), List.of("correction application point: no physics step"));
    }
    private static Status map(TimingResolution.Status s) {
        return switch (s) {
            case RESOLVED_UNIQUE -> Status.RESOLVED_UNIQUE; case RESOLVED_AMBIGUOUS -> Status.RESOLVED_AMBIGUOUS; case WORLD_DISCONTINUITY, SESSION_DISCONTINUITY -> Status.WORLD_DISCONTINUITY;
            case TELEPORT_UNRESOLVED -> Status.TELEPORT_UNRESOLVED; case UNSUPPORTED_HORIZON -> Status.UNSUPPORTED_HORIZON; case SEARCH_LIMIT -> Status.SEARCH_LIMIT; case TIMING_GAP -> Status.TIMING_GAP;
            case EXTERNAL_MOTION_UNRESOLVED -> Status.EXTERNAL_MOTION_UNRESOLVED; case EXPIRED -> Status.EXPIRED; case NO_ACK_EVIDENCE, INSUFFICIENT_RTT -> Status.RESOLVED_AMBIGUOUS; case UNSUPPORTED -> Status.UNSUPPORTED;
        };
    }
    private static Status worse(Status a, Status b) { if (a == null) return b; return a.ordinal() >= b.ordinal() ? a : b; }
    private record NodeKey(PhysicsState state, Set<String> pending) {}
}
