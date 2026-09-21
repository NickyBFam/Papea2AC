package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.physics.PhysicsState;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MOVEMENT_SIMULATION.md M3 "AbstractState is a finite union of tagged boxes: outward-rounded intervals for position and
 * velocity, discrete sets for mode/pose/contact/jump/use state". This is the Phase 6 pilot representation: each {@link Box}
 * carries position and velocity intervals, the set of possible simulated ground flags, the pose set and the
 * {@code noJumpDelay} range; {@code gamma(A)} is every WALKING state whose fields lie inside one of the boxes and whose
 * stuck multiplier is zero and powder-snow flag false (the pilot preconditions - anything else is represented only by
 * {@link #TOP}). {@link #TOP} is the honest "unbounded" element: it represents every state and excludes nothing.
 *
 * <p>Merging is tagged union (boxes are appended) or interval hull ({@link #hulled}); both retain every concrete state
 * ("Losing correlations widens the set and creates false negatives/UNCERTAIN, never permission to shrink it").
 */
public record AbstractState(List<Box> boxes, boolean top, String topReason) {
    /** One tagged box. Pose set and ground set are bit masks: {@link #GROUND_ON} / {@link #GROUND_OFF}, {@link #POSE_STANDING} / {@link #POSE_CROUCHING}. */
    public record Box(Interval x, Interval y, Interval z, Interval vx, Interval vy, Interval vz, int ground, int poses, int noJumpDelayLo, int noJumpDelayHi) {
        public Box {
            Objects.requireNonNull(x); Objects.requireNonNull(y); Objects.requireNonNull(z); Objects.requireNonNull(vx); Objects.requireNonNull(vy); Objects.requireNonNull(vz);
            if (ground == 0 || (ground & ~3) != 0 || poses == 0 || (poses & ~3) != 0 || noJumpDelayLo < 0 || noJumpDelayHi < noJumpDelayLo) throw new IllegalArgumentException("box tags");
        }
        public boolean mayBeOnGround() { return (ground & GROUND_ON) != 0; }
        public boolean mayBeAirborne() { return (ground & GROUND_OFF) != 0; }
        public boolean mayBeStanding() { return (poses & POSE_STANDING) != 0; }
        public boolean mayBeCrouching() { return (poses & POSE_CROUCHING) != 0; }
        public boolean contains(PhysicsState s) {
            return x.contains(s.position().x()) && y.contains(s.position().y()) && z.contains(s.position().z()) && vx.contains(s.velocity().x()) && vy.contains(s.velocity().y()) && vz.contains(s.velocity().z())
                && ((s.onGround() ? GROUND_ON : GROUND_OFF) & ground) != 0 && (poseBit(s.pose()) & poses) != 0 && s.noJumpDelay() >= noJumpDelayLo && s.noJumpDelay() <= noJumpDelayHi;
        }
        public Box hull(Box o) {
            return new Box(x.hull(o.x), y.hull(o.y), z.hull(o.z), vx.hull(o.vx), vy.hull(o.vy), vz.hull(o.vz), ground | o.ground, poses | o.poses, Math.min(noJumpDelayLo, o.noJumpDelayLo), Math.max(noJumpDelayHi, o.noJumpDelayHi));
        }
        /** Whether the position box meets the observation set (a point, or the outer cube of the silence ball - a sound superset of the ball). */
        public boolean intersectsPosition(TickObservation.Position p, MovementObservationProfile profile) {
            return switch (p) {
                case TickObservation.Exact e -> x.contains(e.x()) && y.contains(e.y()) && z.contains(e.z());
                case TickObservation.WithinSendThreshold w -> { double r = Math.nextUp(Math.sqrt(profile.positionSendThresholdSqr())); yield x.intersects(new Interval(w.lastSentX() - r, w.lastSentX() + r).outward()) && y.intersects(new Interval(w.lastSentY() - r, w.lastSentY() + r).outward()) && z.intersects(new Interval(w.lastSentZ() - r, w.lastSentZ() + r).outward()); }
                case TickObservation.Unobserved u -> true;
            };
        }
        public boolean bounded() { return x.bounded() && y.bounded() && z.bounded() && vx.bounded() && vy.bounded() && vz.bounded(); }
    }
    public static final int GROUND_ON = 1, GROUND_OFF = 2, GROUND_ANY = 3, POSE_STANDING = 1, POSE_CROUCHING = 2, POSE_ANY = 3;
    /** Maximum boxes retained before the union is hulled into fewer boxes (bounded representation; hulling is sound). */
    public static final int MAX_BOXES = 16;
    public static final AbstractState TOP = new AbstractState(List.of(), true, "top");

    public AbstractState {
        boxes = List.copyOf(boxes); Objects.requireNonNull(topReason);
        if (!top && boxes.isEmpty()) throw new IllegalArgumentException("empty non-top abstract state");
    }
    public static AbstractState top(String reason) { return new AbstractState(List.of(), true, reason); }
    public static AbstractState of(Box... boxes) { return new AbstractState(List.of(boxes), false, ""); }
    public static AbstractState of(List<Box> boxes) { return boxes.isEmpty() ? TOP : new AbstractState(boxes, false, ""); }
    public static int poseBit(PlayerBox.Pose p) { return p == PlayerBox.Pose.STANDING ? POSE_STANDING : p == PlayerBox.Pose.CROUCHING ? POSE_CROUCHING : 0; }
    /** The singleton abstraction of a concrete pilot-domain state, or TOP when the state is outside the pilot domain (stuck, powder snow, unsupported pose/locomotion). */
    public static AbstractState fromConcrete(PhysicsState s) {
        if (s.locomotion() != PhysicsState.Locomotion.WALKING) return top("locomotion " + s.locomotion());
        if (poseBit(s.pose()) == 0) return top("pose " + s.pose());
        if (s.stuck().lengthSqr() != 0.0 || s.inPowderSnow()) return top("stuck multiplier / powder snow");
        if (!s.finite()) return top("non-finite state");
        Vec3d p = s.position(), v = s.velocity();
        return of(new Box(Interval.point(p.x()), Interval.point(p.y()), Interval.point(p.z()), Interval.point(v.x()), Interval.point(v.y()), Interval.point(v.z()), s.onGround() ? GROUND_ON : GROUND_OFF, poseBit(s.pose()), s.noJumpDelay(), s.noJumpDelay()));
    }
    /** Whether the concrete state is represented (TOP represents everything). */
    public boolean contains(PhysicsState s) {
        if (top) return true;
        if (s.locomotion() != PhysicsState.Locomotion.WALKING || s.stuck().lengthSqr() != 0.0 || s.inPowderSnow()) return false;
        for (Box b : boxes) if (b.contains(s)) return true;
        return false;
    }
    /** Whether some represented position could satisfy the observation (TOP: always). */
    public boolean mayMatch(TickObservation.Position p, MovementObservationProfile profile) {
        if (top) return true;
        for (Box b : boxes) if (b.intersectsPosition(p, profile)) return true;
        return false;
    }
    /** Tagged union; when the box count would exceed {@link #MAX_BOXES}, the boxes are hulled pairwise (sound). */
    public AbstractState union(AbstractState o) {
        if (top) return this; if (o.top) return o;
        List<Box> all = new ArrayList<>(boxes.size() + o.boxes.size()); all.addAll(boxes); all.addAll(o.boxes);
        return of(compact(all));
    }
    /** Everything hulled into one box (sound, loses correlations). */
    public AbstractState hulled() {
        if (top || boxes.size() == 1) return this;
        Box h = boxes.get(0); for (int i = 1; i < boxes.size(); i++) h = h.hull(boxes.get(i));
        return of(h);
    }
    static List<Box> compact(List<Box> all) {
        while (all.size() > MAX_BOXES) { // deterministic: merge the first two boxes with the same tags, else the first two
            int i = 0, j = 1; boolean found = false;
            outer: for (int a = 0; a < all.size(); a++) for (int b = a + 1; b < all.size(); b++) if (all.get(a).ground == all.get(b).ground && all.get(a).poses == all.get(b).poses) { i = a; j = b; found = true; break outer; }
            if (!found) { i = 0; j = 1; }
            Box merged = all.get(i).hull(all.get(j)); List<Box> next = new ArrayList<>(all.size() - 1);
            for (int k = 0; k < all.size(); k++) { if (k == i) next.add(merged); else if (k != j) next.add(all.get(k)); }
            all = next;
        }
        return all;
    }
    public int boxCount() { return boxes.size(); }
    /** Bounded one-line summary (diagnostics). */
    public String summary() {
        if (top) return "TOP(" + topReason + ")";
        StringBuilder sb = new StringBuilder(); int n = 0;
        for (Box b : boxes) { if (n++ == 3) { sb.append(" +").append(boxes.size() - 3).append(" boxes"); break; } sb.append(n > 1 ? " | " : "").append("x").append(b.x).append(" y").append(b.y).append(" z").append(b.z).append(" ground=").append(b.ground).append(" poses=").append(b.poses); }
        return sb.toString();
    }
}
