package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.collision.AxisClip;
import me.nicolas.papea2ac.collision.CollisionResult;
import me.nicolas.papea2ac.collision.CollisionShape;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.BlockLookup;
import java.util.ArrayList;
import java.util.List;

/**
 * The movement step's view of geometry: vanilla {@code Entity.collide} / {@code collideWithShapes} /
 * {@code collectCandidateStepUpHeights} / {@code Level.noCollision} / {@code Level.findSupportingBlock} composed over
 * the Phase 4 primitives (COLLISION_ENGINE.md: "narrow phase clips using the selected version's axis/step ordering. Do
 * not invent a universal XYZ ordering" - the ordering lives HERE, in the physics module, exactly as 26.2 does it:
 * {@code Direction.axisStepOrder}: Y first, then the larger horizontal component). Colliders are ordered as vanilla
 * {@code collectColliders} orders them: entity hard colliders, then the world border, then block shapes in broad-phase
 * order. Every engine query is charged to the step budget; certainty below COMPLETE and possible-only envelope hits are
 * recorded as reasons - the step continues on the DEFINITE geometry (a lower bound) and the caller sees the status.
 */
final class MovementGeometry {
    /** One collider as vanilla holds it: a whole shape at a world origin (block cell + offset, or 0 for an entity box). */
    record Collider(CollisionShape shape, double ox, double oy, double oz, int cellX, int cellY, int cellZ) {}
    private final PhysicsEnvironment env; private final Uncertainty u; private final AxisClip.Axis[] axes = AxisClip.Axis.values();
    int queries, cells, boxes, insideCells, entityOps; boolean budgetExceeded, steppedUp;

    MovementGeometry(PhysicsEnvironment env, Uncertainty u) { this.env = env; this.u = u; }

    BlockBehaviorQuery.Behavior behavior(int x, int y, int z) { return env.behaviors().at(env.view(), x, y, z); }
    me.nicolas.papea2ac.model.BlockStateKey key(int stateId) { return env.behaviors().dictionary().key(stateId); }

    /** Vanilla {@code Level.getBlockCollisions} through the Phase 4 engine, plus coverage bookkeeping. Returns the definite colliders in broad-phase order (never null). */
    List<Collider> blockColliders(Aabb region, ShapeContext ctx, String purpose) {
        List<Collider> out = new ArrayList<>();
        if (queries >= env.budget().maxQueries()) { if (!budgetExceeded) u.add(PhysicsResult.Reason.Code.QUERY_BUDGET, purpose + ": more than " + env.budget().maxQueries() + " queries"); budgetExceeded = true; return out; }
        queries++;
        CollisionResult r = env.collision().query(env.view(), region, ctx, env.worldEpoch());
        cells += r.cellsVisited(); boxes += r.boxCount();
        if (cells > env.budget().maxCells() && !budgetExceeded) { u.add(PhysicsResult.Reason.Code.CELL_BUDGET, purpose + ": " + cells + " cells visited"); budgetExceeded = true; }
        switch (r.certainty()) {
            case COMPLETE -> { }
            case INCOMPLETE -> u.add(PhysicsResult.Reason.Code.COLLISION_INCOMPLETE, purpose + ": " + r.summary());
            case UNAVAILABLE -> u.add(r.unavailableReason() == me.nicolas.papea2ac.collision.CellStatus.WRONG_WORLD_EPOCH ? PhysicsResult.Reason.Code.WRONG_WORLD_EPOCH : PhysicsResult.Reason.Code.VIEW_UNAVAILABLE, purpose + ": " + r.unavailableReason());
            case REJECTED -> u.add(PhysicsResult.Reason.Code.QUERY_REJECTED, purpose + ": " + region);
        }
        if (env.equipment().knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN && r.count(me.nicolas.papea2ac.collision.CellStatus.EXACT_CONTEXT) > 0) u.add(PhysicsResult.Reason.Code.EQUIPMENT_UNKNOWN, purpose + ": a context-dependent shape was resolved without knowing the boots");
        for (CollisionResult.Hit h : r.hits()) {
            if (h.definite()) out.add(new Collider(h.shape(), h.originX(), h.originY(), h.originZ(), h.x(), h.y(), h.z()));
            else u.add(PhysicsResult.Reason.Code.ENVELOPE_GEOMETRY, purpose + ": possible-only shape at " + h.x() + "," + h.y() + "," + h.z() + " " + h.status());
        }
        return out;
    }
    /** Vanilla {@code Level.getEntityCollisions(entity, box)}: hard-collider entities whose box intersects the region; UNKNOWN neighbourhood is a reason, not an empty list. */
    List<Collider> entityColliders(Aabb region) {
        PhysicsEnvironment.EntityNeighborhood n = env.entities();
        if (n.knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN) { u.add(PhysicsResult.Reason.Code.ENTITIES_UNKNOWN, "entity colliders unknown for " + region); return List.of(); }
        List<Collider> out = new ArrayList<>();
        for (Aabb b : n.hardColliders()) { entityOps++; if (b.intersects(region)) out.add(new Collider(CollisionShape.of(b), 0, 0, 0, 0, 0, 0)); }
        if (entityOps > env.budget().maxEntityOperations() && !budgetExceeded) { u.add(PhysicsResult.Reason.Code.ENTITY_BUDGET, "entity operations " + entityOps); budgetExceeded = true; }
        return out;
    }
    /** Vanilla {@code collectColliders(entity, level, entityShapes, region)}: entities, then the border (unsupported when close), then blocks. */
    List<Collider> collectColliders(List<Collider> entityShapes, Aabb region, double px, double pz, ShapeContext ctx, String purpose) {
        List<Collider> all = new ArrayList<>(entityShapes);
        border(px, pz, region);
        all.addAll(blockColliders(region, ctx, purpose));
        return all;
    }
    private void border(double px, double pz, Aabb region) {
        PhysicsEnvironment.WorldBorder b = env.border();
        if (b.knowledge() == PhysicsEnvironment.Knowledge.UNKNOWN) u.add(PhysicsResult.Reason.Code.BORDER_UNKNOWN, "world border unknown");
        else if (b.closeTo(px, pz, region)) u.add(PhysicsResult.Reason.Code.BORDER_PROXIMITY, "within border proximity of " + region);
    }

