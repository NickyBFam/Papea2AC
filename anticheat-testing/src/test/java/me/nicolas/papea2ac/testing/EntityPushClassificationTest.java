package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.player.MovementContextLedger;
import me.nicolas.papea2ac.player.MovementContextLedger.EntityClass;
import org.junit.jupiter.api.Test;

/**
 * P9-F5 regression (found by the Phase 9 VPS qualification with a second, remote player; capture 7; originating component: P8-F3).
 *
 * <p>The live failure: a legitimate airborne player moved 0.0126 further east in one tick than the model's largest legal air impulse
 * (0.0255) allowed - height exact, previous velocity pinned by four exact position matches, no server influence, no disputed block.
 * The 26.2 client's own pushing code explains it: {@code ClientLevel.getPushableEntities(pusher, box)} returns the LOCAL player for
 * every other entity whose box intersects it, so a rabbit's {@code aiStep -> pushEntities} shoves the local player
 * ({@code Entity.push}: up to 0.05 * sqrt(overlap) per tick). P8-F3 had classified animals and mobs as irrelevant (untracked), on the
 * premise that the client pushes only players; the chunk still holds a rabbit a few blocks from the failure point.
 *
 * <p>The fix classifies every entity type that pushes the local player as a PUSHER, so one near the player makes the entity
 * neighbourhood UNKNOWN (the client's interpolated position of a remote entity is not reproducible by the server). The table below
 * is pinned against the vanilla 26.2 jar: {@code docs/phase9-artifacts/entity-push-classes-26.2.json} was derived from the
 * {@code EntityTypes} field signatures, each type's class hierarchy, and the {@code pushEntities}/{@code doPush} overrides.
 */
class EntityPushClassificationTest {
    static final SessionKey KEY = new SessionKey(UUID.nameUUIDFromBytes("p9f5".getBytes()), 1);

    static Map<String, String> pinned() throws Exception {
        String json = Files.readString(TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase9-artifacts/entity-push-classes-26.2.json"));
        Matcher m = Pattern.compile("\"id\": \"([a-z0-9_:]+)\",.*?\"expected\": \"([A-Z_]+)\"", Pattern.DOTALL).matcher(json);
        Map<String, String> out = new LinkedHashMap<>(); while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    @Test void everyVanilla262EntityTypeIsClassifiedExactlyAsTheJarPushes() throws Exception {
        Map<String, String> table = pinned();
        assertEquals(158, table.size(), "the pinned 26.2 registry has 158 entity types");
        List<String> wrong = new ArrayList<>();
        for (var e : table.entrySet()) if (MovementContextLedger.classify(e.getKey()) != EntityClass.valueOf(e.getValue())) wrong.add(e.getKey() + " expected " + e.getValue() + " got " + MovementContextLedger.classify(e.getKey()));
        assertEquals(List.of(), wrong);
        assertEquals(new TreeSet<>(MovementContextLedger.NON_PUSHERS), new TreeSet<>(table.entrySet().stream().filter(e -> e.getValue().equals("OTHER")).map(Map.Entry::getKey).toList()),
                "NON_PUSHERS is exactly the jar's non-pushing set: nothing living that pushes can hide in it");
    }

    @Test void theLivePushersAndTheNonPushers() {
        for (String t : List.of("minecraft:rabbit", "minecraft:cow", "minecraft:sheep", "minecraft:zombie", "minecraft:villager", "minecraft:minecart", "minecraft:mannequin"))
            assertEquals(EntityClass.PUSHER, MovementContextLedger.classify(t), t);
        for (String t : List.of("minecraft:item", "minecraft:arrow", "minecraft:experience_orb", "minecraft:armor_stand", "minecraft:bat", "minecraft:parrot", "minecraft:text_display"))
            assertEquals(EntityClass.OTHER, MovementContextLedger.classify(t), t);
        assertEquals(EntityClass.PUSHER, MovementContextLedger.classify("minecraft:some_future_mob"), "an unknown type costs precision, never soundness");
        assertEquals(EntityClass.PLAYER, MovementContextLedger.classify("minecraft:player"));
        assertEquals(EntityClass.HARD_COLLIDER, MovementContextLedger.classify("minecraft:oak_boat"));
    }

    @Test void aRabbitNextToThePlayerMakesTheNeighbourhoodRelevantAndAnItemDoesNot() {
        MovementContextLedger l = new MovementContextLedger(KEY); long seq = 1;
        l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 10, "minecraft:item", 0.2, 64, 0));
        assertEquals(0, l.view().trackedEntities()); assertFalse(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4));
        l.apply(KEY, new ContextEvent.EntitySpawned(seq++, 11, "minecraft:rabbit", -6.5, 64, -2.5));
        assertEquals(1, l.view().trackedEntities()); assertFalse(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4), "a rabbit 7 blocks away pushes nobody");
        l.apply(KEY, new ContextEvent.EntityMovedBy(seq++, 11, (short) (4096 * 6), (short) 0, (short) (4096 * 2)));
        assertTrue(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4), "the hopping rabbit is now next to the player: its client-side push is unknowable");
        l.apply(KEY, new ContextEvent.EntitiesRemoved(seq++, List.of(11))); assertFalse(l.view().anyRelevantEntityNear(0, 64, 0, 4, 4));
    }
}
