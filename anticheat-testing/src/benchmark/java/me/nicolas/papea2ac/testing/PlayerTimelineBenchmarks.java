package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;

/** Same disciplined JMH methodology as PacketBenchmarks (Phase 1): descriptive-anomaly-detection kernels,
 * never a production-capacity claim. */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=5,time=1) @Measurement(iterations=10,time=1) @Fork(value=3,jvmArgsAppend={"-Xms512m","-Xmx512m"})
@State(Scope.Thread)
public class PlayerTimelineBenchmarks {
    private long seq;
    private final SessionKey key = new SessionKey(new UUID(1, 2), 1);
    private final Vector3 pos = new Vector3(1, 2, 3);
    private final Rotation rot = new Rotation(9f, 1f);
    private PlayerTimeline movementTimeline, rotationTimeline, bothTimeline, lookupTimeline, fullTimeline;

    @Setup public void setup() {
        movementTimeline = new PlayerTimeline(key);
        rotationTimeline = new PlayerTimeline(key);
        bothTimeline = new PlayerTimeline(key);
        lookupTimeline = new PlayerTimeline(key);
        for (long i = 1; i <= 2048; i++) lookupTimeline.append(key, new PlayerObservation.MovementClaim(Optional.of(pos), Optional.of(rot), true, false), i, i);
        fullTimeline = new PlayerTimeline(key, PlayerTimeline.TARGET_BYTES);
        for (long i = 1; i <= 4096; i++) fullTimeline.append(key, new PlayerObservation.TickObserved(i), i, i);
    }

    @Benchmark public boolean movementAppend() { return movementTimeline.append(key, new PlayerObservation.MovementClaim(Optional.of(pos), Optional.empty(), true, false), ++seq, seq); }
    @Benchmark public boolean rotationAppend() { return rotationTimeline.append(key, new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(rot), true, false), ++seq, seq); }
    @Benchmark public boolean positionAndRotationAppend() { return bothTimeline.append(key, new PlayerObservation.MovementClaim(Optional.of(pos), Optional.of(rot), true, false), ++seq, seq); }
    @Benchmark public PlayerTimeline.Snapshot snapshotPublication() { return lookupTimeline.snapshot(); }
    @Benchmark public TimelineLookup exactHistoricalLookup() { return lookupTimeline.stateAtSequence(1024); }
    @Benchmark public TimelineLookup previousStateLookup() { return lookupTimeline.stateBefore(1024); }
    @Benchmark public java.util.List<TimelineRecord> rangeLookup() { return lookupTimeline.snapshotRange(500, 600); }
    /** Already at the 4096-record ceiling: every append here also evicts the oldest record - the steady-state overwrite cost, not cold-start growth. */
    @Benchmark public boolean evictionAtFullCapacity() { return fullTimeline.append(key, new PlayerObservation.TickObserved(++seq), 4096 + seq, 4096 + seq); }
}
