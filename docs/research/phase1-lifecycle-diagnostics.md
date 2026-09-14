# Phase 1 lifecycle diagnostics: instrumentation spec and preregistration

Draft for independent review before anything is implemented. Source locations refer to commit
`080fc42eec1809507305c67e891eb8c45bc85a95` on master.

## What Phase 1 is, and what it is not

Phase 1 measures where path requests spend main-thread time, worker CPU and waiting time, and what
becomes of each one. It changes no routing, admission, install or return behaviour. Its output is a
stop/go decision. **A valid outcome is no production optimisation at all.**

It answers five questions, each tied to a decision in the last section:

- **Q1** How much main-thread time does setting up one dispatched request cost, by section, and how
  much of it could a cheaper implementation plausibly remove?
- **Q2** How much worker CPU goes to requests whose answer nobody consumes, split by *when* the
  request stopped being wanted: before its worker dequeued it, while it waited at the start gate,
  during the search, or after the search finished but before the drain?
- **Q3** How long from a request to a consumed path, and how often admission refusals push searches
  back onto the server thread, with what synchronous cost?
- **Q4** Is the worker pool actually saturated at any preregistered load (occupancy, queue wait,
  refusals), or is obsolete work only spare-core CPU?
- **Q5** (separate stream) Which mods run path searches on the client, on which thread, at what CPU
  cost? Report only.

It does not measure or propose request coalescing (A/B), tolerance changes (C) or client-side
refusal (E). Those are behavioural and out of this lane.

## Where the code lives

A branch, `diag/phase1`, not merged to master. Hooks are compiled into PathWeaver behind
`static final boolean TRACE = Boolean.getBoolean("pathweaver.trace")`, read once at class
initialisation, so with the property absent the JIT removes each guarded block. One new package,
`dev.pathweaver.diag`, holds the recorder and writer. The only other production edits are guarded
`if (LifecycleTrace.TRACE) LifecycleTrace.x(...)` calls at the sites below, plus a
`/pathweaver trace start|stop` subcommand that exists only when the property is set.

If Phase 2 needs none of it, the branch is not merged and no release carries it.

## Clocks

- **Wall time** is `System.nanoTime()` everywhere. It is monotonic and shared by all threads in one
  JVM, which is what cross-thread ordering needs. Its resolution on this machine is measured and
  recorded at trace start (the smallest non-zero delta over 10^6 consecutive reads).
- **Worker CPU time** is `ThreadMXBean.getCurrentThreadCpuTime()`, read only at the two worker
  events that bracket a search (W3, W4). If `isCurrentThreadCpuTimeSupported()` is false, or the
  call returns -1, the field is written as UNKNOWN, never as 0.
- **Main-thread sections are wall time only.** A CPU-time read costs microseconds per call on
  Windows. Around every setup section, at thousands of dispatches a second, it would cost more
  than some of the sections it measures. The server thread is busy for the whole setup, so its wall
  time over a section is close to CPU time. The report says "wall" wherever that is what was
  measured.
- **Ticks** are `server.getTickCount()` at the event.

## Identity

Two identities, because setup starts before a `RequestKey` exists.

- **attemptId** (long, main thread, incremented at A0): one entry into
  `pathweaver$asyncCreatePath` that passed the depth check. Every attempt ends in exactly one A-terminal.
- **RequestKey** (`serverEpoch, requestToken, entityId`, production type): exists only from
  `rt.nextRequestKey(entityId)` at `PathNavigationMixin.java:816`. The event carrying it also
  carries its attemptId, which is the join between the two populations.

A failed setup, a cache hit or an early return is an attempt with no key. None of them is counted
as a dispatched request.

Synchronous searches are attributed to an attempt through a main-thread "current attempt" slot, set
at A0 and cleared when the wrapped `createPath` call returns. Every place that opts a call into
PathWeaver already brackets it with `pathweaver$navigationRequestDepth`: increments at
`PathNavigationMixin.java:182` (`pathweaver$enterMovementRequest`, used by the brain sink), `:225`,
`:415`, `:440`, `:457`, `:473`, and the matching decrements at `:194`, `:231`, `:422`, `:444`, `:461`,
`:477`. The slot is cleared wherever the depth returns to zero, so a nested request cannot clear its
outer attempt. A synchronous `findPath` on the server thread with no current attempt is
`UNATTRIBUTED_SYNC`: a direct `createPath` from a mod, or a vanilla caller outside the four wrappers.

## Events

