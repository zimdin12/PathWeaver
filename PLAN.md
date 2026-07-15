# PathWeaver release plan

This plan tracks approved current work and marks already-landed milestones explicitly. Historical proposals for shared immutable `PathNavigationRegion` caches or unbuilt features are not described as implemented.

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

## Interim release — v0.1.2

- [x] Land navigation-only routing (`37ffaa6`) and fail-closed compatibility discovery (`b77bac6`).
- [x] Default newly generated configs to `asyncEnabled=true`; preserve explicit persisted values.
- [x] Keep exact Walk + Swim eligibility, Fly/Amphibious exclusion and repath tolerance zero.
- [x] Retain experimental alpha, live-input and no-net-speedup disclosures.
- [x] Complete the final JDK 25 cold build and independent exact-diff PASS.

## Phase 2 — v0.2 rework

Each item requires a failing regression first, a focused green result, the full suite, and runtime evidence where applicable.

### Dispatch contract

- [x] Move async dispatch to a seam representing an actual navigation command.
- [x] Keep reachability/query-only `createPath` callers synchronous.
- [x] Prove query callers receive the real synchronous result and never trigger deferred `moveTo`.

### Immutable worker inputs

- [x] Prove vanilla evaluator reuse cannot provide strict immutable worker inputs.
- [x] Record the field constraint that standard Fabric content registries currently deny Walk + Swim,
  making v0.1.2's async engine effectively inert in a typical pack even though bare-stack offload works.
- [ ] After remaining correctness slices, profile the 160/320/640 mixed-mob normal pack synchronously
  to establish whether pathfinding is a meaningful tick-budget cost before funding the private port.
- [ ] If the load gate shows meaningful benefit, architecture-proof an in-mod immutable snapshot
  evaluator + private A* as the sole async engine behind `asyncEnabled`, including a proven replacement
  for sensitive content-registry path-type provider semantics rather than merely removing the denial.
- [ ] Audit Fabric API `0.153.0` content-registry bytecode and enumerate the actually registered provider
  set. Permit only exact version/provider combinations proven worker-safe; unknown/dynamic providers deny.
- [ ] Prefer server-thread capture of provider-influenced path-type facts into the immutable snapshot so
  worker threads execute no third-party provider callbacks.
- [ ] Prove exhaustive Walk/Swim path equivalence before restoring any safety/equivalence claim.

### Request and lifecycle identity

- [x] Carry server epoch and process-unique request token through dispatch, completion, and install.
- [x] Require exact epoch/token/entity-ID registration matching before install, discard, failure cooldown,
  or callback mutation.
- [x] Isolate executor capacity and failure counters per generation so interrupt-ignoring old workers
  cannot mutate restart state.
- [ ] Check world/dimension, entity UUID/removal state, navigation identity, target revision, and maximum age.
- [ ] Supersede materially changed targets.

### Completion semantics

- Introduce tagged `SUCCESS`, `NO_PATH`, and `FAILED` outcomes.
- Reserve failure cooldown/logging for actual exceptions.
- Balance callbacks for submit rejection, exception, no-path, stale discard, clear and shutdown.
- Model evaluator-specific callback multiplicity; keep unsupported evaluators synchronous.
- Do not silently swallow completion callback failures.
- Record dispatch-to-install latency distribution/counters before broader async eligibility.
- Deterministically prove accepted-pending movement behavior for no current path, a live current path,
  and a superseded target under controlled worker delay; visible stalls are release blockers.

### Compatibility gate

- [x] Fail closed on unreadable/unparseable/unverifiable scan input.
- [x] Cover `NodeEvaluator`, concrete evaluators, `PathfindingContext`, `PathNavigation`,
  `GroundPathNavigation`, and `PathFinder` with correct global/per-family denial.
- [x] Read Fabric-declared mixin configs from Loader-resolved containers/JiJ mods and account for
  plugin-expanded prepared mixins.
- [x] Replace prefix trust with exact mod ID + version + config + mixin class + target audit entries.
- [x] Emit scanned/failed/denied diagnostics.
- [ ] Extend retained compatibility evidence when additional concrete pack combinations are tested.

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
- Normal-pack scanner decision plus dispatch/install/discard counters, so an inert fail-closed run is not
  misreported as async performance or behavioral evidence.
- 160, 320, and 640 nearby mixed mob types in the realistic Fabric stack, with async OFF/ON legs,
  `dispatched > 0` for every claimed async leg, trajectory/movement-continuity observations, path validity,
  crashes/errors, MSPT/TPS distribution, and clean shutdown accounting.

### v0.2 acceptance and release

- [ ] Land all correctness slices, prominent in-game toggle, latency telemetry, and accepted-pending tests.
- [ ] Resolve normal-pack inertness without broad trust or arbitrary live provider execution.
- [ ] Pass the full mixed-mob scaling matrix and final near-budget ON/OFF benchmark with retained evidence.
- [ ] Obtain Steven's review before publishing v0.2 and then update Modrinth.
- [ ] If v0.1.2 is instead proven to cause a crash or serious regression, prioritize a minimal confirmed
  fix and coordinate an immediate Modrinth patch after in-game no-crash verification; do not wait for the
  v0.2 review. Inert fail-closed behavior alone is not classified as that emergency.

No claim of safe, equivalent or faster behavior is restored unless the corresponding test/runtime gate proves it. Multi-version work remains out of scope until explicitly approved.
