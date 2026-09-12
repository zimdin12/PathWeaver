# Did the final 0.9.0 regress? Written before running it

Committed 2026-09-13, before any run of this check.

## Why this exists

The project page says PathWeaver saves 6-10% of tick time and that 0.9.0 does not regress against
0.8.0. Both figures were measured on a 0.9.0 built at `68dff75`, before distance LOD and before Cloth
Config became optional. The jar tagged `v0.9.0` (`7b590fa5`) has never been through the ladder. LOD
ships off, so it should change nothing here, but "should" is what this checks.

## Arms, identified by hash

| arm | jar | sha256 |
|---|---|---|
| off | every pathweaver jar held out of `mods/` | |
| 0.8.0 | the file downloaded from Modrinth, sha512 `f7aba38b5c016c49...` verified against the API | `033b0b811f7b4e25...` |
| 0.9.0 | `pathweaver-0.9.0+26.1.2.jar` built at the `v0.9.0` tag | `7b590fa5f8e36be0...` |

The 0.8.0 arm is the downloaded file on purpose. The locally built 0.8.0 jar differs from the published
one byte for byte (sha512 `599288d2...` against `f7aba38b...`), most likely zip timestamps from a
rebuild, which has not been checked. The earlier campaign's method file says its 0.8.0 arm was "as
published"; that cannot now be verified.

## Method

`bench/ladder.sh`, unchanged except for the shared hold library, with the settings recorded in
`docs/evidence/perf-2026-09/METHOD.md`: rungs 100 500 1000 2500 5000 10000, `BUDGET=600 RESERVE=90`,
spark health's 10 s window per rung, median reported.

Two rounds, the second reversed: R1 off, 0.8.0, 0.9.0; R2 0.9.0, 0.8.0, off.

## Predictions, and what falsifies each

**Q1, the instrument control.** In every run the median rises from 1000 to 2500 to 5000 to 10000. A run
where it does not is not reading the server's load and is void.

**Q2, no regression.** 0.9.0 is a regression only if its median is more than 3% worse than 0.8.0's at the
same rung, in the same direction, in **both** rounds, at any of 2500, 5000 or 10000. Predicted: no rung
meets that. 3% is the resolution the earlier campaign stated.

**Q3, the page's figure.** Saving = (off - 0.9.0) / off on the mean of the two rounds' medians. The page's
6-10% is reproduced if the saving lies between 4% and 12% at each of 2500, 5000 and 10000. If it falls
below 4% at any of them the page overstates and gets corrected; if it is above 12% at all three it
understates.

**Q4, round agreement.** If the same arm's two rounds differ by more than 5% at a busy rung, conclusions
about that rung are withheld rather than averaged away. The earlier campaign discarded a round for
exactly this and never established why.

## What this cannot say

One arena, one mob type, a dense population, two rounds on one machine. It says whether the tagged jar
behaves like the one the page was measured on, not what anyone else's server will see.
