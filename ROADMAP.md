# PathWeaver — road to 1.0

The goal this serves: **a pack that can run very large numbers of mobs without the server thread
falling over.** PathWeaver is one mod in that pack. Every item below is judged against four tests,
in this order:

1. **Does it have value?** A measurable improvement, not a tidier internal.
2. **Is it safe?** No corruption, no races, fails closed.
3. **Does it work for everyone?** Benefits the default configuration on an ordinary pack, not a
   minority who opt into something.
4. **Is it worth updating a modpack for?**

An item that fails 3 is not necessarily dropped, but it does not headline a release.

---

## Release and tag rules

Learned the hard way over 0.5.0–0.5.3, where four consecutive releases each shipped a defect a review
found within a day:

- **A tag is created only at publish**, on the exact commit whose jar was verified. No tags on
  work in progress.
- **The published jar must be byte-identical to a locally verified build** — check the hash the
  registry serves against the hash that was tested.
- **A release is gated on:** the full unit suite with zero skips, all four server harnesses, the
  client harness, an independent review that comes back clean, and — for anything touching the
  hot path — a benchmark showing no regression.
- **Mutations, not readings.** A fix is not verified until the mutation that reintroduces the bug
  has been compiled and observed to fail the suite. Five review rounds on 0.6 found that reading
  code missed what executing mutations caught.
- **Version numbers describe content.** A release with no new capability is a patch, whatever work
  went into it. 0.5.4 was folded into 0.6 rather than published for exactly this reason.

---

## 0.6 — SHIPPED: spiders, and diagnostics that agree with each other

The goal below — *an ordinary pack gets most of its mob types pathing off-thread with the safety checks
on* — was **not** reached, and moves to 0.6.1. What 0.6.0 did ship: wall-climber chases dispatch, and
every reporting site answers through the predicate dispatch actually evaluates.

`compatibilityTier=AUDITED` still leaves **0 of 187 mob types eligible** on a real 221-jar server pack,
so the shipped default remains `UNSAFE` — the mod's own safety mechanism is unusable. Measured cause
(`tools/scan_pack.py`, which over-approximates): 20 mods claim a watched pathfinding target, any one of
which denies every family; PathWeaver has an audit for 6; the other 14 have none, and **9 of those 14
touch only `BlockBehaviour$BlockStateBase`** — FerriteCore, ModernFix, Tectonic, Balm and friends, none
of which are pathfinding mods. PathWeaver's own scanner names nine blockers, a different nine.

The gate is asking the wrong question: *"did anyone touch this class?"* instead of *"does the injected
code do anything unsafe on a worker?"*

### What was attempted and reverted, and exactly why

Both 6a and 6b were built, measured on the real 221-jar pack, reviewed, and **reverted**. The review
executed its attacks rather than arguing them, and found four blockers. They are recorded here so the
next attempt starts from them instead of rediscovering them:

1. **The reachable-method walk matched the wrong owner.** It compared against
   `BlockBehaviour$BlockStateBase`, but javac emits those calls with the owner of the *static receiver
   type*, `BlockState`. So the set missed `isPathfindable`, `isAir`, `getFluidState` and `getValue`
   entirely — every real call a search makes. `getCollisionShape` appeared only because
   `BlockStateBase` happens to call it on itself. A mod injecting `isPathfindable` — the most common
   reason to touch block state — was cleared, releasing all six families. This project's own
   `FabricInteractionCompatibility` already uses the `BlockState` owner for exactly this reason.
2. **Dropping the version check was a no-op.** The version stayed part of `AuditKey`, so the audit
   emitted evidence keyed on its pinned constant while the claim looked itself up with the runtime
   version. The two never matched and the denial was unchanged. The symptom was visible in the
   real-pack run — it logged "Verified exact audited compatibility tuple for 'lithium'" while lithium
   stayed in the blocker list — and was not chased.
3. **MixinExtras annotations were skipped, not failed closed.** The "unmodelled annotation" guard only
   fired for `org.spongepowered.*`, so every `com.llamalad7.*` injector fell through silently,
   including `@WrapMethod`, the most powerful one MixinExtras has. `@Inject(target = @Desc(...))` was
   invisible for the same reason.
4. **An unannotated method in a mixin is an implicit `@Overwrite`** and never entered the annotation
   visitor at all.

Plus: dropping the whole-jar hash unpinned classes the audits' own reasoning names —
`StarCollisionBlock`, Lithium's `BlockInfoInitializer` and `BlockStateFlagHolder`, ServerCore's
`BlockGetterMixin` — leaving a javadoc asserting a guarantee the code no longer provided.

**For the next attempt:** fix 6b first and alone — remove `version` from `AuditKey` AND add per-class
hashes for the four classes above. That delivers the whole user-visible benefit (updates stop silently
disabling the mod) with no new analysis. Only then consider 6a, and only with hostile-mixin fixtures
in the test suite: five mutations survived the suite as written, including deleting every seed of the
walk.

- **6a. Narrow claims from class level to method level.** A mixin into `BlockStateBase` matters only
  if the method it injects is on a path a search actually calls. Should clear most of those 9.
