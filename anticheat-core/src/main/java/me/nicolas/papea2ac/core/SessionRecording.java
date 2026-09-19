package me.nicolas.papea2ac.core;

import java.io.IOException;
import me.nicolas.papea2ac.model.HealthLatch;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Actor-owner projection only. No native values, runtime UUIDs, file I/O, or claimed server authority.
 * Recording can be paused (capture toggled off) and resumed (capture toggled back on, or a writer becomes
 * available mid-session) any number of times; each resume opens a fresh capsule beginning with an explicit
 * {@link TransportCapsule.Checkpoint} frame describing the coherent starting boundary, so a replay/diagnostic
 * consumer never has to assume history before that point is present. All construction happens on the
 * session's single owning shard thread, so a checkpoint always reflects one coherent ordered instant.
 */
final class SessionRecording implements AutoCloseable {
    private final long epoch;
    /** Time zero of the capsule's relative clocks. Fixed at the first frame: for a session-start recording that is the first observation's capture time when it
     *  is EARLIER than construction - a real session begins with the pre-login journal (handshake/login/configuration packets captured by the early-transport hook
     *  seconds before the Session exists), which otherwise fails Input's capturedNanos >= 0 check at frame 1 and voided every real recording (P3-F10). */
    private long origin=System.nanoTime();private boolean originFixed;
    /** Owner-shard-written; volatile so other threads' diagnostic reads (armed/complete/recordingEpoch) observe pause/resume promptly - the enclosing SessionRecording reference on Session never changes, only this field does. */
    private volatile LiveCapture capture;
    private volatile long recordingEpoch;
    private long generation;
    private boolean closed;
    private SessionRecording(long epoch){this.epoch=epoch;}
    SessionRecording(ReplayQuota quota,long epoch,int activeSessions){
        this.epoch=epoch;recordingEpoch=1;capture=new LiveCapture(quota,epoch,activeSessions);
    }
    /** Never-yet-armed placeholder; used when a session connects before recording becomes available. */
    static SessionRecording idle(long epoch){return new SessionRecording(epoch);}
    boolean armed(){return capture!=null;}
    boolean complete(){return capture==null||capture.complete();}
    long recordingEpoch(){return recordingEpoch;}
    void before(HealthLatch.State state,long now){
        if(capture==null)return;
        if(state.generation()!=generation){
            capture.append(new TransportCapsule.Barrier(now-origin,state.reason().name(),state.firstMissingSeq()));generation=state.generation();
        }
    }
    void input(Observation observation,long now){
        if(capture==null||!capture.complete())return;
        if(!originFixed){if(capture.frameCount()==0)origin=Math.min(origin,observation.capturedNanos());originFixed=true;}
        try{capture.append(TransportReplayMapper.export(observation,origin,now));}
        catch(IOException|IllegalArgumentException e){capture.incomplete("EXPORT_ENCODE_FAILURE:"+describe(observation)+":"+e.getMessage());}// bounded to 64 chars by LiveCapture; names the payload so a real-traffic encode failure is diagnosable (real-client qualification)
    }
    private static String describe(Observation o){
        Object payload=switch(o.event()){case Observation.Outbound x->x.payload();case Observation.Attempt x->x.payload();case Observation.Outcome x->x.finalPayload();default->null;};
        return o.event().getClass().getSimpleName()+(payload==null?"":"/"+payload.getClass().getSimpleName());
    }
    void clock(long now){if(capture!=null)capture.append(new TransportCapsule.Clock(now-origin));}
    void finish(long now){if(capture!=null)capture.append(new TransportCapsule.Close(now-origin));}
    void incomplete(){if(capture!=null)capture.incomplete();}
    boolean export(CaptureWriter writer,String configuration){
        if(capture==null)return false;
        return writer.submit(epoch,capture.chargedBytes(),()->capture.snapshot(TransportReplayDriver.PROFILE,configuration,"working-tree-unqualified",false));
    }
    /** Deliberate stop: exports (if a writer exists) and releases the current prefix as a genuinely complete capsule, then goes idle. */
    void pause(CaptureWriter writer,String configuration,long now){
        if(capture==null)return;
        finish(now);
        if(writer!=null)export(writer,configuration);
        capture.close();capture=null;
    }
    /** Deliberate (re)start: opens a fresh capsule seeded with a checkpoint describing this exact coherent boundary. */
    void resume(ReplayQuota quota,int activeSessions,long nextSequence,TruthActor.Snapshot snapshot,HealthLatch.State health,
            String protocolState,boolean captureEnabled,String effectsMode,long now){
        if(capture!=null)return;
        recordingEpoch++;
        // For a brand-new (just-idle()'d) recording, origin is captured here, a few nanoseconds after the shard
        // loop's own "now" snapshot was taken earlier in this same iteration - clamp rather than reject that
        // measurement-order noise as a real clock regression.
        var checkpoint=new TransportCapsule.Checkpoint(Math.max(0,now-origin),nextSequence,health.generation(),recordingEpoch,
            protocolState,captureEnabled,effectsMode,snapshot.lastIngressOrdinal(),snapshot.certifiedIngressOrdinal(),
            snapshot.lastProcessingOrdinal(),snapshot.lastServerRevision());
        capture=new LiveCapture(quota,epoch,activeSessions,checkpoint);originFixed=true;// the checkpoint frame already used this origin
        generation=health.generation();
    }
    @Override public void close(){if(closed)return;closed=true;if(capture!=null)capture.close();}
}
