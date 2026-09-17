package me.nicolas.papea2ac.replay;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Reservation accounting only. Buffers must be reserved before allocation/publication. */
public final class ReplayQuota {
    public enum Pool {ROLLING,PROMOTION,IO}
    private final long[] limits={48L<<20,32L<<20,16L<<20};
    private final long[] used=new long[3];
    private final Map<Long,Long> rolling=new HashMap<>();
    private int promotions;private boolean closed;
    public final class Lease implements AutoCloseable {
        private final Pool pool;private final long owner,bytes;private final AtomicBoolean released=new AtomicBoolean();
        private Lease(Pool pool,long owner,long bytes){this.pool=pool;this.owner=owner;this.bytes=bytes;}
        public long bytes(){return bytes;}
        @Override public void close(){
            if(!released.compareAndSet(false,true))return;
            synchronized(ReplayQuota.this){
                used[pool.ordinal()]-=bytes;
                if(pool==Pool.ROLLING)rolling.computeIfPresent(owner,(id,n)->n==bytes?null:n-bytes);
                if(pool==Pool.PROMOTION)promotions--;
            }
        }
    }
    public synchronized Optional<Lease> reserve(Pool pool,long owner,long bytes,int activeSessions){
        if(closed||owner<1||bytes<1||activeSessions<1||activeSessions>1024)return Optional.empty();
        long fair=Math.min(256L<<10,limits[0]/activeSessions);
        if(pool==Pool.ROLLING&&(fair<(16L<<10)||bytes>fair-rolling.getOrDefault(owner,0L)
            ||!rolling.containsKey(owner)&&rolling.size()>=activeSessions))return Optional.empty();
        if(pool==Pool.PROMOTION&&(bytes>(8L<<20)||promotions>=4))return Optional.empty();
        if(bytes>limits[pool.ordinal()]-used[pool.ordinal()])return Optional.empty();
        used[pool.ordinal()]+=bytes;
        if(pool==Pool.ROLLING)rolling.merge(owner,bytes,Long::sum);
        if(pool==Pool.PROMOTION)promotions++;
        return Optional.of(new Lease(pool,owner,bytes));
    }
    public synchronized long used(Pool pool){return used[pool.ordinal()];}
    /** Stop admission, retaining all charges until active users release their leases. */
    public synchronized void closeAdmission(){closed=true;}
}
