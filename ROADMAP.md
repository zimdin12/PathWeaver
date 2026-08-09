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

## 0.6.0 — SHIPPED (2026-08-09)

One capability and a lot of honesty work.

- **Spiders dispatch.** `WallClimberNavigation` overrides `moveTo(Entity, double)` without calling
  `super`, so the dispatch marker never ran for it. Third instance of one mixin mistake.
- **Every reporting site answers through the predicate dispatch evaluates.** The banner could
  announce "ACTIVE: all 6 families" while five were refused every tick.
- **Setup failures before registration are counted and logged** instead of vanishing.
- **The startup log says why `UNSAFE` is the default**, as a trade rather than a safety claim.

**What it did NOT do:** the stated goal — *an ordinary pack gets most of its mob types pathing
off-thread with the checks on* — was not reached. `AUDITED` is still **0 of 187** on a real 221-jar
pack. See the verdict below.

Measured on the shipping jar: 184 of 187 eligible at the default, 755 dispatched / 749 installed /
6 discarded, all six families node-for-node identical to a synchronous oracle, no PathWeaver
exception. Eleven review rounds.

---

## The `AUDITED` verdict: stop building it

**Decision, 2026-08-09.** The tier is not being fixed. The evidence is not ambiguous:

- **0 of 187 since 0.3.0.** Five releases, zero movement.
- **It asks the wrong question.** *"Did any mod touch this class?"* On a real modpack the answer is
  always yes — 20 mods on the reference pack claim a watched target, nine of which the scanner names.
  A question with no useful answer on the packs the mod is for is not a safety mechanism.
- **6a was built and was wrong four independent ways** (below), one of which cleared `isPathfindable`
  — the most common reason a mod touches block state. Getting it right means whole-program
  reachability analysis over arbitrary mod bytecode. That is a research project, not a release.
- **Even a perfect scan would not deliver what a user wants.** It is a proxy for the real question,
  and the proxy is what fails.

`AUDITED` stays in the code as a diagnostic and as the conservative escape hatch for anyone who wants
it. It is not the thing that will make PathWeaver safe on an arbitrary pack. 6a, 6b and 6c are
**withdrawn**, not deferred.

### The four blockers, kept because they are expensive to rediscover

1. **The reachable-method walk matched the wrong owner.** It compared against
   `BlockBehaviour$BlockStateBase`, but javac emits those calls with the owner of the *static receiver
   type*, `BlockState`. The set missed `isPathfindable`, `isAir`, `getFluidState` and `getValue`
   entirely. `getCollisionShape` appeared only because `BlockStateBase` happens to call it on itself.
2. **Dropping the version check was a no-op.** The version stayed part of `AuditKey`, so the audit
   emitted evidence keyed on its pinned constant while the claim looked itself up with the runtime
   version. The symptom was in the real-pack log — "Verified exact audited compatibility tuple for
   'lithium'" while lithium stayed in the blocker list — and was not chased.
3. **MixinExtras annotations were skipped, not failed closed.** The unmodelled-annotation guard only
   fired for `org.spongepowered.*`, so every `com.llamalad7.*` injector fell through silently,
   including `@WrapMethod`.
4. **An unannotated method in a mixin is an implicit `@Overwrite`** and never entered the annotation
   visitor at all.

---

## 0.6.1 — Detect unsafety instead of predicting it

**The replacement for `AUDITED`, and it is a better mod for it.**

Today a worker search that throws is counted `SEARCH_FAILED`, discarded, and the mob paths
synchronously for that tick. Correct — and nothing learns from it. There is no circuit breaker in the
codebase.

Invert the premise: **stop proving safety in advance; observe it.** A throwable on a worker trips a
per-family counter. Past a threshold, that family is disabled for the rest of the session, in-flight
requests are dropped, and the log names the family, the exception and the mod whose classes were on
the stack. Falling back to vanilla is always safe, so the breaker cannot itself destabilise anything.

Why this beats the scan on all four tests at the top of this file:

1. **Value** — it protects against the thing that actually goes wrong, including from mods that did
   not exist when the audit was written.
2. **Safe** — the failure action is "be vanilla".
3. **Everyone** — no per-mod audit, no allowlist, works on the default configuration of any pack.
4. **Worth updating for** — "will this corrupt my world?" is the first question every prospective
   user asks, and this improves the answer without being it. A breaker sees **throws**. The
   corruption a user actually fears comes from a silent torn read that returns the wrong block and
   never throws, and nothing here catches that. What ships is a smoke detector plus, more usefully,
   **attribution**: when something does go wrong, the log names the family, the exception and — when
   it can — the mod. Claiming more than that on the Modrinth page would be the overclaiming this
   project keeps having to correct.

