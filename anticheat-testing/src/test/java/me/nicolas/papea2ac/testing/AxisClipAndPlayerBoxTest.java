package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.collision.AxisClip.Axis;
import me.nicolas.papea2ac.model.Aabb;

/** The single-axis clip primitive (task §18, geometry only) and the player box contract (task §4), with hand-derived expectations. */
class AxisClipAndPlayerBoxTest {
    static final CollisionShape SLAB = CollisionShape.of(0, 0, 0, 1, 0.5, 1), STAIR = CollisionShape.of(0, 0, 0, 1, 0.5, 1, 0, 0.5, 0, 1, 1, 0.5);
    static final Aabb BODY = new Aabb(0.2, 1.0, 0.2, 0.8, 2.8, 0.8);

    @Test void fallingOntoASlabStopsAtItsTopAndRisingIntoACeilingStopsBelowIt() {
        assertEquals(-0.5, AxisClip.clip(Axis.Y, BODY, -1.0, SLAB, 0, 0, 0), "slab top at 0.5: a body at 1.0 may only fall 0.5");
        assertEquals(-1.0, AxisClip.clip(Axis.Y, BODY, -1.0, SLAB, 3, 0, 0), "a slab three cells away in x is never touched");
        assertEquals(-1.0, AxisClip.clip(Axis.Y, BODY, -1.0, CollisionShape.EMPTY, 0, 0, 0));
        assertEquals(0.2, AxisClip.clip(Axis.Y, BODY, 1.0, CollisionShape.FULL_CUBE, 0, 3, 0), 1e-12, "ceiling at y=3, head at 2.8");
        assertEquals(0.0, AxisClip.clip(Axis.Y, BODY, 5.0E-8, CollisionShape.FULL_CUBE, 0, 3, 0), "|desired| below EPSILON is zero, as in vanilla");
    }
    @Test void horizontalClipUsesTheNearestFaceAndAcceptsEpsilonContact() {
        var wall = CollisionShape.FULL_CUBE;
        assertEquals(0.2, AxisClip.clip(Axis.X, BODY, 0.5, wall, 1, 1, 0), 1e-12, "wall at x=1, body edge at 0.8");
        assertEquals(-0.2, AxisClip.clip(Axis.X, BODY, -0.5, wall, -1, 1, 0), 1e-12);
        assertEquals(0.5, AxisClip.clip(Axis.X, BODY, 0.5, wall, 1, 3, 0), "a block above the head does not block horizontal motion");
        assertEquals(0.5, AxisClip.clip(Axis.Z, BODY, 0.5, wall, 1, 1, 0), "no overlap in x: the block to the east is irrelevant for z motion");
        var touching = new Aabb(0.2, 1.0, 0.2, 1.0, 2.8, 0.8);
        assertEquals(0.0, AxisClip.clip(Axis.X, touching, 0.5, wall, 1, 1, 0), "already in face contact: d = 0 is accepted (>= -EPS) and the body does not move");
        var slightlyInside = new Aabb(0.2, 1.0, 0.2, 1.0 + 5.0E-8, 2.8, 0.8);
        assertEquals(-5.0E-8, AxisClip.clip(Axis.X, slightlyInside, 0.5, wall, 1, 1, 0), 1e-15, "penetrating by less than EPSILON: pushed back to the face (vanilla d >= -1e-7)");
    }
    @Test void penetratedShapesClipToTheirInternalGridLinesLikeVanilla() {
        // a 3/4 slab built by vanilla has a 1/4 grid: a body already inside it moving up snaps to the next internal line
        var threeQuarter = CollisionShape.of(new double[] { 0, 0, 0, 1, 0.75, 1 }, new me.nicolas.papea2ac.version.BlockShapeTable.Grid(new double[] { 0, 1 }, new double[] { 0, 0.25, 0.5, 0.75, 1 }, new double[] { 0, 1 }, new long[] { 0b0111 }));
        var inside = new Aabb(0.2, 0.1, 0.2, 0.8, 0.3, 0.8);
        assertEquals(0.5 - 0.3, AxisClip.clip(Axis.Y, inside, 1.0, threeQuarter, 0, 0, 0), 1e-15, "next filled grid cell above the body top (0.3) starts at 0.5");
        var edgeOnly = CollisionShape.of(0, 0, 0, 1, 0.75, 1); // synthesized: only the box edges form the grid
        assertEquals(1.0, AxisClip.clip(Axis.Y, inside, 1.0, edgeOnly, 0, 0, 0), "without internal lines the body inside moves freely: the exact grid matters, which is why the table stores it");
    }
    @Test void resultLevelClipConsidersOnlyDefiniteHits() {
        var s = new Scene(); s.baseline(0, 0); s.set(4, 70, 4, SLAB_BOTTOM); s.set(6, 70, 4, SHULKER);
        var body = new Aabb(4.2, 71.0, 4.2, 4.8, 72.8, 4.8);
        var r = s.query(body.expandTowards(0, -1, 0)); assertTrue(r.definitelyObstructed());
        assertEquals(-0.5, AxisClip.clip(Axis.Y, body, -1.0, r), "falls onto the slab (70.5) - the dirt at 70 is below the slab");
        var overLid = new Aabb(6.2, 71.2, 4.2, 6.8, 72.8, 4.8); var rl = s.query(overLid.expandTowards(0, -1, 0));
        assertEquals(-0.2, AxisClip.clip(Axis.Y, overLid, -1.0, rl), 1e-12, "the closed shulker body (definite) stops the fall at 71; the possible lid (upper envelope) is not a collider");
    }
    @Test void playerBoxesPerPoseFollowVanillaMakeBoundingBoxAndNoPoseIsAssumed() {
        float half = 0.6f / 2.0f, h = 1.8f; assertEquals(new Aabb(0.5 - (double) half, 70, 0.5 - (double) half, 0.5 + (double) half, 70 + (double) h, 0.5 + (double) half), PlayerBox.of(PROFILE, PlayerBox.Pose.STANDING, 0.5, 70, 0.5), "float dimensions widened like vanilla (0.6f/2 is not 0.3)");
        assertEquals(1.5, PlayerBox.of(PROFILE, PlayerBox.Pose.CROUCHING, 0, 0, 0).maxY(), 1e-7); assertEquals(0.6, PlayerBox.of(PROFILE, PlayerBox.Pose.SWIMMING, 0, 0, 0).maxY(), 1e-7);
        assertEquals(0.6, PlayerBox.of(PROFILE, PlayerBox.Pose.FALL_FLYING, 0, 0, 0).maxY(), 1e-7); assertEquals(0.2, PlayerBox.of(PROFILE, PlayerBox.Pose.SLEEPING, 0, 0, 0).sizeX(), 1e-7);
        assertEquals(-(double) half, PlayerBox.of(PROFILE, PlayerBox.Pose.STANDING, 0, 0, 0).minX(), "float half-width, exactly as vanilla widens it");
        assertEquals((double) (1.8f * 0.5f), PlayerBox.of(PROFILE, PlayerBox.Pose.STANDING, 0.5f, 0, 0, 0).maxY(), "scale attribute multiplies as float first");
        assertThrows(IllegalArgumentException.class, () -> PlayerBox.of(PROFILE, PlayerBox.Pose.STANDING, 0f, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> PlayerBox.of(PROFILE, null, 0, 0, 0), "there is no default pose");
        assertArrayEquals(new float[] { 0.6f, 0.6f }, PlayerBox.dimensions(PROFILE, PlayerBox.Pose.SPIN_ATTACK));
    }
}
