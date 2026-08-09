# Worker failure circuit breaker — design

**Status:** revised 2026-08-09 after an adversarial design review that found three real defects and
one overclaim. Targets 0.6.1. **Read "What the review changed" first.**
**Replaces:** the `AUDITED` tier's role as the safety mechanism (see `ROADMAP.md`, *The `AUDITED`
verdict*)

---

## What the review changed

The first draft was wrong about its own threading model, and that wrongness propagated into three of
its five risk rows and into the choice of where to count.

1. **The failure path is the main thread, not a worker.** `PathWorkerPool` only *enqueues* a failed
   outcome; `EntityInstallSink.failed(...)` runs from `ResultInstaller.drain`, called by
   `PathWeaverRuntime.onEndTick` on `END_SERVER_TICK`. So there is no trip storm, no cross-thread
   race with the installer, and no lock-ordering question — those three risk rows were answering a
   question that does not exist. **But** `drain` is `try { deliver } finally { runEpilogue }` with
   **no catch**, and `onEndTick` does not wrap it either. The draft claimed "every breaker call site
   is inside an existing `catch (Throwable)`". It is not. A throwable escaping the breaker would
   propagate into the Fabric tick event and **crash the server**. Wrapping is mandatory, and it is
   mandatory for a reason the draft got backwards.
2. **Counting in the sink under-counts, and it under-counts in exactly the wrong place.**
   `EntityInstallSink.failed` starts with `matching(key)`, which returns null once the registration
   has been removed — and `sink.supersede(entityId)` is called unconditionally on entry to
   `recomputePath` (`PathNavigationMixin:237`). `recomputePath` is what vanilla calls on
   **block-change invalidation**: precisely when the world near the searching mob is being mutated,
   which is precisely when a concurrent read throws. The blind spot is positively correlated with
   the hazard. Count in `PathWorkerPool`'s catch instead, where every throw is visible — a counter
   already exists there (`Generation.failCount`) with no production consumer.
3. **Bumping `configVersion` would disable the mod for all 138 existing users.**
   `PathWeaverConfigSerializer.deserialize` throws on any version that is not 2, 1 or 0, and a
   deserialization failure installs fail-closed defaults, which set `enabled = false`. Two new fields
   need **no** version bump: GSON leaves absent keys at their Java initializers.

**And one claim the review made that I checked and rejected:** it reported the surviving
`DispatchStage` mutations as a live bug leaking `inFlight` registrations for the 138 users today.
They are not live — `sink.register(...)` is immediately followed by the `REGISTERED` assignment with
nothing between them that can throw. It is an unpinned invariant a future edit could break, which is
worth a test and is not worth a release.

**Scope changes that follow:** `workerFailureAction`/`DISABLE_ALL` is cut (`enabled=false` already
is that setting), the threshold becomes windowed rather than cumulative, and the *attribution* half
is promoted from a detail of the trip log to the feature's main deliverable.

---

## The problem this solves

PathWeaver's safety story has always been *predictive*: scan every mod at startup, refuse to dispatch
if anything unaudited touched pathfinding code. On a real 221-jar pack that yields **0 of 187 mob
types eligible**, and it has since 0.3.0. The tier is unusable, so the shipped default is `UNSAFE`,
which performs no check at all.

So the mod currently has two modes: one that does nothing, and one that checks nothing.

Meanwhile, the thing that would actually go wrong — a worker thread reading world state while the
main thread mutates it — **is observable when it happens**. Today it is observed and then forgotten:

```java
// EntityInstallSink.failed(...)
finishDiscard(registration, RequestOutcome.SEARCH_FAILED);
failUntilTick.put(key.entityId(), currentTick + FAIL_COOLDOWN_TICKS);
```

That request is discarded, that one entity backs off for 40 ticks, and dispatch continues exactly as
before — including for every other mob using the same evaluator, through the same code, reading the
same world. There is no circuit breaker anywhere in the codebase (verified: no matching symbol).

**Inverting the premise:** stop trying to prove in advance that a pack is safe. Detect the failure,
and stop dispatching the affected family for the rest of the session.

## Why this is a better mechanism than the scan

Judged against the four tests at the top of `ROADMAP.md`:

1. **Value.** It defends against mods that did not exist when an audit was written, against mod
   updates, and against interactions between mods that no per-mod audit can see. The scan defends
   against none of those.
2. **Safe.** The action on trip is "behave exactly like vanilla". A breaker cannot make anything worse
   than not having the mod installed.
3. **Everyone.** No allowlist, no per-mod audit, no configuration. It protects the default install of
   any pack, which is the test `AUDITED` fails hardest.
