# PathWeaver design status

**Release line:** 0.1.2 default-on alpha

**Target:** Minecraft 26.1.2, Fabric Loader 0.19.3, Java 25

**Defaults:** asynchronous search on; repath tolerance zero

This document describes v0.1.2. It does not restore the original unsupported claims that `PathNavigationRegion` was an immutable snapshot or that async paths were safe by construction, vanilla-identical, or universally faster.

Development master additionally carries the completed v0.2 lifecycle/staleness slices: server epochs,
process-unique request tokens, generation-owned counters, and install validation over UUID/removal,
world/dimension, exact navigation/current-path identity, semantic target revision, movement, and maximum
age. Changed targets and navigation stop cancel the prior registration. These checks prevent late-result
mutation; they do not make the worker's captured search inputs immutable.

## 1. Current mechanism

PathWeaver arms async interception only around four proven genuine-navigation `createPath` invocations in `PathNavigation`: coordinate movement, coordinate movement with explicit reach, entity movement, and recomputation. Direct/external/query-only `createPath` calls remain vanilla synchronous.

For an enabled, permitted navigation request, PathWeaver builds a `PathNavigationRegion` and submits A* to a bounded executor. Each request uses a fresh evaluator/finder. A worker-local `PathTypeCache` avoids writes to the level's shared path-type cache. Completion is returned to the main thread and installed through navigation state.

`PathNavigationRegion` is a read-only API view backed by live `LevelChunk` objects. It is **not a block/fluid copy**. The worker also receives the live `Mob`; vanilla evaluator code reads its position, bounding box, attributes, malus values, and level. Default-on v0.1.2 is therefore an experimental opt-out alpha, not a thread-safety proof.

## 2. Default and fallback behavior

New v0.1.2 configurations use `asyncEnabled=true`. Existing files are not migrated and retain their explicit value.

Users can prevent async dispatch with either:

- `asyncEnabled=false`; or
- `syncFallbackOnly=true`.

Dispatch saturation/rejection leaves that same invocation synchronous. A worker exception is different: the failed request is discarded and only later requests for that mob are forced synchronous during a cooldown.

Repath tolerance remains `0`, so the experimental widened Feature B short-circuit is inactive by default.

## 3. Eligibility

Exact-class candidates:

- `WalkNodeEvaluator`
- `SwimNodeEvaluator`

Forced synchronous:

- `FlyNodeEvaluator`: start-node selection advances the live mob RNG;
- `AmphibiousNodeEvaluator`: `prepare`/`done` write live mob water malus;
- every subclass/custom evaluator;
- any family denied by compatibility discovery.

## 4. Fail-closed compatibility discovery

At initialization, PathWeaver:

- reads Fabric-declared server mixin configs for every Loader-resolved container, including JiJ mods;
- reconciles every declaration with Mixin's prepared active configs;
- retains exact mod ID, version, config, concrete mixin class, and target identity;
- includes plugin-expanded prepared targets;
- covers concrete evaluators, `NodeEvaluator`, `PathfindingContext`, `PathNavigation`, `GroundPathNavigation`, and `PathFinder`;
- denies Walk and Swim on metadata, ownership, reconciliation, reflection, or parsing failure;
- grants no prefix, whole-mod, or exact exemptions in v0.1.2.

The standard Fabric content-registry module mixes into `PathfindingContext` and `WalkNodeEvaluator` to expose dynamic path-type providers. Those callbacks are not proven worker-safe, so the scanner forces Walk and Swim synchronous in that stack. This is an intentional safety reduction, not an error.

## 5. Current safeguards

- Async is on by default but remains behind exact evaluator and fail-closed compatibility gates.
- Direct/query-only `createPath` stays synchronous.
- Repath tolerance defaults to zero.
- Thread, in-flight, tolerance, and staleness values are bounded after load.
- Fresh finder/evaluator per request.
- Per-thread path-type cache.
- Main-thread completion/install path.
- Dispatch rejection keeps the same invocation synchronous.
- Users have both normal and panic-switch opt-outs.

These measures reduce known risk; they do not repair the unresolved live-input defect below.

## 6. Remaining defects

1. **Live inputs:** region reads reach live chunks and evaluators read a live mob.
2. **Live-input immutability:** completed install staleness can reject an obsolete result but cannot undo
   worker reads that raced live chunk/mob state; the private snapshot engine remains required.

Worker results are now explicitly tagged `SUCCESS`, `NO_PATH`, or `FAILED`; ordinary vanilla `null` is
`NO_PATH` and only actual exceptions enter failure cooldown/logging.

Callback replay is evaluator-specific and exact: Walk owns one main-thread start/done pair and Swim owns
none. Every accepted-registration terminal path, including clear/shutdown and install exceptions, removes
first and balances completion behind a contained callback boundary.

Positive repath tolerance now requires an active reached path, exact reach-range agreement, current
navigation update eligibility, and one target that satisfies both path-target tolerance and endpoint
reach. Successful reuse advances navigation target intent. Recompute is explicitly invalidated from `HEAD`
through `RETURN`, so normal block-change recompute cannot take the widened reuse path or preserve same-target
pending work; the old registration is terminally superseded and a fresh request uses current world facts.
Tolerance remains `0` by default despite this proof.

## 7. Performance evidence

Four real paired Spark runs in an isolated 160-zombie Walk workload measured a 90.97% reduction in Server-thread `WalkNodeEvaluator` inclusive samples and a 76.60% reduction in self samples. `PathFinder` samples moved fully off the Server thread in those captures.

Net MSPT did not improve reliably: average mean MSPT was 2.927 ms OFF versus 3.012 ms ON. This supports
an offload claim only in that unsaturated workload. A near-tick-budget Walk/Swim benchmark is required to
validate and tune the snapshot evaluator/A* port; it does not decide whether the approved port may begin.

## 8. Snapshot-engine acceptance boundary

The approved future architecture is an in-mod immutable snapshot evaluator plus private A* loop as
PathWeaver's sole async engine behind `asyncEnabled`. It begins after the remaining tractable correctness
slices. Saturated near-budget benchmarks validate and tune the implementation and its server-thread relief;
they are not a pre-port permission gate.

No safety or path-equivalence language is earned until exhaustive sync-versus-snapshot tests prove Walk/Swim path equivalence across offset-upward behavior, multi-target selection, doors, fences, water, height/fall constraints, malus values, and step-height variation.
