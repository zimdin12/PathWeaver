# Phase 1 diagnostics, v3: one decision, one report

Draft for independent review before anything is implemented. Replaces
`phase1-lifecycle-diagnostics-v2.md` at `6fd91cb1f28d27447e7aca1ddbce1fdc04f2ef63` (REVISE, review
`phase1-6fd91cb-verdict.md`), kept unedited, as is v1 at `be258c31`. Source locations refer to
`080fc42eec1809507305c67e891eb8c45bc85a95`; `src` is unchanged since.

## What changed from v2, and why

Two reviews found that every per-request law (lifecycle phases, consumption, obsolete CPU,
saturation) opened further obligations, and that the scheduling decision could not be stated as a
bound. A scheduling experiment is not planned for 0.10. So v3 removes everything that only served it.

**Phase 1 now makes one decision**, whether an investigation into cheaper request handling on the
server thread (D) may be proposed, **and produces one report**, of path searches run on client threads.

Removed, and deferred to any future phase that needs them: request lifecycle phases, the worker
marker, consumption and censoring laws, C_S, saturation evidence, and the density control. A future
scheduling measurement must answer every finding in both reviews about those parts before it is
proposed. They are listed at the end so they are not lost.

## Scope

Diagnostic only. No routing, admission, install, cancellation, return or exception behaviour changes.

- **D decision.** How much server-thread wall time PathWeaver's request-side hooks take, excluding
  the synchronous searches inside them. That is an empirical upper figure for the recorded run, under
  the assumptions stated below, not a proof about an untraced baseline.
- **Client report.** Wall time of path searches executed on client threads, by thread and attributed
  jar. Report only; no decision.

## What C_D covers, exactly

**Included: request-side hooks.** Every mixin handler method, in the compiled `dev.pathweaver.mixin`
classes, whose target is one of `PathNavigation`, `WallClimberNavigation`, `MoveToTargetSink` or
`ServerLevel`, plus `PathWeaverRuntime.onEndTick` (`PathWeaverRuntime.java:476`, which contains the
drain). At `080fc42` that is the movement capture, deferred-result, arm, recompute, `createPath`,
`stop`, brain-sink and block-change handlers. It includes the movement and depth bookkeeping at
`PathNavigationMixin.java:166-168`, `:250-253` and `:438-444`, because those run inside those
handlers. The list is **derived, not typed**: a diagnostic-branch test reads the compiled mixin
classes, selects handlers by annotation and target, and fails if any selected handler lacks a
bracket (see controls).

**Wrapping handlers include the wrapped original.** A `@WrapOperation` handler's body contains the
vanilla call it wraps, so vanilla `createPath` work inside it is counted. Only synchronous
`PathFinder.findPath` time inside a bracket is subtracted.

**Excluded, and outside D:** handlers that run inside a search, on any thread
(`PathFinderMixin`, `PathfindingContextMixin`, `WalkNodeEvaluatorMixin`,
`AmphibiousNodeEvaluatorMixin`, `FlyNodeEvaluatorMixin`, `LandPathTypeRegistryMixin`), command
handling, config loading and startup. Their cost inside a synchronous search is inside the
subtracted search time. D makes no claim about them.

## Measurement

**Brackets.** Each included handler body is wrapped in `try/finally`. Nesting is tracked with a
server-thread-only depth counter. The accumulator adds wall time only for the **outermost** bracket,
so a handler inside another handler is counted once. Brackets record only when the current thread is
the server's own thread (captured at server start). On any other thread they do nothing.

**Synchronous search subtraction.** A MixinExtras `@WrapMethod` (the build resolves MixinExtras 0.5.4, whose jar contains `injector/wrapmethod/WrapMethod`) on
`PathFinder.findPath(PathNavigationRegion, Mob, Set, float, int, float)`, exception-safe, records wall
when the thread is the server thread and the bracket depth is above zero. That wall is subtracted
from the enclosing outermost bracket. A search on the server thread at depth zero is outside every
bracket and outside C_D.