Every event records `(type, nanoTime, tick, threadKind, attemptId | NONE, key | NONE, fields)`.
threadKind is SERVER, WORKER, RENDER or OTHER, taken from the thread (`PathWeaverThread.Worker`,
the server thread, the render thread).

### Attempt stream (server thread, `PathNavigationMixin.java`)

| event | site | brackets / meaning | fields |
|---|---|---|---|
| A0 ATTEMPT | after `:574` depth check | start of an attempt | entityId, mob type, origin, RequestTarget hash, evaluator class |
| A1 SUPERSEDED_OLD | after `:600` when `supersede` returned true | this attempt replaced the previous key | the replaced key (read from the sink before `:600`) |
| A2 PRESERVED | `:594` branch | same target already pending | pending key |
| AT EARLY_RETURN | each `return` from `:606` to `:670` | attempt handed to vanilla, with the reason | reason: VANILLA_SAME_TARGET `:606`, DISABLED `:610`, TOLERANCE_REUSE `:612`, NOT_RUNNING `:615`, NOT_SERVER `:616`, NO_EVALUATOR, GATE_DENIED `:618`, BREAKER_OPEN, PATHFINDER_SUBCLASS `:633`, LAND_REGISTRY `:648`, MOB_ORIGIN `:651`, FORCE_SYNC `:655`, ALREADY_REGISTERED `:658`, OWES_EPILOGUE `:669` |
| S0 SETUP_BEGIN | `:732`, before the attribute captures | start of `pathweaver$dispatchSearch` | |
| S1 CACHE | around `:753`-`:767` | cache key build plus lookup, wall | active?, served? (served is an A-terminal: CACHE_SERVED) |
| S2 REGION | around `:774` | `new PathNavigationRegion`, wall | radius |
| S3 EVALUATOR | around `:780`-`:781` | evaluator clone plus `new PathFinder`, wall | |
| S4 REGISTER | around `:816`-`:832` | key issue, `sink.register`, brain-sink note, wall | key (first event with a key) |
| S5 SUBMIT | around `:834`-`:850` | `pool.submit`, wall | accepted?, pool inFlight, maxInFlight, executor queue size. Refused is an A-terminal: POOL_REFUSED |
| S6 OPTIMISTIC | `:858`-`:866` | optimistic target bookkeeping, wall | |
| S7 PREPARE | around `:886` | `freshEval.prepare(region, mob)`, wall | |
| S8 ARM | around `:890`-`:906` | arm epilogue plus cache remember, wall | |
| S9 GATE_OPEN | at `:946` | start gate opened; setup complete | total setup wall S0 to S9 |
| SF SETUP_FAILED | `:911` catch | setup threw | stage, key if registered |
| SY SYNC_SEARCH | PathFinder.findPath on the server thread (new diagnostic mixin, HEAD and RETURN) | one synchronous search, wall | current attemptId or UNATTRIBUTED_SYNC, mob type, result null? |

### Worker stream (`PathWorkerPool.java` and the search callable)

| event | site | brackets / meaning | fields |
|---|---|---|---|
| W1 DEQUEUE | first line of the runnable, `:83` | a worker took the request | key; queue wait = W1 minus S5 |
| W2 STILL_WANTED | `:99` | the pre-search check | answer |
| W3 SEARCH_BEGIN | after `awaitStart()` returns, `:799` | gate released, search starts | gate authorised?, thread CPU |
| W4 SEARCH_END | after `findPath` returns or throws, `:809` | search finished | thread CPU, wall since W3, status (SUCCESS, NO_PATH, FAILED, CANCELLED), path node count |
| W5 ENQUEUED | after `onDone.accept`, `:124` | result is in the installer queue | |

### Main-thread terminal stream

| event | site | meaning | fields |
|---|---|---|---|
| M1 INVALIDATED | `EntityInstallSink.java:237` `supersede`, `:245` `cancel` | main thread removed the registration | key, reason SUPERSEDED or NAVIGATION_STOPPED |
| D1 DELIVERED | `ResultInstaller.java:61`, after `deliver` | drain handled the result | key, delivery: INSTALLED, PARKED, ARRIVED_STALE, INSTALL_REJECTED, INSTALL_FAILED, NO_PATH, SEARCH_FAILED, CANCELLED_BEFORE_START, NOT_REGISTERED (the key was already invalidated), HANDOFF_FAILED; cache offered? |
| D2 EPILOGUE | `ResultInstaller.java:65` | `done()` ran, wall | key |
| C1 BRAIN_COLLECTED | `EntityInstallSink.java:541` on a hit | a parked path was taken by its behaviour | entityId, and the key parked for that slot (the diagnostic side-map keeps it) |

