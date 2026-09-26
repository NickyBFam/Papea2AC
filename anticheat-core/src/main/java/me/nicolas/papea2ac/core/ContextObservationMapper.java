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
        if (p instanceof SemanticPayload.EntityPosition ep) return List.of(new ContextEvent.EntityMovedTo(seq, ep.entityId(), ep.position().x(), ep.position().y(), ep.position().z()));
        if (p instanceof SemanticPayload.EntityDelta d) return d.hasPosition() ? List.of(new ContextEvent.EntityMovedBy(seq, d.entityId(), d.dx(), d.dy(), d.dz())) : List.of();
        if (p instanceof SemanticPayload.RemoveEntities r) return List.of(new ContextEvent.EntitiesRemoved(seq, r.ids()));
        return List.of();
    }
    static String mode(int id) { return switch (id) { case 0 -> "survival"; case 1 -> "creative"; case 2 -> "adventure"; case 3 -> "spectator"; default -> "unknown"; }; }
}
