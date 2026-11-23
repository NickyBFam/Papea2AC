package me.nicolas.papea2ac.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Version-pinned vanilla entity attack geometry (Phase 12; VERSION_SUPPORT.md "entity dimensions/poses ... interaction reach"), loaded once
 * from the generated, reviewable resource {@code entity-geometry-paper-26.2-124.tsv} that the nativeTest-only extraction
 * ({@code EntityGeometryExtractMain}) produces from the pinned Paper 26.2 build 124 runtime registry and the pinned vanilla 26.2 client jar's
 * bytecode. Production never touches NMS: the table is data.
 *
 * <p>An unknown type id, a VARIABLE dimension class, a dynamic pick radius or dynamic interpolation is never guessed: callers turn them into
 * UNSUPPORTED. Immutable and safely publishable; one shared instance ({@link #paper26_2()}).
 */
public final class EntityGeometryTable {
    /** How the client computes a type's box dimensions (see the extractor's {@code dimensionClass}). */
    public enum DimensionClass {
        /** Type dimensions; not a living entity, so no scale attribute. */
        PLAIN,
        /** Type dimensions times the {@code scale} attribute (LivingEntity.getDimensions); never a baby. */
        PLAIN_LIVING,
        /** The Avatar pose table times {@code scale} (players, mannequins). */
        AVATAR,
        /** Adult = type dimensions, baby = {@link Type#babyDimensions()} chosen by the synced boolean {@link Type#babyDataId()}; times {@code scale}. */
        BABY,
        /** Anything else: unsupported until modelled. */
        VARIABLE
    }
    /** Pickability as the client computes {@code isPickable()} for the type. */
    public enum Pickable { TRUE, FALSE, ALIVE, DYNAMIC }

    public record Dimensions(float width, float height, float eyeHeight, boolean fixed) {}

    /**
     * @param pickRadius the constant pick radius, or {@code null} when the type computes it ("dynamic")
     * @param interpolationSteps the client's steps; 0 = snaps (no interpolation handler); -1 = dynamic (unsupported)
     */
    public record Type(String id, String clientClass, Dimensions dimensions, String category, int updateInterval, int trackingRange, Float pickRadius,
                       int interpolationSteps, DimensionClass dimensionClass, String variableReason, Dimensions babyDimensions, int babyDataId,
                       Pickable pickable, Pickable pickableFromInside) {
        public boolean living() { return dimensionClass == DimensionClass.PLAIN_LIVING || dimensionClass == DimensionClass.AVATAR || dimensionClass == DimensionClass.BABY; }
    }

    public record Attribute(String id, double defaultValue, double min, double max) {}

    public static final String PAPER_26_2_RESOURCE = "/me/nicolas/papea2ac/version/entity-geometry-paper-26.2-124.tsv";
    private static final class Holder { static final EntityGeometryTable TABLE = load("paper-26.2-124-entity-geometry", PAPER_26_2_RESOURCE); }
    public static EntityGeometryTable paper26_2() { return Holder.TABLE; }

    private final String id, digest;
    private final Map<String, Type> types;
    private final Map<String, Dimensions> avatarPoses;
    private final Dimensions avatarStanding, livingSleeping;
    private final Map<String, Attribute> attributes;
    private final float creativeEntityRangeModifier;

    private EntityGeometryTable(String id, String digest, Map<String, Type> types, Map<String, Dimensions> avatarPoses, Dimensions avatarStanding, Dimensions livingSleeping,
                                Map<String, Attribute> attributes, float creativeEntityRangeModifier) {
        this.id = id; this.digest = digest; this.types = Map.copyOf(types); this.avatarPoses = Map.copyOf(avatarPoses); this.avatarStanding = avatarStanding;
        this.livingSleeping = livingSleeping; this.attributes = Map.copyOf(attributes); this.creativeEntityRangeModifier = creativeEntityRangeModifier;
    }

    public String id() { return id; }
    /** SHA-256 of the resource bytes (a changed table is a reviewed event; replay capsules carry it). */
    public String digest() { return digest; }
    /** The type, or null for an id the pinned version does not have (callers: UNSUPPORTED, never a nearest guess). */
    public Type type(String typeId) { return types.get(typeId); }
    public int typeCount() { return types.size(); }
    /** Avatar pose dimensions by Pose constant name; null for a pose outside the table (callers: STANDING_DIMENSIONS is the Avatar default). */
    public Dimensions avatarPose(String pose) { return avatarPoses.get(pose); }
    public Dimensions avatarStanding() { return avatarStanding; }
    public Dimensions livingSleeping() { return livingSleeping; }
    public Attribute attribute(String name) { return attributes.get(name); }
    public float creativeEntityRangeModifier() { return creativeEntityRangeModifier; }

    static EntityGeometryTable load(String id, String resource) {
        try (InputStream in = EntityGeometryTable.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return parse(id, in.readAllBytes());
        } catch (IOException e) { throw new IllegalStateException("cannot read " + resource, e); }
    }

    public static EntityGeometryTable parse(String id, byte[] bytes) {
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); }
        Map<String, Type> types = new TreeMap<>(); Map<String, Dimensions> poses = new TreeMap<>(); Map<String, Attribute> attributes = new TreeMap<>();
        Dimensions standing = null, sleeping = null; Float creative = null;
        for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.contains(" # ") ? raw.substring(0, raw.indexOf(" # ")) : raw;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] t = line.trim().split(" ");
            switch (t[0]) {
                case "T" -> {
                    Dimensions d = new Dimensions(f(t[3]), f(t[4]), f(t[5]), t[6].equals("fixed"));
                    Map<String, String> kv = new HashMap<>();
                    for (int i = 10; i < t.length; i++) { int eq = t[i].indexOf('='); if (eq > 0) kv.put(t[i].substring(0, eq), t[i].substring(eq + 1)); }
                    String pick = kv.get("pick"), steps = kv.get("steps"), dims = kv.get("dims");
                    Float pickRadius = pick.equals("dynamic") ? null : Float.parseFloat(pick);
                    int st = steps.equals("snap") ? 0 : steps.equals("dynamic") ? -1 : Integer.parseInt(steps);
                    DimensionClass dc; String reason = ""; Dimensions baby = null; int dataId = -1;
                    if (dims.startsWith("VARIABLE")) { dc = DimensionClass.VARIABLE; reason = dims.substring(dims.indexOf(':') + 1); }
                    else if (dims.startsWith("BABY:")) {
                        String[] b = dims.substring(5).split(",");
                        dc = DimensionClass.BABY; baby = new Dimensions(f(b[0]), f(b[1]), f(b[2]), b[3].equals("fixed")); dataId = Integer.parseInt(b[4]);
                    } else dc = DimensionClass.valueOf(dims);
                    types.put(t[1], new Type(t[1], t[2], d, t[7], Integer.parseInt(t[8]), Integer.parseInt(t[9]), pickRadius, st, dc, reason, baby, dataId,
                        pickable(kv.get("isPickable")), pickable(kv.get("pickableFromInside"))));
                }
                case "P" -> {
                    if (t[1].equals("AVATAR_STANDING")) standing = new Dimensions(f(t[2]), f(t[3]), f(t[4]), t[5].equals("fixed"));
                    else if (t[1].equals("LIVING_SLEEPING")) sleeping = new Dimensions(f(t[2]), f(t[3]), f(t[4]), t[5].equals("fixed"));
                    else if (t[1].equals("AVATAR")) poses.put(t[2], new Dimensions(f(t[3]), f(t[4]), f(t[5]), t[6].equals("fixed")));
                    else throw new IllegalArgumentException("pose line " + line);
                }
                case "A" -> attributes.put(t[1], new Attribute(t[1], Double.longBitsToDouble(Long.parseLong(t[2])), Double.longBitsToDouble(Long.parseLong(t[3])), Double.longBitsToDouble(Long.parseLong(t[4]))));
                case "M" -> { if (t[1].equals("CREATIVE_ENTITY_INTERACTION_RANGE")) creative = f(t[2]); }
                default -> throw new IllegalArgumentException("unknown line " + line);
            }
        }
        if (standing == null || sleeping == null || creative == null || types.isEmpty()) throw new IllegalArgumentException("incomplete entity geometry table");
        return new EntityGeometryTable(id, digest, types, poses, standing, sleeping, attributes, creative);
    }

    private static float f(String bits) { return Float.intBitsToFloat(Integer.parseInt(bits)); }
    private static Pickable pickable(String v) {
        if (v == null) return Pickable.DYNAMIC;
        return switch (v) { case "true" -> Pickable.TRUE; case "false" -> Pickable.FALSE; case "alive" -> Pickable.ALIVE; default -> Pickable.DYNAMIC; };
    }
}