4. **Worth updating for.** "Will this corrupt my world?" is the first question a prospective user
   asks. The honest answer moves from *"we scanned your mods and can't tell"* to *"if it starts going
   wrong, the mod switches that family off and tells you which mod was on the stack."*

**What it is not.** It is not a proof, and the README must not describe it as one. It is a smoke
detector: it does not stop the fire starting, it stops the house burning down and tells you which room
it started in. A silent corruption that never throws is still not caught — that limitation goes in
*What is unproven* alongside the existing ones.

---

## Design

### What trips it

**A throwable escaping `req.search().call()` in `PathWorkerPool` (line 89)** — counted there, not at
`EntityInstallSink.failed`, for the reason in *What the review changed* §2. The `SEARCH_FAILED`
outcome remains what the *installer* records for the discarded request; the two are no longer the
same event.

Trigger set: worker search throws. Nothing else.

Deliberately excluded, with reasons, because a breaker that trips on the wrong thing is worse than
none:

| Outcome | Excluded because |
|---|---|
| `INSTALL_FAILED` | Main thread. It is PathWeaver's own install logic failing, not evidence of an unsafe read. It already has a per-entity cooldown and a one-shot log. Routing around our own bug hides it. |
| `HANDOFF_FAILED` | The result-queue callback, also not a world read. |
| `SETUP_FAILED*` | Happens before any worker touches anything. Already counted and logged since 0.6.0. |
| `NO_PATH` | A successful search that found nothing. Normal. |
| `ARRIVED_STALE`, `SUPERSEDED`, `NAVIGATION_STOPPED` | Normal life-cycle discards. |

If evidence later shows install failures correlate with bad reads, that is a separate change with its
own justification — not a quiet widening of this one.

### Unit of tripping: the evaluator family

The same unit `SafetyGate` already denies in, and the same unit the scan, the banner and
`/pathweaver mobs` all report in. Tripping `WalkNodeEvaluator` denies all five walk-derived families
through the existing `isAssignableFrom` rule in `SafetyGate.isDenied` — which is correct, because they
all execute Walk's code.

The family is known at **dispatch** time, not at failure time, so `PathRequest` carries it alongside
the key and dispatch tick. (`Registration` was the first draft's answer; counting moved to the pool,
so `Registration` is untouched.)

**Attribute each failure to the nearest allowlisted ancestor, not the exact dispatch class.** A Walk
bug reached through `Frog$FrogNodeEvaluator`, `AmphibiousNodeEvaluator` and `FlyNodeEvaluator` would
otherwise register one failure each against three different keys and never trip anything — the
effective threshold would silently be 5× the configured one. A mod subclass admitted at `UNSAFE` via
`isWaivableSubclass` resolves to its allowlisted ancestor for the same reason.

### Threshold and scope

**Windowed, not cumulative.** The first draft said cumulative-per-session, default 3, justified by
"755 dispatches produced zero failures, so any failure is abnormal". That sample is about one minute
of a busy server, and it is contradicted by this project's own Lithium audit
(`COMPATIBILITY.md`), which describes a concurrent-resize exception as an expected, contained event
on the most widely installed performance mod in the ecosystem.

A cumulative counter with no decay converges on a **certain** trip given enough uptime. Over weeks,
three transient races is not a hypothesis, it is a schedule — and the result is that a healthy install
silently loses five of six families. The "fail-closed is fine because the action is *be vanilla*"
argument does not survive contact with that: being vanilla is the thing the user installed the mod to
stop. A false trip is not a safe no-op, it is *"the feature stopped and nothing told me"* — the exact
user-facing failure the last three releases were spent removing.

So:

- **`workerFailureLimit` failures within `workerFailureWindowTicks`** trips the family.
- Defaults: **3 within 1200 ticks** (one minute). A systemic incompatibility produces failures in
  seconds; a benign race produces them hours apart. The window is the only thing that distinguishes
  them, which is what the draft's "a window means a slow leak never trips" objection missed — a slow
  leak is *supposed* not to trip a mechanism whose false-positive cost is the whole product.
- A hard cumulative ceiling of **25 per family per session** catches the genuine slow leak without
  turning ordinary uptime into a countdown.
- **Session-scoped, never persisted**, and **reset on server start**, not merely per JVM.
  `EntityInstallSink.clear()` already re-arms its one-shot log flags for exactly this reason: a
  failure logged in world A silenced the first failure of every later world in the same JVM. Breaker
  state must be reset the same way, or a singleplayer user who trips in one world gets a permanently
  inert mod in every later world **with no log line**, because the one-shot already burned.

### Trip action

