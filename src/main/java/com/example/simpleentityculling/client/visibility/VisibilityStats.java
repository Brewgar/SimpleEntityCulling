package com.example.simpleentityculling.client.visibility;

/**
 * Counters for the debug overlay. Per-tick counters are reset each client
 * tick; cumulative counters persist for hit-rate/average computations.
 * Plain fields are fine: worst case a counter lags by one frame.
 */
public final class VisibilityStats {
    // per-frame / per-tick windows -----------------------------------------
    public volatile int consideredTotal;   // entities reaching dispatcher.shouldRender (HEAD)
    public int candidates;                 // passed all vanilla checks (RETURN=true)
    public int rendered;                   // candidates we allow to render
    public int occlusionCulled;            // suppressed by confirmed HIDDEN state
    public int graceHidden;                // suppressed while refresh pending (grace window)
    public int cellCulled;                 // suppressed via dense spatial-cell result
    public int forcedRendered;             // special cases that bypass culling
    public int closeSkipped;               // too close for occlusion logic
    public int rangeSkipped;               // beyond occlusionDistance -> render normally
    public int cacheHits;
    public int testsRequested;
    public int testsDropped;               // queue full or budget exhausted at submit
    public int newEntries;

    public int queueSize;
    public int resultsPending;
    public int cacheEntries;
    public int denseCells;

    public long testNanosThisTick;
    public double avgTestMicros;

    // cumulative ------------------------------------------------------------
    public long totalTestsCompleted;
    public long totalCacheHits;
    public long totalOcclusionCulled;
    public long totalCellCulled;
    public long totalTestNanos;

    public void startTick() {
        candidates = 0;
        rendered = 0;
        occlusionCulled = 0;
        graceHidden = 0;
        cellCulled = 0;
        forcedRendered = 0;
        closeSkipped = 0;
        rangeSkipped = 0;
        cacheHits = 0;
        testsRequested = 0;
        testsDropped = 0;
        newEntries = 0;
        testNanosThisTick = 0;
    }

    public void endTick() {
        totalTestsCompleted += testsRequested > 0 ? completedProxy() : 0;
        totalCacheHits += cacheHits;
        totalOcclusionCulled += occlusionCulled;
        totalCellCulled += cellCulled;
        totalTestNanos += testNanosThisTick;
        if (testsRequested > 0) {
            avgTestMicros = (testNanosThisTick / 1000.0) / Math.max(1, completedProxy());
        } else {
            avgTestMicros = 0.0;
        }
    }

    private int completedProxy() {
        // results drained this tick; approximated via testsRequested minus drops
        return Math.max(0, testsRequested - testsDropped);
    }

    public double hitRatePercent() {
        long hits = totalCacheHits;
        long misses = totalTestsCompleted + hits; // every test follows a miss
        return misses == 0 ? 100.0 : (100.0 * hits) / (hits + misses);
    }
}
