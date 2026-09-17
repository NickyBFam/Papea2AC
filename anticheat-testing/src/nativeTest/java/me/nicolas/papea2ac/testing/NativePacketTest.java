package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import me.nicolas.papea2AC.platform.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.protocol.*;
class NativePacketTest {
    @Test void idleOutboundInsertionIsDetectedWithoutPacketTraffic()throws Exception{
        PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));EmbeddedChannel channel=new EmbeddedChannel();
        channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter());
        PaperCapture adapter=new PaperCapture(engine);var session=adapter.attach(new UUID(11,12),channel);channel.runPendingTasks();
        try{
            channel.pipeline().addAfter("encoder","foreign-outbound",new ChannelOutboundHandlerAdapter());
            adapter.audit(session);adapter.audit(session);channel.runPendingTasks();
            assertFalse(session.health.get().healthy());assertFalse(session.actor.snapshot().certificationAvailable());
        }finally{adapter.detach(channel,session);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
    @Test void exactNativeInventoryMatchesAndMissingFamilyFailsProbe()throws Exception{
        var entries=PaperPacketInventory.inspect();assertTrue(PaperPacketInventory.matchesPinned(entries));
        var missing=new ArrayList<>(entries);missing.removeIf(e->e.type().equals("minecraft:move_player_pos"));
        assertFalse(PaperPacketInventory.matchesPinned(missing));
    }
    static void awaitSequence(PacketEngine.Session session,long sequence)throws Exception{
        long deadline=System.nanoTime()+2_000_000_000L;
        while(session.actor.snapshot().processedSequence()<sequence&&System.nanoTime()<deadline)Thread.sleep(1);
        assertTrue(session.actor.snapshot().processedSequence()>=sequence,"capture must actually reach the actor");
    }
    @Test void delayedDetachCannotRemoveReplacementHooks()throws Exception{
        PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));EmbeddedChannel channel=new EmbeddedChannel();
        channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter());
        PaperCapture adapter=new PaperCapture(engine);UUID player=new UUID(9,10);
        var old=adapter.attach(player,channel);channel.runPendingTasks();
        var replacement=adapter.attach(player,channel);channel.runPendingTasks();
        try{
            adapter.detach(channel,old);channel.runPendingTasks();channel.writeInbound(movement(7));
            awaitSequence(replacement,1);assertTrue(old.ingress.closed());
            assertNotNull(channel.pipeline().get("papea2ac-in-"+replacement.key.epoch()));
        }finally{adapter.detach(channel,replacement);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
    @Test void voidPromiseStillRecordsOutboundCompletion()throws Exception{
        PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));EmbeddedChannel channel=new EmbeddedChannel();
        channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter());
        PaperCapture adapter=new PaperCapture(engine);var session=adapter.attach(new UUID(7,8),channel);channel.runPendingTasks();
        try{
            Object packet=create("net.minecraft.network.protocol.game.ClientboundSetHealthPacket",new Class<?>[]{float.class,int.class,float.class},19f,20,5f);
            channel.pipeline().writeAndFlush(packet,channel.voidPromise());channel.runPendingTasks();
            awaitSequence(session,2);
            assertEquals(0,engine.metrics.count(PacketMetrics.Kind.WRITE_FAILED));
            assertSame(packet,channel.readOutbound());
        }finally{adapter.detach(channel,session);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
    @BeforeAll static void bootstrap()throws Exception{
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
    }
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{
        return Class.forName(name).getConstructor(types).newInstance(args);
    }
    static Object movement(double x)throws Exception{
        return create("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos",
            new Class<?>[]{double.class,double.class,double.class,boolean.class,boolean.class},x,64d,2d,true,false);
    }
    @Test void normalizesNativeMovementWithoutLeakingPacket()throws Exception{
        Object packet=movement(3);var normalized=(PacketPayload.Movement)PaperPacketNormalizer.normalize(packet);
        assertEquals(new PacketPayload.Position(3,64,2),normalized.position());assertNull(normalized.rotation());
    }
    @Test void nativeNonfinitePositionRejected()throws Exception{assertThrows(IllegalArgumentException.class,()->PaperPacketNormalizer.normalize(movement(Double.NaN)));}
    @Test void nativeVelocityPreservesCommunicatedVector()throws Exception{
        Class<?> vec=Class.forName("net.minecraft.world.phys.Vec3");Object v=vec.getConstructor(double.class,double.class,double.class).newInstance(.1,.2,.3);
        Object packet=create("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket",new Class<?>[]{int.class,vec},7,v);
        assertEquals(new PacketPayload.Velocity(7,new PacketPayload.Position(.1,.2,.3)),PaperPacketNormalizer.normalize(packet));
    }
    @Test void nativeHealth()throws Exception{
        Object p=create("net.minecraft.network.protocol.game.ClientboundSetHealthPacket",new Class<?>[]{float.class,int.class,float.class},19f,20,5f);
        assertEquals(new PacketPayload.Health(19,20,5),PaperPacketNormalizer.normalize(p));
    }
    @Test void nativeTickRateAndFreeze()throws Exception{
        Object p=create("net.minecraft.network.protocol.game.ClientboundTickingStatePacket",new Class<?>[]{float.class,boolean.class},10f,true);
        assertEquals(new PacketPayload.TickRate(10,true),PaperPacketNormalizer.normalize(p));
    }
    @Test void nativeHeldItemAndTeleportConfirm()throws Exception{
        Object p=create("net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket",new Class<?>[]{int.class},4);
        assertEquals(new PacketPayload.Control(PacketPayload.ControlKind.HELD_ITEM,4),PaperPacketNormalizer.normalize(p));
        p=create("net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket",new Class<?>[]{int.class},27);
        assertEquals(new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM,27),PaperPacketNormalizer.normalize(p));
    }
    @Test void nativeInputMask()throws Exception{
        Class<?> input=Class.forName("net.minecraft.world.entity.player.Input");
        Object value=input.getConstructors()[0].newInstance(true,false,false,true,true,false,true);
        Object p=create("net.minecraft.network.protocol.game.ServerboundPlayerInputPacket",new Class<?>[]{input},value);
        assertEquals(new PacketPayload.Control(PacketPayload.ControlKind.INPUT,89),PaperPacketNormalizer.normalize(p));
    }
    @Test void downstreamCancellationNeverCertifies()throws Exception{
        PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));
        EmbeddedChannel channel=new EmbeddedChannel();
        channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter(){@Override public void channelRead(ChannelHandlerContext ctx,Object message){ /* server-side cancellation */ }});
        PaperCapture adapter=new PaperCapture(engine);
        var session=adapter.attach(new UUID(1,2),channel);channel.runPendingTasks();
        try{
            channel.writeInbound(movement(3));
            awaitSequence(session,1);
            assertEquals(0,session.actor.snapshot().certifiedIngressOrdinal(),"CANCELLED PACKET DOES NOT ADVANCE CERTIFIED FRONTIER");
            assertFalse(session.actor.snapshot().certificationAvailable());
        }finally{adapter.detach(channel,session);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
    @Test void downstreamMutationDoesNotCertifyOriginal()throws Exception{
        PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));EmbeddedChannel channel=new EmbeddedChannel();
        channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());
        var accepted=new ArrayList<Object>();
        channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter(){
            @Override public void channelRead(ChannelHandlerContext ctx,Object message)throws Exception{accepted.add(movement(99));}
        });
        PaperCapture adapter=new PaperCapture(engine);var session=adapter.attach(new UUID(3,4),channel);channel.runPendingTasks();
        try{channel.writeInbound(movement(1));assertEquals(1,accepted.size());awaitSequence(session,1);assertEquals(0,session.actor.snapshot().certifiedIngressOrdinal());}
        finally{adapter.detach(channel,session);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
    @Test void insertedForeignHandlerDropsBoundary()throws Exception{
        PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));EmbeddedChannel channel=new EmbeddedChannel();
        channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter());
        PaperCapture adapter=new PaperCapture(engine);var session=adapter.attach(new UUID(5,6),channel);channel.runPendingTasks();
        try{
            channel.pipeline().addBefore("packet_handler","foreign",new ChannelInboundHandlerAdapter());
            channel.writeInbound(movement(1));assertFalse(session.health.get().healthy());assertFalse(session.actor.snapshot().certificationAvailable());
        }finally{adapter.detach(channel,session);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
}
