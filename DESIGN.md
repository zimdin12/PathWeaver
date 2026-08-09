# PathWeaver design status

**Sections 1–7 describe 0.2.x and are retained as a record of that design, not as current
documentation.** They still say only Walk and Swim are candidates and that flying and amphibious are
ineligible; that stopped being true in 0.4.0, when those evaluators' live-mob writes were found to
live only in `prepare()`/`done()` and were hoisted onto the main thread. All six concrete vanilla
evaluators dispatch today. Sections 8 onward are current.

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

The navigation-subclass boundary is deliberate: `WallClimberNavigation` overrides `moveTo(Entity, double)` and so bypasses everything the base mixin attaches to that method. **As of 0.6 it is covered** by `WallClimberNavigationMixin`, which re-attaches all three: the movement marker, the request depth around the override's own `createPath` call, and the accepted-dispatch result. Before that, spiders resolved every chase path on the server thread while `/pathweaver mobs` counted them eligible. Its inherited coordinate route always passed through already-covered `GroundPathNavigation`. `WaterBoundPathNavigation` inherits the covered base path-creation seam. (This sentence used to end "while Flying and Amphibious evaluators are ineligible" — that has been false since 0.4.0, which admitted both. A reader auditing the threading story from this file would have concluded that axolotls, turtles, drowned, frogs and every flying mob were synchronous when they are the default async path. §9 already cost a reviewer a correct verdict by being stale; this is the same failure in the same file.)

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

**Superseded text warning.** This section used to carry a 0.2.2-era micro-benchmark reporting mean
MSPT of 2.927 ms OFF against 3.012 ms ON — i.e. the mod costing more than it saved. That number was
about the *rejected private snapshot engine* of §8, was explicitly labelled "not a user-real
benchmark" with "noisy pairs", and measured a load so small that the difference was inside its own
noise. It sat here unchanged for three releases, and in review a senior reader took it as the
project's performance evidence and concluded from it that the mod does not pay off. That is a fair
reading of what was written, and the fault is the document's.

Measured on the shipped 0.5.0 jar. 1024 zombies in a walled maze, all retargeted every 6 ticks, four
paired runs interleaved and order-reversed so machine drift cannot fake a result:

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 89.3–93.7 ms | **50.0–58.2 ms** |
| Tick interval, p99 | 827–893 ms | **371–492 ms** |
| Effective TPS | 10.7–11.2 | **17.2–20.0** |

Mean **−40.9%**, p99 **−49.8%**. Compared against the same benchmark on 0.4.0 by ratio rather than raw
figures — the host was ~7% slower on the later day, visible in the sync arm — 0.4.0 achieved −32.5% /
−40.1%, so 0.5.0 is at least as good and measures better on both axes. With n=2 per arm and the
0.5.0 async runs themselves spanning 50.0–58.2 ms, "no regression" is the confident claim and
"improved" is the tentative one.

Profiled with spark on an identical drive, only the master switch differing:

| | Off | On |
|---|---|---|
| Server-thread time in `PathFinder.findPath` | **52.83%** of busy time | **0.58%** |
| Server-thread busy time | 5,224 ms | 2,780 ms |
| PathWeaver's own main-thread cost | — | 24 ms per 45 s |

Two captures from a real 510-mod client two minutes apart, only the master switch differing, agree in
direction: TPS 18.90 → 20.00, mean tick 27.29 → 20.38 ms, blocking `findPath` −33.4%. Background CPU
differed between those two captures (57.1% vs 43.0%) in a way that favours the ON arm, so treat those
magnitudes as approximate.

