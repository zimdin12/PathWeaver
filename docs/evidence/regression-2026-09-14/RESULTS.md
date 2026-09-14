# Regression check on 0.9.0 with the hot-path fixes: no regression, saving 5 to 9%

Run 2026-09-14 12:33 to 13:08 UTC against the jar built at `3d10163` (sha256 `0ba3d71a...`) and the 0.8.0
file downloaded from Modrinth, both identified in `raw/JARS.txt` before the first run. Judged by
`bench/regression_report.py` and `bench/machine_load.py`, both unedited, against `PREREGISTRATION.md`,
committed first.

## Verdicts

Every check passed.

| | result |
|---|---|
| **Q1** tick time rises with population, every run | PASS in all six |
| **Q4** the same arm's two rounds agree within 5% | PASS at every busy rung; the widest gap is 3.4% |
| **Q5** background load did not move | PASS; 29.7% to 31.8% in all six runs |
| **Q2** 0.9.0 no more than 3% worse than 0.8.0 in both rounds | PASS at 2500, 5000, 10000 |
| **Q3** saving against no mod between 4% and 12% | PASS at 2500, 5000, 10000 |
| **Q6** saving within 3 points of the release jar's (9.6, 8.2, 7.1%) | held: 7.8, 9.1, 5.2% |

| rung | off | 0.8.0 | 0.9.0 | 0.9.0 vs 0.8.0, per round | saving vs off |
|---|---|---|---|---|---|
| 2500 | 34.0 | 32.2 | 31.4 | -2.8%, -2.5% | **7.8%** |
| 5000 | 79.8 | 75.2 | 72.6 | -4.7%, -2.4% | **9.1%** |
| 10000 | 193.7 | 185.8 | 183.7 | -1.5%, -0.8% | **5.2%** |

Median tick ms, mean of the two rounds; per-round values are in `REPORT.txt`.

## What it means

**The fixed jar does not regress against 0.8.0.** It is faster than 0.8.0 in both rounds at every busy
rung, by 0.8 to 4.7%. Most of that is below the 3% this method can resolve, so it is not claimed as a gain.

**The fixes did not change this benchmark, as predicted.** The arena's zombies search off the server
thread almost entirely, and the fixes remove a cost paid by searches on it. The savings moved by -1.8,
+0.9 and -1.9 points against the release jar's, in both directions, which is day-to-day noise rather
than an effect. The 10000 rung's no-mod arm ran 3.7 ms faster today than on 2026-09-13, and that alone
accounts for most of that rung's lower saving.

**The copy's range needs to say what this jar measured.** The release notes said 7 to 10% and the page
6-10%. This jar measured 5.2 to 9.1%, so the notes now say 5 to 9%, the figure from the jar that ships.

## What this cannot say

One arena, one mob type, dense populations, two rounds on one machine carrying a foreign process that
holds about 8 of 32 logical cores. It says the jar that will ship behaves like the build the page was
measured on. It says nothing about villager-heavy worlds, where the fixes do matter; that is
`docs/evidence/client-lag-2026-09-14/`.

## Files

`REPORT.txt` and `MACHINE.txt` are the judges' outputs, regenerated from `raw/`. `raw/` holds each run's
rungs file, the campaign console with local paths removed, the machine-load samples, and `JARS.txt`.

## The jar that ships is not byte-identical to the one measured here

Found while staging the release, after this series: the checkout the measured jar (`0ba3d71a...`) was built
in had CRLF line endings on disk in `src/main/resources/pathweaver.mixins.json`, which git stores as LF.
A fresh checkout of the same commit builds `47e24d24...`. The two jars have the same entries in the same
order and differ in that one file only, whose bytes are equal once CR is removed and whose parsed JSON is
equal (`docs/evidence/RELEASE-JARS-0.9.0.txt`). Mixin reads that file as JSON, so the measured behaviour
is the shipped jar's; the claim this rests on is that comparison, not a re-run.
