package me.nicolas.papea2ac.collision;
import java.util.Objects;
/**
 * Geometry of one observed cell as a two-sided envelope (task §10, MOVEMENT_SIMULATION.md M3 direction rule):
 * {@code lower} is geometry that is DEFINITELY present, {@code upper} geometry that MAY be present. For an exact
 * status both are the same instance. Consumers use the bounds in the direction that is conservative for them:
 * a "definitely obstructed" conclusion may only use {@code lower}; a "definitely clear" conclusion needs
 * {@code upper} to be empty. Nothing here ever narrows an unknown to air or widens it to an invented solid.
 */
public record ResolvedShape(CollisionShape lower, CollisionShape upper, CellStatus status) {
    public ResolvedShape {
        Objects.requireNonNull(lower); Objects.requireNonNull(upper); Objects.requireNonNull(status);
        if (status.exact() && lower != upper && !lower.equals(upper)) throw new IllegalArgumentException("exact status needs lower == upper");
        if (status.worldUnknown()) throw new IllegalArgumentException("world statuses are not shapes");
    }
    public static ResolvedShape exact(CollisionShape shape, CellStatus status) { return new ResolvedShape(shape, shape, status); }
    public boolean exact() { return status.exact(); }
}
