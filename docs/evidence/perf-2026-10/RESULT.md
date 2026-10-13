# Result: zombies that actually chase, 2026-10-13

Six runs, 23:22 to 23:59 UTC, as preregistered in `PREREGISTERED.md` (committed before the first
run). Every run is valid: the fake player joined, survived, and 12 of 12 teleports landed in each.
No run was voided or replaced. The server was restored after each run and checked: e4mc and the
operator's own PathWeaver jar back in `mods/`, `level-name=world`, the operator's config back.

Reproduce one run: `PW_OUT=<dir> BUDGET=570 RESERVE=90 bash bench/ladder.sh r1-off off 100 500 1000 2500 5000 10000`
(or a jar path instead of `off`). Raw rows: `r*-*.rungs.txt`. Total CPU every 5 s: `machine-load.csv`.

## Median tick, ms (spark health, 10 s window)

| zombies | off: r1, r4, r5 | on: r2, r3, r6 | median off | median on | gain |
|---:|---|---|---:|---:|---:|
| 100 | 4.2, 4.4, 3.7 | 4.2, 4.4, 2.9 | 4.2 | 4.2 | 0% |
| 500 | 10.2, 10.1, 7.4 | 8.9, 9.4, 6.9 | 10.1 | 8.9 | 12% |
| 1000 | 18.2, 18.6, 13.0 | 16.7, 17.9, 13.4 | 18.2 | 16.7 | 8% |
| 2500 | 45.8, 46.4, 35.5 | 43.3, 46.1, 32.6 | 45.8 | 43.3 | 5.5% |
| 5000 | 106.9, 111.5, 86.0 | 102.7, 103.3, 80.7 | 106.9 | 102.7 | 3.9% |
| 10000 | 258.0, 261.5, 216.1 | 246.3, 250.2, 200.1 | 258.0 | 246.3 | 4.5% |

Searches moved off the main thread across a whole on-run: 39,429, 50,902 and 53,716.

## Against the preregistered rules

1. Gain of at least 3% at 1000, 2500, 5000 and 10000: **met** (8, 5.5, 3.9, 4.5%).
2. No overlap between the arms' runs at those rungs: **not met at any rung.** By the rule written
   before the runs, **no difference is established by this series.**

## Why they overlap: the machine changed speed between run 4 and run 5

In run order, 10000-zombie median: r1 off 258.0, r2 on 246.3, r3 on 250.2, r4 off 261.5, r5 off 216.1,
r6 on 200.1. Both arms dropped about 17% from run 5 on, so the shift is not an arm effect. The cause
is not established. `machine-load.csv` shows total CPU under 80% in every sample but one (93% at
23:36:39, WSL, during run 3's arena setup, before its first rung).

Outside the rules, labelled as such: the order was built as adjacent off/on pairs (r1-r2, r4-r3, r5-r6),
and within each pair the on run is faster at 2500, 5000 and 10000 zombies, 3 pairs of 3:

| zombies | pair 1 | pair 2 | pair 3 |
|---:|---:|---:|---:|
| 1000 | 8.2% | 3.8% | -3.1% |
| 2500 | 5.5% | 0.6% | 8.2% |
| 5000 | 3.9% | 7.4% | 6.2% |
| 10000 | 4.5% | 4.3% | 7.4% |

Three of three pairs in the same direction would happen by chance one time in eight per rung, so
this points the same way as the medians without establishing it.

## Against what was published

The 0.9.0 page says 5 to 11% (500: 11, 1000: 10, 2500: 8, 5000: 9, 10000: 5) for "zombies chasing a
player", measured on 2026-09-12 when the zombies were in fact wandering. With them chasing, the
medians here are 12, 8, 5.5, 3.9 and 4.5%: the same size at 500 to 1000 zombies, smaller at 2500 to
5000, and this series cannot formally separate the arms. The 95th-percentile tick did not improve
consistently (10000 zombies: 322 off, 336 on).
