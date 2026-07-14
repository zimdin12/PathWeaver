# Changelog

## 0.1.1 — Honesty and default-off patch (2026-07-15)

### Changed

- Async search defaults off for newly generated configs; users must opt in explicitly.
- Repath tolerance defaults to `0`.
- Invalid pool-thread, in-flight, tolerance and staleness values are clamped before startup.
- Async eligibility is restricted to exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator`.
- `FlyNodeEvaluator` is synchronous because its start-node selection consumes the live mob RNG off-thread.
- Documentation now calls `PathNavigationRegion` a read-only view backed by live chunks, not an immutable copy.
- Safety/equivalence/universal-speedup claims were removed. Real Spark evidence supports server-thread A* offload for the tested isolated Walk workload, but did not show a reliable net MSPT improvement.

### Known limitations

The current worker still reads live chunk and mob state. General `createPath` interception can alter query-only caller semantics. Lifecycle/staleness identity, callback accounting, result typing, repath invalidation and foreign-mixin discovery require the v0.2 rework. Dispatch rejection leaves the invocation synchronous; a worker exception only forces later requests synchronous during a cooldown and does not recompute the failed request.

Existing config files are preserved; users upgrading from 0.1.0 should inspect `asyncEnabled` explicitly.

## 0.1.0 — Initial release (2026-07-10)

First public alpha release of asynchronous A* search and conservative repath elision for Minecraft 26.1.x (Fabric).

The 0.1.0 release text described the design as “safe by construction” and the region as an immutable snapshot. The 0.1.1 review found those claims were not supported: the region is backed by live chunks, evaluators read live mob state, and additional API/lifecycle/scanner defects remain. Those earlier statements are historical claims, not current guarantees.

### Features

- Bounded-worker A* dispatch behind an exact evaluator-class gate.
- Configurable repath tolerance.
- Fresh finder/evaluator and per-thread path-type cache per async request.
- Main-thread completion/install path, synchronous fall-through on dispatch rejection, and a later-request sync cooldown after worker failure.
- Startup foreign-mixin scan that reduces eligible coverage for recognized evaluator targets.

### Requirements

Fabric API, Cloth Config, Minecraft 26.1.x and Java 25.
