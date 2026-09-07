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
  has been compiled and observed to fail the suite. Seventeen review rounds across the 0.6 line —
  eleven for 0.6.0, six for 0.6.1 — found that reading code missed what executing mutations caught,
  every single time.
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

- **0 of 187 since 0.3.0** on the reference 221-jar pack with `trustedMods` empty. Five releases,
  zero movement. On a lean pack the tier admits everything unaided — the problem is not that it never
  works, it is that it never works on the packs this mod exists for.
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

## 0.6.1 — SHIPPED (2026-08-15): detect unsafety instead of predicting it

**Also the first multi-version release: 26.1.1, 26.1.2 and 26.2.** 26.1.1 and 26.1.2 are served by
one file (every pathfinding and navigation class is byte-identical between them); 26.2 is built from
the `mc-26.2` branch and needs no code change, only a retarget plus two test-source workarounds for
vanilla churn (`EntityType` lost ~170 constants; `Minecraft.screen` moved to a private `Gui.screen`).

### Rejected: splitting `PathNavigationMixin`

The file is 940 lines, over the 400-line signal and approaching the 1000-line refactor line, and the obvious response is to split it. Measured,
that would make the code worse, so it is written down rather than done.

A mixin cannot share `@Unique` state with another mixin. The evidence is in this repo:
`WallClimberNavigationMixin` targets a subclass of the same vanilla type and still cannot touch a
single `pathweaver$` field directly — it casts `this` to the `PWNavigation` duck interface and goes
through accessors. That is the only mechanism available.

`PathNavigationMixin` holds 15 `@Unique` fields, and most of its methods read or write them. Any
split therefore crosses that state heavily, and paying for it means adding accessor pairs to a duck
interface for each field that crosses — putting interface dispatch in front of the hot path's own
fields, to relocate at most six methods while the remaining file stays around 750 lines.

The size is real and worth watching. The 306-line dispatch method inside it was the part that
actually hurt, and that is now 109 with the body behind a named step. Splitting the file buys
nothing further.

### Candidate: make `AUDITED` version-portable

Every audit gates on `MINECRAFT_VERSION.equals("26.1.2")`, so the checked tier refuses on 26.1.1 and
26.2 and switches the mod off there. On **26.1.1 that is provably over-strict**: the pinned vanilla
class bytes are byte-identical to 26.1.2 and the mod artifacts are the same builds, so the evidence
genuinely still holds and only a string comparison rejects it. Gating on "do the pinned hashes match"
rather than "does the version label match" would fix 26.1.1 for free and change nothing on 26.2,
where the bytes really did change. It is a *loosening* of a safety gate, so it needs its own review
and its own mutation test — the hash set must be proven to cover every input the proof depended on
before the version string is allowed to stop being a backstop.

26.2 is a different problem and not this one: `WalkNodeEvaluator`, `PathNavigation` and
`BlockStateBase` all changed, so those proofs must actually be re-derived, not re-labelled.

