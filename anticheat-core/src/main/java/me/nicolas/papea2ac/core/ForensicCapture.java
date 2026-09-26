package me.nicolas.papea2ac.core;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.PhysicsResult;
import me.nicolas.papea2ac.physics.PhysicsSimulator;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.InputDomain;
import me.nicolas.papea2ac.simulation.MovementValidationRequest;
import me.nicolas.papea2ac.simulation.MovementValidationResult;
import me.nicolas.papea2ac.simulation.InputReconstructor;
import me.nicolas.papea2ac.simulation.ReconstructionRequest;
import me.nicolas.papea2ac.simulation.TickObservation;
import me.nicolas.papea2ac.simulation.TimingReconstruction;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;

/**
 * P9-F2 forensic capture (diagnostic only, never a verdict input and never on by default): the complete deterministic state of ONE
 * armed live INVALID, frozen at the first trigger and never overwritten. It answers "which state, input, environment or ordering field
 * differs between the real client transition and every candidate Papea2AC reconstructed" by recording, as exact bits where the value is a
 * double:
 *
 * <ul>
 * <li>the recent chain history (the transitions that produced the failing start state: a wrong-but-unique continuation node earlier in the
 *     chain is invisible in the failing transition alone);</li>
 * <li>the failing transition's frontier start states, anchor provenance, observation, context ledger, environment and timing work;</li>
 * <li>the FULL raw candidate table: every one of the 144 raw input tuples simulated from every frontier node, with its prediction,
 *     residual and physics status - not the four nearest (§7 of the brief);</li>
 * <li>a re-run of the same request with the M3 envelope prefilter disabled, and the same request through the production validator, so a
 *     prefilter or equivalence-merge soundness defect shows up as a classification or candidate-set difference (§9, §10).</li>
 * </ul>
 *
 * Everything here runs after a classification that has already been published; it never mutates live state and never feeds evidence.
 */
public final class ForensicCapture {
    /** One retained past transition of the chain (bounded ring), enough to see which continuation node the chain carried forward. */
    record History(long startSequence, long endSequence, String classification, int legalCandidates, int legalHistories, int continuationNodes,
                   long evaluations, int enumerated, int excludedExact, int excludedEnvelope, List<String> witnesses, List<PhysicsState> next, boolean nextComplete, String observation) {}

    private ForensicCapture() {}

    static String bits(double v) { return String.format(Locale.ROOT, "%.17g/0x%016x", v, Double.doubleToRawLongBits(v)); }
    static String bits(float v) { return String.format(Locale.ROOT, "%.9g/0x%08x", v, Float.floatToRawIntBits(v)); }
    private static String vec(Vec3d v) { return "(" + bits(v.x()) + ", " + bits(v.y()) + ", " + bits(v.z()) + ")"; }

    /** Every Phase 5 field the simulation reads, as exact bits. */
    static String state(PhysicsState s) {
        return "pos=" + vec(s.position()) + " vel=" + vec(s.velocity()) + " pose=" + s.pose() + " onGround=" + s.onGround()
            + " hColl=" + s.horizontalCollision() + " vColl=" + s.verticalCollision() + " vCollBelow=" + s.verticalCollisionBelow()
            + " fall=" + bits(s.fallDistance()) + " stuck=" + vec(s.stuck()) + " powderSnow=" + s.inPowderSnow()
            + " support=" + (s.support().present() ? s.support().x() + "," + s.support().y() + "," + s.support().z() : "none") + "/noBlocks=" + s.support().onGroundNoBlocks()
            + " noJumpDelay=" + s.noJumpDelay() + " locomotion=" + s.locomotion() + " sprintModifier=" + s.sprintModifier();
    }
    private static String obs(TimingEvent.MovementObserved m) {
        return "seq=" + m.sequence() + " nanos=" + m.nanos() + " hasPosition=" + m.hasPosition()
            + (m.hasPosition() ? " pos=(" + bits(m.position().x()) + ", " + bits(m.position().y()) + ", " + bits(m.position().z()) + ")" : "")
            + " hasRotation=" + m.hasRotation() + (m.hasRotation() ? " yaw=" + bits(m.rotation().yaw()) + " pitch=" + bits(m.rotation().pitch()) : "")
            + " onGroundClaim=" + m.onGround();
    }

