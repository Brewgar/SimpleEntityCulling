package com.example.simpleentityculling.client.visibility;

import com.example.simpleentityculling.client.config.PerformanceProfile;
import com.example.simpleentityculling.client.config.SimpleEntityCullingConfig;
import com.example.simpleentityculling.client.spatial.SpatialCell;
import com.example.simpleentityculling.client.spatial.SpatialIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;

import java.util.Iterator;
import java.util.Map;

/**
 * Central visibility decision system.
 *
 * <p>Layered pipeline per entity, cheapest first:</p>
 * <ol>
 *   <li>vanilla eligibility (the mixin only runs for entities vanilla renders)</li>
 *   <li>special-case guard (glowing, ridden, crosshair target, ...)</li>
 *   <li>squared-distance gates</li>
 *   <li>dense spatial-cell shortcut</li>
 *   <li>temporal visibility cache (camera generations + hysteresis)</li>
 *   <li>budgeted adaptive occlusion probes</li>
 * </ol>
 *
 * <p><b>Uncertainty always renders.</b> Only a confirmed HIDDEN state with
 * fresh camera/entity/world conditions suppresses rendering, and any internal
 * failure fails open toward vanilla behavior.</p>
 */
public final class VisibilityManager {
    private static final double CLOSE_RANGE_BLOCKS = 3.0;
    private static final double CLOSE_RANGE_SQ = CLOSE_RANGE_BLOCKS * CLOSE_RANGE_BLOCKS;
    /** Cell classifications are refreshed with this fraction of the test budget. */
    private static final double CELL_BUDGET_FRACTION = 0.25;
    private static final int CELL_SAMPLE_GRID = 3; // 3x3x3 = 27 points
    private static final int SWEEP_INTERVAL_TICKS = 200;
    private static final long CACHE_SWEEP_MAX_AGE = 600;

    private static VisibilityManager instance;

    private volatile SimpleEntityCullingConfig config;
    private final MinecraftClient client;
    private final OcclusionEngine engine = new OcclusionEngine();
    public final VisibilityStats stats = new VisibilityStats();

    private final SpatialIndex spatial = new SpatialIndex();
    private VisibilityCache cache = new VisibilityCache(2048);

    // camera state / generation tracking
    private boolean cameraKnown;
    private double camX, camY, camZ;
    private float camYaw, camPitch;
    private int generation;

    private long lastSweepTick;

    private VisibilityManager(SimpleEntityCullingConfig config, MinecraftClient client) {
        this.config = config;
        this.client = client;
        engine.configure(config.asyncCulling && config.enabled && config.occlusionCulling,
                config.maxQueueSize);
    }

    public static void init(SimpleEntityCullingConfig config, MinecraftClient client) {
        instance = new VisibilityManager(config, client);
    }

    public static VisibilityManager get() {
        return instance;
    }

    /** Replaces the configuration object and reapplies worker settings. */
    public void reload(SimpleEntityCullingConfig newConfig) {
        this.config = newConfig;
        cache.clear();
        spatial.clear();
        generation = 0;
        cameraKnown = false;
        engine.configure(newConfig.asyncCulling && newConfig.enabled && newConfig.occlusionCulling,
                newConfig.maxQueueSize);
    }

    public OcclusionEngine engine() {
        return engine;
    }

    public SimpleEntityCullingConfig config() {
        return config;
    }

    /**
     * Debug hook: current cached visibility state byte for an entity, or -1
     * when it has no fresh entry. Used only by the visualization renderer.
     */
    public byte debugStateFor(int entityId, Entity entity) {
        VisibilityCache.Entry e = cache.get(entityId);
        if (e == null) {
            return -1;
        }
        ClientWorld world = client.world;
        if (world == null || world.getTime() - e.lastCheckTick > config.cacheDuration + 2) {
            return VisibilityCache.STATE_UNKNOWN;
        }
        return e.state;
    }

