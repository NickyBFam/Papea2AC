package me.nicolas.papea2ac.protocol;

import java.io.IOException;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.schema.TransportCapsule;

/** Owner-side mapping; runtime session IDs become caller-supplied replay pseudonyms. */
public final class TransportReplayMapper {
    private TransportReplayMapper() {}
    private static TransportCapsule.Payload encode(PacketPayload payload) throws IOException {
        return new TransportCapsule.Payload(PayloadCodec.encode(payload));
    }
    public static TransportCapsule.Input export(Observation observation, long origin, long processedAt) throws IOException {
        var c = observation.context();
        var context = new TransportCapsule.Context(c.worldEpoch(), c.direction().name(), c.sourceKind().name(),
                c.sourceEventId(), c.causalParentSequence(), c.serverTickObservation(), c.protocolState().name(), c.clientProtocol(), c.rulesetHash());
        TransportCapsule.Event event = switch (observation.event()) {
            case Observation.Attempt e -> new TransportCapsule.Attempt(e.id(), e.ingressOrdinal(), encode(e.payload()));
            case Observation.Outcome e -> new TransportCapsule.Outcome(e.attemptId(), e.processingOrdinal(), e.serverRevision(),
                    e.result().name(), e.finalPayload() == null ? null : encode(e.finalPayload()), e.originalDigest());
            case Observation.Outbound e -> new TransportCapsule.Outbound(e.wireOrdinal(), encode(e.payload()));
            case Observation.WriteOutcome e -> new TransportCapsule.WriteOutcome(e.wireOrdinal(), e.success());
            case Observation.Tick e -> new TransportCapsule.Tick(e.serverTick());
            case Observation.ControlChange e -> new TransportCapsule.ControlChange(e.kind(), e.value());
            case Observation.CheckpointMarker e -> new TransportCapsule.CheckpointMarker(e.requestId());
        };
        return new TransportCapsule.Input(processedAt - origin, observation.capturedNanos() - origin,
                observation.sequence(), observation.boundaryGeneration(), context, event);
    }
    public static Observation restore(TransportCapsule.Input input, SessionKey key) throws IOException {
        try {
            var c = input.context();
            var context = new Observation.Context(1, c.worldEpoch(), Observation.Direction.valueOf(c.direction()),
                    Observation.SourceKind.valueOf(c.sourceKind()), c.sourceEventId(), c.causalParentSequence(), c.serverTick(),
                    Observation.ProtocolState.valueOf(c.protocolState()), c.clientProtocol(), c.rulesetHash());
            Observation.Event event = switch (input.event()) {
                case TransportCapsule.Attempt e -> new Observation.Attempt(e.id(), e.ingressOrdinal(), PayloadCodec.decode(e.payload().bytes()));
                case TransportCapsule.Outcome e -> new Observation.Outcome(e.attemptId(), e.processingOrdinal(), e.serverRevision(),
                        Observation.Result.valueOf(e.result()), e.finalPayload() == null ? null : PayloadCodec.decode(e.finalPayload().bytes()), e.originalDigest());
                case TransportCapsule.Outbound e -> new Observation.Outbound(e.wireOrdinal(), PayloadCodec.decode(e.payload().bytes()));
                case TransportCapsule.WriteOutcome e -> new Observation.WriteOutcome(e.wireOrdinal(), e.success());
                case TransportCapsule.Tick e -> new Observation.Tick(e.serverTick());
                case TransportCapsule.ControlChange e -> new Observation.ControlChange(e.kind(), e.value());
                case TransportCapsule.CheckpointMarker e -> new Observation.CheckpointMarker(e.requestId());
            };
            return new Observation(key, input.sequence(), input.capturedNanos(), input.generation(), event, context);
        } catch (IllegalArgumentException e) { throw new IOException("invalid transport record", e); }
    }
}
