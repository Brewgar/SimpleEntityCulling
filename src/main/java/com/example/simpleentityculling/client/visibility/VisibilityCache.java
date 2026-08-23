package com.example.simpleentityculling.client.visibility;

/**
 * Compact open-addressing int-&gt;Entry map keyed by entity network id.
 *
 * <p>Entries hold only primitives - no {@code Entity} references - so stale
 * entries can never leak entities and are simply swept by age.</p>
 *
 * <p>Not thread-safe by itself; it is confined to the render/client thread.
 * Individual entries use volatile fields so results produced on the worker
 * thread and consumed here remain visible.</p>
 */
public final class VisibilityCache {
    public static final byte STATE_UNKNOWN = 0;
    public static final byte STATE_VISIBLE = 1;
    public static final byte STATE_PROBABLY_VISIBLE = 2;
    public static final byte STATE_PROBABLY_HIDDEN = 3;
    public static final byte STATE_HIDDEN = 4;

    /** Per-entity visibility record. Kept deliberately small. */
    public static final class Entry {
        public volatile byte state = STATE_UNKNOWN;
        /** Camera generation this entry was last validated against. */
        public volatile int generation = Integer.MIN_VALUE;
        /** World tick of the last completed visibility test. */
        public volatile long lastCheckTick;
        /** Non-zero while an async probe is in flight (deduplication guard). */
        public volatile long pendingTaskId;
        /** Consecutive blocked full-sampling confirmations. */
        public byte hiddenStreak;
        public boolean fullPending;
        public double lastEntX, lastEntY, lastEntZ;
        public boolean hasLastEnt;
    }

    private static final byte EMPTY = 0;
    private static final byte FULL = 1;
    private static final byte REMOVED = 2;
    private static final float MAX_LOAD = 0.6f;

    private int[] keys;
    private Entry[] values;
    private byte[] marks;
    private int mask;
    private int size;
    private int removedCount;
    private int growThreshold;

    public VisibilityCache(int initialCapacity) {
        int cap = Integer.highestOneBit(Math.max(16, initialCapacity) - 1) << 1;
        keys = new int[cap];
        values = new Entry[cap];
        marks = new byte[cap];
        mask = cap - 1;
        growThreshold = (int) (cap * MAX_LOAD);
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    public int size() {
        return size;
    }

    public Entry get(int key) {
        int i = mix(key) & mask;
        while (true) {
            byte m = marks[i];
            if (m == EMPTY) {
                return null;
            }
            if (m == FULL && keys[i] == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
    }

    /** Inserts a fresh entry for {@code key}; caller initializes its fields. */
    public Entry create(int key) {
        if (size + removedCount >= growThreshold) {
            rehash();
        }
        int i = mix(key) & mask;
        int insertAt = -1;
        while (true) {
            byte m = marks[i];
            if (m == EMPTY) {
                if (insertAt < 0) {
                    insertAt = i;
                }
                break;
            }
            if (m == REMOVED) {
                if (insertAt < 0) {
                    insertAt = i;
                }
                i = (i + 1) & mask;
                continue;
            }
            if (keys[i] == key) {
                return values[i]; // already present
            }
            i = (i + 1) & mask;
        }
        keys[insertAt] = key;
        Entry e = values[insertAt];
        if (e == null) {
            e = new Entry();
        } else {
            // reuse pooled entry object to avoid allocation churn
        }
        values[insertAt] = e;
        if (marks[insertAt] != FULL) {
            if (marks[insertAt] == REMOVED) {
                removedCount--;
            }
            size++;
        }
        marks[insertAt] = FULL;
        return e;
    }

    public void remove(int key) {
        int i = mix(key) & mask;
        while (true) {
            byte m = marks[i];
            if (m == EMPTY) {
                return;
            }
            if (m == FULL && keys[i] == key) {
                marks[i] = REMOVED;
                values[i].state = STATE_UNKNOWN;
                values[i].pendingTaskId = 0L;
                values[i].hiddenStreak = 0;
                values[i].fullPending = false;
                values[i].hasLastEnt = false;
                values[i].generation = Integer.MIN_VALUE;
                size--;
                removedCount++;
                return;
            }
            i = (i + 1) & mask;
        }
    }

    public void clear() {
        java.util.Arrays.fill(marks, EMPTY);
        java.util.Arrays.fill(values, null);
        size = 0;
        removedCount = 0;
    }

    private void rehash() {
        int newCap = keys.length << 1;
        int[] oldKeys = keys;
        Entry[] oldVals = values;
        byte[] oldMarks = marks;
        keys = new int[newCap];
        values = new Entry[newCap];
        marks = new byte[newCap];
        mask = newCap - 1;
        growThreshold = (int) (newCap * MAX_LOAD);
        size = 0;
        removedCount = 0;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldMarks[j] != FULL) {
                continue;
            }
            int i = mix(oldKeys[j]) & mask;
            while (marks[i] == FULL) {
                i = (i + 1) & mask;
            }
            keys[i] = oldKeys[j];
            values[i] = oldVals[j];
            marks[i] = FULL;
            size++;
        }
    }
}
