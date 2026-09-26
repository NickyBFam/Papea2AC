package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.world.BlockLookup;
import java.util.*;

/**
 * Phase 6 input reconstruction: "given an observed player-state transition and the currently known simulation context,
 * which LEGAL client input candidates could explain that transition?" (INPUT_RECONSTRUCTION.md I1: inputs are hypotheses;
 * MOVEMENT_SIMULATION.md M1/M2: a concrete witness lane plus a separate sound envelope lane, under the fixed solver limits).
 *
 * <p>Concrete lane, per tick of the caller's hypothesis: every admitted raw input (canonical order, the reported input
 * first) is forward-simulated by the frozen Phase 5 simulator from every live node; a prediction that is EXACT and inside
 * the observation set becomes a node of the next tick, a prediction that is EXACT and outside it is EXCLUDED (an exact
 * exclusion: under the modelled context that input cannot have produced the observation), and a prediction the physics
 * could not make exact (unknown cell, unknown attribute, budget, unsupported mechanic) is UNDECIDED - it is never dropped
 * and never counted as excluded, and the result is not complete. Nodes with a bit-identical complete simulation state are
 * merged, keeping every raw input history up to the budget and the exact count always (§21).
 *
 * <p>Envelope lane (M3, {@link AbstractTransfer}): before a node is expanded, its sound outer envelope over ALL admitted
 * inputs is propagated through the remaining ticks; a node whose envelope cannot reach an observation set is excluded as a
 * whole (proven exclusion, counted separately); a TOP envelope never excludes. Abstract start regions are propagated per
 * input and yield ENVELOPE_POSSIBLE candidates only - never witnesses.
 *
 * <p>The engine never produces VALID / INVALID, never ranks by residual, never drops a legal state to save work: when a
 * budget is exhausted the result says SEARCH_LIMIT with {@code complete = false} and keeps what was found. It holds no
 * mutable state between requests; one instance serves any number of threads.
 */
public final class InputReconstructor {
    private final PhysicsSimulator simulator;
    public InputReconstructor(PhysicsSimulator simulator) { this.simulator = Objects.requireNonNull(simulator); }
    public PhysicsSimulator simulator() { return simulator; }

