package me.nicolas.papea2ac.schema;
import java.util.List;
import java.util.Objects;
/** Transport-only archival projection; no live implementation or platform handles. */
public record TransportSnapshot(int schema,long epoch,long processed,long certified,List<Pending> pending,String numericProfile) {
    public TransportSnapshot {
        Objects.requireNonNull(pending);Objects.requireNonNull(numericProfile);
        if(schema!=1||epoch<1||processed<0||certified<0||pending.size()>256||numericProfile.length()>128)
            throw new IllegalArgumentException("unsupported/incomplete snapshot");
        pending=List.copyOf(pending);
    }
    public record Pending(long attemptId,String digest,long expiryNanos) {
        public Pending {if(attemptId<1||digest==null||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("pending");}
    }
}
