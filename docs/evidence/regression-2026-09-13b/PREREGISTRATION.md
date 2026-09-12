# Did the final 0.9.0 regress? Second attempt, written before running it

Committed 2026-09-13, before any run of this attempt. The first attempt
(`docs/evidence/regression-2026-09-13/`) was inconclusive: the machine slowed down during it, and its own
round-agreement rule withheld every verdict. That folder is kept as it is.

## What changed since the first attempt

1. **The jar.** `v0.9.0` now points at the commit that fixes distance LOD dropping refreshes. LOD ships
   off, and the ladder never reaches `recomputePath`, so the fix is predicted to change nothing here. The
   0.9.0 arm is the jar built at the moved tag; its sha256 is recorded in `raw/` before the first run.
2. **The machine is recorded.** `bench/machine-load.ps1` samples every 10 s for the whole campaign: total
   CPU, the benchmark's java, and the three busiest other processes. The first attempt had only spark's
   system-CPU figure at the final rung, and could not say what slowed runs 3 to 5.
3. **Known before the run:** a desktop window manager process holds about 8 of 32 logical cores
   continuously (sampled 2026-09-13 23:27 UTC, 7.8 to 8.1 cores in every sample). It is not stopped,
   because that is not ours to do. It is recorded, and the rule below catches it changing.

Nothing else changes: same arms, same order, same rungs, same thresholds, and the same judge,
`bench/regression_report.py`, unedited, pointed at this attempt's folder.

## Arms

| arm | jar |
|---|---|
| off | every pathweaver jar held out of `mods/` |
| 0.8.0 | the file downloaded from Modrinth, sha512 `f7aba38b5c016c49...` verified against the API |
| 0.9.0 | `pathweaver-0.9.0+26.1.2.jar` built at the moved `v0.9.0` tag |

Two rounds, the second reversed: R1 off, 0.8.0, 0.9.0; R2 0.9.0, 0.8.0, off. `bench/ladder.sh`, rungs
100 500 1000 2500 5000 10000, `BUDGET=600 RESERVE=90`, spark health's 10 s window, median reported.

## Predictions, and what falsifies each

Q1 to Q4 are copied from the first attempt word for word in meaning, and `regression_report.py` applies
them.

**Q1, instrument control.** In every run the median rises from 1000 to 2500 to 5000 to 10000, or the run
is void.

**Q2, no regression.** 0.9.0 is a regression only if its median is more than 3% worse than 0.8.0's at the
same rung, in the same direction, in both rounds, at any of 2500, 5000, 10000. Predicted: no rung meets
that.

**Q3, the page's figure.** Saving = (off - 0.9.0) / off on the mean of the two rounds' medians. The page's
6-10% is reproduced if the saving is between 4% and 12% at each of 2500, 5000, 10000. Below 4% at any, the
page overstates and gets corrected; above 12% at all three, it understates.

**Q4, round agreement.** If one arm's two rounds differ by more than 5% at a busy rung, Q2 and Q3 at that
rung are withheld.

**Q5, new: the machine held still.** For each run, the median over its samples of total CPU percent minus
the benchmark java's share (java cores / 32 x 100) is its background load. If any run's background load
differs from the median of all six runs' by more than 8 percentage points, the campaign's Q2 and Q3 are
reported alongside that fact and not as a clean result; if the slow run is also the one Q4 flags, the
disagreement is attributed to the machine, not the jar. Judged by `bench/machine_load.py`, written before
the run.

## What stops the campaign

A run the ladder itself voids is kept and not retried within this attempt. A third attempt, if needed, gets
its own folder and preregistration.

## What this cannot say

One arena, one mob type, a dense population, two rounds on one machine that is not idle. It says whether
the tagged jar behaves like the one the page was measured on.
