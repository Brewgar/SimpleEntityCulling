package com.example.simpleentityculling.client.config;

/**
 * Performance profiles. Each profile bundles internally-tuned parameters that
 * trade conservatism against culling aggressiveness.
 *
 * <ul>
 *   <li>{@link #SAFE} - correctness first, frequent revalidation.</li>
 *   <li>{@link #BALANCED} - default; strong savings without obvious artifacts.</li>
 *   <li>{@link #AGGRESSIVE} - maximum reuse, lower refresh frequency.</li>
 * </ul>
 */
public enum PerformanceProfile {
    SAFE(24, 10, 96, 4, 3, 0.30, 4.0),
    BALANCED(64, 20, 256, 6, 2, 0.50, 8.0),
    AGGRESSIVE(128, 40, 512, 8, 1, 1.00, 15.0);

    /** Maximum occlusion probes started per tick on the client thread. */
    public final int maxTestsPerTick;
    /** Ticks a visibility result is reused before refresh is required. */
    public final int cacheDurationTicks;
    /** Maximum queued (not yet evaluated) probes. */
    public final int maxQueueSize;
    /** Upper bound of sample points tested for a single entity probe. */
    public final int maxSamplesPerEntity;
    /** Consecutive blocked full-samplings required before an entity is HIDDEN. */
    public final int hiddenConfirmationsRequired;
    /** Camera translation (blocks) that invalidates cached visibility. */
    public final double cameraPositionThreshold;
    /** Camera rotation (degrees) that invalidates cached visibility. */
    public final double cameraRotationThreshold;

    PerformanceProfile(int maxTestsPerTick, int cacheDurationTicks, int maxQueueSize,
                       int maxSamplesPerEntity, int hiddenConfirmationsRequired,
                       double cameraPositionThreshold, double cameraRotationThreshold) {
        this.maxTestsPerTick = maxTestsPerTick;
        this.cacheDurationTicks = cacheDurationTicks;
        this.maxQueueSize = maxQueueSize;
        this.hiddenConfirmationsRequired = hiddenConfirmationsRequired;
        this.maxSamplesPerEntity = maxSamplesPerEntity;
        this.cameraPositionThreshold = cameraPositionThreshold;
        this.cameraRotationThreshold = cameraRotationThreshold;
    }

    public static PerformanceProfile byName(String name) {
        if (name != null) {
            String upper = name.trim().toUpperCase();
            for (PerformanceProfile p : values()) {
                if (p.name().equals(upper)) {
                    return p;
                }
            }
        }
        return BALANCED;
    }
}