**Window.** `/pathweaver trace start` and `stop` take effect at the next `END_SERVER_TICK`, before
PathWeaver's own end-tick hook. On the server thread at that point every bracket is closed. The
recorder asserts depth 0 at both boundaries, and a non-zero depth voids the window. The window is
the ticks strictly between the two boundaries. A bracket belongs to the window if it opens and
closes inside it. There are no straddling brackets, because both boundaries are at depth 0.

**C_D** = (sum over outermost brackets in the window of (bracket wall minus subtracted search wall))
divided by the number of ticks in the window, in ms per tick.

**Clock.** `System.nanoTime()`. Resolution is measured at property load, before any window, and
written to the header.

## Recorder

- Events: bracket totals (per handler id: count, outermost wall, subtracted search wall), window
  start and stop with tick numbers, depth checks, sampled callers (below), client events, and status
  snapshots (below).
- Aggregation happens on the server thread into fixed arrays, so the hot path does not allocate.
  Totals are written once per second of ticks by a writer thread, from an immutable copy, each with a
  sequence number.
- **Positive finalization.** A file is valid only if it ends with a FINAL trailer: contiguous sequence
  numbers, per-handler totals, and a sha256 of the body, flushed after the stop boundary. Missing,
  truncated or mismatched trailers, sequence gaps, a depth assertion failure or recording disabled
  by a failure all make the window VOID.
- **Exception safety.** Argument construction and recorder bodies sit inside the guarded block. A
  Throwable disables recording for the process and voids the window. Tracing never changes a
  return value or an exception.
- Activation: the JVM property `pathweaver.trace`. A `static final` field initialised from a property
  is not a compile-time constant, so disabled cost is measured, not assumed.

## Client report

- **Hook:** the same `@WrapMethod` on `PathFinder.findPath`, recording a client event when the
  current thread is not a `PathWeaverThread.Worker` and not the server thread, and
  `mob.level().isClientSide()` is true. That expression is evaluated inside the guard; if it throws,
  recording stops and the client report is VOID.
- **Fields:** thread name, mob type, wall, returned null or threw, and for 1 search in 64 (by a
  per-thread counter) the first stack frame outside `net.minecraft.world.entity.ai.navigation`,
  `net.minecraft.world.level.pathfinder` and `dev.pathweaver`, with its class's code-source jar.
- **Output:** wall per second of window time by thread, and by attributed jar with the sample count
  behind each attribution. Nothing is decided from it.

## Observer screens

States, all on the `diag/phase1` jar: **ABSENT** (property unset), **IDLE** (property set, not
recording), **RECORDING**. ABSENT versus IDLE measures the cost of activation in the diagnostic jar,
not the cost of compiled-in hooks. That is a separate comparison, the diagnostic jar ABSENT against
the 0.9.0 release jar on W1, using the regression method's 3% rule.

**Status snapshots.** `/pathweaver status` is read at the start and stop boundaries in every state.
Its dispatched, installed and "target changed" counts exist without tracing, so the three states can
be compared on the same production counters.

A load-round is **PERTURBED** if, between RECORDING and ABSENT in that round, any of these hold:
- server tick mean differs by more than 1%, either direction;
- server tick p95 differs by more than 3%, either direction;
- dispatched per tick, installed per tick or "target changed" per tick differs by more than 5%
  relative, either direction;
- on client workloads, frame p95 differs by more than 5%, or the share of frames over 50 ms differs by
  more than 1 percentage point.

These screens can show that tracing changed the run. Passing them does not prove it did not. That is
the assumption under which a non-perturbed C_D is read as an upper figure for request-side hook time.

## Decision

