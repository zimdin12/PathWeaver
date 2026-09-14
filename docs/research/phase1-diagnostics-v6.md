# Phase 1 diagnostics, v6: one decision, one report

Draft for independent review before anything is implemented. Replaces
`phase1-diagnostics-v5.md` at `f2f02c13f05de1170ab48fca7e49edc58da2336d` (REVISE: timer accepted,
membership blocker, review `phase1-f2f02c1-verdict.md`), kept unedited, as are v4 (`c3dfa3f`), v3
(`18eede7`), v2 (`6fd91cb`) and v1 (`be258c31`). Source locations refer to `080fc42eec1809507305c67e891eb8c45bc85a95`; `src` is unchanged
since.

## Changes from v5

v5's tick invocation timer was accepted. One blocker remained: capturing everything from one
invocation's entry to a later one's also captures server-thread work **between** invocations, which
the timer does not. That work is real: `processPacketsAndTick` runs `processQueuedPackets` before
`tickServer`, and `waitUntilNextTick` runs `runAllTasks` (reviewer's disassembly of the 26.1.2 server
jar). A selected handler such as `ServerLevelBlockChangeMixin`'s can run there.

v6 makes membership explicit: **only work inside a timed `tickServer` invocation** counts toward
C_D, the server sampler and the counter deltas used by the screens. Selected-handler work between
invocations is measured separately as GAP and reported, never added. One control is added.

Carried unchanged from v5: the invocation timer and its containment control, entry boundaries, and
everything v5 carried from v4.

## Scope

Diagnostic only. No routing, admission, install, cancellation, return or exception behaviour changes.

- **D decision**: server-thread wall time inside PathWeaver's request-side handler bodies, minus the
  synchronous searches inside them, on a recorded run that passes the observer screens.
- **Client report**: wall time of base `PathFinder.findPath` executions on client threads. Report only.

## What C_D covers

**Included.** The bodies of every mixin handler method in the compiled `dev.pathweaver.mixin`
classes whose `@Mixin` target is `PathNavigation`, `WallClimberNavigation`, `MoveToTargetSink` or
`ServerLevel`, plus the body of `PathWeaverRuntime.onEndTick` (`PathWeaverRuntime.java:476`, which
contains the drain). Anything those bodies call is inside them, including the movement and depth
bookkeeping (`PathNavigationMixin.java:166-168`, `:250-253`, `:438-444`), `BrainSinkPort` (called
from `MoveToTargetSinkMixin` handlers) and the `EntityInstallSink` routes.

The set is derived, not typed. A diagnostic-branch test reads the compiled mixin classes, selects
handlers by annotation and `@Mixin` target, and writes a manifest: each handler's owner, name and
exact descriptor. It fails if a selected handler has no bracket.

**Excluded from C_D and from D:**
- injected call-site machinery that runs outside a handler body: `CallbackInfo` construction,
  argument capture, the invocation itself, where no enclosing selected handler covers it;
- per-instance mixin field initialisation;
- handlers inside a search, on any thread: `PathFinderMixin`, `PathfindingContextMixin`,
  `WalkNodeEvaluatorMixin`, `AmphibiousNodeEvaluatorMixin`, `FlyNodeEvaluatorMixin`,
  `LandPathTypeRegistryMixin`;
- command handling, config loading, server start and server stop;
- any code reached by reflection or by a foreign mod outside these bodies.

D makes no claim about any of these.

## Server measurement

**Handler brackets.** Each included handler body is wrapped in `try/finally`. A server-thread-only
`handlerDepth` counter is incremented on entry and restored in `finally`. Wall time is accumulated only
for a bracket entered at `handlerDepth == 0` (outermost). For each handler id the manifest records
**invocations** (every entry) and **outermost entries** separately: an inner handler can have
invocations and no outermost time of its own, and that is correct.

