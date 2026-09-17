package me.nicolas.papea2ac.testing;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;

/** Synthetic actor load and noisy-neighbor exercise; not a native Paper capacity claim. */
public final class LoadProfileMain {
    private LoadProfileMain() {}
    private static final class Loop implements ConnectionIngress.EventLoop {
        final Thread owner=Thread.currentThread();final ArrayBlockingQueue<Runnable> tasks=new ArrayBlockingQueue<>(4096);
        public boolean inEventLoop(){return Thread.currentThread()==owner;}
        public void execute(Runnable task){if(!tasks.offer(task))throw new RejectedExecutionException("harness task bound");}
        void drain(){Runnable task;while((task=tasks.poll())!=null)task.run();}
    }
    private static String run(int count)throws Exception{
        var failures=new ConcurrentLinkedQueue<Throwable>();
        var engine=new PacketEngine(4,count+1,1L<<30,failures::add);var loop=new Loop();
        var sessions=new ArrayList<PacketEngine.Session>();
        var qualified=new TransportCapability(true,true,true,true);
        long started=System.nanoTime();int ticks=100;
        long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        try{
            for(int i=0;i<count;i++)sessions.add(engine.connect(new UUID(0,i+1),loop,qualified));
            var hot=engine.connect(new UUID(1,1),loop,TransportCapability.observeOnly());
            var payload=new PacketPayload.Movement(new PacketPayload.Position(1,64,2),null,true,false);
            String digest=PayloadDigest.of(payload);
            for(int tick=1;tick<=ticks;tick++){
                long cycle=System.nanoTime();
                for(var session:sessions){
                    if(!session.ingress.capture(new Observation.Attempt(tick,tick,payload),System.nanoTime()))throw new AssertionError("ordinary capture dropped");
                    if(!session.ingress.capture(new Observation.Outcome(tick,tick,tick,Observation.Result.APPLIED,payload,digest),System.nanoTime()))throw new AssertionError("ordinary outcome dropped");
                }
                for(int burst=0;burst<2048;burst++)hot.ingress.capture(new Observation.Tick(tick),System.nanoTime());
                loop.drain();long remainder=50_000_000L-(System.nanoTime()-cycle);
                if(remainder>0)TimeUnit.NANOSECONDS.sleep(remainder);
            }
            long deadline=System.nanoTime()+5_000_000_000L;
            while(sessions.stream().anyMatch(s->s.actor.snapshot().processedSequence()<ticks*2L)&&System.nanoTime()<deadline)Thread.sleep(1);
            for(var session:sessions)if(session.actor.snapshot().certifiedIngressOrdinal()!=ticks)throw new AssertionError("ordinary actor starved/degraded: "+session.actor.snapshot());
            if(hot.health.get().healthy()||hot.actor.snapshot().certifiedIngressOrdinal()!=0)throw new AssertionError("hot actor did not degrade safely");
            if(!failures.isEmpty())throw new AssertionError("worker failure",failures.peek());
            long elapsed=System.nanoTime()-started;
            long heapAfter=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
            return String.format(Locale.ROOT,"{\"ordinarySessions\":%d,\"hotSessions\":1,\"ticks\":%d,\"elapsedNanos\":%d,\"certifiedFixtureOutcomes\":%d,\"ingressDropped\":%d,\"p95DelayUpperNanos\":%d,\"p99DelayUpperNanos\":%d,\"heapBeforeBytes\":%d,\"heapAfterBytes\":%d,\"reservedBytes\":%d}",
                count,ticks,elapsed,engine.metrics.count(PacketMetrics.Kind.CERTIFIED),engine.metrics.count(PacketMetrics.Kind.INGRESS_DROPPED),
                engine.metrics.delayPercentileUpperBound(.95),engine.metrics.delayPercentileUpperBound(.99),heapBefore,heapAfter,engine.reservedBytes());
        }finally{
            engine.close();long deadline=System.nanoTime()+5_000_000_000L;
            while(!engine.terminated()&&System.nanoTime()<deadline){loop.drain();Thread.sleep(1);}
            engine.supervise();
            if(!engine.terminated()||engine.sessions()!=0||engine.reservedBytes()!=(256L<<20))throw new AssertionError("cleanup did not reclaim session leases");
        }
    }
    public static void main(String[] args)throws Exception{
        String output="{\"kind\":\"synthetic-actor-only\",\"profiles\":["+run(50)+","+run(100)+"]}\n";
        Files.writeString(Path.of(args[0]),output);
    }
}
