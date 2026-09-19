package me.nicolas.papea2AC.platform;
import io.netty.channel.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;
import me.nicolas.papea2ac.version.ProtocolProfile;
/**
 * Pinned development adapter. Dispatch outcome instrumentation is deliberately absent.
 *
 * <p>Capture attaches at raw connection-accept time via {@link ChannelInitializeListener}
 * (see {@link #afterInitChannel}), before HANDSHAKING is read. Before a player/UUID exists,
 * captured packets are journaled per-connection under a locally-minted {@link TransportSessionId}
 * (never a client-supplied value). {@link #bind} later hands that connection to a real
 * {@link PacketEngine.Session}, replaying the journal through the same {@code ingress.capture}
 * path so sequence numbering is continuous rather than restarted, then arms the same,
 * already-installed pipeline handlers to forward directly from then on.
 *
 * <p>{@code io.papermc.paper.network.ChannelInitializeListener}/{@code Holder} are not part of the
 * compile-time {@code paper-api} artifact this module builds against (verified: absent from the
 * pinned paper-api jar's own contents; only the running server jar has them). Registration
 * therefore uses reflection and a dynamic proxy, the same pattern {@link PaperPacketInventory}
 * already uses for {@code PacketType.PacketVisitor} - never a new dependency, never a direct
 * compile-time type.
 */
