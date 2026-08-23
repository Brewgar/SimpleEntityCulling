# SimpleEntityCulling

A client-side rendering optimization mod for **Minecraft Java Edition 1.20.1 + Fabric**.

**What it does:** entities that the player cannot actually see — outside the camera's view, behind walls, behind terrain, behind the player — are skipped *before their renderers execute*. They still simulate exactly as vanilla; only `EntityRenderer` work is suppressed.

> This mod changes rendering only. It never despawns, freezes, slows, or alters entities in any way.

## Supported versions

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | 0.16.14+ |
| Fabric API | 0.92.x+1.20.1 |
| Mappings | Yarn `1.20.1+build.10` |
| Java | 17+ |

Client-side only (`"environment": "client"`); safe to install on clients connecting to any server.

## How it works

A single, focused Mixin injects into `EntityRenderDispatcher.shouldRender(...)`:

- at **HEAD** only to count considered entities (statistics),
- at **RETURN** so that *only entities vanilla itself would render* are evaluated — vanilla remains the baseline authority and SimpleEntityCulling can only ever add rejections, never force renders.

Entities that pass vanilla checks flow through a layered pipeline, cheapest layer first:

```text
vanilla eligibility → special-case guard → distance gate (squared)
→ dense spatial-cell shortcut → temporal visibility cache
→ budgeted adaptive occlusion probes → RENDER / SKIP
```

### Occlusion probes without a "center ray"

Visibility is never decided from one ray. Sample points are derived from the entity's bounding box (expanded by a configurable safety margin): center first; larger/closer entities additionally test top/bottom centers and diagonal corners. Each ray is walked with an allocation-free **3D DDA voxel traversal**, recording one bit per entered voxel ("is this an opaque full cube?"). An entity is hidden only if *every* sample is blocked.

Only obviously opaque full blocks are treated as occluders. Glass, leaves, water, fences, slabs, stairs etc. never count as blockers, so partial geometry cannot cause false invisibility.

### Thread safety

The worker thread never touches the world. Block opacity data along each candidate ray is captured on the client thread into immutable snapshots; the worker performs pure bit arithmetic on them. If the worker dies for any reason the engine permanently falls back to synchronous evaluation. Any unexpected error in the decision path fails open toward vanilla rendering.

### Temporal coherence

Results are cached per entity id (primitive open-addressing map, no `Entity` references retained) with:

- camera **generations**: crossing a small movement/rotation threshold invalidates caches globally; mouse jitter does not,
- per-entity movement thresholds,
- confidence states (`UNKNOWN → VISIBLE → PROBABLY_* → HIDDEN`) where uncertainty always renders,
- hysteresis: hiding requires consecutive full-sampling confirmations (3 / 2 / 1 depending on profile).

### Dense areas

A lightweight 8×8×8 spatial histogram detects high-density cells (e.g. a 500-chicken pen). A stale dense cell gets one conservative 3×3×3 sample-grid probe using a fraction of the test budget. Only "clearly visible" and "clearly hidden" classifications are applied, and "hidden" applies solely to entities fully inside the cell inset by 1 block — anything ambiguous falls back to per-entity logic.

## Installation

1. Install the Fabric Loader for Minecraft 1.20.1.
2. Drop `fabric-api` and `simpleentityculling-1.0.0.jar` into `.minecraft/mods`.
3. Play. Defaults (BALANCED) require no setup.


## Configuration

`config/simpleentityculling.json`, or `/sec` commands in-game:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `occlusionCulling` | `true` | Enables occlusion tests |
| `asyncCulling` | `true` | Evaluate snapshots on worker thread (auto-falls back) |
| `aggressiveMode` | `false` | Alias forcing the AGGRESSIVE profile |
| `profile` | `"BALANCED"` | `SAFE`, `BALANCED` or `AGGRESSIVE` |
| `occlusionDistance` | `64.0` | Beyond this, entities render normally without tests |
| `cacheDuration` | `20` | Ticks a result is reused before refresh |
| `cameraPositionThreshold` | `0.5` | Blocks of camera movement that invalidate caches |
| `cameraRotationThreshold` | `8.0` | Degrees of rotation that invalidate caches |
| `entityMovementThreshold` | `0.25` | Entity movement that invalidates its own entry |
| `maxVisibilityTestsPerTick` | `64` | Probe budget per tick |
| `maxQueueSize` | `256` | Bounded probe queue (farthest-first eviction) |
| `minimumDenseEntityCount` | `48` | Entities per cell before density optimizations activate |
| `samplingQuality` | `2` | 1–3, controls optional extra sample points |
| `boundingBoxMargin` | `0.15` | Safety margin around entity boxes |
| `debugOverlay` | `false` | HUD statistics overlay |
| `debugVisualization` | `false` | Wireframe state visualization |

Commands: `/sec profile <safe|balanced|aggressive>`, `/sec overlay`, `/sec visualize`, `/sec reload`.

Profiles bundle internal tunables (test budgets, cache durations, thresholds, confirmation counts):

- **SAFE** – correctness first: frequent revalidation, strict sampling.
- **BALANCED** *(default)* – strong savings without visible artifacts.
- **AGGRESSIVE** – maximal reuse for extreme entity counts.

## Debug overlay

Shows considered/rendered/culled counts, cache hit rate, probe queue depth, average probe cost, worker mode, and dense-cell activity. Wireframe visualization colors entities green (visible), red (hidden), orange/yellow (probable), gray (unknown). Both default off.
## Performance considerations

The design goal is not maximum culling percentage but **net positive value**: every unit of CPU spent deciding visibility must save more rendering work than it costs. That is why the hot path is a few integer compares plus one hash lookup for cached entities, probes are strictly budgeted, far-away work is evicted first when queues fill, and everything beyond range or too close simply renders.

## Known limitations (conservative by design)

- Non-opaque occluders (glass panes, leaves, fences…) are invisible to the occlusion test.
- Very large entities partially clipped by terrain may occasionally remain rendered.
- After fast camera swings, briefly-stale hidden entities re-render until refreshed (bounded grace window).
- The mod does not cull block entities, item entities beyond vanilla rules, or particles.

## Compatibility

Uses a single RETURN-phase injection into `EntityRenderDispatcher.shouldRender` and no rendering-subsystem replacements, which keeps it compatible with most optimization mods in practice. Mods that replace the entire entity dispatch pipeline may bypass or conflict with the hook; test combinations before relying on them. Report suspected conflicts via the issue tracker.

## Building

```bash
./gradlew build        # jar in build/libs/
./gradlew runClient    # dev environment launch
```

See [BENCHMARKS.md](BENCHMARKS.md) for the benchmarking methodology and required metrics.

