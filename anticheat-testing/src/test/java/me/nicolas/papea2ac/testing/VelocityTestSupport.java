package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.core.MovementValidationRunner;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.TimingProfile;
/**
 * Phase 13 test scaffolding: a synthetic client that receives server knockback (velocity SET / explosion ADD) followed by a PING marker
 * and answers that marker from its packet drain, exactly as the pinned 26.2 client does (P13-N1: velocity and ping wait in one FIFO
 * queue that is drained between client ticks; the pong is sent from that drain). The HIDDEN truth decides, per knockback, when the
 * client processed the velocity and the ping, and how it responded: an honest client applies the server's vector, a cheating client
 * cancels it or scales it while still answering the marker. Expected values come from the construction, never from the resolver.
 */
final class VelocityTestSupport {
    private VelocityTestSupport() {}

    /** How the client responds to a knockback it has received. */
    enum Response { HONEST, CANCEL, SCALE, DELAYED_PAST_FENCE }
    /**
     * One knockback: written in the gap before {@code emitTick} (velocity, then a PING marker right behind it); processed by the client in
     * the gap before {@code applyTick}; the marker answered in the gap before {@code ackTick} (0 = never answered: a stall). An honest client
     * processes the velocity no later than the ping that follows it: {@code applyTick <= ackTick}.
     */
    record Knock(TimingEvent.Operation op, Vec3d vector, int emitTick, int applyTick, int ackTick, Response response, double horizontalScale, double verticalScale) {
        Knock {
            if (emitTick < 1 || applyTick < emitTick || (ackTick != 0 && ackTick < emitTick) || (response != Response.DELAYED_PAST_FENCE && ackTick != 0 && ackTick < applyTick)) throw new IllegalArgumentException("knock timing");
        }
        static Knock honest(Vec3d v, int emit, int apply, int ack) { return new Knock(TimingEvent.Operation.SET, v, emit, apply, ack, Response.HONEST, 1, 1); }
        static Knock cancelled(Vec3d v, int emit, int ack) { return new Knock(TimingEvent.Operation.SET, v, emit, emit, ack, Response.CANCEL, 0, 0); }
        static Knock scaled(Vec3d v, int emit, int apply, int ack, double h, double vert) { return new Knock(TimingEvent.Operation.SET, v, emit, apply, ack, Response.SCALE, h, vert); }
        /** The motion the client actually applied, or null when it applied none. */
        PhysicsEnvironment.ExternalMotion applied(String id) {
            if (response == Response.CANCEL) return null;
            Vec3d v = response == Response.SCALE ? new Vec3d(vector.x() * horizontalScale, vector.y() * verticalScale, vector.z() * horizontalScale) : vector;
            return new PhysicsEnvironment.ExternalMotion(op == TimingEvent.Operation.SET ? PhysicsEnvironment.ExternalMotion.Operation.SET : PhysicsEnvironment.ExternalMotion.Operation.ADD, v, id);
        }
    }

