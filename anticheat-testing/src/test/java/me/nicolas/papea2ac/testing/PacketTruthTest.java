package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.io.IOException;
import java.security.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.version.*;
import me.nicolas.papea2ac.schema.*;
import me.nicolas.papea2ac.replay.*;
class PacketTruthTest {
    static final SessionKey KEY=new SessionKey(new UUID(1,2),1);
    static final TransportCapability QUALIFIED=new TransportCapability(true,true,true,true);
    static final PacketPayload.Movement MOVE=new PacketPayload.Movement(new PacketPayload.Position(1,2,3),null,true,false);
    static final class Fixture {
        final HealthLatch health=new HealthLatch();final PacketMetrics metrics=new PacketMetrics();
        final TruthActor actor;long sequence;
        Fixture(){this(QUALIFIED,256);}
        Fixture(TransportCapability capability,int capacity){actor=new TruthActor(KEY,health,capability,metrics,capacity);}
        void attempt(long id){send(new Observation.Attempt(id,id,MOVE),0);}
        void outcome(long id,Observation.Result result){send(new Observation.Outcome(id,id,id,result,
            result==Observation.Result.APPLIED||result==Observation.Result.TRANSFORMED_APPLIED?MOVE:null,PayloadDigest.of(MOVE)),1);}
        void send(Observation.Event e,long nanos){actor.process(new Observation(KEY,++sequence,nanos,0,e),nanos);}
    }
    @Test void captureIsNotCertification(){var f=new Fixture();f.attempt(1);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());assertEquals(1,f.actor.snapshot().pending());}
    @Test void acceptedMatchingOutcomePromotes(){var f=new Fixture();f.attempt(1);f.outcome(1,Observation.Result.APPLIED);assertEquals(1,f.actor.snapshot().certifiedIngressOrdinal());assertEquals(0,f.actor.snapshot().pending());}
    @Test void cancelledPacketDoesNotAdvanceCertifiedFrontier(){
        var f=new Fixture();f.attempt(1);f.attempt(2);f.outcome(1,Observation.Result.REJECTED);f.outcome(2,Observation.Result.APPLIED);
        f.actor.tick(1_000_000_000L);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal(),"CANCELLED PACKET DOES NOT ADVANCE CERTIFIED FRONTIER");
        assertFalse(f.actor.snapshot().certificationAvailable());assertEquals(0,f.actor.snapshot().pending());
    }
    @Test void mutationPreservesFinalServerProjectionButNeverCertifiesOriginal(){
        var f=new Fixture();f.attempt(1);var changed=new PacketPayload.Movement(new PacketPayload.Position(9,2,3),null,true,false);
        f.send(new Observation.Outcome(1,1,2,Observation.Result.TRANSFORMED_APPLIED,changed,PayloadDigest.of(MOVE)),1);
        assertEquals(changed,f.actor.snapshot().acceptedServerProjection());assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());assertFalse(f.actor.snapshot().certificationAvailable());
    }
    @Test void changedPayloadLabeledAppliedIsRejected(){var f=new Fixture();f.attempt(1);f.send(new Observation.Outcome(1,1,1,Observation.Result.APPLIED,new PacketPayload.Control(PacketPayload.ControlKind.INPUT,9),PayloadDigest.of(MOVE)),1);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());}
    @ParameterizedTest @EnumSource(value=Observation.Result.class,names={"UNKNOWN","FAILED","REJECTED"})
    void uncertaintyNeverAccepts(Observation.Result result){var f=new Fixture();f.attempt(1);f.outcome(1,result);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());}
    @Test void timeoutNeverAccepts(){var f=new Fixture();f.attempt(1);f.actor.tick(500_000_000L);f.outcome(1,Observation.Result.APPLIED);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());assertEquals(HealthLatch.Reason.EXPIRED,f.actor.snapshot().reason());}
    @Test void laterOutcomeDoesNotOvertakePrefix(){var f=new Fixture();f.attempt(1);f.attempt(2);f.outcome(2,Observation.Result.APPLIED);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());f.outcome(1,Observation.Result.APPLIED);assertEquals(2,f.actor.snapshot().certifiedIngressOrdinal());assertEquals(4,f.actor.snapshot().promotingSequence());}
    @Test void staleEpochCannotMutateActor(){var f=new Fixture();f.attempt(1);f.actor.process(new Observation(new SessionKey(KEY.player(),2),2,0,0,new Observation.Tick(1)),0);assertEquals(1,f.actor.snapshot().processedSequence());assertEquals(1,f.metrics.count(PacketMetrics.Kind.STALE));}
    @Test void closeClearsProvisional(){var f=new Fixture();f.attempt(1);f.actor.close();f.outcome(1,Observation.Result.APPLIED);assertEquals(0,f.actor.snapshot().pending());assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());}
    @Test void boundedJournalOverflowInvalidatesDescendants(){var f=new Fixture(QUALIFIED,2);f.attempt(1);f.attempt(2);f.attempt(3);assertEquals(0,f.actor.snapshot().pending());assertEquals(HealthLatch.Reason.OVERLOADED,f.actor.snapshot().reason());}
    @Test void captureOnlyCannotCertify(){var f=new Fixture(TransportCapability.observeOnly(),256);f.attempt(1);f.outcome(1,Observation.Result.APPLIED);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());}
    @Test void downgradeCannotBeUndoneByLateOutcome(){var f=new Fixture();f.attempt(1);f.actor.downgrade();f.outcome(1,Observation.Result.APPLIED);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());}
    @Test void forgedDigestCannotCertify(){var f=new Fixture();f.attempt(1);f.send(new Observation.Outcome(1,1,1,Observation.Result.APPLIED,MOVE,"0".repeat(64)),1);assertEquals(0,f.actor.snapshot().certifiedIngressOrdinal());}
    @Test void sequenceHoleDegrades(){var f=new Fixture();f.actor.process(new Observation(KEY,2,0,0,new Observation.Tick(1)),0);assertFalse(f.actor.snapshot().certificationAvailable());}
    @Test void failureIsLocal(){var broken=new Fixture();var healthy=new Fixture();broken.actor.fail();healthy.attempt(1);healthy.outcome(1,Observation.Result.APPLIED);assertEquals(1,healthy.actor.snapshot().certifiedIngressOrdinal());assertFalse(broken.actor.snapshot().certificationAvailable());}
    @Test void fieldPresenceAndFiniteNormalization(){
        var p=Normalizer.movement(ProtocolProfile.PAPER_26_2,false,Double.NaN,0,0,true,720,1,true,false);
        assertNull(p.position());assertEquals(720,p.rotation().yaw());
        assertThrows(IllegalArgumentException.class,()->Normalizer.movement(ProtocolProfile.PAPER_26_2,true,Double.NaN,0,0,false,0,0,true,false));
    }
    @Test void bundleBoundsAndCopy(){var list=new ArrayList<PacketPayload>();list.add(MOVE);var bundle=new PacketPayload.Bundle(list);list.clear();assertEquals(1,bundle.children().size());assertThrows(IllegalArgumentException.class,()->new PacketPayload.Bundle(List.of(bundle)));}
    @Test void queueSaturationIsExplicit(){var queue=new BoundedMailbox<Integer>(2,8,x->4);assertTrue(queue.offer(1));assertTrue(queue.offer(2));assertFalse(queue.offer(3));assertEquals(1,queue.poll());assertEquals(2,queue.poll());assertEquals(0,queue.bytes());}
    @Test void concurrentQueuePreservesPerProducerOrder()throws Exception{
        var q=new BoundedMailbox<Long>(1024,8192,x->8);var pool=Executors.newFixedThreadPool(4);var futures=new ArrayList<Future<?>>();
        for(int p=0;p<4;p++){int producer=p;futures.add(pool.submit(()->{for(int i=1;i<=10000;i++){long v=((long)producer<<32)|i;while(!q.offer(v))Thread.onSpinWait();}}));}
        int[] last=new int[4];long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try{
            for(int n=0;n<40000;){assertTrue(System.nanoTime()<deadline);Long v=q.poll();if(v==null){Thread.onSpinWait();continue;}int p=(int)(v>>>32),i=(int)(long)v;assertEquals(last[p]+1,i);last[p]=i;n++;}
            for(var f:futures)f.get(1,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertEquals(0,q.size());assertEquals(0,q.bytes());
    }
    static class Loop implements ConnectionIngress.EventLoop {
        final Thread owner=Thread.currentThread();final Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();
        public boolean inEventLoop(){return Thread.currentThread()==owner;}
        public void execute(Runnable task){tasks.add(task);}
        void drain(){for(Runnable r;(r=tasks.poll())!=null;)r.run();}
    }
    @Test void concurrentBridgeHasOneDrainAndMonotoneCapture()throws Exception{
        var loop=new Loop();var health=new HealthLatch();var in=new ConnectionIngress(KEY,loop,health,1024);
        var pool=Executors.newFixedThreadPool(4);var fs=new ArrayList<Future<?>>();
        try{for(int p=0;p<4;p++)fs.add(pool.submit(()->{for(int i=0;i<50;i++)assertTrue(in.bridge(new Observation.Tick(i)));}));for(var f:fs)f.get();}
        finally{pool.shutdownNow();}
        assertEquals(1,loop.tasks.size());loop.drain();for(int i=1;i<=200;i++)assertEquals(i,in.poll().sequence());assertNull(in.poll());
    }
    @Test void wrongThreadCannotAssignSequence()throws Exception{var loop=new Loop();var in=new ConnectionIngress(KEY,loop,new HealthLatch(),2);var pool=Executors.newSingleThreadExecutor();try{pool.submit(()->assertThrows(IllegalStateException.class,()->in.capture(new Observation.Tick(1),0))).get();}finally{pool.shutdownNow();}}
    @Test void ingressOverflowLatchesOutsideFullQueue(){var h=new HealthLatch();var in=new ConnectionIngress(KEY,new Loop(),h,2);assertTrue(in.capture(new Observation.Tick(1),0));assertTrue(in.capture(new Observation.Tick(2),0));assertFalse(in.capture(new Observation.Tick(3),0));assertEquals(HealthLatch.Reason.OVERLOADED,h.get().reason());}
    @Test void reconnectAndShutdownUseFreshEpochs()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new Loop();
        var first=engine.connect(KEY.player(),loop,TransportCapability.observeOnly());var second=engine.connect(KEY.player(),loop,TransportCapability.observeOnly());
        assertNotEquals(first.key,second.key);assertTrue(first.ingress.closed());engine.close();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!engine.terminated()&&System.nanoTime()<end){loop.drain();Thread.sleep(1);}
        assertTrue(engine.terminated());engine.supervise();assertEquals(0,engine.sessions());assertEquals(256L*1024*1024,engine.reservedBytes());
    }
    @Test void topologyMatchWithoutMediatedIntegrityCannotCertify(){var probe=new BoundaryProbe();assertFalse(probe.probe(List.of("decoder"),List.of("decoder"),true,true,false,true).capability().certification());}
    @Test void foreignHandlerAndMissingFamilyDowngrade(){var p=new BoundaryProbe();assertEquals("ForeignHandlerInsideBoundary",p.probe(List.of("decoder"),List.of("foreign","decoder"),true,true,true,true).reason());assertFalse(p.probe(List.of(),List.of(),false,true,true,true).capability().certification());}
    @Test void configurationCanonicalizationAndFailure(){var a=EngineConfiguration.parse("# hello\nworkers=1\ncapacity=32\n",4L<<30);var b=EngineConfiguration.parse("capacity=32\nworkers=1\nmode=OBSERVE_ONLY\n",4L<<30);assertEquals(a.digest(),b.digest());assertThrows(IllegalArgumentException.class,()->EngineConfiguration.parse("workers=1\nworkers=2",4L<<30));assertThrows(IllegalArgumentException.class,()->EngineConfiguration.parse("mode=ENFORCE",4L<<30));}
    @Test void globalGuardAndRestriction(){var c=new RuntimeControl(100);c.enable(true,true);assertTrue(c.admitSynthetic(new UUID(0,1),100,0));assertTrue(c.admitSynthetic(new UUID(0,2),100,1));assertFalse(c.admitSynthetic(new UUID(0,3),100,2));assertEquals(RuntimeControl.Mode.OBSERVE_ONLY,c.mode());}
    @Test void replayRoundtripAndCompatibility()throws Exception{
        var s=new TransportSnapshot(1,1,3,0,List.of(new TransportSnapshot.Pending(1,PayloadDigest.of(MOVE),500)),"transport-ieee-v1");
        byte[] b=TransportCodec.encode(s);assertEquals(s,TransportCodec.decode(b,"transport-ieee-v1"));assertArrayEquals(b,TransportCodec.encode(TransportCodec.decode(b,"transport-ieee-v1")));
        assertThrows(IOException.class,()->TransportCodec.decode(b,"other"));assertThrows(IOException.class,()->TransportCodec.decode(Arrays.copyOf(b,b.length-1),"transport-ieee-v1"));
    }
    @Test void destinationRegionIsImmutableAndBounded(){var cells=new ArrayList<DestinationRegion.Cell>();cells.add(new DestinationRegion.Cell(0,1,2,3));var region=new DestinationRegion("world",1,1,false,cells);cells.clear();assertEquals(1,region.cells().size());assertThrows(UnsupportedOperationException.class,()->region.cells().clear());}
    @Test void generatedOutcomesNeverCertifyBeyondFirstUncertainAttempt(){
        long seed=0x50A0AC;var random=new Random(seed);
        for(int trial=0;trial<1000;trial++){
            var f=new Fixture();int n=1+random.nextInt(20);for(int i=1;i<=n;i++)f.attempt(i);
            int firstBad=n+1;var outcomes=new ArrayList<Observation.Outcome>();
            for(int i=1;i<=n;i++){var result=Observation.Result.values()[random.nextInt(5)];if(result!=Observation.Result.APPLIED)firstBad=Math.min(firstBad,i);outcomes.add(new Observation.Outcome(i,i,i,result,result==Observation.Result.APPLIED||result==Observation.Result.TRANSFORMED_APPLIED?MOVE:null,PayloadDigest.of(MOVE)));}
            Collections.shuffle(outcomes,random);for(var result:outcomes){f.send(result,1);assertTrue(f.actor.snapshot().certifiedIngressOrdinal()<firstBad,"seed="+seed+" trial="+trial);}
        }
    }
}
