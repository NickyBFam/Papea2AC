package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.world.*;

/** Section 27 (palette/storage safety) and the copy-on-write / known-mask contract. */
class SectionSnapshotTest {
    static long[] packed(int bits, int[] idx) { int perLong = 64 / bits; long[] out = new long[SectionSnapshot.packedLongs(bits)]; for (int i = 0; i < 4096; i++) out[i / perLong] |= ((long) idx[i]) << ((i % perLong) * bits); return out; }

    @Test void paletteLookupDecodesEveryCellAtEveryBitWidth() {
        for (int bits : new int[]{4, 5, 6, 7, 8, 9, 12}) {
            int n = Math.min(1 << bits, 4096); int[] palette = new int[n]; for (int i = 0; i < n; i++) palette[i] = 100 + i;
            int[] idx = new int[4096]; for (int i = 0; i < 4096; i++) idx[i] = i % n;
            var s = SectionSnapshot.fromPalette(palette, bits, packed(bits, idx));
            for (int i = 0; i < 4096; i++) assertEquals(100 + (i % n), s.stateIdAt(i), "bits=" + bits + " i=" + i);
            assertTrue(s.complete());
        }
    }
    @Test void malformedPaletteInputFailsSafely() {
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[0], 0, new long[0]), "empty palette");
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[]{1, 2}, 0, new long[0]), "two states cannot be 0 bits");
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[]{1}, 4, new long[10]), "wrong packed length");
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[4097], 12, new long[SectionSnapshot.packedLongs(12)]), "palette too large");
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[]{1, 2, 3}, 13, new long[1]), "bits too large");
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[]{-1}, 0, new long[0]), "unresolved id");
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[]{5, 6}, 4, packed(4, fill(3))), "index 3 beyond a 2-entry palette");
    }
    static int[] fill(int v) { int[] a = new int[4096]; Arrays.fill(a, v); return a; }
    @Test void invalidPackedIndexIsRejectedBeforeAnyCellIsTrusted() {
        int[] idx = new int[4096]; idx[4095] = 7; // palette has 2 entries
        assertThrows(IllegalArgumentException.class, () -> SectionSnapshot.fromPalette(new int[]{1, 2}, 4, packed(4, idx)));
    }
    @Test void copyOnWriteLeavesTheOriginalUntouchedAndGrowsBitsOnlyWhenNeeded() {
        var uniform = SectionSnapshot.uniform(1);
        assertEquals(0, uniform.bitsPerEntry());
        var one = uniform.with(0, 2);
        assertEquals(1, uniform.stateIdAt(0), "original unchanged"); assertEquals(2, one.stateIdAt(0)); assertEquals(1, one.stateIdAt(1));
        assertEquals(4, one.bitsPerEntry()); assertEquals(2, one.paletteSize());
        var same = one.with(5, 2); assertEquals(2, same.paletteSize(), "existing state reuses its slot");
        var s = one; for (int i = 3; i <= 17; i++) s = s.with(i, i); // 17 distinct -> needs 5 bits
        assertEquals(5, s.bitsPerEntry()); for (int i = 3; i <= 17; i++) assertEquals(i, s.stateIdAt(i));
        assertEquals(1, s.stateIdAt(4000), "untouched cells keep the old state after repacking");
    }
    @Test void unknownSectionKnowsOnlyTheCellsItWasTold() {
        var u = SectionSnapshot.unknown();
        assertFalse(u.complete()); assertEquals(0, u.knownCount()); assertEquals(-1, u.stateIdAt(123));
        var one = u.with(123, 9);
        assertEquals(9, one.stateIdAt(123)); assertEquals(-1, one.stateIdAt(124), "neighbours stay unknown, never air"); assertEquals(1, one.knownCount()); assertFalse(one.complete());
        assertEquals(-1, u.stateIdAt(123), "original unknown section is untouched");
    }
    @Test void aPartialSectionBecomesCompleteOnlyWhenEveryCellHasBeenObserved() {
        var s = SectionSnapshot.unknown();
        for (int i = 0; i < 4095; i++) s = s.with(i, 1);
        assertFalse(s.complete()); assertEquals(4095, s.knownCount());
        s = s.with(4095, 1);
        assertTrue(s.complete());
    }
    @Test void contentEqualityMakesIdenticalBaselinesShareable() {
        int[] idx = new int[4096]; for (int i = 0; i < 4096; i++) idx[i] = i & 1;
        var a = SectionSnapshot.fromPalette(new int[]{1, 2}, 4, packed(4, idx)); var b = SectionSnapshot.fromPalette(new int[]{1, 2}, 4, packed(4, idx));
        assertNotSame(a, b); assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.with(0, 2));
    }
    @Test void chargedBytesTrackTheRetainedArrays() {
        assertEquals(48 + 4, SectionSnapshot.uniform(1).chargedBytes());
        assertEquals(48 + 8 + 8 * 256, SectionSnapshot.uniform(1).with(0, 2).chargedBytes());
        assertEquals(48 + 512, SectionSnapshot.unknown().chargedBytes(), "empty palette: only the known mask is retained");
        assertEquals(48 + 4 + 512, SectionSnapshot.unknown().with(7, 3).chargedBytes());
    }
    /** withAll is the batched, copy-once form of with(): identical result (later entries win, palette/bit growth included) from every starting shape. */
    @Test void batchedUpdateEqualsSequentialUpdatesFromEveryStartingShape() {
        var r = new Random(4096);
        int[] idx = new int[4096]; for (int i = 0; i < 4096; i++) idx[i] = i & 1;
        var starts = List.of(SectionSnapshot.unknown(), SectionSnapshot.uniform(7), SectionSnapshot.fromPalette(new int[]{1, 2}, 4, packed(4, idx)), SectionSnapshot.unknown().with(5, 9));
        for (var start : starts) for (int trial = 0; trial < 40; trial++) {
            int n = 1 + r.nextInt(trial < 20 ? 8 : 60); int[] indices = new int[n], ids = new int[n];
            for (int k = 0; k < n; k++) { indices[k] = r.nextInt(4096); ids[k] = r.nextInt(trial < 20 ? 3 : 40); }
            var sequential = start; for (int k = 0; k < n; k++) sequential = sequential.with(indices[k], ids[k]);
            var batched = start.withAll(indices, ids);
            assertEquals(sequential, batched, "start=" + start + " trial=" + trial);
            for (int i = 0; i < 4096; i++) assertEquals(sequential.stateIdAt(i), batched.stateIdAt(i));
            assertEquals(sequential.knownCount(), batched.knownCount()); assertEquals(sequential.bitsPerEntry(), batched.bitsPerEntry());
        }
        assertSame(starts.get(1), starts.get(1).withAll(new int[0], new int[0]), "empty batch is the identity");
        assertThrows(IllegalArgumentException.class, () -> starts.get(1).withAll(new int[]{1}, new int[]{1, 2}));
    }
}