**Search subtraction, outermost-only per bracket.** A MixinExtras `@WrapMethod` on
`PathFinder.findPath(PathNavigationRegion, Mob, Set, float, int, float)`. The build resolves MixinExtras
0.5.4, whose jar contains `injector/wrapmethod/WrapMethod`; that is checked when implemented. On the
server thread, while `handlerDepth > 0`, it uses a `searchDepth` counter restored in `finally`. Only a
search entered at `searchDepth == 0` adds its wall to the current outermost bracket's subtraction.
A search nested inside another search, however it got there (a foreign evaluator, a callback), is
already inside the outer search's interval and is never subtracted again. Sequential searches in one
bracket are disjoint, and each is subtracted once. The subtracted total therefore equals the union of
search intervals inside the bracket. A bracket whose subtraction exceeds its own wall is an error:
it voids the window, and it is never clamped.

**Tick invocation timer.** A MixinExtras `@WrapMethod` on
`MinecraftServer.tickServer(BooleanSupplier)`, present in the diagnostic jar in all three states. It
takes `System.nanoTime()` on entry and in `finally`. `@WrapMethod` is meant to wrap the method together
with the injectors other mixins apply to it, so Fabric's TAIL END_SERVER_TICK listeners, PathWeaver's
`onEndTick` among them, run inside the timed interval. That containment is a claim about MixinExtras
application order; control 8 must demonstrate it on the built jar before any run counts. The time
between invocations, which includes the server's sleep and catch-up, is part of no duration.

**Boundaries.** The diagnostic jar adds `/pathweaver trace mark begin` and `mark end`, available in
every state. A mark takes effect at the **entry of the next `tickServer` invocation**, inside the
timer, before the tick's own work. At that instant it:
1. checks `handlerDepth == 0` and `searchDepth == 0` (depth accounting runs in IDLE and RECORDING,
   and in IDLE while a begin is pending), and voids the window otherwise;
2. records the invocation's tick number, a `System.nanoTime()` stamp, and a snapshot of PathWeaver's
   outcome counters read directly from `PathWeaverRuntime`, not parsed from status text;
3. in RECORDING only, opens or closes the accumulation window.

If begin takes effect at the entry of invocation A and end at the entry of invocation B, the capture
interval is **invocations A through B - 1, each complete**: B - A invocations, each from its entry to
the end of its tail listeners. Every per-tick quantity uses B - A. B - A < 20 voids the window.

**Membership: inside an invocation only.** The invocation timer sets a server-thread
`insideInvocation` flag at entry and restores it in `finally`. For invocations A through B - 1:
- a handler bracket, and the searches it subtracts, count toward C_D only if the outermost bracket
  was entered with `insideInvocation` true;
- a server sampler entry counts only if `insideInvocation` is true;
- the counter deltas for the screens are **summed per invocation**: PathWeaver's outcome counters are
  read at each invocation's entry and in its `finally`, and the difference is added. This runs in
  all three states. Counter changes between invocations are excluded from the screened rates.

**GAP.** Outermost selected-handler brackets entered with `insideInvocation` false, between A's entry
and B's entry, are accumulated separately (count, wall, subtracted search wall), along with the
counter changes between invocations. GAP is reported per load-round. It is **excluded from D**: D
concerns request-side work inside ticks, and the tick durations cannot price anything outside them.
A large GAP is recorded as a finding for a separate question, not folded into C_D.

**Tick timing.** The durations of exactly those B - A invocations, from the invocation timer, in every
state. Mean and p95 come from them. C_D's brackets, the sampler and the summed per-invocation
counter deltas therefore cover exactly those invocations, and none of the work between them.

**C_D** = (sum over outermost brackets in the interval of (bracket wall minus its subtracted search
wall)) / (B − A), in ms per tick.

## Server request sampler (exposure only)

- **Population:** every entry to `PathNavigation.createPath(Set,int,boolean,int,float)` on the
  server thread with `insideInvocation` true, in invocations A through B - 1, in RECORDING. It is
  counted by a server-thread counter.
- **Rate:** entries whose counter value is divisible by 32 are sampled.
- **Record:** a `StackWalker` walks up to 24 frames. It keeps
  the declaring class name and method name of each frame outside `net.minecraft.world.entity.ai.navigation`,
  `net.minecraft.world.level.pathfinder` and `dev.pathweaver`, in order.
- **Denominator:** the number of samples taken.
- **What a sample can show:** class and method names. **Not** which jar supplied a method: a mixin
  merges a method into its target class, so the declaring class is the target. The report never
  infers mod ownership from a class's code source.
