package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.collision.CollisionResult;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.simulation.MovementValidation.Classification;
import me.nicolas.papea2ac.simulation.MovementValidation.InvalidReason;
import me.nicolas.papea2ac.simulation.MovementValidation.UncertainReason;
import me.nicolas.papea2ac.simulation.MovementValidation.ValidReason;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Completeness;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Counts;
import me.nicolas.papea2ac.simulation.MovementValidationResult.NearMiss;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Proof;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Provenance;
import me.nicolas.papea2ac.simulation.MovementValidationResult.ReachBound;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Residual;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Witness;
import me.nicolas.papea2ac.simulation.MovementValidationResult.Work;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
/**
 * Phase 8 movement validation (ARCHITECTURE.md A1 "simulation owns ... movement validation"; A2 outcome semantics): for one
 * observed transition it asks whether AT LEAST ONE sound, supported combination of timing hypothesis + external-motion branch +
 * legal client input + vanilla physics + observed world explains the movement. It does not re-derive any of those: the Phase 7
 * adapter {@link TimingReconstruction} runs the Phase 7 resolver and the Phase 6 reconstructor exactly once per request and this
 * class only CLASSIFIES what came back ({@link #classify}), so a caller that already holds a {@code TimingReconstruction.Result}
 * consumes it without recomputation.
 *
 * <p>The central rule, implemented in one place ({@link #classify}) and enforced by {@link MovementValidationResult}'s constructor:
 * <pre>
 *   a legal explanation exists                                  -> VALID   (ambiguity is fine; nothing is chosen)
 *   none exists and any relevant branch is incomplete/unsupported -> UNCERTAIN, naming the blocking dimension(s)
 *   none exists and every dimension is complete                  -> INVALID, with an exhaustion proof
 * </pre>
 * No residual threshold, no "closest candidate", no RTT, no motive. Residuals and reach are diagnostics attached afterwards from
 * work the search already did. An exception inside the pipeline is contained as UNCERTAIN / INTERNAL_ERROR.
 */
public final class MovementValidator {
    private final TimingReconstruction timing;
    public MovementValidator(TimingReconstruction timing) { this.timing = Objects.requireNonNull(timing); }
    public TimingReconstruction timing() { return timing; }

    /** Runs Phase 7 + Phase 6 once for the request and classifies the union. */
    public MovementValidationResult validate(MovementValidationRequest req) {
        Objects.requireNonNull(req);
        if (req.frontier().isEmpty()) return unavailable(req, UncertainReason.START_UNAVAILABLE, "no supported start state: the chain is not anchored (rest fixed point, correction application point) - nothing can be judged");
        TimingReconstruction.Result r;
        try { r = timing.run(req.timing(), req.frontier(), req.environment(), req.reconstructionBudget()); }
        catch (RuntimeException e) { return unavailable(req, UncertainReason.INTERNAL_ERROR, "pipeline failure contained: " + e.getClass().getSimpleName()); }
        try { return classify(req, r); }
        catch (RuntimeException e) { return unavailable(req, UncertainReason.INTERNAL_ERROR, "classifier failure contained: " + e.getClass().getSimpleName()); }
    }