The drain runs at END_SERVER_TICK (`PathWeaverRuntime.java:476`-`:484`). D1 and D2 carry that tick.

### Client stream (Q5, separate file, never joined to the above)

| event | site | fields |
|---|---|---|
| E1 CLIENT_FINDPATH | PathFinder.findPath where `mob.level().isClientSide()`, HEAD and RETURN | thread name, mob type, wall, result null?, sampled caller |

The caller is taken for 1 call in 64 with a `StackWalker`, as the first frame outside
`net.minecraft.world.entity.ai.navigation`, `net.minecraft.world.level.pathfinder` and PathWeaver. It
is attributed to a jar through the class's code source. No call is refused and no return changed.

## Classifying each request

One row per RequestKey. The **terminal class** comes from the first main-thread terminal event: M1,
or D1 when no M1 came first. A later D1 for an invalidated key (CANCELLED_BEFORE_START,
NOT_REGISTERED) is an attribute of that row, never a second request. That is the double count in
today's status rows, where "target changed" and "nobody wanted it" can describe the same request.

For keys with M1 at time tM, the **invalidation phase** comes from the same key's worker events:

| phase | condition |
|---|---|
| QUEUED | no W1, or W1 > tM |
| GATE_WAIT | W1 <= tM, and no W3 or W3 > tM |
| SEARCHING | W3 <= tM < W4 |
| COMPLETED_UNDRAINED | W4 <= tM |
| UNKNOWN | tM within epsilon of the boundary that decides it, or a needed worker event is missing |

epsilon is 200 µs, fixed here. W1 and W3 are taken a few instructions after the state they mark, and
M1 before `inFlight.remove`, so near-ties cannot be ordered and are not guessed.

**Obsolete worker CPU** for a key is its search CPU (W3 to W4) that bought nothing consumed:
- all of it, for COMPLETED_UNDRAINED and for any SUCCESS whose delivery was not INSTALLED, PARKED
  then C1, or served to another mob from the cache;
- for SEARCHING, the fraction after tM, estimated as CPU × (W4 − tM) / (W4 − W3) and always labelled
  an estimate;
- none, for QUEUED and GATE_WAIT, which never searched.

**Consumed** means INSTALLED, or PARKED followed by C1 for the same key within its expiry.

**Useful-path latency** for a consumed key is D1 (or C1) minus A0 of its attempt, in ticks and wall.

## Window, drops, unfinished requests

- `/pathweaver trace start` opens the window at the next A0; `stop` closes it. Attempts with A0
  before start or after stop are excluded, along with their keys. Events for included keys keep
  recording for 200 ticks after stop. A key without a terminal by then is UNFINISHED and counts in
  no class.
- Events go into a lock-free queue drained by one writer thread into a binary file. The queue holds
  at most 4,000,000 events. When it is full an event is dropped and a per-type drop counter
  increments; the counters go in the file trailer. A key with any dropped event is UNKNOWN in every
  class that needed it. **A window whose drops exceed 0.1% of its events is void.**
- A writer failure stops tracing, writes a FAILED marker and never throws into the game. Tracing
  never changes a return value, and every hook body sits in try/catch(Throwable) that disables
  tracing on first failure.

## Observer overhead

- **Per-event cost**, offline: a JMH-style loop in a unit test reports nanoseconds per event for
  each event shape, single-threaded and with 30 producer threads. Reported, not gated.
- **In-situ cost, gated**: at every preregistered load the same jar runs with the property absent,
  then with it set and tracing started, in both rounds. **If trace-on server tick mean is more
  than 2% above trace-off at a load, in either round, that load's diagnostic results are void.**
- **Compiled-in and off equals release behaviour**: the `diag/phase1` jar with the property absent
  passes the full witness suite, the unit suite and the harness roster, and sits within the
  regression method's 3% of the 0.9.0 release jar on the zombie ladder.

## Controls for the instrument

Positive controls must produce the class they target; adverse controls must produce UNKNOWN or a
refusal, never a class.

- **Offline, synthetic streams** through the joiner: one stream per phase; a CANCELLED delivery
  after SUPERSEDED (one row); ties within epsilon (UNKNOWN); a missing W3 (UNKNOWN); a dropped
  event (UNKNOWN); two terminals out of order (refused with an error, not silently resolved); an
  attempt with no key (never a request).
