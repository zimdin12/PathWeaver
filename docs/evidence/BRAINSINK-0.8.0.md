# brainSinkAsync, measured

The first measurement of this feature that exists. Everything published before 0.8.0 measures the
master switch on a population that contained no brain-mob pathfinding at all: one `Brain` frame
totalling 4 ms across both retained profiles, and zero time in `MoveToTargetSink`, which is the only
route `brainSinkAsync` gates. See `README.md` in this directory.

Taken 2026-08-31 with `bench/server-bench.sh` on the 222-jar dedicated pack, Minecraft 26.1.2,
PathWeaver 0.8.0, `compatibilityTier=UNSAFE`. Three pairs, alternating arms, 60-second windows, the
only variable being `brainSinkAsync`.

## Result

| | `brainSinkAsync=true` | `false` | change |
|---|---|---|---|
| **Pathfinding on the server thread** | **139 ms** | **325 ms** | **-57%** |
| Pathfinding on worker threads | 227 ms | 0 ms | moved here |
| Total pathfinding, all threads | 365 ms | 325 ms | **+12%** |
| Pathfinding as share of `tickServer` | 2.09% | 4.76% | -56% |
| MSPT | 5.13 ms | 5.25 ms | -2.2% |

Per run, so the spread is visible rather than hidden behind a mean:

```
server-thread pathfinding   ON  [120, 148, 148] ms
                            OFF [288, 288, 400] ms      ranges do not overlap
MSPT                        ON  [5.35, 5.11, 4.92] ms
                            OFF [5.58, 4.78, 5.38] ms   ranges overlap
```

## What it supports, and what it does not

**The work moves off the tick, repeatably.** Every ON run is below every OFF run on server-thread
pathfinding, across three pairs. That is the claim the feature makes.

**It costs more CPU in total, not less.** 365 ms across all threads with the sink on against 325 ms
with it off: about 12% more work done, to get 57% of it off the tick. That is the snapshot, hand-off
and install overhead, and it is the honest price of the design. No previously published figure could
have shown this, because every earlier profile sampled the server thread alone — the one thread
guaranteed to look better when the mod succeeds.

**It does not show a TPS gain, and this run cannot.** MSPT ranges overlap and the server sat at
~5.1 ms against a 50 ms budget. At that load there is nothing to rescue. The supportable claim is
headroom: the tick has more room, which matters on a server that is actually near its budget. Anyone
wanting a throughput claim needs a scenario that pushes MSPT past 50 ms first.

## Controls

Every run had to survive these, and seven earlier runs did not:

- **Population stable across the window**, counted with the same command on both sides: 112/112,
  105/105, 104/104 on; 114/114, 98/98, 107/107 off. Absolute survival is not the control, because the
  pack ships ServerCore, which trims entities during the settle in both arms alike. What invalidates
  a run is the population changing while it is being measured.
- **The setting demonstrably took effect, in both directions.** ON dispatched 2388, 948 and 916
  searches inside the window, of which 99.6% were the brain-sink route. OFF dispatched 0. A control
  that only demanded dispatches would have passed a silently-ignored setting as a clean result.
- **Every thread profiled** (`--thread * --not-combined`), so the workers receiving the moved work are
  in the sample. Without that the measurement cannot tell moving work from removing it.
- **The parser reproduces a known answer**, 12.38% and 4.92% on the retained 0.6.1 pair.

## The seven discarded runs

Recorded because each looked healthy and each was caught by a control rather than by luck, and
because the list is the argument for having them:

1. Server thread parked 48.5 s of 54.8 s. `pause-when-empty-seconds=60`, and the settle time was 60.
2. 220 mobs summoned into open air at y=-60; all fell to y=-252 and died. `level-type=flat` is not
   flat on a pack shipping Tectonic and Terralith.
3. Population alive but not ticking; same pause cause, not yet found.
4. Mobs summoned at y=-60, inside the stone floor filled at y=-60, and ejected downward.
5. Villagers walked off an open slab: 117 of 180 left.
6. 13 villagers swam in lava and 7 burned. Terrain keeps supplying it into a cleared box near
   bedrock, which is why the arena is now at y=200 in empty sky.
7. A three-pair campaign died after its first run, exited 0, and left a half-finished set of profiles
   that looked complete. `kill "${SERVERPID:-0}"` against an unassigned variable is `kill 0`, which
   signals the whole process group, including the caller.

## Reproducing

```
bash bench/run-pairs.sh 1 2 3
python bench/spark_summary.py "on=bench/out/brainsink-on-1.sparkprofile" \
                              "off=bench/out/brainsink-off-1.sparkprofile"
```

`brainsink-on-0.8.0.sparkprofile` and `brainsink-off-0.8.0.sparkprofile` in this directory are the
first pair, retained. Saved locally, never uploaded.
