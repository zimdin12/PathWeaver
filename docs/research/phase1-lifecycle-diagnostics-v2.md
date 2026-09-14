# Phase 1 lifecycle diagnostics, v2: instrumentation spec and preregistration

Draft for independent review before anything is implemented. Replaces
`phase1-lifecycle-diagnostics.md` as of `be258c31bee4894c84893977ee184347ad3654cf`, which was
rejected (review `phase1-be258c31-verdict.md`, sha256 `a8487b10...`) and is kept unedited. Source
locations refer to `080fc42eec1809507305c67e891eb8c45bc85a95`; `src` is unchanged since.

## What changed from v1, and why

v1 tried to measure exactly how much work was wasted and to gate decisions on those measurements.
The review showed that several of those numbers cannot be observed as defined: the CPU split around
an invalidation, cache consumers, and the worker's state at a moment the main thread picked. So v2
changes the kind of decision the data supports.

**Every decision in Phase 1 is a STOP rule on a ceiling.** A ceiling is a number that can only
overstate the quantity a later optimisation could recover. Unknown, censored and observer-inflated
amounts all go into the ceiling, never out of it. A ceiling below its threshold stops that line of
work. A ceiling above its threshold stops nothing and proves nothing; it permits a separately
preregistered investigation. No Phase 1 number is presented as a realizable gain.

Descriptive measurements (phases, queue waits, latencies) are still collected, reported with their
UNKNOWN shares, and used for nothing that decides.

## Scope

Diagnostic only. No routing, admission, install, cancellation or return behaviour changes.

- **Q1** Ceiling on the server-thread time PathWeaver adds to path requests, beyond the searches
  themselves. Decides whether a "cheaper setup" investigation (D) may be proposed.
- **Q2** Ceiling on worker search CPU spent on requests that ended without their own navigation
  consuming the result, plus direct evidence of whether the pool was ever capacity-bound. Decides
  whether a scheduling experiment may be proposed.
- **Q3** Descriptive only: request lifecycle phases, queue wait, time to consumption, refusals.
- **Q4** Descriptive only, separate stream: path searches executed on client threads, wall time,
  by thread and attributed jar.

Out of scope, and not claimed: cache lineage (whether a result later served another mob), movement
success after collection, anything about behavioural policies.

## Build, activation and artifacts

- Branch `diag/phase1`, never merged unless a later preregistered phase needs it.
- Activation is the JVM property `pathweaver.trace`, read into a `static final` field at class
  initialisation. That field is not a compile-time constant, so the disabled cost is **measured**
  (below), not assumed from JIT elimination.
- Three runtime states, measured separately: **ABSENT** (property unset), **IDLE** (property set,
  recording stopped), **RECORDING**.
- Artifacts: the 0.9.0 release jar (`47e24d24...`) and the `diag/phase1` jar, both hashed in
  `FROZEN.txt` before any run. Game-test controls run in the `diag/phase1` gametest source set, which
  adds a test-only hook class activated by `pathweaver.trace.testHooks`. It is absent from the
  production jar, and every place a test bypasses a real gate is listed in the test's Javadoc.

## Recorder

- Every event carries a per-producer-thread sequence number, `System.nanoTime()`, the server tick,
  and the producing thread's kind (SERVER, WORKER, RENDER, OTHER).
- Producers write to per-thread buffers; one writer thread drains them to a file.
- **No dropping.** If a buffer cannot accept an event, recording stops, the window is marked VOID
  with the reason, and producers go idle. No partial window is ever analysed.
- **Positive finalization.** A capture is valid only if its file ends with a FINAL trailer: per-type
  event counts, per-producer first and last sequence numbers, and a sha256 of the body, written
  after the last drain and flushed. A missing, truncated or mismatched trailer, a sequence gap, or
  recording stopped by a failure makes the window VOID.
- **Exception safety.** Argument construction and recorder bodies both sit inside the guarded
  block. Any Throwable disables recording for the process and marks the window VOID. Tracing never
  alters a return value or an exception path.
- Clock resolution is measured once at property load, before any window opens, and written to the
  file header.

## Attempt law (server thread)

