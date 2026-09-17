package me.nicolas.papea2ac.model;
/** Locally-minted connection identity that exists before any Minecraft player identity. Never derived from client-supplied data. */
public record TransportSessionId(long value) {
    public TransportSessionId { if (value < 1) throw new IllegalArgumentException("transport session id"); }
}
