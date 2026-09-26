package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Native objects from the pinned server, never stand-in classes with matching names. */
class NativeSemanticTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static Object vec(double x,double y,double z)throws Exception{return create("net.minecraft.world.phys.Vec3",new Class<?>[]{double.class,double.class,double.class},x,y,z);}
    static Object block(int x,int y,int z)throws Exception{return create("net.minecraft.core.BlockPos",new Class<?>[]{int.class,int.class,int.class},x,y,z);}
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    @Test void attackAndInteractionPreserveIdentityHandLocationAndSecondary()throws Exception{
        assertEquals(new Attack(123),check(create(GAME+"ServerboundAttackPacket",new Class<?>[]{int.class},123)));
        Object p=create(GAME+"ServerboundInteractPacket",new Class<?>[]{int.class,type("net.minecraft.world.InteractionHand"),type("net.minecraft.world.phys.Vec3"),boolean.class},
            7,value("net.minecraft.world.InteractionHand","OFF_HAND"),vec(.25,.5,-0.0),true);
        assertEquals(new Interact(7,Hand.OFF_HAND,new PacketPayload.Position(.25,.5,-0.0),true),check(p));
    }
    @Test void useItemPreservesSequenceAndRotation()throws Exception{
        Object p=create(GAME+"ServerboundUseItemPacket",new Class<?>[]{type("net.minecraft.world.InteractionHand"),int.class,float.class,float.class},
            value("net.minecraft.world.InteractionHand","MAIN_HAND"),44,90f,-20f);
        assertEquals(new UseItem(Hand.MAIN_HAND,44,new PacketPayload.Rotation(90,-20)),check(p));
    }
    @Test void useOnPreservesFaceCursorAndPredictionFlags()throws Exception{
        Object hit=create("net.minecraft.world.phys.BlockHitResult",new Class<?>[]{type("net.minecraft.world.phys.Vec3"),type("net.minecraft.core.Direction"),type("net.minecraft.core.BlockPos"),boolean.class,boolean.class},
            vec(2.5,64,3.25),value("net.minecraft.core.Direction","UP"),block(2,64,3),true,true);
        Object p=create(GAME+"ServerboundUseItemOnPacket",new Class<?>[]{type("net.minecraft.world.InteractionHand"),type("net.minecraft.world.phys.BlockHitResult"),int.class},
            value("net.minecraft.world.InteractionHand","OFF_HAND"),hit,51);
        assertEquals(new UseOn(Hand.OFF_HAND,51,new BlockPosition(2,64,3),Face.UP,new PacketPayload.Position(2.5,64,3.25),true,true),check(p));
    }
    @Test void diggingPreservesActionBlockFaceAndSequence()throws Exception{
        Object p=create(GAME+"ServerboundPlayerActionPacket",new Class<?>[]{type(GAME+"ServerboundPlayerActionPacket$Action"),type("net.minecraft.core.BlockPos"),type("net.minecraft.core.Direction"),int.class},
            value(GAME+"ServerboundPlayerActionPacket$Action","STOP_DESTROY_BLOCK"),block(-1,70,4),value("net.minecraft.core.Direction","NORTH"),12);
        assertEquals(new Dig("STOP_DESTROY_BLOCK",new BlockPosition(-1,70,4),Face.NORTH,12),check(p));
    }
    static Object transform()throws Exception{return create("net.minecraft.world.entity.PositionMoveRotation",new Class<?>[]{type("net.minecraft.world.phys.Vec3"),type("net.minecraft.world.phys.Vec3"),float.class,float.class},vec(1,64,3),vec(.1,-.2,.3),45f,15f);}
    @Test void teleportRelativeFlagsAndDeltaAreNotCollapsedToAbsolute()throws Exception{
        Object p=create(GAME+"ClientboundPlayerPositionPacket",new Class<?>[]{int.class,type("net.minecraft.world.entity.PositionMoveRotation"),Set.class},
            19,transform(),Set.of(value("net.minecraft.world.entity.Relative","X"),value("net.minecraft.world.entity.Relative","DELTA_Y")));
        var c=(Correction)check(p);assertEquals(19,c.teleportId());assertEquals(Set.of(Relative.X,Relative.DELTA_Y),c.relative());assertEquals(-.2,c.delta().y());
        var r=(RotationCorrection)check(create(GAME+"ClientboundPlayerRotationPacket",new Class<?>[]{float.class,boolean.class,float.class,boolean.class},90f,true,30f,false));
        assertTrue(r.relativeYaw());assertFalse(r.relativePitch());
    }
    @Test void entitySyncAndRemovalPreservePrimitiveIdentity()throws Exception{
        var p=(EntityPosition)check(create(GAME+"ClientboundEntityPositionSyncPacket",new Class<?>[]{int.class,type("net.minecraft.world.entity.PositionMoveRotation"),boolean.class},4,transform(),true));
        assertEquals(4,p.entityId());assertTrue(p.onGround());assertTrue(p.relative().isEmpty());
        int[] ids={1,2,3};var r=(RemoveEntities)check(create(GAME+"ClientboundRemoveEntitiesPacket",new Class<?>[]{int[].class},(Object)ids));
        ids[0]=999;assertEquals(List.of(1,2,3),r.ids());
    }
    @Test void abilitiesAreCopiedIncludingSpeeds()throws Exception{
        Object a=create("net.minecraft.world.entity.player.Abilities",new Class<?>[]{});
        type("net.minecraft.world.entity.player.Abilities").getField("flying").setBoolean(a,true);
        var p=(Abilities)check(create(GAME+"ClientboundPlayerAbilitiesPacket",new Class<?>[]{a.getClass()},a));assertTrue(p.flying());
        assertEquals(new FlightRequest(true),check(create(GAME+"ServerboundPlayerAbilitiesPacket",new Class<?>[]{a.getClass()},a)));
    }
    @Test void canonicalBlockPropertiesAreCopied()throws Exception{
        Object nativeBlock=value("net.minecraft.world.level.block.Blocks","OAK_STAIRS");Object state=nativeBlock.getClass().getMethod("defaultBlockState").invoke(nativeBlock);
        var p=(BlockChange)check(create(GAME+"ClientboundBlockUpdatePacket",new Class<?>[]{type("net.minecraft.core.BlockPos"),type("net.minecraft.world.level.block.state.BlockState")},block(1,2,3),state));
        assertEquals("minecraft:oak_stairs",p.state().block());assertTrue(p.state().properties().stream().anyMatch(v->v.name().equals("facing")));
    }
    @Test void bundleIsOrderedBoundedAndCannotNest()throws Exception{
        Object first=create(GAME+"ClientboundBlockChangedAckPacket",new Class<?>[]{int.class},8);
        Object second=create(GAME+"ClientboundSetHeldSlotPacket",new Class<?>[]{int.class},2);
        Object bundle=create(GAME+"ClientboundBundlePacket",new Class<?>[]{Iterable.class},List.of(first,second));
        assertEquals(new PacketPayload.Bundle(List.of(new SequenceAck(8),new HeldSlot(2))),check(bundle));
        Object nested=create(GAME+"ClientboundBundlePacket",new Class<?>[]{Iterable.class},List.of(bundle));
        assertThrows(IllegalArgumentException.class,()->PaperPacketNormalizer.normalize(nested));
        Object oversized=create(GAME+"ClientboundBundlePacket",new Class<?>[]{Iterable.class},Collections.nCopies(257,first));
        assertThrows(IllegalArgumentException.class,()->PaperPacketNormalizer.normalize(oversized));
    }
    @Test void nativeSpawnIncludesRegistryIdentityAndMovement()throws Exception{
        Object p=create(GAME+"ClientboundAddEntityPacket",new Class<?>[]{int.class,UUID.class,double.class,double.class,double.class,float.class,float.class,type("net.minecraft.world.entity.EntityType"),int.class,type("net.minecraft.world.phys.Vec3"),double.class},
            5,new UUID(1,2),1d,64d,3d,15f,45f,value("net.minecraft.world.entity.EntityTypes","PIG"),7,vec(.1,.2,.3),90d);
        var spawn=(EntitySpawn)check(p);assertEquals(5,spawn.entityId());assertEquals(new UUID(1,2),spawn.uuid());assertEquals(7,spawn.data());assertEquals(.2,spawn.delta().y());
    }
    /** P9-F5: vanilla pairs a tracked entity as ONE bundle (ServerEntity.addPairing); the spawn inside it must reach the neighbourhood tracker as a pusher. */
    @Test void aBundledNativeSpawnReachesTheNeighbourhoodTracker()throws Exception{
        Object spawn=create(GAME+"ClientboundAddEntityPacket",new Class<?>[]{int.class,UUID.class,double.class,double.class,double.class,float.class,float.class,type("net.minecraft.world.entity.EntityType"),int.class,type("net.minecraft.world.phys.Vec3"),double.class},
            9,new UUID(3,4),10d,64d,-3d,0f,0f,value("net.minecraft.world.entity.EntityTypes","LLAMA"),0,vec(0,0,0),0d);
        var bundle=(PacketPayload.Bundle)check(create(GAME+"ClientboundBundlePacket",new Class<?>[]{Iterable.class},List.of(spawn)));
        var key=new me.nicolas.papea2ac.model.SessionKey(new UUID(5,6),1);
        var events=me.nicolas.papea2ac.core.ContextObservationMapper.map(new Observation(key,11,11,0,new Observation.Outbound(11,bundle)),42);
        assertEquals(1,events.size(),events.toString());
        var s=(me.nicolas.papea2ac.player.ContextEvent.EntitySpawned)events.get(0);assertEquals(9,s.entityId());assertEquals("minecraft:llama",s.type());assertEquals(11,s.sequence());
        var ledger=new me.nicolas.papea2ac.player.MovementContextLedger(key);events.forEach(e->ledger.apply(key,e));
        assertTrue(ledger.view().anyRelevantEntityNear(10,64,-2,4,4),"the bundled llama is a pusher next to that position");
    }
    @Test void worldBorderCopiesEveryCommunicatedPrimitive()throws Exception{
        Object border=create("net.minecraft.world.level.border.WorldBorder",new Class<?>[]{});
        border.getClass().getMethod("setCenter",double.class,double.class).invoke(border,12d,-9d);
        border.getClass().getMethod("setSize",double.class).invoke(border,200d);
        var center=(BorderCenter)check(create(GAME+"ClientboundSetBorderCenterPacket",new Class<?>[]{border.getClass()},border));assertEquals(new BorderCenter(12,-9),center);
        assertEquals(new BorderSize(200),check(create(GAME+"ClientboundSetBorderSizePacket",new Class<?>[]{border.getClass()},border)));
        var init=(BorderInitialize)check(create(GAME+"ClientboundInitializeBorderPacket",new Class<?>[]{border.getClass()},border));assertEquals(200,init.newSize());assertEquals(12,init.x());
        check(create(GAME+"ClientboundSetBorderWarningDelayPacket",new Class<?>[]{border.getClass()},border));
        check(create(GAME+"ClientboundSetBorderWarningDistancePacket",new Class<?>[]{border.getClass()},border));
        check(create(GAME+"ClientboundSetBorderLerpSizePacket",new Class<?>[]{border.getClass()},border));
    }
    @Test void rawEntityRelativeDeltasSurviveReplayExactly()throws Exception{
        var delta=(EntityDelta)check(create(GAME+"ClientboundMoveEntityPacket$PosRot",new Class<?>[]{int.class,short.class,short.class,short.class,byte.class,byte.class,boolean.class},
            8,(short)-123,(short)2,(short)32767,(byte)127,(byte)-128,true));
        assertEquals(-123,delta.dx());assertEquals(32767,delta.dz());assertTrue(delta.hasPosition());assertTrue(delta.hasRotation());
    }
    @Test void attributesUseCanonicalRegistryKeysAndCopyModifiers()throws Exception{
        Object holder=value("net.minecraft.world.entity.ai.attributes.Attributes","MOVEMENT_SPEED");
        Object snapshot=create(GAME+"ClientboundUpdateAttributesPacket$AttributeSnapshot",new Class<?>[]{type("net.minecraft.core.Holder"),double.class,Collection.class},holder,.125,List.of());
        var constructor=type(GAME+"ClientboundUpdateAttributesPacket").getDeclaredConstructor(int.class,List.class);constructor.setAccessible(true);
        var attrs=(Attributes)check(constructor.newInstance(9,List.of(snapshot)));
        assertEquals("minecraft:movement_speed",attrs.values().getFirst().id());assertEquals(.125,attrs.values().getFirst().base());
    }
    @Test void effectsPreserveRegistryDurationAndFlags()throws Exception{
        Object holder=value("net.minecraft.world.effect.MobEffects","SPEED");
        Object effect=create("net.minecraft.world.effect.MobEffectInstance",new Class<?>[]{type("net.minecraft.core.Holder"),int.class,int.class,boolean.class,boolean.class,boolean.class},holder,200,2,true,false,true);
        var p=(Effect)check(create(GAME+"ClientboundUpdateMobEffectPacket",new Class<?>[]{int.class,effect.getClass(),boolean.class},7,effect,true));
        assertEquals("minecraft:speed",p.effect());assertEquals(200,p.duration());assertEquals(2,p.amplifier());assertTrue(p.ambient());assertFalse(p.visible());assertTrue(p.icon());assertTrue(p.blend());
        assertEquals(new RemoveEffect(7,"minecraft:speed"),check(create(GAME+"ClientboundRemoveMobEffectPacket",new Class<?>[]{int.class,type("net.minecraft.core.Holder")},7,holder)));
    }
}
