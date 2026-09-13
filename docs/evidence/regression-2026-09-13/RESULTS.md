# Regression check on the tagged 0.9.0: inconclusive

*Superseded 2026-09-13 by `docs/evidence/regression-2026-09-13b/`, which repeated it on the release jar with
the machine recorded, and concluded: no regression, and the page's 6-10% holds. This file is kept as it was.*

Run 2026-09-12 21:45-22:20 UTC. Judged by `bench/regression_report.py` against `PREREGISTRATION.md`,
which was committed first. **No conclusion about 0.9.0 against 0.8.0, or about the page's 6-10%, can be
drawn from this run.** It has to be repeated on a quiet machine.

## What the preregistered rules said

Q1, the instrument control, passed in all six runs: tick time rises with population every time.

Q4 failed at almost every busy rung: the same arm's two rounds disagreed by far more than 5%, up to 156%.
By the rule written before the run, that withholds every Q2 and Q3 verdict. The rule did its job.

## Why the rounds disagree: the machine got slower, not a jar

In the order the runs happened:

| # | run | started (UTC) | median at 2500 | at 5000 | at 10000 | system CPU, final rung |
|---|---|---|---|---|---|---|
| 1 | off | 21:45 | 33.2 | 77.2 | 192.4 | 34% |
| 2 | 0.8.0 | 21:51 | 31.5 | 72.2 | 180.0 | 33% |
| 3 | 0.9.0 | 21:56 | 38.4 | 87.8 | 228.6 | 37% |
| 4 | 0.9.0 | 22:02 | 40.1 | 93.1 | 233.4 | 37% |
| 5 | 0.8.0 | 22:08 | 38.7 | 89.2 | 230.7 | 36% |
| 6 | off | 22:14 | 63.9 | 197.9 | 434.6 | **65%** |

The slowdown follows the clock. 0.8.0 in run 5 is as slow as 0.9.0 in runs 3 and 4, and the arm with no
mod at all is the slowest of the six.

- **Run 6 is explained.** A game (`STORRORParkourPRO-Win64-Shipping`) started at 22:16:32 UTC, inside that
  run, and was measured afterwards using 14.6 of 32 logical cores. spark's own record of system CPU during
  the run agrees: 65% against 33-37% in the others.
- **Runs 3 to 5 are not explained.** They carry only 2-4 points more system CPU than runs 1 and 2, which
  does not obviously account for ticks 20-30% slower. Something began degrading the machine between run 2
  and run 3, and what it was has not been established. An earlier instance of the same program, a launcher,
  or shader compilation are possibilities, not findings.

## The mistake this caught

After runs 1 to 4, before round 2 had finished, the data read as a clean 25% regression in 0.9.0: both
0.9.0 runs slower than both 0.8.0 and off, agreeing with each other within 2%. That was said out loud as a
real regression, and was wrong. The two 0.9.0 runs agreed because they ran back to back in the degraded
period. The second round, run in reverse order, is the only reason it did not stand. Reporting before the
reversed round existed would have blamed the release for the machine.

## What remains true

The earlier campaign's figures for 0.9.0 built at `68dff75` stand as they were. This run neither confirms
nor contradicts them for the tagged jar. The distance LOD campaign ran before any of this, between 20:57 and
21:41 UTC, at a steady 30-31% system CPU in every run checked, and its within-campaign comparisons are
unaffected.

## Files

`REPORT.txt` is the judge's output. `raw/` holds each run's rungs file and the system CPU spark recorded at
its final rung.
