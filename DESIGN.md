# PathWeaver 0.2.2 design status

**Target:** Minecraft 26.1.2, Fabric Loader 0.19.3, Java 25

**Product decision:** ship the correctness baseline; fail closed; no private A* engine

**Defaults:** `asyncEnabled=true`, `allowModdedMobAsync=false`, `repathToleranceBlocks=0`

## 1. Product boundary

PathWeaver 0.2.2 is an experimental opt-out engine with conservative compatibility eligibility. It does not claim universal speed, vanilla-identical paths, immutable worker inputs, or blanket thread safety.

The standard Fabric content-registry module installs dynamic path-type provider hooks into `PathfindingContext` and `WalkNodeEvaluator`. Provider purity and worker safety are not declared. PathWeaver therefore denies Walk and Swim in standard content-registry packs and runs them synchronously. This inert fail-closed outcome is an intentional safety boundary.

## 2. Routing

Async depth is armed only around four proven navigation operations in `PathNavigation`:

1. coordinate movement;
2. coordinate movement with explicit reach;
3. entity movement;
4. recomputation.

Direct, external, and query-only `createPath` calls remain synchronous and do not mutate navigation path/speed state through PathWeaver.

When enabled and eligible, the engine submits a fresh exact vanilla Walk or Swim evaluator/finder to a bounded executor. Dispatch rejection leaves that same invocation synchronous. Accepted movement reports deferred success to its AI caller, and its exact speed value is bound to installation. A worker exception discards that request and forces later requests for the mob synchronous during cooldown; it does not recompute the failed request.

## 3. Compatibility eligibility

Only exact `WalkNodeEvaluator` and `SwimNodeEvaluator` classes are candidates. Fly consumes live mob RNG; Amphibious mutates live water malus; subclasses and custom evaluators remain synchronous. No Fly mixin is installed because Fly never enters the worker path.

The scanner reconciles current-environment Loader-resolved Fabric/JiJ metadata with Mixin's prepared active targets, including plugin-expanded configs. It normalizes dotted/internal target names and covers concrete evaluators, `NodeEvaluator`, `PathfindingContext`, `PathNavigation`, `GroundPathNavigation`, and `PathFinder`. Ownership, metadata, config, reflection, or discovery ambiguity denies Walk and Swim. There are no broad Fabric/Lithium/content-registry trust rules and no compatibility exemptions in 0.2.2.

The concrete mob class is independently checked through a cached `ClassValue<Boolean>`. Its `CodeSource` URL must equal vanilla `Mob`'s runtime origin; null origins and security failures deny dispatch. This works with Fabric Knot's shared classloader without trusting a spoofable package prefix. `allowModdedMobAsync` defaults false and bypasses only this origin gate.

The navigation-subclass boundary is deliberate: `WallClimberNavigation` entity movement overrides and bypasses the four base routing seams, so it stays synchronous; its inherited coordinate route passes through already-covered `GroundPathNavigation`. `WaterBoundPathNavigation` inherits the covered base path-creation seam, while Flying and Amphibious evaluators are ineligible.

## 4. Request lifecycle and installation

Every accepted request carries:

- server epoch and process-unique request token;
- numeric entity ID plus UUID/removal identity;
- exact level, dimension, navigation object, and current-path identity;
- semantic target revision;
- dispatch position and maximum result age.

Install requires the exact current registration and all identity/staleness checks. Changed targets supersede prior work. `stop()` invalidates pending work at an exact required injection. Block-change recomputation supersedes accepted pre-change work before vanilla's `canUpdatePath` guard, scopes invalidation around its virtual `createPath` call with `finally` cleanup, preserves the accepted movement speed for an eligible replacement, and dispatches from fresh world facts. An already accepted same-target request remains authoritative across ordinary mid-flight toggle changes.

Worker outcomes are closed and tagged:

- `SUCCESS` carries a path;
- `NO_PATH` carries neither path nor failure;
- `FAILED` carries the real throwable.

Only `FAILED` enters failure cooldown. Walk owns exactly one main-thread start/done callback pair; Swim owns none. Each accepted worker waits behind a one-shot gate until the main-thread start callback and request setup complete; opening the gate publishes those effects before live worker reads, while every abort releases it cancelled. Every terminal route balances accepted registration even if callbacks or diagnostics throw.

## 5. Repath reuse

Positive tolerance requires a reached active path, exact reach-range agreement, update-eligible navigation, no recompute invalidation, and one requested target satisfying both target tolerance and endpoint reach. Valid reuse preserves path identity and advances target intent. Overflow-safe Manhattan calculations use widened per-axis differences.

The shipped tolerance is `0`; Feature B's wider reuse is available but inactive by default.

## 6. Configuration UI

`fabric.mod.json` declares an explicit ModMenu entrypoint implementing `ModMenuApi#getModConfigScreenFactory`. Cloth AutoConfig supplies the persistent screen. `asyncEnabled` is the first option with the short experimental warning; `syncFallbackOnly` remains a lower panic switch. `allowModdedMobAsync` is a default-off advanced unsafe override for only the mob-origin gate. Direct JSON edits and previously persisted false values remain supported.

## 7. Unresolved live-input boundary

By default, only vanilla-origin mobs are eligible, so mod-defined subclasses with direct or indirect pathfinding overrides are synchronous. The residual experimental surface is narrower but still real: mods may Mixin into vanilla `Entity`, `LivingEntity`, or `Mob` methods, and the worker reads live vanilla mob/world/block state through `PathNavigationRegion` rather than an immutable snapshot. Fabric content-registry hooks are already denied. Completion validation prevents stale installation but cannot retroactively make live reads immutable; this is not a claim of full safety.

## 8. Rejected private snapshot engine

A private immutable snapshot evaluator/A* was designed to avoid worker access to live providers and world state. Eager full-cube capture was clearly too large. A later sparse Walk surface-capture spike measured a simplified lower bound against paired vanilla searches and failed the agreed relative-cost gate. Correct cave/detour coverage and provider semantics would only increase capture work, allocation, and maintenance.

The engine is cancelled, not pending implementation. The only plausible future architecture is an upstream immutable-chunk/provider-purity API that makes safe immutable inputs available without reconstructing them per request. That API does not exist here and is not pursued by 0.2.2.

## 9. Performance evidence

The retained four-pair Spark benchmark proves isolated server-thread A* offload: Walk evaluator inclusive samples fell 90.97% and `PathFinder` samples moved off the server thread. It does not prove net MSPT improvement: mean MSPT averaged 2.927 ms OFF and 3.012 ms ON with noisy pairs.

No load/scaling matrix is claimed for an engine that will not be built.
