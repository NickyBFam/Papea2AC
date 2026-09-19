package me.nicolas.papea2ac.testing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.schema.TimelineCapsule;

/**
 * Compact, code-defined Phase 2 timeline fixtures (no binary artifacts): each is a scripted normalized
 * observation sequence whose replayed timeline must be byte-for-byte identical on every run and every host.
 * The pinned digests live in docs/phase2-artifacts/timeline-fixtures.json (regenerate deliberately with the
 * `timelineFixtures` Gradle task; a changed digest is a reviewed event, never a silent update).
 */
final class TimelineFixtures {
    private TimelineFixtures() {}
    static final Vector3 A = new Vector3(1, 64, 1), B = new Vector3(1.5, 64, 1.25), C = new Vector3(2, 65, 1.5);
    static final Rotation R0 = new Rotation(0, 0), R1 = new Rotation(45, -10), R2 = new Rotation(-90, 30);

    record Step(PlayerObservation observation, String transportDegradeReason) {
        Step(PlayerObservation observation) { this(observation, ""); }
    }
    record Fixture(String name, long sessionEpoch, List<Step> steps) {}

    static PlayerObservation move(Vector3 p, Rotation r) { return new PlayerObservation.MovementClaim(Optional.ofNullable(p), Optional.ofNullable(r), true, false); }

    static List<Fixture> all() {
        return List.of(
            new Fixture("basicMovement", 1, List.of(new Step(move(A, R0)), new Step(move(B, R0)), new Step(move(C, R0)))),
            new Fixture("positionOnly", 1, List.of(new Step(move(A, null)), new Step(move(B, null)))),
            new Fixture("rotationOnly", 1, List.of(new Step(move(null, R1)), new Step(move(null, R2)))),
            new Fixture("positionAndRotation", 1, List.of(new Step(move(A, R1)), new Step(move(B, R2)))),
            new Fixture("teleportObservation", 1, List.of(new Step(move(A, R0)), new Step(new PlayerObservation.TeleportSent(7, C, R1)),
                    new Step(new PlayerObservation.TeleportAcknowledged(7)), new Step(move(C, R1)))),
            new Fixture("velocityObservation", 1, List.of(new Step(move(A, R0)),
                    new Step(new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_REPLACE, "entity:9", new Vector3(0, 0.4, 0), 0)),
                    new Step(new PlayerObservation.ExternalMotionObserved(ExternalMotionInfluence.Mechanism.VELOCITY_DELTA, "explosion", new Vector3(0.3, 0.5, -0.2), 0)),
                    new Step(move(B, R0)))),
            new Fixture("captureBoundary", 1, List.of(new Step(move(A, R0)), new Step(new PlayerObservation.CoverageGap("PIPELINE_UNCOVERED"), "PIPELINE_UNCOVERED"),
                    new Step(move(B, R0), "PIPELINE_UNCOVERED"))),
            new Fixture("worldDiscontinuity", 1, List.of(new Step(move(A, R0)), new Step(new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.RESPAWN)),
                    new Step(move(new Vector3(0, 70, 0), R0)), new Step(new PlayerObservation.Discontinuity(PlayerObservation.DiscontinuityKind.DIMENSION_CHANGE)),
                    new Step(move(new Vector3(0, 40, 0), R0)))),
            new Fixture("reconnectNewSession", 2, List.of(new Step(move(C, R2)), new Step(new PlayerObservation.SessionClosed()))));
    }

    static TimelineCapsule capsule(Fixture fixture) {
        var entries = new ArrayList<TimelineCapsule.Entry>();
        long seq = 0;
        for (var step : fixture.steps()) { seq++; entries.add(new TimelineCapsule.Entry(seq, seq * 50_000_000L, TimelineReplayMapper.export(step.observation()), step.transportDegradeReason())); }
        return new TimelineCapsule(1, fixture.sessionEpoch(), entries, true, "");
    }

    /** Canonical rendering: Java record toString is field-ordered and Double.toString is deterministic, so this is stable across runs and hosts. */
    static String digest(TimelineReplayMapper.ReplayResult replay) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (var r : replay.records())
                md.update((r.captureSequence() + "|" + r.kind() + "|" + r.worldEpoch() + "|" + r.uncertaintyReasons() + "|" + r.transportDegradeReason() + "|" + r.resultingState() + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
