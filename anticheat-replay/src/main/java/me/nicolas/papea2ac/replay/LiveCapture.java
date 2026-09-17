package me.nicolas.papea2ac.replay;

import java.util.*;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Owner-confined diagnostic prefix. Exhaustion is explicitly incomplete, never a fabricated checkpoint.
 * Retention is adaptive: the fair per-session share is computed from the CURRENT active session count at
 * construction time, not a static worst-case capacity, so light load gets more retention and heavy load
 * still respects the global rolling budget (see ReplayQuota.reserve, which additionally caps any single
 * session to its own fair share so a noisy session cannot starve the others).
 */
public final class LiveCapture implements AutoCloseable {
    private final ReplayQuota.Lease rolling;
    private final List<TransportCapsule.Frame> frames=new ArrayList<>();
    private final long epoch;
    private long bytes=512,lastSequence;
    private boolean complete=true,closed;
    private String incompleteReason="";
    public LiveCapture(ReplayQuota quota,long epoch,int activeSessions){
        this.epoch=epoch;
        long fair=Math.min(256L<<10,(48L<<20)/Math.max(1,activeSessions));
        rolling=quota.reserve(ReplayQuota.Pool.ROLLING,epoch,fair,activeSessions).orElse(null);
        if(rolling==null)incomplete("ROLLING_BUDGET_UNAVAILABLE_AT_START");
    }
    /** Resumes recording mid-session: the capsule's first frame documents the coherent starting boundary. */
    public LiveCapture(ReplayQuota quota,long epoch,int activeSessions,TransportCapsule.Checkpoint checkpoint){
        this(quota,epoch,activeSessions);
        lastSequence=checkpoint.startSequence()-1;
        if(complete)append(checkpoint);
    }
    public void incomplete(){incomplete("DEGRADED");}
    public void incomplete(String reason){
        if(complete){complete=false;incompleteReason=reason.length()>64?reason.substring(0,64):reason;}
    }
    public void append(TransportCapsule.Frame frame){
        if(closed||!complete)return;
        if(frame instanceof TransportCapsule.Input input){
            if(input.sequence()!=lastSequence+1){incomplete("SEQUENCE_GAP");return;}lastSequence=input.sequence();
        }
        if(frames.size()>=4096){incomplete("FRAME_COUNT_LIMIT");return;}
        if(rolling==null||bytes+frame.chargedBytes()>rolling.bytes()){incomplete("ROLLING_BUDGET_EXCEEDED");return;}
        bytes+=frame.chargedBytes();frames.add(frame);
    }
    public long chargedBytes(){return bytes;}
    public boolean complete(){return complete;}
    public String incompleteReason(){return incompleteReason;}
    public TransportCapsule snapshot(String profile,String configuration,String revision,boolean qualified){
        if(closed)throw new IllegalStateException("recorder closed");
        return new TransportCapsule(1,profile,configuration,revision,epoch,qualified,frames,complete,incompleteReason);
    }
    @Override public void close(){if(closed)return;closed=true;frames.clear();if(rolling!=null)rolling.close();}
}
