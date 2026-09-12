# Distance LOD: what it removes, written down before measuring it

Written and committed 2026-09-12, before any run of this campaign. The results file will be judged
against this one, and this one does not get edited afterwards. Anything learned that changes the design
becomes a new campaign with its own preregistration.

## The question

`lodEnabled` claims to redo distant mobs' path searches less often. It ships off, and the project page
says there is no benchmark number for it. This measures whether it removes searches, how many relative
to vanilla, and whether that reaches tick time in the configuration people actually run.

## Why the ordinary ladder cannot answer it

The ladder loads the server by teleporting the player, which gives every mob a new destination. New
destinations go `moveTo -> createPath` and never reach `PathNavigation.recomputePath`, the only method
LOD touches. A ladder run would show no difference and be right about the wrong workload.

## The mechanism, from the 26.1.2 bytecode

- `recomputePath` has exactly two callers in the game: `ServerLevel.sendBlockUpdated`, and
  `PathNavigation.tick` retrying a deferred call. Scanned across 10,682 classes in both server and client
  jars.
- `sendBlockUpdated` only consults navigations when a block's **collision shape** changes.
- `recomputePath` searches only when `gameTime - timeLastRecompute > 20`, so at most once per 21 ticks per
  navigation.
- With `lodIntervalTicks = 40`, a navigation beyond 64 blocks searches at most once per 40 ticks.

So under demand that never lets up, searches per navigation fall from 1/21 to 1/40 of ticks: a ratio of
**0.525**. Demand that comes and goes lets the first search after a quiet period through in both arms,
which pushes the ratio towards 1.

## The scenario

Script: `bench/lod-bench.sh`. The live 221-mod dedicated server, one boot per run, jar under test
identified by sha256 before boot.

- A fake player at (0, 201, 0). A walled pen at x 70-110, z -20..20, so every mob is at least 70 blocks
  from the player (past the 64-block LOD distance) and at most 112 (inside simulation distance).
- 400 persistent zombies, `follow_range` 16, so they wander rather than chase and `moveTo` traffic stays
  low.
- **Churn**: two carpet strips across the pen, each changing every 2 ticks, driven by a command-block
  chain. Carpet, because it has a collision box, so it reaches the navigation loop, and mobs walk over it.
  With churn **off** the identical chain runs, and its fills replace a block that is not there, so
  command cost and region scans match and only block changes differ.
- A 60-second `spark profiler --thread *` window after 20 seconds of settling.

## Arms

Instrument A, `enabled=false`: every recompute search runs on the server thread, where the call tree
attributes it to `recomputePath`. This is the instrument for how much search LOD removes.

| arm | churn | lod |
|---|---|---|
| A1 | on | off |
| A2 | on | on |
| A3 | off | off |
| A4 | off | on |

Instrument B, `enabled=true`, the shipped configuration: recompute searches run on workers.

| arm | churn | lod |
|---|---|---|
| B1 | on | off |
| B2 | on | on |

Two rounds each, order reversed in the second: R1 A1 A2 A3 A4 B1 B2, R2 A2 A1 A4 A3 B2 B1. An earlier
campaign ran a fixed arm order and could not separate arm from position.

## Measures

Read by `bench/frame_totals.py`, which sums every outermost occurrence of a frame, and was controlled
before this campaign on preserved profiles.

- **M1** server-thread ms under `PathNavigation.recomputePath`
- **M2** server-thread ms under `ServerLevel.sendBlockUpdated` (the navigation loop, the same in both arms)
- **M3** non-server-thread ms under `PathFinder.findPath` (instrument B: the searches moved to workers)
- **M4** `dispatched` delta across the window, from `/pathweaver status` (instrument B)
- **M5** spark health tick durations, and server-thread ms under `tickServer` as the denominator

## Predictions, and what falsifies each

**P1, positive control.** The scenario exercises the mechanism: in both rounds, M1(A1) is at least 200 ms
and at least 5x M1(A3). *If this fails, the whole campaign is void*, because a scenario that does not
reach `recomputePath` cannot say anything about LOD, and the design is wrong rather than the feature.

**P2, negative control.** With no block changes LOD changes nothing: in both rounds M1(A3) and M1(A4)
differ by less than 100 ms, or `recomputePath` is ABSENT in both. A difference here means the throttle
is acting on something other than terrain changes.

**P3, the treatment.** In both rounds, M1(A2) / M1(A1) lies between **0.45 and 0.85**. Below 0.45
removes more than the mechanism allows, which would mean something besides the refresh floor is being
throttled. Above 0.85 means LOD is ineffective even in the case built to favour it.

**P4, the shipped configuration.** In both rounds, B2 dispatches fewer searches than B1 (M4) and spends
less worker time in `findPath` (M3). Tick durations are predicted *not* to separate: with the search
already off the server thread, the difference between B1 and B2 on the tick should be smaller than the
difference between two rounds of the same arm. If B2 is clearly faster on the tick, that prediction was
wrong and gets said so.

## What this cannot say

- It is built to favour LOD: continuous, collision-changing churn inside a distant herd. A world whose
  terrain is not changing around distant mobs gets nothing, because `recomputePath` is never called
  there. That is stated alongside any number.
- One machine, one pack, two rounds. It establishes a direction and a size under stated conditions, not
  a percentage anyone else should expect.
- It does not measure the behavioural cost, a distant mob walking an out-of-date route for longer.

## What voids an individual run

Enforced by the script: the jar in `mods/` not matching the jar under test by hash; the zombie count
moving between the start and end of the window; the command chain not ticking through the window; the
carpet strip never toggling with churn on, or toggling with churn off; no profile saved. A voided run is
kept, marked, and repeated under a new label, never deleted.
