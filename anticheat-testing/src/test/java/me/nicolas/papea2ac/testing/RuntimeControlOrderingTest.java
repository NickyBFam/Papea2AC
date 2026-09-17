package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;

/** P1-H3: deterministic, ordered runtime-control application; guard/budget records; effects suspension lifecycle. */
class RuntimeControlOrderingTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    @Test void packetControlPacketControlPacketCheckpointPacketIsAppliedInExactOrderNotThreadTimingLuck()throws Exception{
        // Packet A / Capture OFF / Packet B / Capture ON / Packet C / Checkpoint request / Packet D
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();UUID id=new UUID(9,9);
        engine.enableRecording(directory,"0".repeat(64));
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime()); // A: seq1
            assertTrue(engine.setRecordingEnabled(id,false));loop.drain(); // seq2, applied before B
            session.ingress.capture(new Observation.Attempt(2,2,PacketTruthTest.MOVE),System.nanoTime()); // B: seq3
            assertTrue(engine.setRecordingEnabled(id,true));loop.drain(); // seq4, applied before C
            session.ingress.capture(new Observation.Attempt(3,3,PacketTruthTest.MOVE),System.nanoTime()); // C: seq5
            assertTrue(engine.requestReplay(id));loop.drain(); // seq6, applied before D
            session.ingress.capture(new Observation.Attempt(4,4,PacketTruthTest.MOVE),System.nanoTime()); // D: seq7
            await(()->session.actor.snapshot().processedSequence()==7);
            // The actor advanced through every ordered slot exactly once, in exactly this order - not merely
            // "eventually consistent": a hole or reorder would have latched PIPELINE_UNCOVERED.
            assertTrue(session.health.get().healthy(),"a correctly ordered mix of packets and controls must never look like a sequence gap");
        }finally{
            engine.close();loop.drain();
            await(engine::terminated);engine.supervise();
            var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
            var quota=(me.nicolas.papea2ac.replay.ReplayQuota)quotaField.get(engine);
            await(()->quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO)==0&&quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION)==0);
        }
    }
    @Test void concurrentCommandAndPacketSubmissionStillProducesADeterministicSingleOrderPerRun()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();UUID id=new UUID(9,10);
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            var pool=Executors.newFixedThreadPool(2);
            try{
                var packets=pool.submit(()->{for(int i=1;i<=50;i++)session.ingress.bridge(new Observation.Attempt(i,i,PacketTruthTest.MOVE));});
                var controls=pool.submit(()->{for(int i=0;i<10;i++){engine.setRecordingEnabled(id,i%2==0);}});
                packets.get(2,TimeUnit.SECONDS);controls.get(2,TimeUnit.SECONDS);
            }finally{pool.shutdown();}
            loop.drain();
            await(()->session.actor.snapshot().processedSequence()>=50);
            // Whatever the actual interleaving was, it was resolved through the one ingress sequence counter:
            // the actor never saw a gap or duplicate, proven by staying healthy across every processed observation.
            assertTrue(session.health.get().healthy(),"concurrent packet+control submission must still resolve to one coherent order, not a race the actor detects as a gap");
        }finally{engine.close();loop.drain();}
    }
    @Test void guardStatusIsFailClosedForAnUnadmittedPlayerAndNeverClaimsProductionQualification()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));
        try{
            var missing=engine.guardStatus(new UUID(0,0));
            assertFalse(missing.captureCapability());assertFalse(missing.certificationCapability());
            assertFalse(missing.overloaded());assertFalse(missing.replayArmed());assertFalse(missing.replayDurable());
            assertFalse(missing.productionQualified(),"Phase 1 has no production certification path, structurally, for any session");
            var loop=new PacketTruthTest.Loop();UUID id=new UUID(11,11);
            var session=engine.connect(id,loop,new TransportCapability(true,true,true,true));
            var admitted=engine.guardStatus(id);
            assertTrue(admitted.captureCapability());assertFalse(admitted.productionQualified());
        }finally{engine.close();}
    }
    @Test void budgetStatusExplainsConfiguredAndEffectiveLimitsNotJustCurrentUsage()throws Exception{
        var engine=new PacketEngine(1,4,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        try{
            engine.connect(new UUID(0,1),loop,TransportCapability.observeOnly());
            var budget=engine.budgetStatus();
            assertEquals(4,budget.configuredCapacity());assertEquals(1,budget.activeSessions());
            assertTrue(budget.globalBudgetBytes()>0);assertTrue(budget.reservedBytes()>0);
            assertEquals(0,budget.replayRollingBytesUsed(),"no writer configured, so no rolling reservation exists yet");
            assertEquals(CaptureWriterQueueCapacity(),budget.writerQueueCapacity());
        }finally{engine.close();loop.drain();}
    }
    private static int CaptureWriterQueueCapacity(){return me.nicolas.papea2ac.replay.CaptureWriter.QUEUE_CAPACITY;}
    @Test void monitoringSnapshotIsBoundedAndNeverExposesPerPlayerIdentity()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        try{
            for(int i=1;i<=5;i++)engine.connect(new UUID(0,i),loop,TransportCapability.observeOnly());
            var snapshot=engine.monitoringSnapshot();
            assertEquals(5,snapshot.activeSessions());
            assertTrue(snapshot.captureEnabled());
            assertNotNull(snapshot.effectsMode());
            assertEquals("DISABLED",snapshot.writerHealth(),"no writer configured yet");
            for(var field:snapshot.getClass().getRecordComponents())
                assertFalse(field.getType()==UUID.class||field.getType()==Map.class,"monitoring snapshot must stay aggregate-only, field "+field.getName());
        }finally{engine.close();loop.drain();}
    }
    @Test void automatedEffectsSuspensionSuspendsAndResumesWithoutEverReachingEnforce()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();UUID id=new UUID(12,12);
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            assertTrue(engine.effectsSuspended(),"startup default is suspended (never ENFORCE) until explicitly qualified, which this engine never does");
            engine.suspendEffects();loop.drain();assertTrue(engine.effectsSuspended());
            engine.resumeEffects();loop.drain();assertTrue(engine.effectsSuspended(),"resume returns to the safe OBSERVE_ONLY default, still never ENFORCE");
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()>=3,"suspend+resume markers plus the packet must all land in this session's ordered stream");
        }finally{engine.close();loop.drain();}
    }
    static void await(java.util.function.BooleanSupplier condition,String message)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean(),message);
    }
}
