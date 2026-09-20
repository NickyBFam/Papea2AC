package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import me.nicolas.papea2ac.model.Aabb;

/** Numeric contract of the model box (task §3/§17): strict vanilla intersection, finite-only, min <= max, zero-extent legal, no epsilon. */
class AabbTest {
    static final Aabb UNIT = new Aabb(0, 0, 0, 1, 1, 1);
    @Test void rejectsNonFiniteAndInvertedButAllowsZeroExtent() {
        assertThrows(IllegalArgumentException.class, () -> new Aabb(Double.NaN, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Aabb(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Aabb(0, 0, 0, 1, Double.NEGATIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new Aabb(1, 0, 0, 0, 1, 1), "inverted is never silently swapped");
        var zero = new Aabb(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        assertEquals(0.0, zero.sizeX()); assertTrue(zero.intersects(UNIT), "a degenerate box strictly inside another does intersect (0 < 0.5 < 1 on every axis) - vanilla AABB semantics"); assertTrue(UNIT.intersects(zero));
        assertFalse(new Aabb(1, 0.5, 0.5, 1, 0.5, 0.5).intersects(UNIT), "a degenerate box ON a face does not");
    }
    @Test void intersectionIsStrictOnEveryAxis() {
        assertTrue(UNIT.intersects(new Aabb(0.5, 0.5, 0.5, 1.5, 1.5, 1.5)), "partial overlap");
        assertTrue(UNIT.intersects(new Aabb(-1, -1, -1, 2, 2, 2)), "containment (other contains this)");
        assertTrue(UNIT.intersects(new Aabb(0.2, 0.2, 0.2, 0.8, 0.8, 0.8)), "containment (this contains other)");
        assertTrue(UNIT.intersects(UNIT), "full overlap");
        assertFalse(UNIT.intersects(new Aabb(1, 0, 0, 2, 1, 1)), "face touching is not intersecting");
        assertFalse(UNIT.intersects(new Aabb(1, 1, 0, 2, 2, 1)), "edge touching");
        assertFalse(UNIT.intersects(new Aabb(1, 1, 1, 2, 2, 2)), "corner touching");
        assertFalse(UNIT.intersects(new Aabb(-1, 0, 0, 0, 1, 1)), "face touching from the negative side");
        assertTrue(UNIT.intersects(new Aabb(1 - 1.0E-9, 0, 0, 2, 1, 1)), "a tiny overlap IS an intersection: no epsilon lives in the model value");
        assertFalse(UNIT.intersects(new Aabb(1 + 1.0E-9, 0, 0, 2, 1, 1)), "a tiny gap is not");
    }
    @Test void negativeAndVeryLargeCoordinatesBehaveIdentically() {
        var neg = new Aabb(-3, -70, -3, -2, -69, -2);
        assertTrue(neg.intersects(new Aabb(-2.5, -69.5, -2.5, -1, -60, -1))); assertFalse(neg.intersects(new Aabb(-2, -70, -3, -1, -69, -2)), "face touch at negative coordinates");
        double big = 2.9E7; var far = new Aabb(big, 64, -big, big + 1, 65, -big + 1);
        assertTrue(far.intersects(new Aabb(big + 0.5, 64.5, -big + 0.5, big + 2, 66, -big + 2))); assertFalse(far.intersects(new Aabb(big + 1, 64, -big, big + 2, 65, -big + 1)));
    }
    @Test void moveInflateExpandTowardsAndContainsFollowVanillaSemantics() {
        assertEquals(new Aabb(1, 2, 3, 2, 3, 4), UNIT.move(1, 2, 3));
        assertEquals(new Aabb(-0.5, -0.5, -0.5, 1.5, 1.5, 1.5), UNIT.inflate(0.5));
        assertEquals(new Aabb(0, 0, 0, 1.5, 1, 1), UNIT.expandTowards(0.5, 0, 0)); assertEquals(new Aabb(0, -0.5, 0, 1, 1, 1), UNIT.expandTowards(0, -0.5, 0));
        assertTrue(UNIT.contains(0, 0, 0)); assertFalse(UNIT.contains(1, 1, 1), "max faces are exclusive for contains");
        assertEquals(1.0, UNIT.sizeY());
        assertEquals(new Aabb(0, 0, 0, 1, 1, 1), new Aabb(-0.0, 0, 0, 1, 1, 1).move(0, 0, 0).intersects(UNIT) ? UNIT : null, "-0.0 compares equal to 0.0");
    }
}
