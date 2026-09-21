package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.collision.CollisionResult;
import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MOVEMENT_SIMULATION.md M3 abstract transfer {@code T#} for the Phase 6 PILOT DOMAIN (M2 "Qualification domains": "verified
 * one-step walking/free-flight/known flat contact with known mode/rotation/effects, complete neighborhood and bounded force
 * domain"). Required containment: {@code step(gamma(A), admissibleInputs) subset gamma(T#(A))}.
 *
 * <p>Domain (everything else returns {@link AbstractState#TOP}, which excludes nothing): WALKING, standing/crouching pose,
 * zero stuck multiplier, every movement attribute and effect known (only the modelled effects), a KNOWN empty entity
 * neighbourhood, a known far border, no unresolved external motion, and a swept region whose cells are all KNOWN and
 * either air (no fluid, no inside effect, not climbable) or - for exactly one complete layer under the region - the SAME
 * full-cube block with speed factor 1, jump factor 1, restitution 0 and a motion-free {@code stepOn}. In that region the
 * vanilla tick reduces to per-axis operations that are monotone in each interval operand ({@link Interval}), so the
 * concrete tick's own arithmetic on the interval endpoints bounds every concrete state exactly; the genuinely case-split
 * places (the small-velocity zeroing thresholds, the jump eligibility, landing on the floor versus staying airborne, slow
 * falling's sign test) are unioned, never chosen. The floor landing height is a point only inside the Sterbenz regime
 * ({@code y in [Yf/2, 2Yf]}, or {@code Yf == 0}) where {@code y + (Yf - y) == Yf} exactly; elsewhere the step is TOP.
 *
 * <p>The transfer is used for exclusion and node pruning only: an envelope that meets the observation set is "possible",
 * never a witness (M3: "They cannot establish a LegitimateWitness, reconstruct actual keys").
 */
public final class AbstractTransfer {
    private static final double LANDING_EPSILON = 1.0E-6, BORDER_MARGIN = 3.0;
    private final PhysicsSimulator simulator; private final PhysicsEnvironment env; private final MovementObservationProfile observation; private final int maxCells;
    private final PhysicsSimulator.ResolvedAttributes walk, sprint;
    private final double halfWidth, height;
    private final boolean slowFalling; private final PhysicsEnvironment.EffectInstance levitation, jumpBoost;
    private final String environmentTop;
    int cellsScanned, transfers;
    public int cellsScanned() { return cellsScanned; }
    public int transfers() { return transfers; }

    public AbstractTransfer(PhysicsSimulator simulator, PhysicsEnvironment env, MovementObservationProfile observation, int maxCells) {
        this.simulator = Objects.requireNonNull(simulator); this.env = Objects.requireNonNull(env); this.observation = Objects.requireNonNull(observation); this.maxCells = maxCells;
        walk = simulator.resolveAttributes(env, false); sprint = simulator.resolveAttributes(env, true);
        String top = null;
        if (walk == null || sprint == null) top = "unknown attribute";
        else if (env.effects().knowledge() != PhysicsEnvironment.Knowledge.KNOWN) top = "effects incomplete";
        else { for (PhysicsEnvironment.EffectInstance e : env.effects().active()) if (simulator.profile().effect(e.id()) == null) { top = "unknown effect " + e.id(); break; } }
        if (top == null && (env.entities().knowledge() != PhysicsEnvironment.Knowledge.KNOWN || !env.entities().hardColliders().isEmpty() || !env.entities().pushers().isEmpty())) top = "entity neighbourhood";
        if (top == null && env.border().knowledge() != PhysicsEnvironment.Knowledge.KNOWN) top = "border unknown";
        if (top == null && walk.bounciness() != 0.0) top = "bounciness attribute";
        environmentTop = top;
        float scale = walk == null ? 1.0f : (float) walk.scale();
        Aabb b = PlayerBox.of(simulator.profile().collision(), PlayerBox.Pose.STANDING, scale, 0.0, 0.0, 0.0);
        halfWidth = b.maxX(); height = b.maxY();
        slowFalling = env.effects().find(MovementProfile.EFFECT_SLOW_FALLING) != null;
        levitation = env.effects().find(MovementProfile.EFFECT_LEVITATION); jumpBoost = env.effects().find(MovementProfile.EFFECT_JUMP_BOOST);
    }

    /** {@code T#} over the union of the given inputs (all share the tick's yaw) after the caller's external motions; TOP when any box leaves the pilot domain. */
    public AbstractState step(AbstractState a, List<MovementInput> inputs, List<PhysicsEnvironment.ExternalMotion> motions) {
        transfers++;
        if (a.top()) return a;
        if (environmentTop != null) return AbstractState.top(environmentTop);
        if (inputs.isEmpty()) return AbstractState.top("no admitted input");
        // the union is kept as one hull per (ground, pose) tag - four boxes at most - which is what node pruning needs (a sound reach set) without
        // materializing hundreds of per-input boxes
        AbstractState.Box[] hulls = new AbstractState.Box[4];
        for (AbstractState.Box box : a.boxes()) {
            Prepared pre = prepare(box, motions);
            if (pre == null) return AbstractState.top(topReason);
            for (MovementInput in : inputs) {
                List<AbstractState.Box> r = apply(pre, in); if (r == null) return AbstractState.top(topReason);
                for (AbstractState.Box b : r) { int tag = (b.ground() == AbstractState.GROUND_ON ? 0 : 1) + (b.poses() == AbstractState.POSE_STANDING ? 0 : 2); hulls[tag] = hulls[tag] == null ? b : hulls[tag].hull(b); }
            }
        }
        List<AbstractState.Box> boxes = new ArrayList<>(4); for (AbstractState.Box h : hulls) if (h != null) boxes.add(h);
        return AbstractState.of(boxes);
    }
    /** Why the last {@link #prepare} / {@link #apply} returned null (TOP). */
    public String topReason() { return topReason; }
    private String topReason = "";

    /** The input-independent part of one box's transfer: external motion, small-velocity zeroing, the swept region proof and the floor. Null = TOP ({@link #topReason}). */
    public Prepared prepare(AbstractState.Box box, List<PhysicsEnvironment.ExternalMotion> motions) {
        if (environmentTop != null) { topReason = environmentTop; return null; }
        if (!box.bounded()) { topReason = "unbounded box"; return null; }
        Interval vx = box.vx(), vy = box.vy(), vz = box.vz();
        for (PhysicsEnvironment.ExternalMotion m : motions) {
            switch (m.operation()) {
                case SET -> { vx = Interval.point(m.vector().x()); vy = Interval.point(m.vector().y()); vz = Interval.point(m.vector().z()); }
                case ADD -> { vx = vx.add(m.vector().x()); vy = vy.add(m.vector().y()); vz = vz.add(m.vector().z()); }
                case UNRESOLVED -> { topReason = "unresolved external motion " + m.sourceId(); return null; }
            }
        }
        // ---- small-velocity zeroing (case split unioned when the threshold may or may not fire)
        Interval sq = vx.square().add(vz.square());
        if (sq.hi() < MovementProfile.PLAYER_HORIZONTAL_ZERO_THRESHOLD_SQR) { vx = Interval.ZERO; vz = Interval.ZERO; }
        else if (!(sq.lo() >= MovementProfile.PLAYER_HORIZONTAL_ZERO_THRESHOLD_SQR)) { vx = vx.hull(0.0); vz = vz.hull(0.0); }
        if (vy.allAbsBelow(MovementProfile.VELOCITY_ZERO_THRESHOLD)) vy = Interval.ZERO;
        else if (!vy.noneAbsBelow(MovementProfile.VELOCITY_ZERO_THRESHOLD)) vy = vy.hull(0.0);
        int decLo = Math.max(0, box.noJumpDelayLo() - 1), decHi = Math.max(0, box.noJumpDelayHi() - 1);
        // ---- the region every input/case can sweep: position box moved by every possible pre-move velocity, plus the body, plus one cell of margin
        double maxSpeed = Math.max(Math.max(groundSpeed(walk, 0.6f), groundSpeed(sprint, 0.6f)), Math.max(MovementProfile.AIR_SPRINT_SPEED, walk.speed() * 2.0f)) + 0.2; // input vector <= speed (normalized), sprint-jump impulse 0.2
        double jumpMax = Math.max(0.0, (float) Math.max(walk.jumpStrength(), sprint.jumpStrength()) * 1.0f + (jumpBoost == null ? 0.0f : MovementProfile.JUMP_BOOST_PER_LEVEL * (jumpBoost.amplifier() + 1.0f))) + 0.1;
        Interval mx = vx.add(new Interval(-maxSpeed, maxSpeed)), mz = vz.add(new Interval(-maxSpeed, maxSpeed)), my = vy.hull(vy.maxWith(jumpMax)).add(new Interval(-0.001, 0.001));
        Interval rx = box.x().hull(box.x().add(mx)), ry = box.y().hull(box.y().add(my)), rz = box.z().hull(box.z().add(mz));
        Aabb region = new Aabb(rx.lo() - halfWidth - 1.0, ry.lo() - 1.0, rz.lo() - halfWidth - 1.0, rx.hi() + halfWidth + 1.0, ry.hi() + height + 1.0, rz.hi() + halfWidth + 1.0);
        if (Math.abs(region.minX()) > MovementProfile.COORDINATE_CLAMP - 1 || Math.abs(region.maxX()) > MovementProfile.COORDINATE_CLAMP - 1 || Math.abs(region.minZ()) > MovementProfile.COORDINATE_CLAMP - 1 || Math.abs(region.maxZ()) > MovementProfile.COORDINATE_CLAMP - 1) { topReason = "coordinate clamp region"; return null; }
        if (!borderFar(region)) { topReason = "border proximity"; return null; }
        Floor floor = scanRegion(region, box.y().lo());
        if (floor == null) { topReason = scanFailure; return null; }
        if (floor.present && floor.friction < 0.0f) { topReason = "floor outside pilot (speed/jump factor, restitution, stepOn)"; return null; }
        if (box.mayBeOnGround() && !floor.present) { topReason = "ground state without a floor in the region"; return null; }
        if (box.mayBeOnGround() && !(box.y().isPoint() && box.y().lo() == floor.top)) { topReason = "ground state not resting exactly on the floor"; return null; }
        return new Prepared(box, vx, vy, vz, decLo, decHi, floor);
    }
    /** One box after {@link #prepare}: ready for per-input application. */
    public record Prepared(AbstractState.Box box, Interval vx, Interval vy, Interval vz, int decLo, int decHi, Floor floor) {}

    /** The per-input, per-case boxes of {@code T#} for one prepared box (usually 1-2 boxes; a point box yields point intervals except at a case split). Null = TOP. */
    public List<AbstractState.Box> apply(Prepared pre, MovementInput in) {
        AbstractState.Box box = pre.box(); Floor floor = pre.floor(); Interval vx = pre.vx(), vy = pre.vy(), vz = pre.vz();
        List<AbstractState.Box> boxes = new ArrayList<>(2);
        PhysicsSimulator.ResolvedAttributes a = in.sprinting() ? sprint : walk;
        // crouching == shift here: the standing box fits everywhere in the region, so isMovingSlowly reduces to the sneak key
        float[] move = PhysicsKernels.clientMoveVector(in.forward(), in.strafe(), in.shift(), in.usingItem(), (float) a.sneakingSpeed());
        float xxa = move[0], zza = move[1];
        int poses = in.shift() ? AbstractState.POSE_CROUCHING : AbstractState.POSE_STANDING;
        for (int g = 0; g < 2; g++) {
            boolean onGround = g == 0;
            if (onGround ? !box.mayBeOnGround() : !box.mayBeAirborne()) continue;
            float friction = onGround ? MovementProfile.computeModifiedFriction(floor.friction, (float) a.frictionModifier()) : 1.0f;
            float speed = onGround ? groundSpeed(a, friction) : (in.sprinting() ? MovementProfile.AIR_SPRINT_SPEED : MovementProfile.AIR_SPEED);
            Vec3d iv = simulator.inputVector((double) xxa, 0.0, (double) zza, speed, in.yaw());
            // jump cases: fires iff on ground and the decremented delay is 0
            boolean jumpPossible = in.jump() && onGround && pre.decLo() == 0, noJumpPossible = !in.jump() || !onGround || pre.decHi() > 0;
            for (int j = 0; j < 2; j++) {
                boolean jumped = j == 0;
                if (jumped ? !jumpPossible : !noJumpPossible) continue;
                Interval jx = vx, jy = vy, jz = vz; int njLo, njHi;
                if (jumped) {
                    float power = (float) a.jumpStrength() * 1.0f * 1.0f + (jumpBoost == null ? 0.0f : MovementProfile.JUMP_BOOST_PER_LEVEL * ((float) jumpBoost.amplifier() + 1.0f));
                    if (power > MovementProfile.JUMP_POWER_EPSILON) {
                        jy = jy.maxWith((double) power);
                        if (in.sprinting()) { float yawRad = in.yaw() * MovementProfile.DEG_TO_RAD; jx = jx.add((double) (-simulator.profile().sin(yawRad)) * MovementProfile.SPRINT_JUMP_IMPULSE); jz = jz.add((double) simulator.profile().cos(yawRad) * MovementProfile.SPRINT_JUMP_IMPULSE); }
                    }
                    njLo = njHi = MovementProfile.JUMP_DELAY_TICKS;
                } else if (in.jump()) { njLo = onGround ? Math.max(pre.decLo(), 1) : pre.decLo(); njHi = pre.decHi(); if (njLo > njHi) njLo = njHi; } else { njLo = njHi = 0; }
                Interval px = jx.add(iv.x()), py = jy.add(iv.y()), pz = jz.add(iv.z()); // v = v.add(inputVector)
                // ---- Entity.move in free space over (at most) one flat floor: no horizontal collision, no step-up, no edge back-off effect
                Interval nx = box.x().add(px), nz = box.z().add(pz), yEnd = box.y().add(py);
                boolean landPossible = floor.present && py.lo() < 0.0 && yEnd.lo() <= floor.top + LANDING_EPSILON;
                boolean freePossible = !floor.present || py.hi() >= 0.0 || yEnd.hi() >= floor.top - LANDING_EPSILON;
                // Shapes.collide zeroes a requested axis movement below SHAPE_EPSILON whenever the swept box has any collider (the floor): a
                // tiny non-zero horizontal request then leaves the position unchanged on that axis (union both), a tiny non-zero vertical
                // request would count as a landing at the current height - outside the pilot (TOP) rather than approximated.
                if (landPossible) {
                    if (mayBeTinyNonZero(px)) nx = nx.hull(box.x());
                    if (mayBeTinyNonZero(pz)) nz = nz.hull(box.z());
                    if (mayBeTinyNonZero(py)) { topReason = "vertical request below the shape epsilon over a floor"; return null; }
                }
                for (int c = 0; c < 2; c++) {
                    boolean land = c == 0;
                    if (land ? !landPossible : !freePossible) continue;
                    Interval ny, vyAfter;
                    if (land) { if (!sterbenz(box.y(), floor.top)) { topReason = "floor landing outside the Sterbenz regime"; return null; } ny = Interval.point(floor.top); vyAfter = Interval.ZERO; }
                    else { ny = yEnd; vyAfter = py; }
                    // gravity / levitation, then drag (block speed factor is 1 in the pilot region)
                    Interval d;
                    if (levitation != null) { double target = MovementProfile.LEVITATION_TARGET_PER_LEVEL * (double) (levitation.amplifier() + 1); Interval diff = new Interval(target - vyAfter.hi(), target - vyAfter.lo()).mul(MovementProfile.LEVITATION_RATE); d = vyAfter.add(diff).outward(); }
                    else if (slowFalling) { Interval g1 = vyAfter.sub(Math.min(a.gravity(), MovementProfile.SLOW_FALLING_GRAVITY)), g2 = vyAfter.sub(a.gravity()); d = vyAfter.hi() <= 0.0 ? g1 : vyAfter.lo() > 0.0 ? g2 : g1.hull(g2); }
                    else d = vyAfter.sub(a.gravity());
                    float gd = friction * MovementProfile.computeModifiedFriction(MovementProfile.AIR_FRICTION, (float) a.airDragModifier());
                    float hd = MovementProfile.computeModifiedFriction(MovementProfile.VERTICAL_DRAG, (float) a.airDragModifier());
                    Interval fx = px.mul((double) gd), fy = d.mul((double) hd), fz = pz.mul((double) gd);
                    boxes.add(new AbstractState.Box(nx, ny, nz, fx, fy, fz, land ? AbstractState.GROUND_ON : AbstractState.GROUND_OFF, poses, njLo, njHi));
                }
            }
        }
        return boxes;
    }
    /** Whether the interval may hold a value {@code v} with {@code 0 < |v| < SHAPE_EPSILON} (the collide zeroing band). */
    private static boolean mayBeTinyNonZero(Interval v) { double eps = me.nicolas.papea2ac.version.CollisionProfile.SHAPE_EPSILON; return v.lo() < eps && v.hi() > -eps && !(v.lo() == 0.0 && v.hi() == 0.0); }
    private static float groundSpeed(PhysicsSimulator.ResolvedAttributes a, float friction) { return (double) friction > MovementProfile.GROUND_SPEED_FRICTION_THRESHOLD ? a.speed() * (MovementProfile.GROUND_SPEED_NUMERATOR / (friction * friction * friction)) : a.speed(); }
    /** {@code y + (Yf - y) == Yf} exactly for every y in the interval: Sterbenz ({@code Yf/2 <= y <= 2 Yf}, positive) or {@code Yf == 0}. */
    private static boolean sterbenz(Interval y, double top) { return top == 0.0 || (top > 0.0 && y.lo() >= top / 2.0 && y.hi() <= 2.0 * top); }
    private boolean borderFar(Aabb region) {
        PhysicsEnvironment.WorldBorder b = env.border();
        double dist = Math.min(Math.min(region.minX() - b.minX(), b.maxX() - region.maxX()), Math.min(region.minZ() - b.minZ(), b.maxZ() - region.maxZ()));
        return dist >= BORDER_MARGIN;
    }

    /** The one flat floor layer under a prepared region (absent = pure free flight); the scan also proves every other cell in the region is plain air. */
    public static final class Floor { boolean present; double top; float friction = -1.0f; public boolean present() { return present; } public double top() { return top; } }
    private String scanFailure = "";
    private Floor scanRegion(Aabb region, double lowestFeet) {
        // cells whose cube strictly intersects the region (the collision query's own acceptance rule): [floor(min), floor(nextDown(max))]
        int x0 = floor(region.minX()), x1 = floor(Math.nextDown(region.maxX())), y0 = floor(region.minY()), y1 = floor(Math.nextDown(region.maxY())), z0 = floor(region.minZ()), z1 = floor(Math.nextDown(region.maxZ()));
        long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells > maxCells) { scanFailure = "region of " + cells + " cells exceeds the envelope cell budget"; return null; }
        CollisionResult q = env.collision().query(env.view(), region, ShapeContext.body(region.minY()), env.worldEpoch());
        if (q.certainty() != CollisionResult.Certainty.COMPLETE) { scanFailure = "collision region " + q.certainty(); return null; }
        // the floor is the HIGHEST collider layer in the region; it must be a complete layer of definite full cubes over every column
        // (then nothing below it can be reached, whatever it is) and nothing may lie above it
        Floor f = new Floor(); int floorCellY = Integer.MIN_VALUE;
        for (CollisionResult.Hit h : q.hits()) if (h.y() > floorCellY) floorCellY = h.y();
        if (floorCellY != Integer.MIN_VALUE) {
            f.present = true; f.top = floorCellY + 1.0;
            if (lowestFeet < f.top) { scanFailure = "feet below the floor top"; return null; }
            int columns = 0;
            for (CollisionResult.Hit h : q.hits()) {
                if (h.y() != floorCellY) continue;
                if (!h.definite()) { scanFailure = "context-dependent shape in the floor layer"; return null; }
                if (h.shape().boxCount() != 1) { scanFailure = "non-cube collider at " + h.x() + "," + h.y() + "," + h.z(); return null; }
                Aabb b = h.worldBox(0);
                if (b.minX() != h.x() || b.minY() != h.y() || b.minZ() != h.z() || b.maxX() != h.x() + 1 || b.maxY() != h.y() + 1 || b.maxZ() != h.z() + 1) { scanFailure = "non-full-cube collider at " + h.x() + "," + h.y() + "," + h.z(); return null; }
                columns++;
            }
            int need = (x1 - x0 + 1) * (z1 - z0 + 1); if (columns != need) { scanFailure = "incomplete floor layer (" + columns + " of " + need + " columns)"; return null; }
        }
        BlockMovementTable.Block floorBlock = null;
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            cellsScanned++;
            if (f.present && y < floorCellY) continue; // never read by the tick
            BlockBehaviorQuery.Behavior b = env.behaviors().at(env.view(), x, y, z);
            if (!b.known()) { scanFailure = "cell " + x + "," + y + "," + z + " " + b.status() + "/" + b.worldStatus(); return null; }
            if (f.present && y == floorCellY) {
                if (floorBlock == null) floorBlock = b.block(); else if (floorBlock != b.block()) { scanFailure = "floor of mixed blocks"; return null; }
                if (b.hasFluid()) { scanFailure = "fluid in floor layer"; return null; }
                continue;
            }
            if (!b.air() || b.hasFluid() || b.block().climbable() || b.block().insideEffect() != BlockMovementTable.InsideEffect.NONE) { scanFailure = "non-air cell " + b.block().name() + " at " + x + "," + y + "," + z; return null; }
        }
        if (f.present) {
            boolean plain = floorBlock != null && (double) floorBlock.speedFactor() == 1.0 && (double) floorBlock.jumpFactor() == 1.0 && floorBlock.bounceRestitution() == 0.0f && (floorBlock.stepOn() == BlockMovementTable.StepOn.NONE || floorBlock.stepOn() == BlockMovementTable.StepOn.NON_MOTION) && !floorBlock.climbable();
            f.friction = plain ? floorBlock.friction() : -1.0f;
        }
        return f;
    }
    private static int floor(double v) { return (int) Math.floor(v); }
    MovementObservationProfile observation() { return observation; }
}