    /** A live search node: one exact predicted state (or an abstract region) with every raw input history that reached it. */
    private static final class Node {
        final PhysicsState state; final AbstractState region; final String startLabel; final boolean uncertain, inside; final PhysicsResult.Status physicsStatus; final List<PhysicsResult.Reason> reasons; final TickObservation.Residual residual;
        final List<List<MovementInput>> histories = new ArrayList<>(); int historyCount; boolean witness; long[][] masks = new long[0][];
        /** The concrete chain start this node descends from (diagnostic reach reference; null for abstract starts). */
        PhysicsState start;
        Node(PhysicsState state, AbstractState region, String startLabel, boolean uncertain, boolean inside, PhysicsResult.Status physicsStatus, List<PhysicsResult.Reason> reasons, TickObservation.Residual residual) {
            this.state = state; this.region = region; this.startLabel = startLabel; this.uncertain = uncertain; this.inside = inside; this.physicsStatus = physicsStatus; this.reasons = reasons; this.residual = residual;
        }
        Boolean lastSprinting() { Boolean v = null; for (List<MovementInput> h : histories) { if (h.isEmpty()) return null; boolean s = h.get(h.size() - 1).sprinting(); if (v == null) v = s; else if (v != s) return null; } return v; }
        boolean plausible() { return inside; }
    }
    /** Merge key: the complete simulated state plus the uncertainty flag (an uncertain node never merges with an exact one). */
    private record Key(PhysicsState state, boolean uncertain) {}
    /** Mutable accounting for one request. */
    private static final class Acc {
        int evaluations, maxLive, merged, dropped, excludedExact, excludedEnvelope, undecided, enumerated, simulated, unexplored, envelopeTransfers, envelopeCells;
        long q, c, i, e; boolean searchLimit, worldIncomplete, physicsUnsupported, environmentUnknown;
        ReconstructionResult.Status worstPhysics = ReconstructionResult.Status.EXACT_UNIQUE; final List<String> reasons = new ArrayList<>(), notes = new ArrayList<>();
        // Phase 8 diagnostics (derived from work already done): nearest excluded exact predictions of the last tick, reach of the evaluated set
        final List<ReconstructionResult.Nearest> nearest = new ArrayList<>();
        int reachPredictions, uncertainNodes, abstractNodes; double simMaxH, simMaxRise = Double.NEGATIVE_INFINITY, simMinRise = Double.POSITIVE_INFINITY; boolean envelopeReachUsed; double envMaxH, envMaxRise = Double.NEGATIVE_INFINITY, envMinRise = Double.POSITIVE_INFINITY;
        void reach(PhysicsState start, PhysicsState predicted) {
            if (start == null) return;
            double dx = predicted.position().x() - start.position().x(), dz = predicted.position().z() - start.position().z(), dy = predicted.position().y() - start.position().y();
            double h = Math.sqrt(dx * dx + dz * dz); reachPredictions++; if (h > simMaxH) simMaxH = h; if (dy > simMaxRise) simMaxRise = dy; if (dy < simMinRise) simMinRise = dy;
        }
        void envelopeReach(PhysicsState start, List<AbstractState.Box> boxes) {
            if (start == null || boxes == null) return; // no envelope for this input: it is simulated below and counted by reach()
            for (AbstractState.Box b : boxes) {
                double sx = start.position().x(), sz = start.position().z(), sy = start.position().y();
                double fx = Math.max(Math.abs(b.x().lo() - sx), Math.abs(b.x().hi() - sx)), fz = Math.max(Math.abs(b.z().lo() - sz), Math.abs(b.z().hi() - sz));
                double h = Math.sqrt(fx * fx + fz * fz); if (h > envMaxH) envMaxH = h;
                if (b.y().hi() - sy > envMaxRise) envMaxRise = b.y().hi() - sy; if (b.y().lo() - sy < envMinRise) envMinRise = b.y().lo() - sy;
                envelopeReachUsed = true;
            }
        }
        void near(String branch, Node parent, MovementInput in, PhysicsState predicted, TickObservation.Residual residual) {
            if (parent.start == null || !residual.finite()) return;
            List<MovementInput> h = new ArrayList<>(parent.histories.isEmpty() ? List.of() : parent.histories.get(0)); h.add(in);
            ReconstructionResult.Nearest n = new ReconstructionResult.Nearest(branch, parent.startLabel, h, parent.start, predicted, residual);
            int at = nearest.size(); while (at > 0 && nearest.get(at - 1).residual().outsideDistance() > residual.outsideDistance()) at--;
            if (at < ReconstructionResult.Diagnostics.MAX_NEAREST) { nearest.add(at, n); if (nearest.size() > ReconstructionResult.Diagnostics.MAX_NEAREST) nearest.remove(nearest.size() - 1); }
        }
        ReconstructionResult.Diagnostics diagnostics(int ticks, boolean envelopeLane) {
            // outer bound of a single-tick request: every (node, input) of the concrete frontier was either covered by its sound M3 envelope or simulated exactly
            // (no abstract or uncertain start, no undecided physics, no search bound, nothing unexplored) - the bound is the union of both extents
            boolean outer = ticks == 1 && envelopeLane && uncertainNodes == 0 && abstractNodes == 0 && undecided == 0 && !searchLimit && unexplored == 0 && (envelopeReachUsed || reachPredictions > 0);
            double oMaxH = Math.max(envelopeReachUsed ? envMaxH : 0, reachPredictions > 0 ? simMaxH : 0);
            double oMaxRise = Math.max(envelopeReachUsed ? envMaxRise : Double.NEGATIVE_INFINITY, reachPredictions > 0 ? simMaxRise : Double.NEGATIVE_INFINITY);
            double oMinRise = Math.min(envelopeReachUsed ? envMinRise : Double.POSITIVE_INFINITY, reachPredictions > 0 ? simMinRise : Double.POSITIVE_INFINITY);
            ReconstructionResult.Extent r = new ReconstructionResult.Extent(reachPredictions, simMaxH, reachPredictions > 0 ? simMaxRise : 0, reachPredictions > 0 ? simMinRise : 0, excludedEnvelope == 0 && !searchLimit && unexplored == 0 && undecided == 0 && uncertainNodes == 0 && abstractNodes == 0,
                outer, outer ? oMaxH : 0, outer ? oMaxRise : 0, outer ? oMinRise : 0);
            return new ReconstructionResult.Diagnostics(nearest, r);
        }
        void physics(PhysicsResult r) {
            q += r.work().queries(); c += r.work().cells(); i += r.work().insideCells(); e += r.work().entityOperations();
            if (r.exact()) return;
            ReconstructionResult.Status s = ReconstructionResult.Status.of(r.status());
            if (s.ordinal() < worstPhysics.ordinal()) worstPhysics = s;
            switch (r.status()) {
                case INCOMPLETE_WORLD, UNAVAILABLE_WORLD -> worldIncomplete = true;
                case UNKNOWN_ATTRIBUTE, UNKNOWN_EFFECT, UNKNOWN_EQUIPMENT, UNKNOWN_ENTITY_NEIGHBORHOOD, UNKNOWN_WORLD_BORDER, UNRESOLVED_EXTERNAL_MOTION -> environmentUnknown = true;
                case QUERY_LIMIT -> searchLimit = true;
                default -> physicsUnsupported = true;
            }
            for (PhysicsResult.Reason re : r.reasons()) reason(re.code() + ":" + re.detail());
        }
        void reason(String s) { if (reasons.size() < 32 && !reasons.contains(s)) reasons.add(s); }
        void note(String s) { if (notes.size() < 16 && !notes.contains(s)) notes.add(s); }
    }

