package me.nicolas.papea2ac.packet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.Observation;
/** One event-loop writer assigns sequence and publishes; foreign producers use a coalesced bridge. */
public final class ConnectionIngress {
    public interface EventLoop {boolean inEventLoop(); void execute(Runnable task);}
    private final SessionKey session;private final EventLoop loop;private final HealthLatch health;
    private final BoundedMailbox<Observation> queue;
    private final BoundedMailbox<Observation.Event> bridge=new BoundedMailbox<>(256,65536,e->96+e.chargedBytes());
    private final AtomicBoolean scheduled=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicBoolean releaseScheduled=new AtomicBoolean();
    private final AtomicInteger publishers=new AtomicInteger();
    private volatile boolean loopReleased;
    private long sequence;
    private final java.util.function.Function<Observation.Event,Observation.Context> context;
    public enum Loss { INGRESS_FULL, BRIDGE_FULL, DISPATCH_REJECTED }
    private final java.util.function.Consumer<Loss> losses;
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity){
        this(session,loop,health,capacity,event->Observation.Context.synthetic());
    }
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity,
            java.util.function.Function<Observation.Event,Observation.Context> context){
        this(session,loop,health,capacity,context,loss->{});
    }
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity,
            java.util.function.Function<Observation.Event,Observation.Context> context,java.util.function.Consumer<Loss> losses){
        this.session=session;this.loop=loop;this.health=health;
        this.context=java.util.Objects.requireNonNull(context);
        this.losses=java.util.Objects.requireNonNull(losses);
        queue=new BoundedMailbox<>(capacity,192*1024,Observation::chargedBytes);
    }
    public boolean capture(Observation.Event event,long nanos){
        if(!loop.inEventLoop())throw new IllegalStateException("event-loop ownership");
        publishers.incrementAndGet();
        try {
        if(closed.get())return false;
        if(sequence==Long.MAX_VALUE){close();return false;}
        Observation observation=new Observation(session,++sequence,nanos,health.get().generation(),event,context.apply(event));
        if(!queue.offer(observation)){health.invalidate(HealthLatch.Reason.OVERLOADED,sequence);losses.accept(Loss.INGRESS_FULL);return false;}
        return true;
        } finally { publisherFinished(); }
    }
    public boolean bridge(Observation.Event event){
        publishers.incrementAndGet();
        try {
        if(closed.get())return false;
        if(!bridge.offer(event)){health.invalidate(HealthLatch.Reason.LOST_SERVER_OBSERVATION,0);losses.accept(Loss.BRIDGE_FULL);return false;}
        schedule();return true;
        } finally { publisherFinished(); }
    }
    private void schedule(){
        if(scheduled.compareAndSet(false,true)){
            try{loop.execute(this::drain);}catch(RuntimeException e){scheduled.set(false);health.invalidate(HealthLatch.Reason.CLOSED,0);losses.accept(Loss.DISPATCH_REJECTED);}
        }
    }
    private void drain(){
        try{
            for(int i=0;i<256;i++){var e=bridge.poll();if(e==null)break;capture(e,System.nanoTime());}
        }finally{scheduled.set(false);if(bridge.size()>0&&!closed.get())schedule();}
    }
    public Observation poll(){return queue.poll();}
    public int queueDepth(){return queue.size();}
    public int queueCapacity(){return queue.capacity();}
    public int retainedBytes(){return queue.bytes()+bridge.bytes();}
    public void close(){
        health.invalidate(HealthLatch.Reason.CLOSED,0);
        closed.set(true);requestRelease();
    }
    private void publisherFinished(){
        if(publishers.decrementAndGet()==0&&closed.get())requestRelease();
    }
    private void requestRelease(){
        // The last admitted publisher schedules exactly one release, without spinning on a live producer.
        if(closed.get()&&publishers.get()==0&&!loopReleased&&releaseScheduled.compareAndSet(false,true)){
            try { loop.execute(this::releaseBridge); }
            catch(RuntimeException e){releaseScheduled.set(false);losses.accept(Loss.DISPATCH_REJECTED);}
        }
    }
    private void releaseBridge(){
        while(bridge.poll()!=null){ /* event-loop is the sole bridge consumer */ }
        loopReleased=true;
    }
    /** Actor/supervisor calls only after the actor stops consuming; late producers cannot publish. */
    public boolean releaseAfterClose(){
        if(!closed.get()||!loopReleased||publishers.get()!=0)return false;
        while(queue.poll()!=null){ /* bounded by ingress capacity */ }
        return true;
    }
    public boolean closed(){return closed.get();}
}
