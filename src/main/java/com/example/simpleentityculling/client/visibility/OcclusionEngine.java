package com.example.simpleentityculling.client.visibility;

import com.example.simpleentityculling.SimpleEntityCulling;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs occlusion probes using immutable snapshots so no Minecraft world
 * API is ever touched off the client thread:
 *
 * <ol>
 *   <li><b>Capture</b> (client thread): for each sample point, walk the voxel
 *       grid from camera to sample with a 3D DDA and pack one bit per voxel
 *       ("is opaque full cube") into a {@code long[]}.</li>
 *   <li><b>Evaluate</b> (worker thread or inline): pure arithmetic over those
 *       bits - a ray is blocked iff any traversed voxel bit (excluding the
 *       camera's own voxel and the sample's own voxel) is set. An entity is
 *       visible if ANY of its rays reaches it unblocked.</li>
 * </ol>
 *
 * The worker never reads world state, so there is no thread-safety exposure.
 */
public final class OcclusionEngine {
    public static final int MAX_RAY_STEPS = 192;
    private static final int WORDS_PER_RAY = (MAX_RAY_STEPS + 63) >>> 6;

    /** Work item produced on the client thread. */
    public static final class Probe {
        public int entityId;
        public int generation;
        public long tick;
        /** Non-zero when this probe validates a spatial cell instead of an entity. */
        public long packedCell;
        /** False for cheap center-only confirmation probes. */
        public boolean fullSampling;
        public double distanceSq;
        public double camX, camY, camZ;
        public double maxRayLength;
        public int rayCount;
        /** Sample points as xyz triplets (owned by this probe). */
        public double[] points = new double[3];
        /** Per-ray number of voxels entered. */
        public int[] stepCounts = new int[1];
        /** Per-ray packed opacity bits. */
        public long[][] bits = new long[1][];
        public long taskId;
        long captureNanos;
    }

    /** Evaluation result pushed back to the render thread. */
    public static final class Result {
        public final int entityId;
        public final int generation;
        public final long tick;
        public final long packedCell;
        public final boolean visible;
        public final boolean fullSampling;
        public final long nanos;

        Result(Probe p, boolean visible, long evalNanos) {
            this.entityId = p.entityId;
            this.generation = p.generation;
            this.tick = p.tick;
            this.packedCell = p.packedCell;
            this.visible = visible;
            this.fullSampling = p.fullSampling;
            this.nanos = p.captureNanos + evalNanos;
        }
    }

    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private Probe[] ring = new Probe[64];
    private int head, count;
    private Thread worker;
    private volatile boolean stopWorker;
    private volatile boolean useAsync;
    private volatile boolean workerFailed;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private long nextTaskId = 1L;

    /** Allocates a fresh probe instance. Client-thread only. */
    public Probe obtain(int entityId, int generation, long tick) {
        Probe p = new Probe();
        p.entityId = entityId;
        p.generation = generation;
        p.tick = tick;
        return p;
    }

    /**
     * Captures ray data on the calling thread (must be the client/render
     * thread) and either hands the snapshot to the worker or evaluates it
     * inline. Returns the task id, or 0 if rejected (queue full).
     */
    public long submit(Probe p, ClientWorld world) {
        p.taskId = nextTaskId++;
        long t0 = System.nanoTime();
        captureAll(world, p);
        p.captureNanos = System.nanoTime() - t0;

        if (useAsync) {
            Thread w = worker;
            if (w != null && w.isAlive()) {
                synchronized (lock) {
                    if (count >= ring.length) {
                        // Queue full: evict the farthest queued probe, or drop
                        // the incoming one if everything queued is closer.
                        long id = dropOrEvictLocked(p);
                        lock.notifyAll();
                        return id;
                    }
                    ring[(head + count) % ring.length] = p;
                    count++;
                    lock.notifyAll();
                }
                return p.taskId;
            }
        }
        evaluateInline(p);
        return p.taskId;
    }

    /** Must hold {@link #lock}. Evicts farthest probe or rejects incoming. */
    private long dropOrEvictLocked(Probe p) {
        int farthestIdx = -1;
        double farthestD2 = -1.0;
        for (int i = 0; i < count; i++) {
            Probe q = ring[(head + i) % ring.length];
            if (q.distanceSq > farthestD2) {
                farthestD2 = q.distanceSq;
                farthestIdx = i;
            }
        }
        if (farthestIdx >= 0 && farthestD2 <= p.distanceSq) {
            return 0L; // everything queued is closer: drop new probe
        }
        int slot = (head + farthestIdx) % ring.length;
        ring[slot].taskId = 0L; // orphaned; owning entry will retry
        ring[slot] = p;
        return p.taskId;
    }

    private void evaluateInline(Probe p) {
        long t0 = System.nanoTime();
        boolean visible = evaluate(p);
        results.add(new Result(p, visible, System.nanoTime() - t0));
    }

    /** @return the number of currently queued probes. */
    public int queueSize() {
        synchronized (lock) {
            return count;
        }
    }

    public int resultBacklog() {
        return results.size();
    }

    public Result pollResult() {
        return results.poll();
    }

    public boolean isAsync() {
        return useAsync;
    }

    /** Applies current settings; starts/stops the worker as needed. */
    public void configure(boolean async, int maxQueueSize) {
        boolean wantAsync = async && !workerFailed;
        int cap = Math.max(64, Math.min(maxQueueSize, 4096));
        synchronized (lock) {
            if (ring.length != cap) {
                ring = new Probe[cap];
                head = 0;
                count = 0;
            }
        }
        useAsync = false;
        stopWorkerThread();
        if (wantAsync) {
            startWorkerThread();
            useAsync = true;
        }
    }

    public void shutdown() {
        stopWorkerThread();
        useAsync = false;
        results.clear();
    }

