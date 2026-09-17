package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Native objects for the 11 previously hook-timing-blocked HANDSHAKING/LOGIN/PLAY-join entries. */
class NativeEarlyTransportSemanticTest {
    static final String HANDSHAKE="net.minecraft.network.protocol.handshake.",LOGIN="net.minecraft.network.protocol.login.",GAME="net.minecraft.network.protocol.game.";
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    @Test void handshakeCarriesProtocolVersionAndIntentOnly()throws Exception{
        Object p=create(HANDSHAKE+"ClientIntentionPacket",new Class<?>[]{int.class,String.class,int.class,type(HANDSHAKE+"ClientIntent")},772,"play.example.org",25565,value(HANDSHAKE+"ClientIntent","LOGIN"));
        assertEquals(new Handshake(772,"LOGIN"),check(p));
    }
    @Test void loginHelloCarriesClaimedIdentityOnlyNeverTrusted()throws Exception{
        UUID claimed=new UUID(1,2);
        var hello=(LoginHello)check(create(LOGIN+"ServerboundHelloPacket",new Class<?>[]{String.class,UUID.class},"Steve",claimed));
        assertEquals("Steve",hello.claimedName());assertEquals(claimed,hello.claimedProfileId());
    }
    @Test void loginEncryptionAndKeyExchangeAreMarkersWithNoKeyMaterialCaptured()throws Exception{
        Object hello=create(LOGIN+"ClientboundHelloPacket",new Class<?>[]{String.class,byte[].class,byte[].class,boolean.class},"",new byte[]{1,2,3},new byte[]{4,5,6},true);
        assertEquals(new Lifecycle(LifecycleTransition.LOGIN_ENCRYPTION_REQUEST),check(hello));
        var rsa=java.security.KeyPairGenerator.getInstance("RSA");rsa.initialize(2048);var keyPair=rsa.generateKeyPair();
        var aes=javax.crypto.KeyGenerator.getInstance("AES");aes.init(128);var secretKey=aes.generateKey();
        Object key=create(LOGIN+"ServerboundKeyPacket",new Class<?>[]{javax.crypto.SecretKey.class,java.security.PublicKey.class,byte[].class},secretKey,keyPair.getPublic(),new byte[]{1,2,3});
        assertEquals(new Lifecycle(LifecycleTransition.LOGIN_KEY_RESPONSE),check(key));
        assertEquals(new Lifecycle(LifecycleTransition.LOGIN_ACKNOWLEDGED),check(value(LOGIN+"ServerboundLoginAcknowledgedPacket","INSTANCE")));
    }
    @Test void loginCompressionCarriesThresholdOnly()throws Exception{
        assertEquals(new LoginCompression(256),check(create(LOGIN+"ClientboundLoginCompressionPacket",new Class<?>[]{int.class},256)));
    }
    @Test void loginDisconnectSharesTheCommonDisconnectRepresentation()throws Exception{
        Object reason=type("net.minecraft.network.chat.Component").getMethod("literal",String.class).invoke(null,"banned");
        assertEquals(new Disconnect("banned"),check(create(LOGIN+"ClientboundLoginDisconnectPacket",new Class<?>[]{type("net.minecraft.network.chat.Component")},reason)));
    }
    @Test void loginPluginQueryCarriesChannelIdentityNotPayloadBytes()throws Exception{
        Object id=type("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath",String.class,String.class).invoke(null,"example","auth");
        Object payload=create(LOGIN+"custom.DiscardedQueryPayload",new Class<?>[]{type("net.minecraft.resources.Identifier")},id);
        var query=(LoginPluginQuery)check(create(LOGIN+"ClientboundCustomQueryPacket",new Class<?>[]{int.class,type(LOGIN+"custom.CustomQueryPayload")},7,payload));
        assertEquals(7,query.transactionId());assertEquals("example:auth",query.channel());
        Object answerPayload=create(LOGIN+"custom.DiscardedQueryAnswerPayload",new Class<?>[]{},new Object[]{});
        var answer=(LoginPluginQueryResponse)check(create(LOGIN+"ServerboundCustomQueryAnswerPacket",new Class<?>[]{int.class,type(LOGIN+"custom.CustomQueryAnswerPayload")},7,answerPayload));
        assertTrue(answer.understood());
        var unanswered=(LoginPluginQueryResponse)check(create(LOGIN+"ServerboundCustomQueryAnswerPacket",new Class<?>[]{int.class,type(LOGIN+"custom.CustomQueryAnswerPayload")},7,(Object)null));
        assertFalse(unanswered.understood());
    }
    @Test void loginFinishedCarriesServerCommittedProfileDistinctFromClaim()throws Exception{
        Object profile=create("com.mojang.authlib.GameProfile",new Class<?>[]{UUID.class,String.class},new UUID(9,9),"Steve");
        var finished=(LoginFinished)check(create(LOGIN+"ClientboundLoginFinishedPacket",new Class<?>[]{type("com.mojang.authlib.GameProfile"),UUID.class},profile,new UUID(0,0)));
        assertEquals(new UUID(9,9),finished.profileId());assertEquals("Steve",finished.profileName());
    }
    static Object dimensionType()throws Exception{
        Object monsterSettings=create("net.minecraft.world.level.dimension.DimensionType$MonsterSettings",
            new Class<?>[]{type("net.minecraft.util.valueproviders.IntProvider"),int.class},
            type("net.minecraft.util.valueproviders.ConstantInt").getMethod("of",int.class).invoke(null,0),0);
        return create("net.minecraft.world.level.dimension.DimensionType",new Class<?>[]{
                boolean.class,boolean.class,boolean.class,boolean.class,double.class,int.class,int.class,int.class,
                type("net.minecraft.core.HolderSet"),float.class,
                type("net.minecraft.world.level.dimension.DimensionType$MonsterSettings"),
                type("net.minecraft.world.level.dimension.DimensionType$Skybox"),
                type("net.minecraft.world.level.CardinalLighting$Type"),
                type("net.minecraft.world.attribute.EnvironmentAttributeMap"),
                type("net.minecraft.core.HolderSet"),java.util.Optional.class},
            false,false,false,false,1.0d,-64,384,384,
            type("net.minecraft.core.HolderSet").getMethod("empty").invoke(null),0f,
            monsterSettings,value("net.minecraft.world.level.dimension.DimensionType$Skybox","NONE"),
            value("net.minecraft.world.level.CardinalLighting$Type","DEFAULT"),
            value("net.minecraft.world.attribute.EnvironmentAttributeMap","EMPTY"),
            type("net.minecraft.core.HolderSet").getMethod("empty").invoke(null),Optional.empty());
    }
    @Test void joinGameCarriesIdentityAndCoarseWorldFactsNotDimensionTypeContent()throws Exception{
        Object dimensionTypeHolder=type("net.minecraft.core.Holder").getMethod("direct",Object.class).invoke(null,dimensionType());
        Object spawnInfo=create(GAME+"CommonPlayerSpawnInfo",new Class<?>[]{
                type("net.minecraft.core.Holder"),type("net.minecraft.resources.ResourceKey"),long.class,
                type("net.minecraft.world.level.GameType"),type("net.minecraft.world.level.GameType"),
                boolean.class,boolean.class,Optional.class,int.class,int.class},
            dimensionTypeHolder,value("net.minecraft.world.level.Level","OVERWORLD"),42L,
            value("net.minecraft.world.level.GameType","SURVIVAL"),value("net.minecraft.world.level.GameType","SURVIVAL"),
            false,false,Optional.empty(),0,63);
        Object p=create(GAME+"ClientboundLoginPacket",new Class<?>[]{int.class,boolean.class,Set.class,int.class,int.class,int.class,boolean.class,boolean.class,boolean.class,type(GAME+"CommonPlayerSpawnInfo"),boolean.class,boolean.class},
            5,false,Set.of(value("net.minecraft.world.level.Level","OVERWORLD")),20,10,10,false,false,false,spawnInfo,true,false);
        var join=(JoinGame)check(p);
        assertEquals(5,join.playerId());assertEquals("minecraft:overworld",join.dimension());assertEquals("SURVIVAL",join.gameType());assertEquals(20,join.maxPlayers());assertTrue(join.onlineMode());
    }
}
