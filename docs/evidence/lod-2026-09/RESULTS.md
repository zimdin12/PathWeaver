# Distance LOD: what the campaign found

Run 2026-09-12 to 2026-09-13 against the jar tagged `v0.9.0` (sha256 `7b590fa5...`), judged against
`PREREGISTRATION.md`, which was committed before any run and has not been edited. The verdicts below are
printed by `bench/lod_report.py`, not read off by hand, and reproduce exactly from the files committed
in `raw/`.

## Verdicts

| | round 1 | round 2 | |
|---|---|---|---|
| **P1** scenario reaches `recomputePath` | PASS | PASS | 1504 and 1520 ms with churn; ABSENT without |
| **P2** no block changes, no effect | PASS | PASS | ABSENT in all four churn-off runs, LOD on or off |
| **P3** LOD keeps 0.45-0.85 of recompute time | **FAIL** | **FAIL** | 0.370 and 0.287: it removed *more* than predicted |
| **P4a** fewer searches dispatched, shipped config | PASS | PASS | 27.0% fewer in both; 6 of 6 attempt pairings |
| **P4b** less worker search time, shipped config | PASS | **FAIL** | -13.6%, then +5.8%; 1 of 6 pairings in round 2 |
| **P4c** tick time does not separate, shipped config | PASS | | medians 6.3 / 6.2 against 6.2 / 6.2 ms |

## What the P3 failure turned out to be

The preregistration said a ratio below 0.45 "would mean something besides the refresh floor is being
throttled." That was followed up rather than explained away, with `bench/lod_split.py`, which splits
server-thread time in the synchronous arms three ways. Its totals match `frame_totals.py` to the
millisecond on all four files.

| | recompute | = search inside | + overhead inside | search outside |
|---|---|---|---|---|
| r1 A1, LOD off | 1504 | 1464 | 40 | 360 |
| r1 A2, LOD on | 556 | 500 | 56 | 472 |
| r2 A1, LOD off | 1520 | 1456 | 64 | 428 |
| r2 A2, LOD on | 436 | 424 | 12 | 396 |
| r1 A3, churn off, LOD off | 0 | 0 | 0 | 560 |
| r1 A4, churn off, LOD on | 0 | 0 | 0 | 424 |

**Per-call overhead: ruled out.** The model behind P3 assumed search dominates `recomputePath`, and it
does, at 96-97% of it. The search share on its own falls to 0.342 and 0.291.

**LOD breaking navigations so routes die: not supported.** That predicts a consistent rise in search
*outside* `recomputePath`, as mobs that lost a route ask for a new one. It moves +112 ms in round 1 and
-32 ms in round 2, while the two churn-off arms, which LOD cannot affect at all, differ by 136 ms. That
is noise at this resolution. A small effect cannot be excluded; a large one would have shown.

**A real defect, established from the bytecode.** Reading vanilla again for the gap:

- when `recomputePath` refuses inside its own 21-tick window, it sets `hasDelayedRecomputation = true`;
- `PathNavigation.tick` calls `recomputePath` again every tick while that flag is set;
- `shouldRecomputePath` returns false while it is set, so further block changes do not pile up.

That is how vanilla **delays** a refresh. The LOD hook cancels at HEAD and **does not set the flag**. So
a throttled request is not delayed, it is dropped: nothing retries it, and only a *later* block change
near the route asks again. For a block change that is not repeated, a distant mob inside its 40-tick
window never refreshes its route for that change at all, where vanilla would have within 21 ticks.

That explains a ratio below 0.525, and so does a second effect the model left out: wander routes are
short, and one that ends between tick 21 and tick 40 after its last refresh is refreshed by vanilla and
never by LOD. The data cannot say which of the two dominates.

