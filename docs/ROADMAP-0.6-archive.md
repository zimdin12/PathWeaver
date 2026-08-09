> **Archived.** Superseded by [ROADMAP.md](../ROADMAP.md), which reorganises this into the 0.6-1.0
> ladder. Kept because the measurements and the rejected-approach reasoning here are still the
> evidence behind those decisions.

# PathWeaver 0.6 — roadmap

Working document. Items are added as they are found, with the evidence that justifies them, so the
next release is planned from what is known rather than from what feels overdue.

Nothing here is committed to a date. Items marked **carried** were known before 0.6 opened and were
deliberately not fixed in a hotfix.

---

## The theme

0.5.x proved the mod works and is honest about what it does not do. Three of its four defects were
found *after* publication by reviews of the shipped code, and all three shared a shape: **a guard
that was correct for the case its author had in mind and silent about the case next to it.**
`require = 1` proved a redirect applied to `WalkNodeEvaluator` and said nothing about a subclass. A
test asserted a default against itself. A supersede was right about cancelling and wrong about what
it cancelled.

So 0.6's theme is not features. It is **making the remaining guards general rather than particular**,
and reducing the surface that needs guarding at all.

---

## 0. Make the live-read audit structural *(promoted to top after 0.5.2)*

The project's failure mode across three releases is the same sentence: **the audit was a list, and the
list was short.** `SHARED_PATHFINDING_TARGETS` enumerated worker-reachable classes and stopped one
call short of the chunk read. `WalkNodeEvaluatorMixin` enumerated two call sites and missed the
subclass override. `CONFINED_MOB_READS` enumerates read *names* and missed `getMaxFallDistance`.

Replace the name list with reachability. From every method of every allowlisted evaluator — plus its
`net.minecraft` supertypes and nested classes — walk the call graph inside `net.minecraft` and flag
any path reaching `AttributeInstance.getValue()`, a `RandomSource`, or `SynchedEntityData.get`. Cross
-reference against redirects read from the mixins actually listed in `pathweaver.mixins.json`.

That is roughly 150 lines and it would have found **both** the 0.5.1 blocker and the 0.5.2 one without
being told what to look for. Nothing else on this roadmap prevents a fourth instance of the same bug.

A runtime counterpart is worth having too: an `AttributeInstance` wrapper, or a field-access watch,
that throws when a pathfinding-relevant attribute is read from a `PathWeaverThread` worker, exercised
under load for each of the six families. A static analysis proves the call sites are covered; that
would prove no path reaches one at all.

---

## 1. Make AUDITED usable — the tier decision, answered with measurement

Measured on the live 317-jar pack (`tools/scan_pack.py`, an offline replica of the scanner):

```
21 mods claim a watched pathfinding target -> each one alone denies all six families
   6 have a hand-written audit in PathWeaver
  15 do not                                 <- so AUDITED can never allow anything
```

That is the whole of the 0/187 result, and it will not be fixed by writing more audits: the next
pack has a different fifteen. **Per-mod auditing does not scale, and shipping a tier that is
structurally always-empty is worse than not shipping it.**

The finding that shows the gate is asking the wrong question: **9 of those 15 claim only
`BlockBehaviour$BlockStateBase`** — FerriteCore, ModernFix, Tectonic, Balm, SereneSeasons,
ScalableLux, ExpandAbility, terrain_slabs, vehicleupgrade. Not one is a pathfinding mod. They are
ordinary block/performance mods being treated as pathfinding hazards because the gate asks *"did
anyone touch this class?"* rather than *"does the injected code do anything unsafe on a worker?"*

