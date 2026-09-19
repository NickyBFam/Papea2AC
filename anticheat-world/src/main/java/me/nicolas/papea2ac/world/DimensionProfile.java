package me.nicolas.papea2ac.world;
import java.util.Objects;
/**
 * Platform-independent dimension identity + vertical range, taken from the JoinGame/Respawn observation the client
 * itself received (P3-E1) - never from Bukkit World/NMS dimension objects and never hard-coded 0..255.
 * Section indexing handles negative Y via arithmetic shift: blockY -64 -> sectionY -4 -> index 0 in the overworld.
 */
public record DimensionProfile(String dimensionId, int minY, int height) {
    public DimensionProfile {
        Objects.requireNonNull(dimensionId);
        if (minY % 16 != 0 || height % 16 != 0 || height < 16 || height > 4064 || minY < -2032 || minY + height > 2032) throw new IllegalArgumentException("dimension range");
    }
    public int maxY() { return minY + height - 1; }
    public int minSectionY() { return minY >> 4; }
    public int sectionCount() { return height >> 4; }
    public boolean containsY(int blockY) { return blockY >= minY && blockY <= maxY(); }
    /** Section array index for a block Y, or -1 when outside the dimension. */
    public int sectionIndexOf(int blockY) { if (!containsY(blockY)) return -1; return (blockY >> 4) - minSectionY(); }
    public int sectionIndexOfSectionY(int sectionY) { int i = sectionY - minSectionY(); return i < 0 || i >= sectionCount() ? -1 : i; }
}
