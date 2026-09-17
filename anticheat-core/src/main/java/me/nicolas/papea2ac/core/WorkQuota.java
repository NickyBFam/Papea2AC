package me.nicolas.papea2ac.core;
/** Single-writer token bucket. Grants use monotonic elapsed time, never wall time. */
public final class WorkQuota {
    private final long rate,burst;private long credits,lastNanos;
    public WorkQuota(long rate,long burst,long now){
        if(rate<1||rate>2_000_000||burst<1||burst>200_000)throw new IllegalArgumentException("quota");
        this.rate=rate;this.burst=burst;credits=burst;lastNanos=now;
    }
    public boolean take(long cost,long now){
        if(cost<1||cost>burst)return false;
        long elapsed=now-lastNanos;
        if(elapsed>0){credits=Math.min(burst,credits+Math.min(elapsed,1_000_000_000L)*rate/1_000_000_000L);lastNanos=now;}
        if(credits<cost)return false;credits-=cost;return true;
    }
    public long credits(){return credits;}
}
