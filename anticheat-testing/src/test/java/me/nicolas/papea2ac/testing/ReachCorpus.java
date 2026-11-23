package me.nicolas.papea2ac.testing;

import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.geometry.ScenePick;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.combat.reach.ReachValidation;
import me.nicolas.papea2ac.combat.reach.ReachValidator;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.EntityLookup;

/**
 * Phase 12 deterministic attack corpus at client-tick resolution. Each scenario simulates, exactly as the 26.2 client does it:
 * <ul>
 * <li>a target whose server position is sent every tick (stationary, walking, or knocked back), arriving at the client after a random
 *     downlink latency with jitter (applications monotone), interpolated by the client's {@code InterpolationHandler} (3 steps, Mth.lerp);</li>
 * <li>a combat-time PING marker after every update (D12-2), answered when the client handles it;</li>
 * <li>an attacker whose eye and view are known exactly; the vanilla pick ({@link ScenePick}, client-exact) decides whether a click becomes an
 *     ATTACK.</li>
 * </ul>
 * The validator gets exactly what the live runner would derive: the window since the newest answered marker before the attack (every update the
 * server sent before it captured the attack, applied or not), the lifetime box since spawn, the proven ticks since the pong.
 *
 * <p>Classes: legitimate (the vanilla pick returns the target) and adversarial (reach R, hitbox expansion E, backtrack K ticks with prompt pong
 * answers, attack beside the box). Legitimate classes must never be INVALID; adversarial shares are measured, never tuned.
 */
final class ReachCorpus {
    static final EntityGeometryTable TABLE = EntityGeometryTable.paper26_2();
    static final MovementProfile TRIG = MovementProfile.paper26_2();
    static final SessionKey KEY = new SessionKey(new UUID(0x12, 0xC0), 1);
    static final float W = 0.6F, H = 1.95F; // zombie
    static final double EYE_HEIGHT = 1.62F;
    /** Use every answered marker (the nested bound, R5 refinement) rather than only the newest one. */
    static boolean NESTED = true;

    record ClassResult(String name, boolean legitimate, int scenarios, int attacks, int valid, int uncertain, int invalid, Map<String, Integer> missClasses, Map<String, Integer> reasons) {}

    /** One simulated fight. */
    static final class Fight {
        final Random r; final int ticks = 80; final double[][] server = new double[ticks][]; final int[] applied = new int[ticks]; final int uplink;
        final double[][] client; // client position at the START of each tick (before that tick's entity step, after its packets)
        Fight(Random r, String motion, int downlink, int jitter, int uplink, int backtrack) {
            this.r = r; this.uplink = uplink;
            double x = 0.5 + (r.nextDouble() - 0.5) * 0.2, y = 64, z = 2.6 + r.nextDouble() * 1.4;
            for (int t = 0; t < ticks; t++) {
                switch (motion) {
                    case "walk" -> { x += (r.nextDouble() - 0.5) * 0.25; z += (r.nextDouble() - 0.5) * 0.25; }
                    case "knockback" -> { if (t % 17 == 5) { z += 0.6 + r.nextDouble() * 0.4; y += 0.36; } else if (y > 64) y = Math.max(64, y - 0.12); z -= 0.03; }
                    default -> { }
                }
                server[t] = new double[]{x, y, z};
            }
            int last = 0;
            for (int t = 0; t < ticks; t++) { int a = t + downlink + (jitter == 0 ? 0 : r.nextInt(jitter + 1)) + backtrack; a = Math.max(a, last); applied[t] = a; last = a; }
            // the client (the backtrack cheat holds updates `backtrack` ticks longer than its pong answers)
            client = new double[ticks + downlink + jitter + backtrack + 8][];
            ReachValidatorTest.ClientEntity c = new ReachValidatorTest.ClientEntity(server[0][0], server[0][1], server[0][2], 3);
            int ui = 0;
            for (int t = 0; t < client.length; t++) {
                while (ui < ticks && applied[ui] == t) { c.interpolateTo(server[ui][0], server[ui][1], server[ui][2]); ui++; }
                client[t] = new double[]{c.x, c.y, c.z};
                c.tick();
            }
        }
        /** The marker after update i is answered when the honest client handles update i (the backtrack cheat answers at the honest time). */
        int pongTick(int i, int backtrack) { return applied[i] - backtrack; }
    }

