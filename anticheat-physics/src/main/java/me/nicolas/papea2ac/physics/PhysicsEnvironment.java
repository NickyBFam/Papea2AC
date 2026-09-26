package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.BlockBehaviorQuery;
import me.nicolas.papea2ac.world.WorldView;
import java.util.*;

/**
 * Everything a forward step needs besides the state and the input, supplied EXPLICITLY by the caller (MOVEMENT_SIMULATION.md
 * "SimulationInput: ... world view(s); abilities/attributes/effects; external impulse ledger; collision/numeric
 * profile; deterministic work budget"). Every fact that could be unknown carries a {@link Knowledge} so the step can say
 * precisely why a prediction is not EXACT instead of defaulting: an unknown attribute is not the vanilla default, an
 * unknown entity neighbourhood is not "empty", an unknown border is not "far away".
 *
 * <ul>
 * <li>{@code view}/{@code worldEpoch} - the immutable observer world snapshot the step reads (one view per step, PERFORMANCE.md);</li>
 * <li>{@code attributes} - per movement attribute: base value + the client's modifier list (packet order); Speed/Slowness
 *     effect modifiers may be given either here (as the server sends them) or through {@code effects} (deduplicated by id);</li>
 * <li>{@code effects} - the active effects with amplifiers, and whether the list is complete;</li>
 * <li>{@code externalMotions} - influences the CALLER decided are applied before this tick (PLAYER_STATE.md: "physics
 *     applies it"; the consumption decision is Phase 7's): SET replaces the velocity ({@code lerpMotion} for a velocity
 *     packet), ADD adds to it (explosion knockback);</li>
 * <li>{@code entities} - the bounded neighbourhood (WORLD_MODEL.md NearbyMotionEntities): hard colliders (boats, shulkers)
 *     and pushers (players, and in 26.2 every other pushing entity - P9-F5); UNKNOWN makes the step incomplete, never empty;</li>
 * <li>{@code border} - the world border; UNKNOWN is a coverage gap;</li>
 * <li>{@code equipment} - whether the player can walk on powder snow (leather boots), needed only when powder snow is touched;</li>
 * <li>{@code budget} - the per-step work bounds (PERFORMANCE.md rows "Movement geometry", "Block inside sampling", "Entity motion neighborhood").</li>
 * </ul>
 */
