# PathWeaver post-0.2.3 design status

**Target:** Minecraft 26.1.2, Fabric Loader 0.19.3, Java 25

**Product decision:** hold publication while the required aggregate Fabric API makes every supported install fail closed

**Defaults:** schema v2, `enabled=true`, `allowModdedMobAsync=false`, `repathToleranceBlocks=0`

## 1. Product boundary

PathWeaver is an experimental opt-out engine with conservative compatibility eligibility. It does not claim universal speed, vanilla-identical paths, immutable worker inputs, or blanket thread safety.

The current manifest requires the official aggregate `fabric-api`, which normally JiJ-loads `fabric-content-registries-v0`. Its required prepared Mixin config targets `PathfindingContext` and `WalkNodeEvaluator`. Provider purity and worker safety are not declared, so the declaration-driven scanner denies Walk and Swim. The current supported dependency graph is therefore inert. Loader's debug-only `fabric.debug.disableModIds` exclusion can remove the nested module and has live-proven dispatch, but it is documented as mostly for unit testing and is not a representative release configuration.

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

`fabric.mod.json` declares an explicit ModMenu entrypoint implementing `ModMenuApi#getModConfigScreenFactory`. Cloth AutoConfig supplies the persistent screen. Schema v2 exposes one first-listed default-on `Enabled` master. OFF gates both new async dispatch and repath reuse; accepted work drains through its existing exact registration. `allowModdedMobAsync` is a subordinate default-off unsafe override for only the mob-origin gate. Raw JSON migration computes `enabled = legacyAsyncEnabled && !legacySyncFallbackOnly`, so no old OFF combination becomes ON; malformed and future schemas fail closed.

## 7. Unresolved live-input boundary

By default, only vanilla-origin mobs are eligible, so mod-defined subclasses with direct or indirect pathfinding overrides are synchronous. The residual experimental surface is narrower but still real: mods may Mixin into vanilla `Entity`, `LivingEntity`, or `Mob` methods, and the worker reads live vanilla mob/world/block state through `PathNavigationRegion` rather than an immutable snapshot. Fabric content-registry hooks are already denied. Completion validation prevents stale installation but cannot retroactively make live reads immutable; this is not a claim of full safety.

## 8. Rejected private snapshot engine

A private immutable snapshot evaluator/A* was designed to avoid worker access to live providers and world state. Eager full-cube capture was clearly too large. A later sparse Walk surface-capture spike measured a simplified lower bound against paired vanilla searches and failed the agreed relative-cost gate. Correct cave/detour coverage and provider semantics would only increase capture work, allocation, and maintenance.

The engine is cancelled, not pending implementation. The only plausible future architecture is an upstream immutable-chunk/provider-purity API that makes safe immutable inputs available without reconstructing them per request. That API does not exist here and is not pursued by 0.2.2.

## 9. Performance evidence

The retained four-pair test-only denial-cleared Spark benchmark proves only that the isolated engine path can offload server-thread A*: Walk evaluator inclusive samples fell 90.97% and `PathFinder` samples moved off the server thread. It is not a user-real benchmark under the current required dependency graph and does not prove net MSPT improvement: mean MSPT averaged 2.927 ms OFF and 3.012 ms ON with noisy pairs.

No load/scaling matrix is claimed for an engine that will not be built.

## 10. Rejected async `MoveToTargetSink` (brain-driven walk targets)

The largest remaining block of server-thread A* on a real 317-mod client is not a gap in coverage.
It is structural, and this section records why, because the profile makes it look like low-hanging
fruit and it is not.

