package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * Phase 8 per-session movement-context ledger (PLAYER_STATE.md S1: actor-owned components, immutable projections): the
 * client-visible physics context, kept exactly as the client learned it - attributes (defaults until synced), effects (only what
 * was added and not removed), abilities / flight / game mode, the world border, and a bounded tracker of the OTHER entities
 * the client was shown that touch the local player's physics (players push, boats/rafts/shulkers collide; P8-F3: everything else
 * - items, animals, projectiles, hundreds of them in an ordinary world - is not tracked at all, so it can neither overflow the
 * bound nor make the neighbourhood UNKNOWN). Owner-thread {@link #apply}; {@link #view()} is an immutable published projection,
 * re-published only when the projection changes.
 *
 * <p>Bounds: {@value #MAX_ENTITIES} tracked relevant entities (overflow makes the neighbourhood UNKNOWN until the overflow clears),
 * {@value #MAX_EFFECTS} effects, {@value #MAX_ATTRIBUTES} attributes. A loss-class coverage break makes every dimension UNKNOWN
 * until a world change re-baselines it (the server re-sends border/effects/attributes on respawn); a world change clears the
 * entities. A reconnect gets a new ledger with a new SessionKey.
 */
public final class MovementContextLedger {
    public static final int MAX_ENTITIES = 1024, MAX_EFFECTS = 32, MAX_ATTRIBUTES = 32;
    /** Entity classes that touch the local player's client-side physics: players push, boats/shulkers collide; the rest is irrelevant. */
    public enum EntityClass { PLAYER, HARD_COLLIDER, OTHER }
    public record TrackedEntity(int entityId, EntityClass entityClass, double x, double y, double z) {}
    public record AttributeValue(double base, List<ContextEvent.ModifierValue> modifiers) { public AttributeValue { modifiers = List.copyOf(modifiers); } }
    public record Border(boolean known, double minX, double minZ, double maxX, double maxZ, boolean moving) {
        public static final Border UNKNOWN = new Border(false, 0, 0, 0, 0, false);
        static Border of(double cx, double cz, double size, boolean moving) { double h = size / 2.0; return new Border(true, cx - h, cz - h, cx + h, cz + h, moving); }
    }
    /** Immutable projection. {@code contextComplete} = no loss-class break since the last (re)baseline; {@code entitiesComplete} = tracker not overflowed and no break. */
    public record View(SessionKey key, long lastSequence, boolean contextComplete, boolean entitiesComplete, Map<String, AttributeValue> attributes, Map<String, Integer> effects, boolean flying, boolean canFly, String gameMode, Border border,
                       List<TrackedEntity> relevantEntities, int trackedEntities, long worldChanges, long breaks) {
        public View { attributes = Map.copyOf(attributes); effects = Map.copyOf(effects); relevantEntities = List.copyOf(relevantEntities); Objects.requireNonNull(gameMode); Objects.requireNonNull(border); }
        /** Whether a player or hard collider is within the given horizontal / vertical distance of a position (entity positions are the client's interpolated targets: a conservative radius is the caller's business). */
        public boolean anyRelevantEntityNear(double x, double y, double z, double horizontal, double vertical) {
            for (TrackedEntity e : relevantEntities) { double dx = e.x() - x, dz = e.z() - z; if (dx * dx + dz * dz <= horizontal * horizontal && Math.abs(e.y() - y) <= vertical) return true; }
            return false;
        }
        /** Locomotion the model supports: the game mode is known (join or a mode change was observed), not flying (and not able to start flying unobserved), survival/adventure-like modes. */
        public boolean locomotionSupported() { return !flying && !canFly && (gameMode.equals("survival") || gameMode.equals("adventure")); }
    }

    private final SessionKey key;
    private final Map<String, AttributeValue> attributes = new LinkedHashMap<>();
    private final Map<String, Integer> effects = new LinkedHashMap<>();
    private final Map<Integer, TrackedEntity> entities = new LinkedHashMap<>();
    private boolean flying, canFly, contextComplete = true, entitiesComplete = true, broken;
    private String gameMode = "unknown";
    private Border border = Border.UNKNOWN;
    private long lastSequence, worldChanges, breaks;
    private Thread owner;
    private volatile View view;

    public MovementContextLedger(SessionKey key) { this.key = Objects.requireNonNull(key); publish(); }
    private void assertOwner() { if (owner == null) owner = Thread.currentThread(); else if (owner != Thread.currentThread()) throw new IllegalStateException("context ledger ownership"); }

    /** Applies one event in capture order (non-decreasing sequence: one observation may yield several events); a stale-session or out-of-order event is a no-op. */
    public boolean apply(SessionKey observedFor, ContextEvent e) {
        assertOwner(); Objects.requireNonNull(e);
        if (!key.equals(observedFor) || e.sequence() < lastSequence) return false;
        lastSequence = e.sequence();
        boolean changed = true;
        switch (e) {
            case ContextEvent.AttributeUpdated a -> { if (attributes.size() < MAX_ATTRIBUTES || attributes.containsKey(a.id())) attributes.put(a.id(), new AttributeValue(a.base(), a.modifiers())); else contextComplete = false; }
            case ContextEvent.EffectAdded a -> { if (effects.size() < MAX_EFFECTS || effects.containsKey(a.id())) effects.put(a.id(), a.amplifier()); else contextComplete = false; }
            case ContextEvent.EffectRemoved r -> effects.remove(r.id());
            case ContextEvent.AbilitiesUpdated a -> { flying = a.flying(); canFly = a.canFly(); }
            case ContextEvent.FlightToggled f -> flying = f.flying();
            case ContextEvent.GameModeChanged g -> gameMode = g.mode();
            case ContextEvent.BorderInitialized b -> border = Border.of(b.centerX(), b.centerZ(), b.newSize(), b.lerpMillis() > 0 && b.oldSize() != b.newSize());
            case ContextEvent.BorderCenter c -> border = border.known() ? Border.of(c.centerX(), c.centerZ(), border.maxX() - border.minX(), border.moving()) : Border.UNKNOWN;
            case ContextEvent.BorderSize s -> border = border.known() ? Border.of((border.minX() + border.maxX()) / 2.0, (border.minZ() + border.maxZ()) / 2.0, s.size(), false) : Border.UNKNOWN;
            case ContextEvent.BorderLerp l -> border = border.known() ? Border.of((border.minX() + border.maxX()) / 2.0, (border.minZ() + border.maxZ()) / 2.0, l.newSize(), l.lerpMillis() > 0 && l.oldSize() != l.newSize()) : Border.UNKNOWN;
            case ContextEvent.EntitySpawned s -> {
                EntityClass c = classify(s.type());
                if (c == EntityClass.OTHER) changed = false; // irrelevant to the local player's physics: not tracked (P8-F3)
                else if (entities.size() >= MAX_ENTITIES && !entities.containsKey(s.entityId())) entitiesComplete = false;
                else entities.put(s.entityId(), new TrackedEntity(s.entityId(), c, s.x(), s.y(), s.z()));
            }
            case ContextEvent.EntityMovedTo m -> { TrackedEntity t = entities.get(m.entityId()); if (t != null) entities.put(m.entityId(), new TrackedEntity(t.entityId(), t.entityClass(), m.x(), m.y(), m.z())); else changed = false; }
            case ContextEvent.EntityMovedBy m -> { TrackedEntity t = entities.get(m.entityId()); if (t != null) entities.put(m.entityId(), new TrackedEntity(t.entityId(), t.entityClass(), t.x() + m.dx() / 4096.0, t.y() + m.dy() / 4096.0, t.z() + m.dz() / 4096.0)); else changed = false; }
            case ContextEvent.EntitiesRemoved r -> { changed = false; for (int id : r.entityIds()) if (entities.remove(id) != null) changed = true; if (entities.size() < MAX_ENTITIES / 2 && entitiesComplete != !broken) { entitiesComplete = !broken; changed = true; } }
            case ContextEvent.WorldChanged w -> { entities.clear(); worldChanges++; broken = false; contextComplete = true; entitiesComplete = true; }
            case ContextEvent.CoverageBreak b -> { breaks++; broken = true; contextComplete = false; entitiesComplete = false; }
        }
        if (changed) publish();
        return true;
    }
    static EntityClass classify(String type) {
        if (type.equals("minecraft:player")) return EntityClass.PLAYER;
        if (type.equals("minecraft:shulker") || type.endsWith("_boat") || type.endsWith("_raft")) return EntityClass.HARD_COLLIDER;
        return EntityClass.OTHER;
    }
    public View view() { return view; }
    public SessionKey key() { return key; }
    private void publish() {
        List<TrackedEntity> relevant = new ArrayList<>(); for (TrackedEntity t : entities.values()) if (t.entityClass() != EntityClass.OTHER) relevant.add(t);
        relevant.sort((a, b) -> Integer.compare(a.entityId(), b.entityId()));
        view = new View(key, lastSequence, contextComplete, entitiesComplete, attributes, effects, flying, canFly, gameMode, border, relevant, entities.size(), worldChanges, breaks);
    }
}