- **Game tests, deterministic** (a test-only evaluator that blocks in `prepare` or mid-search on a
  latch, compiled only into the gametest source set):
  1. QUEUED: pool of one thread held busy, a second request superseded before it dequeues.
  2. SEARCHING: supersede while the latch holds a search open.
  3. COMPLETED_UNDRAINED: wait for W5, supersede in the same tick before END_SERVER_TICK.
  4. Adverse: scenario 2 with the W3 event suppressed by a test flag. Must come out UNKNOWN.
  5. Cache hit and setup failure produce attempts with no key.
- **Workload controls**, below: the zombie ladder must not show a storm; Enhanced Cats must, or it
  cannot serve as the positive workload.

## Preregistration

Committed before any trace run. Every attempt in a fresh directory; nothing is overwritten.

### Frozen

The `diag/phase1` jar sha256 and the 0.9.0 release jar sha256, the player config and the defaults
config, the world copy (`PW-repro-base` sha256 manifest), the dedicated server's mod list, run
order, repetitions, warmup. Recorded in `JARS.txt` and `FROZEN.txt` before the first run.

### Workloads and load levels

| id | where | load | role |
|---|---|---|---|
| W1 | dedicated server, zombie ladder (`bench/ladder.sh`) | 1000 and 5000 zombies | no-storm control, and the main D measurement |
| W2 | client pack, village scenario, Enhanced Cats absent | as in h1 | ordinary play |
| W3 | client pack, village scenario, Enhanced Cats added | as in h1-e | positive-control storm |
| W4 | dedicated server, ladder arena: 300 cows within 8 blocks of the Bench fake player holding wheat, teleported per burst as in the ladder | 300 cows | vanilla every-tick entity-target workload (TemptGoal), chosen before any result |

Each load runs trace-off then trace-on, two rounds, the second reversed, each run under ten minutes.
The same jar and the same worker configuration (shipped defaults on W1 and W4, the player's config
on W2 and W3) throughout. W3 is a valid positive control only if SUPERSEDED is at least 50% of its
dispatched keys; otherwise W3 is reported and used for nothing.

### Primary endpoints, absolute

Per load, median of the two rounds, trace-on runs:

- E1: server-thread setup wall per tick (sum S0 to S9 over the tick), and per accepted dispatch
  (median, p95), by section.
- E2: worker search CPU per second, split into consumed, obsolete by phase, and UNKNOWN.
- E3: server tick mean and p95, in ms, from the trace-off runs (the trace-on values are only for the
  overhead gate).
- E4: useful-path latency, ticks, median and p95, by origin.
- E5: admission refusals per second, and synchronous search wall per tick by attribution
  (POOL_REFUSED, each EARLY_RETURN reason, UNATTRIBUTED_SYNC).
- E6: pool occupancy (in-flight over maxInFlight at S5) and queue wait (W1 minus S5), median and p95.
- E7 (client stream): client-side findPath wall per second by thread and by attributed jar.

### Decisions, written before the result

**D, cheaper per-request setup. GO to design** only if, at some W1 or W4 load in both rounds, the
removable setup (S1 + S2 + S3 wall, the sections a cheaper implementation could plausibly shrink
without moving `prepare` or any live read off the main thread) is at least **2% of that load's
trace-off tick mean**. Otherwise **STOP**: D is not pursued, whatever the other endpoints show.
GO means designing D with its own preregistration. It does not mean shipping it.

**Obsolete work, for any future opt-in scheduling experiment (not scheduled).** An experiment may
even be proposed only if, at some load in both rounds, obsolete worker CPU (E2, excluding UNKNOWN)
is at least **25% of all worker search CPU**, **and** that load shows saturation: occupancy p95 at
least 0.9 or admission refusals in at least half of its one-second windows. Otherwise obsolete work
is recorded as spare-core CPU and nothing is proposed.

**Client stream.** Reported as a table by jar. No decision follows from it in Phase 1.

**Instrument validity.** A load's results count only if its overhead gate passed, its drops stayed
under 0.1%, its UNKNOWN share of invalidated keys is under 10%, and the game-test controls passed on
the jar used. Anything else is reported as void, with the reason.

## What this cannot say

One machine, carrying a foreign process that holds about 8 of 32 cores, a handful of workloads, two
rounds. It locates cost inside PathWeaver's request lifecycle under those loads. It does not measure
any behavioural policy, and a STOP on D is a finding, not a failure to find one.
