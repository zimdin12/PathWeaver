# Both hot-path fixes, measured in the client: written before measuring them

Committed 2026-09-14, before any run of this comparison.

## What came before

The ThreadLocal comparison (`THREADLOCAL-PREREGISTRATION.md`, series `t1`) judged:

- T1 FAIL. The instrument counted only `ThreadLocalMap` frames. In two of three 0.9.0 runs the JIT had
  inlined the read into its callers, so the cost was there but under other frame names.
- T2 PASS. With commit a3bd6dd no ThreadLocal frame appears inside synchronous search.
- T3 PASS. Villager point-of-interest search as a share of tick: none 21.1, 0.9.0 28.1, candidate 24.0
  points (medians).

Read afterwards, and not preregistered (`bench/client/lookup_cost.py`): the self time of the lookup in
all three places it can land (ThreadLocal frames, Fabric's `LandPathTypeRegistry.getPathTypeProvider`,
Fabric's `PathfindingContext` node-type handler) inside synchronous search came to 10.7, 28.6 and 11.4%
for 0.9.0 and 6.8, 1.4 and 1.2% for a3bd6dd. The candidate's residual sat in `getPathTypeProvider`,
where PathWeaver still had a cancellable inject that allocates on every call. Commit 7f05b0d replaces it
with a redirect on the one `Map.get` inside it.

That measure was chosen after seeing the data. This comparison runs it on new runs, with its threshold
written first.

## Arms and order

Enhanced Cats is not installed. The player's config (`config/pathweaver.json.player`).

| arm | jar |
|---|---|
| none | no PathWeaver |
| v090 | release 0.9.0, sha256 63d7d123... |
| cand | built at 3d10163 (both fixes), sha256 0ba3d71a... |

R1 none, v090, cand; R2 cand, v090, none; R3 v090, none, cand. Then, reported only, one round with
Enhanced Cats added back: e-v090, e-cand. `bench/client/hotpath-campaign.sh`, judged by
`bench/client/hotpath_report.py`.

## Predictions, and what falsifies each

Measures from the spark profile of each run, server thread, from the one marker to the end marker.

**H0, the instrument.** In at least two of three v090 runs, lookup cost (all three forms, self time)
inside synchronous `findPath` is at least 8% of that search time. If not, the defect did not reproduce
and H1 says nothing.

**H1, the per-node cost is gone.** In every cand run, lookup cost is under 2% of synchronous search.

**H2, villagers' searches cost what they cost without PathWeaver.** Share of tick in synchronous
`findPath` under point-of-interest callers (`FindPointOfInterest` tasks, `NearestBedSensor`): cand's
median within 3 points of none's. Decidable only if v090's median is more than 3 points above none's;
otherwise the scenario did not show the cost and H2 is undecidable.

**H3, no ThreadLocal came back.** In every cand run, `ThreadLocalMap` self time inside synchronous
`findPath` is under 2% of it.

**H4, reported not predicted.** Per arm, median over rounds of the integrated-server tick mean and the
share of frames over 50 ms, in the one and horde phases. Frame figures are weak: an unfocused game caps
itself at 30 FPS, and nothing here holds focus.

**H5, reported not predicted.** With Enhanced Cats back, one round: the same measures for e-v090 and
e-cand, and the share of path requests made on the render thread.

**Q, machine.** `bench/machine_load.py`; a run whose background moved more than 8 points is flagged.

## What this cannot say

One world, one machine carrying a foreign process that holds about 8 of 32 logical cores, three rounds.
It says whether this cost is gone from synchronous search. It does not say how much any other server
gains, and the lookup measure depends on where the JIT put the code, which is why it counts all three
places.

## The judge, controlled before any run

`hotpath_report.py` pointed at the `t1` series (the same arms, with a3bd6dd as the candidate) returns
H0 PASS (10.7, 28.6, 11.4%) and H1 FAIL (6.8, 1.4, 1.2%): it can say the defect is present, and it can
say a candidate has not removed it.
