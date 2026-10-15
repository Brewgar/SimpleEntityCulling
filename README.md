# SimpleEntityCulling

A client-side Fabric mod for Minecraft Java 1.20.1 that stops your game from drawing things you can't see.

If an entity is outside your view, behind a wall, behind a hill, or behind you, the mod skips rendering it. The entity still exists and still behaves exactly like vanilla; your game just doesn't waste time drawing it. Only the entity rendering is skipped. Game logic is never touched.

Think of it as your GPU finally getting permission to stop drawing the 500 chickens in that pen you aren't even looking at.

## Versions

- Minecraft 1.20.1
- Fabric Loader 0.16.14+
- Fabric API 0.92.x+1.20.1
- Yarn mappings `1.20.1+build.10`
- Java 17+
- Client-side only

## Install

1. Install Fabric Loader for Minecraft 1.20.1.
2. Drop `fabric-api` and `simpleentityculling-1.0.0.jar` into `.minecraft/mods`.
3. Play. The default profile needs no setup.

## Commands

`/sec profile <safe|balanced|aggressive>`, `/sec overlay`, `/sec visualize`, `/sec reload`

Profiles:

- **SAFE**: correctness first. Rechecks often and samples strictly.
- **BALANCED** (default): good savings without visible glitches.
- **AGGRESSIVE**: reuses results as much as possible, for extreme entity counts.

## Debug overlay

`/sec overlay` shows how many entities were considered, rendered, and culled, plus cache hit rate, probe queue depth, average probe cost, worker mode, and dense-cell activity.

`/sec visualize` draws wireframes around entities: green is visible, red is hidden, orange/yellow is probably one of those, gray is unknown. Both are off by default.

## How it stays fast

The goal isn't to cull as much as possible. It's to make sure that deciding what to hide costs less than drawing it would have. So the common path is a few integer comparisons and one hash lookup, the visibility checks have a strict budget, and anything too far away or too close just gets rendered.

## How it stays safe

- The worker thread never touches the world. Block opacity data is copied into immutable snapshots on the client thread, and the worker only does bit math on those.
- If the worker dies for any reason, the mod permanently switches to doing the checks on the main thread.
- Any unexpected error in the decision logic results in the entity being drawn normally.
- For crowded areas, a lightweight 8×8×8 histogram spots dense cells. Only "clearly visible" and "clearly hidden" results are used there. Anything unclear falls back to per-entity checks.

## Known limitations

- Glass panes, leaves, fences, and other non-opaque blocks aren't treated as occluders.
- Very large entities partly clipped by terrain may sometimes still render.
- After a fast camera swing, an entity that was hidden may take a moment to show up again.
- It doesn't cull block entities, item entities beyond vanilla rules, or particles.

## Compatibility

It uses a single injection into `EntityRenderDispatcher.shouldRender` and doesn't replace any rendering systems, so it works with most optimization mods. Mods that replace the whole entity dispatch pipeline might bypass it or conflict with it. If you find a conflict, open an issue.

## Building

```
./gradlew build        # jar in build/libs/
./gradlew runClient    # dev environment launch
```

Benchmarking details are in [BENCHMARKS.md](BENCHMARKS.md).

## License

MIT.
