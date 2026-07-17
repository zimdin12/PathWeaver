# Changelog

## Unreleased — v0.2 correctness rework

### Changed

- Server start/stop advances an epoch; every async dispatch carries that epoch plus a process-unique
  request token and entity ID through worker completion and main-thread install.
- Late results, failures, and cooldown updates require an exact request-key match and cannot consume a
  replacement registration that happens to reuse the same numeric entity ID.
- Every worker-pool generation owns its executor, in-flight capacity, and failure counters. An
  interrupt-ignoring old worker can finish after restart without decrementing or incrementing the new
  generation's counters.
- Install validation now binds entity UUID/removal state, exact world and navigation object, dimension,
  current path identity, semantic target revision, movement threshold, and a bounded result age. A changed
  target supersedes and balances the prior registration; an accepted same-target pending request remains
  authoritative across mid-flight config toggles. `stop()` invalidates pending work at an exact, required
  injection, and a throwing mod callback is contained/logged once so cancellation remains terminal.
- Worker completion is now explicitly tagged `SUCCESS`, `NO_PATH`, or `FAILED`. Vanilla `null` is an
  ordinary no-path completion and does not trigger the exception cooldown. Actual search exceptions retain
  their cause and take the failure path. Completion-consumer exceptions are counted and logged instead of
  being silently swallowed.
- Exact Walk searches replay one main-thread start/done callback pair; exact Swim searches replay none.
  The Walk redirects lock `require=1` and `expect=1`. Success, no-path, failure, stale discard,
  supersession, stop, install exception, clear, and server shutdown all terminally balance accepted
  registrations; duplicate registration fails closed without displacing the accepted owner, and callback
  or logging exceptions cannot strand the remaining registrations.
- Positive Feature B reuse now requires a reached, active path, exact reach-range agreement, a navigation
  that can update, and one requested target satisfying both path-target tolerance and endpoint reach. A valid
  reuse advances navigation target intent so later recomputation follows the new target.
  `recomputePath()` is scoped by exact `HEAD`/`RETURN` injections so block-change recomputation cannot reuse
  the invalidated path or preserve same-target pending work; it supersedes that registration and dispatches
  from fresh world facts. Live GameTest proof covers pending replacement, valid one-block drift, and forced
  recompute replacement.
  The shipped/default tolerance remains `0` as the conservative product setting.

### Still pending

The worker inputs are still live-backed until the approved immutable snapshot/private A* replaces the
current evaluator reuse.

## 0.1.2 — Default-on routing and fail-closed compatibility (2026-07-15)

### Changed

- Async search defaults **on** for newly generated configs. Existing files retain their explicit value;
  set `asyncEnabled=false` or `syncFallbackOnly=true` to opt out.
- Async routing is armed only by genuine navigation/recompute operations; direct and query-only
  `createPath` calls remain synchronous and do not dispatch or mutate navigation path/speed state.
- Foreign-mixin discovery now fails closed, reads Fabric-declared configs for every Loader-resolved
  container (including JiJ mods), inspects Mixin's prepared target sets including plugin contributions,
  and covers `NodeEvaluator`, concrete evaluators, `PathfindingContext`, `PathNavigation`,
  `GroundPathNavigation`, and `PathFinder`.
- Prefix and whole-mod trust rules were removed. Exemptions are exact mod-version/config/mixin-class/
  target audit tuples; none are currently granted.
- Scanner diagnostics report scanned, failed, and denied counts. The standard Fabric API stack is
  intentionally forced synchronous because its content-registry module exposes dynamic path-type
  providers through sensitive pathfinding mixins that are not proven worker-safe.

### Still unresolved

The worker still reads live chunk and mob state. True immutability requires the approved but benchmark-
gated private snapshot evaluator/A* port. Epoch/token/staleness, callback accounting, tagged outcomes,
and positive-tolerance repath validity remain separate slices. Default-on does not imply proven safety,
vanilla equivalence, or a net MSPT improvement.

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
