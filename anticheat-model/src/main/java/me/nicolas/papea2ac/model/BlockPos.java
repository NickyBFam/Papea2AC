package me.nicolas.papea2ac.model;
/**
 * Compact immutable block position. Packed-long form (for maps/keys) uses the vanilla allocation: x and z 26 bits
 * each (signed, +/-33,554,432), y 12 bits (signed, -2048..2047, which covers the protocol's -2032..2031 world
 * height range). Coordinates outside those ranges throw - they are never silently wrapped.
 */
public record BlockPos(int x, int y, int z) {
    public static final int MAX_HORIZONTAL = (1 << 25) - 1, MIN_HORIZONTAL = -(1 << 25);
    public static final int MAX_Y = 2047, MIN_Y = -2048;
    public BlockPos {
        if (x < MIN_HORIZONTAL || x > MAX_HORIZONTAL || z < MIN_HORIZONTAL || z > MAX_HORIZONTAL) throw new IllegalArgumentException("horizontal coordinate range");
        if (y < MIN_Y || y > MAX_Y) throw new IllegalArgumentException("vertical coordinate range");
    }
    public ChunkKey chunk() { return ChunkKey.ofBlock(x, z); }
    /** Arithmetic shift: floor division for negatives (block -1 is in section -1, local 15). */
    public int sectionY() { return y >> 4; }
    public int localX() { return x & 15; }
    public int localY() { return y & 15; }
    public int localZ() { return z & 15; }
    /** Index inside a 16x16x16 section in the wire/storage order (y<<8 | z<<4 | x). */
    public int sectionIndex() { return (localY() << 8) | (localZ() << 4) | localX(); }
    public long packed() { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    public static BlockPos unpack(long packed) {
        int x = (int) (packed << 0 >> 38), z = (int) (packed << 26 >> 38), y = (int) (packed << 52 >> 52);
        return new BlockPos(x, y, z);
    }
}
