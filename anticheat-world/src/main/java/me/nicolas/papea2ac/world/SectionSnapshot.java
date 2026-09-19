package me.nicolas.papea2ac.world;
import java.util.Arrays;
/**
 * Immutable 16x16x16 block-state section: an int palette of dictionary ids plus a packed bit array
 * (index = y<<8 | z<<4 | x, values never straddle a long), never 4096 objects. A {@code knownMask} (64 longs)
 * marks which cells were actually observed; {@code null} means every cell is known (a full baseline). A section
 * created from a single block update without a baseline therefore has exactly one known cell - the rest are
 * UNKNOWN, never air (WORLD_MODEL.md: "a delta without baseline is incomplete"; "Unknown is neither air nor solid").
 * Copy-on-write: {@link #with} returns a new section sharing nothing mutable with the old one, so historical
 * snapshots stay immutable and no parent chain forms (each snapshot owns its own arrays).
 * Equality is by content, which is what makes identical baselines shareable across observers.
 */
public final class SectionSnapshot {
    public static final int BLOCKS = 4096, MAX_PALETTE = 4096, MAX_BITS = 12;
    private final int[] palette;
    private final int bits;
    private final long[] packed;
    private final long[] knownMask;
    private final int knownCount;

    private SectionSnapshot(int[] palette, int bits, long[] packed, long[] knownMask, int knownCount) {
        this.palette = palette; this.bits = bits; this.packed = packed; this.knownMask = knownMask; this.knownCount = knownCount;
    }

    public static int packedLongs(int bits) { if (bits == 0) return 0; int perLong = 64 / bits; return (BLOCKS + perLong - 1) / perLong; }

    /** Fully known section from decoded baseline content. Validates bits/palette/length before trusting a single index. */
    public static SectionSnapshot fromPalette(int[] palette, int bits, long[] packed) {
        if (palette.length < 1 || palette.length > MAX_PALETTE || bits < 0 || bits > MAX_BITS) throw new IllegalArgumentException("section palette");
        if (bits == 0 ? (palette.length != 1 || packed.length != 0) : (palette.length > (1 << bits) || packed.length != packedLongs(bits))) throw new IllegalArgumentException("section storage");
        for (int id : palette) if (id < 0) throw new IllegalArgumentException("unresolved palette id");
        if (bits > 0) { int perLong = 64 / bits, mask = (1 << bits) - 1, max = palette.length - 1;
            for (int i = 0; i < BLOCKS; i++) if (((int) (packed[i / perLong] >>> ((i % perLong) * bits)) & mask) > max) throw new IllegalArgumentException("palette index out of range"); }
        return new SectionSnapshot(palette.clone(), bits, packed.clone(), null, BLOCKS);
    }
    public static SectionSnapshot uniform(int stateId) {
        if (stateId < 0) throw new IllegalArgumentException("unresolved state id");
        return new SectionSnapshot(new int[]{stateId}, 0, new long[0], null, BLOCKS);
    }
    /** No cell known yet; only with() can populate it. The palette is EMPTY - no placeholder id, so nothing run-local can leak into a projection of a partial section. */
    public static SectionSnapshot unknown() { return new SectionSnapshot(new int[0], 0, new long[0], new long[64], 0); }

    public boolean complete() { return knownMask == null; }
    public int knownCount() { return knownCount; }
    public boolean known(int index) { return knownMask == null || (knownMask[index >>> 6] & (1L << (index & 63))) != 0; }
    public int bitsPerEntry() { return bits; }
    public int paletteSize() { return palette.length; }

    private int paletteIndex(int index) {
        if (bits == 0) return 0;
        int perLong = 64 / bits;
        return (int) (packed[index / perLong] >>> ((index % perLong) * bits)) & ((1 << bits) - 1);
    }
    /** Dictionary id at a section index, or -1 for an unobserved cell. Never throws for a valid index. */
    public int stateIdAt(int index) {
        if (index < 0 || index >= BLOCKS) throw new IllegalArgumentException("section index");
        if (!known(index)) return -1;
        return palette[paletteIndex(index)];
    }
    public int stateIdAt(int x, int y, int z) { return stateIdAt((y << 8) | (z << 4) | x); }