**0.5.1 changed these materially.** The same benchmark on the 0.5.1 jar: async mean 50.00 ms in both
runs, p99 150.8 and 152.4 ms, TPS 20.00 in both, install ratio 99.0%. Against 0.5.0's async arm
(mean 54.08, p99 431.8, install 76-80%) that is a threefold p99 improvement, and it is attributable to
a single line — a contended `AtomicLong` in the land-registry lookup becoming a `LongAdder`. Isolated
by rebuilding 0.5.1 with only that revert: dispatched 56,920 vs 95,865, admission refused 68,454 vs
11,190, install 83.1% vs 99.0%, p99 380 vs 151. The sync arms in that sweep spanned 82.9-131.6 ms, so
compare the async arms and the isolated control rather than the sweep's headline ratio.

**What this does and does not establish.** It establishes that under pathfinding-heavy load on a
many-core host the mod removes most A* from the server thread and cuts tail latency substantially. It
does not establish a benefit on a small host — see `PathWeaverRuntime.lowCoreAdvice`, which
recommends turning the mod off at two cores or fewer — nor on a pack where little pathfinding
happens, where the honest expectation is no measurable change either way.

## 10. Async `MoveToTargetSink` — reopened, and the stated reason for rejecting it was wrong

This section previously said the idea was structurally impossible. A bytecode investigation in 0.5.4
showed the argument it made was about a different idea, so it is rewritten rather than amended.

**What it used to say:** deferring the answer to `checkExtraStartConditions` makes the behaviour set
`CANT_REACH_WALK_TARGET_SINCE` and send the mob wandering; and speculation cannot help because 99.7%
of the cost is the behaviour starting on a target set the same tick. Both are true — of *deferring*.
They say nothing about *answering optimistically*, i.e. returning true immediately and dispatching.

**The kill condition it should have tested, and the answer.** If `start()` re-pathed, an optimistic
answer would cost a second search and the idea would be dead. It does not: `start` reads the `path`
field `tryComputePath` already wrote (offsets 7-11 and 18-22) and calls `moveTo(Path, double)`. It
never calls `createPath`. So the optimistic answer converts one synchronous search into one
asynchronous search — it adds nothing.

**The real blocker, which this section never identified.** `Brain.tick` runs
`startEachNonRunningBehavior` and `tickEachRunningBehavior` in the *same tick*, and `canStillUse`
begins with `if (this.path == null) return false` (offsets 0-15). A behaviour told "yes, reachable"
with no path yet is therefore started and torn down within that tick, before any worker can return —
and `stop()` calls `navigation.stop()`, which PathWeaver's own `pathweaver$invalidateStoppedRequest`
turns into a cancellation of the request just dispatched. **The naive version cancels its own search
every time.** Making it work needs a second injection that keeps `canStillUse` true while a request
is pending, plus a reconciliation path that knows about brain memories.

**Measured payoff.** `MoveToTargetSink.checkExtraStartConditions` is 3456 ms of a 120 s capture, i.e.
~1.44 ms per tick averaged, ~5-7% of tick time. Real, not transformative, and less than that in
practice once the main-thread prologue and lost registration races are subtracted.

**The risk, and why this is not being rushed.** `CANT_REACH_WALK_TARGET_SINCE` left set for 1200
ticks makes `SetWalkTargetFromBlockMemory` call `villager.releasePoi(...)` — the villager
**permanently loses its workstation or bed**, silently, with no crash and no log line. That is the
worst-shaped bug this mod could ship, and it would be guarded by one line of reconciliation logic.

**Blocked on:** the request must carry its origin before a brain-origin result can reconcile brain
memories. That is the same refactor §13 and roadmap 2a/2g need and have deliberately deferred.
Building this feature first would mean doing that refactor under feature pressure, which is exactly
how 0.5.1 and 0.5.2 each shipped a half-covered fix to the recompute seam.

**Prerequisite for shipping it:** a game test asserting the whole
`CANT_REACH_WALK_TARGET_SINCE` transition table — erased on `canReach`, set once with the dispatch
game time when unreachable, erased on arrival, never surviving a later successful search. If that
test cannot be written, the feature must not ship.


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


## 12. Direction for the next version: stop producing discards

Recorded as intent, not as a design. 0.5.0's priority is stability; this is what comes after it.

