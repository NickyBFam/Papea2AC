package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import me.nicolas.papea2AC.platform.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.model.HealthLatch;

class NativeFaultTest {
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static final class Fixture implements AutoCloseable {
        final PacketEngine engine=new PacketEngine(1,1L<<30,e->fail(e));
        final EmbeddedChannel channel=new EmbeddedChannel();
        final PaperCapture adapter=new PaperCapture(engine);
        final PacketEngine.Session session;
        Fixture(ChannelInboundHandlerAdapter downstream){
            channel.pipeline().addLast("encoder",new ChannelOutboundHandlerAdapter());
            channel.pipeline().addLast("packet_handler",downstream);
            session=adapter.attach(new UUID(31,32),channel);channel.runPendingTasks();
        }
        public void close(){adapter.detach(channel,session);channel.runPendingTasks();channel.finishAndReleaseAll();engine.close();}
    }
    @Test void malformedNativePacketCannotBecomeCertifiedAndIsForwarded()throws Exception{
        try(var f=new Fixture(new ChannelInboundHandlerAdapter())){
            Object p=NativePacketTest.movement(Double.NaN);f.channel.writeInbound(p);
            assertSame(p,f.channel.readInbound());assertEquals(HealthLatch.Reason.MALFORMED,f.session.health.get().reason());assertEquals(0,f.session.actor.snapshot().certifiedIngressOrdinal());
        }
    }
    @Test void unexpectedSemanticDegradesAfterActuallyReachingActor()throws Exception{
        try(var f=new Fixture(new ChannelInboundHandlerAdapter())){
            f.channel.writeInbound(new Object());NativePacketTest.awaitSequence(f.session,1);
            long end=System.nanoTime()+2_000_000_000L;while(f.session.health.get().healthy()&&System.nanoTime()<end)Thread.sleep(1);
            assertEquals(HealthLatch.Reason.PIPELINE_UNCOVERED,f.session.health.get().reason());assertEquals(0,f.session.actor.snapshot().certifiedIngressOrdinal());
        }
    }
    @Test void removingCaptureHandlerInvalidatesImmediatelyWithoutAudit()throws Exception{
        try(var f=new Fixture(new ChannelInboundHandlerAdapter())){
            f.channel.pipeline().remove("papea2ac-in-"+f.session.key.epoch());
            assertFalse(f.session.health.get().healthy());assertFalse(f.session.actor.snapshot().certificationAvailable());
        }
    }
    @Test void downstreamExceptionDoesNotManufactureAppliedOutcome()throws Exception{
        try(var f=new Fixture(new ChannelInboundHandlerAdapter(){@Override public void channelRead(ChannelHandlerContext context,Object packet){throw new IllegalStateException("injected downstream failure");}})){
            assertThrows(IllegalStateException.class,()->f.channel.writeInbound(NativePacketTest.movement(1)));
            NativePacketTest.awaitSequence(f.session,1);assertEquals(0,f.session.actor.snapshot().certifiedIngressOrdinal());assertFalse(f.session.actor.snapshot().certificationAvailable());
        }
    }
    @Test void disconnectWithPendingNativeMovementDoesNotCertify()throws Exception{
        try(var f=new Fixture(new ChannelInboundHandlerAdapter())){
            f.channel.writeInbound(NativePacketTest.movement(1));NativePacketTest.awaitSequence(f.session,1);
            f.channel.close();f.channel.runPendingTasks();assertTrue(f.session.ingress.closed());
            assertEquals(0,f.session.actor.snapshot().certifiedIngressOrdinal());assertFalse(f.session.actor.snapshot().certificationAvailable());
        }
    }
}