public final class PaperCapture {
    private static final String IN="papea2ac-in",OUT="papea2ac-out";
    /**
     * Global bound on aggregate pre-player (not-yet-bound) transport state, independent of the existing
     * per-connection journal bounds. Policy is deliberately simple: a hard global cap, first-admitted-wins,
     * no scheduling/fairness machinery - abandoned connections are cleaned up on channelInactive (see
     * {@link #closeTransport}) or on bind (see {@link #bind}), returning their share immediately, so capacity
     * is always reusable and no single connection can pin it indefinitely. 4096 connections is far above any
     * plausible legitimate concurrent-handshake count for a single server process; 64 MiB aggregate is a small
     * fraction of the engine's own 256 MiB global session budget, chosen so pre-authentication pressure can
     * never approach player-session-scale memory even under a connection flood. Kept as plain instance fields
     * rather than {@code static final} (which javac would inline as compile-time constants) so a test can
     * reflectively lower them to exercise the admission mechanism deterministically without needing thousands
     * of real connections/megabytes; production code always constructs with these defaults.
     */
    private int maxPreLoginConnections=4096;
    private long maxPreLoginAggregateBytes=64L<<20;
    private Object earlyHookKey;
    private final PacketEngine engine;
    private final String profileHash;
    private final AtomicLong transportIds=new AtomicLong();
    private final java.util.concurrent.ConcurrentMap<Channel,PreLoginTransport> preLogin=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long,Audit> audits=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger preLoginCount=new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong preLoginAggregateBytes=new AtomicLong();
    /** Set once, at the start of shutdown; checked by the early hook so a connection accepted mid-teardown never installs permanent state referencing a plugin instance that is going away. */
    private volatile boolean closing;
    /**
     * One attachment topology's identity/anchors. Two distinct real topologies exist and each is audited
     * against its OWN correct invariants, never the other's: {@code earlyHook=true} (the common path, installed
     * by {@link #afterInitChannel} before any protocol byte is read) anchors on the stable {@code packet_handler}/
     * {@code hackfix} handlers and never tracks a specific encoder/dispatch instance (see {@link #afterInitChannel}'s
     * javadoc for why {@code hackfix} was chosen); {@code earlyHook=false} is the {@link #attachFresh} fallback
     * for a connection the early hook never saw, which anchors on {@code packet_handler}/{@code encoder} and does
     * track specific instances since those handlers can legitimately be replaced during protocol negotiation.
     */
    private static final class Audit {
        final Channel channel;final PacketEngine.Session session;final String inboundName,outboundName;
        final boolean earlyHook;final String inboundAnchor,outboundAnchor;
        final java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
        ChannelHandler encoder,dispatch;
        Audit(Channel channel,PacketEngine.Session session,String inboundName,String outboundName,boolean earlyHook,String inboundAnchor,String outboundAnchor){
            this.channel=channel;this.session=session;this.inboundName=inboundName;this.outboundName=outboundName;
            this.earlyHook=earlyHook;this.inboundAnchor=inboundAnchor;this.outboundAnchor=outboundAnchor;
        }
    }
    public PaperCapture(PacketEngine engine){
        this.engine=engine;
        try{profileHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("paper-26.2-124-observe-only|numeric-unqualified|schema-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static Observation.ProtocolState state(TransportPhase phase){
        return switch(phase){
            case CONFIGURATION -> Observation.ProtocolState.CONFIGURATION;
            case PLAY -> Observation.ProtocolState.PLAY;
            case LOGIN -> Observation.ProtocolState.LOGIN;
            default -> Observation.ProtocolState.UNKNOWN;
        };
    }
    private Observation.Context context(Observation.Event event,PreLoginTransport pre){
        Observation.Direction direction=event instanceof Observation.Attempt?Observation.Direction.INBOUND:
            event instanceof Observation.Outbound?Observation.Direction.OUTBOUND:Observation.Direction.LOCAL;
        Observation.SourceKind source=event instanceof Observation.Attempt||event instanceof Observation.Outbound?
            Observation.SourceKind.NETWORK:event instanceof Observation.Outcome?Observation.SourceKind.SERVER_OUTCOME:Observation.SourceKind.SERVER_OBSERVATION;
        long id=event instanceof Observation.Attempt a?a.id():event instanceof Observation.Outbound o?o.wireOrdinal():0;
        return new Observation.Context(1,0,direction,source,id,0,event instanceof Observation.Tick tick?tick.serverTick():-1,
            state(pre.phase()),pre.clientProtocol,profileHash);
    }
    /** Called on Paper main thread; channel use and hooks are moved to its event loop. */
    public static Channel channel(Object player) throws ReflectiveOperationException {
        Object handle=player.getClass().getMethod("getHandle").invoke(player);
        Object listener=field(handle,"connection");
        Object connection=field(listener,"connection");
        return (Channel)field(connection,"channel");
    }
    private static Object field(Object o,String name) throws ReflectiveOperationException{
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass()){
            try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
            catch(NoSuchFieldException ignored){ /* search superclass, bounded by Java hierarchy */ }
        }
        throw new NoSuchFieldException(name);
    }
    /**
     * Registers {@link #afterInitChannel} with {@code ChannelInitializeListenerHolder} via
     * reflection (see class javadoc). Idempotent; safe to call once from {@code onEnable}. Returns
     * false (does not throw) if the holder class is unavailable on this server, so plugin startup
     * degrades to no early capture rather than failing.
     */
    public boolean registerEarlyHook(){
        try{
            Class<?> listenerType=Class.forName("io.papermc.paper.network.ChannelInitializeListener");
            Class<?> holder=Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Class<?> keyType=Class.forName("net.kyori.adventure.key.Key");
            earlyHookKey=keyType.getMethod("key",String.class,String.class).invoke(null,"papea2ac","capture");
            Object proxy=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},(target,method,args)->{
                if(method.getName().equals("afterInitChannel")){afterInitChannel((Channel)args[0]);return null;}
                return switch(method.getName()){
                    case "toString" -> "Papea2AC early capture listener";
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target==args[0];
                    default -> throw new IllegalStateException("unexpected listener method");
                };
            });
            holder.getMethod("addListener",keyType,listenerType).invoke(null,earlyHookKey,proxy);
            return true;
        }catch(ReflectiveOperationException|RuntimeException e){earlyHookKey=null;engine.reportFailure(e);return false;}
    }
    /**
     * Sets {@link #closing} unconditionally, before anything else, so a connection whose accept-time
     * {@code afterInitChannel} invocation is already in flight on its own Netty thread right as this runs on
     * the main thread still observes the flag (it is checked as the very first statement there) and installs
     * nothing, rather than racing to create permanent pipeline state that references a plugin instance
     * about to be discarded. Removing the actual listener registration afterward is best-effort on top of
     * that: even if Paper's own internal listener iteration already grabbed a reference before removal
     * completes, the flag alone is what prevents new pre-login state from being created.
     */
    public void unregisterEarlyHook(){
        closing=true;
        if(earlyHookKey==null)return;
        try{
            Class<?> holder=Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Class<?> keyType=Class.forName("net.kyori.adventure.key.Key");
            holder.getMethod("removeListener",keyType).invoke(null,earlyHookKey);
        }catch(ReflectiveOperationException|RuntimeException e){engine.reportFailure(e);}
        finally{earlyHookKey=null;}
    }
    /**
     * The actual early-attach logic, invoked by the proxy installed in {@link #registerEarlyHook}.
     * Fires synchronously on the channel's own event loop, before any protocol byte is read
     * (verified against the pinned server: it is the final statement of
     * {@code ServerConnectionListener$1.initChannel}). Installs one permanent inbound handler
     * immediately before {@code packet_handler} (already live under that name at this point) and
     * one permanent outbound handler immediately after {@code hackfix} (stable and adjacent to the
     * eventual encoder regardless of whether the outbound codec is still the pre-negotiation
     * placeholder). Minimal, non-blocking, catches everything: a failure here must degrade this one
     * connection's coverage, never take down plugin startup or other connections.
     */
    void afterInitChannel(Channel channel){
        try{
            if(closing)return;
            var pipeline=channel.pipeline();
            if(pipeline.get("packet_handler")==null||pipeline.get("hackfix")==null)return;
            if(preLoginCount.get()>=maxPreLoginConnections){
                // Global admission exhausted: install nothing for this connection rather than block Netty or
                // grow an unbounded queue. It is not abandoned - bind() below already has a fallback for
                // "no pre-login transport on record" (attachFresh, the pre-early-hook mechanism), so the
                // connection still becomes a fully functional session once the player joins; it simply never
                // gets pre-login-phase (HANDSHAKING/LOGIN) observations, an explicit, metered degradation.
                engine.metrics.increment(PacketMetrics.Kind.PRELOGIN_ADMISSION_REJECTED);
                return;
            }
            TransportSessionId id=new TransportSessionId(transportIds.incrementAndGet());
            PreLoginTransport pre=new PreLoginTransport(id,channel);
            String inboundName=IN+"-t"+id.value(),outboundName=OUT+"-t"+id.value();
            pre.inboundHandlerName=inboundName;pre.outboundHandlerName=outboundName;
            preLogin.put(channel,pre);
            preLoginCount.incrementAndGet();
            pipeline.addBefore("packet_handler",inboundName,new EarlyInbound(pre));
            pipeline.addAfter("hackfix",outboundName,new EarlyOutbound(pre));
        }catch(RuntimeException failure){engine.reportFailure(failure);}
    }
    /** Global aggregate-byte-bounded wrapper around PreLoginTransport.record; never blocks, only degrades. */
    private void recordBounded(PreLoginTransport pre,PreLoginTransport.Direction direction,long ordinal,long now,PacketPayload payload){
        if(preLoginAggregateBytes.get()>=maxPreLoginAggregateBytes){
            if(!pre.truncated())engine.metrics.increment(PacketMetrics.Kind.PRELOGIN_TRUNCATED_GLOBAL);
            pre.truncateForGlobalPressure();return;
        }
        long before=pre.journalBytes();
        pre.record(direction,ordinal,now,payload);
        long delta=pre.journalBytes()-before;
        if(delta>0)preLoginAggregateBytes.addAndGet(delta);
    }
    /** Releases this connection's share of the global pre-login byte budget; safe to call more than once (idempotent once the journal is already empty). */
    private void releaseGlobalPreLoginShare(PreLoginTransport pre){
        long owned=pre.journalBytes();
        if(owned>0)preLoginAggregateBytes.addAndGet(-owned);
    }
    private final class EarlyInbound extends ChannelInboundHandlerAdapter {
        private final PreLoginTransport pre;private long ordinal;
        EarlyInbound(PreLoginTransport pre){this.pre=pre;}
        @Override public void channelRead(ChannelHandlerContext ctx,Object message)throws Exception{
            if(!isPacket(message)){ctx.fireChannelRead(message);return;}
            PacketEngine.Session bound=pre.boundSession;
            try{
                PacketPayload payload=normalize(message);
                classify(pre,payload,true);
                long assigned=++ordinal;
                if(bound!=null)bound.ingress.capture(new Observation.Attempt(assigned,assigned,payload),System.nanoTime());
                else recordBounded(pre,PreLoginTransport.Direction.INBOUND,assigned,System.nanoTime(),payload);
            }catch(ReflectiveOperationException|RuntimeException e){
                engine.metrics.increment(PacketMetrics.Kind.MALFORMED);
                if(bound!=null)bound.health.invalidate(HealthLatch.Reason.MALFORMED,0);
            }
            ctx.fireChannelRead(message);
        }
        @Override public void channelInactive(ChannelHandlerContext ctx)throws Exception{closeTransport(pre);ctx.fireChannelInactive();}
        @Override public void handlerRemoved(ChannelHandlerContext ctx){
            PacketEngine.Session bound=pre.boundSession;
            if(bound!=null&&!bound.ingress.closed()){bound.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);bound.captureCompromised("EARLY_HANDLER_REMOVED");}
        }
    }
    private final class EarlyOutbound extends ChannelOutboundHandlerAdapter {
        private final PreLoginTransport pre;private long ordinal;
        EarlyOutbound(PreLoginTransport pre){this.pre=pre;}
        @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception{
            if(!isPacket(message)){ctx.write(message,promise);return;}
            PacketEngine.Session bound=pre.boundSession;
            ChannelPromise completion=promise.unvoid();
            try{
                PacketPayload payload=normalize(message);
                classify(pre,payload,false);
                long assigned=++ordinal;
                if(bound!=null){
                    bound.ingress.capture(new Observation.Outbound(assigned,payload),System.nanoTime());
                    completion.addListener(future->bound.ingress.capture(new Observation.WriteOutcome(assigned,future.isSuccess()),System.nanoTime()));
                } else recordBounded(pre,PreLoginTransport.Direction.OUTBOUND,assigned,System.nanoTime(),payload);
            }catch(ReflectiveOperationException|RuntimeException e){
                engine.metrics.increment(PacketMetrics.Kind.FAILURE);
                if(bound!=null)bound.health.invalidate(HealthLatch.Reason.INTERNAL_FAILURE,0);
            }
            ctx.write(message,completion);
        }
        @Override public void handlerRemoved(ChannelHandlerContext ctx){
            PacketEngine.Session bound=pre.boundSession;
            if(bound!=null&&!bound.ingress.closed()){bound.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);bound.captureCompromised("EARLY_HANDLER_REMOVED");}
        }
    }
    /** Best-effort phase/identity tracking from already-normalized facts; never trusts claimed identity. */
    private static void classify(PreLoginTransport pre,PacketPayload payload,boolean inbound){
        if(payload instanceof Handshake h){pre.clientProtocol=h.protocolVersion();pre.advance(TransportPhase.HANDSHAKING);}
        else if(payload instanceof LoginHello hello){pre.advance(TransportPhase.LOGIN);pre.claimedIdentity=hello.claimedProfileId();}
        else if(payload instanceof Lifecycle l&&l.transition()==LifecycleTransition.LOGIN_ACKNOWLEDGED)pre.advance(TransportPhase.CONFIGURATION);
    }
    private void closeTransport(PreLoginTransport pre){
        PacketEngine.Session bound=pre.boundSession;
        preLogin.remove(pre.channel);
        pre.advance(TransportPhase.CLOSING);pre.advance(TransportPhase.CLOSED);
        releaseGlobalPreLoginShare(pre);
        if(pre.releaseGlobalAdmissionOnce())preLoginCount.decrementAndGet();
        pre.clear();
        if(bound!=null){audits.remove(bound.key.epoch());bound.close();}
    }
    /** Pre-early-hook name, retained as an alias: identical behavior to {@link #bind}. */
    public PacketEngine.Session attach(UUID player,Channel channel){return bind(player,channel);}
    /**
     * Hands an already-accepted connection to a real, UUID-keyed session once the server has
     * established (not merely received a claim for) a player identity. Replays anything captured
     * before this point through the same {@code ingress.capture} call the live path uses, so
     * sequence numbering continues rather than restarting, then arms the already-installed early
     * handlers to forward directly. Falls back to installing fresh handlers (the pre-early-hook
     * behavior) if no pre-login transport is on record for this channel.
     */
    public PacketEngine.Session bind(UUID player,Channel channel){
        PreLoginTransport pre=preLogin.get(channel);
        var loop=new ConnectionIngress.EventLoop(){
            public boolean inEventLoop(){return channel.eventLoop().inEventLoop();}
            public void execute(Runnable task){channel.eventLoop().execute(task);}
        };
        PreLoginTransport contextSource=pre!=null?pre:new PreLoginTransport(new TransportSessionId(transportIds.incrementAndGet()),channel);
        if(pre==null)contextSource.advance(TransportPhase.PLAY);
        var session=engine.connect(player,loop,TransportCapability.observeOnly(),event->context(event,contextSource));
        if(pre!=null){
            audits.put(session.key.epoch(),new Audit(channel,session,pre.inboundHandlerName,pre.outboundHandlerName,true,"packet_handler","hackfix"));
            try{
                channel.eventLoop().execute(()->{
                    try{
                        if(!channel.isActive()||session.ingress.closed())return;
                        for(PreLoginTransport.Entry entry:pre.journalSnapshot()){
                            Observation.Event event=entry.direction()==PreLoginTransport.Direction.INBOUND
                                ?new Observation.Attempt(entry.ordinal(),entry.ordinal(),entry.payload())
                                :new Observation.Outbound(entry.ordinal(),entry.payload());
                            session.ingress.capture(event,entry.capturedNanos());
                        }
                        // A pre-login prefix truncated by either the per-connection or the global bound is a
                        // genuine coverage gap, not a cosmetic detail: never let the resulting session look
                        // pristine when it is not. Sticky, matching every other coverage-affecting reason.
                        if(pre.truncated())session.health.invalidate(HealthLatch.Reason.OVERLOADED,0);
                        releaseGlobalPreLoginShare(pre);
                        if(pre.releaseGlobalAdmissionOnce())preLoginCount.decrementAndGet();
                        pre.clear();pre.establishedIdentity=player;
                        if(!pre.advance(TransportPhase.PLAY))pre.advance(TransportPhase.CLOSING);
                        pre.boundSession=session;
                    }catch(RuntimeException failure){
                        session.health.invalidate(HealthLatch.Reason.INTERNAL_FAILURE,0);session.close();engine.reportFailure(failure);
                    }
                });
            }catch(RuntimeException failure){session.close();engine.reportFailure(failure);}
            return session;
        }
        return attachFresh(session,channel);
    }
    /** Fallback path for a connection the early hook never saw (e.g. plugin enabled after accept). */
    private PacketEngine.Session attachFresh(PacketEngine.Session session,Channel channel){
        String inbound=IN+"-"+session.key.epoch(),outbound=OUT+"-"+session.key.epoch();
        audits.put(session.key.epoch(),new Audit(channel,session,inbound,outbound,false,"packet_handler","encoder"));
        try {
        channel.eventLoop().execute(()->{
            try {
            if(!channel.isActive()||session.ingress.closed())return;
            var pipeline=channel.pipeline();
            if(pipeline.get("packet_handler")==null||pipeline.get("encoder")==null){
                session.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);session.close();return;
            }
            ChannelHandler dispatch=pipeline.get("packet_handler"),encoder=pipeline.get("encoder");
            Audit audit=audits.get(session.key.epoch());audit.dispatch=dispatch;audit.encoder=encoder;
            pipeline.addBefore("packet_handler",inbound,new ChannelInboundHandlerAdapter(){
                long ordinal;
                @Override public void channelRead(ChannelHandlerContext ctx,Object message) throws Exception{
                    if(!isPacket(message)){ctx.fireChannelRead(message);return;}
                    try{
                        if(pipeline.get("packet_handler")!=dispatch||pipeline.get("encoder")!=encoder
                            ||!adjacent(pipeline,inbound,"packet_handler")){session.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);session.captureCompromised("INBOUND_DISPLACED");}
                        PacketPayload payload=normalize(message);
                        session.ingress.capture(new Observation.Attempt(++ordinal,ordinal,payload),System.nanoTime());
                    }catch(ReflectiveOperationException|RuntimeException e){
                        engine.metrics.increment(PacketMetrics.Kind.MALFORMED);
                        session.health.invalidate(HealthLatch.Reason.MALFORMED,0);
                    }
                    ctx.fireChannelRead(message);
                }
                @Override public void channelInactive(ChannelHandlerContext ctx)throws Exception{audits.remove(session.key.epoch());session.close();ctx.fireChannelInactive();}
                @Override public void handlerRemoved(ChannelHandlerContext ctx){
                    if(!session.ingress.closed()){session.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);session.captureCompromised("INBOUND_HANDLER_REMOVED");}
                }
            });
            pipeline.addAfter("encoder",outbound,new ChannelOutboundHandlerAdapter(){
                long ordinal;
                @Override public void handlerRemoved(ChannelHandlerContext ctx){
                    if(!session.ingress.closed()){session.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);session.captureCompromised("OUTBOUND_HANDLER_REMOVED");}
                }
                @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception{
                    if(!isPacket(message)){ctx.write(message,promise);return;}
                    long wire=++ordinal;
                    ChannelPromise completion=promise.unvoid();
                    try{
                        if(pipeline.get("encoder")!=encoder||!adjacent(pipeline,"encoder",outbound)){
                            session.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);session.captureCompromised("OUTBOUND_DISPLACED");}
                        session.ingress.capture(new Observation.Outbound(wire,normalize(message)),System.nanoTime());
                        completion.addListener(future->session.ingress.capture(new Observation.WriteOutcome(wire,future.isSuccess()),System.nanoTime()));
                    }catch(ReflectiveOperationException|RuntimeException e){
                        engine.metrics.increment(PacketMetrics.Kind.FAILURE);
                        session.health.invalidate(HealthLatch.Reason.INTERNAL_FAILURE,0);
                    }
                    ctx.write(message,completion);
                }
            });
            } catch(RuntimeException failure) {
                session.health.invalidate(HealthLatch.Reason.INTERNAL_FAILURE,0);
                session.close();removeHooks(channel,inbound,outbound);engine.reportFailure(failure);
            }
        });
        } catch(RuntimeException failure) {
            session.close();engine.reportFailure(failure);
        }
        return session;
    }
    /**
     * At most one pending idle probe per admitted connection; execution remains on its event loop.
     *
     * <p>This is a periodic health/diagnostic detection, not continuous proof: a handler could in principle
     * be removed and reinserted between two audit ticks and never be observed missing (see Phase 0's own
     * "periodic audits cannot prove no transient tampering occurred" concern). A failed audit degrades
     * coverage going forward from that point; it does not, and cannot, retroactively certify that every
     * observation since session start was captured - {@link HealthLatch} is already sticky for exactly
     * this reason, so a later successful audit tick never heals a reason it did not itself invalidate.
     *
     * <p>Checks the topology that actually matches how this specific session was attached (see {@link Audit}):
     * for the early-hook path there is no specific encoder/dispatch instance to compare (none is ever
     * recorded - see {@link #afterInitChannel}'s javadoc for why {@code hackfix} was chosen precisely so
     * instance-tracking is unnecessary), so the check is the adjacency invariant plus the two anchor
     * handlers still existing at all; for the {@link #attachFresh} fallback path, the specific encoder/dispatch
     * instances recorded at attach time are also compared, since those handlers can legitimately be replaced
     * during protocol negotiation and a silent replacement there is exactly the "handler still present but
     * meaningless" case worth catching.
     */
    public void audit(PacketEngine.Session session){
        Audit audit=audits.get(session.key.epoch());
        if(audit==null||session.ingress.closed()||!audit.queued.compareAndSet(false,true))return;
        try{audit.channel.eventLoop().execute(()->{
            try{
                if(session.ingress.closed())return;
                var pipeline=audit.channel.pipeline();
                boolean topologyIntact=adjacent(pipeline,audit.inboundName,audit.inboundAnchor)
                    &&adjacent(pipeline,audit.outboundAnchor,audit.outboundName)
                    &&(audit.earlyHook
                        ?pipeline.get(audit.inboundAnchor)!=null&&pipeline.get(audit.outboundAnchor)!=null
                        :pipeline.get(audit.inboundAnchor)==audit.dispatch&&pipeline.get(audit.outboundAnchor)==audit.encoder);
                if(!topologyIntact){session.health.invalidate(HealthLatch.Reason.PIPELINE_UNCOVERED,0);session.captureCompromised("TOPOLOGY_AUDIT");}
            }finally{audit.queued.set(false);}
        });}catch(RuntimeException failure){audit.queued.set(false);session.close();engine.reportFailure(failure);}
    }
    private static boolean adjacent(ChannelPipeline pipeline,String before,String after){
        List<String> names=pipeline.names();return names.indexOf(before)>=0&&names.indexOf(after)==names.indexOf(before)+1;
    }
    public void detach(Channel channel,PacketEngine.Session session){
        audits.remove(session.key.epoch());
        session.close();
        PreLoginTransport pre=preLogin.remove(channel);
        try { channel.eventLoop().execute(()->{
            if(pre!=null)removeHooks(channel,pre.inboundHandlerName,pre.outboundHandlerName);
            else removeHooks(channel,IN+"-"+session.key.epoch(),OUT+"-"+session.key.epoch());
        }); }
        catch(RuntimeException failure){engine.reportFailure(failure);}
    }
    private static void removeHooks(Channel channel,String inbound,String outbound){
        var pipeline=channel.pipeline();
        for(String name:List.of(inbound,outbound))
            if(pipeline.get(name)!=null)pipeline.remove(name);
    }
    /** Only protocol packets are observations. The pipeline also carries control objects that are not packets - e.g. the vanilla
     *  UnconfiguredPipelineHandler$OutboundConfigurationTask written at every protocol-state switch (login -> configuration -> play), or a
     *  pre-encoded ByteBuf. Capturing those as "uncovered packets" degraded every real session at its first protocol switch and ended
     *  its recording at frame 2 (P3-F11, real-client qualification). They pass through untouched and un-numbered. */
    static boolean isPacket(Object message){return message!=null&&message.getClass().getName().startsWith("net.minecraft.network.protocol.");}
    public static PacketPayload normalize(Object packet)throws ReflectiveOperationException{
        return PaperPacketNormalizer.normalize(packet);
    }
}
