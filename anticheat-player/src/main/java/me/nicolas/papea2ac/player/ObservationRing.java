package me.nicolas.papea2ac.player;
import java.util.*;
import java.util.function.ToIntFunction;
/** Actor-owned observation history. Eviction is explicit and never interpreted as acceptance. */
public final class ObservationRing<T> {
    private record Item<T>(T value,int bytes,long time){}
    private final ArrayDeque<Item<T>> items=new ArrayDeque<>();
    private final int countLimit,byteLimit;private final long horizon;private final ToIntFunction<T> charge;
    private int bytes;private long evictions;
    public ObservationRing(int countLimit,int byteLimit,long horizon,ToIntFunction<T> charge){
        if(countLimit<1||countLimit>4096||byteLimit<1||byteLimit>1048576||horizon<1||horizon>3_000_000_000L)
            throw new IllegalArgumentException("history bounds");
        this.countLimit=countLimit;this.byteLimit=byteLimit;this.horizon=horizon;this.charge=charge;
    }
    public void append(T value,long time){
        int cost=charge.applyAsInt(value);
        if(cost<1||cost>byteLimit){evictions++;return;}
        expire(time);
        while(items.size()>=countLimit||bytes>byteLimit-cost)evict();
        items.addLast(new Item<>(value,cost,time));bytes+=cost;
    }
    public void expire(long now){while(!items.isEmpty()&&now-items.getFirst().time()>=horizon)evict();}
    private void evict(){bytes-=items.removeFirst().bytes();evictions++;}
    public List<T> snapshot(){return items.stream().map(Item::value).toList();}
    public int size(){return items.size();}
    public int bytes(){return bytes;}
    public long evictions(){return evictions;}
    public void clear(){items.clear();bytes=0;}
}
