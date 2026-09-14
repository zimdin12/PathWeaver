# Does 0.9.0 with the hot-path fixes regress? Written before running it

Committed 2026-09-14, before any run of this attempt.

## Why again

`docs/evidence/regression-2026-09-13b/` measured the release jar (sha256 63d7d123...) and found no
regression and a 9.6, 8.2 and 7.1% saving at 2500, 5000 and 10000 zombies. Since then two changes went
into production code, both on the per-node path of every search on every thread:

1. a3bd6dd: the per-node worker checks read fields on the worker thread instead of ThreadLocals.
2. 7f05b0d: the land path-type provider lookup is a redirect instead of a cancellable inject.

The release copy says "this exact jar was put back through the benchmark". After these changes it would
not be, so the benchmark runs again on the jar that will ship.

## What is the same

Arms, order, rungs, thresholds, and the judges `bench/regression_report.py` and `bench/machine_load.py`,
unedited: `bench/regression-campaign.sh`, R1 off, 0.8.0, 0.9.0; R2 0.9.0, 0.8.0, off. Q1 to Q5 exactly as
written in `docs/evidence/regression-2026-09-13b/PREREGISTRATION.md`.

| arm | jar |
|---|---|
| off | every pathweaver jar held out of `mods/` |
| 0.8.0 | the file downloaded from Modrinth, sha512 `f7aba38b5c016c49...` |
| 0.9.0 | built at 3d10163, sha256 `0ba3d71a...`, recorded in `raw/JARS.txt` before the first run |

## What is new: a prediction about the fixes

**Q6, reported against a prediction, no threshold.** The ladder's zombies search off the server thread
almost entirely, and the fixes remove a cost paid mostly by searches on the server thread. Predicted: the
saving at each of 2500, 5000 and 10000 is within 3 points of 13b's (9.6, 8.2, 7.1%). A saving more
than 3 points larger would mean the ladder had more synchronous search in it than we
thought, and that is reported, not claimed as a gain, because 13b and this attempt ran on different days
and are not one experiment.

## What this cannot say

One arena, one mob type, a dense population, two rounds on a machine carrying a foreign process. It says
whether the jar that will ship behaves like the one the page was measured on.
