package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.replay.*;
class ResourceSafetyTest {
    @Test void latencyHistogramReportsConservativeBounds(){
        var metrics=new PacketMetrics();metrics.latency(0);metrics.latency(8);metrics.latency(1000);
        assertEquals(1023,metrics.delayPercentileUpperBound(.99));assertEquals(15,metrics.delayPercentileUpperBound(.5));
        assertEquals(3,metrics.delaySamples());assertEquals(1008,metrics.totalDelayNanos());
    }
    @Test void configuredCapacityRejectsNewPlayerButPermitsReplacement(){
        var engine=new PacketEngine(1,1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        try{
            var original=engine.connect(new UUID(0,1),loop,TransportCapability.observeOnly());
            assertThrows(IllegalStateException.class,()->engine.connect(new UUID(0,2),loop,TransportCapability.observeOnly()));
            assertFalse(original.ingress.closed());
            var replacement=engine.connect(new UUID(0,1),loop,TransportCapability.observeOnly());
            assertTrue(original.ingress.closed());assertTrue(replacement.key.epoch()>original.key.epoch());
            assertEquals(1,engine.sessions());
        }finally{engine.close();loop.drain();}
    }
    @Test void terminatedShardReclaimsOnlyAfterLoopAcknowledgesClose()throws Exception{
        var engine=new PacketEngine(1,1L<<30,e->fail(e));var loop=new PacketTruthTest.Loop();
        var session=engine.connect(new UUID(0,1),loop,TransportCapability.observeOnly());
        engine.fenceShard(0);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!engine.terminated()&&System.nanoTime()<deadline)Thread.sleep(1);
        assertTrue(engine.terminated());engine.supervise();
        assertEquals(258L<<20,engine.reservedBytes(),"unacknowledged transport lease remains charged");
        assertThrows(IllegalStateException.class,()->engine.connect(new UUID(0,2),loop,TransportCapability.observeOnly()));
        loop.drain();engine.supervise();
        assertEquals(256L<<20,engine.reservedBytes());assertEquals(0,session.actor.snapshot().pending());engine.close();
    }
    @Test void liveWriterCannotBeReclaimed(){
        var actor=new TruthActor(PacketTruthTest.KEY,new HealthLatch(),PacketTruthTest.QUALIFIED,new PacketMetrics(),256);
        actor.tick(0);assertThrows(IllegalStateException.class,actor::retireAfterWriterTermination);
    }
    @Test void closingIngressReleasesBothQueuesAfterLoopAck(){
        var loop=new PacketTruthTest.Loop();var in=new ConnectionIngress(PacketTruthTest.KEY,loop,new HealthLatch(),1024);
        in.capture(new Observation.Tick(1),0);in.bridge(new Observation.Tick(2));in.close();
        assertFalse(in.releaseAfterClose());loop.drain();assertTrue(in.releaseAfterClose());assertEquals(0,in.retainedBytes());
        assertFalse(in.capture(new Observation.Tick(3),0));assertFalse(in.bridge(new Observation.Tick(4)));
    }
    @Test void bridgeSaturationIsVisible(){
        var in=new ConnectionIngress(PacketTruthTest.KEY,new PacketTruthTest.Loop(),new HealthLatch(),1024);
        int accepted=0;while(in.bridge(new Observation.Tick(1)))accepted++;
        assertEquals(256,accepted);assertTrue(in.retainedBytes()<=256*1024);
    }
    @Test void memoryAdmissionNeverExceedsGlobalFloor(){
        var engine=new PacketEngine(1,340L<<20,e->fail(e));var loop=new PacketTruthTest.Loop();
        int admitted=0;
        try{for(int i=1;i<=128;i++){engine.connect(new UUID(0,i),loop,TransportCapability.observeOnly());admitted++;}}
        catch(IllegalStateException expected){assertTrue(admitted<128);}
        assertTrue(engine.reservedBytes()<=(340L<<20)*.8);
        engine.close();loop.drain();
    }
    @Test void historyEvictionIsVisibleAndBounded(){
        var history=new ObservationRing<Integer>(4,12,100,x->4);
        for(int i=0;i<10;i++)history.append(i,i);
        assertEquals(List.of(7,8,9),history.snapshot());assertEquals(7,history.evictions());assertEquals(12,history.bytes());
        history.expire(109);assertEquals(0,history.size());assertEquals(0,history.bytes());
    }
    @Test void replayPressureDoesNotEscapePartitions(){
        var quota=new ReplayQuota();var leases=new ArrayList<ReplayQuota.Lease>();
        for(int i=1;i<=1000;i++)leases.add(quota.reserve(ReplayQuota.Pool.ROLLING,i,48000,1000).orElseThrow());
        assertTrue(quota.used(ReplayQuota.Pool.ROLLING)<=48L<<20);
        assertTrue(quota.reserve(ReplayQuota.Pool.ROLLING,1,65536,1000).isEmpty());
        for(var lease:leases){lease.close();lease.close();}assertEquals(0,quota.used(ReplayQuota.Pool.ROLLING));
    }
    @Test void promotionCountBytesAndShutdownProtectLiveUsers(){
        var quota=new ReplayQuota();var leases=new ArrayList<ReplayQuota.Lease>();
        for(int i=1;i<=4;i++)leases.add(quota.reserve(ReplayQuota.Pool.PROMOTION,i,8L<<20,4).orElseThrow());
        assertTrue(quota.reserve(ReplayQuota.Pool.PROMOTION,5,1,5).isEmpty());
        quota.closeAdmission();assertEquals(32L<<20,quota.used(ReplayQuota.Pool.PROMOTION));
        assertTrue(quota.reserve(ReplayQuota.Pool.IO,1,1,1).isEmpty());leases.forEach(ReplayQuota.Lease::close);
        assertEquals(0,quota.used(ReplayQuota.Pool.PROMOTION));
    }
    @Test void oneSessionCannotConsumeAnotherWorkQuota(){
        var hot=new WorkQuota(2_000_000,200_000,0);var normal=new WorkQuota(2_000_000,200_000,0);
        assertTrue(hot.take(200_000,0));assertFalse(hot.take(1,0));assertTrue(normal.take(2048,0));
        assertTrue(hot.take(200_000,100_000_000));assertFalse(hot.take(1,99_000_000));
    }
    @Test void healthBarrierWinsBeforeCommitAuthorization(){
        var health=new HealthLatch();assertTrue(health.authorize(0));health.invalidate(HealthLatch.Reason.OVERLOADED,3);
        assertFalse(health.authorize(0));assertFalse(health.authorize(1));
    }
    @Test void digestPreservesSignedZeroAndFieldPresence(){
        var a=new PacketPayload.Movement(new PacketPayload.Position(0,0,0),null,false,false);
        var b=new PacketPayload.Movement(new PacketPayload.Position(-0.0,0,0),null,false,false);
        assertNotEquals(PayloadDigest.of(a),PayloadDigest.of(b));
        assertNotEquals(PayloadDigest.of(a),PayloadDigest.of(new PacketPayload.Movement(null,null,false,false)));
    }
}