- **Observables used by exposure rules:**
  - *TemptGoal*: a kept frame whose class is `net.minecraft.world.entity.ai.goal.TemptGoal`.
  - *Enhanced Cats*: a kept frame whose class is `net.minecraft.world.entity.animal.feline.Cat` and
    whose method name is `BabyvillagerAnnoysCat`, `cathuntsfish` or `getfish`. These are the methods
    the committed census found in Enhanced Cats' `CatEntityMixin`, which targets `Cat`
    (`path-request-patterns-2026-09.csv`). The merged names survive at runtime: the earlier
    measurement probe (`bench/frameprobe` `PathCalls`, a `StackWalker` keeping class plus method,
    1 call in 32) named `Cat.BabyvillagerAnnoysCat` as a sampled caller in 41 of its periodic log lines
    in `bench/client-runs/e2-v090/latest.log` (Enhanced Cats installed) and in none of
    `e3-noecats-v090` (held out). A W3 run in which no sample
    matches is EXPOSURE_FAIL, not evidence that the requests were vanilla.

## Client report

- **Hook:** the same `@WrapMethod` on base `PathFinder.findPath`, when the current thread is neither
  a `PathWeaverThread.Worker` nor the server thread, and `mob.level().isClientSide()` evaluates true
  inside the guard. **Coverage:** only executions of the base method. A `PathFinder` subclass that
  overrides `findPath` without calling it is not seen (`PathNavigationMixin.java:627-633` documents
  that such subclasses exist), and the report says so.
- **Producer ownership:** each client thread that reaches the hook owns one fixed-size record: a
  search-depth counter, outermost-only wall total, count, and 64 caller-sample slots (class and method,
  as the server sampler). It registers that record once, in a concurrent registry, on first use.
  Nested client searches on one thread are counted outermost-only. Sample slots that fill count
  their overflow; totals are counters and cannot overflow.
- **Window membership:** the mark boundaries' nanoTime stamps. A client search counts in the report
  only if its begin and end both lie in [A, B). Searches that straddle either boundary are counted
  and timed separately as STRADDLING, and are never added to the in-window total.
- **Transport and finalization:** at mark end, each registered producer publishes its record the
  next time its search depth returns to 0, or immediately if already at 0. FINAL waits at most 2 s
  wall for every registered producer to publish at depth 0. Any producer still unpublished makes the
  **client report** VOID. The server D result is unaffected. The file records which thread it was.
- **Output:** in-window wall per second of (B − A) wall time, by thread; sampled class.method callers
  with their sample counts; STRADDLING count and wall; sample overflow counts. No decision.

## Recorder

- Server totals accumulate in fixed arrays on the server thread. A writer thread writes periodic
  records, each carrying **cumulative totals since boundary A** and a sequence number. Periodic
  records are for monitoring and gap detection only. The analyser takes C_D from the FINAL trailer's
  cumulative totals and **never sums periodic records**.
- **Positive finalization:** a valid file ends with a FINAL trailer holding contiguous sequence
  numbers, the manifest, per-handler invocations, outermost count, outermost wall and subtracted
  wall, both boundary snapshots, the client producers' published records, and a sha256 of the body,
  flushed after mark end. A missing, truncated or mismatched trailer, a sequence gap, a depth check
  failure, or subtraction exceeding bracket wall voids the window.
- **Exception safety:** argument construction and recorder bodies sit inside the guarded block. A
  Throwable disables recording for the process and voids the window. Tracing never changes a return
  value or an exception.
- **Activation:** the JVM property `pathweaver.trace`. The disabled cost is measured, not assumed.
- Clock resolution is measured at property load and written to the header.

## Observer screens

States on the diagnostic jar: **ABSENT** (property unset; marks, tick timing and counter snapshots
still work), **IDLE** (property set, not recording), **RECORDING**. ABSENT versus IDLE is the
activation cost inside the diagnostic jar. The compiled-in cost is a separate comparison: the
diagnostic jar in ABSENT against the 0.9.0 release jar on W1, by the regression method's 3% rule.