    // ------------------------------------------------------------------
    // Frame / tick lifecycle
    // ------------------------------------------------------------------

    /**
     * Called once per frame before world rendering. Advances the coarse
     * camera "generation": small movements/rotations keep the same
     * generation so caches stay valid; crossing angular or positional
     * thresholds invalidates them globally.
     */
    public void beginFrame(Camera camera) {
        double x = camera.getPos().x;
        double y = camera.getPos().y;
        double z = camera.getPos().z;
        float yaw = camera.getYaw();
        float pitch = camera.getPitch();

        if (!cameraKnown) {
            camX = x;
            camY = y;
            camZ = z;
            camYaw = yaw;
            camPitch = pitch;
            cameraKnown = true;
            return;
        }

        PerformanceProfile p = config.profile();
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        double rotDeg = Math.abs(yaw - camYaw) + Math.abs(pitch - camPitch);

        if (distSq > p.cameraPositionThreshold * p.cameraPositionThreshold
                || rotDeg > p.cameraRotationThreshold) {
            generation++;
        }
        camX = x;
        camY = y;
        camZ = z;
        camYaw = yaw;
        camPitch = pitch;
    }

    /** Called at the start of every client tick. */
    public void startTick() {
        stats.startTick();
    }

    /** Called at the end of every client tick on the client thread. */
    public void endTick(ClientWorld world) {
        if (world == null) {
            return;
        }
        drainResults(world);

        if (config.enabled) {
            updateSpatial(world);
            maintenance(world);
        }
        stats.endTick();
    }

    /** Full reset: dimension change, disconnect, or world reload. */
    public void reset() {
        cache.clear();
        spatial.clear();
        generation = 0;
        cameraKnown = false;
        stats.startTick();
    }

    // ------------------------------------------------------------------
    // Central render decision (render-thread hot path)
    // ------------------------------------------------------------------

    /**
     * @return true when the entity should be rendered. Only called for
     *         entities that already passed every vanilla check inside
     *         {@code EntityRenderDispatcher.shouldRender}.
     */
    public boolean shouldRender(Entity entity) {
        stats.candidates++;
        try {
            SimpleEntityCullingConfig cfg = this.config;
            if (!cfg.enabled) {
                stats.rendered++;
                return true;
            }

            ClientWorld world = client.world;
            if (world == null) {
                return true;
            }

            // ---- special cases: fail open toward vanilla ----------------
            Entity focus = client.gameRenderer != null
                    && client.gameRenderer.getCamera() != null
                    ? client.gameRenderer.getCamera().getFocusedEntity()
                    : null;
            if (focus == null || entity == focus
                    || entity.isGlowing()
                    || entity.hasPassenger(focus)
                    || (client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == entity)) {
                stats.forcedRendered++;
                stats.rendered++;
                return true;
            }

            double ex = entity.getX(), ey = entity.getY(), ez = entity.getZ();
            double dx = ex - camX, dy = ey - camY, dz = ez - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            // ---- distance gates -----------------------------------------
            if (distSq < CLOSE_RANGE_SQ) {
                stats.closeSkipped++;
                stats.rendered++;
                return true; // overlapping camera: never occlude
            }
            if (distSq > cfg.occlusionDistance * cfg.occlusionDistance) {
                stats.rangeSkipped++; // renders normally, no tests spent
                stats.rendered++;
                return true;
            }

            long tick = world.getTime();
            PerformanceProfile profile = cfg.profile();

            VisibilityCache.Entry entry = cache.get(entity.getId());
            if (entry == null) {
                entry = cache.create(entity.getId());
                stats.newEntries++;
                entry.generation = generation;
                entry.lastCheckTick = tick;
            }

            boolean moved = hasMoved(entity, entry, cfg.entityMovementThreshold);
            entry.lastEntX = ex;
            entry.lastEntY = ey;
            entry.lastEntZ = ez;
            entry.hasLastEnt = true;

            return decideWithCache(world, entity, entry, distSq, tick, profile, moved);
        } catch (Throwable t) {
            RenderFailureHandler.onError(t);
            return true; // Rule: optimization must never break rendering.
        }
    }

