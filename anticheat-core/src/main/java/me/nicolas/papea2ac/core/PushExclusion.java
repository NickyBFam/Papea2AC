package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.combat.reach.ReachValidator;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.simulation.MovementValidationRequest;
import me.nicolas.papea2ac.simulation.TimingReconstruction;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.world.EntityHistory;
import me.nicolas.papea2ac.world.ObserverWorld;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
/**
 * Phase 13 push exclusion (melee knockback): proves that no relevant entity's box can have intersected the local player's box during one
 * transition, so that the client's entity pushes ({@code ClientLevel.getPushableEntities}: only on box intersection) and entity collisions
 * cannot have acted on it. When the proof holds the runner treats the neighbourhood as empty; otherwise it stays UNKNOWN (Phase 8). The proof
 * never models a push: it only shows that none was possible.
 *
 * <p>Local player: every client tick between the anchor and the target transmitted nothing beyond the send threshold, so every intermediate
 * position lies within {@link #SEND_THRESHOLD} of the last transmitted one (the client's send rule, MovementObservationProfile); the target
 * tick ends at the target position; the frontier holds the anchor states. Its box uses the standing height (the tallest supported pose) times
 * the client's {@code scale}, which must be known (default 1.0 when the attribute was never synced with modifiers).
 *
 * <p>Other entities: the Phase 12 shared bound ({@link ReachValidator#possibleEntityBox}: the interpolation hull of the network targets since
 * the newest answered marker before the anchor, grown by the widest/tallest admissible dimensions incl. scale, baby and pose). Every box is
 * grown by {@link #MARGIN} on each side (boats push with a box inflated by 0.2). Any entity whose bound is unknown means "contact possible".
 */
public final class PushExclusion implements MovementValidationRunner.NeighborhoodProof {
    public static final double SEND_THRESHOLD = 2.0E-4, MARGIN = 0.25, STANDING_HEIGHT = 1.8, WIDTH = 0.6, CANDIDATE_RADIUS_H = 4.0, CANDIDATE_RADIUS_V = 4.0;
    static final String ATTR_SCALE = "minecraft:scale";
    private final Supplier<ReachRunner> reach; private final Supplier<EntityHistory> entities; private final Supplier<List<TimingEvent>> events; private final Supplier<ObserverWorld> world; private final EntityGeometryTable table;
    private long proven, refused;

    public PushExclusion(Supplier<ReachRunner> reach, Supplier<EntityHistory> entities, Supplier<List<TimingEvent>> events, Supplier<ObserverWorld> world, EntityGeometryTable table) {
        this.reach = Objects.requireNonNull(reach); this.entities = Objects.requireNonNull(entities); this.events = Objects.requireNonNull(events); this.world = Objects.requireNonNull(world); this.table = Objects.requireNonNull(table);
    }
    public long proven() { return proven; }
    public long refused() { return refused; }

    @Override public boolean noContact(MovementValidationRequest req, MovementContextLedger.View ctx) {
        try { boolean ok = check(req, ctx); if (ok) proven++; else refused++; return ok; }
        catch (RuntimeException e) { refused++; return false; } // a failed proof is never a proof
    }

    private boolean check(MovementValidationRequest req, MovementContextLedger.View ctx) {
        ApplicationFrontier frontier = ApplicationFrontier.of(events.get(), req.timing().anchorSequence() + 1);
        long target = req.timing().target().sequence();
        return noContact(req, ctx, frontier, id -> ReachValidator.possibleEntityBox(reach.get().entityInput(id, target, entities.get(), frontier, world.get()), table));
    }
    /** The decision itself, over a bound per entity id ({@code null} = unknown, contact possible). */
    public static boolean noContact(MovementValidationRequest req, MovementContextLedger.View ctx, ApplicationFrontier frontier, java.util.function.IntFunction<PickMath.Box> boxOf) {
        Double scale = selfScale(ctx, frontier); if (scale == null) return false;
        PickMath.Box local = localRegion(req, scale);
        double cx = (local.minX() + local.maxX()) / 2, cy = local.minY(), cz = (local.minZ() + local.maxZ()) / 2;
        for (MovementContextLedger.TrackedEntity e : ctx.relevantEntities()) {
            double dx = e.x() - cx, dz = e.z() - cz, rh = CANDIDATE_RADIUS_H + (local.maxX() - local.minX());
            if (dx * dx + dz * dz > rh * rh || Math.abs(e.y() - cy) > CANDIDATE_RADIUS_V + (local.maxY() - local.minY())) continue; // the Phase 8 radius rule already ignores it
            PickMath.Box b = boxOf.apply(e.entityId());
            if (b == null) return false;
            PickMath.Box grown = new PickMath.Box(b.minX() - MARGIN, b.minY() - MARGIN, b.minZ() - MARGIN, b.maxX() + MARGIN, b.maxY() + MARGIN, b.maxZ() + MARGIN);
            if (grown.intersects(local)) return false;
        }
        return true;
    }

    /** Every box the local player can have had during the transition (send-threshold rule for the silent ticks, the target and the anchor states). */
    static PickMath.Box localRegion(MovementValidationRequest req, double scale) {
        Vector3 s = req.timing().lastSentPosition();
        double minX = s.x() - SEND_THRESHOLD, minY = s.y() - SEND_THRESHOLD, minZ = s.z() - SEND_THRESHOLD, maxX = s.x() + SEND_THRESHOLD, maxY = s.y() + SEND_THRESHOLD, maxZ = s.z() + SEND_THRESHOLD;
        if (req.timing().target().hasPosition()) { Vector3 t = req.timing().target().position(); minX = Math.min(minX, t.x()); minY = Math.min(minY, t.y()); minZ = Math.min(minZ, t.z()); maxX = Math.max(maxX, t.x()); maxY = Math.max(maxY, t.y()); maxZ = Math.max(maxZ, t.z()); }
        for (TimingReconstruction.Node n : req.frontier().nodes()) { var p = n.state().position(); minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z()); maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z()); }
        double hw = Math.nextUp(WIDTH * scale / 2.0), h = Math.nextUp(STANDING_HEIGHT * scale);
        return new PickMath.Box(Math.nextDown(minX - hw), Math.nextDown(minY), Math.nextDown(minZ - hw), Math.nextUp(maxX + hw), Math.nextUp(maxY + h), Math.nextUp(maxZ + hw));
    }
    /** The local player's scale: 1.0 when never synced; the base when synced without modifiers before the marker-proven point (so the client applied it); otherwise unknown (no proof). */
    static Double selfScale(MovementContextLedger.View ctx, ApplicationFrontier frontier) {
        if (!ctx.contextComplete()) return null;
        MovementContextLedger.AttributeValue v = ctx.attributes().get(ATTR_SCALE);
        if (v == null) return 1.0;
        if (!frontier.proves(v.sequence())) return null; // a scale change the client may not have applied yet
        for (ContextEvent.ModifierValue m : v.modifiers()) if (m.amount() != 0.0) return null;
        return v.base() >= 0.0625 && v.base() <= 16.0 ? v.base() : null;
    }
}
