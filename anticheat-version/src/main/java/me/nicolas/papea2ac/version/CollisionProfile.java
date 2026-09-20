package me.nicolas.papea2ac.version;
import java.util.Objects;
/**
 * The pinned target's collision rules as pure data (COLLISION_ENGINE.md C1: "shape kinds have version provenance";
 * NUMERICAL_POLICY: "Tolerances must name their source"). Every constant below names the vanilla source it
 * reproduces; none is a generic "close enough" epsilon and none compounds per tick. The geometry engine in
 * anticheat-collision reads these; it defines no tolerance of its own.
 */
public record CollisionProfile(String id, BlockShapeTable table) {
    public CollisionProfile { Objects.requireNonNull(id); Objects.requireNonNull(table); if (id.isEmpty() || id.length() > 64) throw new IllegalArgumentException("profile id"); }

    /** Vanilla {@code Shapes.EPSILON}: the coordinate-merge tolerance of voxel-shape boolean joins and of {@code VoxelShape.collide}'s index probes (min+eps / max-eps) and its final contact acceptance (d >= -eps). */
    public static final double SHAPE_EPSILON = 1.0E-7;
    /** Vanilla {@code EntityCollisionContext.isAbove}: {@code entityBottom > blockY + shapeMaxY - 1.0E-5F}; the float literal widened exactly as the JVM does. */
    public static final double ABOVE_TOLERANCE = 9.999999747378752E-6;
    /** Vanilla {@code PowderSnowBlock.getCollisionShape}: an entity that has fallen more than this many blocks gets the 0.9-high falling shape instead of nothing/full. */
    public static final double POWDER_SNOW_FALL_DISTANCE = 2.5;
    /** Vanilla {@code BlockCollisions}/{@code Cursor3D}: the cell range is floor(min - EPSILON) - 1 .. floor(max + EPSILON) + 1; ring cells are visited only for large shapes (face ring) and moving pistons (edge ring), never corners. */
    public static final double BROAD_PHASE_PADDING = SHAPE_EPSILON;
    /** PERFORMANCE.md "Collision: 4096 cells / 16384 boxes / query" - cells counted over the full padded range including the ring, boxes over emitted shape boxes. */
    public static final int MAX_CELLS_PER_QUERY = 4096, MAX_BOXES_PER_QUERY = 16384;
    /** Vanilla {@code ShulkerBoxBlockEntity.getBoundingBox}: the lid extends at most {@code 0.5 * progress} blocks toward the facing direction (progress <= 1). */
    public static final double SHULKER_LID_EXTENT = 0.5;

    public static final CollisionProfile PAPER_26_2 = new CollisionProfile("paper-26.2-124-collision", BlockShapeTable.paper26_2());
}
