# PathWeaver

**Experimental, fail-closed asynchronous mob pathfinding for Minecraft 26.1.2 (Fabric).**

PathWeaver can move eligible Walk/Swim A* searches off the server thread. It does not move entity ticks or collision processing off-thread. Version 0.2.3 is a conservative quality and compatibility pass over the experimental engine; it is not a universal-speed, vanilla-equivalence, or thread-safety claim.

See the [version-exact compatibility matrix](COMPATIBILITY.md) for audited verdicts, artifact hashes, and the fail-closed evidence boundary.

## Toggle and disable instructions

With ModMenu installed, open **Mods → PathWeaver → Config**. The first option is **Enabled**. Turning it off stops new off-thread searches and repath reuse. Work already accepted before the save drains safely; later routing is vanilla-synchronous. Save writes schema-v2 `config/pathweaver.json` and updates the live config; worker-thread and in-flight limits apply after restart.

You can also edit `config/pathweaver.json`:

```json
{
  "configVersion": 2,
  "enabled": false
}
```

- `enabled=false` is the single normal and emergency off switch; it gates both async dispatch and repath reuse.
- `allowModdedMobAsync=true` is an advanced unsafe override. It is ineffective while `enabled=false` and bypasses only the vanilla-origin mob gate; all evaluator, Mixin-scanner, lifecycle, and fallback gates still apply.
- Legacy files migrate conservatively with `enabled = asyncEnabled && !syncFallbackOnly`; every old OFF combination remains OFF. Explicit save writes v2 and removes the legacy keys.
- Malformed or unreadable persisted config fails closed to synchronous runtime defaults until a valid
  config is saved; Cloth's enabled in-memory defaults are not mistaken for a successful load.

## Honest compatibility status

PathWeaver fails closed. Only exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator` searches are candidates. By default, the concrete mob class must also come from the same runtime code source as vanilla `Mob`; mod-defined mob subclasses therefore stay synchronous even when they inherit Walk/Swim evaluators. Fly, Amphibious, evaluator subclasses, unknown ownership, scanner failures, and sensitive foreign mixins stay synchronous.

The current artifact requires the official aggregate `fabric-api`, which normally loads `fabric-content-registries-v0`. That module declares active pathfinding Mixins whose provider registry has no worker-safety contract. The fail-closed scanner therefore denies both eligible evaluator families. **The current supported dependency graph is synchronous even when `enabled=true`.** Fabric Loader's `-Dfabric.debug.disableModIds=fabric-content-registries-v0` debug facility can exclude that nested module and has live-proven dispatch, but Loader documents the facility as mostly for unit testing. It is not a supported or representative user configuration and cannot justify normal-release benchmarks.

A clean compatibility scan only means no known sensitive Mixin target was discovered. The default origin gate closes the direct and indirect mod-defined mob-override gap, but it does not prove the remaining live-input boundary safe. Mods may still Mixin into vanilla `Entity`, `LivingEntity`, or `Mob` methods, and workers still read live vanilla mob/world/block state without an immutable snapshot. Fabric content-registry hooks are already denied. Do not enable the advanced modded-mob override unless you deliberately accept additional unverified virtual-method execution.

## What 0.2 changed

- Async interception is limited to four genuine navigation/recompute operations; direct and query-only `createPath` calls stay synchronous and immediate.
- Every request is bound to a server epoch, process-unique request token, entity UUID/removal state, world/dimension, exact navigation/current-path identity, semantic target revision, movement, and maximum result age.
- Supersession, navigation stop, recompute invalidation, shutdown, stale results, and exceptions terminally balance accepted registrations.
- Worker outcomes are tagged `SUCCESS`, `NO_PATH`, or `FAILED`; an ordinary no-path does not enter exception cooldown.
- Walk callback replay is exactly one start/done pair; Swim replays none. An accepted worker search is
  held behind a main-thread start barrier, so callback effects happen-before worker reads.
- Accepted deferred movement reports success to the requesting AI, and exact caller speed—including
  `0`, negative, or `NaN`—is bound to installation and refreshed by same-target pending requests.
- Recompute supersedes pre-change accepted work before vanilla's `canUpdatePath` guard and preserves
  the accepted request's exact speed when a replacement is dispatched.
- Mod-defined mob subclasses are synchronous by default through a cached, fail-closed code-origin gate.
- Positive repath reuse requires a reached active path, exact reach agreement, a valid endpoint, and update-eligible navigation. Block-change recomputation always bypasses reuse and supersedes pending same-target work.
- Repath tolerance remains `0` by default.
- ModMenu now has an explicit entrypoint and persistent configuration screen.

## Remaining experimental boundary

Where compatibility permits dispatch, the current worker still receives a read-only view backed by live chunks plus live vanilla mob inputs. Install-time validation can reject obsolete results, but it cannot make those reads immutable. Mixins into vanilla entity methods can also extend that live call graph. Therefore PathWeaver makes no vanilla-equivalence or general thread-safety guarantee.

A private immutable snapshot evaluator/A* was designed and cost-measured. Even a simplified lower-bound surface capture consumed too much of the paired vanilla search budget; correct cave, detour, and provider coverage would add work. The private engine was rejected rather than forced through. The only credible future route is an upstream immutable-chunk snapshot and provider-purity API; PathWeaver does not implement or pursue that API in 0.2.2.

## Defaults

```json
{
  "enabled": true,
  "configVersion": 2,
  "allowModdedMobAsync": false,
  "repathElisionEnabled": true,
  "poolThreads": 0,
  "maxInFlight": 256,
  "repathToleranceBlocks": 0,
  "stalenessMoveThreshold": 4.0,
  "maxResultAgeTicks": 40
}
```

Invalid numeric values are clamped before runtime services consume them.

## What the benchmark proved

Four paired Spark profiles in a test-only denial-cleared isolated server with 160 pathfinding zombies showed measured server-thread A* offload:

- `WalkNodeEvaluator` inclusive samples: 2613 → 236 ms per run on average (-90.97%)
- `WalkNodeEvaluator` self samples: 94 → 22 ms (-76.60%)
- `PathfindingContext` inclusive samples: 499 → 76 ms (-84.77%)
- `PathFinder` inclusive samples: 787 → 0 ms

It did **not** prove a net MSPT win and is not representative of the current required Fabric API install. Average mean MSPT was 2.927 ms OFF and 3.012 ms ON, with noisy paired results. The only supported claim is that the isolated engine path can offload server-thread A* when its production compatibility denial is removed test-only—not that current users receive it.

## Requirements

- Minecraft 26.1.x
- Fabric Loader 0.19+
- Fabric API
- Cloth Config
- Java 25
- ModMenu is optional but recommended for the in-game toggle

Server-side; vanilla clients can connect. Keep backups and disable async if you do not accept the experimental boundary.

## Building and testing

```bash
./gradlew clean test build
```

When reporting issues, include Minecraft/Fabric/PathWeaver versions, the config, complete mod list and log, async state, reproduction steps, and a real Spark profile for performance claims.

Source and issues: <https://github.com/Zimdin12/PathWeaver>