**Relative difference** of a recorded value r against the ABSENT value a in the same round:
- a and r both finite and a > 0: rel = |r − a| / a;
- a = 0 and r = 0: rel = 0;
- a = 0 and r > 0: the screen trips;
- either value missing or non-finite: the load-round is VOID.

Counter values come from the runtime snapshot, so a zero is a real zero, never an omitted row.

A load-round trips the screen (PERTURBED) if any of:
- server tick mean rel > 1%;
- server tick p95 rel > 3%;
- dispatched, installed or superseded per tick, from the summed per-invocation deltas, rel > 5%;
- on W2 and W3, frame p95 rel > 5%, or the share of frames over 50 ms changing by more than 1
  percentage point.

These screens can show that tracing changed a run. They cannot show it did not. Counts can match
while the population or cost of setups differs (review example: identical counts and a 0.2% tick
change, with setup time crossing the threshold). So a screen-passing C_D is read as the recorded run's
value under that assumption, and nothing stronger.

## Decision

**Threshold T(L)** = max(1 ms per tick, 2% of that round's ABSENT tick mean at load L). This is an
engineering policy for adding code to the dispatch path, not a quantity derived from other results.
For context, 2% is 22 to 38% of the 5.2 to 9.1% saving 0.9.0 measured on the ladder.

**Load-round state**, taking the first matching row and recording every condition that held:

| order | state | condition |
|---|---|---|
| 1 | VOID | the server capture is VOID (a VOID client report does not affect D), the run exceeded ten minutes, or a screen input is missing or non-finite |
| 2 | EXPOSURE_FAIL | the load's exposure rule failed |
| 3 | PERTURBED | any screen tripped |
| 4 | AT_OR_ABOVE | C_D >= T(L) |
| 5 | BELOW | otherwise |

A load is **determinate** when both rounds are BELOW or AT_OR_ABOVE.

| outcome | condition |
|---|---|
| **ELIGIBLE**: a D investigation may be proposed, with its own preregistration; nothing is claimed | at least one determinate load has AT_OR_ABOVE in at least one round |
| **STOP**: we choose not to investigate D, on the recorded, screen-admitted evidence at these loads | every load determinate, every load-round BELOW |
| **INDETERMINATE**: reported, nothing decided, no replacement runs in this attempt | anything else |

STOP is not a claim that the untraced workload has no worthwhile saving.

## Controls

Offline, synthetic inputs to the analyser:
- handler nesting counted once; an exception in a nested handler closing both brackets;
- a search nested in a search (subtracted once); a throwing nested search (subtracted once, depth
  restored); two sequential disjoint searches in one bracket (both subtracted); subtraction greater
  than bracket wall (VOID, not clamped);
- a boundary with handler or search depth 1 (VOID); B − A below 20 (VOID); a sequence gap, truncated
  trailer and hash mismatch (VOID); periodic cumulative records present alongside FINAL (C_D from FINAL
  only, same result whether periodic records are included or not);
- relative difference with a = 0 and r = 0, a = 0 and r > 0, and a missing input;
- every row of both decision tables, including several conditions holding at once (first match wins,
  all reasons kept).

Diagnostic branch, unit and game tests:
1. **Handler coverage:** the manifest test passes; it fails on a copy with one bracket removed, and on
   a test-only mixin targeting `PathNavigation` with no bracket.
2. **Server-thread only:** a selected handler invoked on a non-server thread records nothing.
3. **Nested handlers in the game:** a `moveTo` inside a `MoveToTargetSink`-driven request; the inner
   handler has invocations, and the outermost total counts its time once.
4. **Subtraction in the game:** a synchronous search forced through the refused-gate route. A second
   case uses a test evaluator that calls `findPath` again from inside its search; the nested search is
   subtracted once.
5. **Exception path:** a test evaluator whose `prepare` throws inside `createPath`; brackets close, the
   sync fallback runs, and its search is subtracted.
6. **Server sampler:** a game test issues requests from a test `Goal` subclass; samples show that
   class and method, and the TemptGoal observable does not match it.
7. **Client window:** in `ClientSingleplayerGameTest` on the diagnostic branch: a client-side request
   inside the window is counted; one begun before mark begin and one ending after mark end are
   STRADDLING; a client thread held inside a search past mark end by a test latch makes the client
   report VOID while the server capture stays valid.
8. **Tail-listener containment:** a test-only END_SERVER_TICK listener, registered after PathWeaver's
   own, busy-waits 20 ms per tick for 40 ticks. The invocation timer's mean over
   those ticks must exceed a matching run without the listener by at least 19 ms, and a test-only
   selected handler invoked from that listener must appear in C_D. If either fails, no measurement
   run counts and the timer design is revised.
9. **Interval edges:** a test-only listener adds a distinct marker cost in invocations A - 1, A, B - 1
   and B. Only A and B - 1 contribute to C_D and to the tick durations.
10. **Between-invocation work excluded:** a game test schedules, with `server.execute`, a task that
    calls `ServerLevel.sendBlockUpdated` (reaching the selected block-change handler) and a navigation
    request, and has the handler record `insideInvocation` at entry. The test passes only if that
    flag is observed false for the task's calls (placement observed, not assumed from scheduling),
    and their time, samples and counter changes appear in GAP and in none of C_D, the server samples
    or the summed invocation deltas. The same calls made from inside a tick (an END_SERVER_TICK test
    listener) must appear in C_D, the samples and the deltas, and not in GAP.

Test evaluators pass the real gates through the gametest harness's pinned compatibility tier, the
route the existing harnesses use. The latch in control 7 and the re-entrant evaluator in control 4 are
test-only, in the gametest source set.

## Preregistration

`FROZEN.txt`, committed before the first run: both jar hashes; the config bytes each harness actually
writes, captured after generation; the world copy manifest; the dedicated server mod list; this run
order.

### Workloads, windows and exposure

Exposure uses RECORDING runs: the server sampler and the summed per-invocation counter deltas
(dispatched counts below are those deltas). ABSENT and IDLE
runs are checked on dispatched count alone.

| id | where | window (mark begin to mark end) | exposure |
|---|---|---|---|
| W1 | dedicated server, `bench/ladder.sh` arena, rungs 1000 and 5000 (two loads) | the ladder's own per-rung measurement window | at least 2,000 dispatched |
| W4 | same arena, 300 cows within 8 blocks of the Bench fake player holding wheat, teleported per burst as the ladder does | 60 s beginning 10 s after the first teleport | at least 2,000 dispatched, and at least 50% of server samples show the TemptGoal observable |
| W2 | client pack village scenario, Enhanced Cats absent | PWMARK one to PWMARK end | at least 1,000 dispatched |
| W3 | as W2, Enhanced Cats added | as W2 | at least 5,000 dispatched, and at least 50% of server samples (integrated server thread) show the Enhanced Cats observable |

Shipped defaults on W1 and W4; the player's config on W2 and W3.

### Run order

Per load, two rounds, each run a fresh boot in a fresh directory. R1: ABSENT, IDLE, RECORDING. R2:
RECORDING, IDLE, ABSENT. Workload order W1, W4, W2, W3. Warmup: the ladder's own settle before each
rung; the client scenario's 60 s settle and base phase. Each run under ten minutes, or VOID. A
replacement for any run is a new preregistered attempt in a new directory; the original stays and is
reported.

## Deferred, carried forward

Any future measurement of scheduling, obsolete work or request lifecycle must first resolve, at
least: PARKED-never-collected and INSTALL_FAILED consumption; positive consumption evidence with its
own completion law; NOT_APPLICABLE obligations for refused or pre-registration failures; publication
stamps that follow their consumers; phase at invalidation versus phase observed after it; outcome
ownership per attempt frame; window cohorts for open work; a strictly positive CPU denominator; that
an unconsumed-result bound does not bound policies omitting installed intermediates; and saturation
as positive evidence rather than a ceiling (reviews `phase1-be258c31-verdict.md`,
`phase1-6fd91cb-verdict.md`).

## What this cannot say

One machine carrying a foreign process that holds about 8 of 32 cores, four workloads, two rounds.
STOP means we chose not to investigate D from what these recorded runs showed. It says nothing about
worker CPU, scheduling, excluded code, or other packs.
