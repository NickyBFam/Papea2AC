package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.VelocityTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.version.TimingProfile;
/**
 * Phase 13 synthetic velocity corpus: seeded legitimate and cheating knockback scenarios walked through the REAL live chain
 * (MovementValidationRunner: Phase 7 timing + Phase 6 reconstruction + Phase 5 physics + Phase 8 classification) under a chosen timing
 * profile. Every scenario's truth is constructed (the influence vector applied by the client is known by construction), never derived from
 * the resolver. Classes are fixed before any tuning; the per-class counts are reported separately (VALID / UNCERTAIN / INVALID transitions
 * after the first knockback, and whether the scenario produced any INVALID).
 */
final class VelocityCorpus {
    private VelocityCorpus() {}
    /** A legitimate class must never produce INVALID; a cheat class is measured. */
    enum Kind { LEGITIMATE, CHEAT }
    record Scenario(String cls, Kind kind, long seed, List<MovementInput> inputs, List<Knock> knocks, Set<Integer> attacks, Set<Integer> slowdowns) {}
    record Outcome(String cls, Kind kind, long seed, int transitions, int valid, int uncertain, int invalid, String firstInvalid) {}
    static final class ClassTally {
        final String cls; final Kind kind; int scenarios, scenariosWithInvalid, transitions, valid, uncertain, invalid; final List<String> examples = new ArrayList<>();
        ClassTally(String cls, Kind kind) { this.cls = cls; this.kind = kind; }
        void add(Outcome o) { scenarios++; transitions += o.transitions(); valid += o.valid(); uncertain += o.uncertain(); invalid += o.invalid(); if (o.invalid() > 0) { scenariosWithInvalid++; if (examples.size() < 3) examples.add("seed " + o.seed() + ": " + o.firstInvalid()); } }
        double detectionRate() { return scenarios == 0 ? 0 : (double) scenariosWithInvalid / scenarios; }
    }

    static final double[] VERTICALS = { 0.36, 0.4, 0.42, 0.3608, 0.2 };
    static Vec3d vector(SplittableRandom r) { double h = 0.2 + r.nextDouble() * 0.35, ang = r.nextDouble() * Math.PI * 2; return new Vec3d(round(h * Math.cos(ang)), VERTICALS[r.nextInt(VERTICALS.length)], round(h * Math.sin(ang))); }
    /** The low-precision codec quantises a real velocity; the corpus uses short decimals so the vector is representable as sent (the codec itself is pinned by Phase 7). */
    static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
    static List<MovementInput> inputs(SplittableRandom r, int n, int mode) {
        List<MovementInput> l = new ArrayList<>(); float yaw = (float) (r.nextInt(360) - 180);
        for (int i = 0; i < n; i++) {
            switch (mode) {
                case 0 -> l.add(MovementInput.idle(yaw));
                case 1 -> l.add(MovementInput.forward(yaw, false));
                case 2 -> l.add(MovementInput.forward(yaw, true));
                default -> l.add(new MovementInput(r.nextInt(3) - 1, r.nextInt(3) - 1, false, false, false, false, yaw));
            }
        }
        return l;
    }

