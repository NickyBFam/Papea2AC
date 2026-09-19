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
import me.nicolas.papea2ac.schema.*;

/** P1-H2: mid-session checkpoint/prefix-coverage semantics, adaptive retention, and writer watchdog. */
class CheckpointReplayTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    static TransportCapsule firstFile(Path dir)throws Exception{
        Path file;try(var files=Files.list(dir)){file=files.min(Comparator.naturalOrder()).orElseThrow();}
        return CapsuleCodec.decode(Files.readAllBytes(file));
    }
    static List<TransportCapsule> allFiles(Path dir)throws Exception{
        var out=new ArrayList<TransportCapsule>();
        try(var files=Files.list(dir)){for(var f:files.sorted().toList())out.add(CapsuleCodec.decode(Files.readAllBytes(f)));}
        return out;
    }
    /** Filenames are random UUIDs, not creation-ordered - identify the pre-resume capsule by shape (no leading Checkpoint) instead of file order. */
    static TransportCapsule withoutLeadingCheckpoint(List<TransportCapsule> capsules){
        return capsules.stream().filter(c->!(c.frames().getFirst() instanceof TransportCapsule.Checkpoint)).findFirst().orElseThrow();
    }
    /** engine.close() deliberately does not join the replay writer thread, and session closure itself submits a
     * final close-time export job asynchronously; await full termination and drained writer quotas so @TempDir
     * cleanup never races a still-open handle or a not-yet-written second file (Windows, unlike POSIX, cannot
     * delete a directory containing either). */
    static void awaitWriterDrained(PacketEngine engine)throws Exception{
        await(engine::terminated);engine.supervise();
        var quotaField=PacketEngine.class.getDeclaredField("replayQuota");quotaField.setAccessible(true);
        var quota=(me.nicolas.papea2ac.replay.ReplayQuota)quotaField.get(engine);
        await(()->quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO)==0&&quota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION)==0);
    }
    @Test void recordingEnabledDuringAnExistingPlaySessionOpensWithACheckpointNotAFalseSessionStart()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        var session=engine.connect(new UUID(1,1),loop,TransportCapability.observeOnly());
        try{
            for(int i=1;i<=3;i++)session.ingress.capture(new Observation.Attempt(i,i,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()==3);
            engine.enableRecording(directory,"0".repeat(64));loop.drain();
            await(session::recordingArmed);
            assertTrue(engine.requestReplay(new UUID(1,1)));loop.drain();
            await(()->{try(var files=Files.list(directory)){return files.findAny().isPresent();}catch(Exception e){throw new RuntimeException(e);}});
            var capsule=firstFile(directory);
            assertInstanceOf(TransportCapsule.Checkpoint.class,capsule.frames().getFirst(),"a mid-session (re)start must document its coherent boundary, never pretend history starts at sequence 1");
            var checkpoint=(TransportCapsule.Checkpoint)capsule.frames().getFirst();
            // 3 direct Attempt captures (sequence 1-3) plus the RECORDING control marker itself (sequence 4,
            // ordered through the same ingress sequence counter) means the checkpoint opens at sequence 5.
            assertEquals(5,checkpoint.startSequence(),"checkpoint must begin exactly where this session's real sequence already was, not at 1");
            assertEquals(1,checkpoint.recordingEpoch());
        }finally{engine.close();loop.drain();awaitWriterDrained(engine);}
    }
    @Test void pauseThenResumeProducesTwoCompleteCapsulesNeitherClaimingTheOthersRange()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));UUID id=new UUID(2,2);
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            // Every ordered event - direct packet capture or bridged control marker - shares one ingress sequence
            // counter, so: Attempt(1)=seq1, RECORDING=false marker=seq2, Attempt(2)=seq3, RECORDING=true marker=seq4
            // (checkpoint opens at seq5), Attempt(3)=seq5, CheckpointMarker=seq6.
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()==1);
            assertTrue(engine.setRecordingEnabled(id,false));loop.drain();
            await(()->!session.recordingArmed());
            session.ingress.capture(new Observation.Attempt(2,2,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()==3);
            assertTrue(engine.setRecordingEnabled(id,true));loop.drain();
            await(session::recordingArmed);
            session.ingress.capture(new Observation.Attempt(3,3,PacketTruthTest.MOVE),System.nanoTime());
            await(()->session.actor.snapshot().processedSequence()==5);
            assertTrue(engine.requestReplay(id));loop.drain();
            await(()->{try{return allFiles(directory).size()>=2;}catch(Exception e){throw new RuntimeException(e);}});
            var files=allFiles(directory);
            var first=withoutLeadingCheckpoint(files);
            assertTrue(first.complete(),"a deliberate pause is a coherent stop, not data loss");
            assertInstanceOf(TransportCapsule.Input.class,first.frames().getFirst());
            var second=files.stream().filter(c->c.frames().getFirst() instanceof TransportCapsule.Checkpoint).findFirst().orElseThrow();
            var checkpoint=(TransportCapsule.Checkpoint)second.frames().getFirst();
            assertEquals(5,checkpoint.startSequence(),"resume must start exactly after the last processed sequence (including the resume marker's own slot), skipping only the deliberately-paused gap");
            assertEquals(2,checkpoint.recordingEpoch(),"second arm of recording for this session is its own recording epoch");
        }finally{engine.close();loop.drain();awaitWriterDrained(engine);}
    }
    @Test void checkpointedCapsuleReplaysThroughTheDriverAndCanStillCertifyContinuingFromTheSeededOrdinal()throws Exception{
        var context=new TransportCapsule.Context(0,"INBOUND","NETWORK",0,0,-1,"PLAY",770,"0".repeat(64));
        var checkpoint=new TransportCapsule.Checkpoint(0,5,0,1,"PLAY",true,"OBSERVE_ONLY",4,4,4,0);
        String digest=PayloadDigest.of(PacketTruthTest.MOVE);
        var attempt=new TransportCapsule.Input(10,5,5,0,context,
            new TransportCapsule.Attempt(5,5,new TransportCapsule.Payload(PayloadCodec.encode(PacketTruthTest.MOVE))));
        var outcome=new TransportCapsule.Input(20,15,6,0,context,
            new TransportCapsule.Outcome(5,5,5,"APPLIED",new TransportCapsule.Payload(PayloadCodec.encode(PacketTruthTest.MOVE)),digest));
        // qualifiedFixture=true: only a correctly-seeded actor (lastIngress=4 from the checkpoint) accepts ingressOrdinal=5 and can certify it.
        var capsule=new TransportCapsule(TransportCapsule.CURRENT_SCHEMA,TransportReplayDriver.PROFILE,"0".repeat(64),"checkpoint-fixture",1,true,List.of(checkpoint,attempt,outcome),true);
        var outputs=new TransportReplayDriver().replay(capsule);
        assertEquals(3,outputs.size(),"one digest per frame, including the checkpoint's own seeded starting state");
        // Negative control: an intentionally wrong lastIngressOrdinal (0 instead of 4) must degrade instead of certifying,
        // proving the checkpoint's seeded value is actually load-bearing for continuity, not ignored.
        var wrongCheckpoint=new TransportCapsule.Checkpoint(0,5,0,1,"PLAY",true,"OBSERVE_ONLY",0,0,0,0);
        var wrongCapsule=new TransportCapsule(TransportCapsule.CURRENT_SCHEMA,TransportReplayDriver.PROFILE,"0".repeat(64),"checkpoint-fixture",2,true,List.of(wrongCheckpoint,attempt,outcome),true);
        var wrongOutputs=new TransportReplayDriver().replay(wrongCapsule);
        assertNotEquals(outputs.get(2),wrongOutputs.get(2),"a mismatched checkpoint baseline must produce a different (degraded) outcome, not the same certified result");
    }
    @Test void prefixIncompleteCarriesAnExplicitMachineReadableReasonNotJustABoolean(){
        var quota=new ReplayQuota();var capture=new LiveCapture(quota,1,1024);
        for(int i=0;i<4096;i++)capture.append(new TransportCapsule.Clock(i));
        assertFalse(capture.complete());assertFalse(capture.incompleteReason().isEmpty());
        var snapshot=capture.snapshot(TransportReplayDriver.PROFILE,"0".repeat(64),"test",false);
        assertEquals(capture.incompleteReason(),snapshot.incompleteReason());
        capture.close();
    }
    @Test void completeCapsuleCarriesNoReasonAndAnIncompleteOneCannotClaimComplete(){
        assertThrows(IllegalArgumentException.class,()->new TransportCapsule(TransportCapsule.CURRENT_SCHEMA,TransportReplayDriver.PROFILE,"0".repeat(64),"x",1,false,List.of(),true,"SOME_REASON"));
    }
    @Test void adaptiveRetentionGrantsMoreShareAtLowLoadThanAtWorstCaseCapacity(){
        var quota=new ReplayQuota();
        var lowLoad=new LiveCapture(quota,1,4);var highLoad=new LiveCapture(quota,2,400);
        try{
            // fair share: lowLoad=min(256KiB,48MiB/4)=256KiB; highLoad=min(256KiB,48MiB/400)=~123KiB.
            // 3000*64=192000 bytes fits under lowLoad's share but overflows highLoad's smaller adaptive share.
            for(int i=0;i<3000;i++)lowLoad.append(new TransportCapsule.Clock(i));
            for(int i=0;i<3000;i++)highLoad.append(new TransportCapsule.Clock(i));
            assertTrue(lowLoad.complete(),"few concurrent sessions must be able to retain more than the worst-case per-session share");
            assertFalse(highLoad.complete(),"many concurrent sessions must still respect the global cap, even if that means less retained history");
        }finally{lowLoad.close();highLoad.close();}
    }
    @Test void noisySessionCannotConsumeAnotherSessionsRollingShare(){
        var quota=new ReplayQuota();
        var noisy=new LiveCapture(quota,1,4);var quiet=new LiveCapture(quota,2,4);
        try{
            for(int i=0;i<10_000&&noisy.complete();i++)noisy.append(new TransportCapsule.Clock(i));
            assertFalse(noisy.complete(),"the noisy session exhausts only its own fair share");
            for(int i=0;i<10;i++)quiet.append(new TransportCapsule.Clock(i));
            assertTrue(quiet.complete(),"a quiet session is never starved by a noisy sibling's rolling-budget pressure");
        }finally{noisy.close();quiet.close();}
    }
    /** Pre-baseline decision (real-client finding): on a real session the session-start rolling capture ends at its first health barrier (EXPIRED within 0.5 s under UNSUPPORTED correlation) with an explicit terminal reason - the fail-closed prefix contract. The operator path stays available: disabling and re-enabling AFTER the barrier opens a checkpoint-seeded capsule that records normally, because the sticky latch never bumps its generation again. */
    @Test void aRecordingReArmedAfterTheFirstBarrierRecordsNormally()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        engine.enableRecording(directory,"0".repeat(64));UUID id=new UUID(3,3);
        var session=engine.connect(id,loop,TransportCapability.observeOnly());
        try{
            // a journal-shaped stale attempt: never certified under UNSUPPORTED correlation -> EXPIRED at the next tick (the real-client first barrier)
            session.ingress.capture(new Observation.Attempt(1,1,PacketTruthTest.MOVE),System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
            session.ingress.capture(new Observation.Tick(1),System.nanoTime());
            await(()->session.actor.snapshot().reason()==me.nicolas.papea2ac.model.HealthLatch.Reason.EXPIRED);
            await(()->!session.recordingComplete());// the session-start capture is terminal at the barrier
            assertTrue(engine.setRecordingEnabled(id,false));loop.drain();await(()->!session.recordingArmed());
            assertTrue(engine.setRecordingEnabled(id,true));loop.drain();await(session::recordingArmed);
            assertTrue(session.recordingComplete(),"a capsule re-armed after the barrier starts complete from its checkpoint");
            for(int i=2;i<=6;i++){session.ingress.capture(new Observation.Outbound(i,new PacketPayload.Velocity(i,new PacketPayload.Position(0,1,0))),System.nanoTime());}
            await(()->session.actor.snapshot().processedSequence()>=9);
            assertTrue(session.recordingComplete(),"EXPIRED re-degrades every tick but never bumps the generation again: no new barrier");
            assertTrue(engine.requestReplay(id));loop.drain();
            // two files: the paused session-start capsule (terminal at the barrier, DEGRADED) and the re-armed one
            await(()->{try(var files=Files.list(directory)){return files.count()==2;}catch(Exception e){throw new RuntimeException(e);}});
            TransportCapsule capsule=null,initial=null;
            try(var files=Files.list(directory)){for(Path f:files.toList()){var c=me.nicolas.papea2ac.replay.CapsuleCodec.decode(Files.readAllBytes(f));if(c.frames().getFirst() instanceof TransportCapsule.Checkpoint)capsule=c;else initial=c;}}
            assertNotNull(initial);assertFalse(initial.prefixComplete());assertEquals("DEGRADED",initial.incompleteReason(),"the session-start capture is terminal at the first barrier - explicit, never silently continued");
            assertNotNull(capsule);
            assertTrue(capsule.prefixComplete(),"complete from its own checkpoint boundary: "+capsule.incompleteReason());
            var checkpoint=assertInstanceOf(TransportCapsule.Checkpoint.class,capsule.frames().getFirst());
            assertEquals(2,checkpoint.recordingEpoch()); assertTrue(checkpoint.startGeneration()>0,"the checkpoint records that it opened in the degraded generation");
            assertTrue(capsule.frames().stream().filter(f->f instanceof TransportCapsule.Input).count()>=5,"the observations after re-arming are in the capsule");
        }finally{engine.close();loop.drain();awaitWriterDrained(engine);}
    }
}
