package com.example.simpleentityculling.client.spatial;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Lightweight per-tick entity histogram over an {@code 8^3} block grid.
 *
 * <p>Cells that exceed the configured density threshold can share one coarse
 * visibility evaluation (a conservative 3x3x3 sample grid over the cell
 * volume) instead of dozens of individual probes. Classification is strictly
 * conservative: only "clearly visible" and "clearly hidden" results are
 * applied; anything else falls through to per-entity logic.</p>
 */
public final class SpatialIndex {
    public static final int CELL_SIZE = 8;
    private static final long CELL_TTL_TICKS = 400;

    private final Map<Long, SpatialCell> cells = new HashMap<>();

    /** Zeroes per-tick counters; keeps pooled cells and their bounds. */
    public void beginUpdate() {
        for (SpatialCell c : cells.values()) {
            c.entityCount = 0;
        }
    }

    public void visitEntity(double x, double y, double z, long tick) {
        int cx = Math.floorDiv(floor(x), CELL_SIZE);
        int cy = Math.floorDiv(floor(y), CELL_SIZE);
        int cz = Math.floorDiv(floor(z), CELL_SIZE);
        long key = pack(cx, cy, cz);
        SpatialCell c = cells.get(key);
        if (c == null) {
            if (cells.size() >= MAX_CELLS_TRACKED) {
                return; // pathological case: refuse to grow unbounded
            }
            c = new SpatialCell();
            c.minX = cx * (double) CELL_SIZE;
            c.minY = cy * (double) CELL_SIZE;
            c.minZ = cz * (double) CELL_SIZE;
            c.maxX = c.minX + CELL_SIZE;
            c.maxY = c.minY + CELL_SIZE;
            c.maxZ = c.minZ + CELL_SIZE;
            c.lastUsedTick = tick;
            cells.put(key, c);
        }
        c.entityCount++;
        c.lastUsedTick = tick;
    }

    public void endUpdate(long tick, int denseThreshold) {
        Iterator<Map.Entry<Long, SpatialCell>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            SpatialCell c = it.next().getValue();
            c.dense = denseThreshold > 0 && c.entityCount >= denseThreshold;
            if (!c.dense) {
                // Non-dense cells never carry a shared classification.
                c.visibilityState = SpatialCell.AMBIGUOUS;
                c.probePending = false;
                if (tick - c.lastUsedTick > CELL_TTL_TICKS) {
                    it.remove();
                }
            }
        }
    }

    /** @return the cell containing the point, or null when absent. */
    public SpatialCell lookup(double x, double y, double z) {
        return cells.get(pack(Math.floorDiv(floor(x), CELL_SIZE),
                Math.floorDiv(floor(y), CELL_SIZE),
                Math.floorDiv(floor(z), CELL_SIZE)));
    }

    public SpatialCell byPackedKey(long key) {
        return cells.get(key);
    }

    public Iterable<Map.Entry<Long, SpatialCell>> entries() {
        return cells.entrySet();
    }

    public int cellCount() {
        return cells.size();
    }

    public void clear() {
        cells.clear();
    }

    private static final int MAX_CELLS_TRACKED = 16384;

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    public static long pack(int cx, int cy, int cz) {
        return ((long) (cx & 0x3FFFFF) << 44) | ((long) (cy & 0x3FFFFF) << 22) | (long) (cz & 0x3FFFFF);
    }
}