    public ReconstructionResult reconstruct(ReconstructionRequest req) {
        Objects.requireNonNull(req);
        PhysicsEnvironment env = req.environment();
        if (env.profile() != simulator.profile()) throw new IllegalArgumentException("environment profile mismatch");
        ReconstructionResult.Provenance prov = new ReconstructionResult.Provenance(env.view().worldEpoch(), env.view().revision(), env.view().sequence(), req.ticks().get(0).sequence(), req.ticks().get(req.ticks().size() - 1).sequence(), req.ticks().size(), simulator.profile().id(), req.observationProfile().id(), simulator.profile().collision().id());
        Acc acc = new Acc();
        // ---- gates (an explicit status, no search)
        for (ReconstructionRequest.Start s : req.frontier()) if (s instanceof ReconstructionRequest.ConcreteStart c && !c.state().finite()) return gate(ReconstructionResult.Status.NUMERIC_INVALID, "start state carries NaN/Infinity", req, prov);
        if (req.discontinuity()) return gate(ReconstructionResult.Status.DISCONTINUITY, "a teleport/world discontinuity lies inside the transition: not reconstructed as ordinary movement", req, prov);
        if (env.view().worldEpoch() != req.worldEpoch() || env.worldEpoch() != req.worldEpoch()) return gate(ReconstructionResult.Status.WORLD_EPOCH_MISMATCH, "observation epoch " + req.worldEpoch() + " != view epoch " + env.view().worldEpoch() + " / environment epoch " + env.worldEpoch(), req, prov);
        if (env.view().availability() != BlockLookup.Status.KNOWN) return gate(ReconstructionResult.Status.UNAVAILABLE_WORLD, "world view " + env.view().availability(), req, prov);
        if (req.ticks().size() > req.budget().maxTicks()) return gate(ReconstructionResult.Status.TIMING_UNRESOLVED, req.ticks().size() + " ticks exceed the " + req.budget().maxTicks() + "-step horizon (MOVEMENT_SIMULATION.md M2)", req, prov);
        for (TickObservation t : req.ticks()) if (!t.yawKnown()) return gate(ReconstructionResult.Status.ROTATION_UNRESOLVED, "yaw of tick at sequence " + t.sequence() + " is not established", req, prov);
        if (req.frontier().size() > req.budget().maxNodes()) return gate(ReconstructionResult.Status.SEARCH_LIMIT, "frontier of " + req.frontier().size() + " exceeds " + req.budget().maxNodes() + " nodes", req, prov);
        // ---- branches
        List<ReconstructionResult.Candidate> candidates = new ArrayList<>();
        List<ReconstructionResult.BranchSummary> summaries = new ArrayList<>();
        int perTick = InputDomain.enumerate(req.constraints(), 0f, req.assumptions(), req.previousSprinting()).size();
        int matchingStates = 0, matchingHistories = 0;
        for (ReconstructionRequest.ExternalMotionBranch branch : req.branches()) {
            int undecidedBefore = acc.undecided, unexploredBefore = acc.unexplored;
            List<Node> finals = runBranch(req, branch, acc);
            int states = 0, histories = 0;
            for (Node n : finals) {
                ReconstructionResult.Match match = n.region != null && !n.region.top() ? ReconstructionResult.Match.ENVELOPE_POSSIBLE : n.uncertain ? ReconstructionResult.Match.POSSIBLE_INCOMPLETE : ReconstructionResult.Match.EXACT_WITNESS;
                if (candidates.size() < req.budget().maxCandidatesReported())
                    candidates.add(new ReconstructionResult.Candidate(candidates.size(), branch.id(), n.startLabel, n.histories, n.historyCount, n.masks, n.state, n.region, match, n.residual, n.physicsStatus, n.reasons, n.witness));
                else acc.note("candidate list truncated at " + req.budget().maxCandidatesReported() + " (counts remain exact)");
                if (n.plausible()) { states++; histories += n.historyCount; }
            }
            summaries.add(new ReconstructionResult.BranchSummary(branch.id(), states, histories, !acc.searchLimit && acc.undecided == undecidedBefore && acc.unexplored == unexploredBefore));
            matchingStates += states; matchingHistories += histories;
        }
        // ---- status: the most severe coverage loss, else the exact classification
        ReconstructionResult.Status status;
        if (acc.searchLimit) status = ReconstructionResult.Status.SEARCH_LIMIT;
        else if (acc.worstPhysics != ReconstructionResult.Status.EXACT_UNIQUE) status = acc.worstPhysics;
        else if (acc.undecided > 0 || acc.unexplored > 0) status = ReconstructionResult.Status.INCOMPLETE_WORLD;
        else if (acc.physicsUnsupported) status = ReconstructionResult.Status.UNSUPPORTED_CONTEXT;
        else if (matchingStates == 0) status = ReconstructionResult.Status.NO_MODELLED_CANDIDATE;
        else if (!anyWitness(candidates)) status = ReconstructionResult.Status.ENVELOPE_ONLY;
        else status = matchingHistories == 1 && matchingStates == 1 ? ReconstructionResult.Status.EXACT_UNIQUE : ReconstructionResult.Status.EXACT_AMBIGUOUS;
        ReconstructionResult.Ambiguity amb = matchingStates == 0 ? ReconstructionResult.Ambiguity.NONE : matchingStates > 1 ? ReconstructionResult.Ambiguity.DISTINCT_STATES : matchingHistories > 1 ? ReconstructionResult.Ambiguity.EQUIVALENT_INPUTS : ReconstructionResult.Ambiguity.UNIQUE;
        ReconstructionResult.Coverage cov = new ReconstructionResult.Coverage(true, true, true, !acc.environmentUnknown, !acc.physicsUnsupported, !acc.worldIncomplete, !acc.searchLimit && acc.unexplored == 0, acc.reasons);
        ReconstructionResult.Counts counts = new ReconstructionResult.Counts(perTick, acc.enumerated, acc.simulated, acc.excludedExact, acc.excludedEnvelope, acc.undecided, matchingStates, matchingHistories, acc.unexplored);
        ReconstructionResult.Work work = new ReconstructionResult.Work(acc.evaluations, acc.maxLive, acc.merged, acc.dropped, acc.q, acc.c, acc.i, acc.e, acc.envelopeTransfers, acc.envelopeCells);
        return new ReconstructionResult(status, status.completeSearch(), amb, cov, candidates, counts, work, prov, summaries, acc.notes, acc.diagnostics(req.ticks().size(), req.envelopePruning()));
    }
    private static boolean anyWitness(List<ReconstructionResult.Candidate> cs) { for (ReconstructionResult.Candidate c : cs) if (c.match() == ReconstructionResult.Match.EXACT_WITNESS) return true; return false; }