An **attempt** is one call to `PathNavigation.createPath(Set,int,boolean,int,float)` on a server
navigation, bracketed exception-safely by a MixinExtras `@WrapMethod` on that method. That bracket
is the only place attempts open and close. It covers every call, including unwrapped ones with
depth 0.

- **AO ATTEMPT_OPEN**, wrap entry: attemptId, navigation identity, entityId, mob type,
  requestDepth, origin. Nothing that can throw or does not yet exist (no RequestTarget, no
  evaluator class).
- **AC ATTEMPT_CLOSE**, wrap exit in `finally`: attemptId, wall since AO, thrown?, and the
  **outcome** recorded for this attempt, exactly one of:
  `DEPTH_ZERO` (`:574`), `EMPTY_TARGETS` (`:577`), `BELOW_MIN_Y` (`:578`), `CANNOT_UPDATE` (`:579`),
  `PRESERVED` (`:594`), `VANILLA_SAME_TARGET` (`:606`), `DISABLED` (`:610`),
  `TOLERANCE_REUSED` (`:612`, returns the existing path), `NOT_RUNNING` (`:615`), `NOT_SERVER`
  (`:616`), `NO_EVALUATOR` (`:617`), `GATE_DENIED` or `BREAKER_OPEN` (`:618`-`:625`),
  `PATHFINDER_SUBCLASS` (`:633`), `LAND_REGISTRY` (`:648`), `MOB_ORIGIN` (`:651`), `FORCE_SYNC`
  (`:655`), `ALREADY_REGISTERED` (`:658`), `OWES_EPILOGUE` (`:669`), `CACHE_SERVED` (`:763`),
  `POOL_REFUSED` (`:850`), `SETUP_FAILED` (`:911`), `DISPATCHED` (`:910`).
  Each site sets a per-navigation outcome slot immediately before its return. An attempt that
  closes with the slot unset is `OUTCOME_UNRECORDED` and makes the window VOID. That is the check that
  the outcome list is closed.
- **Scope.** Each navigation keeps its own stack of open attemptIds, pushed at AO and popped in the
  `finally` at AC. Nested attempts, attempts on other navigations, sequential calls in one wrapper,
  and exceptions all restore the enclosing attempt exactly.
- **SY SYNC_SEARCH**, `@WrapOperation` on the `PathFinder.findPath` invocation inside that same
  `createPath` (exception-safe): wall, result null or threw, and the attemptId on top of **this
  navigation's** stack. A synchronous search is therefore attributed to the attempt of the
  navigation that ran it, and to nothing else. A `findPath` reached any other way is not an SY
  event; the client stream and a per-thread total cover it.
- **SS sections**, descriptive only: `S_CACHE` `:753`-`:767`, `S_REGION` `:774`, `S_EVAL`
  `:780`-`:781`, `S_REGISTER` `:816`-`:832`, `S_SUBMIT` `:834`-`:852`, `S_PREPARE` `:886`,
  `S_ARM` `:890`-`:906`, each as a begin/end pair in `try/finally`, so a throw records a section
  with `threw`.
- Sampled caller: for 1 attempt in 64, chosen by attemptId, a `StackWalker` records the first frame
  outside `net.minecraft.world.entity.ai.navigation`, `net.minecraft.world.level.pathfinder` and
  `dev.pathweaver`, with that class's code-source jar name.

## Request law (one RequestKey)

States are recorded **after** the transition succeeds, never before an attempt at it:

| event | recorded | fields |
|---|---|---|
| K1 MINTED | after `:816` returns | key, attemptId, RequestTarget hash |
| K2 REGISTERED | after `sink.register` returns, `:819` | key |
| K3 SUBMITTED | after `submit` returns, `:834`; begin/end nanoTime pair around the call | key, accepted, refusal reason from inside `submit`: NO_GENERATION (`PathWorkerPool.java:72`), CAPACITY (`:75`), GENERATION_REPLACED (`:78`-`:80`), EXECUTOR_REJECTED (`:139`-`:141`) |
| K4 AUTHORIZED | inside `SearchStartGate.open()` when its CAS succeeds | key |
| K5 GATE_CANCELLED | inside `SearchStartGate.cancel()` when its CAS succeeds | key |

**Main-thread dispositions**, each recorded where the production code has the key and after the
state change succeeded:

