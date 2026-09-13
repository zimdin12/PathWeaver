# Regression check on the release 0.9.0, second attempt: no regression, and the page's figure holds

Run 2026-09-13 03:20 to 03:55 UTC against the jar built at `a695c38` (sha256 `63d7d123...`) and the 0.8.0
file downloaded from Modrinth, both identified in `raw/JARS.txt` before the first run. Judged by
`bench/regression_report.py`, unedited since the first attempt, and `bench/machine_load.py`, against
`PREREGISTRATION.md`, committed first.

## Verdicts

Every check passed.

| | result |
|---|---|
| **Q1** tick time rises with population, every run | PASS in all six |
| **Q4** the same arm's two rounds agree within 5% | PASS at every busy rung; the widest gap is 3.1% |
| **Q5** background load did not move | PASS; 28.6% to 29.6% in all six runs |
| **Q2** 0.9.0 no more than 3% worse than 0.8.0 in both rounds | PASS at 2500, 5000, 10000 |
| **Q3** saving against no mod between 4% and 12% | PASS at 2500, 5000, 10000 |

| rung | off | 0.8.0 | 0.9.0 | 0.9.0 vs 0.8.0, per round | saving vs off |
|---|---|---|---|---|---|
| 2500 | 34.0 | 31.5 | 30.7 | -3.5%, -1.6% | **9.6%** |
| 5000 | 79.5 | 72.9 | 73.0 | -2.2%, +2.4% | **8.2%** |
| 10000 | 197.4 | 180.8 | 183.2 | +1.7%, +1.1% | **7.1%** |

Median tick ms, mean of the two rounds; per-round values are in `REPORT.txt`.

## What it means

**0.9.0 does not regress against 0.8.0.** Its differences from 0.8.0 are inside ±3.5% and change sign
between rounds at 5000; at 10000 it is 1 to 2% slower in both rounds, which is below the 3% this method
can resolve and is reported rather than rounded away.

**The project page's 6-10% of tick holds for the release jar.** Measured savings are 9.6%, 8.2% and 7.1%,
against the page's 9%, 6% and 6% at those rungs. Slightly better at 5000 and 10000 than the page says;
not enough to change the page's range.

## Why this attempt could conclude and the first could not

The first attempt's rounds disagreed by up to 156%, and its record could explain only one of the slow
runs. This time the load sampler shows the machine's background at 28.6% to 29.6% in every run, and the
rounds agree within 3.1%. The background includes a desktop window manager process holding about 8 of 32
logical cores throughout, which was there before the run and was not stopped; it was constant, which is
what the method needs.

## What this cannot say

One arena, one mob type, dense populations, two rounds on one machine. It says the release jar behaves
like the build the page was measured on, not what another server will see.

## Files

`REPORT.txt` and `MACHINE.txt` are the judges' outputs, regenerated from `raw/`. `raw/` holds each run's
rungs file, the campaign console, the machine-load samples, and `JARS.txt`.