    /** Classifies an already computed Phase 7/6 union for the request it was computed from (no physics, no timing, no reconstruction is re-run). */
    public MovementValidationResult classify(MovementValidationRequest req, TimingReconstruction.Result r) {
        Objects.requireNonNull(req); Objects.requireNonNull(r);
        int ops = 0;
        TimingRequest t = req.timing();
        List<String> notes = new ArrayList<>();
        Set<UncertainReason> uncertain = EnumSet.noneOf(UncertainReason.class);
        // ---- completeness dimensions, one at a time (never merged)
        boolean timingOk = true, reconOk = true, worldOk = true, attrOk = true, effOk = true, equipOk = true, entOk = true, borderOk = true, extOk = true, mechOk = true, searchOk = true, obsOk = true, frontierOk = req.frontierComplete();
        if (!req.frontierComplete()) uncertain.add(UncertainReason.FRONTIER_INCOMPLETE);
        if (!req.contentRetained()) { worldOk = false; uncertain.add(UncertainReason.CONTENT_NOT_RETAINED); }
        int completeHyp = 0;
        for (TimingResolution res : r.resolutions()) {
            ops++;
            if (res.complete()) completeHyp += res.hypotheses().size();
            switch (res.status()) {
                case RESOLVED_UNIQUE, RESOLVED_AMBIGUOUS, NO_ACK_EVIDENCE, INSUFFICIENT_RTT -> { if (!res.complete()) { timingOk = false; uncertain.add(UncertainReason.TIMING_INCOMPLETE); } }
                case TIMING_GAP -> { timingOk = false; obsOk = false; uncertain.add(UncertainReason.TIMING_GAP); }
                case SEARCH_LIMIT -> { timingOk = false; searchOk = false; uncertain.add(UncertainReason.SEARCH_LIMIT); }
                case UNSUPPORTED_HORIZON -> { timingOk = false; obsOk = false; uncertain.add(UncertainReason.UNSUPPORTED_HORIZON); }
                case TELEPORT_UNRESOLVED -> { timingOk = false; uncertain.add(UncertainReason.TELEPORT_UNRESOLVED); }
                case WORLD_DISCONTINUITY -> { timingOk = false; obsOk = false; uncertain.add(UncertainReason.DISCONTINUITY); }
                case SESSION_DISCONTINUITY -> { timingOk = false; obsOk = false; uncertain.add(UncertainReason.CAPTURE_GAP); }
                case EXTERNAL_MOTION_UNRESOLVED -> { extOk = false; uncertain.add(UncertainReason.EXTERNAL_MOTION_UNRESOLVED); if (!res.complete()) timingOk = false; }
                case EXPIRED -> { extOk = false; timingOk = false; uncertain.add(UncertainReason.EXTERNAL_MOTION_EXPIRED); }
                case UNSUPPORTED -> { extOk = false; timingOk = false; uncertain.add(res.evidence().influencesConsidered() > 0 || res.hypotheses().isEmpty() && res.notes().stream().anyMatch(n -> n.contains("influence")) ? UncertainReason.EXTERNAL_MOTION_OVERFLOW : UncertainReason.TIMING_INCOMPLETE); }
            }
        }
        if (r.resolutions().isEmpty()) { timingOk = false; uncertain.add(UncertainReason.TIMING_INCOMPLETE); notes.add("no timing resolution was produced"); }
        int legalCandidates = 0, legalHistories = 0, reconCount = r.work().reconstructions(), rawEnumerated = 0, excludedExact = 0, excludedEnvelope = 0; long physicsQueries = 0;
        List<Witness> witnesses = new ArrayList<>(); List<String> hypIds = new ArrayList<>();
        List<ReconstructionResult.Nearest> near = new ArrayList<>(); List<String> nearHyp = new ArrayList<>();
        boolean reachBoundSound = true, anyReach = false; double maxH = 0, maxRise = Double.NEGATIVE_INFINITY, minRise = Double.POSITIVE_INFINITY; boolean envelopeBound = true;
        for (TimingReconstruction.Step st : r.steps()) {
            ops++;
            ReconstructionResult rr = st.result(); TimingHypothesis h = st.hypothesis();
            if (hypIds.size() < 32) hypIds.add(h.id());
            rawEnumerated += rr.counts().rawEnumerated(); excludedExact += rr.counts().excludedExact(); excludedEnvelope += rr.counts().excludedEnvelope(); physicsQueries += rr.work().physicsQueries();
            if (st.explains()) {
                if (h.teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT) {
                    legalCandidates++; legalHistories++;
                    if (witnesses.size() < MovementValidationResult.MAX_WITNESSES) witnesses.add(new Witness(h.id(), h.branch().id(), -1, List.of(), 1, null, new TickObservation.Residual(0, 0, 0, 0, true, true, false), new ArrayList<>(h.appliedInfluences()), true));
                } else for (ReconstructionResult.Candidate c : rr.candidates()) {
                    if (!c.branchId().equals(h.branch().id()) || !c.exact()) continue;
                    legalCandidates++; legalHistories += c.historyCount();
                    if (witnesses.size() < MovementValidationResult.MAX_WITNESSES) witnesses.add(new Witness(h.id(), c.branchId(), c.id(), c.input(), c.historyCount(), c.predicted(), c.residual(), new ArrayList<>(h.appliedInfluences()), false));
                }
            }
            // per-step completeness (a step's own coverage record: each false is its own dimension)
            if (!rr.complete()) reconOk = false;
            ReconstructionResult.Coverage cov = rr.coverage();
            if (!cov.searchComplete()) { searchOk = false; uncertain.add(rr.status() == ReconstructionResult.Status.QUERY_LIMIT ? UncertainReason.QUERY_LIMIT : UncertainReason.SEARCH_LIMIT); }
            if (!cov.worldComplete()) { worldOk = false; uncertain.add(cov.reasons().stream().anyMatch(s -> s.contains("CONTENT_NOT_RETAINED")) ? UncertainReason.CONTENT_NOT_RETAINED : UncertainReason.WORLD_INCOMPLETE); }
            if (!cov.rotationResolved()) { obsOk = false; uncertain.add(UncertainReason.ROTATION_UNRESOLVED); }
            if (!cov.timingResolved()) { obsOk = false; uncertain.add(UncertainReason.UNSUPPORTED_HORIZON); }
            if (!cov.epochAndContinuity()) { obsOk = false; uncertain.add(rr.status() == ReconstructionResult.Status.UNAVAILABLE_WORLD ? UncertainReason.WORLD_INCOMPLETE : UncertainReason.DISCONTINUITY); if (rr.status() == ReconstructionResult.Status.UNAVAILABLE_WORLD) worldOk = false; }
            if (!cov.physicsSupported()) { mechOk = false; uncertain.add(mechanic(rr)); }
            if (!cov.environmentKnown()) {
                boolean named = false;
                for (String reason : cov.reasons()) {
                    if (reason.startsWith("ATTRIBUTE_")) { attrOk = false; uncertain.add(UncertainReason.ATTRIBUTE_UNKNOWN); named = true; }
                    else if (reason.startsWith("EFFECT")) { effOk = false; uncertain.add(UncertainReason.EFFECT_UNKNOWN); named = true; }
                    else if (reason.startsWith("EQUIPMENT_")) { equipOk = false; uncertain.add(UncertainReason.EQUIPMENT_UNKNOWN); named = true; }
                    else if (reason.startsWith("ENTITIES_") || reason.startsWith("PUSH_ORDER")) { entOk = false; uncertain.add(UncertainReason.ENTITY_NEIGHBORHOOD_UNKNOWN); named = true; }
                    else if (reason.startsWith("BORDER_UNKNOWN")) { borderOk = false; uncertain.add(UncertainReason.WORLD_BORDER_UNKNOWN); named = true; }
                    else if (reason.startsWith("EXTERNAL_MOTION")) { extOk = false; uncertain.add(UncertainReason.EXTERNAL_MOTION_UNRESOLVED); named = true; }
                }
                if (!named) { // the status alone names it
                    switch (rr.status()) {
                        case UNKNOWN_ATTRIBUTE -> { attrOk = false; uncertain.add(UncertainReason.ATTRIBUTE_UNKNOWN); }
                        case UNKNOWN_EFFECT -> { effOk = false; uncertain.add(UncertainReason.EFFECT_UNKNOWN); }
                        case UNKNOWN_EQUIPMENT -> { equipOk = false; uncertain.add(UncertainReason.EQUIPMENT_UNKNOWN); }
                        case UNKNOWN_ENTITY_NEIGHBORHOOD -> { entOk = false; uncertain.add(UncertainReason.ENTITY_NEIGHBORHOOD_UNKNOWN); }
                        case UNKNOWN_WORLD_BORDER -> { borderOk = false; uncertain.add(UncertainReason.WORLD_BORDER_UNKNOWN); }
                        case UNRESOLVED_EXTERNAL_MOTION -> { extOk = false; uncertain.add(UncertainReason.EXTERNAL_MOTION_UNRESOLVED); }
                        default -> { entOk = false; uncertain.add(UncertainReason.ENTITY_NEIGHBORHOOD_UNKNOWN); }
                    }
                }
            }
            if (rr.status() == ReconstructionResult.Status.NUMERIC_INVALID) { obsOk = false; uncertain.add(UncertainReason.NUMERIC_INVALID); }
            // reach / nearest diagnostics of the exhausted set (only meaningful for physics steps)
            if (h.teleport() != TimingHypothesis.TeleportRelation.APPLICATION_POINT) {
                ReconstructionResult.Extent reach = rr.diagnostics().extent();
                if (reach.envelopePresent()) { anyReach = true; maxH = Math.max(maxH, reach.envelopeMaxHorizontal()); maxRise = Math.max(maxRise, reach.envelopeMaxRise()); minRise = Math.min(minRise, reach.envelopeMinRise()); }
                else if (reach.predictions() > 0 && reach.allInputsSimulated()) { anyReach = true; envelopeBound = false; maxH = Math.max(maxH, reach.simulatedMaxHorizontal()); maxRise = Math.max(maxRise, reach.simulatedMaxRise()); minRise = Math.min(minRise, reach.simulatedMinRise()); }
                else reachBoundSound = false;
                for (ReconstructionResult.Nearest n : rr.diagnostics().nearest()) { near.add(n); nearHyp.add(h.id()); }
            }
        }
        if (r.notes().stream().anyMatch(n -> n.startsWith("frontier cap") || n.startsWith("reconstruction budget") || n.startsWith("silent closure hit a bound"))) { searchOk = false; uncertain.add(UncertainReason.SEARCH_LIMIT); }
        if (r.status() == TimingReconstruction.Status.SEARCH_LIMIT && !uncertain.contains(UncertainReason.SEARCH_LIMIT)) { searchOk = false; uncertain.add(UncertainReason.SEARCH_LIMIT); }
        if (r.steps().isEmpty() && uncertain.isEmpty()) { timingOk = false; uncertain.add(UncertainReason.TIMING_INCOMPLETE); notes.add("no timing hypothesis was enumerated for this interval"); }
        // P8-F2: the exact model is not qualified for INVALID within reach of a climbable block (ladder, vine, scaffolding): the mechanic dimension is incomplete there
        if (climbableInReach(req)) { mechOk = false; uncertain.add(UncertainReason.CLIMBABLE_CONTACT); if (notes.size() < MovementValidationResult.MAX_NOTES - 2) notes.add("climbable block within reach of the transition: not qualified for INVALID (P8-F2)"); }
        // P9-F2 (found live): a block whose state the client and this observer may legitimately disagree about - a client-predicted interaction
        // still unacknowledged, or a change this observer applied that the client cannot have seen yet - is an UNKNOWN cell for this transition,
        // whatever the observer world currently holds. The world dimension is incomplete: an explained transition stays VALID, an unexplained
        // one is UNCERTAIN. Never a tolerance and never a suppressed result: the missing dimension is named.
        if (req.worldDisputed()) { worldOk = false; uncertain.add(UncertainReason.WORLD_INCOMPLETE); if (notes.size() < MovementValidationResult.MAX_NOTES - 2) notes.add("a block state within the transition's reach is in dispute (client prediction unacknowledged, or a change the client cannot have applied yet): world knowledge incomplete"); }
        // safety net (pre-baseline audit): an adapter-level incompleteness that no dimension above named still forbids INVALID
        if (!r.complete() && uncertain.isEmpty()) { searchOk = false; uncertain.add(UncertainReason.SEARCH_LIMIT); notes.add("the union reported incomplete without a named dimension: treated as a search bound"); }
        Completeness comp = new Completeness(timingOk, reconOk, worldOk, attrOk, effOk, equipOk, entOk, borderOk, extOk, mechOk, searchOk, obsOk, frontierOk);
        for (String n : r.notes()) if (notes.size() < MovementValidationResult.MAX_NOTES - 2) notes.add(n);
        // ---- next frontier completeness for the caller's chain: a correction application point without relative axes re-anchors it
        boolean reanchored = false; for (TimingReconstruction.Step st : r.explaining()) if (st.hypothesis().teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT && st.hypothesis().correction() != null && st.hypothesis().correction().relativeMask() == 0) reanchored = true;
        boolean nextComplete = reanchored ? r.complete() : (req.frontierComplete() && r.complete());
        int totalHyp = r.work().hypotheses();
        Counts counts = new Counts(r.work().resolutions(), totalHyp, completeHyp, r.explaining().size(), reconCount, legalCandidates, legalHistories, req.frontier().nodes().size(), r.next().nodes().size());
        Work work = new Work(r.work().resolutions(), totalHyp, reconCount, r.work().evaluations(), physicsQueries, ops);
        Provenance prov = new Provenance(t.worldEpoch(), req.environment().view().revision(), t.anchorSequence(), t.target().sequence(), t.profile().id(), req.environment().profile().id(), profileId(r), req.environment().collision().profile().id());
        // ---- the rule
        if (!r.explaining().isEmpty()) {
            Set<ValidReason> vr = new LinkedHashSet<>();
            for (TimingReconstruction.Step st : r.explaining()) {
                if (st.hypothesis().teleport() == TimingHypothesis.TeleportRelation.APPLICATION_POINT) vr.add(ValidReason.TELEPORT_APPLICATION_POINT); else vr.add(ValidReason.LEGAL_INPUT_FOUND);
                if (!st.hypothesis().appliedInfluences().isEmpty()) vr.add(ValidReason.EXTERNAL_MOTION_EXPLAINS);
            }
            if (totalHyp > 1) vr.add(ValidReason.LEGAL_TIMING_BRANCH_FOUND);
            return new MovementValidationResult(Classification.VALID, t.anchorSequence(), t.target().sequence(), t.worldEpoch(), comp, new ArrayList<>(vr), List.of(), List.of(), counts, witnesses, Proof.NONE, Residual.NONE, work, prov, r.next(), nextComplete, notes);
        }
        if (!uncertain.isEmpty() || !comp.all()) {
            if (uncertain.isEmpty()) uncertain.add(UncertainReason.TIMING_INCOMPLETE); // defensive: a false dimension always names a reason
            Residual res = residual(req, near, nearHyp, anyReach, reachBoundSound, envelopeBound, maxH, maxRise, minRise, notes);
            return new MovementValidationResult(Classification.UNCERTAIN, t.anchorSequence(), t.target().sequence(), t.worldEpoch(), comp, List.of(), new ArrayList<>(uncertain), List.of(), counts, List.of(), Proof.NONE, res, work, prov, r.next(), false, notes);
        }
        // ---- INVALID: exhaustion with complete knowledge; diagnostics derived from the exhausted set
        Residual res = residual(req, near, nearHyp, anyReach, reachBoundSound, envelopeBound, maxH, maxRise, minRise, notes);
        Set<InvalidReason> ir = new LinkedHashSet<>(); ir.add(InvalidReason.NO_LEGAL_INPUT);
        if (res.present() && res.bound() != ReachBound.NONE) {
            if (res.horizontalExcess() > 0) ir.add(InvalidReason.HORIZONTAL_EXCESS);
            if (res.observedRise() > res.maxLegalRise()) { ir.add(InvalidReason.VERTICAL_EXCESS); ir.add(InvalidReason.IMPOSSIBLE_ASCENT); }
            else if (res.observedRise() < res.minLegalRise()) { ir.add(InvalidReason.VERTICAL_EXCESS); ir.add(InvalidReason.IMPOSSIBLE_DESCENT); }
        }
        if (t.target().hasPosition() && penetrates(req, t.target().position())) ir.add(InvalidReason.COLLISION_PENETRATION);
        if (ir.size() == 1) ir.add(InvalidReason.UNEXPLAINED_DISPLACEMENT);
        Proof proof = new Proof(r.steps().size(), reconCount, r.work().evaluations(), rawEnumerated, excludedExact, excludedEnvelope, 0, 0, profileId(r), req.environment().profile().id(), req.environment().collision().profile().id(), t.profile().id(), t.worldEpoch(), req.environment().view().revision(), hypIds);
        if (notes.size() < MovementValidationResult.MAX_NOTES) notes.add("complete supported hypothesis union is empty: a physical statement about the modelled domain, not a statement of motive");
        return new MovementValidationResult(Classification.INVALID, t.anchorSequence(), t.target().sequence(), t.worldEpoch(), comp, List.of(), List.of(), new ArrayList<>(ir), counts, List.of(), proof, res, work, prov, r.next(), false, notes);
    }

