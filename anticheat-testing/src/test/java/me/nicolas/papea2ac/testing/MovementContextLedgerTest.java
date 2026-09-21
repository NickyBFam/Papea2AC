package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.ContextObservationMapper;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;
/**
 * The player-owned Phase 8 movement context: ordering and session filtering, attribute/effect bounds, abilities / game mode and the
 * locomotion-support rule, the border lifecycle, the entity tracker (P8-F3: only players and hard colliders are tracked, so an ordinary
 * world's hundreds of items and animals can neither overflow it nor make the neighbourhood UNKNOWN), coverage breaks and world changes,
 * and the observation mapper that feeds it.
 */
class MovementContextLedgerTest {
    static final SessionKey KEY = new SessionKey(new UUID(8, 3), 1);
    @Test void orderingSessionAndBoundsAreEnforced() {
        MovementContextLedger l = new MovementContextLedger(KEY);
        assertTrue(l.apply(KEY, new ContextEvent.GameModeChanged(5, "survival")));
        assertFalse(l.apply(KEY, new ContextEvent.GameModeChanged(4, "creative")), "an out-of-order event is a no-op"); assertEquals("survival", l.view().gameMode());
        assertFalse(l.apply(new SessionKey(new UUID(8, 3), 2), new ContextEvent.GameModeChanged(6, "creative")), "another session's event is a no-op");
        assertTrue(l.apply(KEY, new ContextEvent.GameModeChanged(5, "adventure")), "an equal sequence (several events from one observation) is accepted");
        long seq = 10;
        for (int i = 0; i < MovementContextLedger.MAX_ATTRIBUTES; i++) l.apply(KEY, new ContextEvent.AttributeUpdated(seq++, "minecraft:a" + i, 0.1, List.of()));
        assertTrue(l.view().contextComplete());
        l.apply(KEY, new ContextEvent.AttributeUpdated(seq++, "minecraft:overflow", 0.1, List.of()));
        assertFalse(l.view().contextComplete(), "an attribute beyond the bound makes the context UNKNOWN, never silently dropped");
        MovementContextLedger m = new MovementContextLedger(KEY); seq = 1;
        for (int i = 0; i < MovementContextLedger.MAX_EFFECTS; i++) m.apply(KEY, new ContextEvent.EffectAdded(seq++, "minecraft:e" + i, 0));
        assertTrue(m.view().contextComplete()); m.apply(KEY, new ContextEvent.EffectAdded(seq++, "minecraft:e_more", 0)); assertFalse(m.view().contextComplete());
        m.apply(KEY, new ContextEvent.EffectRemoved(seq++, "minecraft:e0")); assertEquals(MovementContextLedger.MAX_EFFECTS - 1, m.view().effects().size());
    }
    @Test void locomotionSupportFollowsAbilitiesAndGameMode() {
        MovementContextLedger l = new MovementContextLedger(KEY);
        assertFalse(l.view().locomotionSupported(), "unknown game mode: the runner refuses until the mode is known");
        l.apply(KEY, new ContextEvent.GameModeChanged(1, "survival")); assertTrue(l.view().locomotionSupported());
        l.apply(KEY, new ContextEvent.AbilitiesUpdated(2, false, true)); assertFalse(l.view().locomotionSupported(), "able to fly (unobserved take-off): unsupported");
        l.apply(KEY, new ContextEvent.AbilitiesUpdated(3, false, false)); assertTrue(l.view().locomotionSupported());
        l.apply(KEY, new ContextEvent.FlightToggled(4, true)); assertFalse(l.view().locomotionSupported());
        l.apply(KEY, new ContextEvent.FlightToggled(5, false)); l.apply(KEY, new ContextEvent.GameModeChanged(6, "creative")); assertFalse(l.view().locomotionSupported());
        l.apply(KEY, new ContextEvent.GameModeChanged(7, "spectator")); assertFalse(l.view().locomotionSupported());
        l.apply(KEY, new ContextEvent.GameModeChanged(8, "adventure")); assertTrue(l.view().locomotionSupported());
    }
    @Test void borderLifecycle() {
        MovementContextLedger l = new MovementContextLedger(KEY);
        assertFalse(l.view().border().known());
        l.apply(KEY, new ContextEvent.BorderInitialized(1, 10, -10, 100, 100, 0)); var b = l.view().border();
        assertTrue(b.known()); assertFalse(b.moving()); assertEquals(-40, b.minX()); assertEquals(60, b.maxX()); assertEquals(-60, b.minZ()); assertEquals(40, b.maxZ());
        l.apply(KEY, new ContextEvent.BorderLerp(2, 100, 50, 10_000)); assertTrue(l.view().border().moving(), "a lerp in progress: the border is UNKNOWN for physics");
        l.apply(KEY, new ContextEvent.BorderSize(3, 50)); assertFalse(l.view().border().moving()); assertEquals(-15, l.view().border().minX());
        l.apply(KEY, new ContextEvent.BorderCenter(4, 0, 0)); assertEquals(-25, l.view().border().minX()); assertEquals(25, l.view().border().maxZ());
    }
    @Test void onlyPhysicsRelevantEntitiesAreTrackedAndTheBoundIsHonest() {
        MovementContextLedger l = new MovementContextLedger(KEY); long seq = 1;
        for (int i = 0; i < 5000; i++) l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 100 + i, i % 2 == 0 ? "minecraft:item" : "minecraft:cow", i, 64, 0)); // P8-F3: an ordinary world
        assertTrue(l.view().entitiesComplete()); assertEquals(0, l.view().trackedEntities()); assertTrue(l.view().relevantEntities().isEmpty());
        l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 7, "minecraft:player", 3, 64, 0)); l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 8, "minecraft:oak_boat", 9, 64, 0)); l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 9, "minecraft:shulker", 20, 64, 0)); l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 10, "minecraft:bamboo_raft", 30, 64, 0));
        assertEquals(4, l.view().trackedEntities()); assertTrue(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4)); assertFalse(l.view().anyRelevantEntityNear(0, 70, 0, 4, 4)); assertFalse(l.view().anyRelevantEntityNear(15, 64, 0, 4, 4));
        l.apply(KEY, new ContextEvent.EntityMovedBy(seq++, 7, (short) 4096, (short) 0, (short) 0)); assertFalse(l.view().anyRelevantEntityNear(0, 64, 0, 3.5, 4)); assertTrue(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4));
        l.apply(KEY, new ContextEvent.EntityMovedTo(seq++, 7, 50, 64, 50)); assertFalse(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4));
        l.apply(KEY, new ContextEvent.EntityMovedTo(seq++, 100, 0, 64, 0)); assertFalse(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4), "an untracked entity's move is nothing");
        l.apply(KEY, new ContextEvent.EntitiesRemoved(seq++, List.of(7, 8, 100))); assertEquals(2, l.view().trackedEntities());
        for (int i = 0; i < MovementContextLedger.MAX_ENTITIES; i++) l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 10_000 + i, "minecraft:player", 1000, 64, 1000));
        assertFalse(l.view().entitiesComplete(), "more relevant entities than the bound: UNKNOWN, never dropped silently"); assertEquals(MovementContextLedger.MAX_ENTITIES, l.view().trackedEntities());
        List<Integer> ids = new ArrayList<>(); for (int i = 0; i < MovementContextLedger.MAX_ENTITIES; i++) ids.add(10_000 + i); l.apply(KEY, new ContextEvent.EntitiesRemoved(seq++, ids));
        assertTrue(l.view().entitiesComplete(), "the overflow cleared");
        l.apply(KEY, new ContextEvent.CoverageBreak(seq++, "OVERLOADED")); assertFalse(l.view().entitiesComplete()); assertFalse(l.view().contextComplete());
        l.apply(KEY, new ContextEvent.EntitiesRemoved(seq++, List.of(9))); assertFalse(l.view().entitiesComplete(), "a break is not cleared by removals");
        l.apply(KEY, new ContextEvent.WorldChanged(seq++, 1)); assertTrue(l.view().entitiesComplete()); assertTrue(l.view().contextComplete()); assertEquals(0, l.view().trackedEntities()); assertEquals(1, l.view().worldChanges());
    }
    static Observation ob(long seq, PacketPayload p) { return new Observation(KEY, seq, seq, 0, new Observation.Outbound(seq, p)); }
    @Test void theObservationMapperFeedsTheLedgerFromTheCapturedPackets() {
        int self = 42;
        var join = ContextObservationMapper.map(ob(1, new SemanticPayload.JoinGame(self, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false, -64, 384)), self);
        assertTrue(join.stream().anyMatch(e -> e instanceof ContextEvent.GameModeChanged g && g.mode().equals("survival")), join.toString());
        var mode = ContextObservationMapper.map(ob(2, new SemanticPayload.GameEvent(3, 1f)), self);
        assertTrue(mode.stream().anyMatch(e -> e instanceof ContextEvent.GameModeChanged g && g.mode().equals("creative")), mode.toString());
        var ab = ContextObservationMapper.map(ob(3, new SemanticPayload.Abilities(false, true, true, false, 0.05f, 0.1f)), self);
        assertTrue(ab.stream().anyMatch(e -> e instanceof ContextEvent.AbilitiesUpdated a && a.flying() && a.canFly()), ab.toString());
        var other = ContextObservationMapper.map(ob(4, new SemanticPayload.Attributes(self + 1, List.of())), self);
        assertTrue(other.isEmpty(), "another entity's attributes are not this player's context");
        var spawn = ContextObservationMapper.map(ob(5, new SemanticPayload.EntitySpawn(77, UUID.randomUUID(), "minecraft:player", new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), 0f, 0)), self);
        assertTrue(spawn.stream().anyMatch(e -> e instanceof ContextEvent.EntitySpawned s && s.entityId() == 77 && s.type().equals("minecraft:player")), spawn.toString());
        var selfSpawn = ContextObservationMapper.map(ob(6, new SemanticPayload.EntitySpawn(self, UUID.randomUUID(), "minecraft:player", new PacketPayload.Position(1, 2, 3), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), 0f, 0)), self);
        assertTrue(selfSpawn.isEmpty(), "the player's own entity is never a neighbour");
        var border = ContextObservationMapper.map(ob(7, new SemanticPayload.BorderInitialize(0, 0, 100, 100, 0, 29999984, 5, 15)), self);
        assertTrue(border.stream().anyMatch(e -> e instanceof ContextEvent.BorderInitialized), border.toString());
        var removed = ContextObservationMapper.map(ob(8, new SemanticPayload.RemoveEntities(List.of(77, 78))), self);
        assertTrue(removed.stream().anyMatch(e -> e instanceof ContextEvent.EntitiesRemoved r && r.entityIds().equals(List.of(77, 78))), removed.toString());
    }
}