    private static ReconstructionResult gate(ReconstructionResult.Status status, String reason, ReconstructionRequest req, ReconstructionResult.Provenance prov) {
        boolean epoch = status != ReconstructionResult.Status.WORLD_EPOCH_MISMATCH && status != ReconstructionResult.Status.DISCONTINUITY && status != ReconstructionResult.Status.UNAVAILABLE_WORLD;
        ReconstructionResult.Coverage cov = new ReconstructionResult.Coverage(epoch, status != ReconstructionResult.Status.TIMING_UNRESOLVED, status != ReconstructionResult.Status.ROTATION_UNRESOLVED, true, status != ReconstructionResult.Status.NUMERIC_INVALID, true, status != ReconstructionResult.Status.SEARCH_LIMIT, List.of(reason));
        int perTick = InputDomain.enumerate(req.constraints(), 0f, req.assumptions(), req.previousSprinting()).size();
        return new ReconstructionResult(status, false, ReconstructionResult.Ambiguity.NONE, cov, List.of(), new ReconstructionResult.Counts(perTick, 0, 0, 0, 0, 0, 0, 0, 0), new ReconstructionResult.Work(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), prov, List.of(), List.of(reason));
    }

    private List<Node> runBranch(ReconstructionRequest req, ReconstructionRequest.ExternalMotionBranch branch, Acc acc) {
        PhysicsEnvironment base = req.environment(); ReconstructionRequest.SearchBudget budget = req.budget();
        List<Node> nodes = new ArrayList<>();
        for (ReconstructionRequest.Start s : req.frontier()) {
            Node n = s instanceof ReconstructionRequest.ConcreteStart c ? new Node(c.state(), null, c.label(), false, true, PhysicsResult.Status.EXACT, List.of(), null) : new Node(null, ((ReconstructionRequest.AbstractStart) s).region(), s.label(), false, true, PhysicsResult.Status.EXACT, List.of(), null);
            n.start = n.state; n.histories.add(List.of()); n.historyCount = 1; nodes.add(n);
        }
        AbstractTransfer transfer = req.envelopePruning() || hasAbstract(nodes) ? new AbstractTransfer(simulator, base, req.observationProfile(), budget.maxEnvelopeCells()) : null;
        int T = req.ticks().size();
        // the admitted inputs of every tick without a stateful prerequisite (a superset of any node's list: sound for the envelope lane)
        List<List<MovementInput>> tickInputs = new ArrayList<>(T);
        for (int k = 0; k < T; k++) tickInputs.add(InputDomain.enumerate(req.constraints(), req.ticks().get(k).yaw(), req.assumptions(), null));
        for (int t = 0; t < T && !nodes.isEmpty(); t++) {
            TickObservation obs = req.ticks().get(t);
            PhysicsEnvironment envT = base.withExternalMotions(branch.at(t));
            LinkedHashMap<Key, Node> next = new LinkedHashMap<>();
            List<Node> leaves = new ArrayList<>();
            boolean last = t == T - 1;
            for (Node node : nodes) {
                Boolean prevSprinting = t == 0 ? req.previousSprinting() : node.lastSprinting();
                List<MovementInput> inputs = ordered(req.assumptions().isEmpty() ? tickInputs.get(t) : InputDomain.enumerate(req.constraints(), obs.yaw(), req.assumptions(), prevSprinting), req.witnessProposal());
                acc.enumerated += inputs.size();
                if (node.region != null) { acc.abstractNodes++; expandAbstract(node, inputs, branch, req, t, transfer, leaves, acc); if (acc.searchLimit) break; continue; }
                if (node.uncertain) acc.uncertainNodes++;
                // ---- envelope lane (M3): each input's own sound envelope for THIS tick must meet this tick's observation, and its propagation
                //      over ALL inputs of the remaining ticks must meet every later observation; otherwise that input is excluded without a
                //      physics step (a proven exclusion). A TOP envelope never excludes anything.
                AbstractTransfer.Prepared prepared = null;
                if (transfer != null && req.envelopePruning() && !node.uncertain) {
                    AbstractState a = AbstractState.fromConcrete(node.state);
                    if (!a.top()) prepared = transfer.prepare(a.boxes().get(0), branch.at(t)); // per-input envelopes below (a whole-node reach check measured as pure overhead: the per-input checks already prune everything it would)
                }
                // ---- concrete lane
                for (int idx = 0; idx < inputs.size(); idx++) {
                    MovementInput in = inputs.get(idx);
                    boolean isWitness = req.witnessProposal() != null && idx == 0 && sameKeys(in, req.witnessProposal());
                    if (prepared != null) {
                        List<AbstractState.Box> env1 = transfer.apply(prepared, in); acc.envelopeTransfers++;
                        if (T == 1) acc.envelopeReach(node.start, env1);
                        if (env1 != null) {
                            boolean may = false; for (AbstractState.Box b : env1) if (b.intersectsPosition(obs.position(), req.observationProfile())) { may = true; break; }
                            if (may && T - t > 1) {
                                AbstractState u = AbstractState.of(env1);
                                for (int k = t + 1; k < T && !u.top(); k++) {
                                    u = transfer.step(u, tickInputs.get(k), branch.at(k)); acc.envelopeTransfers++;
                                    if (!u.top() && !u.mayMatch(req.ticks().get(k).position(), req.observationProfile())) { may = false; break; }
                                }
                            }
                            if (!may) { acc.excludedEnvelope++; continue; }
                        }
                    }
                    if (acc.evaluations >= budget.maxEvaluations()) { acc.searchLimit = true; acc.unexplored += inputs.size() - idx; acc.reason("SEARCH_LIMIT: " + budget.maxEvaluations() + " physics evaluations reached"); break; }
                    for (PhysicsState start : node.state.sprintModifierCases(in)) { // P9-F1: a MAYBE sprint-modifier class splits into its members only when they differ for this input
                        PhysicsResult r = simulator.simulate(start, in, envT); acc.evaluations++; acc.simulated++; acc.physics(r);
                        if (r.next() == null) { // nothing sound could be computed: undecided, never excluded
                            acc.undecided++;
                            if (last) addNode(next, leaves, new Node(null, AbstractState.top(r.status().name()), node.startLabel, true, true, r.status(), r.reasons(), null), node, in, isWitness, budget, acc);
                            continue;
                        }
                        boolean inside = obs.position().contains(r.next().position(), req.observationProfile());
                        TickObservation.Residual residual = obs.residual(r.next().position(), r.next().onGround(), req.observationProfile());
                        if (r.exact()) {
                            if (last && !node.uncertain) acc.reach(node.start, r.next());
                            if (!inside) { acc.excludedExact++; if (last && !node.uncertain) acc.near(branch.id(), node, in, r.next(), residual); continue; }
                            Node child = new Node(r.next(), null, node.startLabel, node.uncertain, true, node.uncertain ? node.physicsStatus : r.status(), node.uncertain ? node.reasons : List.of(), residual); child.start = node.start;
                            addNode(next, leaves, child, node, in, isWitness, budget, acc);
                        } else {
                            acc.undecided++;
                            // inexact prediction: never excluded; continued only while it is inside the observation set, otherwise reported at the end and left unexplored
                            if (inside) { Node child = new Node(r.next(), null, node.startLabel, true, true, r.status(), r.reasons(), residual); child.start = node.start; addNode(next, leaves, child, node, in, isWitness, budget, acc); }
                            else if (last) addNode(next, leaves, new Node(null, AbstractState.top("lower-bound prediction outside the observation under " + r.status()), node.startLabel, true, false, r.status(), r.reasons(), null), node, in, isWitness, budget, acc);
                            else acc.unexplored++;
                        }
                    }
                }
                if (acc.searchLimit) break;
            }
            nodes = new ArrayList<>(next.values()); nodes.addAll(leaves);
            acc.maxLive = Math.max(acc.maxLive, nodes.size());
            if (acc.searchLimit) { if (!last) { acc.unexplored += nodes.size(); return List.of(); } return nodes; }
        }
        if (transfer != null) acc.envelopeCells += transfer.cellsScanned;
        return nodes;
    }
    private static boolean hasAbstract(List<Node> nodes) { for (Node n : nodes) if (n.region != null) return true; return false; }

