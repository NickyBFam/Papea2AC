package me.nicolas.papea2ac.mitigation;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.collision.CollisionResult;
import me.nicolas.papea2ac.collision.ShapeContext;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.AnchorSource;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
/**
 * The explicit safe-anchor criteria (Phase 10 SAFE ANCHOR MODEL). Core extracts the facts of one completed live validation (it owns the
 * Phase 8 types; mitigation does not depend on simulation) and this class decides - in the documented order, first failure wins - whether
 * the resulting state may ever be a setback target. UNKNOWN is never clear.
 *
 * <ol>
 * <li>the transition was VALID with every one of the 13 completeness dimensions true (world, entities, external motion, mechanics ...);</li>
 * <li>the chain continues ({@code nextFrontierComplete}) and every continuation agrees on ONE concrete position (exact doubles);</li>
 * <li>every continuation is on the ground (the trusted velocity context), on foot (STANDING or CROUCHING - MITIGATION.md: "Initial setback
 *     profile is on-foot only") and finite;</li>
 * <li>no correction is pending or unconfirmed, no external motion is pending, no block dispute touches the region;</li>
 * <li>the session and world epoch are the owner's (never pre-capture, never across a discontinuity);</li>
 * <li>the STANDING box at the position is certainly clear in the observer world (COMPLETE query, no hit) - clearance for the tallest
 *     on-foot pose, so a crouching continuation is covered too.</li>
 * </ol>
 */
public final class AnchorAdmission {
    /** Facts of one completed live transition, extracted by core. */
    public record Facts(SessionKey session, long worldEpoch, long sequence, long nanos, boolean valid, boolean completenessAll, boolean nextFrontierComplete,
                        List<Vector3> continuationPositions, boolean allGrounded, boolean anyCrouching, boolean onFootPose, boolean finiteState,
                        int pendingTeleports, int pendingInfluences, boolean worldDisputed, boolean locomotionSupported, float yaw, float pitch) {
        public Facts { Objects.requireNonNull(session); continuationPositions = List.copyOf(continuationPositions); }
    }
    /** Admission answer: the anchor, or the typed reason it was refused. */
    public record Result(Optional<SafeAnchor> anchor, Reason refusal, CollisionResult.Certainty clearance) {
        public Result { Objects.requireNonNull(anchor); }
    }

    private final CollisionEngine collision; private final Function<Vector3, Aabb> standingBox;
    /** {@code standingBox}: the STANDING on-foot player box at a position (supplied by core from the pinned collision profile; mitigation does not depend on version). */
    public AnchorAdmission(CollisionEngine collision, Function<Vector3, Aabb> standingBox) { this.collision = Objects.requireNonNull(collision); this.standingBox = Objects.requireNonNull(standingBox); }
    public Aabb standingBox(Vector3 p) { return standingBox.apply(p); }

    public Result admit(Facts f, SessionKey ownerSession, long ownerEpoch, WorldView view) {
        Objects.requireNonNull(f); Objects.requireNonNull(view);
        if (!f.session().equals(ownerSession)) return refuse(Reason.SESSION_MISMATCH);
        if (f.worldEpoch() != ownerEpoch || view.worldEpoch() != ownerEpoch) return refuse(Reason.WORLD_EPOCH_MISMATCH);
        if (!f.valid() || !f.completenessAll()) return refuse(Reason.WORLD_INCOMPLETE);
        if (!f.locomotionSupported() || !f.onFootPose()) return refuse(Reason.UNSUPPORTED_LOCOMOTION);
        if (!f.nextFrontierComplete() || f.continuationPositions().isEmpty()) return refuse(Reason.NO_SAFE_ANCHOR);
        Vector3 p = f.continuationPositions().get(0);
        for (Vector3 q : f.continuationPositions()) if (Double.doubleToLongBits(q.x()) != Double.doubleToLongBits(p.x()) || Double.doubleToLongBits(q.y()) != Double.doubleToLongBits(p.y()) || Double.doubleToLongBits(q.z()) != Double.doubleToLongBits(p.z())) return refuse(Reason.NO_SAFE_ANCHOR);
        if (!f.finiteState() || !f.allGrounded()) return refuse(Reason.NO_SAFE_ANCHOR);
        if (f.pendingTeleports() > 0) return refuse(Reason.UNRESOLVED_TELEPORT);
        if (f.pendingInfluences() > 0) return refuse(Reason.UNRESOLVED_EXTERNAL_MOTION);
        if (f.worldDisputed()) return refuse(Reason.BLOCK_DISPUTE);
        Clearance c = clearance(view, p, ownerEpoch);
        if (c.reason != null) return new Result(Optional.empty(), c.reason, c.certainty);
        List<String> basis = new ArrayList<>(List.of("VALID+ALL13", "concrete x" + f.continuationPositions().size(), "grounded", "no-pending-correction", "no-pending-motion", "no-dispute", "standing-box-CLEAR@rev" + view.revision()));
        return new Result(Optional.of(new SafeAnchor(f.session(), f.worldEpoch(), view.revision(), f.sequence(), f.nanos(), p, f.yaw(), f.pitch(), true, f.anyCrouching(), AnchorSource.VALID_CONCRETE_GROUNDED, basis)), null, c.certainty);
    }

    /** The standing box at {@code p}, certified against {@code view}: CLEAR only for a COMPLETE query with no hit. */
    public Clearance clearance(WorldView view, Vector3 p, long epoch) {
        Aabb box = standingBox.apply(p);
        CollisionResult r = collision.query(view, box, ShapeContext.UNKNOWN, epoch);
        if (r.definitelyObstructed()) return new Clearance(Reason.COLLISION_OBSTRUCTED, r.certainty());
        if (r.certainty() != CollisionResult.Certainty.COMPLETE || r.hitCount() > 0) return new Clearance(Reason.COLLISION_UNKNOWN, r.certainty());
        return new Clearance(null, r.certainty());
    }
    public record Clearance(Reason reason, CollisionResult.Certainty certainty) { public boolean clear() { return reason == null; } }
    private static Result refuse(Reason r) { return new Result(Optional.empty(), r, null); }
}
