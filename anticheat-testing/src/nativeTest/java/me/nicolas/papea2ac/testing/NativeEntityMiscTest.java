package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.netty.buffer.Unpooled;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Native entity-facing family: damage/simple events, look-at, minecart motion, respawn. */
class NativeEntityMiscTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    static Object registryProvider;
    @BeforeAll static void bootstrap()throws Exception{
        NativePacketTest.bootstrap();
        registryProvider=Class.forName("net.minecraft.data.registries.VanillaRegistries").getMethod("createLookup").invoke(null);
    }
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static Object vec(double x,double y,double z)throws Exception{return create("net.minecraft.world.phys.Vec3",new Class<?>[]{double.class,double.class,double.class},x,y,z);}
    static Object genericDamageTypeHolder()throws Exception{
        Object key=value("net.minecraft.world.damagesource.DamageTypes","GENERIC");
        Object registryKey=value("net.minecraft.core.registries.Registries","DAMAGE_TYPE");
        Object lookup=registryProvider.getClass().getMethod("lookupOrThrow",type("net.minecraft.resources.ResourceKey")).invoke(registryProvider,registryKey);
        return lookup.getClass().getMethod("getOrThrow",type("net.minecraft.resources.ResourceKey")).invoke(lookup,key);
    }
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    @Test void damageEventWithoutAnOptionalPositionOrCauseIsRepresentedExactly()throws Exception{
        Object holder=genericDamageTypeHolder();
        var event=(DamageEvent)check(create(GAME+"ClientboundDamageEventPacket",
            new Class<?>[]{int.class,type("net.minecraft.core.Holder"),int.class,int.class,Optional.class},
            9,holder,-1,-1,Optional.empty()));
        assertEquals(9,event.entityId());assertFalse(event.hasPosition());assertEquals(-1,event.sourceCauseId());assertEquals("minecraft:generic",event.sourceType());
    }
    @Test void damageEventWithSourcePositionPreservesCoordinates()throws Exception{
        Object holder=genericDamageTypeHolder();
        var event=(DamageEvent)check(create(GAME+"ClientboundDamageEventPacket",
            new Class<?>[]{int.class,type("net.minecraft.core.Holder"),int.class,int.class,Optional.class},
            9,holder,4,7,Optional.of(vec(1.5,64,3.25))));
        assertTrue(event.hasPosition());assertEquals(1.5,event.x());assertEquals(4,event.sourceCauseId());assertEquals(7,event.sourceDirectId());
    }
    /** No public constructor takes a raw (entityId, eventId) pair; decode via the real private wire constructor with hand-verified bytes (writeInt then writeByte, confirmed against this class's own write() bytecode). */
    @Test void entitySimpleEventDecodesRawEntityIdAndEventIdFromTheWireConstructor()throws Exception{
        Object buf=type("net.minecraft.network.FriendlyByteBuf").getConstructor(io.netty.buffer.ByteBuf.class).newInstance(Unpooled.buffer());
        buf.getClass().getMethod("writeInt",int.class).invoke(buf,42);
        buf.getClass().getMethod("writeByte",int.class).invoke(buf,3);
        var ctor=type(GAME+"ClientboundEntityEventPacket").getDeclaredConstructor(type("net.minecraft.network.FriendlyByteBuf"));
        ctor.setAccessible(true);
        var packet=ctor.newInstance(buf);
        var event=(EntitySimpleEvent)check(packet);
        assertEquals(42,event.entityId());assertEquals(3,event.eventId());
    }
    @Test void lookAtPositionOnlyVariantHasNoEntityReference()throws Exception{
        Object anchor=value("net.minecraft.commands.arguments.EntityAnchorArgument$Anchor","EYES");
        var look=(LookAt)check(create(GAME+"ClientboundPlayerLookAtPacket",new Class<?>[]{type("net.minecraft.commands.arguments.EntityAnchorArgument$Anchor"),double.class,double.class,double.class},anchor,1d,64d,3d));
        assertEquals("EYES",look.fromAnchor());assertEquals(1d,look.x());assertEquals(0,look.entityId());assertFalse(look.atEntity());
    }
    @Test void minecartMoveCarriesOrderedInterpolationSteps()throws Exception{
        Object step=create("net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior$MinecartStep",
            new Class<?>[]{type("net.minecraft.world.phys.Vec3"),type("net.minecraft.world.phys.Vec3"),float.class,float.class,float.class},
            vec(1,64,3),vec(.1,-.2,.3),45f,15f,1f);
        var move=(MinecartMove)check(create(GAME+"ClientboundMoveMinecartPacket",new Class<?>[]{int.class,java.util.List.class},7,List.of(step)));
        assertEquals(7,move.entityId());assertEquals(1,move.steps().size());assertEquals(1d,move.steps().getFirst().x());assertEquals(45f,move.steps().getFirst().yaw());
    }
    @Test void respawnCarriesDimensionIdentityAndKeepFlagsNotDimensionTypeContent()throws Exception{
        Object dimensionTypeHolder=type("net.minecraft.core.Holder").getMethod("direct",Object.class).invoke(null,NativeEarlyTransportSemanticTest.dimensionType());
        Object spawnInfo=create(GAME+"CommonPlayerSpawnInfo",new Class<?>[]{
                type("net.minecraft.core.Holder"),type("net.minecraft.resources.ResourceKey"),long.class,
                type("net.minecraft.world.level.GameType"),type("net.minecraft.world.level.GameType"),
                boolean.class,boolean.class,Optional.class,int.class,int.class},
            dimensionTypeHolder,value("net.minecraft.world.level.Level","NETHER"),1L,
            value("net.minecraft.world.level.GameType","SURVIVAL"),value("net.minecraft.world.level.GameType","SURVIVAL"),
            false,false,Optional.empty(),0,63);
        Object keepAll=type(GAME+"ClientboundRespawnPacket").getField("KEEP_ALL_DATA").get(null);
        var respawn=(Respawn)check(create(GAME+"ClientboundRespawnPacket",new Class<?>[]{type(GAME+"CommonPlayerSpawnInfo"),byte.class},spawnInfo,keepAll));
        assertEquals("minecraft:the_nether",respawn.dimension());
    }
}
