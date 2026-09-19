package me.nicolas.papea2ac.testing;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.CapsuleCodec;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Bounded, read-only inspection of a recorded transport capsule for qualification evidence: header, frame counts by
 * kind, and (optionally filtered by payload simple class name) one line per matching Input frame with sequence and
 * the payload's toString(). Usage: CapsuleInspectMain <capsule file> [payloadSimpleName ...]
 */
public final class CapsuleInspectMain {
    private CapsuleInspectMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("capsule path required");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        var capsule = CapsuleCodec.decode(bytes);
        var filter = new HashSet<>(Arrays.asList(args).subList(1, args.length));
        System.out.println("capsule schema=" + capsule.schema() + " profile=" + capsule.profile() + " epoch=" + capsule.epoch() + " frames=" + capsule.frames().size()
            + " complete=" + capsule.prefixComplete() + (capsule.incompleteReason().isEmpty() ? "" : " incompleteReason=" + capsule.incompleteReason()));
        var kinds = new TreeMap<String, Integer>(); long firstSeq = -1, lastSeq = -1;
        var key = new SessionKey(new UUID(0, 1), capsule.epoch());
        for (var frame : capsule.frames()) {
            if (frame instanceof TransportCapsule.Input input) {
                if (firstSeq < 0) firstSeq = input.sequence(); lastSeq = input.sequence();
                var observation = TransportReplayMapper.restore(input, key);
                String kind = observation.event().getClass().getSimpleName();
                PacketPayload payload = switch (observation.event()) {
                    case Observation.Outbound o -> o.payload();
                    case Observation.Attempt a -> a.payload();
                    case Observation.Outcome o -> o.finalPayload();
                    default -> null;
                };
                String name = payload == null ? kind : kind + ":" + payload.getClass().getSimpleName();
                kinds.merge(name, 1, Integer::sum);
                if (payload instanceof PacketPayload.Bundle b) for (var child : b.children()) { kinds.merge(kind + ":Bundle/" + child.getClass().getSimpleName(), 1, Integer::sum); print(filter, input.sequence(), child, true); }
                else if (payload != null) print(filter, input.sequence(), payload, false);
            } else kinds.merge(frame.getClass().getSimpleName(), 1, Integer::sum);
        }
        System.out.println("sequences " + firstSeq + ".." + lastSeq);
        kinds.forEach((k, v) -> System.out.println("  " + k + " = " + v));
    }
    private static void print(Set<String> filter, long sequence, PacketPayload payload, boolean inBundle) {
        if (filter.isEmpty() || !filter.contains(payload.getClass().getSimpleName())) return;
        String text = payload.toString(); if (text.length() > 300) text = text.substring(0, 300) + "...";
        System.out.println("seq " + sequence + (inBundle ? " (bundle) " : " ") + text);
    }
}
