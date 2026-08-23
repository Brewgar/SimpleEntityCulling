package com.example.simpleentityculling.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.example.simpleentityculling.SimpleEntityCulling;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON configuration stored at {@code config/simpleentityculling.json}.
 *
 * <p>Defaults mirror the BALANCED profile so the mod works correctly right
 * after installation with no user setup.</p>
 */
public final class SimpleEntityCullingConfig {
    // ---- master switches -------------------------------------------------
    public boolean enabled = true;
    public boolean occlusionCulling = true;
    public boolean asyncCulling = true;
    /** Convenience alias that forces the AGGRESSIVE profile when true. */
    public boolean aggressiveMode = false;

    // ---- debugging --------------------------------------------------------
    public boolean debugOverlay = false;
    public boolean debugVisualization = false;

    // ---- tuning (overwritten when a profile is applied) --------------------
    public String profile = "BALANCED";
    /** Beyond this distance no occlusion tests run; entities render normally. */
    public double occlusionDistance = 64.0;
    public int cacheDuration = PerformanceProfile.BALANCED.cacheDurationTicks;
    public double cameraPositionThreshold = PerformanceProfile.BALANCED.cameraPositionThreshold;
    public double cameraRotationThreshold = PerformanceProfile.BALANCED.cameraRotationThreshold;
    /** Entity movement (blocks) that invalidates its own cached result. */
    public double entityMovementThreshold = 0.25;
    public int maxVisibilityTestsPerTick = PerformanceProfile.BALANCED.maxTestsPerTick;
    public int maxQueueSize = PerformanceProfile.BALANCED.maxQueueSize;
    /** Entities per spatial cell before density optimizations activate. */
    public int minimumDenseEntityCount = 48;
    /** 1 = low .. 3 = high; controls optional extra sample points. */
    public int samplingQuality = 2;
    /** Safety margin expanded around entity bounding boxes before sampling. */
    public double boundingBoxMargin = 0.15;

    private transient PerformanceProfile resolved = PerformanceProfile.BALANCED;

    public PerformanceProfile profile() {
        if (aggressiveMode) {
            return PerformanceProfile.AGGRESSIVE;
        }
        if (resolved == null) {
            resolved = PerformanceProfile.byName(profile);
        }
        return resolved;
    }

    /**
     * Applies a named profile, copying its tunables into the persisted fields.
     */
    public void applyProfile(String name) {
        resolved = PerformanceProfile.byName(name);
        profile = resolved.name();
        aggressiveMode = false;
        cacheDuration = resolved.cacheDurationTicks;
        cameraPositionThreshold = resolved.cameraPositionThreshold;
        cameraRotationThreshold = resolved.cameraRotationThreshold;
        maxVisibilityTestsPerTick = resolved.maxTestsPerTick;
        maxQueueSize = resolved.maxQueueSize;
    }

    private void sanitize() {
        if (resolved == null) {
            resolved = PerformanceProfile.byName(profile);
        }
        occlusionDistance = clamp(occlusionDistance, 16.0, 256.0);
        cacheDuration = (int) clamp(cacheDuration, 1, 200);
        cameraPositionThreshold = clamp(cameraPositionThreshold, 0.05, 16.0);
        cameraRotationThreshold = clamp(cameraRotationThreshold, 0.5, 90.0);
        entityMovementThreshold = clamp(entityMovementThreshold, 0.05, 16.0);
        maxVisibilityTestsPerTick = (int) clamp(maxVisibilityTestsPerTick, 1, 4096);
        maxQueueSize = (int) clamp(maxQueueSize, 32, 8192);
        minimumDenseEntityCount = (int) clamp(minimumDenseEntityCount, 0, 4096);
        samplingQuality = (int) clamp(samplingQuality, 1, 3);
        boundingBoxMargin = clamp(boundingBoxMargin, 0.0, 2.0);
    }

    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    // ---- persistence -------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoaderHelper.configDir().resolve("simpleentityculling.json");
    }

    public static SimpleEntityCullingConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                SimpleEntityCullingConfig cfg = GSON.fromJson(r, SimpleEntityCullingConfig.class);
                if (cfg != null) {
                    cfg.sanitize();
                    return cfg;
                }
            } catch (Exception e) {
                SimpleEntityCulling.LOGGER.error("[SimpleEntityCulling] failed to read config, using defaults", e);
            }
        }
        SimpleEntityCullingConfig cfg = new SimpleEntityCullingConfig();
        cfg.applyProfile("BALANCED");
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            SimpleEntityCulling.LOGGER.error("[SimpleEntityCulling] failed to save config", e);
        }
    }

    /** Indirection so the config class does not import Fabric loader directly. */
    private static final class FabricLoaderHelper {
        static Path configDir() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        }
    }
}
