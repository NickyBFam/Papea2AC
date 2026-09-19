package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;

/** Hardening pass: stale control/replay events across session replacement, and resource-counter double-release/underflow audit. */
class StaleControlAndResourceAccountingTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    @Test void recordingControlBridgedBeforeReconnectNeverAppliesToTheReplacementSession()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));UUID id=new UUID(60,1);
        var oldSession=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            // Bridge (but do not yet drain) a RECORDING control aimed at the current (soon to be old) session.
            assertTrue(engine.setRecordingEnabled(id,false));
            // Reconnect before that bridged control is ever drained/applied.
            var newSession=engine.connect(id,loop,TransportCapability.observeOnly());
            assertNotEquals(oldSession.key.epoch(),newSession.key.epoch());
            loop.drain(); // now deliver whatever was bridged
            newSession.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->newSession.actor.snapshot().processedSequence()>=1);
            assertTrue(newSession.recordingArmed(),"a control event bridged for the OLD session must never reach or mutate the REPLACEMENT session's recording state");
            assertTrue(oldSession.ingress.closed());
        }finally{
            engine.close();loop.drain();
            await(engine::terminated);engine.supervise();
            var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
            var quota=(ReplayQuota)quotaField.get(engine);
            await(()->quota.used(ReplayQuota.Pool.IO)==0&&quota.used(ReplayQuota.Pool.PROMOTION)==0);
        }
    }
    @Test void checkpointRequestRacingSessionReplacementNeitherCorruptsNorMisdelivers()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));UUID id=new UUID(60,2);
        var oldSession=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            assertTrue(engine.requestReplay(id)); // bridged against the current (old) session
            var newSession=engine.connect(id,loop,TransportCapability.observeOnly());
            loop.drain();
            newSession.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->newSession.actor.snapshot().processedSequence()>=1);
            assertTrue(newSession.health.get().healthy(),"a stale checkpoint request must never corrupt or degrade the replacement session");
        }finally{
            engine.close();loop.drain();
            await(engine::terminated);engine.supervise();
            var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
            var quota=(ReplayQuota)quotaField.get(engine);
            await(()->quota.used(ReplayQuota.Pool.IO)==0&&quota.used(ReplayQuota.Pool.PROMOTION)==0);
        }
    }
    @Test void replayQuotaLeaseDoubleCloseNeverUnderflowsOrDoubleReleases(){
        var quota=new ReplayQuota();
        var lease=quota.reserve(ReplayQuota.Pool.ROLLING,1,1024,1).orElseThrow();
        assertEquals(1024,quota.used(ReplayQuota.Pool.ROLLING));
        lease.close();
        assertEquals(0,quota.used(ReplayQuota.Pool.ROLLING));
        lease.close();lease.close();lease.close(); // adversarial repeated release
        assertEquals(0,quota.used(ReplayQuota.Pool.ROLLING),"a lease must be releasable exactly once; repeated close() must never drive usage negative");
        // A fresh lease for the same owner after the first is fully released must reserve cleanly, proving no residual state.
        var second=quota.reserve(ReplayQuota.Pool.ROLLING,1,1024,1).orElseThrow();
        assertEquals(1024,quota.used(ReplayQuota.Pool.ROLLING));
        second.close();
    }
    @Test void writerLeaseDoubleReleaseAcrossFenceAndCompletionNeverUnderflows()throws Exception{
        var quota=new ReplayQuota();
        var writer=new CaptureWriter(quota,c->{},e->fail(e));
        try{
            assertTrue(writer.submit(1,1024,()->new me.nicolas.papea2ac.schema.TransportCapsule(me.nicolas.papea2ac.schema.TransportCapsule.CURRENT_SCHEMA,TransportReplayDriver.PROFILE,"0".repeat(64),"x",1,false,
                List.of(new me.nicolas.papea2ac.schema.TransportCapsule.Close(1)),true)));
            await(()->writer.written()==1);
            writer.fence(); // fence races an already-completed job's own release; must not double-account
            assertEquals(0,quota.used(ReplayQuota.Pool.IO));
            assertEquals(0,quota.used(ReplayQuota.Pool.PROMOTION));
        }finally{writer.close();await(writer::terminated);}
    }
}
