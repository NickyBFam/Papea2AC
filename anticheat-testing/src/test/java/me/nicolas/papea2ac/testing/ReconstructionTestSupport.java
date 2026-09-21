package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.simulation.*;
import java.util.ArrayList;
import java.util.List;

/** Phase 6 test scaffolding: a reconstructor over the Phase 5 scene, observation builders from simulated transitions, and history helpers. */
final class ReconstructionTestSupport {
    private ReconstructionTestSupport() {}
    static final float YAW = 0f;
    static final MovementInput IDLE = MovementInput.idle(YAW), W = MovementInput.forward(YAW, false), W_SPRINT = MovementInput.forward(YAW, true),
        JUMP = new MovementInput(0, 0, true, false, false, false, YAW), W_JUMP = new MovementInput(1, 0, true, false, false, false, YAW), W_SHIFT = new MovementInput(1, 0, false, true, false, false, YAW),
        D = new MovementInput(0, -1, false, false, false, false, YAW), A = new MovementInput(0, 1, false, false, false, false, YAW), S = new MovementInput(-1, 0, false, false, false, false, YAW),
        W_A = new MovementInput(1, 1, false, false, false, false, YAW), W_USE = new MovementInput(1, 0, false, false, false, true, YAW);

    static InputReconstructor reconstructor(PhysicsScene ps) { return new InputReconstructor(ps.simulator); }
    /** The exact observation of a predicted state (what the client would transmit for it), with the input's yaw and the simulated ground flag as the claim. */
    static TickObservation observe(PhysicsState next, float yaw, long seq) {
        return TickObservation.exact(next.position().x(), next.position().y(), next.position().z(), yaw, next.onGround() ? TickObservation.GroundClaim.TRUE : TickObservation.GroundClaim.FALSE, seq);
    }
    /** Simulates {@code inputs} from {@code start} (all steps must be EXACT) and returns the per-tick exact observations. */
    static List<TickObservation> observations(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs) {
        List<TickObservation> out = new ArrayList<>(); PhysicsState s = start;
        for (int i = 0; i < inputs.size(); i++) { PhysicsResult r = ps.simulator.simulate(s, inputs.get(i), env); if (!r.exact()) throw new AssertionError("tick " + i + ": " + r.summary()); s = r.next(); out.add(observe(s, inputs.get(i).yaw(), 100 + i)); }
        return out;
    }
    static ReconstructionResult reconstruct(PhysicsScene ps, PhysicsState start, List<MovementInput> inputs) {
        PhysicsEnvironment env = ps.env();
        return reconstructor(ps).reconstruct(ReconstructionRequest.of(start, observations(ps, env, start, inputs), env));
    }
    static ReconstructionResult reconstruct(PhysicsScene ps, PhysicsState start, MovementInput input) { return reconstruct(ps, start, List.of(input)); }
    static String keys(List<MovementInput> h) { StringBuilder sb = new StringBuilder(); for (MovementInput m : h) sb.append(sb.length() > 0 ? ">" : "").append(InputDomain.describe(m)); return sb.toString(); }
    static String dump(ReconstructionResult r) { return String.join("\n", r.describe(20)); }
}
