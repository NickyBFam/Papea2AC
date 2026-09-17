package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import me.nicolas.papea2AC.platform.PaperCapture;
import me.nicolas.papea2ac.core.PacketEngine;

/** Hardening pass: PreLoginTransport -> Bukkit identity -> PacketEngine.Session binding races. */
class BindingRaceTest {
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static EmbeddedChannel acceptedChannel(){
        var channel=new EmbeddedChannel();
        var pipeline=channel.pipeline();
        pipeline.addLast("timeout",new ChannelInboundHandlerAdapter());
        pipeline.addLast("splitter",new ChannelInboundHandlerAdapter());
        pipeline.addLast("decoder",new ChannelInboundHandlerAdapter());
        pipeline.addLast("prepender",new ChannelOutboundHandlerAdapter());
        pipeline.addLast("outbound_config",new ChannelOutboundHandlerAdapter());
        pipeline.addLast("hackfix",new ChannelOutboundHandlerAdapter());
        pipeline.addLast("packet_handler",new ChannelInboundHandlerAdapter());
        return channel;
    }
    static void fireAccept(Channel channel)throws Exception{
        type("io.papermc.paper.network.ChannelInitializeListenerHolder").getMethod("callListeners",Channel.class).invoke(null,channel);
    }
    static Object handshake(int version)throws Exception{
        return create("net.minecraft.network.protocol.handshake.ClientIntentionPacket",
            new Class<?>[]{int.class,String.class,int.class,type("net.minecraft.network.protocol.handshake.ClientIntent")},
            version,"localhost",25565,type("net.minecraft.network.protocol.handshake.ClientIntent").getField("LOGIN").get(null));
    }
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    @Test void oldConnectionClosingAfterNewConnectionBoundNeverAffectsTheReplacement()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            UUID player=new UUID(50,1);
            var oldChannel=acceptedChannel();fireAccept(oldChannel);
            oldChannel.writeInbound(handshake(772));
            var oldSession=capture.bind(player,oldChannel);
            oldChannel.runPendingTasks();
            NativePacketTest.awaitSequence(oldSession,1);
            // The player reconnects on a distinct physical channel before the old one is detected as dead.
            var newChannel=acceptedChannel();fireAccept(newChannel);
            newChannel.writeInbound(handshake(772));
            var newSession=capture.bind(player,newChannel);
            newChannel.runPendingTasks();
            NativePacketTest.awaitSequence(newSession,1);
            assertNotEquals(oldSession.key.epoch(),newSession.key.epoch(),"reconnect must mint a fresh epoch, never reuse the old one");
            // The old connection's TCP teardown finally arrives late.
            oldChannel.pipeline().fireChannelInactive();
            oldChannel.runPendingTasks();
            assertTrue(oldSession.ingress.closed(),"the old session is closed by its own channel's teardown");
            assertFalse(newSession.ingress.closed(),"a stale channelInactive from the OLD physical connection must never close the REPLACEMENT session");
            newChannel.writeInbound(handshake(772));
            newChannel.runPendingTasks();
            NativePacketTest.awaitSequence(newSession,2);
            assertEquals(2,newSession.actor.snapshot().processedSequence(),"the replacement session must keep capturing normally after the old connection's late teardown");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void bindingWhileCaptureDisabledFailsCleanlyWithoutCorruptingPreLoginState()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            channel.writeInbound(handshake(772));
            engine.setCaptureEnabled(false);
            assertThrows(IllegalStateException.class,()->capture.bind(new UUID(51,1),channel),
                "bind() must fail fast, before touching any pre-login state, when the engine-wide capture gate is off");
            // The pre-login transport must remain completely untouched: a later bind (once re-enabled) still works.
            engine.setCaptureEnabled(true);
            var session=capture.bind(new UUID(51,1),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,1);
            assertEquals(1,session.actor.snapshot().processedSequence(),"the journaled handshake from before the failed bind attempt must still be replayed once bind succeeds");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void bufferedObservationsFlushInOrderAndLiveCapturesArrivingDuringTheGapAreNeitherLostNorReordered()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            // Packets 1-2 arrive and are journaled (pre-login, no player identity yet).
            channel.writeInbound(handshake(772));
            channel.writeInbound(handshake(772));
            // capture.bind() is called (simulating PlayerJoinEvent), which posts a replay-then-bind task to the
            // channel's own event loop - but does not run it yet (mirroring the real gap between the Bukkit
            // main-thread join-event call and the channel's own event loop actually picking up that task).
            var session=capture.bind(new UUID(52,1),channel);
            // A 3rd packet arrives in that exact gap, before the posted bind task has run: since boundSession
            // is still null at this point, it is journaled too, appended after the first two, never lost.
            channel.writeInbound(handshake(772));
            // Only now does the event loop actually run the posted bind task (and the queued channelRead calls).
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,3);
            assertEquals(3,session.actor.snapshot().processedSequence(),"all three pre-bind observations must be delivered, in order, exactly once");
            // A 4th packet, arriving after boundSession is set, goes through the live path directly.
            channel.writeInbound(handshake(772));
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,4);
            assertEquals(4,session.actor.snapshot().processedSequence(),"post-bind live capture must continue the same sequence with no gap or duplicate");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void duplicateLoginProactivelyDetachesTheOldBindingBeforeCreatingTheNewOne()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            UUID player=new UUID(53,1);
            var firstChannel=acceptedChannel();fireAccept(firstChannel);
            firstChannel.writeInbound(handshake(772));
            var firstSession=capture.bind(player,firstChannel);
            firstChannel.runPendingTasks();
            NativePacketTest.awaitSequence(firstSession,1);
            // Simulate the platform layer's own duplicate-login handling: detach the old binding first.
            capture.detach(firstChannel,firstSession);
            firstChannel.runPendingTasks();
            assertTrue(firstSession.ingress.closed());
            var secondChannel=acceptedChannel();fireAccept(secondChannel);
            secondChannel.writeInbound(handshake(772));
            var secondSession=capture.bind(player,secondChannel);
            secondChannel.runPendingTasks();
            NativePacketTest.awaitSequence(secondSession,1);
            assertEquals(1,engine.sessions(),"only the replacement session may remain active for this player");
            assertTrue(secondSession.key.epoch()>firstSession.key.epoch());
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
}