    private boolean decideWithCache(ClientWorld world, Entity entity, VisibilityCache.Entry entry,
                                    double distSq, long tick, PerformanceProfile profile, boolean moved) {
        SimpleEntityCullingConfig cfg = this.config;

        // ---- dense spatial-cell shortcut -----------------------------
        if (cfg.occlusionCulling && cfg.minimumDenseEntityCount > 0 && !moved) {
            SpatialCell cell = spatial.lookup(entity.getX(), entity.getY(), entity.getZ());
            if (cell != null && cell.dense
                    && cell.generation == generation
                    && cell.visibilityState != SpatialCell.AMBIGUOUS
                    && tick - cell.lastEvalTick <= cfg.cacheDuration) {
                if (cell.visibilityState == SpatialCell.CLEARLY_VISIBLE) {
                    entry.state = VisibilityCache.STATE_VISIBLE;
                    entry.hiddenStreak = 0;
                    entry.generation = generation;
                    entry.lastCheckTick = tick;
                    stats.rendered++;
                    return true;
                }
                if (boxInsideInsetCell(entity.getBoundingBox(), cell, 1.0)) {
                    // Conservative: only interior entities of a cell whose
                    // entire sampled volume is blocked may be suppressed.
                    entry.state = VisibilityCache.STATE_HIDDEN;
                    entry.hiddenStreak = (byte) profile.hiddenConfirmationsRequired;
                    entry.pendingTaskId = 0L;
                    entry.generation = generation;
                    entry.lastCheckTick = tick;
                    stats.cellCulled++;
                    stats.occlusionCulled++;
                    return false;
                }
                // near a cell border -> fall through to per-entity logic
            }
        }

        // ---- temporal cache ------------------------------------------
        boolean stale = moved
                || entry.generation != generation
                || tick - entry.lastCheckTick > cfg.cacheDuration;

        if (!stale) {
            if (entry.state == VisibilityCache.STATE_HIDDEN) {
                stats.occlusionCulled++;
                return false;
            }
            // VISIBLE / PROBABLY_* / UNKNOWN all render.
            stats.cacheHits++;
            stats.rendered++;
            return true;
        }

        // Stale HIDDEN: keep suppressing for a bounded grace window while a
        // refresh is in flight or the budget is exhausted; otherwise fail
        // open and re-probe with full sampling.
        if (entry.state == VisibilityCache.STATE_HIDDEN) {
            long overdue = tick - entry.lastCheckTick - cfg.cacheDuration;
            boolean budgetStarved = stats.testsRequested >= profile.maxTestsPerTick;
            if (overdue <= cfg.cacheDuration
                    && (entry.pendingTaskId != 0L || budgetStarved)) {
                tryScheduleProbe(world, entity, entry, distSq, true, profile, tick);
                stats.graceHidden++;
                stats.occlusionCulled++;
                return false;
            }
            entry.state = VisibilityCache.STATE_PROBABLY_HIDDEN;
        } else if (entry.state == VisibilityCache.STATE_VISIBLE) {
            // Cheap center-only revalidation for entities known visible.
            tryScheduleProbe(world, entity, entry, distSq, false, profile, tick);
            stats.cacheHits++;
            stats.rendered++;
            return true;
        } else {
            tryScheduleProbe(world, entity, entry, distSq, true, profile, tick);
        }

        // Uncertain -> render.
        stats.rendered++;
        return true;
    }

    private boolean hasMoved(Entity entity, VisibilityCache.Entry entry, double threshold) {
        if (!entry.hasLastEnt) {
            return true;
        }
        double mdx = entity.getX() - entry.lastEntX;
        double mdy = entity.getY() - entry.lastEntY;
        double mdz = entity.getZ() - entry.lastEntZ;
        double t2 = threshold * threshold;
        return mdx * mdx + mdy * mdy + mdz * mdz > t2;
    }