    /** Copy-on-write single-cell replacement: grows the palette (and bit width) only when the state is new to this section. */
    public SectionSnapshot with(int index, int stateId) {
        if (index < 0 || index >= BLOCKS || stateId < 0) throw new IllegalArgumentException("section update");
        if (palette.length == 0) { // first observed cell of an unknown section: single-slot palette, 0 bits, one known cell
            long[] mask = knownMask.clone(); mask[index >>> 6] |= 1L << (index & 63);
            return new SectionSnapshot(new int[]{stateId}, 0, new long[0], mask, 1);
        }
        int slot = -1;
        for (int i = 0; i < palette.length; i++) if (palette[i] == stateId) { slot = i; break; }
        int[] newPalette = palette; int newBits = bits; long[] newPacked;
        if (slot < 0) {
            if (palette.length >= MAX_PALETTE) throw new IllegalStateException("section palette exhausted");
            newPalette = Arrays.copyOf(palette, palette.length + 1); newPalette[palette.length] = stateId; slot = palette.length;
            int needed = Math.max(1, 32 - Integer.numberOfLeadingZeros(newPalette.length - 1));
            if (needed > bits) { newBits = Math.max(4, needed); newPacked = repack(newBits); }
            else newPacked = packed.clone();
        } else newPacked = bits == 0 ? new long[0] : packed.clone();
        if (newBits == 0) {
            // uniform section; the only way to stay at 0 bits is writing the same single state
            long[] mask = knownMask == null ? null : knownMask.clone();
            int count = knownCount;
            if (mask != null && (mask[index >>> 6] & (1L << (index & 63))) == 0) { mask[index >>> 6] |= 1L << (index & 63); count++; if (count == BLOCKS) mask = null; }
            return new SectionSnapshot(newPalette, 0, new long[0], mask, count);
        }
        int perLong = 64 / newBits, shift = (index % perLong) * newBits; long m = ((1L << newBits) - 1) << shift;
        newPacked[index / perLong] = (newPacked[index / perLong] & ~m) | (((long) slot) << shift);
        long[] mask = knownMask == null ? null : knownMask.clone();
        int count = knownCount;
        if (mask != null && (mask[index >>> 6] & (1L << (index & 63))) == 0) { mask[index >>> 6] |= 1L << (index & 63); count++; if (count == BLOCKS) mask = null; }
        return new SectionSnapshot(newPalette, newBits, newPacked, mask, count);
    }
    private long[] repack(int newBits) { return repack(packed, bits, newBits); }
    private static long[] repack(long[] src, int oldBits, int newBits) {
        long[] out = new long[packedLongs(newBits)];
        int perLong = 64 / newBits;
        if (oldBits == 0) return out; // every cell was palette slot 0
        int oldPer = 64 / oldBits, oldMask = (1 << oldBits) - 1;
        for (int i = 0; i < BLOCKS; i++) { int slot = (int) (src[i / oldPer] >>> ((i % oldPer) * oldBits)) & oldMask; out[i / perLong] |= ((long) slot) << ((i % perLong) * newBits); }
        return out;
    }
    /**
     * Batched copy-on-write: the same result as applying {@link #with} cell by cell (later entries win), but the
     * arrays are copied ONCE, not once per cell - a section update packet may carry up to 4096 cells, and per-cell
     * copies would allocate megabytes per packet (JMH -prof gc finding).
     */
    public SectionSnapshot withAll(int[] indices, int[] stateIds) {
        if (indices.length != stateIds.length) throw new IllegalArgumentException("batch shape");
        if (indices.length == 0) return this;
        if (indices.length == 1) return with(indices[0], stateIds[0]);
        for (int k = 0; k < indices.length; k++) if (indices[k] < 0 || indices[k] >= BLOCKS || stateIds[k] < 0) throw new IllegalArgumentException("section update");
        int[] pal = Arrays.copyOf(palette, Math.max(2, palette.length)); int palSize = palette.length;
        int b = bits; long[] pk = packed.clone(); long[] mask = knownMask == null ? null : knownMask.clone(); int count = knownCount;
        for (int k = 0; k < indices.length; k++) {
            int index = indices[k], id = stateIds[k], slot = -1;
            for (int i = 0; i < palSize; i++) if (pal[i] == id) { slot = i; break; }
            if (slot < 0) {
                if (palSize >= MAX_PALETTE) throw new IllegalStateException("section palette exhausted");
                if (palSize == pal.length) pal = Arrays.copyOf(pal, Math.min(MAX_PALETTE, pal.length * 2));
                pal[palSize] = id; slot = palSize++;
                if (palSize > 1) { int needed = 32 - Integer.numberOfLeadingZeros(palSize - 1); if (needed > b) { int nb = Math.max(4, needed); pk = repack(pk, b, nb); b = nb; } }
            }
            if (b > 0) { int perLong = 64 / b, shift = (index % perLong) * b; long m = ((1L << b) - 1) << shift; pk[index / perLong] = (pk[index / perLong] & ~m) | (((long) slot) << shift); }
            if (mask != null && (mask[index >>> 6] & (1L << (index & 63))) == 0) { mask[index >>> 6] |= 1L << (index & 63); count++; }
        }
        if (mask != null && count == BLOCKS) mask = null;
        return new SectionSnapshot(Arrays.copyOf(pal, palSize), b, pk, mask, count);
    }
    // read-only part accessors for the replay projection (never the arrays themselves)
    public int paletteId(int slot) { return palette[slot]; }
    public int packedLength() { return packed.length; }
    public long packedWord(int i) { return packed[i]; }
    public long knownWord(int i) { return knownMask == null ? -1L : knownMask[i]; }
    /** Restore path: rebuilds a (possibly partial) section from projected parts, validating exactly like fromPalette. */
    public static SectionSnapshot fromParts(int[] palette, int bits, long[] packed, long[] knownMask) {
        if (palette.length == 0) { // an unknown section with no observed cell round-trips as exactly that
            if (bits != 0 || packed.length != 0 || knownMask == null || knownMask.length != 64) throw new IllegalArgumentException("empty palette");
            for (long w : knownMask) if (w != 0) throw new IllegalArgumentException("known cell without palette");
            return unknown();
        }
        SectionSnapshot full = bits == 0 && palette.length == 1 ? new SectionSnapshot(palette.clone(), 0, new long[0], null, BLOCKS) : fromPalette(palette, bits, packed);
        if (knownMask == null) return full;
        if (knownMask.length != 64) throw new IllegalArgumentException("known mask");
        int count = 0; for (long w : knownMask) count += Long.bitCount(w);
        if (count == BLOCKS) return full;
        return new SectionSnapshot(full.palette, full.bits, full.packed, knownMask.clone(), count);
    }
    /** Retained-heap estimate: arrays + header, matching PERFORMANCE.md's charge-what-you-retain rule. */
    public int chargedBytes() { return 48 + 4 * palette.length + 8 * packed.length + (knownMask == null ? 0 : 8 * 64); }

    @Override public boolean equals(Object o) {
        return o instanceof SectionSnapshot s && bits == s.bits && knownCount == s.knownCount && Arrays.equals(palette, s.palette) && Arrays.equals(packed, s.packed) && Arrays.equals(knownMask, s.knownMask);
    }
    @Override public int hashCode() { return ((bits * 31 + Arrays.hashCode(palette)) * 31 + Arrays.hashCode(packed)) * 31 + Arrays.hashCode(knownMask); }
    @Override public String toString() { return "SectionSnapshot[bits=" + bits + ",palette=" + palette.length + ",known=" + knownCount + "]"; }
}
