package me.nicolas.papea2ac.protocol;
import me.nicolas.papea2ac.model.SessionKey;
import java.util.Objects;
public record Observation(SessionKey session, long sequence, long capturedNanos, long boundaryGeneration, Event event, Context context) {
    private static boolean validDigest(String value){
        if(value==null||value.length()!=64)return false;
        for(int i=0;i<64;i++){char c=value.charAt(i);if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;}
        return true;
    }
    public enum Direction { INBOUND, OUTBOUND, LOCAL }
    public enum SourceKind { NETWORK, SERVER_OUTCOME, SERVER_OBSERVATION, SYNTHETIC }
    public enum ProtocolState { PLAY, CONFIGURATION, LOGIN, UNKNOWN }
    public record Context(int schemaVersion,long worldEpoch,Direction direction,SourceKind sourceKind,
        long sourceEventId,long causalParentSequence,long serverTickObservation,ProtocolState protocolState,
        int clientProtocol,String rulesetHash) {
        private static final Context SYNTHETIC=new Context(1,0,Direction.LOCAL,SourceKind.SYNTHETIC,0,0,-1,ProtocolState.UNKNOWN,-1,"0".repeat(64));
        public Context {
            Objects.requireNonNull(direction);Objects.requireNonNull(sourceKind);Objects.requireNonNull(protocolState);
            if(schemaVersion!=1||worldEpoch<0||sourceEventId<0||causalParentSequence<0||serverTickObservation< -1
                ||clientProtocol< -1||!validDigest(rulesetHash))throw new IllegalArgumentException("context");
        }
        public static Context synthetic(){return SYNTHETIC;}
    }
    public Observation(SessionKey session,long sequence,long capturedNanos,long boundaryGeneration,Event event){
        this(session,sequence,capturedNanos,boundaryGeneration,event,Context.synthetic());
    }
    public Observation { Objects.requireNonNull(session); Objects.requireNonNull(event);Objects.requireNonNull(context); if(sequence<1||boundaryGeneration<0)throw new IllegalArgumentException("sequence/generation"); }
    public int chargedBytes() { return 224+event.chargedBytes(); }
    public sealed interface Event {
        int chargedBytes();
    }
    public record Attempt(long id, long ingressOrdinal, PacketPayload payload) implements Event {
        public Attempt { if(id<1||ingressOrdinal<1)throw new IllegalArgumentException("attempt"); Objects.requireNonNull(payload); }
        public int chargedBytes(){return 64+payload.chargedBytes();}
    }
    public enum Result { APPLIED, TRANSFORMED_APPLIED, REJECTED, FAILED, UNKNOWN }
    public record Outcome(long attemptId, long processingOrdinal, long serverRevision,
                          Result result, PacketPayload finalPayload, String originalDigest) implements Event {
        public Outcome {
            Objects.requireNonNull(result); Objects.requireNonNull(originalDigest);
            if(attemptId<1||processingOrdinal<1||serverRevision<0||!validDigest(originalDigest))throw new IllegalArgumentException("outcome");
            if((result==Result.APPLIED||result==Result.TRANSFORMED_APPLIED)&&finalPayload==null)throw new IllegalArgumentException("final payload");
        }
        public int chargedBytes(){return 256+(finalPayload==null?0:finalPayload.chargedBytes());}
    }
    public record Outbound(long wireOrdinal, PacketPayload payload) implements Event {
        public Outbound { Objects.requireNonNull(payload); if(wireOrdinal<1)throw new IllegalArgumentException("wire"); }
        public int chargedBytes(){return 64+payload.chargedBytes();}
    }
    public record WriteOutcome(long wireOrdinal, boolean success) implements Event {
        public WriteOutcome {if(wireOrdinal<1)throw new IllegalArgumentException("wire");}
        public int chargedBytes(){return 64;}
    }
    public record Tick(long serverTick) implements Event {public int chargedBytes(){return 64;}}
    /** Session-ordered administrative marker; never packet truth. Sequenced via the same ingress ordering as packets so its placement relative to Attempt/Outcome events is deterministic, not thread-timing luck. */
    public record ControlChange(String kind, String value) implements Event {
        private static boolean invalid(String s,int max){return s==null||s.isEmpty()||s.length()>max;}
        public ControlChange { if(invalid(kind,32)||invalid(value,32)) throw new IllegalArgumentException("control change"); }
        public int chargedBytes(){return 64;}
    }
    /** Ordered request for a coherent diagnostic checkpoint at this exact point in the observation sequence. */
    public record CheckpointMarker(long requestId) implements Event {
        public CheckpointMarker { if(requestId<1) throw new IllegalArgumentException("requestId"); }
        public int chargedBytes(){return 32;}
    }
}
