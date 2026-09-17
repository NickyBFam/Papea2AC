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

/**
 * P1-M1: a practical local long-duration synthetic qualification for retention/resource stability - not a
 * production-scale claim, but enough repeated connect/disconnect, capture on/off, checkpoint/export, and
 * writer-completion/failure cycles under budget pressure to detect unbounded growth or a lease/queue leak.
 */
class LongRunRetentionTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    @Test void repeatedConnectDisconnectCaptureToggleAndCheckpointCyclesReturnToBaselineEveryTime()throws Exception{
        var errors=new CopyOnWriteArrayList<Throwable>();
        var engine=new PacketEngine(2,8,1L<<30,errors::add);var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));
        var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
        var quota=(me.nicolas.papea2ac.replay.ReplayQuota)quotaField.get(engine);
        long baselineReserved=engine.reservedBytes();
        try{
            for(int cycle=0;cycle<300;cycle++){
                UUID id=new UUID(30,cycle);
                var session=engine.connect(id,loop,TransportCapability.observeOnly());
                session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
                await(()->session.actor.snapshot().processedSequence()>=1);
                engine.setRecordingEnabled(id,false);loop.drain();
                engine.setRecordingEnabled(id,true);loop.drain();
                engine.requestReplay(id);loop.drain();
                session.close();loop.drain();
                await(()->{engine.supervise();return engine.sessions()==0;});
                // Every completed cycle must return to the exact same baseline - no accumulation across cycles.
                assertEquals(baselineReserved,engine.reservedBytes(),"session lease must be fully reclaimed after cycle "+cycle);
            }
            // 300 cycles legitimately exceed the diagnostic-file quota (64 files) partway through; that specific,
            // by-design rejection is expected budget pressure, not an internal engine failure - anything else is.
            for(var e:errors)assertTrue(e instanceof java.io.IOException io&&io.getMessage()!=null&&io.getMessage().contains("diagnostic file quota"),
                "only the known diagnostic-file-quota rejection is expected budget pressure, not: "+e);
            assertTrue(quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.ROLLING)==0,"no rolling-pool leak across 300 cycles");
        }finally{
            engine.close();loop.drain();
            await(()->quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO)==0&&quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION)==0);
        }
    }
    @Test void repeatedWriterFailureCyclesNeverAccumulatePendingWriterQueueOrLeases()throws Exception{
        var quota=new me.nicolas.papea2ac.replay.ReplayQuota();
        var writer=new me.nicolas.papea2ac.replay.CaptureWriter(quota,c->{throw new java.io.IOException("injected cycle failure");},e->{});
        try{
            int accepted=0;
            for(int i=1;i<=200;i++){
                boolean submitted=writer.submit(i,1024,()->new me.nicolas.papea2ac.schema.TransportCapsule(1,TransportReplayDriver.PROFILE,"0".repeat(64),"x",1,false,
                    java.util.List.of(new me.nicolas.papea2ac.schema.TransportCapsule.Close(1)),true));
                if(submitted){accepted++;final int expected=accepted;await(()->writer.failed()>=expected);}
            }
            assertTrue(accepted>0);
            assertEquals(0,quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO),"no lease leak across "+accepted+" failing write cycles");
            assertEquals(0,quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION));
        }finally{writer.close();await(writer::terminated);}
    }
    @Test void budgetPressureAcrossManyShortLivedSessionsStillReturnsToBaseline()throws Exception{
        var engine=new PacketEngine(1,4,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        long baseline=engine.reservedBytes();
        try{
            for(int i=1;i<=100;i++){
                var session=engine.connect(new UUID(31,i),loop,TransportCapability.observeOnly());
                session.close();loop.drain();
                await(()->{engine.supervise();return engine.sessions()==0;});
            }
            assertEquals(baseline,engine.reservedBytes(),"admission-limited churn must never leave stale reservations behind");
        }finally{engine.close();loop.drain();}
    }
}
