package me.nicolas.papea2ac.packet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.ToIntFunction;
/** Bounded MPSC publication slots. A reserved but unpublished head is never overtaken. */
public final class BoundedMailbox<T> {
    private final AtomicReferenceArray<T> slots;
    private final AtomicInteger producer=new AtomicInteger(), consumer=new AtomicInteger(), bytes=new AtomicInteger();
    private final int byteLimit;
    private final ToIntFunction<T> charge;
    public BoundedMailbox(int capacity,int byteLimit,ToIntFunction<T> charge){
        if(capacity<1||capacity>65536||Integer.bitCount(capacity)!=1||byteLimit<1)throw new IllegalArgumentException("capacity");
        slots=new AtomicReferenceArray<>(capacity);this.byteLimit=byteLimit;this.charge=charge;
    }
    public boolean offer(T value){
        int cost=charge.applyAsInt(value);
        if(cost<1||cost>byteLimit)return false;
        int b;
        do{b=bytes.get();if(b>byteLimit-cost)return false;}while(!bytes.compareAndSet(b,b+cost));
        int p;
        do{p=producer.get();if(Integer.toUnsignedLong(p-consumer.get())>=slots.length()){bytes.addAndGet(-cost);return false;}}
        while(!producer.compareAndSet(p,p+1));
        slots.set(Integer.remainderUnsigned(p,slots.length()),value);
        return true;
    }
    public T poll(){
        int c=consumer.get();
        if(c==producer.get())return null;
        int i=Integer.remainderUnsigned(c,slots.length());T value=slots.get(i);
        if(value==null)return null;
        slots.set(i,null);consumer.set(c+1);bytes.addAndGet(-charge.applyAsInt(value));return value;
    }
    public int size(){return producer.get()-consumer.get();}
    public int bytes(){return bytes.get();}
    public int capacity(){return slots.length();}
}