    private void startWorkerThread() {
        stopWorker = false;
        worker = new Thread(this::workerLoop, "SimpleEntityCulling-Worker");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private void stopWorkerThread() {
        Thread w = worker;
        worker = null;
        if (w == null) {
            return;
        }
        stopWorker = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            w.join(250);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerLoop() {
        try {
            while (!stopWorker) {
                Probe p;
                synchronized (lock) {
                    while (count == 0 && !stopWorker) {
                        lock.wait(50);
                    }
                    if (stopWorker) {
                        return;
                    }
                    p = ring[head];
                    ring[head] = null;
                    head = (head + 1) % ring.length;
                    count--;
                }
                if (p.taskId == 0L) {
                    continue; // evicted before dispatch
                }
                long t0 = System.nanoTime();
                boolean visible = evaluate(p);
                results.add(new Result(p, visible, System.nanoTime() - t0));
            }
        } catch (Throwable t) {
            // Fail-safe: permanently fall back to synchronous evaluation.
            workerFailed = true;
            useAsync = false;
            if (failureLogged.compareAndSet(false, true)) {
                SimpleEntityCulling.LOGGER.error(
                        "[SimpleEntityCulling] visibility worker failed; falling back to synchronous evaluation", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Capture (client thread only - the only place the world is read)
    // ------------------------------------------------------------------

    private void captureAll(ClientWorld world, Probe p) {
        int n = p.rayCount;
        if (p.stepCounts.length < n) {
            p.stepCounts = new int[n];
            p.bits = new long[n][];
        }
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int r = 0; r < n; r++) {
            long[] out = p.bits[r];
            if (out == null || out.length != WORDS_PER_RAY) {
                out = new long[WORDS_PER_RAY];
                p.bits[r] = out;
            }
            p.stepCounts[r] = captureRay(world,
                    p.camX, p.camY, p.camZ,
                    p.points[r * 3], p.points[r * 3 + 1], p.points[r * 3 + 2],
                    p.maxRayLength, out, mut);
        }
    }

    /**
     * Amanatides &amp; Woo voxel traversal from {@code (sx,sy,sz)} toward
     * {@code (ex,ey,ez)}. Records one bit per entered voxel; the starting
     * (camera) voxel is intentionally not recorded.
     *
     * @return number of voxels entered (capped at {@link #MAX_RAY_STEPS});
     *         traversal also stops early once {@code maxRayLength} is
     *         reached, which simply yields fewer recorded voxels and thus a
     *         conservative "visible" evaluation
     */
    static int captureRay(ClientWorld world, double sx, double sy, double sz,
                          double ex, double ey, double ez, double maxRayLength,
                          long[] bits, BlockPos.Mutable pos) {
        Arrays.fill(bits, 0L);

        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        double segLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (segLen < 1.0e-4) {
            return 0;
        }
        double maxT = segLen > maxRayLength ? maxRayLength / segLen : 1.0;

        int cx = MathHelper.floor(sx), cy = MathHelper.floor(sy), cz = MathHelper.floor(sz);
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
        double tMaxX = stepX != 0 ? (stepX > 0 ? cx + 1 - sx : sx - cx) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY != 0 ? (stepY > 0 ? cy + 1 - sy : sy - cy) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ != 0 ? (stepZ > 0 ? cz + 1 - sz : sz - cz) * tDeltaZ : Double.POSITIVE_INFINITY;

        pos.set(cx, cy, cz); // start voxel not recorded

        int steps = 0;
        while (steps < MAX_RAY_STEPS) {
            double tCross;
            if (tMaxX <= tMaxY) {
                if (tMaxX <= tMaxZ) {
                    tCross = tMaxX;
                    if (tCross >= maxT) break;
                    cx += stepX;
                    tMaxX += tDeltaX;
                    pos.set(cx, cy, cz);
                } else {
                    tCross = tMaxZ;
                    if (tCross >= maxT) break;
                    cz += stepZ;
                    tMaxZ += tDeltaZ;
                    pos.set(cx, cy, cz);
                }
            } else {
                if (tMaxY <= tMaxZ) {
                    tCross = tMaxY;
                    if (tCross >= maxT) break;
                    cy += stepY;
                    tMaxY += tDeltaY;
                    pos.set(cx, cy, cz);
                } else {
                    tCross = tMaxZ;
                    if (tCross >= maxT) break;
                    cz += stepZ;
                    tMaxZ += tDeltaZ;
                    pos.set(cx, cy, cz);
                }
            }
            BlockState state = world.getBlockState(pos);
            if (state.isOpaqueFullCube(world, pos)) {
                bits[steps >> 6] |= 1L << (steps & 63);
            }
            steps++;
        }
        return steps;
    }

    // ------------------------------------------------------------------
    // Evaluation (pure arithmetic over captured bits; thread-agnostic)
    // ------------------------------------------------------------------

    /**
     * A ray is "clear" if none of its traversed voxels is opaque; the final
     * voxel (containing the sample point itself) is excluded. The entity is
     * considered VISIBLE as soon as one ray is clear.
     */
    public static boolean evaluate(Probe p) {
        for (int r = 0; r < p.rayCount; r++) {
            int usable = p.stepCounts[r] - 1; // exclude sample's own voxel
            if (usable <= 0) {
                return true; // ray too short to prove occlusion -> conservative
            }
            long[] b = p.bits[r];
            boolean blocked = false;
            for (int w = 0; w * 64 < usable; w++) {
                long word = b[w];
                if (word == 0L) {
                    continue;
                }
                int remaining = usable - w * 64;
                long mask = remaining >= 64 ? -1L : ((1L << remaining) - 1L);
                if ((word & mask) != 0L) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return true;
            }
        }
        return false;
    }


}
