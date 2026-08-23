# Benchmarking SimpleEntityCulling

No performance numbers in this document are claimed until measured with the
procedure below. Do not cite percentages that were not produced this way.

## Environment control

- Same machine, same power profile, same resolution/render distance for all runs.
- Minecraft 1.20.1, Fabric Loader, Fabric API identical across runs.
- Compare: **Vanilla** (mod absent), **SAFE**, **BALANCED**, **AGGRESSIVE**.
- Warm up each scenario for ~60 seconds before measuring; measure >= 120 seconds.
- Repeat 3+ times per configuration; report medians.

## Entity-count scenarios

Build a flat superflat test world (or use commands/structure blocks) with:

```text
50 / 100 / 250 / 500 / 1000 / 2500 / 5000 entities
```

(For counts above vanilla spawn limits use `/summon` loops from datapacks or
mods such as a dev-only spawner; document exactly how entities were placed.)

## Scenarios

| Scenario | Setup |
|---|---|
| Open field | All entities visible around the player |
| All visible | Entities in front, line of sight |
| All hidden | Identical layout behind an opaque wall |
| Behind player | Entities directly behind the camera |
| Mixed | Half visible / half hidden |
| Dense pen | 500 chickens in ~5x5 blocks (primary acceptance case) |
| Mob farm | Multi-platform dark-room farm load |
| Large cave | Entities spread through occluded cave volume |
| Camera rotation | Continuous fast yaw sweeps over mixed scene |
| Player movement | Running back and forth past the scene |
| Entity pile | Maximum stack of entities in one block column |

## Primary acceptance benchmark

500 chickens in ~5x5 blocks, player nearby, four sub-cases:
`500 visible`, `500 behind wall`, `500 behind player`, `250+250 mixed`.
Measure vanilla vs. each profile.

## Required metrics

Per run capture:

- FPS, average frame time, 1% low and 0.1% low frame times (use Spark or F3-era sampling; keep tooling identical between runs)
- rendered entity count (debug overlay: "Rendered")
- occlusion-culled count (overlay)
- visibility tests/tick, cache hit rate (overlay)
- average probe cost in microseconds (overlay)
- render-thread CPU time and worker CPU time where available (JFR/async-profiler)
- allocation rate / GC pauses if available

## Success criterion

The headline metric is **net value**:

```text
saved rendering work  >  visibility-system CPU cost
```

A configuration that culls more entities but spends more CPU than it saves
is a regression, not an improvement. Report both sides of the equation.