    /** Vanilla {@code Entity.collideWithShapes}: per axis in {@code axisStepOrder(movement)}, {@code Shapes.collide} against the accumulated-moved box. */
    Vec3d collideWithShapes(Vec3d movement, Aabb box, List<Collider> shapes) {
        if (shapes.isEmpty()) return movement;
        Vec3d v = Vec3d.ZERO;
        for (int axis : movement.axisStepOrder()) {
            double d = movement.get(axis);
            if (d != 0.0) v = v.with(axis, collide(axes[axis], box.move(v.x(), v.y(), v.z()), shapes, d));
        }
        return v;
    }
    /** Vanilla {@code Shapes.collide(axis, box, shapes, desired)}: early exit to 0 below {@code Shapes.EPSILON}, then each shape's {@code collideX}. */
    static double collide(AxisClip.Axis axis, Aabb box, List<Collider> shapes, double desired) {
        for (Collider c : shapes) {
            if (Math.abs(desired) < CollisionProfile.SHAPE_EPSILON) return 0.0;
            desired = AxisClip.clip(axis, box, desired, c.shape, c.ox, c.oy, c.oz);
        }
        return desired;
    }

    /**
     * Vanilla {@code Entity.collide(Vec3)} (client): initial clip over the swept box, then the step-up candidates
     * ({@code collectCandidateStepUpHeights}: every collider Y edge above the (possibly lowered) box bottom, below the
     * step height, not equal to the already achieved vertical result, ascending) tried in order until one yields more
     * horizontal progress. {@code steppedUp} records whether a candidate won.
     */
    Vec3d collide(Vec3d movement, Aabb box, double px, double pz, boolean onGround, float maxUpStep, ShapeContext ctx) {
        steppedUp = false;
        if (movement.x() == 0.0 && movement.y() == 0.0 && movement.z() == 0.0) return movement;
        Aabb swept = box.expandTowards(movement.x(), movement.y(), movement.z());
        List<Collider> entityShapes = entityColliders(swept);
        List<Collider> colliders = collectColliders(entityShapes, swept, px, pz, ctx, "move");
        for (Collider c : colliders) if (c.shape.isFullCube() ? box.intersects(c.ox, c.oy, c.oz, c.ox + 1, c.oy + 1, c.oz + 1) : me.nicolas.papea2ac.collision.CollisionEngine.intersects(c.shape, c.ox, c.oy, c.oz, box)) { u.add(PhysicsResult.Reason.Code.INSIDE_BLOCK_START, "body overlaps a collider at " + c.cellX + "," + c.cellY + "," + c.cellZ + ": LocalPlayer.moveTowardsClosestSpace is not modelled"); break; }
        Vec3d collided = movement.lengthSqr() == 0.0 ? movement : collideWithShapes(movement, box, colliders);
        boolean bx = movement.x() != collided.x(), by = movement.y() != collided.y(), bz = movement.z() != collided.z();
        boolean down = by && movement.y() < 0.0;
        if (maxUpStep > 0.0f && (down || onGround) && (bx || bz)) {
            Aabb box2 = down ? box.move(0.0, collided.y(), 0.0) : box;
            Aabb box3 = box2.expandTowards(movement.x(), (double) maxUpStep, movement.z());
            if (!down) box3 = box3.expandTowards(0.0, -MovementProfile.STEP_BOTTOM_EPSILON, 0.0);
            List<Collider> stepColliders = collectColliders(entityShapes, box3, px, pz, ctx, "step");
            float[] heights = stepUpHeights(box2, stepColliders, maxUpStep, (float) collided.y());
            for (float h : heights) {
                Vec3d c2 = collideWithShapes(new Vec3d(movement.x(), (double) h, movement.z()), box2, stepColliders);
                if (c2.horizontalDistanceSqr() > collided.horizontalDistanceSqr()) {
                    double d = box.minY() - box2.minY();
                    steppedUp = true;
                    return c2.add(0.0, -d, 0.0);
                }
            }
        }
        return collided;
    }
    /** Vanilla {@code collectCandidateStepUpHeights}: a FloatArraySet of {@code (float)(edgeY - box.minY)} values, then unstable-sorted ascending. */
    static float[] stepUpHeights(Aabb box, List<Collider> colliders, float maxUpStep, float achieved) {
        float[] set = new float[8]; int n = 0;
        for (Collider c : colliders) {
            int ny = c.shape.gridSize(1) + 1;
            for (int i = 0; i < ny; i++) {
                double d = c.shape.gridCoord(1, i) + c.oy;
                float f = (float) (d - box.minY());
                if (f < 0.0f || f == achieved) continue;
                if (f > maxUpStep) break;
                boolean dup = false; for (int k = 0; k < n; k++) if (set[k] == f) { dup = true; break; }
                if (dup) continue;
                if (n == set.length) set = java.util.Arrays.copyOf(set, n * 2);
                set[n++] = f;
            }
        }
        float[] out = java.util.Arrays.copyOf(set, n);
        java.util.Arrays.sort(out);
        return out;
    }

