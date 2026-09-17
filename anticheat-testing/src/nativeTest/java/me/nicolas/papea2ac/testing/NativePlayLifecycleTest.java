package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Simple PLAY-state identity/lifecycle marker packets: no chunk, item or entity-metadata content. */
class NativePlayLifecycleTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    @Test void containerCloseIsSharedBetweenBothDirections()throws Exception{
        assertEquals(new ContainerClose(4),check(create(GAME+"ClientboundContainerClosePacket",new Class<?>[]{int.class},4)));
        assertEquals(new ContainerClose(4),check(create(GAME+"ServerboundContainerClosePacket",new Class<?>[]{int.class},4)));
    }
    @Test void cooldownCarriesGroupIdentityAndDuration()throws Exception{
        Object group=type("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath",String.class,String.class).invoke(null,"minecraft","ender_pearl");
        assertEquals(new Cooldown("minecraft:ender_pearl",100),check(create(GAME+"ClientboundCooldownPacket",new Class<?>[]{group.getClass(),int.class},group,100)));
    }
    @Test void projectilePowerPreservesEntityIdentityAndAcceleration()throws Exception{
        assertEquals(new ProjectilePower(9,1.25),check(create(GAME+"ClientboundProjectilePowerPacket",new Class<?>[]{int.class,double.class},9,1.25)));
    }
    @Test void configurationRoundTripMarkersAreDistinctFromInitialFinish()throws Exception{
        assertEquals(new Lifecycle(LifecycleTransition.PLAY_TO_CONFIGURATION),check(value(GAME+"ClientboundStartConfigurationPacket","INSTANCE")));
        assertEquals(new Lifecycle(LifecycleTransition.PLAY_TO_CONFIGURATION_ACK),check(value(GAME+"ServerboundConfigurationAcknowledgedPacket","INSTANCE")));
        assertEquals(new Lifecycle(LifecycleTransition.PLAYER_LOADED),check(create(GAME+"ServerboundPlayerLoadedPacket",new Class<?>[]{},new Object[]{})));
    }
    @Test void gameModeAndClientCommandPreserveTheRequestedEnum()throws Exception{
        var mode=(GameModeChange)check(create(GAME+"ServerboundChangeGameModePacket",new Class<?>[]{type("net.minecraft.world.level.GameType")},value("net.minecraft.world.level.GameType","SPECTATOR")));
        assertEquals("SPECTATOR",mode.mode());
        var action=(ClientCommand)check(create(GAME+"ServerboundClientCommandPacket",new Class<?>[]{type(GAME+"ServerboundClientCommandPacket$Action")},value(GAME+"ServerboundClientCommandPacket$Action","PERFORM_RESPAWN")));
        assertEquals("PERFORM_RESPAWN",action.action());
    }
    @Test void chunkBatchAckPreservesRequestedRate()throws Exception{
        var ack=(ChunkBatchAck)check(create(GAME+"ServerboundChunkBatchReceivedPacket",new Class<?>[]{float.class},6.5f));
        assertEquals(6.5f,ack.desiredChunksPerTick());
    }
    @Test void chunkCacheAndSimulationDistanceCarryOnlyClientRenderBookkeeping()throws Exception{
        assertEquals(new ChunkCacheCenter(4,-7),check(create(GAME+"ClientboundSetChunkCacheCenterPacket",new Class<?>[]{int.class,int.class},4,-7)));
        assertEquals(new ChunkCacheRadius(12),check(create(GAME+"ClientboundSetChunkCacheRadiusPacket",new Class<?>[]{int.class},12)));
        assertEquals(new SimulationDistance(10),check(create(GAME+"ClientboundSetSimulationDistancePacket",new Class<?>[]{int.class},10)));
    }
    @Test void openScreenCarriesContainerIdentityMenuTypeAndTitle()throws Exception{
        Object menu=value("net.minecraft.world.inventory.MenuType","GENERIC_9x3");
        Object title=type("net.minecraft.network.chat.Component").getMethod("literal",String.class).invoke(null,"Chest");
        var screen=(OpenScreen)check(create(GAME+"ClientboundOpenScreenPacket",new Class<?>[]{int.class,type("net.minecraft.world.inventory.MenuType"),type("net.minecraft.network.chat.Component")},3,menu,title));
        assertEquals(3,screen.containerId());assertEquals("minecraft:generic_9x3",screen.menuType());assertEquals("Chest",screen.title());
    }
    @Test void combatKillPreservesPlayerIdentityAndMessage()throws Exception{
        Object message=type("net.minecraft.network.chat.Component").getMethod("literal",String.class).invoke(null,"slain by Zombie");
        assertEquals(new CombatKill(6,"slain by Zombie"),check(create(GAME+"ClientboundPlayerCombatKillPacket",new Class<?>[]{int.class,type("net.minecraft.network.chat.Component")},6,message)));
    }
}
