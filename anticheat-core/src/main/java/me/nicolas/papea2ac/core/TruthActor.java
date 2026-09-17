package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.player.ProvisionalJournal;
import me.nicolas.papea2ac.player.ObservationRing;
import me.nicolas.papea2ac.protocol.*;
/** Packet truth only. This is not a movement-validity or physics frontier. */
public final class TruthActor {
    private static final long TIMEOUT=500_000_000L;
    private static final class Pending {
        final Observation.Attempt attempt;final String digest;Observation.Outcome outcome;
        Pending(Observation.Attempt a){attempt=a;digest=PayloadDigest.of(a.payload());}
    }
    public record Snapshot(long processedSequence,long certifiedIngressOrdinal,long promotingSequence,
                           PacketPayload acceptedServerProjection,int pending,int journalBytes,
                           boolean certificationAvailable,HealthLatch.Reason reason,
                           long lastIngressOrdinal,long lastProcessingOrdinal,long lastServerRevision){}
    private final SessionKey key;private final HealthLatch health;private final PacketMetrics metrics;
    private final ProvisionalJournal<Pending> journal;
    private final ObservationRing<Observation> history=new ObservationRing<>(4096,512*1024,3_000_000_000L,Observation::chargedBytes);
    private TransportCapability capability;
    private Thread owner;
    private long processed,certified,promoting,lastIngress,lastProcessing,lastRevision;
    private PacketPayload projection;
    private boolean closed;
    private volatile Snapshot snapshot;
    public TruthActor(SessionKey key,HealthLatch health,TransportCapability capability,PacketMetrics metrics,int capacity){
        this.key=key;this.health=health;this.capability=capability;this.metrics=metrics;
        journal=new ProvisionalJournal<>(capacity,262144);publish();
    }
    private void assertOwner(){if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("actor ownership");}
    /** Resume from a mid-session checkpoint. Only valid before any process()/tick() call ever runs. */
    void seed(long processedSequence,long lastIngressOrdinal,long certifiedIngressOrdinal,long lastProcessingOrdinal,long lastServerRevision){
        if(owner!=null)throw new IllegalStateException("actor already active");
        if(processedSequence<0||lastIngressOrdinal<0||certifiedIngressOrdinal<0||lastProcessingOrdinal<0||lastServerRevision<0)
            throw new IllegalArgumentException("checkpoint seed");
        processed=processedSequence;lastIngress=lastIngressOrdinal;certified=certifiedIngressOrdinal;
        promoting=processedSequence;lastProcessing=lastProcessingOrdinal;lastRevision=lastServerRevision;
        publish();
    }
    public void process(Observation o,long now){
        assertOwner();
        if(!o.session().equals(key)){metrics.increment(PacketMetrics.Kind.STALE);return;}
        if(closed){return;}
        if(o.sequence()!=processed+1||o.boundaryGeneration()!=health.get().generation()){
            degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);
            if(o.sequence()<=processed)return;
        }
        history.append(o,o.capturedNanos());
        processed=o.sequence();metrics.increment(PacketMetrics.Kind.CAPTURED);metrics.latency(now-o.capturedNanos());
        tick(now);if(!health.get().healthy())return;
        if(o.event() instanceof Observation.Outbound outbound&&uncovered(outbound.payload())){
            degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);return;
        }
        if(o.event() instanceof Observation.Attempt a){
            if(a.ingressOrdinal()!=lastIngress+1||a.id()!=a.ingressOrdinal()){degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);return;}
            lastIngress=a.ingressOrdinal();metrics.increment(PacketMetrics.Kind.NORMALIZED);
            if(uncovered(a.payload())){degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);return;}
            if(!journal.add(a.id(),new Pending(a),512+a.payload().chargedBytes(),o.capturedNanos()+TIMEOUT)){
                metrics.increment(PacketMetrics.Kind.OVERLOAD);degrade(HealthLatch.Reason.OVERLOADED);return;
            }
            metrics.increment(PacketMetrics.Kind.PROVISIONAL);
        }else if(o.event() instanceof Observation.Outcome result){outcome(result,o.sequence());}
        else if(o.event() instanceof Observation.WriteOutcome write&&!write.success()){
            metrics.increment(PacketMetrics.Kind.WRITE_FAILED);degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);
        }
        publish();
    }
    private static boolean uncovered(PacketPayload payload){
        return payload instanceof PacketPayload.Uncovered
            ||payload instanceof PacketPayload.Bundle bundle&&bundle.children().stream().anyMatch(TruthActor::uncovered);
    }
    private void outcome(Observation.Outcome result,long seq){
        Pending p=journal.get(result.attemptId());
        if(p==null||!p.digest.equals(result.originalDigest())){metrics.increment(PacketMetrics.Kind.UNKNOWN);degrade(HealthLatch.Reason.OUTCOME_UNKNOWN);return;}
        if(p.outcome!=null){
            if(!p.outcome.equals(result))degrade(HealthLatch.Reason.OUTCOME_UNKNOWN);
            return;
        }
        switch(result.result()){
            case REJECTED -> {metrics.increment(PacketMetrics.Kind.CANCELLED);degrade(HealthLatch.Reason.CANCELLED);return;}
            case FAILED, UNKNOWN -> {metrics.increment(PacketMetrics.Kind.UNKNOWN);degrade(HealthLatch.Reason.OUTCOME_UNKNOWN);return;}
            case TRANSFORMED_APPLIED -> {
                metrics.increment(PacketMetrics.Kind.MUTATED);
                if(result.processingOrdinal()<=lastProcessing||result.serverRevision()<lastRevision){degrade(HealthLatch.Reason.OUTCOME_UNKNOWN);return;}
                // Keep distinct final server projection. No client-frontier reconciliation provider exists here.
                if(capability.certification())projection=result.finalPayload();
                degrade(HealthLatch.Reason.TRANSFORMED);return;
            }
            case APPLIED -> {
                if(!p.digest.equals(PayloadDigest.of(result.finalPayload()))){
                    metrics.increment(PacketMetrics.Kind.UNKNOWN);degrade(HealthLatch.Reason.OUTCOME_UNKNOWN);return;
                }
            }
        }
        // APPLIED has the identical canonical semantics. Retain the already charged payload once.
        p.outcome=new Observation.Outcome(result.attemptId(),result.processingOrdinal(),result.serverRevision(),
                result.result(),p.attempt.payload(),p.digest);
        while(journal.firstId()!=null){
            Pending first=journal.get(journal.firstId());
            if(first.outcome==null)return;
            if(!capability.certification()){metrics.increment(PacketMetrics.Kind.UNKNOWN);degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);return;}
            if(first.outcome.processingOrdinal()<=lastProcessing||first.outcome.serverRevision()<lastRevision){
                degrade(HealthLatch.Reason.OUTCOME_UNKNOWN);return;
            }
            // Only this branch promotes: qualified provider, reconciled baseline, matching APPLIED payload and prefix.
            if (!health.authorize(0)) { journal.clear(); publish(); return; }
            certified=first.attempt.ingressOrdinal();projection=first.outcome.finalPayload();promoting=seq;
            lastProcessing=first.outcome.processingOrdinal();lastRevision=first.outcome.serverRevision();
            journal.remove(first.attempt.id());metrics.increment(PacketMetrics.Kind.CERTIFIED);
        }
    }
    public void tick(long now){assertOwner();history.expire(now);if(journal.expired(now)){metrics.increment(PacketMetrics.Kind.EXPIRED);degrade(HealthLatch.Reason.EXPIRED);}else if(!health.get().healthy()){journal.clear();publish();}}
    public void downgrade(){assertOwner();capability=TransportCapability.observeOnly();degrade(HealthLatch.Reason.PIPELINE_UNCOVERED);}
    public void close(){assertOwner();closed=true;health.invalidate(HealthLatch.Reason.CLOSED,processed);journal.clear();history.clear();projection=null;metrics.increment(PacketMetrics.Kind.CLOSED);publish();}
    public java.util.List<Observation> history(){assertOwner();return history.snapshot();}
    public long historyEvictions(){assertOwner();return history.evictions();}
    /** Coherent owner-only replay state hash, including unresolved payloads and deadlines. */
    public String replayStateDigest() throws java.io.IOException {
        assertOwner();var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(bytes)){
            out.writeUTF(TransportReplayDriver.digest(snapshot()));
            out.writeLong(lastIngress);out.writeLong(lastProcessing);out.writeLong(lastRevision);out.writeBoolean(closed);
            out.writeBoolean(capability.capture());out.writeBoolean(capability.outcomes());
            out.writeBoolean(capability.mediatedIntegrity());out.writeBoolean(capability.baselineReconciled());
            out.writeLong(health.get().generation());out.writeLong(health.get().firstMissingSeq());
            for(var entry:journal.snapshot()){
                Pending p=entry.value();out.writeLong(entry.id());out.writeInt(entry.bytes());out.writeLong(entry.deadline());
                out.writeLong(p.attempt.ingressOrdinal());out.writeUTF(p.digest);out.writeBoolean(p.outcome!=null);
                if(p.outcome!=null){
                    var o=p.outcome;out.writeLong(o.processingOrdinal());out.writeLong(o.serverRevision());out.writeUTF(o.result().name());
                    out.writeUTF(o.originalDigest());out.writeUTF(o.finalPayload()==null?"":PayloadDigest.of(o.finalPayload()));
                }
            }
        }
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public void retireAfterWriterTermination(){
        if(owner!=null&&owner.isAlive())throw new IllegalStateException("writer still alive");
        closed=true;journal.clear();history.clear();projection=null;publish();
    }
    public void fail(){assertOwner();metrics.increment(PacketMetrics.Kind.FAILURE);degrade(HealthLatch.Reason.INTERNAL_FAILURE);}
    private void degrade(HealthLatch.Reason reason){health.invalidate(reason,processed);journal.clear();publish();}
    private void publish(){snapshot=new Snapshot(processed,certified,promoting,projection,journal.size(),journal.bytes(),health.get().healthy()&&capability.certification(),health.get().reason(),lastIngress,lastProcessing,lastRevision);}
    public Snapshot snapshot(){
        var s=snapshot;
        if(!health.get().healthy()&&s.certificationAvailable())
            return new Snapshot(s.processedSequence(),s.certifiedIngressOrdinal(),s.promotingSequence(),s.acceptedServerProjection(),s.pending(),s.journalBytes(),false,health.get().reason(),s.lastIngressOrdinal(),s.lastProcessingOrdinal(),s.lastServerRevision());
        return s;
    }
}