Also in 0.6.1:

- **Give `/pathweaver status` and `/pathweaver mobs` a testable seam.** Both are
  `private static void (CommandSourceStack)` with nothing extracted but `scanSummary` and
  `ScanCounts`, and the eleventh review compiled eight mutations inside them that no test sees —
  including reading `moddedMobAsyncAllowed()` and then using `true` anyway, which is the hole round
  ten closed for the land registry and left open one method over. A `List<String>` producer per
  command kills eight at once. None changes dispatch; all can make the mod misreport itself.
- **Coverage gaps recorded by review.** `bodyCalls` cannot tell an invoked-and-used call from an
  invoked-and-discarded one, and `pushesConstantInto` is both over- and under-strict. Five
  `DispatchStage` assignment-instant mutations survive, two of which leak an `inFlight` registration.
- **Spiders still do not dispatch on a pack that replaces their `PathFinder`.** stormiespiders
  supplies `AdvancedPathFinder`; dispatch declines any `PathFinder` subclass before the evaluator
  matters. Admitting a foreign `PathFinder` is a separate and much larger question.

---

## 0.7 — Requests carry their origin

Plumbing with a real payoff, and a prerequisite for everything after it.

A result currently arrives knowing only which navigation asked. It must arrive knowing **why**, so
reconciliation can do the right thing per origin. That single change closes:

- **DESIGN.md §13** — a recompute-originated dispatch leaves the mob pathless for a tick, and vanilla
  suppresses its own retry for 20 ticks because it believes the recompute succeeded.
- **The dropped claim on the fourth `recomputePath` exit** — with a route installed and vanilla
  declining to recompute, the claimed destination is lost and the mob walks to the one it abandoned.
- **Discard elimination.** **0.8%** on the 0.6.0 shipping jar (6 of 755) — an earlier draft of this
  line said 2.8% from a 0.5.x capture, and quoting the worse number to justify the work would be
  exactly the overclaiming this project keeps correcting. At 0.8% this is no longer a headline
  reason to do 0.7; the two defects above are. Still wants `ARRIVED_STALE` split into its real
  causes, which is the same change.

---

## 0.8 — Brain-driven mobs: the city release

Villagers, piglins, axolotls, allays, the warden. **86% of the A\* still on the server thread** is
`MoveToTargetSink.checkExtraStartConditions`, worth ~1.4 ms/tick (~5–7% of tick time) on an ordinary
world.

**Moved ahead of crowd pathfinding, deliberately.** The thing people build modpacks to do is put up a
city, and a city is hundreds of villagers. Every one of them paths through the brain, so today every
one of them paths on the server thread — PathWeaver is close to useless in exactly the build a player
is proudest of. The ~1.4 ms/tick above was measured on a normal world; it scales with villager count,
which is the whole point. Crowds of hostile mobs converging on a player is a mob farm, and that is a
narrower want.

Feasible — `DESIGN.md` §10 rejected an idea it never actually evaluated, and is corrected. Blocked on
0.7, which must land first. Gated on a game test asserting the whole `CANT_REACH_WALK_TARGET_SINCE` transition table: if that
test cannot be written, the feature does not ship. The failure mode is a villager **permanently losing
its workstation or bed**, silently, and it is guarded by one line of reconciliation logic.

**Open question, to decide at 0.8 and not before:** ship this inside PathWeaver, or as a separate mod?
Separate keeps PathWeaver's *"the search only reads"* claim clean, since this one needs a weaker second
claim — *"we reproduce vanilla's state machine faithfully"*. Those are different promises.

**Not this, and not later:** running whole brain *ticks* off-thread. Behaviour ticks write memories,
claim POIs, farm, breed and trade. The blocker is not conflict detection — a region version counter
handles that cheaply — it is that discarding a speculative tick requires **buffering every write**, and
the write surface is unbounded because mods add behaviours. A missed write is silent corruption.

---

## 0.9 — Crowd pathfinding

**Where "many mobs" starts paying, and where the equivalence promise changes.** Demoted below the
city release: it helps a narrower case and it is the first item that stops promising identical
results.

Fifty zombies converging on one player currently run fifty independent A\* searches over nearly
identical terrain. Sharing that work is the largest remaining lever.

**This is a deliberate change of promise, and must be labelled as one.** PathWeaver's claim to date is
*identical results, just off the main thread* — that is what the safety story rests on. Shared routing
gives a mob a **good** path rather than **its own** path: crowds move in lanes instead of each picking
an individual line. Not unsafe — no races, all main-thread decisions — but visibly different.

So: config-gated, off by default, documented as a behaviour change. For a many-mobs pack it is
probably the right trade; it must be chosen, not inherited on update.

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