- **6b. Replace hash pinning with runtime property verification.** Every audit currently pins an exact
  mod version and jar hash, so a Fabric API or Lithium update silently switches the mod off with no
  actionable error. Verify the property against whatever bytes are loaded instead; the hashes become
  provenance in the log, not a precondition.
- **6c. Ship the tier that results as the default**, if and only if it reaches most families on an
  ordinary pack. If it cannot, delete the tier rather than ship decoration.

**Shipped in 0.6.0:** spiders dispatch (`WallClimberNavigation` override), and the startup banner,
`/pathweaver status` and `/pathweaver mobs` no longer contradict each other or invent causes.

### 0.6.1 — carried over

- **6b, then 6a**, as scoped above. This is the whole of the "most mobs, safely" goal.
- **Coverage gaps found by review and not blocking a release.** `bodyCalls` cannot tell an invoked-
  and-used call from an invoked-and-discarded one, and `pushesConstantInto` is both over- and
  under-strict — both are bytecode contracts that pass for the wrong reason. Five `DispatchStage`
  assignment-instant mutations survive the suite, two of which leak an `inFlight` registration.
- **Spiders still do not dispatch on a pack that replaces their `PathFinder`.** stormiespiders supplies
  an `AdvancedPathFinder`, and dispatch declines any `PathFinder` subclass before the evaluator
  matters. The 0.6.0 override is what makes them dispatchable at all; admitting a foreign `PathFinder`
  is a separate, larger question about what `createPath` is allowed to be.

---

## 0.7 — Requests carry their origin

Plumbing with a real payoff, and a prerequisite for everything after it.

A result currently arrives knowing only which navigation asked. It must arrive knowing **why**, so
reconciliation can do the right thing per origin. That single change closes:

- **DESIGN.md §13** — a recompute-originated dispatch leaves the mob pathless for a tick, and vanilla
  suppresses its own retry for 20 ticks because it believes the recompute succeeded.
- **The dropped claim on the fourth `recomputePath` exit** — with a route installed and vanilla
  declining to recompute, the claimed destination is lost and the mob walks to the one it abandoned.
- **Discard elimination.** 2.8% today (82 of 2920 measured). Small at ordinary counts; not small at
  the counts this pack is aiming for. Needs `ARRIVED_STALE` split into its five real causes first,
  which is the same change.

---

## 0.8 — Crowd pathfinding

**Where "many mobs" starts paying, and where the equivalence promise changes.**

Fifty zombies converging on one player currently run fifty independent A\* searches over nearly
identical terrain. Sharing that work is the largest remaining lever.

**This is a deliberate change of promise, and must be labelled as one.** PathWeaver's claim to date is
*identical results, just off the main thread* — that is what the safety story rests on. Shared routing
gives a mob a **good** path rather than **its own** path: crowds move in lanes instead of each picking
an individual line. Not unsafe — no races, all main-thread decisions — but visibly different.

So: config-gated, off by default, documented as a behaviour change. For a many-mobs pack it is
probably the right trade; it must be chosen, not inherited on update.

---

## 0.9 — Brain-driven mobs

Villagers, piglins, axolotls, allays, the warden. **86% of the A\* still on the server thread** is
`MoveToTargetSink.checkExtraStartConditions`, worth ~1.4 ms/tick (~5–7% of tick time).

Feasible — `DESIGN.md` §10 rejected an idea it never actually evaluated, and is corrected. Blocked on
0.7. Gated on a game test asserting the whole `CANT_REACH_WALK_TARGET_SINCE` transition table: if that
test cannot be written, the feature does not ship. The failure mode is a villager **permanently losing
its workstation or bed**, silently, and it is guarded by one line of reconciliation logic.

**Open question, to decide at 0.9 and not before:** ship this inside PathWeaver, or as a separate mod?
Separate keeps PathWeaver's *"the search only reads"* claim clean, since this one needs a weaker second
claim — *"we reproduce vanilla's state machine faithfully"*. Those are different promises.

**Not this, and not later:** running whole brain *ticks* off-thread. Behaviour ticks write memories,
claim POIs, farm, breed and trade. The blocker is not conflict detection — a region version counter
handles that cheaply — it is that discarding a speculative tick requires **buffering every write**, and
the write surface is unbounded because mods add behaviours. A missed write is silent corruption.

---

## 1.0 — Flow fields

One vector field per region per destination; mobs read it instead of searching. The technique that
actually scales past a few hundred units. Same relaxed-equivalence trade as 0.8, at larger scale.

**Gate before starting:** a mob-scaling profile. PathWeaver has already taken ~25% off mean tick, and
at 200/500/1000 mobs the binding constraint may no longer be pathfinding — it could be entity ticking,
collision, or goal evaluation. Two investigations in this project (provider-gating, entity-scan) ended
in *"no safe crack exists"* **after** the design work. Measure first.

---

## Standing rules

- Adding a mod to the pack: research alternatives, assess fit, warn proactively before installing.
- Original jars move to `mods/.original/` before replacing.
- Prefer fixing over disabling.
- Spark profiles are saved locally; uploading publishes pack composition to a third party.
