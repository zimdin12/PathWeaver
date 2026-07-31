# PathWeaver 0.4.0 — design

Status: proposed, 2026-07-31. Supersedes nothing; extends the 0.3.0 boundary.

## Why this is 0.4.0 and not 0.3.1

Two changes are not patch-level: the compatibility tier is renamed (`ALL` → `UNSAFE`, with a config
migration), and the set of mobs that dispatch off-thread grows. A patch release may not change which
code runs on a worker.

## What 0.3.0 got wrong, and how it was found

Three defects were found by reading the dispatch path end to end rather than by testing, which is
worth recording because each was invisible to the 218-test suite.

**The subclass waiver is inert.** `cc27c44` widened `SafetyGate` to admit third-party
`WalkNodeEvaluator`/`SwimNodeEvaluator` subclasses at the unsafe tier. `EvaluatorCallbackContract`
still matches on exact class identity and throws for anything else. The throw lands inside the
dispatch try-block, so an admitted subclass builds a region, clones an evaluator, constructs a
`PathFinder`, submits to the pool, counts a dispatch, and then unwinds to a synchronous search —
every time. Two independent admission decisions disagreed and the disagreement was silent because
the fallback is correct. Pinned by `SubclassDispatchReachabilityTest`.

**`discarded` is not a meaningful number.** It conflates superseded targets, `stop()`, pool
saturation, setup throws, stale-on-arrival, worker exceptions, install failures, and — through
`ResultInstaller.Sink.noPath` — searches that correctly determined no route exists. A successful
search is being counted as a discard. This already cost real work: the adaptive admission controller
built and reverted during 0.3.0 failed because its ratio could never reach target, and this is why.

**A published claim rests on that number.** The mod page says *14–22% of dispatched searches are not
installed in time*. An unknown share of that figure is mobs with nowhere to go. The claim cannot be
restated honestly until the causes are separated.

## Scope

### 1. Correct the subclass waiver

`EvaluatorCallbackContract.forAsyncEvaluator` resolves by assignability instead of identity:
Swim-derived → `(0,0)`, Walk-derived → `(1,1)`. Exact vanilla classes keep their current answers.
Anything derived from neither still throws, because a contract that guesses is worse than a refusal.

### 2. Split the discard counter

A `DiscardReason` enum threaded through `EntityInstallSink` and `ResultInstaller`, with a counter per
reason. `noPath` moves out of the discard family entirely — it becomes its own outcome, because it is
one. `/pathweaver status` reports the breakdown instead of one aggregate.

This is the prerequisite for restating the mod-page claim, so it lands before any re-measurement.

### 3. Fly mobs dispatch off-thread

`FlyNodeEvaluator` has exactly one live-mob hazard: `Mob.getRandom()` inside
`iteratePathfindingStartNodeCandidatePositions`, reached only from `getStart()`.

A `@Redirect` returns a worker-local `RandomSource` when `PathWeaverThread.isWorker()`, mirroring
`WalkNodeEvaluatorMixin`. Vanilla selects a *random* start candidate, so selecting a different random
one is inside the specification — this needs no reproduction, only isolation from shared state. The
shared mob RNG is not advanced during an async search, which is a divergence from vanilla's sequence
and is not a behavioural contract Minecraft offers.

`FlyNodeEvaluator` declares its own `prepare`/`done`, so `WalkNodeEvaluatorMixin` does not cover its
`onPathfindingStart`/`onPathfindingDone` calls. It needs its own redirect pair.

### 4. Amphibious mobs dispatch off-thread

`AmphibiousNodeEvaluator.prepare` reads and then overwrites three pathfinding malus values on the
live mob; `done` restores two. Both delegate to `super`, so their `onPathfindingStart`/`Done` calls
are already handled by the existing Walk redirect. Only the malus is new.

`EvaluatorCallbackContract` is promoted from a pair of counts to a main-thread **prologue/epilogue**:
the prologue runs at dispatch before submission, the epilogue at install or discard, both on the main
thread, both through the exactly-once balance machinery that already survives success, discard,
clear, shutdown and exception. The worker's own malus writes are redirected to no-ops.

**Accepted cost:** the mob carries amphibious malus for the whole in-flight window — roughly one tick
— rather than only for the duration of the search. Anything else reading malus in that window sees
amphibious values. In practice only pathfinding reads it, so exposure is small, but it is a real
semantic change and is stated in the README and on the mod page rather than left to be discovered.

Rejected alternative: redirecting both the writes and the search's malus *reads* onto a worker-local
overlay. It removes the window entirely, but requires intercepting every read site in the search,
which is a much larger mixin surface against code mods commonly touch. The window is the cheaper
honest trade.

### 5. Command tests and cost

`PathWeaverCommand` is the only untested file in the tree. `/pathweaver mobs` constructs every
registered entity type on the server thread — 187 on the reference pack — and has never been timed.
Add tests; measure; add a budget or an explicit warning if it stalls a tick.

### 6. Documentation

Two kinds, treated differently:

- **Claims that are now false** get rewritten: `README.md:15` ("evaluator subclasses always stay
  synchronous") and the Modrinth page ("even `Everything` does not widen it").
- **Historical records are annotated, never renamed.** Eleven of the sixteen `ALL` occurrences in
  `COMPATIBILITY.md`/`CHANGELOG.md` are measurement runs and the 0.3.0 changelog entry describing
  what 0.3.0 actually shipped. Rewriting them would falsify the evidence record. They read `ALL` (the
  tier now named `UNSAFE`).

### 7. Re-measure and republish

Eligibility (163/187) was measured at the old `ALL` and changes once subclasses, Fly and Amphibious
dispatch. Benchmarks re-run on the 0.4.0 jar. The "14–22%" claim restated from the split counters.

## Out of scope

Entity ticking, collision, AI goal selection. Any evaluator derived from neither Walk nor Swim.
Reviving the adaptive admission controller — the split counters make it *possible*, not warranted.

## Testing

Each of 1–4 gets unit coverage at the seam that failed, not at the feature level: contract
resolution by assignability; one counter per discard cause including `noPath` leaving the family;
worker-local RNG identity under `isWorker()`; prologue/epilogue balance across every terminal path.
The three benchmark harnesses gate the release, and the 371-mod pack run is the eligibility witness.

## Risks

The subclass waiver has never actually dispatched, so 0.4.0 is the first release where third-party
evaluator code runs on a worker. That is the point of the unsafe tier, and it is the single largest
behavioural change here — it deserves the most adversarial review round, not the least.
