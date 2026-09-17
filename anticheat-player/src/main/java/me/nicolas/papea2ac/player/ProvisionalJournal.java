package me.nicolas.papea2ac.player;
import java.util.LinkedHashMap;
import java.util.Map;
/** Single-writer storage; core supplies typed entries and owns packet transition policy. */
public final class ProvisionalJournal<T> {
    private record Entry<T>(T value,int bytes,long deadline){}
    private final LinkedHashMap<Long,Entry<T>> entries=new LinkedHashMap<>();
    private final int capacity,byteLimit;private int bytes;
    public ProvisionalJournal(int capacity,int byteLimit){
        if(capacity<1||capacity>256||byteLimit<1||byteLimit>262144)throw new IllegalArgumentException("journal bounds");
        this.capacity=capacity;this.byteLimit=byteLimit;
    }
    public boolean add(long id,T value,int chargedBytes,long deadline){
        if(chargedBytes<1||entries.containsKey(id)||entries.size()>=capacity||chargedBytes>byteLimit-bytes)return false;
        entries.put(id,new Entry<>(value,chargedBytes,deadline));bytes+=chargedBytes;return true;
    }
    public T get(long id){var e=entries.get(id);return e==null?null:e.value();}
    public Long firstId(){return entries.isEmpty()?null:entries.keySet().iterator().next();}
    public T remove(long id){var e=entries.remove(id);if(e==null)return null;bytes-=e.bytes();return e.value();}
    public boolean expired(long now){return entries.values().stream().anyMatch(e->now-e.deadline()>=0);}
    public int size(){return entries.size();}
    public int bytes(){return bytes;}
    public record SnapshotEntry<T>(long id,T value,int bytes,long deadline){}
    public java.util.List<SnapshotEntry<T>> snapshot(){
        return entries.entrySet().stream().map(e->new SnapshotEntry<>(e.getKey(),e.getValue().value(),e.getValue().bytes(),e.getValue().deadline())).toList();
    }
    public void clear(){entries.clear();bytes=0;}
}
