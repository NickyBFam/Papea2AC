package me.nicolas.papea2ac.physics;
/**
 * One tick's EXPLICIT input candidate (INPUT_RECONSTRUCTION.md I1: inputs are hypotheses supplied by the caller; the
 * physics module never infers or enumerates them). Legal domain, verified against the 26.2 client/server code:
 * <ul>
 * <li>{@code forward}, {@code strafe} in {-1, 0, +1}: the vanilla keyboard client produces exactly
 *     {@code KeyboardInput.calculateImpulse} = +1 / -1 / 0 (opposing keys cancel) per axis; {@code +1} strafe is LEFT
 *     ({@code leftImpulse}), the server-side {@code Input} record carries the same seven booleans. Scaled analog values
 *     are NOT part of the vanilla keyboard domain; the profile applies the sneak ({@code SNEAKING_SPEED}) and
 *     item-use ({@code 0.2F}) factors and {@code applyInput}'s {@code 0.98F} itself, so the caller passes keys, not floats.</li>
 * <li>{@code jump} - the jump key ({@code LivingEntity.jumping}); {@code shift} - the sneak key
 *     ({@code isShiftKeyDown()}, which is what edge back-off, bounce suppression, ladder hold and crouch pose read);</li>
 * <li>{@code sprinting} - the entity sprint FLAG in effect during this tick's travel ({@code Entity.isSprinting()}: speed
 *     modifier, air speed, sprint-jump impulse). Whether the client toggled it is Phase 6's inference, not physics;</li>
 * <li>{@code usingItem} - {@code isUsingItem() && !isPassenger()} (move impulses scaled by {@code 0.2F});</li>
 * <li>{@code yaw} - the rotation the client applies its input basis to (degrees, float, as sent/kept by the client).</li>
 * </ul>
 */
public record MovementInput(int forward, int strafe, boolean jump, boolean shift, boolean sprinting, boolean usingItem, float yaw) {
    public MovementInput {
        if (forward < -1 || forward > 1 || strafe < -1 || strafe > 1) throw new IllegalArgumentException("forward/strafe must be -1, 0 or +1");
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("yaw");
    }
    public static MovementInput idle(float yaw) { return new MovementInput(0, 0, false, false, false, false, yaw); }
    public static MovementInput forward(float yaw, boolean sprinting) { return new MovementInput(1, 0, false, false, sprinting, false, yaw); }
    public boolean anyMove() { return forward != 0 || strafe != 0; }
}
