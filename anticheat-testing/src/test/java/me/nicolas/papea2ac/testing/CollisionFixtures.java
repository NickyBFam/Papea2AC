package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.world.*;

/**
 * Deterministic collision fixtures (task §57): each is a scripted observer world plus one or more queries, and its
 * digest is SHA-256 over the canonical textual rendering of every query result (certainty, provenance, status counts,
 * hits with world boxes, definite flag). Canonical block-state keys only; never run-local ids, never a live cache.
 * A changed digest is a reviewed event (docs/phase4-artifacts/collision-fixtures.json).
 */
final class CollisionFixtures {
    private CollisionFixtures() {}
    record Query(String label, Aabb box, ShapeContext context, long atSequence /* -1 = current */, long epochOverride /* -1 = world epoch */) {}
    record Fixture(String name, java.util.function.Consumer<Scene> script, List<Query> queries) {}
    static Query now(String label, Aabb box) { return new Query(label, box, ShapeContext.UNKNOWN, -1, -1); }

    static List<Fixture> all() {
        return List.of(
            new Fixture("simpleFullCube", s -> { s.baseline(0, 0); }, List.of(now("standing", standing(4.5, 70, 4.5)), now("sunk", standing(4.5, 69.5, 4.5)), now("cornerSunk", standing(5.0, 69.5, 5.0)))),
            new Fixture("complexShapes", s -> { s.baseline(0, 0); s.set(4, 70, 4, STAIRS); s.set(5, 70, 4, FENCE_POST); s.set(6, 70, 4, WALL_POST); s.set(7, 70, 4, SLAB_TOP); s.set(8, 70, 4, CAULDRON); s.set(9, 70, 4, HOPPER); s.set(4, 70, 6, DOOR_CLOSED); s.set(5, 70, 6, TRAPDOOR_OPEN); s.set(6, 70, 6, GATE_CLOSED); s.set(7, 70, 6, SNOW_2); s.set(8, 70, 6, CARPET); s.set(9, 70, 6, LADDER); s.set(4, 70, 8, BED_HEAD); s.set(5, 70, 8, CHEST); },
                List.of(now("rowA", new Aabb(3.5, 69.5, 3.5, 10.5, 72.5, 4.5)), now("rowB", new Aabb(3.5, 69.5, 5.5, 10.5, 72.5, 6.5)), now("rowC", new Aabb(3.5, 69.5, 7.5, 6.5, 72.5, 8.5)), now("aboveFence", new Aabb(5.2, 71.2, 4.2, 5.8, 72.8, 4.8)))),
            new Fixture("contextAndOffsetShapes", s -> { s.baseline(0, 0); s.set(4, 70, 4, SCAFFOLDING); s.set(6, 70, 4, POWDER_SNOW); s.set(8, 70, 4, BAMBOO); s.set(10, 70, 4, SHULKER); s.set(12, 70, 4, MOVING_PISTON); },
                List.of(now("noContext", new Aabb(3.5, 70.5, 3.5, 13.5, 72.5, 4.5)), new Query("bodyContext", new Aabb(3.5, 70.999995, 3.5, 13.5, 72.5, 4.5), ShapeContext.of(70.999995, false, 0, true), -1, -1), new Query("descending", new Aabb(3.5, 70.999995, 3.5, 13.5, 72.5, 4.5), ShapeContext.of(70.999995, true, 3.0, false), -1, -1))),
            new Fixture("unknownWorld", s -> { s.set(4, 70, 4, STONE); }, List.of(now("partialCell", new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)), now("unknownChunk", standing(40.5, 70, 40.5)))),
            new Fixture("coverageStates", s -> { s.baseline(0, 0); s.baseline(1, 0); s.apply(new WorldObservation.ChunkUnloaded(1, 0)); s.apply(unretained(2, 0, "OVER_INLINE_CAP")); s.set(4, 70, 4, BOGUS); },
                List.of(now("unloaded", standing(20.5, 70, 8.5)), now("notRetained", standing(36.5, 70, 8.5)), now("unsupported", new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8)), now("edge", standing(15.5, 70, 8.5)))),
            new Fixture("historicalState", s -> { s.baseline(0, 0); s.mark(); s.set(4, 70, 4, STONE); s.mark(); s.set(4, 70, 4, AIR); },
                List.of(new Query("beforeChange", new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8), ShapeContext.UNKNOWN, 3, -1), new Query("atChange", new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8), ShapeContext.UNKNOWN, 4, -1), new Query("afterRemoval", new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8), ShapeContext.UNKNOWN, 6, -1), new Query("future", new Aabb(4.2, 70.2, 4.2, 4.8, 71, 4.8), ShapeContext.UNKNOWN, 99, -1))),
            new Fixture("worldEpochMismatch", s -> { s.baseline(0, 0); s.apply(NETHER); },
                List.of(new Query("oldEpoch", standing(4.5, 70, 4.5), ShapeContext.UNKNOWN, -1, 0), now("newEpochNoChunk", standing(4.5, 70, 4.5)))),
            new Fixture("boundsAndRejection", s -> { s.baseline(0, 0); },
                List.of(now("oversized", new Aabb(0, 60, 0, 40, 80, 40)), now("coordinateLimit", new Aabb(3.3E7, 70, 0, 3.3E7 + 1, 71, 1)), now("aboveWorld", new Aabb(4.2, 318.5, 4.2, 4.8, 320.3, 4.8)))));
    }
    static List<CollisionResult> run(Fixture f, Scene s) {
        f.script.accept(s); var out = new ArrayList<CollisionResult>();
        for (var q : f.queries) {
            var view = q.atSequence < 0 ? s.world.currentView() : s.world.viewAtSequence(q.atSequence);
            out.add(s.engine.query(view, q.box, q.context, q.epochOverride < 0 ? s.world.worldEpoch() : q.epochOverride));
        }
        return out;
    }
    /** Canonical rendering: label, certainty(+reason), epoch/revision/sequence, counts by status name, truncated, then every hit as cell / definite / status / canonical world boxes. */
    static String render(Fixture f, List<CollisionResult> results) {
        var sb = new StringBuilder(f.name).append('\n');
        for (int i = 0; i < results.size(); i++) {
            var q = f.queries.get(i); var r = results.get(i);
            sb.append(q.label).append(' ').append(r.certainty()).append(r.unavailableReason() == null ? "" : "(" + r.unavailableReason() + ")").append(" epoch=").append(r.worldEpoch()).append(" rev=").append(r.revision()).append(" seq=").append(r.sequence()).append(" cells=").append(r.cellsVisited()).append(" truncated=").append(r.truncated());
            for (CellStatus cs : CellStatus.values()) if (r.count(cs) > 0) sb.append(' ').append(cs.name()).append('=').append(r.count(cs));
            sb.append('\n');
            for (var h : r.hits()) { sb.append("  ").append(h.x()).append(',').append(h.y()).append(',').append(h.z()).append(h.definite() ? " definite " : " possible ").append(h.status()); for (int b = 0; b < h.shape().boxCount(); b++) { var w = h.worldBox(b); sb.append(" [").append(w.minX()).append(' ').append(w.minY()).append(' ').append(w.minZ()).append(' ').append(w.maxX()).append(' ').append(w.maxY()).append(' ').append(w.maxZ()).append(']'); } sb.append('\n'); }
        }
        return sb.toString();
    }
    static String digest(String rendered) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rendered.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