    /** Abstract start regions: each input propagates the region; a region that cannot meet the observation is excluded, the rest stay ENVELOPE_POSSIBLE (never witnesses). */
    private void expandAbstract(Node node, List<MovementInput> inputs, ReconstructionRequest.ExternalMotionBranch branch, ReconstructionRequest req, int t, AbstractTransfer transfer, List<Node> out, Acc acc) {
        TickObservation obs = req.ticks().get(t);
        for (MovementInput in : inputs) {
            if (acc.envelopeTransfers >= req.budget().maxEvaluations()) { acc.searchLimit = true; acc.reason("SEARCH_LIMIT: envelope transfers reached the evaluation budget"); return; }
            AbstractState a = transfer.step(node.region, List.of(in), branch.at(t)); acc.envelopeTransfers++; acc.simulated++;
            if (!a.top() && !a.mayMatch(obs.position(), req.observationProfile())) { acc.excludedEnvelope++; continue; }
            if (a.top()) { acc.physicsUnsupported = true; acc.reason("ENVELOPE_TOP:" + a.topReason()); }
            Node n = new Node(null, a, node.startLabel, true, true, PhysicsResult.Status.EXACT, List.of(), null);
            appendHistories(n, node, in, req.budget(), acc); n.witness = node.witness;
            if (out.size() >= req.budget().maxNodes()) { acc.searchLimit = true; acc.dropped++; acc.reason("SEARCH_LIMIT: " + req.budget().maxNodes() + " live nodes"); return; }
            out.add(n);
        }
    }

