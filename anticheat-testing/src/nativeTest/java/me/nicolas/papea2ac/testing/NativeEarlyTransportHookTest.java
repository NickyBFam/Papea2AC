package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import me.nicolas.papea2AC.platform.PaperCapture;
import me.nicolas.papea2ac.core.PacketEngine;

/**
 * Local dynamic pipeline proof for the early attachment mechanism: the real, pinned
 * {@code io.papermc.paper.network.ChannelInitializeListenerHolder} is exercised exactly as the
 * pinned server's own {@code ServerConnectionListener$1.initChannel} exercises it, against a
 * hand-built pipeline shaped like the one verified in that class's bytecode (a "hackfix"/
 * "packet_handler" pair with no "encoder" yet). This is NOT a real client or a real server socket;
 * see docs/PHASE_1_EARLY_TRANSPORT_INVESTIGATION.md for what remains external qualification.
 */
class NativeEarlyTransportHookTest {
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    /** Mirrors the pinned ServerConnectionListener$1.initChannel pipeline shape at accept time: no "encoder" yet, only the placeholder position via "hackfix"'s neighbor. */
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
    static Object handshake(int version,String intent)throws Exception{
        return create("net.minecraft.network.protocol.handshake.ClientIntentionPacket",
            new Class<?>[]{int.class,String.class,int.class,type("net.minecraft.network.protocol.handshake.ClientIntent")},
            version,"localhost",25565,type("net.minecraft.network.protocol.handshake.ClientIntent").getField("LOGIN").get(null));
    }
    static Object loginHello(String name,UUID id)throws Exception{
        return create("net.minecraft.network.protocol.login.ServerboundHelloPacket",new Class<?>[]{String.class,UUID.class},name,id);
    }
    static Object encryptionRequest()throws Exception{
        return create("net.minecraft.network.protocol.login.ClientboundHelloPacket",new Class<?>[]{String.class,byte[].class,byte[].class,boolean.class},"",new byte[]{1},new byte[]{2},false);
    }
    @Test void realChannelInitializeListenerHolderInstallsBeforeAnyProtocolByte()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();
            int before=channel.pipeline().names().size();
            fireAccept(channel);
            var names=channel.pipeline().names();
            assertTrue(names.size()>before);
            assertTrue(names.stream().anyMatch(n->n.startsWith("papea2ac-in-t")));
            assertTrue(names.stream().anyMatch(n->n.startsWith("papea2ac-out-t")));
            int inIndex=indexOfPrefix(names,"papea2ac-in-t"),outIndex=indexOfPrefix(names,"papea2ac-out-t");
            assertEquals(names.indexOf("packet_handler"),inIndex+1,"inbound tap must sit immediately before packet_handler");
            assertEquals(names.indexOf("hackfix"),outIndex-1,"outbound tap must sit immediately after hackfix, not depend on an 'encoder' name that does not exist yet");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    static int indexOfPrefix(List<String> names,String prefix){
        for(int i=0;i<names.size();i++)if(names.get(i).startsWith(prefix))return i;
        throw new AssertionError("missing "+prefix);
    }
    @Test void handshakeAndLoginInboundAreJournaledPrePlayerThenReplayedContinuously()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();
            fireAccept(channel);
            channel.writeInbound(handshake(772,"LOGIN"));
            channel.writeInbound(loginHello("Steve",new UUID(9,9)));
            channel.writeOutbound(encryptionRequest());
            UUID claimed=new UUID(9,9),established=new UUID(1,1);
            var session=capture.bind(established,channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,3);
            assertEquals(3,session.actor.snapshot().processedSequence());
            assertEquals(established,session.key.player(),"the bound session must key on the server-established identity, never the client-claimed one");
            assertNotEquals(claimed,session.key.player(),"a client-claimed UUID from ServerboundHelloPacket must never be promoted into the trusted session key");
            channel.writeInbound(handshake(772,"LOGIN"));
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,4);
            assertEquals(4,session.actor.snapshot().processedSequence(),"sequence must continue, not restart, once bound");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void distinctConnectionsNeverShareOrLeakPreLoginState()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var oldChannel=acceptedChannel();
            fireAccept(oldChannel);
            oldChannel.writeInbound(handshake(772,"LOGIN"));
            var oldSession=capture.bind(new UUID(5,5),oldChannel);
            oldChannel.runPendingTasks();
            oldChannel.pipeline().fireChannelInactive();
            oldChannel.runPendingTasks();
            assertTrue(oldSession.ingress.closed());
            var newChannel=acceptedChannel();
            fireAccept(newChannel);
            newChannel.writeInbound(handshake(772,"LOGIN"));
            newChannel.writeInbound(loginHello("Steve",new UUID(9,9)));
            var newSession=capture.bind(new UUID(6,6),newChannel);
            newChannel.runPendingTasks();
            NativePacketTest.awaitSequence(newSession,2);
            assertEquals(2,newSession.actor.snapshot().processedSequence(),"the new channel's own pre-login capture must not be affected by the old, unrelated channel");
            assertNotEquals(oldSession.key.epoch(),newSession.key.epoch());
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void preLoginJournalOverflowMarksIncompleteInsteadOfGrowingWithoutBoundOrRejectingTraffic()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();
            fireAccept(channel);
            for(int i=0;i<300;i++)channel.writeInbound(handshake(772,"LOGIN"));
            var session=capture.bind(new UUID(7,7),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,256);
            assertEquals(256,session.actor.snapshot().processedSequence(),"replay must stop at the bounded journal capacity rather than growing without limit");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void disconnectBeforeBindNeverInvolvesAPlayerOrUuidAndReportsNoFailure()throws Exception{
        var errors=new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var engine=new PacketEngine(1,1L<<30,errors::add);var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();
            fireAccept(channel);
            channel.writeInbound(handshake(772,"LOGIN"));
            channel.writeInbound(loginHello("Alex",new UUID(2,2)));
            channel.pipeline().fireChannelInactive();
            channel.runPendingTasks();
            assertEquals(0,engine.sessions());
            assertTrue(errors.isEmpty(),"a pre-player disconnect must not be reported as an engine failure: "+errors);
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
}
