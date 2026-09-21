package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.MovementInput;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.PhysicsResult;
import me.nicolas.papea2ac.physics.PhysicsSimulator;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.player.RttEstimator;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.simulation.InputDomain;
import me.nicolas.papea2ac.simulation.InputReconstructor;
import me.nicolas.papea2ac.simulation.ReconstructionRequest;
import me.nicolas.papea2ac.simulation.ReconstructionResult;
import me.nicolas.papea2ac.simulation.TimingBudget;
import me.nicolas.papea2ac.simulation.TimingHypothesis;
import me.nicolas.papea2ac.simulation.TimingReconstruction;
import me.nicolas.papea2ac.simulation.TimingRequest;
import me.nicolas.papea2ac.simulation.TimingResolver;
import me.nicolas.papea2ac.version.TimingProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.WorldView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
/**
 * Phase 7 diagnostic and real-client qualification tracer ({@code /papea2ac timing <player> arm|report|cancel}): from the arm
 * moment's rest hypothesis it walks the session's published timing events transmission by transmission, asks the TimingResolver
 * for the bounded hypotheses of each interval (tick-end alignment, influence application branches, correction relation) and
 * Phase 6 for the legal inputs under each, and carries the UNION of exact continuations (with per-branch pending influences)
 * forward. It prints what was actually observed and which hypotheses explain it; it never chooses a branch, never prints a
 * verdict, and an unexplained transmission is reported as coverage/timing, not as anything about the player. Bounded: 4 traces,
 * 120 s lifetime, 128 steps, ~120 lines; every physics question goes through InputReconstructor.
 */
public final class TimingTracer {
    public static final int MAX_TRACES = 4, MAX_STEPS = 600, MAX_LINES = 120; static final long LIFETIME_NANOS = 120_000_000_000L;
    private static final class Trace {
        final UUID player; final PhysicsQualification.Scenario declared; final long armedNanos, restSequence; final Vector3 restPosition; final float restYaw; final TimingLedger ledger; final WorldView view; final long worldEpoch, sessionEpoch; final PhysicsQualification.ArmFacts facts;
        Trace(UUID player, PhysicsQualification.Scenario declared, long armedNanos, long restSequence, Vector3 restPosition, float restYaw, TimingLedger ledger, WorldView view, long worldEpoch, long sessionEpoch, PhysicsQualification.ArmFacts facts) {
            this.player = player; this.declared = declared; this.armedNanos = armedNanos; this.restSequence = restSequence; this.restPosition = restPosition; this.restYaw = restYaw; this.ledger = ledger; this.view = view; this.worldEpoch = worldEpoch; this.sessionEpoch = sessionEpoch; this.facts = facts;
        }
    }
    private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
    private final TimingReconstruction timing; private final PhysicsSimulator simulator; private final CollisionEngine collision; private final BlockBehaviorQuery behaviors; private final TimingProfile profile;
    final LongAdder steps = new LongAdder(), explained = new LongAdder(), unexplained = new LongAdder();

    public TimingTracer(InputReconstructor reconstructor, CollisionEngine collision, BlockBehaviorQuery behaviors, TimingProfile profile) {
        this.timing = new TimingReconstruction(new TimingResolver(), reconstructor); this.simulator = reconstructor.simulator(); this.collision = collision; this.behaviors = behaviors; this.profile = profile;
    }
    public int activeTraces() { return traces.size(); }

