# PathWeaver design status

**Release line:** 0.1.1 alpha honesty patch

**Target:** Minecraft 26.1.2, Fabric Loader, Java 25

**Default:** asynchronous search off; repath tolerance zero

This document keeps the released 0.1.1 baseline explicit. Development master has since landed two v0.2 slices:

- genuine-navigation-only routing; direct/query-only `createPath` calls remain synchronous;
- fail-closed compatibility discovery over Loader-resolved Fabric/JiJ metadata and Mixin's prepared
  target sets, including plugin-expanded configs and shared evaluator/context/navigation/finder targets.

The development scanner has no broad owner exemptions. In the standard Fabric API test stack it detects
`fabric-content-registries-v0` hooks into `PathfindingContext`/`WalkNodeEvaluator` and denies Walk and
Swim async eligibility because dynamic path-type providers are not proven worker-safe. Immutable inputs
remain unresolved and require the held private snapshot-evaluator/A* design.

## 1. Current mechanism

PathWeaver intercepts `PathNavigation.createPath`. For an eligible exact vanilla evaluator and an enabled config, it builds a `PathNavigationRegion` and submits A* to a bounded executor. Each request uses a fresh evaluator/finder. A worker-local `PathTypeCache` prevents the search from writing the level's shared path-type cache. Completion is returned to the main thread and installed through navigation state.

`PathNavigationRegion` is a read-only API view backed by live `LevelChunk` objects. It is **not a block/fluid copy**. The worker also receives the live `Mob`; vanilla evaluator code reads its position, bounding box, attributes, malus values and level. Consequently 0.1.1 is experimental opt-in behavior, not a thread-safety proof.

## 2. Eligibility in 0.1.1

Exact-class allowlist:

- `WalkNodeEvaluator`
- `SwimNodeEvaluator`

Forced synchronous:

- `FlyNodeEvaluator`: flying start-node selection advances the live mob RNG
- `AmphibiousNodeEvaluator`: `prepare`/`done` write live mob water malus
- every subclass/custom evaluator
- evaluator classes denied by the startup foreign-mixin scan

The released 0.1.1 scanner is defense in depth, not a complete safety boundary. It can miss metadata-declared nonstandard configs, plugin/dynamic mixins, and navigation/base/context targets; scan failures do not deny globally. Development master replaces it with the fail-closed scanner summarized above.

## 3. Current safeguards

- New configs default `asyncEnabled=false`.
- Repath tolerance defaults to zero.
- `poolThreads`, `maxInFlight`, repath tolerance and staleness distance are clamped after load.
- Fresh finder/evaluator per request.
- Per-thread path-type cache.
- Main-thread completion/install path.
- Exact evaluator class gate.
- Dispatch-time saturation/rejection leaves that invocation synchronous.
- Worker exceptions mark the result failed and force later requests synchronous during a cooldown;
  the failed request itself is not recomputed synchronously.

These safeguards reduce risk; they do not repair the unresolved contract/input/lifecycle defects below.

## 4. Known 0.1.1 defects

1. **General `createPath` contract:** callers may use `createPath` only to query reachability. Returning old/null immediately and later installing the completed path can give a wrong answer and force movement that was never requested.
2. **Live inputs:** region reads reach live chunks; evaluators read a live mob. A staleness distance check cannot make those inputs immutable.
3. **Incomplete staleness identity:** target generation, maximum age, dimension/world identity, server epoch, entity UUID and exact navigation/request identity are not all bound and checked.
4. **Lifecycle generations:** shutdown does not await/epoch-isolate every interrupt-ignoring A* completion.
5. **Callbacks:** every rejection/clear/shutdown/exception path is not yet proven balanced; evaluator-specific multiplicity is not modeled.
6. **Result typing:** ordinary vanilla `null`/no-path is conflated with worker failure.
7. **Foreign-mixin discovery in released 0.1.1:** not fail closed and not complete over Fabric metadata/JiJ/plugins/expanded target classes/exact versioned trust. Development master resolves this item.
8. **Repath elision:** no changed-block guard; therefore the default tolerance is zero.

## 5. Performance evidence

Four real paired Spark runs in an isolated 160-zombie Walk workload measured a 90.97% reduction in Server-thread `WalkNodeEvaluator` inclusive samples and a 76.60% reduction in self samples. `PathFinder` samples moved fully off the Server thread in those captures.

Net MSPT did not improve reliably: average mean MSPT was 2.927 ms OFF versus 3.012 ms ON, with paired deltas ranging from -12.70% to +19.89%. The evidence supports an **offload** claim only. A v0.2 benchmark must also use a pathfinding load near the tick budget before any TPS/MSPT improvement claim is considered.

## 6. v0.2 acceptance boundary

Only restore safety/equivalence language after tests and runtime evidence prove:

- real-navigation-only async dispatch while query-only `createPath` remains synchronous;
- immutable block/fluid and mob-derived worker inputs, including captured RNG/world identity;
- request/server epochs and complete install identity/staleness checks;
- balanced callback accounting on every terminal path;
- tagged `SUCCESS` / `NO_PATH` / `FAILED` outcomes;
- fail-closed mixin discovery with exact versioned trust (landed on development master);
- sync/async node-sequence equivalence across the required Walk/Swim matrix;
- mutation/lifecycle/restart stress coverage;
- proof-based near-budget Spark benchmark.

Until those gates pass, 0.1.1 remains default-off and experimental.
