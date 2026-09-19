package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Empirical, JVM-heap-diffing memory harness for PlayerTimeline (PERFORMANCE.md's own methodology note: these
 * are design-estimate reconciliations against measured behavior, never exact heap truth or a supported-capacity
 * claim). No JOL dependency is added: JOL is not already a project dependency, and a documented
 * forced-GC-then-diff empirical approach is sufficient to sanity-check the exact numeric bounds already in
 * use, which is this harness's actual purpose.
 *
 * Methodology: allocate N independent PlayerTimeline instances under a representative workload, force GC
 * repeatedly to let garbage settle, then diff Runtime totalMemory()-freeMemory() before and after. Report
 * bytes/timeline and bytes/entry. Repeated 3 times per workload; the median is reported, and run-to-run
 * variance is shown so a reader can judge noise, not just a single number.
 */
public final class PlayerTimelineMemoryMain {
    private PlayerTimelineMemoryMain() {}
    private static final int TIMELINES_PER_TRIAL = 200;
    private static final int RECORDS_PER_TIMELINE = 512;

    private static void settle() {
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    private static long usedBytes() {
        var rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double median(long[] values) {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    interface Workload { void apply(PlayerTimeline timeline, SessionKey key); }

    private static long[] measure(Workload workload) {
        long[] deltas = new long[3];
        for (int trial = 0; trial < 3; trial++) {
            settle();
            long before = usedBytes();
            var timelines = new ArrayList<PlayerTimeline>(TIMELINES_PER_TRIAL);
            for (int t = 0; t < TIMELINES_PER_TRIAL; t++) {
                var key = new SessionKey(new UUID(0, t + 1), 1);
                var timeline = new PlayerTimeline(key);
                workload.apply(timeline, key);
                timelines.add(timeline);
            }
            settle();
            long after = usedBytes();
            deltas[trial] = (after - before) / TIMELINES_PER_TRIAL;
            if (timelines.get(0) == null) throw new AssertionError(); // keep the list reachable through the measurement window
        }
        return deltas;
    }

    public static void main(String[] args) throws IOException {
        var pos = new Vector3(1, 2, 3);
        var rot = new Rotation(9f, 1f);
        var results = new LinkedHashMap<String, long[]>();

        results.put("idleMinimal", measure((t, k) -> t.append(k, new PlayerObservation.SessionClosed(), 1, 0)));
        results.put("movementHeavy", measure((t, k) -> {
            for (long i = 1; i <= RECORDS_PER_TIMELINE; i++) t.append(k, new PlayerObservation.MovementClaim(Optional.of(pos), Optional.empty(), true, false), i, i);
        }));
        results.put("rotationHeavy", measure((t, k) -> {
            for (long i = 1; i <= RECORDS_PER_TIMELINE; i++) t.append(k, new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(rot), true, false), i, i);
        }));
        results.put("mixedMovementRotation", measure((t, k) -> {
            for (long i = 1; i <= RECORDS_PER_TIMELINE; i++)
                t.append(k, i % 2 == 0 ? new PlayerObservation.MovementClaim(Optional.of(pos), Optional.empty(), true, false)
                        : new PlayerObservation.MovementClaim(Optional.empty(), Optional.of(rot), true, false), i, i);
        }));
        results.put("teleportVelocityHeavy", measure((t, k) -> {
            for (long i = 1; i <= RECORDS_PER_TIMELINE; i++)
                t.append(k, i % 2 == 0 ? new PlayerObservation.TeleportSent(i, pos, rot)
                        : new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA, "creeper", pos, i), i, i);
        }));
        results.put("timelineAtRetentionMaximum", measure((t, k) -> {
            for (long i = 1; i <= 4096; i++) t.append(k, new PlayerObservation.MovementClaim(Optional.of(pos), Optional.of(rot), true, false), i, i);
        }));

        var json = new StringBuilder("{\n  \"methodology\": \"forced-GC heap diff (Runtime totalMemory-freeMemory), 3 trials per workload, median reported, per-timeline average over " + TIMELINES_PER_TRIAL + " instances - an empirical estimate, not exact heap truth or a capacity claim\",\n  \"recordsPerTimeline\": " + RECORDS_PER_TIMELINE + ",\n  \"workloads\": {\n");
        int i = 0;
        for (var entry : results.entrySet()) {
            long[] trials = entry.getValue();
            json.append("    \"").append(entry.getKey()).append("\": {\"trialsBytesPerTimeline\": ").append(Arrays.toString(trials))
                    .append(", \"medianBytesPerTimeline\": ").append(median(trials)).append("}");
            json.append(++i < results.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");

        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString());
        System.out.println(json);
    }
}