    /**
     * The complete capture. {@code history} is oldest-first. Never throws: a diagnostic failure is reported inside the text.
     */
    static List<String> capture(SessionKey key, String anchorKind, long anchorSequence, long anchorNanos, boolean anchorInTick, long speedSnapshot, int transitionsSinceAnchor,
                                MovementValidationRequest req, MovementValidationResult res, MovementContextLedger.View context, PhysicsSimulator simulator, BlockBehaviorQuery behaviors,
                                String disputes, List<History> history) {
        List<String> out = new ArrayList<>();
        try {
            out.add("=== P9-F2 FORENSIC CAPTURE (diagnostic only)");
            out.add("session=" + key.player() + "/" + key.epoch() + " worldEpoch=" + res.worldEpoch() + " worldRevision=" + req.environment().view().revision() + " capturedAtNanos=" + req.timing().nowNanos());
            out.add("classification=" + res.classification() + " seq=" + res.startSequence() + "->" + res.endSequence() + " completeness=" + (res.completeness().all() ? "ALL" : res.completeness().missing().toString())
                + " invalidReasons=" + res.invalidReasons() + " uncertainReasons=" + res.uncertainReasons());
            out.add("--- anchor provenance");
            out.add("anchorKind=" + anchorKind + " anchorSequence=" + anchorSequence + " anchorNanos=" + anchorNanos + " anchorInTick=" + anchorInTick
                + " transitionsSinceAnchor=" + transitionsSinceAnchor + " speedSnapshotSequence=" + speedSnapshot + " frontierComplete=" + req.frontierComplete());
            out.add("--- frontier start states (" + req.frontier().nodes().size() + ")");
            for (TimingReconstruction.Node n : req.frontier().nodes()) out.add("  node " + n.label() + " pending=" + n.pendingInfluences() + " " + state(n.state()));
            out.add("--- observation");
            out.add("  target " + obs(req.timing().target()));
            out.add("  lastSentPosition=(" + bits(req.timing().lastSentPosition().x()) + ", " + bits(req.timing().lastSentPosition().y()) + ", " + bits(req.timing().lastSentPosition().z()) + ") lastYaw=" + bits(req.timing().lastYaw()));
            out.add("  tickEndCapable=" + req.timing().tickEndCapable() + " coverageDegraded=" + req.timing().coverageDegraded() + " rttSmoothedNanos=" + req.timing().rtt().smoothedNanos() + " jitterNanos=" + req.timing().rtt().jitterNanos());
            out.add("  slice (" + req.timing().events().size() + " event(s)):");
            for (TimingEvent e : req.timing().events()) out.add("    " + describeEvent(e));
            out.add("  pendingInfluences=" + req.timing().pendingInfluences().size() + " teleports=" + req.timing().teleports().size());
            for (var t : req.timing().teleports()) out.add("    teleport id=" + t.teleportId() + " confirmed=" + t.confirmed() + " superseded=" + t.superseded() + " relativeMask=" + t.sent().relativeMask() + " confirmedSequence=" + t.confirmedSequence());
            out.add("--- context ledger (sequence " + context.lastSequence() + ")");
            out.add("  complete=" + context.contextComplete() + " entitiesComplete=" + context.entitiesComplete() + " gameMode=" + context.gameMode() + " flying=" + context.flying() + " canFly=" + context.canFly()
                + " locomotionSupported=" + context.locomotionSupported() + " trackedEntities=" + context.trackedEntities() + " relevantEntities=" + context.relevantEntities().size() + " worldChanges=" + context.worldChanges() + " breaks=" + context.breaks());
            for (var e : context.attributes().entrySet()) out.add("  attribute " + e.getKey() + " base=" + bits(e.getValue().base()) + " appliedAtSequence=" + e.getValue().sequence() + " modifiers=" + e.getValue().modifiers());
            for (var e : context.effects().entrySet()) out.add("  effect " + e.getKey() + " amplifier=" + e.getValue());
            out.add("  border=" + context.border());
            out.add("--- environment");
            PhysicsEnvironment env = req.environment();
            out.add("  view=" + env.view().availability() + " revision=" + env.view().revision() + " epoch=" + env.view().worldEpoch() + " sequence=" + env.view().sequence());
            out.add("  attributesKnowledge=" + env.attributes().byId().size() + " effects=" + env.effects().knowledge() + "/" + env.effects().active().size() + " entities=" + env.entities().knowledge()
                + " border=" + env.border().knowledge() + " equipment=" + env.equipment().knowledge() + " externalMotions=" + env.externalMotions().size());
            for (String id : MovementProfile.MOVEMENT_ATTRIBUTES) {
                PhysicsEnvironment.Attribute a = env.attributes().get(id);
                out.add("  attr " + id + " knowledge=" + a.knowledge() + " provenance=" + a.provenance() + " base=" + bits(a.base()) + " modifiers=" + a.modifiers());
            }
            out.add("  resolvedWalk=" + simulator.resolveAttributes(env, false) + " resolvedSprintModifier=" + simulator.resolveAttributes(env, true) + " snapshotCarriesSprintModifier=" + simulator.snapshotCarriesSprintModifier(env));
            out.add("--- block disputes (P9-F2 ledger state for this transition)");
            out.add("  " + disputes);
            out.add("--- timing / work");
            out.add("  counts=" + res.counts());
            out.add("  work=" + res.work());
            out.add("  proof=" + res.proof());
            out.add("  residual=" + res.residual());
            for (String l : res.describe()) out.add("  | " + l);
            out.add("--- recent chain history (oldest first, " + history.size() + ")");
            for (History h : history) {
                out.add("  seq=" + h.startSequence() + "->" + h.endSequence() + " " + h.classification() + " legal=" + h.legalCandidates() + "/" + h.legalHistories()
                    + " continuation=" + h.continuationNodes() + " evaluations=" + h.evaluations() + " enumerated=" + h.enumerated() + " excludedExact=" + h.excludedExact() + " excludedEnvelope=" + h.excludedEnvelope()
                    + " nextComplete=" + h.nextComplete() + " obs " + h.observation());
                for (String w : h.witnesses()) out.add("    witness " + w);
                for (PhysicsState s : h.next()) out.add("    next " + state(s));
            }
            out.addAll(world(req, behaviors));
            out.addAll(candidateTable(req, simulator));
            out.addAll(prefilterDifferential(req, res, new InputReconstructor(simulator)));
        } catch (RuntimeException ex) {
            out.add("!! forensic capture failed: " + ex);
        }
        return out;
    }
    /**
     * P9-F3: the cells that can decide the transition - what the observer world holds, and for each the friction, the speed factor and the
     * jump factor the model would use. The friction block of a grounded tick is the one that sets both the decay and the ground speed
     * ({@code 0.216 / friction^3}), so a disagreement here is visible as a scalar error in the next tick's impulse.
     */
    private static List<String> world(MovementValidationRequest req, BlockBehaviorQuery behaviors) {
        List<String> out = new ArrayList<>();
        out.add("--- world cells in reach (observer view, revision " + req.environment().view().revision() + ")");
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY; boolean any = false;
        for (TimingReconstruction.Node n : req.frontier().nodes()) { var p = n.state().position(); minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z()); maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z()); any = true; }
        if (req.timing().target().hasPosition()) { var p = req.timing().target().position(); minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z()); maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z()); any = true; }
        if (!any) { out.add("  (no positioned node)"); return out; }
        int x0 = (int) Math.floor(minX - 1.0), x1 = (int) Math.floor(maxX + 1.0), y0 = (int) Math.floor(minY - 2.0), y1 = (int) Math.floor(maxY + 2.0), z0 = (int) Math.floor(minZ - 1.0), z1 = (int) Math.floor(maxZ + 1.0);
        int printed = 0;
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            BlockBehaviorQuery.Behavior b = behaviors.at(req.environment().view(), x, y, z);
            String desc = b.known() ? b.block().name() + " friction=" + bits(b.block().friction()) + " speedFactor=" + bits(b.block().speedFactor()) + " jumpFactor=" + bits(b.block().jumpFactor())
                : b.status() + "/" + b.worldStatus() + " stateId=" + b.stateId();
            out.add("  cell " + x + "," + y + "," + z + " " + desc);
            if (++printed > 300) { out.add("  (world dump truncated at 300 cells)"); return out; }
        }
        return out;
    }
    private static String describeEvent(TimingEvent e) {
        if (e instanceof TimingEvent.MovementObserved m) return "MovementObserved " + obs(m);
        if (e instanceof TimingEvent.ClientTickEnd t) return "ClientTickEnd seq=" + t.sequence() + " nanos=" + t.nanos();
        return e.getClass().getSimpleName() + " seq=" + e.sequence() + " nanos=" + e.nanos();
    }

    /**
     * §7 / §10: the complete raw 144-tuple enumeration simulated directly from every frontier node for the LAST tick of the transition
     * (the tick the observation constrains), bypassing the reconstructor's equivalence classes and its envelope prefilter entirely. If the
     * client's real input is here with a matching prediction while the production result excluded it, the defect is in the search, not the step.
     */
    private static List<String> candidateTable(MovementValidationRequest req, PhysicsSimulator simulator) {
        List<String> out = new ArrayList<>();
        TimingEvent.MovementObserved target = req.timing().target();
        if (!target.hasPosition()) { out.add("--- raw candidate table: target has no position (rotation-only): skipped"); return out; }
        float yaw = target.hasRotation() ? target.rotation().yaw() : req.timing().lastYaw();
        MovementObservationProfile profile = MovementObservationProfile.PAPER_26_2;
        out.add("--- raw candidate table (144 raw tuples x " + req.frontier().nodes().size() + " node(s), one tick, yaw=" + bits(yaw) + ", prefilter and equivalence classes bypassed)");
        int printed = 0;
        for (TimingReconstruction.Node n : req.frontier().nodes()) {
            for (MovementInput in : InputDomain.enumerate(InputDomain.Constraints.NONE, yaw, List.of(), null)) {
                for (PhysicsState start : n.state().sprintModifierCases(in)) {
                    PhysicsResult r = simulator.simulate(start, in, req.environment());
                    StringBuilder sb = new StringBuilder("  cand ").append(n.label()).append(' ').append(InputDomain.describe(in)).append(' ').append(start.sprintModifier());
                    if (r.trace() != null) sb.append(" speed=").append(bits(r.trace().speed())).append(" friction=").append(bits(r.trace().friction()))
                        .append(" inputVector=").append(vec(r.trace().inputVector())).append(" crouching=").append(r.trace().crouchingInput());
                    if (r.next() == null) { sb.append(" status=").append(r.status()).append(" reasons=").append(r.reasons()); }
                    else {
                        double dx = r.next().position().x() - target.position().x(), dy = r.next().position().y() - target.position().y(), dz = r.next().position().z() - target.position().z();
                        boolean inside = Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9 && Math.abs(dz) < 1e-9;
                        sb.append(" status=").append(r.status()).append(" exact=").append(r.exact())
                          .append(" predPos=").append(vec(r.next().position())).append(" predVel=").append(vec(r.next().velocity()))
                          .append(" onGround=").append(r.next().onGround()).append(" pose=").append(r.next().pose())
                          .append(" hColl=").append(r.next().horizontalCollision()).append(" support=").append(r.next().support().present() ? r.next().support().x() + "," + r.next().support().y() + "," + r.next().support().z() : "none")
                          .append(" d=(").append(bits(dx)).append(", ").append(bits(dy)).append(", ").append(bits(dz)).append(')')
                          .append(" h=").append(bits(Math.sqrt(dx * dx + dz * dz))).append(inside ? " MATCHES-OBSERVATION-EXACTLY" : "");
                    }
                    out.add(sb.toString());
                    if (++printed > 2000) { out.add("  (candidate table truncated at 2000 rows)"); return out; }
                }
            }
        }
        out.add("  observation profile sendThresholdSqr=" + bits(profile.positionSendThresholdSqr()));
        return out;
    }

    /**
     * §9 / §10: the same single tick re-reconstructed directly with the M3 envelope prefilter DISABLED and the raw input domain, from every
     * frontier node. A legal candidate that appears only here proves a prefilter (or equivalence-merge) soundness defect; an identical empty
     * set proves the search is faithful and the mismatch is in the start state, the environment or the observation.
     */
    private static List<String> prefilterDifferential(MovementValidationRequest req, MovementValidationResult res, InputReconstructor reconstructor) {
        List<String> out = new ArrayList<>();
        out.add("--- prefilter / raw-domain differential (§9, §10)");
        TimingEvent.MovementObserved target = req.timing().target();
        if (!target.hasPosition()) { out.add("  target has no position: skipped"); return out; }
        float yaw = target.hasRotation() ? target.rotation().yaw() : req.timing().lastYaw();
        TickObservation tick = TickObservation.exact(target.position().x(), target.position().y(), target.position().z(), yaw,
            target.onGround() ? TickObservation.GroundClaim.TRUE : TickObservation.GroundClaim.FALSE, target.sequence());
        for (boolean pruning : new boolean[] { true, false }) {
            int legal = 0, exclEnv = 0, exclExact = 0; long evals = 0; String status = "-";
            try {
                for (TimingReconstruction.Node n : req.frontier().nodes()) {
                    ReconstructionRequest rr = ReconstructionRequest.of(n.state(), tick, req.environment()).withEnvelopePruning(pruning)
                        .withBudget(new ReconstructionRequest.SearchBudget(256, 65536, 3, 256, 144, 65536));
                    var r = reconstructor.reconstruct(rr);
                    legal += r.candidates().size(); exclEnv += r.counts().excludedEnvelope(); exclExact += r.counts().excludedExact(); evals += r.work().evaluations(); status = r.status().name();
                }
                out.add("  pruning=" + pruning + " candidates=" + legal + " excludedExact=" + exclExact + " excludedEnvelope=" + exclEnv + " evaluations=" + evals + " status=" + status);
            } catch (RuntimeException ex) { out.add("  pruning=" + pruning + " failed: " + ex); }
        }
        out.add("  productionClassification=" + res.classification() + " legal=" + res.counts().legalCandidates() + " proof=" + res.proof().rawEnumerated() + "/" + res.proof().excludedExact() + "/" + res.proof().excludedEnvelope() + " remaining=" + res.proof().candidatesRemaining());
        return out;
    }
    /** §11: the processed impulse of the candidate input families for the failing state, so the residual can be matched against them bit for bit. */
    static List<String> impulses(PhysicsSimulator simulator, PhysicsEnvironment env, float yaw, PlayerBox.Pose pose) {
        List<String> out = new ArrayList<>();
        PhysicsSimulator.ResolvedAttributes walk = simulator.resolveAttributes(env, false), sprint = simulator.resolveAttributes(env, true);
        if (walk == null || sprint == null) { out.add("--- impulse table: attributes unknown"); return out; }
        out.add("--- impulse table (§11) pose=" + pose + " yaw=" + bits(yaw));
        out.add("  walkGround=" + bits(0.98 * walk.speed()) + " sneakGround=" + bits(0.98 * walk.speed() * walk.sneakingSpeed()) + " useGround=" + bits(0.98 * walk.speed() * 0.2)
            + " sneakUseGround=" + bits(0.98 * walk.speed() * walk.sneakingSpeed() * 0.2) + " sprintGround=" + bits(0.98 * sprint.speed()));
        out.add("  walkAir=" + bits(0.98 * MovementProfile.AIR_SPEED) + " sneakAir=" + bits(0.98 * MovementProfile.AIR_SPEED * walk.sneakingSpeed())
            + " sprintAir=" + bits(0.98 * MovementProfile.AIR_SPRINT_SPEED) + " sneakSprintAir=" + bits(0.98 * MovementProfile.AIR_SPRINT_SPEED * walk.sneakingSpeed()));
        return out;
    }
}
