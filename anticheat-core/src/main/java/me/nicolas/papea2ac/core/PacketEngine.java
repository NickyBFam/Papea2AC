package me.nicolas.papea2ac.core;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.api.CoverageSummary;
import me.nicolas.papea2ac.player.PlayerObservation;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.world.*;
/** Fixed shards. Lifecycle lock is admission-only, never used by packet callbacks. */
public final class PacketEngine implements AutoCloseable {
    public final class Session {
        public final SessionKey key;public final HealthLatch health=new HealthLatch();
        public final ConnectionIngress ingress;public final TruthActor actor;public final PlayerTimeline timeline;public final ObserverWorld world;
        /** Phase 7: the session's timing ledger (player-owned, shard-written, published view readable anywhere) and the player's own entity id once the platform reports it (-1 = unknown: velocity packets cannot be attributed yet). */
        public final me.nicolas.papea2ac.player.TimingLedger timing;volatile int selfEntityId=-1;
        long lastMarkerNanos,lastTimingExpireNanos,timingBreakGeneration=-1;int markerCounter;boolean bracketMarkerDue;
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
            this.key=key;recording=writer==null?null:new SessionRecording(replayQuota,key.epoch(),activeSessions);ingress=new ConnectionIngress(key,loop,health,4096,context,loss->{// 4096 records: one real tick's flush (P3-F7); arena bytes above the guaranteed slice and all bulk come from the engine-wide infrastructure pool
                metrics.increment(switch(loss){
                    case INGRESS_FULL -> PacketMetrics.Kind.INGRESS_DROPPED;
                    case BRIDGE_FULL -> PacketMetrics.Kind.BRIDGE_DROPPED;
                    case DISPATCH_REJECTED -> PacketMetrics.Kind.DISPATCH_REJECTED;
                    case BULK_SHED -> PacketMetrics.Kind.BULK_SHED;
                });
            },infrastructurePool);
            actor=new TruthActor(key,health,capability,metrics,256);
            timeline=new PlayerTimeline(key);
            world=new ObserverWorld(key,worldBudget,blockStates);
            timing=new me.nicolas.papea2ac.player.TimingLedger(key);
        }
        public void close(){closeStarted.compareAndSet(0,System.nanoTime());closing.set(true);ingress.close();}
        /** The capture position itself is compromised (our handler removed or displaced in the pipeline): clientbound packets may now pass unobserved.
         *  The platform reports it here (in addition to the Phase 1 latch); the shard turns the first report into a world CoverageBreak on the next ordered
         *  observation (bridged server ticks keep arriving even when the channel handlers are gone). The latch reason PIPELINE_UNCOVERED itself is NOT a
         *  world break: on a real session it is raised by uncovered inbound/outbound packets that carry no world state (P3-F12). */
        public void captureCompromised(String reason){captureCompromised.compareAndSet(null,reason==null||reason.isEmpty()?"UNSPECIFIED":reason);}
        final AtomicReference<String> captureCompromised=new AtomicReference<>();
        /** Diagnostic-only: current recording arm state, for tests/monitoring. Not for control decisions (racy against the shard). */
        public boolean recordingArmed(){var r=recording;return r!=null&&r.armed();}
        public boolean recordingComplete(){var r=recording;return r==null||r.complete();}
    }
    /** Ordered world ingestion for one observation: bundle children keep wire order via subIndex; a world-silent observation still advances the "observed through" bound (player/world coherence). */
    private static void applyWorld(Session s,Observation o,long now,String worldTransport){
        int base=0;String compromised=s.captureCompromised.getAndSet(null);
        if(compromised!=null){String reason="CAPTURE_COMPROMISED:"+compromised;s.world.apply(s.key,new WorldObservation.CoverageBreak(reason.length()>64?reason.substring(0,64):reason),o.sequence(),0,now,worldTransport);base=1;}
        var worldObservations=WorldObservationMapper.map(o);
        for(int w=0;w<worldObservations.size();w++)s.world.apply(s.key,worldObservations.get(w),o.sequence(),base+w,now,worldTransport);
        if(worldObservations.isEmpty()&&base==0)s.world.observeSequence(s.key,o.sequence(),now);
    }
    private final class Shard implements Runnable {
        final Thread thread;final AtomicReference<List<Session>> sessions=new AtomicReference<>(List.of());
        final AtomicBoolean fenced=new AtomicBoolean();
        volatile long heartbeat=System.nanoTime();
        Shard(int index){thread=new Thread(this,"Papea2AC-shard-"+index);thread.setDaemon(true);}
        public void run(){
            try{
                while(!fenced.get()&&(!stopping.get()||!sessions.get().isEmpty())){
                    heartbeat=System.nanoTime();boolean busy=false;
                    for(Session s:sessions.get()){
                        if(s.closing.get()||stopping.get()){
                            if(!s.actorClosed){
                                // The final export must never prevent the session's own release: at server stop Bukkit may already have closed the plugin jar
                                // (lazy class loading then throws), and the actor/world below still have to release their shared charge (P3-F13, real-client run).
                                if(s.recording!=null){try{s.recording.before(s.health.get(),System.nanoTime());s.recording.finish(System.nanoTime());s.recording.export(writer,replayConfiguration);}catch(RuntimeException|LinkageError e){errors.accept(e);}finally{try{s.recording.close();}catch(RuntimeException e){errors.accept(e);}}}
                                s.actor.close();s.deferred=null;s.actorClosed=true;
                                s.timeline.append(s.key,new PlayerObservation.SessionClosed(),s.timeline.snapshot().lastCommittedSequence()+1,System.nanoTime());
                                s.world.close();s.timing.close();
                            }
                            if(s.ingress.releaseAfterClose())remove(s);
                            continue;
                        }
                        try{
                            long clock=System.nanoTime();var before=s.actor.snapshot();
                            if(s.recording!=null)s.recording.before(s.health.get(),clock);
                            s.actor.tick(clock);
                            if(s.recording!=null&&before.pending()!=s.actor.snapshot().pending())s.recording.clock(clock);
                            if(clock-s.lastTimingExpireNanos>1_000_000_000L){s.lastTimingExpireNanos=clock;s.timing.expire(clock);}
                            issueMarker(s,clock);
                            int work=0;
                            for(int i=0;i<32;i++){
                                var o=s.deferred!=null?s.deferred:s.ingress.poll();if(o==null)break;
                                int cost=1+(o.chargedBytes()+31)/32;
                                // The 2048-unit slice is a fairness bound, never a size limit: an oversize observation (a near-maximal Bundle) runs alone in
                                // its own pass. Quota exhaustion DEFERS (backpressure through the bounded ingress, whose overflow is an accounted drop) -
                                // the previous "deferred=null" silently dropped the observation, which the world exposed as a SEQUENCE_GAP without any
                                // INGRESS_DROPPED during ordinary play (P3-F6, real-client qualification).
                                if(work>0&&work+cost>2048){s.deferred=o;break;}
                                if(!s.quota.take(Math.min(cost,200_000),System.nanoTime())){
                                    s.health.invalidate(HealthLatch.Reason.WORK_QUOTA,o.sequence());metrics.increment(PacketMetrics.Kind.WORK_QUOTA_DEFERRED);s.deferred=o;break;
                                }
                                busy=true;
                                s.deferred=null;work+=cost;long now=System.nanoTime();
                                var boundary=s.health.get();
                                boolean administrative=o.event() instanceof Observation.ControlChange||o.event() instanceof Observation.CheckpointMarker;
                                if(s.recording!=null&&!administrative){s.recording.before(boundary,now);s.recording.input(o,now);}
                                s.actor.process(o,now);
                                // Session.close() (any thread) sets closing=true and then invalidates health to CLOSED via
                                // ConnectionIngress.close(). If that lands between the boundary read above and this check, the
                                // generation bump is a deliberate close, not data loss: the closing branch will finish() the
                                // recording as complete. Only a bump that is NOT that deliberate close is a real degrade.
                                var after=s.health.get();
                                boolean deliberateClose=after.reason()==HealthLatch.Reason.CLOSED&&s.closing.get();
                                boolean degraded=boundary.generation()!=after.generation()&&!deliberateClose;
                                // Sticky Phase 1 health travels with every record (null = healthy), so a session already
                                // degraded before this observation still yields uncertain records, not just the one that bumped.
                                String transport=after.healthy()||deliberateClose?null:after.reason().name();
                                // The world only breaks on reasons that can mean lost/undecoded CLIENTBOUND observation (P3-F3); outcome-correlation reasons keep it healthy.
                                String worldTransport=WorldObservationMapper.coverageBreakReason(after,deliberateClose);
                                // Timing coverage breaks only on loss-class latch reasons (the same filter as the world, P3-F3): the permanent correlation limitation
                                // (PIPELINE_UNCOVERED from the client's brand payload, EXPIRED/OUTCOME_UNKNOWN journal outcomes) loses no tick end and no marker, so it
                                // must not push every real session into the bounded fallback alignment (P7-F4, real-client qualification; brief 56/57).
                                // One break per health generation that carries a loss-class reason, whether the latch tripped inside this observation's processing or
                                // before it (an ingress overflow invalidates at capture time, before the shard reads its boundary); the observation itself is the break.
                                String timingBreak=WorldObservationMapper.coverageBreakReason(after,deliberateClose);
                                if(timingBreak!=null&&after.generation()!=s.timingBreakGeneration){s.timingBreakGeneration=after.generation();s.timing.apply(s.key,new me.nicolas.papea2ac.player.TimingEvent.CoverageBreak(o.sequence(),o.capturedNanos(),timingBreak));}
                                else{
                                    var te=TimingObservationMapper.map(o,s.selfEntityId,s.timeline.currentState().worldEpoch()+(o.event() instanceof Observation.Outbound ob&&ob.payload() instanceof me.nicolas.papea2ac.protocol.SemanticPayload.Respawn?1:0));
                                    if(te.isPresent()){
                                        var event=te.get();
                                        if(event instanceof me.nicolas.papea2ac.player.TimingEvent.ExternalMotionSent||event instanceof me.nicolas.papea2ac.player.TimingEvent.TeleportSent)s.bracketMarkerDue=true;
                                        if(event instanceof me.nicolas.papea2ac.player.TimingEvent.MarkerAcknowledged){long beforeSamples=s.timing.view().rtt().samples();s.timing.apply(s.key,event);var after2=s.timing.view();if(after2.rtt().samples()>beforeSamples){timingMetrics.rttSamples.increment();timingMetrics.markersAnswered.increment();}else timingMetrics.lateOrUnknownAcks.increment();}
                                        else s.timing.apply(s.key,event);
                                    }
                                    if(o.event() instanceof Observation.Outbound ob2&&ob2.payload() instanceof me.nicolas.papea2ac.protocol.PacketPayload.Velocity&&s.selfEntityId<0)timingMetrics.velocityIgnoredUnknownSelf.increment();
                                }
                                if(degraded){
                                    s.timeline.append(s.key,new PlayerObservation.CoverageGap(after.reason().name()),o.sequence(),now,transport);
                                    if(worldTransport!=null)s.world.apply(s.key,new WorldObservation.CoverageBreak(worldTransport),o.sequence(),0,now,worldTransport);
                                    else applyWorld(s,o,now,null);// the bump does not concern clientbound observation: the world still consumes this observation
                                }else{
                                    PlayerObservationMapper.map(o,s.selfEntityId).ifPresent(po->s.timeline.append(s.key,po,o.sequence(),now,transport));
                                    applyWorld(s,o,now,worldTransport);
                                }
                                if(s.recording!=null&&degraded)s.recording.incomplete();
                                if(administrative)applyControl(s,o,now);
                            }
                        }catch(RuntimeException ex){
                            s.actor.fail();s.close();
                            errors.accept(ex);
                        }
                    }
                    // Park only when the pass found nothing to do. The 32-per-session slice stays (fairness across sessions), but a busy
                    // pass loops straight into the next one: a park is >= 1 ms on Windows (timer granularity), which capped drain at
                    // ~32 observations/ms and let a real join flood (thousands of packets in one tick) overflow the ingress (P3-F5).
                    if(busy)Thread.onSpinWait();else LockSupport.parkNanos(100_000);
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
                case Observation.ControlChange cc when cc.kind().equals("SELF_ENTITY") -> {
                    try{s.selfEntityId=Integer.parseInt(cc.value());}catch(NumberFormatException ignored){}
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
    /** Engine-wide observer-world accounting (PERFORMANCE.md shared-world partition + per-session private tier) and the run-local block-state dictionary; both thread-safe, shared by every session's ObserverWorld. */
    public final WorldBudget worldBudget=new WorldBudget();
    public final BlockStateDictionary blockStates=new BlockStateDictionary();
    /** Phase 4: one pure geometry engine per PacketEngine (its shape cache is keyed by the engine-wide dictionary). Constructed here - core is the composition root (ARCHITECTURE path 1); it never reads the live server world. */
    public final me.nicolas.papea2ac.collision.CollisionEngine collision=new me.nicolas.papea2ac.collision.CollisionEngine(me.nicolas.papea2ac.version.CollisionProfile.PAPER_26_2,blockStates);
    /** Phase 5: the pinned movement profile, the world-owned block behaviour resolver over the engine-wide dictionary, and the stateless forward simulator (ARCHITECTURE path 1: core constructs physics; platform-paper only displays primitives). */
    public final me.nicolas.papea2ac.version.MovementProfile movementProfile=me.nicolas.papea2ac.version.MovementProfile.paper26_2();
    public final me.nicolas.papea2ac.world.BlockBehaviorQuery blockBehaviors=new me.nicolas.papea2ac.world.BlockBehaviorQuery(movementProfile.blocks(),collision.profile().table(),blockStates);
    public final me.nicolas.papea2ac.physics.PhysicsSimulator physics=new me.nicolas.papea2ac.physics.PhysicsSimulator(movementProfile);
    /** Bounded real-client qualification tracer (declared-input scenarios replayed against observed samples); reads published snapshots only. */
    public final PhysicsQualification physicsQualification=new PhysicsQualification(physics,collision,blockBehaviors,this::timelineStatus);
    /** Phase 6: the stateless input reconstructor over the Phase 5 simulator (ARCHITECTURE path 1: core constructs simulation; platform-paper only displays primitives) and its bounded diagnostic / real-client tracer. */
    public final me.nicolas.papea2ac.simulation.InputReconstructor reconstruction=new me.nicolas.papea2ac.simulation.InputReconstructor(physics);
    public final ReconstructionTracer reconstructionTracer=new ReconstructionTracer(reconstruction,collision,blockBehaviors,this::timelineStatus);
    /** Phase 7: the version-scoped timing profile, the bounded timing diagnostic/qualification tracer, the optional marker port (platform-supplied; absent = keep-alive evidence only) and bounded engine-wide counters. */
    public final me.nicolas.papea2ac.version.TimingProfile timingProfile=me.nicolas.papea2ac.version.TimingProfile.PAPER_26_2;
    public final TimingTracer timingTracer=new TimingTracer(reconstruction,collision,blockBehaviors,timingProfile);
    private volatile MarkerPort markerPort;
    /** Marker cadence: one ping per second per session while the platform port exists, plus one right after an outbound influence/correction (bracketing), never more than one per 50 ms, never with more than 16 pending. */
    public static final long MARKER_PERIOD_NANOS=1_000_000_000L,MARKER_MIN_GAP_NANOS=50_000_000L;public static final int MARKER_MAX_PENDING=16;
    static final class TimingMetrics{final java.util.concurrent.atomic.LongAdder markersIssued=new java.util.concurrent.atomic.LongAdder(),markerSendFailures=new java.util.concurrent.atomic.LongAdder(),markersAnswered=new java.util.concurrent.atomic.LongAdder(),rttSamples=new java.util.concurrent.atomic.LongAdder(),lateOrUnknownAcks=new java.util.concurrent.atomic.LongAdder(),velocityIgnoredUnknownSelf=new java.util.concurrent.atomic.LongAdder();}
    final TimingMetrics timingMetrics=new TimingMetrics();
    public void setMarkerPort(MarkerPort port){markerPort=port;}
    /** Shard-thread marker issue: a session-scoped id (epoch in the high byte, a counter below) whose pong the ledger correlates; late pongs of an earlier session never match (new ledger, new epoch byte). */
    void issueMarker(Session s,long clock){
        MarkerPort port=markerPort;if(port==null||s.selfEntityId<0)return;
        boolean periodic=clock-s.lastMarkerNanos>=MARKER_PERIOD_NANOS,bracket=s.bracketMarkerDue&&clock-s.lastMarkerNanos>=MARKER_MIN_GAP_NANOS;
        if(!periodic&&!bracket)return;
        if(s.timing.view().markers().pending()>=MARKER_MAX_PENDING){if(periodic)s.lastMarkerNanos=clock;return;}
        s.lastMarkerNanos=clock;s.bracketMarkerDue=false;
        int id=(int)(((s.key.epoch()&0xFF)<<24)|(++s.markerCounter&0xFFFFFF));
        if(port.sendMarker(s.key.player(),id))timingMetrics.markersIssued.increment();else timingMetrics.markerSendFailures.increment();
    }
    /** PERFORMANCE.md G partition "other queues/bulk/worker scratch/control 32 MiB": the one bound every session's in-flight ingress overflow and bulk content compose into (P3-F7). */
    public final me.nicolas.papea2ac.packet.InfrastructurePool infrastructurePool=new me.nicolas.papea2ac.packet.InfrastructurePool(me.nicolas.papea2ac.packet.InfrastructurePool.DEFAULT_BYTES);
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
        session.ingress.onEnqueue(()->LockSupport.unpark(shard.thread));// a parked (idle) shard wakes on the first observation instead of finishing its park (P3-F5)
        shard.sessions.updateAndGet(list->{var copy=new ArrayList<>(list);copy.add(session);return List.copyOf(copy);});
        LockSupport.unpark(shard.thread);
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
                    s.actor.retireAfterWriterTermination();s.world.retireAfterOwnerTermination();s.deferred=null;
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
    /** Bounded, thread-safe: reads the Timeline's published Snapshot, never its owner-thread-only history. Empty for an unknown/not-admitted player, never a fabricated default state. */
    public synchronized java.util.Optional<PlayerTimeline.Snapshot> timelineSnapshot(UUID player){
        Session s=active.get(player);
        return s==null?java.util.Optional.empty():java.util.Optional.of(s.timeline.snapshot());
    }
    /** Primitive-only diagnostic projection of the published observer-world snapshot. Empty for an unknown player - never a fabricated default. */
    public synchronized java.util.Optional<WorldStatus> worldStatus(UUID player){
        Session s=active.get(player);
        if(s==null)return java.util.Optional.empty();
        var w=s.world.snapshot();var d=w.dimension();
        return java.util.Optional.of(new WorldStatus(s.key.epoch(),w.worldEpoch(),d!=null,d==null?"":d.dimensionId(),d==null?0:d.minY(),d==null?0:d.height(),
            w.revision(),w.lastSequence(),w.chunkCount(),w.completeChunks(),w.partialChunks(),w.contentNotRetainedChunks(),w.transportIncompleteChunks(),
            w.evictedChunks(),w.unloadedChunks(),w.privateBytes(),w.historyBytes(),w.historyEntries(),w.historyEvictions(),w.transportBroken(),w.transportBreakReason(),w.transportBreaks(),w.lastBreakSequence(),w.blockUpdates(),w.lastBlockUpdate(),w.recentBlockUpdates()));
    }
    /** Engine-wide observer-world accounting (fixed shape, no per-player labels). */
    public WorldBudget.Snapshot worldBudgetSnapshot(){return worldBudget.snapshot();}
    /** Engine-wide collision counters (fixed shape), projected to primitives for platform-paper. */
    public CollisionEngineStatus collisionEngineStatus(){
        var m=collision.metrics().snapshot();var t=collision.profile().table();
        return new CollisionEngineStatus(collision.profile().id(),t.digest(),t.stateCount(),t.shapeCount(),t.blockCount(),m.queries(),m.complete(),m.incomplete(),m.unavailable(),m.rejected(),m.truncated(),m.cellsVisited(),m.hits(),m.boxesEmitted(),
            m.shapeCacheHits(),m.shapeCacheMisses(),m.unsupportedStates(),m.contextRequired(),m.dynamicCells(),m.worldUnknownCells(),m.historicalMisses());
    }
    /**
     * Bounded collision readout for one session against the PUBLISHED world snapshot at the PUBLISHED observed position, for the
     * STANDING and CROUCHING boxes explicitly (no silent pose assumption; the pose a player is actually in is Phase 5's business).
     * Cross-thread safe: snapshot views only. Empty for an unknown player. Geometry only - no validity judgment.
     */
    public synchronized java.util.Optional<CollisionStatus> collisionStatus(UUID player){
        Session s=active.get(player);
        if(s==null)return java.util.Optional.empty();
        var w=s.world.snapshot();var view=w.view(blockStates);var st=s.timeline.snapshot().state();var m=st.movement();
        boolean hasPosition=m.observedAtSequence()>0;
        var poses=new java.util.ArrayList<CollisionStatus.PoseReport>(2);
        if(hasPosition)for(var pose:new me.nicolas.papea2ac.collision.PlayerBox.Pose[]{me.nicolas.papea2ac.collision.PlayerBox.Pose.STANDING,me.nicolas.papea2ac.collision.PlayerBox.Pose.CROUCHING}){
            var box=me.nicolas.papea2ac.collision.PlayerBox.of(collision.profile(),pose,m.position().x(),m.position().y(),m.position().z());
            var r=collision.query(view,box,me.nicolas.papea2ac.collision.ShapeContext.body(box.minY()),st.worldEpoch());
            var first=new java.util.ArrayList<String>(CollisionStatus.MAX_BOXES);
            outer:for(var h:r.hits())for(int i=0;i<h.shape().boxCount();i++){if(first.size()>=CollisionStatus.MAX_BOXES)break outer;var b=h.worldBox(i);
                first.add((h.definite()?"":"possible ")+h.status()+" @"+h.x()+","+h.y()+","+h.z()+" ["+b.minX()+","+b.minY()+","+b.minZ()+" -> "+b.maxX()+","+b.maxY()+","+b.maxZ()+"]");}
            var counts=new StringBuilder();
            for(var cs:me.nicolas.papea2ac.collision.CellStatus.values())if(r.count(cs)>0)counts.append(counts.length()>0?" ":"").append(cs.name()).append('=').append(r.count(cs));
            poses.add(new CollisionStatus.PoseReport(pose.name(),box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ(),r.certainty().name(),r.unavailableReason()==null?"":r.unavailableReason().name(),
                r.cellsVisited(),r.hitCount(),r.boxCount(),r.truncated(),r.definitelyObstructed(),r.definitelyClear(),counts.toString(),first));
        }
        return java.util.Optional.of(new CollisionStatus(s.key.epoch(),w.worldEpoch(),w.revision(),w.lastSequence(),hasPosition,m.position().x(),m.position().y(),m.position().z(),collision.profile().id(),poses));
    }
    /** Engine-wide physics identity and diagnostic counters, projected to primitives for platform-paper. */
    public PhysicsEngineStatus physicsEngineStatus(){
        var b=movementProfile.blocks();
        return new PhysicsEngineStatus(movementProfile.id(),movementProfile.digest(),movementProfile.sineDigest(),b.digest(),b.blockCount(),b.fluidStateCount(),movementProfile.effects().size(),movementProfile.attributes().size(),
            physicsQualification.steps.sum(),physicsQualification.exact.sum(),physicsQualification.activeTraces());
    }
    /** Engine-wide input-reconstruction identity and diagnostic counters, projected to primitives for platform-paper (no simulation edge there). */
    public ReconstructionEngineStatus reconstructionEngineStatus(){
        return new ReconstructionEngineStatus(me.nicolas.papea2ac.simulation.InputDomain.RAW_SIZE,me.nicolas.papea2ac.simulation.InputDomain.CLASS_COUNT,me.nicolas.papea2ac.version.MovementObservationProfile.PAPER_26_2.id(),
            me.nicolas.papea2ac.simulation.ReconstructionRequest.SearchBudget.DEFAULT.maxNodes(),me.nicolas.papea2ac.simulation.ReconstructionRequest.SearchBudget.DEFAULT.maxEvaluations(),me.nicolas.papea2ac.simulation.ReconstructionRequest.SearchBudget.DEFAULT.maxTicks(),
            reconstructionTracer.requests.sum(),reconstructionTracer.witnessed.sum(),reconstructionTracer.samplesRecorded.sum(),reconstructionTracer.activeTraces());
    }
    /** Engine-wide Phase 7 counters, primitives only. */
    public TimingEngineStatus timingEngineStatus(){
        long outliers=0,rejected=0,expired=0,stale=0;
        synchronized(this){for(Session s:active.values()){var v=s.timing.view();outliers+=v.rtt().outliers();rejected+=v.rtt().rejected();expired+=v.markers().expired();stale+=v.counters().staleTeleportConfirms();}}
        return new TimingEngineStatus(timingProfile.id(),markerPort!=null,timingMetrics.markersIssued.sum(),timingMetrics.markerSendFailures.sum(),timingMetrics.markersAnswered.sum(),timingMetrics.rttSamples.sum(),outliers,rejected,timingMetrics.lateOrUnknownAcks.sum(),
            expired,stale,timingMetrics.velocityIgnoredUnknownSelf.sum(),timingTracer.activeTraces(),timingTracer.steps.sum(),timingTracer.explained.sum(),timingTracer.unexplained.sum());
    }
    /** The session's published timing view projected to primitives (thread-safe), empty for an unknown player. */
    public synchronized java.util.Optional<TimingStatus> timingStatus(UUID player){
        Session s=active.get(player);if(s==null)return java.util.Optional.empty();
        var v=s.timing.view();var r=v.rtt();var m=v.markers();var c=v.counters();long now=System.nanoTime();
        java.util.List<String> corrections=new java.util.ArrayList<>();
        for(var pt:v.teleports())corrections.add("correction "+pt.teleportId()+" sent@"+pt.sent().sequence()+" -> ("+pt.sent().position().x()+","+pt.sent().position().y()+","+pt.sent().position().z()+") relative="+pt.sent().relativeMask()+(pt.confirmed()?" confirmed@"+pt.confirmedSequence():pt.superseded()?" SUPERSEDED":pt.writeFailed()?" WRITE_FAILED":" pending"));
        return java.util.Optional.of(new TimingStatus(s.key.epoch(),v.worldEpoch(),v.tickEndCapable(),c.clientTickEnds(),c.movements(),c.externalMotions(),c.teleportsSent(),c.teleportsConfirmed(),c.staleTeleportConfirms(),c.evictions(),
            r.hasSamples(),r.lastNanos(),r.minNanos(),r.smoothedNanos(),r.jitterNanos(),r.maxNanos(),r.samples(),r.outliers(),r.rejected(),r.ageNanos(now),
            m.sent(),m.acknowledged(),m.pending(),m.expired(),m.lateOrUnknown(),m.failedWrites(),corrections));
    }
    /** Arms a timing trace (see TimingTracer); {@code declared} may be null. */
    public synchronized String timingArm(UUID player,PhysicsQualification.Scenario declared,PhysicsQualification.ArmFacts facts){
        Session s=active.get(player);
        if(s==null)return "REFUSED: no admitted session";
        var t=timelineStatus(player);if(t.isEmpty())return "REFUSED: no admitted session";
        var w=s.world.snapshot();
        return timingTracer.arm(player,declared,s.timing,t.get(),w.view(blockStates),w.worldEpoch(),s.key.epoch(),facts);
    }
    public java.util.List<String> timingReport(UUID player){return timingTracer.report(player);}
    public String timingCancel(UUID player){return timingTracer.cancel(player);}
    /** Arms an input-reconstruction trace for a session against its PUBLISHED world snapshot (see ReconstructionTracer); {@code declared} may be null. */
    public synchronized String reconstructArm(UUID player,PhysicsQualification.Scenario declared,PhysicsQualification.ArmFacts facts){
        Session s=active.get(player);
        if(s==null)return "REFUSED: no admitted session";
        var w=s.world.snapshot();
        return reconstructionTracer.arm(player,declared,w.view(blockStates),w.worldEpoch(),s.key.epoch(),facts);
    }
    public java.util.List<String> reconstructReport(UUID player){return reconstructionTracer.report(player);}
    public String reconstructCancel(UUID player){return reconstructionTracer.cancel(player);}
    /** Arms a controlled-input physics qualification trace for a session against its PUBLISHED world snapshot (see PhysicsQualification). */
    public synchronized String physicsArm(UUID player,PhysicsQualification.Scenario scenario,PhysicsQualification.ArmFacts facts){
        Session s=active.get(player);
        if(s==null)return "REFUSED: no admitted session";
        var w=s.world.snapshot();
        return physicsQualification.arm(player,scenario,w.view(blockStates),w.worldEpoch(),s.key.epoch(),facts);
    }
    public java.util.List<String> physicsReport(UUID player){return physicsQualification.report(player);}
    public String physicsCancel(UUID player){return physicsQualification.cancel(player);}
    /** One explicit-hypothesis physics step from the published position (never infers input). */
    public synchronized java.util.List<String> physicsOneStep(UUID player,PhysicsQualification.Scenario scenario,PhysicsQualification.ArmFacts facts){
        Session s=active.get(player);
        if(s==null)return java.util.List.of("no admitted session");
        var w=s.world.snapshot();
        return physicsQualification.oneStep(player,scenario,w.view(blockStates),w.worldEpoch(),facts);
    }
    /** Primitive-only diagnostic projection of the published Timeline Snapshot (for platform-paper, which may not depend on anticheat-player). Empty for an unknown player - never a fabricated default. */
    public synchronized java.util.Optional<TimelineStatus> timelineStatus(UUID player){
        Session s=active.get(player);
        if(s==null)return java.util.Optional.empty();
        var snap=s.timeline.snapshot();var st=snap.state();var m=st.movement();var r=st.rotation();var t=st.teleport().active();
        boolean hasPosition=m.observedAtSequence()>0,hasRotation=r.observedAtSequence()>0;
        return java.util.Optional.of(new TimelineStatus(s.key.epoch(),snap.lastCommittedSequence(),snap.recordCount(),snap.recordBytes(),snap.evictions(),snap.lastAppendHadCoverageIssue(),
            st.worldEpoch(),st.revision(),hasPosition,m.position().x(),m.position().y(),m.position().z(),m.observedAtSequence(),hasRotation,r.rotation().yaw(),r.rotation().pitch(),m.onGround(),
            t.isPresent(),t.map(p->p.teleportId()).orElse(-1L),t.map(p->p.acknowledged()).orElse(false),
            st.velocity().active().size(),st.velocity().overflow(),st.tick().hasServerTick(),st.tick().lastServerTick()));
    }
    public synchronized void close(){stopping.set(true);finishReplayShutdown();for(Shard shard:shards){for(Session s:shard.sessions.get())s.close();LockSupport.unpark(shard.thread);}}
    public boolean terminated(){return shards.stream().noneMatch(s->s.thread.isAlive());}
    /** Bounded wait for the shards to drain their closing sessions after close(); the platform calls this from onDisable BEFORE Bukkit closes the plugin classloader (P3-F13). Never blocks past the deadline; returns whether every shard terminated. */
    public boolean awaitTermination(long timeoutMillis){
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0,timeoutMillis));
        for(Shard shard:shards){
            LockSupport.unpark(shard.thread);
            long remaining=deadline-System.nanoTime();
            if(remaining>0){try{shard.thread.join(Math.max(1,remaining/1_000_000L));}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
        }
        return terminated();
    }
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