**Threshold T(L)** for a load L: the larger of 1 ms per tick and 2% of L's ABSENT server tick mean
(that round's). This is an engineering policy. D would add new code on the dispatch path, and below
this much time on the server thread I do not think that code is worth its risk. It is not derived
from the mod's other results. For context only: 2% is between 22% and 38% of the 5.2 to 9.1% tick
saving 0.9.0 measured on the ladder.

Each load-round gets exactly one state:

| state | condition |
|---|---|
| VOID | the window is VOID, or the run exceeded ten minutes |
| EXPOSURE_FAIL | the load's exposure rule failed |
| PERTURBED | an observer screen tripped |
| BELOW | none of the above, and C_D < T(L) |
| AT_OR_ABOVE | none of the above, and C_D >= T(L) |

A load is **determinate** when both rounds are BELOW or AT_OR_ABOVE.

| outcome | condition |
|---|---|
| **ELIGIBLE** (a D investigation may be proposed, with its own preregistration; nothing is claimed) | at least one determinate load has AT_OR_ABOVE in at least one round |
| **STOP** (D is not pursued) | every load is determinate, and every load-round is BELOW |
| **INDETERMINATE** (reported, nothing decided, no replacement runs in this attempt) | anything else |

Nothing above uses a run's C_D unless that load-round is BELOW or AT_OR_ABOVE.

## Controls

Offline, synthetic inputs to the analyser:
- nested brackets counted once; an exception inside a nested bracket closes both; a synchronous
  search inside a nested bracket subtracted once;
- a window boundary with depth 1 (VOID); a sequence gap, truncated trailer and hash mismatch (VOID);
- each row of both decision tables, including mixed rounds and a determinate AT_OR_ABOVE alongside an
  INDETERMINATE load (ELIGIBLE).

Diagnostic branch, unit and game tests:
1. **Handler coverage (positive and adverse):** the ASM coverage test passes on the diagnostic branch.
   It fails on a copy with one bracket removed from a selected handler, and on a test-only mixin
   targeting `PathNavigation` with no bracket.
2. **Server-thread only:** a request-side handler invoked from a non-server thread in a unit test
   records nothing.
3. **Nesting in the real game:** a game test calls `moveTo` from inside a `MoveToTargetSink`-driven
   request; the per-handler totals show the inner handler counted, and the outermost total counts its
   time once.
4. **Subtraction in the real game:** a game test forces a synchronous search (compatibility gate
   denies the evaluator, the existing refused harness route) and checks that its findPath wall appears
   as subtracted time, not as C_D.
5. **Client hook:** in `ClientSingleplayerGameTest` on the diagnostic branch, a navigation request on
   a client-side mob produces a client event, and a server-side request does not.
6. **Exception path:** a test evaluator whose `prepare` throws inside `createPath`; the bracket
   closes, the sync fallback runs, and its search is subtracted.

The test evaluators pass the real gates through the gametest harness's pinned compatibility tier,
the route the existing harnesses use. No production timing is bypassed.

## Preregistration

`FROZEN.txt`, committed before the first run: both jar hashes; the config bytes each harness actually
writes, captured from the server or client directory after generation (not the input template); the
world copy manifest; the dedicated server mod list; this run order.

### Workloads, windows and exposure

| id | where | window | exposure rule (RECORDING runs, from status snapshots and caller samples) |
|---|---|---|---|
| W1 | dedicated server, `bench/ladder.sh` arena, rungs 1000 and 5000 (two loads) | the ladder's own per-rung measurement window; start and stop sent at its boundaries | at least 2,000 dispatched in the window |
| W4 | same arena, 300 cows within 8 blocks of the Bench fake player holding wheat, teleported per burst as the ladder does | 60 s after the first teleport settles | at least 2,000 dispatched, and at least 50% of sampled callers are `TemptGoal` |
| W2 | client pack village scenario, Enhanced Cats absent | PWMARK one to PWMARK end | at least 1,000 dispatched |
| W3 | as W2, Enhanced Cats added | as W2 | at least 5,000 dispatched, and at least 50% of sampled callers from the Enhanced Cats jar |

ABSENT and IDLE runs have no caller samples; their exposure is the dispatched count alone. Shipped
defaults on W1 and W4; the player's config on W2 and W3.

### Run order

Per load, two rounds, each run a fresh boot in a fresh directory. R1: ABSENT, IDLE, RECORDING. R2:
RECORDING, IDLE, ABSENT. Workload order W1, W4, W2, W3. Warmup: the ladder's own settle before each
rung; the client scenario's 60 s settle and base phase. Each run under ten minutes, or VOID.

A replacement for any run is a new preregistered attempt in a new directory; the original stays and
is reported.

## Deferred, carried forward so it is not lost

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
A STOP means that, at these loads, PathWeaver's request-side hooks did not take enough server-thread
time for a cheaper implementation to be worth proposing. It says nothing about worker CPU,
scheduling, or code outside the included hooks.
