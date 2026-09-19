package me.nicolas.papea2ac.protocol;
import java.util.List;
import java.util.Objects;
/** Typed immutable values. Omitted movement fields remain omitted. */
public sealed interface PacketPayload permits PacketPayload.Movement,PacketPayload.Control,PacketPayload.Velocity,
        PacketPayload.Explosion,PacketPayload.Vehicle,PacketPayload.Passengers,PacketPayload.Health,PacketPayload.TickRate,
        PacketPayload.Uncovered,PacketPayload.Bundle,SemanticPayload {
    int chargedBytes();
    /** Bytes of BULK content (PERFORMANCE.md "bulk/scratch" pool: charged against the per-session exposure ceiling, not the ingress record arena). 0 for ordinary records. */
    default int bulkBytes() { return 0; }
    /** The same observation with its bulk content deliberately not retained (explicit, never fabricated); identity when there is none. */
    default PacketPayload shedBulk(String reason) { return this; }
    record Movement(Position position, Rotation rotation, boolean onGround, boolean horizontalCollision) implements PacketPayload {
        public int chargedBytes() { return 160; }
    }
    record Position(double x, double y, double z) {
        public Position { if (!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) throw new IllegalArgumentException("nonfinite position"); }
    }
    record Rotation(float yaw, float pitch) {
        public Rotation { if (!Float.isFinite(yaw)||!Float.isFinite(pitch)) throw new IllegalArgumentException("nonfinite rotation"); }
    }
    enum ControlKind { INPUT, ACTION, SWING, TICK_END, HELD_ITEM, TELEPORT_CONFIRM, KEEPALIVE, PONG, CAMERA, TICK_STEP, PADDLE, PING }
    record Control(ControlKind kind, long value) implements PacketPayload {
        public Control { Objects.requireNonNull(kind); }
        public int chargedBytes() { return 96; }
    }
    record Velocity(int entityId, Position vector) implements PacketPayload {
        public Velocity {Objects.requireNonNull(vector);if(entityId<0)throw new IllegalArgumentException("entity id");}
        public int chargedBytes(){return 128;}
    }
    /**
     * radius/blockCount are included because PACKET_PIPELINE.md's Observation Matrix scopes this row
     * to "ExternalMotionInfluence" (motion/affected-scope), which they are part of; explosionParticle/
     * explosionSound/blockParticles are cosmetic-only per that same scoping and are intentionally
     * never read - a known-irrelevant omission per an explicit citation, not an unexplained gap.
     */
    record Explosion(Position center, float radius, int blockCount, java.util.Optional<Position> playerKnockback) implements PacketPayload {
        public Explosion {Objects.requireNonNull(center);Objects.requireNonNull(playerKnockback);if(!Float.isFinite(radius)||radius<0)throw new IllegalArgumentException("explosion radius");}
        public int chargedBytes(){return 192;}
    }
    record Vehicle(Position position, Rotation rotation, boolean onGround) implements PacketPayload {
        public Vehicle {Objects.requireNonNull(position);Objects.requireNonNull(rotation);}
        public int chargedBytes(){return 160;}
    }
    record Passengers(int vehicleId, List<Integer> passengers) implements PacketPayload {
        public Passengers {
            Objects.requireNonNull(passengers);
            if(vehicleId<0||passengers.size()>256||passengers.stream().anyMatch(id->id==null||id<0))throw new IllegalArgumentException("passengers");
            passengers=List.copyOf(passengers);
        }
        public int chargedBytes(){return 128+32*passengers.size();}
    }
    record Health(float health, int food, float saturation) implements PacketPayload {
        public Health {if(!Float.isFinite(health)||!Float.isFinite(saturation))throw new IllegalArgumentException("health");}
        public int chargedBytes(){return 96;}
    }
    record TickRate(float rate, boolean frozen) implements PacketPayload {
        public TickRate {if(!Float.isFinite(rate)||rate<=0)throw new IllegalArgumentException("rate");}
        public int chargedBytes(){return 96;}
    }
    enum Family { ENTITY, VELOCITY, EXPLOSION, VEHICLE, PASSENGERS, CAMERA, LIFECYCLE,
        HEALTH, TICK_RATE, INVENTORY, ATTRIBUTES, WORLD, INTERACTION, PROTOCOL, UNKNOWN }
    /** An explicitly uncovered semantic family; never interpreted as a complete normalized packet. */
    record Uncovered(Family family, String reason) implements PacketPayload {
        public Uncovered { Objects.requireNonNull(family); Objects.requireNonNull(reason); if(reason.length()>128)throw new IllegalArgumentException("reason"); }
        public int chargedBytes() { return 384; }
    }
    record Bundle(List<PacketPayload> children) implements PacketPayload {
        @Override public int bulkBytes() { int b = 0; for (var c : children) b += c.bulkBytes(); return b; }
        @Override public PacketPayload shedBulk(String reason) { if (bulkBytes() == 0) return this; var out = new java.util.ArrayList<PacketPayload>(children.size()); for (var c : children) out.add(c.shedBulk(reason)); return new Bundle(out); }
        public Bundle {
            Objects.requireNonNull(children);
            if(children.size()>256 || children.stream().anyMatch(p -> p instanceof Bundle)) throw new IllegalArgumentException("bundle");
            children=List.copyOf(children);
            if(children.stream().mapToLong(PacketPayload::chargedBytes).sum()>65536)throw new IllegalArgumentException("bundle bytes");
        }
        public int chargedBytes() { return 64+children.stream().mapToInt(PacketPayload::chargedBytes).sum(); }
    }
}
