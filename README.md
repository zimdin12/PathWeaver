# PathWeaver

**Experimental, opt-in asynchronous mob pathfinding for Minecraft 26.1.2 (Fabric).**

PathWeaver can move the A* search used by eligible land and aquatic mobs off the server thread. It does not run entity ticks or collision off-thread. Version 0.1.1 defaults asynchronous search **off** while the v0.2 input/lifecycle rework is developed.

## Status and safety

PathWeaver 0.1.1 is an alpha honesty release, not a claim of proven thread safety or vanilla-equivalent behavior.

When explicitly enabled, the worker receives:

- a fresh `PathFinder` and exact vanilla `WalkNodeEvaluator` or `SwimNodeEvaluator`;
- a per-thread `PathTypeCache`, avoiding writes to the level's shared cache;
- a `PathNavigationRegion`, which is a read-only **view** of live chunks, not an immutable copy.

The eligible worker path avoids known shared-state writes, but it still reads live chunk and mob state. Those inputs can change during a search. The current general `createPath` interception can also change query-only call semantics, and lifecycle/staleness/foreign-mixin detection are not yet complete safety boundaries. Dispatch-time guards and pool rejection leave that invocation synchronous. A worker exception does not recompute the failed request; it forces later requests for that mob synchronous during a cooldown.

**Back up worlds and opt in only if you accept those limitations.** The v0.2 plan is to replace live inputs with immutable copies, preserve query-only `createPath` behavior, add complete request epochs/staleness checks, balance every callback path, and make compatibility discovery fail closed.

## Defaults in 0.1.1

```json
{
  "asyncEnabled": false,
  "repathElisionEnabled": true,
  "poolThreads": 0,
  "maxInFlight": 256,
  "distanceThrottleEnabled": false,
  "syncFallbackOnly": false,
  "repathToleranceBlocks": 0,
  "stalenessMoveThreshold": 4.0
}
```

- `asyncEnabled=false`: explicit opt-in is required on a newly generated config.
- `repathToleranceBlocks=0`: Feature B does not widen vanilla's short-circuit by default.
- Invalid numeric values are clamped before executor startup.
- `syncFallbackOnly=true` remains a panic switch that prevents async dispatch.

Existing `config/pathweaver.json` files are preserved on upgrade; review them explicitly if they came from 0.1.0.

## Eligibility gate

Only exact vanilla evaluator classes are eligible—never subclasses:

- Experimental async eligibility: `WalkNodeEvaluator`, `SwimNodeEvaluator`
- Always synchronous in 0.1.1:
  - `FlyNodeEvaluator`: its start-node search consumes the live mob RNG off-thread
  - `AmphibiousNodeEvaluator`: its `prepare`/`done` mutate live mob water malus
  - custom evaluator subclasses
  - evaluator families denied by the startup foreign-mixin scan

The current scanner reduces coverage when it recognizes a foreign evaluator mixin, but it is not yet fail closed: nonstandard/plugin-provided configs, scan failures, navigation/base/context targets, and broad trust rules remain v0.2 work. A clean scan is not proof that an arbitrary pack is safe.

## What the benchmark proved

Four paired, real Spark profiles in an isolated Fabric server with 160 pathfinding zombies showed that async ON moved measured A* work off the **Server thread**:

- `WalkNodeEvaluator` inclusive samples: 2613 → 236 ms per run on average (-90.97%)
- `WalkNodeEvaluator` self samples: 94 → 22 ms (-76.60%)
- `PathfindingContext` inclusive samples: 499 → 76 ms (-84.77%)
- `PathFinder` inclusive samples: 787 → 0 ms

It did **not** prove an overall MSPT speedup. Average mean MSPT was 2.927 ms OFF and 3.012 ms ON; paired results were noisy. The honest conclusion is measured server-thread pathfinding offload under that isolated Walk workload—not a universal TPS/MSPT improvement. Performance evidence does not resolve the correctness limitations above.

Raw profile URLs are listed in [`MODRINTH-COPY-v0.1.1.md`](MODRINTH-COPY-v0.1.1.md#what-the-benchmark-actually-proved). The raw sampler/health protobufs, complete logs, extraction JSON, scripts, hashes and baseline world are retained in the evidence bundle delivered with the release handoff.

## Compatibility

- Server-side; vanilla clients can connect.
- Fabric API and Cloth Config are required.
- Lithium is supported in the audited isolated stack, but no blanket compatibility guarantee is made for all versions/configurations.
- Exact-class gating keeps custom navigators/evaluators synchronous.
- Unknown or missed mixins can still evade the 0.1.1 scanner; use async only in a pack you have tested.

## Building and testing

Requires JDK 25:

```bash
./gradlew clean test build
```

## Reporting issues

Include:

- Minecraft/Fabric/PathWeaver versions
- `config/pathweaver.json`
- complete mod list and `latest.log`
- whether async was enabled
- reproduction steps and, for performance claims, a real Spark profile

Source and issues: <https://github.com/Zimdin12/PathWeaver>