| event | site | disposition |
|---|---|---|
| M1 INVALIDATED | `EntityInstallSink.supersede` `:237` and `cancel` `:245`, after `inFlight.remove` returned true | SUPERSEDED, NAVIGATION_STOPPED; timing and the worker markers only, the disposition itself is the M2 that `finishDiscard` then records |
| M2 DELIVERY_DISPOSED | inside `finishDiscard` `:262`, `install` `:613`-`:651`, `parkForBrain` `:564`-`:586`, at each `markOutcome` with the key in scope | INSTALLED, INSTALL_REJECTED, INSTALL_FAILED, PARKED, ARRIVED_STALE, NO_PATH, SEARCH_FAILED, HANDOFF_FAILED, POOL_SATURATED, SETUP_FAILED, SERVER_RESET |
| M3 DRAINED_UNREGISTERED | the no-match branch of `install` `:613`, `discard` `:665`, `noPath` `:673` and `failed` `:681` (where `matching` is null or `inFlight.remove` returns false), and the CANCELLED branch of `ResultInstaller.deliver` `:102` | a drained result for a key no longer registered, with the delivery status it carried |
| M4 EPILOGUE | inside `EntityInstallSink.runEpilogue` `:416` | NONE_OWED (`owed == null`), DONE_OK, DONE_THREW (caught in `finishCallback` `:424`) |
| M5 COLLECTED | `takeBrainSinkPath` `:541` on a hit; the diagnostic side-map records which key parked into that slot | key |

**Worker events** (`PathWorkerPool` runnable, and the search callable):

| event | recorded | fields |
|---|---|---|
| W1 RUN_BEGIN | first statement of the runnable, `:83` | key |
| W2 STILL_WANTED | after `:99` evaluates | answer |
| W3 GATE_RESULT | after `awaitStart()` returns, `:799` | authorised |
| W4 SEARCH | `try/finally` around `finder.findPath`, `:809` | thread CPU at begin and end (UNKNOWN if unsupported), wall, SUCCESS, NO_PATH or THREW |
| W5 PUBLISHED | inside `ResultInstaller.enqueue` and `enqueueDiscard`, after `queue.add` | key, discardOnly |
| W6 CALLBACK_THREW | the catch at `:125` | key |

**The worker's own state marker.** Each `PathWeaverThread.Worker` holds a volatile
`(key, phase, cpuAtPhaseStart)` written at W1 (RUNNING), W4 begin (SEARCHING) and W4 end (DONE).
At M1 the main thread reads the markers of all workers and records any that name the invalidated
key, bracketed by nanoTime before and after the read. This observes the worker's phase at
invalidation directly, instead of inferring it from timestamps. If the marker's key changes during
the read, or events for that key later contradict it, the phase is UNKNOWN.

**Descriptive phase at invalidation** (reported, never gated), from the marker plus event order:
NOT_STARTED (no marker, and W1 absent or after M1's after-stamp), STARTED_BEFORE_SEARCH (marker
RUNNING), SEARCHING (marker SEARCHING), SEARCH_DONE (marker DONE, or W4 end before M1's
before-stamp), UNKNOWN otherwise. **STARTED_BEFORE_SEARCH does not mean the search was avoided.**
The worker checks `stillWanted` before the gate and never again, so a request invalidated after W2
answered true can still run its whole search. Whether it did is W4, reported separately.

**Completeness.** Every key has four independent completions:
- disposition: M2 (exactly one per key);
- worker: W4 end, or W2 false, or W3 not authorised;
- publication and drain: W5 then M2 or M3;
- epilogue: M4.

A key missing any of them at window close plus 200 ticks is CENSORED in that dimension, and it is
counted as censored, never dropped from the denominator.

## Decision inputs, all ceilings

**C_D, PathWeaver server-thread overhead ceiling, per tick.** For attempts closed in the window:
the sum of AC wall minus the sum of SY wall inside those same attempts, divided by window ticks. It
includes every outcome, early returns included. It also includes vanilla's own non-search
`createPath` work, the evaluator `prepare` a synchronous search would have run anyway, and the
recorder's own cost. Each of those only inflates it. It therefore bounds from above anything D
could remove.