    /** True when {@code box} lies fully within the cell shrunk by margin. */
    private static boolean boxInsideInsetCell(Box box, SpatialCell cell, double margin) {
        return box.minX >= cell.minX + margin && box.maxX <= cell.maxX - margin
                && box.minY >= cell.minY + margin && box.maxY <= cell.maxY - margin
                && box.minZ >= cell.minZ + margin && box.maxZ <= cell.maxZ - margin;
    }

    // ------------------------------------------------------------------
    // Probe scheduling (adaptive sampling + budgeting)
    // ------------------------------------------------------------------

    private static final int MAX_SAMPLES = 9;
    private final double[] sampleScratch = new double[3 * MAX_SAMPLES];
    private final double[] cellSampleScratch = new double[3 * CELL_SAMPLE_GRID * CELL_SAMPLE_GRID * CELL_SAMPLE_GRID];

    private void tryScheduleProbe(ClientWorld world, Entity entity, VisibilityCache.Entry entry,
                                  double distSq, boolean full, PerformanceProfile profile, long tick) {
        if (!config.occlusionCulling) {
            return;
        }
        if (entry.pendingTaskId != 0L || stats.testsRequested >= profile.maxTestsPerTick) {
            return; // dedupe / budget
        }

        OcclusionEngine.Probe probe = engine.obtain(entity.getId(), generation, tick);
        probe.fullSampling = full;
        probe.distanceSq = distSq;
        probe.camX = camX;
        probe.camY = camY;
        probe.camZ = camZ;
        probe.maxRayLength = config.occlusionDistance;

        Box box = entity.getBoundingBox().expand(config.boundingBoxMargin);
        int n = computeSamplePoints(box, full, profile);
        probe.points = new double[3 * n];
        System.arraycopy(sampleScratch, 0, probe.points, 0, 3 * n);
        probe.rayCount = n;

        long taskId = engine.submit(probe, world);
        if (taskId != 0L) {
            entry.pendingTaskId = taskId;
            entry.fullPending = full;
            stats.testsRequested++;
        } else {
            stats.testsDropped++;
        }
    }

