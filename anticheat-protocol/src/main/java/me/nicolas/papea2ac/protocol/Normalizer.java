package me.nicolas.papea2ac.protocol;
import me.nicolas.papea2ac.version.ProtocolProfile;
public final class Normalizer {
    private Normalizer() {}
    public static PacketPayload.Movement movement(ProtocolProfile profile, boolean hasPosition, double x, double y,
            double z, boolean hasRotation, float yaw, float pitch, boolean ground, boolean collision) {
        if (hasPosition && (Math.abs(x)>profile.coordinateLimit() || Math.abs(y)>profile.coordinateLimit()
                || Math.abs(z)>profile.coordinateLimit())) throw new IllegalArgumentException("coordinate range");
        return new PacketPayload.Movement(hasPosition ? new PacketPayload.Position(x,y,z):null,
            hasRotation ? new PacketPayload.Rotation(yaw,pitch):null,ground,collision);
    }
}
