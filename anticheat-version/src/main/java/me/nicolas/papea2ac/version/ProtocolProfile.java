package me.nicolas.papea2ac.version;
/** Development profile. No native application or numeric qualification is implied. */
public record ProtocolProfile(String id, double coordinateLimit, int maxPayloadBytes) {
    public static final ProtocolProfile PAPER_26_2 = new ProtocolProfile("paper-26.2-124-observe-only", 3.2E7, 2*1024*1024);
    public ProtocolProfile {
        if (id == null || id.length()>128 || !Double.isFinite(coordinateLimit)
            || coordinateLimit <= 0 || maxPayloadBytes < 1 || maxPayloadBytes > 2*1024*1024)
            throw new IllegalArgumentException("profile");
    }
}