    /** The fixed class list (legitimate then cheat). */
    static List<Scenario> scenarios(long seed0, int perClass) {
        List<Scenario> out = new ArrayList<>();
        for (int c = 0; c < perClass; c++) {
            long seed = seed0 + c; SplittableRandom r = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 13);
            int mode = r.nextInt(4), emit = 3 + r.nextInt(4), lag = r.nextInt(5), ackLag = r.nextInt(4);
            Vec3d v = vector(r);
            List<MovementInput> in = inputs(r, 36, mode); List<MovementInput> still = inputs(r, 36, 0);
            // ---- legitimate
            out.add(new Scenario("L-single-moving", Kind.LEGITIMATE, seed, inputs(r, 36, 1 + r.nextInt(3)), List.of(Knock.honest(v, emit, emit + lag, emit + lag + ackLag)), Set.of(), Set.of()));
            out.add(new Scenario("L-single-standing", Kind.LEGITIMATE, seed, still, List.of(Knock.honest(v, emit, emit + lag, emit + lag + ackLag)), Set.of(), Set.of()));
            int gap = 3 + r.nextInt(8);
            out.add(new Scenario("L-combo-3", Kind.LEGITIMATE, seed, inputs(r, 40, mode), List.of(Knock.honest(v, emit, emit + lag, emit + lag + ackLag), Knock.honest(vector(r), emit + gap, emit + gap + lag, emit + gap + lag + r.nextInt(3)), Knock.honest(vector(r), emit + 2 * gap, emit + 2 * gap + lag, emit + 2 * gap + lag)), Set.of(), Set.of()));
            out.add(new Scenario("L-explosion-add", Kind.LEGITIMATE, seed, in, List.of(new Knock(TimingEvent.Operation.ADD, new Vec3d(round(v.x() * 1.5), round(v.y() * 1.2), round(v.z() * 1.5)), emit, emit + lag, emit + lag + ackLag, Response.HONEST, 1, 1)), Set.of(), Set.of()));
            out.add(new Scenario("L-stall-no-answer", Kind.LEGITIMATE, seed, in, List.of(Knock.honest(v, emit, emit + lag + 6, 0)), Set.of(), Set.of()));
            int lat = new int[] { 1, 3, 6 }[r.nextInt(3)]; // 50 / 150 / 300 ms one-way-ish delivery delay in ticks
            out.add(new Scenario("L-latency-" + lat * 50 + "ms", Kind.LEGITIMATE, seed, in, List.of(Knock.honest(v, emit, emit + lat, emit + lat + ackLag)), Set.of(), Set.of()));
            int hit = emit + lag;
            out.add(new Scenario("L-hit-while-hit", Kind.LEGITIMATE, seed, inputs(r, 36, 2), List.of(Knock.honest(v, emit, hit, hit)), Set.of(hit), r.nextBoolean() ? Set.of(hit) : Set.of()));
            // ---- cheats (each still answers the fence on time: the client keeps the connection healthy)
            out.add(new Scenario("C-cancel", Kind.CHEAT, seed, r.nextBoolean() ? in : still, List.of(Knock.cancelled(v, emit, emit + ackLag)), Set.of(), Set.of()));
            out.add(new Scenario("C-horizontal-0", Kind.CHEAT, seed, in, List.of(Knock.scaled(v, emit, emit + lag, emit + lag + ackLag, 0.0, 1.0)), Set.of(), Set.of()));
            out.add(new Scenario("C-vertical-0", Kind.CHEAT, seed, in, List.of(Knock.scaled(v, emit, emit + lag, emit + lag + ackLag, 1.0, 0.0)), Set.of(), Set.of()));
            out.add(new Scenario("C-reverse", Kind.CHEAT, seed, in, List.of(Knock.scaled(v, emit, emit + lag, emit + lag + ackLag, -1.0, 1.0)), Set.of(), Set.of()));
            double frac = 0.4 + 0.55 * r.nextDouble();
            out.add(new Scenario("C-scale-40-95", Kind.CHEAT, seed, in, List.of(Knock.scaled(v, emit, emit + lag, emit + lag + ackLag, round(frac), 1.0)), Set.of(), Set.of()));
            // delayed application while answering the fence on time: the velocity is processed AFTER the ping that followed it (impossible for the vanilla FIFO)
            out.add(new Scenario("C-delay-past-fence", Kind.CHEAT, seed, in, List.of(new Knock(TimingEvent.Operation.SET, v, emit, emit + 2 + ackLag + lag, emit + ackLag, Response.DELAYED_PAST_FENCE, 1, 1)), Set.of(), Set.of()));
        }
        return out;
    }

    /** Runs one scenario through the live chain. */
    static Outcome run(PhysicsScene ps, Scenario s, TimingProfile profile) { return run(ps, s, profile, false); }
    /** The Phase 12 combat-time cadence lag for a scenario: the client answers every ping as late as its own honest processing allows (FIFO), a cheat answers on time; -1 for the stall class (no answers). */
    static int combatLag(Scenario s) {
        if (s.cls().equals("L-stall-no-answer")) return -1;
        int lag = 0;
        for (Knock k : s.knocks()) lag = Math.max(lag, switch (k.response()) { case HONEST, SCALE -> k.applyTick() - k.emitTick(); case CANCEL, DELAYED_PAST_FENCE -> k.ackTick() - k.emitTick(); });
        return lag;
    }
    static Outcome run(PhysicsScene ps, Scenario s, TimingProfile profile, boolean combatMarkers) { return run(ps, s, profile, combatMarkers, Double.NaN); }
    /**
     * {@code meleeDistance} (not NaN): a melee attacker (a player) stands that far from the start, on the side the first knockback pushes away
     * from, inside the Phase 8 neighbourhood radius; the push-exclusion proof is attached with the attacker's exact box (a stationary attacker:
     * its admissible positions are its one position). Without the proof such a scenario is ENTITY_NEIGHBORHOOD_UNKNOWN.
     */
    static Outcome run(PhysicsScene ps, Scenario s, TimingProfile profile, boolean combatMarkers, double meleeDistance) {
        Harness h = new Harness(ps, profile);
        if (!Double.isNaN(meleeDistance)) {
            me.nicolas.papea2ac.physics.Vec3d v = s.knocks().get(0).vector(); double hl = Math.sqrt(v.x() * v.x() + v.z() * v.z());
            double ax = 8.5 - v.x() / hl * meleeDistance, az = 8.5 - v.z() / hl * meleeDistance;
            h.context.apply(KEY, new me.nicolas.papea2ac.player.ContextEvent.EntitySpawned(2, 77, "minecraft:player", ax, SURFACE, az));
            me.nicolas.papea2ac.combat.geometry.PickMath.Box box = me.nicolas.papea2ac.combat.geometry.PickMath.makeBoundingBox(0.6f, 1.8f, ax, SURFACE, az);
            h.runner.attachNeighborhoodProof((req, ctx) -> me.nicolas.papea2ac.core.PushExclusion.noContact(req, ctx, me.nicolas.papea2ac.combat.reach.ApplicationFrontier.NONE, id -> id == 77 ? box : null));
        }
        h.anchor(1, 8.5, SURFACE, 8.5);
        List<MovementInput> all = new ArrayList<>(s.inputs()); all.addAll(idle(10));
        try { simulate(ps, ps.env(), anchored(ps, 8.5, 8.5), all, s.knocks(), s.attacks(), s.slowdowns(), combatMarkers ? combatLag(s) : -1, h.rec, false); }
        catch (AssertionError notExact) { return null; } // a truth step the simulator cannot certify (e.g. an exotic edge): the scenario is not a corpus member
        h.feed();
        ValidationStatus st = h.status();
        String first = null; for (String l : st.recent()) if (l.contains(" INVALID ")) { first = l; break; }
        return new Outcome(s.cls(), s.kind(), s.seed(), st.transitions(), st.valid(), st.uncertain(), st.invalid(), first);
    }
}