    static Map<String, ClassResult> run(long seed, int scenariosPerClass) {
        Map<String, ClassResult> out = new LinkedHashMap<>();
        String[][] classes = {
            {"legit-stationary", "legit", "none", "0"}, {"legit-walking", "legit", "walk", "0"}, {"legit-knockback", "legit", "knockback", "0"},
            {"legit-high-latency", "legit", "walk", "0"},
            {"reach-3.1", "reach", "walk", "3.1"}, {"reach-3.5", "reach", "walk", "3.5"}, {"reach-4.0", "reach", "walk", "4.0"}, {"reach-6.0", "reach", "none", "6.0"},
            {"hitbox+0.2", "hitbox", "walk", "0.2"}, {"hitbox+1.0", "hitbox", "walk", "1.0"},
            {"backtrack-4t", "backtrack", "walk", "4"}, {"backtrack-8t", "backtrack", "walk", "8"},
        };
        for (String[] c : classes) {
            Random r = new Random(seed ^ c[0].hashCode());
            int attacks = 0, valid = 0, uncertain = 0, invalid = 0; Map<String, Integer> miss = new TreeMap<>(), reasons = new TreeMap<>();
            for (int s = 0; s < scenariosPerClass; s++) {
                boolean highLatency = c[0].equals("legit-high-latency");
                int downlink = highLatency ? 4 + r.nextInt(8) : 1 + r.nextInt(3), jitter = highLatency ? 4 : r.nextInt(3), uplink = highLatency ? 4 + r.nextInt(6) : 1 + r.nextInt(2);
                int backtrack = c[1].equals("backtrack") ? Integer.parseInt(c[3]) : 0;
                Fight f = new Fight(r, c[2], downlink, jitter, uplink, backtrack);
                boolean certified = r.nextBoolean();
                for (int attempt = 0; attempt < 12; attempt++) {
                    int T = 30 + r.nextInt(40); // the attack's client tick
                    double[] eye = {0.5, 64 + EYE_HEIGHT, 0.5};
                    double[] tp = f.client[T]; PickMath.Box honest = PickMath.makeBoundingBox(W, H, tp[0], tp[1], tp[2]);
                    // aim: a random point of the box the cheat (or the honest client) sees, with small noise
                    double ax = honest.minX() + r.nextDouble() * (honest.maxX() - honest.minX()), ay = honest.minY() + r.nextDouble() * (honest.maxY() - honest.minY()), az = honest.minZ() + r.nextDouble() * (honest.maxZ() - honest.minZ());
                    if (c[1].equals("hitbox")) { double e = Double.parseDouble(c[3]); ax += (r.nextBoolean() ? 1 : -1) * (0.3 + r.nextDouble() * e); }
                    float[] rot = aim(eye, ax, ay, az);
                    double[] view = PickMath.viewVector(TRIG, rot[1], rot[0]);
                    boolean honestHit = ScenePick.pick(eye, view, 3.0, 4.5, honest, true, ScenePick.BlockClip.MISS).hit();
                    boolean sends = switch (c[1]) {
                        case "legit" -> honestHit;
                        case "reach" -> !honestHit && ScenePick.pick(eye, view, Double.parseDouble(c[3]), Math.max(4.5, Double.parseDouble(c[3])), honest, true, ScenePick.BlockClip.MISS).hit();
                        case "hitbox" -> !honestHit && ScenePick.pick(eye, view, 3.0, 4.5, honest.inflate(Double.parseDouble(c[3])), true, ScenePick.BlockClip.MISS).hit();
                        case "backtrack" -> honestHit; // the cheat's own (delayed) client hit; the honest-schedule geometry decides what is admissible
                        default -> false;
                    };
                    if (!sends) continue;
                    attacks++;
                    ReachValidation v = validate(f, T, backtrack, eye, rot, certified, r);
                    switch (v.status()) { case VALID -> valid++; case UNCERTAIN -> uncertain++; case INVALID -> invalid++; }
                    for (var m : v.missClasses()) miss.merge(m.name(), 1, Integer::sum);
                    for (var x : v.reasons()) reasons.merge(x.name(), 1, Integer::sum);
                }
            }
            out.put(c[0], new ClassResult(c[0], c[1].equals("legit"), scenariosPerClass, attacks, valid, uncertain, invalid, miss, reasons));
        }
        return out;
    }

