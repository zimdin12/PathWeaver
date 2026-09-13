# Distance LOD after the drop-not-delay fix: what the campaign found

Run 2026-09-13 02:46 to 03:20 UTC against the jar built at `a695c38` (sha256 `63d7d123...`, identified in
`raw/JARS.txt` before the first run), judged against `PREREGISTRATION.md`, which was committed before any
run and has not been edited. Every verdict is printed by a script, not read off by hand, and reproduces
from the files in `raw/`.

## Verdicts

Every preregistered prediction held, in both rounds. No run was voided and none was retried.

| | round 1 | round 2 | |
|---|---|---|---|
| **P1** scenario reaches `recomputePath` | PASS | PASS | 1432 and 1640 ms with churn; ABSENT without |
| **P2** no block changes, no effect | PASS | PASS | ABSENT in all four churn-off runs |
| **P3** LOD keeps 0.45-0.85 of recompute time | PASS | PASS | **0.788** and **0.556** |
| **P3b** above the dropped hook's 0.370 | PASS | PASS | |
| **P4a** fewer searches dispatched, shipped config | PASS | PASS | **18.8%** and **12.5%** fewer |
| **P4b** less worker search time, shipped config | PASS | PASS | 31.1% and 6.1% less |
| **P4c** tick time does not separate, shipped config | PASS | | medians 7.4 / 6.2 against 7.3 / 7.5 ms |
| **P4d** reduction at most 30% | PASS | PASS | |
| **P5** the per-tick retry costs under 150 ms | PASS | PASS | 32 and 96 ms, against vanilla's 68 and 60 |
| **Q5** the machine held still | PASS | | background 28.8% to 32.6% in all twelve runs |

`REPORT.txt` is `bench/lod_report.py` (P1 to P4c, the first campaign's judge, unchanged).
`REPORT-NEW.txt` is `bench/lod_fixed_report.py` (P3b, P4d, P5). `SPLIT.txt` is `bench/lod_split.py`.
`MACHINE.txt` is `bench/machine_load.py`.

## What it means

**The fix did what it was for.** The dropped-refresh hook left 0.370 and 0.287 of vanilla's recompute
time; the fixed one leaves 0.788 and 0.556. The mechanism predicted 0.525 under demand that never lets up
and higher where demand comes and goes, and both readings sit in that range. The first campaign's ratio
was below what the refresh floor allows because refreshes were being lost, and now they are not.

**The size is not pinned down.** The two rounds disagree by 0.23 on the same arms. Both are inside the
preregistered window, so no prediction depended on which is right, but a single figure for "how much
recompute search LOD removes here" would claim more than two rounds show. Honest range, in this scenario:
somewhere between about a fifth and about half.

**In the configuration that ships, with LOD switched on:** 13 to 19% fewer path searches dispatched, in a
scenario built to favour it, and no measurable change in tick time, because those searches were already
off the server thread. The dropped-refresh hook "saved" 27%, and some of that was refreshes it should
have done.

**What the retry costs.** A mob whose refresh is pending now calls `recomputePath` every tick until it is
allowed, as vanilla's own pending refreshes do. The time inside `recomputePath` that is not a search was
32 and 96 ms over 60 s with LOD on, against 68 and 60 ms for vanilla. It does not stand out.

Not preregistered, reported as an observation only: with async off, median tick time was 9.1 and 8.4 ms
without LOD, 8.4 and 8.1 ms with it.

## Compared with the first campaign

Same scenario, arms, order, script and judge; later server boots, and the jar differs only in
the LOD hook. The comparison of ratios across the two campaigns (P3b) is the only cross-campaign claim, and
it is made because the ratio is taken within each campaign, from arms run back to back. Absolute
milliseconds are not compared: the async-off tick medians here are 0.6 to 1.3 ms higher than in the
first campaign, for reasons this campaign did not establish.

## What this does not say

One machine, one pack, 400 zombies, two rounds, a scenario built to favour LOD. It says the fixed hook
delays rather than drops, and roughly how much work it removes under continuous nearby churn. It does not
measure what a distant mob walking an older route costs in behaviour, and it gives nothing to a world
whose terrain is not changing around distant mobs, since `recomputePath` is never called there (P2).

## Files

- `PREREGISTRATION.md`: the predictions, committed first.
- `REPORT.txt`, `REPORT-NEW.txt`, `SPLIT.txt`, `MACHINE.txt`: judge outputs, regenerated from `raw/`.
- `raw/`: every run's spark profile (with `SHA256SUMS`), the row the run script wrote, each server log
  trimmed to the lines the judges read, the campaign console, the machine-load samples, and `JARS.txt`.
