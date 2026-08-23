package com.example.simpleentityculling.client.spatial;

/**
 * Mutable per-cell state. Cell objects are pooled by {@link SpatialIndex}
 * and reused across ticks to avoid allocation churn.
 */
public final class SpatialCell {
    public static final byte AMBIGUOUS = 0;
    public static final byte CLEARLY_VISIBLE = 1;
    public static final byte CLEARLY_HIDDEN = 2;

    public int entityCount;
    public boolean dense;

    /** One of {@link #AMBIGUOUS}, {@link #CLEARLY_VISIBLE}, {@link #CLEARLY_HIDDEN}. */
    public byte visibilityState = AMBIGUOUS;
    /** Camera generation the classification belongs to. */
    public int generation = Integer.MIN_VALUE;
    public long lastEvalTick = Long.MIN_VALUE;
    public boolean probePending;
    public long lastUsedTick;

    public double minX, minY, minZ;
    public double maxX, maxY, maxZ;
}
