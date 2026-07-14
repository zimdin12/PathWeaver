# Modrinth copy — PathWeaver v0.1.1

## Summary (≤256 characters)

Experimental opt-in server-side A* offload for Walk/Swim mobs. Default-off in v0.1.1. Rejected dispatches stay sync; worker failures force a later request sync. No universal MSPT speedup or full safety/equivalence claim.

## Release title

PathWeaver 0.1.1 — honesty and default-off patch

## Version changelog

PathWeaver 0.1.1 is an honesty release for the existing experimental implementation.

### Changed

- Async pathfinding now defaults **off** for newly generated configs; explicit opt-in is required.
- Repath tolerance now defaults to `0`, preserving vanilla's existing short-circuit unless a user opts in.
- Invalid thread-count, in-flight, tolerance and staleness values are clamped before startup.
- `FlyNodeEvaluator` is no longer eligible for async search because vanilla flying start-node selection consumes the live mob RNG off-thread.
- The experimental allowlist is now exact vanilla `WalkNodeEvaluator` + `SwimNodeEvaluator` only. Custom subclasses, Fly and Amphibious remain synchronous.
- Documentation now describes `PathNavigationRegion` correctly: it is a read-only **view backed by live chunks**, not an immutable copy.

### Important limitations

The current worker still reads live chunk and mob state. The current general `createPath` interception can also alter query-only reachability semantics, and request lifecycle/staleness plus foreign-mixin discovery are not yet complete safety boundaries. Version 0.2 will rework those foundations with immutable inputs, navigation-only dispatch, epochs, tagged outcomes, balanced callbacks and fail-closed compatibility discovery.

Dispatch-time guards and pool rejection leave that invocation synchronous. A worker exception does **not** recompute the failed request; it marks the result failed and forces later requests for that mob synchronous during a cooldown. Neither mechanism proves concurrent reads safe or behavior vanilla-equivalent. Keep backups and enable async only if you accept an experimental alpha.

### What the benchmark actually proved

Four paired real Spark profiles in an isolated server with 160 pathfinding zombies measured substantial **Server-thread A* offload**:

- `WalkNodeEvaluator` inclusive samples: 2613 → 236 ms per run on average (-90.97%)
- `WalkNodeEvaluator` self samples: 94 → 22 ms (-76.60%)
- `PathfindingContext` inclusive: 499 → 76 ms (-84.77%)
- `PathFinder` inclusive: 787 → 0 ms

It did **not** prove an overall MSPT speedup. Average mean MSPT was 2.927 ms OFF and 3.012 ms ON, with noisy paired results. Therefore the claim is limited to measured server-thread pathfinding offload under that isolated Walk workload—never a universal TPS/MSPT gain.

Spark evidence (OFF / ON):

1. <https://spark.lucko.me/UhFS8p6c1j> / <https://spark.lucko.me/GkIZYLcI8X>
2. <https://spark.lucko.me/0Y4U6ec6R6> / <https://spark.lucko.me/Ob2Eel57E7>
3. <https://spark.lucko.me/pnBiuYyai8> / <https://spark.lucko.me/gZeudtOgj5>
4. <https://spark.lucko.me/4S3SLe2fTh> / <https://spark.lucko.me/Ln5wXmh0xU> (ON captured first)

Each profile used Spark's 4 ms Java sampler for about 122 seconds after a 30-second warm-up on the same restored world with target and block churn. Raw protobufs, logs, extracted method/self times, health reports, counters, scripts and the baseline world are retained in the release evidence bundle.

### Upgrade note

Existing `config/pathweaver.json` files are preserved. If upgrading from 0.1.0, inspect `asyncEnabled` explicitly; delete/regenerate the config or set it to `false` if you want the new conservative default.

Requires Minecraft 26.1.x, Fabric Loader, Fabric API, Cloth Config and Java 25. Lithium is supported in the audited isolated stack, but this release does not claim blanket compatibility with every modpack/version/configuration.
