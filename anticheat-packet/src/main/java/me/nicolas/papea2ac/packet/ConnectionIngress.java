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
    public enum Loss { INGRESS_FULL, BRIDGE_FULL, DISPATCH_REJECTED, BULK_SHED }
    /** PERFORMANCE.md bulk/scratch: 4 MiB per-session exposure ceiling for bulk payload content in flight (chunk section content). Bulk is shed - the record still flows - never dropped, so no sequence gap is created by bulk pressure (P3-F4). */
    public static final int BULK_CEILING_BYTES=4*1024*1024;
    /** Record arena for observations in flight. Phase 1's 192 KiB (PERFORMANCE.md floor composition) held ~245 entity observations; a real server flushes a whole tick's
     *  clientbound packets at once (join ~3000, a tick loading mob-dense chunks 300-600 at ~800 B charged each), which overflowed it during ordinary play (P3-F7, real-client
     *  qualification; a 2 MiB arena still lost 2 observations in extreme creative flight). 4 MiB absorbs one tick's flush (~4096 entity-sized records); the shard drains it within a tick. DEVIATION from the Phase 1 floor figure - reviewer decision recorded in PHASE_3_IMPLEMENTATION.md. */
    public static final int INGRESS_ARENA_BYTES=4*1024*1024;
    /** The PERFORMANCE.md floor slice of the record arena (ingress 192 KiB + bridge 64 KiB = the "256 KiB floor" composition): guaranteed to the session from its tier
     *  reservation and never charged to the shared pool. Arena bytes above it, and all bulk content, are reserved from the engine-wide {@link InfrastructurePool} first. */
    public static final int GUARANTEED_ARENA_BYTES=192*1024;
    private final InfrastructurePool pool;
    private final java.util.concurrent.atomic.AtomicInteger bulkBytes=new java.util.concurrent.atomic.AtomicInteger();
    // Record-arena bytes in flight (producer adds before offer, consumer subtracts after poll) and the part of them currently reserved from the pool; both guarded by poolLock.
    private final Object poolLock=new Object();private int arenaBytes;private long poolArena;
    private final java.util.function.Consumer<Loss> losses;
    /** Consumer wake-up (the owning shard's unpark), run when the queue goes empty -> non-empty; a no-op until the owner installs it (P3-F5). */
    private volatile Runnable wake=()->{};
    public void onEnqueue(Runnable wake){this.wake=java.util.Objects.requireNonNull(wake);}
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity){
        this(session,loop,health,capacity,event->Observation.Context.synthetic());
    }
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity,
            java.util.function.Function<Observation.Event,Observation.Context> context){
        this(session,loop,health,capacity,context,loss->{});
    }
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity,
            java.util.function.Function<Observation.Event,Observation.Context> context,java.util.function.Consumer<Loss> losses){
        this(session,loop,health,capacity,context,losses,new InfrastructurePool(InfrastructurePool.DEFAULT_BYTES));
    }
    public ConnectionIngress(SessionKey session,EventLoop loop,HealthLatch health,int capacity,
            java.util.function.Function<Observation.Event,Observation.Context> context,java.util.function.Consumer<Loss> losses,InfrastructurePool pool){
        this.session=session;this.loop=loop;this.health=health;this.pool=java.util.Objects.requireNonNull(pool);
        this.context=java.util.Objects.requireNonNull(context);
        this.losses=java.util.Objects.requireNonNull(losses);
        queue=new BoundedMailbox<>(capacity,INGRESS_ARENA_BYTES,o->o.chargedBytes()-bulkOf(o.event()));// the record arena charges records; bulk content is charged to the bulk pool
    }
    public boolean capture(Observation.Event event,long nanos){
        if(!loop.inEventLoop())throw new IllegalStateException("event-loop ownership");
        publishers.incrementAndGet();
        try {
        if(closed.get())return false;
        if(sequence==Long.MAX_VALUE){close();return false;}
        int bulk=bulkOf(event);
        if(bulk>0&&!reserveBulk(bulk)){event=shedBulk(event);losses.accept(Loss.BULK_SHED);bulk=0;}// over the session ceiling or the shared pool: keep the record, drop only its bulk content, explicitly
        Observation observation=new Observation(session,++sequence,nanos,health.get().generation(),event,context.apply(event));
        int recordCost=observation.chargedBytes()-bulkOf(observation.event());
        if(!reserveArena(recordCost)){if(bulk>0)releaseBulk(bulk);health.invalidate(HealthLatch.Reason.OVERLOADED,sequence);losses.accept(Loss.INGRESS_FULL);return false;}// shared pool exhausted: accounted drop, never a block
        boolean wasEmpty=queue.size()==0;
        if(!queue.offer(observation)){releaseArena(recordCost);if(bulk>0)releaseBulk(bulk);health.invalidate(HealthLatch.Reason.OVERLOADED,sequence);losses.accept(Loss.INGRESS_FULL);return false;}
        if(wasEmpty)wake.run();
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
    public Observation poll(){var o=queue.poll();if(o!=null){int bulk=bulkOf(o.event());releaseArena(o.chargedBytes()-bulk);if(bulk>0)releaseBulk(bulk);}return o;}
    public int bulkBytes(){return bulkBytes.get();}
    /** Arena bytes currently reserved from the shared pool (those above the guaranteed slice). */
    public long pooledArenaBytes(){synchronized(poolLock){return poolArena;}}
    /** Producer side: account the record in the arena and reserve from the shared pool the part that lies above the guaranteed slice. */
    private boolean reserveArena(int cost){
        synchronized(poolLock){
            long after=(long)arenaBytes+cost;long needPool=Math.max(0L,after-GUARANTEED_ARENA_BYTES)-poolArena;
            if(needPool>0&&!pool.reserve(needPool))return false;
            if(needPool>0)poolArena+=needPool;arenaBytes=(int)after;return true;
        }
    }
    /** Consumer/rollback side: return the record's bytes and give back to the pool whatever no longer lies above the guaranteed slice. */
    private void releaseArena(int cost){
        synchronized(poolLock){
            arenaBytes-=cost;long excess=poolArena-Math.max(0L,(long)arenaBytes-GUARANTEED_ARENA_BYTES);
            if(excess>0){pool.release(excess);poolArena-=excess;}
        }
    }
    private void releaseBulk(int bulk){bulkBytes.addAndGet(-bulk);pool.release(bulk);}
    static int bulkOf(Observation.Event event){
        return switch(event){
            case Observation.Attempt a->a.payload().bulkBytes();
            case Observation.Outbound o->o.payload().bulkBytes();
            case Observation.Outcome o->o.finalPayload()==null?0:o.finalPayload().bulkBytes();
            default->0;
        };
    }
    private boolean reserveBulk(int bulk){
        int b;do{b=bulkBytes.get();if(b>BULK_CEILING_BYTES-bulk)return false;}while(!bulkBytes.compareAndSet(b,b+bulk));
        if(!pool.reserve(bulk)){bulkBytes.addAndGet(-bulk);return false;}// the session ceiling is within the shared pool, never in addition to it
        return true;
    }
    private static Observation.Event shedBulk(Observation.Event event){
        return switch(event){
            case Observation.Attempt a->new Observation.Attempt(a.id(),a.ingressOrdinal(),a.payload().shedBulk("BULK_EXPOSURE_CEILING"));
            case Observation.Outbound o->new Observation.Outbound(o.wireOrdinal(),o.payload().shedBulk("BULK_EXPOSURE_CEILING"));
            case Observation.Outcome o->new Observation.Outcome(o.attemptId(),o.processingOrdinal(),o.serverRevision(),o.result(),o.finalPayload()==null?null:o.finalPayload().shedBulk("BULK_EXPOSURE_CEILING"),o.originalDigest());
            default->event;
        };
    }
    public int queueDepth(){return queue.size();}
    public int queueCapacity(){return queue.capacity();}
    public int retainedBytes(){return queue.bytes()+bridge.bytes()+bulkBytes.get();}
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
        while(poll()!=null){ /* bounded by ingress capacity; poll() returns every arena/bulk/pool charge */ }
        return true;
    }
    public boolean closed(){return closed.get();}
}