    public synchronized String arm(UUID player, PhysicsQualification.Scenario declared, TimingLedger ledger, TimelineStatus t, WorldView view, long worldEpoch, long sessionEpoch, PhysicsQualification.ArmFacts facts) {
        expire(System.nanoTime());
        if (!t.hasPosition() || !t.hasRotation()) return "REFUSED: no observed position/rotation yet";
        if (!t.onGround()) return "REFUSED: the player must be standing still on the ground (observed onGround=false) - the rest hypothesis needs a resting start";
        if (traces.size() >= MAX_TRACES && !traces.containsKey(player)) return "REFUSED: " + MAX_TRACES + " traces already armed";
        Trace old = traces.put(player, new Trace(player, declared, System.nanoTime(), t.movementSequence(), new Vector3(t.x(), t.y(), t.z()), t.yaw(), ledger, view, worldEpoch, sessionEpoch, facts));
        if (old != null && old.ledger != ledger) old.ledger.publishEvents(false);
        ledger.publishEvents(true);
        TimingLedger.View v = ledger.view();
        return "ARMED timing" + (declared == null ? "" : " (declared " + declared + ", membership only)") + " at observed rest (" + t.x() + "," + t.y() + "," + t.z() + ") yaw=" + t.yaw() + " movementSequence=" + t.movementSequence() + " worldEpoch=" + worldEpoch
            + " ledger: tickEnds=" + v.counters().clientTickEnds() + " rtt=" + rtt(v.rtt(), System.nanoTime()) + " markers=" + v.markers().sent() + "/" + v.markers().acknowledged() + " - now act (velocity/teleport/movement), then run report";
    }
    public synchronized String cancel(UUID player) { Trace tr = traces.remove(player); if (tr == null) return "no armed trace"; tr.ledger.publishEvents(false); return "trace cancelled"; }
    private void expire(long now) { traces.values().removeIf(tr -> { boolean e = now - tr.armedNanos > LIFETIME_NANOS; if (e) tr.ledger.publishEvents(false); return e; }); }

    static String rtt(RttEstimator.Summary r, long now) {
        if (!r.hasSamples()) return "none";
        return String.format(Locale.ROOT, "last=%.1fms min=%.1fms smoothed=%.1fms jitter=%.1fms max=%.1fms samples=%d outliers=%d age=%dms%s", r.lastNanos() / 1e6, r.minNanos() / 1e6, r.smoothedNanos() / 1e6, r.jitterNanos() / 1e6, r.maxNanos() / 1e6, r.samples(), r.outliers(), r.ageNanos(now) / 1_000_000, r.stale(now) ? " STALE" : "");
    }

