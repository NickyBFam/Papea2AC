package me.nicolas.papea2ac.model;
import java.util.List;
import java.util.Objects;
/** Immutable server-current sample, not proof of client visibility. No geometry algorithm. */
public record DestinationRegion(String worldKey, long worldEpoch, long revision,
                                boolean complete, List<Cell> cells) {
    public DestinationRegion {
        Objects.requireNonNull(worldKey); Objects.requireNonNull(cells);
        if (worldKey.length() > 256 || worldEpoch < 0 || revision < 0 || cells.size() > 4096)
            throw new IllegalArgumentException("region bounds");
        cells = List.copyOf(cells);
    }
    public record Cell(int x, int y, int z, int blockStateId) {
        public Cell { if (blockStateId < 0) throw new IllegalArgumentException("state"); }
    }
}
