package me.nicolas.papea2ac.model;
/**
 * Immutable chunk column identity: chunk coordinates only. Dimension/world scoping is the observer world's
 * job (an ObserverWorld belongs to one SessionKey and one world epoch), so a ChunkKey never silently
 * resolves across dimensions - a chunk from the previous epoch is simply not in the new epoch's map.
 * Floor-division conversion is explicit: {@code x >> 4} is arithmetic shift = floor(x/16) for negatives,
 * unlike {@code x / 16} which truncates toward zero.
 */
public record ChunkKey(int x, int z) {
    /** Protocol/serializer chunk-coordinate range for a 30-bit block coordinate space. */
    public static final int MAX_ABS = 1 << 26;
    public ChunkKey {
        if (x < -MAX_ABS || x > MAX_ABS || z < -MAX_ABS || z > MAX_ABS) throw new IllegalArgumentException("chunk coordinate range");
    }
    public static ChunkKey ofBlock(int blockX, int blockZ) { return new ChunkKey(blockX >> 4, blockZ >> 4); }
    public static int localOf(int block) { return block & 15; }
    /** Chebyshev distance in chunks, overflow-safe via long arithmetic. */
    public int distanceTo(ChunkKey other) {
        long dx = Math.abs((long) x - other.x), dz = Math.abs((long) z - other.z);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(dx, dz));
    }
}
