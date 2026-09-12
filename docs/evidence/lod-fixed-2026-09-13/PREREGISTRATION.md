# Distance LOD after the drop-not-delay fix: written before measuring it

Committed 2026-09-13, before any run of this campaign. The first campaign (`docs/evidence/lod-2026-09/`)
measured a hook that dropped throttled refreshes instead of delaying them. It is kept as it is, and says
so. This one measures the fixed hook, with the same scenario, arms, order, measures and judge.

## What the fix should change, and what it should not

The hook now sets `hasDelayedRecomputation` before cancelling, as vanilla does inside its own 21-tick
floor. A throttled refresh is retried every tick by `PathNavigation.tick` and goes through when the
40-tick interval has passed.

- **Where churn is continuous around a route**, the dropped version lost little: the next carpet toggle
  two ticks later asked again. Both versions search about once per 40 ticks there.
- **Where a route is touched once, or the mob wanders away from the strip**, the dropped version never
  searched and the fixed one searches at tick 40. So the fixed hook searches *more* than the dropped one,
  never fewer.
- **Cost the fix adds**: a mob with a pending refresh calls `recomputePath` every tick until it is allowed,
  and each call evaluates the hook's distance check. That is time inside `recomputePath` that is not a
  search.

## Scenario, arms, order, measures

Unchanged from `docs/evidence/lod-2026-09/PREREGISTRATION.md`: `bench/lod-bench.sh`, 400 zombies in a
pen 70 to 110 blocks from the player, carpet churn on or off, a 60 s profile after 20 s. Instrument A with
`enabled=false`, arms A1 to A4; instrument B, the shipped configuration, arms B1 and B2. Order R1 A1 A2 A3
A4 B1 B2, R2 A2 A1 A4 A3 B2 B1. The jar is the one built at the moved `v0.9.0` tag, identified by sha256
before boot.

Two additions, neither of which changes a run: `bench/machine-load.ps1` records the machine for the whole
campaign (a desktop window manager was holding about 8 of 32 cores before it started), and the churn gate
the first campaign adopted as a recorded deviation, `recomputePath` present exactly when churn is on, is
the gate from the start.

## Predictions, and what falsifies each

`bench/lod_report.py` judges P1 to P4 with the first campaign's thresholds, unchanged. P3b, P4d and P5 are
new and judged by `bench/lod_fixed_report.py`, written before the run.

**P1, positive control.** In both rounds M1(A1) is at least 200 ms and at least 5x M1(A3). If this fails
the campaign is void.

**P2, negative control.** M1(A3) and M1(A4) differ by less than 100 ms, or both are ABSENT.

**P3, the treatment, unchanged.** M1(A2) / M1(A1) lies between 0.45 and 0.85 in both rounds. The first
campaign failed this low, at 0.370 and 0.287.

**P3b, the fix points the right way.** In both rounds M1(A2) / M1(A1) is above **0.370**, the higher of
the two ratios the dropped version produced. The fixed hook can only add searches back. A ratio at or
below 0.370 says the fix changed nothing measurable, or the ratio is noisier than two rounds showed.

If P3 fails low while P3b passes, the fix moved the ratio but something else still holds it under 0.45.
The route-lifetime effect, a wander route that ends between tick 21 and tick 40 and is refreshed by vanilla
and never by LOD, is the remaining named explanation, and it stays a hypothesis.

**P4, the shipped configuration, unchanged.** In both rounds B2 dispatches fewer searches than B1 (P4a)
and spends less worker time in `findPath` (P4b); median tick time does not separate between B1 and B2
(P4c).

**P4d, the saving shrinks or holds, it does not grow.** In both rounds B2 dispatches at most **30%** fewer
searches than B1. The dropped version measured 27.0% in both rounds; the fix adds searches back, so a
reduction above 27.0% plus 3 points of allowance would contradict the mechanism.

**P5, the retry is cheap.** In both rounds, server-thread time inside `recomputePath` that is not under
`PathFinder.findPath` (`bench/lod_split.py`'s overhead column) is under **150 ms** in A2, over a 60 s
window. The dropped version measured 56 and 12 ms, vanilla's A1 40 and 64. Above 150 ms, the per-tick
retry costs enough to be worth making cheaper, and that gets said.

**Q5, the machine held still.** As in the regression attempt: `bench/machine_load.py`, a run is flagged
when its median background load is more than 8 points from the campaign median. A flagged run is reported
with its verdicts, not silently kept.

## What voids a run, what stops the campaign

As before: the jar hash not matching, the zombie count moving, the command chain not ticking, no profile,
or `recomputePath` present when churn is off or absent when it is on. A voided run is kept and repeated
once as `-retry`; a second void stops the campaign.

## What this cannot say

As before. It is built to favour LOD, on one machine and one pack, two rounds. It does not measure what a
distant mob walking an older route costs in behaviour. And it cannot separate the route-lifetime effect
from anything else that keeps the ratio low.
