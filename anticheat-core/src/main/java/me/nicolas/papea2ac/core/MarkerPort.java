package me.nicolas.papea2ac.core;
import java.util.UUID;
/**
 * Platform port for outbound timing markers (LATENCY_COMPENSATION.md N1: "Markers use a supported play-state request/response
 * mechanism selected from the pinned protocol, initially a ping/pong-style candidate"). The platform writes the pinned protocol's
 * clientbound ping with the given id on the player's connection; the write passes the ordinary outbound capture (it becomes an
 * Outbound PING observation with a wire ordinal and a WriteOutcome), and the client's pong is captured inbound. Core never sees
 * a channel or a packet class. A marker is diagnostic traffic: the server ignores pongs ({@code handlePong} is a no-op in the
 * pinned jar), and a client that never answers only loses RTT evidence (never a violation).
 */
public interface MarkerPort {
    /** @return false when the marker could not be handed to the transport (player gone, channel closed); counted, never retried. */
    boolean sendMarker(UUID player, int id);
}
