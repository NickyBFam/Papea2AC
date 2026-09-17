package me.nicolas.papea2ac.replay;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Single bounded I/O owner; shutdown retains charges while a blocked writer can still access data.
 * A watchdog observes progress without ever performing unsafe use-after-free cleanup: a timeout means the
 * writer did not make expected progress, never that its thread has terminated. Resources are only released
 * once termination is actually proven (see {@link #terminated()}), matching the safety invariant that a
 * writer/session resource charge is never released while an execution context may still access it.
 */
public final class CaptureWriter implements AutoCloseable {
    @FunctionalInterface public interface Sink {void write(TransportCapsule capsule)throws IOException;}
    /** RUNNING: idle or making progress. SLOW/HUNG: no progress observed on the in-flight job for that long, but the thread may still finish it. FENCED: no longer admitting new jobs, in-flight work untouched. TERMINATED: thread proven dead; only then may leases be released. */
    public enum Health {RUNNING,SLOW,HUNG,FENCED,TERMINATED}
    private static final long SLOW_NANOS=2_000_000_000L,HUNG_NANOS=10_000_000_000L;
    public static final int QUEUE_CAPACITY=2;
    private record Job(TransportCapsule capsule,ReplayQuota.Lease promotion,ReplayQuota.Lease io,long submittedNanos){
        void release(){promotion.close();io.close();}
    }
    private final ArrayBlockingQueue<Job> jobs=new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ReplayQuota quota;
    private final Sink sink;
    private final Consumer<Throwable> failures;
    private final Thread thread;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicBoolean fenced=new AtomicBoolean();
    private volatile boolean inFlight;
    private volatile long jobAcceptedNanos;
    private final AtomicLong written=new AtomicLong(),rejected=new AtomicLong(),failed=new AtomicLong();
    /** Self-contained submit-to-start queue-delay histogram; kept local rather than depending on anticheat-core's PacketMetrics (wrong dependency direction). */
    private final LongAdder queueDelaySamples=new LongAdder();
    private final LongAdder[] queueDelayBuckets=new LongAdder[64];
    { for(int i=0;i<64;i++)queueDelayBuckets[i]=new LongAdder(); }
    public CaptureWriter(ReplayQuota quota,Sink sink,Consumer<Throwable> failures){
        this.quota=quota;this.sink=sink;this.failures=failures;
        thread=new Thread(this::run,"Papea2AC-replay-writer");thread.setDaemon(true);thread.start();
    }
    /** Stops admitting new jobs without touching in-flight resources; mirrors PacketEngine shard fencing. */
    public void fence(){fenced.set(true);}
    public Health health(long now){
        if(!thread.isAlive())return Health.TERMINATED;
        if(fenced.get())return Health.FENCED;
        if(!inFlight)return Health.RUNNING;
        long elapsed=now-jobAcceptedNanos;
        if(elapsed>HUNG_NANOS)return Health.HUNG;
        if(elapsed>SLOW_NANOS)return Health.SLOW;
        return Health.RUNNING;
    }
    public static Sink directory(Path directory){return capsule->{
        Files.createDirectories(directory);
        try(var files=Files.list(directory)){
            if(files.limit(64).count()>=64)throw new IOException("diagnostic file quota: archive captures before exporting more");
        }
        // Random diagnostic filename, no runtime player identity. No rename/durability guarantee.
        Path output=directory.resolve("transport-"+java.util.UUID.randomUUID()+".capsule");
        Files.write(output,CapsuleCodec.encode(capsule),StandardOpenOption.CREATE_NEW);
    };}
    /** Reserve all retained/temporary allocations before obtaining the immutable frame list. */
    public synchronized boolean submit(long epoch,long bytes,Supplier<TransportCapsule> snapshot){
        if(bytes<512||bytes>(256L<<10))throw new IllegalArgumentException("diagnostic prefix bound");
        if(closing.get()||fenced.get()||!thread.isAlive()){rejected.incrementAndGet();return false;}
        var promotion=quota.reserve(ReplayQuota.Pool.PROMOTION,epoch,bytes+65536,1);
        if(promotion.isEmpty()){rejected.incrementAndGet();return false;}
        var io=quota.reserve(ReplayQuota.Pool.IO,epoch,8L<<20,1);
        if(io.isEmpty()){promotion.get().close();rejected.incrementAndGet();return false;}
        Job job;
        try{
            TransportCapsule capsule=snapshot.get();
            long actual=512+capsule.frames().stream().mapToLong(TransportCapsule.Frame::chargedBytes).sum();
            if(actual>bytes)throw new IllegalArgumentException("unreserved diagnostic bytes");
            job=new Job(capsule,promotion.get(),io.get(),System.nanoTime());
        }
        catch(RuntimeException e){promotion.get().close();io.get().close();throw e;}
        if(!jobs.offer(job)){job.release();rejected.incrementAndGet();return false;}
        return true;
    }
    private void run(){
        try{
            while(!closing.get()||!jobs.isEmpty()){
                Job job;
                try{job=jobs.poll(100,TimeUnit.MILLISECONDS);}catch(InterruptedException e){continue;}
                if(job==null)continue;
                jobAcceptedNanos=System.nanoTime();inFlight=true;
                long waited=Math.max(0,jobAcceptedNanos-job.submittedNanos());
                queueDelayBuckets[waited==0?0:64-Long.numberOfLeadingZeros(waited)].increment();queueDelaySamples.increment();
                try{sink.write(job.capsule());written.incrementAndGet();}
                // Report before counting: a caller observing failed()==N via this AtomicLong's memory barrier
                // must also already observe the corresponding failures.accept(e) call, never a narrow window
                // where the counter moved but the failure has not yet been reported.
                catch(IOException|RuntimeException e){failures.accept(e);failed.incrementAndGet();}
                finally{inFlight=false;job.release();}
            }
        }finally{synchronized(this){closing.set(true);Job job;while((job=jobs.poll())!=null)job.release();}}
    }
    public long written(){return written.get();}
    public long rejected(){return rejected.get();}
    public long failed(){return failed.get();}
    public boolean terminated(){return !thread.isAlive();}
    /** Submit-to-start queue delay (how long a diagnostic export waited behind the bounded writer queue), cumulative power-of-two nanosecond bucket upper bound. */
    public long queueDelayPercentileUpperBound(double percentile){
        if(!(percentile>0&&percentile<=1))throw new IllegalArgumentException("percentile");
        long count=queueDelaySamples.sum();if(count==0)return 0;
        long wanted=(long)Math.ceil(count*percentile),seen=0;
        for(int i=0;i<64;i++){seen+=queueDelayBuckets[i].sum();if(seen>=wanted)return i==0?0:i==63?Long.MAX_VALUE:(1L<<i)-1;}
        return Long.MAX_VALUE;
    }
    public long queueDelaySamples(){return queueDelaySamples.sum();}
    @Override public synchronized void close(){closing.set(true);}
}