public record PhysicsEnvironment(WorldView view, long worldEpoch, CollisionEngine collision, BlockBehaviorQuery behaviors, MovementProfile profile,
                                 Attributes attributes, Effects effects, List<ExternalMotion> externalMotions, EntityNeighborhood entities, WorldBorder border, Equipment equipment, Budget budget) {
    public enum Knowledge { KNOWN, UNKNOWN }
    public PhysicsEnvironment {
        Objects.requireNonNull(view); Objects.requireNonNull(collision); Objects.requireNonNull(behaviors); Objects.requireNonNull(profile); Objects.requireNonNull(attributes); Objects.requireNonNull(effects);
        externalMotions = List.copyOf(externalMotions); Objects.requireNonNull(entities); Objects.requireNonNull(border); Objects.requireNonNull(equipment); Objects.requireNonNull(budget);
    }
    /** A convenient fully-known, empty environment: default player attributes, no effects, no external motion, no entities, a known default vanilla border, no boots. */
    public static PhysicsEnvironment of(WorldView view, long worldEpoch, CollisionEngine collision, BlockBehaviorQuery behaviors, MovementProfile profile) {
        return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, Attributes.defaults(profile), Effects.NONE, List.of(), EntityNeighborhood.NONE, WorldBorder.VANILLA_DEFAULT, Equipment.NO_BOOTS, Budget.DEFAULT);
    }
    public PhysicsEnvironment withAttributes(Attributes a) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, a, effects, externalMotions, entities, border, equipment, budget); }
    public PhysicsEnvironment withEffects(Effects e) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, attributes, e, externalMotions, entities, border, equipment, budget); }
    public PhysicsEnvironment withExternalMotions(List<ExternalMotion> m) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, attributes, effects, m, entities, border, equipment, budget); }
    public PhysicsEnvironment withEntities(EntityNeighborhood n) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, attributes, effects, externalMotions, n, border, equipment, budget); }
    public PhysicsEnvironment withBorder(WorldBorder b) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, attributes, effects, externalMotions, entities, b, equipment, budget); }
    public PhysicsEnvironment withEquipment(Equipment e) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, attributes, effects, externalMotions, entities, border, e, budget); }
    public PhysicsEnvironment withBudget(Budget b) { return new PhysicsEnvironment(view, worldEpoch, collision, behaviors, profile, attributes, effects, externalMotions, entities, border, equipment, b); }
    public PhysicsEnvironment withView(WorldView v, long epoch) { return new PhysicsEnvironment(v, epoch, collision, behaviors, profile, attributes, effects, externalMotions, entities, border, equipment, budget); }

    /** One attribute instance as the client holds it: base + transient/permanent modifiers in insertion (packet) order; {@code provenance} says whether the base is the profile default or a server-sent value. */
    public record Attribute(Knowledge knowledge, Provenance provenance, double base, List<MovementProfile.Modifier> modifiers) {
        public enum Provenance { PROFILE_DEFAULT, SERVER_SENT, UNKNOWN }
        public Attribute { Objects.requireNonNull(knowledge); Objects.requireNonNull(provenance); modifiers = List.copyOf(modifiers); }
        public static final Attribute UNKNOWN = new Attribute(Knowledge.UNKNOWN, Provenance.UNKNOWN, Double.NaN, List.of());
        public static Attribute known(double base, Provenance provenance, List<MovementProfile.Modifier> modifiers) { return new Attribute(Knowledge.KNOWN, provenance, base, modifiers); }
    }
    /** The movement attributes; anything not listed is UNKNOWN (never the default). */
    public record Attributes(Map<String, Attribute> byId) {
        public Attributes { byId = Map.copyOf(byId); }
        /** Every movement attribute at the profile's player default with no modifiers (a fresh player that received no attribute packet). */
        public static Attributes defaults(MovementProfile profile) {
            Map<String, Attribute> m = new HashMap<>();
            for (String id : MovementProfile.MOVEMENT_ATTRIBUTES) m.put(id, Attribute.known(profile.attribute(id).playerDefault(), Attribute.Provenance.PROFILE_DEFAULT, List.of()));
            return new Attributes(m);
        }
        public static final Attributes ALL_UNKNOWN = new Attributes(Map.of());
        public Attribute get(String id) { Attribute a = byId.get(id); return a == null ? Attribute.UNKNOWN : a; }
        public Attributes with(String id, Attribute a) { Map<String, Attribute> m = new HashMap<>(byId); m.put(id, a); return new Attributes(m); }
        public Attributes withBase(String id, double base) { return with(id, Attribute.known(base, Attribute.Provenance.SERVER_SENT, get(id).modifiers())); }
        public Attributes withModifiers(String id, List<MovementProfile.Modifier> modifiers) { Attribute a = get(id); return with(id, Attribute.known(a.base(), a.knowledge() == Knowledge.KNOWN ? a.provenance() : Attribute.Provenance.SERVER_SENT, modifiers)); }
    }
    public record EffectInstance(String id, int amplifier) { public EffectInstance { Objects.requireNonNull(id); if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("amplifier"); } }
    /** Active effects. {@code knowledge} UNKNOWN = the list may be incomplete (no effect packet coverage). */
    public record Effects(Knowledge knowledge, List<EffectInstance> active) {
        public Effects { Objects.requireNonNull(knowledge); active = List.copyOf(active); }
        public static final Effects NONE = new Effects(Knowledge.KNOWN, List.of()), UNKNOWN = new Effects(Knowledge.UNKNOWN, List.of());
        public static Effects of(EffectInstance... e) { return new Effects(Knowledge.KNOWN, List.of(e)); }
        public EffectInstance find(String id) { for (EffectInstance e : active) if (e.id.equals(id)) return e; return null; }
    }
    /**
     * An external motion influence the caller applies before this tick (PLAYER_STATE.md ExternalMotionInfluence: "operation"
     * REPLACE/ADD). {@code UNRESOLVED} declares that an influence exists whose vector or application is not known (a
     * DeclaredUnknownMotion, an unmapped mode acceleration): the step then cannot be exact - UNRESOLVED_EXTERNAL_MOTION.
     */
    public record ExternalMotion(Operation operation, Vec3d vector, String sourceId) {
        public enum Operation { SET, ADD, UNRESOLVED }
        public ExternalMotion { Objects.requireNonNull(operation); Objects.requireNonNull(vector); Objects.requireNonNull(sourceId); if (!vector.isFinite()) throw new IllegalArgumentException("non-finite external motion"); }
        public static ExternalMotion unresolved(String sourceId) { return new ExternalMotion(Operation.UNRESOLVED, Vec3d.ZERO, sourceId); }
    }
    /** A pusher near the simulated player (26.2 client: any other entity whose {@code pushEntities} reaches the local player - P9-F5): its position and box, and whether the push applies (alive, not spectator, not on a ladder, team rule allows). */
    public record Pusher(double x, double z, Aabb box, boolean pushable) { public Pusher { Objects.requireNonNull(box); } }
    /** Bounded entity neighbourhood: hard colliders (entities with {@code canBeCollidedWith}: boats, shulkers) and pushers. */
    public record EntityNeighborhood(Knowledge knowledge, List<Aabb> hardColliders, List<Pusher> pushers) {
        public EntityNeighborhood { Objects.requireNonNull(knowledge); hardColliders = List.copyOf(hardColliders); pushers = List.copyOf(pushers); }
        public static final EntityNeighborhood NONE = new EntityNeighborhood(Knowledge.KNOWN, List.of(), List.of()), UNKNOWN = new EntityNeighborhood(Knowledge.UNKNOWN, List.of(), List.of());
    }
    /** World border bounds (a static, non-lerping border; a moving border is UNKNOWN here). */
    public record WorldBorder(Knowledge knowledge, double minX, double minZ, double maxX, double maxZ) {
        public static final WorldBorder UNKNOWN = new WorldBorder(Knowledge.UNKNOWN, 0, 0, 0, 0);
        /** The vanilla default border: centre 0, size 5.9999968E7 -> +-2.9999984E7. */
        public static final WorldBorder VANILLA_DEFAULT = new WorldBorder(Knowledge.KNOWN, -2.9999984E7, -2.9999984E7, 2.9999984E7, 2.9999984E7);
        public static WorldBorder of(double centerX, double centerZ, double size) { double h = size / 2.0; return new WorldBorder(Knowledge.KNOWN, centerX - h, centerZ - h, centerX + h, centerZ + h); }
        /** Vanilla {@code WorldBorder.isInsideCloseToBorder(entity, box)}: distance to the border below twice the larger horizontal box size (at least 1), and within bounds + that size. */
        public boolean closeTo(double x, double z, Aabb box) {
            double d = Math.max(Math.max(Math.abs(box.sizeX()), Math.abs(box.sizeZ())), 1.0);
            double dist = Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
            return dist < d * 2.0 && x > minX - d && x < maxX + d && z > minZ - d && z < maxZ + d;
        }
    }
    /** Equipment facts the movement code reads: leather boots ({@code PowderSnowBlock.canEntityWalkOnPowderSnow}). */
    public record Equipment(Knowledge knowledge, boolean canWalkOnPowderSnow) {
        public static final Equipment NO_BOOTS = new Equipment(Knowledge.KNOWN, false), LEATHER_BOOTS = new Equipment(Knowledge.KNOWN, true), UNKNOWN = new Equipment(Knowledge.UNKNOWN, false);
    }
    /** Per-step work bounds. Exceeding any yields QUERY_LIMIT (an incomplete prediction, never a verdict). */
    public record Budget(int maxQueries, int maxCells, int maxInsideCells, int maxEntityOperations) {
        public Budget { if (maxQueries <= 0 || maxCells <= 0 || maxInsideCells <= 0 || maxEntityOperations <= 0) throw new IllegalArgumentException("budget"); }
        /** 64 collision queries / 16384 visited cells (a quarter of the 65536-unit movement event budget) / 512 inside-sampling cells / 2048 entity operations per step (PERFORMANCE.md). */
        public static final Budget DEFAULT = new Budget(64, 16384, 512, 2048);
    }
}
