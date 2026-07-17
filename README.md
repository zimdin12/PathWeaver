# PathWeaver

**Experimental, default-on asynchronous mob pathfinding for Minecraft 26.1.2 (Fabric).**

PathWeaver can move the A* search used by eligible land and aquatic mobs off the server thread. It does not run entity ticks or collision off-thread. Version 0.1.2 enables asynchronous search by default for newly generated configurations, while retaining strict gates and synchronous fallbacks.

This is an alpha. **Expect bugs, keep backups, and disable async if you do not accept the current limitations.** Default-on is an opt-out choice, not a claim of proven thread safety, vanilla-equivalent paths, or faster MSPT.

## Disable or limit async

Set either option in `config/pathweaver.json`:

- `"asyncEnabled": false` — disables async dispatch and uses vanilla pathfinding.
- `"syncFallbackOnly": true` — panic switch that prevents async dispatch.

Existing configuration files are preserved during upgrades. A v0.1.1 file with `asyncEnabled=false` remains off; delete/regenerate it or set the field explicitly to adopt the new default.

## Current implementation

For a permitted navigation request, the worker receives:

- a fresh `PathFinder` and exact vanilla `WalkNodeEvaluator` or `SwimNodeEvaluator`;
- a per-thread `PathTypeCache`, avoiding writes to the level's shared cache;
- a `PathNavigationRegion`, which is a read-only **view** of live chunks, not an immutable copy;
- the live mob inputs used by vanilla evaluator code.

Version 0.1.2 includes two correctness improvements over v0.1.1:

- async interception is armed only by genuine navigation/recompute operations; direct and query-only `createPath` calls remain synchronous and do not dispatch or mutate navigation path/speed state;
- compatibility discovery fails closed over Loader-resolved Fabric/JiJ metadata and Mixin's prepared targets, including plugin-expanded configs and shared evaluator/context/navigation/finder targets.

The worker still reads live chunk and mob state. Those inputs can change during a search. Development master now binds every completion to server epoch/token, UUID/removal state, world/dimension, exact navigation/current-path identity, semantic target revision, movement, and maximum age, with target supersession and stop invalidation. These checks reject obsolete installs; they do not make worker inputs immutable. Worker completion is explicitly tagged `SUCCESS`, `NO_PATH`, or `FAILED`, so an ordinary vanilla null/no-path does not trigger exception cooldown. Callback replay is exact per evaluator—one Walk start/done pair, none for Swim—and every accepted terminal path, clear, and shutdown balances it. Dispatch rejection leaves that invocation synchronous. A worker exception does not recompute the failed request; it discards that result and forces later requests for the mob synchronous during a cooldown.

A private snapshot evaluator and A* port is approved as the eventual single async engine after the remaining correctness slices. Saturated normal-pack benchmarks validate and tune server-thread relief; they no longer gate permission to build the port. No immutable-input or path-equivalence claim is made today.

## Defaults in 0.1.2

```json
{
  "asyncEnabled": true,
  "repathElisionEnabled": true,
  "poolThreads": 0,
  "maxInFlight": 256,
  "distanceThrottleEnabled": false,
  "syncFallbackOnly": false,
  "repathToleranceBlocks": 0,
  "stalenessMoveThreshold": 4.0
}
```

- `asyncEnabled=true`: async is attempted by default only after every runtime gate passes.
- `repathToleranceBlocks=0`: Feature B does not widen vanilla's short-circuit by default. Positive values
  require a reached active path, exact reach agreement, valid endpoint and navigation state, and are always
  bypassed by normal recompute/block-change invalidation. Recompute also supersedes same-target pending work
  so the replacement request observes fresh world facts.
- Invalid numeric values are clamped before executor startup.
- `syncFallbackOnly=true` prevents all async dispatch.

## Eligibility and compatibility gate

Only exact vanilla evaluator classes are candidates—never subclasses:

- Async candidates: `WalkNodeEvaluator`, `SwimNodeEvaluator`
- Always synchronous:
  - `FlyNodeEvaluator` because its start-node search consumes the live mob RNG;
  - `AmphibiousNodeEvaluator` because its `prepare`/`done` mutate live mob water malus;
  - custom evaluator subclasses;
  - evaluator families denied by compatibility discovery.

The scanner covers concrete evaluators plus `NodeEvaluator`, `PathfindingContext`, `PathNavigation`, `GroundPathNavigation`, and `PathFinder`. Metadata, ownership, active-config, plugin, or reflection uncertainty denies Walk and Swim rather than guessing. There are no broad Fabric, Diagonal, or Lithium trust rules and no compatibility exemptions in v0.1.2.

The standard Fabric content-registry module hooks `PathfindingContext` and `WalkNodeEvaluator` to expose dynamic path-type providers. Those callbacks are not proven worker-safe, so v0.1.2 forces Walk and Swim synchronous when that module is installed. This is the intended fail-closed outcome: enabling async by default does not override compatibility denials.

A clean scan proves only that this gate found no known sensitive mixin target. It does not make live worker inputs immutable or prove an arbitrary pack safe.

## What the benchmark proved

Four paired, real Spark profiles in an isolated Fabric server with 160 pathfinding zombies showed that async ON moved measured A* work off the **Server thread**:

- `WalkNodeEvaluator` inclusive samples: 2613 → 236 ms per run on average (-90.97%)
- `WalkNodeEvaluator` self samples: 94 → 22 ms (-76.60%)
- `PathfindingContext` inclusive samples: 499 → 76 ms (-84.77%)
- `PathFinder` inclusive samples: 787 → 0 ms

It did **not** prove an overall MSPT speedup. Average mean MSPT was 2.927 ms OFF and 3.012 ms ON; paired results were noisy. The supported claim is measured server-thread pathfinding offload under that isolated Walk workload—not a universal TPS/MSPT improvement.

Raw profile URLs are retained in [`MODRINTH-COPY-v0.1.2.md`](MODRINTH-COPY-v0.1.2.md#what-the-benchmark-actually-proved). Later near-budget load benchmarks validate and tune the approved snapshot/A* port and its server-thread relief; they do not gate permission to implement it.

## Requirements

- Minecraft 26.1.x
- Fabric Loader 0.19+
- Fabric API
- Cloth Config
- Java 25

Server-side; vanilla clients can connect. No blanket compatibility guarantee is made for every modpack, version, or configuration.

## Building and testing

```bash
./gradlew clean test build
```

## Reporting issues

Include Minecraft/Fabric/PathWeaver versions, `config/pathweaver.json`, the complete mod list and `latest.log`, whether async was enabled, reproduction steps, and a real Spark profile for performance claims.

Source and issues: <https://github.com/Zimdin12/PathWeaver>
