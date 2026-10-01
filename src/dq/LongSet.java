package dq;

/**
 * A set of 64-bit hashes in one long[] (open addressing). Used to count distinct values and duplicate rows exactly
 * without keeping every string: 1 million entries cost about 16 MB instead of ~100 MB of Strings.
 * Two different values sharing a 64-bit hash is possible but vanishingly rare at these sizes (~1 in 10^7 for 1M values).
 */
final class LongSet {
    private static final long EMPTY = 0L;       // hashes of 0 are remapped, so 0 can mark an empty slot
    private long[] slots = new long[1 << 10];
    private int size;

    /** Adds h; returns true if it was new. */
    boolean add(long h) {
        if (h == EMPTY) h = 0x9E3779B97F4A7C15L;
        if (size * 2 >= slots.length) grow();
        int mask = slots.length - 1, i = (int) (mix(h) & mask);
        while (slots[i] != EMPTY) {
            if (slots[i] == h) return false;
            i = (i + 1) & mask;
        }
        slots[i] = h;
        size++;
        return true;
    }

    int size() { return size; }

    private void grow() {
        long[] old = slots;
        slots = new long[old.length * 2];
        size = 0;
        for (long h : old) if (h != EMPTY) add(h);
    }

    private static long mix(long h) {           // spread the bits so nearby hashes don't cluster
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return h;
    }

    /** 64-bit FNV-1a hash of a string. */
    static long hash(CharSequence s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0x100000001b3L; }
        return h;
    }
}
