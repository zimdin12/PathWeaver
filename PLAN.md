# PathWeaver release plan

This plan tracks only approved, current work. Historical proposals for shared immutable `PathNavigationRegion` caches, already-captured mob state, completed GameTests, or other unbuilt features have been removed rather than described as implemented.

## Phase 1 — v0.1.1 honesty patch

- [x] Replace immutable-snapshot / safe-by-construction / identical-behavior claims with the actual 0.1.1 boundaries.
- [x] Default new configs to `asyncEnabled=false`.
- [x] Default `repathToleranceBlocks=0`.
- [x] Clamp invalid pool-thread, in-flight, tolerance and staleness values before startup.
- [x] Restrict the exact evaluator allowlist to Walk + Swim; keep Fly and Amphibious synchronous.
- [x] Record proof-based benchmark framing: offload measured, net MSPT speedup not proven.
- [x] Build and run the complete JDK 25 test suite.
- [x] Independently review the exact diff.
- [x] Build the release JAR and verify its expanded metadata/default config behavior.
- [x] Commit, push and tag `v0.1.1`.
- [x] Prepare `MODRINTH-COPY-v0.1.1.md` for release handoff.

## Phase 2 — v0.2 rework

Each item requires a failing regression first, a focused green result, the full suite, and runtime evidence where applicable.

### Dispatch contract

- Move async dispatch to a seam representing an actual navigation command.
- Keep reachability/query-only `createPath` callers synchronous.
- Prove query callers receive the real synchronous result and never trigger deferred `moveTo`.

### Immutable worker inputs

- Copy required block/fluid/collision data on the main thread.
- Capture mob position, hitbox, step height, malus/capability inputs and required RNG state.
- Prevent worker code from dereferencing live `Mob`, `Level`, `LevelChunk`, or shared caches.
- Bind the snapshot to immutable world/dimension identity.

### Request and lifecycle identity

- Carry server epoch and request generation through dispatch, completion and install.
- Check epoch, world/dimension, entity UUID/removal state, navigation identity, target generation and maximum tick age.
- Supersede materially changed targets.
- Stop acceptance, cancel, await and generation-reject late tasks during shutdown/restart.

### Completion semantics

- Introduce tagged `SUCCESS`, `NO_PATH`, and `FAILED` outcomes.
- Reserve failure cooldown/logging for actual exceptions.
- Balance callbacks for submit rejection, exception, no-path, stale discard, clear and shutdown.
- Model evaluator-specific callback multiplicity; keep unsupported evaluators synchronous.
- Do not silently swallow completion callback failures.

### Compatibility gate

- Fail closed on unreadable/unparseable/unverifiable scan input.
- Cover `NodeEvaluator`, concrete evaluators, `PathfindingContext`, `PathNavigation`, `GroundPathNavigation`, and `PathFinder` with correct global/per-family denial.
- Read Fabric-declared mixin configs, recurse nested JiJ archives, and account for plugin/dynamic mixins.
- Replace prefix trust with exact mod ID + version + mixin class + target audit entries.
- Emit scanned/failed/denied diagnostics.
- Verify the concrete installed Fabric API, Lithium, DiagonalBlocks JiJ, Carpet, rabbit-pathfinding-fix and ServerCore interactions.

### Repath elision

- Keep tolerance zero until endpoint/reach/navigation validity and changed-block invalidation are implemented and tested.

### Required test/evidence matrix

- Walk/Swim sync-vs-async node sequences: offset-upward, multi-target, doors, fences, water and height transitions.
- Concurrent block/mob mutation stress.
- Removal, unload, dimension change, shutdown and restart.
- Superseded targets and maximum-age rejection.
- Callback ordering/count on every terminal path.
- Scanner integration: JiJ, nonstandard metadata, plugin/unverifiable, failure, exact trust.
- Mixin-application assertions.
- Real Spark profiles under both the isolated workload and a repeatable near-tick-budget pathfinding load.

No claim of safe, equivalent or faster behavior is restored unless the corresponding test/runtime gate proves it. Multi-version work remains out of scope until explicitly approved.