**What the profile shows.** Client spark capture, PathWeaver active, all six families dispatching,
120 s of ordinary play: `PathFinder.findPath` still runs on the server thread for 3984 ms (3.32% of
wall time; ~8% of the server's non-idle work). Of that, 3456 ms — **86%** — is one call site,
`MoveToTargetSink.checkExtraStartConditions` → `tryComputePath` → `PathNavigation.createPath`.
Everything else is noise by comparison: `AcquirePoi.findPathToPois` 408 ms, stormiespiders 96 ms,
MCA's archer task 64 ms.

**Why deferring the return value is not an option.** `tryComputePath` is not a request for a path,
it is a question whose answer is consumed in the same tick. Read from the 26.1.2 bytecode:

- a null or unreachable result **sets `CANT_REACH_WALK_TARGET_SINCE`** on the brain, which other
  behaviours read — so a "not ready yet" is indistinguishable from "this target is unreachable";
- it then runs a **second synchronous `createPath`** to a `DefaultRandomPos.getPosTowards` position,
  sending the mob wandering away from the goal it was given.

So returning null early is not a one-tick delay. It is a wrong answer that propagates into brain
memory and produces visibly different mob behaviour. Measured on the same capture, the fallback
branch currently fires almost never (128 ms, no A* beneath it), meaning these searches are
overwhelmingly *succeeding* today. Deferring them would convert ~3.4 s of successful pathfinding per
two minutes into "unreachable" verdicts. It would make the mod worse in a way a benchmark that only
counts off-thread searches would score as an improvement.

**Why speculative pre-computation does not rescue it.** The obvious repair is to compute the path a
tick early and serve it from cache. It does not apply to the case that matters:
`checkExtraStartConditions` accounts for 3456 ms and `tick()` for 12 ms — **99.7% of the cost is the
behaviour starting**, which happens because another behaviour set a *new* `WALK_TARGET` during the
current tick. A target that did not exist a tick ago cannot be pathed a tick ago. Speculation could
only serve `tick()`, which is already 0.01% because vanilla elides its own repaths whenever the
target moved 2 blocks or less (`distSqr(lastTargetPos) > 4.0`).

**Conclusion.** On this pack PathWeaver has taken essentially all of the pathfinding that can be
moved without changing what mobs decide. The residue is synchronous by construction, not by
oversight. Reopening this needs a different lever — an upstream change letting a behaviour express
"ask me again next tick" without it meaning "unreachable" — not more work on this side of the
boundary.

## 11. Rejected: making an in-flight navigation report itself as "in progress"

A review observed that `moveTo` can return `true` while `isDone()` is also `true`, and proposed
closing it. The observation is correct, the mechanism is real, and the fix is worse than the problem
in both available forms.

**The mechanism.** When a mob dispatches with no existing path, `this.path` stays null until the
result installs. `PathNavigation.isDone()` is `path == null || path.isDone()`, so it answers `true`
on a navigation whose `moveTo` just reported success. `RandomStrollGoal.canContinueToUse()` — and
`MeleeAttackGoal`'s and `FollowMobGoal`'s — is `!navigation.isDone() && ...`, so if the worker does
not finish before the goal ticks again, the goal stops, `stop()` fires
`pathweaver$invalidateStoppedRequest`, and the request is cancelled as `NAVIGATION_STOPPED`.

**Why `isDone()` cannot be made to lie.** `PathNavigation.tick()` returns early *because of*
`isDone()`:

```
21: isDone()
25: ifeq 29        // not done -> fall through
28: return         // done -> stop here
29: canUpdatePath()
37: followThePath()
```

and `followThePath()` dereferences `this.path` immediately (`getNextNodePos()` at offset 49, then
`getNextNode()` and `advance()`). Reporting "in progress" with a null path therefore does not defer a
goal — it NPEs the entity tick on the very next tick, for every mob that dispatches without a prior
path. A fabricated placeholder path avoids the NPE by handing mods and vanilla a route the search
never produced, which is a worse lie in a system whose entire claim is that async and sync searches
agree.

**Why the conservative form costs more than it saves.** Restricting dispatch to navigations that
already hold a live path removes the whole first-move case — the largest single category of
navigation requests — to recover a fraction of one. Measured on a live 317-mod client: 2920
dispatched, 2838 installed, and 70 `NAVIGATION_STOPPED`. That is **2.4%**, and it is 2.4% only in the
subset where the worker did not finish inside the dispatch tick.

**And the outcome is not waste.** `NAVIGATION_STOPPED` is already documented as "overwhelmingly the
ordinary case: it arrived, or lost interest", and `/pathweaver status` says so on screen. A mob whose
goal moved on is a mob behaving normally; the search that was cancelled was work that had stopped
being wanted. Spending correctness risk to reclaim it would be paying for the wrong thing.
