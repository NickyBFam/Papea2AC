package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.version.*;
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=5,time=1) @Measurement(iterations=10,time=1) @Fork(value=3,jvmArgsAppend={"-Xms512m","-Xmx512m"})
@State(Scope.Thread)
public class PacketBenchmarks {
    private long id;
    private final SessionKey key=new SessionKey(new UUID(1,2),1);
    private BoundedMailbox<Long> queue;
    private ConnectionIngress ingress;
    private ProvisionalJournal<Long> journal;
    private TruthActor actor;
    private PacketPayload.Movement payload;
    private String digest;
    @Setup public void setup(){
        queue=new BoundedMailbox<>(1024,262144,x->32);
        ingress=new ConnectionIngress(key,new ConnectionIngress.EventLoop(){
            public boolean inEventLoop(){return true;}public void execute(Runnable r){r.run();}
        },new HealthLatch(),1024);
        journal=new ProvisionalJournal<>(256,262144);
        actor=new TruthActor(key,new HealthLatch(),new TransportCapability(true,true,true,true),new PacketMetrics(),256);
        payload=Normalizer.movement(ProtocolProfile.PAPER_26_2,true,1,64,2,false,0,0,true,false);digest=PayloadDigest.of(payload);
    }
    @Benchmark public PacketPayload normalize(){return Normalizer.movement(ProtocolProfile.PAPER_26_2,true,(++id)%100,64,2,true,45,0,true,false);}
    @Benchmark public Long enqueueDequeue(){queue.offer(++id);return queue.poll();}
    @Benchmark public Observation sequence(){ingress.capture(new Observation.Tick(++id),0);return ingress.poll();}
    @Benchmark public Long journal(){journal.add(++id,id,128,500000000);return journal.remove(id);}
    @Benchmark public TruthActor.Snapshot correlate(){
        long n=++id;
        actor.process(new Observation(key,2*n-1,0,0,new Observation.Attempt(n,n,payload)),0);
        actor.process(new Observation(key,2*n,0,0,new Observation.Outcome(n,n,n,Observation.Result.APPLIED,payload,digest)),0);
        return actor.snapshot();
    }
}
