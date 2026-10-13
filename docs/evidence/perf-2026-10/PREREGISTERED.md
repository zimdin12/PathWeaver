# Remeasurement with a target that stays, preregistered 2026-10-13 before any run

Why: the 2026-09-12 ladder (docs/PERFORMANCE-2026-09.md on master) ran with no player. e4mc removed
Carpet's fake player about a second after it joined, so the zombies wandered instead of chasing, and
the published 0.9.0 page describes that load as "zombies chasing a player". This repeats the
measurement with the corrected ladder on this branch: e4mc held out, 26.1 gamerule names checked, and
a run void unless the fake player joined, survived, and every teleport landed.

- Server: the 219-mod dedicated server, MC 26.1.2, unchanged except e4mc and resident pathfinding jars held out per run.
- Arms: `off` (no PathWeaver jar at all) and the published 0.9.0+26.1.2 jar, sha256 47e24d24.
- Rungs: 100 500 1000 2500 5000 10000 zombies. Two bursts per rung, 11 s apart.
- Order: off, on, on, off, off, on. Three runs per arm. BUDGET=570 RESERVE=90, so each run is under ten minutes.
- Reported number: median tick (spark health, 10 s window) per rung, median of the three runs per arm.

What would show the published "5 to 11%" claim wrong for this load:
- at 1000, 2500, 5000 and 10000 zombies, the on arm's median tick is less than 3% below the off arm's; or
- at those rungs, the three runs of each arm overlap (the slowest on run is slower than the fastest off run),
  in which case no difference is established, whatever the medians say.

A void run is reported and not replaced inside this series.
