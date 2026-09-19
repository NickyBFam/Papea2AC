package me.nicolas.papea2ac.core;
import java.util.concurrent.atomic.LongAdder;
/** Fixed global labels; no UUID/name map. */
public final class PacketMetrics {
    public enum Kind {CAPTURED,NORMALIZED,PROVISIONAL,CERTIFIED,CANCELLED,MUTATED,UNKNOWN,
        EXPIRED,OVERLOAD,MALFORMED,STALE,FAILURE,CLOSED,WRITE_FAILED,INGRESS_DROPPED,BRIDGE_DROPPED,DISPATCH_REJECTED,CLEANUP_STALLED,
        WRITER_HUNG,CHECKPOINT_CREATED,RECORDING_PAUSED,CONTROL_APPLIED,PRELOGIN_ADMISSION_REJECTED,PRELOGIN_TRUNCATED_GLOBAL,BULK_SHED,WORK_QUOTA_DEFERRED}
    /** Named hot-path timing channels. CAPTURE_TO_PROCESS is the network-callback-to-ordered-processing handoff (the only channel with production call sites prior to this pass). CHECKPOINT_REQUEST_TO_EXPORT isolates the ordering/queueing delay specifically for operator checkpoint requests, so it can be distinguished from ordinary packet latency under load. */
    public enum Timer {CAPTURE_TO_PROCESS,CHECKPOINT_REQUEST_TO_EXPORT}
    private final LongAdder[] counts=new LongAdder[Kind.values().length];
    private final LongAdder[] delay=new LongAdder[Timer.values().length];
    private final LongAdder[] samples=new LongAdder[Timer.values().length];
    private final LongAdder[][] delayBuckets=new LongAdder[Timer.values().length][64];
    public PacketMetrics(){
        for(int i=0;i<counts.length;i++)counts[i]=new LongAdder();
        for(int t=0;t<Timer.values().length;t++){
            delay[t]=new LongAdder();samples[t]=new LongAdder();
            for(int i=0;i<64;i++)delayBuckets[t][i]=new LongAdder();
        }
    }
    public void increment(Kind kind){counts[kind.ordinal()].increment();}
    public long count(Kind kind){return counts[kind.ordinal()].sum();}
    public void latency(long nanos){latency(Timer.CAPTURE_TO_PROCESS,nanos);}
    public void latency(Timer timer,long nanos){
        int t=timer.ordinal();long value=Math.max(0,nanos);
        delay[t].add(value);delayBuckets[t][value==0?0:64-Long.numberOfLeadingZeros(value)].increment();samples[t].increment();
    }
    /** Cumulative power-of-two nanosecond bucket upper bound, not an interpolated exact percentile. */
    public long delayPercentileUpperBound(double percentile){return delayPercentileUpperBound(Timer.CAPTURE_TO_PROCESS,percentile);}
    public long delayPercentileUpperBound(Timer timer,double percentile){
        if(!(percentile>0&&percentile<=1))throw new IllegalArgumentException("percentile");
        int t=timer.ordinal();long count=samples[t].sum();if(count==0)return 0;
        long wanted=(long)Math.ceil(count*percentile),seen=0;
        for(int i=0;i<64;i++){seen+=delayBuckets[t][i].sum();if(seen>=wanted)return i==0?0:i==63?Long.MAX_VALUE:(1L<<i)-1;}
        return Long.MAX_VALUE;
    }
    public long delaySamples(){return delaySamples(Timer.CAPTURE_TO_PROCESS);}
    public long delaySamples(Timer timer){return samples[timer.ordinal()].sum();}
    public long totalDelayNanos(){return totalDelayNanos(Timer.CAPTURE_TO_PROCESS);}
    public long totalDelayNanos(Timer timer){return delay[timer.ordinal()].sum();}
}