    /** Runs {@code inputs} from {@code start} under the hidden knock truth, recording exactly what the server-side capture would see. */
    static Truth simulate(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs, List<Knock> knocks, Recorder rec, boolean leadingTickEnd) { return simulate(ps, env, start, inputs, knocks, Set.of(), rec, leadingTickEnd); }
    /**
     * {@code slowdownTicks}: ticks before whose movement the client's own attack ran {@code Player.causeExtraKnockback}'s attacker slowdown
     * ({@code setDeltaMovement(getDeltaMovement().multiply(0.6, 1.0, 0.6))}, after the tick's packet drain, before {@code LocalPlayer.tick}).
     * The truth applies it as the exact resulting motion; the capture carries no trace of it unless the caller records the ATTACK.
     */
    static Truth simulate(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs, List<Knock> knocks, Set<Integer> slowdownTicks, Recorder rec, boolean leadingTickEnd) { return simulate(ps, env, start, inputs, knocks, Set.of(), slowdownTicks, rec, leadingTickEnd); }
    /** {@code attackTicks}: ticks whose ATTACK packet the capture records (sent from handleKeybinds, after the drain, before the tick's movement). */
    static Truth simulate(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs, List<Knock> knocks, Set<Integer> attackTicks, Set<Integer> slowdownTicks, Recorder rec, boolean leadingTickEnd) { return simulate(ps, env, start, inputs, knocks, attackTicks, slowdownTicks, -1, rec, leadingTickEnd); }
    /**
     * {@code combatLag} >= 0: Phase 12 combat-time cadence (finding phase12-to-phase13-01): one PING in every inter-tick gap, delivered and answered
     * {@code combatLag} ticks later (FIFO with the influences: a ping written after a velocity is answered no earlier than that velocity applies).
     */
    static Truth simulate(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs, List<Knock> knocks, Set<Integer> attackTicks, Set<Integer> slowdownTicks, int combatLag, Recorder rec, boolean leadingTickEnd) {
        Map<Integer, List<Long>> combatAcks = new HashMap<>();
        List<TruthTick> ticks = new ArrayList<>(); Map<String, Integer> applicationTick = new LinkedHashMap<>();
        PhysicsState s = start; double lx = start.position().x(), ly = start.position().y(), lz = start.position().z(); float lastYaw = inputs.get(0).yaw(); int sinceSend = 0;
        Map<Knock, TimingEvent.ExternalMotionSent> emitted = new LinkedHashMap<>(); Map<Knock, Long> markerOf = new LinkedHashMap<>();
        long markerId = 7000 + rec.sequence;
        if (leadingTickEnd) { rec.advance(1_000_000L); rec.tickEnd(); }
        for (int i = 1; i <= inputs.size(); i++) {
            for (Knock k : knocks) if (k.emitTick() == i) {
                rec.advance(TICK_NANOS / 8);
                TimingEvent.ExternalMotionSent e = k.op() == TimingEvent.Operation.SET ? rec.velocity(k.vector().x(), k.vector().y(), k.vector().z()) : rec.explosion(k.vector().x(), k.vector().y(), k.vector().z());
                emitted.put(k, e);
                rec.advance(200_000L); long id = markerId++; rec.marker(TimingEvent.MarkerKind.PING, id); markerOf.put(k, id);
            }
            List<PhysicsEnvironment.ExternalMotion> applied = new ArrayList<>();
            for (Knock k : knocks) if (k.applyTick() == i) { PhysicsEnvironment.ExternalMotion m = k.applied(emitted.get(k).id()); if (m != null) { applied.add(m); applicationTick.put(m.sourceId(), i); } }
            applied.sort(Comparator.comparingLong(m -> Long.parseLong(m.sourceId().substring(1))));
            for (Knock k : knocks) if (k.ackTick() == i) { rec.advance(300_000L); rec.ack(TimingEvent.MarkerKind.PING, markerOf.get(k)); }
            for (long cid : combatAcks.getOrDefault(i, List.of())) { rec.advance(100_000L); rec.ack(TimingEvent.MarkerKind.PING, cid); }
            if (combatLag >= 0) { rec.advance(100_000L); long cid = markerId++; rec.marker(TimingEvent.MarkerKind.PING, cid); combatAcks.computeIfAbsent(i + Math.max(1, combatLag), x -> new ArrayList<>()).add(cid); }
            rec.advance(TICK_NANOS / 4);
            if (attackTicks.contains(i)) { rec.advance(100_000L); rec.add(new TimingEvent.LocalAttack(rec.nextSeq(), rec.nanos, 4242)); }
            rec.advance(TICK_NANOS / 4);
            MovementInput in = inputs.get(i - 1);
            if (slowdownTicks.contains(i)) { // drained influences first, then the attack in handleKeybinds: the slowdown acts on the post-drain motion
                PhysicsState pre = s;
                for (PhysicsEnvironment.ExternalMotion m : applied) pre = pre.withVelocity(m.operation() == PhysicsEnvironment.ExternalMotion.Operation.SET ? m.vector() : new Vec3d(pre.velocity().x() + m.vector().x(), pre.velocity().y() + m.vector().y(), pre.velocity().z() + m.vector().z()));
                applied = new ArrayList<>(List.of(new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, new Vec3d(pre.velocity().x() * 0.6, pre.velocity().y(), pre.velocity().z() * 0.6), "attack" + i)));
            }
            PhysicsResult r = ps.simulator.simulate(s, in, applied.isEmpty() ? env : env.withExternalMotions(applied));
            if (!r.exact()) throw new AssertionError("tick " + i + " not exact: " + r.summary());
            PhysicsState next = r.next();
            boolean sendPosition = !OBS.withinSendThreshold(next.position().x(), next.position().y(), next.position().z(), lx, ly, lz) || ++sinceSend >= 20;
            boolean yawChanged = in.yaw() != lastYaw;
            TimingEvent.MovementObserved tx = null;
            if (sendPosition) { tx = rec.movement(next.position().x(), next.position().y(), next.position().z(), in.yaw(), next.onGround()); lx = next.position().x(); ly = next.position().y(); lz = next.position().z(); sinceSend = 0; lastYaw = in.yaw(); }
            else if (yawChanged) { tx = rec.rotationOnly(in.yaw(), next.onGround()); lastYaw = in.yaw(); }
            rec.advance(1_000_000L); rec.tickEnd();
            rec.advance(TICK_NANOS / 2);
            ticks.add(new TruthTick(i, in, applied, next, sendPosition, tx));
            s = next;
        }
        return new Truth(start, ticks, applicationTick, rec);
    }

    static final SessionKey KEY = new SessionKey(new UUID(13, 1), 1);
    /** The live runner over the synthetic client, under a chosen timing profile; fed exactly what the engine feeds it. */
    static final class Harness {
        final PhysicsScene ps; final TimingLedger timing = new TimingLedger(KEY); final MovementContextLedger context = new MovementContextLedger(KEY); final MovementValidationRunner.Metrics metrics = new MovementValidationRunner.Metrics(); final MovementValidationRunner runner; final Recorder rec;
        int fed;
        Harness(PhysicsScene ps, TimingProfile profile) {
            this.ps = ps;
            runner = new MovementValidationRunner(KEY, new MovementValidator(new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator))), ps.simulator, ps.scene.engine, ps.behaviors, profile, metrics);
            rec = new Recorder(new TimingLedger(KEY)); rec.nanos = 5_000_000_000L; // the recorder's own ledger; the runner sees `timing`, applied event by event in feed() as the engine does
            context.apply(KEY, new ContextEvent.BorderInitialized(0, 0, 0, 5.9999968E7, 5.9999968E7, 0));
            context.apply(KEY, new ContextEvent.GameModeChanged(1, "survival"));
        }
        /** Applies each recorded event to the session ledger, then hands it to the runner with the view AT THAT EVENT (the engine order: ledger first, then validation). */
        void feed() { for (; fed < rec.events.size(); fed++) { var e = rec.events.get(fed); timing.apply(KEY, e); runner.onEvent(e, timing.view(), context.view(), ps.scene.world.currentView(), ps.scene.world.worldEpoch()); } }
        ValidationStatus status() { return runner.status(); }
        /** A join-like absolute correction confirmed and echoed: the chain's first anchor. */
        void anchor(long id, double x, double y, double z) {
            rec.advance(1_000_000L); rec.tickEnd(); rec.advance(TICK_NANOS / 8); rec.teleport(id, x, y, z, 0f, 0); rec.advance(TICK_NANOS / 4); rec.confirm(id); rec.advance(100_000L); rec.movement(x, y, z, 0f, false);
            feed();
        }
        String dump() { ValidationStatus st = status(); return "transitions=" + st.transitions() + " valid=" + st.valid() + " uncertain=" + st.uncertain() + " invalid=" + st.invalid() + "\n" + String.join("\n", st.recent()) + "\nNOTABLE\n" + String.join("\n", st.notable()); }
    }
    static List<MovementInput> idle(int n) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(MovementInput.idle(0f)); return l; }
    static List<MovementInput> walk(int n, boolean sprint) { List<MovementInput> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(MovementInput.forward(0f, sprint)); return l; }
    /** The resting state the anchor correction installs (velocity zero, no support yet). */
    static PhysicsState anchored(PhysicsScene ps, double x, double z) { return settled(ps, x, SURFACE, z).withVelocity(Vec3d.ZERO).withSupport(PhysicsState.Support.NONE); }
}
