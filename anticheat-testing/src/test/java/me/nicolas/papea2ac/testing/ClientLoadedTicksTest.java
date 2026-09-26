package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.MovementValidationRunnerTest.correction;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.ContextEvent;
import org.junit.jupiter.api.Test;

/**
 * P9-F4 regression (found by the Phase 9 VPS qualification on a respawn, capture 9; first seen locally as capture 4).
 *
 * <p>The live failure: after a death the client was placed at the spawn point (teleport 68, confirmed), then sent NINE tick ends with no
 * movement, then one tick that moved exactly one walking AIR impulse (0.0196) with Δy = 0 - the first tick of a freshly respawned player,
 * whose {@code onGround} starts false and whose first move has zero vertical velocity. The model simulated nine physics ticks after the
 * correction instead (the air node fell and landed; the ground node took the ground impulse 0.098), so no candidate matched: a complete
 * {@code INVALID}.
 *
 * <p>Root cause (pinned 26.2 client): {@code ClientPacketListener.handleLogin/handleRespawn} clear {@code clientLoaded};
 * {@code LocalPlayer.tick} returns at once while it is false; {@code Minecraft.tick} still sends {@code ServerboundClientTickEnd}, and then
 * {@code ClientPacketListener.tick} sends {@code ServerboundPlayerLoaded} and sets the flag once the level is ready. The tick ends between the
 * respawn and the player-loaded packet close ticks in which the player did not move. The runner now leaves them out of the physics slice,
 * which turns a respawn into the ordinary correction shape (teleport echo, then the first physics tick).
 */
class ClientLoadedTicksTest {
    static final int FROZEN = 9;

    /** The live shape: respawn correction at rest, {@link #FROZEN} tick ends while loading, then the first physics ticks (strafe, walk). */
    static ValidationStatus run(boolean announce, boolean loadedPacket) {
        PhysicsScene ps = new PhysicsScene(); MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        if (announce) h.runner.clientLoadReset(); // the server sent the respawn
        correction(h, 68, 8.5, SURFACE, 8.5, 0f); h.feed();
        assertTrue(h.status().anchored(), h.status().anchorKind());
        for (int i = 0; i < FROZEN; i++) { h.rec.advance(TICK_NANOS); h.rec.tickEnd(); }
        h.feed();
        if (announce && loadedPacket) h.runner.clientLoaded(); // ServerboundPlayerLoaded, sent after the last frozen tick end
        // a freshly respawned player: at the corrected position, zero velocity, onGround false until its first vertical collision
        PhysicsState fresh = settled(ps, 8.5, SURFACE, 8.5).withVelocity(Vec3d.ZERO).withOnGround(false).withSupport(PhysicsState.Support.NONE);
        List<MovementInput> in = new ArrayList<>();
        in.add(new MovementInput(0, 1, false, false, false, false, 0f)); // strafe: one air impulse, Δy = 0
        for (int i = 0; i < 5; i++) in.add(MovementInput.forward(0f, false));
        h.rec.advance(TICK_NANOS);
        simulate(ps, ps.env(), fresh, in, List.of(), h.rec, true, false); h.feed();
        return h.status();
    }

    @Test void theTicksBeforeThePlayerLoadedPacketAreNotPhysicsTicks() {
        ValidationStatus st = run(true, true);
        assertEquals(0, st.invalid(), String.join("\n", st.notable()));
        assertEquals(st.transitions(), st.valid(), String.join("\n", st.recent()));
        assertTrue(st.transitions() >= 5, st.toString());
    }
    @Test void countingTheFrozenTicksAsPhysicsIsExactlyTheLiveFailure() {
        ValidationStatus st = run(false, false);
        assertTrue(st.invalid() >= 1, "without the client-loaded rule the first respawned tick is unexplainable: " + String.join("\n", st.recent()));
    }
    @Test void aPositionWhileSupposedlyNotLoadedIsNeverJudged() {
        ValidationStatus st = run(true, false); // no player-loaded packet, yet the client moves: contradicts the rule, so re-anchor, never INVALID
        assertEquals(0, st.invalid(), String.join("\n", st.notable()));
    }
}
