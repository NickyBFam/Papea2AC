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
import me.nicolas.papea2ac.replay.*;
import me.nicolas.papea2ac.schema.*;

class LiveRecordingTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    static TransportCapsule capsule(){return new TransportCapsule(TransportCapsule.CURRENT_SCHEMA,TransportReplayDriver.PROFILE,"0".repeat(64),"test",1,false,List.of(new TransportCapsule.Close(1)),true);}
    @Test void actualEngineConsumptionAndCloseProduceReplayableDiagnostic()throws Exception{
        var errors=new CopyOnWriteArrayList<Throwable>();var engine=new PacketEngine(1,1L<<30,errors::add);var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));
        var session=engine.connect(new UUID(8,9),loop,TransportCapability.observeOnly());
        try{
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().pending()==1);
            session.close();loop.drain();
            await(()->engine.replayStatus().contains("written=1"));
            Path file;try(var files=Files.list(directory)){file=files.findFirst().orElseThrow();}
            var recorded=CapsuleCodec.decode(Files.readAllBytes(file));
            assertTrue(recorded.complete());assertFalse(recorded.qualifiedFixture());
            assertInstanceOf(TransportCapsule.Input.class,recorded.frames().getFirst());
            assertInstanceOf(TransportCapsule.Close.class,recorded.frames().getLast());
            assertEquals(recorded.frames().size(),new TransportReplayDriver().replay(recorded).size());
            assertTrue(errors.isEmpty(),errors.toString());
        }finally{engine.close();loop.drain();}
    }
    @Test void prefixOverflowIsIncompleteAndReleasesRollingCharge(){
        var quota=new ReplayQuota();var capture=new LiveCapture(quota,1,1024);
        for(int i=0;i<4096;i++)capture.append(new TransportCapsule.Clock(i));
        assertFalse(capture.complete());
        var c=capture.snapshot(TransportReplayDriver.PROFILE,"0".repeat(64),"test",false);
        assertThrows(java.io.IOException.class,()->new TransportReplayDriver().replay(c));
        assertTrue(quota.used(ReplayQuota.Pool.ROLLING)<=48L<<20);
        capture.close();assertEquals(0,quota.used(ReplayQuota.Pool.ROLLING));
    }
    @Test void hungWriterRetainsIoAndPromotionUntilActuallyReturned()throws Exception{
        var quota=new ReplayQuota();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writer=new CaptureWriter(quota,c->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}},e->fail(e));
        try{
            assertTrue(writer.submit(1,1024,LiveRecordingTest::capsule));assertTrue(entered.await(2,TimeUnit.SECONDS));
            writer.close();quota.closeAdmission();assertFalse(writer.terminated());
            assertEquals(8L<<20,quota.used(ReplayQuota.Pool.IO));assertTrue(quota.used(ReplayQuota.Pool.PROMOTION)>0);
            assertFalse(writer.submit(2,1024,LiveRecordingTest::capsule));
        }finally{release.countDown();writer.close();}
        await(writer::terminated);assertEquals(0,quota.used(ReplayQuota.Pool.IO));assertEquals(0,quota.used(ReplayQuota.Pool.PROMOTION));
    }
    @Test void failingWriterReleasesChargesAndReportsFailure()throws Exception{
        var quota=new ReplayQuota();var errors=new CopyOnWriteArrayList<Throwable>();
        var writer=new CaptureWriter(quota,c->{throw new java.io.IOException("injected disk failure");},errors::add);
        assertTrue(writer.submit(1,1024,LiveRecordingTest::capsule));writer.close();await(writer::terminated);
        assertEquals(1,writer.failed());assertEquals(1,errors.size());assertEquals(0,quota.used(ReplayQuota.Pool.PROMOTION));assertEquals(0,quota.used(ReplayQuota.Pool.IO));
    }
    @Test void captureDisableClosesOldEpochAndResumeCreatesFreshEpoch()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();UUID id=new UUID(1,2);
        try{
            var old=engine.connect(id,loop,TransportCapability.observeOnly());engine.setCaptureEnabled(false);
            assertTrue(old.ingress.closed());assertThrows(IllegalStateException.class,()->engine.connect(id,loop,TransportCapability.observeOnly()));
            engine.setCaptureEnabled(true);var next=engine.connect(id,loop,TransportCapability.observeOnly());
            assertTrue(next.key.epoch()>old.key.epoch());assertFalse(next.actor.snapshot().certificationAvailable());
            engine.suspendEffects();assertTrue(engine.effectsSuspended());
        }finally{engine.close();loop.drain();}
    }
    @Test void disabledSyntheticGuardCannotAccidentallyUpgradeMode(){
        var guard=new RuntimeControl(100);guard.restrict(RuntimeControl.Mode.DISABLED);
        for(int i=0;i<4;i++)assertFalse(guard.admitSynthetic(new UUID(0,i),100,i));
        assertEquals(RuntimeControl.Mode.DISABLED,guard.mode());
    }
    @Test void fencedHungShardKeepsSessionLeaseUntilWriterTerminates()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));UUID id=new UUID(4,5);
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
        Object quota=quotaField.get(engine);
        var shardsField=PacketEngine.class.getDeclaredField("shards");shardsField.setAccessible(true);
        Object shard=((List<?>)shardsField.get(engine)).getFirst();
        var threadField=shard.getClass().getDeclaredField("thread");threadField.setAccessible(true);Thread writer=(Thread)threadField.get(shard);
        try{
            synchronized(quota){
                assertTrue(engine.requestReplay(id));loop.drain();await(()->writer.getState()==Thread.State.BLOCKED);
                engine.fenceShard(0);loop.drain();engine.supervise();
                assertTrue(writer.isAlive());assertEquals(258L<<20,engine.reservedBytes());assertEquals(1,engine.sessions());
                assertFalse(session.actor.snapshot().certificationAvailable());
            }
            await(engine::terminated);engine.supervise();assertEquals(256L<<20,engine.reservedBytes());assertEquals(0,engine.sessions());
            // engine.close() below does not join the writer; wait for its actual file write to finish so
            // @TempDir cleanup never races a still-open handle (Windows, unlike POSIX, can't delete those).
            var replayQuota=(me.nicolas.papea2ac.replay.ReplayQuota)quota;
            await(()->replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO)==0
                &&replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION)==0);
        }finally{engine.close();loop.drain();}
    }
    @Test void syntheticGuardFreezesEligiblePopulationForWindow(){
        var guard=new RuntimeControl(100);guard.enable(true,true);
        assertTrue(guard.admitSynthetic(new UUID(0,1),100,0));
        assertTrue(guard.admitSynthetic(new UUID(0,2),1,2_000_000_000L));
        assertTrue(guard.admitSynthetic(new UUID(0,3),1,4_000_000_000L));
    }
    /** P3-F10 (real-client finding): a real session starts with the pre-login journal, whose observations were captured seconds BEFORE the Session (and its recording) existed. The recording's origin must precede them, or frame 1 fails Input validation and the whole capsule is EXPORT_ENCODE_FAILURE. */
    @Test void observationsCapturedBeforeTheSessionExistedStillRecord()throws Exception{
        var errors=new CopyOnWriteArrayList<Throwable>();var engine=new PacketEngine(1,1L<<30,errors::add);var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));
        var session=engine.connect(new UUID(8,10),loop,TransportCapability.observeOnly());
        try{
            long journaled=System.nanoTime()-TimeUnit.SECONDS.toNanos(5);// the early-transport journal's capture time
            session.ingress.capture(new Observation.Tick(1),journaled);// journal-shaped: captured long before this recording's construction
            session.ingress.capture(new Observation.Outbound(1,new PacketPayload.Velocity(7,new PacketPayload.Position(0,1,0))),journaled+1000);
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()==3);
            session.close();loop.drain();
            await(()->engine.replayStatus().contains("written=1"));
            Path file;try(var files=Files.list(directory)){file=files.findFirst().orElseThrow();}
            var recorded=CapsuleCodec.decode(Files.readAllBytes(file));
            assertFalse(recorded.incompleteReason().startsWith("EXPORT_ENCODE_FAILURE"),"recording must not be voided by pre-session capture times: "+recorded.incompleteReason());
            var first=assertInstanceOf(TransportCapsule.Input.class,recorded.frames().getFirst());
            assertEquals(1,first.sequence());assertTrue(first.capturedNanos()>=0&&first.capturedNanos()<=first.elapsedNanos());
            assertEquals(3,recorded.frames().stream().filter(x->x instanceof TransportCapsule.Input).count());
            assertTrue(errors.isEmpty(),errors.toString());
        }finally{engine.close();loop.drain();}
    }
    /** P3-F13: the platform must be able to drain the shards (final exports, actor/world release) BEFORE the plugin classloader is closed; awaitTermination is that bounded barrier and the session's world charge is released by the time it returns. */
    @Test void awaitTerminationDrainsClosingSessionsAndReleasesTheirWorlds()throws Exception{
        var errors=new CopyOnWriteArrayList<Throwable>();var engine=new PacketEngine(1,1L<<30,errors::add);var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));
        var session=engine.connect(new UUID(8,11),loop,TransportCapability.observeOnly());
        session.ingress.capture(new Observation.Outbound(1,ObserverWorldIntegrationTest.JOIN),System.nanoTime());
        session.ingress.capture(new Observation.Outbound(2,ObserverWorldIntegrationTest.wireBaseline(0,0)),System.nanoTime());
        ObserverWorldIntegrationTest.await(()->session.world.snapshot().chunkCount()==1);
        assertTrue(engine.worldBudgetSnapshot().sharedBytes()>0);
        engine.close();loop.drain();
        long t0=System.nanoTime();assertTrue(engine.awaitTermination(2000),"shards drain within the bound");assertTrue(System.nanoTime()-t0<TimeUnit.SECONDS.toNanos(2));
        assertTrue(engine.terminated());
        assertEquals(0,engine.worldBudgetSnapshot().observerWorlds());assertEquals(0,engine.worldBudgetSnapshot().sharedBytes(),"the session's world was released during the drain, not left to the classloader");
        assertTrue(errors.isEmpty(),errors.toString());
    }
}