**1a. Replace hash pinning with runtime property verification.** *(supersedes the old "decide the
fate of the tier")* Every audit currently pins an exact mod version and jar hash, so a Fabric API or
Lithium update silently turns the mod off with no actionable error. The audit classes already read
bytecode with ASM — the pin is applied *before* that work as a gate. Invert it: define the property
that makes each shape safe and verify it against whatever bytes are loaded. Any version that still
satisfies the property passes; one that does not fails loudly and specifically. The hashes become
provenance in the log, not a precondition.

**1b. Narrow watching from class-level to method-level.** A mixin into `BlockStateBase` matters only
if the method it injects is on a path a search actually calls. Resolve the injected method from the
injector annotation and test membership in the set the worker can reach, instead of denying on the
class. On this pack that alone should clear 9 of 15 without weakening anything real.

**1c. One verifier, two directions.** 1a and 1b need the same engine as item 0 — walk a method's call
graph and decide whether it can reach a live-mutable sink. Item 0 points it at vanilla to find
hazards PathWeaver must confine; this points it at foreign mixins to decide whether a claim is
actually dangerous. Build it once.

**Honest ceiling, to be stated in the README rather than discovered by a user.** This cannot be a
proof. It will be sound for the common shapes — an injector that reads only its arguments and
immutable data — and must keep failing closed on reflection, indirect call sites, and anything
reaching a live `Level` or entity. Mods that genuinely read live world state during node evaluation
(stormiespiders is the standing example) *should* keep denying: that is the gate working. The
realistic target is "most families eligible on an ordinary pack", not 187/187.

**Still the maintainer's call, but now an informed one:** if 1a–1c do not get an ordinary pack to mostly-
eligible, the tier should be deleted rather than shipped as decoration.

**1d. `SHARED_PATHFINDING_TARGETS` is knowingly incomplete.** *(carried)* It lists
`PathNavigationRegion` but not `LevelChunk`, `LevelChunkSection`, `PalettedContainer`, `BlockGetter`.
Completing it makes the coarse gate deny even more, which is why it is blocked behind 1b — with
method-level resolution the completion becomes affordable instead of fatal.

---

## 2. Carried defects

**2a. A recompute-originated dispatch leaves the mob pathless for a tick.** *(carried, DESIGN.md §13)*
Vanilla nulls `path` before calling `createPath`, so the interceptor returns null and vanilla then
believes the recompute succeeded, suppressing its own retry for 20 ticks. The obvious fix — returning
the captured pre-null path — was implemented, broke the exact-Swim witness (a non-null `this.path`
re-enables the vanilla reuse short-circuit *and* Feature B elision on a seam where neither may fire),
and was removed. The real fix is to have the registration carry its origin so
`hasDelayedRecomputation` can be re-armed when a recompute-originated request ends without
installing. That is a change to the request record and wants a soak.

**2b. Async-pathed mobs vanish from the vanilla pathfinding debug renderer.** *(carried; the naive
fix is wrong)* A bug hunt confirmed the mechanism from bytecode — `PathNavigation`'s constructor wires
its finder to the debug subscribers, PathWeaver's fresh `PathFinder` keeps the default
`() -> false`, and `findPath` consults it before building any `Path$DebugData`, so paths flicker on
and off with worker-pool load for anyone debugging mob AI. **But do not simply copy `setCaptureDebug`
to the clone**, which is what the previous entry implied: that supplier reads `ServerDebugSubscribers`
and the capture writes `Path.debugData`, both from a worker — a brand new off-thread read of live
server state, added to fix a diagnostic. Snapshot `hasAnySubscriberFor(ENTITY_PATHS)` on the main
thread at dispatch and hand the async finder a constant `BooleanSupplier`, or document it and say so
in `/pathweaver status`.

**2c. `RequestTarget` is allocated above the gates.** *(carried)* `RequestTarget.of(...)` runs a
`Set.copyOf` plus a record allocation plus a `ConcurrentHashMap` lookup before `cfg.enabled`, before
`SafetyGate`, and before `MobOriginGate` — so every pathfind on every install pays it, including with
the mod switched off and on packs where the startup banner has just said it is doing nothing. Only
`pendingDecision` genuinely needs it. Moving it below the gates is easy; doing so without a
measurement behind it is how hot-path reorders go wrong, so it wants a benchmark arm of its own.

**2d. `ARRIVED_STALE` is five causes under one label.** *(carried)* It covers key mismatch,
land-registry re-check failure, age, `NavigationIdentity` mismatch, and position delta — and reports
all of them as "arrived too late". The identity arm is the most likely to fire in practice, so a user
reading a non-zero count will lower `maxInFlight` and see no change. Splitting it is a prerequisite
for item 3.

**2e. No watchdog on a wedged worker.** *(carried)* A third-party evaluator that hangs permanently
burns an admission slot — on a one-worker host that silently ends off-thread pathfinding for the
session — and pins the previous world's `ServerLevel` and chunks across a world switch. Needs a
per-generation deadline and, at minimum, a log line rather than silence.

---

**2g. The recompute seam drops a claim on the fourth exit.** *(new, found by the v0.5 cumulative
review)* With a route already installed and `canUpdatePath()` false, the supersede rolls `targetPos`
back and nothing re-applies the claimed destination — so a mob whose goal was told "yes, going to C"
keeps walking to the B it abandoned until the goal re-issues (10–20 ticks for `MeleeAttackGoal`;
`RandomStrollGoal` calls `moveTo` once and would not re-issue at all). `targetPos` and `path` stay
consistent, so this is not the 0.5.1 pairing and not a regression — 0.5.0 dropped the claim on all
three branches. Re-applying it there IS the 0.5.1 bug, so the condition cannot simply be widened.
The fix is the same one 2a needs: have the registration carry its origin so `hasDelayedRecomputation`
can be re-armed against the claim. Deliberately not rushed into a patch — this seam has now been got
wrong twice by changing it without a fixture that reaches the branch.

**2f. `Mob.getPathfindingMalus()` is the largest live-mob read still crossing the thread boundary.**
*(new, raised by the 0.5.2 review)* The worker reads the live `pathfindingMalus` map while
`AmphibiousNodeEvaluator.prepare` can `Map.put` into it on the main thread — reachable because the
dispatch guard falls back to a *synchronous* search for a mob that already has an async one in
flight. It is an `EnumMap` (array-backed, no rehash), so the realistic worst case is a stale float
rather than structural corruption, and `DESIGN.md` already admits it in prose. That is exactly the
problem: admitted in prose, pinned by no test. Either give it the confined-value treatment the step
height and fall distance now have — capture the maluses the evaluators actually query at dispatch —
or write down why an `EnumMap` slot race is acceptable and close it. Item 0's reachability pass will
surface it anyway, so decide before it does.

---

## 3. Stop producing discards

*(The maintainer's stated priority for the next version. Detail in DESIGN.md §12.)*

A discard is CPU spent for nothing. Measured on a live 317-mod client: 2920 dispatched, 2838
installed, 82 discarded — 2.8%, which is not alarming, but on a machine without a spare core that
spending comes out of the same budget the mod exists to protect. It is the same problem the low-core
recommendation describes, seen from the other end.

Directions, none committed:

- **Do not start a search that is already unwanted.** Most `NAVIGATION_STOPPED` discards are known to
  be pointless before the worker begins, not after. A cheap re-check when a worker picks the task up
  would cancel those without computing them.
- **Admit by predicted latency rather than by depth.** `maxInFlight` bounds the queue, not the age of
  what comes out of it. Admitting only what the pool can finish inside `maxResultAgeTicks` at its
  current rate would convert most `ARRIVED_STALE` into a refusal that costs nothing.
- **Make supersession cheap.** A superseded request runs to completion today; a cooperative cancel
  flag checked between A* iterations would let the worker abandon it.

**Blocked on 2d** — none of these can be evaluated while five causes share one label.

---

## 4. Coverage the mod does not have

**4a. Brain-driven movement stays synchronous.** *(NOT structural — DESIGN.md §10 was wrong and is
rewritten. `start()` does not re-path, so an optimistic answer costs no extra search. The real
blocker is that `Brain.tick` starts and ticks in the same tick and `canStillUse` returns false on a
null path, so the naive version cancels its own request. Feasible; blocked on the origin-carrying
refactor; gated on a `CANT_REACH_WALK_TARGET_SINCE` transition-table game test.)* On a real 317-mod
client, 86% of the remaining server-thread A* is
`MoveToTargetSink.checkExtraStartConditions` — villagers, piglins, axolotls, frogs, allays, the
warden. It is a synchronous question, not a request: a deferred answer sets
`CANT_REACH_WALK_TARGET_SINCE` and sends the mob wandering. Speculation does not rescue it either,
because 99.7% of the cost is the behaviour *starting* on a target another behaviour set the same tick.

Reopening this needs an upstream change letting a behaviour say "ask me again next tick" without it
meaning "unreachable". Worth raising with Fabric/Mojang rather than working around.

**4b. `WallClimberNavigation.moveTo(Entity, double)` bypasses every dispatch seam.** Spiders'
chase route is fully synchronous because that override never calls `super`. Wrapping its `createPath`
call site would be a fifth arm. Small, self-contained, and honest to do because `/pathweaver mobs`
counts those mobs as eligible.

---

## 6. The reachability engine, and what measuring it taught

Prototyped in `src/test/java/dev/pathweaver/reach/`. It walks the call graph from every method of the
six admitted evaluators plus `PathFinder` and reports paths reaching `AttributeInstance.getValue`,
`SynchedEntityData.get` or a `RandomSource`.

**The naive version is useless, not merely imprecise, and that is the finding worth keeping.**
Expanding virtual calls to every implementor lets `BlockGetter.getBlockEntity` reach
`WorldGenRegion`, then `LevelChunk.setBlockEntity`, then block-entity load, explosions and the client
renderer: 14,944 reachable methods, 3,174 classes, 1,220 "hazards", nearly all nonsense. An analysis
that flags everything is worth exactly as much as one that flags nothing.

Two constraints make it tractable, and both are facts about PathWeaver rather than guesses about
Minecraft:

- **`RECEIVER_UNIVERSE`** — a worker is handed a `PathNavigationRegion` and nothing else, because
  dispatch constructs it. Constraining `BlockGetter`/`LevelReader`/`CollisionGetter` to that one
  concrete type, and `Level`/`ServerLevel`/`LevelAccessor` to nothing, gives 1,897 methods and 25
  hazards over 10,208 indexed classes in ~1s.
- **`cutting(edges)`** — subtract the call edges the shipped mixins sever. 25 becomes 12. The contract
  worth asserting is not "vanilla reaches no sink" but "vanilla reaches no sink *after confinement*".

Of the 12 survivors: the `AttributeInstance` count is **zero**, which independently corroborates the
hand inventory; `Creaking.getHomePos` is the read `SafetyGate` already documents and deliberately
accepts; the `getPathfindingMalus` chain is 2f and is worse than 2f describes, because it reaches
`SynchedEntityData` through `getControlledVehicle()` before it ever touches the `EnumMap`; and four
19–22 hop `RandomSource` chains through `getCollisionShape → MovingPistonBlock.getBlockEntity` are
almost certainly residual over-approximation and are **not** being reported as defects until triaged.

**A review found it unsound in three ways; all three are now fixed, and the numbers changed a lot.**
`bodyOf` never consulted interfaces, so default methods were unresolvable and the walk stopped
silently. It recorded `unresolved` only when the *class* was missing, never on the dominant
class-found/method-missing drop — so the reported `unresolved = 0` was an artifact of that gap rather
than a result. And `RECEIVER_UNIVERSE` mapped `LevelReader` onto `PathNavigationRegion`, which
(verified with `javap`) implements only `CollisionGetter` — a type error that deleted every
`LevelReader`-mediated edge.

After the fix: **2,060 reachable methods, 168 unresolved, 45 hazards**, against the previous
1,735 / 0 / 12. The `Level`/`ServerLevel` truncations are kept — following them re-explodes the graph
— but they are now *recorded* rather than silent, which is the difference between a bound and a lie.
The `AttributeInstance` count is still zero, now under a materially wider walk.

Its hard-coded cut list was also one entry wrong — it named `FlyNodeEvaluator#getStart` when the
shipped redirect targets `iteratePathfindingStartNodeCandidatePositions`, which is precisely the
hand-written-list failure the whole analysis exists to end, reproduced inside the analysis itself.
Deriving that list from `pathweaver.mixins.json` is the remaining work before it can gate anything.

**It found no unknown bug.** That is the honest result and it is still the argument for finishing it:
it independently rediscovered every read a human had to reason about, without being told what to look
for, which is precisely what the three hand-written lists failed to do.

Remaining work before it can gate anything: triage the four long chains, decide the `SynchedEntityData`
policy (it is a pure read of a non-volatile field — staleness, not corruption, unlike the attribute
RMW), derive the cut set from `pathweaver.mixins.json` instead of hard-coding it, and only then wire
it to `CONFINED_MOB_READS` and to the foreign-claim decision in item 1.

---

## 5. Test and process debt

**5a. Invert the confined-read list from allow to deny.** *(sharpened by the 0.5.2 review)*
`CONFINED_MOB_READS` currently gates BOTH sides of the coverage comparison, so a one-line edit to the
test's own literal can uncover a real hazard — and 0.5.3 had to add a pin for `getRandom` precisely
because deleting it from the list and from its redirect shrank both sides symmetrically and stayed
green. Collect *every* live-entity-receiver call the evaluators make, and require each to be either
redirected or on an explicit `KNOWN_SAFE` list carrying a one-line reason. That is the same
default-deny principle `SafetyGate` already applies to evaluator classes, applied to reads.

**5a-ii. Widen the receiver filter beyond `Mob`.** *(DONE in 0.6.0 — kept for the record)* The declared side matches only
`net/minecraft/world/entity/Mob` receivers, so a hazard reached through any other type is invisible —
`Creaking$HomeNodeEvaluator` calls `Creaking.getHomePos()` (`SynchedEntityData`-backed) and the
contract says nothing about it.

**5b. A benchmark arm per shipped default.** The tolerance sweep only happened because a reviewer
noticed the maintainer's live config differed from the shipped one. Every config value the mod ships
should have a measurement, or an explicit note that it does not.

**5c. Publish-time verification.** 0.5.0 shipped with a blocker that a review found within the hour.
A pre-publish checklist that runs the full harness set against the *exact artifact being uploaded*,
and diffs the Modrinth page against the README, would have caught the stale page copy too.

---

## Explicitly not doing

- **A private immutable snapshot engine.** Cancelled in 0.2.2 and still cancelled (DESIGN.md §8).
- **Making an in-flight navigation report itself as in-progress.** Rejected with measurement;
  `followThePath()` dereferences `this.path` immediately, so it NPEs the entity tick (DESIGN.md §11).
- **Async `MoveToTargetSink`** in its naive form — see 4a.