    private static UncertainReason mechanic(ReconstructionResult rr) {
        if (rr.status() == ReconstructionResult.Status.UNSUPPORTED_FLUID) return UncertainReason.UNSUPPORTED_FLUID;
        for (String s : rr.coverage().reasons()) { if (s.startsWith("FLUID_CONTACT") || s.startsWith("BUBBLE_COLUMN")) return UncertainReason.UNSUPPORTED_FLUID; if (s.startsWith("LOCOMOTION_UNSUPPORTED") || s.startsWith("POSE_UNSUPPORTED")) return UncertainReason.UNSUPPORTED_LOCOMOTION; }
        return UncertainReason.UNSUPPORTED_CONTEXT;
    }
    private static String profileId(TimingReconstruction.Result r) { for (TimingReconstruction.Step st : r.steps()) if (st.result().provenance().observationProfile() != null && !st.result().provenance().observationProfile().equals("-")) return st.result().provenance().observationProfile(); return "-"; }

    /** Observed displacement from the nearest chain start, the legal reach and the nearest excluded predictions (diagnostic only). */
    private static Residual residual(MovementValidationRequest req, List<ReconstructionResult.Nearest> near, List<String> nearHyp, boolean anyReach, boolean reachSound, boolean envelope, double maxH, double maxRise, double minRise, List<String> notes) {
        TimingRequest t = req.timing();
        Vector3 ref = t.target().hasPosition() ? t.target().position() : t.lastSentPosition();
        double obsH = Double.POSITIVE_INFINITY, obsRise = 0; boolean any = false;
        for (TimingReconstruction.Node n : req.frontier().nodes()) {
            double dx = ref.x() - n.state().position().x(), dz = ref.z() - n.state().position().z(), h = Math.sqrt(dx * dx + dz * dz);
            if (h < obsH) { obsH = h; obsRise = ref.y() - n.state().position().y(); any = true; }
        }
        List<NearMiss> misses = new ArrayList<>();
        // the nearest across hypotheses, by distance outside the observation set, bounded
        List<Integer> order = new ArrayList<>(); for (int i = 0; i < near.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(near.get(a).residual().outsideDistance(), near.get(b).residual().outsideDistance()));
        for (int i : order) { if (misses.size() >= ReconstructionResult.Diagnostics.MAX_NEAREST) break; ReconstructionResult.Nearest n = near.get(i); if (!n.residual().finite()) continue; misses.add(new NearMiss(nearHyp.get(i), n.branchId(), n.input(), n.residual().dx(), n.residual().dy(), n.residual().dz(), n.residual().outsideDistance())); }
        if (!any) return new Residual(false, 0, 0, 0, 0, 0, ReachBound.NONE, 0, 0, misses);
        boolean bound = anyReach && reachSound && Double.isFinite(maxRise) && Double.isFinite(minRise);
        double hx = bound ? Math.max(0.0, obsH - maxH) : 0, vx = bound ? (obsRise > maxRise ? obsRise - maxRise : obsRise < minRise ? obsRise - minRise : 0) : 0;
        if (anyReach && !reachSound && notes.size() < MovementValidationResult.MAX_NOTES) notes.add("legal reach not bounded for every hypothesis (multi-tick or pruned domain): no excess attributed");
        return new Residual(true, obsH, obsRise, bound ? maxH : 0, bound ? maxRise : 0, bound ? minRise : 0, bound ? (envelope ? ReachBound.ENVELOPE : ReachBound.SIMULATED_COMPLETE) : ReachBound.NONE, hx, vx, misses);
    }

