# Worker failure circuit breaker — design

**Status:** approved 2026-08-09, targets 0.6.1
**Replaces:** the `AUDITED` tier's role as the safety mechanism (see `ROADMAP.md`, *The `AUDITED`
verdict*)

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

**`SEARCH_FAILED` only** — a throwable escaping `req.search().call()` on a worker thread
(`PathWorkerPool:89`).

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

The family is known at **dispatch** time, not at failure time. `Registration` already carries a
dispatch-time captured fact (`requiresEmptyLandRegistry`), so the evaluator class joins it there.

### Threshold and scope

- Count **cumulative `SEARCH_FAILED` per family per server session**.
- Default limit: **3**. On the reference 221-jar pack, 755 dispatches produced **zero** search
  failures, so any failure at all is abnormal; 3 is generous and still catches a genuine problem
  within seconds.
- **Not** a sliding window. A window means a slow leak never trips, and the fail-closed direction is
  the right default for a mechanism whose failure action is "be vanilla".
- **Session-scoped, never persisted.** A restart re-tests. Writing a permanent disable into the config
  file is the "installs, does nothing, never mentions it" failure this project already fixed once.

### Trip action

1. Add the family to a **new** `SafetyGate.deniedByRuntimeFailure` set — deliberately *not*
   `deniedBySafety`.
2. `SafetyGate.isDenied` consults both sets.
3. Drop every in-flight registration for that family; those searches may be reading the state that
   just threw.
4. Emit one loud log block: family, exception class and message, failure count, and **the mods whose
   classes appear on the stack trace**, resolved through `FabricLoader` code sources. Naming the mod
   is what turns a scary log line into an actionable bug report.

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
| `workerFailureLimit` | `3` | Search failures per family before that family stops dispatching for the session. `0` disables the breaker entirely. |
| `workerFailureAction` | `DISABLE_FAMILY` | `DISABLE_FAMILY` or `DISABLE_ALL`. The paranoid option switches the whole mod off on the first trip rather than one family. |

`0` and `DISABLE_ALL` both need the config screen to say plainly what they give up. A user who sets
`workerFailureLimit=0` is choosing the 0.6.0 behaviour, which is a legitimate choice for someone
benchmarking — it just must not be a choice made by accident.

### Reporting

- `/pathweaver status` gains a line only when something has tripped, naming the family, the count and
  the exception. Silence when nothing has tripped; a permanently-present "breaker: armed" line is
  noise.
- `/pathweaver mobs` verdict for an affected mob reads *"switched off after a worker failure"*, not
  *"evaluator not allowlisted"* — a diagnostic that invents a cause is the defect class this release
  exists to end.
- The startup log is unchanged. Nothing has failed yet at startup.

---

## Risks, and what is done about each

| Risk | Mitigation |
|---|---|
| **The breaker itself throws**, inside a failure path, on a worker. | Every breaker call site is inside an existing `catch (Throwable)`. The breaker's own body is wrapped and swallows; a breaker that cannot record a failure must not turn that failure into a second one. |
| **False trip** disables a family for a benign, unrelated exception. | Consequence is vanilla behaviour plus a log line — the same thing the user had before installing the mod. Acceptable by construction. |
| **Trip storm**: many failures at once from many threads. | Trip is idempotent and guarded by the set's own monitor; the log block is one-shot per family via `AtomicBoolean`, matching `installFailureLogged`. |
| **In-flight drop races the installer.** | Reuse the existing `inFlight.remove(id, registration)` compare-and-remove; anything the installer already claimed completes normally. |
| **Regression for the 138 existing users.** | Default limit 3 with zero observed failures on a 221-mod pack means the expected behaviour change for a healthy install is *none*. A benchmark against 0.6.0 must show no measurable difference, and the release is gated on it. |

## Test plan

Per the project's standard, **every claim below is verified by compiling the mutation that
reintroduces the bug and observing the suite fail** — a test that passes with the fix reverted is not
evidence.

1. Three `SEARCH_FAILED`s for Walk trip the breaker; two do not.
2. A trip denies all five walk-derived families and leaves Swim dispatching.
3. **A trip survives `compatibilityTier=UNSAFE`** — the single most important test in this feature.
4. `workerFailureLimit=0` never trips, at any failure count.
5. `workerFailureAction=DISABLE_ALL` denies every family on the first trip.
6. In-flight registrations for the tripped family are dropped; other families' are untouched.
7. The breaker's own exception cannot escape into the failure path.
8. `/pathweaver mobs` reports the runtime cause, not a scan cause, for an affected mob.
9. `/pathweaver status` says nothing about the breaker until something trips.
10. A game test that injects a throwing evaluator and asserts the family goes synchronous in a live
    server.
11. Benchmark against 0.6.0: no measurable change on a healthy pack.

## Out of scope for 0.6.1

- Persisting trips across restarts.
- Any attempt to *diagnose* which mod is at fault beyond naming stack-trace classes.
- Tripping on install or handoff failures.
- Anything touching the `AUDITED` tier, which is now frozen.
