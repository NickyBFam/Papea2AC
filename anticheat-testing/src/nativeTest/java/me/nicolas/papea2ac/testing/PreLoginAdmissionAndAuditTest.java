package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import me.nicolas.papea2AC.platform.PaperCapture;
import me.nicolas.papea2ac.core.PacketEngine;
import me.nicolas.papea2ac.model.HealthLatch;

/**
 * Hardening pass: global pre-login admission bound (Reviewer finding 1), pipeline-audit topology
 * re-derivation for the early-hook architecture (Reviewer finding 2), and the shutdown-race guard.
 */
class PreLoginAdmissionAndAuditTest {
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
    static void setField(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @SuppressWarnings("unchecked")
    static <T> T getField(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(target);
    }
    static int preLoginCount(PaperCapture capture)throws Exception{return ((AtomicInteger)getField(capture,"preLoginCount")).get();}
    static long preLoginBytes(PaperCapture capture)throws Exception{return ((AtomicLong)getField(capture,"preLoginAggregateBytes")).get();}
    static ConcurrentMap<Channel,Object> preLoginMap(PaperCapture capture)throws Exception{return getField(capture,"preLogin");}

    @Test void globalConnectionCountAdmissionRejectsBeyondTheCapWithoutBlockingOrLeaking()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            setField(capture,"maxPreLoginConnections",3);
            assertTrue(capture.registerEarlyHook());
            var admitted=new ArrayList<EmbeddedChannel>();
            for(int i=0;i<3;i++){var c=acceptedChannel();fireAccept(c);admitted.add(c);}
            for(var c:admitted)assertTrue(c.pipeline().names().stream().anyMatch(n->n.startsWith("papea2ac-in-t")),"the first 3 connections must be admitted normally");
            assertEquals(3,preLoginCount(capture));
            // A 4th connection, beyond the cap: must not block, must not install any early-hook state, must not throw.
            var overCap=acceptedChannel();
            int before=overCap.pipeline().names().size();
            fireAccept(overCap);
            assertEquals(before,overCap.pipeline().names().size(),"a connection beyond the global cap must get zero early-hook handlers installed");
            assertEquals(3,preLoginCount(capture),"the rejected connection must not be counted");
            assertFalse(preLoginMap(capture).containsKey(overCap),"a rejected connection must not leak a PreLoginTransport entry");
            // It must still become a fully functional session via the attachFresh fallback once the player joins.
            overCap.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());
            var session=capture.bind(new UUID(40,1),overCap);
            overCap.runPendingTasks();
            overCap.writeInbound(handshake(772));
            NativePacketTest.awaitSequence(session,1);
            assertEquals(1,session.actor.snapshot().processedSequence(),"a connection denied pre-login admission must still capture normally once bound");
            // Closing an admitted connection frees a slot for a new one.
            admitted.get(0).pipeline().fireChannelInactive();admitted.get(0).runPendingTasks();
            assertEquals(2,preLoginCount(capture),"closing a connection must return its global admission slot");
            var reusesSlot=acceptedChannel();fireAccept(reusesSlot);
            assertTrue(reusesSlot.pipeline().names().stream().anyMatch(n->n.startsWith("papea2ac-in-t")),"a freed slot must be reusable by a later connection");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void globalAggregateByteBoundTruncatesAndSurfacesAsDegradedHealthOnBind()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            setField(capture,"maxPreLoginAggregateBytes",4096L);
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            for(int i=0;i<200;i++)channel.writeInbound(handshake(772));
            // Admission is check-then-add (matching the existing per-connection bound's own style), so the one
            // entry that crosses the threshold may overshoot it slightly; the real property under test is that
            // it stays in the same ballpark rather than growing toward 200 unbounded entries' worth of bytes.
            assertTrue(preLoginBytes(capture)<4096L*2,"the global aggregate must stay bounded near its configured cap, not grow toward the unbounded 200-entry total");
            var session=capture.bind(new UUID(41,1),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,1);
            await(()->!session.health.get().healthy());
            assertEquals(HealthLatch.Reason.OVERLOADED,session.health.get().reason(),
                "a prefix truncated by global pressure must degrade health, never look like clean coverage");
            assertEquals(0,preLoginBytes(capture),"bind() must release this connection's global byte share");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void manyEmptyConnectionsNeverAccumulateUnboundedAggregateState()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            setField(capture,"maxPreLoginConnections",50);
            assertTrue(capture.registerEarlyHook());
            var channels=new ArrayList<EmbeddedChannel>();
            for(int i=0;i<50;i++){var c=acceptedChannel();fireAccept(c);channels.add(c);}
            assertEquals(50,preLoginCount(capture));
            assertEquals(0,preLoginBytes(capture),"connections that never send anything must not charge any bytes");
            for(var c:channels){c.pipeline().fireChannelInactive();c.runPendingTasks();}
            assertEquals(0,preLoginCount(capture),"closing every connection must return all global usage");
            assertEquals(0,preLoginBytes(capture));
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void serverShutdownReturnsGlobalUsageAndNewAcceptDuringShutdownInstallsNothing()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        assertTrue(capture.registerEarlyHook());
        var channel=acceptedChannel();fireAccept(channel);
        channel.writeInbound(handshake(772));
        assertEquals(1,preLoginCount(capture));
        capture.unregisterEarlyHook();
        // A connection accepted after shutdown began must install nothing: the early hook itself is removed
        // AND the closing flag guards against an already-in-flight callback racing the removal.
        var lateChannel=acceptedChannel();
        int before=lateChannel.pipeline().names().size();
        fireAccept(lateChannel);
        assertEquals(before,lateChannel.pipeline().names().size(),"a connection accepted after unregisterEarlyHook must never install permanent state");
        assertFalse(preLoginMap(capture).containsKey(lateChannel));
        engine.close();
    }
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }

    // ---- Pipeline audit topology re-derivation ----

    @Test void earlyHookTopologyAuditPassesWhenIntactAndDegradesWhenInboundHandlerRemoved()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            channel.writeInbound(handshake(772));
            var session=capture.bind(new UUID(42,1),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,1);
            capture.audit(session);channel.runPendingTasks();
            assertTrue(session.health.get().healthy(),"an intact early-hook topology must audit clean");
            String inbound=channel.pipeline().names().stream().filter(n->n.startsWith("papea2ac-in-t")).findFirst().orElseThrow();
            channel.pipeline().remove(inbound);
            capture.audit(session);channel.runPendingTasks();
            assertFalse(session.health.get().healthy(),"removing the owned inbound handler must degrade health");
            assertEquals(HealthLatch.Reason.PIPELINE_UNCOVERED,session.health.get().reason());
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void earlyHookTopologyAuditDegradesWhenOutboundHandlerRemoved()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            channel.writeInbound(handshake(772));
            var session=capture.bind(new UUID(42,2),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,1);
            String outbound=channel.pipeline().names().stream().filter(n->n.startsWith("papea2ac-out-t")).findFirst().orElseThrow();
            channel.pipeline().remove(outbound);
            capture.audit(session);channel.runPendingTasks();
            assertFalse(session.health.get().healthy(),"removing the owned outbound handler must degrade health");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void earlyHookTopologyAuditDegradesWhenHandlerMovedToInvalidPosition()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            channel.writeInbound(handshake(772));
            var session=capture.bind(new UUID(42,3),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,1);
            String inbound=channel.pipeline().names().stream().filter(n->n.startsWith("papea2ac-in-t")).findFirst().orElseThrow();
            // A real ChannelHandler instance is not @Sharable and cannot be removed-then-re-added; a fresh
            // handler under the SAME name at the wrong position is an equivalent probe, since the audit's
            // adjacency check is name/position-based, not instance-based, for the early-hook topology.
            channel.pipeline().remove(inbound);
            channel.pipeline().addFirst(inbound,new ChannelInboundHandlerAdapter());
            capture.audit(session);channel.runPendingTasks();
            assertFalse(session.health.get().healthy(),"a handler present but in the wrong position must still degrade health - position matters, not mere presence");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void earlyHookTopologyAuditDegradesWhenAnchorHandlerMissing()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            channel.writeInbound(handshake(772));
            var session=capture.bind(new UUID(42,4),channel);
            channel.runPendingTasks();
            NativePacketTest.awaitSequence(session,1);
            channel.pipeline().remove("hackfix");
            capture.audit(session);channel.runPendingTasks();
            assertFalse(session.health.get().healthy(),"removing the anchor handler itself (hackfix) must degrade health even though our own handlers are untouched");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void fallbackTopologyAuditDetectsEncoderReplacementEvenThoughHandlerNamesArePresent()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            // Force the attachFresh (no early hook) fallback path.
            var channel=acceptedChannel();
            channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());
            var session=capture.bind(new UUID(42,5),channel);
            channel.runPendingTasks();
            channel.writeInbound(handshake(772));
            NativePacketTest.awaitSequence(session,1);
            capture.audit(session);channel.runPendingTasks();
            assertTrue(session.health.get().healthy(),"an intact fallback topology must audit clean");
            // Replace "encoder" with a different instance under the same name: names/adjacency look identical,
            // but the specific instance changed - exactly the case the fallback path's identity tracking exists for.
            channel.pipeline().replace("encoder","encoder",new ChannelOutboundHandlerAdapter());
            capture.audit(session);channel.runPendingTasks();
            assertFalse(session.health.get().healthy(),"a silently replaced encoder instance must be caught, not just its name/position");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void duplicateOwnedHandlerNameIsRejectedByNettyNotSilentlyIgnored()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        try{
            assertTrue(capture.registerEarlyHook());
            var channel=acceptedChannel();fireAccept(channel);
            String inbound=channel.pipeline().names().stream().filter(n->n.startsWith("papea2ac-in-t")).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class,()->channel.pipeline().addLast(inbound,new ChannelInboundHandlerAdapter()),
                "Netty itself must refuse a duplicate handler name; this is not something the audit needs to separately guard");
        }finally{capture.unregisterEarlyHook();engine.close();}
    }
    @Test void pluginDisableRemovesEarlyHookHandlersViaDetach()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var capture=new PaperCapture(engine);
        assertTrue(capture.registerEarlyHook());
        var channel=acceptedChannel();fireAccept(channel);
        channel.writeInbound(handshake(772));
        var session=capture.bind(new UUID(42,6),channel);
        channel.runPendingTasks();
        NativePacketTest.awaitSequence(session,1);
        capture.detach(channel,session);
        channel.runPendingTasks();
        assertTrue(channel.pipeline().names().stream().noneMatch(n->n.startsWith("papea2ac-in-t")||n.startsWith("papea2ac-out-t")),
            "detach (used by plugin disable) must remove the owned handlers from a still-open channel");
        capture.unregisterEarlyHook();engine.close();
    }
}
