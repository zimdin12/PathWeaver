# Modrinth copy — PathWeaver v0.1.2

## Summary (≤256 characters)

Experimental server-side Walk/Swim A* offload, now on by default with navigation-only routing and fail-closed compatibility checks. Disable any time. Alpha: keep backups; no proven safety, path equivalence, or universal MSPT gain.

## Release title

PathWeaver 0.1.2 — default-on alpha with navigation-only routing and fail-closed compatibility

## Version changelog

PathWeaver 0.1.2 makes asynchronous pathfinding **on by default for newly generated configs**. This changes the alpha from opt-in to opt-out; it does **not** claim that the current live-input worker is proven thread-safe, vanilla-equivalent, or faster overall.

### What changed

- `asyncEnabled` now defaults to `true` for new configs.
- Direct and query-only `createPath` calls remain synchronous. Async dispatch is armed only by genuine movement/recompute operations, fixing the worst v0.1.1 reachability/force-move contract bug.
- Compatibility discovery now fails closed over Fabric metadata, Loader-resolved JiJ mods, prepared Mixin configs, plugin-expanded mixins, evaluator/base/context/navigation/finder targets, and exact owner/config/mixin/target identity.
- Broad Fabric, Diagonal, and Lithium trust rules are gone. v0.1.2 grants no compatibility exemptions.
- Exact vanilla Walk and Swim evaluators remain the only async candidates. Fly, Amphibious, subclasses, and scanner-denied families stay synchronous.
- Repath tolerance remains `0` by default.

### Disable async

Set either option in `config/pathweaver.json`:

```json
"asyncEnabled": false
```

or use the panic switch:

```json
"syncFallbackOnly": true
```

Existing config files are preserved. If a v0.1.1 config already says `asyncEnabled=false`, it stays off until you edit or regenerate it.

### Important compatibility result

The standard Fabric content-registry module mixes into `PathfindingContext` and `WalkNodeEvaluator` to expose dynamic path-type providers. Those callbacks are not proven worker-safe. The v0.1.2 scanner therefore forces Walk and Swim synchronous when that module is present—even though async defaults on.

That is intentional fail-closed behavior: the default enables async attempts, but compatibility evidence can still reduce coverage to zero rather than guess. A clean scan is not proof that an arbitrary pack is safe.

### Important remaining limitations

The worker still consumes a read-only **view backed by live chunks** and live mob inputs; it is not an immutable snapshot. Request epochs, complete staleness identity, evaluator-specific callback balance, and tagged `SUCCESS`/`NO_PATH`/`FAILED` outcomes remain in development.

Dispatch-time saturation or rejection leaves that same invocation synchronous. A worker exception does **not** recompute the failed request; it discards that result and forces later requests for the mob synchronous during a cooldown.

A private in-mod snapshot evaluator/A* engine is approved in principle, but implementation is gated on the remaining correctness slices and a real near-tick-budget load benchmark. Until exhaustive Walk/Swim equivalence tests pass, paths may differ from vanilla.

**This is an alpha: expect bugs, keep backups, and disable async if you prefer vanilla behavior.**

### What the benchmark actually proved

Four paired real Spark profiles in an isolated server with 160 pathfinding zombies measured substantial **Server-thread A* offload**:

- `WalkNodeEvaluator` inclusive samples: 2613 → 236 ms per run on average (-90.97%)
- `WalkNodeEvaluator` self samples: 94 → 22 ms (-76.60%)
- `PathfindingContext` inclusive: 499 → 76 ms (-84.77%)
- `PathFinder` inclusive: 787 → 0 ms

It did **not** prove an overall MSPT speedup. Average mean MSPT was 2.927 ms OFF and 3.012 ms ON, with noisy paired results. The supported claim is measured server-thread pathfinding offload under that isolated Walk workload—not a universal TPS/MSPT gain.

Spark evidence (OFF / ON):

1. <https://spark.lucko.me/UhFS8p6c1j> / <https://spark.lucko.me/GkIZYLcI8X>
2. <https://spark.lucko.me/0Y4U6ec6R6> / <https://spark.lucko.me/Ob2Eel57E7>
3. <https://spark.lucko.me/pnBiuYyai8> / <https://spark.lucko.me/gZeudtOgj5>
4. <https://spark.lucko.me/4S3SLe2fTh> / <https://spark.lucko.me/Ln5wXmh0xU> (ON captured first)

Each profile used Spark's 4 ms Java sampler for about 122 seconds after a 30-second warm-up on the same restored world with target and block churn. Raw protobufs, logs, extraction JSON, scripts, hashes, and the baseline world remain in the external evidence bundle and are not included in the repository.

### Requirements

Minecraft 26.1.x, Fabric Loader 0.19+, Fabric API, Cloth Config, and Java 25. Server-side; vanilla clients can connect. No blanket compatibility guarantee is made for every modpack, version, or configuration.
