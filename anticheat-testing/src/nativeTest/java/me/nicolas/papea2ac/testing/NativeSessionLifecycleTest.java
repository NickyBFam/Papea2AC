package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/**
 * Native objects for the CONFIGURATION-state session/lifecycle family. These classes are shared
 * ("common"/"cookie" package) between CONFIGURATION and PLAY states in the pinned server, so a
 * mapping proven here also resolves the identical PLAY-state coverage-table rows.
 */
class NativeSessionLifecycleTest {
    static final String COMMON="net.minecraft.network.protocol.common.",CONFIG="net.minecraft.network.protocol.configuration.";
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    static Object component(String text)throws Exception{return type("net.minecraft.network.chat.Component").getMethod("literal",String.class).invoke(null,text);}
    @Test void disconnectIsSharedBetweenConfigurationAndPlay()throws Exception{
        Object p=create(COMMON+"ClientboundDisconnectPacket",new Class<?>[]{type("net.minecraft.network.chat.Component")},component("kicked for testing"));
        assertEquals(new Disconnect("kicked for testing"),check(p));
    }
    @Test void pingIsSharedBetweenConfigurationAndPlay()throws Exception{
        var control=(PacketPayload.Control)check(create(COMMON+"ClientboundPingPacket",new Class<?>[]{int.class},77));
        assertEquals(PacketPayload.ControlKind.PING,control.kind());assertEquals(77,control.value());
    }
    @Test void clientInformationIsSharedBetweenConfigurationAndPlay()throws Exception{
        Object info=create("net.minecraft.server.level.ClientInformation",
            new Class<?>[]{String.class,int.class,type("net.minecraft.world.entity.player.ChatVisiblity"),boolean.class,int.class,type("net.minecraft.world.entity.HumanoidArm"),boolean.class,boolean.class,type("net.minecraft.server.level.ParticleStatus")},
            "en_us",12,value("net.minecraft.world.entity.player.ChatVisiblity","FULL"),true,0,value("net.minecraft.world.entity.HumanoidArm","RIGHT"),false,true,value("net.minecraft.server.level.ParticleStatus","ALL"));
        Object p=create(COMMON+"ServerboundClientInformationPacket",new Class<?>[]{info.getClass()},info);
        assertEquals(new ClientSettings("en_us",12,"RIGHT",true),check(p));
    }
    @Test void transferIsSharedBetweenConfigurationAndPlay()throws Exception{
        Object p=create(COMMON+"ClientboundTransferPacket",new Class<?>[]{String.class,int.class},"example.org",25565);
        assertEquals(new Transfer("example.org",25565),check(p));
    }
    @Test void updateTagsIsSharedBetweenConfigurationAndPlayAndBoundsRegistryCount()throws Exception{
        assertEquals(new TagSync(List.of()),check(create(COMMON+"ClientboundUpdateTagsPacket",new Class<?>[]{Map.class},Map.of())));
    }
    @Test void finishConfigurationAndResetChatAreTransitionMarkers()throws Exception{
        assertEquals(new Lifecycle(LifecycleTransition.CONFIGURATION_FINISH),check(value(CONFIG+"ClientboundFinishConfigurationPacket","INSTANCE")));
        assertEquals(new Lifecycle(LifecycleTransition.CONFIGURATION_FINISH),check(value(CONFIG+"ServerboundFinishConfigurationPacket","INSTANCE")));
        assertEquals(new Lifecycle(LifecycleTransition.RESET_CHAT),check(value(CONFIG+"ClientboundResetChatPacket","INSTANCE")));
    }
    @Test void registryDataCarriesRegistryIdentityAndEntryCountOnly()throws Exception{
        Object registryKey=value("net.minecraft.core.registries.Registries","BLOCK");
        Object p=create(CONFIG+"ClientboundRegistryDataPacket",new Class<?>[]{type("net.minecraft.resources.ResourceKey"),List.class},registryKey,List.of());
        assertEquals(new RegistrySync("minecraft:block",0),check(p));
    }
    @Test void enabledFeaturesCarriesBoundedIdentifierList()throws Exception{
        Object vanilla=type("net.minecraft.resources.Identifier").getMethod("withDefaultNamespace",String.class).invoke(null,"vanilla");
        Object p=create(CONFIG+"ClientboundUpdateEnabledFeaturesPacket",new Class<?>[]{Set.class},Set.of(vanilla));
        assertEquals(new EnabledFeatures(List.of("minecraft:vanilla")),check(p));
    }
    @Test void selectKnownPacksIsSharedBetweenBothDirections()throws Exception{
        Object pack=create("net.minecraft.server.packs.repository.KnownPack",new Class<?>[]{String.class,String.class,String.class},"minecraft","core","1.0");
        assertEquals(new KnownPacks(List.of("minecraft:core@1.0")),check(create(CONFIG+"ClientboundSelectKnownPacks",new Class<?>[]{List.class},List.of(pack))));
        assertEquals(new KnownPacks(List.of("minecraft:core@1.0")),check(create(CONFIG+"ServerboundSelectKnownPacks",new Class<?>[]{List.class},List.of(pack))));
    }
    @Test void codeOfConductRoundTripsAndAcceptIsATransitionMarker()throws Exception{
        assertEquals(new CodeOfConduct("be excellent to each other"),check(create(CONFIG+"ClientboundCodeOfConductPacket",new Class<?>[]{String.class},"be excellent to each other")));
        assertEquals(new Lifecycle(LifecycleTransition.ACCEPT_CODE_OF_CONDUCT),check(create(CONFIG+"ServerboundAcceptCodeOfConductPacket",new Class<?>[]{},new Object[]{})));
    }
}
