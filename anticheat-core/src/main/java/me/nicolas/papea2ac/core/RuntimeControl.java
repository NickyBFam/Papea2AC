package me.nicolas.papea2ac.core;
import java.util.*;
/** Main-thread synthetic admission guard. No platform action is implemented. */
public final class RuntimeControl {
    public enum Mode {DISABLED,OBSERVE_ONLY,ENFORCE}
    private final Thread owner=Thread.currentThread();
    private final Map<UUID,Long> recent=new HashMap<>();
    private final int capacity;
    private Mode mode=Mode.OBSERVE_ONLY;private long generation;
    private long windowStart=Long.MIN_VALUE;private int windowEligible;
    public RuntimeControl(int capacity){if(capacity<1||capacity>1024)throw new IllegalArgumentException("capacity");this.capacity=capacity;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("main-thread arbiter");}
    public void restrict(Mode next){owner();if(next.ordinal()>mode.ordinal())throw new IllegalArgumentException("restriction only");mode=next;generation++;}
    /** Returns to the safe startup default from a stricter suspension. Never reaches ENFORCE; that remains reachable only through enable(). */
    public void resumeObservation(){owner();if(mode==Mode.ENFORCE)throw new IllegalStateException("cannot resume through enforce");mode=Mode.OBSERVE_ONLY;generation++;}
    public void enable(boolean authorized,boolean allGuardsQualified){owner();if(!authorized||!allGuardsQualified)throw new IllegalStateException("unqualified");mode=Mode.ENFORCE;generation++;recent.clear();windowStart=Long.MIN_VALUE;}
    public boolean admitSynthetic(UUID session,int eligible,long now){
        owner();if(mode!=Mode.ENFORCE)return false;
        if(eligible<1||eligible>capacity)throw new IllegalArgumentException("eligible count");
        if(windowStart==Long.MIN_VALUE||now-windowStart>10_000_000_000L){windowStart=now;windowEligible=eligible;recent.clear();}
        if(recent.size()>=capacity&&!recent.containsKey(session)){restrict(Mode.OBSERVE_ONLY);return false;}
        recent.put(session,now);
        long burst=recent.values().stream().filter(t->now-t<=1_000_000_000L).count();
        if(recent.size()>=Math.max(3,(int)Math.ceil(.20*windowEligible))||burst>=3){restrict(Mode.OBSERVE_ONLY);return false;}
        return mode==Mode.ENFORCE;
    }
    public Mode mode(){owner();return mode;}
    public long generation(){owner();return generation;}
}
