package me.nicolas.papea2ac.player;
import java.util.List;
import java.util.Objects;
/**
 * Phase 8 movement-context vocabulary (player-owned, protocol-free): the client-visible facts that decide which physics
 * context a transition is simulated in - the self entity's attributes and effects as the CLIENT received them, its abilities /
 * game mode (flight capability), the world border, and the observer's view of nearby entities (pushers / hard colliders). Core
 * maps Phase 1 observations into these; {@link MovementContextLedger} keeps them per session. Everything the client was never
 * sent is, by construction, at the client's own default - exactly what the client simulates with.
 */
public sealed interface ContextEvent permits ContextEvent.AttributeUpdated, ContextEvent.EffectAdded, ContextEvent.EffectRemoved, ContextEvent.AbilitiesUpdated, ContextEvent.FlightToggled, ContextEvent.GameModeChanged,
        ContextEvent.BorderInitialized, ContextEvent.BorderCenter, ContextEvent.BorderSize, ContextEvent.BorderLerp, ContextEvent.EntitySpawned, ContextEvent.EntityMovedTo, ContextEvent.EntityMovedBy, ContextEvent.EntitiesRemoved, ContextEvent.WorldChanged, ContextEvent.CoverageBreak {
    long sequence();
    /** One attribute modifier as the packet carries it (operation as the protocol names it: ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL). */
    record ModifierValue(String id, double amount, String operation) { public ModifierValue { Objects.requireNonNull(id); Objects.requireNonNull(operation); if (!Double.isFinite(amount)) throw new IllegalArgumentException("modifier amount"); } }
    /** The self entity's attribute instance as synced to the client (base + modifiers, replacing the previous instance). */
    record AttributeUpdated(long sequence, String id, double base, List<ModifierValue> modifiers) implements ContextEvent { public AttributeUpdated { Objects.requireNonNull(id); modifiers = List.copyOf(modifiers); if (!Double.isFinite(base)) throw new IllegalArgumentException("attribute base"); if (modifiers.size() > 32) throw new IllegalArgumentException("modifiers"); } }
    record EffectAdded(long sequence, String id, int amplifier) implements ContextEvent { public EffectAdded { Objects.requireNonNull(id); } }
    record EffectRemoved(long sequence, String id) implements ContextEvent { public EffectRemoved { Objects.requireNonNull(id); } }
    /** Server-sent abilities (the client adopts them). */
    record AbilitiesUpdated(long sequence, boolean flying, boolean canFly) implements ContextEvent {}
    /** The client's own flight toggle claim (double-tap jump): the client is flying as of this packet - observed, untrusted, and enough to make the locomotion unsupported. */
    record FlightToggled(long sequence, boolean flying) implements ContextEvent {}
    record GameModeChanged(long sequence, String mode) implements ContextEvent { public GameModeChanged { Objects.requireNonNull(mode); } }
    record BorderInitialized(long sequence, double centerX, double centerZ, double oldSize, double newSize, long lerpMillis) implements ContextEvent {}
    record BorderCenter(long sequence, double centerX, double centerZ) implements ContextEvent {}
    record BorderSize(long sequence, double size) implements ContextEvent {}
    record BorderLerp(long sequence, double oldSize, double newSize, long lerpMillis) implements ContextEvent {}
    /** Another entity became visible: its protocol type id and spawn position. */
    record EntitySpawned(long sequence, int entityId, String type, double x, double y, double z) implements ContextEvent { public EntitySpawned { Objects.requireNonNull(type); } }
    record EntityMovedTo(long sequence, int entityId, double x, double y, double z) implements ContextEvent {}
    /** A relative move in 1/4096 block units as the protocol encodes it. */
    record EntityMovedBy(long sequence, int entityId, short dx, short dy, short dz) implements ContextEvent {}
    record EntitiesRemoved(long sequence, List<Integer> entityIds) implements ContextEvent { public EntitiesRemoved { entityIds = List.copyOf(entityIds); } }
    /** A respawn / dimension change: every tracked entity of the old world is gone; the border and effects are re-sent by the server. */
    record WorldChanged(long sequence, long newWorldEpoch) implements ContextEvent {}
    /** A loss-class transport break: observations may have been lost, the tracked context is no longer complete until re-anchored. */
    record CoverageBreak(long sequence, String reason) implements ContextEvent { public CoverageBreak { Objects.requireNonNull(reason); } }
}
