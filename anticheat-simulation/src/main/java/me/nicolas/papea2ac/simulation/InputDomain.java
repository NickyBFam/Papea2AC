package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.physics.MovementInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The LEGAL client input domain of one walking tick (INPUT_RECONSTRUCTION.md "Reconstruction domain": "InputDomain describes
 * forward/strafe possibilities, jump, sprint, crouch, item use"). It is the Phase 5 {@link MovementInput} domain, nothing
 * invented on top of it: {@code forward}, {@code strafe} in {-1, 0, +1} (the vanilla keyboard client's
 * {@code KeyboardInput.calculateImpulse} collapses opposing keys to 0 and never produces analog values; the server-side
 * {@code Input} record of the pinned 26.2 jar carries the same seven booleans forward/backward/left/right/jump/shift/sprint
 * and the server accepts every one of the 128 bit patterns), {@code jump}, {@code shift} (the sneak key), {@code sprinting}
 * (the entity sprint FLAG the tick's travel reads) and {@code usingItem}. That is {@value #RAW_SIZE} raw tuples, exactly the
 * "144-state calculation" MOVEMENT_SIMULATION.md M2 names; the yaw is not enumerated - it is an observed rotation (see
 * {@link TickObservation}).
 *
 * <p>Nothing is excluded from the raw domain by "likelihood". The only client-side legality rules that would remove tuples
 * (sprint start needing a forward impulse, sprint stopping on item use, ...) live in client-only classes ({@code LocalPlayer})
 * that are NOT present in the pinned server jar and therefore cannot be verified by this project's controlled extraction;
 * they are listed in {@link ClientAssumption} as explicitly UNVERIFIED and are never applied unless a caller opts in. What
 * IS exact is the context-free equivalence of §17: with {@code forward == strafe == 0} the {@code usingItem} scaling
 * multiplies a zero impulse, so the two tuples are the same physical input ({@link #classRepresentative}).
 *
 * <p>Canonical enumeration order (deterministic, documented, never a hash order): lexicographic in
 * {@code forward (-1, 0, +1) > strafe (-1, 0, +1) > jump (false, true) > shift (false, true) > sprinting (false, true) >
 * usingItem (false, true)}; {@link #ordinal(MovementInput)} is that rank, 0..143.
 */
public final class InputDomain {
    private InputDomain() {}
    /** 3 x 3 x 2 x 2 x 2 x 2. */
    public static final int RAW_SIZE = 144;

    /** Caller knowledge per input dimension (§8 "Known vs unknown input components"): only UNKNOWN dimensions are enumerated. */
    public record Constraints(int forward, int strafe, int jump, int shift, int sprinting, int usingItem) {
        /** Marker for an unknown dimension; booleans are 0/1 when known. */
        public static final int UNKNOWN = -2;
        public static final Constraints NONE = new Constraints(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        public Constraints {
            check(forward, -1, 1, "forward"); check(strafe, -1, 1, "strafe"); check(jump, 0, 1, "jump"); check(shift, 0, 1, "shift"); check(sprinting, 0, 1, "sprinting"); check(usingItem, 0, 1, "usingItem");
        }
        private static void check(int v, int lo, int hi, String name) { if (v != UNKNOWN && (v < lo || v > hi)) throw new IllegalArgumentException(name + " out of domain: " + v); }
        public Constraints withForward(int v) { return new Constraints(v, strafe, jump, shift, sprinting, usingItem); }
        public Constraints withStrafe(int v) { return new Constraints(forward, v, jump, shift, sprinting, usingItem); }
        public Constraints withJump(boolean v) { return new Constraints(forward, strafe, v ? 1 : 0, shift, sprinting, usingItem); }
        public Constraints withShift(boolean v) { return new Constraints(forward, strafe, jump, v ? 1 : 0, sprinting, usingItem); }
        public Constraints withSprinting(boolean v) { return new Constraints(forward, strafe, jump, shift, v ? 1 : 0, usingItem); }
        public Constraints withUsingItem(boolean v) { return new Constraints(forward, strafe, jump, shift, sprinting, v ? 1 : 0); }
        /** Every dimension pinned to one raw input. */
        public static Constraints exactly(MovementInput in) { return new Constraints(in.forward(), in.strafe(), in.jump() ? 1 : 0, in.shift() ? 1 : 0, in.sprinting() ? 1 : 0, in.usingItem() ? 1 : 0); }
        public boolean admits(MovementInput in) {
            return (forward == UNKNOWN || forward == in.forward()) && (strafe == UNKNOWN || strafe == in.strafe()) && (jump == UNKNOWN || (jump == 1) == in.jump())
                && (shift == UNKNOWN || (shift == 1) == in.shift()) && (sprinting == UNKNOWN || (sprinting == 1) == in.sprinting()) && (usingItem == UNKNOWN || (usingItem == 1) == in.usingItem());
        }
        /** Number of raw tuples this constraint set admits (product of the free dimensions). */
        public int admittedCount() { return (forward == UNKNOWN ? 3 : 1) * (strafe == UNKNOWN ? 3 : 1) * (jump == UNKNOWN ? 2 : 1) * (shift == UNKNOWN ? 2 : 1) * (sprinting == UNKNOWN ? 2 : 1) * (usingItem == UNKNOWN ? 2 : 1); }
    }

    /**
     * Client-side legality assumptions that would shrink the raw domain. Each names its source; none is verifiable from the
     * pinned SERVER jar (the logic lives in the client-only {@code LocalPlayer} class, absent from the server
     * distribution), so every one is UNVERIFIED and disabled by default. Applying one is the caller's explicit, recorded
     * decision; the reconstructor never applies them on its own (a false exclusion is the one unrecoverable error).
     */
    public enum ClientAssumption {
        /** {@code LocalPlayer.canStartSprinting}: a sprint that STARTS this tick needs a forward impulse; a continuing sprint does not. Stateful (needs the previous tick's sprint flag), unverified. */
        SPRINT_START_REQUIRES_FORWARD("LocalPlayer.canStartSprinting / hasEnoughImpulseToStartSprinting (client-only, unverified)", false),
        /** {@code LocalPlayer.aiStep}: item use ends/prevents sprinting on the client. Unverified for 26.2 (the server accepts sprint + use). */
        SPRINT_EXCLUDES_ITEM_USE("LocalPlayer.aiStep item-use sprint handling (client-only, unverified)", false);
        public final String source; public final boolean verified;
        ClientAssumption(String source, boolean verified) { this.source = source; this.verified = verified; }
        /** Whether this assumption would reject the raw tuple. {@code previousSprinting} is the stateful prerequisite (§7); {@code null} = unknown, which never rejects. */
        public boolean rejects(MovementInput in, Boolean previousSprinting) {
            return switch (this) {
                case SPRINT_START_REQUIRES_FORWARD -> in.sprinting() && previousSprinting != null && !previousSprinting && in.forward() != 1;
                case SPRINT_EXCLUDES_ITEM_USE -> in.sprinting() && in.usingItem();
            };
        }
    }

    /** Canonical rank of a raw input, 0..143 (yaw ignored). */
    public static int ordinal(MovementInput in) {
        return (((((in.forward() + 1) * 3 + (in.strafe() + 1)) * 2 + (in.jump() ? 1 : 0)) * 2 + (in.shift() ? 1 : 0)) * 2 + (in.sprinting() ? 1 : 0)) * 2 + (in.usingItem() ? 1 : 0);
    }
    /** Inverse of {@link #ordinal(MovementInput)}. */
    public static MovementInput fromOrdinal(int o, float yaw) {
        if (o < 0 || o >= RAW_SIZE) throw new IllegalArgumentException("ordinal " + o);
        boolean use = (o & 1) == 1; o >>= 1; boolean sprint = (o & 1) == 1; o >>= 1; boolean shift = (o & 1) == 1; o >>= 1; boolean jump = (o & 1) == 1; o >>= 1;
        int strafe = o % 3 - 1; o /= 3; int forward = o - 1;
        return new MovementInput(forward, strafe, jump, shift, sprint, use, yaw);
    }
    /** The raw tuples admitted by {@code constraints} (and not rejected by an opted-in assumption), in canonical order. */
    public static List<MovementInput> enumerate(Constraints constraints, float yaw, List<ClientAssumption> assumptions, Boolean previousSprinting) {
        Objects.requireNonNull(constraints); Objects.requireNonNull(assumptions);
        List<MovementInput> out = new ArrayList<>(constraints.admittedCount());
        for (int o = 0; o < RAW_SIZE; o++) {
            MovementInput in = fromOrdinal(o, yaw);
            if (!constraints.admits(in)) continue;
            boolean rejected = false; for (ClientAssumption a : assumptions) if (a.rejects(in, previousSprinting)) { rejected = true; break; }
            if (!rejected) out.add(in);
        }
        return out;
    }
    public static List<MovementInput> enumerate(Constraints constraints, float yaw) { return enumerate(constraints, yaw, List.of(), null); }
    /**
     * Context-free physical equivalence (§17): the canonical representative of the raw tuple's class. Two tuples are in the same
     * class exactly when the tick's arithmetic cannot distinguish them for ANY state and world: with no forward/strafe key the
     * item-use factor scales a zero impulse ({@code 0 * 0.2F == 0}), so {@code usingItem} is dropped there. Every other
     * dimension changes some observable path (impulse magnitude, jump, crouch pose / edge back-off / ladder hold, sprint
     * modifier and air speed), so nothing else is merged before simulation; identical predicted STATES are merged after it.
     */
    public static MovementInput classRepresentative(MovementInput in) {
        return in.forward() == 0 && in.strafe() == 0 && in.usingItem() ? new MovementInput(0, 0, in.jump(), in.shift(), in.sprinting(), false, in.yaw()) : in;
    }
    /** Number of distinct context-free classes in the full raw domain: 144 - 8 (the idle tuples with usingItem = true). */
    public static final int CLASS_COUNT = RAW_SIZE - 8;
    /** Bounded stable text for a raw input (diagnostics): keys as letters, {@code f+1 s-1 J C R U} style. */
    public static String describe(MovementInput in) {
        StringBuilder sb = new StringBuilder(12);
        sb.append(in.forward() > 0 ? "W" : in.forward() < 0 ? "S" : "-").append(in.strafe() > 0 ? "A" : in.strafe() < 0 ? "D" : "-");
        sb.append(in.jump() ? "J" : "-").append(in.shift() ? "C" : "-").append(in.sprinting() ? "R" : "-").append(in.usingItem() ? "U" : "-");
        return sb.toString();
    }
}