A discard is a finished search that nobody wanted by the time it landed — the mob stopped, its target
moved, the world changed under it, or the pool admitted more than it could deliver in time. Measured
on a live 317-mod client: 2920 dispatched, 2838 installed, 82 discarded. That is a 2.8% waste rate and
it is not the problem; the problem is that discards are *CPU spent for nothing*, and on a machine
without a spare core that spending comes out of the same budget the mod exists to protect. It is also
the mechanism behind the low-core recommendation in `PathWeaverRuntime.lowCoreAdvice`: the fewer cores
there are, the more a wasted search costs relative to what it saves.

Directions worth exploring, none committed:

- **Do not start a search that is already unwanted.** Most `NAVIGATION_STOPPED` discards are known to
  be pointless before the worker begins, not after — the goal that asked has already moved on. A
  cheap pre-flight re-check at the moment a worker picks the task up, rather than only at install,
  would cancel those without computing them.
- **Admit by predicted latency rather than by depth.** `maxInFlight` bounds the queue, not the age of
  what comes out of it. Admitting only what the pool can finish inside `maxResultAgeTicks` at its
  current rate would convert most `ARRIVED_STALE` into a refusal that costs nothing.
- **Make supersession cheap.** A superseded request runs to completion today. A cooperative
  cancellation flag checked between A* iterations would let the worker abandon it.

Any of these changes what the counters mean, so they need the accounting rework that landed in 0.5.0
to stay honest, and they need the discard split described under finding `ARRIVED_STALE` — five causes
currently share one label, and none of the above can be evaluated until they are told apart.

## 13. Known gap: a recompute-originated dispatch leaves the mob pathless for a tick

Two independent reviews found this from different angles, and the obvious fix is worse than the gap,
so it is written down rather than patched.

**The gap.** Vanilla's `recomputePath` sets `this.path = null` immediately before calling
`createPath`. When PathWeaver intercepts that call and dispatches asynchronously, it returns
`this.path` — which is already null — so `recomputePath` assigns null, stamps `timeLastRecompute`
and clears `hasDelayedRecomputation`. Vanilla now believes the recompute succeeded and will not retry
for 20 ticks, on a mob that has no path at all. Vanilla in that same situation still had its old
path. The mob visibly stops until the result drains at end of tick, and if the result is superseded,
arrives stale, or returns no path, it stands still for up to a second.

This is distinct from §11, which is about a mob dispatching with *no* existing path. This one is a
mob that *had* a path and lost it, and it is the more visible of the two.

**Why the obvious fix is wrong.** Capturing the pre-null path at the `canUpdatePath()` injection
point and returning that instead was implemented and tested. It fixes the stall and breaks something
else: a non-null `this.path` re-enables both the vanilla reuse short-circuit
(`path != null && !path.isDone() && targets.contains(targetPos)`) and Feature B's tolerance elision
(guarded on `this.path != null`) on a seam where neither may fire. The exact-Swim witness in
`PathNavigationRoutingGameTest` failed on it — 3 of 10 requests discarded rather than installed. The
correct-looking change silently altered which searches ran.

**What was fixed instead.** The narrower half of the same problem: the supersede in
`pathweaver$supersedeBeforeRecomputeGuard` rolled the optimistic `targetPos` back to its pre-dispatch
value, and vanilla reads `targetPos` two bytecodes later to decide where to recompute — so the mob
re-pathed to a destination it had already abandoned, discarding a move whose `moveTo` had returned
`true`. The claimed destination is now preserved across the supersede, and the routing game test
asserts it.

**The direction for a real fix** is to re-arm `hasDelayedRecomputation` when a recompute-originated
request reaches a terminal state without installing, so vanilla's own retry takes over instead of
waiting out its 20-tick cooldown. That needs the registration to carry its origin, which is a change
to the request record rather than a one-line patch, and it belongs in a release that can be soaked
rather than a hotfix.
