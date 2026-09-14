# The ThreadLocal fix, measured in the client: written before measuring it

Committed 2026-09-14, before any run of this comparison.

## What was found

In the client village scenario, with PathWeaver 0.9.0, 5.7 s of 20.6 s of synchronous search on the
integrated server thread was `ThreadLocalMap.getEntryAfterMiss`, and villagers' point-of-interest
searches cost about 1.9 and 2.6 ms per call against 1.3 and 1.5 ms with no PathWeaver (one run each, with
Enhanced Cats in and out). The per-node worker checks were ThreadLocal reads. Commit a3bd6dd replaces them
with fields on a worker thread type.

## Arms and order

Enhanced Cats is not installed (removed from the pack on 2026-09-14). The player's config.

| arm | jar |
|---|---|
| none | no PathWeaver |
| v090 | release 0.9.0, sha256 63d7d123... |
| cand | built at a3bd6dd, sha256 recorded in each run's jars.txt |

R1 none, v090, cand; R2 cand, v090, none; R3 v090, none, cand.

## Predictions, and what falsifies each

Measures from the spark profile of each run, server thread, the window from the one marker to the end
marker. Judged on the median of the three rounds per arm.

**T1, the instrument.** In every v090 run, ThreadLocal internals (`ThreadLocalMap.getEntryAfterMiss` plus
`ThreadLocalMap.getEntry`, self time) inside synchronous `findPath` are at least 10% of that search time.
If not, the defect did not reproduce and T2-T3 say nothing.

**T2, the defect is gone.** In every cand run, the same ThreadLocal self time inside synchronous
`findPath` is under 2% of that search time.

**T3, synchronous search costs what it costs without PathWeaver.** ThreadLocal-free share: synchronous
`findPath` self time outside PathWeaver and ThreadLocal frames, per unit of tick. Stated as the simpler
measure it predicts: the share of tick spent in synchronous `findPath` for villager POI callers
(`FindPointOfInterest` tasks and `NearestBedSensor`) is, for cand, within 3 percentage points of none's
median, while v090's median is more than 3 points above none's.

**T4, the tick, reported not predicted.** Median integrated-server tick mean in the one and horde phases
for all three arms. No threshold: three rounds on a noisy machine may not separate them, and that is said.

## What this cannot say

One world, one machine carrying a foreign process, three rounds. It says whether this defect is gone and
whether villagers' searches are back to vanilla cost, not how much any other server gains.