    /** yaw/pitch (degrees, the client's convention) looking from eye to (x,y,z). Test-side only (Math.atan2 is fine here: the view is then recomputed with the client's table). */
    static float[] aim(double[] eye, double x, double y, double z) {
        double dx = x - eye[0], dy = y - eye[1], dz = z - eye[2];
        float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz)), pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    /** The validator input the live runner derives at client tick T (server knowledge only). */
    static ReachValidation validate(Fight f, int T, int backtrack, double[] eye, float[] rot, boolean certified, Random r) {
        // the newest marker (sent right after update i) answered before the attack: the client handled it at tick <= T
        int proven = -1;
        for (int i = 0; i < f.ticks; i++) if (f.pongTick(i, backtrack) <= T) proven = i;
        // every update the server had sent before it captured the attack (the attack reaches the server ~uplink ticks after the client sent it)
        int sentBefore = Math.min(f.ticks - 1, T - 1 + f.uplink);
        List<double[]> window = new ArrayList<>();
        ApplicationFrontier frontier;
        if (proven >= 0) {
            for (int i = proven; i <= sentBefore; i++) window.add(f.server[i]);
            frontier = new ApplicationFrontier(ApplicationFrontier.Status.MARKER_PROVEN, 2L * proven + 1, 1, T - f.pongTick(proven, backtrack));
        } else { for (int i = 0; i <= sentBefore; i++) window.add(f.server[i]); frontier = ApplicationFrontier.NONE; }
        List<double[]> since = new ArrayList<>(); for (int i = 0; i <= sentBefore; i++) since.add(f.server[i]);
        PickMath.Box lifetime = ReachValidatorTest.box(since);
        // every answered marker (the nested bound): marker i follows update i and is answered when the (honest-timed) client handles it
        List<me.nicolas.papea2ac.combat.reach.TargetHull.Bound> bounds = new ArrayList<>();
        for (int i = 0; i <= sentBefore; i++) if (f.pongTick(i, backtrack) <= T) bounds.add(new me.nicolas.papea2ac.combat.reach.TargetHull.Bound(i, T - f.pongTick(i, backtrack)));
        var target = NESTED
            ? new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:zombie", 9, 1, window, true, false, false, lifetime, frontier, "STANDING", 1.0, false, since, bounds)
            : new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:zombie", 9, 1, window, true, false, false, lifetime, frontier, "STANDING", 1.0, false);
        ReachValidator.EyeInput e = certified
            ? new ReachValidator.EyeInput(ReachValidation.EyeSource.CERTIFIED_CONCRETE, List.of(new ReachValidator.EyeCandidate(eye[0], eye[1] - EYE_HEIGHT, eye[2], "STANDING", false, 0, 0, 0)), null, List.of(), false)
            : new ReachValidator.EyeInput(ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY, List.of(), new PickMath.Box(eye[0] - 2e-4, eye[1] - EYE_HEIGHT - 2e-4, eye[2] - 2e-4, eye[0] + 2e-4, eye[1] - EYE_HEIGHT + 2e-4, eye[2] + 2e-4), List.of("STANDING", "CROUCHING"), false);
        var attacker = new ReachValidator.AttackerInput(3.0, 4.5, 1.0, false, ReachValidator.HeldItem.CLASSIC, "survival");
        return ReachValidator.validate(new ReachValidator.Input(KEY, 1000 + T, "A1:" + (1000 + T), target, e, List.of(new ReachValidator.View(rot[0], rot[1])), attacker, (ReachValidator.AirOracle) (x, y, z) -> Boolean.TRUE, true), TABLE, TRIG, "corpus");
    }
}
