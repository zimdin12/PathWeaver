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

## 1. Reduce, don't add

The strongest external criticism of 0.5.x, from an independent senior review, is that the safety
machinery is large and mostly inert in the shipped configuration:

> "`AUDITED` is measured to leave 0 of 187 mob types eligible on a 222-mod pack. `UNSAFE` is the
> default. So `compatibilityTier` has exactly one useful setting and one setting that turns the mod
> off — which is what `enabled=false` already does."

That is a fair reading and it deserves a real decision rather than a defence.

**1a. Decide the fate of the tier system.** Either delete `compatibilityTier` and keep `enabled` +
`trustedMods`, or keep the tier and accept that five per-mod audit classes
(`FabricSwimCompatibility`, `AuditedMixinCompatibility`, `FarmersDelightStoveCompatibility`,
`DiagonalBlocksCompatibility`, `LithiumPathfindingCompatibility` — roughly 2000 lines pinned to
artifact hashes) are permanent maintenance debt that goes stale whenever those mods update.

Argument for keeping: `trustedMods` only means something because the scan still runs, and the scan
only means something because the audits define what "audited" is. Argument for deleting: the shipped
default consults none of it.

**Not a code decision — a product one. Steven's call.**

**1b. `SHARED_PATHFINDING_TARGETS` is knowingly incomplete.** *(carried)* It lists
`PathNavigationRegion` but not the rest of the block read — `LevelChunk`, `LevelChunkSection`,
`PalettedContainer`, `BlockGetter`. On an ordinary performance pack that is not hypothetical: Lithium
`@Overwrite`s `LevelChunk.getBlockState`, Lithium and FerriteCore both remove `PalettedContainer`'s
threading detector, and ServerCore mixes into `BlockGetter` through a config PathWeaver already pins
by hash — so the audit read the file and looked past the target.

Measured: adding those classes makes `AUDITED` deny every family on any pack containing Lithium. That
is the gate working correctly, and it is why this is a product decision rather than a bug fix. It
interacts directly with 1a — if the tier goes, this question goes with it.

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

**2b. Async-pathed mobs vanish from the vanilla pathfinding debug renderer.** *(carried)*
`PathNavigation`'s constructor wires its own finder to the debug subscribers via `setCaptureDebug`;
dispatch builds a fresh `PathFinder` and never copies that supplier, so `findPath` attaches no
`Path$DebugData`. Anyone debugging mob AI on a server running PathWeaver sees paths for
synchronously-resolved mobs and nothing for async ones, with no explanation. Fix: an `@Accessor` for
`captureDebug` and copy it to the clone.

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

## 3. Stop producing discards

*(Steven's stated priority for the next version. Detail in DESIGN.md §12.)*

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

**4a. Brain-driven movement stays synchronous.** *(structural, DESIGN.md §10)* On a real 317-mod
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

## 5. Test and process debt

**5a. Make the remaining hand-maintained sets self-checking.** `LiveMobReadCoverageContractTest`'s
`MIXED_IN` set is maintained by hand and will rot the same way `SHARED_PATHFINDING_TARGETS` did.
Derive it from the mixin config rather than restating it.

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
