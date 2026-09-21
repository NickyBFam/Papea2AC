package me.nicolas.papea2ac.core;

import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.WorldView;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Phase 5 real-client physics qualification support (PHASE_5_IMPLEMENTATION.md "REAL CLIENT QUALIFICATION"): an operator
 * ARMS a controlled-input scenario for a player who is standing still ("release all keys", "hold W", ...), the poller
 * records the following OBSERVED movement samples from the published timeline snapshot, and REPORT replays the same
 * scenario's EXPLICIT input through the simulator from the observed rest state, printing predicted vs observed per tick.
 * The input is never inferred: it is the scenario the operator declared; a mismatch is a discrepancy to investigate,
 * never "cheating". Bounded: at most {@link #MAX_TRACES} traces, {@link #MAX_SAMPLES} samples each, 60 s lifetime.
 *
 * Everything is thread-safe and reads only published snapshots (the timeline status and the published world view of the
 * arm moment); it never touches the owner thread or the live server world. The world border, effects, boots and the
 * "no entities nearby" fact are supplied by the platform at arm time (main-thread facts, labelled as such).
 */
public final class PhysicsQualification {
    public static final int MAX_TRACES = 4, MAX_SAMPLES = 64; static final long LIFETIME_NANOS = 60_000_000_000L;
    /** Controlled input scenarios the operator can declare (INPUT_RECONSTRUCTION.md: inputs are hypotheses; here they are declared, not reconstructed). */
    public enum Scenario {
        RELEASE_ALL(0, 0, false, false, false), HOLD_W(1, 0, false, false, false), HOLD_S(-1, 0, false, false, false), HOLD_A(0, 1, false, false, false), HOLD_D(0, -1, false, false, false),
        W_SPRINT(1, 0, false, false, true), JUMP_STATIONARY(0, 0, true, false, false), W_JUMP(1, 0, true, false, false), W_SHIFT(1, 0, false, true, false), W_SPRINT_JUMP(1, 0, true, false, true),
        /** Phase 6 additions (diagonals and item use; additive, the Phase 5 scenarios are unchanged). */
        W_A(1, 1, false, false, false), W_D(1, -1, false, false, false), W_USE(1, 0, false, false, false, true);
        final int forward, strafe; final boolean jump, shift, sprint, use;
        Scenario(int forward, int strafe, boolean jump, boolean shift, boolean sprint) { this(forward, strafe, jump, shift, sprint, false); }
        Scenario(int forward, int strafe, boolean jump, boolean shift, boolean sprint, boolean use) { this.forward = forward; this.strafe = strafe; this.jump = jump; this.shift = shift; this.sprint = sprint; this.use = use; }
        MovementInput input(float yaw) { return new MovementInput(forward, strafe, jump, shift, sprint, use, yaw); }
        public static Scenario parse(String s) { try { return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('+', '_').replace('-', '_')); } catch (IllegalArgumentException e) { return null; } }
    }
    public record Sample(long movementSequence, double x, double y, double z, boolean onGround, float yaw, float pitch, long nanos) {}
    /** Main-thread facts the platform supplies when arming (server-current, diagnostic provenance). */
    public record ArmFacts(boolean borderKnown, double borderMinX, double borderMinZ, double borderMaxX, double borderMaxZ, boolean noEntitiesNearby, boolean leatherBoots, List<String> effects, Map<String, Double> attributeBases) {
        public ArmFacts { effects = List.copyOf(effects); attributeBases = Map.copyOf(attributeBases); }
    }
    private static final class Trace {
        final UUID player; final Scenario scenario; final long armedNanos; final Sample rest; final WorldView view; final long worldEpoch; final long sessionEpoch; final ArmFacts facts;
        final List<Sample> samples = new ArrayList<>(); long lastSequence;
        Trace(UUID player, Scenario scenario, long armedNanos, Sample rest, WorldView view, long worldEpoch, long sessionEpoch, ArmFacts facts) { this.player = player; this.scenario = scenario; this.armedNanos = armedNanos; this.rest = rest; this.view = view; this.worldEpoch = worldEpoch; this.sessionEpoch = sessionEpoch; this.facts = facts; this.lastSequence = rest.movementSequence; }
    }
    private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
    private final PhysicsSimulator simulator; private final CollisionEngine collision; private final BlockBehaviorQuery behaviors; private final MovementProfile profile;
    private final Function<UUID, Optional<TimelineStatus>> timeline;
    final LongAdder steps = new LongAdder(), exact = new LongAdder();
    private volatile Thread poller;

    public PhysicsQualification(PhysicsSimulator simulator, CollisionEngine collision, BlockBehaviorQuery behaviors, Function<UUID, Optional<TimelineStatus>> timeline) {
        this.simulator = simulator; this.collision = collision; this.behaviors = behaviors; this.profile = simulator.profile(); this.timeline = timeline;
    }
    public int activeTraces() { return traces.size(); }

    /** Arms a trace: the player's published position becomes the rest state. Returns a one-line acceptance or refusal (never throws for operator input). */
    public synchronized String arm(UUID player, Scenario scenario, WorldView view, long worldEpoch, long sessionEpoch, ArmFacts facts) {
        expire(System.nanoTime());
        Optional<TimelineStatus> ts = timeline.apply(player);
        if (ts.isEmpty()) return "REFUSED: no admitted session";
        TimelineStatus t = ts.get();
        if (!t.hasPosition() || !t.hasRotation()) return "REFUSED: no observed position/rotation yet";
        if (!t.onGround()) return "REFUSED: the player must be standing still on the ground (observed onGround=false) - the rest hypothesis needs a resting start";
        if (traces.size() >= MAX_TRACES && !traces.containsKey(player)) return "REFUSED: " + MAX_TRACES + " traces already armed";
        Sample rest = new Sample(t.movementSequence(), t.x(), t.y(), t.z(), t.onGround(), t.yaw(), t.pitch(), System.nanoTime());
        traces.put(player, new Trace(player, scenario, rest.nanos(), rest, view, worldEpoch, sessionEpoch, facts));
        ensurePoller();
        return "ARMED " + scenario + " at observed rest (" + t.x() + "," + t.y() + "," + t.z() + ") yaw=" + t.yaw() + " movementSequence=" + t.movementSequence() + " worldEpoch=" + worldEpoch + " worldRevision=" + view.revision() + " - now perform the scenario, then run report";
    }
    public synchronized String cancel(UUID player) { return traces.remove(player) == null ? "no armed trace" : "trace cancelled"; }

    private void ensurePoller() {
        Thread p = poller;
        if (p != null && p.isAlive()) return;
        p = new Thread(this::pollLoop, "papea2ac-physics-qualification-poller"); p.setDaemon(true); poller = p; p.start();
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
                    if (t.movementSequence() != tr.lastSequence && tr.samples.size() < MAX_SAMPLES) { tr.samples.add(new Sample(t.movementSequence(), t.x(), t.y(), t.z(), t.onGround(), t.yaw(), t.pitch(), now)); tr.lastSequence = t.movementSequence(); }
                }
            }
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
        }
    }
    private void expire(long now) { traces.values().removeIf(tr -> now - tr.armedNanos > LIFETIME_NANOS); }

    /** Replays the armed scenario through the simulator from the rest state and compares tick by tick with the recorded samples. Bounded output (at most ~80 lines). */
    public List<String> report(UUID player) {
        Trace tr = traces.get(player);
        if (tr == null) return List.of("no armed trace for that player (arm one first)");
        List<Sample> samples; synchronized (tr) { samples = new ArrayList<>(tr.samples); }
        List<String> out = new ArrayList<>();
        out.add("physics qualification " + tr.scenario + " sessionEpoch=" + tr.sessionEpoch + " worldEpoch=" + tr.worldEpoch + " worldRevision=" + tr.view.revision() + " throughSequence=" + tr.view.sequence() + " profile=" + profile.id());
        out.add("rest (observed) pos=" + tr.rest.x + "," + tr.rest.y + "," + tr.rest.z + " yaw=" + tr.rest.yaw + " movementSequence=" + tr.rest.movementSequence + " samples=" + samples.size() + (samples.size() >= MAX_SAMPLES ? " (capped)" : ""));
        out.add("facts (server-current, diagnostic): border=" + (tr.facts.borderKnown() ? "known" : "UNKNOWN") + " entitiesNearby=" + (tr.facts.noEntitiesNearby() ? "none" : "UNKNOWN") + " boots=" + tr.facts.leatherBoots() + " effects=" + tr.facts.effects() + " attributeBases=" + tr.facts.attributeBases());
        PhysicsEnvironment env = environment(tr);
        // rest hypothesis: a player standing still carries the resting velocity and its supporting block; two idle ticks from a zero-velocity stand establish both
        PhysicsState s = PhysicsState.resting(tr.rest.x, tr.rest.y, tr.rest.z, PlayerBox.Pose.STANDING, false); // the observed onGround claim is never a simulation input: two idle ticks derive it from geometry
        for (int i = 0; i < 2; i++) { PhysicsResult r = simulator.simulate(s, MovementInput.idle(tr.rest.yaw), env); steps.increment(); if (r.next() == null) { out.add("NOT QUALIFYING: the rest state cannot be simulated: " + r.summary()); return out; } s = r.next(); }
        Aabb restBox = PlayerBox.of(profile.collision(), PlayerBox.Pose.STANDING, tr.rest.x, tr.rest.y, tr.rest.z);
        out.add("rest collision: " + collision.query(tr.view, restBox, ShapeContext.body(tr.rest.y), tr.worldEpoch).summary() + " simulatedRest onGround=" + s.onGround() + " support=" + (s.support().present() ? s.support().x() + "," + s.support().y() + "," + s.support().z() : "none") + " vel=" + s.velocity());
        if (!s.onGround() || !samePlace(s, tr.rest)) { out.add("NOT QUALIFYING: the observed rest position is not a resting fixed point of the simulator (" + s.position() + ", onGround=" + s.onGround() + ") - world coverage or geometry mismatch at the start"); return out; }
        if (samples.isEmpty()) { out.add("NOT QUALIFYING: no movement samples observed after arming"); return out; }
        // alignment: the first sample that differs from rest is the result of scenario tick 1; identical samples before it are idle keep-alives
        int first = 0; while (first < samples.size() && samePlace(samples.get(first), tr.rest)) first++;
        if (tr.scenario == Scenario.RELEASE_ALL) {
            int idle = 0; for (Sample smp : samples) if (samePlace(smp, tr.rest)) idle++;
            out.add((first == samples.size() ? "QUALIFYING" : "MISMATCH") + ": release-all-keys keeps the rest position - " + idle + "/" + samples.size() + " samples identical to rest; prediction: no movement (idle ticks are a fixed point)");
            if (first < samples.size()) out.add("  first differing sample " + describe(samples.get(first)));
            return out;
        }
        if (first == samples.size()) { out.add("NOT QUALIFYING: no sample differs from the rest position (scenario not performed, or samples not yet received)"); return out; }
        if (first > 12) out.add("note: " + first + " idle samples before the first movement");
        List<Sample> moving = samples.subList(first, samples.size());
        int compared = 0, exactTicks = 0; double maxDx = 0, maxDy = 0, maxDz = 0; PhysicsState state = s; String firstMismatch = null; StringBuilder statuses = new StringBuilder();
        for (int i = 0; i < moving.size() && i < 40; i++) {
            Sample smp = moving.get(i);
            PhysicsResult r = simulator.simulate(state, tr.scenario.input(smp.yaw()), env); steps.increment();
            if (r.next() == null) { out.add("t=" + (i + 1) + " simulator: " + r.summary()); break; }
            if (r.exact()) exact.increment();
            double dx = smp.x - r.next().position().x(), dy = smp.y - r.next().position().y(), dz = smp.z - r.next().position().z();
            boolean bit = Double.doubleToRawLongBits(smp.x) == Double.doubleToRawLongBits(r.next().position().x()) && Double.doubleToRawLongBits(smp.y) == Double.doubleToRawLongBits(r.next().position().y()) && Double.doubleToRawLongBits(smp.z) == Double.doubleToRawLongBits(r.next().position().z());
            if (bit) exactTicks++; else if (firstMismatch == null) firstMismatch = "t=" + (i + 1);
            maxDx = Math.max(maxDx, Math.abs(dx)); maxDy = Math.max(maxDy, Math.abs(dy)); maxDz = Math.max(maxDz, Math.abs(dz));
            if (statuses.indexOf(r.status().name()) < 0) statuses.append(statuses.length() > 0 ? "," : "").append(r.status().name());
            if (i < 24) out.add(String.format(Locale.ROOT, "t=%d pred=(%.6f,%.6f,%.6f) obs=(%.6f,%.6f,%.6f) d=(%.2e,%.2e,%.2e) %s ground pred=%s obs=%s%s", i + 1, r.next().position().x(), r.next().position().y(), r.next().position().z(), smp.x, smp.y, smp.z, dx, dy, dz, bit ? "BIT-EXACT" : "diff", r.next().onGround(), smp.onGround, r.exact() ? "" : " " + r.status() + (r.reasons().isEmpty() ? "" : " " + r.reasons().get(0).code())));
            compared++; state = r.next();
        }
        out.add("summary: compared=" + compared + " bitExact=" + exactTicks + " maxAbsDiff=(" + maxDx + "," + maxDy + "," + maxDz + ") simulatorStatuses=" + statuses + (firstMismatch == null ? "" : " firstMismatch=" + firstMismatch));
        // timing ambiguity check: would the observed first displacement fit tick 2 better than tick 1 (a missing/merged first packet)?
        if (exactTicks < compared) {
            PhysicsResult r1 = simulator.simulate(s, tr.scenario.input(moving.get(0).yaw()), env); steps.increment();
            if (r1.next() != null) { PhysicsResult r2 = simulator.simulate(r1.next(), tr.scenario.input(moving.get(0).yaw()), env); steps.increment();
                if (r2.next() != null && dist(r2.next(), moving.get(0)) < dist(r1.next(), moving.get(0)) * 0.5) out.add("NOT QUALIFYING (timing-ambiguous): the first observed sample matches the SECOND predicted tick better than the first - a movement packet was merged or dropped at the start"); }
        }
        out.add(compared >= 3 && exactTicks == compared ? "QUALIFYING: " + compared + " ticks bit-exact" : compared >= 3 ? "QUALIFYING TRACE WITH DISCREPANCIES: " + (compared - exactTicks) + " of " + compared + " ticks differ (see d=...); not a verdict" : "NOT QUALIFYING: fewer than 3 moving samples");
        return out;
    }
    private PhysicsEnvironment environment(Trace tr) {
        PhysicsEnvironment env = PhysicsEnvironment.of(tr.view, tr.worldEpoch, collision, behaviors, profile);
        PhysicsEnvironment.Attributes attrs = PhysicsEnvironment.Attributes.defaults(profile);
        for (var e : tr.facts.attributeBases().entrySet()) if (profile.attribute(e.getKey()) != null) attrs = attrs.withBase(e.getKey(), e.getValue());
        List<PhysicsEnvironment.EffectInstance> effects = new ArrayList<>();
        for (String ef : tr.facts.effects()) { int at = ef.lastIndexOf('#'); String id = at < 0 ? ef : ef.substring(0, at); int amp = at < 0 ? 0 : Integer.parseInt(ef.substring(at + 1)); effects.add(new PhysicsEnvironment.EffectInstance(id, amp)); }
        return env.withAttributes(attrs).withEffects(new PhysicsEnvironment.Effects(PhysicsEnvironment.Knowledge.KNOWN, effects))
            .withBorder(tr.facts.borderKnown() ? new PhysicsEnvironment.WorldBorder(PhysicsEnvironment.Knowledge.KNOWN, tr.facts.borderMinX(), tr.facts.borderMinZ(), tr.facts.borderMaxX(), tr.facts.borderMaxZ()) : PhysicsEnvironment.WorldBorder.UNKNOWN)
            .withEntities(tr.facts.noEntitiesNearby() ? PhysicsEnvironment.EntityNeighborhood.NONE : PhysicsEnvironment.EntityNeighborhood.UNKNOWN)
            .withEquipment(tr.facts.leatherBoots() ? PhysicsEnvironment.Equipment.LEATHER_BOOTS : PhysicsEnvironment.Equipment.NO_BOOTS);
    }
    private static boolean samePlace(PhysicsState s, Sample r) { return s.position().x() == r.x && s.position().y() == r.y && s.position().z() == r.z; }
    private static boolean samePlace(Sample a, Sample r) { return a.x == r.x && a.y == r.y && a.z == r.z; }
    private static double dist(PhysicsState s, Sample r) { double dx = s.position().x() - r.x, dy = s.position().y() - r.y, dz = s.position().z() - r.z; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
    private static String describe(Sample s) { return "pos=" + s.x + "," + s.y + "," + s.z + " onGround=" + s.onGround + " movementSequence=" + s.movementSequence; }

    /** One-tick diagnostic under an EXPLICIT input hypothesis from the published position (rest hypothesis); never infers keys. */
    public List<String> oneStep(UUID player, Scenario scenario, WorldView view, long worldEpoch, ArmFacts facts) {
        Optional<TimelineStatus> ts = timeline.apply(player);
        if (ts.isEmpty()) return List.of("no admitted session");
        TimelineStatus t = ts.get();
        if (!t.hasPosition()) return List.of("no observed position yet");
        Trace tmp = new Trace(player, scenario, System.nanoTime(), new Sample(t.movementSequence(), t.x(), t.y(), t.z(), t.onGround(), t.yaw(), t.pitch(), 0), view, worldEpoch, 0, facts);
        PhysicsEnvironment env = environment(tmp);
        PhysicsState s = PhysicsState.resting(t.x(), t.y(), t.z(), PlayerBox.Pose.STANDING, false); // observed onGround is displayed, never used as geometry input
        List<String> out = new ArrayList<>();
        out.add("physics one-step hypothesis=" + scenario + " (declared input, not inferred) from observed pos=" + t.x() + "," + t.y() + "," + t.z() + " yaw=" + t.yaw() + " observedOnGround=" + t.onGround() + " assumed velocity=rest, pose=STANDING");
        for (int i = 0; i < 2; i++) { PhysicsResult r = simulator.simulate(s, MovementInput.idle(t.yaw()), env); steps.increment(); if (r.next() == null) { out.add("rest tick " + i + ": " + r.summary()); return out; } s = r.next(); }
        PhysicsResult r = simulator.simulate(s, scenario.input(t.yaw()), env); steps.increment(); if (r.exact()) exact.increment();
        out.add("result: " + r.summary());
        if (r.next() != null) out.add("predicted next pos=" + r.next().position() + " vel=" + r.next().velocity() + " onGround=" + r.next().onGround() + " pose=" + r.next().pose() + " trace=" + r.trace());
        for (int i = 0; i < Math.min(8, r.reasons().size()); i++) out.add("  reason[" + i + "] " + r.reasons().get(i).code() + ": " + r.reasons().get(i).detail());
        return out;
    }
}