    private static void addNode(LinkedHashMap<Key, Node> next, List<Node> leaves, Node fresh, Node parent, MovementInput in, boolean witness, ReconstructionRequest.SearchBudget budget, Acc acc) {
        if (fresh.region != null && fresh.region.top()) { // physics could not compute anything: one undecided node per abort reason, every input history kept (bounded) and counted
            for (Node l : leaves) if (l.region != null && l.region.top() && l.region.topReason().equals(fresh.region.topReason()) && l.physicsStatus == fresh.physicsStatus) { appendHistories(l, parent, in, budget, acc); l.witness |= witness || parent.witness; acc.merged++; return; }
        }
        if (fresh.region != null || !fresh.inside) { // leaves without a continuable state: kept as their own nodes
            if (next.size() + leaves.size() >= budget.maxNodes()) { acc.searchLimit = true; acc.dropped++; acc.reason("SEARCH_LIMIT: " + budget.maxNodes() + " live nodes"); return; }
            appendHistories(fresh, parent, in, budget, acc); fresh.witness = witness || parent.witness; leaves.add(fresh); return;
        }
        Key key = new Key(fresh.state, fresh.uncertain);
        Node existing = next.get(key);
        if (existing != null) { appendHistories(existing, parent, in, budget, acc); existing.witness |= witness || parent.witness; acc.merged++; return; }
        if (next.size() + leaves.size() >= budget.maxNodes()) { acc.searchLimit = true; acc.dropped++; acc.reason("SEARCH_LIMIT: " + budget.maxNodes() + " live nodes"); return; }
        appendHistories(fresh, parent, in, budget, acc); fresh.witness = witness || parent.witness;
        next.put(key, fresh);
    }
    private static void appendHistories(Node target, Node parent, MovementInput in, ReconstructionRequest.SearchBudget budget, Acc acc) {
        for (List<MovementInput> h : parent.histories) {
            if (target.histories.size() >= budget.maxHistoriesPerNode()) { acc.dropped++; break; }
            List<MovementInput> h2 = new ArrayList<>(h.size() + 1); h2.addAll(h); h2.add(in); target.histories.add(h2);
        }
        target.historyCount += parent.historyCount;
        // per-tick raw-ordinal masks: exact per tick, kept even when the materialized histories are capped
        int depth = parent.masks.length + 1;
        if (target.masks.length < depth) { long[][] m = new long[depth][]; for (int t = 0; t < depth; t++) m[t] = t < target.masks.length ? target.masks[t] : new long[3]; target.masks = m; }
        for (int t = 0; t < parent.masks.length; t++) for (int w = 0; w < 3; w++) target.masks[t][w] |= parent.masks[t][w];
        int o = InputDomain.ordinal(in); target.masks[depth - 1][o >>> 6] |= 1L << (o & 63);
    }
    /** The reported input (keys only) first, then the rest in canonical order. */
    static List<MovementInput> ordered(List<MovementInput> canonical, MovementInput witness) {
        if (witness == null) return canonical;
        for (int i = 0; i < canonical.size(); i++) if (sameKeys(canonical.get(i), witness)) {
            if (i == 0) return canonical;
            List<MovementInput> out = new ArrayList<>(canonical.size()); out.add(canonical.get(i)); for (int j = 0; j < canonical.size(); j++) if (j != i) out.add(canonical.get(j)); return out;
        }
        return canonical;
    }
    static boolean sameKeys(MovementInput a, MovementInput b) { return a.forward() == b.forward() && a.strafe() == b.strafe() && a.jump() == b.jump() && a.shift() == b.shift() && a.sprinting() == b.sprinting() && a.usingItem() == b.usingItem(); }
}