    /** Vanilla {@code Level.noCollision(entity, box)}: blocks, then entities, then the border. Uncertain geometry is recorded; the definite answer is returned. */
    boolean noCollision(Aabb box, double px, double pz, ShapeContext ctx, String purpose) {
        if (!blockColliders(box, ctx, purpose).isEmpty()) return false;
        if (!entityColliders(box).isEmpty()) return false;
        border(px, pz, box);
        return true;
    }
    /** Vanilla {@code Level.findSupportingBlock(entity, box)}: among cells whose collision shape intersects the probe, the one whose centre is nearest the entity position (ties: the greater BlockPos by y, z, x). */
    PhysicsState.Support findSupportingBlock(Aabb probe, Vec3d position, ShapeContext ctx) {
        int bx = 0, by = 0, bz = 0; boolean found = false; double best = Double.MAX_VALUE;
        // one entry per cell: collect definite hits per cell (a cell contributes once even with several shapes)
        List<Collider> hits = blockColliders(probe, ctx, "support");
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (Collider c : hits) {
            int x = c.cellX, y = c.cellY, z = c.cellZ;
            if (x == lastX && y == lastY && z == lastZ) continue;
            lastX = x; lastY = y; lastZ = z;
            double dx = (double) x + 0.5 - position.x(), dy = (double) y + 0.5 - position.y(), dz = (double) z + 0.5 - position.z();
            double e = dx * dx + dy * dy + dz * dz;
            if (e < best || e == best && (!found || compare(bx, by, bz, x, y, z) < 0)) { bx = x; by = y; bz = z; best = e; found = true; }
        }
        return found ? PhysicsState.Support.at(bx, by, bz) : PhysicsState.Support.NONE;
    }
    /** Vanilla {@code Vec3i.compareTo}: y, then z, then x. */
    private static int compare(int x1, int y1, int z1, int x2, int y2, int z2) { return y1 != y2 ? y1 - y2 : z1 != z2 ? z1 - z2 : x1 - x2; }
    static boolean unknown(BlockBehaviorQuery.Behavior b) { return b.status() == BlockBehaviorQuery.Status.UNKNOWN; }
    static String describe(BlockBehaviorQuery.Behavior b) { return b.status() == BlockBehaviorQuery.Status.UNKNOWN ? b.worldStatus().name() : b.status() == BlockBehaviorQuery.Status.UNSUPPORTED ? "UNSUPPORTED_STATE" : b.block().name(); }
    static boolean unloaded(BlockBehaviorQuery.Behavior b) { return b.worldStatus() == BlockLookup.Status.UNLOADED; }
}