**Status of the defect, as written at the time: established from code that was read, not yet observed
in a running game.** *Update 2026-09-13: since observed and fixed. `LodDeferralGameTest` failed on the
tagged code with exactly this cause, passed with the flag set before cancelling, failed again with only
that line reverted, and passed restored; see `docs/evidence/lod-deferral-2026-09-13/`. This campaign
measured the defective hook, and the rerun on the fixed jar, `docs/evidence/lod-fixed-2026-09-13/`,
supersedes it for LOD's size.* The project
page, the changelog and the in-game tooltip all described LOD as delaying a refresh "up to" the interval,
and for a change that is not repeated that was false until the fix.

## What it means, in the configuration that ships

With the mod's async path on, which is the default, in a scenario built to favour LOD: LOD cut the path
searches PathWeaver dispatched by **27%** and **did not change tick time** measurably. The searches were
already off the server thread, so what it saves there is worker CPU, and the sampled worker time was too
noisy to confirm even that (P4b failed in round 2).

Not preregistered, and reported as an observation only: with async **off**, median tick time was 7.8 ms
without LOD and 7.1 ms with it, identically in both rounds, about 9%.

And a world whose terrain is not changing around distant mobs gets nothing, because `recomputePath` is
never called there. P2 is the direct evidence for that.

## Deviations from the preregistration

Recorded here rather than folded in, each with its reason.

1. **The churn gate.** The run script voided three instrument-B attempts on its carpet-state probe
   (`r2-B2`, `r2-B1`, `r2-B1-retry`, all "carpet 6 of 6"). Every one of them shows `recomputePath` in its
   profile, and that method cannot be called without a collision-changing block update, so the strip was
   toggling in each. The probe can only void a good run, never pass a bad one: a strip that is not
   toggling cannot read mixed. The likely mechanism is that commands arrive through `tail -f`, which polls
   about once a second, so probes land about 20 ticks apart, a multiple of the 4-tick toggle, and a
   steady server samples the same phase each time. That mechanism is a hypothesis; it fits the voids
   occurring only in the lower-jitter async arm and never in the eight synchronous runs. The report now
   gates churn on `recomputePath` being present exactly when churn is on, applied to every attempt.
2. **The campaign stopped** by its own rule when `r2-B1` voided twice. `r2-B1-rerun` was run afterwards
   under the corrected gate, at a different position in the sequence than preregistered.
3. **Which attempt counts.** An arm's value comes from the attempt that completed under the gates in force
   when it ran: `r2-B2-retry` and `r2-B1-rerun`. P4 is also computed over every pairing of valid attempts
   so a verdict that depends on the pick is visible. P4a holds in 6 of 6; P4b in 1 of 6.
4. **`dispatched` parsing** now reads only the status command's reply. A periodic stats line carries the
   same key. It did not contaminate any row, which was checked, but nothing prevented it.
5. **Sampled `sendBlockUpdated` time is not used.** In the async arms it ranged 16 to 228 ms across
   identical runs, too wide for sampling noise and most likely spark's safepoint-biased sampler
   misattributing a short loop. No preregistered measure depended on it.

## What this does not say

One machine, one pack, 400 zombies, two rounds. It establishes direction and size under stated
conditions, not a figure anyone else should expect. It does not measure the behavioural cost of a distant
mob walking an out-of-date route, and it cannot distinguish the dropped-request defect from the
route-lifetime effect as causes of the P3 ratio.

## Files

- `PREREGISTRATION.md`: the predictions, committed first.
- `REPORT.txt`: `bench/lod_report.py` output. `SPLIT.txt`: `bench/lod_split.py` output.
- `raw/`: every attempt's spark profile (with `SHA256SUMS`), the row the run script wrote, the VOID
  marker where there is one, and each server log trimmed to the lines the report reads (the status
  replies and spark health's tick distributions). The full logs are 14 MB of a 221-mod pack's output and
  are not committed. `python bench/lod_report.py docs/evidence/lod-2026-09/raw` reproduces every number
  above.
- The smoke runs are not part of the series and are not here.