1. Add the family to a **new** `SafetyGate.deniedByRuntimeFailure` set — deliberately *not*
   `deniedBySafety`. Copy-on-write `volatile` reference, not a synchronized set: `isDenied` runs on
   the hot dispatch path once per repath per mob, and it must never be possible for a trip to log
   (i.e. call into a third-party appender — this mod's entire threat model) while holding a monitor
   that path takes.
2. `SafetyGate.isDenied` consults both sets.
3. **Record a refusal outcome for every dispatch declined after a trip.** Without this,
   `PathNavigationMixin` returns early and nothing is counted: `/pathweaver status` shows `dispatched`
   flat-lining with no row saying why, which is verbatim the vanishing-setup-failure defect 0.6.0
   fixed.
4. Emit one loud log block: family, exception class and message, failure count, and the mods
   implicated by the stack. Naming the mod is what turns a scary log line into an actionable bug
   report — see the honest limits on that below.

**Dropped from the first draft: "drop every in-flight registration for that family."** Its stated
reason — *those searches may be reading the state that just threw* — is wrong. Removing a registration
does not stop a worker; the search runs to completion either way. The real property wanted is "do not
install a result from a search we no longer trust", and that already happens for free, because
`matching(key)` returns null for a dropped registration. The only net effect would be falling back to
sync one tick earlier, against a real risk: the epilogue bookkeeping must survive, and abandoning an
`AmphibiousNodeEvaluator` epilogue permanently pins WALKABLE=6.0 on a live mob. Not worth it.

**Why a separate set, and this is the important part.** `deniedBySafety` is cleared wholesale by the
`UNSAFE` tier at startup (`ForeignMixinScanner` → `SafetyGate.replaceDenials(Set.of())`). A runtime
trip **must not be waivable by any tier** — the whole point is that it fires when the prediction was
wrong. A separate set also keeps the diagnostics honest: `/pathweaver status` must be able to say
*"denied by the scan"* and *"switched off after a failure at runtime"* as different sentences. Merging
them would reintroduce exactly the one-label-two-meanings defect 0.6.0 spent a round fixing.

`isAllowed` already calls `isDenied` unconditionally at every tier (verified at `SafetyGate:154`), so
no gate rewiring is needed.

### Settings

Two, both in the existing config schema, both surfaced in the Cloth settings screen:

| Key | Default | Meaning |
|---|---|---|
| `workerFailureLimit` | `3` | Search failures for one family within the window before that family stops dispatching for the session. `0` disables the breaker; failures are still logged and attributed. |
| `workerFailureWindowTicks` | `1200` | The window. `0` means "cumulative, never decays" for anyone who wants the strict behaviour. |

**`workerFailureAction`/`DISABLE_ALL` is cut.** It was undefined (all six families, or `enabled=false`
— which would also kill Feature B repath elision, a main-thread feature with nothing to do with worker
safety), it would have added a second enum to a serializer whose enum validation is hard-coded to
`CompatibilityTier`, and its user story is already served by the `enabled` checkbox one screen away.

**Config plumbing, concretely — this is where the 138 users are at risk:**

- **Do not bump `CURRENT_CONFIG_VERSION`.** `deserialize` throws on any version but 2/1/0, and a
  deserialization failure installs fail-closed defaults with `enabled = false`. Two added fields need
  no bump: absent keys keep their Java initializers, and `validateCurrentFieldTypes` only checks keys
  that are present.
- Clamp both ints in `validatePostLoad`, where every other int is already clamped.
- The settings screen must say plainly what `workerFailureLimit=0` gives up. It is a legitimate
  choice when benchmarking; it must not be one made by accident.

### Reporting — and this is the half that carries the release

The breaker may never fire on a healthy pack. **The attribution fires on the first failure**, needs no
threshold, has no false-positive cost, and is what turns today's single rate-limited `WARN` into an
actionable bug report. It ships whether or not the breaker ever trips, and it is the reason this
feature is worth building at all.

**Honest limit, which must appear in the log text and the README.** A mixin-injected handler is merged
into its *target* class, so a stack frame usually reads
`net.minecraft.world.level.pathfinder.WalkNodeEvaluator`, not the mod's own class. The mod id is often
recoverable from the generated handler method name (`handler$xxx000$modid$…`) and is **not recoverable
at all** for an `@Overwrite` or an inlined `@Redirect`. Parse the handler-name convention as well as
code sources, and say plainly when the culprit cannot be named — printing an empty list would let a
user infer no mod was involved.

**Resolve frames without `Class.forName`.** Loading an arbitrary class by name on the server thread can
trigger static initialization of arbitrary mod code mid-tick. Use `StackWalker` with
`RETAIN_CLASS_REFERENCE`, or match `StackTraceElement` names against a code-source map built once at
startup.

**Two existing diagnostics must be fixed, and this is not additive work.** With a family in the new
set, `MobEligibility.evaluatorReason` falls through to *"whose family the compatibility scan denied"* —
the scan denied nothing. And `PathWeaverCommand.scanSummary` is handed `report.decision().denied()`, so
a trip is either invisible (where the scan already covers that family) or reported as *"most likely an
evaluator that cannot be cloned on this JVM"* (where it does not). Both wrong, in opposite directions,
and both the same invented-cause defect the last four review rounds were about.

**The world-start banner** asserted "ACTIVE" earlier in the session; a trip makes that stale, so the
trip block must say the earlier banner no longer holds.

- `/pathweaver status` gains a line only when something has failed or tripped, naming the family, the
  count and the exception. Silence otherwise; a permanent "breaker: armed" line is noise.
- `/pathweaver mobs` verdict for an affected mob reads *"switched off after a worker failure"*, not
  *"evaluator not allowlisted"* — a diagnostic that invents a cause is the defect class this release
  exists to end.
- The startup log is unchanged. Nothing has failed yet at startup.

---

## Risks, and what is done about each

| Risk | Mitigation |
|---|---|
| **The breaker itself throws.** `ResultInstaller.drain` is `try/finally` with **no catch**, and `onEndTick` does not wrap it — an escaping throwable reaches the Fabric tick event and crashes the server. | Every breaker body wrapped in its own `catch (Throwable)`. A mechanism that cannot record a failure must never convert that failure into a crash. This is the most important correction in this document. |
| **False trip** disables a family for a benign, unrelated exception. | The window exists for this. The consequence is vanilla behaviour plus a loud log — but vanilla behaviour is what the user installed the mod to stop, so this is a real cost, not a free one. That is why the window replaced the cumulative count. |
| **Logging while holding a denial monitor.** `isDenied` takes a static monitor on the hot dispatch path; a log appender is third-party code, which is this mod's whole threat model. | Copy-on-write `volatile` set. No monitor on that path, so the hazard cannot exist. |
| **Class loading during attribution.** `Class.forName` on a stack frame can initialize arbitrary mod code mid-tick. | `StackWalker` / prebuilt code-source map. Never `Class.forName`. |
| **A trip leaks across worlds in one JVM.** `SafetyGate` is per-JVM static and the one-shot log flag burns once. | Reset in `onServerStarting`, exactly as `EntityInstallSink.clear()` already re-arms its flags, and for the documented reason: a failure logged in world A silenced the first failure of every later world. |
| **A tripped family produces silent refusals.** | Record a refusal outcome per declined dispatch, so a flat `dispatched` count is explained rather than guessed at. |
| **No way to re-arm without a restart.** | Accepted for 0.6.1 and stated in the log line so nobody is left wondering. `/pathweaver reset` goes on the next list. |
| **Regression for the 138 existing users.** | No config version bump; both added fields default in for an untouched `configVersion: 2` file. Zero observed search failures in 755 dispatches on the reference pack means the expected behaviour change for a healthy install is *none*. Gated on a benchmark against 0.6.0 showing no measurable difference. |

## Test plan

Per the project's standard, **every claim below is verified by compiling the mutation that
reintroduces the bug and observing the suite fail** — a test that passes with the fix reverted is not
evidence.

1. Three failures for Walk inside the window trip it; two do not; three spread beyond the window do not.
2. A trip denies all five walk-derived families and leaves Swim dispatching.
3. **A trip survives `compatibilityTier=UNSAFE`** — the single most important test in this feature.
4. `workerFailureLimit=0` never trips, at any failure count.
5. `workerFailureAction=DISABLE_ALL` denies every family on the first trip.
6. A failure through `FrogNodeEvaluator` counts against `WalkNodeEvaluator`, so three failures spread
   across three land families still trip.
7. The breaker's own exception cannot escape — asserted at the `drain` boundary, which has no catch.
8. Breaker state resets on server start, and the one-shot log re-arms with it.
9. A dispatch declined by a trip records an outcome.
10. A `configVersion: 2` file without the new keys loads with `enabled` still true and the new fields
    at their defaults. This is the 138-user regression test.
8. `/pathweaver mobs` reports the runtime cause, not a scan cause, for an affected mob.
9. `/pathweaver status` says nothing about the breaker until something trips.
10. A game test that injects a throwing evaluator and asserts the family goes synchronous in a live
    server.
11. Benchmark against 0.6.0: no measurable change on a healthy pack.

## Out of scope for 0.6.1

- Persisting trips across restarts, and `/pathweaver reset` to re-arm without one.
- Any attempt to *diagnose* which mod is at fault beyond naming stack-trace classes.
- Tripping on install or handoff failures.
- Anything touching the `AUDITED` tier, which is now frozen.