**The replacement for `AUDITED`, and it is a better mod for it.** Written below as it was planned;
what shipped is described in `CHANGELOG.md`. Two things changed in the building: in-flight requests
are NOT dropped (it does not stop the worker, and it risks the epilogue that otherwise pins an
amphibious mob's malus), and the threshold is windowed with a session backstop rather than a bare
count.

Before it, a worker search that threw was counted `SEARCH_FAILED`, discarded, and the mob pathed
synchronously for that tick. Correct — and nothing learned from it.

Invert the premise: **stop proving safety in advance; observe it.** A throwable on a worker trips a
per-family counter. Past a threshold, that family is disabled for the rest of the session and the log
names the family, the exception and the mod whose classes were on
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

**The recompute half is DONE.** `RequestOrigin` is threaded to the sink, and a stranded
`RECOMPUTE` request restores `timeLastRecompute` and re-arms `hasDelayedRecomputation`, with the
retry forced synchronous. Note for anyone reading the old plan: re-arming the flag alone does not
work — `recomputePath` is gated on the stamp, so the flag just spins for twenty ticks. Both halves
are required, and DESIGN.md §13 now carries the bytecode that shows it. Five mutations killed,
including one that first SURVIVED because the test drove `failed()`, which sets the sync cooldown
itself and so asserted a property that held with the feature removed.

Still open in 0.7: splitting `ARRIVED_STALE` into its real causes.

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

> **Landed early, in 0.7.0, as `brainSinkAsync`.** Built by deferring
> `MoveToTargetSink.checkExtraStartConditions` by one tick rather than answering it optimistically;
> see DESIGN.md §10 for why the blocker recorded there applies to the optimistic shape and not this
> one. Two corrections to the framing below. The **warden is not covered** — its navigation builds a
> `PathFinder` subclass and dispatch requires the stock class. And the payoff is smaller than the 86%
> quoted: on the reference pack the brain sink was ~9 percentage points of the A\* mix, because
> villager POI *queries* (`AcquirePoi`, `NearestBedSensor`) decide reachability and discard the path,
> and those can never be deferred. What remains here for 0.8 is the query side, which needs a
> different mechanism entirely.

Villagers, piglins, axolotls, frogs, allays. (Not the warden — its navigation builds a custom pathfinder, so it never dispatches.) The 86% figure this section used to lead with was wrong by an order of magnitude and is corrected in the block above: the brain sink was ~9 percentage points of the A\* mix. What is
`MoveToTargetSink.checkExtraStartConditions`, worth ~1.4 ms/tick (~5–7% of tick time) on an ordinary
world.

**Moved ahead of crowd pathfinding, deliberately.** The thing people build modpacks to do is put up a
city, and a city is hundreds of villagers. Every one of them paths through the brain, so today every
one of them paths on the server thread — PathWeaver is close to useless in exactly the build a player
is proudest of. The ~1.4 ms/tick above was measured on a normal world; it scales with villager count,
which is the whole point. Crowds of hostile mobs converging on a player is a mob farm, and that is a
narrower want.

Feasible — `DESIGN.md` §10 rejected an idea it never actually evaluated, and is corrected. Blocked on
0.7, which must land first. **Gate status:** written, now meaningful, waived as blocking (it ran at AUDITED and had never dispatched; see DESIGN.md section 10) — see DESIGN.md section 10. Gated on a game test asserting the whole `CANT_REACH_WALK_TARGET_SINCE` transition table: if that
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

## 0.9 — Measure where it hurts, and fix the 26.2 gap

Ordered by what the 0.8.0 measurements actually said, not by what looked biggest before them.

### 1. Re-derive the Lithium and Diagonal Blocks audits for 26.2

The only thing on the published page that says "not there yet". On 26.2 both resolve to builds their
audits were never derived from, so `AUDITED` denies six and one movement families respectively, and
most performance packs ship Lithium. Exact hashes plus a bytecode shape proof, which is the work
0.8.0 did for `servercore` and `rabbit-pathfinding-fix`. Bounded, and the highest value per hour on
this list.

### 2. A benchmark that actually hurts, in five minutes

**Everything measured for 0.8.0 ran on a server that was 87% idle.** Exclusive self-time on the
server thread in the route-sharing arm was 325,588 ms of 375,804 in `Unsafe.park`, waiting for the
next tick, at 5 to 6 ms against a 50 ms budget. The arms are still comparable with each other, but
none of those percentages tell someone with a struggling server what they would get, which is the
only question that matters to them.

**Design constraint, and it is a hard one: five minutes per invocation.** Running a benchmark means
holding every other agent off this machine, and an hour of that is not a cost worth paying for a
number. `deep-bench.sh` is about 5.8 minutes per run and `server-bench.sh` about 4.3, so a campaign
of interleaved arms is out. One arm per invocation, run twice, on separate approvals.

A workable budget: 20 s startup, 15 s arena, 40 s summon and settle, 120 s measured, 25 s stop. That
is 3.7 minutes with margin for a slow start. What has to fit inside it:

- Enough mobs that the tick misses 50 ms without the mod. The 1024-zombie maze did this (mean tick
  88 to 96 ms unmodded), so that population is the known-saturating one to start from.
- Mean tick and the worst 1% from the same run, because the mod's claim is about spikes rather than
  averages, and an average on a saturated server hides exactly the thing being sold.
- The same per-arm controls the current harness has. A saturating run that quietly lost half its
  population is worse than no run.

Only once that exists is there any basis for deciding what to build next.

### 3. Crowd pathfinding: parked, on the evidence

This was the headline for 0.9 and the roadmap said not to design it before reading the cache's
`BLOCK_ONLY` counter. The number is in: about 14% of lookups matched on everything except the mob's
exact position, against 12 to 16% that matched exactly. Serving those exact hits bought about 3% of
total pathfinding CPU with the run ranges overlapping.

So capturing every near miss plausibly buys another 3%, and it is paid for with the promise that a
mob gets its own path rather than a good one, which is what the whole safety story rests on. That is
a bad trade at this price. Park it until a saturating benchmark or a real-world hit rate says
otherwise.

### 4. Closed: stop producing discards (DESIGN.md 12)

Across 27 runs and 348,412 dispatched searches on 0.8.0: 63.2% installed, 36.7% parked for a mob
brain, and 0.1% wasted in total. The largest waste row was 334 searches for a mob that stopped. There
is nothing left to win here and the section is marked closed rather than left looking open.

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
