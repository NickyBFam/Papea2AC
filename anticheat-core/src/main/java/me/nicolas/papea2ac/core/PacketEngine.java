package me.nicolas.papea2ac.core;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.api.CoverageSummary;
/** Fixed shards. Lifecycle lock is admission-only, never used by packet callbacks. */
public final class PacketEngine implements AutoCloseable {
    public final class Session {
        public final SessionKey key;public final HealthLatch health=new HealthLatch();
        public final ConnectionIngress ingress;public final TruthActor actor;
        private final AtomicBoolean closing=new AtomicBoolean();
        private final AtomicLong closeStarted=new AtomicLong();
        private final AtomicBoolean cleanupAlerted=new AtomicBoolean();
        private final WorkQuota quota=new WorkQuota(2_000_000,200_000,System.nanoTime());
        private me.nicolas.papea2ac.protocol.Observation deferred;
        private boolean actorClosed;
        /** Nullable, owner-shard-written/volatile-published: recording may not exist at connect time and can be lazily armed later (writer becomes available, or an explicit resume) by the owning shard only; other threads only ever read it for diagnostics. */
        private volatile SessionRecording recording;
        private Session(SessionKey key,int activeSessions,ConnectionIngress.EventLoop loop,TransportCapability capability,
                java.util.function.Function<me.nicolas.papea2ac.protocol.Observation.Event,me.nicolas.papea2ac.protocol.Observation.Context> context){
            this.key=key;recording=writer==null?null:new SessionRecording(replayQuota,key.epoch(),activeSessions);ingress=new ConnectionIngress(key,loop,health,1024,context,loss->{
                metrics.increment(switch(loss){
                    case INGRESS_FULL -> PacketMetrics.Kind.INGRESS_DROPPED;
                    case BRIDGE_FULL -> PacketMetrics.Kind.BRIDGE_DROPPED;
                    case DISPATCH_REJECTED -> PacketMetrics.Kind.DISPATCH_REJECTED;
                });
            });
            actor=new TruthActor(key,health,capability,metrics,256);
        }
        public void close(){closeStarted.compareAndSet(0,System.nanoTime());closing.set(true);ingress.close();}
        /** Diagnostic-only: current recording arm state, for tests/monitoring. Not for control decisions (racy against the shard). */
        public boolean recordingArmed(){var r=recording;return r!=null&&r.armed();}
        public boolean recordingComplete(){var r=recording;return r==null||r.complete();}
    }
    private final class Shard implements Runnable {
        final Thread thread;final AtomicReference<List<Session>> sessions=new AtomicReference<>(List.of());
        final AtomicBoolean fenced=new AtomicBoolean();
        volatile long heartbeat=System.nanoTime();
        Shard(int index){thread=new Thread(this,"Papea2AC-shard-"+index);thread.setDaemon(true);}
        public void run(){
            try{
                while(!fenced.get()&&(!stopping.get()||!sessions.get().isEmpty())){
                    heartbeat=System.nanoTime();
                    for(Session s:sessions.get()){
                        if(s.closing.get()||stopping.get()){
                            if(!s.actorClosed){
                                if(s.recording!=null){s.recording.before(s.health.get(),System.nanoTime());s.recording.finish(System.nanoTime());s.recording.export(writer,replayConfiguration);s.recording.close();}
                                s.actor.close();s.deferred=null;s.actorClosed=true;
                            }
                            if(s.ingress.releaseAfterClose())remove(s);
                            continue;
                        }
                        try{
                            long clock=System.nanoTime();var before=s.actor.snapshot();
                            if(s.recording!=null)s.recording.before(s.health.get(),clock);
                            s.actor.tick(clock);
                            if(s.recording!=null&&before.pending()!=s.actor.snapshot().pending())s.recording.clock(clock);
                            int work=0;
                            for(int i=0;i<32;i++){
                                var o=s.deferred!=null?s.deferred:s.ingress.poll();if(o==null)break;
                                int cost=1+(o.chargedBytes()+31)/32;
                                if(cost<=2048&&work+cost>2048){s.deferred=o;break;}
                                if(cost>2048||!s.quota.take(cost,System.nanoTime())){
                                    s.health.invalidate(HealthLatch.Reason.WORK_QUOTA,o.sequence());s.deferred=null;break;
                                }
                                s.deferred=null;work+=cost;long now=System.nanoTime();
                                var boundary=s.health.get();
                                boolean administrative=o.event() instanceof Observation.ControlChange||o.event() instanceof Observation.CheckpointMarker;
                                if(s.recording!=null&&!administrative){s.recording.before(boundary,now);s.recording.input(o,now);}
                                s.actor.process(o,now);
                                if(s.recording!=null&&boundary.generation()!=s.health.get().generation())s.recording.incomplete();
                                if(administrative)applyControl(s,o,now);
                            }
                        }catch(RuntimeException ex){
                            s.actor.fail();s.close();
                            errors.accept(ex);
                        }
                    }
                    LockSupport.parkNanos(100_000);
                }
            }finally{fenced.set(true);for(Session s:sessions.get()){s.health.invalidate(HealthLatch.Reason.SHARD_FAILED,0);s.ingress.close();}}
        }
        /** Runs only inside this shard's exclusive per-session loop, immediately after the marker's own ordered
         * position was processed by the actor - so any checkpoint it produces reflects one coherent, ordered
         * instant (session ownership/order mechanism), never a racy read of independently mutable fields. */
        void applyControl(Session s,me.nicolas.papea2ac.protocol.Observation o,long now){
            switch(o.event()){
                case Observation.ControlChange cc when cc.kind().equals("RECORDING") -> {
                    boolean enable=Boolean.parseBoolean(cc.value());
                    if(enable){
                        if(s.recording==null)s.recording=SessionRecording.idle(s.key.epoch());
                        if(!s.recording.armed()){
                            s.recording.resume(replayQuota,Math.max(1,activeSessionCount),s.actor.snapshot().processedSequence()+1,
                                s.actor.snapshot(),s.health.get(),o.context().protocolState().name(),captureEnabled,effectsModeSnapshot.name(),now);
                            metrics.increment(PacketMetrics.Kind.CHECKPOINT_CREATED);
                        }
                    }else if(s.recording!=null&&s.recording.armed()){
                        s.recording.pause(writer,replayConfiguration,now);metrics.increment(PacketMetrics.Kind.RECORDING_PAUSED);
                    }
                    metrics.increment(PacketMetrics.Kind.CONTROL_APPLIED);
                }
                case Observation.ControlChange cc -> metrics.increment(PacketMetrics.Kind.CONTROL_APPLIED);
                case Observation.CheckpointMarker ignored -> {
                    metrics.latency(PacketMetrics.Timer.CHECKPOINT_REQUEST_TO_EXPORT,now-o.capturedNanos());
                    if(s.recording!=null)s.recording.export(writer,replayConfiguration);
                }
                default -> {}
            }
        }
        void remove(Session s){
            sessions.updateAndGet(old->{var copy=new ArrayList<>(old);copy.remove(s);return List.copyOf(copy);});
            synchronized(PacketEngine.this){active.remove(s.key.player(),s);reservedBytes-=SESSION_BYTES;activeSessionCount=active.size();finishReplayShutdown();}
        }
    }
    private static final long SESSION_BYTES=2L*1024*1024,GLOBAL_BYTES=256L*1024*1024;
    private final List<Shard> shards=new ArrayList<>();private final Map<UUID,Session> active=new HashMap<>();
    private final AtomicLong epochs=new AtomicLong();
    private final AtomicLong checkpointRequestIds=new AtomicLong();
    private final AtomicBoolean stopping=new AtomicBoolean();
    private volatile boolean captureEnabled=true;
    /** Snapshot of the main-thread-owned RuntimeControl mode, safe for cross-thread checkpoint/diagnostic reads (RuntimeControl.mode() itself asserts owner-thread). */
    private volatile RuntimeControl.Mode effectsModeSnapshot=RuntimeControl.Mode.OBSERVE_ONLY;
    /** Best-effort current active-session count, read by shard threads to compute adaptive replay retention shares; a heuristic, not a correctness-critical value. */
    private volatile int activeSessionCount;
    private final AtomicBoolean writerHungAlerted=new AtomicBoolean();
    private final RuntimeControl controls=new RuntimeControl(1024);
    private final me.nicolas.papea2ac.replay.ReplayQuota replayQuota=new me.nicolas.papea2ac.replay.ReplayQuota();
    private volatile me.nicolas.papea2ac.replay.CaptureWriter writer;
    private volatile String replayConfiguration;
    /** Recording may now start at connect time, or later while sessions are already in PLAY: every currently active session is offered recording via the same ordered per-session control path used for any other runtime control. */
    public synchronized void enableRecording(java.nio.file.Path directory,String configurationDigest){
        if(writer!=null||stopping.get())throw new IllegalStateException("recording startup only");
        if(!configurationDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("configuration digest");
        replayConfiguration=configurationDigest;
        writer=new me.nicolas.papea2ac.replay.CaptureWriter(replayQuota,me.nicolas.papea2ac.replay.CaptureWriter.directory(directory),this::reportFailure);
        for(Session s:active.values())s.ingress.bridge(new Observation.ControlChange("RECORDING","true"));
    }
    /** Ordered checkpoint/export request: takes effect at this session's next processed position in its own observation sequence, not on arbitrary thread timing. */
    public boolean requestReplay(UUID player){
        Session s=sessionOf(player);
        if(s==null||writer==null)return false;
        return s.ingress.bridge(new Observation.CheckpointMarker(checkpointRequestIds.incrementAndGet()));
    }
    /** Ordered per-session recording (re)start/stop, distinct from setCaptureEnabled's engine-wide admission gate. */
    public boolean setRecordingEnabled(UUID player,boolean enabled){
        Session s=sessionOf(player);
        return s!=null&&s.ingress.bridge(new Observation.ControlChange("RECORDING",Boolean.toString(enabled)));
    }
    private synchronized Session sessionOf(UUID player){return active.get(player);}
    public synchronized String replayStatus(){return writer==null?"DISABLED":"written="+writer.written()+" rejected="+writer.rejected()+" failed="+writer.failed()+" rollingBytes="+replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.ROLLING);}
    private void finishReplayShutdown(){
        if(stopping.get()&&reservedBytes==GLOBAL_BYTES){if(writer!=null)writer.close();replayQuota.closeAdmission();}
    }
    private final long admittedLimit;private final int capacity;private long reservedBytes=GLOBAL_BYTES;
    public final PacketMetrics metrics=new PacketMetrics();
    private final java.util.function.Consumer<Throwable> errors;
    public PacketEngine(int workers,long budgetBytes,java.util.function.Consumer<Throwable> errors){
        this(workers,workers*128,budgetBytes,errors);
    }
    public PacketEngine(int workers,int capacity,long budgetBytes,java.util.function.Consumer<Throwable> errors){
        if(workers<1||workers>8||budgetBytes<GLOBAL_BYTES*5/4)throw new IllegalArgumentException("configuration floor");
        if(capacity<1||capacity>workers*128)throw new IllegalArgumentException("session capacity");
        this.capacity=capacity;
        this.errors=Objects.requireNonNull(errors);admittedLimit=budgetBytes-budgetBytes/5;
        for(int i=0;i<workers;i++){Shard shard=new Shard(i);shards.add(shard);shard.thread.start();}
    }
    public synchronized Session connect(UUID player,ConnectionIngress.EventLoop loop,TransportCapability capability){
        return connect(player,loop,capability,e->me.nicolas.papea2ac.protocol.Observation.Context.synthetic());
    }
    public synchronized Session connect(UUID player,ConnectionIngress.EventLoop loop,TransportCapability capability,
            java.util.function.Function<me.nicolas.papea2ac.protocol.Observation.Event,me.nicolas.papea2ac.protocol.Observation.Context> context){
        if(stopping.get())throw new IllegalStateException("engine closed");
        if(!captureEnabled)throw new IllegalStateException("capture disabled");
        Session old=active.get(player);
        if(old==null&&active.size()>=capacity)throw new IllegalStateException("session capacity admission");
        Shard shard=shards.stream().filter(s->!s.fenced.get()&&s.thread.isAlive()&&s.sessions.get().size()<128)
            .min(Comparator.comparingInt(s->s.sessions.get().size())).orElseThrow(()->new IllegalStateException("no healthy shard"));
        if(reservedBytes+SESSION_BYTES>admittedLimit)throw new IllegalStateException("memory admission");
        long epoch=epochs.incrementAndGet();if(epoch<=0)throw new IllegalStateException("epoch exhausted");
        Session session=new Session(new SessionKey(player,epoch),active.size()+1,loop,capability,context);
        if(old!=null)old.close();
        reservedBytes+=SESSION_BYTES;active.put(player,session);activeSessionCount=active.size();
        shard.sessions.updateAndGet(list->{var copy=new ArrayList<>(list);copy.add(session);return List.copyOf(copy);});
        return session;
    }
    /** Reap only proven-dead writers; hung writers stay fenced, rooted and charged. */
    public synchronized void supervise(){
        long now=System.nanoTime();
        for(int i=0;i<shards.size();i++){
            Shard shard=shards.get(i);
            if(shard.thread.isAlive()&&now-shard.heartbeat>5_000_000_000L)fenceShard(i);
            for(Session session:shard.sessions.get()){
                if(session.closing.get()&&now-session.closeStarted.get()>5_000_000_000L&&session.cleanupAlerted.compareAndSet(false,true)){
                    metrics.increment(PacketMetrics.Kind.CLEANUP_STALLED);
                    errors.accept(new IllegalStateException("Session cleanup exceeded five seconds; transport/writer lease remains charged"));
                }
            }
        }
        var w=writer;
        if(w!=null){
            var health=w.health(now);
            if(health==me.nicolas.papea2ac.replay.CaptureWriter.Health.HUNG&&writerHungAlerted.compareAndSet(false,true)){
                metrics.increment(PacketMetrics.Kind.WRITER_HUNG);
                errors.accept(new IllegalStateException("Replay writer made no progress beyond the hung threshold; leases remain charged, no unsafe cleanup performed"));
            }else if(health!=me.nicolas.papea2ac.replay.CaptureWriter.Health.HUNG){
                writerHungAlerted.set(false);
            }
        }
        for(Shard shard:shards)if(!shard.thread.isAlive()&&shard.fenced.get()){
            for(Session s:shard.sessions.get()){
                s.ingress.close();
                if(s.ingress.releaseAfterClose()){
                    s.actor.retireAfterWriterTermination();s.deferred=null;
                    if(s.recording!=null&&!s.actorClosed){s.recording.incomplete();s.recording.export(writer,replayConfiguration);s.recording.close();s.actorClosed=true;}
                    shard.remove(s);
                }
            }
        }
    }
    /** Cooperatively fence a failed/unresponsive shard. Never reclaim while its writer is alive. */
    public synchronized void fenceShard(int index){
        Shard shard=shards.get(index);shard.fenced.set(true);
        for(Session s:shard.sessions.get()){
            s.health.invalidate(HealthLatch.Reason.SHARD_FAILED,0);s.close();
        }
        LockSupport.unpark(shard.thread);
    }
    public synchronized long reservedBytes(){return reservedBytes;}
    /** Platform owner-thread control. Re-enabling admits fresh epochs; closed sessions never heal. */
    public synchronized void setCaptureEnabled(boolean enabled){
        if(stopping.get())throw new IllegalStateException("engine closed");
        controls.mode();captureEnabled=enabled;
        if(!enabled)for(Session session:active.values())session.close();
    }
    public boolean captureEnabled(){return captureEnabled;}
    /** Suspends automated effects to the given restriction, broadcasting an ordered marker into every active session's own observation sequence so replay/diagnostics can tell which control state applied to which observation (a control epoch). Never reaches ENFORCE; that remains reachable only through RuntimeControl.enable(), which this engine never calls. */
    public synchronized void suspendEffects(){applyEffectsMode(RuntimeControl.Mode.DISABLED);controls.restrict(RuntimeControl.Mode.DISABLED);}
    /** Returns automated effects to the safe startup default (OBSERVE_ONLY). */
    public synchronized void resumeEffects(){applyEffectsMode(RuntimeControl.Mode.OBSERVE_ONLY);controls.resumeObservation();}
    private void applyEffectsMode(RuntimeControl.Mode mode){
        effectsModeSnapshot=mode;
        for(Session s:active.values())s.ingress.bridge(new Observation.ControlChange("EFFECTS_MODE",mode.name()));
    }
    public boolean effectsSuspended(){return effectsModeSnapshot!=RuntimeControl.Mode.ENFORCE;}
    public void reportFailure(Throwable failure){metrics.increment(PacketMetrics.Kind.FAILURE);errors.accept(failure);}
    public synchronized int sessions(){return active.size();}
    public synchronized CoverageSummary coverage(UUID player){
        Session s=active.get(player);
        if(s==null)return new CoverageSummary(false,false,false,"NOT_ADMITTED",0,0,0,0,0);
        var state=s.actor.snapshot();
        return new CoverageSummary(true,!s.ingress.closed(),state.certificationAvailable(),
            state.certificationAvailable()?"CERTIFIED_PACKET_OUTCOME":state.reason().name()+"_OBSERVE_ONLY",
            state.processedSequence(),state.certifiedIngressOrdinal(),state.pending(),s.ingress.queueDepth(),s.ingress.queueCapacity());
    }
    public synchronized void close(){stopping.set(true);finishReplayShutdown();for(Shard shard:shards){for(Session s:shard.sessions.get())s.close();LockSupport.unpark(shard.thread);}}
    public boolean terminated(){return shards.stream().noneMatch(s->s.thread.isAlive());}
    /** Fail-closed per-session guard states; NOT_ADMITTED sessions report every guard false rather than an unknown/guessed state. */
    public synchronized GuardStatus guardStatus(UUID player){
        Session s=active.get(player);
        if(s==null)return new GuardStatus(false,false,effectsSuspended(),false,false,false);
        var state=s.actor.snapshot();
        boolean overloaded=state.reason()==HealthLatch.Reason.OVERLOADED||s.ingress.queueDepth()>=s.ingress.queueCapacity();
        return new GuardStatus(true,state.certificationAvailable(),effectsSuspended(),overloaded,s.recordingArmed(),s.recordingComplete());
    }
    public synchronized BudgetStatus budgetStatus(){
        var w=writer;
        return new BudgetStatus(capacity,active.size(),admittedLimit,reservedBytes,
            replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.ROLLING),48L<<20,
            replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION),
            replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO),me.nicolas.papea2ac.replay.CaptureWriter.QUEUE_CAPACITY,
            w==null?0:w.written(),w==null?0:w.rejected(),w==null?0:w.failed());
    }
    /** Bounded, fixed-shape export: only aggregates, safe to publish regardless of session count (see MonitoringSnapshot). */
    public synchronized MonitoringSnapshot monitoringSnapshot(){
        int certified=0,overloaded=0,queueOccupancy=0,queueCapacityTotal=0,journalBytes=0;
        for(Session s:active.values()){
            var state=s.actor.snapshot();
            if(state.certificationAvailable())certified++;
            if(state.reason()==HealthLatch.Reason.OVERLOADED)overloaded++;
            queueOccupancy+=s.ingress.queueDepth();queueCapacityTotal+=s.ingress.queueCapacity();journalBytes+=state.journalBytes();
        }
        var w=writer;long now=System.nanoTime();
        return new MonitoringSnapshot(captureEnabled,effectsSuspended(),effectsModeSnapshot.name(),
            active.size(),certified,overloaded,queueOccupancy,queueCapacityTotal,journalBytes,
            replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.ROLLING),
            replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.PROMOTION),
            replayQuota.used(me.nicolas.papea2ac.replay.ReplayQuota.Pool.IO),
            w==null?"DISABLED":w.health(now).name(),w==null?0:w.written(),w==null?0:w.rejected(),w==null?0:w.failed(),
            metrics.count(PacketMetrics.Kind.UNKNOWN),metrics.count(PacketMetrics.Kind.OVERLOAD),
            metrics.count(PacketMetrics.Kind.CHECKPOINT_CREATED),metrics.count(PacketMetrics.Kind.RECORDING_PAUSED));
    }
}