    /** Walks every transmission recorded since the arm through timing resolution + reconstruction. */
    public List<String> report(UUID player) {
        Trace tr = traces.get(player);
        if (tr == null) return List.of("no armed timing trace for that player (arm one first)");
        long now = System.nanoTime();
        TimingLedger.View v = tr.ledger.view();
        List<String> out = new ArrayList<>();
        if (v.closed()) out.add("NOTE: this trace belongs to session epoch " + tr.sessionEpoch + ", which is CLOSED (the player disconnected/reconnected); nothing below concerns the new session - arm again");
        out.add("timing trace sessionEpoch=" + tr.sessionEpoch + " worldEpoch=" + v.worldEpoch() + " profile=" + profile.id() + " tickEndCapable=" + v.tickEndCapable() + " events=" + v.events().size() + (v.events().size() >= TimingLedger.PUBLISHED_EVENTS ? " (capped)" : "") + (tr.declared == null ? "" : " declared=" + tr.declared + " (membership only)"));
        out.add("ledger: rtt " + rtt(v.rtt(), now) + " | markers sent=" + v.markers().sent() + " acked=" + v.markers().acknowledged() + " pending=" + v.markers().pending() + " expired=" + v.markers().expired() + " late/unknown=" + v.markers().lateOrUnknown() + " | tickEnds=" + v.counters().clientTickEnds() + " movements=" + v.counters().movements() + " influences=" + v.counters().externalMotions() + " teleports sent=" + v.counters().teleportsSent() + " confirmed=" + v.counters().teleportsConfirmed() + " stale=" + v.counters().staleTeleportConfirms() + " coverageBreaks=" + v.counters().coverageBreaks());
        for (TimingLedger.PendingTeleport t : v.teleports()) out.add("correction " + t.teleportId() + " sent@" + t.sent().sequence() + " -> (" + t.sent().position().x() + "," + t.sent().position().y() + "," + t.sent().position().z() + ") relative=" + t.sent().relativeMask() + (t.confirmed() ? " confirmed@" + t.confirmedSequence() : t.superseded() ? " SUPERSEDED" : t.writeFailed() ? " WRITE_FAILED" : " pending"));
        out.add("facts (server-current, diagnostic): border=" + (tr.facts.borderKnown() ? "known" : "UNKNOWN") + " entitiesNearby=" + (tr.facts.noEntitiesNearby() ? "none" : "UNKNOWN") + " effects=" + tr.facts.effects());
        PhysicsEnvironment env = environment(tr);
        PhysicsState s = PhysicsState.resting(tr.restPosition.x(), tr.restPosition.y(), tr.restPosition.z(), PlayerBox.Pose.STANDING, false);
        for (int i = 0; i < 2; i++) { PhysicsResult r = simulator.simulate(s, MovementInput.idle(tr.restYaw), env); if (r.next() == null) { out.add("NOT QUALIFYING: the rest state cannot be simulated: " + r.summary()); return out; } s = r.next(); }
        if (!s.onGround() || s.position().x() != tr.restPosition.x() || s.position().y() != tr.restPosition.y() || s.position().z() != tr.restPosition.z()) { out.add("NOT QUALIFYING: the observed rest position is not a resting fixed point of the simulator"); return out; }
        List<TimingEvent> events = new ArrayList<>(); for (TimingEvent e : v.events()) if (e.sequence() > tr.restSequence) events.add(e);
        if (events.isEmpty()) { out.add("NOT QUALIFYING: no timing events observed after arming"); return out; }
        Map<String, TimingEvent.ExternalMotionSent> influences = new LinkedHashMap<>();
        TimingReconstruction.Frontier frontier = TimingReconstruction.Frontier.of(s);
        long anchorSeq = tr.restSequence, anchorNanos = events.get(0).nanos(); Vector3 lastSent = tr.restPosition; float lastYaw = tr.restYaw; boolean anchorInTick = true;
        int stepCount = 0, explainedSteps = 0, unexplainedSteps = 0, lines = 0, declaredPresent = 0, declaredAbsent = 0, restResends = 0; Map<String, Integer> statuses = new LinkedHashMap<>(); Map<String, String> influenceOutcome = new LinkedHashMap<>();
        MovementInput declaredKeys = tr.declared == null ? null : tr.declared.input(0f);
        int from = 0;
        for (int i = 0; i < events.size(); i++) {
            TimingEvent e = events.get(i);
            if (e instanceof TimingEvent.ExternalMotionSent m) influences.put(m.id(), m);
            if (!(e instanceof TimingEvent.MovementObserved target)) continue;
            if (stepCount >= MAX_STEPS) { out.add("step cap " + MAX_STEPS + " reached; remaining transmissions not walked"); break; }
            List<TimingEvent> slice = new ArrayList<>(events.subList(from, i + 1)); from = i + 1;
            // The evaluation time of an interval is the moment it closed (the target's capture), not the report's clock: an influence older than the
            // application horizon at report time was still live inside the interval that applied it (P7-F7, real-client qualification).
            TimingRequest template = new TimingRequest(slice, anchorSeq, anchorNanos, anchorInTick, lastSent, lastYaw, target, new ArrayList<>(influences.values()), v.teleports(), v.rtt(), v.tickEndCapable(), v.counters().coverageBreaks() > 0, v.worldEpoch(), target.nanos(), profile, TimingBudget.DEFAULT);
            TimingReconstruction.Result r = timing.run(template, frontier, env, ReconstructionRequest.SearchBudget.DEFAULT);
            stepCount++; steps.increment(); statuses.merge(r.status().name(), 1, Integer::sum);
            List<TimingReconstruction.Step> explaining = r.explaining();
            boolean present = false; if (declaredKeys != null) for (TimingReconstruction.Step st : explaining) if (st.hypothesis().horizon() > 0 && declaredPresent(st, declaredKeys)) { present = true; break; }
            if (declaredKeys != null && !explaining.isEmpty()) { if (present || explaining.stream().allMatch(st -> st.hypothesis().horizon() == 0)) declaredPresent++; else declaredAbsent++; }
            for (TimingReconstruction.Step st : explaining) for (String id : st.hypothesis().appliedInfluences()) { int tick = 0; for (int t = 0; t < st.hypothesis().branch().motionsPerTick().size(); t++) for (var m : st.hypothesis().branch().motionsPerTick().get(t)) if (m.sourceId().equals(id)) tick = t + 1; influenceOutcome.merge(id, "applied@step" + stepCount + "/tick" + tick + "(" + st.hypothesis().horizon() + ")", (a, b) -> a.contains(b) ? a : a + " | " + b); }
            for (TimingReconstruction.Step st : explaining) for (String id : st.hypothesis().pendingInfluences()) influenceOutcome.putIfAbsent(id, "pending through step " + stepCount);
            if (explaining.isEmpty()) { unexplainedSteps++; unexplained.increment(); } else { explainedSteps++; explained.increment(); }
            // A standing player re-sends its position every 20 ticks; a human-paced qualification produces one such uniquely-resolved rest re-send per
            // second for minutes. They count as steps (the chain is walked) but are folded into one line so the interesting transmissions stay
            // visible within MAX_LINES and the step cap holds ten minutes of rest (P7-F9, real-client qualification).
            boolean restResend = target.hasPosition() && target.position().equals(lastSent) && (!target.hasRotation() || target.rotation().yaw() == lastYaw) && r.status() == TimingReconstruction.Status.RESOLVED_UNIQUE && explaining.size() == 1 && explaining.get(0).hypothesis().appliedInfluences().isEmpty() && r.notes().isEmpty();
            if (restResend) restResends++;
            else if (restResends > 0 && lines++ < MAX_LINES) { out.add("    (" + restResends + " rest re-send(s) at the same position resolved uniquely through the silent closure)"); restResends = 0; }
            if (!restResend && lines++ < MAX_LINES) {
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "seq=%d %s%s%s -> %s hyp=%d explaining=%d recon=%d eval=%d", target.sequence(), target.hasPosition() ? String.format(Locale.ROOT, "pos=(%.6f,%.6f,%.6f)", target.position().x(), target.position().y(), target.position().z()) : "rot-only", target.hasRotation() ? " yaw=" + target.rotation().yaw() : "", target.onGround() ? " ground" : "", r.status(), r.work().hypotheses(), explaining.size(), r.work().reconstructions(), r.work().evaluations()));
                if (!r.resolutions().isEmpty()) { var ev = r.resolutions().get(0).evidence(); sb.append(" tickEnds=").append(ev.clientTickEnds()); if (r.resolutions().get(0).hypotheses().size() > 0) sb.append(" align=").append(r.resolutions().get(0).hypotheses().get(0).alignment()); }
                if (declaredKeys != null && !explaining.isEmpty()) sb.append(present ? " declared=PRESENT" : explaining.stream().allMatch(st -> st.hypothesis().horizon() == 0) ? " declared=n/a(teleport)" : " declared=ABSENT");
                int shown = 0; for (TimingReconstruction.Step st : explaining) { if (shown++ == 3) { sb.append(" +").append(explaining.size() - 3); break; } sb.append(" [").append(st.hypothesis().id()).append(' ').append(st.result().status()).append('/').append(st.result().counts().matchingStates()).append(']'); }
                out.add(sb.toString());
                for (String n : r.notes()) if (lines++ < MAX_LINES) out.add("    note: " + n);
                for (var res : r.resolutions()) for (String n : res.notes()) if (lines++ < MAX_LINES && !n.startsWith("no round-trip")) out.add("    timing: " + n);
                if (explaining.isEmpty()) { // diagnostic only: what was tried, and how far the idle prediction under each hypothesis lands from the observation
                    for (TimingReconstruction.Step st : r.steps()) if (lines++ < MAX_LINES) out.add("    tried " + st.hypothesis().describe() + " -> " + st.result().status() + "/" + st.result().counts().matchingStates());
                    for (TimingEvent.ExternalMotionSent m : influences.values()) if (lines++ < MAX_LINES) out.add(String.format(Locale.ROOT, "    influence %s %s %s (%.6f,%.6f,%.6f) sent@%d %+.1fms before the target", m.id(), m.source(), m.operation(), m.vector().x(), m.vector().y(), m.vector().z(), m.sequence(), (target.nanos() - m.nanos()) / 1e6));
                    if (target.hasPosition() && lines++ < MAX_LINES) out.add("    nearest idle prediction (diagnostic only): " + nearest(frontier, r.steps(), target, lastYaw, env));
                }
            }
            if (r.next().isEmpty()) { out.add("chain stops here: no exact continuation under any hypothesis (" + r.status() + ") - coverage/timing, not a verdict"); break; }
            frontier = r.next(); anchorSeq = target.sequence(); anchorNanos = target.nanos(); anchorInTick = r.targetInTick();
            if (target.hasPosition()) lastSent = target.position(); if (target.hasRotation()) lastYaw = target.rotation().yaw();
        }
        if (restResends > 0) out.add("    (" + restResends + " rest re-send(s) at the same position resolved uniquely through the silent closure)");
        for (var e : influenceOutcome.entrySet()) { TimingEvent.ExternalMotionSent m = influences.get(e.getKey()); out.add("influence " + e.getKey() + (m == null ? "" : " " + m.source() + " " + m.operation() + " (" + m.vector().x() + "," + m.vector().y() + "," + m.vector().z() + ") sent@" + m.sequence()) + ": " + e.getValue()); }
        out.add("summary: steps=" + stepCount + " explained=" + explainedSteps + " unexplained=" + unexplainedSteps + " statuses=" + statuses + (declaredKeys == null ? "" : " declaredPresent=" + declaredPresent + " declaredAbsent=" + declaredAbsent) + " influences=" + influences.size());
        if (stepCount < 3) out.add("NOT QUALIFYING: fewer than 3 transmissions walked");
        else if (unexplainedSteps == 0 && (declaredKeys == null || declaredAbsent == 0)) out.add("QUALIFYING: every transmission has at least one explaining timing hypothesis (" + explainedSteps + ")" + (declaredKeys == null ? "" : " and the declared input is in the candidate set of every explained interval"));
        else out.add("NOT QUALIFYING: " + unexplainedSteps + " transmission(s) without an explaining hypothesis" + (declaredAbsent > 0 ? ", declared input absent from " + declaredAbsent : "") + " - timing/coverage/model to investigate, not a verdict");
        return out;
    }
    /** For an unexplained step: the smallest residual between the observed position and an all-idle run of each hypothesis' influence branch from each frontier node (after the hypothesis' closure ticks). Attribution aid, never a candidate. */
    private String nearest(TimingReconstruction.Frontier frontier, List<TimingReconstruction.Step> steps, TimingEvent.MovementObserved target, float yaw, PhysicsEnvironment env) {
        String best = "no simulable run"; double bestD = Double.MAX_VALUE;
        for (TimingReconstruction.Step st : steps) {
            TimingHypothesis h = st.hypothesis(); if (h.horizon() <= 0) continue;
            for (TimingReconstruction.Node n : frontier.nodes()) {
                PhysicsState s = n.state(); boolean ok = true;
                for (int t = 0; t < h.closureTicks() && ok; t++) { PhysicsResult r = simulator.simulate(s, MovementInput.idle(yaw), env); if (r.next() == null) ok = false; else s = r.next(); }
                for (int t = 0; t < h.horizon() && ok; t++) {
                    var motions = t < h.branch().motionsPerTick().size() ? h.branch().motionsPerTick().get(t) : List.<PhysicsEnvironment.ExternalMotion>of();
                    PhysicsResult r = simulator.simulate(s, MovementInput.idle(yaw), motions.isEmpty() ? env : env.withExternalMotions(motions)); if (r.next() == null) ok = false; else s = r.next();
                }
                if (!ok) continue;
                double dx = s.position().x() - target.position().x(), dy = s.position().y() - target.position().y(), dz = s.position().z() - target.position().z(), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < bestD) { bestD = d; best = String.format(Locale.ROOT, "%s from %s: d=(%.3e,%.3e,%.3e) |d|=%.3e onGround=%s", h.id(), n.label(), dx, dy, dz, d, s.onGround()); }
            }
        }
        return best;
    }
    /** The declared keys explain a step when some explaining hypothesis admits k idle ticks then the keys (k < horizon), under that hypothesis' own branch. */
    private static boolean declaredPresent(TimingReconstruction.Step st, MovementInput keys) {
        int h = st.hypothesis().horizon(); MovementInput idle = MovementInput.idle(0f);
        for (int k = 0; k < h; k++) {
            List<MovementInput> hist = new ArrayList<>(h); for (int t = 0; t < h; t++) hist.add(t < k ? idle : keys);
            for (ReconstructionResult.Candidate c : st.result().candidates()) if (c.branchId().equals(st.hypothesis().branch().id()) && c.containsHistory(hist)) return true;
            List<MovementInput> release = new ArrayList<>(h); for (int t = 0; t < h; t++) release.add(t < k ? keys : idle);
            for (ReconstructionResult.Candidate c : st.result().candidates()) if (c.branchId().equals(st.hypothesis().branch().id()) && c.containsHistory(release)) return true;
        }
        return false;
    }
    private PhysicsEnvironment environment(Trace tr) {
        PhysicsEnvironment env = PhysicsEnvironment.of(tr.view, tr.worldEpoch, collision, behaviors, simulator.profile());
        PhysicsEnvironment.Attributes attrs = PhysicsEnvironment.Attributes.defaults(simulator.profile());
        for (var e : tr.facts.attributeBases().entrySet()) if (simulator.profile().attribute(e.getKey()) != null) attrs = attrs.withBase(e.getKey(), e.getValue());
        List<PhysicsEnvironment.EffectInstance> effects = new ArrayList<>();
        for (String ef : tr.facts.effects()) { int at = ef.lastIndexOf('#'); String id = at < 0 ? ef : ef.substring(0, at); int amp = at < 0 ? 0 : Integer.parseInt(ef.substring(at + 1)); effects.add(new PhysicsEnvironment.EffectInstance(id, amp)); }
        return env.withAttributes(attrs).withEffects(new PhysicsEnvironment.Effects(PhysicsEnvironment.Knowledge.KNOWN, effects))
            .withBorder(tr.facts.borderKnown() ? new PhysicsEnvironment.WorldBorder(PhysicsEnvironment.Knowledge.KNOWN, tr.facts.borderMinX(), tr.facts.borderMinZ(), tr.facts.borderMaxX(), tr.facts.borderMaxZ()) : PhysicsEnvironment.WorldBorder.UNKNOWN)
            .withEntities(tr.facts.noEntitiesNearby() ? PhysicsEnvironment.EntityNeighborhood.NONE : PhysicsEnvironment.EntityNeighborhood.UNKNOWN)
            .withEquipment(tr.facts.leatherBoots() ? PhysicsEnvironment.Equipment.LEATHER_BOOTS : PhysicsEnvironment.Equipment.NO_BOOTS);
    }
    static String describe(MovementInput in) { return InputDomain.describe(in); }
}
