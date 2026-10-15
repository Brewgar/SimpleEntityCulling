# SimpleEntityCulling

A client-side rendering optimization mod for **Minecraft Java Edition 1.20.1 + Fabric**.

**What it does:** entities that the player cannot actually see — outside the camera's view, behind walls, behind terrain, behind the player — are skipped *before their renderers execute*. They still simulate exactly as vanilla; only `EntityRenderer` work is suppressed.

> This mod changes rendering only. It never alters game logic in any way.

## Supported versions

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | 0.16.14+ |
| Fabric API | 0.92.x+1.20.1 |
| Mappings | Yarn `1.20.1+build.10` |
| Java | 17+ |

Client-side only

### Thread safety

The worker thread never touches the world. Block opacity data along each candidate ray is captured on the client thread into immutable snapshots; the worker performs pure bit arithmetic on them. If the worker dies for any reason the engine permanently falls back to synchronous evaluation. Any unexpected error in the decision path fails open toward vanilla rendering.

### Dense areas

A lightweight 8×8×8 spatial histogram detects high-density cells (e.g. a 500-chicken pen). A stale dense cell gets one conservative 3×3×3 sample-grid probe using a fraction of the test budget. Only "clearly visible" and "clearly hidden" classifications are applied, and "hidden" applies solely to entities fully inside the cell inset by 1 block — anything ambiguous falls back to per-entity logic.

## Installation

1. Install the Fabric Loader for Minecraft 1.20.1.
2. Drop `fabric-api` and `simpleentityculling-1.0.0.jar` into `.minecraft/mods`.
3. Play. Defaults (BALANCED) require no setup.

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