**C_S, obsolete search CPU ceiling.** Sum of W4 thread CPU (end minus begin) for keys whose
disposition is SUPERSEDED, NAVIGATION_STOPPED, ARRIVED_STALE or INSTALL_REJECTED, or whose
disposition is censored, divided by total W4 CPU in the window. If any key that could belong to the
numerator has UNKNOWN CPU, or is censored in the worker dimension (its search may still be running),
no finite bound exists and C_S is INDETERMINATE for that window. It is not filled in with an
estimate. It ignores cache reuse and ignores how much of a search ran before its
invalidation, so it overstates what any scheduling policy could avoid.

**SAT, capacity evidence.** Positive observations, never inferred:
- CAPACITY refusals from K3, counted per server tick;
- per-tick samples at END_SERVER_TICK of `inFlight`, `maxInFlight`, the executor's `getActiveCount()`
  and `getQueue().size()`.

Saturation is **observed** in a window only if CAPACITY refusals occur in at least 10% of its ticks,
or the executor queue is non-empty in at least 50% of its tick samples. Otherwise the result is
"saturation not observed at this load". That is not a claim of spare capacity at other loads.

## Decisions, written before any result

**D (cheaper server-thread setup).** Threshold: **2% of that load's ABSENT-state tick mean, and at
least 0.5 ms per tick**. If C_D is below it at every W1 and W4 load, in both rounds: **STOP D**.
Otherwise a D investigation may be proposed, with its own preregistration, and nothing is claimed.

Why 2%: 0.9.0 saves 5.2 to 9.1% of tick on the ladder. A setup change whose *ceiling* is under 2%
could return at most about a fifth of the mod's measured benefit, and realistically much less once
necessary work (prepare, registration) is subtracted. That is below what justifies new code on the
dispatch path. The 0.5 ms floor keeps tiny absolute amounts at light loads from qualifying.

**Scheduling experiment (A/B, opt-in only, not scheduled).** It may be proposed only if, at the same
load in both rounds, **C_S is at least 25%** **and** saturation is **observed**. Otherwise
**STOP**: no scheduling experiment is proposed from Phase 1.

Why 25% and saturation together: below a quarter, even avoiding every counted search frees less
than a quarter of worker CPU. Without observed saturation, freed worker CPU has no demonstrated
route to server tick time or to waiting requests.

**Uncertainty rule.** Each ceiling is computed per round. A STOP needs the ceiling below threshold
in both rounds. A window that is VOID, or a load with an INDETERMINATE overhead result, cannot
support a STOP. It makes that load INDETERMINATE, and INDETERMINATE loads are reported, not
replaced.

**Client stream.** Report only. No decision.

## Observer effect

- **Disabled cost.** ABSENT vs IDLE, both rounds, on server tick mean and p95 (W1, W4) and on
  client frame p95 and the share of frames over 50 ms (W2, W3). Reported, with round agreement.
- **Recording cost.** ABSENT vs RECORDING on the same endpoints. If the tick mean differs by more
  than **1%** in either round, in either direction, that load's descriptive results are
  "perturbed". Its ceilings may still support a STOP only for D, because recorder cost inflates C_D.
  Its C_S and SAT become INDETERMINATE, because recording could hide or create waiting. Two rounds
  are a screen, not a proof of equivalence, and the report says so.
- **Density control.** RECORDING-FULL vs RECORDING-SAMPLED, where only 1 attempt in 8 (by attemptId)
  records its request and worker events. If the phase-at-invalidation proportions or the queue-wait
  median differ by more than 20% relative between the two, the descriptive phases are flagged
  "density-sensitive".
- **Release equivalence.** The `diag/phase1` jar in ABSENT passes the witness suite, the unit suite
  and the harness roster, and is within the regression method's 3% of the release jar on W1.

## Instrument controls

Each positive control must produce its label; each adverse control must produce UNKNOWN,
CENSORED, VOID or OUTCOME_UNRECORDED, never a label.

Offline, synthetic event files through the analyser:
- every attempt outcome; an attempt whose slot is unset (OUTCOME_UNRECORDED, VOID);
- nested attempts on one navigation, cross-navigation nesting, an exception inside a nested
  attempt, and two sequential attempts in one wrapper; SY attributed correctly in each;