    /** Whether a climbable block lies within one block horizontally / from one below to two above the feet of any frontier start or the target (bounded scan; an unknown cell or an oversized region counts as contact, never as clearance). */
    static boolean climbableInReach(MovementValidationRequest req) {
        PhysicsEnvironment env = req.environment();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY; boolean any = false;
        for (TimingReconstruction.Node n : req.frontier().nodes()) { var p = n.state().position(); minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z()); maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z()); any = true; }
        if (req.timing().target().hasPosition()) { Vector3 p = req.timing().target().position(); minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z()); maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z()); any = true; }
        if (!any) return false;
        int x0 = (int) Math.floor(minX - 1.0), x1 = (int) Math.floor(maxX + 1.0), y0 = (int) Math.floor(minY - 1.0), y1 = (int) Math.floor(maxY + 2.0), z0 = (int) Math.floor(minZ - 1.0), z1 = (int) Math.floor(maxZ + 1.0);
        long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells > 4096) return true; // an unbounded scan is never attempted: treated as contact (UNCERTAIN), never as clearance
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            var b = env.behaviors().at(env.view(), x, y, z);
            if (!b.known() || b.block().climbable()) return true;
        }
        return false;
    }
    /** Whether the smallest supported player box at the observed position DEFINITELY intersects known solid geometry (sound under any pose; possible-only hits never count). */
    private static boolean penetrates(MovementValidationRequest req, Vector3 p) {
        PhysicsEnvironment env = req.environment();
        Aabb box = PlayerBox.of(env.collision().profile(), PlayerBox.Pose.SWIMMING, p.x(), p.y(), p.z()); // the lowest pose box: width 0.6, height 0.6
        CollisionResult cr = env.collision().query(env.view(), box, ShapeContext.UNKNOWN, env.worldEpoch());
        return cr.certainty() != CollisionResult.Certainty.UNAVAILABLE && cr.certainty() != CollisionResult.Certainty.REJECTED && cr.definitelyObstructed();
    }

    /** An UNCERTAIN result a caller emits without running the pipeline (unsupported locomotion known before any physics, an unanchored chain, a contained failure): the reason is mandatory. */
    public static MovementValidationResult refuse(MovementValidationRequest req, UncertainReason reason, String note) { return unavailable(req, reason, note); }
    private static MovementValidationResult unavailable(MovementValidationRequest req, UncertainReason reason, String note) {
        TimingRequest t = req.timing();
        boolean mech = reason != UncertainReason.UNSUPPORTED_LOCOMOTION && reason != UncertainReason.UNSUPPORTED_CONTEXT && reason != UncertainReason.UNSUPPORTED_FLUID;
        Completeness comp = new Completeness(false, false, true, true, true, true, true, true, true, mech, true, true, reason != UncertainReason.START_UNAVAILABLE && req.frontierComplete());
        Provenance prov = new Provenance(t.worldEpoch(), req.environment().view().revision(), t.anchorSequence(), t.target().sequence(), t.profile().id(), req.environment().profile().id(), "-", req.environment().collision().profile().id());
        return new MovementValidationResult(Classification.UNCERTAIN, t.anchorSequence(), t.target().sequence(), t.worldEpoch(), comp, List.of(), List.of(reason), List.of(),
            new Counts(0, 0, 0, 0, 0, 0, 0, req.frontier().nodes().size(), 0), List.of(), Proof.NONE, Residual.NONE, new Work(0, 0, 0, 0, 0, 1), prov, new TimingReconstruction.Frontier(List.of()), false, List.of(note));
    }

    /**
     * P9-F3: the idle input that KEEPS a resting client in the given pose. A client standing still while holding the sneak key rests in
     * {@link PlayerBox.Pose#CROUCHING}, whose box is 0.3 shorter; probing every pose with a shift-released input collapses that client to
     * STANDING and drops the crouching hidden state, after which any later traversal that needs the shorter box (a top slab, a trapdoor
     * gap, any 1.5 opening) is mis-clipped and the legitimate movement is proven impossible. The pose is an input fact, not a default.
     */
    private static MovementInput restingInput(PlayerBox.Pose pose, float yaw) {
        return pose == PlayerBox.Pose.CROUCHING ? new MovementInput(0, 0, false, true, false, false, yaw) : MovementInput.idle(yaw);
    }
    /** Why {@link #restFixedPoint} would refuse a position for a pose (diagnostic text; "fixed point" when it would not). */
    public static String restFixedPointReason(me.nicolas.papea2ac.physics.PhysicsSimulator simulator, PhysicsEnvironment env, double x, double y, double z, PlayerBox.Pose pose, float yaw) {
        PhysicsState s = PhysicsState.resting(x, y, z, pose, false);
        MovementInput idle = restingInput(pose, yaw);
        for (int i = 0; i < 2; i++) { var r = simulator.simulate(s, idle, env); if (r.next() == null || !r.exact()) return "idle tick " + (i + 1) + " not exact: " + r.summary(); s = r.next(); }
        if (!s.onGround()) return "not on ground after two idle ticks: " + s.position();
        if (s.position().x() != x || s.position().y() != y || s.position().z() != z) return "moved to " + s.position();
        var again = simulator.simulate(s, idle, env);
        if (again.next() == null || !again.exact()) return "third idle tick not exact: " + again.summary();
        return again.next().equals(s) ? "fixed point" : "not a fixed point: " + again.next();
    }
    /** The resting fixed point of the simulator at a position (idle input twice must reproduce the state): the sound anchor of a chain. Returns null when the position is not a fixed point. */
    public static PhysicsState restFixedPoint(me.nicolas.papea2ac.physics.PhysicsSimulator simulator, PhysicsEnvironment env, double x, double y, double z, PlayerBox.Pose pose, float yaw) {
        PhysicsState s = PhysicsState.resting(x, y, z, pose, false);
        MovementInput idle = restingInput(pose, yaw);
        for (int i = 0; i < 2; i++) { var r = simulator.simulate(s, idle, env); if (r.next() == null || !r.exact()) return null; s = r.next(); }
        if (!s.onGround() || s.position().x() != x || s.position().y() != y || s.position().z() != z) return null;
        var again = simulator.simulate(s, idle, env);
        return again.next() != null && again.exact() && again.next().equals(s) ? s : null;
    }
}