    /**
     * Adaptive sampling: tiny/distant entities get one center ray; larger or
     * closer entities justify top/bottom and diagonal corner samples.
     * Points live in {@link #sampleScratch}; returns the count.
     */
    private int computeSamplePoints(Box box, boolean full, PerformanceProfile profile) {
        double cx = (box.minX + box.maxX) * 0.5;
        double cy = (box.minY + box.maxY) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        sampleScratch[0] = cx;
        sampleScratch[1] = cy;
        sampleScratch[2] = cz;
        if (!full) {
            return 1;
        }

        int maxSamples = Math.min(profile.maxSamplesPerEntity, MAX_SAMPLES);
        int maxQualSamples = switch (Math.max(1, Math.min(3, config.samplingQuality))) {
            case 1 -> Math.min(3, maxSamples);
            case 2 -> Math.min(5, maxSamples);
            default -> maxSamples;
        };

        double sizeX = box.maxX - box.minX;
        double sizeY = box.maxY - box.minY;
        double sizeZ = box.maxZ - box.minZ;
        boolean large = Math.max(sizeX, Math.max(sizeY, sizeZ)) >= 2.0;

        int n = 1;
        if ((large || sizeY >= 1.2) && n < maxSamples) {
            sampleScratch[n * 3] = cx;
            sampleScratch[n * 3 + 1] = box.maxY;
            sampleScratch[n * 3 + 2] = cz;
            n++;
            sampleScratch[n * 3] = cx;
            sampleScratch[n * 3 + 1] = box.minY;
            sampleScratch[n * 3 + 2] = cz;
            n++;
        }
        if (n < maxQualSamples) {
            sampleScratch[n * 3] = box.minX;
            sampleScratch[n * 3 + 1] = box.maxY;
            sampleScratch[n * 3 + 2] = box.minZ;
            n++;
            sampleScratch[n * 3] = box.maxX;
            sampleScratch[n * 3 + 1] = box.minY;
            sampleScratch[n * 3 + 2] = box.maxZ;
            n++;
        }
        if (n < maxQualSamples && large) {
            sampleScratch[n * 3] = box.maxX;
            sampleScratch[n * 3 + 1] = box.maxY;
            sampleScratch[n * 3 + 2] = box.minZ;
            n++;
            sampleScratch[n * 3] = box.minX;
            sampleScratch[n * 3 + 1] = box.minY;
            sampleScratch[n * 3 + 2] = box.maxZ;
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Result consumption (client thread, once per tick)
    // ------------------------------------------------------------------

    private void drainResults(ClientWorld world) {
        long tick = world.getTime();
        PerformanceProfile profile = config.profile();
        OcclusionEngine.Result r;
        while ((r = engine.pollResult()) != null) {
            stats.testNanosThisTick += r.nanos;

            if (r.packedCell != 0L) {
                applyCellResult(r);
                continue;
            }

            VisibilityCache.Entry entry = cache.get(r.entityId);
            if (entry == null) {
                continue;
            }
            entry.pendingTaskId = 0L;
            if (r.generation != generation || r.tick != tick && tick - r.tick > 1) {
                // Result belongs to a previous camera state or is very old;
                // the owning entry will simply be re-probed.
                continue;
            }
            entry.generation = r.generation;
            entry.lastCheckTick = tick;

            if (r.visible) {
                entry.state = VisibilityCache.STATE_VISIBLE;
                entry.hiddenStreak = 0;
            } else if (!r.fullSampling) {
                // Center-only probe blocked for a previously visible entity:
                // never hide on this alone; a full sampling will follow.
                entry.state = VisibilityCache.STATE_PROBABLY_HIDDEN;
            } else {
                int streak = Math.min(Byte.MAX_VALUE, entry.hiddenStreak + 1);
                entry.hiddenStreak = (byte) streak;
                int needed = profile.hiddenConfirmationsRequired;
                entry.state = streak >= needed
                        ? VisibilityCache.STATE_HIDDEN
                        : VisibilityCache.STATE_PROBABLY_HIDDEN;
            }
        }
    }

    private void applyCellResult(OcclusionEngine.Result r) {
        SpatialCell cell = spatial.byPackedKey(r.packedCell);
        if (cell == null) {
            return;
        }
        cell.probePending = false;
        if (r.generation != generation) {
            return; // stale classification attempt; will be retried
        }
        cell.visibilityState = r.visible
                ? SpatialCell.CLEARLY_VISIBLE
                : (r.fullSampling ? SpatialCell.CLEARLY_HIDDEN : SpatialCell.AMBIGUOUS);
        cell.generation = generation;
        cell.lastEvalTick = r.tick;
    }

    // ------------------------------------------------------------------
    // Spatial update + maintenance (client thread, once per tick)
    // ------------------------------------------------------------------

    private void updateSpatial(ClientWorld world) {
        if (!config.occlusionCulling || config.minimumDenseEntityCount <= 0) {
            return;
        }
        long tick = world.getTime();
        spatial.beginUpdate();
        for (Entity e : world.getEntities()) {
            spatial.visitEntity(e.getX(), e.getY(), e.getZ(), tick);
        }
        spatial.endUpdate(tick, config.minimumDenseEntityCount);
        scheduleCellProbes(world, tick);
        stats.denseCells = countDenseCells();
        stats.cacheEntries = cache.size();
    }

    private int countDenseCells() {
        int n = 0;
        for (Map.Entry<Long, SpatialCell> e : spatial.entries()) {
            if (e.getValue().dense) {
                n++;
            }
        }
        return n;
    }

    /**
     * One conservative 3x3x3 sample-grid probe per stale dense cell, using a
     * quarter of the per-tick test budget. Cells are evaluated at most once
     * per camera generation / cache duration.
     */
    private void scheduleCellProbes(ClientWorld world, long tick) {
        PerformanceProfile profile = config.profile();
        int cellBudget = Math.max(1, (int) (profile.maxTestsPerTick * CELL_BUDGET_FRACTION));
        int cellTests = 0;
        final double fracLow = 0.2, fracMid = 0.5, fracHigh = 0.8;

        for (Map.Entry<Long, SpatialCell> e : spatial.entries()) {
            SpatialCell cell = e.getValue();
            if (!cell.dense || cell.probePending) {
                continue;
            }
            boolean fresh = cell.generation == generation
                    && cell.lastEvalTick != Long.MIN_VALUE
                    && tick - cell.lastEvalTick <= config.cacheDuration;
            if (fresh) {
                continue;
            }
            if (cellTests >= cellBudget || stats.testsRequested >= profile.maxTestsPerTick) {
                break;
            }

            OcclusionEngine.Probe probe = engine.obtain(0, generation, tick);
            probe.packedCell = e.getKey();
            probe.fullSampling = true;
            probe.camX = camX;
            probe.camY = camY;
            probe.camZ = camZ;
            probe.maxRayLength = config.occlusionDistance;
            double cxm = cell.minX, cym = cell.minY, czm = cell.minZ;
            double sx = (cell.maxX - cxm), sy = (cell.maxY - cym), sz = (cell.maxZ - czm);
            int idx = 0;
            for (int ix = 0; ix < CELL_SAMPLE_GRID; ix++) {
                double fx = ix == 0 ? fracLow : ix == 1 ? fracMid : fracHigh;
                for (int iy = 0; iy < CELL_SAMPLE_GRID; iy++) {
                    double fy = iy == 0 ? fracLow : iy == 1 ? fracMid : fracHigh;
                    for (int iz = 0; iz < CELL_SAMPLE_GRID; iz++) {
                        double fz = iz == 0 ? fracLow : iz == 1 ? fracMid : fracHigh;
                        cellSampleScratch[idx++] = cxm + fx * sx;
                        cellSampleScratch[idx++] = cym + fy * sy;
                        cellSampleScratch[idx++] = czm + fz * sz;
                    }
                }
            }
            probe.points = new double[idx];
            System.arraycopy(cellSampleScratch, 0, probe.points, 0, idx);
            probe.rayCount = CELL_SAMPLE_GRID * CELL_SAMPLE_GRID * CELL_SAMPLE_GRID;

            long taskId = engine.submit(probe, world);
            if (taskId != 0L) {
                cell.probePending = true;
                cellTests++;
                stats.testsRequested++;
            } else {
                stats.testsDropped++;
                break;
            }
        }
    }

    /**
     * Entries store no {@code Entity} references so they cannot leak entities;
     * they are only pruned when the table grows past a sane bound.
     */
    private void maintenance(ClientWorld world) {
        long tick = world.getTime();
        if (cache.size() > MAX_CACHE_ENTRIES) {
            // Amortized O(1): drop everything; next frame re-seeds lazily and
            // unknown states render until fresh probes complete (fail-open).
            cache.clear();
        }
    }

    private static final int MAX_CACHE_ENTRIES = 16384;

    /** Static bridge used by the render hook; keeps the mixin tiny. */
    private static final class RenderFailureHandler {
        private static int errorsLogged;

        static void onError(Throwable t) {
            if (errorsLogged++ < 5) {
                com.example.simpleentityculling.SimpleEntityCulling.LOGGER.error(
                        "[SimpleEntityCulling] visibility decision failed; rendering normally", t);
            }
        }
    }





}