- a key failing after K1, after K2, after K3 accepted, and after K4;
- W1 before K3's end stamp (negative submit-to-run interval reported as such);
- W5 before W6 (enqueue then callback throws); M3 for an unregistered key; M4 NONE_OWED;
- a sequence gap, a truncated trailer, and a body hash mismatch (all VOID);
- C_S with an UNKNOWN-CPU key, and with a censored key (both raise the ceiling).

Game tests on the `diag/phase1` gametest source set, with the test hook class:
1. **Removal fails**: `supersede` on an unregistered entity records no M1.
2. **Started before search, then searched**: a test hook pauses the worker between W2 (true) and
   `awaitStart`; the main thread supersedes; the hook releases; the test requires marker phase
   RUNNING at M1, a W4 with CPU, and a SUPERSEDED disposition.
3. **Searching**: a test evaluator blocks inside its first node evaluation on a latch; supersede;
   release. Requires marker phase SEARCHING.
4. **Search done, undrained**: wait for W5, supersede in the same tick before END_SERVER_TICK.
   Requires SEARCH_DONE and an M3 at drain.
5. **Not started**: pool of one worker held by scenario 3's latch, a second request superseded;
   release. Requires NOT_STARTED and W2 false.
6. **Adverse marker**: scenario 3 with the marker write suppressed by the hook. Requires UNKNOWN.
7. **Setup failure after registration**: a test evaluator whose `prepare` throws. Requires K1, K2,
   K3 accepted, K5, an SETUP_FAILED disposition and SETUP_FAILED attempt outcome.
8. **No epilogue owed**: M4 NONE_OWED for a key whose prepare never completed.

The test evaluators pass the real gates through the gametest harness's pinned compatibility tier,
the same route the existing harnesses use. The pause hook in scenario 2 is test-only and is the
single bypass of production timing.

## Preregistration

To be completed into `FROZEN.txt` and committed before the first run: jar hashes; config file
bytes for each workload; world copy manifest; dedicated server mod list; exact run order below.

### Workloads, loads and exposure rules

| id | where | load and window | valid only if |
|---|---|---|---|
| W1 | dedicated server, `bench/ladder.sh` arena | rungs 1000 and 5000, one boot per run; the recording window is the ladder's existing per-rung measurement window, start and end ticks written into the rungs file | at 1000: at least 2,000 dispatched keys, and SUPERSEDED at most 10% of dispositions (the no-storm definition) |
| W4 | same arena: 300 cows spawned within 8 blocks of the Bench fake player, who holds wheat and is teleported per burst as the ladder does | one level, window as W1 | at least 2,000 dispatched keys, and at least 50% of sampled callers are `TemptGoal` |
| W2 | client pack, village scenario (`bench/client/scenario.txt`), Enhanced Cats absent | window from PWMARK one to PWMARK end | at least 1,000 dispatched keys |
| W3 | as W2, Enhanced Cats added | as W2 | at least 5,000 dispatched keys, SUPERSEDED at least 50% of dispositions, and at least 50% of sampled callers from the Enhanced Cats jar |

Shipped default config on W1 and W4; the player's config on W2 and W3. Fixed throughout.

### Run order

Per workload, two rounds, each run a fresh boot and a fresh directory:
R1: ABSENT, IDLE, RECORDING-FULL, RECORDING-SAMPLED. R2: RECORDING-SAMPLED, RECORDING-FULL, IDLE,
ABSENT. Workload order W1, W4, W2, W3. Warmup is the ladder's own settle before each rung and the
client scenario's 60 s settle plus base phase. Each run stays under ten minutes. A run that exceeds
it is VOID and not retried in this attempt.

### Invalid rounds

A decision needs both rounds valid. If either round of a workload's load is VOID or fails its
exposure rule, that load is INDETERMINATE. A replacement is a new preregistered attempt in a new
directory, and the old one is kept and reported.

## What this cannot say

One machine carrying a foreign process that holds about 8 of 32 cores, four workloads, two rounds.
Phase 1 can stop two lines of work with evidence that errs toward not stopping. It cannot show that
either would pay off; that needs its own preregistration.
