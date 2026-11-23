package me.nicolas.papea2ac.testing;
import java.util.UUID;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;

/** Thread 8 (cross-phase performance) shared payload builders. */
final class CrossPhaseSupport {
    private CrossPhaseSupport() {}
    static SemanticPayload.EntitySpawn spawn(int id, double x, double z) {
        return new SemanticPayload.EntitySpawn(id, new UUID(1, id), "minecraft:zombie", new PacketPayload.Position(x, 64, z), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), 0f, 0);
    }
    static SemanticPayload.EntityDelta move(int id) { return new SemanticPayload.EntityDelta(id, (short) 37, (short) 0, (short) -11, new PacketPayload.Rotation(12f, 3f), true, true, true); }
}
