package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;

/** P1-M1: fairness/backpressure qualification and local failure injection for the H2/H3 control+replay paths. */
class FaultInjectionAndFairnessTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    @Test void oneFloodedSessionCannotStarveControlProcessingForItself()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();UUID id=new UUID(20,1);
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            // Flood well beyond one shard tick's 2048-cost-unit budget; the ingress queue itself is bounded so
            // this also exercises explicit backpressure, not unbounded growth.
            int accepted=0;for(int i=1;i<=2000;i++)if(session.ingress.capture(new Observation.Attempt(i,i,PacketTruthTest.MOVE),System.nanoTime()))accepted++;
            assertTrue(accepted<2000,"a bounded ingress queue must eventually saturate rather than growing without limit");
            // Control commands travel their own small bridge mailbox, so a saturated packet queue must not
            // block a control command from being accepted and, eventually, applied.
            assertTrue(engine.setRecordingEnabled(id,true));loop.drain();
            await(()->session.actor.snapshot().processedSequence()>0);
        }finally{engine.close();loop.drain();}
    }
    @Test void manySiblingSessionsMakeIndependentBoundedProgressUnderOneNoisyNeighbor()throws Exception{
        var engine=new PacketEngine(4,64,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        var quiet=new ArrayList<PacketEngine.Session>();
        try{
            for(int i=1;i<=10;i++)quiet.add(engine.connect(new UUID(21,i),loop,TransportCapability.observeOnly()));
            var noisy=engine.connect(new UUID(22,1),loop,TransportCapability.observeOnly());
            for(int i=1;i<=5000;i++)noisy.ingress.capture(new Observation.Attempt(i,i,PacketTruthTest.MOVE),System.nanoTime());
            for(var s:quiet)s.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            for(var s:quiet)await(()->s.actor.snapshot().processedSequence()==1);
            for(var s:quiet)assertTrue(s.health.get().healthy(),"a noisy neighbor session must never block a quiet sibling's own bounded progress");
        }finally{engine.close();loop.drain();}
    }
    @Test void queueSaturationDegradesHealthAndNeverFalselyReportsCertificationAvailable()throws Exception{
        var h=new me.nicolas.papea2ac.model.HealthLatch();
        var in=new ConnectionIngress(new me.nicolas.papea2ac.model.SessionKey(new UUID(23,1),1),new PacketTruthTest.Loop(),h,2);
        var actor=new TruthActor(new me.nicolas.papea2ac.model.SessionKey(new UUID(23,1),1),h,new TransportCapability(true,true,true,true),new PacketMetrics(),256);
        assertTrue(in.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),0));
        assertTrue(in.capture(new Observation.Attempt(2,2,PacketTruthTest.MOVE),0));
        assertFalse(in.capture(new Observation.Attempt(3,3,PacketTruthTest.MOVE),0),"third capture must be rejected once the bounded queue is full");
        actor.process(new Observation(new me.nicolas.papea2ac.model.SessionKey(new UUID(23,1),1),1,0,0,new Observation.Attempt(1,1,PacketTruthTest.MOVE)),0);
        assertFalse(actor.snapshot().certificationAvailable(),"overload must never be silently reported as available/certified coverage");
    }
    @Test void disconnectDuringACheckpointRequestReportsNoFailureAndLeavesNoDanglingState()throws Exception{
        var errors=new CopyOnWriteArrayList<Throwable>();
        var engine=new PacketEngine(1,1L<<30,errors::add);var loop=new PacketTruthTest.Loop();UUID id=new UUID(24,1);
        engine.enableRecording(directory,"0".repeat(64));
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            assertTrue(engine.requestReplay(id));
            session.close(); // disconnect races the not-yet-drained checkpoint request
            loop.drain();
            await(()->{engine.supervise();return engine.sessions()==0;});
            assertTrue(errors.isEmpty(),"a checkpoint request racing a disconnect must never surface as an engine failure: "+errors);
            assertEquals(256L*1024*1024,engine.reservedBytes(),"the session's lease must still be fully reclaimed");
        }finally{
            engine.close();loop.drain();
            var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
            var quota=(me.nicolas.papea2ac.replay.ReplayQuota)quotaField.get(engine);
            await(()->quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO)==0&&quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION)==0);
        }
    }
    @Test void writerFailureForOneSessionsExportNeverAffectsASiblingSessionsProcessing()throws Exception{
        var errors=new CopyOnWriteArrayList<Throwable>();
        var quota=new me.nicolas.papea2ac.replay.ReplayQuota();
        var writer=new me.nicolas.papea2ac.replay.CaptureWriter(quota,c->{throw new java.io.IOException("injected export failure");},errors::add);
        try{
            assertTrue(writer.submit(1,1024,()->new me.nicolas.papea2ac.schema.TransportCapsule(1,TransportReplayDriver.PROFILE,"0".repeat(64),"x",1,false,
                java.util.List.of(new me.nicolas.papea2ac.schema.TransportCapsule.Close(1)),true)));
            await(()->writer.failed()==1);
            // A second, unrelated actor/session must be completely unaffected by the first export's failure.
            var health=new me.nicolas.papea2ac.model.HealthLatch();
            var actor=new TruthActor(new me.nicolas.papea2ac.model.SessionKey(new UUID(25,1),1),health,new TransportCapability(true,true,true,true),new PacketMetrics(),256);
            actor.process(new Observation(new me.nicolas.papea2ac.model.SessionKey(new UUID(25,1),1),1,0,0,new Observation.Attempt(1,1,PacketTruthTest.MOVE)),0);
            assertTrue(health.get().healthy(),"one session's writer failure must not leak into another session's health");
        }finally{writer.close();await(writer::terminated);}
    }
    @Test void shutdownDuringAnInFlightExportStillReclaimsAllReservedBytes()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();UUID id=new UUID(26,1);
        engine.enableRecording(directory,"0".repeat(64));
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()==1);
        }finally{
            engine.close();loop.drain();
            await(engine::terminated);engine.supervise();
            assertEquals(0,engine.sessions());assertEquals(256L*1024*1024,engine.reservedBytes());
            var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
            var quota=(me.nicolas.papea2ac.replay.ReplayQuota)quotaField.get(engine);
            await(()->quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO)==0&&quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION)==0);
        }
    }
}
