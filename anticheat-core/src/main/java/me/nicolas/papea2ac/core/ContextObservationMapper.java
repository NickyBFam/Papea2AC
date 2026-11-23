package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
/**
 * Phase 1 observations -> the player-owned Phase 8 {@link ContextEvent} vocabulary (the DAG-mandated translation, like
 * PlayerObservationMapper / TimingObservationMapper). Only the SELF entity's attributes and effects count; every other entity's
 * spawn/move/removal feeds the bounded neighbourhood tracker. Game mode comes from the join packet's game type and the CHANGE_GAME_MODE
 * game event (id 3; value 0 survival, 1 creative, 2 adventure, 3 spectator).
 */
public final class ContextObservationMapper {
    private ContextObservationMapper() {}
    /** Vanilla {@code ClientboundGameEventPacket.CHANGE_GAME_MODE}. */
    static final int GAME_EVENT_CHANGE_GAME_MODE = 3;

    /** The context events of one observation, in order (usually zero or one; an attribute packet yields one per attribute, all with the packet's sequence). */
    public static List<ContextEvent> map(Observation o, int selfEntityId) {
        long seq = o.sequence();
        if (o.event() instanceof Observation.Attempt a) {
            if (a.payload() instanceof SemanticPayload.FlightRequest f) return List.of(new ContextEvent.FlightToggled(seq, f.flying()));
            // P11-F27 (QA-10): the item use a spear charge needs, and client-side item moves that make the hand items unknown
            if (a.payload() instanceof SemanticPayload.UseItem u) return List.of(new ContextEvent.ItemUse(seq, true, u.hand() == SemanticPayload.Hand.OFF_HAND));
            if (a.payload() instanceof SemanticPayload.Dig d && d.action().equals("RELEASE_USE_ITEM")) return List.of(new ContextEvent.ItemUse(seq, false, false));
            if (a.payload() instanceof SemanticPayload.Dig d && d.action().equals("SWAP_ITEM_WITH_OFFHAND")) return List.of(new ContextEvent.HandItemsUnknown(seq));
            if (a.payload() instanceof SemanticPayload.ContainerClickClaim) return List.of(new ContextEvent.HandItemsUnknown(seq));
            return List.of();
        }
        if (!(o.event() instanceof Observation.Outbound out)) return List.of();
        return outbound(out.payload(), seq, selfEntityId);
    }
    private static List<ContextEvent> outbound(PacketPayload p, long seq, int selfEntityId) {
        // P9-F5: vanilla pairs every tracked entity (ServerEntity.addPairing) as ONE ClientboundBundlePacket - AddEntity, data, attributes,
        // equipment - so a spawn only ever arrives as a bundle child. Children are mapped in wire order under the bundle's sequence (the
        // normalizer rejects nesting); dropping them left the neighbourhood tracker blind to every entity the client was shown.
        if (p instanceof PacketPayload.Bundle bundle) {
            List<ContextEvent> events = new ArrayList<>();
            for (PacketPayload child : bundle.children()) events.addAll(outbound(child, seq, selfEntityId));
            return events;
        }
        if (p instanceof SemanticPayload.Attributes at) {
            if (selfEntityId < 0 || at.entityId() != selfEntityId) return List.of();
            List<ContextEvent> events = new ArrayList<>();
            for (SemanticPayload.Attribute v : at.values()) { List<ContextEvent.ModifierValue> mods = new ArrayList<>(); for (SemanticPayload.Modifier m : v.modifiers()) mods.add(new ContextEvent.ModifierValue(m.id(), m.amount(), m.operation())); events.add(new ContextEvent.AttributeUpdated(seq, v.id(), v.base(), mods)); }
            return events;
        }
        if (p instanceof SemanticPayload.Effect e) return selfEntityId >= 0 && e.entityId() == selfEntityId ? List.of(new ContextEvent.EffectAdded(seq, e.effect(), e.amplifier())) : List.of();
        if (p instanceof SemanticPayload.RemoveEffect e) return selfEntityId >= 0 && e.entityId() == selfEntityId ? List.of(new ContextEvent.EffectRemoved(seq, e.effect())) : List.of();
        if (p instanceof SemanticPayload.Abilities ab) return List.of(new ContextEvent.AbilitiesUpdated(seq, ab.flying(), ab.canFly()));
        if (p instanceof SemanticPayload.JoinGame j) return List.of(new ContextEvent.GameModeChanged(seq, j.gameType().toLowerCase(Locale.ROOT)));
        if (p instanceof SemanticPayload.GameEvent g && g.kind() == GAME_EVENT_CHANGE_GAME_MODE) return List.of(new ContextEvent.GameModeChanged(seq, mode((int) g.value())));
        if (p instanceof SemanticPayload.BorderInitialize b) return List.of(new ContextEvent.BorderInitialized(seq, b.x(), b.z(), b.oldSize(), b.newSize(), b.millis()));
        if (p instanceof SemanticPayload.BorderCenter b) return List.of(new ContextEvent.BorderCenter(seq, b.x(), b.z()));
        if (p instanceof SemanticPayload.BorderSize b) return List.of(new ContextEvent.BorderSize(seq, b.size()));
        if (p instanceof SemanticPayload.BorderLerp b) return List.of(new ContextEvent.BorderLerp(seq, b.oldSize(), b.newSize(), b.millis()));
        if (p instanceof SemanticPayload.EntitySpawn s) return selfEntityId >= 0 && s.entityId() == selfEntityId ? List.of() : List.of(new ContextEvent.EntitySpawned(seq, s.entityId(), s.type(), s.position().x(), s.position().y(), s.position().z()));
        // P11-F2 split position sync from teleport at the transport; P11-F3: a teleport leaves the client's delta-codec base behind (and resolves relative
        // components against the interpolated position), so it makes the entity uncertain instead of moving it to a possibly-relative coordinate
        if (p instanceof SemanticPayload.EntityPosition ep) return List.of(new ContextEvent.EntityTeleported(seq, ep.entityId()));
        // P11-F9: a pre-schema-3 capture cannot tell a sync from an absolute teleport; both put the entity at the packet position (the pre-Phase-11 reading)
        if (p instanceof SemanticPayload.LegacyEntityPosition lp) return List.of(new ContextEvent.EntityMovedTo(seq, lp.entityId(), lp.position().x(), lp.position().y(), lp.position().z()));
        if (p instanceof SemanticPayload.EntityPositionSync ps) return List.of(new ContextEvent.EntityMovedTo(seq, ps.entityId(), ps.position().x(), ps.position().y(), ps.position().z()));
        if (p instanceof SemanticPayload.EntityDelta d) return d.hasPosition() ? List.of(new ContextEvent.EntityMovedBy(seq, d.entityId(), d.dx(), d.dy(), d.dz())) : List.of();
        if (p instanceof SemanticPayload.RemoveEntities r) return List.of(new ContextEvent.EntitiesRemoved(seq, r.ids()));
        // P11-F27 (QA-10): the hotbar and off-hand items as the server sent them (player inventory menu 0: hotbar 36-44, off hand 45; inventory
        // indices for the direct-slot packets: hotbar 0-8, off hand 40). Another window's slot or content may move them: unknown until re-sent.
        if (p instanceof SemanticPayload.ContainerContent c) {
            if (c.containerId() != 0 || c.items().size() < INVENTORY_MENU_OFF_HAND + 1) return List.of(new ContextEvent.HandItemsUnknown(seq));
            List<ContextEvent> events = new ArrayList<>(ContextEvent.HandItem.OFF_HAND + 1);
            for (int i = 0; i < ContextEvent.HandItem.OFF_HAND; i++) events.add(hand(seq, i, c.items().get(INVENTORY_MENU_HOTBAR + i)));
            events.add(hand(seq, ContextEvent.HandItem.OFF_HAND, c.items().get(INVENTORY_MENU_OFF_HAND)));
            return events;
        }
        if (p instanceof SemanticPayload.ContainerSlot c) {
            if (c.containerId() == 0) { int h = c.slot() == INVENTORY_MENU_OFF_HAND ? ContextEvent.HandItem.OFF_HAND : c.slot() >= INVENTORY_MENU_HOTBAR && c.slot() < INVENTORY_MENU_HOTBAR + 9 ? c.slot() - INVENTORY_MENU_HOTBAR : -1; return h < 0 ? List.of() : List.of(hand(seq, h, c.item())); }
            if (c.containerId() == PLAYER_INVENTORY_CONTAINER) { int h = inventoryHand(c.slot()); return h < 0 ? List.of() : List.of(hand(seq, h, c.item())); }
            return List.of(new ContextEvent.HandItemsUnknown(seq));
        }
        if (p instanceof SemanticPayload.PlayerInventorySlot s) { int h = inventoryHand(s.slot()); return h < 0 ? List.of() : List.of(hand(seq, h, s.item())); }
        return List.of();
    }
    /** {@code InventoryMenu}: hotbar slots 36-44, off hand 45. */
    static final int INVENTORY_MENU_HOTBAR = 36, INVENTORY_MENU_OFF_HAND = 45;
    /** {@code ClientboundContainerSetSlotPacket} container id addressing the player inventory by inventory index. */
    static final int PLAYER_INVENTORY_CONTAINER = -2;
    /** Inventory index to hand-item slot: hotbar 0-8, off hand 40; -1 otherwise. */
    static int inventoryHand(int slot) { return slot >= 0 && slot < 9 ? slot : slot == 40 ? ContextEvent.HandItem.OFF_HAND : -1; }
    /**
     * P11-F27: a kinetic weapon is an item with the {@code minecraft:kinetic_weapon} component (the normalizer lists every component of the stack,
     * prototype included; the vanilla spears carry it) or a {@code *_spear}; a knockback source is a knockback enchantment or attribute modifiers
     * (whose contents are not captured, so any modifier set counts).
     */
    static ContextEvent.HandItem hand(long seq, int slot, SemanticPayload.Item item) {
        if (item.empty()) return new ContextEvent.HandItem(seq, slot, false, false);
        boolean kinetic = item.otherComponents().contains("minecraft:kinetic_weapon") || item.type().endsWith("_spear");
        boolean knockback = item.otherComponents().contains("minecraft:attribute_modifiers") || item.enchantments().stream().anyMatch(e -> e.id().equals("minecraft:knockback") && e.level() > 0);
        return new ContextEvent.HandItem(seq, slot, kinetic, knockback);
    }
    static String mode(int id) { return switch (id) { case 0 -> "survival"; case 1 -> "creative"; case 2 -> "adventure"; case 3 -> "spectator"; default -> "unknown"; }; }
}
