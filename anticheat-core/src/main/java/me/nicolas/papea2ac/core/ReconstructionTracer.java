package me.nicolas.papea2ac.core;

import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.WorldView;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Phase 6 diagnostic and real-client qualification tracer ({@code /papea2ac reconstruct <player> arm [declared] | report | cancel}).
 * An operator ARMS a trace for a player standing still; the poller records the following OBSERVED movement samples from the
 * published timeline snapshot (position-bearing samples when the movement sequence advances, rotation-only samples when the
 * yaw changes without a new position); REPORT reconstructs, sample by sample, which LEGAL inputs could explain each
 * observed transition from the supported frontier (the rest hypothesis first, then the exact-witness states of the previous
 * sample), under the 1-, 2- and 3-tick alignment hypotheses (silent intermediate ticks constrained by the client's send
 * threshold) and every yaw alignment a rotation-only packet allows. Nothing is inferred beyond that: the tick alignment is
 * NOT resolved (Phase 7), the declared input (if any) is only checked for membership in the candidate set - it is never
 * assumed - and no line of the output is a verdict. Bounded: {@link #MAX_TRACES} traces, {@link #MAX_SAMPLES} samples,
 * 60 s lifetime, at most {@link #MAX_REQUESTS_PER_REPORT} reconstructions per report, ~120 output lines.
 *
 * <p>Thread-safe; reads only published snapshots (timeline status, the world view of the arm moment); never touches the
 * owner thread or the live server world. Main-thread facts (border, effects, boots, attributes, "no entities nearby") come
 * from the platform at arm time, labelled as such.
 */
public final class ReconstructionTracer {
    public static final int MAX_TRACES = 4, MAX_SAMPLES = 64, MAX_REQUESTS_PER_REPORT = 512; static final long LIFETIME_NANOS = 60_000_000_000L;
    /** A recorded observation: a new position (movement sequence advanced) or a rotation-only change. */
    public record Sample(boolean hasPosition, long movementSequence, double x, double y, double z, boolean onGround, float yaw, float pitch, long nanos) {}
    private static final class Trace {
        final UUID player; final PhysicsQualification.Scenario declared; final long armedNanos; final Sample rest; final WorldView view; final long worldEpoch, sessionEpoch; final PhysicsQualification.ArmFacts facts;
        final List<Sample> samples = new ArrayList<>(); long lastSequence; float lastYaw;
        Trace(UUID player, PhysicsQualification.Scenario declared, long armedNanos, Sample rest, WorldView view, long worldEpoch, long sessionEpoch, PhysicsQualification.ArmFacts facts) {
            this.player = player; this.declared = declared; this.armedNanos = armedNanos; this.rest = rest; this.view = view; this.worldEpoch = worldEpoch; this.sessionEpoch = sessionEpoch; this.facts = facts; this.lastSequence = rest.movementSequence; this.lastYaw = rest.yaw;
        }
    }
    private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
    private final InputReconstructor reconstructor; private final PhysicsSimulator simulator; private final CollisionEngine collision; private final BlockBehaviorQuery behaviors;
    private final Function<UUID, Optional<TimelineStatus>> timeline;
    final LongAdder requests = new LongAdder(), witnessed = new LongAdder(), samplesRecorded = new LongAdder();
    private volatile Thread poller;

    public ReconstructionTracer(InputReconstructor reconstructor, CollisionEngine collision, BlockBehaviorQuery behaviors, Function<UUID, Optional<TimelineStatus>> timeline) {
        this.reconstructor = reconstructor; this.simulator = reconstructor.simulator(); this.collision = collision; this.behaviors = behaviors; this.timeline = timeline;
    }
    public int activeTraces() { return traces.size(); }

    /** Arms a trace: the player's published position becomes the rest state; {@code declared} (may be null) is the scenario the operator says will be performed - checked for membership only. */
    public synchronized String arm(UUID player, PhysicsQualification.Scenario declared, WorldView view, long worldEpoch, long sessionEpoch, PhysicsQualification.ArmFacts facts) {
        expire(System.nanoTime());
        Optional<TimelineStatus> ts = timeline.apply(player);
        if (ts.isEmpty()) return "REFUSED: no admitted session";
        TimelineStatus t = ts.get();
        if (!t.hasPosition() || !t.hasRotation()) return "REFUSED: no observed position/rotation yet";
        if (!t.onGround()) return "REFUSED: the player must be standing still on the ground (observed onGround=false) - the rest hypothesis needs a resting start";
        if (traces.size() >= MAX_TRACES && !traces.containsKey(player)) return "REFUSED: " + MAX_TRACES + " traces already armed";
        Sample rest = new Sample(true, t.movementSequence(), t.x(), t.y(), t.z(), t.onGround(), t.yaw(), t.pitch(), System.nanoTime());
        traces.put(player, new Trace(player, declared, rest.nanos(), rest, view, worldEpoch, sessionEpoch, facts));
        ensurePoller();
        return "ARMED reconstruction" + (declared == null ? "" : " (declared " + declared + ", checked for membership only)") + " at observed rest (" + t.x() + "," + t.y() + "," + t.z() + ") yaw=" + t.yaw() + " movementSequence=" + t.movementSequence() + " worldEpoch=" + worldEpoch + " worldRevision=" + view.revision() + " - now move, then run report";
    }
    public synchronized String cancel(UUID player) { return traces.remove(player) == null ? "no armed trace" : "trace cancelled"; }

    private void ensurePoller() {
        Thread p = poller;
        if (p != null && p.isAlive()) return;
        p = new Thread(this::pollLoop, "papea2ac-reconstruction-poller"); p.setDaemon(true); poller = p; p.start();
    }
    private void pollLoop() {
        while (!traces.isEmpty()) {
            long now = System.nanoTime();
            for (Trace tr : traces.values()) {
                if (now - tr.armedNanos > LIFETIME_NANOS) { traces.remove(tr.player); continue; }
                Optional<TimelineStatus> ts = timeline.apply(tr.player);
                if (ts.isEmpty()) continue;
                TimelineStatus t = ts.get();
                synchronized (tr) {
                    if (tr.samples.size() >= MAX_SAMPLES) continue;
                    if (t.movementSequence() != tr.lastSequence) { tr.samples.add(new Sample(true, t.movementSequence(), t.x(), t.y(), t.z(), t.onGround(), t.yaw(), t.pitch(), now)); tr.lastSequence = t.movementSequence(); tr.lastYaw = t.yaw(); samplesRecorded.increment(); }
                    else if (t.yaw() != tr.lastYaw) { tr.samples.add(new Sample(false, t.movementSequence(), t.x(), t.y(), t.z(), t.onGround(), t.yaw(), t.pitch(), now)); tr.lastYaw = t.yaw(); samplesRecorded.increment(); }
                }
            }
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
        }
    }
    private void expire(long now) { traces.values().removeIf(tr -> now - tr.armedNanos > LIFETIME_NANOS); }

    /** Reconstructs every recorded transition from the rest hypothesis onward. Bounded output. */
    public List<String> report(UUID player) {
        Trace tr = traces.get(player);
        if (tr == null) return List.of("no armed trace for that player (arm one first)");
        List<Sample> samples; synchronized (tr) { samples = new ArrayList<>(tr.samples); }
        List<String> out = new ArrayList<>();
        out.add("input reconstruction trace sessionEpoch=" + tr.sessionEpoch + " worldEpoch=" + tr.worldEpoch + " worldRevision=" + tr.view.revision() + " profile=" + simulator.profile().id() + " observation=" + MovementObservationProfile.PAPER_26_2.id() + (tr.declared == null ? "" : " declared=" + tr.declared + " (membership check only)"));
        out.add("rest (observed) pos=" + tr.rest.x + "," + tr.rest.y + "," + tr.rest.z + " yaw=" + tr.rest.yaw + " movementSequence=" + tr.rest.movementSequence + " samples=" + samples.size() + (samples.size() >= MAX_SAMPLES ? " (capped)" : ""));
        out.add("facts (server-current, diagnostic): border=" + (tr.facts.borderKnown() ? "known" : "UNKNOWN") + " entitiesNearby=" + (tr.facts.noEntitiesNearby() ? "none" : "UNKNOWN") + " boots=" + tr.facts.leatherBoots() + " effects=" + tr.facts.effects() + " attributeBases=" + tr.facts.attributeBases());
        PhysicsEnvironment env = environment(tr);
        PhysicsState s = PhysicsState.resting(tr.rest.x, tr.rest.y, tr.rest.z, PlayerBox.Pose.STANDING, false);
        for (int i = 0; i < 2; i++) { PhysicsResult r = simulator.simulate(s, MovementInput.idle(tr.rest.yaw), env); if (r.next() == null) { out.add("NOT QUALIFYING: the rest state cannot be simulated: " + r.summary()); return out; } s = r.next(); }
        if (!s.onGround() || s.position().x() != tr.rest.x || s.position().y() != tr.rest.y || s.position().z() != tr.rest.z) { out.add("NOT QUALIFYING: the observed rest position is not a resting fixed point of the simulator (" + s.position() + ", onGround=" + s.onGround() + ")"); return out; }
        if (samples.isEmpty()) { out.add("NOT QUALIFYING: no samples observed after arming"); return out; }
        // frontier continuation: the exact-witness states of the last reconstructed sample seed the next one
        List<ReconstructionRequest.Start> frontier = List.of(ReconstructionRequest.ConcreteStart.of(s));
        double lastX = tr.rest.x, lastY = tr.rest.y, lastZ = tr.rest.z; float yawInEffect = tr.rest.yaw; List<Float> pendingYaws = new ArrayList<>();
        int used = 0, positionSamples = 0, declaredPresent = 0, declaredAbsent = 0, unique = 0, ambiguous = 0, incomplete = 0, none = 0, alignedH1 = 0, gapH = 0, lines = 0, inexactClosures = 0, releaseIdle = 0, prePressIdle = 0; boolean released = false;
        MovementInput declaredKeys = tr.declared == null ? null : tr.declared.input(0f);
        for (int i = 0; i < samples.size(); i++) {
            Sample smp = samples.get(i);
            if (!smp.hasPosition) { pendingYaws.add(smp.yaw); continue; } // a rotation-only packet: the position stayed inside the send-threshold ball; its yaw applies from an unknown tick within the gap
            positionSamples++;
            if (smp.x == lastX && smp.y == lastY && smp.z == lastZ && pendingYaws.isEmpty() && i < samples.size() - 1 && used < MAX_REQUESTS_PER_REPORT) {
                // periodic re-send of the same position at rest: a silent stretch - the frontier becomes its own one-tick silent closure (standing/crouching rest variants), no line
                ReconstructionResult still = reconstructor.reconstruct(ReconstructionRequest.of(s, TickObservation.silent(lastX, lastY, lastZ, yawInEffect, smp.movementSequence), env).withFrontier(frontier)); used++; requests.increment();
                LinkedHashMap<PhysicsState, ReconstructionRequest.Start> d = new LinkedHashMap<>(); for (ReconstructionRequest.Start st : still.frontier()) if (st instanceof ReconstructionRequest.ConcreteStart c) d.putIfAbsent(c.state(), c);
                // matchingStates counts incomplete (undecided/envelope) nodes too; only EXACT witnesses can seed the continuation - with none, the frontier before the closure stays in effect
                if (!d.isEmpty()) { frontier = new ArrayList<>(d.values()); s = ((ReconstructionRequest.ConcreteStart) frontier.get(0)).state(); } else inexactClosures++;
                continue;
            }
            // hypotheses: H = 1..3 ticks between the last transmitted position and this one; intermediate ticks silent; yaw alignments for pending rotation samples
            ReconstructionResult best = null; String bestLabel = ""; List<ReconstructionRequest.Start> nextFrontier = new ArrayList<>(); int hUsed = 0; StringBuilder hyp = new StringBuilder();
            for (int h = 1; h <= 3 && used < MAX_REQUESTS_PER_REPORT; h++) {
                for (float[] yaws : yawAlignments(h, yawInEffect, pendingYaws, smp.yaw)) {
                    List<TickObservation> ticks = new ArrayList<>(h);
                    for (int t = 0; t < h - 1; t++) ticks.add(TickObservation.silent(lastX, lastY, lastZ, yaws[t], smp.movementSequence - h + 1 + t));
                    ticks.add(TickObservation.exact(smp.x, smp.y, smp.z, yaws[h - 1], smp.onGround ? TickObservation.GroundClaim.TRUE : TickObservation.GroundClaim.FALSE, smp.movementSequence));
                    ReconstructionResult r = reconstructor.reconstruct(ReconstructionRequest.of(s, ticks, env).withFrontier(frontier)); used++; requests.increment();
                    hyp.append(hyp.length() > 0 ? " " : "").append("H").append(h).append(yaws.length > 1 && !allSame(yaws) ? "y" : "").append('=').append(r.status()).append('/').append(r.counts().matchingStates());
                    if (r.counts().matchingStates() > 0) { nextFrontier.addAll(r.frontier()); if (best == null) { best = r; bestLabel = "H" + h; hUsed = h; } }
                    else if (best == null && (r.complete() || bestLabel.isEmpty())) { /* keep looking */ }
                    if (used >= MAX_REQUESTS_PER_REPORT) break;
                }
                if (best != null && h == 1) break; // the 1-tick hypothesis explains the transition: longer alignments are not needed for continuation
            }
            String line = String.format(Locale.ROOT, "seq=%d pos=(%.6f,%.6f,%.6f) yaw=%s %s", smp.movementSequence, smp.x, smp.y, smp.z, smp.yaw, hyp);
            if (best == null) {
                none++; out.add(line + " -> NO CANDIDATE under H<=3 (not a verdict: timing/coverage - see statuses); chain stops here");
                out.add("  nearest (diagnostic only, one tick from " + frontier.size() + " frontier state(s), yaw " + smp.yaw + (pendingYaws.isEmpty() ? "" : ", pending rotations " + pendingYaws) + "): " + nearest(frontier, smp, env));
                break;
            }
            if (best.complete()) { if (best.ambiguity() == ReconstructionResult.Ambiguity.UNIQUE) unique++; else ambiguous++; } else incomplete++;
            if (hUsed == 1) alignedH1++; else gapH++;
            String declaredNote = "";
            if (declaredKeys != null) {
                // scenario phases: idle before the press (whole idle ticks, then k idle ticks and the keys), held, then released (the keys then idle) - the tails are explained by idle, a wrong declaration stays ABSENT during the held phase
                boolean present = declaredPresent(best, declaredKeys, hUsed);
                if (released) { if (releasePresent(best, declaredKeys, hUsed)) { releaseIdle++; declaredNote = " released=IDLE"; } else if (present) { declaredPresent++; witnessed.increment(); declaredNote = " declared=PRESENT"; } else { declaredAbsent++; declaredNote = " declared=ABSENT"; } }
                else if (present) { declaredPresent++; witnessed.increment(); declaredNote = " declared=PRESENT"; }
                else if (declaredPresent > 0 && releasePresent(best, declaredKeys, hUsed)) { released = true; releaseIdle++; declaredNote = " released=IDLE"; }
                else if (declaredPresent == 0 && declaredPresent(best, MovementInput.idle(0f), hUsed)) { prePressIdle++; declaredNote = " pre-press=IDLE"; }
                else { declaredAbsent++; declaredNote = " declared=ABSENT"; }
            }
            if (lines++ < 40) {
                StringBuilder cs = new StringBuilder();
                int shown = 0; for (ReconstructionResult.Candidate c : best.candidates()) { if (shown++ == 4) { cs.append(" +").append(best.candidates().size() - 4); break; } cs.append(shown > 1 ? " " : "").append(historyText(c)).append(c.historyCount() > 1 ? "(+" + (c.historyCount() - 1) + ")" : "").append(c.match() == ReconstructionResult.Match.EXACT_WITNESS ? "" : "?"); }
                out.add(line + " -> " + bestLabel + " " + best.status() + " states=" + best.counts().matchingStates() + " histories=" + best.counts().matchingHistories() + declaredNote + " eval=" + best.work().evaluations() + " [" + cs + "]");
            }
            // continuation: dedupe the frontier by state, bounded
            LinkedHashMap<PhysicsState, ReconstructionRequest.Start> dedup = new LinkedHashMap<>();
            for (ReconstructionRequest.Start st : nextFrontier) if (st instanceof ReconstructionRequest.ConcreteStart c && dedup.size() < ReconstructionRequest.SearchBudget.DEFAULT.maxNodes()) dedup.putIfAbsent(c.state(), c);
            if (dedup.isEmpty()) { out.add("chain stops here: no exact witness state to continue from (" + best.status() + ", " + best.counts().matchingStates() + " incomplete state(s)) - coverage, not a verdict"); break; }
            frontier = new ArrayList<>(dedup.values()); s = ((ReconstructionRequest.ConcreteStart) frontier.get(0)).state();
            lastX = smp.x; lastY = smp.y; lastZ = smp.z; yawInEffect = smp.yaw; pendingYaws.clear();
        }
        out.add("summary: positionSamples=" + positionSamples + " reconstructed=" + (unique + ambiguous + incomplete) + " unique=" + unique + " ambiguous=" + ambiguous + " incomplete=" + incomplete + " noCandidate=" + none + " alignedH1=" + alignedH1 + " gapHypotheses=" + gapH + " inexactRestClosures=" + inexactClosures + " requests=" + used + (declaredKeys == null ? "" : " declaredPresent=" + declaredPresent + " declaredAbsent=" + declaredAbsent + " releaseIdle=" + releaseIdle + " prePressIdle=" + prePressIdle));
        if (declaredKeys != null) out.add(declaredAbsent == 0 && declaredPresent >= 3 ? "QUALIFYING: the declared input is in the candidate set of every reconstructed transition (" + declaredPresent + ")" : declaredPresent + declaredAbsent < 3 ? "NOT QUALIFYING: fewer than 3 reconstructed transitions" : "NOT QUALIFYING: the declared input is absent from " + declaredAbsent + " transition(s) - a declaration/alignment discrepancy to investigate, not a verdict");
        return out;
    }
    /** Diagnostic for a NO CANDIDATE line: the three raw inputs whose one-tick prediction from the frontier states lands closest to the observed position (with residuals), so a miss is attributable to timing, rotation bookkeeping or the model - never a candidate. */
    private String nearest(List<ReconstructionRequest.Start> frontier, Sample smp, PhysicsEnvironment env) {
        record Near(String keys, String start, double dx, double dy, double dz, String status) { double d() { return Math.sqrt(dx * dx + dy * dy + dz * dz); } }
        List<Near> near = new ArrayList<>();
        for (ReconstructionRequest.Start st : frontier) {
            if (!(st instanceof ReconstructionRequest.ConcreteStart c)) continue;
            for (MovementInput in : InputDomain.enumerate(InputDomain.Constraints.NONE, smp.yaw)) {
                PhysicsResult r = simulator.simulate(c.state(), in, env);
                if (r.next() == null) continue;
                near.add(new Near(InputDomain.describe(in), c.label(), r.next().position().x() - smp.x, r.next().position().y() - smp.y, r.next().position().z() - smp.z, r.status().name()));
            }
        }
        near.sort(java.util.Comparator.comparingDouble(Near::d));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, near.size()); i++) { Near n = near.get(i); sb.append(i > 0 ? "; " : "").append(n.keys).append('@').append(n.start).append(String.format(Locale.ROOT, " d=(%.3e,%.3e,%.3e) |d|=%.3e %s", n.dx, n.dy, n.dz, n.d(), n.status)); }
        return near.isEmpty() ? "no simulable input" : sb.toString();
    }
    private static boolean allSame(float[] y) { for (float v : y) if (v != y[0]) return false; return true; }
    /** Yaw per tick for an h-tick hypothesis: the yaw in effect at the start, switching to each pending rotation at every admissible tick (monotone), the position packet's own yaw last. Bounded: at most 3 pending rotations, 3 ticks. */
    public static List<float[]> yawAlignments(int h, float start, List<Float> pending, float last) {
        List<float[]> out = new ArrayList<>();
        if (pending.isEmpty() || pending.size() > 3) { float[] y = new float[h]; for (int t = 0; t < h - 1; t++) y[t] = start; y[h - 1] = last; out.add(y); return out; }
        // choose switch ticks 1 <= s1 <= s2 <= ... <= h-1 for the intermediate ticks (the last tick has its own yaw)
        int k = pending.size();
        int[] sw = new int[k]; enumerate(sw, 0, 1, h, pending, start, last, out);
        if (out.isEmpty()) { float[] y = new float[h]; for (int t = 0; t < h - 1; t++) y[t] = pending.get(k - 1); y[h - 1] = last; out.add(y); }
        return out;
    }
    private static void enumerate(int[] sw, int i, int from, int h, List<Float> pending, float start, float last, List<float[]> out) {
        if (i == sw.length) { float[] y = new float[h]; for (int t = 0; t < h - 1; t++) { float v = start; for (int j = 0; j < sw.length; j++) if (t + 1 >= sw[j]) v = pending.get(j); y[t] = v; } y[h - 1] = last; out.add(y); return; }
        for (int s = from; s <= h; s++) { sw[i] = s; enumerate(sw, i + 1, s, h, pending, start, last, out); }
    }
    /** The declared keys explain an h-tick transition when some candidate history is k idle ticks (the keys not yet pressed) followed by the declared keys for the remaining ticks, for any k in [0, h-1]. */
    private static boolean declaredPresent(ReconstructionResult r, MovementInput keys, int h) {
        MovementInput idle = MovementInput.idle(0f);
        for (int k = 0; k < h; k++) { List<MovementInput> hist = new ArrayList<>(h); for (int t = 0; t < h; t++) hist.add(t < k ? idle : keys); if (r.admits(hist)) return true; }
        return false;
    }
    /** The release of the declared keys explains an h-tick transition when some candidate history is the keys for j ticks followed by idle, for any j in [0, h-1] (j = 0: released before the gap). */
    private static boolean releasePresent(ReconstructionResult r, MovementInput keys, int h) {
        MovementInput idle = MovementInput.idle(0f);
        for (int j = 0; j < h; j++) { List<MovementInput> hist = new ArrayList<>(h); for (int t = 0; t < h; t++) hist.add(t < j ? keys : idle); if (r.admits(hist)) return true; }
        return false;
    }
    private static String historyText(ReconstructionResult.Candidate c) { StringBuilder sb = new StringBuilder(); for (int t = 0; t < c.input().size(); t++) sb.append(t > 0 ? ">" : "").append(InputDomain.describe(c.input().get(t))); return sb.toString(); }
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
}
